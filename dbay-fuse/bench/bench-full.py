#!/usr/bin/env python3
"""Full POSIX op benchmark: LakebaseFS (FUSE) vs Native FS.

Each op runs N times on each backend; we report mean / p50 / p95 in ms.
For LakebaseFS write-side ops, "user_ms" is what CC sees (release returns
data fully written to local state + appended to outbox).

Workloads:
  Files:
    create_small        — open+write 200B+close (FUSE: 1 release flush)
    create_large_64K    — open+write 64KB+close
    append_small        — open+append 40B+close (typical session.jsonl line)
    overwrite_small     — open(O_TRUNC)+write 200B+close (CLAUDE.md style)
    stat                — os.stat()
    read_small          — open+read 200B (warm)
    read_large          — open+read 64KB (warm)
    truncate_to_zero    — open(O_TRUNC)+close
    unlink              — os.remove()
  Dirs:
    mkdir               — os.mkdir()
    rmdir               — os.rmdir() (empty dir)
    readdir_small       — os.listdir on dir with 5 entries
    readdir_large       — os.listdir on dir with 100 entries
    rename_file
    rename_dir
"""
import os, pathlib, shutil, statistics, time, sys

N = 100
HOME = pathlib.Path.home()
FUSE_ROOT = HOME / ".dbay/mnt/claude/memory" / "bench-full"
NATIVE_ROOT = pathlib.Path("/tmp/bench_full_native")

SMALL = b"small content " * 14   # ~196B
LARGE = b"x" * 65536              # 64 KB


