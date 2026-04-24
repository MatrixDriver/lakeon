"""Full-stack integration test: end-to-end SRE incident flow."""
from pathlib import Path

from agent_session_log import LogStore, SkillLedger


def test_full_sre_incident_flow(tmp_log_root: Path):
    """End-to-end flow: incident → branching → resolution → outcome."""
    log = LogStore(tmp_log_root)
    ledger = SkillLedger(tmp_log_root)

    # 1) Skill triggers, opens session
    s = log.new_session(
        type="incident",
        trigger={
            "source": "cron/cold-start-watcher",
            "skill_version": "v0.1",
            "alert": "compute cold start 8234ms",
            "tenant_id": "t_abc",
            "db_id": "db_xyz",
        },
        tags=["severity:medium", "component:compute", "skill:cold-start-watcher"],
        model="deepseek-chat",
        runtime="hermes@0.10.0",
    )
    ledger.record_invocation(
        "cold-start-watcher",
        version="v0.1",
        session_id=s.id,
        triggered_at="2026-04-23T09:12:30Z",
    )

    # 2) Initial investigation
    s.append_turn(type="thought", content="Need to narrow scope — check pod + pageserver.")
    s.append_turn(
        type="tool_call",
        tool="log_search",
        args={"component": "compute", "since": "5m"},
        latency_ms=230,
    )
    blob_logs = s.attach_evidence(
        b"2026-04-23 09:12:28 compute pod starting...\n"
        b"2026-04-23 09:12:36 compute ready (took 8234ms)\n",
        mime="text/plain",
        source="log_search@dbay-sre-mcp",
    )
    s.append_turn(type="tool_result", ref_turn=1, evidence=[blob_logs.sha256])

    # 3) Branch out two hypotheses
    b_img = s.branch("h1-image-pull")
    b_ps = s.branch("h2-pageserver")
    b_img.append_turn(type="thought", content="if image pull slow, expect ImagePulling event")
    b_img.append_turn(
        type="tool_call",
        tool="log_search",
        args={"component": "k8s", "keyword": "ImagePulling"},
        latency_ms=180,
    )
    b_img.append_turn(type="tool_result", ref_turn=1, evidence=[])  # empty

    b_ps.append_turn(type="thought", content="check pageserver re-attach duration")
    b_ps.append_turn(
        type="tool_call",
        tool="log_search",
        args={"component": "pageserver", "since": "5m"},
        latency_ms=210,
    )
    blob_ps = s.attach_evidence(b'{"reattach_duration_ms": 6800}', mime="application/json")
    b_ps.append_turn(type="tool_result", ref_turn=1, evidence=[blob_ps.sha256])

    # 4) Resolve: h2 wins
    s.resolve_branches(
        keep="h2-pageserver",
        discard=["h1-image-pull"],
        reason="h1 had no ImagePulling events; h2 showed 6800ms re-attach",
        evidence=[blob_ps.sha256],
    )

    # 5) Conclude
    s.conclude(
        "# Cold start 8234ms for db_xyz\n\n"
        "## Root cause (confidence 0.72)\n"
        "Pageserver re-attach gap for tenant t_abc — 6.8s re-attach.\n\n"
        "## Suggested actions\n"
        "1. Manual PUT location_config for t_abc\n"
    )
    s.close()

    # 6) Outcome (24h later)
    s.record_outcome(did_work=True, notes="p95 back to 2.1s after manual fix")
    ledger.record_outcome("cold-start-watcher", session_id=s.id, did_work=True)

    # ==== Assertions ====
    loaded = log.get_session(s.id)
    assert loaded.status == "closed"

    events = log.store.read_events(s.id, "main")
    types = [e["type"] for e in events]
    assert types.count("branch_open") == 2
    assert types.count("branch_resolve") == 1
    assert types.count("conclude") == 1

    # branches have their own events
    assert len(log.store.read_events(s.id, "h2-pageserver")) == 3

    # outcome filed
    assert "p95 back to 2.1s" in log.store.read_outcome(s.id)

    # skill stats reflect
    stats = ledger.stats("cold-start-watcher")
    assert stats["total_invocations"] == 1
    assert stats["did_work_rate"] == 1.0

    # search works
    hits = log.search_text("pageserver")
    assert s.id in [h["id"] for h in hits]
