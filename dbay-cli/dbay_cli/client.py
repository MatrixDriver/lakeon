import httpx
import time
from typing import Any, Optional


class DbayApiError(Exception):
    def __init__(self, status_code: int, body: Any = None):
        self.status_code = status_code
        self.body = body
        if isinstance(body, dict):
            err = body.get("error", body)
            msg = err.get("message", str(body)) if isinstance(err, dict) else str(err)
        else:
            msg = str(body)
        super().__init__(f"API Error [{status_code}]: {msg}")


class DbayClient:
    def __init__(self, endpoint: str, api_key: str | None = None):
        self.endpoint = endpoint.rstrip("/")
        self.api_key = api_key
        self.http = httpx.Client(verify=False, timeout=300)

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
                             json={"max_databases": max_databases, "max_storage_gb": max_storage_gb,
                                   "max_compute_cu": max_compute_cu})

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

    # -- Knowledge Bases --
    def list_knowledge_bases(self):
        return self._request("GET", "/knowledge/bases")

    def create_knowledge_base(self, name: str, description: str = None):
        body = {"name": name}
        if description:
            body["description"] = description
        return self._request("POST", "/knowledge/bases", json=body)

    def get_knowledge_base(self, kb_id: str):
        return self._request("GET", f"/knowledge/bases/{kb_id}")

    def delete_knowledge_base(self, kb_id: str):
        return self._request("DELETE", f"/knowledge/bases/{kb_id}")

    # -- Knowledge Documents --
    def get_upload_url(self, kb_id: str, filename: str):
        return self._request("GET", f"/knowledge/upload-url?kb_id={kb_id}&filename={filename}")

    def process_document(self, document_id: str):
        return self._request("POST", f"/knowledge/documents/{document_id}/process")

    def list_documents(self, kb_id: str):
        return self._request("GET", f"/knowledge/documents?kb_id={kb_id}")

    def get_document(self, document_id: str):
        return self._request("GET", f"/knowledge/documents/{document_id}")

    def delete_document(self, document_id: str):
        return self._request("DELETE", f"/knowledge/documents/{document_id}")

    # -- Knowledge Search --
    def search_knowledge(self, kb_id: str, query: str, top_k: int = 5,
                         document_ids: list | None = None):
        body = {"kb_id": kb_id, "query": query, "top_k": top_k}
        if document_ids:
            body["document_ids"] = document_ids
        return self._request("POST", "/knowledge/search", json=body)

    # -- Knowledge Chunks --
    def list_chunks(self, kb_id: str, doc_id: str, level: int = 0,
                    offset: int = 0, limit: int = 50):
        return self._request(
            "GET",
            f"/knowledge/bases/{kb_id}/documents/{doc_id}/chunks"
            f"?level={level}&offset={offset}&limit={limit}",
        )

    def list_kb_chunks(self, kb_id: str, doc_id: str | None = None,
                       status: str | None = None, offset: int = 0, limit: int = 50):
        params = f"?offset={offset}&limit={limit}"
        if doc_id:
            params += f"&doc_id={doc_id}"
        if status:
            params += f"&status={status}"
        return self._request("GET", f"/knowledge/bases/{kb_id}/chunks{params}")

    def get_chunk(self, kb_id: str, doc_id: str, chunk_index: int):
        return self._request(
            "GET",
            f"/knowledge/bases/{kb_id}/documents/{doc_id}/chunks/{chunk_index}",
        )

    def get_chunk_context(self, kb_id: str, doc_id: str, chunk_index: int):
        return self._request(
            "GET",
            f"/knowledge/bases/{kb_id}/documents/{doc_id}/chunks/{chunk_index}/context",
        )

    def get_fulltext(self, kb_id: str, doc_id: str):
        return self._request(
            "GET",
            f"/knowledge/bases/{kb_id}/documents/{doc_id}/fulltext",
        )

    def get_chunk_stats(self, kb_id: str, doc_id: str):
        return self._request(
            "GET",
            f"/knowledge/bases/{kb_id}/documents/{doc_id}/chunk-stats",
        )

    def get_write_task(self, kb_id: str, task_id: str):
        return self._request(
            "GET",
            f"/knowledge/bases/{kb_id}/write-tasks/{task_id}",
        )

    def _poll_write_task(self, kb_id: str, task_id: str,
                          timeout: int = 120, interval: float = 1) -> dict:
        """Poll a write task until it reaches a terminal state."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            task = self.get_write_task(kb_id, task_id)
            if task["status"] in ("SUCCEEDED", "FAILED"):
                if task["status"] == "FAILED":
                    raise DbayApiError(500, {"error": task.get("error", "Write task failed")})
                return task
            time.sleep(interval)
        raise DbayApiError(504, {"error": f"Write task {task_id} timed out after {timeout}s"})

    def edit_chunk(self, kb_id: str, doc_id: str, chunk_index: int, content: str):
        result = self._request(
            "PUT",
            f"/knowledge/bases/{kb_id}/documents/{doc_id}/chunks/{chunk_index}",
            json={"content": content},
        )
        if isinstance(result, dict) and "task_id" in result:
            return self._poll_write_task(kb_id, result["task_id"])
        return result

    def delete_chunk(self, kb_id: str, doc_id: str, chunk_index: int):
        result = self._request(
            "DELETE",
            f"/knowledge/bases/{kb_id}/documents/{doc_id}/chunks/{chunk_index}",
        )
        if isinstance(result, dict) and "task_id" in result:
            return self._poll_write_task(kb_id, result["task_id"])
        return result

    def create_chunk(self, kb_id: str, doc_id: str, content: str,
                     insert_after_index: int = -1):
        result = self._request(
            "POST",
            f"/knowledge/bases/{kb_id}/documents/{doc_id}/chunks",
            json={"content": content, "insert_after_index": insert_after_index},
        )
        if isinstance(result, dict) and "task_id" in result:
            return self._poll_write_task(kb_id, result["task_id"])
        return result

    def rechunk(self, kb_id: str, doc_id: str, max_tokens: int = 400,
                overlap_ratio: float = 0.15, custom_separator: str | None = None):
        body = {"max_tokens": max_tokens, "overlap_ratio": overlap_ratio}
        if custom_separator is not None:
            body["custom_separator"] = custom_separator
        return self._request(
            "POST",
            f"/knowledge/bases/{kb_id}/documents/{doc_id}/rechunk",
            json=body,
        )

    def rechunk_rollback(self, kb_id: str, doc_id: str, branch_id: str):
        result = self._request(
            "POST",
            f"/knowledge/bases/{kb_id}/documents/{doc_id}/rechunk/rollback",
            json={"branch_id": branch_id},
        )
        if isinstance(result, dict) and "task_id" in result:
            return self._poll_write_task(kb_id, result["task_id"])
        return result

    def list_rechunk_branches(self, kb_id: str, doc_id: str):
        return self._request(
            "GET",
            f"/knowledge/bases/{kb_id}/documents/{doc_id}/rechunk/branches",
        )

    # -- Database User --
    def list_users(self, db_id: str) -> list:
        return self._request("GET", f"/databases/{db_id}/users")

    def create_user(self, db_id: str, username: str, role: str = "READER") -> dict:
        return self._request("POST", f"/databases/{db_id}/users", json={"username": username, "role": role})

    def delete_user(self, db_id: str, user_id: str) -> dict:
        return self._request("DELETE", f"/databases/{db_id}/users/{user_id}")
