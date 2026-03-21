package com.lakeon.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.LakeonProperties;
import com.lakeon.job.JobEntity;
import com.lakeon.job.JobService;
import com.lakeon.job.JobType;
import com.lakeon.k8s.KbWritePodManager;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.ComputeLifecycleService;
import com.lakeon.service.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
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
 * Ensures per-database serial execution: only one write task runs at a time per database.
 * Lightweight tasks (chunk CRUD) execute directly via API → kb-write pod PG.
 * Heavyweight tasks (document parse, rechunk) submit a job pod that connects to kb-write pod.
 */
@Service
public class KbWriteQueue {
    private static final Logger log = LoggerFactory.getLogger(KbWriteQueue.class);

    private final KbWriteTaskRepository taskRepository;
    private final KbWritePodManager kbWritePodManager;
    private final DatabaseRepository databaseRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final DocumentRepository documentRepository;
    private final JobService jobService;
    private final LakeonProperties props;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final ConcurrentHashMap<String, ReentrantLock> dbLocks = new ConcurrentHashMap<>();

    // Lightweight task types that execute SQL directly (no job pod needed)
    private static final Set<KbWriteTaskType> LIGHTWEIGHT_TYPES = Set.of(
        KbWriteTaskType.EDIT_CHUNK,
        KbWriteTaskType.DELETE_CHUNK,
        KbWriteTaskType.CREATE_CHUNK,
        KbWriteTaskType.RECHUNK_ROLLBACK,
        KbWriteTaskType.DELETE_DOCUMENT_CHUNKS
    );

