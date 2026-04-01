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
@DisplayName("PipelineComponentRepository 数据访问层测试")
class PipelineComponentRepositoryTest {

    @Autowired
    private PipelineComponentRepository componentRepository;

    @Autowired
    private PipelineComponentVersionRepository versionRepository;

    @Test
    @DisplayName("UT-COMP-001: save — 正常保存组件, ID starts with comp_")
    void save_generatesId() {
        // Given
        PipelineComponentEntity entity = createComponent(null, "text-splitter", "文本分段");

        // When
        PipelineComponentEntity saved = componentRepository.save(entity);

        // Then
        assertThat(saved.getId()).isNotNull().startsWith("comp_");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getName()).isEqualTo("text-splitter");
        assertThat(saved.getDisplayName()).isEqualTo("文本分段");
    }

    @Test
    @DisplayName("UT-COMP-002: findByTenantIdIsNull — 查询平台内置组件")
    void findByTenantIdIsNull_returnsBuiltinOnly() {
        // Given
        componentRepository.save(createComponent(null, "builtin-a", "内置A"));
        componentRepository.save(createComponent(null, "builtin-b", "内置B"));
        componentRepository.save(createComponent("tn_user01", "custom-c", "自定义C"));

        // When
        List<PipelineComponentEntity> builtins = componentRepository.findByTenantIdIsNull();

        // Then
        assertThat(builtins).hasSize(2);
        assertThat(builtins).allSatisfy(c -> assertThat(c.getTenantId()).isNull());
    }

    @Test
    @DisplayName("UT-COMP-003: findByTenantIdIsNullOrTenantId — 查询租户可用组件(内置+本租户)")
    void findByTenantIdIsNullOrTenantId_returnsBuiltinAndTenant() {
        // Given
        componentRepository.save(createComponent(null, "builtin-x", "内置X"));
        componentRepository.save(createComponent("tn_user01", "custom-y", "自定义Y"));
        componentRepository.save(createComponent("tn_user02", "custom-z", "自定义Z"));

        // When
        List<PipelineComponentEntity> available = componentRepository
                .findByTenantIdIsNullOrTenantId("tn_user01");

        // Then
        assertThat(available).hasSize(2);
        assertThat(available).extracting(PipelineComponentEntity::getName)
                .containsExactlyInAnyOrder("builtin-x", "custom-y");
    }

    @Test
    @DisplayName("UT-COMP-004: save component version, verify findByComponentIdAndVersion works")
    void saveVersion_findByComponentIdAndVersion() {
        // Given
        PipelineComponentEntity component = componentRepository.save(
                createComponent(null, "embedding", "向量化"));
        String compId = component.getId();

        PipelineComponentVersionEntity v1 = createVersion(compId, 1, "com.lakeon.comp.Embedding.run");
        PipelineComponentVersionEntity v2 = createVersion(compId, 2, "com.lakeon.comp.EmbeddingV2.run");
        versionRepository.save(v1);
        versionRepository.save(v2);

        // When
        var foundV1 = versionRepository.findByComponentIdAndVersion(compId, 1);
        var foundV2 = versionRepository.findByComponentIdAndVersion(compId, 2);
        var notFound = versionRepository.findByComponentIdAndVersion(compId, 99);
        List<PipelineComponentVersionEntity> all = versionRepository
                .findByComponentIdOrderByVersionDesc(compId);

        // Then
        assertThat(foundV1).isPresent();
        assertThat(foundV1.get().getId()).startsWith("compv_");
        assertThat(foundV1.get().getEntrypoint()).isEqualTo("com.lakeon.comp.Embedding.run");

        assertThat(foundV2).isPresent();
        assertThat(foundV2.get().getVersion()).isEqualTo(2);

        assertThat(notFound).isEmpty();

        assertThat(all).hasSize(2);
        assertThat(all.get(0).getVersion()).isEqualTo(2);
        assertThat(all.get(1).getVersion()).isEqualTo(1);
    }

    private PipelineComponentEntity createComponent(String tenantId, String name, String displayName) {
        PipelineComponentEntity entity = new PipelineComponentEntity();
        entity.setTenantId(tenantId);
        entity.setName(name);
        entity.setDisplayName(displayName);
        entity.setCategory("TRANSFORM");
        entity.setDataType("TEXT");
        return entity;
    }

    private PipelineComponentVersionEntity createVersion(String componentId, int version, String entrypoint) {
        PipelineComponentVersionEntity entity = new PipelineComponentVersionEntity();
        entity.setComponentId(componentId);
        entity.setVersion(version);
        entity.setEntrypoint(entrypoint);
        entity.setStatus("PUBLISHED");
        return entity;
    }
}
