package com.lakeon.service;

import com.lakeon.k8s.ComputePodManager;
import com.lakeon.model.dto.CreateBranchRequest;
import com.lakeon.model.entity.BranchEntity;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.neon.NeonApiClient;
import com.lakeon.neon.dto.NeonTimeline;
import com.lakeon.repository.BranchRepository;
import com.lakeon.repository.DatabaseRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BranchService 单元测试")
class BranchServiceTest {

    @Mock
    private DatabaseRepository databaseRepository;

    @Mock
    private BranchRepository branchRepository;

    @Mock
    private NeonApiClient neonApiClient;

    @Mock
    private ComputePodManager computePodManager;

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
            var request = new CreateBranchRequest("feature-test", false);
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
            var request = new CreateBranchRequest("feature-test", true);
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
            var request = new CreateBranchRequest("feature-test", false);
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
}
