# KB Pipeline Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add pipeline performance monitoring, delayed compute wake, and smart retry to the KB ingestion pipeline.

**Architecture:** Extend existing job callback protocol with stage timing + resource metrics. Move compute wake from pre-job to write-time via connstr_refresh_url. Add error classification in Python and retry logic in Java. New Pipeline Monitor tab in admin KB page.

**Tech Stack:** Java 17 / Spring Boot (backend), Python 3 (job pod), Vue 3 / TypeScript (admin frontend)

**Spec:** `docs/superpowers/specs/2026-03-28-kb-pipeline-improvements.md`

---

### Task 1: Add retry fields to KbWriteTaskEntity

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteTaskEntity.java:55`
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteTaskRepository.java:27-32`

- [ ] **Step 1: Add new fields to KbWriteTaskEntity**

After the existing `completedAt` field (line 55), add:

```java
    @Column(name = "retry_count")
    private int retryCount = 0;

    @Column(name = "max_retries")
    private int maxRetries = 3;

    @Column(name = "error_category")
    private String errorCategory;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;
```

Add getters and setters for all four fields.

- [ ] **Step 2: Add repository query methods**

Add to `KbWriteTaskRepository.java`:

```java
@Query("SELECT t FROM KbWriteTaskEntity t WHERE t.status = 'QUEUED' AND t.nextRetryAt IS NOT NULL AND t.nextRetryAt <= :now")
List<KbWriteTaskEntity> findDelayedRetryReady(@Param("now") Instant now);
```

- [ ] **Step 3: Add database migration SQL**

The app uses Hibernate auto-DDL (`spring.jpa.hibernate.ddl-auto=update`), so new nullable columns with defaults will be added automatically. No manual migration script needed — Hibernate handles `ALTER TABLE ADD COLUMN` on startup.

Verify by checking `application.yml` confirms `ddl-auto: update`.

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteTaskEntity.java lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteTaskRepository.java
git commit -m "feat(kb): add retry fields to KbWriteTaskEntity — retryCount, maxRetries, errorCategory, nextRetryAt"
```

---

### Task 2: Python stage timing and metrics infrastructure

**Files:**
- Modify: `knowledge/job/callback.py`
- Modify: `knowledge/job/main.py`

- [ ] **Step 1: Extend callback.py with stages/metrics support**

Add a `StageTracker` class and update all callback functions in `callback.py`:

```python
import os
import sys
import json
import time
import resource
import logging
import requests

logger = logging.getLogger(__name__)


class StageTracker:
    """Tracks per-stage timing and memory for pipeline monitoring."""

    def __init__(self):
        self.stages = {}
        self._current_stage = None
        self._stage_start = None
        self._stage_memory_start = None
        self.metrics = {}

    def _get_memory_mb(self):
        """Get current RSS in MB via getrusage (zero overhead)."""
        # ru_maxrss is in KB on Linux, bytes on macOS
        usage = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
        if sys.platform == "darwin":
            return usage / (1024 * 1024)
        return usage / 1024

    def begin(self, stage_id):
        """Mark stage start."""
        if self._current_stage:
            self.end(self._current_stage)
        self._current_stage = stage_id
        self._stage_start = time.time()
        self._stage_memory_start = self._get_memory_mb()
        self.stages[stage_id] = {
            "started_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(self._stage_start)),
        }

    def end(self, stage_id=None):
        """Mark stage completion."""
        sid = stage_id or self._current_stage
        if sid and sid in self.stages:
            now = time.time()
            self.stages[sid]["completed_at"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(now))
            self.stages[sid]["duration_ms"] = int((now - self._stage_start) * 1000)
            mem_now = self._get_memory_mb()
            self.stages[sid]["memory_mb"] = round(mem_now, 1)
            if sid == self._current_stage:
                self._current_stage = None

    def set_metric(self, key, value):
        self.metrics[key] = value

    def build_result(self):
        """Build stages + metrics dict for callback payload."""
        peak = max((s.get("memory_mb", 0) for s in self.stages.values()), default=0)
        stage_memory = {sid: {"memory_mb": s.get("memory_mb", 0)} for sid, s in self.stages.items() if "memory_mb" in s}
        return {
            "stages": dict(self.stages),
            "metrics": {
                **self.metrics,
                "peak_memory_mb": round(peak, 1),
                "stage_memory": stage_memory,
            },
        }
```

Update existing callback functions to accept optional `tracker` parameter:

```python
def report_success(chunks_count, quality_stats=None, tracker=None):
    if _is_exec_mode():
        return
    result = {"chunks_count": chunks_count}
    if quality_stats:
        result["quality_stats"] = quality_stats
    if tracker:
        result.update(tracker.build_result())
    _send_callback("SUCCEEDED", result=result)


def report_success_batch(documents, tracker=None):
    if _is_exec_mode():
        return
    result = {"documents": documents}
    if tracker:
        result.update(tracker.build_result())
    _send_callback("SUCCEEDED", result=result)


def report_failure(error, error_category="PERMANENT", failed_stage=None, tracker=None):
    if _is_exec_mode():
        return
    result = {}
    if tracker:
        result.update(tracker.build_result())
    if failed_stage:
        result["failed_stage"] = failed_stage
    _send_callback("FAILED", error=error, error_category=error_category, result=result if result else None)


