from __future__ import annotations

import csv
import json
import re
import statistics
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable


SECRET_PATTERNS = [
    (re.compile(r"Bearer\s+[A-Za-z0-9._\-]+"), "Bearer [REDACTED]"),
    (re.compile(r"postgres(?:ql)?://[^@\s]+:[^@\s]+@[^,\s]+"), "postgresql://[REDACTED]"),
]


@dataclass
class OperationSample:
    bench_id: str
    dataset: str
    scenario: str
    operation: str
    resource_type: str = ""
    resource_id: str = ""
    concurrency: int = 1
    depth: int = 0
    attempt: int = 1
    started_at: str = ""
    ended_at: str = ""
    api_latency_ms: float | None = None
    visible_latency_ms: float | None = None
    ready_latency_ms: float | None = None
    connect_latency_ms: float | None = None
    cleanup_latency_ms: float | None = None
    http_status: int | None = None
    success: bool = True
    error_code: str = ""
    error_message: str = ""


def redact_secret(value: str | None) -> str:
    if not value:
        return ""
    text = value
    for pattern, replacement in SECRET_PATTERNS:
        text = pattern.sub(replacement, text)
    return text


def write_raw_csv(path: str | Path, samples: Iterable[OperationSample]) -> None:
    rows = []
    for sample in samples:
        row = asdict(sample)
        row["error_message"] = redact_secret(row.get("error_message"))
        rows.append(row)
    fieldnames = list(asdict(OperationSample(bench_id="", dataset="", scenario="", operation="")).keys())
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    with Path(path).open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


def write_summary_json(path: str | Path, samples: Iterable[OperationSample]) -> None:
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(json.dumps(summarize_samples(list(samples)), indent=2), encoding="utf-8")


def summarize_samples(samples: list[OperationSample]) -> dict[str, dict]:
    groups: dict[str, list[OperationSample]] = {}
    for sample in samples:
        key = f"{sample.scenario}/{sample.operation}/{sample.dataset}"
        groups.setdefault(key, []).append(sample)

    summary = {}
    for key, values in groups.items():
        summary[key] = {
            "sample_count": len(values),
            "success_count": sum(1 for value in values if value.success),
            "error_rate": sum(1 for value in values if not value.success) / len(values),
        }
        for field in [
            "api_latency_ms",
            "visible_latency_ms",
            "ready_latency_ms",
            "connect_latency_ms",
            "cleanup_latency_ms",
        ]:
            latencies = [getattr(value, field) for value in values if getattr(value, field) is not None]
            if latencies:
                summary[key][field] = latency_stats(latencies)
    return summary


def latency_stats(values: list[float]) -> dict[str, float]:
    sorted_values = sorted(values)
    return {
        "min": sorted_values[0],
        "max": sorted_values[-1],
        "p50": percentile(sorted_values, 50),
        "p95": percentile(sorted_values, 95),
        "p99": percentile(sorted_values, 99),
        "stddev": statistics.pstdev(sorted_values) if len(sorted_values) > 1 else 0.0,
    }


def percentile(sorted_values: list[float], pct: int) -> float:
    if not sorted_values:
        raise ValueError("percentile requires at least one value")
    index = (len(sorted_values) - 1) * (pct / 100)
    lower = int(index)
    upper = min(lower + 1, len(sorted_values) - 1)
    weight = index - lower
    return sorted_values[lower] * (1 - weight) + sorted_values[upper] * weight
