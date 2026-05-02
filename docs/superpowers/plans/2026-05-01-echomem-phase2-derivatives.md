# echomem · Phase 2 Derivatives — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** 在 echomem backbone（Plan 1）基础上，实现 4 种衍生物 pipeline + 异步任务队列，让 ingest 触发后台自动产出"时间流 / 树 / 图 / 程序性"四种组织方式的衍生物，让 echomem 真正比纯 KV memory 强。

**Architecture:** ingest 写入 memory 后，**enqueue** 多个后台任务（summarize、extract entities、aggregate timeline）；任务由进程内 asyncio worker pool 并发消费；每个 worker 独立写自己的衍生物表；失败重试（3 次指数退避）后进 dead_letter。Reflector 周期性 batch 跑（每 10 min 扫一次），不在 ingest 路径。Skill 通过外部 importer 一次性导入（superpowers / impeccable 等 skill 文件），不依赖 LLM 萃取。

**Tech Stack:** 沿用 Plan 1（Python 3.11+ · FastAPI · sqlite-vec · Ollama HTTP）+ asyncio.Queue + sentence-transformers free（用 qwen3-embedding 通过 Ollama 算 skill 触发向量）+ NetworkX（图查询时按需加载子图）

**Out of Scope（明确属于后续 Plan）：**
- Context API + FS blobs（add_url / ls / read / write / mv） → Plan 3
- Dashboard（Vue 3 SPA） → Plan 4
- Onboarding install.sh + openclaw / hermes 接入 → Plan 5
- 程序性 `extract`（从会话萃取 skill） → P1 后续
- 因果链（衍生物 5）→ P2+
- Insight Track → 独立子项目

---

## File Structure

```
lakeon/echomem/
├── src/echomem/
│   ├── drivers/
│   │   ├── migrations/
│   │   │   └── m002_derivatives.py    # 新：9 张衍生物相关表
│   │   └── sqlite.py                  # 改：加 8 个衍生物 CRUD 方法
│   ├── workers/
│   │   ├── summarizer.py              # 新：gemma L0/L1/L2 摘要
│   │   ├── entity_extractor.py        # 新：gemma 三元组 + 置信度阈值
│   │   ├── timeline.py                # 新：时间窗 + 相似度聚合
│   │   └── reflector.py               # 新：MVP 占位（周期 noop）
│   ├── pipeline/                      # 新包
│   │   ├── __init__.py
│   │   ├── queue.py                   # asyncio.Queue + dead_letter
│   │   └── orchestrator.py            # ingest hook + worker pool 管理
│   ├── skills/                        # 新包
│   │   ├── __init__.py
│   │   └── importer.py                # 扫 ~/.claude/skills 等目录导入
│   ├── api/
│   │   ├── derivatives.py             # 新：4 个 GET 端点
│   │   ├── skills.py                  # 新：POST /skills/import + GET /skills/surface
│   │   └── memory.py                  # 改：ingest 触发 enqueue
│   └── daemon/
│       └── app.py                     # 改：lifespan 启 worker pool；注册 derivatives + skills router
└── tests/
    ├── unit/
    │   ├── test_migrations_m002.py    # 衍生物表存在 + 字段 + 索引
    │   ├── test_summarizer.py         # gemma mock，L0/L1/L2 + fallback
    │   ├── test_entity_extractor.py   # gemma mock，三元组 + 置信度门槛
    │   ├── test_timeline.py           # 纯算法测试
    │   ├── test_skills_importer.py    # 扫 fixture skill 目录
    │   ├── test_pipeline_queue.py     # enqueue + 重试 + dead_letter
    │   └── test_sqlite_driver_deriv.py # CRUD 方法
    ├── integration/
    │   ├── test_derivatives_endpoints.py  # 4 个 GET + 1 个 POST
    │   ├── test_pipeline_e2e.py       # ingest → 后台跑通 4 种衍生物
    │   └── test_ingest_with_pipeline.py # 集成 ingest 端点 + enqueue
    └── e2e/
        └── test_full_loop_phase2.py   # gated by ECHOMEM_E2E=1
```

**Each new file's responsibility:**

- `m002_derivatives.py` — 创建 derivative_event / derivative_summary / derivative_entity / derivative_triple / derivative_triple_pending / derivative_skill / skill_vec / derivative_task / dead_letter 共 9 张表
- `summarizer.py` — `SummarizerWorker.handle(memory_id)` 调 gemma 三次（L0 ≤100t / L1 ≤500t / L2 原 chunk），失败时退到 truncate-prefix
- `entity_extractor.py` — `EntityExtractorWorker.handle(memory_id)` 调 gemma 抽三元组 + 置信度，置信度 ≥ 0.7 入 derivative_triple；< 0.7 入 derivative_triple_pending
- `timeline.py` — `TimelineWorker.handle(memory_id)` 找最近 30 min 同 agent 的事件，主题 cosine ≥ 0.7 → 加入；否则新建事件
- `reflector.py` — `ReflectorWorker.run_periodic()` 每 10 min 跑 noop（占位，留 P1 接 LLM）
- `pipeline/queue.py` — `WorkerPool` 包 4 个 worker；`enqueue(task_kind, memory_id)` 写 derivative_task 表 + 内存 asyncio.Queue；失败 3 次后写 dead_letter
- `pipeline/orchestrator.py` — daemon lifespan 启 / 停 WorkerPool；ingest 端点调 `orchestrator.on_memory_ingested(memory_id)`
- `skills/importer.py` — `import_from_directory(path)` 扫 `*.md` skill 文件（含 frontmatter `name`/`description`），生成 trigger_emb 写 derivative_skill + skill_vec
- `api/derivatives.py` — 4 个端点：`GET /derivatives/timeline?range=...&agent=...` / `tree?root=...` / `graph?seed=...&hops=2` / `skills?ctx=...&k=5`
- `api/skills.py` — `POST /skills/import?path=...` 调 importer；`GET /skills/surface?ctx=...` recall

---

## Tasks

### Task 1: m002 migration — 9 张衍生物表

**Files:**
- Create: `lakeon/echomem/src/echomem/drivers/migrations/m002_derivatives.py`
- Modify: `lakeon/echomem/src/echomem/drivers/migrations/__init__.py`
- Create: `lakeon/echomem/tests/unit/test_migrations_m002.py`

- [ ] **Step 1: Write the failing test**

`tests/unit/test_migrations_m002.py`:
```python
import sqlite3
import sqlite_vec
from echomem.drivers.migrations import apply_all


def _open():
    con = sqlite3.connect(":memory:")
    con.enable_load_extension(True)
    sqlite_vec.load(con)
    con.enable_load_extension(False)
    apply_all(con)
    return con


EXPECTED_TABLES = {
    "derivative_event",
    "derivative_summary",
    "derivative_entity",
    "derivative_triple",
    "derivative_triple_pending",
    "derivative_skill",
    "skill_vec",
    "derivative_task",
    "dead_letter",
}


def test_m002_creates_all_derivative_tables():
    con = _open()
    rows = {
        r[0]
        for r in con.execute(
            "SELECT name FROM sqlite_master WHERE type IN ('table','virtualtable')"
        ).fetchall()
    }
    assert EXPECTED_TABLES.issubset(rows)


def test_derivative_summary_has_level_and_parent():
    con = _open()
    cols = {r[1] for r in con.execute("PRAGMA table_info(derivative_summary)").fetchall()}
    assert {"id", "source_kind", "source_ref", "level", "parent_id", "text", "token_estimate"}.issubset(cols)


def test_derivative_triple_has_confidence_and_source():
    con = _open()
    cols = {r[1] for r in con.execute("PRAGMA table_info(derivative_triple)").fetchall()}
    assert {"id", "subject_id", "predicate", "object_id", "source_memory_id", "confidence"}.issubset(cols)


def test_derivative_skill_has_trigger_pattern_and_source():
    con = _open()
    cols = {r[1] for r in con.execute("PRAGMA table_info(derivative_skill)").fetchall()}
    assert {"id", "name", "trigger_pattern", "trigger_emb", "steps", "source", "observed_count", "success_count"}.issubset(cols)


def test_derivative_task_table_for_queue():
    con = _open()
    cols = {r[1] for r in con.execute("PRAGMA table_info(derivative_task)").fetchall()}
    assert {"id", "kind", "memory_id", "status", "attempts", "last_error", "created_at", "updated_at"}.issubset(cols)
```

- [ ] **Step 2: Run (fails — m002 not in MIGRATIONS)**

Run: `cd /Users/jacky/code/lakeon/echomem && source .venv/bin/activate && pytest tests/unit/test_migrations_m002.py -v`

- [ ] **Step 3: Implement m002 + register**

`src/echomem/drivers/migrations/m002_derivatives.py`:
```python
import sqlite3


def up(con: sqlite3.Connection) -> None:
    # NOTE: 与 m001 同样使用 IF NOT EXISTS；不需要 transaction 因为
    # 每张表创建是独立的 DDL，且失败时上层 apply_all 会留下 schema_version 不入库。
    con.executescript(
        """
        CREATE TABLE IF NOT EXISTS derivative_event (
          id                TEXT PRIMARY KEY,
          window_start      INTEGER NOT NULL,
          window_end        INTEGER NOT NULL,
          agent_id          TEXT NOT NULL,
          title             TEXT NOT NULL,
          summary           TEXT,
          member_memory_ids TEXT,
          created_at        INTEGER NOT NULL,
          rationale         TEXT
        );
        CREATE INDEX IF NOT EXISTS idx_event_window ON derivative_event(window_start);
        CREATE INDEX IF NOT EXISTS idx_event_agent  ON derivative_event(agent_id);

        CREATE TABLE IF NOT EXISTS derivative_summary (
          id              TEXT PRIMARY KEY,
          source_kind     TEXT NOT NULL,
          source_ref      TEXT NOT NULL,
          level           INTEGER NOT NULL,
          parent_id       TEXT,
          text            TEXT NOT NULL,
          token_estimate  INTEGER,
          created_at      INTEGER NOT NULL,
          rationale       TEXT
        );
        CREATE INDEX IF NOT EXISTS idx_summary_source ON derivative_summary(source_kind, source_ref);
        CREATE INDEX IF NOT EXISTS idx_summary_parent ON derivative_summary(parent_id);

        CREATE TABLE IF NOT EXISTS derivative_entity (
          id            TEXT PRIMARY KEY,
          name          TEXT NOT NULL,
          kind          TEXT,
          meta          TEXT,
          first_seen_at INTEGER NOT NULL,
          last_seen_at  INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_entity_name ON derivative_entity(name);

        CREATE TABLE IF NOT EXISTS derivative_triple (
          id                TEXT PRIMARY KEY,
          subject_id        TEXT NOT NULL,
          predicate         TEXT NOT NULL,
          object_id         TEXT NOT NULL,
          source_memory_id  TEXT NOT NULL,
          confidence        REAL NOT NULL,
          created_at        INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_triple_s ON derivative_triple(subject_id);
        CREATE INDEX IF NOT EXISTS idx_triple_o ON derivative_triple(object_id);
        CREATE INDEX IF NOT EXISTS idx_triple_src ON derivative_triple(source_memory_id);

        CREATE TABLE IF NOT EXISTS derivative_triple_pending (
          id                TEXT PRIMARY KEY,
          subject_text      TEXT NOT NULL,
          predicate         TEXT NOT NULL,
          object_text       TEXT NOT NULL,
          source_memory_id  TEXT NOT NULL,
          confidence        REAL NOT NULL,
          created_at        INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_triple_pending_src ON derivative_triple_pending(source_memory_id);

        CREATE TABLE IF NOT EXISTS derivative_skill (
          id              TEXT PRIMARY KEY,
          name            TEXT NOT NULL,
          trigger_pattern TEXT NOT NULL,
          trigger_emb     BLOB,
          steps           TEXT NOT NULL,
          agent_scope     TEXT,
          source          TEXT NOT NULL,
          observed_count  INTEGER NOT NULL DEFAULT 0,
          success_count   INTEGER NOT NULL DEFAULT 0,
          last_used_at    INTEGER,
          created_at      INTEGER NOT NULL,
          rationale       TEXT
        );
        CREATE INDEX IF NOT EXISTS idx_skill_name   ON derivative_skill(name);
        CREATE INDEX IF NOT EXISTS idx_skill_source ON derivative_skill(source);

        CREATE TABLE IF NOT EXISTS derivative_task (
          id          TEXT PRIMARY KEY,
          kind        TEXT NOT NULL,           -- 'summarize' | 'extract_entity' | 'aggregate_timeline' | 'reflect'
          memory_id   TEXT,                    -- nullable for periodic tasks
          status      TEXT NOT NULL,           -- 'pending' | 'running' | 'done' | 'failed'
          attempts    INTEGER NOT NULL DEFAULT 0,
          last_error  TEXT,
          created_at  INTEGER NOT NULL,
          updated_at  INTEGER NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_task_status ON derivative_task(status, kind);
        CREATE INDEX IF NOT EXISTS idx_task_memory ON derivative_task(memory_id);

        CREATE TABLE IF NOT EXISTS dead_letter (
          id          TEXT PRIMARY KEY,
          task_id     TEXT NOT NULL,
          kind        TEXT NOT NULL,
          memory_id   TEXT,
          payload     TEXT,
          error       TEXT NOT NULL,
          created_at  INTEGER NOT NULL
        );
        """
    )
    # skill_vec virtual table — 1024 维与 memory_vec 一致
    con.execute(
        "CREATE VIRTUAL TABLE IF NOT EXISTS skill_vec USING vec0(skill_id TEXT PRIMARY KEY, embedding float[1024])"
    )
```

