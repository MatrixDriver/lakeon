package com.lakeon.service;

import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "lakeon.backup.scheduled-enabled", havingValue = "true")
public class BackupScheduler {
    private static final Logger log = LoggerFactory.getLogger(BackupScheduler.class);

    private final DatabaseRepository databaseRepository;
    private final TenantRepository tenantRepository;
    private final BackupService backupService;
    private final LakeonProperties props;

    public BackupScheduler(DatabaseRepository databaseRepository,
                           TenantRepository tenantRepository,
                           BackupService backupService,
                           LakeonProperties props) {
        this.databaseRepository = databaseRepository;
        this.tenantRepository = tenantRepository;
        this.backupService = backupService;
        this.props = props;
    }

    @Scheduled(cron = "${lakeon.backup.cron:0 0 2 * * ?}")
    public void runScheduledBackups() {
        log.info("Starting scheduled backup run");
        int retentionCount = props.getBackup().getRetentionCount();

        List<DatabaseEntity> databases = databaseRepository.findAll();
        int successCount = 0;
        int failCount = 0;

        for (DatabaseEntity db : databases) {
            try {
                TenantEntity tenant = tenantRepository.findById(db.getTenantId()).orElse(null);
                if (tenant == null) {
                    log.warn("Tenant not found for database {}, skipping backup", db.getId());
                    continue;
                }

                backupService.createScheduledBackup(tenant, db.getId());
                backupService.cleanupOldBackups(db.getId(), retentionCount);
                successCount++;
            } catch (Exception e) {
                failCount++;
                log.error("Failed to create scheduled backup for database {}: {}", db.getId(), e.getMessage());
            }
        }

        log.info("Scheduled backup run completed: {} success, {} failed out of {} databases",
            successCount, failCount, databases.size());
    }
}
