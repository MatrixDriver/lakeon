#!/usr/bin/env python3
"""Micro-benchmark for three backends:
  1. SQLite + DBay FUSE (async uplink)   — path: ~/.dbay/mnt/claude
  2. Remote only, no cache               — direct HTTPS POST to memory.ingest
  3. Native filesystem                    — path: /tmp/bench_native

Workload per scenario: N iterations of each op
  • append  — append a JSON line to a jsonl file   (dominant during CC use)
  • read    — read a small markdown file
  • create  — create a fresh md file in a memory dir

For scenario 2 (remote-only) we can't really "append" via ingest (there's no
per-append semantic), so we emulate append = ingest(content) and read = recall(query).
"""

import json
import os
import pathlib
import statistics
import sys
import time
import urllib.request
import urllib.error

N = 50
API_KEY = os.environ.get("DBAY_API_KEY") or json.load(open(pathlib.Path.home() / ".dbay/config.json")).get("api_key")
BASE_ID = os.environ.get("DBAY_MEMORY_BASE") or json.load(open(pathlib.Path.home() / ".dbay/config.json")).get("memory_base")
BASE_URL = "https://api.dbay.cloud:8443/api/v1"


def percentiles(xs):
    xs = sorted(xs)
    return {
        "n":   len(xs),
        "min": xs[0],
        "p50": xs[len(xs) // 2],
        "p95": xs[int(len(xs) * 0.95)],
        "max": xs[-1],
        "mean": statistics.mean(xs),
    }


def time_ms(fn, n=N):
    times = []
    for i in range(n):
        t0 = time.perf_counter()
        fn(i)
        times.append((time.perf_counter() - t0) * 1000)
    return percentiles(times)


def fmt(name, op, p):
    print(f"  {name:<30} {op:<8} n={p['n']:<4}  "
          f"mean={p['mean']:7.2f}ms  p50={p['p50']:7.2f}  p95={p['p95']:7.2f}  max={p['max']:7.2f}")


def prep_dir(path):
    p = pathlib.Path(path)
    p.mkdir(parents=True, exist_ok=True)
    (p / "memory").mkdir(exist_ok=True)
    (p / "projects" / "bench").mkdir(parents=True, exist_ok=True)
    # seed a CLAUDE.md for read test
    (p / "CLAUDE.md").write_text("# bench seed\n" * 20)
    # clean old session file
    (p / "projects" / "bench" / "session.jsonl").write_text("")
    return p


def bench_fs(label, root_path):
    p = prep_dir(root_path)
    session = p / "projects" / "bench" / "session.jsonl"
    claude_md = p / "CLAUDE.md"
    mem_dir = p / "memory"
    print(f"\n[{label}] root={p}")

    def do_append(i):
        with open(session, "a") as f:
            f.write(f'{{"i": {i}, "t": {time.time()}}}\n')

    def do_read(_i):
        claude_md.read_text()

    def do_create(i):
        (mem_dir / f"bench_{i}.md").write_text(f"content {i}")

    fmt(label, "append", time_ms(do_append))
    fmt(label, "read",   time_ms(do_read))
    fmt(label, "create", time_ms(do_create))


def bench_remote_only(label):
    """Scenario 2: every op is a synchronous HTTPS call to DBay API."""
    print(f"\n[{label}] base_id={BASE_ID}")
    if not API_KEY or not BASE_ID:
        print("  skip: no API_KEY / BASE_ID")
        return

    headers = {
        "Authorization": f"Bearer {API_KEY}",
        "Content-Type": "application/json",
    }

    def post(path, body):
        req = urllib.request.Request(
            f"{BASE_URL}{path}",
            data=json.dumps(body).encode(),
            headers=headers,
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                r.read()
        except urllib.error.HTTPError as e:
            sys.stderr.write(f"    HTTP {e.code}: {e.read()[:200]!r}\n")
            raise

    def do_append(i):
        # closest remote analogue of appending a session event
        post(f"/memory/bases/{BASE_ID}/ingest", {
            "content": f"bench append {i}",
            "role": "user",
            "memory_type": "fact",
            "importance": 0.1,
            "metadata": {"bench": True, "op": "append"},
        })

    def do_read(i):
        # closest remote analogue: recall one memory
        post(f"/memory/bases/{BASE_ID}/recall", {
            "query": "bench",
            "top_k": 1,
        })

    def do_create(i):
        post(f"/memory/bases/{BASE_ID}/ingest", {
            "content": f"bench create {i}",
            "role": "user",
            "memory_type": "fact",
            "importance": 0.1,
            "metadata": {"bench": True, "op": "create"},
        })

    # smaller N for remote to avoid hammering
    n_remote = min(N, 20)
    fmt(label, "append", time_ms(do_append, n=n_remote))
    fmt(label, "read",   time_ms(do_read,   n=n_remote))
    fmt(label, "create", time_ms(do_create, n=n_remote))


def main():
    print("=" * 78)
    print(f"dbay-fuse benchmark · N={N} iters/op")
    print("=" * 78)

    # 3. native FS (baseline)
    bench_fs("native FS (/tmp)", "/tmp/bench_native")

    # 1. SQLite + DBay FUSE (async uplink)
    mount = pathlib.Path.home() / ".dbay/mnt/claude"
    if mount.exists() and (mount / "CLAUDE.md").exists():
        # DO NOT prep_dir() here — that would wipe real data.
        # Instead use a scratch subdir under /projects/bench (safe).
        session = mount / "projects" / "bench" / "session.jsonl"
        session.parent.mkdir(parents=True, exist_ok=True)
        session.write_text("")
        claude_md = mount / "CLAUDE.md"
        mem_scratch = mount / "projects" / "bench" / "mem"
        mem_scratch.mkdir(exist_ok=True)
        print(f"\n[SQLite+DBay FUSE] root={mount}")
        fmt("SQLite+DBay FUSE", "append", time_ms(
            lambda i: open(session, "a").write(f'{{"i":{i}}}\n')))
        fmt("SQLite+DBay FUSE", "read",   time_ms(
            lambda _i: claude_md.read_text()))
        fmt("SQLite+DBay FUSE", "create", time_ms(
            lambda i: (mem_scratch / f"b_{i}.md").write_text(f"c{i}")))
    else:
        print("\n[SQLite+DBay FUSE] skip — mount not found; start daemon first.")

    # 2. remote only (no cache)
    bench_remote_only("remote only (sync HTTP)")

    print()


if __name__ == "__main__":
    main()
