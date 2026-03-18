package com.lakeon.service;

import com.lakeon.k8s.ComputePodManager;
import com.lakeon.model.dto.BranchTreeResponse;
import com.lakeon.model.dto.CreateBranchRequest;
import com.lakeon.model.dto.RestoreBranchRequest;
import com.lakeon.model.entity.BranchEntity;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.entity.VersionEntity;
import com.lakeon.model.enums.BranchStatus;
import com.lakeon.model.enums.BranchType;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.neon.NeonApiClient;
import com.lakeon.neon.dto.CreateTimelineRequest;
import com.lakeon.neon.dto.NeonTimeline;
import com.lakeon.repository.BranchRepository;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.VersionRepository;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BranchService 单元测试")
class BranchServiceTest {

    @Mock
    private DatabaseRepository databaseRepository;

    @Mock
    private BranchRepository branchRepository;

    @Mock
    private VersionRepository versionRepository;

    @Mock
    private NeonApiClient neonApiClient;

    @Mock
    private ComputePodManager computePodManager;

    @Mock
    private OperationLogService operationLogService;

    @InjectMocks
    private BranchService branchService;

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
    }

    @Nested
    @DisplayName("创建分支")
    class CreateBranch {

        @Test
        @DisplayName("UT-SVC-BR-001: 正常流程 — 创建 Neon timeline，保存元数据")
        void createBranch_success() {
            // Given
            var request = new CreateBranchRequest("feature-test", false, null, null);
            when(databaseRepository.findByIdAndTenantId("db_test001", testTenant.getId()))
                    .thenReturn(Optional.of(testDatabase));
            when(neonApiClient.createTimeline(eq("neon-tenant-abc"), any()))
                    .thenReturn(new NeonTimeline("neon-timeline-feature"));
            when(branchRepository.save(any(BranchEntity.class)))
                    .thenAnswer(inv -> {
                        BranchEntity entity = inv.getArgument(0);
                        entity.setId("br_feat001");
                        return entity;
                    });

            // When
            var result = branchService.create(testTenant, "db_test001", request);

            // Then
            assertThat(result).isNotNull();
            assertThat(result.getName()).isEqualTo("feature-test");
            assertThat(result.getParentBranch()).isEqualTo("main");
            assertThat(result.getConnectionUri()).contains("branch=feature-test");
            verify(neonApiClient).createTimeline(eq("neon-tenant-abc"), any());
            verify(branchRepository).save(any(BranchEntity.class));
            // 不启动 compute
            verify(computePodManager, never()).createComputePod(any());
        }

        @Test
        @DisplayName("UT-SVC-BR-002: 带 start_compute — 创建 timeline 后额外创建 K8s Pod")
        void createBranch_withStartCompute() {
            // Given
            var request = new CreateBranchRequest("feature-test", true, null, null);
            when(databaseRepository.findByIdAndTenantId("db_test001", testTenant.getId()))
                    .thenReturn(Optional.of(testDatabase));
            when(neonApiClient.createTimeline(eq("neon-tenant-abc"), any()))
                    .thenReturn(new NeonTimeline("neon-timeline-feature"));
            when(computePodManager.createComputePod(any()))
                    .thenReturn("10.0.1.20:5432");
            when(branchRepository.save(any(BranchEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            // When
            var result = branchService.create(testTenant, "db_test001", request);

            // Then
            verify(computePodManager).createComputePod(any());
            assertThat(result.getStatus()).isNotNull();
        }

        @Test
        @DisplayName("UT-SVC-BR-003: 父实例不存在 — 抛出 NotFoundException")
        void createBranch_parentNotFound() {
            // Given
            var request = new CreateBranchRequest("feature-test", false, null, null);
            when(databaseRepository.findByIdAndTenantId("db_nonexist", testTenant.getId()))
                    .thenReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() ->
                    branchService.create(testTenant, "db_nonexist", request))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    @DisplayName("删除分支")
    class DeleteBranch {

        @Test
        @DisplayName("UT-SVC-BR-004: 正常流程 — 销毁 compute，删除 timeline，清除元数据")
        void deleteBranch_success() {
            // Given
            var branch = new BranchEntity();
            branch.setId("br_del001");
            branch.setDatabaseId("db_test001");
            branch.setName("feature-test");
            branch.setIsDefault(false);
            branch.setNeonTimelineId("neon-timeline-feature");
            branch.setComputePodName("compute-branch-del001");

            when(databaseRepository.findByIdAndTenantId("db_test001", testTenant.getId()))
                    .thenReturn(Optional.of(testDatabase));
            when(branchRepository.findByIdAndDatabaseId("br_del001", "db_test001"))
                    .thenReturn(Optional.of(branch));

            // When
            branchService.delete(testTenant, "db_test001", "br_del001");

            // Then
            verify(computePodManager).deleteComputePod("compute-branch-del001");
            verify(neonApiClient).deleteTimeline("neon-tenant-abc", "neon-timeline-feature");
            verify(branchRepository).delete(branch);
        }

        @Test
        @DisplayName("UT-SVC-BR-005: 删除默认分支 — 拒绝操作，抛出 BadRequestException")
        void deleteBranch_defaultBranch_rejected() {
            // Given
            var branch = new BranchEntity();
            branch.setId("br_main");
            branch.setDatabaseId("db_test001");
            branch.setName("main");
            branch.setIsDefault(true);

            when(databaseRepository.findByIdAndTenantId("db_test001", testTenant.getId()))
                    .thenReturn(Optional.of(testDatabase));
            when(branchRepository.findByIdAndDatabaseId("br_main", "db_test001"))
                    .thenReturn(Optional.of(branch));

            // When / Then
            assertThatThrownBy(() ->
                    branchService.delete(testTenant, "db_test001", "br_main"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("default");
        }
    }

    @Nested
    @DisplayName("列出分支")
    class ListBranches {

        @Test
        @DisplayName("UT-SVC-BR-006: 正常 — 返回实例下所有分支及状态")
        void listBranches_success() {
            // Given
            var main = new BranchEntity();
            main.setId("br_main");
            main.setName("main");
            main.setIsDefault(true);

            var feature = new BranchEntity();
            feature.setId("br_feat");
            feature.setName("feature-test");
            feature.setIsDefault(false);

            when(databaseRepository.findByIdAndTenantId("db_test001", testTenant.getId()))
                    .thenReturn(Optional.of(testDatabase));
            when(branchRepository.findAllByDatabaseId("db_test001"))
                    .thenReturn(List.of(main, feature));

            // When
            var result = branchService.list(testTenant, "db_test001");

            // Then
            assertThat(result).hasSize(2);
            assertThat(result).extracting("name").containsExactly("main", "feature-test");
        }
    }

    @Nested
    @DisplayName("分支树")
    class GetTree {

        @Test
        @DisplayName("UT-SVC-BR-007: 正常 — 返回分支树节点列表，带 Neon timeline 数据")
        void getTree_success() {
            var main = new BranchEntity();
            main.setId("br_main");
            main.setName("main");
            main.setIsDefault(true);
            main.setNeonTimelineId("neon-timeline-main");

            var feature = new BranchEntity();
            feature.setId("br_feat");
            feature.setName("feature-test");
            feature.setIsDefault(false);
            feature.setParentBranchId("br_main");
            feature.setNeonTimelineId("neon-timeline-feat");

            when(databaseRepository.findByIdAndTenantId("db_test001", testTenant.getId()))
                    .thenReturn(Optional.of(testDatabase));
            when(branchRepository.findAllByDatabaseId("db_test001"))
                    .thenReturn(List.of(main, feature));

            NeonTimeline mainTl = new NeonTimeline("neon-timeline-main");
            mainTl.setLastRecordLsn("0/5000");
            mainTl.setCurrentLogicalSize(10240L);

            NeonTimeline featTl = new NeonTimeline("neon-timeline-feat");
            featTl.setAncestorLsn("0/3000");
            featTl.setLastRecordLsn("0/4000");
            featTl.setCurrentLogicalSize(5120L);

            when(neonApiClient.listTimelines("neon-tenant-abc"))
                    .thenReturn(List.of(mainTl, featTl));

            BranchTreeResponse result = branchService.getTree(testTenant, "db_test001");

            assertThat(result.nodes()).hasSize(2);
            assertThat(result.nodes()).extracting("name").containsExactly("main", "feature-test");
            assertThat(result.nodes().get(0).lastRecordLsn()).isEqualTo("0/5000");
            assertThat(result.nodes().get(1).parentBranchId()).isEqualTo("br_main");
            assertThat(result.nodes().get(1).ancestorLsn()).isEqualTo("0/3000");
        }

        @Test
        @DisplayName("UT-SVC-BR-008: Neon 不可用 — 降级返回基本节点信息")
        void getTree_neonUnavailable_gracefulDegradation() {
            var main = new BranchEntity();
            main.setId("br_main");
            main.setName("main");
            main.setIsDefault(true);
            main.setNeonTimelineId("neon-timeline-main");

            when(databaseRepository.findByIdAndTenantId("db_test001", testTenant.getId()))
                    .thenReturn(Optional.of(testDatabase));
            when(branchRepository.findAllByDatabaseId("db_test001"))
                    .thenReturn(List.of(main));
            when(neonApiClient.listTimelines("neon-tenant-abc"))
                    .thenThrow(new RuntimeException("Neon unavailable"));

            BranchTreeResponse result = branchService.getTree(testTenant, "db_test001");

            assertThat(result.nodes()).hasSize(1);
            assertThat(result.nodes().get(0).name()).isEqualTo("main");
            assertThat(result.nodes().get(0).lastRecordLsn()).isNull();
        }
    }

    @Nested
    @DisplayName("提升分支为默认")
    class PromoteBranch {

        @Test
        @DisplayName("UT-SVC-BR-012: 正常提升 — 交换默认分支，不重建 compute pod")
        void promote_swapsDefaultBranch() {
            testDatabase.setComputePodName("compute-db_test001");

            var currentDefault = new BranchEntity();
            currentDefault.setId("br_main");
            currentDefault.setDatabaseId("db_test001");
            currentDefault.setName("main");
            currentDefault.setIsDefault(true);
            currentDefault.setNeonTimelineId("neon-timeline-main");

            var targetBranch = new BranchEntity();
            targetBranch.setId("br_feat");
            targetBranch.setDatabaseId("db_test001");
            targetBranch.setName("feature-test");
            targetBranch.setIsDefault(false);
            targetBranch.setNeonTimelineId("neon-timeline-feat");

            when(databaseRepository.findByIdAndTenantIdForUpdate("db_test001", testTenant.getId()))
                    .thenReturn(Optional.of(testDatabase));
            when(branchRepository.findByIdAndDatabaseId("br_feat", "db_test001"))
                    .thenReturn(Optional.of(targetBranch));
            when(branchRepository.findByDatabaseIdAndIsDefaultTrue("db_test001"))
                    .thenReturn(Optional.of(currentDefault));
            when(branchRepository.save(any(BranchEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(databaseRepository.save(any(DatabaseEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            var result = branchService.promote(testTenant, "db_test001", "br_feat");

            // Old default demoted
            assertThat(currentDefault.getIsDefault()).isFalse();
            assertThat(currentDefault.getBranchType()).isEqualTo(BranchType.BACKUP);
            assertThat(currentDefault.getName()).startsWith("main-before-promote-");

            // Target promoted
            assertThat(targetBranch.getIsDefault()).isTrue();
            assertThat(result.getName()).isEqualTo("feature-test");

            // Database timeline updated
            assertThat(testDatabase.getNeonTimelineId()).isEqualTo("neon-timeline-feat");

            // No compute pod operations — each branch owns its own compute
            verify(computePodManager, never()).deleteComputePod(anyString(), anyBoolean());
            verify(computePodManager, never()).createComputePod(any());
        }

        @Test
        @DisplayName("UT-SVC-BR-013: 已是默认分支 — 抛出 BadRequestException")
        void promote_alreadyDefault_throwsBadRequest() {
            var targetBranch = new BranchEntity();
            targetBranch.setId("br_main");
            targetBranch.setDatabaseId("db_test001");
            targetBranch.setName("main");
            targetBranch.setIsDefault(true);

            when(databaseRepository.findByIdAndTenantIdForUpdate("db_test001", testTenant.getId()))
                    .thenReturn(Optional.of(testDatabase));
            when(branchRepository.findByIdAndDatabaseId("br_main", "db_test001"))
                    .thenReturn(Optional.of(targetBranch));

            assertThatThrownBy(() ->
                    branchService.promote(testTenant, "db_test001", "br_main"))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("already the default");
        }
    }

    @Nested
    @DisplayName("恢复分支")
    class RestoreBranch {

        @Test
        @DisplayName("UT-SVC-BR-014: 按版本ID恢复 — 创建备份分支和新时间线")
        void restore_byVersionId_createsBackupAndNewTimeline() {
            testDatabase.setComputePodName("compute-db_test001");

            var branch = new BranchEntity();
            branch.setId("br_main");
            branch.setDatabaseId("db_test001");
            branch.setName("main");
            branch.setIsDefault(true);
            branch.setNeonTimelineId("old-timeline");
            branch.setParentBranchId(null);
            branch.setParentBranchName(null);
            branch.setStatus(BranchStatus.ACTIVE);

            var targetVersion = new VersionEntity();
            targetVersion.setId("ver1");
            targetVersion.setBranchId("br_main");
            targetVersion.setLsn(100L);
            targetVersion.setLsnHex("0/64");
            targetVersion.setSnapshotTimelineId("snap1");

            var v1 = new VersionEntity();
            v1.setId("v1");
            v1.setBranchId("br_main");
            v1.setLsn(50L);

            var v2 = new VersionEntity();
            v2.setId("v2");
            v2.setBranchId("br_main");
            v2.setLsn(100L);

            var v3 = new VersionEntity();
            v3.setId("v3");
            v3.setBranchId("br_main");
            v3.setLsn(200L);

            when(databaseRepository.findByIdAndTenantIdForUpdate("db_test001", testTenant.getId()))
                    .thenReturn(Optional.of(testDatabase));
            when(branchRepository.findByIdAndDatabaseId("br_main", "db_test001"))
                    .thenReturn(Optional.of(branch));
            when(versionRepository.findByIdAndBranchId("ver1", "br_main"))
                    .thenReturn(Optional.of(targetVersion));
            when(branchRepository.save(any(BranchEntity.class)))
                    .thenAnswer(inv -> {
                        BranchEntity e = inv.getArgument(0);
                        if (e.getId() == null) e.setId("br_backup001");
                        return e;
                    });
            when(neonApiClient.createTimeline(eq("neon-tenant-abc"), any()))
                    .thenReturn(new NeonTimeline("new-timeline-123"));
            when(versionRepository.findAllByBranchIdOrderByLsnAsc("br_main"))
                    .thenReturn(List.of(v1, v2, v3));
            when(databaseRepository.save(any(DatabaseEntity.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            var result = branchService.restore(testTenant, "db_test001", "br_main",
                    new RestoreBranchRequest("ver1", null));

            // Verify backup branch saved with BACKUP type and old timeline
            verify(branchRepository, atLeast(2)).save(any(BranchEntity.class));
            verify(neonApiClient).createTimeline(eq("neon-tenant-abc"), argThat(req ->
                    req.getAncestorTimelineId().equals("snap1") &&
                    req.getAncestorStartLsn().equals("0/64")));

            // Branch timeline updated
            assertThat(branch.getNeonTimelineId()).isNotEqualTo("old-timeline");

            // v3 (lsn=200 > 100) moved to backup branch
            assertThat(v3.getBranchId()).isEqualTo("br_backup001");
            // v1, v2 remain on original branch
            assertThat(v1.getBranchId()).isEqualTo("br_main");
            assertThat(v2.getBranchId()).isEqualTo("br_main");

            // Compute rebuilt for default branch
            verify(computePodManager).deleteComputePod("compute-db_test001", true);
            verify(computePodManager).createComputePod(any());
        }

        @Test
        @DisplayName("UT-SVC-BR-015: 按LSN恢复 — 使用分支时间线作为祖先")
        void restore_byLsn_works() {
            var branch = new BranchEntity();
            branch.setId("br_main");
            branch.setDatabaseId("db_test001");
            branch.setName("main");
            branch.setIsDefault(false);
            branch.setNeonTimelineId("branch-timeline");
            branch.setStatus(BranchStatus.ACTIVE);

            when(databaseRepository.findByIdAndTenantIdForUpdate("db_test001", testTenant.getId()))
                    .thenReturn(Optional.of(testDatabase));
            when(branchRepository.findByIdAndDatabaseId("br_main", "db_test001"))
                    .thenReturn(Optional.of(branch));
            when(branchRepository.save(any(BranchEntity.class)))
                    .thenAnswer(inv -> {
                        BranchEntity e = inv.getArgument(0);
                        if (e.getId() == null) e.setId("br_backup002");
                        return e;
                    });
            when(neonApiClient.createTimeline(eq("neon-tenant-abc"), any()))
                    .thenReturn(new NeonTimeline("new-timeline-456"));
            when(versionRepository.findAllByBranchIdOrderByLsnAsc("br_main"))
                    .thenReturn(List.of());

            var result = branchService.restore(testTenant, "db_test001", "br_main",
                    new RestoreBranchRequest(null, "0/C8"));

            // Ancestor timeline is branch's own timeline (not a version's snapshot)
            verify(neonApiClient).createTimeline(eq("neon-tenant-abc"), argThat(req ->
                    req.getAncestorTimelineId().equals("branch-timeline") &&
                    req.getAncestorStartLsn().equals("0/C8")));

            // Non-default branch: compute NOT rebuilt
            verify(computePodManager, never()).deleteComputePod(any(), anyBoolean());
            verify(computePodManager, never()).createComputePod(any());
        }

        @Test
        @DisplayName("UT-SVC-BR-016: 既无版本ID也无LSN — 抛出 BadRequestException")
        void restore_neitherVersionNorLsn_throwsBadRequest() {
            var branch = new BranchEntity();
            branch.setId("br_main");
            branch.setDatabaseId("db_test001");
            branch.setName("main");
            branch.setIsDefault(true);
            branch.setNeonTimelineId("old-timeline");

            when(databaseRepository.findByIdAndTenantIdForUpdate("db_test001", testTenant.getId()))
                    .thenReturn(Optional.of(testDatabase));
            when(branchRepository.findByIdAndDatabaseId("br_main", "db_test001"))
                    .thenReturn(Optional.of(branch));

            assertThatThrownBy(() ->
                    branchService.restore(testTenant, "db_test001", "br_main",
                            new RestoreBranchRequest(null, null)))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("target_version_id or target_lsn");
        }
    }
}
