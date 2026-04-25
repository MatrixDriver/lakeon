"""LakeonAdminClient — httpx wrapper around lakeon-api admin REST endpoints.

All new dbay-sre-mcp tools (find_database, database_status, etc.) go through this
client so token handling, base URL, timeouts are centralised.

Auth header convention: `Authorization: Bearer <token>` (per ApiKeyFilter.java:111).
"""
from __future__ import annotations

import os
from typing import Any

import httpx


class LakeonAdminClient:
    def __init__(
        self,
        *,
        base_url: str | None = None,
        token: str | None = None,
        timeout: float = 30.0,
    ) -> None:
        self._base_url = (
            base_url or os.environ.get("LAKEON_API_BASE_URL", "https://api.dbay.cloud:8443/api/v1")
        ).rstrip("/")
        self._token = token or os.environ["LAKEON_ADMIN_TOKEN"]
        self._timeout = timeout

    def _get(self, path: str, params: dict | None = None) -> dict | None:
        url = f"{self._base_url}{path}"
        with httpx.Client(timeout=self._timeout) as client:
            resp = client.get(
                url,
                headers={"Authorization": f"Bearer {self._token}"},
                params=params,
                timeout=self._timeout,
            )
            if resp.status_code == 404:
                return None
            resp.raise_for_status()
            return resp.json()

    # ---- Databases ----

    def get_database(self, *, db_id: str) -> dict | None:
        """GET /admin/databases/{databaseId}"""
        return self._get(f"/admin/databases/{db_id}")

    def list_databases(self, *, name_contains: str | None = None, tenant_id: str | None = None) -> list[dict]:
        """GET /admin/databases — paginated list, supports search."""
        params: dict[str, Any] = {}
        if name_contains:
            params["search"] = name_contains
        if tenant_id:
            params["tenant_id"] = tenant_id
        body = self._get("/admin/databases", params=params)
        if not body:
            return []
        return body.get("items", [])

    # ---- Tenants ----

    def get_tenant(self, *, tenant_id: str) -> dict | None:
        """GET /admin/tenants/{tenantId}"""
        return self._get(f"/admin/tenants/{tenant_id}")

    def list_tenants(self, *, name_contains: str | None = None) -> list[dict]:
        """GET /admin/tenants"""
        params: dict[str, Any] = {}
        if name_contains:
            params["search"] = name_contains
        body = self._get("/admin/tenants", params=params)
        if not body:
            return []
        return body.get("items", [])

    # ---- Operations / Compute / Health ----

    def get_operations(self, *, component: str | None = None, since: str = "1h") -> list[dict]:
        """GET /admin/operations — k8s + lifecycle events."""
        params: dict[str, Any] = {"since": since}
        if component:
            params["component"] = component
        body = self._get("/admin/operations", params=params)
        if not body:
            return []
        return body.get("items", [])

    def get_compute_cold_start(self, *, since: str = "24h", db_id: str | None = None) -> dict:
        """GET /admin/compute/cold-start — aggregate cold-start metrics."""
        params: dict[str, Any] = {"since": since}
        if db_id:
            params["db_id"] = db_id
        return self._get("/admin/compute/cold-start", params=params) or {}

    def system_health(self, *, component: str | None = None) -> dict:
        """GET /admin/system/health[/{component}]"""
        if component:
            return self._get(f"/admin/system/health/{component}") or {}
        return self._get("/admin/system/health") or {}
