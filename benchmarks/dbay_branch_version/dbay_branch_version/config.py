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


def load_config(path: str | Path) -> BenchmarkConfig:
    raw = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        raise ConfigError("Config root must be a mapping")
    allow_large_dataset = raw.get("allow_large_dataset", False)
    if not isinstance(allow_large_dataset, bool):
        raise ConfigError("allow_large_dataset must be a boolean")

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

    if int(config.limits.get("max_total_branches", 0)) <= 0:
        raise ConfigError("max_total_branches must be positive")
    if int(config.limits.get("max_total_versions", 0)) <= 0:
        raise ConfigError("max_total_versions must be positive")
