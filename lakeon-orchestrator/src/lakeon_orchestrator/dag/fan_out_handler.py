from __future__ import annotations

from typing import Any, Optional


class FanOutHandler:
    """Handles 1->N expansion when a component returns fan_out results.

    When a step calls ctx.fan_out(items), the Orchestrator:
    1. Creates N new step_runs for the downstream step (one per item)
    2. Each step_run receives one item as input
    3. All run in parallel on the Ray cluster
    """

    @staticmethod
    def is_fan_out(result: Any) -> bool:
        """Check if a component result signals fan-out."""
        if not isinstance(result, dict):
            return False
        return result.get("__fan_out__") is True

    @staticmethod
    def generate_step_run_id(run_id: str, step_id: str, index: int) -> str:
        return f"sr_{run_id}_{step_id}_{index}"

    def expand(
        self,
        fan_out_result: dict[str, Any],
        source_step_id: str,
        downstream_step_id: str,
        run_id: str,
    ) -> list[dict[str, Any]]:
        """Expand a fan-out result into individual step run descriptors.

        Returns a list of dicts, each containing:
        - step_run_id: unique ID for the expanded step run
        - step_id: the downstream step ID (with index suffix)
        - input_data: the individual item
        - index: the item index
        """
        items = fan_out_result.get("items", [])
        expanded = []
        for i, item in enumerate(items):
            expanded.append({
                "step_run_id": self.generate_step_run_id(run_id, downstream_step_id, i),
                "step_id": f"{downstream_step_id}",
                "input_data": item,
                "index": i,
                "source_step_id": source_step_id,
            })
        return expanded
