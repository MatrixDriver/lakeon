# echomem

> Local-first agent memory hub for Claude Code / openclaw / hermes.
> Phase 1 backbone — see `docs/superpowers/specs/2026-04-30-echomem-design.md` for the full design.

## Status

Phase 1 / Backbone — Memory API only. No derivatives, no Dashboard, no Context API yet.

## Install (dev)

```bash
cd lakeon/echomem
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"
```

## Bootstrap

```bash
echomem init           # creates ~/.echomem/{config.toml, blobs/, logs/, sessions/, cache/}
echomem start          # uvicorn on http://127.0.0.1:8473 (or ECHOMEM_PORT)
echomem status         # probe /health
```

## Use from CLI

```bash
echomem mem ingest "hello world" --agent cli
echomem mem recall "hello"
echomem mem list --limit 10
```

## Wire into Claude Code

Add to `~/.claude/settings.json` under `mcpServers`:

```json
{
  "mcpServers": {
    "echomem": {
      "command": "echomem-mcp-shim",
      "args": [],
      "env": {}
    }
  }
}
```

Restart Claude Code. Tools `mcp__echomem__memory_ingest` etc. are available.

## Run tests

```bash
pytest -v                     # unit + integration (mocks Ollama)
ECHOMEM_E2E=1 pytest -v -s    # full loop (requires real Ollama)
```

## What's next

- Plan 2: derivatives pipeline (timeline / hierarchical / graph / procedural)
- Plan 3: Context API + FS blobs (add_url / ls / read / write / mv)
- Plan 4: Dashboard
- Plan 5: Onboarding + openclaw / hermes wiring
