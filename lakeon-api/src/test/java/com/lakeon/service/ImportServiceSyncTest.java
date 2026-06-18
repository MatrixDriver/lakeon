package com.lakeon.service;

import com.lakeon.config.LakeonProperties;
import com.lakeon.k8s.ComputePodManager;
import com.lakeon.k8s.ImportJobPodManager;
import com.lakeon.model.dto.CreateImportRequest;
import com.lakeon.model.dto.ImportCallbackRequest;
import com.lakeon.model.dto.ImportTaskResponse;
import com.lakeon.model.dto.SyncStatusResponse;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.ImportTableTaskEntity;
import com.lakeon.model.entity.ImportTaskEntity;
import com.lakeon.model.entity.OperationLogEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.enums.ConflictStrategy;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.model.enums.ImportMode;
import com.lakeon.model.enums.ImportTaskStatus;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.ImportTableTaskRepository;
import com.lakeon.repository.ImportTaskRepository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ImportService Sync 相关单元测试")
class ImportServiceSyncTest {

    @Mock
    private ImportTaskRepository importTaskRepository;

    @Mock
    private ImportTableTaskRepository importTableTaskRepository;

    @Mock
    private DatabaseRepository databaseRepository;

    @Mock
    private ImportJobPodManager importJobPodManager;

    @Mock
    private ComputePodManager computePodManager;

    @Mock
    private DatabaseService databaseService;

    @Mock
    private OperationLogService operationLogService;

    private LakeonProperties props;
    private ImportService importService;

