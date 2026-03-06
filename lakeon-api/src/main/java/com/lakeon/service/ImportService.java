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
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.ImportTableTaskRepository;
import com.lakeon.repository.ImportTaskRepository;
import com.lakeon.service.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.*;

@Service
public class ImportService {
    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final ImportTaskRepository importTaskRepository;
    private final ImportTableTaskRepository importTableTaskRepository;
    private final DatabaseRepository databaseRepository;
    private final ImportJobPodManager importJobPodManager;
    private final ComputePodManager computePodManager;
    private final LakeonProperties props;

    public ImportService(ImportTaskRepository importTaskRepository,
                         ImportTableTaskRepository importTableTaskRepository,
                         DatabaseRepository databaseRepository,
                         ImportJobPodManager importJobPodManager,
                         ComputePodManager computePodManager,
                         LakeonProperties props) {
        this.importTaskRepository = importTaskRepository;
        this.importTableTaskRepository = importTableTaskRepository;
        this.databaseRepository = databaseRepository;
        this.importJobPodManager = importJobPodManager;
        this.computePodManager = computePodManager;
        this.props = props;
    }

    public Map<String, Object> testConnection(TestConnectionRequest req) {
        Properties connProps = new Properties();
        connProps.setProperty("user", req.user());
        connProps.setProperty("password", req.password());
        connProps.setProperty("loginTimeout", "5");
        connProps.setProperty("connectTimeout", "5");
        connProps.setProperty("socketTimeout", "5");

        String url = "jdbc:postgresql://" + req.host() + ":" + req.port() + "/" + req.dbname();
        try (Connection conn = DriverManager.getConnection(url, connProps)) {
            return Map.of("success", true, "message", "连接成功");
        } catch (Exception e) {
            log.warn("Test connection failed for {}:{}/{}: {}", req.host(), req.port(), req.dbname(), e.getMessage());
            return Map.of("success", false, "message", e.getMessage());
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
        String sql = "SELECT t.table_schema, t.table_name, COALESCE(c.reltuples::bigint, 0) " +
            "FROM information_schema.tables t " +
            "LEFT JOIN pg_class c ON c.relname = t.table_name " +
            "AND c.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = t.table_schema) " +
            "WHERE t.table_type = 'BASE TABLE' " +
            "AND t.table_schema NOT IN ('pg_catalog', 'information_schema') " +
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

        // Ensure compute is running
        if (database.getStatus() != DatabaseStatus.RUNNING) {
            throw new IllegalStateException("Database must be in RUNNING state to import data. Current status: " + database.getStatus());
        }

        // Create ImportTaskEntity
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

        // Determine tables to import
        List<SourceTableInfo> sourceTables;
        if (req.mode() == ImportMode.FULL) {
            TestConnectionRequest connReq = new TestConnectionRequest(
                req.sourceHost(), req.sourcePort(), req.sourceDbname(), req.sourceUser(), req.sourcePassword()
            );
            sourceTables = listSourceTables(connReq);
        } else {
            // SELECTIVE: parse "schema.table" format
            sourceTables = new ArrayList<>();
            if (req.tables() != null) {
                for (String tableSpec : req.tables()) {
                    String[] parts = tableSpec.split("\\.", 2);
                    String schema = parts.length > 1 ? parts[0] : "public";
                    String table = parts.length > 1 ? parts[1] : parts[0];
                    sourceTables.add(new SourceTableInfo(schema, table, 0));
                }
            }
        }

        task.setTotalTables(sourceTables.size());
        task.setCompletedTables(0);
        // Save task first to generate ID via @PrePersist
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

        // Set task to RUNNING and launch job pod
        task.setStatus(ImportTaskStatus.RUNNING);
        task.setStartedAt(Instant.now());

        String podName = importJobPodManager.launchJobPod(task, tableTasks, database);
        task.setJobPodName(podName);
        task = importTaskRepository.save(task);

        return toResponse(task, tableTasks);
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
            if (failedCount == 0) {
                task.setStatus(ImportTaskStatus.COMPLETED);
            } else if (task.getCompletedTables() > 0) {
                task.setStatus(ImportTaskStatus.PARTIAL);
            } else {
                task.setStatus(ImportTaskStatus.FAILED);
            }
            task.setFinishedAt(Instant.now());
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
}
