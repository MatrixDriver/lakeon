# DBay Branch Version Benchmark Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a reproducible DBay.cloud production benchmark harness for branch and DBay self-managed version operations, with safety gates, correctness checks, cleanup, and comparison artifacts.

**Architecture:** Create a standalone Python project under `benchmarks/dbay_branch_version/` so it can run without changing DBay services. The harness uses a typed REST client for DBay control-plane calls, a focused Postgres workload module for dataset and checksum operations, a metrics writer for raw and summary artifacts, and an always-safe cleanup module. The runner orchestrates dry-run, benchmark, cleanup-only, and report generation through dependency-injected clients so tests can run without DBay.cloud credentials.

**Tech Stack:** Python 3.11, `httpx`, `psycopg[binary]`, `PyYAML`, `pytest`, standard-library `csv`, `json`, `statistics`, `argparse`, `concurrent.futures`.

---

## File Structure

- Create `benchmarks/dbay_branch_version/pyproject.toml`
  - Standalone uv project with runtime and test dependencies.
- Create `benchmarks/dbay_branch_version/README.md`
  - Operator guide, required env vars, dry-run, production run, cleanup-only, artifact descriptions.
- Create `benchmarks/dbay_branch_version/config.example.yaml`
  - Safe default config: datasets `S,M`, dataset `L` disabled unless explicitly requested, branch concurrency max 10.
- Create `benchmarks/dbay_branch_version/dbay_branch_version/__init__.py`
  - Package marker and version.
- Create `benchmarks/dbay_branch_version/dbay_branch_version/config.py`
  - Config dataclasses, YAML loading, CLI overrides, safety validation.
- Create `benchmarks/dbay_branch_version/dbay_branch_version/metrics.py`
  - `OperationSample`, raw CSV writer, summary JSON writer, percentile/stat helpers, secret redaction.
- Create `benchmarks/dbay_branch_version/dbay_branch_version/dbay_client.py`
  - Measured DBay API client with branch, version, database, polling, and safe-delete helpers.
- Create `benchmarks/dbay_branch_version/dbay_branch_version/pg_workload.py`
  - Dataset schema/load/checksum/isolation SQL and Postgres connection helpers.
- Create `benchmarks/dbay_branch_version/dbay_branch_version/cleanup.py`
  - Cleanup registry and cleanup runner that deletes versions, non-main branches, then temporary DB.
- Create `benchmarks/dbay_branch_version/dbay_branch_version/report.py`
  - `comparison.md` generation with official-claim table and measured-result summary.
- Create `benchmarks/dbay_branch_version/dbay_branch_version/runner.py`
  - Benchmark orchestration: preflight, dry-run, benchmark phases, cleanup-only, result directory creation.
- Create `benchmarks/dbay_branch_version/run_benchmark.py`
  - Thin executable wrapper around `runner.main`.
- Create tests under `benchmarks/dbay_branch_version/tests/`
  - Unit tests for config, metrics, DBay client using `httpx.MockTransport`, cleanup ordering, workload SQL, runner dry-run.

## Task 1: Project Scaffold and Config

**Files:**
- Create: `benchmarks/dbay_branch_version/pyproject.toml`
- Create: `benchmarks/dbay_branch_version/README.md`
- Create: `benchmarks/dbay_branch_version/config.example.yaml`
- Create: `benchmarks/dbay_branch_version/dbay_branch_version/__init__.py`
- Create: `benchmarks/dbay_branch_version/dbay_branch_version/config.py`
- Test: `benchmarks/dbay_branch_version/tests/test_config.py`

- [ ] **Step 1: Create the standalone project files**

Create `benchmarks/dbay_branch_version/pyproject.toml`:

```toml
[project]
name = "dbay-branch-version-benchmark"
version = "0.1.0"
description = "DBay.cloud branch and version benchmark harness"
requires-python = ">=3.11"
dependencies = [
  "httpx>=0.27",
  "psycopg[binary]>=3.2",
  "pyyaml>=6.0",
]

[project.optional-dependencies]
dev = ["pytest>=8.0"]

[project.scripts]
dbay-branch-version-bench = "dbay_branch_version.runner:main"

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["dbay_branch_version"]

[tool.pytest.ini_options]
testpaths = ["tests"]
```

Create `benchmarks/dbay_branch_version/dbay_branch_version/__init__.py`:

```python
"""DBay branch and version benchmark harness."""

__version__ = "0.1.0"
```

Create `benchmarks/dbay_branch_version/config.example.yaml`:

```yaml
profile: public-comparison
resource_prefix: bench-branch-version
api_base_url: https://api.dbay.cloud:8443/api/v1
compute_size: 1cu
poll_interval_seconds: 2.0
poll_timeout_seconds: 600.0
request_timeout_seconds: 60.0
result_root: results
datasets:
  - S
  - M
allow_large_dataset: false
limits:
  max_branch_concurrency: 10
  max_total_branches: 600
  max_total_versions: 600
  max_runtime_seconds: 14400
scenarios:
  branch_create_without_compute:
    samples_per_dataset: 100
  branch_create_with_compute:
    samples_per_dataset: 50
  branch_create_concurrent:
    total_samples: 100
    concurrency:
      - 5
      - 10
  branch_depth:
    depths:
      - 1
      - 5
      - 10
  version_create:
    samples_per_dataset: 100
  version_read:
    samples: 1000
    concurrency: 10
  version_squash:
    groups: 20
    versions_per_group: 10
```

Create `benchmarks/dbay_branch_version/README.md`:

```markdown
# DBay Branch and Version Benchmark

This harness measures DBay.cloud branch and DBay self-managed version operations using temporary databases only.

## Required environment

```bash
export DBAY_API_TOKEN="..."
export DBAY_API_BASE_URL="https://api.dbay.cloud:8443/api/v1"
```

## Dry run

```bash
uv run --project benchmarks/dbay_branch_version dbay-branch-version-bench \
  --config benchmarks/dbay_branch_version/config.example.yaml \
  --dry-run
```

## First production run

The first production run uses datasets `S,M`. Dataset `L` requires `--allow-large-dataset`.

```bash
uv run --project benchmarks/dbay_branch_version dbay-branch-version-bench \
  --config benchmarks/dbay_branch_version/config.example.yaml \
  --datasets S,M
```

## Cleanup an interrupted run

```bash
uv run --project benchmarks/dbay_branch_version dbay-branch-version-bench \
  --config benchmarks/dbay_branch_version/config.example.yaml \
  --cleanup-only <bench_id>
```

## Safety

The runner only creates and deletes database names beginning with `bench-branch-version-`.
It deletes versions first, then non-main branches, then the temporary database instance.
Artifacts redact tokens, passwords, and full connection strings.
```

- [ ] **Step 2: Write the failing config tests**

