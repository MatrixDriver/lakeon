package com.lakeon.service;

import com.lakeon.model.dto.BackupResponse;
import com.lakeon.model.dto.CreateBackupRequest;
import com.lakeon.model.dto.RestoreFromBackupRequest;
import com.lakeon.model.entity.BackupEntity;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.OperationLogEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.enums.BackupStatus;
import com.lakeon.model.enums.BackupType;
import com.lakeon.model.enums.OperationType;
import com.lakeon.neon.NeonApiClient;
import com.lakeon.neon.dto.CreateTimelineRequest;
import com.lakeon.neon.dto.NeonTimeline;
import com.lakeon.repository.BackupRepository;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;

@Service
public class BackupService {
    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    private final BackupRepository backupRepository;
    private final DatabaseRepository databaseRepository;
    private final NeonApiClient neonApiClient;
    private final OperationLogService operationLogService;
    private final DatabaseService databaseService;

    public BackupService(BackupRepository backupRepository,
                         DatabaseRepository databaseRepository,
                         NeonApiClient neonApiClient,
                         OperationLogService operationLogService,
                         DatabaseService databaseService) {
        this.backupRepository = backupRepository;
        this.databaseRepository = databaseRepository;
        this.neonApiClient = neonApiClient;
        this.operationLogService = operationLogService;
        this.databaseService = databaseService;
    }

    @Transactional
    public BackupResponse createBackup(TenantEntity tenant, String dbId, CreateBackupRequest req) {
        return createBackupInternal(tenant, dbId, req.name(), BackupType.MANUAL);
    }

