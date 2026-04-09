# dbay-cli

Command-line tool for [DBay](https://dbay.cloud) — manage memory bases, knowledge bases, databases, and more from the terminal.

## Install

```bash
pip install dbay-cli
```

## Getting Started

```bash
# Login (creates ~/.dbay/config.json)
dbay login

# Create a memory base and set it as default
dbay mem create my-mem
dbay mem use my-mem

# Register MCP server with Claude Code
claude mcp add --scope user dbay -- uvx dbay-mcp

# Done! Claude now remembers across sessions.
```

## Memory Commands

```bash
dbay mem list                    # List memory bases (* marks default)
dbay mem use <name|id>           # Switch default memory base
dbay mem create <name>           # Create a memory base
dbay mem create --encrypted <name>  # Create with end-to-end encryption
dbay mem info                    # Show current memory base details
dbay mem stats                   # Show memory statistics

dbay mem list-memories           # List all memories (decrypts if encrypted)
dbay mem recall "query"          # Semantic search (decrypts if encrypted)
dbay mem ingest "content"        # Store a memory (encrypts if encrypted)
dbay mem delete-memory <id>      # Delete a memory

dbay mem change-password         # Change encryption password
```

All commands use the default memory base from `~/.dbay/config.json`. Override with positional argument or `--base` option.

## Knowledge Base Commands

```bash
dbay kb list                     # List knowledge bases (* marks default)
dbay kb use <name|id>            # Switch default knowledge base
dbay kb create <name>            # Create a knowledge base
dbay kb info <id>                # Show knowledge base details

dbay kb upload <id> <file>       # Upload document (PDF, DOCX, MD)
dbay kb docs <id>                # List documents
dbay kb search <id> "query"      # Semantic search
```

## End-to-End Encryption

Create an encrypted memory base where content is encrypted locally before upload. The server never sees plaintext.

```bash
dbay mem create --encrypted my-private-mem
# 1. Set a password (saved to ~/.dbay/secret)
# 2. Choose embedding provider
# 3. Keys generated automatically
```

**Three-factor security**:
- **Password** (`~/.dbay/secret`) - only you know
- **Config file** (`~/.dbay/encrypted_bases.json`) - portable, safe to share (encrypted private key)
- **Server** - stores encrypted DEK (useless without private key)

**Cross-device**: copy `~/.dbay/encrypted_bases.json` to the new device, create `~/.dbay/secret` with your password, then `dbay login`.

## Configuration

`~/.dbay/config.json` (auto-created by `dbay login`):

```json
{
  "endpoint": "https://api.dbay.cloud:8443",
  "api_key": "lk_...",
  "memory_base": "mem_...",
  "knowledge_base": "kb_..."
}
```

## Links

- [DBay Console](https://console.dbay.cloud) — web UI for managing everything
- [dbay-mcp](https://pypi.org/project/dbay-mcp/) — MCP server for AI agents
