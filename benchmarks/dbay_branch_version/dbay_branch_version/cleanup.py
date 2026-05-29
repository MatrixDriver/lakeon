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
            client.delete_version(
                registry.database_id,
                registry.database_name,
                version["branch_id"],
                version["id"],
            )
        except Exception as exc:
            failures.append(
                {"type": "version", "id": version.get("id"), "error": str(exc)}
            )

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
            failures.append(
                {"type": "branch", "id": branch.get("id"), "error": str(exc)}
            )

    try:
        client.delete_database(registry.database_id, registry.database_name)
    except Exception as exc:
        failures.append(
            {"type": "database", "id": registry.database_id, "error": str(exc)}
        )

    return {
        "bench_id": registry.bench_id,
        "database_id": registry.database_id,
        "database_name": registry.database_name,
        "cleanup_status": "failed" if failures else "clean",
        "failures": failures,
    }
