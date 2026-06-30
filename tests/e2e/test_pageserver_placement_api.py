from dbay_cli.client import DbayClient
from conftest import ADMIN_TOKEN, ENDPOINT


def test_admin_pageserver_topology_exposes_three_nodes():
    admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)

    topology = admin._request("GET", "/admin/pageserver/topology")

    nodes = topology["nodes"]
    assert len(nodes) >= 3
    assert {node["id"] for node in nodes} >= {"ps-0", "ps-1", "ps-2"}
    assert all(node["pg_connstring"].startswith("postgresql://") for node in nodes)
    assert all(isinstance(node["load_score"], (int, float)) for node in nodes)
    assert all(isinstance(node["placement_count"], int) for node in nodes)
    assert {node["source"] for node in nodes} <= {"configured", "dicer", "dicer-live"}
    decision_engine = topology["decision_engine"]
    assert decision_engine["mode"] in {"dicer-assisted-placement", "static-placement"}
    assert decision_engine["transport"] == "grpc"
    assert decision_engine["live_load_enabled"] is True
    assert decision_engine["auto_failover_enabled"] is True
    assert decision_engine["auto_rebalance_enabled"] is True


def test_admin_pageserver_summary_exposes_operational_diagnostics():
    admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)

    summary = admin._request("GET", "/admin/pageserver/summary")

    assert summary["health_status"] in {"healthy", "degraded", "critical", "disabled"}
    assert summary["risk_level"] in {"normal", "warning", "critical"}
    assert summary["node_counts"]["total"] >= 3
    assert summary["node_counts"]["healthy"] >= 0
    assert summary["node_counts"]["unhealthy"] >= 0
    assert summary["node_counts"]["cooling_down"] >= 0
    assert isinstance(summary["placement_distribution"], list)
    for item in summary["placement_distribution"]:
        assert item["node_id"]
        assert isinstance(item["placement_count"], int)
        assert isinstance(item["share"], (int, float))
    assert isinstance(summary["recent_events"], list)
    assert isinstance(summary["recommendations"], list)
    assert summary["decision_engine"]["auto_failover_enabled"] is True


def test_admin_pageserver_resolve_persists_assignment(e2e_tenant):
    admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)
    tenant_id = e2e_tenant["id"]

    placement = admin._request("POST", f"/admin/pageserver/placements/{tenant_id}/resolve", json={"shard_id": 0})
    placements = admin._request("GET", f"/admin/pageserver/placements/{tenant_id}")

    assert placement["tenant_id"] == tenant_id
    assert placement["shard_id"] == 0
    assert placement["node_id"] in {"ps-0", "ps-1", "ps-2", "default"}
    assert placement["epoch"] >= 1
    assert any(p["tenant_id"] == tenant_id and p["shard_id"] == 0 for p in placements)


def test_admin_pageserver_rebalance_dry_run_contract():
    admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)

    plan = admin._request("POST", "/admin/pageserver/rebalance/dry-run")

    assert plan["dry_run"] is True
    assert isinstance(plan["moves"], list)
    for move in plan["moves"]:
        assert move["tenant_id"]
        assert move["from_node_id"]
        assert move["to_node_id"]
        assert move["from_node_id"] != move["to_node_id"]
        assert move["next_epoch"] >= 1


def test_admin_pageserver_rebalance_apply_contract():
    admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)

    plan = admin._request("POST", "/admin/pageserver/rebalance/apply")

    assert plan["dry_run"] is False
    assert isinstance(plan["moves"], list)
    for move in plan["moves"]:
        assert move["tenant_id"]
        assert move["from_node_id"]
        assert move["to_node_id"]
        assert move["from_node_id"] != move["to_node_id"]
        assert move["next_epoch"] >= 1


def test_admin_pageserver_rebalance_dry_run_is_audited():
    admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)

    before = admin._request("GET", "/admin/pageserver/rebalance/events?limit=1")
    plan = admin._request("POST", "/admin/pageserver/rebalance/dry-run")
    after = admin._request("GET", "/admin/pageserver/rebalance/events?limit=5")

    assert isinstance(before, list)
    assert isinstance(after, list)
    assert after, "dry-run should create a Dicer operation history row"
    latest = after[0]
    assert latest["action"] == "REBALANCE_DRY_RUN"
    assert latest["trigger_type"] == "ADMIN"
    assert latest["actor"] == "admin-api"
    assert latest["dry_run"] is True
    assert latest["status"] in {"PLANNED", "NOOP"}
    assert latest["move_count"] == len(plan["moves"])
    assert isinstance(latest["moves"], list)
