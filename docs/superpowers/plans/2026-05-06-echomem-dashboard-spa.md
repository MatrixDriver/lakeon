# echomem Dashboard SPA Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a Vue 3 single-page dashboard served by the echomem daemon at `/dashboard`, with five-page Hub-and-Spoke IA, right-side cascading lineage drawer, and one new backend endpoint `/health/diagnostic`.

**Architecture:** Vue 3 + Vite SPA in `dashboard/`, hand-written components driven by `tokens.css` (Harbor Editorial palette). Pinia stores with 10s/2s-burst polling against existing daemon API plus one new diagnostic endpoint. Build pipeline copies `dashboard/dist/` into `src/echomem/_dashboard_dist/` which is `force-include`-d into the wheel; daemon mounts it via `StaticFiles(html=True)`. Real-Ollama Playwright E2E covers the demo path.

**Tech Stack:** Vue 3.5+, Vite 5, TypeScript 5.6+, Pinia 2, Vue Router 4 (hash mode), d3-force, vitest, @vue/test-utils, Playwright. Backend: existing FastAPI 0.115 + SQLite + httpx 0.27.

**Spec:** `docs/superpowers/specs/2026-05-06-echomem-dashboard-spa-design.md`

**Path conventions:** All paths in this plan are relative to the echomem project root. Today that root is `/Users/jacky/code/lakeon/echomem/`. The user plans to move it to `~/code/echomem` later — the plan is agnostic, paths stay relative.

---

## Phase 0 — Sanity check

### Task 0.1: Verify echomem repo state

**Files:**
- Read: `echomem/pyproject.toml`
- Read: `echomem/src/echomem/daemon/app.py`
- Read: `echomem/src/echomem/api/health.py`

- [ ] **Step 1: Confirm pre-conditions**

```bash
cd /Users/jacky/code/lakeon/echomem
test -f pyproject.toml || { echo "wrong cwd"; exit 1; }
python -c "import echomem; print(echomem.__version__)"
python -m pytest -q
```

Expected: existing tests pass (111 unit/integration green per session brief).

- [ ] **Step 2: Confirm Node 20+ available**

```bash
node --version          # ≥ v20
npm --version           # ≥ v10
```

If missing, install via nvm before proceeding.

- [ ] **Step 3: No commit needed for sanity check.**

---

## Phase 1 — Backend: `/health/diagnostic` endpoint

This phase adds **one** route plus the SQLite/Ollama plumbing it needs. Strict TDD.

### Task 1.1: Define diagnostic pydantic schemas

**Files:**
- Modify: `echomem/src/echomem/api/schemas.py`
- Create: `echomem/tests/unit/test_diagnostic_schemas.py`

- [ ] **Step 1: Write failing schema test**

Create `tests/unit/test_diagnostic_schemas.py`:

```python
from echomem.api.schemas import (
    DaemonHealth, OllamaHealth, WorkerStatus,
    DiagnosticCounts, DeadLetterEntry, DiagnosticResponse,
)


def test_diagnostic_response_round_trip():
    payload = {
        "daemon": {
            "status": "ok", "version": "0.1.0",
            "data_dir": "/tmp/echomem", "db_size_bytes": 1024,
        },
        "ollama": {
            "status": "ok", "latency_ms": 12,
            "generate_model": "gemma2:2b",
            "embedding_model": "nomic-embed-text",
            "embedding_dim": 768,
        },
        "workers": {
            "embedder": {"queue_depth": 0, "last_run_at": 0,
                         "processed_total": 0, "throttle": None},
        },
        "counts": {"memories": 0, "cognitions": 0, "entities": 0, "skills": 0},
        "dead_letter": [],
    }
    parsed = DiagnosticResponse.model_validate(payload)
    assert parsed.daemon.version == "0.1.0"
    assert parsed.ollama.embedding_dim == 768
    assert parsed.counts.memories == 0
```

- [ ] **Step 2: Run test to verify failure**

```bash
cd /Users/jacky/code/lakeon/echomem
python -m pytest tests/unit/test_diagnostic_schemas.py -v
```

Expected: ImportError — names not yet defined.

- [ ] **Step 3: Add schemas to `src/echomem/api/schemas.py`**

Append to the bottom:

```python
class DaemonHealth(BaseModel):
    status: str
    version: str
    data_dir: str
    db_size_bytes: int


class OllamaHealth(BaseModel):
    status: str  # "ok" | "unreachable" | "timeout"
    latency_ms: int | None
    generate_model: str
    embedding_model: str
    embedding_dim: int


class WorkerStatus(BaseModel):
    queue_depth: int
    last_run_at: int | None
    processed_total: int
    throttle: str | None


class DiagnosticCounts(BaseModel):
    memories: int
    cognitions: int
    entities: int
    skills: int


class DeadLetterEntry(BaseModel):
    mem_id: str | None
    worker: str
    kind: str
    retries: int
    at: int
    traceback: str | None


class DiagnosticResponse(BaseModel):
    daemon: DaemonHealth
    ollama: OllamaHealth
    workers: dict[str, WorkerStatus]
    counts: DiagnosticCounts
    dead_letter: list[DeadLetterEntry]
```

- [ ] **Step 4: Run test to verify pass**

```bash
python -m pytest tests/unit/test_diagnostic_schemas.py -v
```

Expected: 1 passed.

- [ ] **Step 5: Commit**

```bash
git add echomem/src/echomem/api/schemas.py echomem/tests/unit/test_diagnostic_schemas.py
git commit -m "feat(echomem-api): add diagnostic response schemas"
```

---

### Task 1.2: SQLite count helpers

**Files:**
- Modify: `echomem/src/echomem/drivers/sqlite.py`
- Create: `echomem/tests/unit/test_sqlite_diagnostic.py`

- [ ] **Step 1: Write failing test**

Create `tests/unit/test_sqlite_diagnostic.py`:

```python
import time
from pathlib import Path

import pytest

from echomem.drivers.sqlite import SQLiteDriver
from echomem.drivers.base import Memory


@pytest.fixture
def driver(tmp_path: Path) -> SQLiteDriver:
    d = SQLiteDriver(tmp_path / "db.sqlite", embedding_dim=4)
    yield d
    d.close()


def _mem(idx: int) -> Memory:
    now = int(time.time() * 1000)
    return Memory(
        id=f"m{idx:04d}", agent_id="t",
        source_kind="explicit", source_ref=None,
        text=f"hello {idx}", meta=None,
        created_at=now, updated_at=now, deleted_at=None,
        embedding=[0.1, 0.2, 0.3, 0.4],
    )


def test_count_memories_empty(driver):
    assert driver.count_memories() == 0


def test_count_memories_after_insert(driver):
    for i in range(5):
        driver.upsert_memory(_mem(i))
    assert driver.count_memories() == 5


def test_count_cognitions_zero_initially(driver):
    assert driver.count_cognitions() == 0


def test_count_entities_zero_initially(driver):
    assert driver.count_entities() == 0


def test_count_skills_zero_initially(driver):
    assert driver.count_skills() == 0
```

- [ ] **Step 2: Run test to verify failure**

```bash
python -m pytest tests/unit/test_sqlite_diagnostic.py -v
```

Expected: AttributeError on `count_memories`.

- [ ] **Step 3: Add helpers to `src/echomem/drivers/sqlite.py`**

Add inside the `SQLiteDriver` class (place near other read helpers):

```python
def count_memories(self) -> int:
    row = self.con.execute(
        "SELECT COUNT(*) FROM memory WHERE deleted_at IS NULL"
    ).fetchone()
    return int(row[0])

def count_cognitions(self) -> int:
    """Sum of timeline events + summary nodes + skill rows."""
    timeline = self.con.execute(
        "SELECT COUNT(*) FROM timeline_event"
    ).fetchone()[0]
    summary = self.con.execute(
        "SELECT COUNT(*) FROM summary"
    ).fetchone()[0]
    skill = self.con.execute(
        "SELECT COUNT(*) FROM skill"
    ).fetchone()[0]
    return int(timeline) + int(summary) + int(skill)

def count_entities(self) -> int:
    row = self.con.execute("SELECT COUNT(*) FROM entity").fetchone()
    return int(row[0])

def count_skills(self) -> int:
    row = self.con.execute("SELECT COUNT(*) FROM skill").fetchone()
    return int(row[0])
```

> Note: confirm table names by reading `src/echomem/drivers/migrations/`. If `timeline_event`/`summary`/`entity`/`skill` differ in casing/naming, adjust the SQL above to match.

- [ ] **Step 4: Run test to verify pass**

```bash
python -m pytest tests/unit/test_sqlite_diagnostic.py -v
```

Expected: 5 passed. If any FAIL with "no such table" — table names mismatch the schema; check migrations and fix the SQL.

- [ ] **Step 5: Commit**

```bash
git add echomem/src/echomem/drivers/sqlite.py echomem/tests/unit/test_sqlite_diagnostic.py
git commit -m "feat(echomem-driver): count_{memories,cognitions,entities,skills} helpers"
```

---

### Task 1.3: SQLite worker stats + dead letter listing

**Files:**
- Modify: `echomem/src/echomem/drivers/sqlite.py`
- Modify: `echomem/tests/unit/test_sqlite_diagnostic.py`

- [ ] **Step 1: Append failing tests**

```python
def test_worker_stats_empty(driver):
    stats = driver.worker_stats()
    # All known kinds present, all zero.
    assert "summarize" in stats and stats["summarize"]["queue_depth"] == 0
    assert stats["summarize"]["processed_total"] == 0
    assert stats["summarize"]["last_run_at"] is None


def test_worker_stats_after_inserts(driver):
    now = int(time.time() * 1000)
    driver.con.execute(
        "INSERT INTO derivative_task(id,kind,memory_id,status,attempts,created_at,updated_at) "
        "VALUES('t1','summarize','m0','pending',0,?,?)",
        (now, now),
    )
    driver.con.execute(
        "INSERT INTO derivative_task(id,kind,memory_id,status,attempts,created_at,updated_at) "
        "VALUES('t2','summarize','m1','done',1,?,?)",
        (now, now),
    )
    driver.con.commit()
    stats = driver.worker_stats()
    assert stats["summarize"]["queue_depth"] == 1
    assert stats["summarize"]["processed_total"] == 1
    assert stats["summarize"]["last_run_at"] == now


def test_list_dead_letters_empty(driver):
    assert driver.list_dead_letters(limit=10) == []


def test_list_dead_letters_after_insert(driver):
    now = int(time.time() * 1000)
    driver.con.execute(
        "INSERT INTO dead_letter(id,task_id,kind,memory_id,error,created_at) "
        "VALUES('d1','t1','summarize','m0','boom',?)",
        (now,),
    )
    driver.con.commit()
    items = driver.list_dead_letters(limit=10)
    assert len(items) == 1
    assert items[0]["worker"] == "summarize"
    assert items[0]["traceback"] == "boom"
```

- [ ] **Step 2: Verify failure**

```bash
python -m pytest tests/unit/test_sqlite_diagnostic.py -v
```

- [ ] **Step 3: Add helpers to `SQLiteDriver`**

```python
KNOWN_TASK_KINDS = (
    "summarize", "extract_entity", "aggregate_timeline",
    "reflect", "summarize_blob", "extract_blob",
)

def worker_stats(self) -> dict[str, dict]:
    """Per-task-kind {queue_depth, last_run_at, processed_total, throttle}.

    queue_depth = pending OR running (not yet terminal).
    processed_total = done.
    last_run_at = max(updated_at) where status = 'running' OR 'done'.
    throttle = None until P5+ adds explicit signaling.
    """
    out: dict[str, dict] = {
        kind: {"queue_depth": 0, "last_run_at": None,
               "processed_total": 0, "throttle": None}
        for kind in self.KNOWN_TASK_KINDS
    }

    rows = self.con.execute(
        "SELECT kind, status, COUNT(*) AS c, MAX(updated_at) AS last "
        "FROM derivative_task GROUP BY kind, status"
    ).fetchall()

    for kind, status, count, last in rows:
        bucket = out.setdefault(
            kind, {"queue_depth": 0, "last_run_at": None,
                   "processed_total": 0, "throttle": None}
        )
        if status in ("pending", "running"):
            bucket["queue_depth"] += int(count)
        if status == "done":
            bucket["processed_total"] = int(count)
        if status in ("running", "done"):
            current = bucket["last_run_at"] or 0
            bucket["last_run_at"] = max(current, int(last) if last else 0) or None
    return out


def list_dead_letters(self, *, limit: int = 20) -> list[dict]:
    rows = self.con.execute(
        "SELECT memory_id, kind, error, created_at "
        "FROM dead_letter ORDER BY created_at DESC LIMIT ?",
        (limit,),
    ).fetchall()
    return [
        {
            "mem_id": mem_id,
            "worker": kind,
            "kind": "worker_error",
            "retries": 0,    # WorkerPool retries before dead letter; row itself is terminal
            "at": int(at),
            "traceback": err,
        }
        for (mem_id, kind, err, at) in rows
    ]
```

- [ ] **Step 4: Run tests**

```bash
python -m pytest tests/unit/test_sqlite_diagnostic.py -v
```

Expected: 9 passed total.

- [ ] **Step 5: Commit**

```bash
git add echomem/src/echomem/drivers/sqlite.py echomem/tests/unit/test_sqlite_diagnostic.py
git commit -m "feat(echomem-driver): worker_stats and list_dead_letters"
```

---

### Task 1.4: OllamaClient.ping

**Files:**
- Modify: `echomem/src/echomem/ollama_client.py`
- Create: `echomem/tests/unit/test_ollama_ping.py`

- [ ] **Step 1: Write failing test**

```python
import pytest
from pytest_httpx import HTTPXMock

from echomem.ollama_client import OllamaClient


@pytest.mark.asyncio
async def test_ping_ok(httpx_mock: HTTPXMock):
    httpx_mock.add_response(url="http://localhost:11434/", text="Ollama is running")
    client = OllamaClient("http://localhost:11434")
    result = await client.ping()
    await client.aclose()
    assert result["status"] == "ok"
    assert result["latency_ms"] >= 0


@pytest.mark.asyncio
async def test_ping_unreachable():
    # Use an unroutable port to force connect failure quickly
    client = OllamaClient("http://127.0.0.1:1", timeout=1.0)
    result = await client.ping()
    await client.aclose()
    assert result["status"] in ("unreachable", "timeout")
    assert result["latency_ms"] is None
```

- [ ] **Step 2: Verify failure**

```bash
python -m pytest tests/unit/test_ollama_ping.py -v
```

- [ ] **Step 3: Implement `ping`**

Append to `OllamaClient`:

```python
import time

# ... inside class OllamaClient:

async def ping(self) -> dict:
    """Probe Ollama base URL. Returns {status, latency_ms}.

    status ∈ {"ok", "unreachable", "timeout"}.
    """
    start = time.perf_counter()
    try:
        resp = await self._client.get("/", timeout=2.0)
    except httpx.TimeoutException:
        return {"status": "timeout", "latency_ms": None}
    except httpx.HTTPError:
        return {"status": "unreachable", "latency_ms": None}
    elapsed_ms = int((time.perf_counter() - start) * 1000)
    if resp.status_code != 200:
        return {"status": "unreachable", "latency_ms": elapsed_ms}
    return {"status": "ok", "latency_ms": elapsed_ms}
```

- [ ] **Step 4: Verify pass**

```bash
python -m pytest tests/unit/test_ollama_ping.py -v
```

- [ ] **Step 5: Commit**

```bash
git add echomem/src/echomem/ollama_client.py echomem/tests/unit/test_ollama_ping.py
git commit -m "feat(echomem-ollama): add ping() with status + latency"
```

---

### Task 1.5: `/health/diagnostic` route

**Files:**
- Modify: `echomem/src/echomem/api/health.py`
- Create: `echomem/tests/integration/test_diagnostic_route.py`

- [ ] **Step 1: Write failing integration test**

```python
import pytest
from fastapi.testclient import TestClient

from echomem.daemon.app import create_app
from echomem.config import EchomemConfig


@pytest.fixture
def client(tmp_path):
    cfg = EchomemConfig(
        data_dir=tmp_path,
        ollama_url="http://127.0.0.1:1",      # force unreachable for deterministic test
        embedding_model="nomic-embed-text",
        generate_model="gemma2:2b",
        embedding_dim=4,
    )
    app = create_app(cfg)
    with TestClient(app) as c:
        yield c


def test_diagnostic_returns_full_shape(client):
    resp = client.get("/health/diagnostic")
    assert resp.status_code == 200
    data = resp.json()
    assert set(data.keys()) >= {"daemon", "ollama", "workers", "counts", "dead_letter"}
    assert data["daemon"]["status"] == "ok"
    assert data["ollama"]["status"] in ("unreachable", "timeout")
    assert data["counts"] == {"memories": 0, "cognitions": 0, "entities": 0, "skills": 0}
    assert isinstance(data["workers"], dict)
    assert "summarize" in data["workers"]
    assert data["dead_letter"] == []
```