    public KbWriteQueue(KbWriteTaskRepository taskRepository,
                         KbWritePodManager kbWritePodManager,
                         DatabaseRepository databaseRepository,
                         KnowledgeBaseRepository knowledgeBaseRepository,
                         DocumentRepository documentRepository,
                         @Lazy JobService jobService,
                         LakeonProperties props,
                         ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.kbWritePodManager = kbWritePodManager;
        this.databaseRepository = databaseRepository;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.documentRepository = documentRepository;
        this.jobService = jobService;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * Submit a write task to the queue. Returns the task entity (QUEUED status).
     * Triggers async drain for the database.
     */
    public KbWriteTaskEntity submit(String tenantId, String kbId, String databaseId,
                                     KbWriteTaskType type, Map<String, Object> params) {
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
     */
    @SuppressWarnings("unchecked")
    public void onJobCompleted(String jobId, boolean success, String result, String error) {
        taskRepository.findByJobId(jobId).ifPresent(task -> {
            task.setStatus(success ? KbWriteTaskStatus.SUCCEEDED : KbWriteTaskStatus.FAILED);
            task.setResult(result);
            task.setError(error);
            task.setCompletedAt(Instant.now());
            taskRepository.save(task);
            log.info("kb-write task {} completed via job {}: {}", task.getId(), jobId,
                     success ? "SUCCEEDED" : "FAILED");

            // Sync document status for DOCUMENT_PARSE tasks
            if (task.getType() == KbWriteTaskType.DOCUMENT_PARSE) {
                syncDocumentFromTask(task, success, result, error);
            }

            // Trigger drain for next task
            executor.submit(() -> drain(task.getDatabaseId()));
        });
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
                        knowledgeBaseRepository.findById(doc.getKbId()).ifPresent(kb -> {
                            kb.setDocumentCount((kb.getDocumentCount() == null ? 0 : kb.getDocumentCount()) + 1);
                            knowledgeBaseRepository.save(kb);
                        });
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

    /**
     * Get a task by ID (for polling endpoint).
     */
    public KbWriteTaskEntity getTask(String tenantId, String taskId) {
        return taskRepository.findByIdAndTenantId(taskId, tenantId)
            .orElseThrow(() -> new NotFoundException("Write task not found: " + taskId));
    }

    /**
     * Per-database serial drain: take the next QUEUED task and execute it.
     * Only one drain runs per database at a time (ReentrantLock).
     */
    private void drain(String databaseId) {
        ReentrantLock lock = dbLocks.computeIfAbsent(databaseId, k -> new ReentrantLock());
        if (!lock.tryLock()) {
            log.debug("drain already running for db {}", databaseId);
            return;
        }
        try {
            while (true) {
                // Check if there's already a RUNNING task (heavyweight waiting for job callback)
                List<KbWriteTaskEntity> active = taskRepository.findActiveByDatabaseId(databaseId);
                boolean hasRunning = active.stream()
                    .anyMatch(t -> t.getStatus() == KbWriteTaskStatus.RUNNING);
                if (hasRunning) {
                    log.debug("db {} has a RUNNING task, waiting for callback", databaseId);
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
                        // Heavyweight tasks wait for job callback — exit drain loop
                        return;
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
                    }
                    // Continue draining next task
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Execute a lightweight task: connect directly to kb-write pod PG and run SQL.
     */
    @SuppressWarnings("unchecked")
    private void executeLightweight(KbWriteTaskEntity task) throws Exception {
        DatabaseEntity db = databaseRepository.findById(task.getDatabaseId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + task.getDatabaseId()));

        String address = kbWritePodManager.ensureKbWritePod(db);
        String jdbcUrl = "jdbc:postgresql://" + address + "/" + db.getName() + "?sslmode=disable";

        Map<String, Object> params = objectMapper.readValue(task.getParams(), Map.class);

        try (Connection conn = DriverManager.getConnection(jdbcUrl, "cloud_admin", "cloud-admin-internal")) {
            switch (task.getType()) {
                case EDIT_CHUNK -> executeEditChunk(conn, params);
                case DELETE_CHUNK -> executeDeleteChunk(conn, params);
                case CREATE_CHUNK -> executeCreateChunk(conn, params);
                case RECHUNK_ROLLBACK -> executeRechunkRollback(conn, params, task);
                case DELETE_DOCUMENT_CHUNKS -> executeDeleteDocumentChunks(conn, params);
                default -> throw new IllegalStateException("Unknown lightweight type: " + task.getType());
            }
        }
    }

    /**
     * Execute a heavyweight task: submit a job pod that connects to kb-write pod PG.
     */
    @SuppressWarnings("unchecked")
    private void executeHeavyweight(KbWriteTaskEntity task) throws Exception {
        DatabaseEntity db = databaseRepository.findById(task.getDatabaseId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + task.getDatabaseId()));

        // Ensure kb-write pod is ready
        String address = kbWritePodManager.ensureKbWritePod(db);
        String connstr = "postgresql://cloud_admin:cloud-admin-internal@" + address + "/" + db.getName()
                + "?sslmode=disable";

        Map<String, Object> params = objectMapper.readValue(task.getParams(), Map.class);
        // Override database_connstr to point to kb-write pod
        params.put("database_connstr", connstr);

        TenantEntity tenant = new TenantEntity();
        tenant.setId(task.getTenantId());

        JobEntity job = jobService.submitJob(tenant, JobType.DOCUMENT_PARSE, params);
        task.setJobId(job.getId());
        taskRepository.save(task);
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
            String chunkId = "chk_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO knowledge_chunks (id, document_id, chunk_index, content, embedding, " +
                    "char_count, edited, level, overlap_prev, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?::vector, ?, true, 0, 0, now(), now())")) {
                ps.setString(1, chunkId);
                ps.setString(2, docId);
                ps.setInt(3, newIndex);
                ps.setString(4, content);
                ps.setString(5, vectorStr);
                ps.setInt(6, content.length());
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
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM knowledge_chunks WHERE document_id = ?")) {
            ps.setString(1, docId);
            int deleted = ps.executeUpdate();
            log.info("Deleted {} chunks for document {}", deleted, docId);
        }
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
