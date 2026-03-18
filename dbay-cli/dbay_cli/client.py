import httpx
from typing import Any, Optional


class DbayApiError(Exception):
    def __init__(self, status_code: int, body: Any = None):
        self.status_code = status_code
        self.body = body
        msg = body.get("error", {}).get("message", str(body)) if isinstance(body, dict) else str(body)
        super().__init__(f"API Error [{status_code}]: {msg}")


class DbayClient:
    def __init__(self, endpoint: str, api_key: str | None = None):
        self.endpoint = endpoint.rstrip("/")
        self.api_key = api_key
        self.http = httpx.Client(verify=False, timeout=30)

    def _headers(self) -> dict:
        h = {"Content-Type": "application/json"}
        if self.api_key:
            h["Authorization"] = f"Bearer {self.api_key}"
        return h

    def _url(self, path: str) -> str:
        return f"{self.endpoint}/api/v1{path}"

    def _request(self, method: str, path: str, **kwargs) -> Any:
        resp = self.http.request(method, self._url(path), headers=self._headers(), **kwargs)
        if resp.status_code >= 400:
            try:
                body = resp.json()
            except Exception:
                body = {"error": {"message": resp.text}}
            raise DbayApiError(resp.status_code, body)
        if resp.status_code == 204:
            return {}
        return resp.json() if resp.content else None

    def _request_raw(self, method: str, path: str, **kwargs) -> httpx.Response:
        """Returns raw response without raising exceptions. For tests to check status codes."""
        return self.http.request(method, self._url(path), headers=self._headers(), **kwargs)

    # -- Admin --
    def admin_create_invite_code(self, max_uses: int = 1) -> dict:
        return self._request("POST", "/admin/invite-codes", json={"max_uses": max_uses})

    def admin_update_quota(self, tenant_id: str, max_databases: int = 100,
                           max_storage_gb: int = 100, max_compute_cu: int = 100) -> dict:
        return self._request("PUT", f"/admin/tenants/{tenant_id}/quota",
                             json={"maxDatabases": max_databases, "maxStorageGb": max_storage_gb,
                                   "maxComputeCu": max_compute_cu})

    def admin_delete_invite_code(self, code: str) -> dict:
        return self._request("DELETE", f"/admin/invite-codes/{code}")

    # -- Tenant --
    def create_tenant(self, username: str, password: str, name: str | None = None,
                      invite_code: str | None = None) -> dict:
        body: dict[str, Any] = {"username": username, "password": password}
        if name:
            body["name"] = name
        if invite_code:
            body["inviteCode"] = invite_code
        return self._request("POST", "/tenants", json=body)

    def login(self, username: str, password: str) -> dict:
        return self._request("POST", "/auth/login", json={"username": username, "password": password})

    def get_me(self) -> dict:
        return self._request("GET", "/tenants/me")

    # -- Database --
    def create_database(self, name: str, compute_size: str = "1cu") -> dict:
        return self._request("POST", "/databases", json={"name": name, "compute_size": compute_size})

    def list_databases(self) -> list:
        return self._request("GET", "/databases")

    def get_database(self, db_id: str) -> dict:
        return self._request("GET", f"/databases/{db_id}")

    def delete_database(self, db_id: str) -> dict:
        return self._request("DELETE", f"/databases/{db_id}")

    def suspend_database(self, db_id: str) -> dict:
        return self._request("POST", f"/databases/{db_id}/suspend")

    def resume_database(self, db_id: str) -> dict:
        return self._request("POST", f"/databases/{db_id}/resume")

    def find_database_by_name(self, name: str) -> Optional[dict]:
        return next((d for d in self.list_databases() if d["name"] == name), None)

    # -- Branch --
    def list_branches(self, db_id: str) -> list:
        return self._request("GET", f"/databases/{db_id}/branches")

    def create_branch(self, db_id: str, name: str, parent_branch_id: str | None = None) -> dict:
        body: dict[str, Any] = {"name": name}
        if parent_branch_id:
            body["parentBranchId"] = parent_branch_id
        return self._request("POST", f"/databases/{db_id}/branches", json=body)

    def delete_branch(self, db_id: str, branch_id: str) -> dict:
        return self._request("DELETE", f"/databases/{db_id}/branches/{branch_id}")

    def promote_branch(self, db_id: str, branch_id: str) -> dict:
        return self._request("POST", f"/databases/{db_id}/branches/{branch_id}/promote")

    def restore_branch(self, db_id: str, branch_id: str,
                       target_version_id: str | None = None,
                       target_lsn: str | None = None) -> dict:
        body: dict[str, Any] = {}
        if target_version_id:
            body["target_version_id"] = target_version_id
        if target_lsn:
            body["target_lsn"] = target_lsn
        return self._request("POST", f"/databases/{db_id}/branches/{branch_id}/restore", json=body)

    def find_branch_by_name(self, db_id: str, name: str) -> Optional[dict]:
        return next((b for b in self.list_branches(db_id) if b["name"] == name), None)

    # -- Version --
    def list_versions(self, db_id: str, branch_id: str) -> list:
        return self._request("GET", f"/databases/{db_id}/branches/{branch_id}/versions")

    def create_version(self, db_id: str, branch_id: str, name: str, description: str | None = None) -> dict:
        body: dict[str, Any] = {"name": name}
        if description:
            body["description"] = description
        return self._request("POST", f"/databases/{db_id}/branches/{branch_id}/versions", json=body)

    def delete_version(self, db_id: str, branch_id: str, version_id: str) -> dict:
        return self._request("DELETE", f"/databases/{db_id}/branches/{branch_id}/versions/{version_id}")

    def squash_versions(self, db_id: str, branch_id: str, from_version_id: str, to_version_id: str) -> dict:
        return self._request("POST", f"/databases/{db_id}/branches/{branch_id}/versions/squash",
                             json={"from_version_id": from_version_id, "to_version_id": to_version_id})

    # -- Database User --
    def list_users(self, db_id: str) -> list:
        return self._request("GET", f"/databases/{db_id}/users")

    def create_user(self, db_id: str, username: str, role: str = "READER") -> dict:
        return self._request("POST", f"/databases/{db_id}/users", json={"username": username, "role": role})

    def delete_user(self, db_id: str, user_id: str) -> dict:
        return self._request("DELETE", f"/databases/{db_id}/users/{user_id}")