Create `benchmarks/dbay_branch_version/tests/test_config.py`:

```python
import pytest

from dbay_branch_version.config import BenchmarkConfig, ConfigError, load_config


def test_load_config_rejects_large_dataset_without_flag(tmp_path):
    config_path = tmp_path / "config.yaml"
    config_path.write_text(
        """
profile: public-comparison
resource_prefix: bench-branch-version
api_base_url: https://api.dbay.cloud:8443/api/v1
datasets: [S, L]
allow_large_dataset: false
limits:
  max_branch_concurrency: 10
  max_total_branches: 20
  max_total_versions: 20
  max_runtime_seconds: 100
scenarios: {}
""",
        encoding="utf-8",
    )

    with pytest.raises(ConfigError, match="Dataset L requires"):
        load_config(config_path)


def test_config_builds_bench_name_with_safe_prefix():
    config = BenchmarkConfig(
        profile="public-comparison",
        resource_prefix="bench-branch-version",
        api_base_url="https://api.dbay.cloud:8443/api/v1",
        compute_size="1cu",
        poll_interval_seconds=2.0,
        poll_timeout_seconds=60.0,
        request_timeout_seconds=30.0,
        result_root="results",
        datasets=("S", "M"),
        allow_large_dataset=False,
        limits={
            "max_branch_concurrency": 10,
            "max_total_branches": 20,
            "max_total_versions": 20,
            "max_runtime_seconds": 100,
        },
        scenarios={},
    )

    name = config.make_database_name("20260529t120000z", "abc123")

    assert name == "bench-branch-version-20260529t120000z-abc123"
    assert config.is_benchmark_database_name(name)
    assert not config.is_benchmark_database_name("customer-prod")


def test_concurrency_above_limit_is_rejected(tmp_path):
    config_path = tmp_path / "config.yaml"
    config_path.write_text(
        """
profile: public-comparison
resource_prefix: bench-branch-version
api_base_url: https://api.dbay.cloud:8443/api/v1
datasets: [S]
allow_large_dataset: false
limits:
  max_branch_concurrency: 10
  max_total_branches: 20
  max_total_versions: 20
  max_runtime_seconds: 100
scenarios:
  branch_create_concurrent:
    concurrency: [11]
""",
        encoding="utf-8",
    )

    with pytest.raises(ConfigError, match="max_branch_concurrency"):
        load_config(config_path)
```

- [ ] **Step 3: Run the config tests and verify they fail**

Run:

```bash
uv run --project benchmarks/dbay_branch_version pytest benchmarks/dbay_branch_version/tests/test_config.py -v
```

Expected: FAIL with `ModuleNotFoundError: No module named 'dbay_branch_version.config'`.

- [ ] **Step 4: Implement config loading**

Create `benchmarks/dbay_branch_version/dbay_branch_version/config.py`:

```python
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
        allow_large_dataset=bool(raw.get("allow_large_dataset", False)),
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
```

- [ ] **Step 5: Run config tests and commit**

Run:

```bash
uv run --project benchmarks/dbay_branch_version pytest benchmarks/dbay_branch_version/tests/test_config.py -v
```

Expected: PASS.

Commit:

```bash
git add benchmarks/dbay_branch_version
git commit -m "feat(bench): scaffold dbay branch version config"
```

## Task 2: Metrics and Artifact Writer

**Files:**
- Create: `benchmarks/dbay_branch_version/dbay_branch_version/metrics.py`
- Test: `benchmarks/dbay_branch_version/tests/test_metrics.py`

- [ ] **Step 1: Write failing metrics tests**

Create `benchmarks/dbay_branch_version/tests/test_metrics.py`:

```python
import csv
import json

from dbay_branch_version.metrics import OperationSample, redact_secret, summarize_samples, write_raw_csv


def test_redact_secret_hides_tokens_and_passwords():
    text = "Authorization: Bearer lk_secret postgresql://user:pass@host/db"

    redacted = redact_secret(text)

    assert "lk_secret" not in redacted
    assert "pass@host" not in redacted
    assert "Bearer [REDACTED]" in redacted
    assert "postgresql://[REDACTED]" in redacted


def test_summarize_samples_groups_by_scenario_operation():
    samples = [
        OperationSample(bench_id="b1", dataset="S", scenario="branch", operation="create", api_latency_ms=10, success=True),
        OperationSample(bench_id="b1", dataset="S", scenario="branch", operation="create", api_latency_ms=30, success=True),
        OperationSample(bench_id="b1", dataset="S", scenario="branch", operation="create", api_latency_ms=50, success=False),
    ]

    summary = summarize_samples(samples)
    key = "branch/create/S"

    assert summary[key]["sample_count"] == 3
    assert summary[key]["success_count"] == 2
    assert summary[key]["error_rate"] == 1 / 3
    assert summary[key]["api_latency_ms"]["p50"] == 30


def test_write_raw_csv_redacts_error_message(tmp_path):
    sample = OperationSample(
        bench_id="b1",
        dataset="S",
        scenario="version",
        operation="create",
        api_latency_ms=12.5,
        success=False,
        error_message="Bearer lk_secret",
    )

    path = tmp_path / "raw_samples.csv"
    write_raw_csv(path, [sample])

    rows = list(csv.DictReader(path.open(newline="", encoding="utf-8")))
    assert rows[0]["error_message"] == "Bearer [REDACTED]"
```

- [ ] **Step 2: Run tests and verify failure**

Run:

```bash
uv run --project benchmarks/dbay_branch_version pytest benchmarks/dbay_branch_version/tests/test_metrics.py -v
```

Expected: FAIL with `ModuleNotFoundError: No module named 'dbay_branch_version.metrics'`.

- [ ] **Step 3: Implement metrics module**

Create `benchmarks/dbay_branch_version/dbay_branch_version/metrics.py`:

```python
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
```

- [ ] **Step 4: Run metrics tests and commit**

Run:

```bash
uv run --project benchmarks/dbay_branch_version pytest benchmarks/dbay_branch_version/tests/test_metrics.py -v
```

Expected: PASS.

Commit:

```bash
git add benchmarks/dbay_branch_version/dbay_branch_version/metrics.py benchmarks/dbay_branch_version/tests/test_metrics.py
git commit -m "feat(bench): add metrics artifact writer"
```

## Task 3: DBay REST Client

**Files:**
- Create: `benchmarks/dbay_branch_version/dbay_branch_version/dbay_client.py`
- Test: `benchmarks/dbay_branch_version/tests/test_dbay_client.py`

- [ ] **Step 1: Write failing DBay client tests**

Create `benchmarks/dbay_branch_version/tests/test_dbay_client.py`:

