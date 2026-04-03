package com.lakeon.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.job.JobEntity;
import com.lakeon.job.JobService;
import com.lakeon.job.JobType;
import com.lakeon.k8s.ComputePodManager;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.ComputeLifecycleService;
import com.lakeon.service.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Core scheduler for KB write operations.
 * Lightweight tasks (chunk CRUD) execute serially per database via direct JDBC.
 * Heavyweight tasks (document parse) submit job pods that run concurrently — multiple
 * documents can be parsed/embedded in parallel, then write to the same compute pod
 * (PostgreSQL handles concurrent inserts to different document rows).
 */
@Service
public class KbWriteQueue {
    private static final Logger log = LoggerFactory.getLogger(KbWriteQueue.class);

    private final KbWriteTaskRepository taskRepository;
    private final ComputeLifecycleService computeLifecycleService;
    private final ComputePodManager computePodManager;
    private final DatabaseRepository databaseRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentRepository documentRepository;
    private final JobService jobService;
    private final SummaryService summaryService;
    private final LakeonProperties props;
    private final ObjectMapper objectMapper;
    @org.springframework.beans.factory.annotation.Value("${lakeon.job.callback-base-url:}")
    private String callbackBaseUrl;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final ConcurrentHashMap<String, ReentrantLock> dbLocks = new ConcurrentHashMap<>();

    // Lightweight task types that execute SQL directly (no job pod needed)
    private static final Set<KbWriteTaskType> LIGHTWEIGHT_TYPES = Set.of(
        KbWriteTaskType.EDIT_CHUNK,
        KbWriteTaskType.DELETE_CHUNK,
        KbWriteTaskType.CREATE_CHUNK,
        KbWriteTaskType.RECHUNK_ROLLBACK,
        KbWriteTaskType.DELETE_DOCUMENT_CHUNKS,
        KbWriteTaskType.DOCUMENT_SUMMARIZE,
        KbWriteTaskType.KB_SUMMARIZE
    );

