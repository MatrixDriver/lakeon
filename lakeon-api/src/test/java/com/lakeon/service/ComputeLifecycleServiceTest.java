package com.lakeon.service;

import com.lakeon.config.LakeonProperties;
import com.lakeon.k8s.ComputePodManager;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.repository.BranchRepository;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.exception.WakeComputeTimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ComputeLifecycleService 单元测试")
class ComputeLifecycleServiceTest {

    @Mock
    private DatabaseRepository databaseRepository;

    @Mock
    private BranchRepository branchRepository;

    @Mock
    private ComputePodManager computePodManager;

    @Mock
    private OperationLogService operationLogService;

    private LakeonProperties props;
    private ComputeLifecycleService computeLifecycleService;

    @BeforeEach
    void setUp() {
        props = new LakeonProperties();
        computeLifecycleService = new ComputeLifecycleService(
                databaseRepository, branchRepository, computePodManager, operationLogService, props,
                TestTransactionTemplate.create());
    }

    @Test
    @DisplayName("UT-SVC-CL-001: wake_compute — cold path, create Pod and return address")
    void wakeCompute_coldPath_success() {
        var dbEntity = new DatabaseEntity();
        dbEntity.setId("db_wake001");
        dbEntity.setStatus(DatabaseStatus.SUSPENDED);
        dbEntity.setNeonTenantId("neon-tenant-wake");
        dbEntity.setNeonTimelineId("neon-timeline-wake");
        dbEntity.setComputeSize("1cu");

        when(databaseRepository.findById("db_wake001"))
                .thenReturn(Optional.of(dbEntity));
        when(computePodManager.createComputePod(any()))
                .thenAnswer(inv -> {
                    DatabaseEntity entity = inv.getArgument(0);
                    entity.setComputePodName("compute-db-wake001");
                    return "10.0.1.50:5432";
                });
        when(computePodManager.waitForPodReady(eq("compute-db-wake001"), anyLong()))
                .thenReturn(true);

        var address = computeLifecycleService.wakeCompute("db_wake001");

        assertThat(address).isEqualTo("10.0.1.50:5432");
        verify(computePodManager).createComputePod(any());
        verify(computePodManager).waitForPodReady(anyString(), anyLong());

        ArgumentCaptor<DatabaseEntity> captor = ArgumentCaptor.forClass(DatabaseEntity.class);
        verify(databaseRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(DatabaseStatus.RUNNING);
        assertThat(captor.getValue().getSuspendedAt()).isNull();
    }

    @Test
    @DisplayName("UT-SVC-CL-002: wake_compute — Pod timeout throws WakeComputeTimeoutException")
    void wakeCompute_timeout() {
        var dbEntity = new DatabaseEntity();
        dbEntity.setId("db_wake002");
        dbEntity.setStatus(DatabaseStatus.SUSPENDED);
        dbEntity.setNeonTenantId("neon-tenant-wake");
        dbEntity.setNeonTimelineId("neon-timeline-wake");
        dbEntity.setComputeSize("1cu");

        when(databaseRepository.findById("db_wake002"))
                .thenReturn(Optional.of(dbEntity));
        when(computePodManager.createComputePod(any()))
                .thenAnswer(inv -> {
                    DatabaseEntity entity = inv.getArgument(0);
                    entity.setComputePodName("compute-db-wake002");
                    return "10.0.1.51:5432";
                });
        when(computePodManager.waitForPodReady(eq("compute-db-wake002"), anyLong()))
                .thenReturn(false);

        assertThatThrownBy(() ->
                computeLifecycleService.wakeCompute("db_wake002"))
                .isInstanceOf(WakeComputeTimeoutException.class)
                .hasMessageContaining("timeout");

        ArgumentCaptor<DatabaseEntity> captor = ArgumentCaptor.forClass(DatabaseEntity.class);
        verify(databaseRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(DatabaseStatus.ERROR);
    }

    @Test
    @DisplayName("UT-SVC-CL-003: warm wake — Pod still running, instant resume")
    void wakeCompute_warmPath_success() {
        var dbEntity = new DatabaseEntity();
        dbEntity.setId("db_warm001");
        dbEntity.setStatus(DatabaseStatus.SUSPENDED);
        dbEntity.setComputePodName("compute-db-warm001");
        dbEntity.setComputeHost("10.0.1.60");
        dbEntity.setComputePort(55433);
        dbEntity.setSuspendedAt(Instant.now());

        when(databaseRepository.findById("db_warm001"))
                .thenReturn(Optional.of(dbEntity));
        when(computePodManager.isPodReady("compute-db-warm001"))
                .thenReturn(true);

        var address = computeLifecycleService.wakeCompute("db_warm001");

        assertThat(address).isEqualTo("10.0.1.60:55433");
        // Should NOT create a new Pod
        verify(computePodManager, never()).createComputePod(any());
        verify(computePodManager, never()).waitForPodReady(anyString(), anyLong());

        ArgumentCaptor<DatabaseEntity> captor = ArgumentCaptor.forClass(DatabaseEntity.class);
        verify(databaseRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(DatabaseStatus.RUNNING);
        assertThat(captor.getValue().getSuspendedAt()).isNull();
    }

    @Test
    @DisplayName("UT-SVC-CL-004: auto-suspend retains Pod (no deletion)")
    void autoSuspend_retainsPod() {
        var dbEntity = new DatabaseEntity();
        dbEntity.setId("db_auto001");
        dbEntity.setTenantId("tn_test");
        dbEntity.setName("testdb");
        dbEntity.setStatus(DatabaseStatus.RUNNING);
        dbEntity.setComputePodName("compute-db-auto001");
        dbEntity.setComputeHost("10.0.1.70");
        dbEntity.setComputePort(55433);
        dbEntity.setSuspendTimeout("5m");

        when(databaseRepository.findAllByStatus(DatabaseStatus.RUNNING))
                .thenReturn(List.of(dbEntity));
        when(databaseRepository.findAllByStatus(DatabaseStatus.SUSPENDED))
                .thenReturn(List.of());
        when(computePodManager.getLastActivityTime("compute-db-auto001"))
                .thenReturn(System.currentTimeMillis() - 600_000L); // 10 min ago

        computeLifecycleService.checkAutoSuspend();

        // Pod should NOT be deleted
        verify(computePodManager, never()).deleteComputePod(anyString());

        ArgumentCaptor<DatabaseEntity> captor = ArgumentCaptor.forClass(DatabaseEntity.class);
        verify(databaseRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(DatabaseStatus.SUSPENDED);
        // Pod info retained
        assertThat(saved.getComputePodName()).isEqualTo("compute-db-auto001");
        assertThat(saved.getComputeHost()).isEqualTo("10.0.1.70");
        assertThat(saved.getComputePort()).isEqualTo(55433);
        assertThat(saved.getSuspendedAt()).isNotNull();
    }

    @Test
    @DisplayName("UT-SVC-CL-005: expired Pod cleanup deletes Pod after retain period")
    void cleanupExpiredPods_deletesOldPods() {
        props.getSuspend().setPodRetainMinutes(30);

        var dbEntity = new DatabaseEntity();
        dbEntity.setId("db_expired001");
        dbEntity.setTenantId("tn_test");
        dbEntity.setStatus(DatabaseStatus.SUSPENDED);
        dbEntity.setComputePodName("compute-db-expired001");
        dbEntity.setComputeHost("10.0.1.80");
        dbEntity.setComputePort(55433);
        // Suspended 60 minutes ago (> 30 min retain)
        dbEntity.setSuspendedAt(Instant.now().minusSeconds(3600));

        when(databaseRepository.findAllByStatus(DatabaseStatus.RUNNING))
                .thenReturn(List.of());
        when(databaseRepository.findAllByStatus(DatabaseStatus.SUSPENDED))
                .thenReturn(List.of(dbEntity));

        computeLifecycleService.checkAutoSuspend();

        // Pod should be deleted after retain period
        verify(computePodManager).deleteComputePod("compute-db-expired001");

        ArgumentCaptor<DatabaseEntity> captor = ArgumentCaptor.forClass(DatabaseEntity.class);
        verify(databaseRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getComputePodName()).isNull();
        assertThat(saved.getComputeHost()).isNull();
        assertThat(saved.getComputePort()).isNull();
    }
}
