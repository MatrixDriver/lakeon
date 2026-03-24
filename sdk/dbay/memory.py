"""DBay Memory client for AI agent memory management."""
from typing import Optional
from .client import BaseClient


class MemoryClient:
    """Client for DBay Memory API.

    Usage:
        from dbay import MemoryClient

        client = MemoryClient(api_key="lk_xxx", base_id="mem_abc123")
        client.ingest("User prefers TypeScript over JavaScript")
        results = client.recall("What programming languages does the user prefer?")
        for mem in results:
            print(mem["content"], mem["memory_type"])
    """

    def __init__(self, api_key: str, base_id: str, base_url: Optional[str] = None, timeout: float = 60):
        self._client = BaseClient(api_key=api_key, base_url=base_url, timeout=timeout)
        self._base_id = base_id
        self._prefix = f"/memory/bases/{base_id}"

    def ingest(self, content: str, role: str = "user", memory_type: str = "fact",
               importance: float = 0.5, metadata: Optional[dict] = None) -> dict:
        """Store a memory."""
        return self._client.post(f"{self._prefix}/ingest", json={
            "content": content,
            "role": role,
            "memory_type": memory_type,
            "importance": importance,
            "metadata": metadata or {},
        })

    def recall(self, query: str, top_k: int = 10,
               memory_types: Optional[list[str]] = None) -> list[dict]:
        """Search memories by semantic similarity."""
        resp = self._client.post(f"{self._prefix}/recall", json={
            "query": query,
            "top_k": top_k,
            "memory_types": memory_types,
        })
        return resp.get("memories", [])

    def digest(self) -> dict:
        """Run reflection to discover behavioral traits."""
        return self._client.post(f"{self._prefix}/digest")

    def list_memories(self, memory_type: Optional[str] = None,
                      offset: int = 0, limit: int = 20) -> dict:
        """List memories with pagination."""
        params = {"offset": offset, "limit": limit}
        if memory_type:
            params["memory_type"] = memory_type
        return self._client.get(f"{self._prefix}/memories", params=params)

    def get_memory(self, memory_id: int) -> dict:
        """Get a single memory by ID."""
        return self._client.get(f"{self._prefix}/memories/{memory_id}")

    def delete_memory(self, memory_id: int) -> dict:
        """Delete a memory."""
        return self._client.delete(f"{self._prefix}/memories/{memory_id}")

    def list_traits(self) -> list[dict]:
        """List all discovered traits."""
        return self._client.get(f"{self._prefix}/traits")

    def stats(self) -> dict:
        """Get memory statistics."""
        return self._client.get(f"{self._prefix}/stats")

    def close(self):
        self._client.close()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()
