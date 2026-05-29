from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import replace
from datetime import datetime, timezone
from pathlib import Path
from typing import Sequence

from dbay_branch_version.cleanup import CleanupRegistry, cleanup_benchmark_resources
from dbay_branch_version.config import BenchmarkConfig, load_config, validate_config
from dbay_branch_version.dbay_client import DbayClient


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Run the DBay branch and version benchmark harness."
    )
    parser.add_argument("--config", required=True, help="Path to the benchmark config YAML.")
    parser.add_argument("--datasets", default="", help="Comma-separated dataset list, such as S,M.")
    parser.add_argument(
        "--allow-large-dataset",
        action="store_true",
        help="Allow dataset L when selected explicitly.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Print the benchmark plan without running it.",
    )
    parser.add_argument(
        "--cleanup-only",
        default="",
        metavar="BENCH_ID",
        help="Clean up resources for an interrupted run.",
    )
    return parser


def build_parser() -> argparse.ArgumentParser:
    return build_arg_parser()


def build_run_plan(
    config: BenchmarkConfig,
    datasets: str = "",
    allow_large_dataset: bool = False,
) -> dict[str, object]:
    updated = _apply_cli_overrides(config, datasets, allow_large_dataset)
    return {
        "profile": updated.profile,
        "datasets": list(updated.datasets),
        "resource_prefix": updated.resource_prefix,
        "will_create_database": True,
        "will_cleanup_database": True,
        "max_branch_concurrency": int(updated.limits.get("max_branch_concurrency", 10)),
    }


def create_result_dir(root: str | Path, bench_id: str, run_config: dict[str, object]) -> Path:
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    result_dir = Path(root) / f"{timestamp}_{bench_id}"
    result_dir.mkdir(parents=True, exist_ok=False)
    (result_dir / "run_config.json").write_text(
        json.dumps(run_config, indent=2, sort_keys=True),
        encoding="utf-8",
    )
    return result_dir


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_arg_parser()
    args = parser.parse_args(argv)

    config = load_config(args.config)
    plan = build_run_plan(config, args.datasets, args.allow_large_dataset)

    if args.cleanup_only:
        token = os.environ.get("DBAY_API_TOKEN")
        if not token:
            print("DBAY_API_TOKEN is required for --cleanup-only", file=sys.stderr)
            return 2

        registry_path = _find_cleanup_registry(Path(config.result_root), args.cleanup_only)
        registry = CleanupRegistry.read(registry_path)
        client = DbayClient(
            config.api_base_url,
            token,
            timeout_seconds=config.request_timeout_seconds,
        )
        try:
            status = cleanup_benchmark_resources(client, registry)
        finally:
            client.close()

        print(json.dumps(status, indent=2, sort_keys=True))
        return 0 if status["cleanup_status"] == "clean" else 2

    if args.dry_run:
        print(
            json.dumps(
                {
                    "bench_id": _make_bench_id(),
                    "plan": plan,
                },
                indent=2,
                sort_keys=True,
            )
        )
        return 0

    print(
        "Full benchmark orchestration is planned for Task 8; "
        "use --dry-run or --cleanup-only.",
        file=sys.stderr,
    )
    return 2


def _apply_cli_overrides(
    config: BenchmarkConfig,
    datasets_arg: str,
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


def _make_bench_id() -> str:
    return f"bench_{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}"


def _find_cleanup_registry(root: Path, bench_id: str) -> Path:
    direct = root / bench_id / "cleanup_registry.json"
    if direct.exists():
        return direct

    matches = sorted(root.glob(f"*{bench_id}/cleanup_registry.json"))
    if matches:
        return matches[-1]

    raise FileNotFoundError(f"No cleanup registry found for {bench_id!r} under {root}")


def _build_plan(config: BenchmarkConfig, cleanup_only: str | None) -> dict[str, object]:
    plan = build_run_plan(config)
    return {
        "dry_run": True,
        "profile": plan["profile"],
        "resource_prefix": plan["resource_prefix"],
        "api_base_url": config.api_base_url,
        "compute_size": config.compute_size,
        "datasets": plan["datasets"],
        "allow_large_dataset": config.allow_large_dataset,
        "result_root": config.result_root,
        "cleanup_only": cleanup_only,
        "limits": config.limits,
        "scenarios": config.scenarios,
    }


if __name__ == "__main__":
    raise SystemExit(main())
