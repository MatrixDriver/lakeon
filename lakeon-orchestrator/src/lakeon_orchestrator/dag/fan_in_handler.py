from __future__ import annotations

from typing import Any, Optional


class FanInHandler:
    """Handles N->1 merge when multiple parallel branches converge.

    Collects results from all upstream branches/fan-out expansions
    and merges them into a single input for the downstream step.
    """

    def merge(
        self,
        branch_results: list[dict[str, Any]],
        expected_branches: Optional[list[str]] = None,
    ) -> dict[str, Any]:
        """Merge results from multiple branches into a single collection.

        Args:
            branch_results: List of result dicts, each with "data" key.
            expected_branches: If provided, only include results from these branches.

        Returns:
            Dict with "items" key containing the merged data list.
        """
        items = []
        for result in branch_results:
            data = result.get("data")
            if expected_branches:
                branch = result.get("branch")
                if branch and branch not in expected_branches:
                    continue
            if data is not None:
                items.append(data)

        return {
            "__merged__": True,
            "items": items,
            "count": len(items),
        }

    @staticmethod
    def is_all_complete(
        step_statuses: dict[str, str], expected_step_ids: list[str]
    ) -> bool:
        """Check if all expected upstream steps have completed."""
        done = {"SUCCEEDED", "SKIPPED"}
        return all(step_statuses.get(sid) in done for sid in expected_step_ids)
