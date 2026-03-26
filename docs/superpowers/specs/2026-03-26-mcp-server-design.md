# DBay MCP Server Design

## Overview

Add an MCP (Model Context Protocol) server endpoint to lakeon-api, exposing knowledge base search and memory operations to Claude Code and other MCP clients. Positioned as **CC's cross-project brain** — complements CC's native per-project memory with cross-project recall, large-volume semantic search, and long-term memory consolidation.

## Architecture

Embed MCP server into the existing Spring Boot application (lakeon-api). No separate service.

- **Transport**: Streamable HTTP (`POST /mcp`)
- **Auth**: Reuse existing `ApiKeyFilter` — CC connects with `--header "Authorization: Bearer lk_..."`
- **Dependency**: `spring-ai-mcp-server-webmvc-spring-boot-starter`
- **Implementation**: Register `ToolCallbackProvider` beans, each tool maps to existing Service layer methods

### Why embedded

- Zero additional deployment — shared auth, shared Service layer
- MCP requests are lightweight (same as REST calls)
- API pod resource bump to 2C/4Gi provides headroom

## MCP Tools (9 total)

### Knowledge Base (4 tools)

| Tool | Parameters | Returns | Purpose |
|------|-----------|---------|---------|
| `knowledge_list_bases` | — | `[{id, name, description, document_count}]` | Browse available knowledge bases |
| `knowledge_search` | `kb_id`, `query`, `top_k?` (default 5) | `[{content, score, document_name, chunk_index}]` | Semantic search across a knowledge base |
| `knowledge_list_documents` | `kb_id` | `[{id, filename, format, status, chunks_count}]` | List documents in a knowledge base |
| `knowledge_get_chunk` | `kb_id`, `document_id`, `chunk_index` | `{content, metadata, char_start, char_end}` | Get full chunk content and context |

### Memory (5 tools)

| Tool | Parameters | Returns | Purpose |
|------|-----------|---------|---------|
| `memory_recall` | `base_id`, `query`, `memory_types?`, `top_k?` | `[{content, memory_type, importance, score}]` | Semantic recall — CC's primary memory read path |
| `memory_ingest` | `base_id`, `content`, `role?`, `memory_type?`, `importance?` | `{id, memory_type}` | Store a memory (server-side extraction) |
| `memory_ingest_extracted` | `base_id`, `memories[]` (each: `{content, memory_type, importance?}`) | `{count}` | Agent-extract mode — CC sends pre-extracted memories |
| `memory_list` | `base_id`, `memory_type?`, `limit?`, `offset?` | `[{id, content, memory_type, importance, created_at}]` | Browse memories, optionally filter by type |
| `memory_delete` | `base_id`, `memory_id` | `{status: "deleted"}` | Delete a memory |

### Memory Types

`fact`, `episode`, `procedural`, `decision`, `rejection`, `convention`

## Design Principles

### Complement, don't replace CC's native memory

- CC's `CLAUDE.md` + `memory/*.md` = L1 cache (small, fast, full injection, per-project)
- DBay memory = L2/L3 store (large, semantic search, cross-project, Q-value ranked)
- No hooks, no auto-triggers — CC decides when to call tools

### What goes where

| Content | Where | Why |
|---------|-------|-----|
| API keys, cloud credentials | DBay memory (fact) | Cross-project, recall on demand |
| Project code locations, deploy procedures | DBay memory (procedural) | Cross-project reuse |
| Operational lessons ("don't helm upgrade manually") | DBay memory (rejection/decision) | Universal conventions |
| User preferences (style, language) | DBay memory (convention) | All projects benefit |
| Project-specific code structure | CC native memory | Per-project, full injection better |
| Reference documents, research papers | DBay knowledge base | Large volume, semantic search |

### Judgment rule

- About the **user** → memory (preferences, habits, decisions, credentials)
- About **things** → knowledge (documents, research, reference materials)

## Implementation Details

### New files

```
lakeon-api/src/main/java/com/lakeon/mcp/
├── McpServerConfig.java          # Spring AI MCP server configuration
├── KnowledgeMcpTools.java        # 4 knowledge tools
└── MemoryMcpTools.java           # 5 memory tools
```

### McpServerConfig.java

- Registers `ToolCallbackProvider` with all tool beans
- Configures MCP server name, version, capabilities

### KnowledgeMcpTools.java

- `@Tool` annotated methods
- Delegates to existing `KnowledgeService` and `ChunkService`
- Tenant resolved from MCP session's authenticated API key

### MemoryMcpTools.java

- `@Tool` annotated methods
- Delegates to existing `MemoryService` (which proxies to Python microservice)
- Tenant resolved from MCP session's authenticated API key

### Auth integration

The MCP endpoint (`/mcp`) must go through `ApiKeyFilter`. The filter extracts the tenant from the API key and sets it as a request attribute. MCP tool implementations retrieve the tenant from the request context.

Challenge: Spring AI MCP server may not expose the HTTP request directly to tool methods. Options:
1. Use `ThreadLocal` to pass tenant context from filter to tools
2. Custom `McpServerTransportProvider` that injects tenant into tool context
3. Spring `RequestContextHolder` (if running in request-scoped thread)

Recommended: Option 3 (`RequestContextHolder`) since Spring AI MCP WebMVC processes requests synchronously on the servlet thread.

### Configuration changes

**application.yml**:
```yaml
spring:
  ai:
    mcp:
      server:
        name: dbay
        version: 1.0.0
```

**Helm values (hwstaff)**:
```yaml
api:
  resources:
    requests:
      cpu: "200m"
      memory: "512Mi"
    limits:
      cpu: "2"
      memory: "4Gi"
```

### ApiKeyFilter update

Add `/mcp` to authenticated paths (it should already be covered since only explicitly listed paths are public).

## CC Integration

### Setup command

```bash
claude mcp add --scope user --transport http dbay \
  https://api.dbay.cloud:8443/mcp \
  --header "Authorization: Bearer lk_..."
```

### Alternative: `.mcp.json` (for Cursor/Windsurf)

```json
{
  "mcpServers": {
    "dbay": {
      "transport": "http",
      "url": "https://api.dbay.cloud:8443/mcp",
      "headers": {
        "Authorization": "Bearer lk_..."
      }
    }
  }
}
```

## Testing Plan

1. Unit tests for each MCP tool method (mock Service layer)
2. Integration test: start Spring Boot, connect MCP client, verify tool listing
3. E2E test: `claude mcp add` locally, verify tools appear in CC, run search/recall
4. Auth test: invalid API key returns 401, missing auth returns 401

## Dependencies

- `spring-ai-mcp-server-webmvc-spring-boot-starter` (Spring AI 1.0+)
- No other new dependencies — all business logic exists in current Service layer

## Out of Scope

- `memory_digest` — runs as background task, not triggered by CC
- MCP Resources/Prompts — only Tools for now
- Rate limiting per MCP connection (use existing API rate limiter)
- SSE transport (Streamable HTTP covers the use case)
