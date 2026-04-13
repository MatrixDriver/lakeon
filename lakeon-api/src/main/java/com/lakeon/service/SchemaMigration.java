package com.lakeon.service;

import com.lakeon.dataset.DatasetSourceType;
import com.lakeon.knowledge.DocumentStatus;
import com.lakeon.model.entity.BranchEntity;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.enums.ComputeStatus;
import com.lakeon.knowledge.KbWriteTaskType;
import com.lakeon.model.enums.OperationType;
import com.lakeon.repository.BranchRepository;
import com.lakeon.repository.DatabaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Startup migration: keep CHECK constraints in sync with Java enums.
 */
@Component
public class SchemaMigration {
    private static final Logger log = LoggerFactory.getLogger(SchemaMigration.class);

    private final DataSource dataSource;
    private final DatabaseRepository databaseRepository;
    private final BranchRepository branchRepository;

    public SchemaMigration(DataSource dataSource,
                           DatabaseRepository databaseRepository,
                           BranchRepository branchRepository) {
        this.dataSource = dataSource;
        this.databaseRepository = databaseRepository;
        this.branchRepository = branchRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncOperationTypeConstraint() {
        String values = Arrays.stream(OperationType.values())
                .map(e -> "'" + e.name() + "'")
                .collect(Collectors.joining(","));

        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE operation_logs DROP CONSTRAINT IF EXISTS operation_logs_operation_type_check");
            st.execute("ALTER TABLE operation_logs ADD CONSTRAINT operation_logs_operation_type_check " +
                    "CHECK (operation_type::text = ANY(ARRAY[" + values + "]))");
            log.info("Synced operation_logs CHECK constraint with OperationType enum: {}", values);
        } catch (Exception e) {
            log.warn("Failed to sync operation_type CHECK constraint: {}", e.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncKbWriteTaskTypeConstraint() {
        String values = Arrays.stream(KbWriteTaskType.values())
                .map(e -> "'" + e.name() + "'")
                .collect(Collectors.joining(","));

        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE kb_write_tasks DROP CONSTRAINT IF EXISTS kb_write_tasks_type_check");
            st.execute("ALTER TABLE kb_write_tasks ADD CONSTRAINT kb_write_tasks_type_check " +
                    "CHECK (type::text = ANY(ARRAY[" + values + "]))");
            log.info("Synced kb_write_tasks CHECK constraint with KbWriteTaskType enum: {}", values);
        } catch (Exception e) {
            log.warn("Failed to sync kb_write_tasks type CHECK constraint: {}", e.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncDocumentStatusConstraint() {
        String values = Arrays.stream(DocumentStatus.values())
                .map(e -> "'" + e.name() + "'")
                .collect(Collectors.joining(","));

        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE documents DROP CONSTRAINT IF EXISTS documents_status_check");
            st.execute("ALTER TABLE documents ADD CONSTRAINT documents_status_check " +
                    "CHECK (status::text = ANY(ARRAY[" + values + "]))");
            log.info("Synced documents CHECK constraint with DocumentStatus enum: {}", values);
        } catch (Exception e) {
            log.warn("Failed to sync documents status CHECK constraint: {}", e.getMessage());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void syncDatasetSourceTypeConstraint() {
        String values = Arrays.stream(DatasetSourceType.values())
                .map(e -> "'" + e.name() + "'")
                .collect(Collectors.joining(","));

        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE datasets DROP CONSTRAINT IF EXISTS datasets_source_type_check");
            st.execute("ALTER TABLE datasets ADD CONSTRAINT datasets_source_type_check " +
                    "CHECK (source_type::text = ANY(ARRAY[" + values + "]))");
            log.info("Synced datasets CHECK constraint with DatasetSourceType enum: {}", values);
        } catch (Exception e) {
            log.warn("Failed to sync datasets source_type CHECK constraint: {}", e.getMessage());
        }
    }

    /**
     * Migrate existing databases: copy compute fields from database to its default branch.
     * This ensures branches created before the branch-level compute feature get their compute state.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void migrateComputeToDefaultBranches() {
        List<DatabaseEntity> allDatabases = databaseRepository.findAll();
        int migrated = 0;
        for (DatabaseEntity db : allDatabases) {
            if (db.getComputePodName() == null) continue;
            BranchEntity defaultBranch = branchRepository.findByDatabaseIdAndIsDefaultTrue(db.getId())
                    .orElse(null);
            if (defaultBranch == null) continue;
            if (defaultBranch.getComputePodName() != null) continue; // already migrated
            defaultBranch.setComputePodName(db.getComputePodName());
            defaultBranch.setComputeHost(db.getComputeHost());
            defaultBranch.setComputePort(db.getComputePort());
            defaultBranch.setComputeStatus(ComputeStatus.RUNNING);
            defaultBranch.setSuspendTimeout(db.getSuspendTimeout());
            defaultBranch.setLastActiveAt(db.getLastActiveAt() != null ? db.getLastActiveAt() : Instant.now());
            branchRepository.save(defaultBranch);
            migrated++;
        }
        if (migrated > 0) {
            log.info("Migrated compute fields to {} default branches", migrated);
        }
    }
}
