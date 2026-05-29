from __future__ import annotations

import argparse
import json
import sys
from dataclasses import replace
from pathlib import Path
from typing import Sequence

from dbay_branch_version.config import BenchmarkConfig, load_config, validate_config


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run the DBay branch and version benchmark harness.")
    parser.add_argument("--config", required=True, type=Path, help="Path to the benchmark config YAML.")
    parser.add_argument("--datasets", help="Comma-separated dataset list, such as S,M.")
    parser.add_argument(
        "--allow-large-dataset",
        action="store_true",
        help="Allow dataset L when selected explicitly.",
    )
    parser.add_argument("--dry-run", action="store_true", help="Print the benchmark plan without running it.")
    parser.add_argument("--cleanup-only", metavar="BENCH_ID", help="Clean up resources for an interrupted run.")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)

    config = load_config(args.config)
    if args.datasets or args.allow_large_dataset:
        config = _apply_cli_overrides(config, args.datasets, args.allow_large_dataset)

    if args.dry_run:
        print(json.dumps(_build_plan(config, args.cleanup_only), indent=2, sort_keys=True))
        return 0

    print("Full benchmark orchestration is implemented in Task 8", file=sys.stderr)
    return 2


def _apply_cli_overrides(
    config: BenchmarkConfig,
    datasets_arg: str | None,
    allow_large_dataset: bool,
) -> BenchmarkConfig:
    datasets = config.datasets
    if datasets_arg:
        datasets = tuple(value.strip() for value in datasets_arg.split(",") if value.strip())
    updated = replace(
        config,
        datasets=datasets,
        allow_large_dataset=config.allow_large_dataset or allow_large_dataset,
    )
    validate_config(updated)
    return updated


def _build_plan(config: BenchmarkConfig, cleanup_only: str | None) -> dict[str, object]:
    return {
        "dry_run": True,
        "profile": config.profile,
        "resource_prefix": config.resource_prefix,
        "api_base_url": config.api_base_url,
        "compute_size": config.compute_size,
        "datasets": list(config.datasets),
        "allow_large_dataset": config.allow_large_dataset,
        "result_root": config.result_root,
        "cleanup_only": cleanup_only,
        "limits": config.limits,
        "scenarios": config.scenarios,
    }


if __name__ == "__main__":
    raise SystemExit(main())
