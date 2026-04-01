from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Any, Optional


@dataclass
class ComponentContext:
    """Runtime context passed to every component function.

    Attributes:
        input: Upstream output data (dict of named values).
        params: User-configured parameters for this step.
    """

    step_id: str
    run_id: str
    input_data: dict[str, Any]
    params: dict[str, Any]

    # Populated by the component at runtime
    metrics: dict[str, Any] = field(default_factory=dict)
    logs: list[str] = field(default_factory=list)
    checkpoint_data: Optional[Any] = None

    @property
    def input(self) -> dict[str, Any]:
        return self.input_data

    def report(self, metrics: dict[str, Any]) -> None:
        """Report step-level metrics (e.g. input_count, output_count)."""
        self.metrics.update(metrics)

    def fan_out(self, items: list[Any]) -> dict[str, Any]:
        """Signal that this step produces N output items for fan-out expansion."""
        self.report({"output_count": len(items)})
        return {
            "__fan_out__": True,
            "items": items,
        }

    def classify(self, data: Any, label: str) -> dict[str, Any]:
        """Classify an item into a named branch for conditional routing."""
        return {
            "__branch__": label,
            "data": data,
        }

    def checkpoint(self, data: Any) -> None:
        """Mark intermediate data for async persistence to OBS."""
        self.checkpoint_data = data

    def log(self, msg: str) -> None:
        """Append a timestamped log message."""
        ts = datetime.now(timezone.utc).isoformat()
        self.logs.append(f"[{ts}] {msg}")