    @Transactional
    public BackupResponse createBackupInternal(TenantEntity tenant, String dbId, String name, BackupType type) {
        DatabaseEntity database = databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));

        String backupName = name;
        if (backupName == null || backupName.isBlank()) {
            backupName = "backup-" + Instant.now().toString().replace(":", "-").substring(0, 19);
        }

        OperationLogEntity opLog = operationLogService.startOperation(
            dbId, tenant.getId(), database.getName(), OperationType.BACKUP);

        try {
            // Get current LSN from the database's timeline
            NeonTimeline currentTimeline = neonApiClient.getTimeline(
                database.getNeonTenantId(), database.getNeonTimelineId());
            String currentLsn = currentTimeline.getLastRecordLsn();
            Long currentSize = currentTimeline.getCurrentLogicalSize();

            // Create a branch timeline at the current LSN (instant operation in Neon)
            String newTimelineId = generateHexId();
            CreateTimelineRequest timelineReq = new CreateTimelineRequest(
                newTimelineId, database.getNeonTimelineId(), currentLsn);
            NeonTimeline backupTimeline = neonApiClient.createTimeline(
                database.getNeonTenantId(), timelineReq);

            // Create backup entity
            BackupEntity backup = new BackupEntity();
            backup.setDatabaseId(dbId);
            backup.setTenantId(tenant.getId());
            backup.setName(backupName);
            backup.setStatus(BackupStatus.COMPLETED);
            backup.setType(type);
            backup.setNeonTenantId(database.getNeonTenantId());
            backup.setNeonTimelineId(backupTimeline.getTimelineId());
            backup.setSourceTenantId(database.getNeonTenantId());
            backup.setSourceTimelineId(database.getNeonTimelineId());
            backup.setLsn(currentLsn);
            backup.setSizeBytes(currentSize);
            backup.setCompletedAt(Instant.now());

            backup = backupRepository.save(backup);
            operationLogService.completeOperation(opLog, null);

            log.info("Created backup {} for database {} at LSN {}", backup.getId(), dbId, currentLsn);
            return toResponse(backup);
        } catch (Exception e) {
            operationLogService.completeOperation(opLog, e.getMessage());
            throw e;
        }
    }

    public BackupResponse getBackup(TenantEntity tenant, String dbId, String backupId) {
        BackupEntity backup = findBackupForTenant(tenant, dbId, backupId);
        return toResponse(backup);
    }

    public List<BackupResponse> listBackups(TenantEntity tenant, String dbId) {
        // Validate database belongs to tenant
        databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));

        return backupRepository.findByDatabaseIdOrderByCreatedAtDesc(dbId).stream()
            .map(this::toResponse)
            .toList();
    }

    @Transactional
    public void deleteBackup(TenantEntity tenant, String dbId, String backupId) {
        BackupEntity backup = findBackupForTenant(tenant, dbId, backupId);

        // Delete backup timeline from Neon (best-effort)
        if (backup.getNeonTenantId() != null && backup.getNeonTimelineId() != null) {
            try {
                neonApiClient.deleteTimeline(backup.getNeonTenantId(), backup.getNeonTimelineId());
                log.info("Deleted backup timeline {} from Neon", backup.getNeonTimelineId());
            } catch (Exception e) {
                log.warn("Failed to delete backup timeline {} from Neon: {}",
                    backup.getNeonTimelineId(), e.getMessage());
            }
        }

        backupRepository.delete(backup);
        log.info("Deleted backup {} for database {}", backupId, dbId);
    }

    @Transactional
    public DatabaseEntity restoreFromBackup(TenantEntity tenant, String dbId, String backupId,
                                             RestoreFromBackupRequest req) {
        BackupEntity backup = findBackupForTenant(tenant, dbId, backupId);

        if (backup.getStatus() != BackupStatus.COMPLETED) {
            throw new IllegalStateException("Cannot restore from backup with status: " + backup.getStatus());
        }

        DatabaseEntity sourceDb = databaseRepository.findByIdAndTenantId(dbId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Database not found: " + dbId));

        OperationLogEntity opLog = operationLogService.startOperation(
            dbId, tenant.getId(), sourceDb.getName(), OperationType.RESTORE);

        try {
            // Create a new timeline branched from the backup's timeline
            String newTimelineId = generateHexId();
            CreateTimelineRequest timelineReq = new CreateTimelineRequest(
                newTimelineId, backup.getNeonTimelineId(), backup.getLsn());
            NeonTimeline restoredTimeline = neonApiClient.createTimeline(
                backup.getNeonTenantId(), timelineReq);

            // Create a new database entity pointing to the restored timeline
            // Reuse the same Neon tenant but with the new timeline
            DatabaseEntity restoredDb = new DatabaseEntity();
            restoredDb.setName(req.name());
            restoredDb.setTenantId(tenant.getId());
            restoredDb.setNeonTenantId(backup.getNeonTenantId());
            restoredDb.setNeonTimelineId(restoredTimeline.getTimelineId());
            restoredDb.setComputeSize(sourceDb.getComputeSize());
            restoredDb.setSuspendTimeout(sourceDb.getSuspendTimeout());
            restoredDb.setStorageLimitGb(sourceDb.getStorageLimitGb());
            restoredDb.setDbUser(sourceDb.getDbUser());
            restoredDb.setDbPassword(sourceDb.getDbPassword());
            restoredDb.setComputeHost(sourceDb.getComputeHost());
            restoredDb.setComputePort(sourceDb.getComputePort());
            restoredDb.setStatus(com.lakeon.model.enums.DatabaseStatus.SUSPENDED);

            restoredDb = databaseRepository.save(restoredDb);

            operationLogService.completeOperation(opLog, null);
            log.info("Restored database {} from backup {} as new database {}",
                dbId, backupId, restoredDb.getId());

            return restoredDb;
        } catch (Exception e) {
            operationLogService.completeOperation(opLog, e.getMessage());
            throw e;
        }
    }

    /**
     * Create a scheduled backup for a database. Used by BackupScheduler.
     */
    public BackupResponse createScheduledBackup(TenantEntity tenant, String dbId) {
        String name = "auto-" + Instant.now().toString().replace(":", "-").substring(0, 19);
        return createBackupInternal(tenant, dbId, name, BackupType.SCHEDULED);
    }

    /**
     * Delete old backups beyond retention count for a given database.
     */
    @Transactional
    public void cleanupOldBackups(String dbId, int retentionCount) {
        List<BackupEntity> backups = backupRepository.findByDatabaseIdOrderByCreatedAtDesc(dbId);
        if (backups.size() <= retentionCount) return;

        List<BackupEntity> toDelete = backups.subList(retentionCount, backups.size());
        for (BackupEntity backup : toDelete) {
            if (backup.getType() != BackupType.SCHEDULED) continue; // Only auto-clean scheduled backups
            try {
                if (backup.getNeonTenantId() != null && backup.getNeonTimelineId() != null) {
                    neonApiClient.deleteTimeline(backup.getNeonTenantId(), backup.getNeonTimelineId());
                }
                backupRepository.delete(backup);
                log.info("Cleaned up old scheduled backup {} for database {}", backup.getId(), dbId);
            } catch (Exception e) {
                log.warn("Failed to cleanup backup {}: {}", backup.getId(), e.getMessage());
            }
        }
    }

    private BackupEntity findBackupForTenant(TenantEntity tenant, String dbId, String backupId) {
        BackupEntity backup = backupRepository.findByIdAndTenantId(backupId, tenant.getId())
            .orElseThrow(() -> new NotFoundException("Backup not found: " + backupId));
        if (!backup.getDatabaseId().equals(dbId)) {
            throw new NotFoundException("Backup not found: " + backupId);
        }
        return backup;
    }

    private BackupResponse toResponse(BackupEntity entity) {
        return new BackupResponse(
            entity.getId(),
            entity.getDatabaseId(),
            entity.getTenantId(),
            entity.getName(),
            entity.getStatus(),
            entity.getType(),
            entity.getNeonTenantId(),
            entity.getNeonTimelineId(),
            entity.getSourceTenantId(),
            entity.getSourceTimelineId(),
            entity.getLsn(),
            entity.getSizeBytes(),
            entity.getCreatedAt(),
            entity.getCompletedAt(),
            entity.getErrorMessage()
        );
    }

    private String generateHexId() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