    private TenantEntity testTenant;
    private DatabaseEntity testDatabase;

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @BeforeEach
    void setUp() {
        props = new LakeonProperties();
        LakeonProperties.SyncConfig syncConfig = new LakeonProperties.SyncConfig();
        syncConfig.setMaxTasks(10);
        props.setSync(syncConfig);

        importService = new ImportService(
            importTaskRepository,
            importTableTaskRepository,
            databaseRepository,
            importJobPodManager,
            computePodManager,
            databaseService,
            operationLogService,
            props
        );

        // Initialize transaction synchronization for tests that call createImport
        TransactionSynchronizationManager.initSynchronization();

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
    @DisplayName("createImport — SYNC 模式")
    class CreateImportSync {

        @Test
        @DisplayName("UT-SVC-IMP-001: SYNC 模式超过最大活跃任务数 — 抛出 IllegalStateException")
        void createImport_syncModeLimitExceeded() {
            // Given
            props.getSync().setMaxTasks(1);

            when(databaseRepository.findByIdAndTenantId("db_test001", "tn_test001"))
                .thenReturn(Optional.of(testDatabase));

            ImportTaskEntity existingTask = new ImportTaskEntity();
            existingTask.setId("imp_existing");
            existingTask.setMode(ImportMode.SYNC);
            existingTask.setStatus(ImportTaskStatus.SYNCING);

            when(importTaskRepository.findAllByDatabaseIdAndTenantIdOrderByCreatedAtDesc("db_test001", "tn_test001"))
                .thenReturn(List.of(existingTask));

            var request = new CreateImportRequest(
                null,
                "source.example.com", 5432, "sourcedb", "srcuser", "srcpass",
                ImportMode.SYNC, ConflictStrategy.APPEND, List.of("public.users")
            );

            // When / Then
            assertThatThrownBy(() ->
                importService.createImport(testTenant, "db_test001", request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Maximum active sync tasks");
        }

        @Test
        @DisplayName("UT-SVC-IMP-002: SYNC 模式正常创建 — 生成 pub/sub/slot 名称")
        void createImport_syncModeGeneratesNames() {
            // Given
            props.getSync().setMaxTasks(10);

            when(databaseRepository.findByIdAndTenantId("db_test001", "tn_test001"))
                .thenReturn(Optional.of(testDatabase));

            when(importTaskRepository.findAllByDatabaseIdAndTenantIdOrderByCreatedAtDesc("db_test001", "tn_test001"))
                .thenReturn(List.of());

            when(importTaskRepository.save(any(ImportTaskEntity.class)))
                .thenAnswer(inv -> {
                    ImportTaskEntity entity = inv.getArgument(0);
                    if (entity.getId() == null) {
                        entity.setId("imp_test123");
                    }
                    return entity;
                });

            when(operationLogService.startOperation(any(), any(), any(), any()))
                .thenReturn(new OperationLogEntity());

            var request = new CreateImportRequest(
                null,
                "source.example.com", 5432, "sourcedb", "srcuser", "srcpass",
                ImportMode.SYNC, ConflictStrategy.APPEND, List.of("public.users", "public.orders")
            );

            // When
            ImportTaskResponse result = importService.createImport(testTenant, "db_test001", request);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.publicationName()).startsWith("lakeon_pub_");
            assertThat(result.subscriptionName()).startsWith("lakeon_sub_");
        }
    }

    @Nested
    @DisplayName("getSyncStatus")
    class GetSyncStatus {

        @Test
        @DisplayName("UT-SVC-IMP-003: 返回正确的同步状态响应")
        void getSyncStatus_returnsCorrectResponse() {
            // Given
            ImportTaskEntity task = new ImportTaskEntity();
            task.setId("imp_sync001");
            task.setTenantId("tn_test001");
            task.setDatabaseId("db_test001");
            task.setMode(ImportMode.SYNC);
            task.setSyncStatus("SYNCING");
            task.setReplayLagSeconds(5.0);
            task.setWalRetainedBytes(1024L);
            task.setWalWarning(false);

            when(importTaskRepository.findByIdAndTenantId("imp_sync001", "tn_test001"))
                .thenReturn(Optional.of(task));

            ImportTableTaskEntity table1 = new ImportTableTaskEntity();
            table1.setId("itb_001");
            table1.setImportTaskId("imp_sync001");
            table1.setSchemaName("public");
            table1.setTableName("users");
            table1.setSyncState("r");
            table1.setSyncedRows(1000L);

            ImportTableTaskEntity table2 = new ImportTableTaskEntity();
            table2.setId("itb_002");
            table2.setImportTaskId("imp_sync001");
            table2.setSchemaName("public");
            table2.setTableName("orders");
            table2.setSyncState("d");
            table2.setSyncedRows(500L);

            when(importTableTaskRepository.findAllByImportTaskIdOrderBySchemaNameAscTableNameAsc("imp_sync001"))
                .thenReturn(List.of(table1, table2));

            // When
            SyncStatusResponse result = importService.getSyncStatus(testTenant, "db_test001", "imp_sync001");

            // Then
            assertThat(result.overallStatus()).isEqualTo("SYNCING");
            assertThat(result.replayLagSeconds()).isEqualTo(5.0);
            assertThat(result.walRetainedBytes()).isEqualTo(1024L);
            assertThat(result.walWarning()).isFalse();
            assertThat(result.tables()).hasSize(2);
            assertThat(result.tables().get(0).status()).isEqualTo("SYNCING");   // "r" -> SYNCING
            assertThat(result.tables().get(1).status()).isEqualTo("COPYING");   // "d" -> COPYING
        }

        @Test
        @DisplayName("UT-SVC-IMP-004: 非 SYNC 任务 — 抛出 IllegalStateException")
        void getSyncStatus_throwsForNonSyncTask() {
            // Given
            ImportTaskEntity task = new ImportTaskEntity();
            task.setId("imp_full001");
            task.setTenantId("tn_test001");
            task.setDatabaseId("db_test001");
            task.setMode(ImportMode.FULL);

            when(importTaskRepository.findByIdAndTenantId("imp_full001", "tn_test001"))
                .thenReturn(Optional.of(task));

            // When / Then
            assertThatThrownBy(() ->
                importService.getSyncStatus(testTenant, "db_test001", "imp_full001"))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("handleCallback — SYNCING 状态")
    class HandleCallbackSyncing {

        @Test
        @DisplayName("UT-SVC-IMP-005: 所有表都 SYNCING — 更新表任务并将任务转换为 SYNCING")
        void handleCallback_syncingAllTablesDone_transitionsTask() {
            // Given
            ImportTaskEntity task = new ImportTaskEntity();
            task.setId("imp_cb001");
            task.setTenantId("tn_test001");
            task.setDatabaseId("db_test001");
            task.setMode(ImportMode.SYNC);
            task.setStatus(ImportTaskStatus.RUNNING);
            task.setTotalTables(2);
            task.setJobPodName("import-pod-abc");

            ImportTableTaskEntity tableTask = new ImportTableTaskEntity();
            tableTask.setId("itb_cb001");
            tableTask.setImportTaskId("imp_cb001");
            tableTask.setSchemaName("public");
            tableTask.setTableName("users");
            tableTask.setStatus(ImportTaskStatus.PENDING);

            when(importTaskRepository.findById("imp_cb001"))
                .thenReturn(Optional.of(task));
            when(importTableTaskRepository.findById("itb_cb001"))
                .thenReturn(Optional.of(tableTask));
            when(importTableTaskRepository.countByImportTaskIdAndStatus("imp_cb001", ImportTaskStatus.SYNCING))
                .thenReturn(2L);

            var callbackReq = new ImportCallbackRequest("itb_cb001", ImportTaskStatus.SYNCING, 1000L, null);

            // When
            importService.handleCallback("imp_cb001", callbackReq);

            // Then
            assertThat(tableTask.getSyncState()).isEqualTo("r");
            assertThat(tableTask.getSyncedRows()).isEqualTo(1000L);
            assertThat(task.getStatus()).isEqualTo(ImportTaskStatus.SYNCING);
            assertThat(task.getSyncStatus()).isEqualTo("SYNCING");
            verify(importJobPodManager).deleteJobPod("imp_cb001");
        }

        @Test
        @DisplayName("UT-SVC-IMP-006: 并非所有表都 SYNCING — 不转换任务状态")
        void handleCallback_syncingNotAllDone_noTransition() {
            // Given
            ImportTaskEntity task = new ImportTaskEntity();
            task.setId("imp_cb002");
            task.setTenantId("tn_test001");
            task.setDatabaseId("db_test001");
            task.setMode(ImportMode.SYNC);
            task.setStatus(ImportTaskStatus.RUNNING);
            task.setTotalTables(2);
            task.setJobPodName("import-pod-def");

            ImportTableTaskEntity tableTask = new ImportTableTaskEntity();
            tableTask.setId("itb_cb002");
            tableTask.setImportTaskId("imp_cb002");
            tableTask.setSchemaName("public");
            tableTask.setTableName("users");
            tableTask.setStatus(ImportTaskStatus.PENDING);

            when(importTaskRepository.findById("imp_cb002"))
                .thenReturn(Optional.of(task));
            when(importTableTaskRepository.findById("itb_cb002"))
                .thenReturn(Optional.of(tableTask));
            when(importTableTaskRepository.countByImportTaskIdAndStatus("imp_cb002", ImportTaskStatus.SYNCING))
                .thenReturn(1L);

            var callbackReq = new ImportCallbackRequest("itb_cb002", ImportTaskStatus.SYNCING, null, null);

            // When
            importService.handleCallback("imp_cb002", callbackReq);

            // Then
            assertThat(task.getStatus()).isEqualTo(ImportTaskStatus.RUNNING);
            verify(importJobPodManager, never()).deleteJobPod(any());
        }
    }

    @Nested
    @DisplayName("stopSync")
    class StopSync {

        private ImportTaskEntity syncTask;

        @BeforeEach
        void setUpSyncTask() {
            syncTask = new ImportTaskEntity();
            syncTask.setId("imp_stop001");
            syncTask.setTenantId("tn_test001");
            syncTask.setDatabaseId("db_test001");
            syncTask.setMode(ImportMode.SYNC);
            syncTask.setSubscriptionName("lakeon_sub_test");
            syncTask.setPublicationName("lakeon_pub_test");
            syncTask.setSlotName("lakeon_slot_test");
            syncTask.setSourceHost("source.example.com");
            syncTask.setSourcePort(5432);
            syncTask.setSourceDbname("sourcedb");
            syncTask.setSourceUser("srcuser");
            syncTask.setSourcePassword("srcpass");
        }

        @Test
        @DisplayName("UT-SVC-IMP-008: RUNNING 状态停止 — 删除 job pod，跳过 JDBC 清理")
        void stopSync_runningStatus_killsJobPodAndSkipsJdbc() {
            syncTask.setStatus(ImportTaskStatus.RUNNING);
            syncTask.setJobPodName("import-pod-sync");

            when(importTaskRepository.findByIdAndTenantId("imp_stop001", "tn_test001"))
                .thenReturn(Optional.of(syncTask));
            when(importTaskRepository.save(any(ImportTaskEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
            when(importTableTaskRepository.findAllByImportTaskIdOrderBySchemaNameAscTableNameAsc("imp_stop001"))
                .thenReturn(List.of());

            // cleanup=true but RUNNING should skip JDBC entirely
            ImportTaskResponse result = importService.stopSync(testTenant, "db_test001", "imp_stop001", true);

            assertThat(result).isNotNull();
            assertThat(syncTask.getStatus()).isEqualTo(ImportTaskStatus.COMPLETED);
            assertThat(syncTask.getSyncStatus()).isEqualTo("STOPPED");
            assertThat(syncTask.getJobPodName()).isNull();
            verify(importJobPodManager).deleteJobPod("imp_stop001");
            // No database lookup needed — JDBC cleanup skipped
            verify(databaseRepository, never()).findByIdAndTenantId(any(), any());
        }

        @Test
        @DisplayName("UT-SVC-IMP-009: COMPLETED 状态的 SYNC 任务不允许停止")
        void stopSync_completedStatus_throws() {
            syncTask.setStatus(ImportTaskStatus.COMPLETED);

            when(importTaskRepository.findByIdAndTenantId("imp_stop001", "tn_test001"))
                .thenReturn(Optional.of(syncTask));

            assertThatThrownBy(() ->
                importService.stopSync(testTenant, "db_test001", "imp_stop001", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot stop task in status");
        }

        @Test
        @DisplayName("UT-SVC-IMP-010: 非 SYNC 任务不允许停止")
        void stopSync_nonSyncTask_throws() {
            syncTask.setMode(ImportMode.FULL);
            syncTask.setStatus(ImportTaskStatus.RUNNING);

            when(importTaskRepository.findByIdAndTenantId("imp_stop001", "tn_test001"))
                .thenReturn(Optional.of(syncTask));

            assertThatThrownBy(() ->
                importService.stopSync(testTenant, "db_test001", "imp_stop001", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only available for SYNC");
        }
    }

    @Nested
    @DisplayName("checkOrphanedImportTasks")
    class CheckOrphanedImportTasks {

        @Test
        @DisplayName("UT-SVC-IMP-007: SYNC 模式任务 — 跳过不检查")
        void checkOrphanedImportTasks_skipsSyncTasks() {
            // Given
            ImportTaskEntity syncTask = new ImportTaskEntity();
            syncTask.setId("imp_sync_orphan");
            syncTask.setMode(ImportMode.SYNC);
            syncTask.setStatus(ImportTaskStatus.RUNNING);
            syncTask.setJobPodName("sync-xxx");

            when(importTaskRepository.findAllByStatus(ImportTaskStatus.RUNNING))
                .thenReturn(List.of(syncTask));

            // When
            importService.checkOrphanedImportTasks();

            // Then
            verify(importJobPodManager, never()).isJobPodRunning(any());
            verify(importTaskRepository, never()).save(any());
        }
    }
}
