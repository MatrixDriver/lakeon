"""E2E: sync an existing local directory to DBay LakebaseFS, then pull it back.

This exercises the new general-folder flow without mounting FUSE:

  existing local dir -> dbay-fuse sync -> sync outbox
  -> dbay-fuse outbox-drain -> DBay LakebaseFS
  -> dbay-fuse pull -> fresh local state dir

NOTE: requires the dbay-fuse binary to be built (cargo build --release).
"""
import json
import os
import base64
import subprocess
import time


REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DBAY_FUSE_BIN = os.path.join(REPO_ROOT, "dbay-fuse", "target", "release", "dbay-fuse")


def _run(args, env, timeout=360):
    res = subprocess.run(args, capture_output=True, text=True, env=env, timeout=timeout)
    assert res.returncode == 0, (
        f"rc={res.returncode}\nstdout={res.stdout}\nstderr={res.stderr}"
    )
    return res


def _path_param(path: str) -> str:
    return base64.urlsafe_b64encode(path.encode()).decode().rstrip("=")


def _lbfs_entries(client, prefix: str):
    resp = client._request(
        "GET",
        "/lbfs/list",
        params={"prefix": _path_param(prefix), "recursive": "true"},
    )
    return resp.get("entries", [])


def _entry(entries, path: str):
    matches = [e for e in entries if e.get("path") == path]
    assert matches, f"missing LakebaseFS entry {path}; entries={entries}"
    return matches[0]


def _assert_profile(entry, *, folder, kind, storage, processing):
    profile = entry.get("properties", {}).get("lbfs_profile")
    assert profile == {
        "folder": folder,
        "directory_kind": kind,
        "storage_policy": storage,
        "processing_profile": processing,
    }


def test_sync_drain_pull_roundtrip_for_existing_data_dir(e2e_client, tmp_path):
    endpoint, key = e2e_client.endpoint, e2e_client.api_key
    folder = f"e2e-lbfs-sync-{int(time.time() * 1000)}"
    remote = f"/e2e-lbfs-sync/{folder}"

    home = tmp_path / "home"
    src = tmp_path / "src"
    pull_state = tmp_path / "pull"
    (home / ".dbay").mkdir(parents=True)
    (src / "nested").mkdir(parents=True)
    pull_state.mkdir()
    (home / ".dbay" / "config.json").write_text(
        json.dumps({"endpoint": endpoint, "api_key": key})
    )
    (src / "nested" / "a.md").write_text(f"hello from {folder}\n")
    (src / "events.csv").write_text(f"id,name\n1,{folder}\n")

    env = {
        **os.environ,
        "HOME": str(home),
        "NO_PROXY": "*",
        "no_proxy": "*",
    }

    sync = _run(
        [
            DBAY_FUSE_BIN,
            "sync",
            str(src),
            "--folder",
            folder,
            "--kind",
            "data-dir",
            "--remote",
            remote,
        ],
        env,
        timeout=60,
    )
    assert "kind:      data-dir" in sync.stdout
    assert "storage:   object-first" in sync.stdout
    assert "processing: dataset" in sync.stdout
    assert not (home / ".dbay" / "state" / folder).exists()

    drain = _run([DBAY_FUSE_BIN, "outbox-drain", "--folder", folder], env)
    assert "outbox drain complete: drained=3" in drain.stdout

    pull = _run(
        [
            DBAY_FUSE_BIN,
            "pull",
            "--folder",
            folder,
            "--state",
            str(pull_state),
            "--prefix",
            remote + "/",
        ],
        env,
        timeout=240,
    )
    assert "pull complete:" in pull.stdout
    assert (pull_state / "e2e-lbfs-sync" / folder / "nested" / "a.md").read_text() == (
        f"hello from {folder}\n"
    )
    assert folder in (pull_state / "e2e-lbfs-sync" / folder / "events.csv").read_text()

    entries = _lbfs_entries(e2e_client, remote + "/")
    _assert_profile(
        _entry(entries, f"{remote}/events.csv"),
        folder=folder,
        kind="data-dir",
        storage="object-first",
        processing="dataset",
    )


def test_table_kind_profiles_reach_lbfs_server_model(e2e_client, tmp_path):
    endpoint, key = e2e_client.endpoint, e2e_client.api_key
    home = tmp_path / "home"
    (home / ".dbay").mkdir(parents=True)
    (home / ".dbay" / "config.json").write_text(
        json.dumps({"endpoint": endpoint, "api_key": key})
    )
    env = {
        **os.environ,
        "HOME": str(home),
        "NO_PROXY": "*",
        "no_proxy": "*",
    }

    cases = [
        ("iceberg-table", "iceberg", "metadata/v1.metadata.json", b'{"format-version":2}\n'),
        ("lance-table", "lance", "_versions/1.manifest", b"lance-version-placeholder\n"),
    ]
    ts = int(time.time() * 1000)

    for kind, processing, rel_path, payload in cases:
        folder = f"e2e-lbfs-{kind}-{ts}"
        remote = f"/e2e-lbfs-profiles/{folder}"
        src = tmp_path / folder
        (src / os.path.dirname(rel_path)).mkdir(parents=True)
        (src / rel_path).write_bytes(payload)

        sync = _run(
            [
                DBAY_FUSE_BIN,
                "sync",
                str(src),
                "--folder",
                folder,
                "--kind",
                kind,
                "--remote",
                remote,
            ],
            env,
            timeout=60,
        )
        assert f"kind:      {kind}" in sync.stdout
        assert "storage:   table-native" in sync.stdout
        assert f"processing: {processing}" in sync.stdout

        drain = _run([DBAY_FUSE_BIN, "outbox-drain", "--folder", folder], env)
        assert "outbox drain complete:" in drain.stdout

        entries = _lbfs_entries(e2e_client, remote + "/")
        _assert_profile(
            _entry(entries, f"{remote}/{rel_path}"),
            folder=folder,
            kind=kind,
            storage="table-native",
            processing=processing,
        )
