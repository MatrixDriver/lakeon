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
import signal
import subprocess
import time

from conftest import poll_until


REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DBAY_FUSE_BIN = os.path.join(REPO_ROOT, "dbay-fuse", "target", "release", "dbay-fuse")


def _run(args, env, timeout=360):
    res = subprocess.run(args, capture_output=True, text=True, env=env, timeout=timeout)
    assert res.returncode == 0, (
        f"rc={res.returncode}\nstdout={res.stdout}\nstderr={res.stderr}"
    )
    return res


def _terminate(proc: subprocess.Popen):
    if proc.poll() is not None:
        return
    proc.send_signal(signal.SIGTERM)
    try:
        proc.wait(timeout=10)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait(timeout=10)


def _path_param(path: str) -> str:
    return base64.urlsafe_b64encode(path.encode()).decode().rstrip("=")


def _lbfs_entries(client, prefix: str):
    resp = client._request(
        "GET",
        "/lbfs/list",
        params={"prefix": _path_param(prefix), "recursive": "true"},
    )
    return resp.get("entries", [])


def _lbfs_folders(client):
    return client._request("GET", "/lbfs/folders").get("folders", [])


def _folder_by_name(client, name: str):
    matches = [folder for folder in _lbfs_folders(client) if folder.get("display_name") == name]
    return matches[0] if matches else None


def _lbfs_auto_jobs(client, folder_id: str):
    return client._request("GET", f"/lbfs/folders/{folder_id}/auto-jobs").get("auto_jobs", [])


def _wait_for_auto_job(client, folder: str, *, path_suffix: str, profile: str):
    registered = poll_until(
        lambda: _folder_by_name(client, folder),
        lambda candidate: candidate is not None,
        timeout=60,
        interval=5,
    )
    return poll_until(
        lambda: _lbfs_auto_jobs(client, registered["id"]),
        lambda jobs: any(
            job.get("source_path", "").endswith(path_suffix)
            and job.get("profile") == profile
            and job.get("status") in ("running", "succeeded", "retrying")
            for job in jobs
        ),
        timeout=180,
        interval=10,
    )


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
    jobs = _wait_for_auto_job(
        e2e_client,
        folder,
        path_suffix="/events.csv",
        profile="dataset",
    )
    assert any(
        job.get("source_path") == f"{remote}/events.csv"
        and job.get("profile") == "dataset"
        for job in jobs
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
        jobs = _wait_for_auto_job(
            e2e_client,
            folder,
            path_suffix="/" + rel_path,
            profile=processing,
        )
        assert any(
            job.get("source_path") == f"{remote}/{rel_path}"
            and job.get("profile") == processing
            for job in jobs
        )


def test_opencode_home_sync_registers_agent_home_profile(e2e_client, tmp_path):
    endpoint, key = e2e_client.endpoint, e2e_client.api_key
    folder = f"e2e-lbfs-opencode-{int(time.time() * 1000)}"
    remote = f"/e2e-lbfs-opencode/{folder}"

    home = tmp_path / "home"
    src = tmp_path / "opencode-home"
    (home / ".dbay").mkdir(parents=True)
    (src / ".opencode" / "sessions").mkdir(parents=True)
    (home / ".dbay" / "config.json").write_text(
        json.dumps({"endpoint": endpoint, "api_key": key})
    )
    (src / "opencode.json").write_text(json.dumps({"model": "test"}))
    (src / ".opencode" / "sessions" / "run.jsonl").write_text(
        f'{{"role":"user","content":"profile {folder}"}}\n'
    )

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
            "opencode-home",
            "--remote",
            remote,
        ],
        env,
        timeout=60,
    )
    assert "kind:      opencode-home" in sync.stdout
    assert "storage:   auto" in sync.stdout
    assert "processing: agent-home" in sync.stdout
    assert "registration: registered" in sync.stdout

    drain = _run([DBAY_FUSE_BIN, "outbox-drain", "--folder", folder], env)
    assert "outbox drain complete:" in drain.stdout

    registered = poll_until(
        lambda: _folder_by_name(e2e_client, folder),
        lambda candidate: candidate is not None,
        timeout=60,
        interval=5,
    )
    assert registered["directory_kind"] == "opencode-home"
    assert registered["processing_profile"] == "agent-home"

    entries = _lbfs_entries(e2e_client, remote + "/")
    _assert_profile(
        _entry(entries, f"{remote}/.opencode/sessions/run.jsonl"),
        folder=folder,
        kind="opencode-home",
        storage="auto",
        processing="agent-home",
    )


def test_sync_watch_pushes_later_file_changes(e2e_client, tmp_path):
    endpoint, key = e2e_client.endpoint, e2e_client.api_key
    folder = f"e2e-lbfs-watch-{int(time.time() * 1000)}"
    remote = f"/e2e-lbfs-watch/{folder}"

    home = tmp_path / "home"
    src = tmp_path / "src"
    (home / ".dbay").mkdir(parents=True)
    src.mkdir()
    (home / ".dbay" / "config.json").write_text(
        json.dumps({"endpoint": endpoint, "api_key": key})
    )
    (src / "initial.csv").write_text("id,name\n1,initial\n")

    env = {
        **os.environ,
        "HOME": str(home),
        "NO_PROXY": "*",
        "no_proxy": "*",
    }

    proc = subprocess.Popen(
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
            "--watch",
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env=env,
    )
    try:
        outbox_log = home / ".dbay" / "sync" / folder / "outbox" / "pending.log"
        poll_until(lambda: outbox_log.exists(), lambda exists: exists, timeout=30, interval=1)
        (src / "later.csv").write_text(f"id,name\n2,{folder}\n")
        poll_until(
            lambda: outbox_log.read_text() if outbox_log.exists() else "",
            lambda text: f"{remote}/later.csv" in text,
            timeout=45,
            interval=1,
        )
    finally:
        _terminate(proc)

    drain = _run([DBAY_FUSE_BIN, "outbox-drain", "--folder", folder], env)
    assert "outbox drain complete:" in drain.stdout

    entries = _lbfs_entries(e2e_client, remote + "/")
    _assert_profile(
        _entry(entries, f"{remote}/later.csv"),
        folder=folder,
        kind="data-dir",
        storage="object-first",
        processing="dataset",
    )
    jobs = _wait_for_auto_job(
        e2e_client,
        folder,
        path_suffix="/later.csv",
        profile="dataset",
    )
    assert any(
        job.get("source_path") == f"{remote}/later.csv"
        and job.get("profile") == "dataset"
        for job in jobs
    )
