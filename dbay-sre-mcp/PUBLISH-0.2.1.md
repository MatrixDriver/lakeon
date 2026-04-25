# Publish dbay-sre-mcp 0.2.1 to PyPI

## What's in 0.2.1

- **Critical fix**: `LakeonAdminClient` now sends `Authorization: Bearer <token>`
  (was `Admin-Token: <token>` in 0.2.0, causing all 7 admin REST tools to 403).
- `data_consistency_check` and `stuck_task_query` now go through admin REST
  endpoints, not PG direct. Removes `LAKEON_DB_DSN` env requirement.
- Requires lakeon-api with `/admin/data-consistency/{rule}` and `/admin/stuck-tasks`
  endpoints (released alongside this version).

## Publish

```bash
cd /Users/jacky/code/lakeon/dbay-sre-mcp
rm -rf dist/dbay_sre_mcp-0.2.1*
python3 -m build --wheel
PYPI_TOKEN=$(grep '^password' ~/.pypirc | head -1 | cut -d'=' -f2 | tr -d ' ')
uv publish --token "$PYPI_TOKEN" dist/dbay_sre_mcp-0.2.1*
```

## Verify

```bash
pip download --no-deps --dest /tmp/verify dbay-sre-mcp==0.2.1
```

## Then bump sre-agent

`sre-agent/Dockerfile` already pins `DBAY_SRE_MCP_VERSION=0.2.1` (Task 7).
Push any commit to trigger Railway rebuild.