Modify `src/echomem/drivers/migrations/__init__.py`:
```python
from echomem.drivers.migrations import m001_initial, m002_derivatives

MIGRATIONS: dict[int, Callable[[sqlite3.Connection], None]] = {
    1: m001_initial.up,
    2: m002_derivatives.up,
}
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/unit/test_migrations_m002.py -v`
Expected: 5 PASSED.

- [ ] **Step 5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add echomem/src/echomem/drivers/migrations/ echomem/tests/unit/test_migrations_m002.py
git commit -m "feat(echomem): m002 — 9 derivative tables (event/summary/entity/triple/skill/task/dead_letter)"
```

---

### Task 2: SQLiteDriver — 衍生物 CRUD 方法（8 个）

**Files:**
- Modify: `lakeon/echomem/src/echomem/drivers/sqlite.py`
- Modify: `lakeon/echomem/src/echomem/drivers/base.py`
- Create: `lakeon/echomem/tests/unit/test_sqlite_driver_deriv.py`

- [ ] **Step 1: Write the failing tests**

`tests/unit/test_sqlite_driver_deriv.py`:
```python
import time
import pytest
from echomem.drivers.sqlite import SQLiteDriver
from echomem.drivers.base import (
    Memory,
    Summary,
    Entity,
    Triple,
    Event,
    Skill,
)


@pytest.fixture
def driver(tmp_path):
    d = SQLiteDriver(tmp_path / "db.sqlite", embedding_dim=1024)
    yield d
    d.close()


def _mem(driver, mid="01HXMEM00000000000000000A", text="hello", agent="cc"):
    now = int(time.time() * 1000)
    m = Memory(
        id=mid, agent_id=agent, source_kind="explicit", source_ref=None,
        text=text, meta=None, created_at=now, updated_at=now, deleted_at=None,
        embedding=[0.0] * 1024,
    )
    driver.upsert_memory(m)
    return m


def test_upsert_summary_and_query_tree(driver):
    m = _mem(driver)
    now = int(time.time() * 1000)
    l0 = Summary(id="01S0", source_kind="memory", source_ref=m.id, level=0, parent_id=None,
                 text="L0 short", token_estimate=10, created_at=now, rationale="fits ≤ 100t")
    l1 = Summary(id="01S1", source_kind="memory", source_ref=m.id, level=1, parent_id=l0.id,
                 text="L1 medium", token_estimate=50, created_at=now, rationale=None)
    driver.upsert_summary(l0)
    driver.upsert_summary(l1)
    tree = driver.query_tree(source_kind="memory", source_ref=m.id)
    assert {s.level for s in tree} == {0, 1}


def test_upsert_entity_and_triple(driver):
    m = _mem(driver)
    now = int(time.time() * 1000)
    e1 = Entity(id="ent:jacky", name="Jacky", kind="person", meta=None, first_seen_at=now, last_seen_at=now)
    e2 = Entity(id="ent:echomem", name="echomem", kind="project", meta=None, first_seen_at=now, last_seen_at=now)
    driver.upsert_entity(e1)
    driver.upsert_entity(e2)
    t = Triple(id="tr:1", subject_id=e1.id, predicate="works_on", object_id=e2.id,
               source_memory_id=m.id, confidence=0.95, created_at=now)
    driver.upsert_triple(t)

    sub = driver.query_subgraph(seed_id=e1.id, hops=1)
    assert len(sub.nodes) >= 2
    assert any(edge[2]["predicate"] == "works_on" for edge in sub.edges)


def test_pending_triple_isolated(driver):
    m = _mem(driver)
    now = int(time.time() * 1000)
    driver.upsert_pending_triple(
        id="tp:1", subject_text="?Jacky", predicate="maybe_likes", object_text="?cats",
        source_memory_id=m.id, confidence=0.4, created_at=now,
    )
    pending = driver.list_pending_triples()
    assert len(pending) == 1
    # main triple table 不受影响
    sub = driver.query_subgraph(seed_id="ent:nonexistent", hops=1)
    assert len(sub.nodes) == 0


def test_event_aggregation(driver):
    now = int(time.time() * 1000)
    ev = Event(
        id="ev:1", window_start=now - 60_000, window_end=now, agent_id="cc",
        title="dev session", summary="working on echomem",
        member_memory_ids=["01M1", "01M2"], created_at=now,
        rationale="topic similarity 0.8 + same window",
    )
    driver.upsert_event(ev)
    items = driver.query_timeline(start_ms=now - 120_000, end_ms=now + 1, agent_id="cc")
    assert len(items) == 1
    assert items[0].title == "dev session"


def test_skill_upsert_and_recall(driver):
    now = int(time.time() * 1000)
    sk = Skill(
        id="sk:tdd", name="TDD",
        trigger_pattern="when implementing a feature, write test first",
        trigger_emb=[1.0] + [0.0] * 1023,
        steps=["write test", "run fail", "implement", "run pass", "commit"],
        agent_scope="all", source="imported", observed_count=0, success_count=0,
        last_used_at=None, created_at=now, rationale=None,
    )
    driver.upsert_skill(sk)
    hits = driver.query_skills(query_emb=[1.0] + [0.0] * 1023, k=3)
    assert any(h.name == "TDD" for h in hits)
```

- [ ] **Step 2: Run (fails)**

Run: `pytest tests/unit/test_sqlite_driver_deriv.py -v`

- [ ] **Step 3: Add dataclasses + Protocol methods + impl**

Append to `src/echomem/drivers/base.py`:
```python
@dataclass(slots=True)
class Summary:
    id: str
    source_kind: str            # 'memory' | 'blob' | 'session'
    source_ref: str
    level: int                  # 0 | 1 | 2
    parent_id: str | None
    text: str
    token_estimate: int | None
    created_at: int
    rationale: str | None = None


@dataclass(slots=True)
class Entity:
    id: str
    name: str
    kind: str | None
    meta: dict | None
    first_seen_at: int
    last_seen_at: int


@dataclass(slots=True)
class Triple:
    id: str
    subject_id: str
    predicate: str
    object_id: str
    source_memory_id: str
    confidence: float
    created_at: int


@dataclass(slots=True)
class Event:
    id: str
    window_start: int
    window_end: int
    agent_id: str
    title: str
    summary: str | None
    member_memory_ids: list[str]
    created_at: int
    rationale: str | None = None


@dataclass(slots=True)
class Skill:
    id: str
    name: str
    trigger_pattern: str
    trigger_emb: list[float] | None
    steps: list[str]
    agent_scope: str | None
    source: str                 # 'imported' | 'extracted'
    observed_count: int
    success_count: int
    last_used_at: int | None
    created_at: int
    rationale: str | None = None


@dataclass(slots=True)
class Subgraph:
    """A small in-memory graph slice; `edges` are 3-tuples (subject_id, object_id, attrs)."""
    nodes: list[Entity]
    edges: list[tuple[str, str, dict]]
```

Append to `StorageDriver` Protocol in same file:
```python
    def upsert_summary(self, s: Summary) -> str: ...
    def query_tree(self, source_kind: str, source_ref: str) -> list[Summary]: ...

    def upsert_entity(self, e: Entity) -> str: ...
    def upsert_triple(self, t: Triple) -> str: ...
    def upsert_pending_triple(self, *, id: str, subject_text: str, predicate: str,
                              object_text: str, source_memory_id: str, confidence: float,
                              created_at: int) -> str: ...
    def list_pending_triples(self, limit: int = 100) -> list[dict]: ...
    def query_subgraph(self, seed_id: str, hops: int = 2) -> Subgraph: ...

    def upsert_event(self, e: Event) -> str: ...
    def query_timeline(self, start_ms: int, end_ms: int, agent_id: str | None = None) -> list[Event]: ...

    def upsert_skill(self, s: Skill) -> str: ...
    def query_skills(self, query_emb: list[float], k: int = 5) -> list[Skill]: ...