def pcts(xs):
    if not xs: return {"mean": 0, "p50": 0, "p95": 0, "max": 0}
    xs = sorted(xs)
    return {
        "mean": statistics.mean(xs),
        "p50": xs[len(xs) // 2],
        "p95": xs[min(len(xs) - 1, int(len(xs) * 0.95))],
        "max": xs[-1],
    }


def time_op(setup, op, teardown=lambda i: None, n=N):
    times = []
    for i in range(n):
        try: setup(i)
        except Exception: pass
        t0 = time.perf_counter()
        op(i)
        times.append((time.perf_counter() - t0) * 1000)
        try: teardown(i)
        except Exception: pass
    return pcts(times)


def reset(root):
    if root.exists():
        shutil.rmtree(root, ignore_errors=True)
    root.mkdir(parents=True, exist_ok=True)


def bench_all(label, root, n_io=N, n_dir=50):
    print(f"\n[{label}]  root={root}  N(io)={n_io} N(dir)={n_dir}")
    reset(root)

    results = {}

    # --- create_small ---
    paths = [root / f"cs_{i}.bin" for i in range(n_io)]
    results['create_small'] = time_op(
        setup=lambda i: None,
        op=lambda i: paths[i].write_bytes(SMALL),
        n=n_io)

    # --- create_large_64K ---
    paths = [root / f"cl_{i}.bin" for i in range(n_io)]
    results['create_large_64K'] = time_op(
        setup=lambda i: None,
        op=lambda i: paths[i].write_bytes(LARGE),
        n=n_io)

    # --- append_small (each iter opens+appends+closes the SAME file) ---
    p = root / "append.jsonl"
    p.write_bytes(b"")
    line = b'{"x":"abcd"}\n'
    def do_append(_i):
        with open(p, "ab") as f: f.write(line)
    results['append_small'] = time_op(setup=lambda i: None, op=do_append, n=n_io)

    # --- overwrite_small ---
    p = root / "overwrite.txt"
    p.write_bytes(b"")
    def do_overwrite(_i):
        with open(p, "wb") as f: f.write(SMALL)
    results['overwrite_small'] = time_op(setup=lambda i: None, op=do_overwrite, n=n_io)

    # --- stat (warm) ---
    p = root / "stat_target.txt"
    p.write_bytes(SMALL)
    results['stat'] = time_op(setup=lambda i: None, op=lambda i: os.stat(p), n=n_io)

    # --- read_small ---
    p = root / "read_small.bin"
    p.write_bytes(SMALL)
    results['read_small'] = time_op(setup=lambda i: None,
                                    op=lambda i: p.read_bytes(), n=n_io)

    # --- read_large ---
    p = root / "read_large.bin"
    p.write_bytes(LARGE)
    results['read_large'] = time_op(setup=lambda i: None,
                                    op=lambda i: p.read_bytes(), n=n_io)

    # --- truncate_to_zero ---
    p = root / "trunc.bin"
    def do_truncate(_i):
        with open(p, "wb") as f: pass
    results['truncate_to_zero'] = time_op(
        setup=lambda i: p.write_bytes(SMALL),
        op=do_truncate, n=n_io)

    # --- unlink ---
    paths = [root / f"unl_{i}.bin" for i in range(n_io)]
    for p in paths: p.write_bytes(SMALL)
    results['unlink'] = time_op(
        setup=lambda i: None,
        op=lambda i: os.remove(paths[i]), n=n_io)

    # --- mkdir ---
    dirs = [root / f"d_{i}" for i in range(n_dir)]
    results['mkdir'] = time_op(
        setup=lambda i: None,
        op=lambda i: os.mkdir(dirs[i]), n=n_dir)

    # --- readdir_small (5 entries) ---
    rd_dir = root / "rd_small"
    rd_dir.mkdir()
    for j in range(5): (rd_dir / f"e_{j}.txt").write_bytes(b"x")
    results['readdir_small'] = time_op(
        setup=lambda i: None,
        op=lambda i: os.listdir(rd_dir), n=n_io)

    # --- readdir_large (100 entries) ---
    rd_dir = root / "rd_large"
    rd_dir.mkdir()
    for j in range(100): (rd_dir / f"e_{j}.txt").write_bytes(b"x")
    results['readdir_large'] = time_op(
        setup=lambda i: None,
        op=lambda i: os.listdir(rd_dir), n=n_io)

    # --- rmdir (empty) ---
    rm_dirs = [root / f"rm_{i}" for i in range(n_dir)]
    for d in rm_dirs: d.mkdir()
    results['rmdir'] = time_op(
        setup=lambda i: None,
        op=lambda i: os.rmdir(rm_dirs[i]), n=n_dir)

    # --- rename_file ---
    base = root / "rename_test"
    base.mkdir()
    for i in range(n_io): (base / f"a_{i}.txt").write_bytes(b"x")
    results['rename_file'] = time_op(
        setup=lambda i: None,
        op=lambda i: os.rename(base / f"a_{i}.txt", base / f"b_{i}.txt"),
        n=n_io)

    # --- rename_dir ---
    rd_base = root / "rename_dirs"
    rd_base.mkdir()
    for i in range(n_dir): (rd_base / f"a_{i}").mkdir()
    results['rename_dir'] = time_op(
        setup=lambda i: None,
        op=lambda i: os.rename(rd_base / f"a_{i}", rd_base / f"b_{i}"),
        n=n_dir)

    return results


def fmt(name, n, f):
    ratio = f["mean"] / n["mean"] if n["mean"] > 0 else float('inf')
    star = ""
    if ratio > 100: star = "  ⚠️"
    elif ratio < 2: star = "  ✓"
    print(f"  {name:<20}  native: {n['mean']:7.3f}ms (p95 {n['p95']:6.3f})   "
          f"FUSE: {f['mean']:7.3f}ms (p95 {f['p95']:6.3f})   "
          f"{ratio:6.1f}×{star}")


def main():
    print(f"=== Full POSIX bench · dbay-fuse vs native FS · N={N} ===")
    if not (FUSE_ROOT.parent.parent).exists():
        print("FUSE mount not ready", file=sys.stderr); sys.exit(1)
    nat = bench_all("native", NATIVE_ROOT)
    fus = bench_all("LakebaseFS", FUSE_ROOT)
    print()
    print(f"=== summary (mean ms, ratio = FUSE / native) ===")
    for op in ['create_small', 'create_large_64K', 'append_small', 'overwrite_small',
               'stat', 'read_small', 'read_large', 'truncate_to_zero', 'unlink',
               'mkdir', 'readdir_small', 'readdir_large', 'rmdir',
               'rename_file', 'rename_dir']:
        fmt(op, nat[op], fus[op])
    print()
    print("legend: ✓ FUSE within 2× native    ⚠️ FUSE >100× native")


if __name__ == "__main__":
    main()
