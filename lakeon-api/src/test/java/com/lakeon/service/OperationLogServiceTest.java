package com.lakeon.service;

import com.lakeon.model.entity.OperationLogEntity;
import com.lakeon.model.enums.OperationStatus;
import com.lakeon.model.enums.OperationType;
import com.lakeon.repository.OperationLogRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OperationLogService 单元测试")
class OperationLogServiceTest {

    @Mock
    private OperationLogRepository repository;

    private OperationLogService operationLogService;

    @BeforeEach
    void setUp() {
        operationLogService = new OperationLogService(repository, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("startOperation — 创建 IN_PROGRESS 状态的操作日志")
    void startOperation_shouldCreateInProgressLog() {
        // Given
        when(repository.save(any(OperationLogEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // When
        var log = operationLogService.startOperation(
                "db_001", "tn_test001", "my-db", OperationType.CREATE);

        // Then
        ArgumentCaptor<OperationLogEntity> captor = ArgumentCaptor.forClass(OperationLogEntity.class);
        verify(repository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getDatabaseId()).isEqualTo("db_001");
        assertThat(saved.getTenantId()).isEqualTo("tn_test001");
        assertThat(saved.getDatabaseName()).isEqualTo("my-db");
        assertThat(saved.getOperationType()).isEqualTo(OperationType.CREATE);
        assertThat(saved.getStatus()).isEqualTo(OperationStatus.IN_PROGRESS);
        assertThat(saved.getStartedAt()).isNotNull();
    }

    @Test
    @DisplayName("completeOperation — 成功时设置 SUCCESS 状态和耗时")
    void completeOperation_shouldSetSuccessAndDuration() {
        // Given
        var log = new OperationLogEntity();
        log.setStartedAt(java.time.Instant.now().minusMillis(100));
        log.setStatus(OperationStatus.IN_PROGRESS);
        log.setOperationType(OperationType.CREATE);
        when(repository.save(any(OperationLogEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // When
        operationLogService.completeOperation(log, null);

        // Then
        verify(repository).save(log);
        assertThat(log.getStatus()).isEqualTo(OperationStatus.SUCCESS);
        assertThat(log.getCompletedAt()).isNotNull();
        assertThat(log.getDurationMs()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(log.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("completeOperation — 有错误时设置 FAILED 状态和错误信息")
    void completeOperation_withError_shouldSetFailed() {
        // Given
        var log = new OperationLogEntity();
        log.setStartedAt(java.time.Instant.now().minusMillis(50));
        log.setStatus(OperationStatus.IN_PROGRESS);
        log.setOperationType(OperationType.DELETE);
        when(repository.save(any(OperationLogEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // When
        operationLogService.completeOperation(log, "Pageserver unavailable");

        // Then
        verify(repository).save(log);
        assertThat(log.getStatus()).isEqualTo(OperationStatus.FAILED);
        assertThat(log.getErrorMessage()).isEqualTo("Pageserver unavailable");
        assertThat(log.getCompletedAt()).isNotNull();
        assertThat(log.getDurationMs()).isNotNull().isGreaterThanOrEqualTo(0L);
    }
}
