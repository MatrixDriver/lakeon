package com.lakeon.service;

import com.lakeon.model.dto.CreateVersionRequest;
import com.lakeon.model.dto.SquashVersionsRequest;
import com.lakeon.model.dto.VersionResponse;
import com.lakeon.model.entity.BranchEntity;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.entity.VersionEntity;
import com.lakeon.neon.NeonApiClient;
import com.lakeon.neon.dto.NeonTimeline;
import com.lakeon.repository.BranchRepository;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.VersionRepository;
import com.lakeon.service.exception.ConflictException;
import com.lakeon.service.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
@DisplayName("VersionService 单元测试")
class VersionServiceTest {

    @Mock
    private VersionRepository versionRepository;

    @Mock
    private BranchRepository branchRepository;

    @Mock
    private DatabaseRepository databaseRepository;

    @Mock
    private NeonApiClient neonApiClient;

    @Mock
    private com.lakeon.k8s.ComputePodManager computePodManager;

    @InjectMocks
    private VersionService versionService;

    private TenantEntity testTenant;
    private DatabaseEntity testDatabase;
    private BranchEntity testBranch;

    private static final String TENANT_ID = "tenant_abc";
    private static final String DB_ID = "db_123";
    private static final String BRANCH_ID = "br_456";
    private static final String NEON_TENANT_ID = "aabbccdd00112233aabbccdd00112233";
    private static final String NEON_TIMELINE_ID = "11223344556677881122334455667788";

    @BeforeEach
    void setUp() {
        testTenant = new TenantEntity();
        testTenant.setId(TENANT_ID);

        testDatabase = new DatabaseEntity();
        testDatabase.setId(DB_ID);
        testDatabase.setTenantId(TENANT_ID);
        testDatabase.setNeonTenantId(NEON_TENANT_ID);

        testBranch = new BranchEntity();
        testBranch.setId(BRANCH_ID);
        testBranch.setDatabaseId(DB_ID);
        testBranch.setNeonTimelineId(NEON_TIMELINE_ID);
    }

    @Test
    @DisplayName("create - current LSN - creates snapshot timeline")
    void create_currentLsn_createsSnapshotTimeline() {
        // Arrange
        CreateVersionRequest request = new CreateVersionRequest("v1.0", "first version", null, null);

        when(databaseRepository.findByIdAndTenantId(DB_ID, TENANT_ID))
                .thenReturn(Optional.of(testDatabase));
        when(branchRepository.findByIdAndDatabaseId(BRANCH_ID, DB_ID))
                .thenReturn(Optional.of(testBranch));
        when(versionRepository.findByBranchIdAndName(BRANCH_ID, "v1.0"))
                .thenReturn(Optional.empty());

        NeonTimeline currentTimeline = new NeonTimeline();
        currentTimeline.setLastRecordLsn("0/1500000");
        when(neonApiClient.getTimeline(NEON_TENANT_ID, NEON_TIMELINE_ID))
                .thenReturn(currentTimeline);

        NeonTimeline snapshotTimeline = new NeonTimeline();
        when(neonApiClient.createTimeline(eq(NEON_TENANT_ID), any()))
                .thenReturn(snapshotTimeline);

        when(versionRepository.save(any(VersionEntity.class))).thenAnswer(inv -> {
            VersionEntity e = inv.getArgument(0);
            e.prePersist();
            return e;
        });

        // Act
        VersionResponse response = versionService.create(testTenant, DB_ID, BRANCH_ID, request);

        // Assert
        assertThat(response.getName()).isEqualTo("v1.0");
        assertThat(response.getLsn()).isEqualTo("0/1500000");
        verify(neonApiClient).getTimeline(NEON_TENANT_ID, NEON_TIMELINE_ID);
        verify(neonApiClient).createTimeline(eq(NEON_TENANT_ID), any());
        verify(versionRepository).save(any(VersionEntity.class));
    }

