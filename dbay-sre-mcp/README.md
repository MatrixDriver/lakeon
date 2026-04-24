# dbay-sre-mcp

MCP (Model Context Protocol) server exposing SRE-style log diagnostics over a Postgres-backed log store. Designed for use by LLM agents that need to query structured application logs.

## Tools (0.2.0)

### Log queries (PG-backed dbay-logs)

| Tool | Purpose |
|---|---|
| `log_search` | Flexible keyword/component/time filter |
| `log_trace` | Follow a request_id chain across components |
| `log_errors` | Recent error-level lines with auto-aggregation |
| `log_stats` | Activity overview by component / level / time |

### Metadata (lakeon-api admin REST)

| Tool | Purpose |
|---|---|
| `find_database` | Resolve DB name → id + status + tenant + compute_host |
| `find_tenant` | Resolve tenant name → id + held databases |
| `database_status` | Comprehensive DB snapshot + last 1h cold-start + events |

### Consistency & queues (lakeon-api production PG, read-only)

| Tool | Purpose |
|---|---|
| `data_consistency_check` | Run named invariant rule (KB↔db_id, enqueued↔drained, etc.) |
| `stuck_task_query` | Async tasks in_progress beyond threshold across known tables |

### Cluster signals (admin REST + dbay-logs)

| Tool | Purpose |
|---|---|
| `pod_create_failures` | k8s pod-create failures aggregated by category |
| `multi_tenant_blast_radius` | Single error pattern affecting N tenants in a window |

## Required env vars

| Variable | Used by | Notes |
|---|---|---|
| `LOG_DB_DSN` | log_*, multi_tenant_blast_radius | dbay-logs Postgres connection string |
| `LAKEON_DB_DSN` | data_consistency_check, stuck_task_query | lakeon-api production Postgres (read-only role recommended) |
| `LAKEON_ADMIN_TOKEN` | find_*, database_status, pod_create_failures | Admin token for `/admin/*` endpoints |
| `LAKEON_API_BASE_URL` | (above) | default `https://api.dbay.cloud:8443/api/v1` |

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