- [ ] **Step 2: Verify failure**

```bash
python -m pytest tests/integration/test_diagnostic_route.py -v
```

Expected: 404 (route absent).

- [ ] **Step 3: Add route to `src/echomem/api/health.py`**

Replace the file body with:

```python
import os
from fastapi import APIRouter, Request

from echomem import __version__
from echomem.api.schemas import (
    DaemonHealth, OllamaHealth, WorkerStatus,
    DiagnosticCounts, DeadLetterEntry, DiagnosticResponse,
)

router = APIRouter()


@router.get("/health")
async def health(request: Request) -> dict:
    cfg = request.app.state.config
    return {
        "status": "ok",
        "version": __version__,
        "embedding_dim": cfg.embedding_dim,
        "embedding_model": cfg.embedding_model,
    }


@router.get("/health/diagnostic", response_model=DiagnosticResponse)
async def diagnostic(request: Request) -> DiagnosticResponse:
    cfg = request.app.state.config
    driver = request.app.state.driver
    ollama = request.app.state.ollama

    db_path = cfg.data_dir / "db.sqlite"
    db_size = db_path.stat().st_size if db_path.exists() else 0

    ping = await ollama.ping()

    return DiagnosticResponse(
        daemon=DaemonHealth(
            status="ok",
            version=__version__,
            data_dir=str(cfg.data_dir),
            db_size_bytes=db_size,
        ),
        ollama=OllamaHealth(
            status=ping["status"],
            latency_ms=ping["latency_ms"],
            generate_model=cfg.generate_model,
            embedding_model=cfg.embedding_model,
            embedding_dim=cfg.embedding_dim,
        ),
        workers={
            kind: WorkerStatus(**stats)
            for kind, stats in driver.worker_stats().items()
        },
        counts=DiagnosticCounts(
            memories=driver.count_memories(),
            cognitions=driver.count_cognitions(),
            entities=driver.count_entities(),
            skills=driver.count_skills(),
        ),
        dead_letter=[DeadLetterEntry(**row) for row in driver.list_dead_letters(limit=20)],
    )
```

- [ ] **Step 4: Verify pass**

```bash
python -m pytest tests/integration/test_diagnostic_route.py -v
python -m pytest -q     # full suite still green
```

- [ ] **Step 5: Commit**

```bash
git add echomem/src/echomem/api/health.py echomem/tests/integration/test_diagnostic_route.py
git commit -m "feat(echomem-api): GET /health/diagnostic returns full status"
```

---

## Phase 2 — Dashboard project bootstrap

The Vue project lives in `echomem/dashboard/`. All commands assume `cwd = echomem/dashboard/` unless stated.

### Task 2.1: Initialize package.json + tsconfig

**Files:**
- Create: `echomem/dashboard/package.json`
- Create: `echomem/dashboard/tsconfig.json`
- Create: `echomem/dashboard/tsconfig.node.json`
- Create: `echomem/dashboard/index.html`
- Create: `echomem/dashboard/.gitignore`

- [ ] **Step 1: Create `package.json`**

```json
{
  "name": "echomem-dashboard",
  "private": true,
  "version": "0.0.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc -b && vite build",
    "preview": "vite preview",
    "test": "vitest run",
    "test:watch": "vitest",
    "test:e2e": "playwright test",
    "test:e2e:full": "ECHOMEM_E2E_REQUIRE_OLLAMA=1 playwright test"
  },
  "dependencies": {
    "@fontsource-variable/source-serif-4": "^5.1.0",
    "@fontsource-variable/geist": "^5.1.0",
    "@fontsource/jetbrains-mono": "^5.1.0",
    "d3-force": "^3.0.0",
    "pinia": "^2.2.4",
    "vue": "^3.5.12",
    "vue-router": "^4.4.5"
  },
  "devDependencies": {
    "@playwright/test": "^1.48.0",
    "@types/d3-force": "^3.0.10",
    "@types/node": "^22.7.5",
    "@vitejs/plugin-vue": "^5.1.4",
    "@vue/test-utils": "^2.4.6",
    "happy-dom": "^15.7.4",
    "typescript": "~5.6.0",
    "vite": "^5.4.10",
    "vitest": "^2.1.3",
    "vue-tsc": "^2.1.6"
  }
}
```

- [ ] **Step 2: Create `tsconfig.json`**

```json
{
  "files": [],
  "references": [
    { "path": "./tsconfig.app.json" },
    { "path": "./tsconfig.node.json" }
  ]
}
```

- [ ] **Step 3: Create `tsconfig.app.json`**

```json
{
  "extends": "@vue/tsconfig/tsconfig.dom.json",
  "include": ["src/**/*.ts", "src/**/*.tsx", "src/**/*.vue"],
  "compilerOptions": {
    "tsBuildInfoFile": "./node_modules/.tmp/tsconfig.app.tsbuildinfo",
    "baseUrl": ".",
    "paths": {
      "@/*": ["src/*"]
    },
    "strict": true,
    "noImplicitAny": true
  }
}
```

> If `@vue/tsconfig` is not in dependencies, add `"@vue/tsconfig": "^0.5.1"` to devDependencies in package.json.

Update `package.json` `devDependencies` with:

```json
"@vue/tsconfig": "^0.5.1"
```

- [ ] **Step 4: Create `tsconfig.node.json`**

```json
{
  "extends": "@tsconfig/node20/tsconfig.json",
  "include": ["vite.config.ts"],
  "compilerOptions": {
    "tsBuildInfoFile": "./node_modules/.tmp/tsconfig.node.tsbuildinfo",
    "composite": true,
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "types": ["node"]
  }
}
```

Add to devDependencies:

```json
"@tsconfig/node20": "^20.1.4"
```

- [ ] **Step 5: Create `index.html`**

```html
<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>echomem · 本地 Agent 记忆中枢</title>
  </head>
  <body>
    <div id="app"></div>
    <script type="module" src="/src/main.ts"></script>
  </body>
</html>
```

- [ ] **Step 6: Create `.gitignore`**

```
node_modules/
dist/
*.tsbuildinfo
.vite/
playwright-report/
test-results/
```

- [ ] **Step 7: Install + verify build skeleton**

```bash
cd echomem/dashboard
npm install
ls node_modules/vue/package.json    # exists
```

- [ ] **Step 8: Commit**

```bash
git add echomem/dashboard/package.json echomem/dashboard/package-lock.json \
        echomem/dashboard/tsconfig*.json echomem/dashboard/index.html \
        echomem/dashboard/.gitignore
git commit -m "chore(echomem-dashboard): initialize Vite + Vue 3 + TS scaffold"
```

---

### Task 2.2: Vite config with API proxy

**Files:**
- Create: `echomem/dashboard/vite.config.ts`

- [ ] **Step 1: Write config**

```ts
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

const API_TARGET = process.env.ECHOMEM_API_URL ?? 'http://127.0.0.1:8473'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    strictPort: true,
    proxy: Object.fromEntries(
      ['/memory', '/derivatives', '/context', '/skills', '/health'].map(
        (path) => [path, { target: API_TARGET, changeOrigin: true }]
      )
    ),
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    sourcemap: false,
  },
  test: {
    environment: 'happy-dom',
    globals: true,
    setupFiles: ['./tests/setup.ts'],
  },
})
```

- [ ] **Step 2: Commit**

```bash
git add echomem/dashboard/vite.config.ts
git commit -m "chore(echomem-dashboard): vite config with /memory|/derivatives|... proxy"
```

---

### Task 2.3: Repo-level gitignore for dashboard build artifacts

**Files:**
- Modify: `echomem/.gitignore`

- [ ] **Step 1: Append**

```
# dashboard build artifact (generated by scripts/build_dashboard.sh)
src/echomem/_dashboard_dist/
```

- [ ] **Step 2: Commit**

```bash
git add echomem/.gitignore
git commit -m "chore(echomem): gitignore dashboard build artifact"
```

---

### Task 2.4: Design tokens

**Files:**
- Create: `echomem/dashboard/src/styles/tokens.css`
- Create: `echomem/dashboard/src/styles/base.css`

- [ ] **Step 1: Create `tokens.css`** (full content from spec §6)

Paste the entire `:root { --c-primary: #2a4d6a; ... }` block from `docs/superpowers/specs/2026-05-06-echomem-dashboard-spa-design.md` §6, including the `prefers-reduced-motion` block.

- [ ] **Step 2: Create `base.css`**

```css
@import './tokens.css';

*, *::before, *::after { box-sizing: border-box; }

html, body, #app {
  margin: 0;
  padding: 0;
  height: 100%;
}

body {
  font-family: var(--font-body);
  font-size: var(--fs-md);
  line-height: var(--lh-body);
  color: var(--c-text);
  background: var(--c-bg-alt);
  -webkit-font-smoothing: antialiased;
  text-rendering: optimizeLegibility;
}

h1, h2, h3, h4, h5, h6 {
  font-family: var(--font-display);
  font-weight: var(--fw-medium);
  margin: 0;
  color: var(--c-primary);
}

a { color: var(--c-accent-text); text-decoration: none; }
a:hover { color: var(--c-accent); }

button { font-family: inherit; }

:focus-visible {
  outline: 2px solid var(--c-accent);
  outline-offset: 2px;
}

code, pre { font-family: var(--font-mono); font-size: var(--fs-sm); }
```

- [ ] **Step 3: Commit**

```bash
git add echomem/dashboard/src/styles/
git commit -m "style(echomem-dashboard): tokens + base CSS (Harbor Editorial palette)"
```

---

### Task 2.5: main.ts and minimal App.vue

**Files:**
- Create: `echomem/dashboard/src/main.ts`
- Create: `echomem/dashboard/src/App.vue`
- Create: `echomem/dashboard/tests/setup.ts`

- [ ] **Step 1: `main.ts`**

```ts
import { createApp } from 'vue'
import { createPinia } from 'pinia'

import '@fontsource-variable/source-serif-4/index.css'
import '@fontsource-variable/geist/index.css'
import '@fontsource/jetbrains-mono/400.css'
import '@fontsource/jetbrains-mono/500.css'

import './styles/base.css'

import App from './App.vue'
import { router } from './router'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
```

- [ ] **Step 2: Stub `App.vue`** (will be expanded in Phase 6)

```vue
<script setup lang="ts">
import { RouterView } from 'vue-router'
</script>

<template>
  <RouterView />
</template>
```

- [ ] **Step 3: Stub `tests/setup.ts`**

```ts
import { afterEach } from 'vitest'

afterEach(() => {
  // Pinia / DOM auto-reset is per-test; nothing global here yet.
})
```

- [ ] **Step 4: Commit (will fail to build until router exists — that comes in Phase 6; for now just commit the file scaffolding)**

```bash
git add echomem/dashboard/src/main.ts echomem/dashboard/src/App.vue echomem/dashboard/tests/setup.ts
git commit -m "chore(echomem-dashboard): main.ts + App.vue stub + vitest setup"
```

---

## Phase 3 — TypeScript types + API client (TDD)

### Task 3.1: API types

**Files:**
- Create: `echomem/dashboard/src/api/types.ts`

- [ ] **Step 1: Define types matching backend schemas**

```ts
// Memory
export interface Memory {
  id: string
  agent_id: string
  source_kind: string
  source_ref: string | null
  text: string
  meta: Record<string, unknown> | null
  created_at: number
  updated_at: number
}

export interface MemoryListResponse { items: Memory[] }
export interface IngestRequest {
  text: string
  agent_id: string
  source_kind?: string
  source_ref?: string
  meta?: Record<string, unknown>
}
export interface IngestResponse {
  id: string
  agent_id: string
  created_at: number
}
export interface RecallHit {
  id: string
  text: string
  score: number
  source_kind: string
  source_ref: string | null
  meta: Record<string, unknown> | null
}

// Derivatives
export interface TimelineEvent {
  id: string
  window_start: number
  window_end: number
  agent_id: string
  title: string
  summary: string | null
  member_memory_ids: string[]
  rationale: string | null
}
export interface TimelineResponse { events: TimelineEvent[] }

export interface TreeNode {
  id: string
  level: number
  parent_id: string | null
  text: string
  token_estimate: number | null
  rationale: string | null
}
export interface TreeResponse { levels: TreeNode[] }

export interface GraphNode { id: string; name: string; kind: string | null }
export interface GraphEdge {
  subject_id: string
  object_id: string
  predicate: string
  confidence: number
}
export interface GraphResponse { nodes: GraphNode[]; edges: GraphEdge[] }

export interface Skill {
  id: string
  name: string
  trigger_pattern: string
  steps: string[]
  source: string
  observed_count: number
  success_count: number
}
export interface SkillsResponse { skills: Skill[] }

// Diagnostic
export interface DaemonHealth {
  status: string
  version: string
  data_dir: string
  db_size_bytes: number
}
export interface OllamaHealth {
  status: 'ok' | 'unreachable' | 'timeout'
  latency_ms: number | null
  generate_model: string
  embedding_model: string
  embedding_dim: number
}
export interface WorkerStatus {
  queue_depth: number
  last_run_at: number | null
  processed_total: number
  throttle: string | null
}
export interface DiagnosticCounts {
  memories: number
  cognitions: number
  entities: number
  skills: number
}
export interface DeadLetterEntry {
  mem_id: string | null
  worker: string
  kind: string
  retries: number
  at: number
  traceback: string | null
}
export interface DiagnosticResponse {
  daemon: DaemonHealth
  ollama: OllamaHealth
  workers: Record<string, WorkerStatus>
  counts: DiagnosticCounts
  dead_letter: DeadLetterEntry[]
}

// Errors
export type ApiErrorKind = 'network' | 'client' | 'server' | 'parse'
export interface ApiError {
  kind: ApiErrorKind
  status?: number
  message: string
  body?: string
}
```

- [ ] **Step 2: Commit**

```bash
git add echomem/dashboard/src/api/types.ts
git commit -m "feat(echomem-dashboard): api type definitions"
```

---

### Task 3.2: API client + tests

**Files:**
- Create: `echomem/dashboard/src/api/client.ts`
- Create: `echomem/dashboard/tests/unit/api/client.test.ts`

- [ ] **Step 1: Write failing tests**

```ts
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { ApiClient } from '@/api/client'

describe('ApiClient', () => {
  let originalFetch: typeof fetch

  beforeEach(() => { originalFetch = globalThis.fetch })
  afterEach(() => { globalThis.fetch = originalFetch })

  function mockFetch(impl: typeof fetch) { globalThis.fetch = impl as typeof fetch }

  it('GET parses JSON on 2xx', async () => {
    mockFetch(async () => new Response(JSON.stringify({ ok: 1 }),
      { status: 200, headers: { 'Content-Type': 'application/json' } }))
    const c = new ApiClient()
    expect(await c.get('/health')).toEqual({ ok: 1 })
  })

  it('POST sends JSON body', async () => {
    let captured: { url: string; body: string } | null = null
    mockFetch(async (input, init) => {
      captured = { url: input.toString(), body: init?.body as string }
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } })
    })
    const c = new ApiClient()
    await c.post('/memory/ingest', { text: 'hi', agent_id: 'cli' })
    expect(captured!.url).toContain('/memory/ingest')
    expect(JSON.parse(captured!.body)).toEqual({ text: 'hi', agent_id: 'cli' })
  })

  it('classifies network errors', async () => {
    mockFetch(async () => { throw new TypeError('failed to fetch') })
    const c = new ApiClient()
    await expect(c.get('/health')).rejects.toMatchObject({ kind: 'network' })
  })

  it('classifies 4xx as client error', async () => {
    mockFetch(async () => new Response('bad', { status: 400 }))
    const c = new ApiClient()
    await expect(c.get('/health')).rejects.toMatchObject({ kind: 'client', status: 400 })
  })

  it('classifies 5xx as server error', async () => {
    mockFetch(async () => new Response('boom', { status: 503 }))
    const c = new ApiClient()
    await expect(c.get('/health')).rejects.toMatchObject({ kind: 'server', status: 503 })
  })
})
```

