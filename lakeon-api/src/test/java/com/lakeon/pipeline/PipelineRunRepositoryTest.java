package com.lakeon.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@DisplayName("PipelineRunRepository 数据访问层测试")
class PipelineRunRepositoryTest {

    @Autowired
    private PipelineRepository pipelineRepository;

    @Autowired
    private PipelineRunRepository runRepository;

    @Autowired
    private PipelineStepRunRepository stepRunRepository;

    @Test
    @DisplayName("UT-RUN-001: save — 正常保存 pipeline run, ID starts with run_")
    void save_generatesId() {
        // Given
        PipelineEntity pipeline = savePipeline("tn_user01", "my-pipeline");
        PipelineRunEntity run = createRun(pipeline.getId(), 1, "tn_user01");

        // When
        PipelineRunEntity saved = runRepository.save(run);

        // Then
        assertThat(saved.getId()).isNotNull().startsWith("run_");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(PipelineRunStatus.PENDING);
    }

    @Test
    @DisplayName("UT-RUN-002: findByPipelineId — 查询 pipeline 的运行记录")
    void findByPipelineId_returnsRuns() {
        // Given
        PipelineEntity pipeline = savePipeline("tn_user01", "etl-pipeline");
        runRepository.save(createRun(pipeline.getId(), 1, "tn_user01"));
        runRepository.save(createRun(pipeline.getId(), 1, "tn_user01"));

        PipelineEntity other = savePipeline("tn_user01", "other-pipeline");
        runRepository.save(createRun(other.getId(), 1, "tn_user01"));

        // When
        List<PipelineRunEntity> runs = runRepository
                .findByPipelineIdOrderByCreatedAtDesc(pipeline.getId());

        // Then
        assertThat(runs).hasSize(2);
        assertThat(runs).allSatisfy(r ->
                assertThat(r.getPipelineId()).isEqualTo(pipeline.getId()));
    }

    @Test
    @DisplayName("UT-RUN-003: findByStatus — 按状态查询运行记录")
    void findByStatus_filtersCorrectly() {
        // Given
        PipelineEntity pipeline = savePipeline("tn_user01", "pipeline-x");
        PipelineRunEntity pending = createRun(pipeline.getId(), 1, "tn_user01");
        PipelineRunEntity running = createRun(pipeline.getId(), 1, "tn_user01");
        running.setStatus(PipelineRunStatus.RUNNING);
        runRepository.save(pending);
        runRepository.save(running);

        // When
        List<PipelineRunEntity> pendingRuns = runRepository.findByStatus(PipelineRunStatus.PENDING);
        List<PipelineRunEntity> runningRuns = runRepository.findByStatus(PipelineRunStatus.RUNNING);

        // Then
        assertThat(pendingRuns).hasSize(1);
        assertThat(runningRuns).hasSize(1);
    }

    @Test
    @DisplayName("UT-RUN-004: save step run, findByRunId returns ordered steps")
    void saveStepRun_findByRunId() {
        // Given
        PipelineEntity pipeline = savePipeline("tn_user01", "pipeline-y");
        PipelineRunEntity run = runRepository.save(
                createRun(pipeline.getId(), 1, "tn_user01"));

        PipelineStepRunEntity step1 = createStepRun(run.getId(), "split", "comp_abc");
        PipelineStepRunEntity step2 = createStepRun(run.getId(), "embed", "comp_def");
        step2.setStatus(PipelineStepRunStatus.RUNNING);
        stepRunRepository.save(step1);
        stepRunRepository.save(step2);

        // When
        List<PipelineStepRunEntity> steps = stepRunRepository
                .findByRunIdOrderByCreatedAtAsc(run.getId());
        List<PipelineStepRunEntity> runningSteps = stepRunRepository
                .findByRunIdAndStatus(run.getId(), PipelineStepRunStatus.RUNNING);

        // Then
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).getId()).startsWith("sr_");
        assertThat(steps.get(0).getStepId()).isEqualTo("split");

        assertThat(runningSteps).hasSize(1);
        assertThat(runningSteps.get(0).getStepId()).isEqualTo("embed");
    }

    private PipelineEntity savePipeline(String tenantId, String name) {
        PipelineEntity entity = new PipelineEntity();
        entity.setTenantId(tenantId);
        entity.setName(name);
        entity.setDataType("TEXT");
        entity.setIsTemplate(false);
        return pipelineRepository.save(entity);
    }

    private PipelineRunEntity createRun(String pipelineId, int version, String tenantId) {
        PipelineRunEntity entity = new PipelineRunEntity();
        entity.setPipelineId(pipelineId);
        entity.setPipelineVersion(version);
        entity.setTenantId(tenantId);
        return entity;
    }

    private PipelineStepRunEntity createStepRun(String runId, String stepId, String componentId) {
        PipelineStepRunEntity entity = new PipelineStepRunEntity();
        entity.setRunId(runId);
        entity.setStepId(stepId);
        entity.setComponentId(componentId);
        entity.setComponentVersion(1);
        return entity;
    }
}
