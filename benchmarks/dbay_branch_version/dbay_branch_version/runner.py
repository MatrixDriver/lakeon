from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import replace
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from typing import Sequence
from urllib.parse import quote
from urllib.parse import urlsplit
from urllib.parse import urlunsplit

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
from dbay_branch_version.pg_workload import (
    DATASETS,
    dataset_row_counts,
    execute_isolation_insert,
    fetch_checksums,
    fetch_row_counts,
    load_dataset,
    marker_count,
)
from dbay_branch_version.report import write_comparison_report


class PreflightError(RuntimeError):
    """Raised when benchmark preflight fails before resource creation."""


class CorrectnessError(RuntimeError):
    """Raised when benchmark correctness checks fail."""


class LimitExceededError(RuntimeError):
    """Raised when configured resource/runtime limits would be exceeded."""


class PsycopgWorkload:
    def load_dataset(self, connstr: str, dataset: str) -> None:
        load_dataset(connstr, dataset)

    def fetch_checksums(self, connstr: str) -> dict[str, str]:
        return fetch_checksums(connstr)

    def fetch_row_counts(self, connstr: str) -> dict[str, int]:
        return fetch_row_counts(connstr)

    def execute_isolation_insert(self, connstr: str, marker: str) -> None:
        execute_isolation_insert(connstr, marker)

    def marker_count(self, connstr: str, marker: str) -> int:
        return marker_count(connstr, marker)


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
    return _plan_from_config(updated)


