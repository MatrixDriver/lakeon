#!/usr/bin/env python3
"""CC-flavored workload on LakebaseFS (via FUSE) vs plain local FS.

Measures USER-VISIBLE latency (what CC sees when it writes a file).
Uplink to cloud is async — not on this hot path, so the FUSE column
reflects passthrough + outbox-append overhead only.

Workload per backend (N iters each):
  append_small  : append 200B JSON line to a session-like jsonl
  create_md     : write a 2KB memory_x_N.md file
  read_back     : cat the CLAUDE.md-like file (4KB)
  rewrite       : full overwrite of a 4KB CLAUDE.md-like file

After the run on LakebaseFS, we wait for outbox to drain and report how
long that took — useful for "when does it actually hit the cloud".
"""
import json
import pathlib
import shutil
import statistics
import subprocess
import sys
import time

N = 100

HOME = pathlib.Path.home()
FUSE_ROOT = HOME / ".dbay/mnt/claude"
NATIVE_ROOT = pathlib.Path("/tmp/bench_native_v3")
BENCH_DIR = "bench-fuse-vs-local"  # subdir we write into (safe, tagged)

LINE = (b'{"role":"user","content":"hello ' + b'x' * 150 + b'"}\n')
MD_2K = b"# memory\n" + (b"line entry with some content.\n" * 50)
MD_4K = b"# CLAUDE.md-like\n" + (b"- instruction line " + b"a" * 50 + b"\n") * 50


def percentiles(xs):
    xs = sorted(xs)
    return {
        "min": xs[0],
        "p50": xs[len(xs) // 2],
        "p95": xs[int(len(xs) * 0.95)],
        "max": xs[-1],
        "mean": statistics.mean(xs),
    }


def ms_each(fn, n=N):
    times = []
    for i in range(n):
        t0 = time.perf_counter()
        fn(i)
        times.append((time.perf_counter() - t0) * 1000)
    return percentiles(times)


def fmt(backend, op, p):
    print(f"  {backend:<12} {op:<14}  mean={p['mean']:7.3f}ms  "
          f"p50={p['p50']:7.3f}  p95={p['p95']:7.3f}  max={p['max']:7.3f}")


def prep(root: pathlib.Path):
    d = root / BENCH_DIR
    if d.exists():
        shutil.rmtree(d, ignore_errors=True)
    d.mkdir(parents=True, exist_ok=True)
    (d / "CLAUDE.md").write_bytes(MD_4K)
    (d / "session.jsonl").write_bytes(b"")
    return d


def bench(backend: str, root: pathlib.Path):
    d = prep(root)
    print(f"\n[{backend}] root={d}")

    session = d / "session.jsonl"
    claude_md = d / "CLAUDE.md"

    def do_append(i):
        with open(session, "ab") as f:
            f.write(LINE)

    def do_create(i):
        (d / f"memory_{i}.md").write_bytes(MD_2K)

    def do_read(_i):
        claude_md.read_bytes()

    def do_rewrite(_i):
        claude_md.write_bytes(MD_4K)

    fmt(backend, "append_small",  ms_each(do_append))
    fmt(backend, "create_md",     ms_each(do_create))
    fmt(backend, "read_back",     ms_each(do_read))
    fmt(backend, "rewrite",       ms_each(do_rewrite))
    return d


def check_outbox():
    try:
        out = subprocess.run(
            [str(HOME / "code/lakeon/dbay-fuse/target/release/dbay-fuse"),
             "outbox-status", "--agent", "claude"],
            capture_output=True, text=True, timeout=5
        )
        for line in out.stdout.splitlines():
            if "pending count" in line:
                return int(line.split(":")[1].strip())
    except Exception as e:
        print(f"outbox-status err: {e}", file=sys.stderr)
    return -1


def measure_uplink_drain():
    print("\n[uplink drain] watching pending count...")
    start = time.perf_counter()
    last = check_outbox()
    print(f"  t=+0.0s  pending={last}")
    while last > 0:
        time.sleep(1.0)
        cur = check_outbox()
        if cur != last:
            print(f"  t=+{time.perf_counter()-start:5.1f}s  pending={cur}")
            last = cur
        if time.perf_counter() - start > 60:
            print("  (timeout 60s)")
            break
    print(f"  drained in ~{time.perf_counter()-start:.1f}s")


def main():
    print(f"dbay-fuse  native-vs-LakebaseFS  N={N}")

    if not NATIVE_ROOT.parent.exists():
        NATIVE_ROOT.parent.mkdir(parents=True)
    bench("native FS", NATIVE_ROOT)

    if not (FUSE_ROOT / "memory").exists():
        print("\n[skipped] FUSE mount not ready — is daemon running?", file=sys.stderr)
        sys.exit(1)
    # Put our bench dir under memory/ so it's on the FUSE path
    bench("LakebaseFS FUSE", FUSE_ROOT / "memory")

    # Now watch the outbox empty (shows async upload cost)
    measure_uplink_drain()


if __name__ == "__main__":
    main()
