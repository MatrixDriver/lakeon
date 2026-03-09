package com.lakeon.service;

import com.lakeon.config.LakeonProperties;
import com.lakeon.k8s.ComputePodManager;
import com.lakeon.k8s.ImportJobPodManager;
import com.lakeon.model.dto.*;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.ImportTableTaskEntity;
import com.lakeon.model.entity.ImportTaskEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.enums.ConflictStrategy;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.model.enums.ImportMode;
import com.lakeon.model.enums.ImportTaskStatus;
import com.lakeon.model.enums.OperationType;
import com.lakeon.model.entity.OperationLogEntity;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.ImportTableTaskRepository;
import com.lakeon.repository.ImportTaskRepository;
import com.lakeon.service.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class ImportService {
    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final ImportTaskRepository importTaskRepository;
    private final ImportTableTaskRepository importTableTaskRepository;
    private final DatabaseRepository databaseRepository;
    private final ImportJobPodManager importJobPodManager;
    private final ComputePodManager computePodManager;
    private final DatabaseService databaseService;
    private final OperationLogService operationLogService;
    private final LakeonProperties props;
    private final ExecutorService importExecutor = Executors.newFixedThreadPool(2);

    public ImportService(ImportTaskRepository importTaskRepository,
                         ImportTableTaskRepository importTableTaskRepository,
                         DatabaseRepository databaseRepository,
                         ImportJobPodManager importJobPodManager,
                         ComputePodManager computePodManager,
                         DatabaseService databaseService,
                         OperationLogService operationLogService,
                         LakeonProperties props) {
        this.importTaskRepository = importTaskRepository;
        this.importTableTaskRepository = importTableTaskRepository;
        this.databaseRepository = databaseRepository;
        this.importJobPodManager = importJobPodManager;
        this.computePodManager = computePodManager;
        this.databaseService = databaseService;
        this.operationLogService = operationLogService;
        this.props = props;
    }

    public Map<String, Object> testConnection(TestConnectionRequest req) {
        Properties connProps = new Properties();
        connProps.setProperty("user", req.user());
        connProps.setProperty("password", req.password());
        connProps.setProperty("loginTimeout", "5");
        connProps.setProperty("connectTimeout", "5");
        connProps.setProperty("socketTimeout", "10");

        String url = "jdbc:postgresql://" + req.host() + ":" + req.port() + "/" + req.dbname();
        try (Connection conn = DriverManager.getConnection(url, connProps);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT version()")) {
            rs.next();
            String version = rs.getString(1);
            return Map.of("ok", true, "version", version);
        } catch (Exception e) {
            return Map.of("ok", false, "error", e.getMessage());
        }
    }

    public List<SourceTableInfo> listSourceTables(TestConnectionRequest req) {
        Properties connProps = new Properties();
        connProps.setProperty("user", req.user());
        connProps.setProperty("password", req.password());
        connProps.setProperty("loginTimeout", "5");
        connProps.setProperty("connectTimeout", "5");
        connProps.setProperty("socketTimeout", "10");

        String url = "jdbc:postgresql://" + req.host() + ":" + req.port() + "/" + req.dbname();
        // Filter out extension-owned tables (e.g. tiger.*, topology.*, spatial_ref_sys)
        // These are auto-created by CREATE EXTENSION and don't need importing
        String sql = "SELECT t.table_schema, t.table_name, COALESCE(c.reltuples::bigint, 0) " +
            "FROM information_schema.tables t " +
            "LEFT JOIN pg_class c ON c.relname = t.table_name " +
            "AND c.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = t.table_schema) " +
            "WHERE t.table_type = 'BASE TABLE' " +
            "AND t.table_schema NOT IN ('pg_catalog', 'information_schema') " +
            "AND NOT EXISTS (" +
            "  SELECT 1 FROM pg_depend d " +
            "  WHERE d.classid = 'pg_class'::regclass " +
            "  AND d.objid = c.oid " +
            "  AND d.deptype = 'e'" +
            ") " +
            "ORDER BY t.table_schema, t.table_name";

        List<SourceTableInfo> tables = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url, connProps);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                tables.add(new SourceTableInfo(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getLong(3)
                ));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to list source tables: " + e.getMessage(), e);
        }
        return tables;
    }

    @Transactional
    public ImportTaskResponse createImport(TenantEntity tenant, String dbId, CreateImportRequest req) {
        // Validate database exists and belongs to tenant
        DatabaseEntity database = databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));

        // For SELECTIVE mode, parse tables immediately (lightweight)
        List<SourceTableInfo> selectedTables = null;
        if (req.mode() == ImportMode.SELECTIVE && req.tables() != null) {
            selectedTables = new ArrayList<>();
            for (String tableSpec : req.tables()) {
                String[] parts = tableSpec.split("\\.", 2);
                String schema = parts.length > 1 ? parts[0] : "public";
                String table = parts.length > 1 ? parts[1] : parts[0];
                selectedTables.add(new SourceTableInfo(schema, table, 0));
            }
        }

        // Log operation
        OperationLogEntity opLog = operationLogService.startOperation(
            dbId, tenant.getId(), database.getName(), OperationType.IMPORT);

        // Create task in PENDING status — return immediately
        ImportTaskEntity task = new ImportTaskEntity();
        task.setTenantId(tenant.getId());
        task.setDatabaseId(dbId);
        task.setSourceHost(req.sourceHost());
        task.setSourcePort(req.sourcePort());
        task.setSourceDbname(req.sourceDbname());
        task.setSourceUser(req.sourceUser());
        task.setSourcePassword(req.sourcePassword());
        task.setMode(req.mode());
        task.setConflictStrategy(req.conflictStrategy() != null ? req.conflictStrategy() : ConflictStrategy.APPEND);
        task.setStatus(ImportTaskStatus.PENDING);
        task.setOperationLogId(opLog.getId());
        task = importTaskRepository.save(task);

        // Launch async preparation (wake compute, list tables, launch pod)
        final String taskId = task.getId();
        final String tenantId = tenant.getId();
        final String opLogId = opLog.getId();
        final List<SourceTableInfo> finalSelectedTables = selectedTables;
        importExecutor.submit(() -> prepareAndLaunchImport(taskId, tenantId, dbId, req, finalSelectedTables, opLogId));

        return toResponse(task, null);
    }

    private void prepareAndLaunchImport(String taskId, String tenantId, String dbId,
                                         CreateImportRequest req, List<SourceTableInfo> selectedTables,
                                         String opLogId) {
        try {
            ImportTaskEntity task = importTaskRepository.findById(taskId)
                .orElseThrow(() -> new NotFoundException("Import task not found: " + taskId));

            DatabaseEntity database = databaseRepository.findByIdAndTenantId(dbId, tenantId)
                .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));

            // Ensure compute is running — auto-wake if suspended
            if (database.getStatus() == DatabaseStatus.SUSPENDED) {
                log.info("Auto-waking compute for import on database {}", dbId);
                databaseService.wakeCompute(database);
                database = databaseRepository.findById(dbId).orElse(database);
            }
            if (database.getStatus() != DatabaseStatus.RUNNING) {
                throw new IllegalStateException("Database must be in RUNNING state. Current: " + database.getStatus());
            }

            // Determine tables to import
            List<SourceTableInfo> sourceTables;
            if (req.mode() == ImportMode.FULL) {
                TestConnectionRequest connReq = new TestConnectionRequest(
                    req.sourceHost(), req.sourcePort(), req.sourceDbname(), req.sourceUser(), req.sourcePassword()
                );
                sourceTables = listSourceTables(connReq);
            } else {
                sourceTables = selectedTables != null ? selectedTables : List.of();
            }

            task.setTotalTables(sourceTables.size());
            task.setCompletedTables(0);
            task = importTaskRepository.save(task);

            // Create ImportTableTaskEntity for each table
            List<ImportTableTaskEntity> tableTasks = new ArrayList<>();
            for (SourceTableInfo src : sourceTables) {
                ImportTableTaskEntity tableTask = new ImportTableTaskEntity();
                tableTask.setImportTaskId(task.getId());
                tableTask.setSchemaName(src.schema());
                tableTask.setTableName(src.table());
                tableTask.setStatus(ImportTaskStatus.PENDING);
                tableTasks.add(tableTask);
            }
            tableTasks = importTableTaskRepository.saveAll(tableTasks);

            // Disable auto-suspend during import
            task.setOriginalSuspendTimeout(database.getSuspendTimeout());
            database.setSuspendTimeout("1440m");
            databaseRepository.save(database);

            // Launch job pod
            task.setStatus(ImportTaskStatus.RUNNING);
            task.setStartedAt(Instant.now());
            String podName = importJobPodManager.launchJobPod(task, tableTasks, database);
            task.setJobPodName(podName);
            importTaskRepository.save(task);

            log.info("Import task {} launched with {} tables", taskId, sourceTables.size());
        } catch (Exception e) {
            log.error("Failed to prepare import task {}: {}", taskId, e.getMessage(), e);
            importTaskRepository.findById(taskId).ifPresent(t -> {
                t.setStatus(ImportTaskStatus.FAILED);
                t.setErrorMessage(e.getMessage());
                t.setFinishedAt(Instant.now());
                completeImportOperationLog(t, e.getMessage());
                importTaskRepository.save(t);
            });
        }
    }

    public ImportTaskResponse getImport(TenantEntity tenant, String dbId, String taskId) {
        ImportTaskEntity task = importTaskRepository.findByIdAndTenantId(taskId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Import task not found: " + taskId));
        if (!task.getDatabaseId().equals(dbId)) {
            throw new NotFoundException("Import task not found: " + taskId);
        }
        List<ImportTableTaskEntity> tableTasks = importTableTaskRepository
            .findAllByImportTaskIdOrderBySchemaNameAscTableNameAsc(taskId);
        return toResponse(task, tableTasks);
    }

    public List<ImportTaskResponse> listImports(TenantEntity tenant, String dbId) {
        // Validate database belongs to tenant
        databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));

        List<ImportTaskEntity> tasks = importTaskRepository
            .findAllByDatabaseIdAndTenantIdOrderByCreatedAtDesc(dbId, tenant.getId());
        return tasks.stream()
            .map(t -> toResponse(t, null))
            .toList();
    }

    @Transactional
    public void handleCallback(String taskId, ImportCallbackRequest req) {
        ImportTaskEntity task = importTaskRepository.findById(taskId)
            .orElseThrow(() -> new NotFoundException("Import task not found: " + taskId));

        ImportTableTaskEntity tableTask = importTableTaskRepository.findById(req.tableTaskId())
            .orElseThrow(() -> new NotFoundException("Import table task not found: " + req.tableTaskId()));

        // Update table task based on callback status
        if (req.status() == ImportTaskStatus.RUNNING) {
            tableTask.setStatus(ImportTaskStatus.RUNNING);
            tableTask.setStartedAt(Instant.now());
        } else if (req.status() == ImportTaskStatus.COMPLETED) {
            tableTask.setStatus(ImportTaskStatus.COMPLETED);
            tableTask.setFinishedAt(Instant.now());
            if (req.rowCount() != null) {
                tableTask.setRowCount(req.rowCount());
            }
            task.setCompletedTables(task.getCompletedTables() + 1);
        } else if (req.status() == ImportTaskStatus.FAILED) {
            tableTask.setStatus(ImportTaskStatus.FAILED);
            tableTask.setFinishedAt(Instant.now());
            tableTask.setErrorMessage(req.errorMessage());
        }

        importTableTaskRepository.save(tableTask);

        // Check if all tables are done (no PENDING or RUNNING)
        long pendingOrRunning = importTableTaskRepository.countByImportTaskIdAndStatus(taskId, ImportTaskStatus.PENDING)
            + importTableTaskRepository.countByImportTaskIdAndStatus(taskId, ImportTaskStatus.RUNNING);

        if (pendingOrRunning == 0) {
            long failedCount = importTableTaskRepository.countByImportTaskIdAndStatus(taskId, ImportTaskStatus.FAILED);
            String errorMsg = null;
            if (failedCount == 0) {
                task.setStatus(ImportTaskStatus.COMPLETED);
            } else if (task.getCompletedTables() > 0) {
                task.setStatus(ImportTaskStatus.PARTIAL);
                errorMsg = failedCount + " table(s) failed";
            } else {
                task.setStatus(ImportTaskStatus.FAILED);
                errorMsg = "All tables failed";
            }
            task.setFinishedAt(Instant.now());

            // Complete operation log
            completeImportOperationLog(task, errorMsg);

            // Restore original suspend timeout
            if (task.getOriginalSuspendTimeout() != null) {
                databaseRepository.findById(task.getDatabaseId()).ifPresent(db -> {
                    db.setSuspendTimeout(task.getOriginalSuspendTimeout());
                    databaseRepository.save(db);
                    log.info("Restored suspend timeout to {} for database {}", task.getOriginalSuspendTimeout(), db.getId());
                });
            }
        }

        importTaskRepository.save(task);
    }

    @Transactional
    public ImportTaskResponse pauseImport(TenantEntity tenant, String dbId, String taskId) {
        ImportTaskEntity task = findTaskForTenant(tenant, dbId, taskId);
        if (task.getStatus() != ImportTaskStatus.RUNNING) {
            throw new IllegalStateException("Can only pause RUNNING tasks. Current status: " + task.getStatus());
        }

        importJobPodManager.deleteJobPod(taskId);
        task.setStatus(ImportTaskStatus.PAUSED);

        // Reset PENDING and RUNNING table tasks to PENDING
        List<ImportTableTaskEntity> activeTasks = importTableTaskRepository
            .findAllByImportTaskIdAndStatus(taskId, ImportTaskStatus.RUNNING);
        for (ImportTableTaskEntity tt : activeTasks) {
            tt.setStatus(ImportTaskStatus.PENDING);
            tt.setStartedAt(null);
        }
        importTableTaskRepository.saveAll(activeTasks);

        task = importTaskRepository.save(task);

        List<ImportTableTaskEntity> allTables = importTableTaskRepository
            .findAllByImportTaskIdOrderBySchemaNameAscTableNameAsc(taskId);
        return toResponse(task, allTables);
    }

    @Transactional
    public ImportTaskResponse resumeImport(TenantEntity tenant, String dbId, String taskId) {
        ImportTaskEntity task = findTaskForTenant(tenant, dbId, taskId);
        if (task.getStatus() != ImportTaskStatus.PAUSED) {
            throw new IllegalStateException("Can only resume PAUSED tasks. Current status: " + task.getStatus());
        }

        DatabaseEntity database = databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));

        // Get non-completed table tasks
        List<ImportTableTaskEntity> pendingTables = importTableTaskRepository
            .findAllByImportTaskIdAndStatus(taskId, ImportTaskStatus.PENDING);

        task.setStatus(ImportTaskStatus.RUNNING);
        task.setStartedAt(Instant.now());

        String podName = importJobPodManager.launchJobPod(task, pendingTables, database);
        task.setJobPodName(podName);
        task = importTaskRepository.save(task);

        List<ImportTableTaskEntity> allTables = importTableTaskRepository
            .findAllByImportTaskIdOrderBySchemaNameAscTableNameAsc(taskId);
        return toResponse(task, allTables);
    }

    @Transactional
    public ImportTaskResponse cancelImport(TenantEntity tenant, String dbId, String taskId) {
        ImportTaskEntity task = findTaskForTenant(tenant, dbId, taskId);
        if (task.getStatus() != ImportTaskStatus.RUNNING
            && task.getStatus() != ImportTaskStatus.PAUSED
            && task.getStatus() != ImportTaskStatus.PENDING) {
            throw new IllegalStateException("Can only cancel RUNNING, PAUSED, or PENDING tasks. Current status: " + task.getStatus());
        }

        if (task.getJobPodName() != null) {
            importJobPodManager.deleteJobPod(taskId);
        }

        task.setStatus(ImportTaskStatus.CANCELLED);
        task.setFinishedAt(Instant.now());
        completeImportOperationLog(task, "Cancelled by user");

        // Restore original suspend timeout
        final String originalTimeout = task.getOriginalSuspendTimeout();
        final String taskDbId = task.getDatabaseId();
        if (originalTimeout != null) {
            databaseRepository.findById(taskDbId).ifPresent(db -> {
                db.setSuspendTimeout(originalTimeout);
                databaseRepository.save(db);
                log.info("Restored suspend timeout to {} for database {}", originalTimeout, db.getId());
            });
        }

        task = importTaskRepository.save(task);

        List<ImportTableTaskEntity> allTables = importTableTaskRepository
            .findAllByImportTaskIdOrderBySchemaNameAscTableNameAsc(taskId);
        return toResponse(task, allTables);
    }

    @Transactional
    public ImportTaskResponse retryImport(TenantEntity tenant, String dbId, String taskId) {
        ImportTaskEntity task = findTaskForTenant(tenant, dbId, taskId);
        if (task.getStatus() != ImportTaskStatus.FAILED && task.getStatus() != ImportTaskStatus.PARTIAL) {
            throw new IllegalStateException("Can only retry FAILED or PARTIAL tasks. Current status: " + task.getStatus());
        }

        DatabaseEntity database = databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));

        // Reset failed table tasks to PENDING
        List<ImportTableTaskEntity> failedTables = importTableTaskRepository
            .findAllByImportTaskIdAndStatus(taskId, ImportTaskStatus.FAILED);
        for (ImportTableTaskEntity tt : failedTables) {
            tt.setStatus(ImportTaskStatus.PENDING);
            tt.setErrorMessage(null);
            tt.setStartedAt(null);
            tt.setFinishedAt(null);
        }
        importTableTaskRepository.saveAll(failedTables);

        // Get all pending tables for the new job pod
        List<ImportTableTaskEntity> pendingTables = importTableTaskRepository
            .findAllByImportTaskIdAndStatus(taskId, ImportTaskStatus.PENDING);

        task.setStatus(ImportTaskStatus.RUNNING);
        task.setFinishedAt(null);
        task.setErrorMessage(null);

        String podName = importJobPodManager.launchJobPod(task, pendingTables, database);
        task.setJobPodName(podName);
        task = importTaskRepository.save(task);

        List<ImportTableTaskEntity> allTables = importTableTaskRepository
            .findAllByImportTaskIdOrderBySchemaNameAscTableNameAsc(taskId);
        return toResponse(task, allTables);
    }

    private void completeImportOperationLog(ImportTaskEntity task, String errorMessage) {
        if (task.getOperationLogId() == null) return;
        try {
            operationLogService.findById(task.getOperationLogId()).ifPresent(opLog ->
                operationLogService.completeOperation(opLog, errorMessage)
            );
        } catch (Exception e) {
            log.warn("Failed to complete operation log for import task {}: {}", task.getId(), e.getMessage());
        }
    }

    private ImportTaskEntity findTaskForTenant(TenantEntity tenant, String dbId, String taskId) {
        ImportTaskEntity task = importTaskRepository.findByIdAndTenantId(taskId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Import task not found: " + taskId));
        if (!task.getDatabaseId().equals(dbId)) {
            throw new NotFoundException("Import task not found: " + taskId);
        }
        return task;
    }

    private ImportTaskResponse toResponse(ImportTaskEntity task, List<ImportTableTaskEntity> tables) {
        List<ImportTableTaskResponse> tableResponses = null;
        if (tables != null) {
            tableResponses = tables.stream()
                .map(t -> new ImportTableTaskResponse(
                    t.getId(),
                    t.getSchemaName(),
                    t.getTableName(),
                    t.getStatus(),
                    t.getRowCount(),
                    t.getErrorMessage(),
                    t.getStartedAt(),
                    t.getFinishedAt()
                ))
                .toList();
        }
        return new ImportTaskResponse(
            task.getId(),
            task.getDatabaseId(),
            task.getSourceHost(),
            task.getSourcePort(),
            task.getSourceDbname(),
            task.getSourceUser(),
            task.getMode(),
            task.getConflictStrategy(),
            task.getStatus(),
            task.getTotalTables(),
            task.getCompletedTables(),
            task.getErrorMessage(),
            task.getCreatedAt(),
            task.getStartedAt(),
            task.getFinishedAt(),
            tableResponses
        );
    }

    /**
     * Periodically check for orphaned import tasks — tasks stuck in RUNNING
     * whose Job Pod no longer exists. Mark them as FAILED and restore suspend timeout.
     */
    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void checkOrphanedImportTasks() {
        List<ImportTaskEntity> runningTasks = importTaskRepository.findAllByStatus(ImportTaskStatus.RUNNING);
        for (ImportTaskEntity task : runningTasks) {
            if (task.getJobPodName() != null && !importJobPodManager.isJobPodRunning(task.getId())) {
                log.warn("Import task {} has no running Job Pod, marking as FAILED", task.getId());
                task.setStatus(ImportTaskStatus.FAILED);
                task.setErrorMessage("Import job pod disappeared unexpectedly");
                task.setFinishedAt(Instant.now());
                completeImportOperationLog(task, "Job pod disappeared");

                if (task.getOriginalSuspendTimeout() != null) {
                    databaseRepository.findById(task.getDatabaseId()).ifPresent(db -> {
                        db.setSuspendTimeout(task.getOriginalSuspendTimeout());
                        databaseRepository.save(db);
                        log.info("Restored suspend timeout to {} for database {}", task.getOriginalSuspendTimeout(), db.getId());
                    });
                }

                importTaskRepository.save(task);
            }
        }
    }
}
