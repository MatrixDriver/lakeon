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
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.neon.NeonApiClient;
import com.lakeon.neon.dto.NeonTimeline;
import com.lakeon.repository.BackupRepository;
import com.lakeon.repository.BranchRepository;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.exception.NotFoundException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BackupService 单元测试")
class BackupServiceTest {

    @Mock
    private BackupRepository backupRepository;

    @Mock
    private DatabaseRepository databaseRepository;

    @Mock
    private BranchRepository branchRepository;

    @Mock
    private NeonApiClient neonApiClient;

    @Mock
    private OperationLogService operationLogService;

    @Mock
    private DatabaseService databaseService;

    @InjectMocks
    private BackupService backupService;

    private TenantEntity testTenant;
    private DatabaseEntity testDatabase;

    @BeforeEach
    void setUp() {
        testTenant = new TenantEntity();
        testTenant.setId("tn_test001");

        testDatabase = new DatabaseEntity();
        testDatabase.setId("db_test001");
        testDatabase.setTenantId("tn_test001");
        testDatabase.setName("my-db");
        testDatabase.setStatus(DatabaseStatus.RUNNING);
        testDatabase.setNeonTenantId("neon-tenant-abc");
        testDatabase.setNeonTimelineId("neon-timeline-main");
        testDatabase.setComputeSize("0.25");
        testDatabase.setSuspendTimeout("300");
        testDatabase.setStorageLimitGb(10);
        testDatabase.setDbUser("cloud_admin");
        testDatabase.setDbPassword("secret123");
    }

    @Nested
    @DisplayName("创建备份")
    class CreateBackup {

        @Test
        @DisplayName("UT-SVC-BK-001: 正常创建 — 获取 LSN，创建 branch timeline，保存实体")
        void createBackup_success() {
            // Given
            var request = new CreateBackupRequest("my-backup");
            when(databaseRepository.findByIdAndTenantId("db_test001", "tn_test001"))
                    .thenReturn(Optional.of(testDatabase));

            NeonTimeline currentTimeline = new NeonTimeline("neon-timeline-main");
            currentTimeline.setLastRecordLsn("0/1A2B3C0");
            currentTimeline.setCurrentLogicalSize(1024000L);
            when(neonApiClient.getTimeline("neon-tenant-abc", "neon-timeline-main"))
                    .thenReturn(currentTimeline);
            when(neonApiClient.createTimeline(eq("neon-tenant-abc"), any()))
                    .thenReturn(new NeonTimeline("neon-timeline-backup-1"));
            when(backupRepository.save(any(BackupEntity.class)))
                    .thenAnswer(inv -> {
                        BackupEntity entity = inv.getArgument(0);
                        entity.setId("bk_test001");
                        return entity;
                    });
            when(operationLogService.startOperation(any(), any(), any(), any()))
                    .thenReturn(new OperationLogEntity());

            // When
            BackupResponse result = backupService.createBackup(testTenant, "db_test001", request);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.name()).isEqualTo("my-backup");
            assertThat(result.status()).isEqualTo(BackupStatus.COMPLETED);
            assertThat(result.type()).isEqualTo(BackupType.MANUAL);
            assertThat(result.lsn()).isEqualTo("0/1A2B3C0");
            assertThat(result.sizeBytes()).isEqualTo(1024000L);
            verify(neonApiClient).getTimeline("neon-tenant-abc", "neon-timeline-main");
            verify(neonApiClient).createTimeline(eq("neon-tenant-abc"), any());
            verify(backupRepository).save(any(BackupEntity.class));
            verify(operationLogService).completeOperation(any(), isNull());
        }

