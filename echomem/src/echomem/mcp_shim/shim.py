from __future__ import annotations

import json
from typing import Any

import httpx

PROTOCOL_VERSION = "2025-06-18"

TOOLS: list[dict[str, Any]] = [
    {
        "name": "memory_ingest",
        "description": "Persist a memory; embedding generated server-side.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "text": {"type": "string"},
                "agent_id": {"type": "string"},
                "meta": {"type": "object"},
            },
            "required": ["text", "agent_id"],
        },
    },
    {
        "name": "memory_recall",
        "description": "Retrieve memories matching a natural-language query.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "query": {"type": "string"},
                "k": {"type": "integer", "default": 10},
                "agent_id": {"type": "string"},
            },
            "required": ["query"],
        },
    },
    {
        "name": "memory_list",
        "description": "List recent memories.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "agent_id": {"type": "string"},
                "limit": {"type": "integer", "default": 50},
            },
        },
    },
    {
        "name": "memory_get",
        "description": "Get a memory by id.",
        "inputSchema": {
            "type": "object",
            "properties": {"id": {"type": "string"}},
            "required": ["id"],
        },
    },
    {
        "name": "memory_delete",
        "description": "Soft-delete a memory by id.",
        "inputSchema": {
            "type": "object",
            "properties": {"id": {"type": "string"}},
            "required": ["id"],
        },
    },
    {
        "name": "context_add_url",
        "description": "Fetch a URL (HTML/PDF/text), persist as blob, trigger pipeline.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "url": {"type": "string"},
                "path": {"type": "string"},
            },
            "required": ["url"],
        },
    },
    {
        "name": "context_ls",
        "description": "List paths or blobs.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "prefix": {"type": "string"},
                "limit": {"type": "integer", "default": 100},
            },
        },
    },
    {
        "name": "context_read",
        "description": "Read blob content by path or sha256.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "path": {"type": "string"},
                "sha256": {"type": "string"},
            },
        },
    },
    {
        "name": "context_write",
        "description": "Write content to a path; persists as blob and triggers pipeline.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "path": {"type": "string"},
                "content": {"type": "string"},
                "mime": {"type": "string", "default": "text/plain"},
            },
            "required": ["path", "content"],
        },
    },
    {
        "name": "context_mv",
        "description": "Rename a path alias (blob unchanged).",
        "inputSchema": {
            "type": "object",
            "properties": {
                "old": {"type": "string"},
                "new": {"type": "string"},
            },
            "required": ["old", "new"],
        },
    },
]


async def handle_message(msg: dict, *, base_url: str) -> dict | None:
    method = msg.get("method")
    msg_id = msg.get("id")

    if method == "initialize":
        return {
            "jsonrpc": "2.0",
            "id": msg_id,
            "result": {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {"tools": {"listChanged": False}},
                "serverInfo": {"name": "echomem", "version": "0.1.0"},
            },
        }

    if method == "tools/list":
        return {"jsonrpc": "2.0", "id": msg_id, "result": {"tools": TOOLS}}

    if method == "tools/call":
        params = msg.get("params") or {}
        return await _call_tool(msg_id, params, base_url)

    if method in ("notifications/initialized", "notifications/cancelled"):
        return None

    return {
        "jsonrpc": "2.0",
        "id": msg_id,
        "error": {"code": -32601, "message": f"Method not found: {method}"},
    }


async def _call_tool(msg_id: Any, params: dict, base_url: str) -> dict:
    name = params.get("name")
    args = params.get("arguments") or {}
    try:
        async with httpx.AsyncClient(base_url=base_url, timeout=60.0) as client:
            if name == "memory_ingest":
                r = await client.post("/memory/ingest", json=args)
                r.raise_for_status()
                content = json.dumps(r.json(), ensure_ascii=False)
            elif name == "memory_recall":
                r = await client.post("/memory/recall", json=args)
                r.raise_for_status()
                content = json.dumps(r.json(), ensure_ascii=False)
            elif name == "memory_list":
                r = await client.get("/memory/list", params=args)
                r.raise_for_status()
                content = json.dumps(r.json(), ensure_ascii=False)
            elif name == "memory_get":
                r = await client.get(f"/memory/{args['id']}")
                r.raise_for_status()
                content = json.dumps(r.json(), ensure_ascii=False)
            elif name == "memory_delete":
                r = await client.delete(f"/memory/{args['id']}")
                r.raise_for_status()
                content = json.dumps(r.json(), ensure_ascii=False)
            elif name == "context_add_url":
                r = await client.post("/context/add_url", json=args)
                r.raise_for_status()
                content = json.dumps(r.json(), ensure_ascii=False)
            elif name == "context_ls":
                r = await client.get("/context/ls", params=args)
                r.raise_for_status()
                content = json.dumps(r.json(), ensure_ascii=False)
            elif name == "context_read":
                r = await client.get("/context/read", params=args)
                r.raise_for_status()
                content = json.dumps(r.json(), ensure_ascii=False)
            elif name == "context_write":
                r = await client.post("/context/write", json=args)
                r.raise_for_status()
                content = json.dumps(r.json(), ensure_ascii=False)
            elif name == "context_mv":
                r = await client.post("/context/mv", json=args)
                r.raise_for_status()
                content = json.dumps(r.json(), ensure_ascii=False)
            else:
                return {
                    "jsonrpc": "2.0",
                    "id": msg_id,
                    "error": {"code": -32602, "message": f"Unknown tool: {name}"},
                }
        return {
            "jsonrpc": "2.0",
            "id": msg_id,
            "result": {"content": [{"type": "text", "text": content}]},
        }
    except httpx.HTTPError as e:
        return {
            "jsonrpc": "2.0",
            "id": msg_id,
            "error": {"code": -32603, "message": f"daemon HTTP error: {e}"},
        }
