# KB Pipeline Improvements — Design Spec

## Summary

Four improvements to the knowledge base ingestion pipeline:

1. **Pipeline Monitor Tab** — SRE visibility into per-stage performance breakdown with resource metrics
2. **Delayed Compute Wake** — Move compute pod wake from pre-job to write-time, eliminating idle compute during parse/embed
3. **Smart Retry** — Automatic retry with error classification (transient/rate-limit/permanent)
4. **Correct Wake Time** — Compute pod wake is ~3s cold / ~100ms warm (no code change, reflected in monitor)

---

## 1. Pipeline Monitor Tab

### 1.1 Stage Definitions

7 timed stages per task:

| Stage | ID | Description | Data Source |
|-------|----|-------------|-------------|
| Job pod startup | `JOB_POD` | submitJob → job pod first execution | Java records submit time, job pod reports start time |
| File download | `DOWNLOAD` | Download from OBS | Job pod |
| Document parse | `PARSE` | Parse to markdown | Job pod |
| Chunking | `CHUNK` | Split + overlap | Job pod |
| Embedding | `EMBED` | Call embedding API | Job pod |
| Compute wake | `COMPUTE_WAKE` | Call connstr_refresh to wake compute pod | Job pod records before/after |
| DB write | `WRITE` | Write chunks to PG | Job pod |

### 1.2 Callback Protocol Extension

Current progress callback:
```json
{"token": "...", "status": "RUNNING", "result": {"progress": 0.5, "message": "..."}}
```

Extended with stages and metrics:
```json
{
  "token": "...",
  "status": "RUNNING",
  "result": {
    "progress": 0.5,
    "message": "Embedding chunks",
    "stages": {
      "JOB_POD":       {"started_at": "2026-03-28T10:00:00Z", "completed_at": "2026-03-28T10:00:03Z", "duration_ms": 3000},
      "DOWNLOAD":      {"started_at": "...", "completed_at": "...", "duration_ms": 1200},
      "PARSE":         {"started_at": "...", "completed_at": "...", "duration_ms": 3400},
      "CHUNK":         {"started_at": "...", "completed_at": "...", "duration_ms": 800},
      "EMBED":         {"started_at": "...", "duration_ms": null}
    },
    "metrics": {
      "file_size_bytes": 5242880,
      "parsed_markdown_chars": 128000,
      "chunks_count": 42,
      "embeddings_count": 42,
      "peak_memory_mb": 384,
      "stage_memory": {
        "DOWNLOAD": {"memory_mb": 120},
        "PARSE":    {"memory_mb": 384},
        "EMBED":    {"memory_mb": 210},
        "WRITE":    {"memory_mb": 150}
      }
    }
  }
}
```

Success callback includes all 7 stages completed + full metrics. Failure callback includes stages up to failure point + `error_category` + `failed_stage`.

### 1.3 Resource Metrics Collection (Python)

- **file_size_bytes**: `os.path.getsize()` after download
- **parsed_markdown_chars**: `len(markdown_text)` after parse
- **chunks_count / embeddings_count**: from processing results
- **Memory**: `resource.getrusage(RUSAGE_SELF).ru_maxrss` recorded at each stage boundary. Per-stage memory = delta between consecutive readings. Zero overhead (OS-level RSS, no tracemalloc).
- **peak_memory_mb**: max of all stage readings

### 1.4 Storage

No new table. Stages and metrics stored in existing `KbWriteTaskEntity.result` JSON field. Java `onJobCompleted()` saves the full result payload as-is.

### 1.5 Frontend — Pipeline Monitor Tab

Location: New tab "Pipeline Monitor" in `/knowledge` page (`KnowledgeList.vue`).

**Components:**
- **Aggregation panel** (top): Average per-stage duration, success rate, retry rate, avg processing time per MB, memory P50/P90/P99
- **Task table** (main): Columns — document name, status, file size, peak memory, total duration, created time. Filterable by status (QUEUED/RUNNING/SUCCEEDED/FAILED) and time range.
- **Expandable row detail**: Horizontal bar chart (Gantt-style) showing 7 stages with duration. Each stage bar shows duration label. Below chart: stage memory bar chart, chunks count, file size → chunks conversion ratio.
- **Retry indicator**: Status column shows "重试中 (2/3)" for retrying tasks. Expanded view shows each attempt's stages and failure reason.

### 1.6 Admin API Endpoints

- `GET /api/v1/admin/knowledge/pipeline/tasks` — Paginated task list with stages/metrics in result JSON. Filters: status, kb_id, time range.
- `GET /api/v1/admin/knowledge/pipeline/stats` — Aggregated stats: avg stage durations, success/failure/retry rates, memory percentiles, throughput (docs/hour).

---

## 2. Delayed Compute Wake

### 2.1 Current Flow
```
Java drain:  wakeAndGetPodAddress() → get connstr → submitJob(connstr)
Job pod:     download → parse → chunk → embed → connect(connstr) → write
```

Problem: Compute pod idles during parse/embed (potentially minutes), wastes resources, may get suspended by timeout.

### 2.2 New Flow
```
Java drain:  submitJob(connstr="", connstr_refresh_url=...)
Job pod:     download → parse → chunk → embed → GET connstr_refresh_url → write
                                                  ↑ compute wakes here
```

### 2.3 Java Changes

**`KbWriteQueue.executeHeavyweight()`:**
- Remove `wakeAndGetPodAddress()` call
- Set `database_connstr` to empty string in job params
- Keep `connstr_refresh_url` generation unchanged

**`JobController` connstr refresh endpoint (`GET /api/v1/jobs/{id}/connstr`):**
- Currently: returns cached pod address
- Change to: if compute pod not running → call `computeLifecycleService.wakeCompute(dbId)` → wait for ready (up to 120s) → return fresh connstr
- On timeout: return HTTP 503, job pod treats as TRANSIENT failure

