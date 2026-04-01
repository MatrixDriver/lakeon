from __future__ import annotations

from collections import deque

from lakeon_orchestrator.dag.parser import DAG

TERMINAL_STATUSES = {"SUCCEEDED", "FAILED", "SKIPPED", "CANCELLED"}
DONE_STATUSES = {"SUCCEEDED", "SKIPPED"}


class DAGScheduler:
    def __init__(self, dag: DAG):
        self._dag = dag

    def topological_sort(self) -> list[str]:
        """Kahn's algorithm. Raises ValueError on cycle."""
        in_degree: dict[str, int] = {nid: 0 for nid in self._dag.nodes}
        for parent, children in self._dag.edges.items():
            for child in children:
                in_degree[child] = in_degree.get(child, 0) + 1

        queue = deque(nid for nid, deg in in_degree.items() if deg == 0)
        result: list[str] = []

        while queue:
            node_id = queue.popleft()
            result.append(node_id)
            for child in self._dag.get_children(node_id):
                in_degree[child] -= 1
                if in_degree[child] == 0:
                    queue.append(child)

        if len(result) != len(self._dag.nodes):
            raise ValueError("DAG contains a cycle — topological sort impossible")

        return result

    def get_ready_steps(self, step_statuses: dict[str, str]) -> list[str]:
        """Return step IDs that are PENDING and all parents are done."""
        ready = []
        for node_id, node in self._dag.nodes.items():
            if step_statuses.get(node_id) != "PENDING":
                continue
            parents = self._dag.get_parents(node_id)
            if all(step_statuses.get(p) in DONE_STATUSES for p in parents):
                # Check condition: if this node has a condition, verify the branch was active
                if node.condition:
                    # condition format: "parent_id.branch_name"
                    # The BranchRouter handles actual routing; here we just check parent done
                    pass
                ready.append(node_id)
        return ready

    def is_complete(self, step_statuses: dict[str, str]) -> bool:
        """All steps are in a terminal success state."""
        return all(
            step_statuses.get(nid) in DONE_STATUSES for nid in self._dag.nodes
        )

    def has_failed(self, step_statuses: dict[str, str]) -> bool:
        """Any step has failed."""
        return any(
            step_statuses.get(nid) == "FAILED" for nid in self._dag.nodes
        )

    def has_paused(self, step_statuses: dict[str, str]) -> bool:
        """Any step is paused (HUMAN_REVIEW)."""
        return any(
            step_statuses.get(nid) == "PAUSED" for nid in self._dag.nodes
        )

    def aggregate_status(self, step_statuses: dict[str, str]) -> str:
        """Compute overall pipeline run status from step statuses."""
        if self.is_complete(step_statuses):
            return "SUCCEEDED"
        if self.has_failed(step_statuses):
            return "FAILED"
        if self.has_paused(step_statuses) and not any(
            step_statuses.get(nid) == "RUNNING" for nid in self._dag.nodes
        ):
            return "PAUSED"
        if any(step_statuses.get(nid) == "RUNNING" for nid in self._dag.nodes):
            return "RUNNING"
        return "RUNNING"