    public KbWriteQueue(KbWriteTaskRepository taskRepository,
                         @Lazy ComputeLifecycleService computeLifecycleService,
                         ComputePodManager computePodManager,
                         DatabaseRepository databaseRepository,
                         KnowledgeBaseRepository knowledgeBaseRepository,
                         DocumentRepository documentRepository,
                         @Lazy JobService jobService,
                         SummaryService summaryService,
                         LakeonProperties props,
                         ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.computeLifecycleService = computeLifecycleService;
        this.computePodManager = computePodManager;
        this.databaseRepository = databaseRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentRepository = documentRepository;
        this.jobService = jobService;
        this.summaryService = summaryService;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    private static final long STUCK_TASK_TIMEOUT_MINUTES = 30;
    private static final long STUCK_SUMMARIZE_TIMEOUT_MINUTES = 5;

    /**
     * On startup, recover any databases that have QUEUED tasks sitting in the DB.
     * Without this, tasks submitted before a restart would never be drained.
     */
    @jakarta.annotation.PostConstruct
    public void recoverOnStartup() {
        try {
            List<String> dbIds = taskRepository.findDatabaseIdsWithActiveTasks();
            if (!dbIds.isEmpty()) {
                log.info("Recovering drain for {} databases with active tasks", dbIds.size());
                for (String dbId : dbIds) {
                    executor.submit(() -> drain(dbId));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to recover drain on startup: {}", e.getMessage());
        }
    }

    /**
     * Every 5 minutes, check for RUNNING tasks stuck longer than 30 minutes.
     * Mark them FAILED so drain can proceed.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void detectStuckTasks() {
        try {
            Instant cutoff = Instant.now().minusSeconds(STUCK_TASK_TIMEOUT_MINUTES * 60);
            List<KbWriteTaskEntity> stuck = taskRepository.findStuckRunningBefore(cutoff);
            for (KbWriteTaskEntity task : stuck) {
                String stuckError = "Job pod lost or timed out (>" + STUCK_TASK_TIMEOUT_MINUTES + "m)";
                String errorCategory = "TRANSIENT";
                task.setErrorCategory(errorCategory);
                task.setError(stuckError);

                if (shouldRetry(task, errorCategory)) {
                    task.setRetryCount(task.getRetryCount() + 1);
                    task.setStatus(KbWriteTaskStatus.QUEUED);
                    task.setStartedAt(null);
                    task.setJobId(null);
                    task.setNextRetryAt(null); // immediate retry
                    taskRepository.save(task);
                    log.warn("Stuck task {} re-queued for retry ({}/{}) (RUNNING since {})",
                             task.getId(), task.getRetryCount(), task.getMaxRetries(), task.getStartedAt());
                } else {
                    task.setStatus(KbWriteTaskStatus.FAILED);
                    task.setCompletedAt(Instant.now());
                    taskRepository.save(task);
                    log.warn("Stuck task {} failed permanently after {} retries (RUNNING since {})",
                             task.getId(), task.getRetryCount(), task.getStartedAt());
                    // Sync document status to FAILED
                    if (task.getType() == KbWriteTaskType.DOCUMENT_PARSE) {
                        syncDocumentFromTask(task, false, null, stuckError);
                    } else if (task.getType() == KbWriteTaskType.BATCH_DOCUMENT_PARSE) {
                        syncBatchDocumentsFromTask(task, false, null, stuckError);
                    }
                }
                // Re-trigger drain for this database
                executor.submit(() -> drain(task.getDatabaseId()));
            }
            if (!stuck.isEmpty()) {
                log.info("Detected {} stuck tasks, processed with retry logic", stuck.size());
            }

            // Detect stuck summarize tasks with a shorter 5-minute timeout
            Instant summarizeCutoff = Instant.now().minusSeconds(STUCK_SUMMARIZE_TIMEOUT_MINUTES * 60);
            List<KbWriteTaskEntity> stuckSummarize = taskRepository.findStuckSummarizeRunningBefore(summarizeCutoff);
            for (KbWriteTaskEntity task : stuckSummarize) {
                String stuckError = "Summarize task timed out (>" + STUCK_SUMMARIZE_TIMEOUT_MINUTES + "m)";
                String errorCategory = "TRANSIENT";
                task.setErrorCategory(errorCategory);
                task.setError(stuckError);

                if (shouldRetry(task, errorCategory)) {
                    task.setRetryCount(task.getRetryCount() + 1);
                    task.setStatus(KbWriteTaskStatus.QUEUED);
                    task.setStartedAt(null);
                    task.setJobId(null);
                    task.setNextRetryAt(null);
                    taskRepository.save(task);
                    log.warn("Stuck summarize task {} re-queued for retry ({}/{}) (RUNNING since {})",
                             task.getId(), task.getRetryCount(), task.getMaxRetries(), task.getStartedAt());
                } else {
                    task.setStatus(KbWriteTaskStatus.FAILED);
                    task.setCompletedAt(Instant.now());
                    taskRepository.save(task);
                    log.warn("Stuck summarize task {} failed permanently after {} retries (RUNNING since {})",
                             task.getId(), task.getRetryCount(), task.getStartedAt());
                }
                executor.submit(() -> drain(task.getDatabaseId()));
            }
            if (!stuckSummarize.isEmpty()) {
                log.info("Detected {} stuck summarize tasks, processed with retry logic", stuckSummarize.size());
            }
        } catch (Exception e) {
            log.warn("Error detecting stuck tasks: {}", e.getMessage());
        }
    }

    /**
     * Every 60 seconds, check for delayed retry tasks whose nextRetryAt has passed.
     * Triggers drain so they get picked up.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void processDelayedRetries() {
        try {
            var readyTasks = taskRepository.findDelayedRetryReady(Instant.now());
            for (var task : readyTasks) {
                log.info("Delayed retry task {} now ready, triggering drain for db {}",
                         task.getId(), task.getDatabaseId());
                executor.submit(() -> drain(task.getDatabaseId()));
            }
        } catch (Exception e) {
            log.warn("Error processing delayed retries: {}", e.getMessage());
        }
    }

    /**
     * Cancel all active tasks for a KB (called when KB is deleted).
     */
    public void cancelTasksForKb(String kbId) {
        int cancelled = taskRepository.cancelByKbId(kbId);
        if (cancelled > 0) {
            log.info("Cancelled {} active tasks for KB {}", cancelled, kbId);
        }
    }

    /**
     * Submit a write task to the queue. Returns the task entity (QUEUED status).
     * Triggers async drain for the database.
     */
    public KbWriteTaskEntity submit(String tenantId, String kbId, String databaseId,
                                     KbWriteTaskType type, Map<String, Object> params) {
        // Validate database exists before queuing
        DatabaseEntity db = databaseRepository.findById(databaseId).orElse(null);
        if (db == null) {
            throw new NotFoundException("Database not found: " + databaseId
                + ". The knowledge base may reference a deleted database.");
        }
        KbWriteTaskEntity task = new KbWriteTaskEntity();
        task.setTenantId(tenantId);
        task.setKbId(kbId);
        task.setDatabaseId(databaseId);
        task.setType(type);
        task.setStatus(KbWriteTaskStatus.QUEUED);
        try {
            task.setParams(params != null ? objectMapper.writeValueAsString(params) : "{}");
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize task params", e);
        }
        taskRepository.save(task);
        log.info("Submitted kb-write task {} type={} db={}", task.getId(), type, databaseId);

        // Trigger async drain
        executor.submit(() -> drain(databaseId));
        return task;
    }

    /**
     * Called when a job pod completes (from JobService.handleCallback).
     * Marks the associated task as SUCCEEDED/FAILED and triggers drain for next task.
     * On failure, applies smart retry logic: PERMANENT errors fail immediately,
     * TRANSIENT errors retry immediately, RATE_LIMIT errors retry after a delay.
     */
    @SuppressWarnings("unchecked")
    @org.springframework.transaction.annotation.Transactional
    public void onJobCompleted(String jobId, boolean success, String result, String error) {
        taskRepository.findByJobId(jobId).ifPresent(task -> {
            if (success) {
                task.setStatus(KbWriteTaskStatus.SUCCEEDED);
                task.setResult(result);
                task.setCompletedAt(Instant.now());
                taskRepository.save(task);
                log.info("kb-write task {} completed via job {}: SUCCEEDED", task.getId(), jobId);

                // Sync document status on success
                if (task.getType() == KbWriteTaskType.DOCUMENT_PARSE) {
                    syncDocumentFromTask(task, true, result, null);
                    enqueueSummarizeAfterParse(task);
                } else if (task.getType() == KbWriteTaskType.BATCH_DOCUMENT_PARSE) {
                    syncBatchDocumentsFromTask(task, true, result, null);
                    enqueueSummarizeAfterBatchParse(task);
                }
            } else {
                // Failure path: classify error and decide whether to retry
                String errorCategory = parseErrorCategory(error, result);
                task.setErrorCategory(errorCategory);
                task.setError(error);
                task.setResult(result);

                if (shouldRetry(task, errorCategory)) {
                    task.setRetryCount(task.getRetryCount() + 1);
                    task.setStatus(KbWriteTaskStatus.QUEUED);
                    task.setStartedAt(null);
                    task.setJobId(null);
                    if ("RATE_LIMIT".equals(errorCategory)) {
                        long delay = rateLimitDelaySeconds(task.getRetryCount());
                        task.setNextRetryAt(Instant.now().plusSeconds(delay));
                        log.info("RATE_LIMIT retry for task {} in {}s (attempt {})",
                                 task.getId(), delay, task.getRetryCount());
                    } else {
                        task.setNextRetryAt(null); // immediate retry
                    }
                    taskRepository.save(task);
                    log.info("kb-write task {} will retry ({}/{}) errorCategory={} via job {}",
                             task.getId(), task.getRetryCount(), task.getMaxRetries(),
                             errorCategory, jobId);
                    // Document stays in PROCESSING — do NOT sync
                } else {
                    // Final failure — no more retries
                    task.setStatus(KbWriteTaskStatus.FAILED);
                    task.setCompletedAt(Instant.now());
                    taskRepository.save(task);
                    log.info("kb-write task {} failed permanently (errorCategory={}) via job {}",
                             task.getId(), errorCategory, jobId);

                    // Sync document status to FAILED
                    if (task.getType() == KbWriteTaskType.DOCUMENT_PARSE) {
                        syncDocumentFromTask(task, false, result, error);
                    } else if (task.getType() == KbWriteTaskType.BATCH_DOCUMENT_PARSE) {
                        syncBatchDocumentsFromTask(task, false, result, error);
                    }
                }
            }

            // Trigger drain for next task
            executor.submit(() -> drain(task.getDatabaseId()));
        });
    }

    /**
     * Exponential backoff with jitter for RATE_LIMIT retries.
     * Base: 30s * 2^retryCount, capped at 240s. Jitter: 0..50% of base.
     */
    static long rateLimitDelaySeconds(int retryCount) {
        long base = Math.min(30L * (1L << retryCount), 240L);
        long jitter = (long) (base * 0.5 * Math.random());
        return base + jitter;
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

    @SuppressWarnings("unchecked")
    private void syncDocumentFromTask(KbWriteTaskEntity task, boolean success,
                                       String result, String error) {
        try {
            Map<String, Object> params = objectMapper.readValue(task.getParams(), Map.class);
            String docId = (String) params.get("document_id");
            if (docId == null) return;

            documentRepository.findById(docId).ifPresent(doc -> {
                if (success) {
                    doc.setStatus(DocumentStatus.READY);
                    if (result != null) {
                        try {
                            Map<String, Object> res = objectMapper.readValue(result, Map.class);
                            Object chunks = res.get("chunks_count");
                            if (chunks instanceof Number) {
                                doc.setChunksCount(((Number) chunks).intValue());
                            }
                        } catch (Exception e) {
                            log.debug("Failed to parse chunks_count from task result: {}", e.getMessage());
                        }
                    }
                    documentRepository.save(doc);
                    // Increment KB document count
                    if (doc.getKbId() != null) {
                        knowledgeBaseRepository.incrementDocumentCount(doc.getKbId(), 1);
                    }
                } else {
                    doc.setStatus(DocumentStatus.FAILED);
                    doc.setError(error);
                    documentRepository.save(doc);
                }
                log.info("Synced document {} status to {} from task {}", docId, doc.getStatus(), task.getId());
            });
        } catch (Exception e) {
            log.warn("Failed to sync document status from task {}: {}", task.getId(), e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void syncBatchDocumentsFromTask(KbWriteTaskEntity task, boolean success,
                                             String result, String error) {
        try {
            Map<String, Object> params = objectMapper.readValue(task.getParams(), Map.class);
            List<String> documentIds = (List<String>) params.get("document_ids");
            if (documentIds == null || documentIds.isEmpty()) return;

            if (!success) {
                // Mark all documents as FAILED
                for (String docId : documentIds) {
                    documentRepository.findById(docId).ifPresent(doc -> {
                        doc.setStatus(DocumentStatus.FAILED);
                        doc.setError(error);
                        documentRepository.save(doc);
                    });
                }
                return;
            }

            // Parse per-document results: {"documents": [{"document_id": "...", "chunks_count": N}, ...]}
            Map<String, Integer> chunkCounts = new HashMap<>();
            if (result != null) {
                try {
                    Map<String, Object> res = objectMapper.readValue(result, Map.class);
                    List<Map<String, Object>> docs = (List<Map<String, Object>>) res.get("documents");
                    if (docs != null) {
                        for (Map<String, Object> d : docs) {
                            String docId = (String) d.get("document_id");
                            Object chunks = d.get("chunks_count");
                            if (docId != null && chunks instanceof Number) {
                                chunkCounts.put(docId, ((Number) chunks).intValue());
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to parse batch task result: {}", e.getMessage());
                }
            }

            for (String docId : documentIds) {
                documentRepository.findById(docId).ifPresent(doc -> {
                    doc.setStatus(DocumentStatus.READY);
                    Integer chunks = chunkCounts.get(docId);
                    if (chunks != null) doc.setChunksCount(chunks);
                    documentRepository.save(doc);
                    if (doc.getKbId() != null) {
                        knowledgeBaseRepository.incrementDocumentCount(doc.getKbId(), 1);
                    }
                });
            }
            log.info("Synced {} batch documents from task {}", documentIds.size(), task.getId());
        } catch (Exception e) {
            log.warn("Failed to sync batch document status from task {}: {}", task.getId(), e.getMessage());
        }
    }

    /**
     * Get a task by ID (for polling endpoint).
     */
    public KbWriteTaskEntity getTask(String tenantId, String taskId) {
        return taskRepository.findByIdAndTenantId(taskId, tenantId)
            .orElseThrow(() -> new NotFoundException("Write task not found: " + taskId));
    }

    /**
     * Per-database drain: process QUEUED tasks.
     *
     * Lightweight tasks (chunk CRUD) execute serially — they use direct JDBC and may
     * have ordering dependencies. A running lightweight task blocks the next task.
     *
     * Heavyweight tasks (document parse) submit job pods that run independently.
     * Multiple heavyweight tasks can run concurrently for the same database — each
     * job pod does download/parse/chunk/embed in parallel, then writes to the shared
     * compute pod (PostgreSQL handles concurrent writes to different document rows).
     */
    private void drain(String databaseId) {
        ReentrantLock lock = dbLocks.computeIfAbsent(databaseId, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            log.debug("drain already running for db {}", databaseId);
            return;
        }
        log.info("drain started for db {}", databaseId);
        try {
            while (true) {
                // Check if there's a RUNNING lightweight task — must wait for it to finish
                List<KbWriteTaskEntity> active = taskRepository.findActiveByDatabaseId(databaseId);
                boolean hasRunningLightweight = active.stream()
                    .anyMatch(t -> t.getStatus() == KbWriteTaskStatus.RUNNING
                                && LIGHTWEIGHT_TYPES.contains(t.getType()));
                if (hasRunningLightweight) {
                    log.debug("db {} has a running lightweight task, waiting", databaseId);
                    return;
                }

                // Check heavyweight task concurrency against tenant quota
                long runningHeavy = active.stream()
                    .filter(t -> t.getStatus() == KbWriteTaskStatus.RUNNING
                              && !LIGHTWEIGHT_TYPES.contains(t.getType()))
                    .count();
                int quota = props.getKnowledge().getMaxConcurrentJobs();
                if (runningHeavy >= quota) {
                    log.debug("db {} at heavyweight quota ({}/{}), pausing drain",
                              databaseId, runningHeavy, quota);
                    return;
                }

                // Get next QUEUED task
                List<KbWriteTaskEntity> queued = taskRepository.findQueuedByDatabaseId(databaseId);
                if (queued.isEmpty()) {
                    log.debug("No queued tasks for db {}", databaseId);
                    return;
                }

                KbWriteTaskEntity task = queued.get(0);
                task.setStatus(KbWriteTaskStatus.RUNNING);
                task.setStartedAt(Instant.now());
                taskRepository.save(task);

                try {
                    if (LIGHTWEIGHT_TYPES.contains(task.getType())) {
                        executeLightweight(task);
                        task.setStatus(KbWriteTaskStatus.SUCCEEDED);
                        task.setCompletedAt(Instant.now());
                        taskRepository.save(task);
                        // Continue draining next task
                    } else {
                        executeHeavyweight(task);
                        // Heavyweight task submitted — continue to submit next queued task
                        // (job pods run independently and call back when done)
                        log.info("Heavyweight task {} submitted for db {}, continuing drain",
                                task.getId(), databaseId);
                    }
                } catch (Exception e) {
                    log.error("kb-write task {} failed: {}", task.getId(), e.getMessage(), e);
                    task.setStatus(KbWriteTaskStatus.FAILED);
                    task.setError(e.getMessage());
                    task.setCompletedAt(Instant.now());
                    taskRepository.save(task);
                    // Sync document status on failure
                    if (task.getType() == KbWriteTaskType.DOCUMENT_PARSE) {
                        syncDocumentFromTask(task, false, null, e.getMessage());
                    } else if (task.getType() == KbWriteTaskType.BATCH_DOCUMENT_PARSE) {
                        syncBatchDocumentsFromTask(task, false, null, e.getMessage());
                    }
                    // Continue draining next task
                }
            }
        } catch (Exception e) {
            log.error("drain failed for db {}: {}", databaseId, e.getMessage(), e);
        } finally {
            lock.unlock();
            log.info("drain finished for db {}", databaseId);
        }
    }

    /**
     * Wake compute and return a fresh connstr for the given database.
     * Used by job pods to refresh their connection after compute suspend.
     */
    public String wakeAndGetConnstr(String databaseId) {
        DatabaseEntity db = databaseRepository.findById(databaseId)
            .orElseThrow(() -> new NotFoundException("Database not found: " + databaseId));
        String address = wakeAndGetPodAddress(db);
        return "postgresql://cloud_admin:cloud-admin-internal@" + address + "/" + db.getName()
                + "?sslmode=disable";
    }

    /**
     * Wake the user's compute pod and return its internal pod IP address (host:55433).
     * wakeCompute() returns the proxy address (for user connections), but KB writes
     * need direct pod access to bypass SSL requirements and use cloud_admin auth.
     */
    private String wakeAndGetPodAddress(DatabaseEntity db) {
        String dbId = db.getId();
        computeLifecycleService.wakeCompute(dbId);
        // Derive pod name from database ID (same convention as ComputePodManager)
        String podName = "compute-" + dbId.replace("_", "-");
        // Wait for pod to be ready (wakeCompute may still be initializing)
        if (!computePodManager.waitForPodReady(podName, 120_000)) {
            throw new RuntimeException("Compute pod not ready: " + podName);
        }
        String podIp = computePodManager.getPodIp(podName);
        if (podIp == null) {
            throw new RuntimeException("Compute pod IP not available for: " + podName);
        }
        log.info("kb-write: using compute pod {} at {} for db {}", podName, podIp, dbId);
        return podIp + ":55433";
    }

    /**
     * Execute a lightweight task: connect directly to the user's compute pod PG and run SQL.
     */
    @SuppressWarnings("unchecked")
    private void executeLightweight(KbWriteTaskEntity task) throws Exception {
        DatabaseEntity db = databaseRepository.findById(task.getDatabaseId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + task.getDatabaseId()));

        // Wake compute and get internal pod address (bypasses proxy SSL)
        String address = wakeAndGetPodAddress(db);
        String jdbcUrl = "jdbc:postgresql://" + address + "/" + db.getName() + "?sslmode=disable";

        Map<String, Object> params = objectMapper.readValue(task.getParams(), Map.class);

        try (Connection conn = DriverManager.getConnection(jdbcUrl, "cloud_admin", "cloud-admin-internal")) {
            switch (task.getType()) {
                case EDIT_CHUNK -> executeEditChunk(conn, params);
                case DELETE_CHUNK -> executeDeleteChunk(conn, params);
                case CREATE_CHUNK -> executeCreateChunk(conn, params);
                case RECHUNK_ROLLBACK -> executeRechunkRollback(conn, params, task);
                case DELETE_DOCUMENT_CHUNKS -> executeDeleteDocumentChunks(conn, params);
                case DOCUMENT_SUMMARIZE -> executeDocumentSummarize(conn, params);
                case KB_SUMMARIZE -> executeKbSummarize(conn, params);
                default -> throw new IllegalStateException("Unknown lightweight type: " + task.getType());
            }
        }
    }

    /**
     * Execute a heavyweight task: submit a job pod that connects to the user's compute pod PG.
     */
    @SuppressWarnings("unchecked")
    private void executeHeavyweight(KbWriteTaskEntity task) throws Exception {
        DatabaseEntity db = databaseRepository.findById(task.getDatabaseId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + task.getDatabaseId()));

        // Delayed wake: job pod will call connstr_refresh_url when it's ready to write.
        // This avoids waking compute during the download/parse/chunk/embed phase.
        String connstr = "";

        Map<String, Object> params = objectMapper.readValue(task.getParams(), Map.class);
        params.put("database_connstr", connstr);
        params.put("database_id", db.getId());
        params.put("database_name", db.getName());
        params.put("job_submitted_at", Instant.now().toString());

        TenantEntity tenant = new TenantEntity();
        tenant.setId(task.getTenantId());

        // Create job without launching pod — we need the job ID/token to build refresh URL first
        JobEntity job = jobService.createJob(tenant, JobType.DOCUMENT_PARSE, params);
        task.setJobId(job.getId());
        taskRepository.save(task);

        // Build connstr_refresh_url so job pod can wake compute and get fresh connstr when ready to write
        if (callbackBaseUrl != null && !callbackBaseUrl.isBlank()) {
            String refreshUrl = callbackBaseUrl + "/api/v1/jobs/" + job.getId()
                    + "/connstr?token=" + job.getCallbackToken();
            params.put("connstr_refresh_url", refreshUrl);
            try {
                job.setParams(objectMapper.writeValueAsString(params));
                jobService.saveJob(job);
            } catch (Exception e) {
                log.warn("Failed to save connstr_refresh_url to job params: {}", e.getMessage());
            }
        }

        // Now schedule the pod launch — params are complete with connstr_refresh_url
        jobService.scheduleJobLaunch(job.getId());

        log.info("kb-write task {} submitted job {} for db {}", task.getId(), job.getId(), task.getDatabaseId());
    }

    private void executeEditChunk(Connection conn, Map<String, Object> params) throws Exception {
        String docId = (String) params.get("document_id");
        int chunkIndex = ((Number) params.get("chunk_index")).intValue();
        String content = (String) params.get("content");
        String vectorStr = (String) params.get("embedding_vector");

        String sql = "UPDATE knowledge_chunks SET content = ?, embedding = ?::vector, " +
                "char_count = ?, edited = true, updated_at = now() " +
                "WHERE document_id = ? AND chunk_index = ? AND level = 0";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, content);
            ps.setString(2, vectorStr);
            ps.setInt(3, content.length());
            ps.setString(4, docId);
            ps.setInt(5, chunkIndex);
            int updated = ps.executeUpdate();
            if (updated == 0) throw new NotFoundException("Chunk not found");
        }
    }

    private void executeDeleteChunk(Connection conn, Map<String, Object> params) throws Exception {
        String docId = (String) params.get("document_id");
        int chunkIndex = ((Number) params.get("chunk_index")).intValue();

        conn.setAutoCommit(false);
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM knowledge_chunks WHERE document_id = ? AND chunk_index = ? AND level = 0")) {
                ps.setString(1, docId);
                ps.setInt(2, chunkIndex);
                if (ps.executeUpdate() == 0) {
                    conn.rollback();
                    throw new NotFoundException("Chunk not found");
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE knowledge_chunks SET chunk_index = chunk_index - 1 " +
                    "WHERE document_id = ? AND chunk_index > ? AND level = 0")) {
                ps.setString(1, docId);
                ps.setInt(2, chunkIndex);
                ps.executeUpdate();
            }
            conn.commit();
        } catch (Exception e) {
            conn.rollback();
            throw e;
        }
    }

    private void executeCreateChunk(Connection conn, Map<String, Object> params) throws Exception {
        String docId = (String) params.get("document_id");
        String content = (String) params.get("content");
        String vectorStr = (String) params.get("embedding_vector");
        int insertAfterIndex = ((Number) params.get("insert_after_index")).intValue();
        int newIndex = insertAfterIndex + 1;

        conn.setAutoCommit(false);
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE knowledge_chunks SET chunk_index = chunk_index + 1 " +
                    "WHERE document_id = ? AND chunk_index >= ? AND level = 0")) {
                ps.setString(1, docId);
                ps.setInt(2, newIndex);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO knowledge_chunks (document_id, chunk_index, content, embedding, " +
                    "char_count, edited, level, overlap_prev, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?::vector, ?, true, 0, 0, now(), now())")) {
                ps.setString(1, docId);
                ps.setInt(2, newIndex);
                ps.setString(3, content);
                ps.setString(4, vectorStr);
                ps.setInt(5, content.length());
                ps.executeUpdate();
            }
            conn.commit();
        } catch (Exception e) {
            conn.rollback();
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private void executeRechunkRollback(Connection conn, Map<String, Object> params,
                                         KbWriteTaskEntity task) throws Exception {
        String docId = (String) params.get("document_id");
        String branchJdbcUrl = (String) params.get("branch_jdbc_url");
        String branchUser = (String) params.get("branch_user");
        String branchPass = (String) params.get("branch_pass");

        List<Map<String, Object>> branchChunks = new ArrayList<>();
        String selectSql = "SELECT id, chunk_index, content, embedding::text, metadata::text, " +
                "char_offset_start, char_offset_end, char_count, overlap_prev, " +
                "page_start, page_end, bbox::text, level, source_chunks, edited, updated_at " +
                "FROM knowledge_chunks WHERE document_id = ? AND level = 0 ORDER BY chunk_index";

        try (Connection branchConn = DriverManager.getConnection(branchJdbcUrl, branchUser, branchPass);
             PreparedStatement ps = branchConn.prepareStatement(selectSql)) {
            ps.setString(1, docId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getString("id"));
                    row.put("chunk_index", rs.getInt("chunk_index"));
                    row.put("content", rs.getString("content"));
                    row.put("embedding", rs.getString("embedding"));
                    row.put("metadata", rs.getString("metadata"));
                    row.put("char_offset_start", rs.getObject("char_offset_start"));
                    row.put("char_offset_end", rs.getObject("char_offset_end"));
                    row.put("char_count", rs.getObject("char_count"));
                    row.put("overlap_prev", rs.getObject("overlap_prev"));
                    row.put("page_start", rs.getObject("page_start"));
                    row.put("page_end", rs.getObject("page_end"));
                    row.put("bbox", rs.getString("bbox"));
                    row.put("level", rs.getInt("level"));
                    row.put("source_chunks", rs.getArray("source_chunks"));
                    row.put("edited", rs.getBoolean("edited"));
                    row.put("updated_at", rs.getTimestamp("updated_at"));
                    branchChunks.add(row);
                }
            }
        }

        if (branchChunks.isEmpty()) {
            throw new RuntimeException("No chunks found in branch snapshot for document: " + docId);
        }

        conn.setAutoCommit(false);
        try {
            try (PreparedStatement delPs = conn.prepareStatement(
                    "DELETE FROM knowledge_chunks WHERE document_id = ? AND level = 0")) {
                delPs.setString(1, docId);
                delPs.executeUpdate();
            }
            String insertSql2 = "INSERT INTO knowledge_chunks " +
                    "(id, document_id, chunk_index, content, embedding, metadata, " +
                    "char_offset_start, char_offset_end, char_count, overlap_prev, " +
                    "page_start, page_end, bbox, level, source_chunks, edited, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?::vector, ?::jsonb, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, now())";
            try (PreparedStatement insPs = conn.prepareStatement(insertSql2)) {
                for (Map<String, Object> chunk : branchChunks) {
                    insPs.setString(1, (String) chunk.get("id"));
                    insPs.setString(2, docId);
                    insPs.setInt(3, (int) chunk.get("chunk_index"));
                    insPs.setString(4, (String) chunk.get("content"));
                    insPs.setString(5, (String) chunk.get("embedding"));
                    insPs.setString(6, (String) chunk.get("metadata"));
                    insPs.setObject(7, chunk.get("char_offset_start"));
                    insPs.setObject(8, chunk.get("char_offset_end"));
                    insPs.setObject(9, chunk.get("char_count"));
                    insPs.setObject(10, chunk.get("overlap_prev"));
                    insPs.setObject(11, chunk.get("page_start"));
                    insPs.setObject(12, chunk.get("page_end"));
                    insPs.setString(13, (String) chunk.get("bbox"));
                    insPs.setInt(14, (int) chunk.get("level"));
                    insPs.setObject(15, chunk.get("source_chunks"));
                    insPs.setBoolean(16, (boolean) chunk.get("edited"));
                    insPs.addBatch();
                }
                insPs.executeBatch();
            }
            conn.commit();
        } catch (Exception e2) {
            conn.rollback();
            throw e2;
        }

        task.setResult("{\"chunks_count\":" + branchChunks.size() + "}");
    }

    private void executeDeleteDocumentChunks(Connection conn, Map<String, Object> params) throws Exception {
        String docId = (String) params.get("document_id");
        if ("__ALL__".equals(docId)) {
            // Truncate all chunks in the KB database
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM knowledge_chunks")) {
                int deleted = ps.executeUpdate();
                log.info("Cleared all {} chunks from KB database", deleted);
            }
        } else {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM knowledge_chunks WHERE document_id = ?")) {
                ps.setString(1, docId);
                int deleted = ps.executeUpdate();
                log.info("Deleted {} chunks (all levels) for document {}", deleted, docId);
            }
        }

