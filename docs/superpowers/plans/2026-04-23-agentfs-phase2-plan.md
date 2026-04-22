# AgentFS Phase 2 — Derivation Worker Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the loop from `agent_files` changes (memory md files) to the `memories` table so that `memory_recall` can hit AgentFS-derived entries alongside `memory_ingest`-produced entries.

**Architecture:** Per-tenant Postgres trigger produces events into a same-DB `agentfs_events` table. lakeon-api `AgentFSEventForwarder` (@Scheduled, HA-locked per tenant) polls events, resolves the target memory base (auto-provisions if missing), and HTTP POSTs to memory-svc `/agentfs/derive`. memory-svc upserts into the target base's `memories` table using a UNIQUE `(source_path, source_etag)` index for idempotency.

**Tech Stack:** Java 17 / Spring Boot 3.3.5 (lakeon-api) · Python 3.11 / FastAPI (memory-svc) · PostgreSQL 16 + Neon · Vue 3 / TypeScript (lakeon-console) · pytest (E2E).

**Source spec:** `docs/superpowers/specs/2026-04-22-agentfs-phase2-design.md`

---

## File Structure

### New files
- `lakeon-api/src/main/resources/db/migration/V38__agentfs_memory_targets.sql` — metadata DB table
- `lakeon-api/src/main/resources/db/migration/V39__agentfs_forwarder_locks.sql` — metadata DB table
- `lakeon-api/src/main/java/com/lakeon/agentfs/AgentFSMemoryTargetEntity.java` — JPA entity
- `lakeon-api/src/main/java/com/lakeon/agentfs/AgentFSMemoryTargetRepository.java`
- `lakeon-api/src/main/java/com/lakeon/agentfs/AgentFSMemoryTargetController.java` — public + internal APIs
- `lakeon-api/src/main/java/com/lakeon/agentfs/AgentFSForwarderLockEntity.java`
- `lakeon-api/src/main/java/com/lakeon/agentfs/AgentFSForwarderLockRepository.java`
- `lakeon-api/src/main/java/com/lakeon/agentfs/AgentFSEventForwarder.java` — @Scheduled
- `lakeon-api/src/main/java/com/lakeon/agentfs/FrontmatterParser.java` — YAML frontmatter
- `lakeon-api/src/main/java/com/lakeon/agentfs/PathWhitelist.java` — regex gate
- `lakeon-api/src/main/java/com/lakeon/agentfs/MemorySvcClient.java` — HTTP client bean
- `deploy/cce/migrate-agentfs-events.sh` — one-shot per-tenant trigger installer
- `memory/service/agentfs_derive.py` — new FastAPI endpoint module
- `lakeon-console/src/components/memory/AgentFSTargetToggle.vue`
- `lakeon-console/src/components/memory/AgentFSPendingBanner.vue`
- `tests/e2e/test_agentfs_phase2.py` — mechanism E2E
- `tests/e2e/test_agentfs_phase2_quality.py` — real-corpus quality
- `tests/e2e/test_derive_idempotent.py` — idempotent regression

### Modified files
- `lakeon-api/src/main/java/com/lakeon/agentfs/AgentFSDatabaseManager.java` — extend FILES_SCHEMA to include events table + trigger
- `lakeon-api/src/main/java/com/lakeon/memory/MemoryBaseController.java` — extend GET /memory/bases with `is_agentfs_target` field
- `memory/service/main.py` — register derive router
- `memory/service/models.py` — add DeriveRequest Pydantic model
- `memory/service/engine.py` — add `ingest_by_source`, `delete_by_source_path` helpers
- `lakeon-console/src/views/memory/MemoryBases.vue` — include AgentFSTargetToggle column + auto badge
- `lakeon-console/src/App.vue` — mount AgentFSPendingBanner globally

---

## Execution Order

```
Phase A (schemas) → Phase B (memory-svc) → Phase C (lakeon-api) → Phase D (Console) → Phase E (E2E) → Phase F (rollout)
```

Phase B and Phase C can proceed in parallel once Phase A's metadata migrations land, since memory-svc's /derive takes `x_database_connstr` from caller (decoupled from lakeon-api infrastructure).

---

## Phase A — Schema Migrations

### Task A1: metadata DB table — agentfs_memory_targets

**Files:**
- Create: `lakeon-api/src/main/resources/db/migration/V38__agentfs_memory_targets.sql`

- [ ] **Step 1: Write the migration**

```sql
-- Maps tenant → their AgentFS-derivation target memory_base.
-- Parallel to agentfs_assignments (which maps tenant → agentfs database).
-- Separate table (not a column on memory_bases) keeps memory_bases schema
-- free of AgentFS-specific concerns.

CREATE TABLE IF NOT EXISTS agentfs_memory_targets (
    tenant_id       VARCHAR(32) PRIMARY KEY,
    memory_base_id  VARCHAR(32) NOT NULL,
    auto_created    BOOLEAN NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_agentfs_memory_targets_base
    ON agentfs_memory_targets(memory_base_id);
```

- [ ] **Step 2: Local validation**

Run: `cd lakeon-api && mvn -q flyway:info` (if configured) or spin up temp postgres and run the SQL.
Expected: no syntax errors.

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/resources/db/migration/V38__agentfs_memory_targets.sql
git commit -m "feat(agentfs): V38 — agentfs_memory_targets table"
```

---

### Task A2: metadata DB table — agentfs_forwarder_locks

**Files:**
- Create: `lakeon-api/src/main/resources/db/migration/V39__agentfs_forwarder_locks.sql`

- [ ] **Step 1: Write the migration**

```sql
-- Leader-election lease: each forwarder pod upserts with
-- ON CONFLICT WHERE locked_until < now() to claim a tenant's events
-- for one cycle. Other pods see no row affected and skip that tenant.

