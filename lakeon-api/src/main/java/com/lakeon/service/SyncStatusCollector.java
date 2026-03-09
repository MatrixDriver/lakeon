package com.lakeon.service;

import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.ImportTableTaskEntity;
import com.lakeon.model.entity.ImportTaskEntity;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.model.enums.ImportMode;
import com.lakeon.model.enums.ImportTaskStatus;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.ImportTableTaskRepository;
import com.lakeon.repository.ImportTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Properties;

@Component
public class SyncStatusCollector {
    private static final Logger log = LoggerFactory.getLogger(SyncStatusCollector.class);

    private final ImportTaskRepository importTaskRepository;
    private final ImportTableTaskRepository importTableTaskRepository;
    private final DatabaseRepository databaseRepository;
    private final LakeonProperties props;

    public SyncStatusCollector(ImportTaskRepository importTaskRepository,
                               ImportTableTaskRepository importTableTaskRepository,
                               DatabaseRepository databaseRepository,
                               LakeonProperties props) {
        this.importTaskRepository = importTaskRepository;
        this.importTableTaskRepository = importTableTaskRepository;
        this.databaseRepository = databaseRepository;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${lakeon.sync.poll-interval-ms:30000}")
    public void collectSyncStatus() {
        List<ImportTaskEntity> syncingTasks = importTaskRepository.findAllByStatus(ImportTaskStatus.SYNCING);
        syncingTasks.addAll(importTaskRepository.findAllByStatus(ImportTaskStatus.CATCHING_UP));

        for (ImportTaskEntity task : syncingTasks) {
            if (task.getMode() != ImportMode.SYNC) continue;
            try {
                collectTaskStatus(task);
            } catch (Exception e) {
                log.debug("Failed to collect sync status for task {}: {}", task.getId(), e.getMessage());
            }
        }
    }

    private void collectTaskStatus(ImportTaskEntity task) {
        DatabaseEntity database = databaseRepository.findById(task.getDatabaseId()).orElse(null);
        if (database == null || database.getStatus() != DatabaseStatus.RUNNING) {
            // Target is suspended or unknown — skip collection
            return;
        }

        // Query target database for subscription status
        String targetUrl = "jdbc:postgresql://" + database.getComputeHost() + ":55433/" + database.getName();
        Properties targetProps = new Properties();
        targetProps.setProperty("user", "cloud_admin");
        targetProps.setProperty("password", "cloud-admin-internal");
        targetProps.setProperty("connectTimeout", "5");
        targetProps.setProperty("socketTimeout", "10");

        try (Connection conn = DriverManager.getConnection(targetUrl, targetProps);
             Statement stmt = conn.createStatement()) {

            // Get replay lag from pg_stat_subscription
            ResultSet lagRs = stmt.executeQuery(
                "SELECT EXTRACT(EPOCH FROM (now() - latest_end_time))::numeric " +
                "FROM pg_stat_subscription WHERE subname = '" + task.getSubscriptionName() + "' LIMIT 1"
            );
            if (lagRs.next()) {
                double lag = lagRs.getDouble(1);
                task.setReplayLagSeconds(lag);
                task.setLastSyncAt(Instant.now());

                // If catching up and lag is small, transition to SYNCING
                if (task.getStatus() == ImportTaskStatus.CATCHING_UP && lag < 10) {
                    task.setStatus(ImportTaskStatus.SYNCING);
                    task.setSyncStatus("SYNCING");
                }
            }

            // Get per-table sync states from pg_subscription_rel
            ResultSet relRs = stmt.executeQuery(
                "SELECT srrelid::regclass::text, srsubstate " +
                "FROM pg_subscription_rel sr " +
                "JOIN pg_subscription s ON sr.srsubid = s.oid " +
                "WHERE s.subname = '" + task.getSubscriptionName() + "'"
            );

            List<ImportTableTaskEntity> tableTasks = importTableTaskRepository
                .findAllByImportTaskIdOrderBySchemaNameAscTableNameAsc(task.getId());

            while (relRs.next()) {
                String relName = relRs.getString(1); // schema.table or just table
                String state = relRs.getString(2);

                // Match to table task entity
                for (ImportTableTaskEntity tt : tableTasks) {
                    String fullName = tt.getSchemaName() + "." + tt.getTableName();
                    if (relName.equals(fullName) || relName.equals(tt.getTableName())) {
                        tt.setSyncState(state);
                        importTableTaskRepository.save(tt);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to query target for sync status: {}", e.getMessage());
        }

        // Query source database for WAL retention
        try {
            String sourceUrl = "jdbc:postgresql://" + task.getSourceHost() + ":" + task.getSourcePort() + "/" + task.getSourceDbname();
            Properties sourceProps = new Properties();
            sourceProps.setProperty("user", task.getSourceUser());
            sourceProps.setProperty("password", task.getSourcePassword());
            sourceProps.setProperty("connectTimeout", "5");
            sourceProps.setProperty("socketTimeout", "10");

            try (Connection conn = DriverManager.getConnection(sourceUrl, sourceProps);
                 Statement stmt = conn.createStatement()) {
                ResultSet walRs = stmt.executeQuery(
                    "SELECT pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn) " +
                    "FROM pg_replication_slots WHERE slot_name = '" + task.getSlotName() + "'"
                );
                if (walRs.next()) {
                    long walBytes = walRs.getLong(1);
                    task.setWalRetainedBytes(walBytes);
                    task.setWalWarning(walBytes > props.getSync().getWalWarnBytes());
                }
            }
        } catch (Exception e) {
            log.debug("Failed to query source for WAL retention: {}", e.getMessage());
        }

        importTaskRepository.save(task);
    }
}