- [ ] **Step 2: Verify failure**

```bash
cd echomem/dashboard
npx vitest run tests/unit/api/client.test.ts
```

- [ ] **Step 3: Implement `client.ts`**

```ts
import type { ApiError, ApiErrorKind } from './types'

export class ApiClient {
  constructor(private base = '') {}

  async get<T>(path: string, params?: Record<string, string | number | undefined>): Promise<T> {
    const qs = params
      ? '?' + new URLSearchParams(
          Object.entries(params)
            .filter(([, v]) => v !== undefined)
            .map(([k, v]) => [k, String(v)])
        ).toString()
      : ''
    return this.request<T>('GET', path + qs)
  }

  async post<T>(path: string, body: unknown): Promise<T> {
    return this.request<T>('POST', path, JSON.stringify(body), {
      'Content-Type': 'application/json',
    })
  }

  async delete<T>(path: string): Promise<T> {
    return this.request<T>('DELETE', path)
  }

  private async request<T>(
    method: string,
    path: string,
    body?: string,
    headers?: Record<string, string>
  ): Promise<T> {
    let resp: Response
    try {
      resp = await fetch(this.base + path, { method, headers, body })
    } catch (e) {
      throw apiErr('network', `network error: ${(e as Error).message}`)
    }
    if (resp.status >= 500) {
      throw apiErr('server', `server ${resp.status}`, resp.status, await resp.text())
    }
    if (resp.status >= 400) {
      throw apiErr('client', `client ${resp.status}`, resp.status, await resp.text())
    }
    if (resp.status === 204) return undefined as T
    try {
      return (await resp.json()) as T
    } catch (e) {
      throw apiErr('parse', `invalid JSON: ${(e as Error).message}`)
    }
  }
}

function apiErr(kind: ApiErrorKind, message: string, status?: number, body?: string): ApiError {
  return { kind, message, status, body }
}

export const api = new ApiClient()
```

- [ ] **Step 4: Verify pass**

```bash
npx vitest run tests/unit/api/client.test.ts
```

- [ ] **Step 5: Commit**

```bash
git add echomem/dashboard/src/api/client.ts echomem/dashboard/tests/unit/api/client.test.ts
git commit -m "feat(echomem-dashboard): ApiClient with error classification + tests"
```

---

## Phase 4 — Pinia stores (TDD)

### Task 4.1: `useUiStore`

**Files:**
- Create: `echomem/dashboard/src/stores/ui.ts`
- Create: `echomem/dashboard/tests/unit/stores/ui.test.ts`

- [ ] **Step 1: Failing test**

```ts
import { setActivePinia, createPinia } from 'pinia'
import { describe, it, expect, beforeEach } from 'vitest'
import { useUiStore } from '@/stores/ui'

describe('useUiStore', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('starts with no banner and dialogs closed', () => {
    const ui = useUiStore()
    expect(ui.banner).toBeNull()
    expect(ui.quickIngestOpen).toBe(false)
    expect(ui.lineageOpen).toBe(false)
  })

  it('setBanner / clearBanner', () => {
    const ui = useUiStore()
    ui.setBanner({ kind: 'error', text: 'down' })
    expect(ui.banner?.text).toBe('down')
    ui.clearBanner()
    expect(ui.banner).toBeNull()
  })

  it('toggleQuickIngest opens and closes', () => {
    const ui = useUiStore()
    ui.toggleQuickIngest(true); expect(ui.quickIngestOpen).toBe(true)
    ui.toggleQuickIngest(false); expect(ui.quickIngestOpen).toBe(false)
  })
})
```

- [ ] **Step 2: Verify failure**

```bash
npx vitest run tests/unit/stores/ui.test.ts
```

- [ ] **Step 3: Implement**

```ts
import { defineStore } from 'pinia'
import { ref } from 'vue'

export type BannerKind = 'error' | 'warning' | 'info'
export interface Banner { kind: BannerKind; text: string; retry?: () => void }

export const useUiStore = defineStore('ui', () => {
  const banner = ref<Banner | null>(null)
  const quickIngestOpen = ref(false)
  const lineageOpen = ref(false)

  function setBanner(b: Banner) { banner.value = b }
  function clearBanner() { banner.value = null }
  function toggleQuickIngest(v: boolean) { quickIngestOpen.value = v }
  function toggleLineage(v: boolean) { lineageOpen.value = v }

  return { banner, quickIngestOpen, lineageOpen, setBanner, clearBanner, toggleQuickIngest, toggleLineage }
})
```

- [ ] **Step 4: Pass + commit**

```bash
npx vitest run tests/unit/stores/ui.test.ts
git add echomem/dashboard/src/stores/ui.ts echomem/dashboard/tests/unit/stores/ui.test.ts
git commit -m "feat(echomem-dashboard): useUiStore"
```

---

### Task 4.2: `useStatusStore` + polling

**Files:**
- Create: `echomem/dashboard/src/stores/status.ts`
- Create: `echomem/dashboard/tests/unit/stores/status.test.ts`

- [ ] **Step 1: Failing test**

```ts
import { setActivePinia, createPinia } from 'pinia'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { useStatusStore } from '@/stores/status'
import type { DiagnosticResponse } from '@/api/types'

const fixture: DiagnosticResponse = {
  daemon: { status: 'ok', version: '0.1.0', data_dir: '/tmp', db_size_bytes: 100 },
  ollama: { status: 'ok', latency_ms: 5, generate_model: 'g', embedding_model: 'e', embedding_dim: 768 },
  workers: {
    summarize: { queue_depth: 0, last_run_at: 0, processed_total: 0, throttle: null },
    extract_entity: { queue_depth: 1, last_run_at: 0, processed_total: 0, throttle: null },
    aggregate_timeline: { queue_depth: 0, last_run_at: 0, processed_total: 0, throttle: null },
    reflect: { queue_depth: 0, last_run_at: 0, processed_total: 0, throttle: null },
    summarize_blob: { queue_depth: 0, last_run_at: 0, processed_total: 0, throttle: null },
    extract_blob: { queue_depth: 0, last_run_at: 0, processed_total: 0, throttle: null },
  },
  counts: { memories: 5, cognitions: 2, entities: 3, skills: 1 },
  dead_letter: [],
}

describe('useStatusStore', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('queueDepth aggregates across workers', () => {
    const s = useStatusStore()
    s.applyDiagnostic(fixture)
    expect(s.queueDepth).toBe(1)
  })

  it('counts available after applyDiagnostic', () => {
    const s = useStatusStore()
    s.applyDiagnostic(fixture)
    expect(s.counts.memories).toBe(5)
    expect(s.counts.cognitions).toBe(2)
  })

  it('refresh() calls /health/diagnostic and applies', async () => {
    const fetchSpy = vi.fn().mockResolvedValue(fixture)
    const s = useStatusStore()
    await s.refresh({ get: fetchSpy } as never)
    expect(fetchSpy).toHaveBeenCalledWith('/health/diagnostic')
    expect(s.counts.memories).toBe(5)
  })
})
```

- [ ] **Step 2: Verify failure**

```bash
npx vitest run tests/unit/stores/status.test.ts
```

- [ ] **Step 3: Implement**

```ts
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api } from '@/api/client'
import type { DiagnosticResponse, OllamaHealth } from '@/api/types'

const EMPTY_OLLAMA: OllamaHealth = {
  status: 'unreachable', latency_ms: null,
  generate_model: '', embedding_model: '', embedding_dim: 0,
}

export const useStatusStore = defineStore('status', () => {
  const daemon = ref<DiagnosticResponse['daemon'] | null>(null)
  const ollama = ref<OllamaHealth>(EMPTY_OLLAMA)
  const workers = ref<DiagnosticResponse['workers']>({})
  const counts = ref({ memories: 0, cognitions: 0, entities: 0, skills: 0 })
  const deadLetter = ref<DiagnosticResponse['dead_letter']>([])
  const lastError = ref<string | null>(null)

  const queueDepth = computed(() =>
    Object.values(workers.value).reduce((s, w) => s + (w?.queue_depth ?? 0), 0)
  )

  function applyDiagnostic(d: DiagnosticResponse) {
    daemon.value = d.daemon
    ollama.value = d.ollama
    workers.value = d.workers
    counts.value = d.counts
    deadLetter.value = d.dead_letter
    lastError.value = null
  }

  async function refresh(client = api) {
    try {
      const data = await client.get<DiagnosticResponse>('/health/diagnostic')
      applyDiagnostic(data)
    } catch (e) {
      lastError.value = (e as { message?: string }).message ?? 'unknown'
      throw e
    }
  }

  return { daemon, ollama, workers, counts, deadLetter, lastError, queueDepth, applyDiagnostic, refresh }
})
```

- [ ] **Step 4: Pass + commit**

```bash
npx vitest run tests/unit/stores/status.test.ts
git add echomem/dashboard/src/stores/status.ts echomem/dashboard/tests/unit/stores/status.test.ts
git commit -m "feat(echomem-dashboard): useStatusStore + refresh"
```

---

### Task 4.3: `useMemoryStore`

**Files:**
- Create: `echomem/dashboard/src/stores/memory.ts`
- Create: `echomem/dashboard/tests/unit/stores/memory.test.ts`

- [ ] **Step 1: Failing test**

```ts
import { setActivePinia, createPinia } from 'pinia'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { useMemoryStore } from '@/stores/memory'
import type { Memory } from '@/api/types'

const m = (id: string, t = 1): Memory => ({
  id, agent_id: 'cli', source_kind: 'explicit', source_ref: null,
  text: 't' + id, meta: null, created_at: t, updated_at: t,
})

describe('useMemoryStore', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('loadInitial replaces items and sets cursor', async () => {
    const get = vi.fn().mockResolvedValue({ items: [m('a', 10), m('b', 5)] })
    const s = useMemoryStore()
    await s.loadInitial({ get } as never)
    expect(s.items.length).toBe(2)
    expect(s.cursor).toBe(5)
    expect(s.hasMore).toBe(true)
  })

  it('loadMore appends and updates cursor', async () => {
    const get = vi.fn()
      .mockResolvedValueOnce({ items: [m('a', 10), m('b', 5)] })
      .mockResolvedValueOnce({ items: [m('c', 3)] })
    const s = useMemoryStore()
    await s.loadInitial({ get } as never)
    await s.loadMore({ get } as never)
    expect(s.items.map((it) => it.id)).toEqual(['a', 'b', 'c'])
    expect(s.cursor).toBe(3)
  })

  it('hasMore false when result is shorter than limit', async () => {
    const get = vi.fn().mockResolvedValue({ items: [] })
    const s = useMemoryStore()
    await s.loadInitial({ get } as never)
    expect(s.hasMore).toBe(false)
  })

  it('filteredItems applies client-side text search', () => {
    const s = useMemoryStore()
    s.items = [m('a'), { ...m('b'), text: 'hello world' }]
    s.filters.query = 'hello'
    expect(s.filteredItems.map((it) => it.id)).toEqual(['b'])
  })
})
```

- [ ] **Step 2: Verify failure**

```bash
npx vitest run tests/unit/stores/memory.test.ts
```

- [ ] **Step 3: Implement**

```ts
import { defineStore } from 'pinia'
import { computed, reactive, ref } from 'vue'
import { api } from '@/api/client'
import type { Memory, MemoryListResponse } from '@/api/types'

const PAGE_SIZE = 50

export const useMemoryStore = defineStore('memory', () => {
  const items = ref<Memory[]>([])
  const cursor = ref<number | null>(null)
  const hasMore = ref(true)
  const loading = ref(false)
  const filters = reactive({ agent: '' as string, sourceKind: '' as string, query: '' })

  async function loadInitial(client = api) {
    loading.value = true
    try {
      const data = await client.get<MemoryListResponse>('/memory/list', {
        agent_id: filters.agent || undefined,
        limit: PAGE_SIZE,
      })
      items.value = data.items
      cursor.value = data.items.at(-1)?.created_at ?? null
      hasMore.value = data.items.length === PAGE_SIZE
    } finally { loading.value = false }
  }

  async function loadMore(client = api) {
    if (loading.value || !hasMore.value || cursor.value == null) return
    loading.value = true
    try {
      const data = await client.get<MemoryListResponse>('/memory/list', {
        agent_id: filters.agent || undefined,
        before: cursor.value,
        limit: PAGE_SIZE,
      })
      items.value.push(...data.items)
      cursor.value = data.items.at(-1)?.created_at ?? cursor.value
      hasMore.value = data.items.length === PAGE_SIZE
    } finally { loading.value = false }
  }

  const filteredItems = computed(() => {
    let out = items.value
    if (filters.sourceKind) out = out.filter((m) => m.source_kind === filters.sourceKind)
    if (filters.query) {
      const q = filters.query.toLowerCase()
      out = out.filter((m) => m.text.toLowerCase().includes(q))
    }
    return out
  })

  return { items, cursor, hasMore, loading, filters, filteredItems, loadInitial, loadMore }
})
```

- [ ] **Step 4: Pass + commit**

```bash
npx vitest run tests/unit/stores/memory.test.ts
git add echomem/dashboard/src/stores/memory.ts echomem/dashboard/tests/unit/stores/memory.test.ts
git commit -m "feat(echomem-dashboard): useMemoryStore + cursor pagination + filters"
```

---

### Task 4.4: `useCognitionStore`

**Files:**
- Create: `echomem/dashboard/src/stores/cognition.ts`
- Create: `echomem/dashboard/tests/unit/stores/cognition.test.ts`

- [ ] **Step 1: Failing test**

```ts
import { setActivePinia, createPinia } from 'pinia'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { useCognitionStore, type CognitionSub } from '@/stores/cognition'

describe('useCognitionStore', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('loadTimeline populates events', async () => {
    const get = vi.fn().mockResolvedValue({
      events: [{ id: 'e1', window_start: 0, window_end: 1, agent_id: 'cli',
                 title: 't', summary: null, member_memory_ids: [], rationale: null }],
    })
    const s = useCognitionStore()
    await s.loadTimeline({ get } as never)
    expect(s.timeline.length).toBe(1)
  })

  it('loadGraph stores nodes/edges', async () => {
    const get = vi.fn().mockResolvedValue({
      nodes: [{ id: 'n1', name: 'jacky', kind: 'person' }],
      edges: [],
    })
    const s = useCognitionStore()
    s.graphSeed = 'ent:jacky'
    await s.loadGraph({ get } as never)
    expect(s.graph.nodes).toHaveLength(1)
  })

  it('setActiveSub updates active', () => {
    const s = useCognitionStore()
    const subs: CognitionSub[] = ['timeline', 'summary', 'graph', 'skill']
    for (const sub of subs) {
      s.setActiveSub(sub)
      expect(s.activeSub).toBe(sub)
    }
  })
})
```

- [ ] **Step 2: Verify failure**

```bash
npx vitest run tests/unit/stores/cognition.test.ts
```

- [ ] **Step 3: Implement**

```ts
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api/client'
import type { TimelineEvent, TimelineResponse, TreeNode, TreeResponse,
              GraphResponse, Skill, SkillsResponse } from '@/api/types'

export type CognitionSub = 'timeline' | 'summary' | 'graph' | 'skill'

export const useCognitionStore = defineStore('cognition', () => {
  const activeSub = ref<CognitionSub>('timeline')

  const timeline = ref<TimelineEvent[]>([])
  const summaryGroups = ref<Record<string, TreeNode[]>>({})  // key = `${source_kind}:${source_ref}`
  const graphSeed = ref<string>('')
  const graphHops = ref<number>(2)
  const graph = ref<GraphResponse>({ nodes: [], edges: [] })
  const skills = ref<Skill[]>([])

  function setActiveSub(s: CognitionSub) { activeSub.value = s }

  async function loadTimeline(client = api) {
    const now = Date.now()
    const data = await client.get<TimelineResponse>('/derivatives/timeline', {
      start_ms: now - 7 * 86_400_000, end_ms: now,
    })
    timeline.value = data.events.sort((a, b) => b.window_end - a.window_end)
  }

  async function loadSummaryGroup(source_kind: string, source_ref: string, client = api) {
    const data = await client.get<TreeResponse>('/derivatives/tree', { source_kind, source_ref })
    summaryGroups.value[`${source_kind}:${source_ref}`] = data.levels
  }

  async function loadGraph(client = api) {
    if (!graphSeed.value) return
    const data = await client.get<GraphResponse>('/derivatives/graph', {
      seed: graphSeed.value, hops: graphHops.value,
    })
    graph.value = data
  }

  async function loadSkills(ctx: string, client = api) {
    const data = await client.get<SkillsResponse>('/derivatives/skills', { ctx, k: 20 })
    skills.value = data.skills
  }

  return {
    activeSub, timeline, summaryGroups,
    graphSeed, graphHops, graph, skills,
    setActiveSub, loadTimeline, loadSummaryGroup, loadGraph, loadSkills,
  }
})
```