        // Re-generate L2 global summary now that a document's chunks are gone
        if (params.containsKey("tenant_id") && params.containsKey("kb_id")) {
            Map<String, Object> kbParams = new LinkedHashMap<>();
            kbParams.put("tenant_id", params.get("tenant_id"));
            kbParams.put("kb_id", params.get("kb_id"));
            kbParams.put("connstr", params.get("connstr"));
            kbParams.put("database_id", params.get("database_id"));
            enqueueTask((String) params.get("database_id"), KbWriteTaskType.KB_SUMMARIZE, kbParams);
            log.info("Enqueued KB_SUMMARIZE after document delete for KB {}", params.get("kb_id"));
        }
    }

    @SuppressWarnings("unchecked")
    private void executeDocumentSummarize(Connection conn, Map<String, Object> params) {
        String tenantId = (String) params.get("tenant_id");
        String kbId = (String) params.get("kb_id");
        String documentId = (String) params.get("document_id");
        summaryService.summarizeDocument(conn, tenantId, kbId, documentId);

        // Check if all documents now have L1 → enqueue KB_SUMMARIZE
        List<String> docIds = (List<String>) params.get("all_document_ids");
        if (docIds != null && summaryService.allDocumentsHaveSummary(conn, docIds)) {
            Map<String, Object> kbParams = new LinkedHashMap<>();
            kbParams.put("tenant_id", tenantId);
            kbParams.put("kb_id", kbId);
            kbParams.put("connstr", params.get("connstr"));
            kbParams.put("database_id", params.get("database_id"));
            enqueueTask((String) params.get("database_id"), KbWriteTaskType.KB_SUMMARIZE, kbParams);
            log.info("All documents have L1 summaries, enqueued KB_SUMMARIZE for KB {}", kbId);
        }
    }

    private void executeKbSummarize(Connection conn, Map<String, Object> params) {
        String tenantId = (String) params.get("tenant_id");
        String kbId = (String) params.get("kb_id");
        summaryService.summarizeKb(conn, tenantId, kbId);
    }

    @SuppressWarnings("unchecked")
    private void enqueueSummarizeAfterParse(KbWriteTaskEntity task) {
        try {
            Map<String, Object> params = objectMapper.readValue(task.getParams(), Map.class);
            String tenantId = (String) params.get("tenant_id");
            String kbId = (String) params.get("kb_id");
            String documentId = (String) params.get("document_id");
            String connstr = (String) params.get("connstr");
            List<String> allDocIds = getAllDocumentIds(tenantId, kbId);

            Map<String, Object> sumParams = new LinkedHashMap<>();
            sumParams.put("tenant_id", tenantId);
            sumParams.put("kb_id", kbId);
            sumParams.put("document_id", documentId);
            sumParams.put("connstr", connstr);
            sumParams.put("database_id", task.getDatabaseId());
            sumParams.put("all_document_ids", allDocIds);
            enqueueTask(task.getDatabaseId(), KbWriteTaskType.DOCUMENT_SUMMARIZE, sumParams);
            log.info("Enqueued DOCUMENT_SUMMARIZE for doc {} in KB {}", documentId, kbId);
        } catch (Exception e) {
            log.warn("Failed to enqueue DOCUMENT_SUMMARIZE after parse: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void enqueueSummarizeAfterBatchParse(KbWriteTaskEntity task) {
        try {
            Map<String, Object> params = objectMapper.readValue(task.getParams(), Map.class);
            String tenantId = (String) params.get("tenant_id");
            String kbId = (String) params.get("kb_id");
            String connstr = (String) params.get("connstr");
            List<Map<String, Object>> docs = (List<Map<String, Object>>) params.get("documents");
            List<String> allDocIds = getAllDocumentIds(tenantId, kbId);

            for (Map<String, Object> doc : docs) {
                String docId = (String) doc.get("document_id");
                Map<String, Object> sumParams = new LinkedHashMap<>();
                sumParams.put("tenant_id", tenantId);
                sumParams.put("kb_id", kbId);
                sumParams.put("document_id", docId);
                sumParams.put("connstr", connstr);
                sumParams.put("database_id", task.getDatabaseId());
                sumParams.put("all_document_ids", allDocIds);
                enqueueTask(task.getDatabaseId(), KbWriteTaskType.DOCUMENT_SUMMARIZE, sumParams);
            }
            log.info("Enqueued {} DOCUMENT_SUMMARIZE tasks for batch in KB {}", docs.size(), kbId);
        } catch (Exception e) {
            log.warn("Failed to enqueue DOCUMENT_SUMMARIZE after batch parse: {}", e.getMessage());
        }
    }

    private List<String> getAllDocumentIds(String tenantId, String kbId) {
        return documentRepository.findAllByKbId(kbId)
                .stream()
                .filter(d -> d.getStatus() == DocumentStatus.READY)
                .map(DocumentEntity::getId)
                .toList();
    }

    public void enqueueTask(String databaseId, KbWriteTaskType type, Map<String, Object> params) {
        KbWriteTaskEntity task = new KbWriteTaskEntity();
        task.setDatabaseId(databaseId);
        task.setType(type);
        task.setStatus(KbWriteTaskStatus.QUEUED);
        task.setMaxRetries(3);
        if (params.containsKey("tenant_id")) task.setTenantId((String) params.get("tenant_id"));
        if (params.containsKey("kb_id")) task.setKbId((String) params.get("kb_id"));
        try {
            task.setParams(objectMapper.writeValueAsString(params));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize task params", e);
        }
        taskRepository.save(task);
        executor.submit(() -> drain(databaseId));
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