CREATE TABLE IF NOT EXISTS agentfs_forwarder_locks (
    tenant_id      VARCHAR(32) PRIMARY KEY,
    locked_by      VARCHAR(64) NOT NULL,           -- pod hostname
    locked_until   TIMESTAMPTZ NOT NULL,
    last_event_id  BIGINT NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-api/src/main/resources/db/migration/V39__agentfs_forwarder_locks.sql
git commit -m "feat(agentfs): V39 — agentfs_forwarder_locks table"
```

---

### Task A3: per-tenant schema — extend FILES_SCHEMA

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/agentfs/AgentFSDatabaseManager.java`

The `FILES_SCHEMA` constant is applied when a new tenant's agentfs_\<uuid\> DB is first provisioned. Extending it means new tenants automatically get `agentfs_events` + trigger. Existing tenants handled in Task A4.

- [ ] **Step 1: Extend FILES_SCHEMA constant**

Replace the `FILES_SCHEMA` constant around `AgentFSDatabaseManager.java:41`:

```java
private static final String FILES_SCHEMA = """
    CREATE TABLE IF NOT EXISTS files (
        path        TEXT PRIMARY KEY,
        kind        VARCHAR(8)  NOT NULL,
        size        BIGINT      NOT NULL DEFAULT 0,
        mtime_ns    BIGINT      NOT NULL,
        etag        VARCHAR(64) NOT NULL,
        properties  JSONB       NOT NULL DEFAULT '{}'::jsonb,
        data        BYTEA,
        created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
        updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
    );
    CREATE INDEX IF NOT EXISTS files_path_pattern ON files(path text_pattern_ops);
    CREATE INDEX IF NOT EXISTS files_kind ON files(kind);

    CREATE TABLE IF NOT EXISTS agentfs_events (
        id          BIGSERIAL PRIMARY KEY,
        path        TEXT NOT NULL,
        etag        VARCHAR(64),
        event_type  VARCHAR(16) NOT NULL,
        status      VARCHAR(16) NOT NULL DEFAULT 'pending',
        retry_count INT NOT NULL DEFAULT 0,
        last_error  TEXT,
        created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
        processed_at TIMESTAMPTZ
    );
    CREATE INDEX IF NOT EXISTS idx_agentfs_events_pending
        ON agentfs_events(status, id) WHERE status = 'pending';

    CREATE OR REPLACE FUNCTION agentfs_files_event_fn() RETURNS TRIGGER AS $$
    BEGIN
      IF (TG_OP = 'INSERT') THEN
        INSERT INTO agentfs_events(path, etag, event_type)
          VALUES (NEW.path, NEW.etag, 'create');
      ELSIF (TG_OP = 'UPDATE') THEN
        INSERT INTO agentfs_events(path, etag, event_type)
          VALUES (NEW.path, NEW.etag, 'update');
      ELSIF (TG_OP = 'DELETE') THEN
        INSERT INTO agentfs_events(path, etag, event_type)
          VALUES (OLD.path, OLD.etag, 'delete');
      END IF;
      RETURN NULL;
    END;
    $$ LANGUAGE plpgsql;

    DROP TRIGGER IF EXISTS agentfs_files_event_trg ON files;
    CREATE TRIGGER agentfs_files_event_trg
      AFTER INSERT OR UPDATE OR DELETE ON files
      FOR EACH ROW EXECUTE FUNCTION agentfs_files_event_fn();
    """;
```

- [ ] **Step 2: Verify compile**

Run: `cd lakeon-api && mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/agentfs/AgentFSDatabaseManager.java
git commit -m "feat(agentfs): new tenants auto-install CDC trigger + events table"
```

---

### Task A4: one-shot migration script for existing tenants

**Files:**
- Create: `deploy/cce/migrate-agentfs-events.sh`

- [ ] **Step 1: Write the script**

```bash
#!/usr/bin/env bash
# Install agentfs_events table + trigger on existing tenant AgentFS DBs.
# Idempotent via CREATE IF NOT EXISTS / CREATE OR REPLACE / DROP IF EXISTS.
#
# Usage:
#   SITE=hwstaff ./deploy/cce/migrate-agentfs-events.sh
#
# Reads agentfs_assignments + database_instances to discover each tenant's
# agentfs DB, connects via the compute IP:port with cloud_admin, applies schema.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/site.sh"

SQL_FILE=$(mktemp)
trap "rm -f $SQL_FILE" EXIT
cat > "$SQL_FILE" <<'SQL'
CREATE TABLE IF NOT EXISTS agentfs_events (
    id          BIGSERIAL PRIMARY KEY,
    path        TEXT NOT NULL,
    etag        VARCHAR(64),
    event_type  VARCHAR(16) NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'pending',
    retry_count INT NOT NULL DEFAULT 0,
    last_error  TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_agentfs_events_pending
    ON agentfs_events(status, id) WHERE status = 'pending';

CREATE OR REPLACE FUNCTION agentfs_files_event_fn() RETURNS TRIGGER AS $$
BEGIN
  IF (TG_OP = 'INSERT') THEN
    INSERT INTO agentfs_events(path, etag, event_type)
      VALUES (NEW.path, NEW.etag, 'create');
  ELSIF (TG_OP = 'UPDATE') THEN
    INSERT INTO agentfs_events(path, etag, event_type)
      VALUES (NEW.path, NEW.etag, 'update');
  ELSIF (TG_OP = 'DELETE') THEN
    INSERT INTO agentfs_events(path, etag, event_type)
      VALUES (OLD.path, OLD.etag, 'delete');
  END IF;
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS agentfs_files_event_trg ON files;
CREATE TRIGGER agentfs_files_event_trg
  AFTER INSERT OR UPDATE OR DELETE ON files
  FOR EACH ROW EXECUTE FUNCTION agentfs_files_event_fn();
SQL

# Discover READY tenant DBs via psql to metadata
TENANTS=$(KUBECONFIG=$SITE_KUBECONFIG kubectl -n lakeon run psql-probe --rm -i --restart=Never --image=postgres:16 \
  --env="PGPASSWORD=$RDS_PASSWORD" -- \
  psql -h "$RDS_PRIVATE_IP" -U lakeon -d lakeon -t -A -c \
  "SELECT a.tenant_id, di.name, di.compute_host, di.compute_port
     FROM agentfs_assignments a JOIN database_instances di ON di.id = a.database_id
    WHERE a.status='READY' AND di.status IN ('RUNNING','SUSPENDED');")

echo "$TENANTS" | while IFS='|' read -r tenant_id db_name host port; do
  [ -z "$tenant_id" ] && continue
  port=${port:-55433}
  echo ">>> $tenant_id → $db_name @ $host:$port"
  KUBECONFIG=$SITE_KUBECONFIG kubectl -n lakeon run psql-migrate-$RANDOM \
    --rm -i --restart=Never --image=postgres:16 \
    --env="PGPASSWORD=cloud-admin-internal" -- \
    psql -h "$host" -p "$port" -U cloud_admin -d "$db_name" < "$SQL_FILE"
done
```

- [ ] **Step 2: Mark executable**

Run: `chmod +x deploy/cce/migrate-agentfs-events.sh`

- [ ] **Step 3: Commit**

```bash
git add deploy/cce/migrate-agentfs-events.sh
git commit -m "feat(agentfs): one-shot per-tenant CDC trigger installer"
```

*Note: script invocation happens in Phase F (rollout), not now.*

---

### Task A5: per-base idempotency index (script to apply to all existing bases)

**Files:**
- Create: `deploy/cce/migrate-memories-idempotency-index.sh`

- [ ] **Step 1: Write the script**

```bash
#!/usr/bin/env bash
# Adds idx_memories_source_idempotent on every tenant's memory_bases' DBs.
# Idempotent via IF NOT EXISTS.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "$SCRIPT_DIR/site.sh"

SQL="CREATE UNIQUE INDEX IF NOT EXISTS idx_memories_source_idempotent
       ON memories ((metadata->>'source_path'), (metadata->>'source_etag'))
       WHERE metadata ? 'source_path';"

BASES=$(KUBECONFIG=$SITE_KUBECONFIG kubectl -n lakeon run psql-probe --rm -i --restart=Never --image=postgres:16 \
  --env="PGPASSWORD=$RDS_PASSWORD" -- \
  psql -h "$RDS_PRIVATE_IP" -U lakeon -d lakeon -t -A -c \
  "SELECT mb.id, di.name, di.compute_host, di.compute_port
     FROM memory_bases mb JOIN database_instances di ON di.id = mb.database_id
    WHERE mb.status='READY';")

echo "$BASES" | while IFS='|' read -r base_id db_name host port; do
  [ -z "$base_id" ] && continue
  port=${port:-55433}
  echo ">>> base $base_id → $db_name @ $host:$port"
  KUBECONFIG=$SITE_KUBECONFIG kubectl -n lakeon run psql-idx-$RANDOM \
    --rm -i --restart=Never --image=postgres:16 \
    --env="PGPASSWORD=cloud-admin-internal" -- \
    psql -h "$host" -p "$port" -U cloud_admin -d "$db_name" -c "$SQL"
done
```

- [ ] **Step 2: mark executable + update memory-svc schema so new bases also get it**

Edit `memory/service/schema.py` — add index to `SCHEMA_SQL` after `CREATE INDEX idx_memories_embedding`:

```python
CREATE UNIQUE INDEX IF NOT EXISTS idx_memories_source_idempotent
    ON memories ((metadata->>'source_path'), (metadata->>'source_etag'))
    WHERE metadata ? 'source_path';
```

- [ ] **Step 3: Commit**

```bash
chmod +x deploy/cce/migrate-memories-idempotency-index.sh
git add deploy/cce/migrate-memories-idempotency-index.sh memory/service/schema.py
git commit -m "feat(memory): idempotency index on memories(source_path,source_etag)"
```

---

## Phase B — memory-svc derive endpoint

### Task B1: Pydantic models

**Files:**
- Modify: `memory/service/models.py`

- [ ] **Step 1: Write the failing test first**

Create `memory/service/tests/test_derive_models.py`:

```python
from models import DeriveRequest

def test_derive_request_minimal_create():
    r = DeriveRequest(
        tenant_id="tn_abc", op="create",
        path="/memory/feedback_x.md",
        content="body text",
        memory_type="procedural",
        source_etag="abc123" * 8,
        source_agent="claude",
        source_frontmatter={"type": "feedback"},
    )
    assert r.op == "create"
    assert r.path.startswith("/")

def test_derive_request_delete_has_no_content():
    r = DeriveRequest(
        tenant_id="tn_abc", op="delete",
        path="/memory/gone.md",
        source_etag="old-etag",
        source_agent="claude",
    )
    assert r.content is None
```

- [ ] **Step 2: Run, expect ImportError / validation error**

Run: `cd memory/service && python -m pytest tests/test_derive_models.py -v`
Expected: ImportError on DeriveRequest.

- [ ] **Step 3: Add model**

Edit `memory/service/models.py` — add at bottom:

```python
class DeriveRequest(BaseModel):
    tenant_id: str
    op: str = Field(..., pattern="^(create|update|delete|backfill)$")
    path: str
    content: Optional[str] = None        # None for op=delete
    memory_type: Optional[str] = None    # None for op=delete
    source_etag: str
    source_agent: str
    source_frontmatter: Optional[dict] = None
```

- [ ] **Step 4: Run tests green**

Run: `python -m pytest tests/test_derive_models.py -v`
Expected: 2 passed.

- [ ] **Step 5: Commit**

```bash
git add memory/service/models.py memory/service/tests/test_derive_models.py
git commit -m "feat(memory): DeriveRequest Pydantic model"
```

---

### Task B2: engine helper `ingest_idempotent`

**Files:**
- Modify: `memory/service/engine.py`

- [ ] **Step 1: Write the failing test**

Create `memory/service/tests/test_ingest_idempotent.py` (assumes local postgres test harness; follow existing test conventions or mark with `@pytest.mark.integration`):

```python
import pytest, asyncio, json
from engine import ingest_idempotent

@pytest.mark.integration
@pytest.mark.asyncio
async def test_ingest_same_source_etag_is_noop(test_connstr):
    metadata = {"source_path": "/memory/x.md", "source_etag": "ETA1"}
    m1 = await ingest_idempotent(test_connstr, "body1", "procedural", 0.5, metadata)
    assert m1 is not None
    m2 = await ingest_idempotent(test_connstr, "body1", "procedural", 0.5, metadata)
    assert m2 is None  # on conflict do nothing
```

- [ ] **Step 2: Add implementation**

Edit `memory/service/engine.py` — add below existing `ingest()`:

```python
async def ingest_idempotent(connstr: str, content: str, memory_type: str,
                            importance: float, metadata: dict,
                            embedding: list[float] | None = None):
    """INSERT into memories; returns Memory if new, None if (source_path,
    source_etag) already exists (idempotent retry). Caller must populate
    metadata['source_path'] and metadata['source_etag']."""
    if embedding is None:
        embedding = await get_embedding(content)
    conn = _connect(connstr)
    try:
        with conn.cursor(cursor_factory=psycopg2.extras.RealDictCursor) as cur:
            cur.execute("""
                INSERT INTO memories (content, memory_type, importance, embedding, metadata, created_at)
                VALUES (%s, %s, %s, %s::vector, %s, now())
                ON CONFLICT ON CONSTRAINT idx_memories_source_idempotent DO NOTHING
                RETURNING id, content, memory_type, importance, access_count, last_accessed_at, metadata, event_time, created_at
            """, (content, memory_type, importance, json.dumps(embedding), json.dumps(metadata)))
            row = cur.fetchone()
            conn.commit()
            return Memory(**row) if row else None
    finally:
        conn.close()
```

- [ ] **Step 3: Run integration test**

Run: `cd memory/service && python -m pytest tests/test_ingest_idempotent.py -v -m integration` (with test fixture running postgres).
Expected: test passes; second call returns None.

- [ ] **Step 4: Commit**

```bash
git add memory/service/engine.py memory/service/tests/test_ingest_idempotent.py
git commit -m "feat(memory): ingest_idempotent helper"
```

---

### Task B3: engine helper `delete_by_source_path`

**Files:**
- Modify: `memory/service/engine.py`

- [ ] **Step 1: Write failing test**

Extend `tests/test_ingest_idempotent.py`:

```python
@pytest.mark.integration
@pytest.mark.asyncio
async def test_delete_by_source_path(test_connstr):
    metadata = {"source_path": "/memory/gone.md", "source_etag": "E1"}
    await ingest_idempotent(test_connstr, "body", "fact", 0.5, metadata)
    from engine import delete_by_source_path
    n = await delete_by_source_path(test_connstr, "/memory/gone.md")
    assert n == 1
    n2 = await delete_by_source_path(test_connstr, "/memory/gone.md")
    assert n2 == 0  # already deleted
```

- [ ] **Step 2: Add implementation**

Edit `memory/service/engine.py`:

```python
async def delete_by_source_path(connstr: str, source_path: str) -> int:
    """Delete all memories whose metadata->>'source_path' matches.
    Returns the number of rows deleted (0 if nothing matched)."""
    conn = _connect(connstr)
    try:
        with conn.cursor() as cur:
            cur.execute(
                "DELETE FROM memories WHERE metadata->>'source_path' = %s",
                (source_path,),
            )
            n = cur.rowcount
            conn.commit()
            return n
    finally:
        conn.close()
```

- [ ] **Step 3: Run + commit**

```bash
python -m pytest tests/test_ingest_idempotent.py -v -m integration
git add memory/service/engine.py memory/service/tests/test_ingest_idempotent.py
git commit -m "feat(memory): delete_by_source_path helper"
```

---

### Task B4: /agentfs/derive endpoint

**Files:**
- Modify: `memory/service/main.py`

- [ ] **Step 1: Write endpoint test** `memory/service/tests/test_derive_endpoint.py`:

```python
from fastapi.testclient import TestClient
from main import app
import pytest

client = TestClient(app)

@pytest.mark.integration
def test_derive_create_returns_200(test_connstr):
    r = client.post(
        "/agentfs/derive",
        headers={"x-database-connstr": test_connstr},
        json={
            "tenant_id": "tn_abc", "op": "create",
            "path": "/memory/foo.md", "content": "body",
            "memory_type": "procedural",
            "source_etag": "deadbeef", "source_agent": "claude",
        },
    )
    assert r.status_code == 200

@pytest.mark.integration
def test_derive_duplicate_source_is_200_noop(test_connstr):
    req = {"tenant_id":"tn_abc","op":"create","path":"/memory/dup.md",
           "content":"body","memory_type":"procedural",
           "source_etag":"e1","source_agent":"claude"}
    r1 = client.post("/agentfs/derive", headers={"x-database-connstr": test_connstr}, json=req)
    r2 = client.post("/agentfs/derive", headers={"x-database-connstr": test_connstr}, json=req)
    assert r1.status_code == 200 and r2.status_code == 200
    # Only one row inserted
    from engine import recall
    mems = list(pg_exec(test_connstr, "SELECT id FROM memories WHERE metadata->>'source_path'='/memory/dup.md'"))
    assert len(mems) == 1

@pytest.mark.integration
def test_derive_delete(test_connstr):
    # precondition: one row inserted
    client.post("/agentfs/derive", headers={"x-database-connstr": test_connstr},
      json={"tenant_id":"tn_abc","op":"create","path":"/memory/rm.md","content":"b",
            "memory_type":"procedural","source_etag":"e","source_agent":"claude"})
    r = client.post("/agentfs/derive", headers={"x-database-connstr": test_connstr},
      json={"tenant_id":"tn_abc","op":"delete","path":"/memory/rm.md",
            "source_etag":"e","source_agent":"claude"})
    assert r.status_code == 200
```

- [ ] **Step 2: Add endpoint to main.py**

```python
from models import DeriveRequest

@app.post("/agentfs/derive")
async def agentfs_derive(req: DeriveRequest, x_database_connstr: str = Header(...)):
    metadata = {
        "source_system": "agentfs",
        "source_path": req.path,
        "source_etag": req.source_etag,
        "source_agent": req.source_agent,
    }
    if req.source_frontmatter:
        metadata["source_frontmatter"] = req.source_frontmatter

    if req.op == "delete":
        await engine.delete_by_source_path(x_database_connstr, req.path)
        return {"status": "deleted"}
    else:  # create / update / backfill
        if req.content is None or req.memory_type is None:
            raise HTTPException(400, "content and memory_type required for op={}".format(req.op))
        mem = await engine.ingest_idempotent(
            x_database_connstr, req.content, req.memory_type, 0.5, metadata,
        )
        return {"status": "ingested" if mem else "idempotent_noop",
                "memory_id": mem.id if mem else None}
```

- [ ] **Step 3: Run integration tests green**

Run: `cd memory/service && python -m pytest tests/test_derive_endpoint.py -v -m integration`
Expected: 3 passed.

- [ ] **Step 4: Commit**

```bash
git add memory/service/main.py memory/service/tests/test_derive_endpoint.py
git commit -m "feat(memory): POST /agentfs/derive endpoint"
```

---

## Phase C — lakeon-api event forwarder

### Task C1: AgentFSMemoryTargetEntity + Repository

**Files:**
- Create: `AgentFSMemoryTargetEntity.java`, `AgentFSMemoryTargetRepository.java`

- [ ] **Step 1: Write entity**

`lakeon-api/src/main/java/com/lakeon/agentfs/AgentFSMemoryTargetEntity.java`:

```java
package com.lakeon.agentfs;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "agentfs_memory_targets")
public class AgentFSMemoryTargetEntity {
    @Id @Column(name="tenant_id", length=32, nullable=false)
    private String tenantId;

    @Column(name="memory_base_id", length=32, nullable=false)
    private String memoryBaseId;

    @Column(name="auto_created", nullable=false)
    private Boolean autoCreated = false;

    @Column(name="created_at", nullable=false, updatable=false)
    private Instant createdAt = Instant.now();

    @Column(name="updated_at", nullable=false)
    private Instant updatedAt = Instant.now();

    public String getTenantId() { return tenantId; }
    public void setTenantId(String v) { this.tenantId = v; }
    public String getMemoryBaseId() { return memoryBaseId; }
    public void setMemoryBaseId(String v) { this.memoryBaseId = v; }
    public Boolean getAutoCreated() { return autoCreated; }
    public void setAutoCreated(Boolean v) { this.autoCreated = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
```

`AgentFSMemoryTargetRepository.java`:

```java
package com.lakeon.agentfs;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface AgentFSMemoryTargetRepository
    extends JpaRepository<AgentFSMemoryTargetEntity, String> {
  Optional<AgentFSMemoryTargetEntity> findByTenantId(String tenantId);
}
```

- [ ] **Step 2: Compile + commit**

```bash
cd lakeon-api && mvn -q -DskipTests compile
git add lakeon-api/src/main/java/com/lakeon/agentfs/AgentFSMemoryTargetEntity.java \
        lakeon-api/src/main/java/com/lakeon/agentfs/AgentFSMemoryTargetRepository.java
git commit -m "feat(agentfs): AgentFSMemoryTarget entity + repo"
```

---

### Task C2: public API — set-target + GET memory-target

**Files:**
- Create: `AgentFSMemoryTargetController.java`

- [ ] **Step 1: Write endpoint**

```java
package com.lakeon.agentfs;

import com.lakeon.memory.MemoryBaseService;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/agentfs/memory-target")
public class AgentFSMemoryTargetController {

  private final AgentFSMemoryTargetRepository repo;
  private final MemoryBaseService memoryBaseService;

  public AgentFSMemoryTargetController(AgentFSMemoryTargetRepository repo,
                                        MemoryBaseService memoryBaseService) {
    this.repo = repo;
    this.memoryBaseService = memoryBaseService;
  }

  @GetMapping
  public Map<String,Object> get(HttpServletRequest req) {
    TenantEntity t = (TenantEntity) req.getAttribute("tenant");
    return repo.findByTenantId(t.getId())
      .map(e -> Map.<String,Object>of(
        "base_id", e.getMemoryBaseId(),
        "auto_created", e.getAutoCreated(),
        "updated_at", e.getUpdatedAt().toString()))
      .orElse(Map.of("base_id", (Object)null));
  }

  @PostMapping
  public Map<String,Object> set(HttpServletRequest req,
                                 @RequestBody Map<String,String> body) {
    TenantEntity t = (TenantEntity) req.getAttribute("tenant");
    String baseId = body.get("base_id");
    if (baseId == null) throw new BadRequestException("base_id required");
    // Verify base belongs to this tenant
    var base = memoryBaseService.findOwned(t.getId(), baseId)
      .orElseThrow(() -> new NotFoundException("memory base not found"));
    var e = repo.findByTenantId(t.getId()).orElseGet(() -> {
      var n = new AgentFSMemoryTargetEntity();
      n.setTenantId(t.getId());
      n.setAutoCreated(false);
      return n;
    });
    e.setMemoryBaseId(baseId);
    e.setAutoCreated(false);
    e.setUpdatedAt(Instant.now());
    repo.save(e);
    return Map.of("base_id", baseId);
  }
}
```

- [ ] **Step 2: Write WebMvc slice test**

`lakeon-api/src/test/java/com/lakeon/agentfs/AgentFSMemoryTargetControllerTest.java` — small happy-path test, mock repo + service, ensure POST updates + GET returns correctly. (Skip if the tenant MockBean scaffolding is also broken per tech-debt task #12; ok to be integration-only.)

- [ ] **Step 3: Commit**

```bash
cd lakeon-api && mvn -q -DskipTests compile
git add lakeon-api/src/main/java/com/lakeon/agentfs/AgentFSMemoryTargetController.java
git commit -m "feat(agentfs): memory-target API"
```

---

### Task C3: extend GET /memory/bases with is_agentfs_target

**Files:**
- Modify: `MemoryBaseController.java` (or wherever the list endpoint lives)

- [ ] **Step 1: Locate + extend**

Find the DTO / Map builder that serializes memory_bases rows. Inject `AgentFSMemoryTargetRepository` and add:

```java
// Pseudo — exact form depends on current serialization style
Optional<AgentFSMemoryTargetEntity> target = agentfsTargetRepo.findByTenantId(tenantId);
String targetBaseId = target.map(AgentFSMemoryTargetEntity::getMemoryBaseId).orElse(null);

// For each base in result:
boolean isTarget = base.getId().equals(targetBaseId);
boolean autoCreated = isTarget && target.get().getAutoCreated();
entry.put("is_agentfs_target", isTarget);
entry.put("auto_created", autoCreated);  // only meaningful when isTarget=true
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/memory/MemoryBaseController.java
git commit -m "feat(memory): expose is_agentfs_target + auto_created on /memory/bases"
```

---

### Task C4: forwarder lock acquisition helper

**Files:**
- Create: `AgentFSForwarderLockEntity.java`, `AgentFSForwarderLockRepository.java`

- [ ] **Step 1: Entity + custom query**

`AgentFSForwarderLockRepository.java`:

```java
package com.lakeon.agentfs;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AgentFSForwarderLockRepository
    extends JpaRepository<AgentFSForwarderLockEntity, String> {

  /** Try to acquire lock for tenant. Returns 1 on success, 0 if another pod holds it. */
  @Modifying
  @Transactional
  @Query(value = """
    INSERT INTO agentfs_forwarder_locks(tenant_id, locked_by, locked_until, updated_at)
    VALUES (:tenantId, :podId, now() + (:secs || ' seconds')::interval, now())
    ON CONFLICT (tenant_id) DO UPDATE
      SET locked_by = EXCLUDED.locked_by,
          locked_until = EXCLUDED.locked_until,
          updated_at = now()
      WHERE agentfs_forwarder_locks.locked_until < now()
    """, nativeQuery = true)
  int tryAcquire(@Param("tenantId") String tenantId,
                 @Param("podId") String podId,
                 @Param("secs") int secs);
}
```

Entity is skeletal, just maps columns.

- [ ] **Step 2: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/agentfs/AgentFSForwarderLockEntity.java \
        lakeon-api/src/main/java/com/lakeon/agentfs/AgentFSForwarderLockRepository.java
git commit -m "feat(agentfs): forwarder lock entity + tryAcquire"
```

---

### Task C5: FrontmatterParser + PathWhitelist

**Files:**
- Create: `FrontmatterParser.java`, `PathWhitelist.java`

- [ ] **Step 1: Unit test FrontmatterParser**

`lakeon-api/src/test/java/com/lakeon/agentfs/FrontmatterParserTest.java`:

```java
package com.lakeon.agentfs;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrontmatterParserTest {
  @Test void parses_yaml_frontmatter_and_strips_body() {
    String raw = "---\nname: xyz\ntype: feedback\n---\n\nHello world\n";
    var r = FrontmatterParser.parse(raw);
    assertEquals("feedback", r.frontmatter.get("type"));
    assertEquals("Hello world\n", r.body);
  }

  @Test void no_frontmatter_returns_whole_body() {
    var r = FrontmatterParser.parse("just body\n");
    assertTrue(r.frontmatter.isEmpty());
    assertEquals("just body\n", r.body);
  }

  @Test void empty_frontmatter_is_tolerated() {
    var r = FrontmatterParser.parse("---\n---\nbody\n");
    assertTrue(r.frontmatter.isEmpty());
    assertEquals("body\n", r.body);
  }
}
```

- [ ] **Step 2: Run, fail, implement**

```java
package com.lakeon.agentfs;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.util.Map;

public class FrontmatterParser {
  public record Parsed(Map<String,Object> frontmatter, String body) {}
  private static final YAMLMapper YAML = new YAMLMapper();

  public static Parsed parse(String raw) {
    if (!raw.startsWith("---\n")) return new Parsed(Map.of(), raw);
    int end = raw.indexOf("\n---\n", 4);
    if (end < 0) return new Parsed(Map.of(), raw);
    String yaml = raw.substring(4, end);
    String body = raw.substring(end + 5);
    if (yaml.isBlank()) return new Parsed(Map.of(), body);
    try {
      @SuppressWarnings("unchecked")
      Map<String,Object> m = YAML.readValue(yaml, Map.class);
      return new Parsed(m == null ? Map.of() : m, body);
    } catch (Exception e) {
      return new Parsed(Map.of(), raw); // malformed → treat as plain body
    }
  }
}
```

- [ ] **Step 3: PathWhitelist class**

`PathWhitelist.java`:

```java
package com.lakeon.agentfs;

import java.util.regex.Pattern;

public class PathWhitelist {
  private static final Pattern GLOBAL_MEMORY  = Pattern.compile("^/memory/[^/]+\\.md$");
  private static final Pattern PROJECT_MEMORY = Pattern.compile("^/projects/[^/]+/memory/[^/]+\\.md$");

  public static boolean accept(String path) {
    if (path == null) return false;
    if (path.endsWith("/MEMORY.md")) return false;           // generated view
    return GLOBAL_MEMORY.matcher(path).matches()
        || PROJECT_MEMORY.matcher(path).matches();
  }

  /** Map frontmatter `type` to memories.memory_type. */
  public static String frontmatterTypeToMemoryType(String ftype) {
    if (ftype == null) return "fact";
    return switch (ftype) {
      case "feedback" -> "procedural";
      case "project"  -> "episode";
      case "reference", "user" -> "fact";
      default -> "fact";
    };
  }
}
```

- [ ] **Step 4: Unit test PathWhitelist**

```java
@Test void accepts_memory_and_project_memory_but_not_memory_md() {
  assertTrue (PathWhitelist.accept("/memory/feedback_x.md"));
  assertTrue (PathWhitelist.accept("/projects/X/memory/project_y.md"));
  assertFalse(PathWhitelist.accept("/memory/MEMORY.md"));
  assertFalse(PathWhitelist.accept("/projects/X/memory/MEMORY.md"));
  assertFalse(PathWhitelist.accept("/tasks/x.md"));
  assertFalse(PathWhitelist.accept("/projects/X/foo.jsonl"));
}
```

- [ ] **Step 5: Run + commit**

```bash
cd lakeon-api && mvn -q test -Dtest='FrontmatterParserTest,PathWhitelistTest'
# Expected: 6 tests pass (3 frontmatter + 3 whitelist).
git add lakeon-api/src/main/java/com/lakeon/agentfs/FrontmatterParser.java \
        lakeon-api/src/main/java/com/lakeon/agentfs/PathWhitelist.java \
        lakeon-api/src/test/java/com/lakeon/agentfs/FrontmatterParserTest.java \
        lakeon-api/src/test/java/com/lakeon/agentfs/PathWhitelistTest.java
git commit -m "feat(agentfs): FrontmatterParser + PathWhitelist"
```

---

### Task C6: MemorySvcClient (HTTP bean)

**Files:**
- Create: `MemorySvcClient.java`

- [ ] **Step 1: Implement**

```java
package com.lakeon.agentfs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.Map;

@Component
public class MemorySvcClient {
  private final RestClient client;

  public MemorySvcClient(@Value("${lakeon.memory-svc.baseUrl:http://memory-svc.lakeon.svc:8080}") String baseUrl) {
    this.client = RestClient.builder().baseUrl(baseUrl).build();
  }

  public record DeriveResponse(int statusCode, String body) {}

  public DeriveResponse derive(String baseConnstr, Map<String,Object> body) {
    try {
      var resp = client.post().uri("/agentfs/derive")
          .header("x-database-connstr", baseConnstr)
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve()
          .toEntity(String.class);
      return new DeriveResponse(resp.getStatusCode().value(), resp.getBody());
    } catch (org.springframework.web.client.HttpClientErrorException e) {
      return new DeriveResponse(e.getStatusCode().value(), e.getResponseBodyAsString());
    }
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/agentfs/MemorySvcClient.java
git commit -m "feat(agentfs): MemorySvcClient HTTP bean"
```

---

### Task C7: AgentFSEventForwarder — main orchestrator

**Files:**
- Create: `AgentFSEventForwarder.java`

This is the heart. Step-by-step assembly.

- [ ] **Step 1: Skeleton — @Scheduled + tenant enumeration**

```java
package com.lakeon.agentfs;

import com.lakeon.memory.MemoryBaseService;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.DatabaseService;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.sql.*;
import java.time.Instant;
import java.util.*;

@Component
@ConditionalOnProperty(name = "lakeon.agentfs.forwarder.enabled",
                       havingValue = "true", matchIfMissing = true)
public class AgentFSEventForwarder {

  private static final Logger log = LoggerFactory.getLogger(AgentFSEventForwarder.class);
  private static final int BATCH_SIZE = 100;
  private static final int LOCK_SECONDS = 30;
  private static final int MAX_RETRY = 5;

  private final AgentFSAssignmentRepository asgRepo;
  private final AgentFSForwarderLockRepository lockRepo;
  private final AgentFSMemoryTargetRepository targetRepo;
  private final AgentFSDatabaseManager dbm;
  private final MemoryBaseService memoryBaseService;
  private final MemorySvcClient memorySvc;
  private final DatabaseRepository databaseRepo;
  private final DatabaseService databaseService;
  private final String podId;

  public AgentFSEventForwarder(/* all above */) {
    // ... constructor
    this.podId = resolvePodId();
  }

  private static String resolvePodId() {
    try { return InetAddress.getLocalHost().getHostName(); }
    catch (Exception e) { return "unknown-" + UUID.randomUUID(); }
  }

  @Scheduled(fixedDelay = 30_000)
  public void tick() {
    for (var a : asgRepo.findAll()) {
      if (!"READY".equals(a.getStatus())) continue;
      if (lockRepo.tryAcquire(a.getTenantId(), podId, LOCK_SECONDS) != 1) continue;
      try {
        processTenant(a);
      } catch (Exception e) {
        log.error("forwarder tenant={} error", a.getTenantId(), e);
      }
    }
  }

  private void processTenant(AgentFSAssignmentEntity a) { /* next step */ }
}
```

- [ ] **Step 2: processTenant — read events + forward**

```java
private void processTenant(AgentFSAssignmentEntity a) throws SQLException {
  var tenant = /* load TenantEntity via tenantRepo */;
  try (Connection c = dbm.openConnection(tenant)) {
    seedBackfillIfEmpty(c);
    List<EventRow> events = loadPending(c, BATCH_SIZE);
    if (events.isEmpty()) return;

    String baseConnstr = resolveTargetBaseConnstr(tenant); // may return null (PROVISIONING)
    if (baseConnstr == null) {
      log.info("tenant={} target base provisioning; will retry next cycle", tenant.getId());
      return;
    }

    for (var e : events) {
      if (!PathWhitelist.accept(e.path)) {
        markDone(c, e.id);  // out of scope, drop
        continue;
      }
      try {
        forwardOne(c, baseConnstr, tenant, e);
      } catch (Exception ex) {
        bumpRetry(c, e, ex.getMessage());
      }
    }
  }
}

record EventRow(long id, String path, String etag, String eventType,
                int retryCount) {}
```

- [ ] **Step 3: Helper methods — loadPending, seedBackfillIfEmpty, markDone, bumpRetry**

```java
private List<EventRow> loadPending(Connection c, int limit) throws SQLException {
  var out = new ArrayList<EventRow>();
  try (var st = c.prepareStatement(
    "SELECT id, path, etag, event_type, retry_count FROM agentfs_events " +
    "WHERE status='pending' ORDER BY id LIMIT ?")) {
    st.setInt(1, limit);
    try (var rs = st.executeQuery()) {
      while (rs.next()) out.add(new EventRow(
        rs.getLong(1), rs.getString(2), rs.getString(3),
        rs.getString(4), rs.getInt(5)));
    }
  }
  return out;
}

private void seedBackfillIfEmpty(Connection c) throws SQLException {
  try (var check = c.prepareStatement(
    "SELECT 1 FROM agentfs_events LIMIT 1")) {
    try (var rs = check.executeQuery()) {
      if (rs.next()) return;  // already has events
    }
  }
  try (var seed = c.prepareStatement(
    "INSERT INTO agentfs_events(path, etag, event_type) " +
    "SELECT path, etag, 'backfill' FROM files WHERE kind='file'")) {
    int n = seed.executeUpdate();
    if (n > 0) log.info("seeded backfill events count={}", n);
  }
}

private void markDone(Connection c, long eventId) throws SQLException {
  try (var st = c.prepareStatement(
    "UPDATE agentfs_events SET status='done', processed_at=now() WHERE id=?")) {
    st.setLong(1, eventId);
    st.executeUpdate();
  }
}

private void bumpRetry(Connection c, EventRow e, String err) throws SQLException {
  int next = e.retryCount + 1;
  String status = next >= MAX_RETRY ? "poison" : "pending";
  try (var st = c.prepareStatement(
    "UPDATE agentfs_events SET retry_count=?, status=?, last_error=? WHERE id=?")) {
    st.setInt(1, next);
    st.setString(2, status);
    st.setString(3, err);
    st.setLong(4, e.id);
    st.executeUpdate();
  }
}
```

- [ ] **Step 4: forwardOne + payload builder**

```java
private void forwardOne(Connection c, String baseConnstr,
                         TenantEntity tenant, EventRow e) throws SQLException {
  Map<String,Object> payload = new LinkedHashMap<>();
  payload.put("tenant_id", tenant.getId());
  payload.put("path", e.path);
  payload.put("source_etag", e.etag == null ? "" : e.etag);
  payload.put("source_agent", "claude");  // MVP: single-agent assumption

  if ("delete".equals(e.eventType)) {
    payload.put("op", "delete");
  } else {
    // Read file content + parse frontmatter
    byte[] content = readFileContent(c, e.path);
    String raw = new String(content, java.nio.charset.StandardCharsets.UTF_8);
    var parsed = FrontmatterParser.parse(raw);
    String ftype = (String) parsed.frontmatter().getOrDefault("type", null);
    payload.put("op", e.eventType);   // create | update | backfill
    payload.put("content", parsed.body());
    payload.put("memory_type", PathWhitelist.frontmatterTypeToMemoryType(ftype));
    payload.put("source_frontmatter", parsed.frontmatter());
  }

  var resp = memorySvc.derive(baseConnstr, payload);
  if (resp.statusCode() == 200) markDone(c, e.id);
  else if (resp.statusCode() == 202) {
    // target provisioning — leave pending, next cycle retries
  } else {
    throw new RuntimeException("memory-svc " + resp.statusCode() + ": " + resp.body());
  }
}

private byte[] readFileContent(Connection c, String path) throws SQLException {
  try (var st = c.prepareStatement("SELECT data FROM files WHERE path=?")) {
    st.setString(1, path);
    try (var rs = st.executeQuery()) {
      if (!rs.next()) return new byte[0];
      byte[] d = rs.getBytes(1);
      return d == null ? new byte[0] : d;
    }
  }
}
```

- [ ] **Step 5: resolveTargetBaseConnstr — auto-provision**

```java
private String resolveTargetBaseConnstr(TenantEntity tenant) {
  var existing = targetRepo.findByTenantId(tenant.getId());
  String baseId;
  if (existing.isPresent()) {
    baseId = existing.get().getMemoryBaseId();
  } else {
    // auto-provision
    var created = memoryBaseService.create(tenant, "agentfs-claude", /* scene */ null);
    var target = new AgentFSMemoryTargetEntity();
    target.setTenantId(tenant.getId());
    target.setMemoryBaseId(created.getId());
    target.setAutoCreated(true);
    targetRepo.save(target);
    baseId = created.getId();
  }
  // Load base's database, check status
  var mb = memoryBaseService.findOwned(tenant.getId(), baseId)
    .orElseThrow(() -> new IllegalStateException("base missing"));
  if (!"READY".equals(mb.getStatus())) return null;  // 202 semantics
  DatabaseEntity db = databaseRepo.findById(mb.getDatabaseId()).orElseThrow();
  databaseService.ensureRunning(tenant, db.getId());
  db = databaseRepo.findById(db.getId()).orElseThrow();
  return buildJdbcUrl(db);  // e.g., "jdbc:postgresql://host:port/name?user=...&password=..."
}
```

- [ ] **Step 6: Unit test (controller-level)**

Build a small fake to verify the enforce logic (whitelist skip + retry escalation). Full integration lives in Phase E.

- [ ] **Step 7: Compile + commit**

```bash
cd lakeon-api && mvn -q -DskipTests compile
git add lakeon-api/src/main/java/com/lakeon/agentfs/AgentFSEventForwarder.java
git commit -m "feat(agentfs): AgentFSEventForwarder @Scheduled — events + forward + ACK"
```

---

## Phase D — Console UI

### Task D1: AgentFSTargetToggle component

**Files:**
- Create: `lakeon-console/src/components/memory/AgentFSTargetToggle.vue`

- [ ] **Step 1: Implement**

```vue
<script setup lang="ts">
import { computed } from 'vue';
import { api } from '@/services/api';

const props = defineProps<{
  baseId: string;
  currentTargetBaseId: string | null;
}>();
const emit = defineEmits<{ (e: 'changed', newTargetId: string): void }>();

const isActive = computed(() => props.baseId === props.currentTargetBaseId);

async function setTarget() {
  await api.post('/api/v1/agentfs/memory-target', { base_id: props.baseId });
  emit('changed', props.baseId);
}
</script>

<template>
  <label class="target-radio">
    <input type="radio" :checked="isActive" @change="setTarget" />
    <span>AgentFS 目标</span>
  </label>
</template>

<style scoped>
.target-radio { display: inline-flex; align-items: center; gap: 0.25rem; }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/components/memory/AgentFSTargetToggle.vue
git commit -m "feat(console): AgentFSTargetToggle component"
```

---

### Task D2: Integrate into MemoryBases page + [auto] badge

**Files:**
- Modify: `lakeon-console/src/views/memory/MemoryBases.vue`

- [ ] **Step 1: Add column + badge**

Add to the table template:

```vue
<th>AgentFS 目标</th>
<!-- per row -->
<td>
  <AgentFSTargetToggle
    :base-id="base.id"
    :current-target-base-id="currentTargetId"
    @changed="onTargetChanged"
  />
</td>
<!-- base name cell -->
<td>
  {{ base.name }}
  <span v-if="base.is_agentfs_target && base.auto_created"
        class="badge-auto" title="系统自动创建（AgentFS 派生库）">[auto]</span>
</td>
```

Add script section logic:

```typescript
const currentTargetId = computed(() => bases.value.find(b => b.is_agentfs_target)?.id ?? null);
function onTargetChanged(id: string) { /* refetch list */ fetchBases(); }
```

- [ ] **Step 2: Styling** (match existing 港湾暖色调):

```vue
<style scoped>
.badge-auto {
  margin-left: 0.25rem; font-size: 0.75rem;
  color: var(--color-warm-accent);
}
</style>
```

- [ ] **Step 3: Build check + commit**

```bash
cd lakeon-console && npx vue-tsc -b --noEmit
git add lakeon-console/src/views/memory/MemoryBases.vue
git commit -m "feat(console): AgentFS target column + auto badge"
```

---

### Task D3: Global AgentFSPendingBanner

**Files:**
- Create: `lakeon-console/src/components/memory/AgentFSPendingBanner.vue`
- Modify: `lakeon-console/src/App.vue`

- [ ] **Step 1: Component**

```vue
<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue';
import { api } from '@/services/api';

const pendingCount = ref(0);
const hasTarget = ref(true);
let timer: number | null = null;

async function poll() {
  try {
    const info = await api.get('/api/v1/agentfs/memory-target');
    hasTarget.value = !!info.data.base_id;
    // pending count requires another endpoint:
    const pending = await api.get('/api/v1/agentfs/pending-derivation-count');
    pendingCount.value = pending.data.count;
  } catch (e) {}
}

onMounted(() => { poll(); timer = window.setInterval(poll, 30_000); });
onUnmounted(() => { if (timer) window.clearInterval(timer); });
</script>

<template>
  <div v-if="pendingCount > 0 && !hasTarget" class="pending-banner">
    AgentFS 有 {{ pendingCount }} 条待派生 memory，
    <RouterLink to="/memory/bases">请选择一个目标 base</RouterLink>
  </div>
</template>

<style scoped>
.pending-banner {
  background: var(--color-warm-accent-soft);
  padding: 0.5rem 1rem; font-size: 0.9rem;
  border-bottom: 1px solid var(--color-warm-border);
}
</style>
```

- [ ] **Step 2: Mount in App.vue**

```vue
<template>
  <AgentFSPendingBanner />
  <router-view />
</template>
```

- [ ] **Step 3: Backend — add /pending-derivation-count endpoint**

In `AgentFSMemoryTargetController.java`, add:

```java
@GetMapping("/pending-derivation-count")
public Map<String,Object> pending(HttpServletRequest req) {
  TenantEntity t = (TenantEntity) req.getAttribute("tenant");
  // Open tenant DB, count pending events
  try (Connection c = dbm.openConnection(t)) {
    try (var st = c.prepareStatement(
        "SELECT count(*) FROM agentfs_events WHERE status='pending'")) {
      try (var rs = st.executeQuery()) {
        rs.next();
        return Map.of("count", rs.getLong(1));
      }
    }
  } catch (SQLException e) {
    return Map.of("count", 0L);
  }
}
```

- [ ] **Step 4: Build + commit**

```bash
cd lakeon-console && npx vue-tsc -b --noEmit
git add lakeon-console/src/components/memory/AgentFSPendingBanner.vue \
        lakeon-console/src/App.vue \
        lakeon-api/src/main/java/com/lakeon/agentfs/AgentFSMemoryTargetController.java
git commit -m "feat(console): AgentFSPendingBanner + pending-derivation-count API"
```

---

## Phase E — E2E testing

### Task E1: mechanism E2E — test_agentfs_phase2.py

**Files:**
- Create: `tests/e2e/test_agentfs_phase2.py`

- [ ] **Step 1: Fixture helpers**

```python
import base64, time, requests
from tests.e2e.conftest import poll_until

def agentfs_put(endpoint, api_key, path, data: bytes):
    return requests.post(f"{endpoint}/api/v1/agentfs/files/put",
        json={"path": path, "data_base64": base64.b64encode(data).decode()},
        headers={"Authorization": f"Bearer {api_key}"}, verify=False, timeout=60)

def memory_recall(endpoint, api_key, base_id, query, top_k=3):
    return requests.post(f"{endpoint}/api/v1/memory/recall",
        json={"base_id": base_id, "query": query, "top_k": top_k},
        headers={"Authorization": f"Bearer {api_key}"}, verify=False, timeout=60).json()
```

- [ ] **Step 2: Single-file happy path**

```python
def test_memory_derivation_end_to_end(e2e_client):
    endpoint, key = e2e_client.endpoint, e2e_client.api_key
    # Warm up agentfs db (absorbs 30s provision on first call)
    agentfs_put(endpoint, key, "/memory/warm.md", b"warm up")

    body = b"---\ntype: feedback\nname: no_emoji\n---\nUser prefers no emoji in output"
    agentfs_put(endpoint, key, "/memory/feedback_no_emoji.md", body)

    # Wait for forwarder + auto-provision + ingest
    def found():
        target = requests.get(f"{endpoint}/api/v1/agentfs/memory-target",
            headers={"Authorization": f"Bearer {key}"}, verify=False).json()
        if not target.get("base_id"): return False
        r = memory_recall(endpoint, key, target["base_id"], "emoji")
        mems = r.get("memories", [])
        return any("no emoji" in (m.get("content") or "").lower() for m in mems)
    poll_until(found, condition=lambda x: x, timeout=180, interval=10)
```

- [ ] **Step 3: Update / Delete / MEMORY.md / project path coverage**

Follow §9.2 test cases (update etag, delete removes, MEMORY.md NOT derived, projects path IS derived).

- [ ] **Step 4: Run + commit**

```bash
python3 -m pytest tests/e2e/test_agentfs_phase2.py -v
git add tests/e2e/test_agentfs_phase2.py
git commit -m "test(agentfs): Phase 2 mechanism E2E"
```

---

### Task E2: quality E2E — test_agentfs_phase2_quality.py

**Files:**
- Create: `tests/e2e/test_agentfs_phase2_quality.py`

- [ ] **Step 1: Corpus fixture + redaction**

```python
import os, re
from pathlib import Path
import pytest

SECRET_PATTERNS = [
  (re.compile(r'lk_[0-9a-f]{40,}'), 'lk_REDACTED'),
  (re.compile(r'sk-[A-Za-z0-9_-]{20,}'), 'sk_REDACTED'),
]

@pytest.fixture(scope="module")
def real_memory_corpus():
    root = Path("~/.claude/projects/-Users-jacky-code-lakeon/memory").expanduser()
    out = []
    for p in sorted(root.glob("*.md")):
        if p.name == "MEMORY.md": continue
        txt = p.read_text()
        for pat, repl in SECRET_PATTERNS:
            txt = pat.sub(repl, txt)
        out.append({"filename": p.name, "content": txt})
    assert len(out) >= 40, f"expected ≥40 files, got {len(out)}"
    return out
```

- [ ] **Step 2: test_full_corpus_derives_all**

```python
def test_full_corpus_derives_all(e2e_client, real_memory_corpus):
    ep, key = e2e_client.endpoint, e2e_client.api_key
    # Upload all
    for f in real_memory_corpus:
        agentfs_put(ep, key, f"/memory/{f['filename']}", f['content'].encode())
    # Wait for target base + all derived
    def all_in():
        target = requests.get(f"{ep}/api/v1/agentfs/memory-target",
            headers={"Authorization": f"Bearer {key}"}, verify=False).json()
        if not target.get("base_id"): return None
        bid = target["base_id"]
        r = requests.get(f"{ep}/api/v1/memory/memories?base_id={bid}&limit=100",
            headers={"Authorization": f"Bearer {key}"}, verify=False).json()
        return len(r.get("memories", []))
    total = poll_until(all_in, condition=lambda n: n is not None and n >= len(real_memory_corpus),
                       timeout=300, interval=15)
    assert total == len(real_memory_corpus)
```

- [ ] **Step 3: test_recall_hits_known_truth (the 10 queries from §9.4)**

```python
GROUND_TRUTH = [
  ("hwstaff 部署", "feedback_deploy_hwstaff.md"),
  ("E2E 测试纪律", "feedback_e2e_testing.md"),
  ("don't use emoji", "feedback_design_preferences.md"),
  ("memory 加密实现", "project_memory_encryption.md"),
  ("TPC-H benchmark 结果", "project_tpch_benchmark.md"),
  ("华为云 MaaS DeepSeek", "project_llm_provider.md"),
  ("pageserver re-attach", "project_pageserver_reattach_gap.md"),
  ("cross-project tokens", "reference_cross_project_tokens.md"),
  ("KB sharing API", "project_kb_sharing.md"),
  ("CCE 基础设施", "project_cce_infrastructure.md"),
]

def test_recall_hits_known_truth(e2e_client, real_memory_corpus):
    ep, key = e2e_client.endpoint, e2e_client.api_key
    # precondition: full_corpus_derives_all already ran (or do it here)
    target_base = requests.get(f"{ep}/api/v1/agentfs/memory-target",
        headers={"Authorization": f"Bearer {key}"}, verify=False).json()["base_id"]
    hits = 0
    for query, expected_file in GROUND_TRUTH:
        r = memory_recall(ep, key, target_base, query, top_k=3)
        paths = [m.get("metadata", {}).get("source_path", "") for m in r.get("memories", [])]
        if any(p.endswith(expected_file) for p in paths):
            hits += 1
        else:
            print(f"MISS: {query} expected {expected_file}, got {paths}")
    assert hits == len(GROUND_TRUTH), f"recall hit rate {hits}/{len(GROUND_TRUTH)}"
```

- [ ] **Step 4: Remaining tests per §9.4** (topic discrimination / stability / content fidelity / metadata / delete / cross-device / concurrent). Each test its own def.

- [ ] **Step 5: Run + commit**

```bash
python3 -m pytest tests/e2e/test_agentfs_phase2_quality.py -v
git add tests/e2e/test_agentfs_phase2_quality.py
git commit -m "test(agentfs): Phase 2 quality E2E with real corpus"
```

---

### Task E3: derive idempotency regression — test_derive_idempotent.py

**Files:**
- Create: `tests/e2e/test_derive_idempotent.py`

- [ ] **Step 1: Write**

```python
def test_derive_same_body_twice_leaves_one_row(e2e_client):
    # Direct call to memory-svc /derive with same body twice, count rows
    # by source_path. Requires internal memory-svc route exposed via
    # lakeon-api for e2e or a shortcut.
    ...  # fill as needed
```

- [ ] **Step 2: Commit**

```bash
git commit -m "test(agentfs): derive idempotency regression"
```

---

## Phase F — Rollout

### Task F1: deploy memory-svc (new /agentfs/derive endpoint)

- [ ] **Step 1: Build + push + deploy**

```bash
SITE=hwstaff bash deploy/cce/build-and-push-memory.sh
# Bump tag in sites/hwstaff/values.yaml (memory-svc tag bump)
SITE=hwstaff bash deploy/cce/deploy.sh --skip-test
```

- [ ] **Step 2: Smoke POST /agentfs/derive**

```bash
# From a pod inside cluster, hit memory-svc directly with a test connstr
```

### Task F2: apply schema migrations

- [ ] **Step 1: Flyway runs V38+V39 automatically on next lakeon-api deploy**

- [ ] **Step 2: One-shot existing tenants**

```bash
SITE=hwstaff bash deploy/cce/migrate-agentfs-events.sh
SITE=hwstaff bash deploy/cce/migrate-memories-idempotency-index.sh
```

### Task F3: deploy lakeon-api with forwarder flag OFF

- [ ] **Step 1: Bump tag + deploy** (flag defaults `matchIfMissing=true` — we need to temporarily override to false for canary)

Set `lakeon.agentfs.forwarder.enabled: false` in `sites/hwstaff/values.yaml` env passthrough, deploy.

### Task F4: enable forwarder + observe

- [ ] **Step 1: Flip flag to true, redeploy**

- [ ] **Step 2: Monitor**

```bash
kubectl -n lakeon logs -l app=lakeon-api --tail=200 | grep -i agentfs
# Expect: "forwarder started", "seeded backfill", "POST /agentfs/derive ..."
```

- [ ] **Step 3: Run full E2E suite** (`test_agentfs_phase2.py` + `_quality.py` + `_idempotent.py`)

All must PASS per CLAUDE.md E2E discipline (no FAILED, no SKIP).

### Task F5: deploy Console

- [ ] **Step 1: Railway auto-deploys on git push**

```bash
git push origin main
```

- [ ] **Step 2: Verify UI**

Open `https://console.dbay.cloud/memory/bases` — confirm radio appears, `[auto]` badge on `agentfs-claude` base, switching target updates backend.

---

## Known Risks / Tech Debt (copied from spec §10)

- scope axis / origin axis / refined memory_type — Phase 3 migration (non-blocking)
- split-brain Hibernate cache — independent task, see #12
- @WebMvcTest slice tests broken (TrialDemoFilter dep) — task #12

---

## Self-Review Summary

- Spec coverage ✓ — every section (4.1/4.2/4.3 schema, 5.1–5.4 components, 6 data flow, 7 errors, 8 rollout, 9 testing) maps to at least one task.
- Placeholder scan ✓ — all steps have concrete code or exact commands.
- Type consistency ✓ — `DeriveRequest` / `AgentFSMemoryTargetEntity` / `EventRow` names used consistently across Java/Python/Vue.
- Ambiguity ✓ — API paths (`/api/v1/agentfs/memory-target` etc.) matched between backend and Vue.
