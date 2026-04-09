# dbay-mcp

MCP server for [DBay](https://dbay.cloud) — gives AI agents persistent memory and knowledge base across projects, sessions, and devices.

## Quick Start

```bash
# Claude Code (one command, global)
claude mcp add --scope user dbay -- uvx dbay-mcp

# Other MCP clients (Cursor, Windsurf, Claude Desktop)
# Add to your MCP config (.mcp.json / claude_desktop_config.json):
{
  "mcpServers": {
    "dbay": {
      "command": "uvx",
      "args": ["dbay-mcp"]
    }
  }
}
```

New to DBay? Install the CLI first: `pip install dbay-cli && dbay login`

## MCP Tools

### Memory

AI agents remember your decisions, conventions, credentials, and preferences across sessions.

| Tool | Description |
|------|-------------|
| `memory_recall` | Semantic search over past decisions, conventions, facts |
| `memory_ingest` | Store a memory (fact, decision, rejection, convention, procedural, episode) |
| `memory_list` | Browse memories by type |
| `memory_delete` | Remove a memory |

### Knowledge

Search and upload documents to your knowledge base.

| Tool | Description |
|------|-------------|
| `knowledge_search` | Hybrid vector + BM25 search over documents |
| `knowledge_upload` | Upload a file (PDF, DOCX, MD) for processing |
| `knowledge_upload_directory` | Bulk upload a directory |
| `knowledge_list` | List all knowledge bases |

## End-to-End Encryption

For privacy-sensitive use cases, create an encrypted memory base. Content is encrypted locally before upload — the server only stores ciphertext.

```bash
pip install dbay-cli
dbay login
dbay mem create --encrypted my-private-mem
```

The MCP server auto-detects encrypted bases and handles encryption/decryption transparently. No changes needed in your AI agent workflow.

**Three-factor security**: password (local) + config file (portable) + server-stored encrypted DEK. Any single factor leaked cannot decrypt your data.

## Configuration

```bash
# Login stores credentials in ~/.dbay/config.json
pip install dbay-cli
dbay login

# Switch default memory/knowledge base
dbay mem use my-mem
dbay kb use my-kb
```

Environment variables override config file:

| Variable | Description |
|----------|-------------|
| `DBAY_API_KEY` | API key (required) |
| `DBAY_MEMORY_BASE` | Default memory base ID |
| `DBAY_KNOWLEDGE_BASE` | Default knowledge base ID |
| `DBAY_ENDPOINT` | API endpoint (default: https://api.dbay.cloud:8443) |

## Links

- [DBay Console](https://console.dbay.cloud) — manage memory and knowledge bases
- [dbay-cli](https://pypi.org/project/dbay-cli/) — CLI for DBay