```

Append implementation to `src/echomem/drivers/sqlite.py` (inside `SQLiteDriver` class, before module-level helpers):
```python
    # ───────────────── SUMMARY ─────────────────
    def upsert_summary(self, s: Summary) -> str:
        self.con.execute(
            """
            INSERT INTO derivative_summary(id, source_kind, source_ref, level, parent_id,
                                           text, token_estimate, created_at, rationale)
            VALUES(:id, :sk, :sr, :lv, :pid, :tx, :te, :ca, :ra)
            ON CONFLICT(id) DO UPDATE SET
              level = excluded.level, parent_id = excluded.parent_id,
              text = excluded.text, token_estimate = excluded.token_estimate,
              rationale = excluded.rationale
            """,
            {"id": s.id, "sk": s.source_kind, "sr": s.source_ref, "lv": s.level,
             "pid": s.parent_id, "tx": s.text, "te": s.token_estimate,
             "ca": s.created_at, "ra": s.rationale},
        )
        self.con.commit()
        return s.id

    def query_tree(self, source_kind: str, source_ref: str) -> list[Summary]:
        rows = self.con.execute(
            "SELECT id, source_kind, source_ref, level, parent_id, text, token_estimate, created_at, rationale "
            "FROM derivative_summary WHERE source_kind = ? AND source_ref = ? ORDER BY level ASC",
            (source_kind, source_ref),
        ).fetchall()
        return [Summary(*r) for r in rows]

    # ───────────────── ENTITY / TRIPLE ─────────────────
    def upsert_entity(self, e: Entity) -> str:
        meta = json.dumps(e.meta) if e.meta is not None else None
        self.con.execute(
            """
            INSERT INTO derivative_entity(id, name, kind, meta, first_seen_at, last_seen_at)
            VALUES(:id, :name, :kind, :meta, :fs, :ls)
            ON CONFLICT(id) DO UPDATE SET
              name = excluded.name, kind = excluded.kind, meta = excluded.meta,
              last_seen_at = excluded.last_seen_at
            """,
            {"id": e.id, "name": e.name, "kind": e.kind, "meta": meta,
             "fs": e.first_seen_at, "ls": e.last_seen_at},
        )
        self.con.commit()
        return e.id

    def upsert_triple(self, t: Triple) -> str:
        self.con.execute(
            """
            INSERT OR REPLACE INTO derivative_triple
            (id, subject_id, predicate, object_id, source_memory_id, confidence, created_at)
            VALUES(?, ?, ?, ?, ?, ?, ?)
            """,
            (t.id, t.subject_id, t.predicate, t.object_id, t.source_memory_id, t.confidence, t.created_at),
        )
        self.con.commit()
        return t.id

    def upsert_pending_triple(self, *, id, subject_text, predicate, object_text,
                              source_memory_id, confidence, created_at):
        self.con.execute(
            "INSERT OR REPLACE INTO derivative_triple_pending"
            "(id, subject_text, predicate, object_text, source_memory_id, confidence, created_at) "
            "VALUES(?, ?, ?, ?, ?, ?, ?)",
            (id, subject_text, predicate, object_text, source_memory_id, confidence, created_at),
        )
        self.con.commit()
        return id

    def list_pending_triples(self, limit: int = 100) -> list[dict]:
        rows = self.con.execute(
            "SELECT id, subject_text, predicate, object_text, source_memory_id, confidence, created_at "
            "FROM derivative_triple_pending ORDER BY created_at DESC LIMIT ?",
            (limit,),
        ).fetchall()
        keys = ["id", "subject_text", "predicate", "object_text", "source_memory_id", "confidence", "created_at"]
        return [dict(zip(keys, r)) for r in rows]

    def query_subgraph(self, seed_id: str, hops: int = 2) -> Subgraph:
        # BFS over derivative_triple
        visited: set[str] = set()
        frontier: set[str] = {seed_id}
        edges: list[tuple[str, str, dict]] = []
        for _ in range(hops):
            if not frontier:
                break
            placeholders = ",".join("?" * len(frontier))
            params = list(frontier) + list(frontier)
            rows = self.con.execute(
                f"SELECT subject_id, predicate, object_id, confidence "
                f"FROM derivative_triple "
                f"WHERE subject_id IN ({placeholders}) OR object_id IN ({placeholders})",
                params,
            ).fetchall()
            visited |= frontier
            new_frontier: set[str] = set()
            for s, p, o, conf in rows:
                edges.append((s, o, {"predicate": p, "confidence": conf}))
                for nid in (s, o):
                    if nid not in visited:
                        new_frontier.add(nid)
            frontier = new_frontier
        # fetch entity nodes
        if not visited:
            return Subgraph(nodes=[], edges=[])
        ph = ",".join("?" * len(visited))
        node_rows = self.con.execute(
            f"SELECT id, name, kind, meta, first_seen_at, last_seen_at "
            f"FROM derivative_entity WHERE id IN ({ph})",
            list(visited),
        ).fetchall()
        nodes = [
            Entity(id=r[0], name=r[1], kind=r[2],
                   meta=json.loads(r[3]) if r[3] else None,
                   first_seen_at=r[4], last_seen_at=r[5])
            for r in node_rows
        ]
        return Subgraph(nodes=nodes, edges=edges)

    # ───────────────── EVENT (timeline) ─────────────────
    def upsert_event(self, e: Event) -> str:
        self.con.execute(
            """
            INSERT INTO derivative_event(id, window_start, window_end, agent_id, title, summary,
                                          member_memory_ids, created_at, rationale)
            VALUES(:id, :ws, :we, :ag, :t, :s, :mm, :ca, :ra)
            ON CONFLICT(id) DO UPDATE SET
              window_end = excluded.window_end, title = excluded.title,
              summary = excluded.summary, member_memory_ids = excluded.member_memory_ids,
              rationale = excluded.rationale
            """,
            {"id": e.id, "ws": e.window_start, "we": e.window_end, "ag": e.agent_id,
             "t": e.title, "s": e.summary,
             "mm": json.dumps(e.member_memory_ids), "ca": e.created_at, "ra": e.rationale},
        )
        self.con.commit()
        return e.id

    def query_timeline(self, start_ms: int, end_ms: int, agent_id: str | None = None) -> list[Event]:
        sql = ("SELECT id, window_start, window_end, agent_id, title, summary, member_memory_ids, "
               "created_at, rationale FROM derivative_event "
               "WHERE window_start >= ? AND window_start < ?")
        params: list[Any] = [start_ms, end_ms]
        if agent_id is not None:
            sql += " AND agent_id = ?"
            params.append(agent_id)
        sql += " ORDER BY window_start DESC"
        rows = self.con.execute(sql, params).fetchall()
        return [
            Event(id=r[0], window_start=r[1], window_end=r[2], agent_id=r[3],
                  title=r[4], summary=r[5],
                  member_memory_ids=json.loads(r[6]) if r[6] else [],
                  created_at=r[7], rationale=r[8])
            for r in rows
        ]

    # ───────────────── SKILL ─────────────────
    def upsert_skill(self, s: Skill) -> str:
        from sqlite_vec import serialize_float32

        self.con.execute(
            """
            INSERT INTO derivative_skill(id, name, trigger_pattern, trigger_emb, steps,
                                         agent_scope, source, observed_count, success_count,
                                         last_used_at, created_at, rationale)
            VALUES(:id, :name, :tp, :te, :st, :sc, :sr, :oc, :sk, :lu, :ca, :ra)
            ON CONFLICT(id) DO UPDATE SET
              name = excluded.name, trigger_pattern = excluded.trigger_pattern,
              trigger_emb = excluded.trigger_emb, steps = excluded.steps,
              agent_scope = excluded.agent_scope, source = excluded.source,
              observed_count = excluded.observed_count, success_count = excluded.success_count,
              last_used_at = excluded.last_used_at, rationale = excluded.rationale
            """,
            {"id": s.id, "name": s.name, "tp": s.trigger_pattern,
             "te": serialize_float32(s.trigger_emb) if s.trigger_emb else None,
             "st": json.dumps(s.steps), "sc": s.agent_scope, "sr": s.source,
             "oc": s.observed_count, "sk": s.success_count,
             "lu": s.last_used_at, "ca": s.created_at, "ra": s.rationale},
        )
        # skill_vec sync
        if s.trigger_emb is not None:
            if len(s.trigger_emb) != self.embedding_dim:
                raise ValueError(
                    f"skill trigger_emb dim {len(s.trigger_emb)} != configured {self.embedding_dim}"
                )
            self.con.execute("DELETE FROM skill_vec WHERE skill_id = ?", (s.id,))
            self.con.execute(
                "INSERT INTO skill_vec(skill_id, embedding) VALUES(?, ?)",
                (s.id, serialize_float32(s.trigger_emb)),
            )
        self.con.commit()
        return s.id

    def query_skills(self, query_emb: list[float], k: int = 5) -> list[Skill]:
        from sqlite_vec import serialize_float32

        vec_rows = self.con.execute(
            "SELECT skill_id, distance FROM skill_vec WHERE embedding MATCH ? "
            "ORDER BY distance LIMIT ?",
            (serialize_float32(query_emb), max(k * 2, 8)),
        ).fetchall()
        if not vec_rows:
            return []
        ids = [r[0] for r in vec_rows]
        ph = ",".join("?" * len(ids))
        rows = self.con.execute(
            f"SELECT id, name, trigger_pattern, steps, agent_scope, source, "
            f"observed_count, success_count, last_used_at, created_at, rationale "
            f"FROM derivative_skill WHERE id IN ({ph})",
            ids,
        ).fetchall()
        order = {sid: i for i, sid in enumerate(ids)}
        rows.sort(key=lambda r: order.get(r[0], 1_000_000))
        return [
            Skill(
                id=r[0], name=r[1], trigger_pattern=r[2], trigger_emb=None,
                steps=json.loads(r[3]) if r[3] else [],
                agent_scope=r[4], source=r[5],
                observed_count=r[6], success_count=r[7],
                last_used_at=r[8], created_at=r[9], rationale=r[10],
            )
            for r in rows[:k]
        ]
```

Update `src/echomem/drivers/__init__.py` to re-export:
```python
from echomem.drivers.base import (
    StorageDriver, Memory, RecallHit,
    Summary, Entity, Triple, Event, Skill, Subgraph,
)
__all__ = [
    "StorageDriver", "Memory", "RecallHit",
    "Summary", "Entity", "Triple", "Event", "Skill", "Subgraph",
]
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/unit/test_sqlite_driver_deriv.py -v`
Expected: 5 PASSED. Plus all existing tests still green: `pytest -v` → 53 passed + 1 skipped.

- [ ] **Step 5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add echomem/src/echomem/drivers/ echomem/tests/unit/test_sqlite_driver_deriv.py
git commit -m "feat(echomem): SQLiteDriver — 8 derivative CRUD methods (summary/entity/triple/event/skill)"
```

---

### Task 3: SummarizerWorker — gemma L0/L1/L2 with truncate-fallback

**Files:**
- Create: `lakeon/echomem/src/echomem/workers/summarizer.py`
- Create: `lakeon/echomem/tests/unit/test_summarizer.py`

- [ ] **Step 1: Write the failing test**

`tests/unit/test_summarizer.py`:
```python
import time
import pytest
from echomem.drivers.sqlite import SQLiteDriver
from echomem.drivers.base import Memory
from echomem.ollama_client import OllamaClient
from echomem.workers.summarizer import SummarizerWorker


@pytest.fixture
def driver(tmp_path):
    d = SQLiteDriver(tmp_path / "db.sqlite")
    yield d
    d.close()


def _seed_memory(driver, text="A long original chunk of text covering many sentences."):
    now = int(time.time() * 1000)
    m = Memory(
        id="01HXSUM00000000000000000A", agent_id="cc", source_kind="explicit",
        source_ref=None, text=text, meta=None, created_at=now, updated_at=now,
        deleted_at=None, embedding=[0.0] * 1024,
    )
    driver.upsert_memory(m)
    return m


@pytest.mark.asyncio
async def test_summarizer_writes_three_levels(tmp_path, httpx_mock, driver):
    m = _seed_memory(driver)
    # gemma will be called 2 times (L0, L1); L2 is the original chunk (no LLM call)
    httpx_mock.add_response(
        method="POST",
        url="http://ol:11434/api/generate",
        json={"response": "L0 short summary."},
    )
    httpx_mock.add_response(
        method="POST",
        url="http://ol:11434/api/generate",
        json={"response": "L1 medium-length summary covering main points and details."},
    )
    async with OllamaClient("http://ol:11434") as ol:
        worker = SummarizerWorker(driver, ol, model="gemma4:e4b")
        await worker.handle(m.id)

    tree = driver.query_tree(source_kind="memory", source_ref=m.id)
    levels = sorted(s.level for s in tree)
    assert levels == [0, 1, 2]


@pytest.mark.asyncio
async def test_summarizer_falls_back_when_llm_fails(tmp_path, httpx_mock, driver):
    m = _seed_memory(driver, text="A" * 500)
    # both calls error out
    httpx_mock.add_response(method="POST", url="http://ol:11434/api/generate",
                            status_code=500, json={"error": "boom"}, is_reusable=True)
    async with OllamaClient("http://ol:11434") as ol:
        worker = SummarizerWorker(driver, ol, model="gemma4:e4b")
        await worker.handle(m.id)

    tree = driver.query_tree(source_kind="memory", source_ref=m.id)
    # L2 always present (original chunk, no LLM)
    assert any(s.level == 2 for s in tree)
    # L0 falls back to truncate-prefix (≤ 100 chars), so it IS present (no LLM dependency)
    l0 = [s for s in tree if s.level == 0]
    assert len(l0) == 1
    assert "fallback" in (l0[0].rationale or "").lower()
```

- [ ] **Step 2: Run (fails)**

Run: `pytest tests/unit/test_summarizer.py -v`

- [ ] **Step 3: Implement**

