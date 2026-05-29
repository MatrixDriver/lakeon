from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from dbay_branch_version.metrics import redact_secret


SENSITIVE_KEYS = {
    "api_token",
    "token",
    "password",
    "connection_uri",
    "connstr",
    "dsn",
}


VENDOR_CLAIMS = [
    {
        "vendor": "Neon",
        "area": "branching",
        "url": "https://neon.com/docs/introduction/point-in-time-restore",
        "comparison": "Partially comparable. DBay uses Neon-style timelines but includes DBay control-plane and compute lifecycle paths.",
    },
    {
        "vendor": "Neon",
        "area": "database versioning with snapshots",
        "url": "https://neon.com/docs/ai/ai-database-versioning",
        "comparison": "Conceptually comparable to DBay version create, but DBay uses its self-managed version API.",
    },
    {
        "vendor": "Xata",
        "area": "instant branching",
        "url": "https://xata.io/documentation/core-concepts",
        "comparison": "Partially comparable. Both position around copy-on-write branching; implementation differs.",
    },
    {
        "vendor": "Supabase",
        "area": "branching",
        "url": "https://supabase.com/docs/guides/deployment/branching",
        "comparison": "Not fully comparable. Supabase branches are complete preview environments.",
    },
    {
        "vendor": "PlanetScale",
        "area": "branching",
        "url": "https://planetscale.com/docs/concepts/branching",
        "comparison": "Not fully comparable. PlanetScale branching is primarily schema/deploy workflow.",
    },
]


def _redact_payload(value: Any) -> Any:
    if isinstance(value, dict):
        return {
            key: "[REDACTED]" if key.lower() in SENSITIVE_KEYS else _redact_payload(item)
            for key, item in value.items()
        }
    if isinstance(value, list):
        return [_redact_payload(item) for item in value]
    if isinstance(value, str):
        return redact_secret(value)
    return value


def _dump_redacted_json(payload: dict[str, Any]) -> str:
    return json.dumps(_redact_payload(payload), indent=2)


def render_comparison_markdown(
    bench_id: str,
    environment: dict[str, Any],
    summary: dict[str, Any],
    cleanup: dict[str, Any],
) -> str:
    lines = [
        "# DBay Branch and Version Benchmark Report",
        "",
        f"Bench ID: `{bench_id}`",
        "",
        "## Test Environment",
        "",
        "```json",
        _dump_redacted_json(environment),
        "```",
        "",
        "## DBay Measured Results",
        "",
        "```json",
        _dump_redacted_json(summary),
        "```",
        "",
        "## Correctness and Cleanup",
        "",
        "```json",
        _dump_redacted_json(cleanup),
        "```",
        "",
        "## Vendor Public Claims",
        "",
        "| Vendor | Area | Source | Comparison note |",
        "| --- | --- | --- | --- |",
    ]
    for claim in VENDOR_CLAIMS:
        lines.append(
            f"| {claim['vendor']} | {claim['area']} | {claim['url']} | {claim['comparison']} |"
        )
    lines.extend(
        [
            "",
            "## Interpretation",
            "",
            "Measured DBay results are production observations from this run. Vendor entries are public claims or product documentation and are not measured in this harness.",
            "",
            "## Raw Artifacts",
            "",
            "- [raw_samples.csv](raw_samples.csv)",
            "- [summary.json](summary.json)",
            "- [correctness.json](correctness.json)",
            "- [cleanup_status.json](cleanup_status.json)",
        ]
    )
    return "\n".join(lines) + "\n"


def write_comparison_report(
    path: str | Path,
    bench_id: str,
    environment: dict[str, Any],
    summary: dict[str, Any],
    cleanup: dict[str, Any],
) -> None:
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(
        render_comparison_markdown(bench_id, environment, summary, cleanup),
        encoding="utf-8",
    )
