#!/usr/bin/env python3
"""Full POSIX bench with cloud e2e timings.

For each op:
  user_ms : per-op syscall return latency (mean of N)
  e2e_ms  : wall-clock from "first op started" to "last op visible in DBay"
            divided by N → "average op-to-cloud latency" amortized over a
            burst of N ops.

For read/stat/readdir, e2e is N/A (no server roundtrip).
"""
import base64, json, os, pathlib, shutil, statistics, sys, time
import urllib.request, urllib.error

N = 30
HOME = pathlib.Path.home()
FUSE_BENCH = HOME / ".dbay/mnt/claude/memory/bench-full-e2e"
NATIVE_BENCH = pathlib.Path("/tmp/bench_full_e2e_native")
CFG = json.load(open(HOME / ".dbay/config.json"))
API_KEY = CFG["api_key"]
BASE_URL = "https://api.dbay.cloud:8443/api/v1"
H = {"Authorization": f"Bearer {API_KEY}"}

SMALL = b"small content " * 14   # ~196B
LARGE = b"x" * 65536              # 64KB


def b64url(s): return base64.urlsafe_b64encode(s.encode()).rstrip(b"=").decode()

def cloud_head(path):
    """GET /lbfs/files/head — returns dict or None if 404."""
    url = f"{BASE_URL}/lbfs/files/head?path={b64url(path)}"
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=H), timeout=10) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        if e.code == 404: return None
        return None
    except Exception:
        return None

def cloud_list(prefix):
    url = f"{BASE_URL}/lbfs/list?prefix={b64url(prefix)}&recursive=true"
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=H), timeout=10) as r:
            return json.loads(r.read()).get("entries", [])
    except Exception:
        return []

def cloud_read(path):
    url = f"{BASE_URL}/lbfs/files?path={b64url(path)}"
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers=H), timeout=10) as r:
            return r.read()
    except Exception:
        return None

def wait_until(check_fn, timeout=120):
    """Poll check_fn() every 0.3s until truthy or timeout. Returns ms elapsed (-1 if timeout)."""
    t0 = time.perf_counter()
    while time.perf_counter() - t0 < timeout:
        if check_fn():
            return (time.perf_counter() - t0) * 1000
        time.sleep(0.3)
    return -1.0


def time_loop(op_fn, n=N):
    """Run op_fn(i) N times, measure user latency per call. Returns list of ms."""
    times = []
    for i in range(n):
        t0 = time.perf_counter()
        op_fn(i)
        times.append((time.perf_counter() - t0) * 1000)
    return times