def report_progress(message, progress=0, tracker=None):
    if _is_exec_mode():
        return
    result = {"progress": progress, "message": message}
    if tracker:
        result.update(tracker.build_result())
    _send_callback("RUNNING", result=result)
```

Update `_send_callback` to support `error_category` field:

```python
def _send_callback(status, result=None, error=None, error_category=None):
    url = os.environ.get("JOB_CALLBACK_URL")
    token = os.environ.get("JOB_CALLBACK_TOKEN")
    if not url or not token:
        return
    payload = {"token": token, "status": status}
    if result:
        payload["result"] = result
    if error:
        payload["error"] = error
    if error_category:
        payload["error_category"] = error_category
    try:
        requests.post(url, json=payload, timeout=10)
    except Exception as e:
        logger.warning(f"Callback failed: {e}")
```

- [ ] **Step 2: Verify callback.py changes by reading**

Read `callback.py` to confirm all functions updated correctly, no syntax errors.

- [ ] **Step 3: Commit**

```bash
git add knowledge/job/callback.py
git commit -m "feat(kb-job): add StageTracker and extend callbacks with stages/metrics/error_category"
```

---

### Task 3: Instrument main.py with stage tracking and error classification

**Files:**
- Modify: `knowledge/job/main.py`

- [ ] **Step 1: Add stage tracking to single document processing (main function, single mode, lines 225-298)**

Read `main.py` fully first. Then modify the single-document mode in `main()` to use StageTracker. The key changes:

```python
from callback import StageTracker, report_success, report_failure, report_progress

# At the start of main(), after params parsing:
tracker = StageTracker()
job_submitted_at = params.get("job_submitted_at")
if job_submitted_at:
    tracker.stages["JOB_POD"] = {
        "started_at": job_submitted_at,
        "completed_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "duration_ms": int((time.time() - _parse_iso(job_submitted_at)) * 1000),
    }
```

Add helper at top of file:

```python
def _parse_iso(iso_str):
    """Parse ISO timestamp to epoch seconds."""
    from datetime import datetime, timezone
    dt = datetime.fromisoformat(iso_str.replace("Z", "+00:00"))
    return dt.timestamp()
```

Wrap each processing stage with `tracker.begin()`/`tracker.end()`:

For single-document mode — wrap each stage:
```python
# Download
tracker.begin("DOWNLOAD")
report_progress("Downloading file", progress=0.1, tracker=tracker)
# ... existing download code ...
tracker.set_metric("file_size_bytes", os.path.getsize(tmp_path))
tracker.end("DOWNLOAD")

# Parse
tracker.begin("PARSE")
report_progress("Parsing document", progress=0.3, tracker=tracker)
# ... existing parse code ...
tracker.set_metric("parsed_markdown_chars", len(markdown_text))
tracker.end("PARSE")

# Chunk
tracker.begin("CHUNK")
report_progress("Chunking document", progress=0.5, tracker=tracker)
# ... existing chunk code ...
tracker.set_metric("chunks_count", len(chunks))
tracker.end("CHUNK")

# Embed
tracker.begin("EMBED")
report_progress("Embedding chunks", progress=0.7, tracker=tracker)
# ... existing embed code ...
tracker.set_metric("embeddings_count", len(all_embeddings))
tracker.end("EMBED")

# Write (COMPUTE_WAKE tracked inside writer.py, WRITE tracked here)
tracker.begin("WRITE")
report_progress("Writing to database", progress=0.9, tracker=tracker)
# ... existing write_chunks call ...
tracker.end("WRITE")

# Success
report_success(chunks_count=len(chunks), quality_stats=quality_stats, tracker=tracker)
```

- [ ] **Step 2: Add error classification to the exception handler**

Replace the existing `except Exception` block in main() with error classification:

```python
except Exception as e:
    error_msg = str(e)
    error_category = "PERMANENT"
    failed_stage = tracker._current_stage if tracker else None

    # Classify by stage and error type
    if failed_stage == "DOWNLOAD":
        if "404" in error_msg or "NoSuchKey" in error_msg:
            error_category = "PERMANENT"
        else:
            error_category = "TRANSIENT"
    elif failed_stage == "PARSE":
        error_category = "PERMANENT"
    elif failed_stage == "CHUNK":
        error_category = "PERMANENT"
    elif failed_stage == "EMBED":
        if "429" in error_msg or "413" in error_msg or "rate" in error_msg.lower():
            error_category = "RATE_LIMIT"
        elif "401" in error_msg or "403" in error_msg:
            error_category = "PERMANENT"
        else:
            error_category = "TRANSIENT"
    elif failed_stage == "WRITE":
        if "OperationalError" in error_msg or "connection" in error_msg.lower():
            error_category = "TRANSIENT"
        else:
            error_category = "PERMANENT"

    if tracker and tracker._current_stage:
        tracker.end()

    logger.error(f"Job failed at stage {failed_stage}: {error_msg}", exc_info=True)
    report_failure(error_msg, error_category=error_category, failed_stage=failed_stage, tracker=tracker)
    sys.exit(1)
