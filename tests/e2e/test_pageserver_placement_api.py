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
    assert {node["source"] for node in nodes} <= {"configured", "dicer", "dicer-live"}


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