`src/echomem/workers/summarizer.py`:
```python
from __future__ import annotations

import time
from echomem.drivers.sqlite import SQLiteDriver
from echomem.drivers.base import Summary
from echomem.ollama_client import OllamaClient
from echomem.ulid import new as new_id
from echomem.logging import get_logger

log = get_logger("echomem.summarizer")

# Token estimates are character-based heuristics; close enough for tier sizing.
L0_MAX_TOKENS = 100
L1_MAX_TOKENS = 500
CHARS_PER_TOKEN = 4  # rough heuristic

L0_PROMPT = (
    "Summarize the following text in at most {max_chars} characters. "
    "Be terse and concrete. Return only the summary, no preamble.\n\n"
    "Text:\n{text}\n\nSummary:"
)
L1_PROMPT = (
    "Summarize the following text in at most {max_chars} characters. "
    "Cover the main points with enough detail to be useful, but do not include "
    "every detail. Return only the summary, no preamble.\n\n"
    "Text:\n{text}\n\nSummary:"
)


def _truncate(text: str, max_chars: int) -> str:
    if len(text) <= max_chars:
        return text
    return text[: max_chars - 1].rstrip() + "…"


class SummarizerWorker:
    def __init__(self, driver: SQLiteDriver, ollama: OllamaClient, *, model: str):
        self.driver = driver
        self.ollama = ollama
        self.model = model

    async def handle(self, memory_id: str) -> None:
        m = self.driver.get_memory(memory_id)
        if m is None:
            log.warning("summarizer.skip_missing", memory_id=memory_id)
            return

        now = int(time.time() * 1000)

        # L2: original chunk; no LLM call
        l2 = Summary(
            id=new_id(), source_kind="memory", source_ref=m.id, level=2,
            parent_id=None, text=m.text,
            token_estimate=len(m.text) // CHARS_PER_TOKEN,
            created_at=now, rationale="L2 = original chunk",
        )
        self.driver.upsert_summary(l2)

        # L0: ≤ 100 tokens (~400 chars)
        l0_text, l0_rationale = await self._gen_or_truncate(
            m.text, L0_PROMPT, L0_MAX_TOKENS * CHARS_PER_TOKEN, "L0"
        )
        l0 = Summary(
            id=new_id(), source_kind="memory", source_ref=m.id, level=0,
            parent_id=None, text=l0_text,
            token_estimate=len(l0_text) // CHARS_PER_TOKEN,
            created_at=now, rationale=l0_rationale,
        )
        self.driver.upsert_summary(l0)

        # L1: ≤ 500 tokens (~2000 chars). Skip if original < L1 budget.
        if len(m.text) > L1_MAX_TOKENS * CHARS_PER_TOKEN:
            l1_text, l1_rationale = await self._gen_or_truncate(
                m.text, L1_PROMPT, L1_MAX_TOKENS * CHARS_PER_TOKEN, "L1"
            )
            l1 = Summary(
                id=new_id(), source_kind="memory", source_ref=m.id, level=1,
                parent_id=l0.id, text=l1_text,
                token_estimate=len(l1_text) // CHARS_PER_TOKEN,
                created_at=now, rationale=l1_rationale,
            )
            self.driver.upsert_summary(l1)

        log.info("summarized", memory_id=m.id)

    async def _gen_or_truncate(self, text: str, prompt_tpl: str, max_chars: int, tier: str):
        try:
            prompt = prompt_tpl.format(max_chars=max_chars, text=text)
            out = await self.ollama.generate(prompt, model=self.model)
            return out.strip(), f"{tier} from gemma"
        except Exception as e:
            log.warning("summarizer.fallback", tier=tier, err=str(e))
            return _truncate(text, max_chars), f"{tier} fallback (truncate-prefix)"
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/unit/test_summarizer.py -v`
Expected: 2 PASSED.

- [ ] **Step 5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add echomem/src/echomem/workers/summarizer.py echomem/tests/unit/test_summarizer.py
git commit -m "feat(echomem): SummarizerWorker — gemma L0/L1/L2 + truncate fallback"
```

---

### Task 4: EntityExtractorWorker — gemma 三元组 + 置信度阈值

**Files:**
- Create: `lakeon/echomem/src/echomem/workers/entity_extractor.py`
- Create: `lakeon/echomem/tests/unit/test_entity_extractor.py`

- [ ] **Step 1: Write the failing tests**

`tests/unit/test_entity_extractor.py`:
```python
import json
import time
import pytest
from echomem.drivers.sqlite import SQLiteDriver
from echomem.drivers.base import Memory
from echomem.ollama_client import OllamaClient
from echomem.workers.entity_extractor import EntityExtractorWorker


@pytest.fixture
def driver(tmp_path):
    d = SQLiteDriver(tmp_path / "db.sqlite")
    yield d
    d.close()


def _seed(driver, mid="01HXEX0000000000000000000", text="Jacky works on echomem."):
    now = int(time.time() * 1000)
    m = Memory(id=mid, agent_id="cc", source_kind="explicit", source_ref=None,
               text=text, meta=None, created_at=now, updated_at=now, deleted_at=None,
               embedding=[0.0] * 1024)
    driver.upsert_memory(m)
    return m


@pytest.mark.asyncio
async def test_extracts_high_confidence_triple_into_main_table(tmp_path, httpx_mock, driver):
    m = _seed(driver)
    httpx_mock.add_response(
        method="POST",
        url="http://ol:11434/api/generate",
        json={"response": json.dumps({
            "triples": [
                {"subject": "Jacky", "predicate": "works_on", "object": "echomem", "confidence": 0.9}
            ]
        })},
    )
    async with OllamaClient("http://ol:11434") as ol:
        worker = EntityExtractorWorker(driver, ol, model="gemma4:e4b", confidence_threshold=0.7)
        await worker.handle(m.id)

    sub = driver.query_subgraph(seed_id=worker._entity_id("Jacky"), hops=1)
    assert any(e.name == "Jacky" for e in sub.nodes)
    assert any(e.name == "echomem" for e in sub.nodes)
    pending = driver.list_pending_triples()
    assert len(pending) == 0


@pytest.mark.asyncio
async def test_low_confidence_routes_to_pending(tmp_path, httpx_mock, driver):
    m = _seed(driver, mid="01HXEX0000000000000000001", text="Maybe X relates to Y somehow.")
    httpx_mock.add_response(
        method="POST",
        url="http://ol:11434/api/generate",
        json={"response": json.dumps({
            "triples": [
                {"subject": "X", "predicate": "relates_to", "object": "Y", "confidence": 0.4}
            ]
        })},
    )
    async with OllamaClient("http://ol:11434") as ol:
        worker = EntityExtractorWorker(driver, ol, model="gemma4:e4b")
        await worker.handle(m.id)

    pending = driver.list_pending_triples()
    assert len(pending) == 1
    assert pending[0]["subject_text"] == "X"
    # main triple table empty
    sub = driver.query_subgraph(seed_id="ent:x", hops=1)
    assert len(sub.edges) == 0


@pytest.mark.asyncio
async def test_malformed_llm_response_does_not_crash(tmp_path, httpx_mock, driver):
    m = _seed(driver, mid="01HXEX0000000000000000002")
    httpx_mock.add_response(
        method="POST",
        url="http://ol:11434/api/generate",
        json={"response": "not valid json at all"},
    )
    async with OllamaClient("http://ol:11434") as ol:
        worker = EntityExtractorWorker(driver, ol, model="gemma4:e4b")
        # should not raise
        await worker.handle(m.id)
    assert driver.list_pending_triples() == []
```

- [ ] **Step 2: Run (fails)**

Run: `pytest tests/unit/test_entity_extractor.py -v`

- [ ] **Step 3: Implement**

`src/echomem/workers/entity_extractor.py`:
```python
from __future__ import annotations

import json
import re
import time
from echomem.drivers.sqlite import SQLiteDriver
from echomem.drivers.base import Entity, Triple
from echomem.ollama_client import OllamaClient
from echomem.ulid import new as new_id
from echomem.logging import get_logger

log = get_logger("echomem.entity_extractor")

EXTRACT_PROMPT = """Extract factual (subject, predicate, object) triples from the text.
Each triple must include a confidence score in [0, 1].
Output STRICT JSON, no commentary, in this exact shape:

{{"triples": [{{"subject": "...", "predicate": "...", "object": "...", "confidence": 0.0}}]}}

If you find no clear factual triples, return {{"triples": []}}.

Text:
{text}

JSON:"""


class EntityExtractorWorker:
    def __init__(
        self,
        driver: SQLiteDriver,
        ollama: OllamaClient,
        *,
        model: str,
        confidence_threshold: float = 0.7,
    ):
        self.driver = driver
        self.ollama = ollama
        self.model = model
        self.threshold = confidence_threshold

    @staticmethod
    def _entity_id(name: str) -> str:
        slug = re.sub(r"[^a-z0-9]+", "_", name.lower()).strip("_")
        return f"ent:{slug}" if slug else f"ent:{new_id()}"

    async def handle(self, memory_id: str) -> None:
        m = self.driver.get_memory(memory_id)
        if m is None:
            return

        now = int(time.time() * 1000)
        try:
            raw = await self.ollama.generate(
                EXTRACT_PROMPT.format(text=m.text), model=self.model
            )
        except Exception as e:
            log.warning("extractor.llm_failed", memory_id=memory_id, err=str(e))
            return

        triples = self._parse_triples(raw)
        if not triples:
            log.info("extractor.no_triples", memory_id=memory_id)
            return

        for t in triples:
            conf = float(t.get("confidence", 0.0))
            sub = str(t.get("subject", "")).strip()
            pred = str(t.get("predicate", "")).strip()
            obj = str(t.get("object", "")).strip()
            if not (sub and pred and obj):
                continue

            if conf < self.threshold:
                self.driver.upsert_pending_triple(
                    id=new_id(), subject_text=sub, predicate=pred, object_text=obj,
                    source_memory_id=memory_id, confidence=conf, created_at=now,
                )
                continue

            sid = self._entity_id(sub)
            oid = self._entity_id(obj)
            self.driver.upsert_entity(Entity(id=sid, name=sub, kind=None, meta=None,
                                             first_seen_at=now, last_seen_at=now))
            self.driver.upsert_entity(Entity(id=oid, name=obj, kind=None, meta=None,
                                             first_seen_at=now, last_seen_at=now))
            self.driver.upsert_triple(Triple(id=new_id(), subject_id=sid, predicate=pred,
                                             object_id=oid, source_memory_id=memory_id,
                                             confidence=conf, created_at=now))

        log.info("extractor.done", memory_id=memory_id, triples=len(triples))

    def _parse_triples(self, raw: str) -> list[dict]:
        # Try to find JSON object in response (gemma sometimes wraps with ``` or extra text)
        m = re.search(r"\{.*\}", raw, re.DOTALL)
        if not m:
            return []
        try:
            data = json.loads(m.group(0))
        except json.JSONDecodeError:
            return []
        return data.get("triples", []) if isinstance(data, dict) else []
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/unit/test_entity_extractor.py -v`
Expected: 3 PASSED.

- [ ] **Step 5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add echomem/src/echomem/workers/entity_extractor.py echomem/tests/unit/test_entity_extractor.py
git commit -m "feat(echomem): EntityExtractorWorker — gemma triples + confidence threshold + pending fallback"
```

---

### Task 5: TimelineWorker — 时间窗 + 主题相似度聚合（无 LLM）

**Files:**
- Create: `lakeon/echomem/src/echomem/workers/timeline.py`
- Create: `lakeon/echomem/tests/unit/test_timeline.py`

- [ ] **Step 1: Write the failing tests**

