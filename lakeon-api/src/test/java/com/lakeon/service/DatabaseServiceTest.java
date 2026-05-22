package com.lakeon.service;

import com.lakeon.config.LakeonProperties;
import com.lakeon.k8s.ComputePodManager;
import com.lakeon.model.dto.CreateDatabaseRequest;
import com.lakeon.model.dto.UpdateDatabaseRequest;
import com.lakeon.model.entity.BranchEntity;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.enums.ComputeSize;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.neon.NeonApiClient;
import com.lakeon.neon.dto.NeonTenant;
import com.lakeon.neon.dto.NeonTimeline;
import com.lakeon.repository.BranchRepository;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.model.entity.OperationLogEntity;
import com.lakeon.model.enums.OperationType;
import com.lakeon.service.exception.ConflictException;
import com.lakeon.service.exception.NotFoundException;
import com.lakeon.service.exception.ServiceException;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@DisplayName("DatabaseService 单元测试")
class DatabaseServiceTest {

    @Mock
    private DatabaseRepository databaseRepository;

    @Mock
    private BranchRepository branchRepository;

    @Mock
    private NeonApiClient neonApiClient;

    @Mock
    private ComputePodManager computePodManager;

    @Mock
    private LakeonProperties props;

    @Mock
    private OperationLogService operationLogService;

    @Mock
    private DatabaseProvisioningService provisioningService;

    @Mock
    private ApplicationEventPublisher events;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private DatabaseService databaseService;

    private TenantEntity testTenant;

    @BeforeEach
    void setUp() {
        TransactionTemplate txTemplate = TestTransactionTemplate.create();
        databaseService = new DatabaseService(databaseRepository, branchRepository,
                neonApiClient, computePodManager, props, operationLogService, meterRegistry, txTemplate, provisioningService, events);

        testTenant = new TenantEntity();
        testTenant.setId("tn_test001");
        testTenant.setApiKey("test-api-key-32chars-long-enough!");

        // Setup default properties (lenient because not all tests call create())
        var defaults = new LakeonProperties.DefaultsConfig();
        defaults.setComputeSize("1cu");
        defaults.setSuspendTimeout("5m");
        defaults.setStorageLimitGb(10);
        lenient().when(props.getDefaults()).thenReturn(defaults);

        var proxy = new LakeonProperties.ProxyConfig();
        proxy.setExternalHost("proxy.lakeon.example.com");
        proxy.setExternalPort(4432);
        lenient().when(props.getProxy()).thenReturn(proxy);

        // Setup operation log mock (lenient because not all tests trigger operations)
        OperationLogEntity mockOpLog = new OperationLogEntity();
        mockOpLog.setId("oplog_test");
        lenient().when(operationLogService.startOperation(anyString(), anyString(), anyString(), any(OperationType.class)))
                .thenReturn(mockOpLog);

        // Quota check: default to empty list (no existing databases)
        lenient().when(databaseRepository.findAllByTenantId(anyString()))
                .thenReturn(List.of());

        // Default: timeline returns no size (lenient because not all tests trigger toResponse)
        lenient().when(neonApiClient.getTimeline(anyString(), anyString()))
                .thenReturn(new NeonTimeline("default-timeline"));
    }

    // ========== UT-SVC-DB-001 ~ UT-SVC-DB-004: 创建实例 ==========

    @Nested
    @DisplayName("创建实例")
    class CreateDatabase {

