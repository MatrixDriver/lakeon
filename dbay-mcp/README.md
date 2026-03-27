# DBay MCP Server

MCP server for connecting AI tools to DBay knowledge base and agent memory.

## Quick Start

```bash
# 1. Install
pip install dbay-mcp

# 2. Login (creates ~/.dbay/config.json)
dbay login

# 3. Register with Claude Code
claude mcp add --scope user dbay -- uvx dbay-mcp
```

New to DBay? Sign up at [dbay.cloud](https://dbay.cloud) first.

## Config

`dbay login` writes `~/.dbay/config.json`:

```json
{
  "endpoint": "https://api.dbay.cloud:8443",
  "api_key": "lk_...",
  "knowledge_base": "kb_...",
  "memory_base": "mem_..."
}
```

Environment variables (`DBAY_API_KEY`, `DBAY_ENDPOINT`, `DBAY_KNOWLEDGE_BASE`, `DBAY_MEMORY_BASE`) take priority over config file.

## Tools

| Tool | Description |
|------|-------------|
| `knowledge_list` | List all knowledge bases |
| `knowledge_search` | Hybrid vector + BM25 search |
| `knowledge_upload` | Upload a file for processing |
| `knowledge_upload_directory` | Upload all files from a directory |
| `memory_recall` | Semantic search over agent memory |
| `memory_ingest` | Store a memory |
| `memory_ingest_extracted` | Batch write structured memories |
| `memory_list` | Browse memories |
| `memory_delete` | Delete a memory |

## Other MCP Clients (Cursor, Windsurf, etc.)

Add to `.mcp.json` in your project root:

```json
{
  "mcpServers": {
    "dbay": {
      "command": "uvx",
      "args": ["dbay-mcp"]
    }
  }
}
```