- [ ] **Step 4: Pass + commit**

```bash
npx vitest run tests/unit/stores/cognition.test.ts
git add echomem/dashboard/src/stores/cognition.ts echomem/dashboard/tests/unit/stores/cognition.test.ts
git commit -m "feat(echomem-dashboard): useCognitionStore (4 sub-views)"
```

---

### Task 4.5: `useLineageStore`

**Files:**
- Create: `echomem/dashboard/src/stores/lineage.ts`
- Create: `echomem/dashboard/tests/unit/stores/lineage.test.ts`

- [ ] **Step 1: Failing test**

```ts
import { setActivePinia, createPinia } from 'pinia'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { useLineageStore } from '@/stores/lineage'
import { useCognitionStore } from '@/stores/cognition'
import type { Memory } from '@/api/types'

function memWith(id: string, sourceRef: string | null = null): Memory {
  return {
    id, agent_id: 'cli', source_kind: 'explicit', source_ref: sourceRef,
    text: 't', meta: null, created_at: 0, updated_at: 0,
  }
}

describe('useLineageStore', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('loadTimeline gathers memories and unique blob refs', async () => {
    const cog = useCognitionStore()
    cog.timeline = [{
      id: 'e1', window_start: 0, window_end: 1, agent_id: 'cli',
      title: 'x', summary: null, rationale: null,
      member_memory_ids: ['m1', 'm2'],
    }]
    const get = vi.fn(async (path: string) => {
      if (path === '/memory/m1') return memWith('m1', 'sha256:abc')
      if (path === '/memory/m2') return memWith('m2', 'sha256:abc')
      throw new Error('unexpected ' + path)
    })
    const s = useLineageStore()
    await s.load('e1', 'timeline', { get } as never)
    expect(s.current?.cognition.id).toBe('e1')
    expect(s.current?.memories.map((m) => m.id)).toEqual(['m1', 'm2'])
    expect(s.current?.blobs).toEqual(['sha256:abc'])
  })

  it('clear resets state', () => {
    const s = useLineageStore()
    s.current = { cognition: { id: 'e1', kind: 'timeline', label: 'x' },
                  memories: [], blobs: [] }
    s.clear()
    expect(s.current).toBeNull()
  })
})
```

- [ ] **Step 2: Verify failure**

```bash
npx vitest run tests/unit/stores/lineage.test.ts
```

- [ ] **Step 3: Implement**

```ts
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api/client'
import type { Memory } from '@/api/types'
import { useCognitionStore, type CognitionSub } from './cognition'

export interface LineageCognition { id: string; kind: CognitionSub; label: string }
export interface LineageBundle {
  cognition: LineageCognition
  memories: Memory[]
  blobs: string[]                // unique sha256: refs
}

export const useLineageStore = defineStore('lineage', () => {
  const current = ref<LineageBundle | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  function clear() { current.value = null; error.value = null }

  async function load(id: string, kind: CognitionSub, client = api) {
    loading.value = true; error.value = null
    try {
      const cog = useCognitionStore()
      if (kind === 'timeline') {
        const event = cog.timeline.find((e) => e.id === id)
        if (!event) throw new Error('timeline event not in cache')
        const memories = await Promise.all(
          event.member_memory_ids.map((mid) => client.get<Memory>(`/memory/${mid}`))
        )
        const blobs = Array.from(new Set(
          memories.map((m) => m.source_ref).filter((r): r is string => !!r && r.startsWith('sha256:'))
        ))
        current.value = {
          cognition: { id, kind, label: event.title },
          memories, blobs,
        }
      } else {
        // summary / graph / skill — v1 placeholders (see spec §4.3)
        current.value = {
          cognition: { id, kind, label: id },
          memories: [], blobs: [],
        }
      }
    } catch (e) {
      error.value = (e as { message?: string }).message ?? 'unknown'
      throw e
    } finally { loading.value = false }
  }

  return { current, loading, error, load, clear }
})
```

- [ ] **Step 4: Pass + commit**

```bash
npx vitest run tests/unit/stores/lineage.test.ts
git add echomem/dashboard/src/stores/lineage.ts echomem/dashboard/tests/unit/stores/lineage.test.ts
git commit -m "feat(echomem-dashboard): useLineageStore (3-column resolution)"
```

---

## Phase 5 — Base components

These are presentation-focused. Each task includes one render/interaction test where it has logic; pure markup components get a smoke test only.

### Task 5.1: `Icon.vue`

**Files:**
- Create: `echomem/dashboard/src/components/Icon.vue`
- Create: `echomem/dashboard/tests/unit/components/Icon.test.ts`

- [ ] **Step 1: Failing test**

```ts
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import Icon from '@/components/Icon.vue'

describe('Icon', () => {
  it('renders the requested glyph', () => {
    const w = mount(Icon, { props: { name: 'plus' } })
    expect(w.find('svg').exists()).toBe(true)
    expect(w.attributes('data-icon')).toBe('plus')
  })

  it('renders nothing for unknown name', () => {
    const w = mount(Icon, { props: { name: 'nope' as never } })
    expect(w.find('svg').exists()).toBe(false)
  })
})
```

- [ ] **Step 2: Implement**

```vue
<script setup lang="ts">
import { computed } from 'vue'

type IconName = 'plus' | 'close' | 'arrow-right' | 'chevron-down' | 'refresh' | 'search' | 'dot'

const props = defineProps<{ name: IconName; size?: number }>()
const dims = computed(() => props.size ?? 16)

const PATHS: Record<IconName, string> = {
  'plus': 'M12 5v14M5 12h14',
  'close': 'M6 6l12 12M18 6l-12 12',
  'arrow-right': 'M5 12h14M13 6l6 6-6 6',
  'chevron-down': 'M6 9l6 6 6-6',
  'refresh': 'M4 12a8 8 0 0 1 14-5.3M20 12a8 8 0 0 1-14 5.3M20 4v6h-6M4 20v-6h6',
  'search': 'M21 21l-4.3-4.3M10 17a7 7 0 1 1 0-14 7 7 0 0 1 0 14z',
  'dot': '',
}
</script>

<template>
  <svg
    v-if="name in PATHS"
    :data-icon="name"
    :width="dims"
    :height="dims"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.6"
    stroke-linecap="round"
    stroke-linejoin="round"
  >
    <circle v-if="name === 'dot'" cx="12" cy="12" r="4" fill="currentColor" stroke="none" />
    <path v-else :d="PATHS[name]" />
  </svg>
</template>
```

- [ ] **Step 3: Pass + commit**

```bash
npx vitest run tests/unit/components/Icon.test.ts
git add echomem/dashboard/src/components/Icon.vue echomem/dashboard/tests/unit/components/Icon.test.ts
git commit -m "feat(echomem-dashboard): Icon component (hand-written SVGs)"
```

---

### Task 5.2: `Button.vue`

**Files:**
- Create: `echomem/dashboard/src/components/Button.vue`

- [ ] **Step 1: Implement**

```vue
<script setup lang="ts">
defineProps<{ variant?: 'primary' | 'subtle' | 'danger'; disabled?: boolean }>()
defineEmits<{ click: [MouseEvent] }>()
</script>

<template>
  <button
    type="button"
    :class="['btn', variant ?? 'subtle']"
    :disabled="disabled"
    @click="$emit('click', $event)"
  >
    <slot />
  </button>
</template>

<style scoped>
.btn {
  font-family: var(--font-body);
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
  padding: var(--space-sm) var(--space-md);
  border-radius: var(--radius-md);
  border: 1px solid transparent;
  cursor: pointer;
  transition: background var(--t-fast) var(--ease-out),
              border-color var(--t-fast) var(--ease-out);
}
.btn.primary { background: var(--c-accent); color: white; }
.btn.primary:hover { background: var(--c-accent-hover); }
.btn.subtle { background: var(--c-bg); color: var(--c-text); border-color: var(--c-border); }
.btn.subtle:hover { border-color: var(--c-border-hover); background: var(--c-bg-alt); }
.btn.danger { background: var(--c-bg); color: var(--c-danger); border-color: var(--c-danger); }
.btn.danger:hover { background: rgba(194, 97, 74, 0.06); }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add echomem/dashboard/src/components/Button.vue
git commit -m "feat(echomem-dashboard): Button component"
```

---

### Task 5.3: `Tag.vue`, `Tile.vue`, `Card.vue`

**Files:**
- Create: `echomem/dashboard/src/components/Tag.vue`
- Create: `echomem/dashboard/src/components/Tile.vue`
- Create: `echomem/dashboard/src/components/Card.vue`

- [ ] **Step 1: `Tag.vue`**

```vue
<script setup lang="ts">
defineProps<{ tone?: 'default' | 'accent' | 'mono' }>()
</script>

<template>
  <span :class="['tag', tone ?? 'default']"><slot /></span>
</template>

<style scoped>
.tag {
  display: inline-flex; align-items: center;
  padding: 2px var(--space-sm);
  border-radius: 10px;
  font-size: var(--fs-xs);
  font-weight: var(--fw-medium);
  background: var(--c-bg);
  border: 1px solid var(--c-border);
  color: var(--c-text-muted);
  letter-spacing: 0.2px;
}
.tag.accent {
  background: var(--c-accent-light);
  border-color: var(--c-accent);
  color: var(--c-accent-text);
}
.tag.mono { font-family: var(--font-mono); font-size: 11px; }
</style>
```

- [ ] **Step 2: `Tile.vue`**

```vue
<script setup lang="ts">
defineProps<{ value: number | string; label: string }>()
</script>

<template>
  <div class="tile">
    <div class="num">{{ value }}</div>
    <div class="lbl">{{ label }}</div>
  </div>
</template>

<style scoped>
.tile {
  background: var(--c-bg);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  padding: var(--space-md) var(--space-lg);
}
.tile .num {
  font-family: var(--font-display);
  font-size: var(--fs-h2);
  font-weight: var(--fw-medium);
  color: var(--c-primary);
  line-height: 1.1;
}
.tile .lbl {
  font-size: var(--fs-xs);
  color: var(--c-text-muted);
  margin-top: 4px;
  letter-spacing: 0.5px;
  text-transform: uppercase;
}
</style>
```

- [ ] **Step 3: `Card.vue`**

```vue
<script setup lang="ts">
defineProps<{ as?: 'div' | 'article' | 'section' }>()
</script>

<template>
  <component :is="as ?? 'div'" class="card"><slot /></component>
</template>

<style scoped>
.card {
  background: var(--c-bg);
  border: 1px solid var(--c-border);
  border-radius: var(--radius-md);
  padding: var(--space-lg);
}
</style>
```

- [ ] **Step 4: Commit**

```bash
git add echomem/dashboard/src/components/Tag.vue \
        echomem/dashboard/src/components/Tile.vue \
        echomem/dashboard/src/components/Card.vue
git commit -m "feat(echomem-dashboard): Tag + Tile + Card primitives"
```

---

### Task 5.4: `Banner.vue`, `EmptyState.vue`

**Files:**
- Create: `echomem/dashboard/src/components/Banner.vue`
- Create: `echomem/dashboard/src/components/EmptyState.vue`

- [ ] **Step 1: `Banner.vue`**

```vue
<script setup lang="ts">
import Button from './Button.vue'
defineProps<{ kind: 'error' | 'warning' | 'info'; text: string; retryLabel?: string }>()
defineEmits<{ retry: [] }>()
</script>

<template>
  <div :class="['banner', kind]" role="status">
    <span class="text">{{ text }}</span>
    <Button v-if="retryLabel" variant="subtle" @click="$emit('retry')">{{ retryLabel }}</Button>
  </div>
</template>

<style scoped>
.banner {
  display: flex; align-items: center; justify-content: space-between;
  padding: var(--space-sm) var(--space-lg);
  border-top: 1px solid var(--c-border);
  background: var(--c-bg-alt);
  font-size: var(--fs-sm);
}
.banner.error { border-top-color: var(--c-danger); }
.banner.warning { border-top-color: var(--c-warning); }
.banner.info { border-top-color: var(--c-primary); }
.text { color: var(--c-text); }
</style>
```

- [ ] **Step 2: `EmptyState.vue`**

```vue
<script setup lang="ts">
defineProps<{ title: string; body?: string }>()
</script>

<template>
  <div class="empty">
    <h3 class="title">{{ title }}</h3>
    <p v-if="body" class="body">{{ body }}</p>
    <div v-if="$slots.actions" class="actions"><slot name="actions" /></div>
  </div>
</template>

<style scoped>
.empty {
  padding: var(--space-3xl) var(--space-2xl);
  max-width: 560px;
  margin: 0 auto;
}
.title { font-size: var(--fs-h3); margin-bottom: var(--space-sm); }
.body {
  color: var(--c-text-muted);
  font-size: var(--fs-sm);
  line-height: var(--lh-loose);
  white-space: pre-wrap;
}
.actions { margin-top: var(--space-lg); display: flex; gap: var(--space-sm); }
</style>
```

- [ ] **Step 3: Commit**

```bash
git add echomem/dashboard/src/components/Banner.vue echomem/dashboard/src/components/EmptyState.vue
git commit -m "feat(echomem-dashboard): Banner + EmptyState"
```

---

### Task 5.5: `Drawer.vue` + `ColumnList.vue`

**Files:**
- Create: `echomem/dashboard/src/components/Drawer.vue`
- Create: `echomem/dashboard/src/components/ColumnList.vue`
- Create: `echomem/dashboard/tests/unit/components/Drawer.test.ts`

- [ ] **Step 1: `Drawer.vue`**

```vue
<script setup lang="ts">
import Icon from './Icon.vue'
defineProps<{ open: boolean; title?: string; widthVw?: number }>()
defineEmits<{ close: [] }>()
</script>

<template>
  <Transition name="drawer">
    <aside v-if="open" class="drawer" :style="{ width: `min(${widthVw ?? 70}vw, 960px)` }">
      <header class="head">
        <h3 v-if="title">{{ title }}</h3>
        <button class="close" @click="$emit('close')" aria-label="close"><Icon name="close" /></button>
      </header>
      <div class="body"><slot /></div>
    </aside>
  </Transition>
</template>

<style scoped>
.drawer {
  position: fixed; right: 0; top: 0; bottom: 0;
  background: var(--c-bg);
  border-left: 1px solid var(--c-border);
  box-shadow: var(--shadow-drawer);
  display: flex; flex-direction: column;
  z-index: 50;
}
.head {
  display: flex; align-items: center; justify-content: space-between;
  padding: var(--space-md) var(--space-lg);
  border-bottom: 1px solid var(--c-border);
}
.head h3 { font-size: var(--fs-md); margin: 0; }
.close { background: transparent; border: none; cursor: pointer; color: var(--c-text-muted); padding: 4px; }
.close:hover { color: var(--c-text); }
.body { flex: 1; overflow-y: auto; }

.drawer-enter-active, .drawer-leave-active {
  transition: transform var(--t-drawer) var(--ease-out);
}
.drawer-enter-from, .drawer-leave-to { transform: translateX(100%); }
</style>
```

- [ ] **Step 2: `ColumnList.vue`**

```vue
<script setup lang="ts">
defineProps<{
  title: string
  items: { id: string; label: string; sub?: string }[]
  activeId?: string | null
}>()
defineEmits<{ select: [id: string] }>()
</script>

<template>
  <div class="col">
    <header class="head">{{ title }}</header>
    <ul class="list">
      <li v-for="it in items" :key="it.id"
          :class="['cell', { active: it.id === activeId }]"
          @click="$emit('select', it.id)">
        <div class="lbl">{{ it.label }}</div>
        <div v-if="it.sub" class="sub">{{ it.sub }}</div>
      </li>
      <li v-if="items.length === 0" class="empty">无</li>
    </ul>
  </div>
</template>

<style scoped>
.col {
  border-right: 1px solid var(--c-border);
  padding: var(--space-md) var(--space-sm);
  overflow-y: auto;
  display: flex; flex-direction: column;
  min-width: 0;
}
.col:last-child { border-right: none; }
.head {
  font-size: var(--fs-xs);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  color: var(--c-text-muted);
  padding: 0 var(--space-sm);
  margin-bottom: var(--space-sm);
}
.list { list-style: none; margin: 0; padding: 0; }
.cell {
  padding: var(--space-sm); border-radius: var(--radius-sm);
  cursor: pointer; margin-bottom: 2px;
  border: 1px solid transparent;
  font-size: var(--fs-sm);
}
.cell:hover { background: var(--c-bg-alt); }
.cell.active { background: var(--c-accent-light); color: var(--c-accent-text); border-color: var(--c-accent); }
.cell .sub { font-size: var(--fs-xs); color: var(--c-text-muted); margin-top: 2px; }
.empty { color: var(--c-text-faint); font-size: var(--fs-xs); padding: var(--space-sm); }
</style>
```

