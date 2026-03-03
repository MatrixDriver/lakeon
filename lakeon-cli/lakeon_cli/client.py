"""
LakeOn API 客户端

使用 httpx 调用管控面 REST API。配置从 lakeon_cli.config 读取。
"""

import httpx
from typing import Any, Optional

from lakeon_cli.config import get_api_url, get_api_key


class LakeonApiError(Exception):
    """LakeOn API 调用异常"""
    pass


class LakeonConnectionError(LakeonApiError):
    """网络连接异常"""
    pass


class LakeonClient:
    """LakeOn 管控面 API 客户端"""

    def __init__(self) -> None:
        self.base_url = get_api_url()
        self.api_key = get_api_key()
        self._client = httpx.Client(
            base_url=self.base_url + "/api/v1",
            headers={"Authorization": f"Bearer {self.api_key}"},
            timeout=30.0,
        )

    def _request(self, method: str, path: str, **kwargs: Any) -> Any:
        """统一发起请求，包装网络异常"""
        try:
            response = self._client.request(method, path, **kwargs)
        except httpx.ConnectError as e:
            raise LakeonConnectionError(f"Cannot connect to {self.base_url}: {e}") from e
        except httpx.TimeoutException as e:
            raise LakeonConnectionError(f"Request timed out: {e}") from e
        except httpx.HTTPError as e:
            raise LakeonConnectionError(f"Network error: {e}") from e
        return self._handle_response(response)

    def _handle_response(self, response: httpx.Response) -> Any:
        """统一处理 API 响应，>=400 抛出异常"""
        if response.status_code >= 400:
            try:
                body = response.json()
                error = body.get("error", {})
                code = error.get("code", response.status_code)
                message = error.get("message", response.text)
            except Exception:
                code = response.status_code
                message = response.text
            raise LakeonApiError(f"API Error [{code}]: {message}")
        if response.status_code == 204:
            return {}
        return response.json()

    # ── Tenant ──────────────────────────────────────────

    def create_tenant(self, name: str) -> dict:
        return self._request("POST", "/tenants", json={"name": name})

    def get_tenant(self, tenant_id: str) -> dict:
        return self._request("GET", f"/tenants/{tenant_id}")

    # ── Database ────────────────────────────────────────

    def create_database(
        self,
        name: str,
        compute_size: Optional[str] = None,
        suspend_timeout: Optional[str] = None,
        storage_limit_gb: Optional[int] = None,
    ) -> dict:
        body: dict[str, Any] = {"name": name}
        if compute_size:
            body["compute_size"] = compute_size
        if suspend_timeout:
            body["suspend_timeout"] = suspend_timeout
        if storage_limit_gb:
            body["storage_limit_gb"] = storage_limit_gb
        return self._request("POST", "/databases", json=body)

    def list_databases(self) -> list:
        return self._request("GET", "/databases")

    def get_database(self, db_id: str) -> dict:
        return self._request("GET", f"/databases/{db_id}")

    def update_database(self, db_id: str, **kwargs: Any) -> dict:
        return self._request("PATCH", f"/databases/{db_id}", json=kwargs)

    def delete_database(self, db_id: str, force: bool = False) -> dict:
        return self._request("DELETE", f"/databases/{db_id}", params={"force": force})

    def suspend_database(self, db_id: str) -> dict:
        return self._request("POST", f"/databases/{db_id}/suspend")

    def resume_database(self, db_id: str) -> dict:
        return self._request("POST", f"/databases/{db_id}/resume")

    # ── Branch ──────────────────────────────────────────

    def create_branch(self, db_id: str, name: str, start_compute: bool = False) -> dict:
        return self._request(
            "POST",
            f"/databases/{db_id}/branches",
            json={"name": name, "start_compute": start_compute},
        )

    def list_branches(self, db_id: str) -> list:
        return self._request("GET", f"/databases/{db_id}/branches")

    def delete_branch(self, db_id: str, branch_id: str) -> dict:
        return self._request("DELETE", f"/databases/{db_id}/branches/{branch_id}")

    # ── Helper ──────────────────────────────────────────

    def find_database_by_name(self, name: str) -> Optional[dict]:
        """通过名称查找数据库，返回 None 如果不存在"""
        databases = self.list_databases()
        return next((d for d in databases if d["name"] == name), None)

    def find_branch_by_name(self, db_id: str, name: str) -> Optional[dict]:
        """通过名称查找分支"""
        branches = self.list_branches(db_id)
        return next((b for b in branches if b["name"] == name), None)