```

- [ ] **Step 3: Add same tracking to process_single_document for batch mode**

Update `process_single_document()` (lines 95-165) to accept and use a StageTracker. For batch mode, create a new tracker per document. The batch `report_success_batch()` call uses the outer tracker.

```python
def process_single_document(s3, obs_bucket, doc_params, database_connstr,
                            embedding_api_url, embedding_api_key, embedding_model,
                            doc_index=None, total_docs=None, tracker=None):
    if tracker is None:
        tracker = StageTracker()
    # Same begin/end pattern as single mode for each stage
    # ... (same wrapping pattern as step 1)
```

- [ ] **Step 4: Verify by reading main.py**

Read the modified file to confirm no syntax issues and all stages tracked.

- [ ] **Step 5: Commit**

```bash
git add knowledge/job/main.py
git commit -m "feat(kb-job): instrument pipeline with stage timing, memory metrics, and error classification"
```

---

### Task 4: Delayed compute wake — Python side

**Files:**
- Modify: `knowledge/job/writer.py`

- [ ] **Step 1: Read writer.py current state**

Read full `knowledge/job/writer.py`.

- [ ] **Step 2: Add pre-write connstr refresh and COMPUTE_WAKE timing**

Modify `write_chunks()` to handle empty connstr by calling connstr_refresh_url first, and track COMPUTE_WAKE stage:

```python
def write_chunks(connstr, document_id, chunks, embeddings, connstr_refresh_url=None, tracker=None):
    """Write chunks to knowledge database. If connstr is empty, fetches it via connstr_refresh_url first."""

    # Delayed wake: if connstr is empty, fetch it now (this wakes compute pod)
    if (not connstr or connstr.strip() == "") and connstr_refresh_url:
        if tracker:
            tracker.end()  # end previous stage (EMBED)
            tracker.begin("COMPUTE_WAKE")
        logger.info("No initial connstr, calling connstr_refresh_url to wake compute pod")
        try:
            resp = requests.get(connstr_refresh_url, timeout=180)
            resp.raise_for_status()
            connstr = resp.json().get("connstr", "")
            if not connstr:
                raise RuntimeError("connstr_refresh_url returned empty connstr")
        except Exception as e:
            if tracker:
                tracker.end("COMPUTE_WAKE")
            raise RuntimeError(f"Failed to obtain connstr via refresh: {e}")
        if tracker:
            tracker.end("COMPUTE_WAKE")
            tracker.begin("WRITE")
    elif tracker and "WRITE" not in tracker.stages:
        tracker.begin("WRITE")

    conn = _connect_with_retry(connstr, connstr_refresh_url=connstr_refresh_url)
    # ... rest of existing write logic unchanged ...
```

Add `import requests` at top of writer.py if not already present.

Update the function signature in all call sites in `main.py` to pass `tracker=tracker`:

```python
write_chunks(database_connstr, document_id, chunks, all_embeddings,
             connstr_refresh_url=connstr_refresh_url, tracker=tracker)
```

- [ ] **Step 3: Verify by reading writer.py**

Read the modified file to confirm correctness.

- [ ] **Step 4: Commit**

```bash
git add knowledge/job/writer.py knowledge/job/main.py
git commit -m "feat(kb-job): delayed compute wake — fetch connstr via refresh URL before write stage"
```

---

### Task 5: Delayed compute wake — Java side (remove pre-wake, enhance refresh endpoint)

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteQueue.java:439-476`
- Modify: JobCallbackController connstr refresh endpoint

- [ ] **Step 1: Read KbWriteQueue.executeHeavyweight (lines 439-476)**

Read `KbWriteQueue.java` lines 430-480.

- [ ] **Step 2: Remove pre-wake from executeHeavyweight**

In `executeHeavyweight()`, replace the wake + connstr building with empty connstr:

Remove these lines:
```java
String podAddress = wakeAndGetPodAddress(db);
String connstr = "postgresql://cloud_admin:cloud-admin-internal@" + podAddress + "/" + db.getName();
```

Replace with:
```java
String connstr = "";  // Delayed wake: job pod will call connstr_refresh_url before writing
```

Keep the rest of the method unchanged — `connstr_refresh_url` generation stays.

Also add `job_submitted_at` to params for JOB_POD stage timing:

```java
params.put("job_submitted_at", java.time.Instant.now().toString());
```

- [ ] **Step 3: Read JobCallbackController connstr refresh endpoint**

Read the connstr refresh endpoint to understand current implementation.

- [ ] **Step 4: Enhance connstr refresh endpoint to wake compute pod**

In the connstr refresh handler, add wake logic:

