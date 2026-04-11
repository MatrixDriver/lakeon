# lakeon-wiki-agent

Agentic wiki compiler for Lakeon knowledge bases. Uses DeepSeek V3.2 tool calling
(via 华为云 MaaS) to ingest documents, curate, and lint wiki pages inside
Lakeon knowledge bases.

Called by `lakeon-api` when a `WIKI_UPDATE` task enters the `KbWriteQueue`,
the agent loops through DeepSeek tool calls against lakeon-api's
`/api/v1/internal/wiki/tool/*` endpoints until it emits `done`.

## Run locally

```bash
uv venv
uv sync --extra dev
uv run uvicorn app.main:app --reload --port 8090
```

## Environment

See `app/config.py` for the full list.

- `WIKI_AGENT_INTERNAL_TOKEN` — token lakeon-api uses to call us
- `LAKEON_API_URL` / `LAKEON_API_INTERNAL_TOKEN` — where this agent POSTs tool calls
- `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL` — DeepSeek via 华为云 MaaS
- `MAX_TOOL_ROUNDS` — hard cap on LLM tool-call rounds per run (default 20)
- `MAX_CONCURRENT_AGENTS` — in-process semaphore (default 8)

## Tests

```bash
uv run pytest -v
```
