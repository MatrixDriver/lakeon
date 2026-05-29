from __future__ import annotations

import argparse
import json
import os
import sys
from dataclasses import replace
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from typing import Sequence

from dbay_branch_version.cleanup import CleanupRegistry, cleanup_benchmark_resources
from dbay_branch_version.config import BenchmarkConfig, ConfigError, load_config, validate_config
from dbay_branch_version.dbay_client import DbayApiError, DbayClient
from dbay_branch_version.metrics import (
    OperationSample,
    redact_secret,
    summarize_samples,
    write_raw_csv,
    write_summary_json,
)
from dbay_branch_version.pg_workload import fetch_checksums, load_dataset
from dbay_branch_version.report import write_comparison_report


class PsycopgWorkload:
    def load_dataset(self, connstr: str, dataset: str) -> None:
        load_dataset(connstr, dataset)

    def fetch_checksums(self, connstr: str) -> dict[str, str]:
        return fetch_checksums(connstr)


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

    try:
        config = load_config(
            args.config,
            allow_large_dataset_override=args.allow_large_dataset,
        )
        plan = build_run_plan(config, args.datasets, args.allow_large_dataset)
    except ConfigError as exc:
        print(str(exc), file=sys.stderr)
        return 2

    if args.cleanup_only:
        token = os.environ.get("DBAY_API_TOKEN")
        if not token:
            print("DBAY_API_TOKEN is required for --cleanup-only", file=sys.stderr)
            return 2

        try:
            registry_path = _find_cleanup_registry(Path(config.result_root), args.cleanup_only)
            registry = CleanupRegistry.read(registry_path)
        except FileNotFoundError as exc:
            print(f"Missing cleanup registry for {args.cleanup_only}: {exc}", file=sys.stderr)
            return 2

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

    token = os.environ.get("DBAY_API_TOKEN")
    if not token:
        print("DBAY_API_TOKEN is required", file=sys.stderr)
        return 2

    client = DbayClient(
        config.api_base_url,
        token,
        timeout_seconds=config.request_timeout_seconds,
    )
    try:
        try:
            result = run_benchmark_with_clients(
                config=config,
                plan=plan,
                result_root=Path(config.result_root),
                dbay=client,
                workload=PsycopgWorkload(),
            )
        except Exception as exc:
            print(
                json.dumps(
                    {
                        "benchmark_status": "failed",
                        "error": {
                            "type": exc.__class__.__name__,
                            "message": redact_secret(str(exc)),
                        },
                    },
                    indent=2,
                    sort_keys=True,
                ),
                file=sys.stderr,
            )
            return 2
    finally:
        client.close()

    print(
        json.dumps(
            {
                "bench_id": result["bench_id"],
                "result_dir": str(result["result_dir"]),
                "benchmark_status": result["benchmark_status"],
                "cleanup_status": result["cleanup_status"],
            },
            indent=2,
            sort_keys=True,
        )
    )
    if result["benchmark_status"] != "completed":
        return 2
    return 0 if result["cleanup_status"]["cleanup_status"] == "clean" else 2


