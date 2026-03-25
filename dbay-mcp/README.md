# DBay MCP Server

MCP server for searching DBay knowledge bases from Claude Code.

## Setup

```bash
# Register with Claude Code (one-time)
claude mcp add dbay -- uv run --directory /path/to/lakeon/dbay-mcp python server.py
```

## Config

Reads from env vars (priority) or `~/.dbay/config.json` (set via `dbay login`):

- `DBAY_API_KEY` — API key
- `DBAY_ENDPOINT` — defaults to `https://api.dbay.cloud:8443`

## Tools

| Tool | Description |
|------|-------------|
| `knowledge_list` | List all knowledge bases |
| `knowledge_search` | Hybrid vector + BM25 search |
| `knowledge_upload` | Upload a file for processing |
