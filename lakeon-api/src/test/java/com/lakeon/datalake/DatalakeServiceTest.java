package com.lakeon.datalake;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DatalakeService 单元测试")
class DatalakeServiceTest {

    @Mock
    private DatalakeJobRepository repository;
    @Mock
    private PythonJobRunner pythonJobRunner;
    @Mock
    private RayJobRunner rayJobRunner;
    @Mock
    private FinetuneJobRunner finetuneJobRunner;

    private DatalakeService service;

    @BeforeEach
    void setUp() {
        service = new DatalakeService(repository, new ObjectMapper(), new com.lakeon.config.LakeonProperties());
        // inject optional runner fields via reflection
        setField(service, "pythonJobRunner", pythonJobRunner);
        setField(service, "rayJobRunner", rayJobRunner);
        setField(service, "finetuneJobRunner", finetuneJobRunner);
    }

    private void setField(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private DatalakeJobEntity makeJob(String id, String tenantId, DatalakeJobType type,
                                      DatalakeJobStatus status) {
        DatalakeJobEntity e = new DatalakeJobEntity();
        e.setId(id);
        e.setTenantId(tenantId);
        e.setName("test-job");
        e.setType(type);
        e.setStatus(status);
        e.setSpec("{}");
        return e;
    }

    // -------------------------------------------------------------------------
    @Nested
    @DisplayName("getJob")
    class GetJob {

        @Test
        void getJob_returnsJob() {
            DatalakeJobEntity job = makeJob("j1", "t1", DatalakeJobType.PYTHON, DatalakeJobStatus.RUNNING);
            when(repository.findById("j1")).thenReturn(Optional.of(job));

            DatalakeJobResponse resp = service.getJob("t1", "j1");

            assertThat(resp.getId()).isEqualTo("j1");
            assertThat(resp.getStatus()).isEqualTo(DatalakeJobStatus.RUNNING);
        }

        @Test
        void getJob_notFound_throws404() {
            when(repository.findById("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getJob("t1", "missing"))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void getJob_wrongTenant_throws403() {
            DatalakeJobEntity job = makeJob("j1", "t1", DatalakeJobType.PYTHON, DatalakeJobStatus.RUNNING);
            when(repository.findById("j1")).thenReturn(Optional.of(job));

            assertThatThrownBy(() -> service.getJob("other-tenant", "j1"))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }
    }

    // -------------------------------------------------------------------------
    @Nested
    @DisplayName("listJobs")
    class ListJobs {

        @Test
        void listJobs_noStatus_returnsAll() {
            DatalakeJobEntity j1 = makeJob("j1", "t1", DatalakeJobType.PYTHON, DatalakeJobStatus.RUNNING);
            DatalakeJobEntity j2 = makeJob("j2", "t1", DatalakeJobType.RAY, DatalakeJobStatus.SUCCEEDED);
            when(repository.findByTenantIdOrderByCreatedAtDesc("t1")).thenReturn(List.of(j1, j2));

            List<DatalakeJobResponse> result = service.listJobs("t1", null);

            assertThat(result).hasSize(2);
            verify(repository).findByTenantIdOrderByCreatedAtDesc("t1");
            verify(repository, never()).findByTenantIdAndStatusOrderByCreatedAtDesc(any(), any());
        }

        @Test
        void listJobs_withStatus_returnsFiltered() {
            DatalakeJobEntity j1 = makeJob("j1", "t1", DatalakeJobType.PYTHON, DatalakeJobStatus.RUNNING);
            when(repository.findByTenantIdAndStatusOrderByCreatedAtDesc("t1", DatalakeJobStatus.RUNNING))
                    .thenReturn(List.of(j1));

            List<DatalakeJobResponse> result = service.listJobs("t1", DatalakeJobStatus.RUNNING);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getStatus()).isEqualTo(DatalakeJobStatus.RUNNING);
            verify(repository).findByTenantIdAndStatusOrderByCreatedAtDesc("t1", DatalakeJobStatus.RUNNING);
        }
    }

    // -------------------------------------------------------------------------
    @Nested
    @DisplayName("submitJob")
    class SubmitJob {

        private DatalakeJobRequest makeReq(DatalakeJobType type) {
            DatalakeJobRequest req = new DatalakeJobRequest();
            req.setName("my-job");
            req.setType(type);
            req.setEntrypoint("python main.py");
            return req;
        }

        @BeforeEach
        void stubSave() {
            lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        void submitJob_python_savesPendingAndCallsRunner() {
            DatalakeJobRequest req = makeReq(DatalakeJobType.PYTHON);

            DatalakeJobResponse resp = service.submitJob("t1", req);

            assertThat(resp.getStatus()).isEqualTo(DatalakeJobStatus.PENDING);
            assertThat(resp.getType()).isEqualTo(DatalakeJobType.PYTHON);
            verify(pythonJobRunner).start(any(), eq(req));
            verify(rayJobRunner, never()).start(any(), any());
        }

        @Test
        void submitJob_ray_savesPendingAndCallsRunner() {
            DatalakeJobRequest req = makeReq(DatalakeJobType.RAY);

            DatalakeJobResponse resp = service.submitJob("t1", req);

            assertThat(resp.getStatus()).isEqualTo(DatalakeJobStatus.PENDING);
            verify(rayJobRunner).start(any(), eq(req));
            verify(pythonJobRunner, never()).start(any(), any());
        }

        @Test
        void submitJob_finetune_savesPendingAndCallsRunner() {
            DatalakeJobRequest req = makeReq(DatalakeJobType.FINETUNE);

            DatalakeJobResponse resp = service.submitJob("t1", req);

            assertThat(resp.getStatus()).isEqualTo(DatalakeJobStatus.PENDING);
            verify(finetuneJobRunner).start(any(), eq(req));
        }

        @Test
        void submitJob_missingName_throws400() {
            DatalakeJobRequest req = new DatalakeJobRequest();
            req.setType(DatalakeJobType.PYTHON);

            assertThatThrownBy(() -> service.submitJob("t1", req))
                    .isInstanceOf(BadRequestException.class);
        }
    }

    // -------------------------------------------------------------------------
    @Nested
    @DisplayName("cancelJob")
    class CancelJob {

        @Test
        void cancelJob_pendingJob_setsCancelled() {
            DatalakeJobEntity job = makeJob("j1", "t1", DatalakeJobType.PYTHON, DatalakeJobStatus.PENDING);
            // no k8sJobName set, so runner not called
            when(repository.findById("j1")).thenReturn(Optional.of(job));
            when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.cancelJob("t1", "j1");

            assertThat(job.getStatus()).isEqualTo(DatalakeJobStatus.CANCELLED);
            verify(repository).save(job);
            verify(pythonJobRunner, never()).cancel(any());
        }

        @Test
        void cancelJob_runningPythonJob_delegatesToRunner() {
            DatalakeJobEntity job = makeJob("j1", "t1", DatalakeJobType.PYTHON, DatalakeJobStatus.RUNNING);
            job.setK8sJobName("dl-j1");
            when(repository.findById("j1")).thenReturn(Optional.of(job));

            service.cancelJob("t1", "j1");

            verify(pythonJobRunner).cancel(job);
            verify(repository, never()).save(any()); // runner handles save
        }

        @Test
        void cancelJob_alreadySucceeded_throws400() {
            DatalakeJobEntity job = makeJob("j1", "t1", DatalakeJobType.PYTHON, DatalakeJobStatus.SUCCEEDED);
            when(repository.findById("j1")).thenReturn(Optional.of(job));

            assertThatThrownBy(() -> service.cancelJob("t1", "j1"))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        void cancelJob_notFound_throws404() {
            when(repository.findById("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.cancelJob("t1", "missing"))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void cancelJob_wrongTenant_throws403() {
            DatalakeJobEntity job = makeJob("j1", "t1", DatalakeJobType.PYTHON, DatalakeJobStatus.RUNNING);
            when(repository.findById("j1")).thenReturn(Optional.of(job));

            assertThatThrownBy(() -> service.cancelJob("other", "j1"))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.FORBIDDEN));
        }
    }
}