- [ ] **Step 3: Drawer interaction test**

```ts
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import Drawer from '@/components/Drawer.vue'

describe('Drawer', () => {
  it('renders when open=true', () => {
    const w = mount(Drawer, { props: { open: true, title: 'x' } })
    expect(w.find('.drawer').exists()).toBe(true)
  })

  it('emits close when close button clicked', async () => {
    const w = mount(Drawer, { props: { open: true, title: 'x' } })
    await w.find('.close').trigger('click')
    expect(w.emitted('close')).toBeTruthy()
  })

  it('hidden when open=false', () => {
    const w = mount(Drawer, { props: { open: false } })
    expect(w.find('.drawer').exists()).toBe(false)
  })
})
```

- [ ] **Step 4: Pass + commit**

```bash
npx vitest run tests/unit/components/Drawer.test.ts
git add echomem/dashboard/src/components/Drawer.vue \
        echomem/dashboard/src/components/ColumnList.vue \
        echomem/dashboard/tests/unit/components/Drawer.test.ts
git commit -m "feat(echomem-dashboard): Drawer + ColumnList"
```

---

## Phase 6 — Router + AppShell + polling orchestration

### Task 6.1: `router.ts`

**Files:**
- Create: `echomem/dashboard/src/router.ts`

- [ ] **Step 1: Implement**

```ts
import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  { path: '/',           name: 'overview',  component: () => import('./pages/OverviewPage.vue') },
  { path: '/memory',     name: 'memory',    component: () => import('./pages/MemoryPage.vue') },
  {
    path: '/cognition',
    component: () => import('./pages/CognitionPage.vue'),
    children: [
      { path: '',          redirect: '/cognition/timeline' },
      { path: 'timeline',  name: 'cog-timeline', component: () => import('./pages/cognition/TimelineView.vue') },
      { path: 'summary',   name: 'cog-summary',  component: () => import('./pages/cognition/SummaryView.vue') },
      { path: 'graph',     name: 'cog-graph',    component: () => import('./pages/cognition/GraphView.vue') },
      { path: 'skill',     name: 'cog-skill',    component: () => import('./pages/cognition/SkillView.vue') },
    ],
  },
  { path: '/status',     name: 'status',    component: () => import('./pages/StatusPage.vue') },
]

export const router = createRouter({
  history: createWebHashHistory(),
  routes,
})
```

- [ ] **Step 2: Commit**

```bash
git add echomem/dashboard/src/router.ts
git commit -m "feat(echomem-dashboard): vue-router (hash mode) with 4 top-level routes"
```

---

### Task 6.2: `AppShell.vue`

**Files:**
- Create: `echomem/dashboard/src/components/AppShell.vue`

- [ ] **Step 1: Implement**

```vue
<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, RouterLink } from 'vue-router'
import Button from './Button.vue'
import Icon from './Icon.vue'
import Banner from './Banner.vue'
import { useUiStore } from '@/stores/ui'

const ui = useUiStore()
const route = useRoute()

const tabs = [
  { to: '/',          label: '总览' },
  { to: '/memory',    label: '记忆' },
  { to: '/cognition', label: '认知' },
  { to: '/status',    label: '状态' },
]

const isActive = (to: string) => {
  if (to === '/') return route.path === '/'
  return route.path.startsWith(to)
}
</script>

<template>
  <div class="shell">
    <header class="topbar">
      <div class="brand">echomem</div>
      <Button variant="primary" @click="ui.toggleQuickIngest(true)">
        <Icon name="plus" :size="14" /> Quick ingest
      </Button>
    </header>
    <nav class="tabs">
      <RouterLink v-for="t in tabs" :key="t.to" :to="t.to" custom v-slot="{ navigate }">
        <a :class="['tab', { active: isActive(t.to) }]" @click="navigate">{{ t.label }}</a>
      </RouterLink>
    </nav>
    <Banner v-if="ui.banner"
            :kind="ui.banner.kind" :text="ui.banner.text"
            :retry-label="ui.banner.retry ? '重试' : undefined"
            @retry="ui.banner?.retry?.()" />
    <main class="main"><slot /></main>
  </div>
</template>

<style scoped>
.shell { min-height: 100%; display: flex; flex-direction: column; }
.topbar {
  display: flex; align-items: center; justify-content: space-between;
  height: var(--top-bar-h);
  padding: 0 var(--space-lg);
  background: var(--c-bg); border-bottom: 1px solid var(--c-border);
}
.brand {
  font-family: var(--font-display);
  font-weight: var(--fw-semibold);
  font-size: var(--fs-lg);
  color: var(--c-primary);
}
.tabs {
  display: flex; gap: var(--space-xl);
  padding: 0 var(--space-lg);
  background: var(--c-bg);
  border-bottom: 1px solid var(--c-border);
}
.tab {
  display: inline-flex; align-items: center;
  height: var(--tabs-h);
  padding: 0 0;
  font-size: var(--fs-sm);
  color: var(--c-text-muted);
  border-bottom: 1.5px solid transparent;
  cursor: pointer;
  text-decoration: none;
}
.tab:hover { color: var(--c-text); }
.tab.active { color: var(--c-primary); border-bottom-color: var(--c-accent); font-weight: var(--fw-medium); }
.main { flex: 1; max-width: var(--content-max); width: 100%; margin: 0 auto; padding: var(--space-xl) var(--space-lg); }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add echomem/dashboard/src/components/AppShell.vue
git commit -m "feat(echomem-dashboard): AppShell (top bar + 4 tabs + slot)"
```

---

### Task 6.3: Polling in `App.vue`

**Files:**
- Modify: `echomem/dashboard/src/App.vue`

- [ ] **Step 1: Replace stub with full polling-aware shell**

```vue
<script setup lang="ts">
import { onMounted, onUnmounted, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { RouterView } from 'vue-router'
import AppShell from '@/components/AppShell.vue'
import LineageDrawer from '@/lineage/LineageDrawer.vue'
import QuickIngestDialog from '@/components/QuickIngestDialog.vue'
import { useStatusStore } from '@/stores/status'
import { useUiStore } from '@/stores/ui'

const POLL_BASE_MS = 10_000
const POLL_BURST_MS = 2_000

const status = useStatusStore()
const ui = useUiStore()
const { queueDepth, ollama } = storeToRefs(status)

let timer: number | null = null

async function tick() {
  if (document.hidden) { schedule(); return }
  try {
    await status.refresh()
    ui.clearBanner()
  } catch (e) {
    const err = e as { kind?: string; message?: string }
    if (err.kind === 'network' || err.kind === 'server') {
      ui.setBanner({
        kind: 'error',
        text: '无法连接 echomem daemon (127.0.0.1:8473)。请确认 echomem start 在跑。',
        retry: () => tick(),
      })
    }
  }
  schedule()
}

function schedule() {
  if (timer != null) clearTimeout(timer)
  const interval = queueDepth.value > 0 ? POLL_BURST_MS : POLL_BASE_MS
  timer = window.setTimeout(tick, interval)
}

function onVisibility() { if (!document.hidden) tick() }

watch(ollama, (o) => {
  if (o.status !== 'ok' && status.daemon) {
    ui.setBanner({
      kind: 'warning',
      text: 'AI worker 暂停——ollama 离线。已 ingest 的记忆会在恢复后自动消化。',
    })
  }
})

onMounted(() => {
  tick()
  document.addEventListener('visibilitychange', onVisibility)
})
onUnmounted(() => {
  if (timer) clearTimeout(timer)
  document.removeEventListener('visibilitychange', onVisibility)
})
</script>

<template>
  <AppShell>
    <RouterView />
  </AppShell>
  <LineageDrawer />
  <QuickIngestDialog />
</template>
```

- [ ] **Step 2: Commit**

```bash
git add echomem/dashboard/src/App.vue
git commit -m "feat(echomem-dashboard): App polling + banner orchestration"
```

---

### Task 6.4: `QuickIngestDialog.vue`

**Files:**
- Create: `echomem/dashboard/src/components/QuickIngestDialog.vue`
- Create: `echomem/dashboard/tests/unit/components/QuickIngestDialog.test.ts`

- [ ] **Step 1: Failing test**

```ts
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import { mount } from '@vue/test-utils'
import QuickIngestDialog from '@/components/QuickIngestDialog.vue'
import { useUiStore } from '@/stores/ui'

describe('QuickIngestDialog', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('hidden when ui.quickIngestOpen is false', () => {
    const w = mount(QuickIngestDialog)
    expect(w.find('.dialog').exists()).toBe(false)
  })

  it('visible when ui.quickIngestOpen is true', async () => {
    const ui = useUiStore()
    ui.toggleQuickIngest(true)
    const w = mount(QuickIngestDialog)
    expect(w.find('.dialog').exists()).toBe(true)
  })

  it('submits via ApiClient.post', async () => {
    const ui = useUiStore()
    ui.toggleQuickIngest(true)
    const post = vi.fn().mockResolvedValue({ id: 'x', agent_id: 'cli', created_at: 1 })
    const w = mount(QuickIngestDialog, {
      global: { provide: { apiClient: { post } } },
    })
    await w.find('textarea').setValue('hello')
    await w.find('form').trigger('submit')
    expect(post).toHaveBeenCalledWith('/memory/ingest', expect.objectContaining({ text: 'hello' }))
  })
})
```

- [ ] **Step 2: Implement**

```vue
<script setup lang="ts">
import { inject, ref } from 'vue'
import { useUiStore } from '@/stores/ui'
import { useStatusStore } from '@/stores/status'
import { api, type ApiClient } from '@/api/client'
import Button from './Button.vue'
import Icon from './Icon.vue'

const ui = useUiStore()
const status = useStatusStore()
const client = inject<ApiClient>('apiClient', api)

const text = ref('')
const agent = ref('cli')
const sourceKind = ref('explicit')
const submitting = ref(false)
const error = ref<string | null>(null)

async function submit() {
  if (!text.value.trim() || submitting.value) return
  submitting.value = true
  error.value = null
  try {
    await client.post('/memory/ingest', {
      text: text.value, agent_id: agent.value, source_kind: sourceKind.value,
    })
    text.value = ''
    ui.toggleQuickIngest(false)
    status.refresh().catch(() => undefined)
  } catch (e) {
    error.value = (e as { message?: string }).message ?? 'submit failed'
  } finally { submitting.value = false }
}
</script>

<template>
  <div v-if="ui.quickIngestOpen" class="overlay" @click.self="ui.toggleQuickIngest(false)">
    <form class="dialog" @submit.prevent="submit">
      <header class="head">
        <h3>+ Quick ingest</h3>
        <button type="button" class="close" @click="ui.toggleQuickIngest(false)" aria-label="close">
          <Icon name="close" />
        </button>
      </header>
      <div class="row">
        <label>agent
          <select v-model="agent">
            <option value="cli">cli</option>
            <option value="cc">cc</option>
            <option value="openclaw">openclaw</option>
            <option value="hermes">hermes</option>
          </select>
        </label>
        <label>source_kind
          <select v-model="sourceKind">
            <option value="explicit">explicit</option>
            <option value="session">session</option>
            <option value="document">document</option>
          </select>
        </label>
      </div>
      <textarea
        v-model="text" rows="6" autofocus
        placeholder="今天我决定..."
        @keydown.meta.enter.prevent="submit"
      />
      <p v-if="error" class="err">{{ error }}</p>
      <footer class="foot">
        <span class="hint">⌘+Enter 提交</span>
        <div class="actions">
          <Button @click="ui.toggleQuickIngest(false)">取消</Button>
          <Button variant="primary" :disabled="submitting || !text.trim()" @click="submit">Ingest</Button>
        </div>
      </footer>
    </form>
  </div>
</template>

<style scoped>
.overlay {
  position: fixed; inset: 0;
  background: rgba(44, 62, 80, 0.18);
  display: flex; align-items: center; justify-content: center;
  z-index: 60;
}
.dialog {
  background: var(--c-bg); border: 1px solid var(--c-border);
  border-radius: var(--radius-lg);
  width: min(560px, 90vw);
  display: flex; flex-direction: column;
  padding: var(--space-lg);
  gap: var(--space-md);
}
.head { display: flex; justify-content: space-between; align-items: center; }
.head h3 { font-size: var(--fs-h3); }
.close { background: transparent; border: none; cursor: pointer; color: var(--c-text-muted); }
.row { display: flex; gap: var(--space-lg); font-size: var(--fs-xs); color: var(--c-text-muted); }
.row label { display: flex; flex-direction: column; gap: 4px; }
.row select { font-family: var(--font-body); padding: 4px 8px; border: 1px solid var(--c-border); border-radius: var(--radius-sm); }
textarea {
  font-family: var(--font-mono); font-size: var(--fs-sm);
  padding: var(--space-md); border: 1px solid var(--c-border); border-radius: var(--radius-md);
  resize: vertical;
}
.err { color: var(--c-danger); font-size: var(--fs-xs); margin: 0; }
.foot { display: flex; justify-content: space-between; align-items: center; }
.hint { font-size: var(--fs-xs); color: var(--c-text-muted); }
.actions { display: flex; gap: var(--space-sm); }
</style>
```

- [ ] **Step 3: Pass + commit**

```bash
npx vitest run tests/unit/components/QuickIngestDialog.test.ts
git add echomem/dashboard/src/components/QuickIngestDialog.vue \
        echomem/dashboard/tests/unit/components/QuickIngestDialog.test.ts
git commit -m "feat(echomem-dashboard): QuickIngestDialog"
```

---

### Task 6.5: Verify dev server runs

- [ ] **Step 1: Start daemon**

```bash
cd /Users/jacky/code/lakeon/echomem
echomem start &
DAEMON_PID=$!
```

- [ ] **Step 2: Start dashboard dev**

```bash
cd dashboard
npm run dev &
DASH_PID=$!
sleep 3
curl -fsS http://localhost:5173/ | grep -q '<div id="app">' && echo OK
curl -fsS http://localhost:5173/health | grep -q '"status"'  # via proxy
```

- [ ] **Step 3: Tear down**

```bash
kill $DASH_PID $DAEMON_PID 2>/dev/null
```

- [ ] **Step 4: No commit needed (smoke).**

---

## Phase 7 — OverviewPage

### Task 7.1: `OverviewPage.vue`

**Files:**
- Create: `echomem/dashboard/src/pages/OverviewPage.vue`

- [ ] **Step 1: Implement**