### 2.4 Python Changes

**`writer.py` — `write_chunks()`:**
- Before connecting: if `connstr` is empty and `connstr_refresh_url` exists, call refresh URL first to obtain connstr
- This is a single GET call, not part of the retry loop
- If refresh call fails: raise exception → main.py catches → error_category=TRANSIENT

**`_connect_with_retry()`:**
- No change to existing retry logic (still 20 retries, still calls refresh on connection failure)

### 2.5 Scope

- Only affects heavyweight tasks (DOCUMENT_PARSE, BATCH_DOCUMENT_PARSE)
- Lightweight tasks (chunk CRUD) unchanged — they execute JDBC directly in drain, still wake compute in drain loop
- `connstr_refresh_url` becomes the sole mechanism for heavyweight tasks to get a live connstr

---

## 3. Smart Retry Mechanism

### 3.1 Error Categories

| Category | ID | Retry Strategy | Max Retries | Examples |
|----------|----|---------------|-------------|---------|
| Transient | `TRANSIENT` | Immediate re-queue | 3 | Pod crash/OOM, compute unreachable, network timeout, connstr refresh failure |
| Rate limit | `RATE_LIMIT` | Delayed re-queue (5 min) | 3 | Embedding API 429/413 |
| Permanent | `PERMANENT` | No retry | 0 | Parse failure, unsupported format, OBS file not found (404), invalid API key (401) |

### 3.2 Python Error Classification

`main.py` exception handling classifies by stage + exception type:

- **DOWNLOAD**: OBS 404 → PERMANENT; timeout/network → TRANSIENT
- **PARSE**: All parse exceptions → PERMANENT
- **CHUNK**: All exceptions → PERMANENT (shouldn't fail on valid input)
- **EMBED**: HTTP 429/413 → RATE_LIMIT; timeout → TRANSIENT; 401 → PERMANENT
- **WRITE**: Connection exhausted (20 retries) → TRANSIENT; SQL error → PERMANENT

Failure callback payload:
```json
{
  "token": "...",
  "status": "FAILED",
  "error": "Embedding API rate limited (429)",
  "error_category": "RATE_LIMIT",
  "result": {
    "stages": { "...completed stages..." },
    "failed_stage": "EMBED",
    "metrics": { "...collected so far..." }
  }
}
```

### 3.3 Database Schema Changes

```sql
ALTER TABLE kb_write_tasks ADD COLUMN retry_count INT DEFAULT 0;
ALTER TABLE kb_write_tasks ADD COLUMN max_retries INT DEFAULT 3;
ALTER TABLE kb_write_tasks ADD COLUMN error_category VARCHAR(32);
ALTER TABLE kb_write_tasks ADD COLUMN next_retry_at TIMESTAMPTZ;
```

### 3.4 Java Retry Logic

**`KbWriteQueue.onJobCompleted()` — on FAILED callback:**
```
1. Parse error_category from callback payload
2. Store errorCategory, failed_stage in task entity
3. Switch on category:
   PERMANENT → status=FAILED, sync document status to FAILED
   TRANSIENT → retryCount < maxRetries?
     YES → retryCount++, status=QUEUED, trigger drain
     NO  → status=FAILED, sync document status
   RATE_LIMIT → retryCount < maxRetries?
     YES → retryCount++, status=QUEUED, nextRetryAt=now+5min
     NO  → status=FAILED, sync document status
```

**`KbWriteQueue.detectStuckTasks()` — stuck RUNNING > 30min:**
- Treat as TRANSIENT (pod likely crashed without callback)
- Apply same retry logic: retryCount < maxRetries → re-queue, else FAILED

**New `@Scheduled` method `processDelayedRetries()`:**
- Runs every 60 seconds
- Finds tasks with status=QUEUED and nextRetryAt <= now
- Triggers drain for each task's databaseId

### 3.5 Document Status Sync

- During retry: document stays in `PROCESSING` status (not flipped to FAILED between attempts)
- Only on final failure (retries exhausted or PERMANENT): document status → FAILED with error message including attempt count and last error

---

## Risk Assessment

| Risk | Mitigation |
|------|-----------|
| connstr_refresh_url is single point for compute wake | TRANSIENT retry (3 attempts) covers temporary API unavailability |
| ru_maxrss only increases (no per-stage decrease visibility) | Acceptable — identifies memory-heavy stages. Stage deltas of 0 are benign. |
| Delayed retry tasks accumulate if embedding API is down for extended period | maxRetries=3 caps total attempts. After 3 failures, task is permanently FAILED. |
| Job pod callback lost (pod crash before sending) | detectStuckTasks catches these after 30min, treats as TRANSIENT |

---

## Files Changed

### Python (`knowledge/job/`)
- `main.py` — Stage timing, memory collection, error classification, extended callbacks
- `writer.py` — Pre-write connstr refresh (delayed wake), COMPUTE_WAKE stage timing
- `callback.py` — Extended payload with stages, metrics, error_category

### Java (`lakeon-api/`)
- `KbWriteTaskEntity.java` — New fields: retryCount, maxRetries, errorCategory, nextRetryAt
- `KbWriteQueue.java` — Remove pre-wake from executeHeavyweight, retry logic in onJobCompleted, processDelayedRetries scheduler, detectStuckTasks retry
- `JobController.java` — connstr refresh endpoint adds wake logic
- `AdminController.java` — Pipeline monitor API endpoints (task list, aggregated stats)

### Frontend (`lakeon-admin/`)
- `KnowledgeList.vue` — Add Pipeline Monitor tab
- New component: pipeline monitor with task table, Gantt chart, metrics, aggregation panel

### Database Migration
- 4 new columns on `kb_write_tasks` table