```java
@GetMapping("/{id}/connstr")
public ResponseEntity<?> getConnstr(@PathVariable String id, @RequestParam String token) {
    // ... existing auth validation ...

    String databaseId = // ... extract from job params ...
    DatabaseEntity db = databaseRepository.findById(databaseId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

    // Wake compute pod if not running (this is the delayed wake)
    try {
        computeLifecycleService.wakeCompute(databaseId);
        String podName = "compute-" + databaseId.replace("_", "-");
        computePodManager.waitForPodReady(podName, 120_000);
        String podIp = computePodManager.getPodIp(podName);
        String connstr = "postgresql://cloud_admin:cloud-admin-internal@" + podIp + ":55433/" + db.getName();
        return ResponseEntity.ok(Map.of("connstr", connstr));
    } catch (Exception e) {
        log.error("Failed to wake compute pod for connstr refresh: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of("error", "Compute pod wake failed: " + e.getMessage()));
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteQueue.java lakeon-api/src/main/java/com/lakeon/controller/JobCallbackController.java
git commit -m "feat(kb): delayed compute wake — remove pre-wake from drain, wake on connstr refresh"
```

---

### Task 6: Smart retry — Java side

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteQueue.java:177-198` (onJobCompleted)
- Modify: `lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteQueue.java:110-136` (detectStuckTasks)

- [ ] **Step 1: Read onJobCompleted and syncDocumentFromTask methods**

Read `KbWriteQueue.java` lines 170-250.

- [ ] **Step 2: Update onJobCompleted with retry logic**

Modify `onJobCompleted()` to parse error_category and apply retry strategy:

```java
public void onJobCompleted(String jobId, boolean success, String result, String error) {
    var taskOpt = taskRepository.findByJobId(jobId);
    if (taskOpt.isEmpty()) {
        log.warn("No task found for jobId: {}", jobId);
        return;
    }
    var task = taskOpt.get();

    if (success) {
        task.setStatus(KbWriteTaskStatus.SUCCEEDED);
        task.setResult(result);
        task.setCompletedAt(Instant.now());
        taskRepository.save(task);
        syncDocumentStatus(task, true);
    } else {
        // Parse error_category from callback
        String errorCategory = parseErrorCategory(error, result);
        task.setErrorCategory(errorCategory);
        task.setError(error);

        if (shouldRetry(task, errorCategory)) {
            // Re-queue for retry
            task.setRetryCount(task.getRetryCount() + 1);
            task.setStatus(KbWriteTaskStatus.QUEUED);
            task.setStartedAt(null);
            task.setJobId(null);
            if ("RATE_LIMIT".equals(errorCategory)) {
                task.setNextRetryAt(Instant.now().plusSeconds(300)); // 5 min delay
            } else {
                task.setNextRetryAt(null); // immediate
            }
            taskRepository.save(task);
            log.info("Task {} retry {}/{} (category={})", task.getId(), task.getRetryCount(), task.getMaxRetries(), errorCategory);
            // Document stays in PROCESSING during retry
        } else {
            // Final failure
            task.setStatus(KbWriteTaskStatus.FAILED);
            task.setCompletedAt(Instant.now());
            taskRepository.save(task);
            syncDocumentStatus(task, false);
        }
    }

    executor.submit(() -> drain(task.getDatabaseId()));
}

private String parseErrorCategory(String error, String resultJson) {
    // Try to extract error_category from the callback result JSON
    if (resultJson != null) {
        try {
            var node = objectMapper.readTree(resultJson);
            if (node.has("error_category")) {
                return node.get("error_category").asText();
            }
        } catch (Exception ignored) {}
    }
    // Fallback: if no category provided, treat as TRANSIENT
    return "TRANSIENT";
}

private boolean shouldRetry(KbWriteTaskEntity task, String errorCategory) {
    if ("PERMANENT".equals(errorCategory)) return false;
    return task.getRetryCount() < task.getMaxRetries();
}
```

Note: Also need to update the callback handler that calls `onJobCompleted()` to pass through `error_category`. Read `JobCallbackController.handleCallback` to see how it currently calls onJobCompleted. The `error_category` from the callback payload needs to be forwarded — either pass it as a parameter, or include it in the `result` JSON that gets stored on the task.

Simplest approach: store `error_category` in the result JSON from the callback, then `parseErrorCategory` extracts it.

- [ ] **Step 3: Update detectStuckTasks with retry logic**

Replace the existing stuck task handling with retry-aware logic:

```java
@Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
public void detectStuckTasks() {
    Instant cutoff = Instant.now().minusSeconds(STUCK_TASK_TIMEOUT_MINUTES * 60);
    var stuckTasks = taskRepository.findStuckRunningBefore(cutoff);
    for (var task : stuckTasks) {
        log.warn("Stuck task detected: {} (running since {})", task.getId(), task.getStartedAt());

        task.setErrorCategory("TRANSIENT");
        String error = "Job pod lost or timed out (>" + STUCK_TASK_TIMEOUT_MINUTES + "m)";
        task.setError(error);

        if (shouldRetry(task, "TRANSIENT")) {
            task.setRetryCount(task.getRetryCount() + 1);
            task.setStatus(KbWriteTaskStatus.QUEUED);
            task.setStartedAt(null);
            task.setJobId(null);
            task.setNextRetryAt(null);
            taskRepository.save(task);
            log.info("Stuck task {} re-queued for retry {}/{}", task.getId(), task.getRetryCount(), task.getMaxRetries());
        } else {
            task.setStatus(KbWriteTaskStatus.FAILED);
            task.setCompletedAt(Instant.now());
            taskRepository.save(task);
            syncDocumentStatus(task, false);
        }
        executor.submit(() -> drain(task.getDatabaseId()));
    }
}
```

- [ ] **Step 4: Add processDelayedRetries scheduler**

Add new method to KbWriteQueue:

```java
@Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
public void processDelayedRetries() {
    var readyTasks = taskRepository.findDelayedRetryReady(Instant.now());
    for (var task : readyTasks) {
        log.info("Delayed retry task {} now ready, triggering drain for db {}", task.getId(), task.getDatabaseId());
        executor.submit(() -> drain(task.getDatabaseId()));
    }
}
```

- [ ] **Step 5: Update drain() to respect nextRetryAt**

In the drain loop where it fetches the next QUEUED task, the repository query `findQueuedByDatabaseId` should skip tasks with future `nextRetryAt`. Update the repository query:

```java
@Query("SELECT t FROM KbWriteTaskEntity t WHERE t.databaseId = :dbId AND t.status = 'QUEUED' AND (t.nextRetryAt IS NULL OR t.nextRetryAt <= CURRENT_TIMESTAMP) ORDER BY t.createdAt ASC")
List<KbWriteTaskEntity> findQueuedByDatabaseId(@Param("dbId") String databaseId);
```

- [ ] **Step 6: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteQueue.java lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteTaskRepository.java
git commit -m "feat(kb): smart retry — error classification, transient/rate-limit/permanent retry strategies"
```

