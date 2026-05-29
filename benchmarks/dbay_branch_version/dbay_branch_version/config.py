from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml


class ConfigError(ValueError):
    """Raised when benchmark config is unsafe or invalid."""


@dataclass(frozen=True)
class BenchmarkConfig:
    profile: str
    resource_prefix: str
    api_base_url: str
    compute_size: str
    poll_interval_seconds: float
    poll_timeout_seconds: float
    request_timeout_seconds: float
    result_root: str
    datasets: tuple[str, ...]
    allow_large_dataset: bool
    limits: dict[str, Any]
    scenarios: dict[str, Any]

    def make_database_name(self, timestamp_slug: str, suffix: str) -> str:
        return f"{self.resource_prefix}-{timestamp_slug}-{suffix}"

    def is_benchmark_database_name(self, name: str) -> bool:
        return name.startswith(f"{self.resource_prefix}-")


def load_config(
    path: str | Path,
    allow_large_dataset_override: bool = False,
) -> BenchmarkConfig:
    raw = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        raise ConfigError("Config root must be a mapping")
    allow_large_dataset = raw.get("allow_large_dataset", False)
    if not isinstance(allow_large_dataset, bool):
        raise ConfigError("allow_large_dataset must be a boolean")
    allow_large_dataset = allow_large_dataset or allow_large_dataset_override

    config = BenchmarkConfig(
        profile=str(raw.get("profile", "public-comparison")),
        resource_prefix=str(raw.get("resource_prefix", "bench-branch-version")),
        api_base_url=str(raw.get("api_base_url", "https://api.dbay.cloud:8443/api/v1")).rstrip("/"),
        compute_size=str(raw.get("compute_size", "1cu")),
        poll_interval_seconds=float(raw.get("poll_interval_seconds", 2.0)),
        poll_timeout_seconds=float(raw.get("poll_timeout_seconds", 600.0)),
        request_timeout_seconds=float(raw.get("request_timeout_seconds", 60.0)),
        result_root=str(raw.get("result_root", "results")),
        datasets=tuple(raw.get("datasets", ["S", "M"])),
        allow_large_dataset=allow_large_dataset,
        limits=dict(raw.get("limits", {})),
        scenarios=dict(raw.get("scenarios", {})),
    )
    validate_config(config)
    return config


def validate_config(config: BenchmarkConfig) -> None:
    if config.resource_prefix != "bench-branch-version":
        raise ConfigError("resource_prefix must be bench-branch-version")
    if "L" in config.datasets and not config.allow_large_dataset:
        raise ConfigError("Dataset L requires allow_large_dataset=true or --allow-large-dataset")

    max_concurrency = int(config.limits.get("max_branch_concurrency", 10))
    if max_concurrency > 10:
        raise ConfigError("max_branch_concurrency must be <= 10 for public-comparison")

    concurrent = config.scenarios.get("branch_create_concurrent", {})
    for value in concurrent.get("concurrency", []):
        if int(value) > max_concurrency:
            raise ConfigError("branch_create_concurrent concurrency exceeds max_branch_concurrency")

    version_read = config.scenarios.get("version_read", {})
    version_read_concurrency = int(version_read.get("concurrency", 1))
    if version_read_concurrency > max_concurrency:
        raise ConfigError("version_read concurrency exceeds max_branch_concurrency")

    max_total_branches = int(config.limits.get("max_total_branches", 0))
    max_total_versions = int(config.limits.get("max_total_versions", 0))
    if max_total_branches <= 0:
        raise ConfigError("max_total_branches must be positive")
    if max_total_versions <= 0:
        raise ConfigError("max_total_versions must be positive")

    branch_budget = estimate_branch_count(config)
    if branch_budget > max_total_branches:
        raise ConfigError(
            f"configured scenarios require {branch_budget} branches, "
            f"exceeding max_total_branches={max_total_branches}"
        )
    version_budget = estimate_version_count(config)
    if version_budget > max_total_versions:
        raise ConfigError(
            f"configured scenarios require {version_budget} versions, "
            f"exceeding max_total_versions={max_total_versions}"
        )


def estimate_branch_count(config: BenchmarkConfig) -> int:
    scenario_count = len(config.datasets)
    without_compute = config.scenarios.get("branch_create_without_compute", {})
    with_compute = config.scenarios.get("branch_create_with_compute", {})
    concurrent = config.scenarios.get("branch_create_concurrent", {})
    depth = config.scenarios.get("branch_depth", {})
    return scenario_count * (
        int(without_compute.get("samples_per_dataset", 0))
        + int(with_compute.get("samples_per_dataset", 0))
        + int(concurrent.get("total_samples", 0))
        * len(concurrent.get("concurrency", []))
        + sum(int(value) for value in depth.get("depths", []))
    )


def estimate_version_count(config: BenchmarkConfig) -> int:
    scenario_count = len(config.datasets)
    create = config.scenarios.get("version_create", {})
    squash = config.scenarios.get("version_squash", {})
    return scenario_count * (
        int(create.get("samples_per_dataset", 0))
        + int(squash.get("groups", 0)) * int(squash.get("versions_per_group", 0))
    )