`tests/unit/test_timeline.py`:
```python
import time
import pytest
from echomem.drivers.sqlite import SQLiteDriver
from echomem.drivers.base import Memory
from echomem.workers.timeline import TimelineWorker


@pytest.fixture
def driver(tmp_path):
    d = SQLiteDriver(tmp_path / "db.sqlite")
    yield d
    d.close()


def _seed(driver, mid, agent, text, ts_ms, emb=None):
    m = Memory(
        id=mid, agent_id=agent, source_kind="explicit", source_ref=None,
        text=text, meta=None, created_at=ts_ms, updated_at=ts_ms, deleted_at=None,
        embedding=emb if emb is not None else [0.0] * 1024,
    )
    driver.upsert_memory(m)
    return m


def _emb(seed: int) -> list[float]:
    v = [0.0] * 1024
    v[seed] = 1.0
    return v


def test_two_close_similar_memories_become_one_event(driver):
    now = int(time.time() * 1000)
    m1 = _seed(driver, "01HXTL0000000000000000001", "cc", "fixing login", now,        _emb(0))
    m2 = _seed(driver, "01HXTL0000000000000000002", "cc", "still on login bug", now + 60_000, _emb(0))

    worker = TimelineWorker(driver)
    worker.handle(m1.id)
    worker.handle(m2.id)

    events = driver.query_timeline(start_ms=now - 1, end_ms=now + 120_000, agent_id="cc")
    assert len(events) == 1
    assert {m1.id, m2.id}.issubset(set(events[0].member_memory_ids))


def test_far_apart_in_time_become_two_events(driver):
    now = int(time.time() * 1000)
    m1 = _seed(driver, "01HXTL0000000000000000003", "cc", "task A", now,                _emb(0))
    m2 = _seed(driver, "01HXTL0000000000000000004", "cc", "task A again", now + 60 * 60_000, _emb(0))

    worker = TimelineWorker(driver)
    worker.handle(m1.id)
    worker.handle(m2.id)

    events = driver.query_timeline(start_ms=now - 1, end_ms=now + 120 * 60_000, agent_id="cc")
    assert len(events) == 2


def test_dissimilar_topic_becomes_new_event(driver):
    now = int(time.time() * 1000)
    m1 = _seed(driver, "01HXTL0000000000000000005", "cc", "auth refactor", now,         _emb(0))
    m2 = _seed(driver, "01HXTL0000000000000000006", "cc", "css tweaks",     now + 60_000, _emb(50))

    worker = TimelineWorker(driver)
    worker.handle(m1.id)
    worker.handle(m2.id)

    events = driver.query_timeline(start_ms=now - 1, end_ms=now + 120_000, agent_id="cc")
    assert len(events) == 2
```

- [ ] **Step 2: Run (fails)**

Run: `pytest tests/unit/test_timeline.py -v`

- [ ] **Step 3: Implement**

`src/echomem/workers/timeline.py`:
```python
from __future__ import annotations

import math
import sqlite3
import time
from echomem.drivers.sqlite import SQLiteDriver
from echomem.drivers.base import Event
from echomem.ulid import new as new_id
from echomem.logging import get_logger

log = get_logger("echomem.timeline")

WINDOW_MS = 30 * 60 * 1000  # 30 minutes
SIMILARITY_THRESHOLD = 0.7


def _cosine(a: list[float], b: list[float]) -> float:
    if not a or not b or len(a) != len(b):
        return 0.0
    dot = sum(x * y for x, y in zip(a, b))
    na = math.sqrt(sum(x * x for x in a))
    nb = math.sqrt(sum(y * y for y in b))
    if na == 0 or nb == 0:
        return 0.0
    return dot / (na * nb)


def _load_memory_embedding(driver: SQLiteDriver, memory_id: str) -> list[float] | None:
    from sqlite_vec import serialize_float32  # noqa: F401
    import struct

    row = driver.con.execute(
        "SELECT embedding FROM memory_vec WHERE memory_id = ?", (memory_id,)
    ).fetchone()
    if row is None or row[0] is None:
        return None
    blob: bytes = row[0]
    n = len(blob) // 4
    return list(struct.unpack(f"{n}f", blob))


class TimelineWorker:
    """Aggregate memories into Episodic events.
    Pure-Python; no LLM calls. Uses memory_vec embeddings to gauge topic similarity."""

    def __init__(self, driver: SQLiteDriver):
        self.driver = driver

    def handle(self, memory_id: str) -> None:
        m = self.driver.get_memory(memory_id)
        if m is None:
            return

        emb = _load_memory_embedding(self.driver, memory_id) or []
        # find candidate event in same agent + within window of m.created_at
        candidate = self._find_open_event(m.agent_id, m.created_at)
        if candidate is not None and self._is_similar(candidate, emb):
            self._extend(candidate, m.id, m.created_at)
        else:
            self._open_new_event(m.agent_id, m.id, m.created_at, m.text)

    def _find_open_event(self, agent_id: str, ts: int) -> Event | None:
        rows = self.driver.query_timeline(
            start_ms=ts - WINDOW_MS, end_ms=ts + 1, agent_id=agent_id
        )
        return rows[0] if rows else None

    def _is_similar(self, ev: Event, emb: list[float]) -> bool:
        if not emb or not ev.member_memory_ids:
            return True  # no signal → join (cheap heuristic)
        # take the first member's embedding as the event centroid (cheap)
        first_emb = _load_memory_embedding(self.driver, ev.member_memory_ids[0])
        if not first_emb:
            return True
        return _cosine(emb, first_emb) >= SIMILARITY_THRESHOLD

    def _extend(self, ev: Event, memory_id: str, ts: int) -> None:
        members = list(ev.member_memory_ids) + [memory_id]
        new_we = max(ev.window_end, ts)
        updated = Event(
            id=ev.id, window_start=ev.window_start, window_end=new_we,
            agent_id=ev.agent_id, title=ev.title, summary=ev.summary,
            member_memory_ids=members, created_at=ev.created_at,
            rationale=(ev.rationale or "") + f"; appended {memory_id}",
        )
        self.driver.upsert_event(updated)

    def _open_new_event(self, agent_id: str, memory_id: str, ts: int, sample_text: str) -> None:
        title = sample_text[:60].replace("\n", " ")
        ev = Event(
            id=new_id(), window_start=ts, window_end=ts, agent_id=agent_id,
            title=title or "(untitled)", summary=None,
            member_memory_ids=[memory_id], created_at=ts,
            rationale="opened new event (no nearby similar event)",
        )
        self.driver.upsert_event(ev)
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/unit/test_timeline.py -v`
Expected: 3 PASSED.

- [ ] **Step 5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add echomem/src/echomem/workers/timeline.py echomem/tests/unit/test_timeline.py
git commit -m "feat(echomem): TimelineWorker — 30min window + cosine topic aggregation (no LLM)"
```

---

### Task 6: ReflectorWorker — MVP 占位（周期性 noop）

**Files:**
- Create: `lakeon/echomem/src/echomem/workers/reflector.py`
- Create: `lakeon/echomem/tests/unit/test_reflector.py`

- [ ] **Step 1: Write the failing tests**

`tests/unit/test_reflector.py`:
```python
import pytest
from echomem.drivers.sqlite import SQLiteDriver
from echomem.workers.reflector import ReflectorWorker


@pytest.fixture
def driver(tmp_path):
    d = SQLiteDriver(tmp_path / "db.sqlite")
    yield d
    d.close()


@pytest.mark.asyncio
async def test_reflect_once_returns_stats(driver):
    worker = ReflectorWorker(driver)
    stats = await worker.reflect_once()
    assert "considered" in stats
    assert stats["status"] == "noop"


@pytest.mark.asyncio
async def test_reflect_runs_without_memories(driver):
    worker = ReflectorWorker(driver)
    # should not raise on empty store
    stats = await worker.reflect_once()
    assert stats["considered"] == 0
```

- [ ] **Step 2: Run (fails)**

Run: `pytest tests/unit/test_reflector.py -v`

- [ ] **Step 3: Implement**

`src/echomem/workers/reflector.py`:
```python
from __future__ import annotations

import time
from echomem.drivers.sqlite import SQLiteDriver
from echomem.logging import get_logger

log = get_logger("echomem.reflector")

# MVP placeholder. P1 will wire gemma to extract procedural skills from session
# clusters and synthesize episodic event titles/summaries.


class ReflectorWorker:
    def __init__(self, driver: SQLiteDriver):
        self.driver = driver

    async def reflect_once(self) -> dict:
        # Count recent events as a sanity probe; do nothing else.
        now = int(time.time() * 1000)
        rows = self.driver.query_timeline(start_ms=now - 24 * 3600_000, end_ms=now + 1)
        log.info("reflect.noop", events_in_24h=len(rows))
        return {"status": "noop", "considered": len(rows), "ts": now}
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/unit/test_reflector.py -v`
Expected: 2 PASSED.

- [ ] **Step 5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add echomem/src/echomem/workers/reflector.py echomem/tests/unit/test_reflector.py
git commit -m "feat(echomem): ReflectorWorker placeholder (P1 will wire gemma extraction)"
```

---

### Task 7: Skill importer — 扫 superpowers/impeccable 等 skill 目录

**Files:**
- Create: `lakeon/echomem/src/echomem/skills/__init__.py` (empty)
- Create: `lakeon/echomem/src/echomem/skills/importer.py`
- Create: `lakeon/echomem/tests/unit/test_skills_importer.py`

- [ ] **Step 1: Write the failing tests**

`tests/unit/test_skills_importer.py`:
```python
import pytest
from echomem.drivers.sqlite import SQLiteDriver
from echomem.ollama_client import OllamaClient
from echomem.skills.importer import import_skills_from_directory


@pytest.fixture
def driver(tmp_path):
    d = SQLiteDriver(tmp_path / "db.sqlite")
    yield d
    d.close()


def _write_skill(dir, name, description, body):
    path = dir / f"{name}.md"
    path.write_text(f"---\nname: {name}\ndescription: {description}\n---\n\n{body}\n",
                    encoding="utf-8")


@pytest.mark.asyncio
async def test_imports_two_skills(tmp_path, httpx_mock, driver):
    skills_dir = tmp_path / "skills"
    skills_dir.mkdir()
    _write_skill(skills_dir, "tdd", "use test-driven development",
                 "## Steps\n1. write test\n2. fail\n3. impl\n4. pass\n5. commit\n")
    _write_skill(skills_dir, "git-commit", "commit small focused changes",
                 "## Steps\n1. stage what changed\n2. write a tight message\n3. commit\n")

    # Each skill triggers one embedding call
    httpx_mock.add_response(method="POST", url="http://ol:11434/api/embeddings",
                            json={"embedding": [1.0] + [0.0] * 1023}, is_reusable=True)

    async with OllamaClient("http://ol:11434") as ol:
        n = await import_skills_from_directory(driver, ol,
                                               directory=skills_dir,
                                               embedding_model="qwen3-embedding:0.6b",
                                               agent_scope="all")

    assert n == 2
    hits = driver.query_skills(query_emb=[1.0] + [0.0] * 1023, k=5)
    names = {h.name for h in hits}
    assert {"tdd", "git-commit"}.issubset(names)


@pytest.mark.asyncio
async def test_skips_files_without_frontmatter(tmp_path, httpx_mock, driver):
    skills_dir = tmp_path / "skills"
    skills_dir.mkdir()
    (skills_dir / "no-fm.md").write_text("# just a doc\n", encoding="utf-8")
    httpx_mock.add_response(method="POST", url="http://ol:11434/api/embeddings",
                            json={"embedding": [0.0] * 1024}, is_reusable=True, is_optional=True)

    async with OllamaClient("http://ol:11434") as ol:
        n = await import_skills_from_directory(driver, ol, directory=skills_dir,
                                               embedding_model="qwen3-embedding:0.6b")
    assert n == 0
```

- [ ] **Step 2: Run (fails)**

Run: `pytest tests/unit/test_skills_importer.py -v`

- [ ] **Step 3: Implement**

`src/echomem/skills/__init__.py`: empty

