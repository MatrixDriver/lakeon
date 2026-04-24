# Publish dbay-sre-mcp 0.2.0 to PyPI

## Prerequisites

- PyPI account with publish rights to `dbay-sre-mcp`
- PyPI API token in `~/.pypirc` or `UV_PUBLISH_TOKEN` env var
- `uv` >= 0.4 OR `twine` installed

## Build + publish

```bash
cd /Users/jacky/code/lakeon/dbay-sre-mcp

# Option A: uv (preferred)
uv build
uv publish

# Option B: build + twine
python -m build
python -m twine upload dist/dbay_sre_mcp-0.2.0*
```

## Verify

```bash
# After publish, verify it's installable:
uv pip install --force-reinstall dbay-sre-mcp==0.2.0
python -c "from dbay_sre_mcp.server import mcp; print('tools:', sorted([t.name for t in mcp._tool_manager._tools.values()]))"
# Expected: 11 tools listed alphabetically.
```

## Then trigger sre-agent rebuild

The sre-agent Dockerfile already pins `DBAY_SRE_MCP_VERSION=0.2.0`. Push any commit to trigger Railway rebuild, OR force a manual redeploy.