    @Test
    @DisplayName("create - explicit LSN - uses provided LSN")
    void create_explicitLsn_usesProvidedLsn() {
        // Arrange
        CreateVersionRequest request = new CreateVersionRequest("v2.0", null, null, "0/1A00000");

        when(databaseRepository.findByIdAndTenantId(DB_ID, TENANT_ID))
                .thenReturn(Optional.of(testDatabase));
        when(branchRepository.findByIdAndDatabaseId(BRANCH_ID, DB_ID))
                .thenReturn(Optional.of(testBranch));
        when(versionRepository.findByBranchIdAndName(BRANCH_ID, "v2.0"))
                .thenReturn(Optional.empty());

        NeonTimeline snapshotTimeline = new NeonTimeline();
        when(neonApiClient.createTimeline(eq(NEON_TENANT_ID), any()))
                .thenReturn(snapshotTimeline);

        when(versionRepository.save(any(VersionEntity.class))).thenAnswer(inv -> {
            VersionEntity e = inv.getArgument(0);
            e.prePersist();
            return e;
        });

        // Act
        VersionResponse response = versionService.create(testTenant, DB_ID, BRANCH_ID, request);

        // Assert
        assertThat(response.getLsn()).isEqualTo("0/1A00000");
        verify(neonApiClient, never()).getTimeline(any(), any());
        verify(neonApiClient).createTimeline(eq(NEON_TENANT_ID), any());
    }

    @Test
    @DisplayName("create - duplicate name - throws ConflictException")
    void create_duplicateName_throwsConflict() {
        CreateVersionRequest request = new CreateVersionRequest("v1.0", null, null, null);

        when(databaseRepository.findByIdAndTenantId(DB_ID, TENANT_ID))
                .thenReturn(Optional.of(testDatabase));
        when(branchRepository.findByIdAndDatabaseId(BRANCH_ID, DB_ID))
                .thenReturn(Optional.of(testBranch));
        when(versionRepository.findByBranchIdAndName(BRANCH_ID, "v1.0"))
                .thenReturn(Optional.of(new VersionEntity()));

        assertThatThrownBy(() -> versionService.create(testTenant, DB_ID, BRANCH_ID, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("v1.0");
    }

    @Test
    @DisplayName("list - returns versions ordered by LSN")
    void list_returnsVersionsOrderedByLsn() {
        when(databaseRepository.findByIdAndTenantId(DB_ID, TENANT_ID))
                .thenReturn(Optional.of(testDatabase));
        when(branchRepository.findByIdAndDatabaseId(BRANCH_ID, DB_ID))
                .thenReturn(Optional.of(testBranch));

        VersionEntity v1 = makeVersion("ver_001", "v1", 100, "0/64");
        VersionEntity v2 = makeVersion("ver_002", "v2", 200, "0/C8");
        when(versionRepository.findAllByBranchIdOrderByLsnAsc(BRANCH_ID))
                .thenReturn(List.of(v1, v2));

        List<VersionResponse> responses = versionService.list(testTenant, DB_ID, BRANCH_ID);

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).getName()).isEqualTo("v1");
        assertThat(responses.get(1).getName()).isEqualTo("v2");
    }

    @Test
    @DisplayName("get - returns version")
    void get_returnsVersion() {
        when(databaseRepository.findByIdAndTenantId(DB_ID, TENANT_ID))
                .thenReturn(Optional.of(testDatabase));
        when(branchRepository.findByIdAndDatabaseId(BRANCH_ID, DB_ID))
                .thenReturn(Optional.of(testBranch));

        VersionEntity v = makeVersion("ver_001", "v1", 100, "0/64");
        when(versionRepository.findByIdAndBranchId("ver_001", BRANCH_ID))
                .thenReturn(Optional.of(v));

        VersionResponse response = versionService.get(testTenant, DB_ID, BRANCH_ID, "ver_001");

        assertThat(response.getId()).isEqualTo("ver_001");
        assertThat(response.getName()).isEqualTo("v1");
    }

