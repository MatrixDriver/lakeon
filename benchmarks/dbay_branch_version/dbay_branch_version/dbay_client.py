from __future__ import annotations

import time
from datetime import datetime, timezone
from typing import Any

import httpx

from .metrics import OperationSample, redact_secret


class DbayApiError(RuntimeError):
    def __init__(
        self,
        status_code: int,
        body: Any,
        sample: OperationSample | None = None,
    ):
        self.status_code = status_code
        self.body = body
        self.sample = sample
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
        body, sample = self._request_sample(
            "GET",
            f"/databases/{db_id}/branches",
            scenario="branch",
            operation="list",
        )
        return body, sample

    def get_branch(self, db_id: str, branch_id: str) -> tuple[dict, OperationSample]:
        return self._request_sample(
            "GET",
            f"/databases/{db_id}/branches/{branch_id}",
            scenario="branch",
            operation="get",
        )

    def delete_branch(
        self,
        db_id: str,
        database_name: str,
        branch_id: str,
        branch_name: str,
        is_default: bool = False,
    ) -> tuple[dict, OperationSample]:
        self._assert_benchmark_name(database_name)
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

    def create_version(
        self,
        db_id: str,
        branch_id: str,
        name: str,
        description: str = "",
    ) -> tuple[dict, OperationSample]:
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

    def delete_version(
        self,
        db_id: str,
        database_name: str,
        branch_id: str,
        version_id: str,
    ) -> tuple[dict, OperationSample]:
        self._assert_benchmark_name(database_name)
        return self._request_sample(
            "DELETE",
            f"/databases/{db_id}/branches/{branch_id}/versions/{version_id}",
            scenario="version",
            operation="delete",
            resource_type="version",
            resource_id=version_id,
        )

    def squash_versions(
        self,
        db_id: str,
        database_name: str,
        branch_id: str,
        from_version_id: str,
        to_version_id: str,
    ) -> tuple[list, OperationSample]:
        self._assert_benchmark_name(database_name)
        body, sample = self._request_sample(
            "POST",
            f"/databases/{db_id}/branches/{branch_id}/versions/squash",
            scenario="version",
            operation="squash",
            json={"from_version_id": from_version_id, "to_version_id": to_version_id},
        )
        return body, sample

    def _request_sample(
        self,
        method: str,
        path: str,
        scenario: str,
        operation: str,
        **kwargs,
    ) -> tuple[Any, OperationSample]:
        resource_type = kwargs.pop("resource_type", "")
        resource_id = kwargs.pop("resource_id", "")
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
                resource_type=resource_type,
                resource_id=resource_id,
                started_at=started_at,
                ended_at=datetime.now(timezone.utc).isoformat(),
                api_latency_ms=elapsed,
                http_status=response.status_code,
                success=response.status_code < 400,
            )
            if response.status_code >= 400:
                sample.success = False
                sample.error_message = redact_secret(str(body))
                raise DbayApiError(response.status_code, body, sample=sample)
            return body, sample
        except httpx.HTTPError as exc:
            elapsed = (time.perf_counter() - start) * 1000
            sample = OperationSample(
                bench_id="",
                dataset="",
                scenario=scenario,
                operation=operation,
                resource_type=resource_type,
                resource_id=resource_id,
                started_at=started_at,
                ended_at=datetime.now(timezone.utc).isoformat(),
                api_latency_ms=elapsed,
                success=False,
                error_code=exc.__class__.__name__,
                error_message=redact_secret(str(exc)),
            )
            raise DbayApiError(0, {"error": {"message": sample.error_message}}, sample=sample) from exc

    @staticmethod
    def _decode_body(response: httpx.Response) -> Any:
        if response.status_code == 204 or not response.content:
            return {}
        try:
            return response.json()
        except ValueError:
            return response.text

    @staticmethod
    def _assert_benchmark_name(name: str) -> None:
        if not name.startswith("bench-branch-version-"):
            raise UnsafeResourceError(f"Refusing non-benchmark database name: {name}")
