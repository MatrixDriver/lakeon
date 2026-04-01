from __future__ import annotations

from typing import Any, Optional

from lakeon_orchestrator.dag.parser import DAG


class BranchRouter:
    """Routes classified items to the correct downstream step based on branch labels.

    When a component uses ctx.classify(item, label), the BranchRouter checks
    which downstream steps have a `condition` matching that label and routes
    the item accordingly.
    """

    def __init__(self, dag: DAG):
        self._dag = dag

    @staticmethod
    def is_branch_result(result: Any) -> bool:
        if not isinstance(result, dict):
            return False
        return "__branch__" in result

    def route(self, source_step_id: str, classify_result: dict[str, Any]) -> list[str]:
        """Determine which downstream steps should receive this classified item.

        Args:
            source_step_id: The step that produced the classification.
            classify_result: Dict with "__branch__" and "data" keys.

        Returns:
            List of downstream step IDs that match the branch label.
        """
        branch_label = classify_result.get("__branch__")
        if not branch_label:
            return []

        children = self._dag.get_children(source_step_id)
        matching = []
        for child_id in children:
            child_node = self._dag.nodes[child_id]
            if child_node.condition:
                # condition format: "source_step_id.branch_name"
                parts = child_node.condition.split(".")
                if len(parts) == 2 and parts[0] == source_step_id and parts[1] == branch_label:
                    matching.append(child_id)
            # merge nodes don't have conditions -- they collect from multiple sources
            # They are not directly routed to by branch results
        return matching

    def get_skipped_steps(
        self, source_step_id: str, active_branches: set[str]
    ) -> list[str]:
        """Identify downstream conditional steps that should be SKIPPED.

        Steps whose condition branch was never activated should be marked SKIPPED.

        Args:
            source_step_id: The branching step.
            active_branches: Set of branch labels that had at least one item.

        Returns:
            List of step IDs to skip.
        """
        children = self._dag.get_children(source_step_id)
        skipped = []
        for child_id in children:
            child_node = self._dag.nodes[child_id]
            if child_node.condition:
                parts = child_node.condition.split(".")
                if len(parts) == 2 and parts[0] == source_step_id:
                    branch_name = parts[1]
                    if branch_name not in active_branches:
                        skipped.append(child_id)
        return skipped
