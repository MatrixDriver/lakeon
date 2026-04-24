"""Tests for WatcherBase — shared dedupe + session-open + ledger helpers."""
from datetime import datetime, timedelta, timezone

from agent_session_log import LogStore
from skills.sre._base.watcher_base import WatcherBase


def test_open_incident_writes_session_with_trigger_and_tags(tmp_log_root):
    log = LogStore(tmp_log_root)
    wb = WatcherBase(log=log, skill_name="test-watcher", skill_version="v0.1")

    sid = wb.open_incident(
        trigger={"source": "cron/test", "alert": "something broke",
                 "signal_id": "foo"},
        tags=["severity:medium", "component:test"],
    )

    m = log.store.read_manifest(sid)
    assert m.type == "incident"
    assert m.trigger["alert"] == "something broke"
    assert "component:test" in m.tags
    assert "skill:test-watcher" in m.tags  # WatcherBase auto-adds skill tag


def test_open_incident_records_ledger_invocation(tmp_log_root):
    from agent_session_log import SkillLedger
    log = LogStore(tmp_log_root)
    wb = WatcherBase(log=log, skill_name="test-watcher", skill_version="v0.1")

    sid = wb.open_incident(trigger={"alert": "x", "signal_id": "foo"}, tags=[])

    stats = SkillLedger(tmp_log_root).stats("test-watcher")
    assert stats["total_invocations"] == 1


def test_is_recently_seen_detects_same_signal_id(tmp_log_root):
    log = LogStore(tmp_log_root)
    wb = WatcherBase(log=log, skill_name="test-watcher", skill_version="v0.1",
                     dedupe_window_sec=600)

    sid = wb.open_incident(
        trigger={"alert": "first", "signal_id": "sig_abc"}, tags=[],
    )
    # Same signal_id within window → should be deduped
    assert wb.is_recently_seen(signal_id="sig_abc") is True
    # Different signal_id → not deduped
    assert wb.is_recently_seen(signal_id="sig_xyz") is False


def test_is_recently_seen_respects_dedupe_window(tmp_log_root):
    log = LogStore(tmp_log_root)
    wb = WatcherBase(log=log, skill_name="test-watcher", skill_version="v0.1",
                     dedupe_window_sec=60)

    sid = wb.open_incident(trigger={"alert": "x", "signal_id": "sig_old"}, tags=[])
    # Backdate the session to > 60 sec ago
    m = log.store.read_manifest(sid)
    m.created_at = (datetime.now(timezone.utc) - timedelta(seconds=120)).strftime("%Y-%m-%dT%H:%M:%SZ")
    log.store.write_manifest(m)

    # Outside window → should NOT be deduped
    assert wb.is_recently_seen(signal_id="sig_old") is False


def test_open_incident_conclude_and_close_helper(tmp_log_root):
    log = LogStore(tmp_log_root)
    wb = WatcherBase(log=log, skill_name="test-watcher", skill_version="v0.1")

    sid = wb.open_incident(trigger={"alert": "x", "signal_id": "s"}, tags=[])
    wb.conclude_and_close(sid, "# Root cause\n\nIt broke because of X.\n")

    assert log.store.read_manifest(sid).status == "closed"
    assert "Root cause" in log.store.read_conclusion(sid)
