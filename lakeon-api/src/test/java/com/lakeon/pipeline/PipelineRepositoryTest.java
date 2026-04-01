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
@DisplayName("PipelineRepository 数据访问层测试")
class PipelineRepositoryTest {

    @Autowired
    private PipelineRepository pipelineRepository;

    @Autowired
    private PipelineVersionRepository versionRepository;

    @Test
    @DisplayName("UT-PIPE-001: save — 正常保存 pipeline, ID starts with pipe_")
    void save_generatesId() {
        // Given
        PipelineEntity entity = createPipeline("tn_user01", "text-etl", false);

        // When
        PipelineEntity saved = pipelineRepository.save(entity);

        // Then
        assertThat(saved.getId()).isNotNull().startsWith("pipe_");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getName()).isEqualTo("text-etl");
    }

    @Test
    @DisplayName("UT-PIPE-002: findByTenantId — 查询租户 pipeline (排除模板)")
    void findByTenantId_excludesTemplates() {
        // Given
        pipelineRepository.save(createPipeline("tn_user01", "pipeline-a", false));
        pipelineRepository.save(createPipeline("tn_user01", "pipeline-b", false));
        pipelineRepository.save(createPipeline("tn_user01", "template-c", true));
        pipelineRepository.save(createPipeline("tn_user02", "pipeline-d", false));

        // When
        List<PipelineEntity> result = pipelineRepository
                .findByTenantIdAndIsTemplateFalseOrderByCreatedAtDesc("tn_user01");

        // Then
        assertThat(result).hasSize(2);
        assertThat(result).allSatisfy(p -> {
            assertThat(p.getTenantId()).isEqualTo("tn_user01");
            assertThat(p.getIsTemplate()).isFalse();
        });
    }

    @Test
    @DisplayName("UT-PIPE-003: findByIsTemplateTrue — 查询模板列表")
    void findByIsTemplateTrue_returnsTemplatesOnly() {
        // Given
        pipelineRepository.save(createPipeline("tn_sys", "tpl-text-etl", true));
        pipelineRepository.save(createPipeline("tn_sys", "tpl-image-etl", true));
        pipelineRepository.save(createPipeline("tn_user01", "my-pipeline", false));

        // When
        List<PipelineEntity> templates = pipelineRepository.findByIsTemplateTrue();

        // Then
        assertThat(templates).hasSize(2);
        assertThat(templates).allSatisfy(p -> assertThat(p.getIsTemplate()).isTrue());
    }

    @Test
    @DisplayName("UT-PIPE-004: save pipeline version, findByPipelineIdAndVersion")
    void saveVersion_findByPipelineIdAndVersion() {
        // Given
        PipelineEntity pipeline = pipelineRepository.save(
                createPipeline("tn_user01", "my-pipeline", false));
        String pipelineId = pipeline.getId();

        PipelineVersionEntity v1 = createVersion(pipelineId, 1, "steps:\n  - id: split\n    component: text-splitter");
        PipelineVersionEntity v2 = createVersion(pipelineId, 2, "steps:\n  - id: split\n  - id: embed");
        versionRepository.save(v1);
        versionRepository.save(v2);

        // When
        var foundV1 = versionRepository.findByPipelineIdAndVersion(pipelineId, 1);
        var foundV2 = versionRepository.findByPipelineIdAndVersion(pipelineId, 2);
        var notFound = versionRepository.findByPipelineIdAndVersion(pipelineId, 99);
        List<PipelineVersionEntity> all = versionRepository
                .findByPipelineIdOrderByVersionDesc(pipelineId);

        // Then
        assertThat(foundV1).isPresent();
        assertThat(foundV1.get().getId()).startsWith("pipev_");
        assertThat(foundV1.get().getDagYaml()).contains("text-splitter");

        assertThat(foundV2).isPresent();
        assertThat(foundV2.get().getVersion()).isEqualTo(2);

        assertThat(notFound).isEmpty();

        assertThat(all).hasSize(2);
        assertThat(all.get(0).getVersion()).isEqualTo(2);
        assertThat(all.get(1).getVersion()).isEqualTo(1);
    }

    private PipelineEntity createPipeline(String tenantId, String name, boolean isTemplate) {
        PipelineEntity entity = new PipelineEntity();
        entity.setTenantId(tenantId);
        entity.setName(name);
        entity.setDataType("TEXT");
        entity.setIsTemplate(isTemplate);
        return entity;
    }

    private PipelineVersionEntity createVersion(String pipelineId, int version, String dagYaml) {
        PipelineVersionEntity entity = new PipelineVersionEntity();
        entity.setPipelineId(pipelineId);
        entity.setVersion(version);
        entity.setDagYaml(dagYaml);
        entity.setStatus("PUBLISHED");
        return entity;
    }
}