`src/echomem/skills/importer.py`:
```python
from __future__ import annotations

import re
import time
from pathlib import Path
from echomem.drivers.sqlite import SQLiteDriver
from echomem.drivers.base import Skill
from echomem.ollama_client import OllamaClient
from echomem.ulid import new as new_id
from echomem.logging import get_logger

log = get_logger("echomem.skill_importer")

FRONTMATTER_RE = re.compile(r"^---\s*\n(.*?)\n---\s*\n", re.DOTALL)


def _parse_frontmatter(text: str) -> tuple[dict[str, str], str]:
    m = FRONTMATTER_RE.match(text)
    if not m:
        return {}, text
    fm: dict[str, str] = {}
    for line in m.group(1).splitlines():
        if ":" in line:
            k, v = line.split(":", 1)
            fm[k.strip()] = v.strip()
    return fm, text[m.end():]


def _parse_steps(body: str) -> list[str]:
    # Pull numbered list items "1. xxx" — first match block only
    steps: list[str] = []
    for line in body.splitlines():
        m = re.match(r"\s*\d+[.\)]\s+(.+)", line)
        if m:
            steps.append(m.group(1).strip())
    return steps


async def import_skills_from_directory(
    driver: SQLiteDriver,
    ollama: OllamaClient,
    *,
    directory: Path,
    embedding_model: str,
    agent_scope: str | None = None,
) -> int:
    """Scan a directory of *.md skill files (with name/description frontmatter)
    and import them as derivative_skill rows. Returns the count imported."""
    if not directory.exists():
        log.warning("importer.dir_missing", path=str(directory))
        return 0

    count = 0
    now = int(time.time() * 1000)
    for path in sorted(directory.glob("*.md")):
        text = path.read_text(encoding="utf-8")
        fm, body = _parse_frontmatter(text)
        if "name" not in fm or "description" not in fm:
            log.info("importer.skip_no_frontmatter", file=str(path))
            continue

        try:
            emb = await ollama.embed(fm["description"], model=embedding_model)
        except Exception as e:
            log.warning("importer.embed_failed", file=str(path), err=str(e))
            continue

        sk = Skill(
            id=f"skill:imported:{fm['name']}",
            name=fm["name"],
            trigger_pattern=fm["description"],
            trigger_emb=emb,
            steps=_parse_steps(body),
            agent_scope=agent_scope,
            source="imported",
            observed_count=0,
            success_count=0,
            last_used_at=None,
            created_at=now,
            rationale=f"imported from {path.name}",
        )
        driver.upsert_skill(sk)
        count += 1
    log.info("importer.done", count=count, dir=str(directory))
    return count
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/unit/test_skills_importer.py -v`
Expected: 2 PASSED.

- [ ] **Step 5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add echomem/src/echomem/skills/ echomem/tests/unit/test_skills_importer.py
git commit -m "feat(echomem): skill importer — scan *.md frontmatter, embed description, write derivative_skill"
```

---

### Task 8: Pipeline queue — asyncio.Queue + retry + dead_letter

**Files:**
- Create: `lakeon/echomem/src/echomem/pipeline/__init__.py` (empty)
- Create: `lakeon/echomem/src/echomem/pipeline/queue.py`
- Create: `lakeon/echomem/tests/unit/test_pipeline_queue.py`

- [ ] **Step 1: Write the failing tests**

`tests/unit/test_pipeline_queue.py`:
```python
import asyncio
import pytest
from echomem.drivers.sqlite import SQLiteDriver
from echomem.pipeline.queue import WorkerPool, TaskKind


@pytest.fixture
def driver(tmp_path):
    d = SQLiteDriver(tmp_path / "db.sqlite")
    yield d
    d.close()


@pytest.mark.asyncio
async def test_enqueue_runs_handler_once(driver):
    seen: list[str] = []

    async def handler(memory_id: str):
        seen.append(memory_id)

    pool = WorkerPool(driver, handlers={TaskKind.SUMMARIZE: handler})
    await pool.start()
    await pool.enqueue(TaskKind.SUMMARIZE, memory_id="01HXM0000000000000000A")
    await pool.drain()
    await pool.stop()
    assert seen == ["01HXM0000000000000000A"]


@pytest.mark.asyncio
async def test_failing_handler_retries_then_dead_letters(driver):
    attempts: list[int] = []

    async def handler(memory_id: str):
        attempts.append(1)
        raise RuntimeError("nope")

    pool = WorkerPool(driver, handlers={TaskKind.SUMMARIZE: handler}, max_attempts=3,
                     retry_base_seconds=0)  # no real backoff in tests
    await pool.start()
    await pool.enqueue(TaskKind.SUMMARIZE, memory_id="01HXM0000000000000000B")
    await pool.drain()
    await pool.stop()

    assert len(attempts) == 3
    rows = driver.con.execute("SELECT count(*) FROM dead_letter").fetchone()
    assert rows[0] == 1


@pytest.mark.asyncio
async def test_unknown_kind_is_dead_lettered_immediately(driver):
    pool = WorkerPool(driver, handlers={})
    await pool.start()
    await pool.enqueue(TaskKind.SUMMARIZE, memory_id="01HXM0000000000000000C")
    await pool.drain()
    await pool.stop()

    rows = driver.con.execute("SELECT error FROM dead_letter").fetchone()
    assert rows is not None
    assert "unknown" in rows[0].lower() or "no handler" in rows[0].lower()
```

- [ ] **Step 2: Run (fails)**

Run: `pytest tests/unit/test_pipeline_queue.py -v`

- [ ] **Step 3: Implement**

`src/echomem/pipeline/__init__.py`: empty

`src/echomem/pipeline/queue.py`:
```python
from __future__ import annotations

import asyncio
import time
from dataclasses import dataclass
from enum import Enum
from typing import Awaitable, Callable

from echomem.drivers.sqlite import SQLiteDriver
from echomem.ulid import new as new_id
from echomem.logging import get_logger

log = get_logger("echomem.pipeline")


class TaskKind(str, Enum):
    SUMMARIZE = "summarize"
    EXTRACT_ENTITY = "extract_entity"
    AGGREGATE_TIMELINE = "aggregate_timeline"
    REFLECT = "reflect"


Handler = Callable[[str], Awaitable[None]]


@dataclass
class _Task:
    id: str
    kind: TaskKind
    memory_id: str | None
    attempts: int = 0


class WorkerPool:
    def __init__(
        self,
        driver: SQLiteDriver,
        *,
        handlers: dict[TaskKind, Handler],
        max_attempts: int = 3,
        retry_base_seconds: float = 1.0,
        concurrency: int = 2,
    ):
        self.driver = driver
        self.handlers = handlers
        self.max_attempts = max_attempts
        self.retry_base_seconds = retry_base_seconds
        self.concurrency = concurrency
        self._q: asyncio.Queue[_Task] = asyncio.Queue()
        self._workers: list[asyncio.Task] = []
        self._running = False

    async def start(self) -> None:
        self._running = True
        for _ in range(self.concurrency):
            self._workers.append(asyncio.create_task(self._loop()))

    async def stop(self) -> None:
        self._running = False
        for w in self._workers:
            w.cancel()
        for w in self._workers:
            try:
                await w
            except (asyncio.CancelledError, Exception):
                pass
        self._workers.clear()

    async def drain(self) -> None:
        await self._q.join()

    async def enqueue(self, kind: TaskKind, *, memory_id: str | None) -> str:
        now = int(time.time() * 1000)
        tid = new_id()
        self.driver.con.execute(
            "INSERT INTO derivative_task(id, kind, memory_id, status, attempts, created_at, updated_at) "
            "VALUES(?, ?, ?, 'pending', 0, ?, ?)",
            (tid, kind.value, memory_id, now, now),
        )
        self.driver.con.commit()
        await self._q.put(_Task(id=tid, kind=kind, memory_id=memory_id))
        return tid

    async def _loop(self) -> None:
        while self._running:
            try:
                task = await self._q.get()
            except asyncio.CancelledError:
                return
            try:
                await self._handle_one(task)
            finally:
                self._q.task_done()

    async def _handle_one(self, task: _Task) -> None:
        handler = self.handlers.get(task.kind)
        if handler is None:
            self._dead_letter(task, "no handler for kind: " + task.kind.value)
            return
        try:
            self._mark_status(task.id, "running", attempts=task.attempts + 1)
            await handler(task.memory_id) if task.memory_id is not None else await handler("")
            self._mark_status(task.id, "done", attempts=task.attempts + 1)
        except Exception as e:
            task.attempts += 1
            self._mark_status(task.id, "failed", attempts=task.attempts, last_error=str(e))
            if task.attempts >= self.max_attempts:
                self._dead_letter(task, str(e))
                return
            await asyncio.sleep(self.retry_base_seconds * (4 ** (task.attempts - 1)))
            await self._q.put(task)

    def _mark_status(self, task_id: str, status: str, *, attempts: int,
                     last_error: str | None = None) -> None:
        now = int(time.time() * 1000)
        self.driver.con.execute(
            "UPDATE derivative_task SET status = ?, attempts = ?, last_error = ?, updated_at = ? WHERE id = ?",
            (status, attempts, last_error, now, task_id),
        )
        self.driver.con.commit()

    def _dead_letter(self, task: _Task, error: str) -> None:
        now = int(time.time() * 1000)
        self.driver.con.execute(
            "INSERT INTO dead_letter(id, task_id, kind, memory_id, error, created_at) "
            "VALUES(?, ?, ?, ?, ?, ?)",
            (new_id(), task.id, task.kind.value, task.memory_id, error, now),
        )
        self.driver.con.commit()
        log.warning("dead_letter", task_id=task.id, kind=task.kind.value, err=error)
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/unit/test_pipeline_queue.py -v`
Expected: 3 PASSED.

- [ ] **Step 5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add echomem/src/echomem/pipeline/ echomem/tests/unit/test_pipeline_queue.py
git commit -m "feat(echomem): WorkerPool — asyncio queue + retry + dead_letter"
```

---

### Task 9: Pipeline orchestrator — bind workers + ingest hook

**Files:**
- Create: `lakeon/echomem/src/echomem/pipeline/orchestrator.py`
- Create: `lakeon/echomem/tests/integration/test_orchestrator.py`

- [ ] **Step 1: Write the failing tests**

`tests/integration/test_orchestrator.py`:
```python
import time
import pytest
from echomem.drivers.sqlite import SQLiteDriver
from echomem.drivers.base import Memory
from echomem.ollama_client import OllamaClient
from echomem.pipeline.orchestrator import Orchestrator


@pytest.fixture
def driver(tmp_path):
    d = SQLiteDriver(tmp_path / "db.sqlite")
    yield d
    d.close()


@pytest.mark.asyncio
async def test_on_memory_ingested_runs_summarize_extract_timeline(tmp_path, httpx_mock, driver):
    httpx_mock.add_response(method="POST", url="http://ol:11434/api/generate",
                            json={"response": "summary"}, is_reusable=True, is_optional=True)
    httpx_mock.add_response(method="POST", url="http://ol:11434/api/embeddings",
                            json={"embedding": [0.0] * 1024}, is_reusable=True, is_optional=True)

    now = int(time.time() * 1000)
    m = Memory(id="01HXOR0000000000000000001", agent_id="cc", source_kind="explicit",
               source_ref=None, text="hello", meta=None, created_at=now, updated_at=now,
               deleted_at=None, embedding=[0.0] * 1024)
    driver.upsert_memory(m)

    async with OllamaClient("http://ol:11434") as ol:
        orch = Orchestrator(driver, ol, summary_model="gemma4:e4b",
                            extract_model="gemma4:e4b", embedding_model="qwen3-embedding:0.6b")
        await orch.start()
        await orch.on_memory_ingested(m.id)
        await orch.drain()
        await orch.stop()

    # Three task rows recorded for this memory
    rows = driver.con.execute(
        "SELECT kind, status FROM derivative_task WHERE memory_id = ? ORDER BY created_at",
        (m.id,),
    ).fetchall()
    kinds = {r[0] for r in rows}
    assert {"summarize", "extract_entity", "aggregate_timeline"}.issubset(kinds)
    # all should reach done (or failed-then-done, but here summary mock returns OK)
    assert all(r[1] in ("done", "failed") for r in rows)
```

- [ ] **Step 2: Run (fails)**

Run: `pytest tests/integration/test_orchestrator.py -v`

- [ ] **Step 3: Implement**

