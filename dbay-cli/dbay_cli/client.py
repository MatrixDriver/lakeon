import httpx
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
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
    def __init__(self, endpoint: str, api_key: str | None = None, extra_headers: dict | None = None):
        self.endpoint = endpoint.rstrip("/")
        self.api_key = api_key
        self.extra_headers = extra_headers or {}
        self.http = httpx.Client(verify=False, timeout=300)

    def _headers(self) -> dict:
        h = {"Content-Type": "application/json"}
        if self.api_key:
            h["Authorization"] = f"Bearer {self.api_key}"
        h.update(self.extra_headers)
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

    def admin_batch_delete_tenants(self, tenant_ids: list) -> dict:
        return self._request("DELETE", "/admin/tenants/batch", json={"ids": tenant_ids})

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
            body["parent_branch_id"] = parent_branch_id
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

    def batch_get_upload_urls(self, kb_id: str, files: list[dict]):
        """Get presigned upload URLs for multiple files. files: [{"filename": "..."}]"""
        return self._request("POST", "/knowledge/batch-upload-urls", json={"kb_id": kb_id, "files": files})

    def batch_process_documents(self, document_ids: list[str]):
        """Submit batch processing for multiple uploaded documents."""
        return self._request("POST", "/knowledge/batch-process", json={"document_ids": document_ids})

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

    # -- Datalake Jobs --
    def submit_datalake_job(self, body: dict) -> dict:
        return self._request("POST", "/datalake/jobs", json=body)

    def list_datalake_jobs(self, status: str | None = None) -> list:
        path = "/datalake/jobs"
        if status:
            path += f"?status={status}"
        return self._request("GET", path)

    def get_datalake_job(self, job_id: str) -> dict:
        return self._request("GET", f"/datalake/jobs/{job_id}")

    def cancel_datalake_job(self, job_id: str) -> dict:
        return self._request("DELETE", f"/datalake/jobs/{job_id}")

    def stream_datalake_logs_raw(self, job_id: str):
        """Returns raw SSE response for log streaming."""
        import httpx
        url = self._url(f"/datalake/jobs/{job_id}/logs")
        headers = {**self._headers(), "Accept": "text/event-stream"}
        return httpx.Client(verify=False, timeout=None).stream("GET", url, headers=headers)

    # -- Datasets --
    def create_dataset(self, name: str, database_id: str, query_mode: str, 
                      tables: list | None = None, sql: str | None = None, 
                      description: str | None = None) -> dict:
        body = {"name": name, "database_id": database_id, "query_mode": query_mode}
        if tables: body["tables"] = tables
        if sql: body["sql"] = sql
        if description: body["description"] = description
        return self._request("POST", "/datasets", json=body)

    def list_datasets(self, status: str | None = None) -> list:
        path = "/datasets"
        if status: path += f"?status={status}"
        return self._request("GET", path)

    def get_dataset(self, dataset_id: str) -> dict:
        return self._request("GET", f"/datasets/{dataset_id}")

    def trigger_export(self, dataset_id: str) -> dict:
        return self._request("POST", f"/datasets/{dataset_id}/export")

    def delete_dataset(self, dataset_id: str) -> dict:
        return self._request("DELETE", f"/datasets/{dataset_id}")

    def get_dataset_upload_urls(self, name: str, files: list[dict],
                                description: str | None = None) -> dict:
        body: dict[str, Any] = {"name": name, "files": files}
        if description:
            body["description"] = description
        return self._request("POST", "/datasets/upload-urls", json=body)

    def finalize_dataset(self, dataset_id: str) -> dict:
        return self._request("POST", f"/datasets/{dataset_id}/finalize")

    def upload_dataset(self, name: str, path: str,
                       description: str | None = None) -> dict:
        """Scan path (file or directory), upload all files via presigned URLs, and finalize."""
        p = Path(path)
        if not p.exists():
            raise FileNotFoundError(f"Path not found: {path}")

        # Collect files with relative paths
        if p.is_file():
            file_entries = [{"path": p.name, "size": p.stat().st_size, "_abs": str(p)}]
        else:
            file_entries = []
            for f in sorted(p.rglob("*")):
                if f.is_file():
                    rel = str(f.relative_to(p.parent))
                    file_entries.append({"path": rel, "size": f.stat().st_size, "_abs": str(f)})

        if not file_entries:
            raise ValueError(f"No files found at: {path}")

        # Get presigned upload URLs
        api_files = [{"path": e["path"], "size": e["size"]} for e in file_entries]
        result = self.get_dataset_upload_urls(name, api_files, description)
        dataset_id = result["dataset_id"]
        uploads = result["uploads"]

        # Build mapping from path to local abs path
        abs_by_path = {e["path"]: e["_abs"] for e in file_entries}

        def _upload_one(upload: dict):
            local_path = abs_by_path[upload["path"]]
            file_bytes = Path(local_path).read_bytes()
            resp = httpx.put(upload["upload_url"], content=file_bytes, timeout=300)
            resp.raise_for_status()

        # Upload concurrently
        with ThreadPoolExecutor(max_workers=4) as pool:
            futures = [pool.submit(_upload_one, u) for u in uploads]
            for fut in futures:
                fut.result()

        return self.finalize_dataset(dataset_id)

    # -- Database extended --
    def update_database(self, db_id: str, **kwargs) -> dict:
        return self._request("PATCH", f"/databases/{db_id}", json=kwargs)

    def reset_database_password(self, db_id: str) -> dict:
        return self._request("POST", f"/databases/{db_id}/reset-password")

    def get_database_metrics(self, db_id: str) -> dict:
        return self._request("GET", f"/databases/{db_id}/metrics")

    def get_database_logs(self, db_id: str, tail: int = 100) -> Any:
        return self._request("GET", f"/databases/{db_id}/logs?tail={tail}")

    def get_allowed_ips(self, db_id: str) -> dict:
        return self._request("GET", f"/databases/{db_id}/allowed-ips")

    def set_allowed_ips(self, db_id: str, ips: list) -> dict:
        return self._request("PUT", f"/databases/{db_id}/allowed-ips", json={"ips": ips})

    def clear_allowed_ips(self, db_id: str) -> dict:
        return self._request("DELETE", f"/databases/{db_id}/allowed-ips")

    # -- Database Query & Schema --
    def list_schemas(self, db_id: str) -> list:
        return self._request("GET", f"/databases/{db_id}/schemas")

    def list_tables(self, db_id: str, schema: str = "public") -> list:
        return self._request("GET", f"/databases/{db_id}/schemas/{schema}/tables")

    def list_columns(self, db_id: str, schema: str, table: str) -> list:
        return self._request("GET", f"/databases/{db_id}/schemas/{schema}/tables/{table}/columns")

    def list_indexes(self, db_id: str, schema: str, table: str) -> list:
        return self._request("GET", f"/databases/{db_id}/schemas/{schema}/tables/{table}/indexes")

    def list_constraints(self, db_id: str, schema: str, table: str) -> list:
        return self._request("GET", f"/databases/{db_id}/schemas/{schema}/tables/{table}/constraints")

    def query_table_data(self, db_id: str, schema: str, table: str,
                         limit: int = 50, offset: int = 0) -> dict:
        return self._request("GET", f"/databases/{db_id}/schemas/{schema}/tables/{table}/data?limit={limit}&offset={offset}")

    def get_table_stats(self, db_id: str, schema: str, table: str) -> dict:
        return self._request("GET", f"/databases/{db_id}/schemas/{schema}/tables/{table}/stats")

    def execute_query(self, db_id: str, sql: str, branch_id: str | None = None) -> dict:
        body: dict[str, Any] = {"sql": sql}
        if branch_id:
            body["branch_id"] = branch_id
        return self._request("POST", f"/databases/{db_id}/query", json=body)

    def get_connections(self, db_id: str) -> dict:
        return self._request("GET", f"/databases/{db_id}/connections")

    # -- Query History --
    def get_query_history(self, db_id: str) -> dict:
        return self._request("GET", f"/databases/{db_id}/query-history")

    def clear_query_history(self, db_id: str) -> dict:
        return self._request("DELETE", f"/databases/{db_id}/query-history")

    def list_all_query_history(self) -> dict:
        return self._request("GET", "/query-history")

    def clear_all_query_history(self) -> dict:
        return self._request("DELETE", "/query-history")

    # -- Operations --
    def get_database_operations(self, db_id: str) -> dict:
        return self._request("GET", f"/databases/{db_id}/operations")

    def get_recent_operations(self) -> list:
        return self._request("GET", "/operations/recent")

    # -- Backups --
    def create_backup(self, db_id: str, name: str | None = None) -> dict:
        body = {}
        if name:
            body["name"] = name
        return self._request("POST", f"/databases/{db_id}/backups", json=body)

    def list_backups(self, db_id: str) -> list:
        return self._request("GET", f"/databases/{db_id}/backups")

    def list_all_backups(self) -> list:
        return self._request("GET", "/backups")

    def get_backup(self, db_id: str, backup_id: str) -> dict:
        return self._request("GET", f"/databases/{db_id}/backups/{backup_id}")

    def restore_backup(self, db_id: str, backup_id: str, new_name: str | None = None) -> dict:
        body = {}
        if new_name:
            body["name"] = new_name
        return self._request("POST", f"/databases/{db_id}/backups/{backup_id}/restore", json=body)

    def delete_backup(self, db_id: str, backup_id: str) -> dict:
        return self._request("DELETE", f"/databases/{db_id}/backups/{backup_id}")

    # -- Database Users extended --
    def update_user_role(self, db_id: str, user_id: str, role: str) -> dict:
        return self._request("PUT", f"/databases/{db_id}/users/{user_id}/role", json={"role": role})

    def reset_user_password(self, db_id: str, user_id: str) -> dict:
        return self._request("POST", f"/databases/{db_id}/users/{user_id}/reset-password")

    # -- Extensions --
    def list_extensions(self, db_id: str) -> list:
        return self._request("GET", f"/databases/{db_id}/extensions")

    def enable_extension(self, db_id: str, name: str) -> dict:
        return self._request("POST", f"/databases/{db_id}/extensions/{name}/enable")

    def disable_extension(self, db_id: str, name: str) -> dict:
        return self._request("POST", f"/databases/{db_id}/extensions/{name}/disable")

    # -- Tenant / Account --
    def check_username(self, username: str) -> dict:
        return self._request("GET", f"/auth/check-username?username={username}")

    def list_api_keys(self) -> list:
        return self._request("GET", "/api-keys")

    def create_api_key(self, name: str | None = None) -> dict:
        body = {}
        if name:
            body["name"] = name
        return self._request("POST", "/api-keys", json=body)

    def delete_api_key(self, key_id: str) -> dict:
        return self._request("DELETE", f"/api-keys/{key_id}")

    # -- Branch tree --
    def get_branch_tree(self, db_id: str) -> dict:
        return self._request("GET", f"/databases/{db_id}/branches/tree")

    # -- Schema Diff --
    def get_schema_diff(self, db_id: str, **params) -> dict:
        qs = "&".join(f"{k}={v}" for k, v in params.items() if v)
        path = f"/databases/{db_id}/diff/schema"
        if qs:
            path += f"?{qs}"
        return self._request("GET", path)

    # -- Audit --
    def get_audit_config(self, db_id: str) -> dict:
        return self._request("GET", f"/databases/{db_id}/audit/config")

    def update_audit_config(self, db_id: str, **kwargs) -> dict:
        return self._request("PUT", f"/databases/{db_id}/audit/config", json=kwargs)

    def get_audit_logs(self, db_id: str, limit: int = 50, offset: int = 0) -> dict:
        return self._request("GET", f"/databases/{db_id}/audit/logs?limit={limit}&offset={offset}")

    # ── Memory Bases ──────────────────────────────────────────────
    def list_memory_bases(self) -> list:
        return self._request("GET", "/memory/bases")

    def create_memory_base(self, name: str, description: str = None,
                           one_llm_mode: bool = False, encrypted: bool = False,
                           encrypted_dek: str = None, kdf_salt: str = None,
                           embedding_dim: int = None) -> dict:
        body: dict = {"name": name, "one_llm_mode": one_llm_mode}
        if description:
            body["description"] = description
        if encrypted:
            body["encrypted"] = True
            body["encrypted_dek"] = encrypted_dek
            body["kdf_salt"] = kdf_salt
            body["embedding_dim"] = embedding_dim
        return self._request("POST", "/memory/bases", json=body)

    def get_memory_base(self, mem_id: str) -> dict:
        return self._request("GET", f"/memory/bases/{mem_id}")

    def delete_memory_base(self, mem_id: str) -> dict:
        return self._request("DELETE", f"/memory/bases/{mem_id}")

    # ── Memory Operations ─────────────────────────────────────────
    def mem_ingest(self, mem_id: str, content: str, role: str = "user",
                   signal: str = "memory", memory_type: str = None,
                   importance: float = None, source: str = None) -> dict:
        body: dict = {"content": content, "role": role, "signal": signal}
        if memory_type is not None:
            body["memory_type"] = memory_type
        if importance is not None:
            body["importance"] = importance
        if source is not None:
            body["source"] = source
        return self._request("POST", f"/memory/bases/{mem_id}/ingest", json=body)

    def mem_ingest_extracted(self, mem_id: str, message_id: str, data: dict) -> dict:
        return self._request("POST", f"/memory/bases/{mem_id}/ingest_extracted",
                             json={"message_id": message_id, "data": data})

    def mem_recall(self, mem_id: str, query: str, top_k: int = 10,
                   memory_types: list = None) -> dict:
        body: dict = {"query": query, "top_k": top_k}
        if memory_types:
            body["memory_types"] = memory_types
        return self._request("POST", f"/memory/bases/{mem_id}/recall", json=body)

    def mem_list(self, mem_id: str, memory_type: str = None,
                 offset: int = 0, limit: int = 20) -> dict:
        params = {"offset": str(offset), "limit": str(limit)}
        if memory_type:
            params["memory_type"] = memory_type
        return self._request("GET", f"/memory/bases/{mem_id}/memories", params=params)

    def mem_delete(self, mem_id: str, memory_id: int) -> dict:
        return self._request("DELETE", f"/memory/bases/{mem_id}/memories/{memory_id}")

    def mem_stats(self, mem_id: str) -> dict:
        return self._request("GET", f"/memory/bases/{mem_id}/stats")

    def mem_digest(self, mem_id: str) -> dict:
        return self._request("POST", f"/memory/bases/{mem_id}/digest")

    def mem_digest_extracted(self, mem_id: str, data: dict) -> dict:
        return self._request("POST", f"/memory/bases/{mem_id}/digest_extracted",
                             json={"data": data})

    # ── Pipelines ─────────────────────────────────────────────────
    def list_pipelines(self) -> list:
        return self._request("GET", "/pipelines")

    def get_pipeline(self, pipeline_id: str) -> dict:
        return self._request("GET", f"/pipelines/{pipeline_id}")

    def create_pipeline(self, name: str, data_type: str,
                        description: str | None = None,
                        source_template_id: str | None = None,
                        dag_yaml: str | None = None) -> dict:
        body: dict[str, Any] = {"name": name, "data_type": data_type}
        if description:
            body["description"] = description
        if source_template_id:
            body["source_template_id"] = source_template_id
        if dag_yaml:
            body["dag_yaml"] = dag_yaml
        return self._request("POST", "/pipelines", json=body)

    def delete_pipeline(self, pipeline_id: str) -> dict:
        return self._request("DELETE", f"/pipelines/{pipeline_id}")

    def list_pipeline_templates(self) -> list:
        return self._request("GET", "/pipelines/templates")

    # ── Pipeline Versions ─────────────────────────────────────────
    def list_pipeline_versions(self, pipeline_id: str) -> list:
        return self._request("GET", f"/pipelines/{pipeline_id}/versions")

    def get_pipeline_version(self, pipeline_id: str, version: int) -> dict:
        return self._request("GET", f"/pipelines/{pipeline_id}/versions/{version}")

    def create_pipeline_version(self, pipeline_id: str, dag_yaml: str,
                                changelog: str | None = None) -> dict:
        body: dict[str, Any] = {"dag_yaml": dag_yaml}
        if changelog:
            body["changelog"] = changelog
        return self._request("POST", f"/pipelines/{pipeline_id}/versions", json=body)

    # ── Pipeline Components ───────────────────────────────────────
    def list_pipeline_components(self) -> list:
        return self._request("GET", "/pipeline-components")

    def get_pipeline_component(self, component_id: str) -> dict:
        return self._request("GET", f"/pipeline-components/{component_id}")

    def list_component_versions(self, component_id: str) -> list:
        return self._request("GET", f"/pipeline-components/{component_id}/versions")

    def get_component_version(self, component_id: str, version: int) -> dict:
        return self._request("GET", f"/pipeline-components/{component_id}/versions/{version}")

    def register_pipeline_component(self, name: str, display_name: str,
                                    category: str, data_type: str,
                                    description: str | None = None,
                                    entrypoint: str | None = None,
                                    params_schema: str | None = None,
                                    input_schema: str | None = None,
                                    output_schema: str | None = None,
                                    output_branches: str | None = None,
                                    requires_gpu: bool = False,
                                    requires_model: str | None = None,
                                    execution_mode: str = "FUNCTION") -> dict:
        body: dict[str, Any] = {
            "name": name,
            "display_name": display_name,
            "category": category,
            "data_type": data_type,
            "requires_gpu": requires_gpu,
            "execution_mode": execution_mode,
        }
        if description:
            body["description"] = description
        if entrypoint:
            body["entrypoint"] = entrypoint
        if params_schema:
            body["params_schema"] = params_schema
        if input_schema:
            body["input_schema"] = input_schema
        if output_schema:
            body["output_schema"] = output_schema
        if output_branches:
            body["output_branches"] = output_branches
        if requires_model:
            body["requires_model"] = requires_model
        return self._request("POST", "/pipeline-components", json=body)

    # ── Pipeline Runs ─────────────────────────────────────────────
    def trigger_pipeline_run(self, pipeline_id: str, version: int,
                             input_dataset_id: str | None = None,
                             input_dataset_version: int | None = None) -> dict:
        body: dict[str, Any] = {"pipeline_id": pipeline_id, "version": version}
        if input_dataset_id:
            body["input_dataset_id"] = input_dataset_id
        if input_dataset_version is not None:
            body["input_dataset_version"] = input_dataset_version
        return self._request("POST", "/pipeline-runs", json=body)

    def get_pipeline_run(self, run_id: str) -> dict:
        return self._request("GET", f"/pipeline-runs/{run_id}")

    def list_step_runs(self, run_id: str) -> list:
        return self._request("GET", f"/pipeline-runs/{run_id}/steps")

    def cancel_pipeline_run(self, run_id: str) -> dict:
        return self._request("POST", f"/pipeline-runs/{run_id}/cancel")
