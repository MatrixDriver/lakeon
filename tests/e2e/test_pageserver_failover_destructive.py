import os

import pytest

from dbay_cli.client import DbayClient
from conftest import ADMIN_TOKEN, ENDPOINT


pytestmark = pytest.mark.skipif(
    os.environ.get("RUN_DESTRUCTIVE_PAGESERVER_E2E") != "1",
    reason="Set RUN_DESTRUCTIVE_PAGESERVER_E2E=1 to run live pageserver failover drills.",
)


def test_live_pageserver_failover_moves_placements_off_target_node():
    admin = DbayClient(endpoint=ENDPOINT, api_key=ADMIN_TOKEN)
    topology = admin._request("GET", "/admin/pageserver/topology")
    placement_counts = {}
    for placement in topology["placements"]:
        placement_counts[placement["node_id"]] = placement_counts.get(placement["node_id"], 0) + 1
    target_node = max(placement_counts, key=placement_counts.get)

    plan = admin._request("POST", f"/admin/pageserver/nodes/{target_node}/failover")
    after = admin._request("GET", "/admin/pageserver/topology")
    remaining = [
        placement for placement in after["placements"]
        if placement["node_id"] == target_node
    ]
    events = admin._request("GET", "/admin/pageserver/rebalance/events?limit=3")

    assert plan["dry_run"] is False
    assert plan["moves"], "destructive failover should move placements off the selected node"
    assert not remaining, f"placements still remain on failed-over node {target_node}"
    assert events[0]["action"] == "FAILOVER_NODE"
    assert events[0]["target_node_id"] == target_node
    assert events[0]["status"] in {"APPLIED", "NOOP"}