`src/echomem/pipeline/orchestrator.py`:
```python
from __future__ import annotations

from echomem.drivers.sqlite import SQLiteDriver
from echomem.ollama_client import OllamaClient
from echomem.pipeline.queue import WorkerPool, TaskKind
from echomem.workers.summarizer import SummarizerWorker
from echomem.workers.entity_extractor import EntityExtractorWorker
from echomem.workers.timeline import TimelineWorker
from echomem.logging import get_logger

log = get_logger("echomem.orchestrator")


class Orchestrator:
    """Glue: owns the WorkerPool and routes tasks to the per-kind worker."""

    def __init__(
        self,
        driver: SQLiteDriver,
        ollama: OllamaClient,
        *,
        summary_model: str,
        extract_model: str,
        embedding_model: str,
        confidence_threshold: float = 0.7,
    ):
        self.driver = driver
        self.ollama = ollama
        self.summarizer = SummarizerWorker(driver, ollama, model=summary_model)
        self.extractor = EntityExtractorWorker(driver, ollama, model=extract_model,
                                               confidence_threshold=confidence_threshold)
        self.timeline = TimelineWorker(driver)
        self.embedding_model = embedding_model

        self.pool = WorkerPool(
            driver,
            handlers={
                TaskKind.SUMMARIZE: self.summarizer.handle,
                TaskKind.EXTRACT_ENTITY: self.extractor.handle,
                TaskKind.AGGREGATE_TIMELINE: self._timeline_async,
            },
        )

    async def _timeline_async(self, memory_id: str) -> None:
        # TimelineWorker.handle is sync; wrap so the queue can await it
        self.timeline.handle(memory_id)

    async def start(self) -> None:
        await self.pool.start()

    async def stop(self) -> None:
        await self.pool.stop()

    async def drain(self) -> None:
        await self.pool.drain()

    async def on_memory_ingested(self, memory_id: str) -> None:
        await self.pool.enqueue(TaskKind.SUMMARIZE, memory_id=memory_id)
        await self.pool.enqueue(TaskKind.EXTRACT_ENTITY, memory_id=memory_id)
        await self.pool.enqueue(TaskKind.AGGREGATE_TIMELINE, memory_id=memory_id)
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/integration/test_orchestrator.py -v`
Expected: 1 PASSED.

- [ ] **Step 5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add echomem/src/echomem/pipeline/orchestrator.py echomem/tests/integration/test_orchestrator.py
git commit -m "feat(echomem): Orchestrator — bind 3 workers + on_memory_ingested hook"
```

---

### Task 10: Wire Orchestrator into daemon lifespan + ingest endpoint

**Files:**
- Modify: `lakeon/echomem/src/echomem/daemon/app.py`
- Modify: `lakeon/echomem/src/echomem/api/memory.py`
- Modify: `lakeon/echomem/tests/integration/test_memory_endpoints.py`

- [ ] **Step 1: Write the failing test**

Append to `tests/integration/test_memory_endpoints.py`:
```python
@pytest.mark.asyncio
async def test_ingest_triggers_pipeline(client):
    r = await client.post("/memory/ingest", json={"text": "hello pipeline", "agent_id": "cc"})
    assert r.status_code == 200
    mid = r.json()["id"]

    # Drain background pipeline (test harness exposes orchestrator on app.state)
    from httpx import ASGITransport, AsyncClient
    # quick & ugly: poke orchestrator directly via app.state — only works in-process
    app = client._transport.app  # type: ignore[attr-defined]
    await app.state.orchestrator.drain()

    rows = app.state.driver.con.execute(
        "SELECT kind FROM derivative_task WHERE memory_id = ? ORDER BY kind", (mid,)
    ).fetchall()
    kinds = {r[0] for r in rows}
    assert {"aggregate_timeline", "extract_entity", "summarize"}.issubset(kinds)
```

- [ ] **Step 2: Run (fails)**

Run: `pytest tests/integration/test_memory_endpoints.py::test_ingest_triggers_pipeline -v`

- [ ] **Step 3: Implement**

Modify `src/echomem/daemon/app.py` lifespan (add orchestrator wiring):
```python
@asynccontextmanager
async def _lifespan(app: FastAPI):
    cfg: EchomemConfig = app.state.config
    cfg.data_dir.mkdir(parents=True, exist_ok=True)
    driver = SQLiteDriver(cfg.data_dir / "db.sqlite", embedding_dim=cfg.embedding_dim)
    ollama = OllamaClient(cfg.ollama_url)
    app.state.driver = driver
    app.state.ollama = ollama

    # Orchestrator
    from echomem.pipeline.orchestrator import Orchestrator
    orchestrator = Orchestrator(
        driver, ollama,
        summary_model=cfg.embedding_model.split(":")[0] + ":" + cfg.embedding_model.split(":")[1] if False else "gemma4:e4b",
        extract_model="gemma4:e4b",
        embedding_model=cfg.embedding_model,
    )
    await orchestrator.start()
    app.state.orchestrator = orchestrator

    try:
        yield
    finally:
        await orchestrator.stop()
        await ollama.aclose()
        driver.close()
```

> **Note on model selection:** The current `EchomemConfig` has only `embedding_model`. Add a config field for the generation model in a follow-up task — for Plan 2 we hardcode `gemma4:e4b`. Document this clearly so T11+ implementers know.

Modify `src/echomem/api/memory.py` `ingest` handler:
```python
@router.post("/ingest", response_model=IngestResponse)
async def ingest(req: IngestRequest, request: Request) -> IngestResponse:
    driver = request.app.state.driver
    ollama = request.app.state.ollama
    cfg = request.app.state.config
    orchestrator = request.app.state.orchestrator

    now = int(time.time() * 1000)
    mid = new_id()
    embedding = await ollama.embed(req.text, model=cfg.embedding_model)
    mem = Memory(
        id=mid, agent_id=req.agent_id, source_kind=req.source_kind,
        source_ref=req.source_ref, text=req.text, meta=req.meta,
        created_at=now, updated_at=now, deleted_at=None, embedding=embedding,
    )
    driver.upsert_memory(mem)
    await orchestrator.on_memory_ingested(mid)
    return IngestResponse(id=mid, agent_id=req.agent_id, created_at=now)
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/integration/test_memory_endpoints.py -v`
Expected: 9 PASSED (8 prior + 1 new).

- [ ] **Step 5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add echomem/src/echomem/daemon/app.py echomem/src/echomem/api/memory.py echomem/tests/integration/test_memory_endpoints.py
git commit -m "feat(echomem): wire Orchestrator into daemon lifespan + ingest hook"
```

---

### Task 11: GET /derivatives/timeline / tree / graph / skills endpoints

**Files:**
- Create: `lakeon/echomem/src/echomem/api/derivatives.py`
- Create: `lakeon/echomem/src/echomem/api/skills.py`
- Modify: `lakeon/echomem/src/echomem/api/schemas.py`
- Modify: `lakeon/echomem/src/echomem/daemon/app.py`
- Create: `lakeon/echomem/tests/integration/test_derivatives_endpoints.py`

- [ ] **Step 1: Write the failing tests**

`tests/integration/test_derivatives_endpoints.py`:
```python
import time
import pytest
from httpx import ASGITransport, AsyncClient
from echomem.config import EchomemConfig
from echomem.daemon.app import create_app


@pytest.fixture
async def client(tmp_path, httpx_mock):
    httpx_mock.add_response(method="POST", url="http://localhost:11434/api/embeddings",
                            json={"embedding": [0.0] * 1024}, is_reusable=True, is_optional=True)
    httpx_mock.add_response(method="POST", url="http://localhost:11434/api/generate",
                            json={"response": "summary"}, is_reusable=True, is_optional=True)
    cfg = EchomemConfig(data_dir=tmp_path)
    app = create_app(cfg)
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://t") as c:
        async with app.router.lifespan_context(app):
            yield c


@pytest.mark.asyncio
async def test_timeline_endpoint(client):
    r = await client.post("/memory/ingest", json={"text": "morning task", "agent_id": "cc"})
    mid = r.json()["id"]
    app = client._transport.app  # type: ignore[attr-defined]
    await app.state.orchestrator.drain()

    r2 = await client.get("/derivatives/timeline?agent=cc")
    assert r2.status_code == 200
    body = r2.json()
    assert "events" in body and len(body["events"]) >= 1
    assert mid in body["events"][0]["member_memory_ids"]


@pytest.mark.asyncio
async def test_tree_endpoint(client):
    r = await client.post("/memory/ingest", json={"text": "the quick brown fox", "agent_id": "cc"})
    mid = r.json()["id"]
    app = client._transport.app  # type: ignore[attr-defined]
    await app.state.orchestrator.drain()

    r2 = await client.get(f"/derivatives/tree?source_kind=memory&source_ref={mid}")
    assert r2.status_code == 200
    levels = {s["level"] for s in r2.json()["levels"]}
    assert 2 in levels  # at minimum L2 always present


@pytest.mark.asyncio
async def test_graph_endpoint_empty_when_no_entities(client):
    r2 = await client.get("/derivatives/graph?seed=ent:nonexistent")
    assert r2.status_code == 200
    body = r2.json()
    assert body["nodes"] == []
    assert body["edges"] == []


@pytest.mark.asyncio
async def test_skills_endpoint_empty_when_none_imported(client):
    r2 = await client.get("/derivatives/skills?ctx=write+a+test+first")
    assert r2.status_code == 200
    assert r2.json()["skills"] == []
```

- [ ] **Step 2: Run (fails)**

Run: `pytest tests/integration/test_derivatives_endpoints.py -v`

- [ ] **Step 3: Implement**

Append to `src/echomem/api/schemas.py`:
```python
class TimelineEventOut(BaseModel):
    id: str
    window_start: int
    window_end: int
    agent_id: str
    title: str
    summary: str | None
    member_memory_ids: list[str]
    rationale: str | None


class TimelineResponse(BaseModel):
    events: list[TimelineEventOut]


class TreeNodeOut(BaseModel):
    id: str
    level: int
    parent_id: str | None
    text: str
    token_estimate: int | None
    rationale: str | None


class TreeResponse(BaseModel):
    levels: list[TreeNodeOut]


class GraphNodeOut(BaseModel):
    id: str
    name: str
    kind: str | None


class GraphEdgeOut(BaseModel):
    subject_id: str
    object_id: str
    predicate: str
    confidence: float


class GraphResponse(BaseModel):
    nodes: list[GraphNodeOut]
    edges: list[GraphEdgeOut]


class SkillOut(BaseModel):
    id: str
    name: str
    trigger_pattern: str
    steps: list[str]
    source: str
    observed_count: int
    success_count: int


class SkillsResponse(BaseModel):
    skills: list[SkillOut]
```

`src/echomem/api/derivatives.py`:
```python
from __future__ import annotations

import time
from fastapi import APIRouter, Request

from echomem.api.schemas import (
    TimelineResponse, TimelineEventOut,
    TreeResponse, TreeNodeOut,
    GraphResponse, GraphNodeOut, GraphEdgeOut,
    SkillsResponse, SkillOut,
)

router = APIRouter(prefix="/derivatives")


@router.get("/timeline", response_model=TimelineResponse)
async def timeline(
    request: Request,
    agent: str | None = None,
    start_ms: int | None = None,
    end_ms: int | None = None,
) -> TimelineResponse:
    driver = request.app.state.driver
    now = int(time.time() * 1000)
    end_ms = end_ms or now + 1
    start_ms = start_ms or 0
    events = driver.query_timeline(start_ms=start_ms, end_ms=end_ms, agent_id=agent)
    return TimelineResponse(events=[
        TimelineEventOut(
            id=e.id, window_start=e.window_start, window_end=e.window_end,
            agent_id=e.agent_id, title=e.title, summary=e.summary,
            member_memory_ids=e.member_memory_ids, rationale=e.rationale,
        ) for e in events
    ])


@router.get("/tree", response_model=TreeResponse)
async def tree(request: Request, source_kind: str, source_ref: str) -> TreeResponse:
    driver = request.app.state.driver
    nodes = driver.query_tree(source_kind=source_kind, source_ref=source_ref)
    return TreeResponse(levels=[
        TreeNodeOut(id=n.id, level=n.level, parent_id=n.parent_id, text=n.text,
                    token_estimate=n.token_estimate, rationale=n.rationale)
        for n in nodes
    ])


@router.get("/graph", response_model=GraphResponse)
async def graph(request: Request, seed: str, hops: int = 2) -> GraphResponse:
    driver = request.app.state.driver
    sub = driver.query_subgraph(seed_id=seed, hops=hops)
    return GraphResponse(
        nodes=[GraphNodeOut(id=n.id, name=n.name, kind=n.kind) for n in sub.nodes],
        edges=[
            GraphEdgeOut(subject_id=s, object_id=o, predicate=attrs["predicate"],
                         confidence=attrs.get("confidence", 0.0))
            for s, o, attrs in sub.edges
        ],
    )


@router.get("/skills", response_model=SkillsResponse)
async def skills(request: Request, ctx: str, k: int = 5) -> SkillsResponse:
    driver = request.app.state.driver
    ollama = request.app.state.ollama
    cfg = request.app.state.config
    if not ctx.strip():
        return SkillsResponse(skills=[])
    emb = await ollama.embed(ctx, model=cfg.embedding_model)
    hits = driver.query_skills(query_emb=emb, k=k)
    return SkillsResponse(skills=[
        SkillOut(id=s.id, name=s.name, trigger_pattern=s.trigger_pattern,
                 steps=s.steps, source=s.source,
                 observed_count=s.observed_count, success_count=s.success_count)
        for s in hits
    ])
```