def _plan_from_config(config: BenchmarkConfig) -> dict[str, object]:
    return {
        "profile": config.profile,
        "datasets": list(config.datasets),
        "resource_prefix": config.resource_prefix,
        "will_create_database": True,
        "will_cleanup_database": True,
        "max_branch_concurrency": int(config.limits.get("max_branch_concurrency", 10)),
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
        config = _apply_cli_overrides(config, args.datasets, args.allow_large_dataset)
        plan = _plan_from_config(config)
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
        preflight = run_preflight(config, dry_run=True)
        print(
            json.dumps(
                {
                    "bench_id": _make_bench_id(),
                    "plan": plan,
                    "preflight": preflight,
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
                "error": result.get("error"),
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
        "preflight": None,
        "datasets": {},
        "checks": [],
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
    start_time = time.monotonic()
    counters = {"branches": 0, "versions": 0}
    created_versions_by_branch: dict[str, list[dict[str, Any]]] = {}

    try:
        preflight = run_preflight(config, dbay=dbay, workload=workload, dry_run=False)
        correctness["preflight"] = preflight
        if preflight["status"] != "passed":
            raise PreflightError(preflight.get("error") or "preflight failed")

        database, sample = dbay.create_database(database_name, config.compute_size)
        _append_sample(samples, sample, bench_id=bench_id, scenario="database", operation="create")
        registry.database_id = database["id"]
        database_password = str(database.get("password") or "")
        connstr = _connection_uri_with_password(
            database.get("connection_uri", ""),
            database_password,
        )

        database = _wait_for_database_branches(
            config=config,
            bench_id=bench_id,
            dbay=dbay,
            database_id=registry.database_id,
            samples=samples,
        )
        ready_connstr = _connection_uri_with_password(
            database.get("connection_uri", ""),
            database_password,
        )
        connstr = ready_connstr or connstr

        branches, sample = dbay.list_branches(registry.database_id)
        _append_sample(samples, sample, bench_id=bench_id, scenario="branch", operation="list")
        registry.branches.extend(_cleanup_branch_payload(branch) for branch in branches)
        main_branch = _select_main_branch(branches)

        for dataset in plan.get("datasets", []):
            dataset_name = str(dataset)
            current_dataset = dataset_name
            workload.load_dataset(connstr, dataset_name)
            base_checksums = workload.fetch_checksums(connstr)
            expected_row_counts = dataset_row_counts(dataset_name)
            actual_row_counts = (
                workload.fetch_row_counts(connstr)
                if hasattr(workload, "fetch_row_counts")
                else None
            )
            correctness["datasets"][dataset_name] = {
                "expected_row_counts": expected_row_counts,
                "base_checksums": base_checksums,
            }
            if actual_row_counts is not None:
                correctness["datasets"][dataset_name]["actual_row_counts"] = actual_row_counts
            _add_check(
                correctness,
                f"base_row_count_expectation:{dataset_name}",
                actual_row_counts == expected_row_counts
                if actual_row_counts is not None
                else (
                    set(expected_row_counts) == set(base_checksums)
                    and all(value > 0 for value in expected_row_counts.values())
                ),
                {
                    "expected_row_counts": expected_row_counts,
                    "actual_row_counts": actual_row_counts,
                    "checksum_tables": sorted(base_checksums),
                },
            )

            _run_scenarios(
                config=config,
                plan={**plan, "datasets": [dataset_name]},
                bench_id=bench_id,
                dbay=dbay,
                workload=workload,
                registry=registry,
                main_branch=main_branch,
                main_connstr=connstr,
                samples=samples,
                correctness=correctness,
                counters=counters,
                start_time=start_time,
                created_versions_by_branch=created_versions_by_branch,
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
        registry.write(result_dir / "cleanup_registry.json")
        if registry.database_id:
            cleanup_status = cleanup_benchmark_resources(dbay, registry)
        else:
            cleanup_status = {
                "bench_id": bench_id,
                "database_id": "",
                "database_name": database_name,
                "cleanup_status": "clean",
                "failures": [],
                "note": "no_database_created",
            }
        _add_check(
            correctness,
            "cleanup_status",
            cleanup_status.get("cleanup_status") == "clean",
            cleanup_status,
        )
        if benchmark_status == "completed":
            failed_checks = [
                check for check in correctness["checks"] if check.get("passed") is False
            ]
            if failed_checks:
                benchmark_status = "failed"
                benchmark_error = {
                    "type": "CorrectnessError",
                    "message": f"{len(failed_checks)} correctness check(s) failed",
                }

        correctness["benchmark_status"] = benchmark_status
        correctness["error"] = benchmark_error
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


def run_preflight(
    config: BenchmarkConfig,
    dbay=None,
    workload=None,
    dry_run: bool = False,
) -> dict[str, Any]:
    timestamp = datetime.now(timezone.utc)
    result: dict[str, Any] = {
        "status": "passed",
        "timestamp": timestamp.isoformat(),
        "dry_run": dry_run,
        "datasets": {"status": "passed", "values": list(config.datasets)},
        "limits": {"status": "passed"},
        "api": {"status": "not_requested"},
    }

    errors: list[str] = []
    if timestamp.year < 2020:
        errors.append("local clock timestamp is invalid")

    unknown = [dataset for dataset in config.datasets if dataset not in DATASETS]
    if unknown:
        errors.append(f"unknown datasets: {', '.join(unknown)}")
        result["datasets"]["status"] = "failed"
        result["datasets"]["unknown"] = unknown

    for key in [
        "max_branch_concurrency",
        "max_total_branches",
        "max_total_versions",
        "max_runtime_seconds",
    ]:
        value = int(config.limits.get(key, 0))
        result["limits"][key] = value
        if value <= 0:
            errors.append(f"{key} must be positive")
            result["limits"]["status"] = "failed"

    if dbay is not None:
        try:
            dbay.list_databases()
            result["api"] = {"status": "passed"}
        except Exception as exc:
            result["api"] = {
                "status": "failed",
                "error": redact_secret(str(exc)),
            }
            errors.append(f"api preflight failed: {redact_secret(str(exc))}")
    elif dry_run and not os.environ.get("DBAY_API_TOKEN"):
        result["api"] = {"status": "skipped_no_token"}
    elif dry_run:
        result["api"] = {"status": "skipped_dry_run"}

    if errors:
        result["status"] = "failed"
        result["error"] = "; ".join(errors)
    return result


def _run_scenarios(
    config: BenchmarkConfig,
    plan: dict[str, Any],
    bench_id: str,
    dbay,
    workload,
    registry: CleanupRegistry,
    main_branch: dict[str, Any],
    main_connstr: str,
    samples: list[OperationSample],
    correctness: dict[str, Any],
    counters: dict[str, int],
    start_time: float,
    created_versions_by_branch: dict[str, list[dict[str, Any]]],
) -> None:
    scenarios = _effective_scenarios(config.scenarios)
    datasets = [str(dataset) for dataset in plan.get("datasets", [])]

    without_compute = scenarios.get("branch_create_without_compute", {})
    for dataset in datasets:
        for index in range(int(without_compute.get("samples_per_dataset", 0))):
            _create_branch_sample(
                config,
                bench_id,
                dbay,
                registry,
                main_branch.get("id"),
                samples,
                counters,
                start_time,
                dataset,
                "branch_create_without_compute",
                index,
                start_compute=False,
            )

    with_compute = scenarios.get("branch_create_with_compute", {})
    isolation_checked = False
    for dataset in datasets:
        base_checksums = correctness["datasets"][dataset]["base_checksums"]
        for index in range(int(with_compute.get("samples_per_dataset", 0))):
            branch = _create_branch_sample(
                config,
                bench_id,
                dbay,
                registry,
                main_branch.get("id"),
                samples,
                counters,
                start_time,
                dataset,
                "branch_create_with_compute",
                index,
                start_compute=True,
            )
            if isolation_checked:
                continue
            branch_connstr = branch.get("connection_uri", "")
            branch_connstr = _connection_uri_with_password(
                branch_connstr,
                _password_from_connection_uri(main_connstr),
            )
            if branch_connstr:
                branch_checksums = workload.fetch_checksums(branch_connstr)
                _add_check(
                    correctness,
                    f"branch_fork_checksum:{dataset}:{index}",
                    branch_checksums == base_checksums,
                    {"branch_id": branch.get("id"), "base": base_checksums, "branch": branch_checksums},
                )
                if not isolation_checked:
                    _check_branch_isolation(
                        correctness,
                        workload,
                        main_connstr,
                        branch_connstr,
                        bench_id,
                        dataset,
                    )
                    isolation_checked = True
            else:
                _add_check(
                    correctness,
                    f"branch_fork_checksum:{dataset}:{index}",
                    None,
                    {"branch_id": branch.get("id"), "reason": "branch has no connection_uri"},
                )
                if not isolation_checked:
                    _add_check(
                        correctness,
                        f"branch_isolation:{dataset}",
                        None,
                        {"reason": "start_compute branch has no connection_uri"},
                    )
                    isolation_checked = True

    concurrent = scenarios.get("branch_create_concurrent", {})
    total_samples = int(concurrent.get("total_samples", 0))
    concurrencies = [int(value) for value in concurrent.get("concurrency", [])]
    for concurrency in concurrencies:
        _run_concurrent_branch_creates(
            config,
            bench_id,
            dbay,
            registry,
            main_branch.get("id"),
            samples,
            counters,
            start_time,
            datasets,
            total_samples,
            concurrency,
        )

    depth_config = scenarios.get("branch_depth", {})
    for dataset in datasets:
        for depth in [int(value) for value in depth_config.get("depths", [])]:
            parent_branch_id = main_branch.get("id")
            for index in range(depth):
                branch = _create_branch_sample(
                    config,
                    bench_id,
                    dbay,
                    registry,
                    parent_branch_id,
                    samples,
                    counters,
                    start_time,
                    dataset,
                    "branch_depth",
                    index,
                    start_compute=False,
                    depth=depth,
                    resource_scenario=f"branch_depth_{depth}",
                )
                parent_branch_id = branch.get("id")

    version_create = scenarios.get("version_create", {})
    read_versions: list[dict[str, Any]] = []
    for dataset in datasets:
        for index in range(int(version_create.get("samples_per_dataset", 0))):
            version = _create_version_sample(
                config,
                bench_id,
                dbay,
                registry,
                main_branch["id"],
                samples,
                counters,
                start_time,
                dataset,
                "version_create",
                index,
            )
            created_versions_by_branch.setdefault(main_branch["id"], []).append(version)
            read_versions.append(version)
            _check_version_metadata(correctness, version)

    version_read = scenarios.get("version_read", {})
    _run_version_reads(
        bench_id,
        dbay,
        registry.database_id,
        main_branch["id"],
        samples,
        correctness,
        read_versions,
        int(version_read.get("samples", 0)),
        int(version_read.get("concurrency", 1)),
        datasets[0] if len(datasets) == 1 else "",
    )

    version_squash = scenarios.get("version_squash", {})
    groups = int(version_squash.get("groups", 0))
    versions_per_group = int(version_squash.get("versions_per_group", 0))
    for group_index in range(groups):
        group_versions = []
        dataset = datasets[group_index % len(datasets)] if datasets else ""
        for version_index in range(versions_per_group):
            version = _create_version_sample(
                config,
                bench_id,
                dbay,
                registry,
                main_branch["id"],
                samples,
                counters,
                start_time,
                dataset,
                "version_squash",
                group_index * versions_per_group + version_index,
            )
            group_versions.append(version)
            created_versions_by_branch.setdefault(main_branch["id"], []).append(version)
            _check_version_metadata(correctness, version)
        if len(group_versions) >= 2:
            remaining, sample = dbay.squash_versions(
                registry.database_id,
                registry.database_name,
                main_branch["id"],
                group_versions[0]["id"],
                group_versions[-1]["id"],
            )
            _append_sample(
                samples,
                sample,
                bench_id=bench_id,
                dataset=dataset,
                scenario="version_squash",
                operation="squash",
                resource_type="version",
                resource_id=group_versions[-1]["id"],
            )
            _check_squash_correctness(
                correctness,
                remaining,
                registry,
                main_branch["id"],
                group_versions,
                group_index,
            )


def _effective_scenarios(scenarios: dict[str, Any]) -> dict[str, Any]:
    if scenarios:
        return scenarios
    return {
        "branch_create_with_compute": {"samples_per_dataset": 1},
        "version_create": {"samples_per_dataset": 1},
    }


def _wait_for_database_branches(
    config: BenchmarkConfig,
    bench_id: str,
    dbay,
    database_id: str,
    samples: list[OperationSample],
) -> dict[str, Any]:
    deadline = time.monotonic() + config.poll_timeout_seconds
    last_database: dict[str, Any] = {}
    while True:
        database, sample = dbay.get_database(database_id)
        _append_sample(
            samples,
            sample,
            bench_id=bench_id,
            scenario="database",
            operation="get",
            resource_type="database",
            resource_id=database_id,
        )
        last_database = database
        branches = database.get("branches")
        if isinstance(branches, list) and branches:
            return database
        status = str(database.get("status", "")).upper()
        if status == "ERROR":
            raise RuntimeError(
                f"DBay database entered ERROR before default branch was available: "
                f"{database.get('status_message') or database.get('statusMessage') or ''}"
            )
        if time.monotonic() >= deadline:
            raise TimeoutError(
                "Timed out waiting for DBay database default branch; "
                f"last_status={database.get('status')!r}, branches={branches!r}"
            )
        time.sleep(config.poll_interval_seconds)


def _password_from_connection_uri(connstr: str) -> str:
    if not connstr:
        return ""
    try:
        return urlsplit(connstr).password or ""
    except ValueError:
        return ""


def _connection_uri_with_password(connstr: str, password: str) -> str:
    if not connstr or not password:
        return connstr
    try:
        parsed = urlsplit(connstr)
    except ValueError:
        return connstr
    if parsed.password or not parsed.username or not parsed.hostname:
        return connstr
    host = parsed.hostname
    if ":" in host and not host.startswith("["):
        host = f"[{host}]"
    if parsed.port is not None:
        host = f"{host}:{parsed.port}"
    username = quote(parsed.username, safe="")
    encoded_password = quote(password, safe="")
    return urlunsplit(
        (
            parsed.scheme,
            f"{username}:{encoded_password}@{host}",
            parsed.path,
            parsed.query,
            parsed.fragment,
        )
    )


def _create_branch_sample(
    config: BenchmarkConfig,
    bench_id: str,
    dbay,
    registry: CleanupRegistry,
    parent_branch_id: str | None,
    samples: list[OperationSample],
    counters: dict[str, int],
    start_time: float,
    dataset: str,
    scenario: str,
    index: int,
    start_compute: bool,
    concurrency: int = 1,
    depth: int = 0,
    resource_scenario: str | None = None,
) -> dict[str, Any]:
    _enforce_limits(config, counters, start_time, next_branches=1)
    branch_name = _resource_name(bench_id, dataset, resource_scenario or scenario, index)
    branch, sample = dbay.create_branch(
        registry.database_id,
        branch_name,
        start_compute=start_compute,
        parent_branch_id=parent_branch_id,
    )
    counters["branches"] += 1
    _append_sample(
        samples,
        sample,
        bench_id=bench_id,
        dataset=dataset,
        scenario=scenario,
        operation="create",
        resource_type="branch",
        resource_id=branch.get("id", ""),
        concurrency=concurrency,
        depth=depth,
    )
    registry.branches.append(_cleanup_branch_payload(branch))
    return branch


def _run_concurrent_branch_creates(
    config: BenchmarkConfig,
    bench_id: str,
    dbay,
    registry: CleanupRegistry,
    parent_branch_id: str | None,
    samples: list[OperationSample],
    counters: dict[str, int],
    start_time: float,
    datasets: list[str],
    total_samples: int,
    concurrency: int,
) -> None:
    _enforce_limits(config, counters, start_time, next_branches=total_samples)

    def create_one(index: int) -> tuple[str, dict[str, Any], OperationSample | None]:
        dataset = datasets[index % len(datasets)] if datasets else ""
        branch_name = _resource_name(
            bench_id,
            dataset,
            f"branch_create_concurrent_{concurrency}",
            index,
        )
        branch, sample = dbay.create_branch(
            registry.database_id,
            branch_name,
            start_compute=False,
            parent_branch_id=parent_branch_id,
        )
        return dataset, branch, sample

    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = [executor.submit(create_one, index) for index in range(total_samples)]
        first_exception: Exception | None = None
        for future in as_completed(futures):
            try:
                dataset, branch, sample = future.result()
            except DbayApiError as exc:
                _append_sample(
                    samples,
                    exc.sample,
                    bench_id=bench_id,
                    scenario="branch_create_concurrent",
                    operation="create",
                )
                first_exception = first_exception or exc
                continue
            except Exception as exc:
                first_exception = first_exception or exc
                continue
            counters["branches"] += 1
            _append_sample(
                samples,
                sample,
                bench_id=bench_id,
                dataset=dataset,
                scenario="branch_create_concurrent",
                operation="create",
                resource_type="branch",
                resource_id=branch.get("id", ""),
                concurrency=concurrency,
            )
            registry.branches.append(_cleanup_branch_payload(branch))
        if first_exception is not None:
            raise first_exception


def _create_version_sample(
    config: BenchmarkConfig,
    bench_id: str,
    dbay,
    registry: CleanupRegistry,
    branch_id: str,
    samples: list[OperationSample],
    counters: dict[str, int],
    start_time: float,
    dataset: str,
    scenario: str,
    index: int,
) -> dict[str, Any]:
    _enforce_limits(config, counters, start_time, next_versions=1)
    version_name = _resource_name(bench_id, dataset, scenario, index)
    version, sample = dbay.create_version(
        registry.database_id,
        branch_id,
        version_name,
        description=f"{bench_id} {dataset} {scenario}",
    )
    counters["versions"] += 1
    _append_sample(
        samples,
        sample,
        bench_id=bench_id,
        dataset=dataset,
        scenario=scenario,
        operation="create",
        resource_type="version",
        resource_id=version.get("id", ""),
    )
    registry.versions.append(_cleanup_version_payload(version, branch_id))
    return version


def _run_version_reads(
    bench_id: str,
    dbay,
    database_id: str,
    branch_id: str,
    samples: list[OperationSample],
    correctness: dict[str, Any],
    created_versions: list[dict[str, Any]],
    sample_count: int,
    concurrency: int,
    dataset: str,
) -> None:
    if sample_count <= 0:
        return
    concurrency = max(1, concurrency)
    versions, sample = dbay.list_versions(database_id, branch_id)
    _append_sample(
        samples,
        sample,
        bench_id=bench_id,
        dataset=dataset,
        scenario="version_read",
        operation="list",
        resource_type="version",
    )
    listed_ids = {version.get("id") for version in versions}
    expected_ids = {version.get("id") for version in created_versions}
    _add_check(
        correctness,
        "version_list_retrieval",
        expected_ids.issubset(listed_ids),
        {"expected_ids": sorted(expected_ids), "listed_ids": sorted(listed_ids)},
    )
    if not created_versions:
        _add_check(
            correctness,
            "version_get_retrieval",
            False,
            {"reason": "no created versions available for version_read"},
        )
        return

    read_targets = [
        created_versions[index % len(created_versions)] for index in range(sample_count)
    ]

    def fetch_one(target: dict[str, Any]) -> tuple[dict[str, Any], OperationSample | None, str]:
        fetched, read_sample = dbay.get_version(database_id, branch_id, target["id"])
        return fetched, read_sample, target["id"]

    first_exception: Exception | None = None
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = [executor.submit(fetch_one, target) for target in read_targets]
        for future in as_completed(futures):
            try:
                fetched, sample, expected_id = future.result()
            except DbayApiError as exc:
                _append_sample(
                    samples,
                    exc.sample,
                    bench_id=bench_id,
                    dataset=dataset,
                    scenario="version_read",
                    operation="get",
                    resource_type="version",
                    concurrency=concurrency,
                )
                first_exception = first_exception or exc
                continue
            except Exception as exc:
                first_exception = first_exception or exc
                continue
            _append_sample(
                samples,
                sample,
                bench_id=bench_id,
                dataset=dataset,
                scenario="version_read",
                operation="get",
                resource_type="version",
                resource_id=expected_id,
                concurrency=concurrency,
            )
            _add_check(
                correctness,
                f"version_get_retrieval:{expected_id}",
                fetched.get("id") == expected_id,
                {"expected_id": expected_id, "fetched_id": fetched.get("id")},
            )
    if first_exception is not None:
        raise first_exception


def _check_version_metadata(correctness: dict[str, Any], version: dict[str, Any]) -> None:
    required = ["id", "branch_id", "lsn", "snapshot_timeline_id", "created_at"]
    missing = [key for key in required if not version.get(key)]
    _add_check(
        correctness,
        "version_metadata",
        not missing,
        {"version_id": version.get("id"), "missing": missing, "required": required},
    )


def _check_squash_correctness(
    correctness: dict[str, Any],
    remaining: Any,
    registry: CleanupRegistry,
    branch_id: str,
    group_versions: list[dict[str, Any]],
    group_index: int,
) -> None:
    if not isinstance(remaining, list):
        _add_check(
            correctness,
            f"version_squash:{group_index}",
            None,
            {"reason": "squash response was not a list"},
        )
        return
    remaining_ids = {version.get("id") for version in remaining}
    middle_ids = {version["id"] for version in group_versions[1:-1]}
    endpoint_ids = {group_versions[0]["id"], group_versions[-1]["id"]}
    unrelated_group_ids = {
        version.get("id")
        for version in registry.versions
        if version.get("branch_id") == branch_id
        and version.get("id") not in middle_ids
        and version.get("id") not in endpoint_ids
    }
    _add_check(
        correctness,
        f"version_squash:{group_index}",
        middle_ids.isdisjoint(remaining_ids)
        and endpoint_ids.issubset(remaining_ids)
        and unrelated_group_ids.issubset(remaining_ids),
        {
            "middle_ids": sorted(middle_ids),
            "endpoint_ids": sorted(endpoint_ids),
            "unrelated_version_ids": sorted(unrelated_group_ids),
            "remaining_ids": sorted(remaining_ids),
        },
    )
    registry.versions = [
        version
        for version in registry.versions
        if version.get("branch_id") != branch_id
        or version.get("id") in remaining_ids
        or version.get("id") not in middle_ids
    ]


def _check_branch_isolation(
    correctness: dict[str, Any],
    workload,
    main_connstr: str,
    branch_connstr: str,
    bench_id: str,
    dataset: str,
) -> None:
    if not (
        hasattr(workload, "execute_isolation_insert")
        and hasattr(workload, "marker_count")
    ):
        _add_check(
            correctness,
            f"branch_isolation:{dataset}",
            None,
            {"reason": "workload does not expose isolation helpers"},
        )
        return

    parent_marker = f"{bench_id}-{dataset}-parent"
    child_marker = f"{bench_id}-{dataset}-child"
    workload.execute_isolation_insert(main_connstr, parent_marker)
    workload.execute_isolation_insert(branch_connstr, child_marker)
    details = {
        "main_parent_count": workload.marker_count(main_connstr, parent_marker),
        "main_child_count": workload.marker_count(main_connstr, child_marker),
        "branch_parent_count": workload.marker_count(branch_connstr, parent_marker),
        "branch_child_count": workload.marker_count(branch_connstr, child_marker),
    }
    _add_check(
        correctness,
        f"branch_isolation:{dataset}",
        details == {
            "main_parent_count": 1,
            "main_child_count": 0,
            "branch_parent_count": 0,
            "branch_child_count": 1,
        },
        details,
    )


def _add_check(
    correctness: dict[str, Any],
    name: str,
    passed: bool | None,
    details: dict[str, Any],
) -> None:
    correctness.setdefault("checks", []).append(
        {
            "name": name,
            "passed": passed,
            "details": details,
        }
    )


def _enforce_limits(
    config: BenchmarkConfig,
    counters: dict[str, int],
    start_time: float,
    next_branches: int = 0,
    next_versions: int = 0,
) -> None:
    if counters["branches"] + next_branches > int(config.limits.get("max_total_branches", 0)):
        raise LimitExceededError("max_total_branches exceeded")
    if counters["versions"] + next_versions > int(config.limits.get("max_total_versions", 0)):
        raise LimitExceededError("max_total_versions exceeded")
    if time.monotonic() - start_time > float(config.limits.get("max_runtime_seconds", 0)):
        raise LimitExceededError("max_runtime_seconds exceeded")


def _resource_name(bench_id: str, dataset: str, scenario: str, index: int) -> str:
    dataset_slug = dataset.lower() if dataset else "all"
    scenario_aliases = {
        "branch_create_without_compute": "bc-no",
        "branch_create_with_compute": "bc-co",
        "branch_create_concurrent": "bc-con",
        "branch_depth": "bd",
        "version_create": "vc",
        "version_squash": "vs",
    }
    scenario_slug = scenario
    suffix = ""
    for full_name, alias in scenario_aliases.items():
        if scenario == full_name:
            scenario_slug = alias
            break
        if scenario.startswith(f"{full_name}_"):
            scenario_slug = alias
            suffix = scenario.removeprefix(f"{full_name}_")
            break
    parts = ["bv", dataset_slug, scenario_slug]
    if suffix:
        parts.append(suffix)
    parts.append(str(index))
    slug = re.sub(r"[^a-z0-9-]+", "-", "-".join(parts).lower())
    return re.sub(r"-+", "-", slug).strip("-")


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
    scenario: str = "",
    operation: str = "",
    resource_type: str = "",
    resource_id: str = "",
    concurrency: int = 1,
    depth: int = 0,
) -> None:
    if sample is None:
        sample = OperationSample(
            bench_id="",
            dataset="",
            scenario=scenario,
            operation=operation,
            resource_type=resource_type,
            resource_id=resource_id,
        )
    sample.bench_id = bench_id
    if dataset:
        sample.dataset = dataset
    if scenario:
        sample.scenario = scenario
    if operation:
        sample.operation = operation
    if resource_type:
        sample.resource_type = resource_type
    if resource_id:
        sample.resource_id = resource_id
    sample.concurrency = concurrency
    sample.depth = depth
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