```python
import httpx
import pytest

from dbay_branch_version.dbay_client import DbayApiError, DbayClient, UnsafeResourceError


def test_create_branch_sends_start_compute_flag():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["body"] = request.content.decode()
        return httpx.Response(201, json={"id": "br_1", "name": "b1"})

    client = DbayClient(
        api_base_url="https://api.dbay.cloud:8443/api/v1",
        api_token="token",
        transport=httpx.MockTransport(handler),
    )

    branch, sample = client.create_branch("db_1", "b1", start_compute=True)

    assert branch["id"] == "br_1"
    assert seen["url"].endswith("/databases/db_1/branches")
    assert '"start_compute":true' in seen["body"].replace(" ", "")
    assert sample.http_status == 201
    assert sample.api_latency_ms is not None


def test_delete_database_rejects_non_benchmark_name():
    client = DbayClient(
        api_base_url="https://api.dbay.cloud:8443/api/v1",
        api_token="token",
        transport=httpx.MockTransport(lambda request: httpx.Response(204)),
    )

    with pytest.raises(UnsafeResourceError):
        client.delete_database("db_1", "customer-prod")


def test_api_error_captures_status_and_body():
    client = DbayClient(
        api_base_url="https://api.dbay.cloud:8443/api/v1",
        api_token="token",
        transport=httpx.MockTransport(lambda request: httpx.Response(409, json={"error": {"code": "CONFLICT"}})),
    )

    with pytest.raises(DbayApiError) as exc:
        client.create_database("bench-branch-version-x", "1cu")

    assert exc.value.status_code == 409
    assert exc.value.body["error"]["code"] == "CONFLICT"
```

- [ ] **Step 2: Run tests and verify failure**

Run:

```bash
uv run --project benchmarks/dbay_branch_version pytest benchmarks/dbay_branch_version/tests/test_dbay_client.py -v
```

Expected: FAIL with `ModuleNotFoundError: No module named 'dbay_branch_version.dbay_client'`.

- [ ] **Step 3: Implement DBay client**

Create `benchmarks/dbay_branch_version/dbay_branch_version/dbay_client.py`:

```python
from __future__ import annotations

import time
from datetime import datetime, timezone
from typing import Any

import httpx

from .metrics import OperationSample, redact_secret


class DbayApiError(RuntimeError):
    def __init__(self, status_code: int, body: Any):
        self.status_code = status_code
        self.body = body
        super().__init__(f"DBay API error {status_code}: {redact_secret(str(body))}")


class UnsafeResourceError(ValueError):
    """Raised before a client operation could modify non-benchmark resources."""


class DbayClient:
    def __init__(
        self,
        api_base_url: str,
        api_token: str,
        timeout_seconds: float = 60.0,
        transport: httpx.BaseTransport | None = None,
    ):
        self.api_base_url = api_base_url.rstrip("/")
        self._client = httpx.Client(
            timeout=timeout_seconds,
            transport=transport,
            headers={
                "Authorization": f"Bearer {api_token}",
                "Content-Type": "application/json",
            },
        )

    def close(self) -> None:
        self._client.close()

    def create_database(self, name: str, compute_size: str) -> tuple[dict, OperationSample]:
        self._assert_benchmark_name(name)
        return self._request_sample(
            "POST",
            "/databases",
            scenario="database",
            operation="create",
            resource_type="database",
            json={"name": name, "compute_size": compute_size},
        )

    def list_databases(self) -> tuple[list, OperationSample]:
        body, sample = self._request_sample("GET", "/databases", scenario="database", operation="list")
        return body, sample

    def get_database(self, db_id: str) -> tuple[dict, OperationSample]:
        return self._request_sample("GET", f"/databases/{db_id}", scenario="database", operation="get")

    def delete_database(self, db_id: str, name: str) -> tuple[dict, OperationSample]:
        self._assert_benchmark_name(name)
        return self._request_sample(
            "DELETE",
            f"/databases/{db_id}",
            scenario="database",
            operation="delete",
            resource_type="database",
            resource_id=db_id,
        )

    def create_branch(
        self,
        db_id: str,
        name: str,
        start_compute: bool = False,
        parent_branch_id: str | None = None,
    ) -> tuple[dict, OperationSample]:
        body: dict[str, Any] = {"name": name, "start_compute": start_compute}
        if parent_branch_id:
            body["parent_branch_id"] = parent_branch_id
        return self._request_sample(
            "POST",
            f"/databases/{db_id}/branches",
            scenario="branch",
            operation="create",
            resource_type="branch",
            json=body,
        )

    def list_branches(self, db_id: str) -> tuple[list, OperationSample]:
        body, sample = self._request_sample("GET", f"/databases/{db_id}/branches", scenario="branch", operation="list")
        return body, sample

    def get_branch(self, db_id: str, branch_id: str) -> tuple[dict, OperationSample]:
        return self._request_sample("GET", f"/databases/{db_id}/branches/{branch_id}", scenario="branch", operation="get")

    def delete_branch(self, db_id: str, branch_id: str, branch_name: str, is_default: bool = False) -> tuple[dict, OperationSample]:
        if is_default or branch_name == "main":
            raise UnsafeResourceError("Refusing to delete default branch")
        return self._request_sample(
            "DELETE",
            f"/databases/{db_id}/branches/{branch_id}",
            scenario="branch",
            operation="delete",
            resource_type="branch",
            resource_id=branch_id,
        )

    def create_version(self, db_id: str, branch_id: str, name: str, description: str = "") -> tuple[dict, OperationSample]:
        return self._request_sample(
            "POST",
            f"/databases/{db_id}/branches/{branch_id}/versions",
            scenario="version",
            operation="create",
            resource_type="version",
            json={"name": name, "description": description},
        )

    def list_versions(self, db_id: str, branch_id: str) -> tuple[list, OperationSample]:
        body, sample = self._request_sample(
            "GET",
            f"/databases/{db_id}/branches/{branch_id}/versions",
            scenario="version",
            operation="list",
        )
        return body, sample

    def get_version(self, db_id: str, branch_id: str, version_id: str) -> tuple[dict, OperationSample]:
        return self._request_sample(
            "GET",
            f"/databases/{db_id}/branches/{branch_id}/versions/{version_id}",
            scenario="version",
            operation="get",
            resource_type="version",
            resource_id=version_id,
        )

    def delete_version(self, db_id: str, branch_id: str, version_id: str) -> tuple[dict, OperationSample]:
        return self._request_sample(
            "DELETE",
            f"/databases/{db_id}/branches/{branch_id}/versions/{version_id}",
            scenario="version",
            operation="delete",
            resource_type="version",
            resource_id=version_id,
        )

    def squash_versions(self, db_id: str, branch_id: str, from_version_id: str, to_version_id: str) -> tuple[list, OperationSample]:
        body, sample = self._request_sample(
            "POST",
            f"/databases/{db_id}/branches/{branch_id}/versions/squash",
            scenario="version",
            operation="squash",
            json={"from_version_id": from_version_id, "to_version_id": to_version_id},
        )
        return body, sample

    def _request_sample(self, method: str, path: str, scenario: str, operation: str, **kwargs) -> tuple[Any, OperationSample]:
        started_at = datetime.now(timezone.utc).isoformat()
        start = time.perf_counter()
        try:
            response = self._client.request(method, f"{self.api_base_url}{path}", **kwargs)
            elapsed = (time.perf_counter() - start) * 1000
            body = self._decode_body(response)
            sample = OperationSample(
                bench_id="",
                dataset="",
                scenario=scenario,
                operation=operation,
                resource_type=kwargs.get("resource_type", ""),
                resource_id=kwargs.get("resource_id", ""),
                started_at=started_at,
                ended_at=datetime.now(timezone.utc).isoformat(),
                api_latency_ms=elapsed,
                http_status=response.status_code,
                success=response.status_code < 400,
            )
            if response.status_code >= 400:
                sample.success = False
                sample.error_message = redact_secret(str(body))
                raise DbayApiError(response.status_code, body)
            return body, sample
        except httpx.HTTPError as exc:
            elapsed = (time.perf_counter() - start) * 1000
            sample = OperationSample(
                bench_id="",
                dataset="",
                scenario=scenario,
                operation=operation,
                started_at=started_at,
                ended_at=datetime.now(timezone.utc).isoformat(),
                api_latency_ms=elapsed,
                success=False,
                error_code=exc.__class__.__name__,
                error_message=redact_secret(str(exc)),
            )
            raise DbayApiError(0, {"error": {"message": sample.error_message}}) from exc

    @staticmethod
    def _decode_body(response: httpx.Response) -> Any:
        if response.status_code == 204 or not response.content:
            return {}
        try:
            return response.json()
        except ValueError:
            return {"text": response.text}

    @staticmethod
    def _assert_benchmark_name(name: str) -> None:
        if not name.startswith("bench-branch-version-"):
            raise UnsafeResourceError(f"Refusing non-benchmark database name: {name}")
```