```vue
<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { useStatusStore } from '@/stores/status'
import { useCognitionStore } from '@/stores/cognition'
import { useMemoryStore } from '@/stores/memory'
import { useUiStore } from '@/stores/ui'
import { useRouter } from 'vue-router'
import Tile from '@/components/Tile.vue'
import Card from '@/components/Card.vue'
import Tag from '@/components/Tag.vue'
import Button from '@/components/Button.vue'
import EmptyState from '@/components/EmptyState.vue'

const status = useStatusStore()
const cognition = useCognitionStore()
const memory = useMemoryStore()
const ui = useUiStore()
const router = useRouter()
const { counts } = storeToRefs(status)

onMounted(async () => {
  await Promise.allSettled([
    cognition.loadTimeline(),
    memory.loadInitial(),
  ])
})

const latest = computed(() => cognition.timeline[0] ?? null)

const recent = computed(() => {
  const events = cognition.timeline.slice(0, 5).map((e) => ({
    at: e.window_end, kind: 'cognition' as const, label: `timeline · "${e.title}"`, id: e.id,
  }))
  const mems = memory.items.slice(0, 5).map((m) => ({
    at: m.created_at, kind: 'memory' as const, label: `ingest · "${m.text.slice(0, 60)}"`, id: m.id,
  }))
  return [...events, ...mems].sort((a, b) => b.at - a.at).slice(0, 5)
})

function fmt(ts: number) {
  return new Date(ts).toLocaleString(undefined, {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  })
}

function openLineage(id: string) {
  router.push({ query: { lineage: id, kind: 'timeline' } })
}
</script>

<template>
  <div class="overview">
    <section class="tiles">
      <Tile :value="counts.memories"  label="记忆" />
      <Tile :value="counts.cognitions" label="认知" />
      <Tile :value="counts.entities"   label="实体" />
      <Tile :value="counts.skills"     label="Skills" />
    </section>

    <section class="stage">
      <template v-if="latest">
        <Card>
          <div class="label">最近一条认知 · TIMELINE · {{ fmt(latest.window_end) }}</div>
          <h2 class="title">{{ latest.title }}</h2>
          <p class="sum" v-if="latest.summary">{{ latest.summary }}</p>
          <p class="meta">由 {{ latest.member_memory_ids.length }} 条记忆合成</p>
          <div class="cta">
            <Button variant="primary" @click="openLineage(latest.id)">查看完整来源 →</Button>
          </div>
        </Card>
      </template>
      <template v-else-if="counts.memories === 0">
        <EmptyState
          title="echomem 是空的"
          body='试试看： echomem mem ingest "今天的笔记" --agent cli
或点右上角 + Quick ingest。'
        >
          <template #actions>
            <Button variant="primary" @click="ui.toggleQuickIngest(true)">+ Quick ingest</Button>
          </template>
        </EmptyState>
      </template>
      <template v-else>
        <EmptyState
          title="记忆已就位，AI worker 还在消化中"
          body="第一条认知通常在 ingest 后 30–90 秒出现。状态页可看队列进度。"
        />
      </template>
    </section>

    <section v-if="recent.length" class="recent">
      <h3 class="hd">最近活动</h3>
      <ul>
        <li v-for="r in recent" :key="r.kind + r.id">
          <span class="when">{{ fmt(r.at) }}</span>
          <Tag>{{ r.kind === 'cognition' ? '认知' : '记忆' }}</Tag>
          <span class="lbl">{{ r.label }}</span>
        </li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.overview { display: flex; flex-direction: column; gap: var(--space-2xl); }
.tiles { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--space-md); }
.stage { display: flex; flex-direction: column; }
.label { font-size: var(--fs-xs); color: var(--c-text-muted); letter-spacing: 0.5px; text-transform: uppercase; }
.title { font-size: var(--fs-h2); margin: var(--space-sm) 0; color: var(--c-primary); font-family: var(--font-display); }
.sum { color: var(--c-text); font-size: var(--fs-md); line-height: var(--lh-loose); margin: 0 0 var(--space-sm); }
.meta { color: var(--c-text-muted); font-size: var(--fs-sm); margin: 0 0 var(--space-md); }
.recent .hd { font-size: var(--fs-h3); margin-bottom: var(--space-md); }
.recent ul { list-style: none; padding: 0; margin: 0; }
.recent li { display: flex; gap: var(--space-md); align-items: center; padding: var(--space-sm) 0; border-bottom: 1px dashed var(--c-divider); font-size: var(--fs-sm); }
.recent li:last-child { border-bottom: none; }
.when { font-family: var(--font-mono); font-size: var(--fs-xs); color: var(--c-text-muted); width: 100px; }
.lbl { color: var(--c-text); }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add echomem/dashboard/src/pages/OverviewPage.vue
git commit -m "feat(echomem-dashboard): OverviewPage (tiles + main stage + recent)"
```

---

## Phase 8 — MemoryPage

### Task 8.1: `MemoryPage.vue`

**Files:**
- Create: `echomem/dashboard/src/pages/MemoryPage.vue`

- [ ] **Step 1: Implement**

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useMemoryStore } from '@/stores/memory'
import EmptyState from '@/components/EmptyState.vue'
import Button from '@/components/Button.vue'
import Tag from '@/components/Tag.vue'
import Drawer from '@/components/Drawer.vue'

const store = useMemoryStore()
const { items, hasMore, loading, filteredItems } = storeToRefs(store)

const sourceKindFilter = ref('')
const queryFilter = ref('')

onMounted(() => store.loadInitial())

let debounceTimer: number | null = null
function onQueryInput(e: Event) {
  queryFilter.value = (e.target as HTMLInputElement).value
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = window.setTimeout(() => { store.filters.query = queryFilter.value }, 200)
}
function applySourceKind(v: string) {
  sourceKindFilter.value = v
  store.filters.sourceKind = v
}

const detail = ref<typeof items.value[number] | null>(null)
function openDetail(m: typeof items.value[number]) { detail.value = m }

function fmtTime(ts: number) {
  return new Date(ts).toLocaleString(undefined, {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
  })
}
</script>

<template>
  <div class="memory">
    <header class="hd">
      <input class="search" type="search" placeholder="搜索文本..."
             :value="queryFilter" @input="onQueryInput" />
      <div class="kinds">
        <button :class="['chip', { active: sourceKindFilter === '' }]" @click="applySourceKind('')">全部</button>
        <button :class="['chip', { active: sourceKindFilter === 'explicit' }]" @click="applySourceKind('explicit')">explicit</button>
        <button :class="['chip', { active: sourceKindFilter === 'session' }]" @click="applySourceKind('session')">session</button>
        <button :class="['chip', { active: sourceKindFilter === 'document' }]" @click="applySourceKind('document')">document</button>
      </div>
    </header>

    <table v-if="filteredItems.length">
      <thead>
        <tr><th>时间</th><th>agent</th><th>kind</th><th>文本</th></tr>
      </thead>
      <tbody>
        <tr v-for="m in filteredItems" :key="m.id" @click="openDetail(m)">
          <td class="time">{{ fmtTime(m.created_at) }}</td>
          <td><Tag>{{ m.agent_id }}</Tag></td>
          <td>{{ m.source_kind }}</td>
          <td class="text">{{ m.text }}</td>
        </tr>
      </tbody>
    </table>

    <EmptyState v-else
      title="还没有记忆"
      body="用 CLI、HTTP 或 MCP 任一入口都可以。
curl 127.0.0.1:8473/memory/ingest -d '...'"
    />

    <div v-if="hasMore && filteredItems.length" class="load-more">
      <Button :disabled="loading" @click="store.loadMore()">{{ loading ? '加载中...' : 'Load more' }}</Button>
    </div>

    <Drawer :open="!!detail" :title="detail?.id" @close="detail = null">
      <div v-if="detail" class="detail">
        <div class="row"><span class="lbl">agent</span><span>{{ detail.agent_id }}</span></div>
        <div class="row"><span class="lbl">source_kind</span><span>{{ detail.source_kind }}</span></div>
        <div class="row" v-if="detail.source_ref"><span class="lbl">source_ref</span><span class="mono">{{ detail.source_ref }}</span></div>
        <div class="row"><span class="lbl">created</span><span>{{ fmtTime(detail.created_at) }}</span></div>
        <h4>文本</h4>
        <pre class="text-block">{{ detail.text }}</pre>
        <h4 v-if="detail.meta">meta</h4>
        <pre v-if="detail.meta" class="text-block">{{ JSON.stringify(detail.meta, null, 2) }}</pre>
      </div>
    </Drawer>
  </div>
</template>

