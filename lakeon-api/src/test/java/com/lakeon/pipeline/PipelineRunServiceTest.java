package com.lakeon.pipeline;

import com.lakeon.service.exception.BadRequestException;
import com.lakeon.service.exception.NotFoundException;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PipelineRunService unit tests")
class PipelineRunServiceTest {

    @Mock private PipelineRunRepository runRepository;
    @Mock private PipelineStepRunRepository stepRunRepository;
    @Mock private PipelineRepository pipelineRepository;
    @Mock private PipelineVersionRepository pipelineVersionRepository;

    private PipelineRunService runService;

    private static final String TENANT_ID = "tn_test001";

    @BeforeEach
    void setUp() {
        runService = new PipelineRunService(runRepository, stepRunRepository,
                pipelineRepository, pipelineVersionRepository);
    }

    // --- UT-SVC-RUN-001: trigger --- creates run + step_runs ---

    @Test
    @DisplayName("UT-SVC-RUN-001: trigger creates run and step runs from dag_yaml")
    void trigger_normalFlow_createsRunAndStepRuns() {
        PipelineEntity pipeline = new PipelineEntity();
        pipeline.setId("pipe_run001");
        pipeline.setTenantId(TENANT_ID);

        PipelineVersionEntity version = new PipelineVersionEntity();
        version.setPipelineId("pipe_run001");
        version.setVersion(1);
        version.setDagYaml("steps:\n" +
                "  - id: chunk\n" +
                "    component: comp_chunk\n" +
                "    component_version: 1\n" +
                "  - id: embed\n" +
                "    component: comp_embed\n" +
                "    component_version: 2\n");

        when(pipelineRepository.findByIdAndTenantId("pipe_run001", TENANT_ID))
                .thenReturn(Optional.of(pipeline));
        when(pipelineVersionRepository.findByPipelineIdAndVersion("pipe_run001", 1))
                .thenReturn(Optional.of(version));
        when(runRepository.save(any(PipelineRunEntity.class)))
                .thenAnswer(inv -> {
                    PipelineRunEntity e = inv.getArgument(0);
                    e.setId("run_test001");
                    e.setCreatedAt(Instant.now());
                    return e;
                });
        when(stepRunRepository.save(any(PipelineStepRunEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        PipelineRunEntity result = runService.trigger(
                TENANT_ID, "pipe_run001", 1, "ds_input", 1);

        assertThat(result.getId()).isEqualTo("run_test001");
        assertThat(result.getStatus()).isEqualTo(PipelineRunStatus.PENDING);
        assertThat(result.getPipelineVersion()).isEqualTo(1);
        assertThat(result.getInputDatasetId()).isEqualTo("ds_input");

        // Verify 2 step runs created
        ArgumentCaptor<PipelineStepRunEntity> stepCaptor =
                ArgumentCaptor.forClass(PipelineStepRunEntity.class);
        verify(stepRunRepository, times(2)).save(stepCaptor.capture());
        List<PipelineStepRunEntity> stepRuns = stepCaptor.getAllValues();
        assertThat(stepRuns.get(0).getStepId()).isEqualTo("chunk");
        assertThat(stepRuns.get(0).getComponentId()).isEqualTo("comp_chunk");
        assertThat(stepRuns.get(0).getComponentVersion()).isEqualTo(1);
        assertThat(stepRuns.get(1).getStepId()).isEqualTo("embed");
        assertThat(stepRuns.get(1).getComponentId()).isEqualTo("comp_embed");
        assertThat(stepRuns.get(1).getComponentVersion()).isEqualTo(2);
    }

    // --- UT-SVC-RUN-002: trigger --- pipeline not found throws ---

    @Test
    @DisplayName("UT-SVC-RUN-002: trigger with non-existent pipeline throws NotFoundException")
    void trigger_pipelineNotFound_throwsNotFoundException() {
        when(pipelineRepository.findByIdAndTenantId("pipe_missing", TENANT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                runService.trigger(TENANT_ID, "pipe_missing", 1, null, null))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("pipe_missing");
    }

    // --- UT-SVC-RUN-003: cancel --- success ---

    @Test
    @DisplayName("UT-SVC-RUN-003: cancel sets status to CANCELLED and finishedAt")
    void cancel_pendingRun_setsStatusCancelled() {
        PipelineRunEntity run = new PipelineRunEntity();
        run.setId("run_cancel001");
        run.setTenantId(TENANT_ID);
        run.setStatus(PipelineRunStatus.PENDING);

        when(runRepository.findByIdAndTenantId("run_cancel001", TENANT_ID))
                .thenReturn(Optional.of(run));
        when(runRepository.save(any(PipelineRunEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        PipelineRunEntity result = runService.cancel(TENANT_ID, "run_cancel001");

        assertThat(result.getStatus()).isEqualTo(PipelineRunStatus.CANCELLED);
        assertThat(result.getFinishedAt()).isNotNull();
        verify(runRepository).save(run);
    }

    @Test
    @DisplayName("UT-SVC-RUN-003b: cancel already succeeded run throws BadRequestException")
    void cancel_succeededRun_throwsBadRequest() {
        PipelineRunEntity run = new PipelineRunEntity();
        run.setId("run_done001");
        run.setTenantId(TENANT_ID);
        run.setStatus(PipelineRunStatus.SUCCEEDED);

        when(runRepository.findByIdAndTenantId("run_done001", TENANT_ID))
                .thenReturn(Optional.of(run));

        assertThatThrownBy(() -> runService.cancel(TENANT_ID, "run_done001"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("SUCCEEDED");
    }
}