- [ ] **Step 4: Run DBay client tests and commit**

Run:

```bash
uv run --project benchmarks/dbay_branch_version pytest benchmarks/dbay_branch_version/tests/test_dbay_client.py -v
```

Expected: PASS.

Commit:

```bash
git add benchmarks/dbay_branch_version/dbay_branch_version/dbay_client.py benchmarks/dbay_branch_version/tests/test_dbay_client.py
git commit -m "feat(bench): add measured dbay rest client"
```

## Task 4: Postgres Workload Module

**Files:**
- Create: `benchmarks/dbay_branch_version/dbay_branch_version/pg_workload.py`
- Test: `benchmarks/dbay_branch_version/tests/test_pg_workload.py`

- [ ] **Step 1: Write failing workload tests**

Create `benchmarks/dbay_branch_version/tests/test_pg_workload.py`:

```python
from dbay_branch_version.pg_workload import DATASETS, checksum_sql, dataset_row_counts, isolation_insert_sql


def test_dataset_sizes_are_explicit():
    assert DATASETS["S"].scale == 10_000
    assert DATASETS["M"].scale == 100_000
    assert DATASETS["L"].scale == 1_000_000


def test_checksum_sql_mentions_all_tables():
    sql = checksum_sql()

    assert "bench_oltp" in sql
    assert "bench_jsonb" in sql
    assert "bench_events" in sql
    assert "md5" in sql


def test_isolation_insert_sql_uses_marker():
    sql = isolation_insert_sql("parent-only")

    assert "parent-only" in sql
    assert "bench_events" in sql


def test_dataset_row_counts_splits_rows_across_tables():
    counts = dataset_row_counts("S")

    assert counts["bench_oltp"] == 10_000
    assert counts["bench_jsonb"] == 10_000
    assert counts["bench_events"] == 10_000
```

- [ ] **Step 2: Run workload tests and verify failure**

Run:

```bash
uv run --project benchmarks/dbay_branch_version pytest benchmarks/dbay_branch_version/tests/test_pg_workload.py -v
```

Expected: FAIL with `ModuleNotFoundError: No module named 'dbay_branch_version.pg_workload'`.

- [ ] **Step 3: Implement workload SQL helpers**

Create `benchmarks/dbay_branch_version/dbay_branch_version/pg_workload.py`:

```python
from __future__ import annotations

from dataclasses import dataclass
from typing import Mapping

import psycopg


@dataclass(frozen=True)
class DatasetSpec:
    name: str
    scale: int


DATASETS: dict[str, DatasetSpec] = {
    "S": DatasetSpec("S", 10_000),
    "M": DatasetSpec("M", 100_000),
    "L": DatasetSpec("L", 1_000_000),
}


SCHEMA_SQL = """
DROP TABLE IF EXISTS bench_events;
DROP TABLE IF EXISTS bench_jsonb;
DROP TABLE IF EXISTS bench_oltp;

CREATE TABLE bench_oltp (
    id BIGINT PRIMARY KEY,
    account_id BIGINT NOT NULL,
    amount NUMERIC(12, 2) NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE bench_jsonb (
    id BIGINT PRIMARY KEY,
    payload JSONB NOT NULL,
    note TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE bench_events (
    id BIGINT PRIMARY KEY,
    marker TEXT NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_bench_oltp_account_id ON bench_oltp(account_id);
CREATE INDEX idx_bench_jsonb_payload ON bench_jsonb USING GIN(payload);
CREATE INDEX idx_bench_events_marker ON bench_events(marker);
"""


def dataset_row_counts(dataset: str) -> dict[str, int]:
    scale = DATASETS[dataset].scale
    return {
        "bench_oltp": scale,
        "bench_jsonb": scale,
        "bench_events": scale,
    }


def load_dataset(connstr: str, dataset: str) -> None:
    scale = DATASETS[dataset].scale
    with psycopg.connect(connstr) as conn:
        with conn.cursor() as cur:
            cur.execute(SCHEMA_SQL)
            cur.execute(
                """
                INSERT INTO bench_oltp (id, account_id, amount, status)
                SELECT g, g % 1000, (g % 10000) / 100.0, CASE WHEN g % 2 = 0 THEN 'open' ELSE 'closed' END
                FROM generate_series(1, %s) AS g
                """,
                (scale,),
            )
            cur.execute(
                """
                INSERT INTO bench_jsonb (id, payload, note)
                SELECT g,
                       jsonb_build_object('id', g, 'group', g % 100, 'tags', ARRAY['dbay', 'bench', (g % 10)::text]),
                       repeat('x', 128)
                FROM generate_series(1, %s) AS g
                """,
                (scale,),
            )
            cur.execute(
                """
                INSERT INTO bench_events (id, marker, payload)
                SELECT g, 'base', repeat(md5(g::text), 8)
                FROM generate_series(1, %s) AS g
                """,
                (scale,),
            )
            cur.execute("ANALYZE")
        conn.commit()


def fetch_checksums(connstr: str) -> dict[str, str]:
    with psycopg.connect(connstr) as conn:
        with conn.cursor() as cur:
            cur.execute(checksum_sql())
            row = cur.fetchone()
            return {
                "bench_oltp": row[0],
                "bench_jsonb": row[1],
                "bench_events": row[2],
            }


def checksum_sql() -> str:
    return """
    SELECT
      (SELECT md5(string_agg(id::text || ':' || account_id::text || ':' || amount::text || ':' || status, ',' ORDER BY id)) FROM bench_oltp) AS bench_oltp,
      (SELECT md5(string_agg(id::text || ':' || payload::text || ':' || note, ',' ORDER BY id)) FROM bench_jsonb) AS bench_jsonb,
      (SELECT md5(string_agg(id::text || ':' || marker || ':' || payload, ',' ORDER BY id)) FROM bench_events) AS bench_events
    """


def isolation_insert_sql(marker: str) -> str:
    safe_marker = marker.replace("'", "''")
    return f"""
    INSERT INTO bench_events (id, marker, payload)
    SELECT COALESCE(MAX(id), 0) + 1, '{safe_marker}', repeat(md5('{safe_marker}'), 8)
    FROM bench_events
    """


def marker_count(connstr: str, marker: str) -> int:
    with psycopg.connect(connstr) as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT count(*) FROM bench_events WHERE marker = %s", (marker,))
            return int(cur.fetchone()[0])


def execute_isolation_insert(connstr: str, marker: str) -> None:
    with psycopg.connect(connstr) as conn:
        with conn.cursor() as cur:
            cur.execute(isolation_insert_sql(marker))
        conn.commit()
```

