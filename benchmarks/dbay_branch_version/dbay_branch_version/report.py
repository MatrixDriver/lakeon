from __future__ import annotations

import json
from pathlib import Path
from typing import Any


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
        json.dumps(environment, indent=2),
        "```",
        "",
        "## DBay Measured Results",
        "",
        "```json",
        json.dumps(summary, indent=2),
        "```",
        "",
        "## Correctness and Cleanup",
        "",
        "```json",
        json.dumps(cleanup, indent=2),
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
            "- `raw_samples.csv`",
            "- `summary.json`",
            "- `correctness.json`",
            "- `cleanup_status.json`",
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
