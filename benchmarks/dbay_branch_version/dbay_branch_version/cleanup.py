from __future__ import annotations

import json
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

from dbay_branch_version.metrics import redact_secret


BENCHMARK_DATABASE_PREFIX = "bench-branch-version-"


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

    def status() -> dict[str, Any]:
        return {
            "bench_id": registry.bench_id,
            "database_id": registry.database_id,
            "database_name": registry.database_name,
            "cleanup_status": "failed" if failures else "clean",
            "failures": failures,
        }

    def record_failure(failure_type: str, resource_id: str | None, exc: Exception) -> None:
        failures.append(
            {"type": failure_type, "id": resource_id, "error": redact_secret(str(exc))}
        )

    if not registry.database_name.startswith(BENCHMARK_DATABASE_PREFIX):
        failures.append(
            {
                "type": "safety",
                "id": registry.database_id,
                "error": (
                    "Refusing cleanup for non-benchmark database "
                    f"{registry.database_name!r}"
                ),
            }
        )
        return status()

    for version in list(registry.versions):
        try:
            client.delete_version(
                registry.database_id,
                registry.database_name,
                version["branch_id"],
                version["id"],
            )
        except Exception as exc:
            record_failure("version", version.get("id"), exc)

    for branch in list(registry.branches):
        if branch.get("is_default") or branch.get("name") == "main":
            continue
        try:
            client.delete_branch(
                registry.database_id,
                registry.database_name,
                branch["id"],
                branch.get("name", ""),
                bool(branch.get("is_default", False)),
            )
        except Exception as exc:
            record_failure("branch", branch.get("id"), exc)

    try:
        client.delete_database(registry.database_id, registry.database_name)
    except Exception as exc:
        record_failure("database", registry.database_id, exc)

    return status()