- [ ] **Step 4: Run workload tests and commit**

Run:

```bash
uv run --project benchmarks/dbay_branch_version pytest benchmarks/dbay_branch_version/tests/test_pg_workload.py -v
```

Expected: PASS.

Commit:

```bash
git add benchmarks/dbay_branch_version/dbay_branch_version/pg_workload.py benchmarks/dbay_branch_version/tests/test_pg_workload.py
git commit -m "feat(bench): add postgres workload helpers"
```

## Task 5: Cleanup Registry and Safe Cleanup

**Files:**
- Create: `benchmarks/dbay_branch_version/dbay_branch_version/cleanup.py`
- Test: `benchmarks/dbay_branch_version/tests/test_cleanup.py`

- [ ] **Step 1: Write failing cleanup tests**

Create `benchmarks/dbay_branch_version/tests/test_cleanup.py`:

```python
from dbay_branch_version.cleanup import CleanupRegistry, cleanup_benchmark_resources


class FakeClient:
    def __init__(self):
        self.calls = []

    def delete_version(self, db_id, branch_id, version_id):
        self.calls.append(("version", db_id, branch_id, version_id))
        return {}, None

    def delete_branch(self, db_id, branch_id, branch_name, is_default=False):
        self.calls.append(("branch", db_id, branch_id, branch_name))
        return {}, None

    def delete_database(self, db_id, name):
        self.calls.append(("database", db_id, name))
        return {}, None


def test_cleanup_order_versions_branches_database():
    client = FakeClient()
    registry = CleanupRegistry(
        bench_id="b1",
        database_id="db_1",
        database_name="bench-branch-version-x",
        branches=[{"id": "br_1", "name": "feature", "is_default": False}],
        versions=[{"id": "ver_1", "branch_id": "br_1"}],
    )

    status = cleanup_benchmark_resources(client, registry)

    assert client.calls == [
        ("version", "db_1", "br_1", "ver_1"),
        ("branch", "db_1", "br_1", "feature"),
        ("database", "db_1", "bench-branch-version-x"),
    ]
    assert status["cleanup_status"] == "clean"


def test_cleanup_skips_main_branch():
    client = FakeClient()
    registry = CleanupRegistry(
        bench_id="b1",
        database_id="db_1",
        database_name="bench-branch-version-x",
        branches=[{"id": "br_main", "name": "main", "is_default": True}],
        versions=[],
    )

    cleanup_benchmark_resources(client, registry)

    assert client.calls == [("database", "db_1", "bench-branch-version-x")]
```

- [ ] **Step 2: Run cleanup tests and verify failure**

Run:

```bash
uv run --project benchmarks/dbay_branch_version pytest benchmarks/dbay_branch_version/tests/test_cleanup.py -v
```

Expected: FAIL with `ModuleNotFoundError: No module named 'dbay_branch_version.cleanup'`.

- [ ] **Step 3: Implement cleanup module**

Create `benchmarks/dbay_branch_version/dbay_branch_version/cleanup.py`:

```python
from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any


@dataclass
class CleanupRegistry:
    bench_id: str
    database_id: str
    database_name: str
    branches: list[dict[str, Any]]
    versions: list[dict[str, Any]]

    def write(self, path: str | Path) -> None:
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        Path(path).write_text(json.dumps(asdict(self), indent=2), encoding="utf-8")

    @classmethod
    def read(cls, path: str | Path) -> "CleanupRegistry":
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        return cls(**data)


def cleanup_benchmark_resources(client, registry: CleanupRegistry) -> dict[str, Any]:
    failures = []

    for version in list(registry.versions):
        try:
            client.delete_version(registry.database_id, version["branch_id"], version["id"])
        except Exception as exc:
            failures.append({"type": "version", "id": version.get("id"), "error": str(exc)})

    for branch in list(registry.branches):
        if branch.get("is_default") or branch.get("name") == "main":
            continue
        try:
            client.delete_branch(
                registry.database_id,
                branch["id"],
                branch.get("name", ""),
                bool(branch.get("is_default", False)),
            )
        except Exception as exc:
            failures.append({"type": "branch", "id": branch.get("id"), "error": str(exc)})

    try:
        client.delete_database(registry.database_id, registry.database_name)
    except Exception as exc:
        failures.append({"type": "database", "id": registry.database_id, "error": str(exc)})

    return {
        "bench_id": registry.bench_id,
        "database_id": registry.database_id,
        "database_name": registry.database_name,
        "cleanup_status": "failed" if failures else "clean",
        "failures": failures,
    }
```

- [ ] **Step 4: Run cleanup tests and commit**

Run:

```bash
uv run --project benchmarks/dbay_branch_version pytest benchmarks/dbay_branch_version/tests/test_cleanup.py -v
```

Expected: PASS.

Commit:

```bash
git add benchmarks/dbay_branch_version/dbay_branch_version/cleanup.py benchmarks/dbay_branch_version/tests/test_cleanup.py
git commit -m "feat(bench): add safe cleanup registry"
```

