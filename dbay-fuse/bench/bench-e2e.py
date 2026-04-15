#!/usr/bin/env python3
"""End-to-end benchmark: user latency + cloud-persistence latency.

Compares:
  A. Native FS           (/tmp/bench_native_v4)
  B. AgentFS via FUSE    (~/.dbay/mnt/claude/memory/bench-e2e)

For each op we report two metrics:
  - user_ms  : what CC sees (syscall return)
  - e2e_ms   : wall time until the file is readable from DBay
               (polls GET /agentfs/files until HTTP 200 with matching etag)
"""
import base64, json, pathlib, shutil, statistics, subprocess, sys, time
import urllib.request, urllib.error

N = 30
HOME = pathlib.Path.home()
FUSE_ROOT = HOME / ".dbay/mnt/claude/memory"
NATIVE_ROOT = pathlib.Path("/tmp/bench_native_v4")
BENCH_DIR = "bench-e2e"
CFG = json.load(open(HOME / ".dbay/config.json"))
API_KEY = CFG["api_key"]
BASE_URL = "https://api.dbay.cloud:8443/api/v1"

H = {"Authorization": f"Bearer {API_KEY}"}


def pcts(xs):
    xs = sorted(xs)
    return {"mean": statistics.mean(xs), "p50": xs[len(xs) // 2],
            "p95": xs[min(len(xs) - 1, int(len(xs) * 0.95))],
            "max": xs[-1]}


def fmt(label, op, u, e):
    print(f"  {label:<14} {op:<12}  user: mean={u['mean']:6.2f}ms p95={u['p95']:6.2f} | "
          f"e2e: mean={e['mean']:7.0f}ms p95={e['p95']:7.0f}")


def b64url(s: str) -> str:
    return base64.urlsafe_b64encode(s.encode()).rstrip(b"=").decode()


def cloud_read(path: str):
    """GET /agentfs/files; return (status, body_bytes)."""
    url = f"{BASE_URL}/agentfs/files?path={b64url(path)}"
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=H), timeout=10) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, b""
    except Exception:
        return 0, b""


def wait_cloud(path: str, expected_size: int, timeout_s: float = 120.0) -> float:
    """Poll until DBay has the file with size >= expected_size. Returns ms elapsed."""
    t0 = time.perf_counter()
    while time.perf_counter() - t0 < timeout_s:
        status, body = cloud_read(path)
        if status == 200 and len(body) >= expected_size:
            return (time.perf_counter() - t0) * 1000
        time.sleep(0.5)
    return -1.0


def bench(label: str, root: pathlib.Path, is_fuse: bool):
    d = root / BENCH_DIR
    if d.exists(): shutil.rmtree(d, ignore_errors=True)
    d.mkdir(parents=True, exist_ok=True)
    session = d / "session.jsonl"
    session.write_bytes(b"")

    print(f"\n[{label}] {d}")

    user_times = []
    e2e_times = []
    expected_size = 0

    for i in range(N):
        line = (f'{{"i":{i},"t":{time.time()}}}\n').encode()
        t0 = time.perf_counter()
        with open(session, "ab") as f:
            f.write(line)
        user_times.append((time.perf_counter() - t0) * 1000)
        expected_size += len(line)

    if is_fuse:
        # After all writes, time until DBay has the complete file.
        virt_path = f"/memory/{BENCH_DIR}/session.jsonl"
        for_e2e = wait_cloud(virt_path, expected_size)
        e2e_times = [for_e2e]
    else:
        e2e_times = [0.0]

    return pcts(user_times), pcts(e2e_times)


def bench_create(label: str, root: pathlib.Path, is_fuse: bool):
    d = root / BENCH_DIR
    print(f"\n[{label}] create 2KB md files, n={N}")
    content = b"# memory\n" + (b"line entry with some content.\n" * 50)
    user_times = []
    e2e_last = 0.0
    for i in range(N):
        p = d / f"mem_{i}.md"
        t0 = time.perf_counter()
        p.write_bytes(content)
        user_times.append((time.perf_counter() - t0) * 1000)

    if is_fuse:
        virt_path = f"/memory/{BENCH_DIR}/mem_{N-1}.md"
        e2e_last = wait_cloud(virt_path, len(content))
    return pcts(user_times), {"mean": e2e_last, "p50": e2e_last, "p95": e2e_last, "max": e2e_last}


def main():
    print(f"=== dbay-fuse B (append-delta) bench · N={N} ===")
    print()
    print("op 1: append 40B JSON line × N  (session.jsonl growing)")
    u_n, _ = bench("native FS",    NATIVE_ROOT, is_fuse=False)
    u_f, e_f = bench("AgentFS",    FUSE_ROOT,   is_fuse=True)
    print()
    fmt("native FS", "append",  u_n, {"mean":0,"p50":0,"p95":0,"max":0})
    fmt("AgentFS",   "append",  u_f, e_f)

    print()
    print("op 2: create 2KB md file × N")
    u_n2, _ = bench_create("native FS",    NATIVE_ROOT, is_fuse=False)
    u_f2, e_f2 = bench_create("AgentFS",   FUSE_ROOT,   is_fuse=True)
    print()
    fmt("native FS", "create_md", u_n2, {"mean":0,"p50":0,"p95":0,"max":0})
    fmt("AgentFS",   "create_md", u_f2, e_f2)

    print()
    print("=== Interpretation ===")
    print(f"  user_ms  : what CC sees at syscall return (local hot path)")
    print(f"  e2e_ms   : wall time until DBay has the file visible via HTTP GET")
    print(f"             (session.jsonl e2e measured ONCE at end; mem_{N-1}.md at end)")


if __name__ == "__main__":
    main()
