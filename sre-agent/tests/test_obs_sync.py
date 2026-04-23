"""Tests for OBS upload worker (sync API)."""
import tarfile
from pathlib import Path

import pytest

from agent_session_log import LogStore
from agent_session_log.obs_sync import FakeObsClient, ObsSync


def test_archive_closed_session(tmp_log_root: Path):
    log = LogStore(tmp_log_root)
    s = log.new_session(type="incident", trigger={}, tags=[])
    s.append_turn(type="thought", content="hi")
    s.conclude("x")
    s.close()

    client = FakeObsClient()
    sync = ObsSync(log.store, client=client, bucket="test-bucket", prefix="agent-log/")
    ref = sync.upload_session(s.id)

    assert ref.startswith("agent-log/")
    assert s.id in ref
    assert ref.endswith(".tar.gz")
    # FakeObsClient captured the upload
    assert ref in client.objects
    # Contents is a valid tar.gz
    data = client.objects[ref]
    # write to tmp and inspect
    tmp = tmp_log_root / "check.tar.gz"
    tmp.write_bytes(data)
    with tarfile.open(tmp, "r:gz") as tf:
        names = tf.getnames()
    assert any("manifest.yaml" in n for n in names)
    assert any("events.jsonl" in n for n in names)


def test_upload_skips_when_manifest_records_obs_ref(tmp_log_root: Path):
    """If manifest already has obs_ref, skip re-upload."""
    log = LogStore(tmp_log_root)
    s = log.new_session(type="incident", trigger={}, tags=[])
    s.close()
    # Pre-set obs_ref
    m = log.store.read_manifest(s.id)
    m.obs_ref = "agent-log/already/uploaded.tar.gz"
    log.store.write_manifest(m)

    client = FakeObsClient()
    sync = ObsSync(log.store, client=client, bucket="test-bucket", prefix="agent-log/")
    ref = sync.upload_session(s.id)
    assert ref == "agent-log/already/uploaded.tar.gz"
    assert len(client.objects) == 0  # no new upload


def test_upload_refuses_open_session(tmp_log_root: Path):
    log = LogStore(tmp_log_root)
    s = log.new_session(type="incident", trigger={}, tags=[])
    # do not close

    client = FakeObsClient()
    sync = ObsSync(log.store, client=client, bucket="test-bucket", prefix="agent-log/")
    with pytest.raises(ValueError, match="open"):
        sync.upload_session(s.id)