## Task 6: Runner Dry-Run, Preflight, and CLI

**Files:**
- Create: `benchmarks/dbay_branch_version/dbay_branch_version/runner.py`
- Create: `benchmarks/dbay_branch_version/run_benchmark.py`
- Test: `benchmarks/dbay_branch_version/tests/test_runner.py`

- [ ] **Step 1: Write failing runner tests**

Create `benchmarks/dbay_branch_version/tests/test_runner.py`:

```python
import json

from dbay_branch_version.runner import build_arg_parser, build_run_plan, create_result_dir


def test_arg_parser_supports_dry_run_and_cleanup_only():
    parser = build_arg_parser()

    args = parser.parse_args(["--config", "config.yaml", "--dry-run"])
    assert args.config == "config.yaml"
    assert args.dry_run is True

    args = parser.parse_args(["--config", "config.yaml", "--cleanup-only", "bench_123"])
    assert args.cleanup_only == "bench_123"


def test_build_run_plan_honors_dataset_override(sample_config):
    plan = build_run_plan(sample_config, datasets="S", allow_large_dataset=False)

    assert plan["datasets"] == ["S"]
    assert plan["profile"] == "public-comparison"
    assert plan["will_create_database"] is True


def test_create_result_dir_writes_run_config(tmp_path, sample_config):
    result_dir = create_result_dir(tmp_path, "bench_123", {"datasets": ["S"]})

    assert result_dir.name.endswith("bench_123")
    assert json.loads((result_dir / "run_config.json").read_text(encoding="utf-8")) == {"datasets": ["S"]}
```

Create `benchmarks/dbay_branch_version/tests/conftest.py`:

```python
import pytest

from dbay_branch_version.config import BenchmarkConfig


@pytest.fixture
def sample_config():
    return BenchmarkConfig(
        profile="public-comparison",
        resource_prefix="bench-branch-version",
        api_base_url="https://api.dbay.cloud:8443/api/v1",
        compute_size="1cu",
        poll_interval_seconds=2.0,
        poll_timeout_seconds=60.0,
        request_timeout_seconds=30.0,
        result_root="results",
        datasets=("S", "M"),
        allow_large_dataset=False,
        limits={
            "max_branch_concurrency": 10,
            "max_total_branches": 20,
            "max_total_versions": 20,
            "max_runtime_seconds": 100,
        },
        scenarios={},
    )
```

- [ ] **Step 2: Run runner tests and verify failure**

Run:

```bash
uv run --project benchmarks/dbay_branch_version pytest benchmarks/dbay_branch_version/tests/test_runner.py -v
```

Expected: FAIL with `ModuleNotFoundError: No module named 'dbay_branch_version.runner'`.

- [ ] **Step 3: Implement runner skeleton**

Create `benchmarks/dbay_branch_version/dbay_branch_version/runner.py`:

```python
from __future__ import annotations

import argparse
import json
import os
import secrets
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from .config import BenchmarkConfig, load_config, validate_config
from .cleanup import CleanupRegistry, cleanup_benchmark_resources
from .dbay_client import DbayClient


def build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="DBay branch and version benchmark")
    parser.add_argument("--config", required=True)
    parser.add_argument("--datasets", default="")
    parser.add_argument("--allow-large-dataset", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--cleanup-only", default="")
    return parser


def build_run_plan(config: BenchmarkConfig, datasets: str = "", allow_large_dataset: bool = False) -> dict[str, Any]:
    selected = [item.strip() for item in datasets.split(",") if item.strip()] if datasets else list(config.datasets)
    effective = BenchmarkConfig(
        profile=config.profile,
        resource_prefix=config.resource_prefix,
        api_base_url=config.api_base_url,
        compute_size=config.compute_size,
        poll_interval_seconds=config.poll_interval_seconds,
        poll_timeout_seconds=config.poll_timeout_seconds,
        request_timeout_seconds=config.request_timeout_seconds,
        result_root=config.result_root,
        datasets=tuple(selected),
        allow_large_dataset=config.allow_large_dataset or allow_large_dataset,
        limits=config.limits,
        scenarios=config.scenarios,
    )
    validate_config(effective)
    return {
        "profile": effective.profile,
        "datasets": list(effective.datasets),
        "resource_prefix": effective.resource_prefix,
        "will_create_database": True,
        "will_cleanup_database": True,
        "max_branch_concurrency": effective.limits.get("max_branch_concurrency", 10),
    }


def create_result_dir(root: str | Path, bench_id: str, run_config: dict[str, Any]) -> Path:
    result_dir = Path(root) / f"{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}-{bench_id}"
    result_dir.mkdir(parents=True, exist_ok=True)
    (result_dir / "run_config.json").write_text(json.dumps(run_config, indent=2), encoding="utf-8")
    return result_dir


def main(argv: list[str] | None = None) -> int:
    parser = build_arg_parser()
    args = parser.parse_args(argv)
    config = load_config(args.config)
    plan = build_run_plan(config, args.datasets, args.allow_large_dataset)

    if args.cleanup_only:
        api_token = os.environ["DBAY_API_TOKEN"]
        client = DbayClient(config.api_base_url, api_token, config.request_timeout_seconds)
        registry_path = Path(config.result_root) / args.cleanup_only / "cleanup_registry.json"
        registry = CleanupRegistry.read(registry_path)
        status = cleanup_benchmark_resources(client, registry)
        print(json.dumps(status, indent=2))
        return 0 if status["cleanup_status"] == "clean" else 2

    bench_id = f"bench_{secrets.token_hex(4)}"
    if args.dry_run:
        print(json.dumps({"bench_id": bench_id, "plan": plan}, indent=2))
        return 0

    raise SystemExit("Full benchmark orchestration is implemented in Task 8")
```

Create `benchmarks/dbay_branch_version/run_benchmark.py`:

```python
#!/usr/bin/env python3
from dbay_branch_version.runner import main


if __name__ == "__main__":
    raise SystemExit(main())
```

- [ ] **Step 4: Run runner tests and commit**

Run:

```bash
uv run --project benchmarks/dbay_branch_version pytest benchmarks/dbay_branch_version/tests/test_runner.py -v
```

Expected: PASS.

Commit:

```bash
git add benchmarks/dbay_branch_version/dbay_branch_version/runner.py benchmarks/dbay_branch_version/run_benchmark.py benchmarks/dbay_branch_version/tests/test_runner.py benchmarks/dbay_branch_version/tests/conftest.py
git commit -m "feat(bench): add runner dry-run cli"
```

## Task 7: Report Generator

**Files:**
- Create: `benchmarks/dbay_branch_version/dbay_branch_version/report.py`
- Create: `benchmarks/dbay_branch_version/compare_claims.md`
- Test: `benchmarks/dbay_branch_version/tests/test_report.py`