---

### Task 7: Update JobCallbackController to forward error_category

**Files:**
- Modify: JobCallbackController (the handleCallback method)

- [ ] **Step 1: Read JobCallbackController handleCallback**

Read the full JobCallbackController to see how callbacks are processed.

- [ ] **Step 2: Forward error_category from callback payload**

In the callback handler that receives job pod callbacks, extract `error_category` from the payload and include it when calling `onJobCompleted()`.

The simplest approach: store the entire callback result JSON (which now includes `error_category` from job pod) in the task's result field. The `parseErrorCategory()` method in KbWriteQueue already knows how to extract it.

Also handle the case where callback payload has top-level `error_category` field — merge it into result JSON before saving:

```java
// In handleCallback:
String errorCategory = payload.has("error_category") ? payload.get("error_category").asText() : null;
// Pass to onJobCompleted or store alongside result
```

Update `onJobCompleted` signature if needed, or keep the current approach where parseErrorCategory reads from the stored result/error.

- [ ] **Step 3: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/controller/JobCallbackController.java
git commit -m "feat(kb): forward error_category from job pod callback to retry logic"
```

---

### Task 8: Pipeline Monitor — Admin API endpoints

**Files:**
- Modify: `lakeon-api/src/main/java/com/lakeon/controller/AdminController.java`

- [ ] **Step 1: Read existing knowledge admin endpoints (lines 646-778)**

Read `AdminController.java` lines 640-780.

- [ ] **Step 2: Add pipeline task list endpoint**

Add after existing knowledge endpoints:

```java
@GetMapping("/knowledge/pipeline/tasks")
public Map<String, Object> getPipelineTasks(
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String kbId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size) {

    // Build query with filters
    var spec = Specification.<KbWriteTaskEntity>where(null);
    if (status != null) {
        spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), KbWriteTaskStatus.valueOf(status)));
    }
    if (kbId != null) {
        spec = spec.and((root, q, cb) -> cb.equal(root.get("kbId"), kbId));
    }
    if (from != null) {
        spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from));
    }
    if (to != null) {
        spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), to));
    }

    var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
    var tasks = taskRepository.findAll(spec, pageable);

    return Map.of(
        "tasks", tasks.getContent(),
        "total", tasks.getTotalElements(),
        "page", page,
        "size", size
    );
}
```

Note: This requires `KbWriteTaskRepository` to extend `JpaSpecificationExecutor<KbWriteTaskEntity>`. Check if it already does; if not, add it.

- [ ] **Step 3: Add pipeline stats endpoint**

```java
@GetMapping("/knowledge/pipeline/stats")
public Map<String, Object> getPipelineStats(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {

    Instant start = from != null ? from : Instant.now().minus(Duration.ofDays(7));
    Instant end = to != null ? to : Instant.now();

    var tasks = taskRepository.findByCreatedAtBetween(start, end);

    long total = tasks.size();
    long succeeded = tasks.stream().filter(t -> t.getStatus() == KbWriteTaskStatus.SUCCEEDED).count();
    long failed = tasks.stream().filter(t -> t.getStatus() == KbWriteTaskStatus.FAILED).count();
    long retried = tasks.stream().filter(t -> t.getRetryCount() > 0).count();

    // Parse stage durations from result JSON for succeeded tasks
    var stageDurations = new HashMap<String, List<Long>>();
    var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
    for (var task : tasks) {
        if (task.getStatus() != KbWriteTaskStatus.SUCCEEDED || task.getResult() == null) continue;
        try {
            var node = objectMapper.readTree(task.getResult());
            var stages = node.get("stages");
            if (stages == null) continue;
            stages.fields().forEachRemaining(entry -> {
                var dur = entry.getValue().get("duration_ms");
                if (dur != null && !dur.isNull()) {
                    stageDurations.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(dur.asLong());
                }
            });
        } catch (Exception ignored) {}
    }

    // Calculate avg per stage
    var avgStageDurations = new HashMap<String, Long>();
    stageDurations.forEach((stage, durations) -> {
        avgStageDurations.put(stage, durations.stream().mapToLong(Long::longValue).sum() / durations.size());
    });

    return Map.of(
        "total", total,
        "succeeded", succeeded,
        "failed", failed,
        "retried", retried,
        "success_rate", total > 0 ? (double) succeeded / total : 0,
        "retry_rate", total > 0 ? (double) retried / total : 0,
        "avg_stage_durations_ms", avgStageDurations,
        "period_start", start.toString(),
        "period_end", end.toString()
    );
}
```

Add to repository:

```java
List<KbWriteTaskEntity> findByCreatedAtBetween(Instant from, Instant to);
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-api/src/main/java/com/lakeon/controller/AdminController.java lakeon-api/src/main/java/com/lakeon/knowledge/KbWriteTaskRepository.java
git commit -m "feat(kb-admin): pipeline monitor API — task list with filters, aggregated stats"
```

---

### Task 9: Pipeline Monitor — Frontend tab

**Files:**
- Modify: `lakeon-admin/src/views/knowledge/KnowledgeList.vue`
- Modify: `lakeon-admin/src/api/admin.ts`

- [ ] **Step 1: Add API calls to admin.ts**

Read `lakeon-admin/src/api/admin.ts`, then add:

```typescript
pipelineTasks: (params?: { status?: string; kbId?: string; from?: string; to?: string; page?: number; size?: number }) =>
  client.get('/knowledge/pipeline/tasks', { params }),
pipelineStats: (params?: { from?: string; to?: string }) =>
  client.get('/knowledge/pipeline/stats', { params }),
```

- [ ] **Step 2: Read KnowledgeList.vue current state**

Read full `lakeon-admin/src/views/knowledge/KnowledgeList.vue`.

- [ ] **Step 3: Add Pipeline Monitor tab**

Add third tab to the tab-bar:

```html
<div class="tab-item" :class="{ active: activeTab === 'pipeline' }" @click="activeTab = 'pipeline'; loadPipeline()">Pipeline Monitor</div>
```

Add pipeline tab content after the existing tasks tab content:

```html
<!-- Pipeline Monitor Tab -->
<div v-if="activeTab === 'pipeline'" class="pipeline-tab">
  <!-- Aggregation Panel -->
  <div class="stats-cards" style="margin-bottom: 20px;">
    <div class="stat-card">
      <div class="stat-value">{{ pipelineStats.total || 0 }}</div>
      <div class="stat-label">总任务数</div>
    </div>
    <div class="stat-card">
      <div class="stat-value" style="color: #52c41a">{{ ((pipelineStats.success_rate || 0) * 100).toFixed(1) }}%</div>
      <div class="stat-label">成功率</div>
    </div>
    <div class="stat-card">
      <div class="stat-value" style="color: #e37318">{{ ((pipelineStats.retry_rate || 0) * 100).toFixed(1) }}%</div>
      <div class="stat-label">重试率</div>
    </div>
    <div class="stat-card" v-for="(ms, stage) in pipelineStats.avg_stage_durations_ms" :key="stage">
      <div class="stat-value">{{ formatDuration(ms) }}</div>
      <div class="stat-label">{{ stageLabels[stage] || stage }} 均值</div>
    </div>
  </div>

  <!-- Filters -->
  <div class="filter-bar" style="margin-bottom: 16px; display: flex; gap: 12px; align-items: center;">
    <select v-model="pipelineFilter.status" @change="loadPipelineTasks()" style="padding: 6px 12px;">
      <option value="">全部状态</option>
      <option value="QUEUED">排队中</option>
      <option value="RUNNING">运行中</option>
      <option value="SUCCEEDED">成功</option>
      <option value="FAILED">失败</option>
    </select>
    <input type="date" v-model="pipelineFilter.dateFrom" @change="loadPipelineTasks()" style="padding: 6px 12px;" />
    <span>—</span>
    <input type="date" v-model="pipelineFilter.dateTo" @change="loadPipelineTasks()" style="padding: 6px 12px;" />
  </div>

  <!-- Task Table -->
  <table class="data-table">
    <thead>
      <tr>
        <th style="width: 30px;"></th>
        <th>文档</th>
        <th>状态</th>
        <th>文件大小</th>
        <th>峰值内存</th>
        <th>总耗时</th>
        <th>重试</th>
        <th>创建时间</th>
      </tr>
    </thead>
    <tbody>
      <template v-for="task in pipelineTasks" :key="task.id">
        <tr @click="togglePipelineExpand(task.id)" style="cursor: pointer;">
          <td>{{ expandedPipeline === task.id ? '▼' : '▶' }}</td>
          <td>{{ getDocName(task) }}</td>
          <td>
            <span :class="'status-' + task.status.toLowerCase()">
              {{ task.retryCount > 0 && task.status === 'QUEUED' ? `重试中 (${task.retryCount}/${task.maxRetries})` : statusLabels[task.status] }}
            </span>
          </td>
          <td>{{ formatBytes(getMetric(task, 'file_size_bytes')) }}</td>
          <td>{{ getMetric(task, 'peak_memory_mb') ? getMetric(task, 'peak_memory_mb') + ' MB' : '-' }}</td>
          <td>{{ getTotalDuration(task) }}</td>
          <td>{{ task.retryCount > 0 ? task.retryCount + '/' + task.maxRetries : '-' }}</td>
          <td>{{ formatTime(task.createdAt) }}</td>
        </tr>
        <!-- Expanded detail row -->
        <tr v-if="expandedPipeline === task.id">
          <td colspan="8" style="padding: 16px 24px; background: #f9fafb;">
            <!-- Gantt chart -->
            <div class="stage-gantt" v-if="getStages(task)">
              <div class="gantt-title" style="font-weight: 600; margin-bottom: 8px;">阶段耗时分解</div>
              <div v-for="(stage, sid) in getStages(task)" :key="sid" class="gantt-row" style="display: flex; align-items: center; margin-bottom: 4px;">
                <div style="width: 120px; font-size: 13px; color: #666;">{{ stageLabels[sid] || sid }}</div>
                <div style="flex: 1; height: 20px; background: #f0f0f0; border-radius: 3px; position: relative;">
                  <div :style="{ width: ganttWidth(task, stage.duration_ms) + '%', background: stageColors[sid] || '#0073e6', height: '100%', borderRadius: '3px', minWidth: stage.duration_ms ? '2px' : '0' }"></div>
                </div>
                <div style="width: 80px; text-align: right; font-size: 13px; color: #333;">{{ stage.duration_ms != null ? formatDuration(stage.duration_ms) : '...' }}</div>
              </div>
            </div>
            <!-- Memory per stage -->
            <div v-if="getStageMemory(task)" style="margin-top: 12px;">
              <div style="font-weight: 600; margin-bottom: 8px;">阶段内存</div>
              <div v-for="(mem, sid) in getStageMemory(task)" :key="sid" style="display: flex; align-items: center; margin-bottom: 4px;">
                <div style="width: 120px; font-size: 13px; color: #666;">{{ stageLabels[sid] || sid }}</div>
                <div style="flex: 1; height: 16px; background: #f0f0f0; border-radius: 3px;">
                  <div :style="{ width: memBarWidth(task, mem.memory_mb) + '%', background: '#8b5cf6', height: '100%', borderRadius: '3px', minWidth: mem.memory_mb ? '2px' : '0' }"></div>
                </div>
                <div style="width: 80px; text-align: right; font-size: 13px;">{{ mem.memory_mb }} MB</div>
              </div>
            </div>
            <!-- Error info for failed tasks -->
            <div v-if="task.status === 'FAILED'" style="margin-top: 12px; padding: 8px 12px; background: #fef2f2; border-radius: 4px; color: #dc2626; font-size: 13px;">
              <strong>{{ task.errorCategory || 'UNKNOWN' }}</strong> @ {{ getFailedStage(task) || '?' }} — {{ task.error }}
            </div>
          </td>
        </tr>
      </template>
    </tbody>
  </table>

  <!-- Pagination -->
  <div style="margin-top: 16px; display: flex; justify-content: flex-end; gap: 8px;">
    <button @click="pipelinePage > 0 && (pipelinePage--, loadPipelineTasks())" :disabled="pipelinePage === 0" class="btn-secondary">上一页</button>
    <span style="padding: 6px 12px;">{{ pipelinePage + 1 }} / {{ Math.ceil(pipelineTotal / 50) || 1 }}</span>
    <button @click="(pipelinePage + 1) * 50 < pipelineTotal && (pipelinePage++, loadPipelineTasks())" :disabled="(pipelinePage + 1) * 50 >= pipelineTotal" class="btn-secondary">下一页</button>
  </div>
</div>
```

- [ ] **Step 4: Add script logic for pipeline tab**

Add to the `<script setup>` section:

```typescript
const pipelineStats = ref<any>({})
const pipelineTasks = ref<any[]>([])
const pipelineTotal = ref(0)
const pipelinePage = ref(0)
const expandedPipeline = ref<string | null>(null)
const pipelineFilter = ref({ status: '', dateFrom: '', dateTo: '' })

const stageLabels: Record<string, string> = {
  JOB_POD: 'Job Pod启动',
  DOWNLOAD: '文件下载',
  PARSE: '文档解析',
  CHUNK: '切片',
  EMBED: '嵌入',
  COMPUTE_WAKE: 'Compute唤醒',
  WRITE: '写入DB',
}

const stageColors: Record<string, string> = {
  JOB_POD: '#94a3b8',
  DOWNLOAD: '#3b82f6',
  PARSE: '#10b981',
  CHUNK: '#f59e0b',
  EMBED: '#8b5cf6',
  COMPUTE_WAKE: '#ec4899',
  WRITE: '#ef4444',
}

const statusLabels: Record<string, string> = {
  QUEUED: '排队中',
  RUNNING: '运行中',
  SUCCEEDED: '成功',
  FAILED: '失败',
}

async function loadPipeline() {
  await Promise.all([loadPipelineStats(), loadPipelineTasks()])
}

async function loadPipelineStats() {
  const params: any = {}
  if (pipelineFilter.value.dateFrom) params.from = new Date(pipelineFilter.value.dateFrom).toISOString()
  if (pipelineFilter.value.dateTo) params.to = new Date(pipelineFilter.value.dateTo).toISOString()
  const { data } = await adminApi.pipelineStats(params)
  pipelineStats.value = data
}

async function loadPipelineTasks() {
  const params: any = { page: pipelinePage.value, size: 50 }
  if (pipelineFilter.value.status) params.status = pipelineFilter.value.status
  if (pipelineFilter.value.dateFrom) params.from = new Date(pipelineFilter.value.dateFrom).toISOString()
  if (pipelineFilter.value.dateTo) params.to = new Date(pipelineFilter.value.dateTo).toISOString()
  const { data } = await adminApi.pipelineTasks(params)
  pipelineTasks.value = data.tasks || []
  pipelineTotal.value = data.total || 0
}

function togglePipelineExpand(id: string) {
  expandedPipeline.value = expandedPipeline.value === id ? null : id
}

function getStages(task: any) {
  try { return JSON.parse(task.result || '{}').stages } catch { return null }
}

function getStageMemory(task: any) {
  try { return JSON.parse(task.result || '{}').metrics?.stage_memory } catch { return null }
}

function getMetric(task: any, key: string) {
  try { return JSON.parse(task.result || '{}').metrics?.[key] } catch { return null }
}

function getDocName(task: any) {
  try {
    const params = JSON.parse(task.params || '{}')
    return params.filename || params.document_id || task.id?.substring(0, 8)
  } catch { return task.id?.substring(0, 8) }
}

function getFailedStage(task: any) {
  try { return JSON.parse(task.result || '{}').failed_stage } catch { return null }
}

function getTotalDuration(task: any) {
  const stages = getStages(task)
  if (!stages) return '-'
  const total = Object.values(stages).reduce((sum: number, s: any) => sum + (s.duration_ms || 0), 0)
  return formatDuration(total)
}

function ganttWidth(task: any, durationMs: number | null) {
  if (!durationMs) return 0
  const stages = getStages(task)
  if (!stages) return 0
  const max = Math.max(...Object.values(stages).map((s: any) => s.duration_ms || 0))
  return max > 0 ? (durationMs / max) * 100 : 0
}

function memBarWidth(task: any, memMb: number) {
  const peak = getMetric(task, 'peak_memory_mb')
  return peak > 0 ? (memMb / peak) * 100 : 0
}

function formatDuration(ms: number) {
  if (ms < 1000) return ms + 'ms'
  if (ms < 60000) return (ms / 1000).toFixed(1) + 's'
  return (ms / 60000).toFixed(1) + 'min'
}

function formatBytes(bytes: number | null) {
  if (!bytes) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1048576).toFixed(1) + ' MB'
}
```

- [ ] **Step 5: Add styles for pipeline tab**

Add to `<style scoped>`:

```css
.status-queued { color: #6b7280; }
.status-running { color: #0073e6; }
.status-succeeded { color: #52c41a; }
.status-failed { color: #e6393d; }
```

- [ ] **Step 6: Verify by reading the modified file**

Read the full modified KnowledgeList.vue to check for syntax issues and correct integration with existing tabs.

- [ ] **Step 7: Commit**

```bash
git add lakeon-admin/src/views/knowledge/KnowledgeList.vue lakeon-admin/src/api/admin.ts
git commit -m "feat(kb-admin): Pipeline Monitor tab — Gantt chart, memory metrics, retry status, filters"
```

---

### Task 10: End-to-end verification

- [ ] **Step 1: Build Java backend**

```bash
cd lakeon-api && ./mvnw compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 2: Build frontend**

```bash
cd lakeon-admin && npx vue-tsc -b --noEmit
```

Expected: No type errors

- [ ] **Step 3: Python syntax check**

```bash
cd knowledge/job && python -m py_compile main.py && python -m py_compile callback.py && python -m py_compile writer.py
```

Expected: No output (clean compilation)

- [ ] **Step 4: Review all changes**

```bash
git diff --stat main
```

Verify file list matches plan:
- `knowledge/job/callback.py` — StageTracker + extended callbacks
- `knowledge/job/main.py` — Stage instrumentation + error classification
- `knowledge/job/writer.py` — Delayed wake + COMPUTE_WAKE timing
- `KbWriteTaskEntity.java` — Retry fields
- `KbWriteTaskRepository.java` — New queries
- `KbWriteQueue.java` — Remove pre-wake, retry logic, delayed retry scheduler
- `JobCallbackController.java` — Wake on connstr refresh, forward error_category
- `AdminController.java` — Pipeline API endpoints
- `KnowledgeList.vue` — Pipeline Monitor tab
- `admin.ts` — New API calls

- [ ] **Step 5: Commit any remaining fixes**

```bash
git add -A && git commit -m "fix: address build issues from kb pipeline improvements"
```