        @Test
        @DisplayName("UT-SVC-DB-001: 正常流程 — 创建 tenant + timeline，异步启动 Pod，立即返回 CREATING")
        void createDatabase_success() {
            // Given
            var request = new CreateDatabaseRequest("my-app-db", "1cu", "5m", 10);
            when(databaseRepository.findByTenantIdAndName(anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(neonApiClient.createTenant(anyString()))
                    .thenReturn(new NeonTenant("neon-tenant-abc"));
            when(neonApiClient.createTimeline(eq("neon-tenant-abc"), any()))
                    .thenReturn(new NeonTimeline("neon-timeline-main"));
            when(databaseRepository.save(any(DatabaseEntity.class)))
                    .thenAnswer(inv -> {
                        DatabaseEntity entity = inv.getArgument(0);
                        if (entity.getId() == null) entity.setId("db_abc123");
                        return entity;
                    });

            // When
            var result = databaseService.create(testTenant, request);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo("db_abc123");
            assertThat(result.getName()).isEqualTo("my-app-db");
            assertThat(result.getStatus()).isEqualTo(DatabaseStatus.CREATING);
            assertThat(result.getComputeSize()).isEqualTo("1cu");
            assertThat(result.getSuspendTimeout()).isEqualTo("5m");
            assertThat(result.getStorageLimitGb()).isEqualTo(10);

            // 验证调用顺序：先 Neon → 保存元数据 → 异步启动 Pod
            var inOrder = inOrder(neonApiClient, databaseRepository, provisioningService);
            inOrder.verify(neonApiClient).createTenant(anyString());
            inOrder.verify(neonApiClient).createTimeline(eq("neon-tenant-abc"), any());
            inOrder.verify(databaseRepository).save(any(DatabaseEntity.class));
            inOrder.verify(provisioningService).provisionAsync(anyString(), anyString(), anyString(), anyString());

            // 验证不直接调用 computePodManager（由 provisioningService 负责）
            verify(computePodManager, never()).createComputePod(any());
        }

        @Test
        @DisplayName("UT-SVC-DB-001a: 使用默认值创建 — compute_size/suspend_timeout/storage_limit 使用默认值")
        void createDatabase_withDefaults() {
            // Given
            var request = new CreateDatabaseRequest("my-db", null, null, null);
            when(databaseRepository.findByTenantIdAndName(anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(neonApiClient.createTenant(anyString()))
                    .thenReturn(new NeonTenant("neon-tenant-def"));
            when(neonApiClient.createTimeline(anyString(), any()))
                    .thenReturn(new NeonTimeline("neon-timeline-main"));
            when(databaseRepository.save(any(DatabaseEntity.class)))
                    .thenAnswer(inv -> {
                        DatabaseEntity entity = inv.getArgument(0);
                        if (entity.getId() == null) entity.setId("db_def456");
                        return entity;
                    });

            // When
            var result = databaseService.create(testTenant, request);

            // Then
            assertThat(result.getComputeSize()).isEqualTo("1cu");
            assertThat(result.getSuspendTimeout()).isEqualTo("5m");
            assertThat(result.getStorageLimitGb()).isEqualTo(10);
        }

        @Test
        @DisplayName("UT-SVC-DB-002: Neon API 创建 tenant 失败 — 不创建 K8s Pod，不保存元数据")
        void createDatabase_neonCreateTenantFails_noSideEffects() {
            // Given
            var request = new CreateDatabaseRequest("my-db", "1cu", "5m", 10);
            when(databaseRepository.findByTenantIdAndName(anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(neonApiClient.createTenant(anyString()))
                    .thenThrow(new RuntimeException("Pageserver unavailable"));

            // When / Then
            assertThatThrownBy(() -> databaseService.create(testTenant, request))
                    .isInstanceOf(ServiceException.class)
                    .hasMessageContaining("Pageserver");

            verify(computePodManager, never()).createComputePod(any());
            verify(databaseRepository, never()).save(any());
        }

        @Test
        @DisplayName("UT-SVC-DB-003: Neon timeline 创建失败 — 回滚 Neon tenant")
        void createDatabase_timelineFails_rollbackNeonTenant() {
            // Given
            var request = new CreateDatabaseRequest("my-db", "1cu", "5m", 10);
            when(databaseRepository.findByTenantIdAndName(anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(neonApiClient.createTenant(anyString()))
                    .thenReturn(new NeonTenant("neon-tenant-to-rollback"));
            when(neonApiClient.createTimeline(anyString(), any()))
                    .thenThrow(new RuntimeException("Timeline creation failed"));

            // When / Then
            assertThatThrownBy(() -> databaseService.create(testTenant, request))
                    .isInstanceOf(ServiceException.class);

            // 验证回滚：Neon tenant 被删除
            verify(neonApiClient).deleteTenant(eq("neon-tenant-to-rollback"));
            // 验证不调用 provisioningService（未到异步阶段）
            verify(provisioningService, never()).provisionAsync(any(), any(), any(), any());
        }

        @Test
        @DisplayName("UT-SVC-DB-004: 名称重复 — 抛出 ConflictException")
        void createDatabase_duplicateName_throwsConflict() {
            // Given
            var request = new CreateDatabaseRequest("existing-db", "1cu", "5m", 10);
            when(databaseRepository.findByTenantIdAndName(testTenant.getId(), "existing-db"))
                    .thenReturn(Optional.of(new DatabaseEntity()));

            // When / Then
            assertThatThrownBy(() -> databaseService.create(testTenant, request))
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("existing-db");

            verify(neonApiClient, never()).createTenant(anyString());
        }
    }

    // ========== UT-SVC-DB-005 ~ UT-SVC-DB-008: 删除/查询/列出实例 ==========

    @Nested
    @DisplayName("删除实例")
    class DeleteDatabase {

        @Test
        @DisplayName("UT-SVC-DB-005: 正常流程 — 销毁 Pod，删除 Neon tenant，清除元数据")
        void deleteDatabase_success() {
            // Given
            var dbEntity = createTestDatabaseEntity("db_del001", "to-delete", DatabaseStatus.RUNNING);
            when(databaseRepository.findByIdAndTenantId("db_del001", testTenant.getId()))
                    .thenReturn(Optional.of(dbEntity));
            when(branchRepository.findAllByDatabaseId("db_del001"))
                    .thenReturn(List.of());

            // When
            databaseService.delete(testTenant, "db_del001");

            // Then
            verify(computePodManager).deleteComputePod(dbEntity.getComputePodName());
            verify(neonApiClient).deleteTenant(dbEntity.getNeonTenantId());
            verify(databaseRepository).delete(dbEntity);
        }

        @Test
        @DisplayName("UT-SVC-DB-005a: 删除带分支的实例 — 所有分支也被清理")
        void deleteDatabase_withBranches_allCleaned() {
            // Given
            var dbEntity = createTestDatabaseEntity("db_del002", "with-branches", DatabaseStatus.RUNNING);
            var branch = new BranchEntity();
            branch.setId("br_xyz");
            branch.setNeonTimelineId("timeline-branch");
            branch.setComputePodName("pod-branch");
            when(databaseRepository.findByIdAndTenantId("db_del002", testTenant.getId()))
                    .thenReturn(Optional.of(dbEntity));
            when(branchRepository.findAllByDatabaseId("db_del002"))
                    .thenReturn(List.of(branch));

            // When
            databaseService.delete(testTenant, "db_del002");

            // Then
            verify(computePodManager).deleteComputePod("pod-branch");
            verify(neonApiClient).deleteTimeline(dbEntity.getNeonTenantId(), "timeline-branch");
            verify(branchRepository).delete(branch);
            verify(computePodManager).deleteComputePod(dbEntity.getComputePodName());
            verify(neonApiClient).deleteTenant(dbEntity.getNeonTenantId());
            verify(databaseRepository).delete(dbEntity);
        }

        @Test
        @DisplayName("UT-SVC-DB-006: 实例不存在 — 抛出 NotFoundException")
        void deleteDatabase_notFound_throwsNotFound() {
            // Given
            when(databaseRepository.findByIdAndTenantId("db_nonexist", testTenant.getId()))
                    .thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> databaseService.delete(testTenant, "db_nonexist"))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("查询实例")
    class GetDatabase {

        @Test
        @DisplayName("UT-SVC-DB-007: 查询实例详情 — 返回完整信息")
        void getDatabase_success() {
            // Given
            var dbEntity = createTestDatabaseEntity("db_get001", "my-db", DatabaseStatus.RUNNING);
            when(databaseRepository.findByIdAndTenantId("db_get001", testTenant.getId()))
                    .thenReturn(Optional.of(dbEntity));

            // When
            var result = databaseService.get(testTenant, "db_get001");

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo("db_get001");
            assertThat(result.getName()).isEqualTo("my-db");
            assertThat(result.getStatus()).isEqualTo(DatabaseStatus.RUNNING);
            assertThat(result.getConnectionUri()).isNotBlank();
        }

        @Test
        @DisplayName("UT-SVC-DB-008: 列出实例 — 仅返回当前租户的实例")
        void listDatabases_tenantIsolation() {
            // Given
            var db1 = createTestDatabaseEntity("db_list001", "db-a", DatabaseStatus.RUNNING);
            var db2 = createTestDatabaseEntity("db_list002", "db-b", DatabaseStatus.SUSPENDED);
            when(databaseRepository.findAllByTenantId(testTenant.getId()))
                    .thenReturn(List.of(db1, db2));

            // When
            var result = databaseService.list(testTenant);

            // Then
            assertThat(result).hasSize(2);
            assertThat(result).extracting("name").containsExactly("db-a", "db-b");
        }
    }

    // ========== UT-SVC-DB-009 ~ UT-SVC-DB-013: 启停 compute + 更新配置 ==========

    @Nested
    @DisplayName("Compute 启停")
    class ComputeLifecycle {

        @Test
        @DisplayName("UT-SVC-DB-009: 启动 compute — 实例已休眠，创建 Pod，状态变 running")
        void resumeCompute_fromSuspended() {
            // Given
            var dbEntity = createTestDatabaseEntity("db_resume001", "my-db", DatabaseStatus.SUSPENDED);
            when(databaseRepository.findByIdAndTenantId("db_resume001", testTenant.getId()))
                    .thenReturn(Optional.of(dbEntity));
            when(databaseRepository.findById("db_resume001"))
                    .thenReturn(Optional.of(dbEntity));
            when(computePodManager.createComputePod(any()))
                    .thenReturn("10.0.1.10:5432");

            // When
            databaseService.resume(testTenant, "db_resume001");

            // Then
            verify(computePodManager).createComputePod(any());
            // Cold path saves twice: (1) pre-write podName/clear suspendedAt so cleanup
            // schedulers don't race-delete the fresh pod, (2) flip status to RUNNING
            // after the pod is ready. The final save carries the RUNNING status.
            ArgumentCaptor<DatabaseEntity> captor = ArgumentCaptor.forClass(DatabaseEntity.class);
            verify(databaseRepository, times(2)).save(captor.capture());
            assertThat(captor.getAllValues()).last()
                    .extracting(DatabaseEntity::getStatus).isEqualTo(DatabaseStatus.RUNNING);
        }

        @Test
        @DisplayName("UT-SVC-DB-010: 启动 compute — 实例已运行，幂等处理")
        void resumeCompute_alreadyRunning_idempotent() {
            // Given
            var dbEntity = createTestDatabaseEntity("db_resume002", "my-db", DatabaseStatus.RUNNING);
            when(databaseRepository.findByIdAndTenantId("db_resume002", testTenant.getId()))
                    .thenReturn(Optional.of(dbEntity));

            // When
            databaseService.resume(testTenant, "db_resume002");

            // Then
            verify(computePodManager, never()).createComputePod(any());
        }

        @Test
        @DisplayName("UT-SVC-DB-011: 停止 compute — Pod 保留（暖启动），状态变 suspended")
        void suspendCompute_success() {
            // Given
            var dbEntity = createTestDatabaseEntity("db_suspend001", "my-db", DatabaseStatus.RUNNING);
            when(databaseRepository.findByIdAndTenantId("db_suspend001", testTenant.getId()))
                    .thenReturn(Optional.of(dbEntity));

            // When
            databaseService.suspend(testTenant, "db_suspend001");

            // Then — Pod retained for warm wake, NOT deleted
            verify(computePodManager, never()).deleteComputePod(anyString());
            ArgumentCaptor<DatabaseEntity> captor = ArgumentCaptor.forClass(DatabaseEntity.class);
            verify(databaseRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(DatabaseStatus.SUSPENDED);
        }
    }

    @Nested
    @DisplayName("更新配置")
    class UpdateConfig {

        @Test
        @DisplayName("UT-SVC-DB-012: 修改 compute 规格 — 更新元数据，触发 compute 重启")
        void updateComputeSize_triggerRestart() {
            // Given
            var dbEntity = createTestDatabaseEntity("db_upd001", "my-db", DatabaseStatus.RUNNING);
            dbEntity.setComputeSize("1cu");
            when(databaseRepository.findByIdAndTenantId("db_upd001", testTenant.getId()))
                    .thenReturn(Optional.of(dbEntity));
            when(computePodManager.createComputePod(any()))
                    .thenReturn("10.0.1.11:5432");
            when(databaseRepository.save(any(DatabaseEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            var request = new UpdateDatabaseRequest("2cu", null, null);

            // When
            databaseService.update(testTenant, "db_upd001", request);

            // Then
            // 验证旧 Pod 被销毁（等待删除完成），新 Pod 被创建
            verify(computePodManager).deleteComputePod(anyString(), eq(true));
            verify(computePodManager).createComputePod(any());
            ArgumentCaptor<DatabaseEntity> captor = ArgumentCaptor.forClass(DatabaseEntity.class);
            verify(databaseRepository).save(captor.capture());
            assertThat(captor.getValue().getComputeSize()).isEqualTo("2cu");
        }

        @Test
        @DisplayName("UT-SVC-DB-013: 修改休眠超时 — 仅更新元数据，不重启 compute")
        void updateSuspendTimeout_noRestart() {
            // Given
            var dbEntity = createTestDatabaseEntity("db_upd002", "my-db", DatabaseStatus.RUNNING);
            when(databaseRepository.findByIdAndTenantId("db_upd002", testTenant.getId()))
                    .thenReturn(Optional.of(dbEntity));
            when(databaseRepository.save(any(DatabaseEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            var request = new UpdateDatabaseRequest(null, "10m", null);

            // When
            databaseService.update(testTenant, "db_upd002", request);

            // Then
            verify(computePodManager, never()).deleteComputePod(anyString());
            verify(computePodManager, never()).createComputePod(any());
            ArgumentCaptor<DatabaseEntity> captor = ArgumentCaptor.forClass(DatabaseEntity.class);
            verify(databaseRepository).save(captor.capture());
            assertThat(captor.getValue().getSuspendTimeout()).isEqualTo("10m");
        }
    }

    // ========== UT-SVC-DB-014 ~ UT-SVC-DB-017: 存储用量 ==========

    @Nested
    @DisplayName("存储用量")
    class StorageUsage {

        @Test
        @DisplayName("UT-SVC-DB-014: 查询存储用量 — 从 Neon pageserver 获取 current_logical_size 并转换为 GB")
        void getDatabase_returnsStorageUsedGb() {
            // Given
            var dbEntity = createTestDatabaseEntity("db_storage001", "my-db", DatabaseStatus.RUNNING);
            when(databaseRepository.findByIdAndTenantId("db_storage001", testTenant.getId()))
                    .thenReturn(Optional.of(dbEntity));
            var timeline = new NeonTimeline("neon-timeline-db_storage001");
            timeline.setCurrentLogicalSize(1_073_741_824L); // 1 GB in bytes
            when(neonApiClient.getTimeline(dbEntity.getNeonTenantId(), dbEntity.getNeonTimelineId()))
                    .thenReturn(timeline);

            // When
            var result = databaseService.get(testTenant, "db_storage001");

            // Then
            assertThat(result.getStorageUsedGb()).isEqualTo(1.0);
            verify(neonApiClient).getTimeline(dbEntity.getNeonTenantId(), dbEntity.getNeonTimelineId());
        }

        @Test
        @DisplayName("UT-SVC-DB-015: 小数 GB 精度 — 返回两位小数")
        void getDatabase_storageUsedGb_decimalPrecision() {
            // Given
            var dbEntity = createTestDatabaseEntity("db_storage002", "small-db", DatabaseStatus.RUNNING);
            when(databaseRepository.findByIdAndTenantId("db_storage002", testTenant.getId()))
                    .thenReturn(Optional.of(dbEntity));
            var timeline = new NeonTimeline("neon-timeline-db_storage002");
            timeline.setCurrentLogicalSize(53_687_091L); // ~0.05 GB
            when(neonApiClient.getTimeline(dbEntity.getNeonTenantId(), dbEntity.getNeonTimelineId()))
                    .thenReturn(timeline);

            // When
            var result = databaseService.get(testTenant, "db_storage002");

            // Then
            assertThat(result.getStorageUsedGb()).isEqualTo(0.05);
        }

        @Test
        @DisplayName("UT-SVC-DB-016: Neon API 异常 — 存储用量降级为 0.0，不影响其他数据")
        void getDatabase_neonTimelineFails_fallbackToZero() {
            // Given
            var dbEntity = createTestDatabaseEntity("db_storage003", "fail-db", DatabaseStatus.RUNNING);
            when(databaseRepository.findByIdAndTenantId("db_storage003", testTenant.getId()))
                    .thenReturn(Optional.of(dbEntity));
            when(neonApiClient.getTimeline(anyString(), anyString()))
                    .thenThrow(new RuntimeException("Pageserver unreachable"));

            // When
            var result = databaseService.get(testTenant, "db_storage003");

            // Then
            assertThat(result.getStorageUsedGb()).isEqualTo(0.0);
            assertThat(result.getId()).isEqualTo("db_storage003");
            assertThat(result.getName()).isEqualTo("fail-db");
        }

        @Test
        @DisplayName("UT-SVC-DB-017: currentLogicalSize 为 null — 返回 0.0")
        void getDatabase_nullLogicalSize_returnsZero() {
            // Given
            var dbEntity = createTestDatabaseEntity("db_storage004", "null-size-db", DatabaseStatus.RUNNING);
            when(databaseRepository.findByIdAndTenantId("db_storage004", testTenant.getId()))
                    .thenReturn(Optional.of(dbEntity));
            var timeline = new NeonTimeline("neon-timeline-db_storage004");
            timeline.setCurrentLogicalSize(null);
            when(neonApiClient.getTimeline(dbEntity.getNeonTenantId(), dbEntity.getNeonTimelineId()))
                    .thenReturn(timeline);

            // When
            var result = databaseService.get(testTenant, "db_storage004");

            // Then
            assertThat(result.getStorageUsedGb()).isEqualTo(0.0);
        }
    }

    // ========== 辅助方法 ==========

    private DatabaseEntity createTestDatabaseEntity(String id, String name, DatabaseStatus status) {
        var entity = new DatabaseEntity();
        entity.setId(id);
        entity.setTenantId(testTenant.getId());
        entity.setName(name);
        entity.setStatus(status);
        entity.setComputeSize("1cu");
        entity.setSuspendTimeout("5m");
        entity.setStorageLimitGb(10);
        entity.setNeonTenantId("neon-tenant-" + id);
        entity.setNeonTimelineId("neon-timeline-" + id);
        entity.setComputePodName("compute-" + id);
        entity.setConnectionUri("postgres://user:pass@proxy.lakeon.example.com/" + name);
        return entity;
    }
}