- [ ] **Step 1: Write failing report tests**

Create `benchmarks/dbay_branch_version/tests/test_report.py`:

```python
from dbay_branch_version.report import VENDOR_CLAIMS, render_comparison_markdown


def test_vendor_claims_use_official_urls():
    urls = [claim["url"] for claim in VENDOR_CLAIMS]

    assert "https://neon.com/docs/introduction/point-in-time-restore" in urls
    assert "https://xata.io/documentation/core-concepts" in urls
    assert all(url.startswith("https://") for url in urls)


def test_render_comparison_separates_claims_from_measurements():
    markdown = render_comparison_markdown(
        bench_id="bench_1",
        environment={"api_base_url": "https://api.dbay.cloud:8443/api/v1"},
        summary={"branch/create/S": {"sample_count": 1, "error_rate": 0}},
        cleanup={"cleanup_status": "clean"},
    )

    assert "## DBay Measured Results" in markdown
    assert "## Vendor Public Claims" in markdown
    assert "bench_1" in markdown
    assert "cleanup_status" in markdown
```

- [ ] **Step 2: Run report tests and verify failure**

Run:

```bash
uv run --project benchmarks/dbay_branch_version pytest benchmarks/dbay_branch_version/tests/test_report.py -v
```

Expected: FAIL with `ModuleNotFoundError: No module named 'dbay_branch_version.report'`.

- [ ] **Step 3: Implement report module**

Create `benchmarks/dbay_branch_version/dbay_branch_version/report.py`:

```python
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
        f"# DBay Branch and Version Benchmark Report",
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


def write_comparison_report(path: str | Path, bench_id: str, environment: dict[str, Any], summary: dict[str, Any], cleanup: dict[str, Any]) -> None:
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(
        render_comparison_markdown(bench_id, environment, summary, cleanup),
        encoding="utf-8",
    )
```

Create `benchmarks/dbay_branch_version/compare_claims.md`:

```markdown
# Vendor Claim Sources

Use these official sources when interpreting DBay measured results.

| Vendor | Source |
| --- | --- |
| Neon branching | https://neon.com/docs/introduction/point-in-time-restore |
| Neon snapshots | https://neon.com/docs/ai/ai-database-versioning |
| Xata instant branching | https://xata.io/documentation/core-concepts |
| Supabase branching | https://supabase.com/docs/guides/deployment/branching |
| PlanetScale branching | https://planetscale.com/docs/concepts/branching |
```

- [ ] **Step 4: Run report tests and commit**

Run:

```bash
uv run --project benchmarks/dbay_branch_version pytest benchmarks/dbay_branch_version/tests/test_report.py -v
```

Expected: PASS.

Commit:

```bash
git add benchmarks/dbay_branch_version/dbay_branch_version/report.py benchmarks/dbay_branch_version/compare_claims.md benchmarks/dbay_branch_version/tests/test_report.py
git commit -m "feat(bench): add comparison report generator"
```

## Task 8: Full Benchmark Orchestration

**Files:**
- Modify: `benchmarks/dbay_branch_version/dbay_branch_version/runner.py`
- Test: `benchmarks/dbay_branch_version/tests/test_runner_orchestration.py`

- [ ] **Step 1: Write failing orchestration test with fakes**

Create `benchmarks/dbay_branch_version/tests/test_runner_orchestration.py`:

```python
from dbay_branch_version.runner import run_benchmark_with_clients


class FakeDbay:
    def __init__(self):
        self.created_database = False

    def create_database(self, name, compute_size):
        self.created_database = True
        return {"id": "db_1", "name": name, "connection_uri": "postgresql://user:pass@host/db", "status": "running"}, None

    def list_branches(self, db_id):
        return [{"id": "br_main", "name": "main", "is_default": True, "connection_uri": "postgresql://user:pass@host/db"}], None

    def create_branch(self, db_id, name, start_compute=False, parent_branch_id=None):
        return {"id": f"br_{name}", "name": name, "is_default": False, "connection_uri": "postgresql://user:pass@host/db"}, None

    def create_version(self, db_id, branch_id, name, description=""):
        return {"id": f"ver_{name}", "branch_id": branch_id, "name": name, "lsn": "0/1", "snapshot_timeline_id": "tl_1"}, None

    def delete_version(self, db_id, branch_id, version_id):
        return {}, None

    def delete_branch(self, db_id, branch_id, branch_name, is_default=False):
        return {}, None

    def delete_database(self, db_id, name):
        return {}, None


class FakeWorkload:
    def load_dataset(self, connstr, dataset):
        return None

    def fetch_checksums(self, connstr):
        return {"bench_oltp": "a", "bench_jsonb": "b", "bench_events": "c"}


def test_run_benchmark_with_clients_creates_artifacts(tmp_path, sample_config):
    result = run_benchmark_with_clients(
        config=sample_config,
        plan={"datasets": ["S"]},
        result_root=tmp_path,
        dbay=FakeDbay(),
        workload=FakeWorkload(),
    )

    assert result["cleanup_status"]["cleanup_status"] == "clean"
    assert (result["result_dir"] / "raw_samples.csv").exists()
    assert (result["result_dir"] / "summary.json").exists()
    assert (result["result_dir"] / "correctness.json").exists()
    assert (result["result_dir"] / "comparison.md").exists()
```

- [ ] **Step 2: Run orchestration test and verify failure**

Run:

```bash
uv run --project benchmarks/dbay_branch_version pytest benchmarks/dbay_branch_version/tests/test_runner_orchestration.py -v
```

Expected: FAIL with `ImportError: cannot import name 'run_benchmark_with_clients'`.

- [ ] **Step 3: Implement minimal full orchestration**

Modify `benchmarks/dbay_branch_version/dbay_branch_version/runner.py` to add this function and replace the non-dry-run `SystemExit` path with a call to it:

