# echomem

> Local-first agent memory hub for Claude Code / openclaw / hermes.
> Phase 1 backbone — see `docs/superpowers/specs/2026-04-30-echomem-design.md` for the full design.

**Public docs site** (architecture / roadmap / phase plans, shareable with colleagues):
source in `docs/`, deployed as an independent Railway service (Caddy + docsify).
Local preview:
```bash
cd docs && docker build -t echomem-docs:dev . && docker run --rm -p 8080:8080 echomem-docs:dev
# open http://localhost:8080/  (use --noproxy '*' with curl if a system HTTP proxy is set)
```

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

## Derivatives (Plan 2)

After ingest, three async workers run in the background:

- **Summarizer** — gemma generates L0 (≤100 tokens) and L1 (≤500 tokens) summaries; L2 is the original chunk
- **EntityExtractor** — gemma extracts (subject, predicate, object) triples; confidence ≥ 0.7 enters the graph, < 0.7 enters `derivative_triple_pending` for review
- **TimelineWorker** — pure-Python: same agent + within 30 min + cosine ≥ 0.7 → joins same Episodic event; otherwise opens a new event

A fourth worker (**Reflector**) runs periodic stats — placeholder until P1.

Skill (the 4th derivative organization) is populated via:

```bash
echomem skill import ~/.claude/skills              # imports superpowers / impeccable / etc.
```

### Query

```bash
curl http://127.0.0.1:8473/derivatives/timeline?agent=cc
curl "http://127.0.0.1:8473/derivatives/tree?source_kind=memory&source_ref=01HXM..."
curl "http://127.0.0.1:8473/derivatives/graph?seed=ent:jacky&hops=2"
curl "http://127.0.0.1:8473/derivatives/skills?ctx=writing+a+test"
```

### Inspect dead letters

```bash
sqlite3 ~/.echomem/db.sqlite "SELECT kind, error, created_at FROM dead_letter ORDER BY created_at DESC LIMIT 10"
```

## Context API (Plan 3)

Long documents (URLs / PDFs / written content) live in a content-addressable
blob store at `~/.echomem/blobs/<sha256[:2]>/<sha256>` with a mutable path
alias table for `mv`.

### CLI

```bash
echomem ctx add-url https://example.com/post                 # fetch + ingest
echomem ctx add-url https://example.com/post --path web/post.html
echomem ctx write notes/today.md "things I learned today"
echomem ctx ls --prefix notes/
echomem ctx read notes/today.md
echomem ctx mv notes/today.md archive/2026-05-06.md
```

### HTTP

```bash
curl -X POST -d '{"url":"https://x.com/y"}' http://127.0.0.1:8473/context/add_url
curl    "http://127.0.0.1:8473/context/ls?prefix=notes/"
curl    "http://127.0.0.1:8473/context/read?path=notes/today.md"
curl -X POST -d '{"path":"a","content":"hi"}'  http://127.0.0.1:8473/context/write
curl -X POST -d '{"old":"a","new":"b"}'        http://127.0.0.1:8473/context/mv
```

### What gets indexed

- Blob written → `blob_ref(sha256, mime, byte_size, origin_url, created_at)`
- Optional path alias → `path_alias(path, sha256, created_at)` (mv mutates path, not sha)
- If extracted text is non-empty → triggers pipeline:
  - SummarizerWorker writes L0/L1/L2 with `source_kind='blob'`, `source_ref=sha256`
  - EntityExtractorWorker extracts triples, source_memory_id=sha256
- Query the result via `/derivatives/tree?source_kind=blob&source_ref=<sha>` and
  `/derivatives/graph?seed=ent:<entity>`

## Dashboard

A Vue 3 SPA shipped inside the daemon's wheel. Hub-and-Spoke layout: 总览 / 记忆 / 认知 / 状态.
Lineage drawer slides in from the right when you click any cognition.

### Local development

```bash
# terminal 1: daemon
echomem start

# terminal 2: dashboard dev server (proxies API to 8473)
cd dashboard
npm install
npm run dev
# open http://localhost:5173
```

### Production build (one command)

```bash
bash scripts/build_dashboard.sh
echomem start
# open http://127.0.0.1:8473/dashboard
```

### Testing

```bash
cd dashboard
npm test              # vitest unit tests
npm run test:e2e      # playwright (requires daemon running)
ECHOMEM_E2E_REQUIRE_OLLAMA=1 npm run test:e2e:full   # full demo path
```

## What's next

- Plan 4 (done): Vue 3 Dashboard SPA — see `docs/superpowers/specs/2026-05-06-echomem-dashboard-spa-design.md`
- Plan 5: Onboarding install.sh + openclaw / hermes wiring
- Insight Track (research): output-length prediction