<style scoped>
.memory { display: flex; flex-direction: column; gap: var(--space-md); }
.hd { display: flex; gap: var(--space-md); align-items: center; }
.search { padding: 6px 10px; border: 1px solid var(--c-border); border-radius: var(--radius-md); font-size: var(--fs-sm); width: 280px; }
.kinds { display: flex; gap: var(--space-xs); }
.chip {
  background: var(--c-bg); border: 1px solid var(--c-border); border-radius: 12px;
  padding: 2px var(--space-sm); font-size: var(--fs-xs); color: var(--c-text-muted); cursor: pointer;
}
.chip.active { background: var(--c-accent-light); color: var(--c-accent-text); border-color: var(--c-accent); }
table { width: 100%; border-collapse: collapse; font-size: 13px; }
th { text-align: left; padding: var(--space-sm) var(--table-cell-px); border-bottom: 1px solid var(--c-border); color: var(--c-text-muted); font-weight: var(--fw-medium); font-size: var(--fs-xs); text-transform: uppercase; letter-spacing: 0.5px; }
td { padding: 0 var(--table-cell-px); height: var(--table-row-h); border-bottom: 1px solid var(--c-divider); }
tbody tr { cursor: pointer; }
tbody tr:hover { background: var(--c-primary-soft); }
.time { font-family: var(--font-mono); color: var(--c-text-muted); white-space: nowrap; }
.text { color: var(--c-text); max-width: 600px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.load-more { text-align: center; padding: var(--space-md); }
.detail { padding: var(--space-lg); }
.detail .row { display: flex; gap: var(--space-md); padding: 4px 0; font-size: var(--fs-sm); }
.detail .lbl { width: 100px; color: var(--c-text-muted); font-size: var(--fs-xs); text-transform: uppercase; }
.text-block { background: var(--c-bg-alt); padding: var(--space-md); border-radius: var(--radius-sm); white-space: pre-wrap; font-family: var(--font-mono); font-size: var(--fs-xs); }
.mono { font-family: var(--font-mono); font-size: var(--fs-xs); }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add echomem/dashboard/src/pages/MemoryPage.vue
git commit -m "feat(echomem-dashboard): MemoryPage (table + filters + detail drawer)"
```

---

## Phase 9 — CognitionPage with 4 sub-views

### Task 9.1: `CognitionPage.vue` shell

**Files:**
- Create: `echomem/dashboard/src/pages/CognitionPage.vue`

- [ ] **Step 1: Implement**

```vue
<script setup lang="ts">
import { useRoute, RouterLink, RouterView } from 'vue-router'
import { computed } from 'vue'
const route = useRoute()
const subs = [
  { to: '/cognition/timeline', label: '时间流' },
  { to: '/cognition/summary',  label: '摘要' },
  { to: '/cognition/graph',    label: '关系' },
  { to: '/cognition/skill',    label: 'Skill' },
]
const isActive = (to: string) => computed(() => route.path === to).value
</script>

<template>
  <div class="cog">
    <nav class="subs">
      <RouterLink v-for="s in subs" :key="s.to" :to="s.to" custom v-slot="{ navigate }">
        <a :class="['sub', { active: isActive(s.to) }]" @click="navigate">{{ s.label }}</a>
      </RouterLink>
    </nav>
    <RouterView />
  </div>
</template>

<style scoped>
.cog { display: flex; flex-direction: column; gap: var(--space-lg); }
.subs { display: flex; gap: var(--space-lg); border-bottom: 1px solid var(--c-divider); }
.sub {
  padding: var(--space-sm) 0;
  font-size: var(--fs-sm);
  color: var(--c-text-muted);
  border-bottom: 1.5px solid transparent;
  cursor: pointer; text-decoration: none;
}
.sub:hover { color: var(--c-text); }
.sub.active { color: var(--c-primary); border-bottom-color: var(--c-accent); font-weight: var(--fw-medium); }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add echomem/dashboard/src/pages/CognitionPage.vue
git commit -m "feat(echomem-dashboard): CognitionPage shell with 4 sub-tabs"
```

---

### Task 9.2: `TimelineView.vue`

**Files:**
- Create: `echomem/dashboard/src/pages/cognition/TimelineView.vue`

- [ ] **Step 1: Implement**

```vue
<script setup lang="ts">
import { onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import { useCognitionStore } from '@/stores/cognition'
import EmptyState from '@/components/EmptyState.vue'
import Tag from '@/components/Tag.vue'

const cog = useCognitionStore()
const { timeline } = storeToRefs(cog)
const router = useRouter()

onMounted(() => cog.loadTimeline())

function fmt(ts: number) {
  return new Date(ts).toLocaleString(undefined, {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  })
}
function open(id: string) {
  router.push({ query: { ...router.currentRoute.value.query, lineage: id, kind: 'timeline' } })
}
</script>

<template>
  <div v-if="timeline.length" class="timeline">
    <article v-for="ev in timeline" :key="ev.id" class="row" @click="open(ev.id)">
      <time class="anchor">{{ fmt(ev.window_end) }}</time>
      <div class="card">
        <h3>{{ ev.title }}</h3>
        <p v-if="ev.summary" class="sum">{{ ev.summary }}</p>
        <div class="meta">
          <Tag>{{ ev.member_memory_ids.length }} 条记忆</Tag>
          <span v-if="ev.rationale" class="rat">{{ ev.rationale }}</span>
        </div>
      </div>
    </article>
  </div>
  <EmptyState v-else
    title="AI worker 待机中"
    body="当 agent 数据足够形成时间窗口时，对应的认知会自动出现。"
  />
</template>

<style scoped>
.timeline { display: flex; flex-direction: column; gap: var(--space-md); }
.row { display: grid; grid-template-columns: 120px 1fr; gap: var(--space-md); cursor: pointer; }
.anchor { font-family: var(--font-mono); font-size: var(--fs-xs); color: var(--c-text-muted); padding-top: var(--space-md); }
.card {
  background: var(--c-bg); border: 1px solid var(--c-border);
  border-radius: var(--radius-md); padding: var(--space-md) var(--space-lg);
}
.card:hover { border-color: var(--c-border-hover); }
.card h3 { font-size: var(--fs-lg); margin-bottom: 4px; }
.sum { color: var(--c-text); font-size: var(--fs-sm); margin: 4px 0 var(--space-sm); line-height: var(--lh-loose); }
.meta { display: flex; gap: var(--space-sm); align-items: center; }
.rat { font-size: var(--fs-xs); color: var(--c-text-muted); }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add echomem/dashboard/src/pages/cognition/TimelineView.vue
git commit -m "feat(echomem-dashboard): TimelineView (vertical timeline)"
```

---

### Task 9.3: `SummaryView.vue`

**Files:**
- Create: `echomem/dashboard/src/pages/cognition/SummaryView.vue`

- [ ] **Step 1: Implement**

This view groups summary nodes by `(source_kind, source_ref)`. It needs to discover what groups exist — there is no list endpoint yet. v1 strategy: derive groups from current `useMemoryStore.items` (`source_kind=document` + `source_ref` distinct values) and call `loadSummaryGroup` for each. If memory page hasn't loaded, load first.

```vue
<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import { useCognitionStore } from '@/stores/cognition'
import { useMemoryStore } from '@/stores/memory'
import EmptyState from '@/components/EmptyState.vue'
import Tag from '@/components/Tag.vue'

const cog = useCognitionStore()
const mem = useMemoryStore()
const router = useRouter()
const { summaryGroups } = storeToRefs(cog)

onMounted(async () => {
  if (!mem.items.length) await mem.loadInitial()
  const seen = new Set<string>()
  const targets: { kind: string; ref: string }[] = []
  for (const m of mem.items) {
    if (m.source_ref) {
      const key = `${m.source_kind}:${m.source_ref}`
      if (!seen.has(key)) {
        seen.add(key)
        targets.push({ kind: m.source_kind, ref: m.source_ref })
      }
    }
  }
  // Also include direct memory-rooted trees
  for (const m of mem.items) {
    const key = `memory:${m.id}`
    if (!seen.has(key)) {
      seen.add(key)
      targets.push({ kind: 'memory', ref: m.id })
    }
    if (targets.length >= 12) break
  }
  await Promise.all(targets.map((t) => cog.loadSummaryGroup(t.kind, t.ref).catch(() => undefined)))
})

const groups = computed(() => Object.entries(summaryGroups.value).filter(([, n]) => n.length))

function open(id: string) {
  router.push({ query: { ...router.currentRoute.value.query, lineage: id, kind: 'summary' } })
}
</script>

<template>
  <div v-if="groups.length" class="summary">
    <details v-for="[key, nodes] in groups" :key="key" open>
      <summary>
        <Tag tone="mono">{{ key }}</Tag>
        <span class="count">· {{ nodes.length }} 节点</span>
      </summary>
      <ul class="tree">
        <li v-for="n in nodes" :key="n.id" :style="{ paddingLeft: `${n.level * 16}px` }" @click="open(n.id)">
          <span class="lvl">L{{ n.level }}</span>
          <span class="text">{{ n.text }}</span>
          <Tag v-if="n.token_estimate">{{ n.token_estimate }} tok</Tag>
        </li>
      </ul>
    </details>
  </div>
  <EmptyState v-else
    title="AI worker 待机中"
    body="当摘要 worker 完成 L0/L1/L2 层级时，对应的认知会自动出现。"
  />
</template>

<style scoped>
.summary { display: flex; flex-direction: column; gap: var(--space-md); }
details { border: 1px solid var(--c-border); border-radius: var(--radius-md); padding: var(--space-md); background: var(--c-bg); }
summary { cursor: pointer; font-size: var(--fs-sm); display: flex; align-items: center; gap: var(--space-sm); }
.count { color: var(--c-text-muted); }
.tree { list-style: none; margin: var(--space-sm) 0 0; padding: 0; }
.tree li { display: flex; gap: var(--space-sm); align-items: flex-start; padding: 4px 0; font-size: var(--fs-sm); cursor: pointer; }
.tree li:hover { background: var(--c-primary-soft); }
.lvl { font-family: var(--font-mono); font-size: 10px; color: var(--c-text-muted); padding-top: 2px; min-width: 22px; }
.text { flex: 1; color: var(--c-text); }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add echomem/dashboard/src/pages/cognition/SummaryView.vue
git commit -m "feat(echomem-dashboard): SummaryView (grouped collapsible trees)"
```

---

### Task 9.4: `SkillView.vue`

**Files:**
- Create: `echomem/dashboard/src/pages/cognition/SkillView.vue`

- [ ] **Step 1: Implement**

The `/derivatives/skills` endpoint requires a `ctx` query string. v1 strategy: pass an empty/wildcard context and let the backend return its k closest skills regardless of context (or pass the latest memory text if available).

```vue
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import { useCognitionStore } from '@/stores/cognition'
import { useMemoryStore } from '@/stores/memory'
import EmptyState from '@/components/EmptyState.vue'
import Card from '@/components/Card.vue'
import Tag from '@/components/Tag.vue'

const cog = useCognitionStore()
const mem = useMemoryStore()
const { skills } = storeToRefs(cog)
const router = useRouter()
const ctx = ref('')

onMounted(async () => {
  if (!mem.items.length) await mem.loadInitial()
  ctx.value = mem.items[0]?.text ?? 'general'
  await cog.loadSkills(ctx.value)
})

const sorted = computed(() => [...skills.value].sort((a, b) => b.observed_count - a.observed_count))

function open(id: string) {
  router.push({ query: { ...router.currentRoute.value.query, lineage: id, kind: 'skill' } })
}
</script>

<template>
  <div v-if="sorted.length" class="skills">
    <Card v-for="s in sorted" :key="s.id" @click="open(s.id)" style="cursor: pointer">
      <h3>{{ s.name }}</h3>
      <div class="trig"><Tag tone="mono">{{ s.trigger_pattern }}</Tag></div>
      <ol class="steps">
        <li v-for="(step, i) in s.steps.slice(0, 5)" :key="i">{{ step }}</li>
      </ol>
      <div class="stats">
        <Tag>{{ s.observed_count }} 次观察</Tag>
        <Tag tone="accent">{{ s.success_count }} 次成功</Tag>
      </div>
    </Card>
  </div>
  <EmptyState v-else
    title="AI worker 待机中"
    body="当重复操作模式被识别时，Skill 会自动出现。"
  />
</template>

<style scoped>
.skills { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: var(--space-md); }
h3 { font-size: var(--fs-h3); margin-bottom: var(--space-sm); }
.trig { margin-bottom: var(--space-md); }
.steps { padding-left: var(--space-lg); margin: 0 0 var(--space-md); font-size: var(--fs-sm); color: var(--c-text); }
.steps li { padding: 2px 0; }
.stats { display: flex; gap: var(--space-sm); }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add echomem/dashboard/src/pages/cognition/SkillView.vue
git commit -m "feat(echomem-dashboard): SkillView (card grid)"
```

---

### Task 9.5: `GraphView.vue` with d3-force

**Files:**
- Create: `echomem/dashboard/src/pages/cognition/GraphView.vue`

- [ ] **Step 1: Implement**

```vue
<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import * as d3 from 'd3-force'
import { useCognitionStore } from '@/stores/cognition'
import EmptyState from '@/components/EmptyState.vue'

interface SimNode extends d3.SimulationNodeDatum { id: string; name: string; kind: string | null }
interface SimLink extends d3.SimulationLinkDatum<SimNode> { predicate: string; confidence: number }

const cog = useCognitionStore()
const router = useRouter()
const { graph, graphSeed, graphHops } = storeToRefs(cog)

const seedInput = ref(graphSeed.value)
const hopsInput = ref(graphHops.value)

const W = 800, H = 500

const simNodes = ref<SimNode[]>([])
const simLinks = ref<SimLink[]>([])
let simulation: d3.Simulation<SimNode, SimLink> | null = null

watch(() => graph.value, rebuild, { immediate: true })

function rebuild() {
  const nodeMap = new Map<string, SimNode>()
  for (const n of graph.value.nodes) nodeMap.set(n.id, { ...n })
  const links: SimLink[] = graph.value.edges.map((e) => ({
    source: nodeMap.get(e.subject_id) ?? e.subject_id,
    target: nodeMap.get(e.object_id) ?? e.object_id,
    predicate: e.predicate, confidence: e.confidence,
  }))
  simNodes.value = Array.from(nodeMap.values())
  simLinks.value = links

  if (simulation) simulation.stop()
  simulation = d3.forceSimulation(simNodes.value)
    .force('link', d3.forceLink<SimNode, SimLink>(simLinks.value).id((d) => d.id).distance(80))
    .force('charge', d3.forceManyBody().strength(-220))
    .force('center', d3.forceCenter(W / 2, H / 2))
    .force('collide', d3.forceCollide(20))
    .on('tick', () => {})
}

async function search() {
  cog.graphSeed = seedInput.value
  cog.graphHops = hopsInput.value
  await cog.loadGraph()
}

function radius(d: SimNode) {
  return 8 + Math.min(20, simLinks.value.filter((l) =>
    (l.source as SimNode).id === d.id || (l.target as SimNode).id === d.id
  ).length * 2)
}

function open(id: string) {
  router.push({ query: { ...router.currentRoute.value.query, lineage: id, kind: 'graph' } })
}

onMounted(() => { if (graphSeed.value) rebuild() })
onUnmounted(() => simulation?.stop())

const hasGraph = computed(() => simNodes.value.length > 0)
</script>

<template>
  <div class="graph">
    <header class="hd">
      <input v-model="seedInput" type="text" placeholder="seed (e.g. ent:jacky)" />
      <select v-model.number="hopsInput">
        <option :value="1">1 hop</option>
        <option :value="2">2 hops</option>
        <option :value="3">3 hops</option>
      </select>
      <button class="go" @click="search">查询</button>
    </header>
    <svg v-if="hasGraph" :viewBox="`0 0 ${W} ${H}`" class="canvas">
      <line v-for="(l, i) in simLinks" :key="i"
            :x1="(l.source as SimNode).x ?? 0" :y1="(l.source as SimNode).y ?? 0"
            :x2="(l.target as SimNode).x ?? 0" :y2="(l.target as SimNode).y ?? 0"
            stroke="var(--c-border)" stroke-width="1" />
      <g v-for="n in simNodes" :key="n.id" :transform="`translate(${n.x ?? 0},${n.y ?? 0})`"
         @click="open(n.id)" style="cursor: pointer">
        <circle :r="radius(n)" fill="var(--c-primary)" stroke="var(--c-bg)" stroke-width="2" />
        <text dy="-12" text-anchor="middle" font-size="11" fill="var(--c-text)">{{ n.name }}</text>
      </g>
    </svg>
    <EmptyState v-else
      title="输入种子节点开始查询"
      body="例如 ent:jacky · 节点会以力导向布局展开"
    />
  </div>
</template>

<style scoped>
.graph { display: flex; flex-direction: column; gap: var(--space-md); }
.hd { display: flex; gap: var(--space-sm); align-items: center; }
.hd input { padding: 6px 10px; border: 1px solid var(--c-border); border-radius: var(--radius-md); font-size: var(--fs-sm); width: 240px; font-family: var(--font-mono); }
.hd select { padding: 6px 8px; border: 1px solid var(--c-border); border-radius: var(--radius-md); font-size: var(--fs-sm); }
.go { background: var(--c-accent); color: white; border: none; padding: 6px 14px; border-radius: var(--radius-md); cursor: pointer; }
.canvas { width: 100%; height: 500px; background: var(--c-bg-canvas); border: 1px solid var(--c-border); border-radius: var(--radius-md); }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add echomem/dashboard/src/pages/cognition/GraphView.vue
git commit -m "feat(echomem-dashboard): GraphView (d3-force entity graph)"
```

---

## Phase 10 — LineageDrawer

### Task 10.1: `LineageDrawer.vue` + wire from query params

**Files:**
- Create: `echomem/dashboard/src/lineage/LineageDrawer.vue`

- [ ] **Step 1: Implement**

```vue
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useLineageStore } from '@/stores/lineage'
import type { CognitionSub } from '@/stores/cognition'
import Drawer from '@/components/Drawer.vue'
import ColumnList from '@/components/ColumnList.vue'

const route = useRoute()
const router = useRouter()
const store = useLineageStore()
const { current, loading } = storeToRefs(store)

const id = computed(() => (route.query.lineage as string) || '')
const kind = computed<CognitionSub | ''>(() => (route.query.kind as CognitionSub) || '')
const open = computed(() => !!id.value)

watch([id, kind], async ([nid, nkind]) => {
  if (nid && nkind) {
    try { await store.load(nid, nkind) } catch { /* error already in store */ }
  } else {
    store.clear()
  }
}, { immediate: true })

const activeMemId = ref<string | null>(null)
const activeBlob = ref<string | null>(null)

watch(current, (c) => {
  activeMemId.value = c?.memories[0]?.id ?? null
  activeBlob.value = c?.blobs[0] ?? null
})

const cogItems = computed(() => current.value
  ? [{ id: current.value.cognition.id, label: current.value.cognition.label,
       sub: current.value.cognition.kind }]
  : [])

const memItems = computed(() =>
  (current.value?.memories ?? []).map((m) => ({
    id: m.id, label: m.text.slice(0, 80), sub: m.agent_id,
  }))
)

const blobItems = computed(() =>
  (current.value?.blobs ?? []).map((b) => ({ id: b, label: b.replace('sha256:', '').slice(0, 12) + '…', sub: 'blob' }))
)

function close() {
  const q = { ...route.query }
  delete q.lineage; delete q.kind
  router.replace({ path: route.path, query: q })
}
</script>

<template>
  <Drawer :open="open" :title="`来源 · ${current?.cognition.label ?? ''}`" :width-vw="70" @close="close">
    <div v-if="loading" class="loading">加载中...</div>
    <div v-else-if="current" class="cols">
      <ColumnList title="认知" :items="cogItems" :active-id="current.cognition.id" />
      <ColumnList title="记忆" :items="memItems" :active-id="activeMemId" @select="activeMemId = $event" />
      <ColumnList title="来源 blob / URL" :items="blobItems" :active-id="activeBlob" @select="activeBlob = $event" />
    </div>
  </Drawer>
</template>

<style scoped>
.loading { padding: var(--space-2xl); text-align: center; color: var(--c-text-muted); }
.cols { display: grid; grid-template-columns: 1fr 1fr 1fr; height: 100%; }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add echomem/dashboard/src/lineage/LineageDrawer.vue
git commit -m "feat(echomem-dashboard): LineageDrawer (3 cascading columns, query-param driven)"
```

---

## Phase 11 — StatusPage

### Task 11.1: `StatusPage.vue`

**Files:**
- Create: `echomem/dashboard/src/pages/StatusPage.vue`

- [ ] **Step 1: Implement**

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useStatusStore } from '@/stores/status'
import Card from '@/components/Card.vue'
import Tag from '@/components/Tag.vue'

const status = useStatusStore()
const { daemon, ollama, workers, deadLetter } = storeToRefs(status)

onMounted(() => status.refresh())

function fmtBytes(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}
function fmtTs(ts: number | null): string {
  if (!ts) return '—'
  return new Date(ts).toLocaleString()
}
const expanded = ref<Record<string, boolean>>({})
function toggle(id: string) { expanded.value[id] = !expanded.value[id] }
</script>

<template>
  <div class="status">
    <Card class="section">
      <h3>服务健康</h3>
      <div class="kv-row">
        <div class="kv"><span class="lbl">daemon</span><Tag tone="accent">{{ daemon?.status ?? '—' }}</Tag></div>
        <div class="kv"><span class="lbl">version</span><span class="mono">{{ daemon?.version ?? '—' }}</span></div>
        <div class="kv"><span class="lbl">data dir</span><span class="mono">{{ daemon?.data_dir ?? '—' }}</span></div>
        <div class="kv"><span class="lbl">db.sqlite</span><span>{{ daemon ? fmtBytes(daemon.db_size_bytes) : '—' }}</span></div>
      </div>
      <hr />
      <div class="kv-row">
        <div class="kv"><span class="lbl">ollama</span>
          <Tag :tone="ollama.status === 'ok' ? 'accent' : 'default'">{{ ollama.status }}</Tag>
        </div>
        <div class="kv"><span class="lbl">latency</span><span>{{ ollama.latency_ms ?? '—' }} ms</span></div>
        <div class="kv"><span class="lbl">generate</span><span class="mono">{{ ollama.generate_model || '—' }}</span></div>
        <div class="kv"><span class="lbl">embedding</span><span class="mono">{{ ollama.embedding_model || '—' }}</span></div>
        <div class="kv"><span class="lbl">dim</span><span>{{ ollama.embedding_dim }}</span></div>
      </div>
    </Card>

    <Card class="section">
      <h3>衍生物 pipeline</h3>
      <table class="workers">
        <thead><tr><th>worker</th><th>queue</th><th>processed</th><th>last_run</th><th>throttle</th></tr></thead>
        <tbody>
          <tr v-for="(w, kind) in workers" :key="kind">
            <td class="mono">{{ kind }}</td>
            <td>{{ w.queue_depth }}</td>
            <td>{{ w.processed_total }}</td>
            <td class="mono">{{ fmtTs(w.last_run_at) }}</td>
            <td>{{ w.throttle ?? '—' }}</td>
          </tr>
        </tbody>
      </table>
    </Card>

    <Card class="section">
      <h3>Dead letter</h3>
      <table v-if="deadLetter.length" class="dl">
        <thead><tr><th>at</th><th>worker</th><th>mem_id</th><th>error</th></tr></thead>
        <tbody>
          <template v-for="(d, i) in deadLetter" :key="i">
            <tr @click="toggle(String(i))">
              <td class="mono">{{ fmtTs(d.at) }}</td>
              <td>{{ d.worker }}</td>
              <td class="mono">{{ d.mem_id ?? '—' }}</td>
              <td class="err-summary">{{ (d.traceback ?? '').split('\n')[0].slice(0, 80) }}</td>
            </tr>
            <tr v-if="expanded[String(i)]" class="trace-row">
              <td colspan="4"><pre class="trace">{{ d.traceback }}</pre></td>
            </tr>
          </template>
        </tbody>
      </table>
      <p v-else class="empty">管道空闲，没有失败任务。</p>
    </Card>
  </div>
</template>

<style scoped>
.status { display: flex; flex-direction: column; gap: var(--space-lg); }
.section h3 { margin-bottom: var(--space-md); }
.kv-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: var(--space-md); }
.kv { display: flex; flex-direction: column; gap: 2px; }
.kv .lbl { font-size: var(--fs-xs); color: var(--c-text-muted); text-transform: uppercase; letter-spacing: 0.5px; }
.kv .mono { font-family: var(--font-mono); font-size: var(--fs-xs); }
hr { border: none; border-top: 1px solid var(--c-divider); margin: var(--space-md) 0; }
table { width: 100%; border-collapse: collapse; font-size: var(--fs-sm); }
th { text-align: left; padding: var(--space-sm); border-bottom: 1px solid var(--c-border); color: var(--c-text-muted); font-weight: var(--fw-medium); font-size: var(--fs-xs); text-transform: uppercase; }
td { padding: var(--space-sm); border-bottom: 1px solid var(--c-divider); }
.mono { font-family: var(--font-mono); font-size: var(--fs-xs); }
.err-summary { color: var(--c-danger); }
.trace-row td { background: var(--c-bg-alt); }
.trace { white-space: pre-wrap; font-family: var(--font-mono); font-size: var(--fs-xs); margin: 0; }
.dl tbody tr { cursor: pointer; }
.empty { color: var(--c-text-muted); font-size: var(--fs-sm); padding: var(--space-md) 0; }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add echomem/dashboard/src/pages/StatusPage.vue
git commit -m "feat(echomem-dashboard): StatusPage (diagnostic surface, no actions)"
```

---

## Phase 12 — Build & packaging

### Task 12.1: `scripts/build_dashboard.sh`

**Files:**
- Create: `echomem/scripts/build_dashboard.sh`

- [ ] **Step 1: Write script**

```bash
#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE/.."

cd dashboard
npm ci
npm run build

DIST_TARGET="../src/echomem/_dashboard_dist"
rm -rf "$DIST_TARGET"
mkdir -p "$DIST_TARGET"
cp -R dist/. "$DIST_TARGET/"
git rev-parse --short HEAD > "$DIST_TARGET/_BUILD_INFO" 2>/dev/null || echo "unknown" > "$DIST_TARGET/_BUILD_INFO"

echo "Dashboard built into $DIST_TARGET"
```

- [ ] **Step 2: Make executable + smoke**

```bash
chmod +x echomem/scripts/build_dashboard.sh
bash echomem/scripts/build_dashboard.sh
ls echomem/src/echomem/_dashboard_dist/index.html       # exists
```

- [ ] **Step 3: Commit**

```bash
git add echomem/scripts/build_dashboard.sh
git commit -m "build(echomem-dashboard): build script copies dist -> _dashboard_dist"
```

---

### Task 12.2: `pyproject.toml` force-include

**Files:**
- Modify: `echomem/pyproject.toml`

- [ ] **Step 1: Replace the `[tool.hatch.build.targets.wheel]` section**

```toml
[tool.hatch.build.targets.wheel]
packages = ["src/echomem"]

[tool.hatch.build.targets.wheel.force-include]
"src/echomem/_dashboard_dist" = "echomem/_dashboard_dist"
```

- [ ] **Step 2: Verify build**

```bash
cd /Users/jacky/code/lakeon/echomem
python -m build --wheel
unzip -l dist/echomem-*.whl | grep _dashboard_dist/index.html  # should print line
```

- [ ] **Step 3: Commit**

```bash
git add echomem/pyproject.toml
git commit -m "build(echomem): force-include dashboard dist into wheel"
```

---

### Task 12.3: Daemon mounts `/dashboard`

**Files:**
- Modify: `echomem/src/echomem/daemon/app.py`
- Create: `echomem/tests/integration/test_dashboard_mount.py`

- [ ] **Step 1: Failing test**

```python
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from echomem.daemon.app import create_app
from echomem.config import EchomemConfig


@pytest.fixture
def client_with_dashboard(tmp_path, monkeypatch):
    # Synthesize a fake _dashboard_dist directory inside the installed package
    import echomem
    pkg_dir = Path(echomem.__file__).parent
    dist_dir = pkg_dir / "_dashboard_dist"
    dist_dir.mkdir(exist_ok=True)
    (dist_dir / "index.html").write_text("<html><body>echomem dashboard</body></html>")

    cfg = EchomemConfig(
        data_dir=tmp_path,
        ollama_url="http://127.0.0.1:1",
        embedding_model="m", generate_model="g", embedding_dim=4,
    )
    app = create_app(cfg)
    with TestClient(app) as c:
        yield c

    (dist_dir / "index.html").unlink()
    dist_dir.rmdir()


def test_dashboard_root_returns_index(client_with_dashboard):
    resp = client_with_dashboard.get("/dashboard/")
    assert resp.status_code == 200
    assert "echomem dashboard" in resp.text
```

- [ ] **Step 2: Verify failure**

```bash
python -m pytest tests/integration/test_dashboard_mount.py -v
```

- [ ] **Step 3: Modify `daemon/app.py`**

Inside `create_app`, after `app.include_router(context_router)`:

```python
from importlib.resources import files
from fastapi.staticfiles import StaticFiles

dist_path = files("echomem").joinpath("_dashboard_dist")
try:
    dist_dir = str(dist_path)
    if dist_path.is_dir():
        app.mount(
            "/dashboard",
            StaticFiles(directory=dist_dir, html=True),
            name="dashboard",
        )
except (FileNotFoundError, NotADirectoryError):
    # No dashboard bundle (e.g. pip install --no-binary or local dev) — skip mount.
    pass
```

- [ ] **Step 4: Verify pass**

```bash
python -m pytest tests/integration/test_dashboard_mount.py -v
python -m pytest -q
```

- [ ] **Step 5: Commit**

```bash
git add echomem/src/echomem/daemon/app.py echomem/tests/integration/test_dashboard_mount.py
git commit -m "feat(echomem-daemon): mount /dashboard from packaged _dashboard_dist"
```

---

## Phase 13 — Playwright E2E

### Task 13.1: Playwright config

**Files:**
- Create: `echomem/dashboard/playwright.config.ts`
- Create: `echomem/dashboard/tests/e2e/fixtures.ts`

- [ ] **Step 1: `playwright.config.ts`**

```ts
import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './tests/e2e',
  timeout: 180_000,
  expect: { timeout: 10_000 },
  use: { baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:5173' },
  projects: [
    { name: 'chromium', use: { browserName: 'chromium' } },
  ],
  reporter: [['list']],
})
```

- [ ] **Step 2: `fixtures.ts` — daemon + dev server lifecycle**

```ts
import { test as base, expect } from '@playwright/test'
import { spawn, ChildProcess } from 'node:child_process'
import { mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

interface Fixtures {
  daemonReady: void
  devReady: void
}

async function waitFor(url: string, ms = 30_000) {
  const t0 = Date.now()
  while (Date.now() - t0 < ms) {
    try {
      const r = await fetch(url)
      if (r.ok) return
    } catch { /* ignore */ }
    await new Promise((r) => setTimeout(r, 250))
  }
  throw new Error(`timeout waiting for ${url}`)
}

export const test = base.extend<Fixtures>({
  daemonReady: [async ({}, use) => {
    const dir = mkdtempSync(join(tmpdir(), 'echomem-e2e-'))
    const proc: ChildProcess = spawn(
      'echomem', ['start', '--data-dir', dir],
      { env: { ...process.env, ECHOMEM_DATA_DIR: dir }, stdio: 'pipe' }
    )
    await waitFor('http://127.0.0.1:8473/health')
    await use()
    proc.kill('SIGTERM')
    await new Promise((r) => proc.once('exit', r))
  }, { auto: true }],
})

export { expect }
```

- [ ] **Step 3: Commit**

```bash
git add echomem/dashboard/playwright.config.ts echomem/dashboard/tests/e2e/fixtures.ts
git commit -m "test(echomem-dashboard): playwright config + daemon lifecycle fixture"
```

---

### Task 13.2: E2E `01-empty-to-first-cognition`

**Files:**
- Create: `echomem/dashboard/tests/e2e/01-empty-to-first-cognition.spec.ts`

- [ ] **Step 1: Write spec**

```ts
import { test, expect } from './fixtures'

test('empty → ingest → first cognition appears with lineage', async ({ page }) => {
  test.skip(!process.env.ECHOMEM_E2E_REQUIRE_OLLAMA,
            'requires real ollama; gated by ECHOMEM_E2E_REQUIRE_OLLAMA=1')

  await page.goto('/')
  await expect(page.getByText('echomem 是空的')).toBeVisible()

  await page.getByRole('button', { name: /\+ Quick ingest/ }).click()
  await page.locator('textarea').fill('今天我决定用 Hub-and-Spoke 布局')
  await page.getByRole('button', { name: 'Ingest' }).click()

  // Within 90s memory count goes 0 → 1
  await expect.poll(async () => {
    return await page.locator('[data-testid="tile-mem"], .tile').first().textContent()
  }, { timeout: 90_000 }).not.toContain('0')

  // Within 120s a timeline event surfaces on overview
  await expect(page.locator('h2').first()).toBeVisible({ timeout: 120_000 })

  // Click "查看完整来源" → drawer
  await page.getByRole('button', { name: /查看完整来源/ }).click()
  await expect(page.locator('aside.drawer')).toBeVisible()
  await expect(page.locator('aside.drawer .col')).toHaveCount(3)
})
```

- [ ] **Step 2: Commit**

```bash
git add echomem/dashboard/tests/e2e/01-empty-to-first-cognition.spec.ts
git commit -m "test(echomem-dashboard): E2E 01 empty → first cognition (real Ollama)"
```

---

### Task 13.3: E2E `02-derivative-views-render`

**Files:**
- Create: `echomem/dashboard/tests/e2e/fixtures-data.ts`
- Create: `echomem/dashboard/tests/e2e/02-derivative-views-render.spec.ts`

- [ ] **Step 1: Fixture loader using HTTP API (no Ollama needed)**

```ts
// fixtures-data.ts
const BASE = 'http://127.0.0.1:8473'

export async function seedFixture() {
  for (let i = 0; i < 8; i++) {
    await fetch(BASE + '/memory/ingest', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        text: `seed memory #${i} about jacky and echomem dashboard work`,
        agent_id: 'cli', source_kind: 'explicit',
      }),
    }).then((r) => { if (!r.ok) throw new Error('seed failed') })
  }
  // wait briefly for embedder; do NOT depend on summarizer (needs ollama)
  await new Promise((r) => setTimeout(r, 5_000))
}
```

- [ ] **Step 2: Write spec — only assert non-empty rendering for views that work without Ollama**

```ts
// 02-derivative-views-render.spec.ts
import { test, expect } from './fixtures'
import { seedFixture } from './fixtures-data'