def pcts(xs):
    if not xs: return {"mean": 0, "p50": 0, "p95": 0}
    xs = sorted(xs)
    return {
        "mean": statistics.mean(xs),
        "p50": xs[len(xs) // 2],
        "p95": xs[min(len(xs) - 1, int(len(xs) * 0.95))],
    }


def reset_dirs():
    for r in [FUSE_BENCH, NATIVE_BENCH]:
        if r.exists(): shutil.rmtree(r, ignore_errors=True)
        r.mkdir(parents=True, exist_ok=True)


# ---------- Per-op runners ----------
# Each takes a (root, virt_prefix) and returns (user_pcts_dict, e2e_ms_or_None)

def run_create_small(root, virt):
    ts = time_loop(lambda i: (root / f"cs_{i}.bin").write_bytes(SMALL))
    e2e = wait_until(lambda: cloud_head(f"{virt}/cs_{N-1}.bin") is not None) if virt else None
    return pcts(ts), e2e

def run_create_large(root, virt):
    ts = time_loop(lambda i: (root / f"cl_{i}.bin").write_bytes(LARGE))
    e2e = wait_until(lambda: cloud_head(f"{virt}/cl_{N-1}.bin") is not None) if virt else None
    return pcts(ts), e2e

def run_append(root, virt):
    p = root / "session.jsonl"
    p.write_bytes(b"")
    line = b'{"x":"abcd"}\n'
    ts = []
    for _ in range(N):
        t0 = time.perf_counter()
        with open(p, "ab") as f: f.write(line)
        ts.append((time.perf_counter() - t0) * 1000)
    expected = N * len(line)
    e2e = wait_until(lambda: (h := cloud_head(f"{virt}/session.jsonl")) and h.get("size", 0) >= expected) if virt else None
    return pcts(ts), e2e

def run_overwrite(root, virt):
    p = root / "overwrite.txt"
    p.write_bytes(b"")
    seq_data = [SMALL + bytes([i % 256]) for i in range(N)]
    ts = []
    for i in range(N):
        t0 = time.perf_counter()
        with open(p, "wb") as f: f.write(seq_data[i])
        ts.append((time.perf_counter() - t0) * 1000)
    expected_size = len(seq_data[-1])
    e2e = wait_until(lambda: (h := cloud_head(f"{virt}/overwrite.txt")) and h.get("size", 0) == expected_size) if virt else None
    return pcts(ts), e2e

def run_stat(root, virt):
    p = root / "stat_target.txt"; p.write_bytes(SMALL)
    if virt: wait_until(lambda: cloud_head(f"{virt}/stat_target.txt") is not None)
    ts = time_loop(lambda i: os.stat(p))
    return pcts(ts), None  # read op, no e2e

def run_read_small(root, virt):
    p = root / "read_small.bin"; p.write_bytes(SMALL)
    if virt: wait_until(lambda: cloud_head(f"{virt}/read_small.bin") is not None)
    ts = time_loop(lambda i: p.read_bytes())
    return pcts(ts), None

def run_read_large(root, virt):
    p = root / "read_large.bin"; p.write_bytes(LARGE)
    if virt: wait_until(lambda: cloud_head(f"{virt}/read_large.bin") is not None)
    ts = time_loop(lambda i: p.read_bytes())
    return pcts(ts), None

def run_truncate(root, virt):
    p = root / "trunc.bin"
    ts = []
    for i in range(N):
        p.write_bytes(SMALL)
        t0 = time.perf_counter()
        with open(p, "wb") as f: pass
        ts.append((time.perf_counter() - t0) * 1000)
    e2e = wait_until(lambda: (h := cloud_head(f"{virt}/trunc.bin")) and h.get("size", 0) == 0) if virt else None
    return pcts(ts), e2e

def run_unlink(root, virt):
    paths = [root / f"unl_{i}.bin" for i in range(N)]
    for p in paths: p.write_bytes(SMALL)
    if virt:
        wait_until(lambda: cloud_head(f"{virt}/unl_{N-1}.bin") is not None)
    ts = time_loop(lambda i: os.remove(paths[i]))
    e2e = wait_until(lambda: cloud_head(f"{virt}/unl_{N-1}.bin") is None) if virt else None
    return pcts(ts), e2e

def run_mkdir(root, virt):
    dirs = [root / f"d_{i}" for i in range(N)]
    ts = time_loop(lambda i: os.mkdir(dirs[i]))
    e2e = wait_until(lambda: cloud_head(f"{virt}/d_{N-1}") is not None) if virt else None
    return pcts(ts), e2e

def run_readdir_small(root, virt):
    rd = root / "rd_small"; rd.mkdir()
    for j in range(5): (rd / f"e_{j}.txt").write_bytes(b"x")
    if virt: wait_until(lambda: len(cloud_list(f"{virt}/rd_small")) >= 5)
    ts = time_loop(lambda i: os.listdir(rd))
    return pcts(ts), None

def run_readdir_large(root, virt):
    rd = root / "rd_large"; rd.mkdir()
    for j in range(100): (rd / f"e_{j}.txt").write_bytes(b"x")
    if virt: wait_until(lambda: len(cloud_list(f"{virt}/rd_large")) >= 100)
    ts = time_loop(lambda i: os.listdir(rd))
    return pcts(ts), None

def run_rmdir(root, virt):
    rms = [root / f"rm_{i}" for i in range(N)]
    for d in rms: d.mkdir()
    if virt: wait_until(lambda: cloud_head(f"{virt}/rm_{N-1}") is not None)
    ts = time_loop(lambda i: os.rmdir(rms[i]))
    e2e = wait_until(lambda: cloud_head(f"{virt}/rm_{N-1}") is None) if virt else None
    return pcts(ts), e2e

def run_rename_file(root, virt):
    base = root / "rename_test"; base.mkdir()
    for i in range(N): (base / f"a_{i}.txt").write_bytes(b"x")
    if virt: wait_until(lambda: cloud_head(f"{virt}/rename_test/a_{N-1}.txt") is not None)
    ts = time_loop(lambda i: os.rename(base / f"a_{i}.txt", base / f"b_{i}.txt"))
    e2e = wait_until(lambda: cloud_head(f"{virt}/rename_test/b_{N-1}.txt") is not None) if virt else None
    return pcts(ts), e2e

def run_rename_dir(root, virt):
    base = root / "rename_dirs"; base.mkdir()
    for i in range(N): (base / f"a_{i}").mkdir()
    if virt: wait_until(lambda: cloud_head(f"{virt}/rename_dirs/a_{N-1}") is not None)
    ts = time_loop(lambda i: os.rename(base / f"a_{i}", base / f"b_{i}"))
    e2e = wait_until(lambda: cloud_head(f"{virt}/rename_dirs/b_{N-1}") is not None) if virt else None
    return pcts(ts), e2e


OPS = [
    ("create_small",     run_create_small),
    ("create_large_64K", run_create_large),
    ("append",           run_append),
    ("overwrite",        run_overwrite),
    ("stat",             run_stat),
    ("read_small",       run_read_small),
    ("read_large",       run_read_large),
    ("truncate",         run_truncate),
    ("unlink",           run_unlink),
    ("mkdir",            run_mkdir),
    ("readdir_5",        run_readdir_small),
    ("readdir_100",      run_readdir_large),
    ("rmdir",            run_rmdir),
    ("rename_file",      run_rename_file),
    ("rename_dir",       run_rename_dir),
]


def fmt_row(name, n_user, f_user, e2e):
    n_user_s = f"{n_user['mean']:6.2f}"
    f_user_s = f"{f_user['mean']:6.2f}"
    ratio = f_user['mean'] / n_user['mean'] if n_user['mean'] > 0 else float('inf')
    if e2e is None or e2e < 0:
        e2e_s = "n/a" if e2e is None else "timeout"
    else:
        e2e_s = f"{e2e:>6.0f} ({e2e/N:.1f}/op)"
    print(f"  {name:<18}  native:{n_user_s}ms   FUSE user:{f_user_s}ms ({ratio:5.1f}×)   FUSE e2e:{e2e_s}")


def main():
    print(f"=== Full POSIX bench with cloud e2e ·  N={N} per op ===")
    print()

    for label, fn in OPS:
        # native
        for r in [NATIVE_BENCH]:
            if r.exists(): shutil.rmtree(r, ignore_errors=True)
            r.mkdir(parents=True, exist_ok=True)
        n_user, _ = fn(NATIVE_BENCH, virt=None)

        # FUSE — fresh subdir per op so e2e checks stay clean
        sub = FUSE_BENCH / label
        if sub.exists(): shutil.rmtree(sub, ignore_errors=True)
        sub.mkdir(parents=True, exist_ok=True)
        virt_prefix = f"/memory/bench-full-e2e/{label}"
        f_user, e2e = fn(sub, virt=virt_prefix)

        fmt_row(label, n_user, f_user, e2e)

    print()
    print("legend:")
    print("  user_ms = per-op syscall return latency (mean)")
    print("  e2e_ms  = wall time from first op to last op visible at DBay")
    print(f"            divided by N={N} = average per-op upload latency")
    print("  n/a     = read/stat op (no server roundtrip)")


if __name__ == "__main__":
    main()