        @Test
        @DisplayName("UT-SVC-BK-002: 空名称 — 自动生成名称")
        void createBackup_emptyName_autoGenerate() {
            // Given
            var request = new CreateBackupRequest("");
            when(databaseRepository.findByIdAndTenantId("db_test001", "tn_test001"))
                    .thenReturn(Optional.of(testDatabase));

            NeonTimeline timeline = new NeonTimeline("neon-timeline-main");
            timeline.setLastRecordLsn("0/1000");
            timeline.setCurrentLogicalSize(500L);
            when(neonApiClient.getTimeline(any(), any())).thenReturn(timeline);
            when(neonApiClient.createTimeline(any(), any())).thenReturn(new NeonTimeline("tl-bk"));
            when(backupRepository.save(any(BackupEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(operationLogService.startOperation(any(), any(), any(), any()))
                    .thenReturn(new OperationLogEntity());

            // When
            BackupResponse result = backupService.createBackup(testTenant, "db_test001", request);

            // Then
            assertThat(result.name()).startsWith("backup-");
        }

        @Test
        @DisplayName("UT-SVC-BK-003: 数据库不存在 — 抛出 NotFoundException")
        void createBackup_dbNotFound() {
            var request = new CreateBackupRequest("test");
            when(databaseRepository.findByIdAndTenantId("db_nonexist", "tn_test001"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    backupService.createBackup(testTenant, "db_nonexist", request))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("列出备份")
    class ListBackups {

        @Test
        @DisplayName("UT-SVC-BK-004: 正常 — 返回数据库下所有备份")
        void listBackups_success() {
            when(databaseRepository.findByIdAndTenantId("db_test001", "tn_test001"))
                    .thenReturn(Optional.of(testDatabase));

            BackupEntity bk1 = createBackupEntity("bk_001", "backup-1");
            BackupEntity bk2 = createBackupEntity("bk_002", "backup-2");
            when(backupRepository.findByDatabaseIdOrderByCreatedAtDesc("db_test001"))
                    .thenReturn(List.of(bk1, bk2));

            List<BackupResponse> result = backupService.listBackups(testTenant, "db_test001");

            assertThat(result).hasSize(2);
            assertThat(result).extracting(BackupResponse::name).containsExactly("backup-1", "backup-2");
        }

        @Test
        @DisplayName("UT-SVC-BK-005: 数据库不存在 — 抛出 NotFoundException")
        void listBackups_dbNotFound() {
            when(databaseRepository.findByIdAndTenantId("db_nonexist", "tn_test001"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    backupService.listBackups(testTenant, "db_nonexist"))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("删除备份")
    class DeleteBackup {

        @Test
        @DisplayName("UT-SVC-BK-006: 正常 — 删除 Neon timeline 并清除实体")
        void deleteBackup_success() {
            BackupEntity backup = createBackupEntity("bk_del001", "backup-del");
            backup.setNeonTenantId("neon-tenant-abc");
            backup.setNeonTimelineId("neon-timeline-bk-del");

            when(backupRepository.findByIdAndTenantId("bk_del001", "tn_test001"))
                    .thenReturn(Optional.of(backup));

            backupService.deleteBackup(testTenant, "db_test001", "bk_del001");

            verify(neonApiClient).deleteTimeline("neon-tenant-abc", "neon-timeline-bk-del");
            verify(backupRepository).delete(backup);
        }

        @Test
        @DisplayName("UT-SVC-BK-007: 备份不存在 — 抛出 NotFoundException")
        void deleteBackup_notFound() {
            when(backupRepository.findByIdAndTenantId("bk_nonexist", "tn_test001"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    backupService.deleteBackup(testTenant, "db_test001", "bk_nonexist"))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("UT-SVC-BK-008: Neon 删除失败 — 不影响实体删除")
        void deleteBackup_neonFailure_stillDeletesEntity() {
            BackupEntity backup = createBackupEntity("bk_del002", "backup-del2");
            backup.setNeonTenantId("neon-tenant-abc");
            backup.setNeonTimelineId("neon-timeline-bk-fail");

            when(backupRepository.findByIdAndTenantId("bk_del002", "tn_test001"))
                    .thenReturn(Optional.of(backup));
            doThrow(new RuntimeException("Neon unavailable"))
                    .when(neonApiClient).deleteTimeline(any(), any());

            backupService.deleteBackup(testTenant, "db_test001", "bk_del002");

            verify(backupRepository).delete(backup);
        }
    }

    @Nested
    @DisplayName("从备份恢复")
    class RestoreFromBackup {

        @Test
        @DisplayName("UT-SVC-BK-009: 正常恢复 — 创建新 timeline 和新数据库")
        void restore_success() {
            BackupEntity backup = createBackupEntity("bk_restore", "backup-for-restore");
            backup.setStatus(BackupStatus.COMPLETED);
            backup.setNeonTenantId("neon-tenant-abc");
            backup.setNeonTimelineId("neon-timeline-bk-restore");
            backup.setLsn("0/5000");

            when(backupRepository.findByIdAndTenantId("bk_restore", "tn_test001"))
                    .thenReturn(Optional.of(backup));
            when(databaseRepository.findByIdAndTenantId("db_test001", "tn_test001"))
                    .thenReturn(Optional.of(testDatabase));
            when(neonApiClient.createTimeline(eq("neon-tenant-abc"), any()))
                    .thenReturn(new NeonTimeline("neon-timeline-restored"));
            when(databaseService.buildConnectionUri("cloud_admin", "my-db", "restored-db"))
                    .thenReturn("postgres://cloud_admin@pg.dbay.cloud:5432/my-db?options=endpoint%3Drestored-db");
            when(databaseRepository.save(any(DatabaseEntity.class)))
                    .thenAnswer(inv -> {
                        DatabaseEntity db = inv.getArgument(0);
                        db.setId("db_restored001");
                        return db;
                    });
            when(operationLogService.startOperation(any(), any(), any(), any()))
                    .thenReturn(new OperationLogEntity());

            var request = new RestoreFromBackupRequest("restored-db");
            DatabaseEntity result = backupService.restoreFromBackup(testTenant, "db_test001", "bk_restore", request);

            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("restored-db");
            assertThat(result.getNeonTimelineId()).isEqualTo("neon-timeline-restored");
            assertThat(result.getConnectionUri()).contains("/my-db?options=endpoint%3Drestored-db");
            assertThat(result.isRecoveredFromPitr()).isTrue();
            assertThat(result.getStatus()).isEqualTo(DatabaseStatus.SUSPENDED);
            verify(neonApiClient).createTimeline(eq("neon-tenant-abc"), any());
            verify(databaseService).buildConnectionUri("cloud_admin", "my-db", "restored-db");
            verify(databaseRepository).save(any(DatabaseEntity.class));
        }

        @Test
        @DisplayName("UT-SVC-BK-010: 备份未完成 — 抛出 IllegalStateException")
        void restore_incompleteBackup() {
            BackupEntity backup = createBackupEntity("bk_pending", "backup-pending");
            backup.setStatus(BackupStatus.RUNNING);

            when(backupRepository.findByIdAndTenantId("bk_pending", "tn_test001"))
                    .thenReturn(Optional.of(backup));

            var request = new RestoreFromBackupRequest("new-db");
            assertThatThrownBy(() ->
                    backupService.restoreFromBackup(testTenant, "db_test001", "bk_pending", request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("RUNNING");
        }
    }

    @Nested
    @DisplayName("清理旧备份")
    class CleanupOldBackups {

        @Test
        @DisplayName("UT-SVC-BK-011: 超过保留数量 — 清理定时备份")
        void cleanup_deletesOldScheduled() {
            BackupEntity bk1 = createBackupEntity("bk_1", "auto-1");
            bk1.setType(BackupType.SCHEDULED);
            bk1.setNeonTenantId("nt");
            bk1.setNeonTimelineId("tl1");

            BackupEntity bk2 = createBackupEntity("bk_2", "auto-2");
            bk2.setType(BackupType.SCHEDULED);
            bk2.setNeonTenantId("nt");
            bk2.setNeonTimelineId("tl2");

            BackupEntity bk3 = createBackupEntity("bk_3", "auto-3");
            bk3.setType(BackupType.SCHEDULED);
            bk3.setNeonTenantId("nt");
            bk3.setNeonTimelineId("tl3");

            when(backupRepository.findByDatabaseIdOrderByCreatedAtDesc("db_test001"))
                    .thenReturn(List.of(bk1, bk2, bk3));

            backupService.cleanupOldBackups("db_test001", 1);

            verify(backupRepository).delete(bk2);
            verify(backupRepository).delete(bk3);
            verify(backupRepository, never()).delete(bk1);
        }

        @Test
        @DisplayName("UT-SVC-BK-012: 未超过保留数量 — 不做任何操作")
        void cleanup_noAction() {
            BackupEntity bk1 = createBackupEntity("bk_1", "auto-1");
            when(backupRepository.findByDatabaseIdOrderByCreatedAtDesc("db_test001"))
                    .thenReturn(List.of(bk1));

            backupService.cleanupOldBackups("db_test001", 5);

            verify(backupRepository, never()).delete(any());
        }
    }

    private BackupEntity createBackupEntity(String id, String name) {
        BackupEntity entity = new BackupEntity();
        entity.setId(id);
        entity.setDatabaseId("db_test001");
        entity.setTenantId("tn_test001");
        entity.setName(name);
        entity.setStatus(BackupStatus.COMPLETED);
        entity.setType(BackupType.MANUAL);
        entity.setLsn("0/1000");
        entity.setSizeBytes(1024L);
        entity.setCreatedAt(Instant.now());
        return entity;
    }
}