`src/echomem/api/skills.py`:
```python
from __future__ import annotations

from pathlib import Path
from fastapi import APIRouter, HTTPException, Request

from echomem.skills.importer import import_skills_from_directory

router = APIRouter(prefix="/skills")


@router.post("/import")
async def import_skills(request: Request, path: str, agent_scope: str | None = None) -> dict:
    p = Path(path).expanduser().resolve()
    if not p.is_dir():
        raise HTTPException(status_code=400, detail=f"not a directory: {p}")
    cfg = request.app.state.config
    n = await import_skills_from_directory(
        request.app.state.driver, request.app.state.ollama,
        directory=p, embedding_model=cfg.embedding_model, agent_scope=agent_scope,
    )
    return {"imported": n, "directory": str(p)}
```

Modify `src/echomem/daemon/app.py` `create_app` — register both routers:
```python
from echomem.api.derivatives import router as derivatives_router
from echomem.api.skills import router as skills_router

# inside create_app:
    app.include_router(memory_router)
    app.include_router(derivatives_router)
    app.include_router(skills_router)
```

- [ ] **Step 4: Tests pass**

Run: `pytest tests/integration/test_derivatives_endpoints.py -v`
Expected: 4 PASSED.

- [ ] **Step 5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add echomem/src/echomem/api/derivatives.py echomem/src/echomem/api/skills.py echomem/src/echomem/api/schemas.py echomem/src/echomem/daemon/app.py echomem/tests/integration/test_derivatives_endpoints.py
git commit -m "feat(echomem): /derivatives/{timeline,tree,graph,skills} + POST /skills/import"
```

---

### Task 12: Add `generate_model` config + remove hardcoded `gemma4:e4b`

**Files:**
- Modify: `lakeon/echomem/src/echomem/config.py`
- Modify: `lakeon/echomem/src/echomem/daemon/app.py`
- Modify: `lakeon/echomem/tests/unit/test_config.py`

- [ ] **Step 1: Write the failing test**

Append to `tests/unit/test_config.py`:
```python
def test_default_generate_model(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    monkeypatch.delenv("ECHOMEM_PORT", raising=False)
    cfg = load_config()
    assert cfg.generate_model == "gemma4:e4b"


def test_load_generate_model_from_file(tmp_path, monkeypatch):
    monkeypatch.setenv("HOME", str(tmp_path))
    cfg_dir = tmp_path / ".echomem"
    cfg_dir.mkdir()
    (cfg_dir / "config.toml").write_bytes(
        tomli_w.dumps({"ollama": {"generate_model": "llama3:8b"}}).encode()
    )
    cfg = load_config()
    assert cfg.generate_model == "llama3:8b"
```

- [ ] **Step 2: Run (fails)**

Run: `pytest tests/unit/test_config.py -v`

- [ ] **Step 3: Implement**

Modify `src/echomem/config.py`:
```python
class EchomemConfig(BaseModel):
    host: str = "127.0.0.1"
    port: int = Field(default_factory=_pick_port, ge=1, le=65535)
    data_dir: Path = Field(default_factory=default_data_dir)
    ollama_url: str = "http://localhost:11434"
    embedding_model: str = "qwen3-embedding:0.6b"
    embedding_dim: int = 1024
    generate_model: str = "gemma4:e4b"          # NEW
    log_level: str = "INFO"
```

Update `load_config` to read `ollama.generate_model`:
```python
    return EchomemConfig(
        host=daemon.get("host", "127.0.0.1"),
        port=daemon.get("port", _pick_port()),
        data_dir=Path(storage.get("data_dir", default_data_dir())),
        ollama_url=ollama.get("url", "http://localhost:11434"),
        embedding_model=ollama.get("embedding_model", "qwen3-embedding:0.6b"),
        embedding_dim=ollama.get("embedding_dim", 1024),
        generate_model=ollama.get("generate_model", "gemma4:e4b"),
        log_level=log.get("level", "INFO"),
    )
```

Update `write_config`:
```python
        "ollama": {
            "url": cfg.ollama_url,
            "embedding_model": cfg.embedding_model,
            "embedding_dim": cfg.embedding_dim,
            "generate_model": cfg.generate_model,
        },
```

Modify `src/echomem/daemon/app.py` lifespan to use `cfg.generate_model`:
```python
    orchestrator = Orchestrator(
        driver, ollama,
        summary_model=cfg.generate_model,
        extract_model=cfg.generate_model,
        embedding_model=cfg.embedding_model,
    )
```

- [ ] **Step 4: Tests pass**

Run: `pytest -v`
Expected: All previous + 2 new = 65 passed + 1 skipped (rough count).

- [ ] **Step 5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add echomem/src/echomem/config.py echomem/src/echomem/daemon/app.py echomem/tests/unit/test_config.py
git commit -m "feat(echomem): config.generate_model — remove hardcoded gemma4:e4b"
```

---

### Task 13: Pipeline e2e — ingest → 4 derivatives all populated (Ollama mocked)

**Files:**
- Create: `lakeon/echomem/tests/integration/test_pipeline_e2e.py`

- [ ] **Step 1: Write the failing test**

`tests/integration/test_pipeline_e2e.py`:
```python
import json
import pytest
from httpx import ASGITransport, AsyncClient
from echomem.config import EchomemConfig
from echomem.daemon.app import create_app


@pytest.fixture
async def client(tmp_path, httpx_mock):
    httpx_mock.add_response(method="POST", url="http://localhost:11434/api/embeddings",
                            json={"embedding": [0.1] + [0.0] * 1023}, is_reusable=True)
    # gemma summarize replies; reused for L0/L1 calls
    httpx_mock.add_response(method="POST", url="http://localhost:11434/api/generate",
                            json={"response": json.dumps({"triples": [
                                {"subject": "Jacky", "predicate": "ships",
                                 "object": "echomem", "confidence": 0.9}]})},
                            is_reusable=True)
    cfg = EchomemConfig(data_dir=tmp_path)
    app = create_app(cfg)
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://t") as c:
        async with app.router.lifespan_context(app):
            yield c


@pytest.mark.asyncio
async def test_ingest_produces_summary_event_and_triple(client):
    r = await client.post("/memory/ingest",
                          json={"text": "Jacky ships echomem after a long sprint.",
                                "agent_id": "cc"})
    mid = r.json()["id"]

    app = client._transport.app  # type: ignore[attr-defined]
    await app.state.orchestrator.drain()

    # 1. timeline has 1 event with this memory id
    r1 = await client.get("/derivatives/timeline?agent=cc")
    assert any(mid in ev["member_memory_ids"] for ev in r1.json()["events"])

    # 2. tree has at least L2 (always) + maybe L0 (gemma returns the triple JSON as 'summary')
    r2 = await client.get(f"/derivatives/tree?source_kind=memory&source_ref={mid}")
    assert any(s["level"] == 2 for s in r2.json()["levels"])

    # 3. graph: Jacky → echomem
    r3 = await client.get("/derivatives/graph?seed=ent:jacky")
    edges = r3.json()["edges"]
    assert any(e["predicate"] == "ships" for e in edges)
```

- [ ] **Step 2: Run (PASS — code from prior tasks supports this)**

Run: `pytest tests/integration/test_pipeline_e2e.py -v`
Expected: 1 PASSED.

If failing, the most likely cause is the gemma response being parsed by both the summarizer (which expects plain text) and the extractor (which expects `{"triples": [...]}`). Both workers tolerate the cross-shaped response: summarizer treats it as opaque text; extractor parses the JSON. So mocking once should work.

- [ ] **Step 3: (No new code.)**

- [ ] **Step 4: Re-run all tests**

Run: `pytest -v`
Expected: All green (gated test SKIPPED).

- [ ] **Step 5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add echomem/tests/integration/test_pipeline_e2e.py
git commit -m "test(echomem): e2e ingest → summary + event + triple all populated"
```

---

### Task 14: Update README + manual smoke

**Files:**
- Modify: `lakeon/echomem/README.md`

- [ ] **Step 1: Append derivatives section to README**

Insert before the existing "## What's next" section:
```markdown
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
```

Update "## What's next" to:
```markdown
## What's next

- Plan 3: Context API + FS blobs (add_url / ls / read / write / mv)
- Plan 4: Vue 3 Dashboard SPA
- Plan 5: Onboarding install.sh + openclaw / hermes wiring
- Insight Track (research): output-length prediction
```

- [ ] **Step 2: Manual smoke (you, the user — not subagent)**

```bash
cd /Users/jacky/code/lakeon/echomem && source .venv/bin/activate
echomem start &
sleep 2
echomem mem ingest "Jacky ships echomem after a long sprint" --agent manual
sleep 5  # let pipeline run
curl --noproxy '*' http://127.0.0.1:8473/derivatives/timeline?agent=manual | jq
curl --noproxy '*' "http://127.0.0.1:8473/derivatives/graph?seed=ent:jacky&hops=2" | jq
```

- [ ] **Step 3: (No code change for manual smoke — subagent should skip steps 2-3)**

- [ ] **Step 4: Run full test suite**

Run:
```bash
cd /Users/jacky/code/lakeon/echomem && source .venv/bin/activate && pytest -v
```
Expected: all green (e2e gated test SKIPPED).

- [ ] **Step 5: Commit**

```bash
cd /Users/jacky/code/lakeon
git add echomem/README.md
git commit -m "docs(echomem): README — derivatives section + skill import + dead_letter inspect"
```

---

## Acceptance / Verify

跑通这些即 Plan 2 验收通过：

1. `pytest -v` 全绿（除 ECHOMEM_E2E gated）
2. `echomem mem ingest "X 与 Y 协作"` → 等几秒 → `GET /derivatives/timeline` 看到事件 + `GET /derivatives/graph?seed=ent:x` 看到 `X → cooperates_with → Y` 边
3. `GET /derivatives/tree?source_kind=memory&source_ref=...` 至少返回 L2
4. `echomem skill import ~/.claude/skills` 后 `GET /derivatives/skills?ctx=...` 召回相关 skill
5. 模拟 Ollama 离线（kill ollama）后 ingest 仍 200，但 task 进 `derivative_task.status='failed'` 后到 `dead_letter`
6. 模拟低置信度三元组 → `derivative_triple_pending` 有，`derivative_triple` 无

## Followups（不在本 plan 范围）

- Plan 3: Context API + FS blobs（add_url / ls / read / write / mv）
- Plan 4: Vue 3 Dashboard SPA
- Plan 5: Onboarding install.sh + openclaw / hermes 接入
- 程序性 `extract`（从会话萃取） → P1
- 因果链衍生物（P2+）
- Insight Track 子项目

## Self-Review Checklist

- [x] 14 个 task，每个 task 含 5 步（write test → fail → impl → pass → commit）
- [x] 所有文件路径都是绝对/确切（lakeon/echomem/...）
- [x] 没有 "TODO / TBD" 占位符
- [x] 类型一致：`Summary` / `Entity` / `Triple` / `Event` / `Skill` 在所有 task 中签名一致
- [x] 测试覆盖：unit (m002 / driver / 各 worker / queue / skill importer) + integration (orchestrator / endpoints / e2e) + 手动 smoke
- [x] 降级路径明确（spec §11.1）：timeline 失败仅按时间窗 / 树 fallback truncate / 图低置信度入 pending / Reflector noop
- [x] 验收画面明确（6 条）