    @Test
    @DisplayName("get - not found - throws NotFoundException")
    void get_notFound_throwsNotFound() {
        when(databaseRepository.findByIdAndTenantId(DB_ID, TENANT_ID))
                .thenReturn(Optional.of(testDatabase));
        when(branchRepository.findByIdAndDatabaseId(BRANCH_ID, DB_ID))
                .thenReturn(Optional.of(testBranch));
        when(versionRepository.findByIdAndBranchId("ver_999", BRANCH_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> versionService.get(testTenant, DB_ID, BRANCH_ID, "ver_999"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("ver_999");
    }

    @Test
    @DisplayName("delete - deletes entity and timeline")
    void delete_deletesEntityAndTimeline() {
        when(databaseRepository.findByIdAndTenantId(DB_ID, TENANT_ID))
                .thenReturn(Optional.of(testDatabase));
        when(branchRepository.findByIdAndDatabaseId(BRANCH_ID, DB_ID))
                .thenReturn(Optional.of(testBranch));

        VersionEntity v = makeVersion("ver_001", "v1", 100, "0/64");
        v.setSnapshotTimelineId("snap_timeline_abc");
        when(versionRepository.findByIdAndBranchId("ver_001", BRANCH_ID))
                .thenReturn(Optional.of(v));

        versionService.delete(testTenant, DB_ID, BRANCH_ID, "ver_001");

        verify(neonApiClient).deleteTimeline(NEON_TENANT_ID, "snap_timeline_abc");
        verify(versionRepository).delete(v);
    }

    @Test
    @DisplayName("squash - deletes middle versions")
    void squash_deletesMiddleVersions() {
        when(databaseRepository.findByIdAndTenantId(DB_ID, TENANT_ID))
                .thenReturn(Optional.of(testDatabase));
        when(branchRepository.findByIdAndDatabaseId(BRANCH_ID, DB_ID))
                .thenReturn(Optional.of(testBranch));

        VersionEntity v1 = makeVersion("ver_001", "v1", 100, "0/64");
        v1.setSnapshotTimelineId("snap_1");
        VersionEntity v2 = makeVersion("ver_002", "v2", 200, "0/C8");
        v2.setSnapshotTimelineId("snap_2");
        VersionEntity v3 = makeVersion("ver_003", "v3", 300, "0/12C");
        v3.setSnapshotTimelineId("snap_3");

        when(versionRepository.findByIdAndBranchId("ver_001", BRANCH_ID))
                .thenReturn(Optional.of(v1));
        when(versionRepository.findByIdAndBranchId("ver_003", BRANCH_ID))
                .thenReturn(Optional.of(v3));
        when(versionRepository.findAllByBranchIdAndLsnBetweenOrderByLsnAsc(BRANCH_ID, 100L, 300L))
                .thenReturn(List.of(v1, v2, v3));
        when(versionRepository.findAllByBranchIdOrderByLsnAsc(BRANCH_ID))
                .thenReturn(List.of(v1, v3));

        SquashVersionsRequest request = new SquashVersionsRequest("ver_001", "ver_003");
        List<VersionResponse> result = versionService.squash(testTenant, DB_ID, BRANCH_ID, request);

        // v2 should be deleted
        verify(neonApiClient).deleteTimeline(NEON_TENANT_ID, "snap_2");
        verify(versionRepository).delete(v2);
        // v1 and v3 should NOT be deleted
        verify(neonApiClient, never()).deleteTimeline(NEON_TENANT_ID, "snap_1");
        verify(neonApiClient, never()).deleteTimeline(NEON_TENANT_ID, "snap_3");
        verify(versionRepository, never()).delete(v1);
        verify(versionRepository, never()).delete(v3);

        assertThat(result).hasSize(2);
    }

    private VersionEntity makeVersion(String id, String name, long lsn, String lsnHex) {
        VersionEntity v = new VersionEntity();
        v.setId(id);
        v.setBranchId(BRANCH_ID);
        v.setName(name);
        v.setLsn(lsn);
        v.setLsnHex(lsnHex);
        v.setCreatedBy("api");
        v.setCreatedAt(Instant.now());
        return v;
    }
}