```python
from .cleanup import CleanupRegistry, cleanup_benchmark_resources
from .metrics import OperationSample, write_raw_csv, write_summary_json, summarize_samples
from .pg_workload import fetch_checksums, load_dataset
from .report import write_comparison_report


class PsycopgWorkload:
    def load_dataset(self, connstr: str, dataset: str) -> None:
        load_dataset(connstr, dataset)

    def fetch_checksums(self, connstr: str) -> dict[str, str]:
        return fetch_checksums(connstr)


def run_benchmark_with_clients(config, plan, result_root, dbay, workload) -> dict:
    bench_id = f"bench_{secrets.token_hex(4)}"
    result_dir = create_result_dir(result_root, bench_id, plan)
    database_name = config.make_database_name(
        datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ").lower(),
        secrets.token_hex(3),
    )
    samples: list[OperationSample] = []
    correctness = {"bench_id": bench_id, "datasets": {}, "checks": []}
    registry = CleanupRegistry(
        bench_id=bench_id,
        database_id="",
        database_name=database_name,
        branches=[],
        versions=[],
    )

    try:
        database, sample = dbay.create_database(database_name, config.compute_size)
        if sample:
            sample.bench_id = bench_id
            samples.append(sample)
        registry.database_id = database["id"]
        connstr = database.get("connection_uri", "")

        branches, sample = dbay.list_branches(database["id"])
        if sample:
            sample.bench_id = bench_id
            samples.append(sample)
        registry.branches.extend(branches)
        main_branch = next((branch for branch in branches if branch.get("is_default") or branch.get("name") == "main"), branches[0])

        for dataset in plan["datasets"]:
            workload.load_dataset(connstr, dataset)
            base_checksums = workload.fetch_checksums(connstr)
            correctness["datasets"][dataset] = {"base_checksums": base_checksums}

            branch_name = f"bench-{dataset.lower()}-{secrets.token_hex(3)}"
            branch, sample = dbay.create_branch(database["id"], branch_name, start_compute=True)
            if sample:
                sample.bench_id = bench_id
                sample.dataset = dataset
                samples.append(sample)
            registry.branches.append(branch)

            version, sample = dbay.create_version(database["id"], main_branch["id"], f"bench-{dataset.lower()}-v1", "benchmark version")
            if sample:
                sample.bench_id = bench_id
                sample.dataset = dataset
                samples.append(sample)
            registry.versions.append({"id": version["id"], "branch_id": main_branch["id"]})
            correctness["checks"].append(
                {
                    "dataset": dataset,
                    "version_metadata_present": all(version.get(key) for key in ["id", "branch_id", "lsn", "snapshot_timeline_id"]),
                }
            )
    finally:
        registry.write(result_dir / "cleanup_registry.json")
        cleanup_status = cleanup_benchmark_resources(dbay, registry)
        (result_dir / "cleanup_status.json").write_text(json.dumps(cleanup_status, indent=2), encoding="utf-8")

    write_raw_csv(result_dir / "raw_samples.csv", samples)
    summary = summarize_samples(samples)
    write_summary_json(result_dir / "summary.json", samples)
    (result_dir / "correctness.json").write_text(json.dumps(correctness, indent=2), encoding="utf-8")
    write_comparison_report(
        result_dir / "comparison.md",
        bench_id,
        {"api_base_url": config.api_base_url, "profile": config.profile},
        summary,
        cleanup_status,
    )
    return {"bench_id": bench_id, "result_dir": result_dir, "cleanup_status": cleanup_status}
```

In `main`, replace:

```python
raise SystemExit("Full benchmark orchestration is implemented in Task 8")
```

with:

```python
api_token = os.environ["DBAY_API_TOKEN"]
client = DbayClient(config.api_base_url, api_token, config.request_timeout_seconds)
result = run_benchmark_with_clients(config, plan, Path(config.result_root), client, PsycopgWorkload())
print(json.dumps({"bench_id": result["bench_id"], "result_dir": str(result["result_dir"]), "cleanup_status": result["cleanup_status"]}, indent=2))
return 0 if result["cleanup_status"]["cleanup_status"] == "clean" else 2
```

- [ ] **Step 4: Run orchestration test and full test suite**

Run:

```bash
uv run --project benchmarks/dbay_branch_version pytest benchmarks/dbay_branch_version/tests -v
```

Expected: PASS.

- [ ] **Step 5: Run dry-run command**

Run:

```bash
uv run --project benchmarks/dbay_branch_version dbay-branch-version-bench \
  --config benchmarks/dbay_branch_version/config.example.yaml \
  --dry-run
```

Expected: JSON output with `bench_id`, datasets `S` and `M`, and `will_create_database: true`.

- [ ] **Step 6: Commit orchestration**

Commit:

```bash
git add benchmarks/dbay_branch_version
git commit -m "feat(bench): orchestrate dbay branch version benchmark"
```

## Task 9: Final Verification and Operator Dry Run

**Files:**
- Modify: `benchmarks/dbay_branch_version/README.md` if verification finds command drift.

- [ ] **Step 1: Run all benchmark tests**

Run:

```bash
uv run --project benchmarks/dbay_branch_version pytest benchmarks/dbay_branch_version/tests -v
```

Expected: PASS.

- [ ] **Step 2: Run dry-run from documented command**

Run:

```bash
uv run --project benchmarks/dbay_branch_version dbay-branch-version-bench \
  --config benchmarks/dbay_branch_version/config.example.yaml \
  --dry-run
```

Expected: JSON contains `"datasets": ["S", "M"]` and does not require `DBAY_API_TOKEN`.

- [ ] **Step 3: Verify dataset L is blocked by default**

Run:

```bash
uv run --project benchmarks/dbay_branch_version dbay-branch-version-bench \
  --config benchmarks/dbay_branch_version/config.example.yaml \
  --datasets L \
  --dry-run
```

Expected: command exits non-zero with text containing `Dataset L requires`.

- [ ] **Step 4: Verify dataset L can be explicitly enabled**

Run:

```bash
uv run --project benchmarks/dbay_branch_version dbay-branch-version-bench \
  --config benchmarks/dbay_branch_version/config.example.yaml \
  --datasets L \
  --allow-large-dataset \
  --dry-run
```

Expected: JSON contains `"datasets": ["L"]`.

- [ ] **Step 5: Check git diff for secrets and generated artifacts**

Run:

```bash
git diff --check
rg -n "DBAY_API_TOKEN|Bearer |postgresql://[^\\[]|password|lk_" benchmarks/dbay_branch_version -S
```

Expected: `git diff --check` has no output. `rg` should only show documented env var names or redaction tests, not real secrets.

- [ ] **Step 6: Commit verification docs if changed**

If README changed:

```bash
git add benchmarks/dbay_branch_version/README.md
git commit -m "docs(bench): update branch version benchmark usage"
```

If README did not change, no commit is needed.

## Self-Review Notes

Spec coverage:

- Temporary DB creation and deletion: Tasks 3, 5, 8.
- Branch APIs: Tasks 3 and 8.
- DBay self-managed Version APIs: Tasks 3 and 8.
- Correctness checks and checksums: Tasks 4 and 8.
- Cleanup order and residual status: Task 5.
- Raw samples, summaries, reports: Tasks 2 and 7.
- Official comparison sources: Task 7.
- Dry-run and cleanup-only: Task 6.
- Dataset `L` explicit enablement: Tasks 1 and 9.

Completeness scan:

- No unresolved implementation gaps are intended in this plan.
- Commands include expected outcomes.
- Tests specify concrete behavior and failure modes.

Type consistency:

- `BenchmarkConfig`, `OperationSample`, `CleanupRegistry`, and client method names are introduced before later tasks use them.
- Runner orchestration uses the same method names defined in the DBay client task.