def run_benchmark_with_clients(
    config: BenchmarkConfig,
    plan: dict[str, Any],
    result_root: str | Path,
    dbay,
    workload,
) -> dict[str, Any]:
    bench_id = _make_bench_id()
    timestamp_slug = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    database_name = config.make_database_name(timestamp_slug, "db")
    run_config = {
        "bench_id": bench_id,
        "plan": plan,
        "profile": config.profile,
        "resource_prefix": config.resource_prefix,
        "api_base_url": config.api_base_url,
        "compute_size": config.compute_size,
    }
    result_dir = create_result_dir(result_root, bench_id, run_config)
    samples: list[OperationSample] = []
    benchmark_status = "completed"
    benchmark_error: dict[str, str] | None = None
    correctness: dict[str, Any] = {
        "benchmark_status": benchmark_status,
        "error": None,
        "datasets": {},
    }
    registry = CleanupRegistry(
        bench_id=bench_id,
        database_id="",
        database_name=database_name,
        branches=[],
        versions=[],
    )
    cleanup_status: dict[str, Any] | None = None
    current_dataset = ""

    try:
        database, sample = dbay.create_database(database_name, config.compute_size)
        _append_sample(samples, sample, bench_id=bench_id)
        registry.database_id = database["id"]
        connstr = database.get("connection_uri", "")

        branches, sample = dbay.list_branches(registry.database_id)
        _append_sample(samples, sample, bench_id=bench_id)
        registry.branches.extend(_cleanup_branch_payload(branch) for branch in branches)
        main_branch = _select_main_branch(branches)

        for dataset in plan.get("datasets", []):
            dataset_name = str(dataset)
            current_dataset = dataset_name
            workload.load_dataset(connstr, dataset_name)
            base_checksums = workload.fetch_checksums(connstr)
            correctness["datasets"][dataset_name] = {"base_checksums": base_checksums}

            branch_name = f"{bench_id}-{dataset_name.lower()}-branch"
            branch, sample = dbay.create_branch(
                registry.database_id,
                branch_name,
                start_compute=True,
                parent_branch_id=main_branch.get("id"),
            )
            _append_sample(samples, sample, bench_id=bench_id, dataset=dataset_name)
            registry.branches.append(_cleanup_branch_payload(branch))

            version_name = f"{bench_id}-{dataset_name.lower()}-version"
            version, sample = dbay.create_version(
                registry.database_id,
                main_branch["id"],
                version_name,
                description=f"{bench_id} {dataset_name} baseline",
            )
            _append_sample(samples, sample, bench_id=bench_id, dataset=dataset_name)
            registry.versions.append(_cleanup_version_payload(version, main_branch["id"]))
            correctness["datasets"][dataset_name]["version_metadata_present"] = all(
                bool(version.get(key))
                for key in ["id", "branch_id", "lsn", "snapshot_timeline_id"]
            )
    except DbayApiError as exc:
        _append_sample(
            samples,
            exc.sample,
            bench_id=bench_id,
            dataset=current_dataset,
        )
        benchmark_status = "failed"
        benchmark_error = _benchmark_error(exc)
    except Exception as exc:
        benchmark_status = "failed"
        benchmark_error = _benchmark_error(exc)
    finally:
        correctness["benchmark_status"] = benchmark_status
        correctness["error"] = benchmark_error
        registry.write(result_dir / "cleanup_registry.json")
        if registry.database_id:
            cleanup_status = cleanup_benchmark_resources(dbay, registry)
        else:
            cleanup_status = {
                "bench_id": bench_id,
                "database_id": "",
                "database_name": database_name,
                "cleanup_status": "failed",
                "failures": [
                    {
                        "type": "database",
                        "id": None,
                        "error": "no_database_created",
                    }
                ],
            }
        (result_dir / "cleanup_status.json").write_text(
            json.dumps(cleanup_status, indent=2, sort_keys=True),
            encoding="utf-8",
        )

        write_raw_csv(result_dir / "raw_samples.csv", samples)
        summary = summarize_samples(samples)
        write_summary_json(result_dir / "summary.json", samples)
        (result_dir / "correctness.json").write_text(
            json.dumps(correctness, indent=2, sort_keys=True),
            encoding="utf-8",
        )
        write_comparison_report(
            result_dir / "comparison.md",
            bench_id=bench_id,
            environment={
                "api_base_url": config.api_base_url,
                "profile": config.profile,
                "compute_size": config.compute_size,
                "datasets": list(plan.get("datasets", [])),
                "benchmark_status": benchmark_status,
                "error": benchmark_error,
            },
            summary=summary,
            cleanup={
                "benchmark_status": benchmark_status,
                "error": benchmark_error,
                "cleanup": cleanup_status,
            },
        )

    return {
        "bench_id": bench_id,
        "result_dir": result_dir,
        "benchmark_status": benchmark_status,
        "error": benchmark_error,
        "cleanup_status": cleanup_status,
    }


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


def _append_sample(
    samples: list[OperationSample],
    sample: OperationSample | None,
    bench_id: str,
    dataset: str = "",
) -> None:
    if sample is None:
        return
    sample.bench_id = bench_id
    if dataset:
        sample.dataset = dataset
    samples.append(sample)


def _cleanup_branch_payload(branch: dict[str, Any]) -> dict[str, Any]:
    return {
        "id": branch["id"],
        "name": branch.get("name", ""),
        "is_default": bool(branch.get("is_default", False)),
    }


def _cleanup_version_payload(version: dict[str, Any], fallback_branch_id: str) -> dict[str, Any]:
    return {
        "id": version["id"],
        "branch_id": version.get("branch_id") or fallback_branch_id,
    }


def _benchmark_error(exc: Exception) -> dict[str, str]:
    return {
        "type": exc.__class__.__name__,
        "message": redact_secret(str(exc)),
    }


def _select_main_branch(branches: list[dict[str, Any]]) -> dict[str, Any]:
    for branch in branches:
        if branch.get("is_default") or branch.get("name") == "main":
            return branch
    if branches:
        return branches[0]
    raise RuntimeError("DBay database has no branches")


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
