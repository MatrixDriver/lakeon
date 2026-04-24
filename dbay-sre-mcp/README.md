# dbay-sre-mcp

MCP (Model Context Protocol) server exposing SRE-style log diagnostics over a Postgres-backed log store. Designed for use by LLM agents that need to query structured application logs.

## Tools exposed

| Tool | Purpose |
|---|---|
| `log_search(component, level, keyword, tenant_id, db_id, since, limit)` | Flexible search across the `logs` table |
| `log_trace(request_id)` | Follow all log rows belonging to a single request chain |
| `log_errors(since, component)` | Recent error spike summary |
| `log_stats(since)` | Overview of activity by level/component |

## Install

```bash
pip install dbay-sre-mcp
```

## Configure

Point at your Postgres log store via either:

- `LOG_DB_DSN` environment variable, or
- `~/.dbay/sre-config.json` with key `"dsn"`

Expected `logs` table schema:

```
logs(id, ts, level, component, request_id, tenant_id, db_id, logger, msg, duration_ms, extra, thread)
```

## Use as MCP server

```bash
dbay-sre-mcp
```

Then connect from any MCP-compatible client (Claude Code, Hermes, Codex, custom).

## License

Apache-2.0
