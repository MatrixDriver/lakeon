"""E2E: a fresh machine (empty state dir) running `dbay-fuse pull` picks up
remote files exactly as the startup-pull in `dbay-fuse mount` would.

We don't actually mount FUSE — we exercise the same `pull` code path
that mount runs on startup (T14), which is equivalent for assertion
purposes and doesn't require macFUSE on the CI host.

NOTE: requires the dbay-fuse binary to be built (cargo build --release).
"""
import base64
import json
import os
import subprocess

import requests


REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DBAY_FUSE_BIN = os.path.join(REPO_ROOT, "dbay-fuse", "target", "release", "dbay-fuse")


def _put(endpoint, key, path, data):
    r = requests.post(
        f"{endpoint}/api/v1/agentfs/files/put",
        json={"path": path, "data_base64": base64.b64encode(data).decode()},
        headers={"Authorization": f"Bearer {key}"},
        verify=False, timeout=120,
    )
    assert r.status_code == 200, r.text


def test_pull_recovers_remote_state_to_empty_local(e2e_client, tmp_path):
    endpoint, key = e2e_client.endpoint, e2e_client.api_key

    # Simulate writes from the "previous machine".
    for p, b in [
        ("/resume/foo.md", b"# foo"),
        ("/resume/bar/baz.txt", b"hello\nworld\n"),
        ("/resume/CLAUDE.md", b"context here"),
    ]:
        _put(endpoint, key, p, b)

    home = tmp_path / "newhome"
    home.mkdir()
    (home / ".dbay").mkdir()
    (home / ".dbay" / "config.json").write_text(
        json.dumps({"endpoint": endpoint, "api_key": key}))

    state = tmp_path / "newstate"
    env = {**os.environ, "HOME": str(home)}
    res = subprocess.run(
        [DBAY_FUSE_BIN, "pull",
         "--agent", "claude",
         "--state", str(state),
         "--prefix", "/resume/"],
        capture_output=True, text=True, env=env, timeout=120,
    )
    assert res.returncode == 0, f"rc={res.returncode}\nstdout={res.stdout}\nstderr={res.stderr}"

    assert (state / "resume" / "foo.md").read_bytes() == b"# foo"
    assert (state / "resume" / "bar" / "baz.txt").read_bytes() == b"hello\nworld\n"
    assert (state / "resume" / "CLAUDE.md").read_bytes() == b"context here"
