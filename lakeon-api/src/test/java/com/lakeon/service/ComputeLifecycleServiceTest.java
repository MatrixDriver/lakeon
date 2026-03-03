package com.lakeon.service;

import com.lakeon.k8s.ComputePodManager;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.exception.WakeComputeTimeoutException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
    private ComputePodManager computePodManager;

    @InjectMocks
    private ComputeLifecycleService computeLifecycleService;

    @Test
    @DisplayName("UT-SVC-CL-001: wake_compute — 正常唤醒，创建 Pod 并返回 compute 地址")
    void wakeCompute_success() {
        // Given
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

        // When
        var address = computeLifecycleService.wakeCompute("db_wake001");

        // Then
        assertThat(address).isEqualTo("10.0.1.50:5432");
        verify(computePodManager).createComputePod(any());
        verify(computePodManager).waitForPodReady(anyString(), anyLong());

        ArgumentCaptor<DatabaseEntity> captor = ArgumentCaptor.forClass(DatabaseEntity.class);
        verify(databaseRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(DatabaseStatus.RUNNING);
    }

    @Test
    @DisplayName("UT-SVC-CL-002: wake_compute — Pod 启动超时，抛出 WakeComputeTimeoutException")
    void wakeCompute_timeout() {
        // Given
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

        // When / Then
        assertThatThrownBy(() ->
                computeLifecycleService.wakeCompute("db_wake002"))
                .isInstanceOf(WakeComputeTimeoutException.class)
                .hasMessageContaining("timeout");

        // 验证状态设置为 error
        ArgumentCaptor<DatabaseEntity> captor = ArgumentCaptor.forClass(DatabaseEntity.class);
        verify(databaseRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(DatabaseStatus.ERROR);
    }

    @Test
    @DisplayName("UT-SVC-CL-003: 自动休眠检测 — 超时触发 Pod 销毁")
    void autoSuspend_timeoutTriggered() {
        // Given
        var dbEntity = new DatabaseEntity();
        dbEntity.setId("db_auto001");
        dbEntity.setStatus(DatabaseStatus.RUNNING);
        dbEntity.setComputePodName("compute-db_auto001");
        dbEntity.setSuspendTimeout("5m");

        when(databaseRepository.findAllByStatus(DatabaseStatus.RUNNING))
                .thenReturn(java.util.List.of(dbEntity));
        when(computePodManager.getLastActivityTime("compute-db_auto001"))
                .thenReturn(System.currentTimeMillis() - 600_000L); // 10 分钟前

        // When
        computeLifecycleService.checkAutoSuspend();

        // Then
        verify(computePodManager).deleteComputePod("compute-db_auto001");
        ArgumentCaptor<DatabaseEntity> captor = ArgumentCaptor.forClass(DatabaseEntity.class);
        verify(databaseRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(DatabaseStatus.SUSPENDED);
    }
}