test.beforeEach(async () => { await seedFixture() })

test('memory page shows seeded rows', async ({ page }) => {
  await page.goto('/#/memory')
  await expect(page.locator('table tbody tr')).toHaveCount(8, { timeout: 10_000 })
})

test('cognition shells render their empty-state when worker idle', async ({ page }) => {
  await page.goto('/#/cognition/timeline')
  // Either rows appear (Ollama present) OR the empty-state copy is visible.
  const empty = page.getByText('AI worker 待机中')
  const cards = page.locator('.timeline .row')
  await expect(empty.or(cards.first())).toBeVisible({ timeout: 10_000 })
})

test('graph view shows the empty-state when no seed', async ({ page }) => {
  await page.goto('/#/cognition/graph')
  await expect(page.getByText('输入种子节点开始查询')).toBeVisible()
})
```

- [ ] **Step 3: Commit**

```bash
git add echomem/dashboard/tests/e2e/fixtures-data.ts \
        echomem/dashboard/tests/e2e/02-derivative-views-render.spec.ts
git commit -m "test(echomem-dashboard): E2E 02 view rendering with seeded memories"
```

---

### Task 13.4: E2E `03-error-recovery`

**Files:**
- Create: `echomem/dashboard/tests/e2e/03-error-recovery.spec.ts`

- [ ] **Step 1: Write spec — overrides daemon fixture so it does NOT auto-start**

```ts
import { test as base, expect } from '@playwright/test'

// Standalone test (does not import the daemon-auto-start fixture).
const test = base

test('shows banner when daemon offline, recovers when banner retry succeeds', async ({ page }) => {
  // Assume daemon is NOT running. Visit dashboard.
  await page.goto('/')
  await expect(page.locator('.banner.error')).toBeVisible({ timeout: 30_000 })
  await expect(page.getByText(/无法连接 echomem daemon/)).toBeVisible()

  // Manually start daemon now
  const { spawn } = await import('node:child_process')
  const { mkdtempSync } = await import('node:fs')
  const { tmpdir } = await import('node:os')
  const { join } = await import('node:path')
  const dir = mkdtempSync(join(tmpdir(), 'echomem-e2e-recover-'))
  const proc = spawn('echomem', ['start', '--data-dir', dir], { stdio: 'pipe' })
  // wait for daemon
  await new Promise((r) => setTimeout(r, 4_000))

  await page.getByRole('button', { name: /重试/ }).click()
  await expect(page.locator('.banner.error')).toBeHidden({ timeout: 15_000 })

  proc.kill('SIGTERM')
})
```

- [ ] **Step 2: Commit**

```bash
git add echomem/dashboard/tests/e2e/03-error-recovery.spec.ts
git commit -m "test(echomem-dashboard): E2E 03 error banner + retry recovery"
```

---

### Task 13.5: GitHub Actions workflow

**Files:**
- Create: `echomem/.github/workflows/dashboard-ci.yml`

- [ ] **Step 1: Write workflow**

```yaml
name: dashboard-ci
on:
  push: { branches: [ main ] }
  pull_request: { branches: [ main ] }

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with: { node-version: '20', cache: 'npm', cache-dependency-path: dashboard/package-lock.json }
      - uses: actions/setup-python@v5
        with: { python-version: '3.11' }

      - name: Install python deps
        run: pip install -e ".[dev]" build

      - name: Run python tests
        run: pytest -q

      - name: Install dashboard deps
        run: cd dashboard && npm ci

      - name: Run dashboard unit tests
        run: cd dashboard && npm test

      - name: Build dashboard
        run: bash scripts/build_dashboard.sh

      - name: Build wheel
        run: python -m build --wheel

      - name: Verify wheel includes dashboard
        run: |
          unzip -l dist/echomem-*.whl | grep _dashboard_dist/index.html

      - name: Cache ollama models
        uses: actions/cache@v4
        with:
          path: ~/.ollama
          key: ollama-gemma2-2b-v1

      - name: Pull ollama (background)
        run: |
          curl -fsSL https://ollama.com/install.sh | sh
          ollama serve &
          sleep 4
          ollama pull gemma2:2b

      - name: Install playwright browsers
        run: cd dashboard && npx playwright install --with-deps chromium

      - name: Run dashboard E2E (real Ollama)
        env: { ECHOMEM_E2E_REQUIRE_OLLAMA: '1' }
        run: cd dashboard && npm run dev & sleep 4 && npm run test:e2e:full
```

- [ ] **Step 2: Commit**

```bash
git add echomem/.github/workflows/dashboard-ci.yml
git commit -m "ci(echomem): dashboard build + wheel verify + real-Ollama E2E"
```

---

## Phase 14 — Documentation

### Task 14.1: README dashboard section

**Files:**
- Modify: `echomem/README.md`

- [ ] **Step 1: Append section**

```markdown
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
```

- [ ] **Step 2: Commit**

```bash
git add echomem/README.md
git commit -m "docs(echomem): README dashboard dev/build/test instructions"
```

---

### Task 14.2: Mark Plan 4 done in roadmap

**Files:**
- Modify: `echomem/README.md` (the existing "Plan 4: Vue 3 Dashboard SPA" line)

- [ ] **Step 1: Adjust roadmap line if present**

```bash
grep -n "Plan 4: Vue 3 Dashboard SPA" echomem/README.md
```

If the line exists, change `Plan 4:` to `Plan 4 (done):` and add a link to the spec:

```markdown
- Plan 4 (done): Vue 3 Dashboard SPA — see `docs/superpowers/specs/2026-05-06-echomem-dashboard-spa-design.md`
```

- [ ] **Step 2: Commit**

```bash
git add echomem/README.md
git commit -m "docs(echomem): mark Plan 4 dashboard as done in roadmap"
```

---

## Self-review notes

This plan covers every spec section:

- §1 audience → reflected in Overview main stage emphasis (Task 7.1) and crafted empty states (Tasks 5.4, 7.1, 9.x)
- §2 architecture → Tasks 2.1–2.5 (scaffold), 12.1–12.3 (build/mount)
- §3 IA → Tasks 6.1 (router), 6.2 (AppShell)
- §4 stores + polling → Tasks 4.1–4.5, 6.3
- §5 five pages → Tasks 7.1, 8.1, 9.1–9.5, 11.1
- §5.5 lineage drawer → Task 10.1
- §6 visual system → Task 2.4
- §7 errors / empty / loading / quick ingest → Tasks 5.4, 6.4, plus banner wiring in 6.3
- §8 auth → no task; spec decision is "no token", nothing to implement
- §9 testing → embedded TDD in every relevant task + Playwright in 13.x
- §10 backend `/health/diagnostic` → Tasks 1.1–1.5
- §11 build & release → Tasks 12.1–12.3, 13.5
- §12 out of scope → no tasks (nothing to do)
- §13 risks → mitigated in 12.2 (wheel verify step), 13.5 (cache ollama models)

Type/method names verified consistent: `useStatusStore.refresh`, `useMemoryStore.loadInitial/loadMore`, `useCognitionStore.loadTimeline/loadGraph/loadSummaryGroup/loadSkills`, `useLineageStore.load(id, kind)`, `ApiClient.get/post/delete`, all match across stores, components, and tests.

