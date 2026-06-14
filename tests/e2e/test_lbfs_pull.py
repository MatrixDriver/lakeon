"""E2E: dbay-fuse pull downloads missing files from remote LakebaseFS.

Spawns the dbay-fuse binary in a subprocess against a temp HOME and state dir
populated from the per-session e2e_client. Each test PUTs files server-side
via HTTP, then runs `dbay-fuse pull --state <tmp>` and asserts the local
state directory matches expectations.

NOTE: requires lakeon-api deployed with T1-T3 changes (server-side if_match
end-to-end). The pull itself doesn't need T1-T3, but conflict scenarios in
test 3 rely on the server returning consistent etag headers.
"""
import base64
import json
import os
import subprocess
import time

import requests


REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DBAY_FUSE_BIN = os.path.join(REPO_ROOT, "dbay-fuse", "target", "release", "dbay-fuse")


def _b64(b: bytes) -> str:
    return base64.b64encode(b).decode()


def _put(endpoint, key, path, data, retries=20, delay=6):
    last = None
    for _ in range(retries):
        try:
            r = requests.post(
                f"{endpoint}/api/v1/lbfs/files/put",
                json={"path": path, "data_base64": _b64(data)},
                headers={"Authorization": f"Bearer {key}"},
                verify=False, timeout=30,
            )
        except (requests.ReadTimeout, requests.ConnectionError) as e:
            last = ("network", str(e)[:200])
            time.sleep(delay)
            continue
        if r.status_code == 200:
            return r.json()
        last = (r.status_code, r.text[:200])
        time.sleep(delay)
    raise RuntimeError(f"PUT failed: {last}")


def _write_config(home: str, endpoint: str, key: str):
    cfgdir = os.path.join(home, ".dbay")
    os.makedirs(cfgdir, exist_ok=True)
    with open(os.path.join(cfgdir, "config.json"), "w") as f:
        json.dump({"endpoint": endpoint, "api_key": key}, f)


def _run_pull(home: str, agent: str, state_dir: str, prefix="/", extra=()):
    env = {**os.environ, "HOME": home}
    cmd = [DBAY_FUSE_BIN, "pull",
           "--agent", agent,
           "--state", state_dir,
           "--prefix", prefix,
           *extra]
    res = subprocess.run(cmd, capture_output=True, text=True,
                         env=env, timeout=180)
    assert res.returncode == 0, f"rc={res.returncode}\nstdout={res.stdout}\nstderr={res.stderr}"
    return res.stdout


def test_pull_downloads_missing_files(e2e_client, tmp_path):
    endpoint, key = e2e_client.endpoint, e2e_client.api_key
    _put(endpoint, key, "/e2e-pull/a.txt", b"alpha")
    _put(endpoint, key, "/e2e-pull/b.txt", b"bravo")
    _put(endpoint, key, "/e2e-pull/sub/c.txt", b"charlie")

    fake_home = tmp_path / "home"
    fake_home.mkdir()
    state_dir = tmp_path / "state"
    _write_config(str(fake_home), endpoint, key)

    out = _run_pull(str(fake_home), "claude", str(state_dir), prefix="/e2e-pull/")
    assert "synced=3" in out, out

    assert (state_dir / "e2e-pull" / "a.txt").read_bytes() == b"alpha"
    assert (state_dir / "e2e-pull" / "b.txt").read_bytes() == b"bravo"
    assert (state_dir / "e2e-pull" / "sub" / "c.txt").read_bytes() == b"charlie"


def test_pull_second_run_skips_unchanged(e2e_client, tmp_path):
    endpoint, key = e2e_client.endpoint, e2e_client.api_key
    _put(endpoint, key, "/e2e-pull2/x.txt", b"x1")

    fake_home = tmp_path / "home"
    fake_home.mkdir()
    state_dir = tmp_path / "state"
    _write_config(str(fake_home), endpoint, key)

    out1 = _run_pull(str(fake_home), "claude", str(state_dir), prefix="/e2e-pull2/")
    assert "synced=1" in out1, out1

    out2 = _run_pull(str(fake_home), "claude", str(state_dir), prefix="/e2e-pull2/")
    assert "synced=0" in out2 and "skipped=1" in out2, out2


def test_pull_remote_change_produces_conflict_sidecar(e2e_client, tmp_path):
    endpoint, key = e2e_client.endpoint, e2e_client.api_key
    _put(endpoint, key, "/e2e-pull3/y.txt", b"v1")

    fake_home = tmp_path / "home"
    fake_home.mkdir()
    state_dir = tmp_path / "state"
    _write_config(str(fake_home), endpoint, key)

    _run_pull(str(fake_home), "claude", str(state_dir), prefix="/e2e-pull3/")
    assert (state_dir / "e2e-pull3" / "y.txt").read_bytes() == b"v1"

    # Simulate "another machine" updates the file remotely.
    _put(endpoint, key, "/e2e-pull3/y.txt", b"v2")

    # Pull again: ledger says v1, remote says v2, local file exists with v1 →
    # decision matrix yields Conflict → sidecar gets v2, canonical stays v1.
    _run_pull(str(fake_home), "claude", str(state_dir), prefix="/e2e-pull3/")
    assert (state_dir / "e2e-pull3" / "y.txt").read_bytes() == b"v1"

    sidecars = [p for p in (state_dir / "e2e-pull3").iterdir()
                if p.name.startswith("y.txt.conflict-pull-")]
    assert len(sidecars) == 1, sorted(p.name for p in (state_dir / "e2e-pull3").iterdir())
    assert sidecars[0].read_bytes() == b"v2"
