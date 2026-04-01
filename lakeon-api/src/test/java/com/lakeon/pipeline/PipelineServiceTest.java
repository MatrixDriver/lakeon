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
@DisplayName("PipelineService unit tests")
class PipelineServiceTest {

    @Mock private PipelineRepository pipelineRepository;
    @Mock private PipelineVersionRepository pipelineVersionRepository;

    private PipelineService pipelineService;

    private static final String TENANT_ID = "tn_test001";

    @BeforeEach
    void setUp() {
        pipelineService = new PipelineService(pipelineRepository, pipelineVersionRepository);
    }

    // ─── UT-SVC-PIPE-001: create — normal ──────────────────────────

    @Test
    @DisplayName("UT-SVC-PIPE-001: create pipeline + v1")
    void create_normalFlow_createsPipelineAndVersion() {
        when(pipelineRepository.save(any(PipelineEntity.class)))
                .thenAnswer(inv -> {
                    PipelineEntity e = inv.getArgument(0);
                    e.setId("pipe_test001");
                    e.setCreatedAt(Instant.now());
                    e.setUpdatedAt(Instant.now());
                    return e;
                });
        when(pipelineVersionRepository.save(any(PipelineVersionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        PipelineEntity result = pipelineService.create(TENANT_ID, "My Pipeline", "desc",
                "text", null, "steps:\n  - chunk");

        assertThat(result.getId()).isEqualTo("pipe_test001");
        assertThat(result.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(result.getName()).isEqualTo("My Pipeline");
        assertThat(result.getLatestVersion()).isEqualTo(1);

        // Verify version was created
        ArgumentCaptor<PipelineVersionEntity> versionCaptor =
                ArgumentCaptor.forClass(PipelineVersionEntity.class);
        verify(pipelineVersionRepository).save(versionCaptor.capture());
        PipelineVersionEntity v1 = versionCaptor.getValue();
        assertThat(v1.getPipelineId()).isEqualTo("pipe_test001");
        assertThat(v1.getVersion()).isEqualTo(1);
        assertThat(v1.getDagYaml()).isEqualTo("steps:\n  - chunk");
        assertThat(v1.getChangelog()).isEqualTo("Initial version");
    }

    // ─── UT-SVC-PIPE-002: create — blank name ─────────────────────

    @Test
    @DisplayName("UT-SVC-PIPE-002: create with blank name throws BadRequestException")
    void create_blankName_throwsBadRequest() {
        assertThatThrownBy(() ->
                pipelineService.create(TENANT_ID, "", null, "text", null, "steps: []"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("name is required");
    }

    @Test
    @DisplayName("UT-SVC-PIPE-002b: create with null name throws BadRequestException")
    void create_nullName_throwsBadRequest() {
        assertThatThrownBy(() ->
                pipelineService.create(TENANT_ID, null, null, "text", null, "steps: []"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("name is required");
    }

    // ─── UT-SVC-PIPE-003: create — blank dag_yaml ─────────────────

    @Test
    @DisplayName("UT-SVC-PIPE-003: create with blank dag_yaml throws BadRequestException")
    void create_blankDagYaml_throwsBadRequest() {
        assertThatThrownBy(() ->
                pipelineService.create(TENANT_ID, "My Pipeline", null, "text", null, ""))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("dag_yaml is required");
    }

    @Test
    @DisplayName("UT-SVC-PIPE-003b: create with null dag_yaml throws BadRequestException")
    void create_nullDagYaml_throwsBadRequest() {
        assertThatThrownBy(() ->
                pipelineService.create(TENANT_ID, "My Pipeline", null, "text", null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("dag_yaml is required");
    }

    // ─── UT-SVC-PIPE-004: get — not found ─────────────────────────

    @Test
    @DisplayName("UT-SVC-PIPE-004: get non-existent pipeline throws NotFoundException")
    void get_notFound_throwsNotFoundException() {
        when(pipelineRepository.findByIdAndTenantId("pipe_missing", TENANT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> pipelineService.get(TENANT_ID, "pipe_missing"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("pipe_missing");
    }

    @Test
    @DisplayName("UT-SVC-PIPE-004b: get existing pipeline returns entity")
    void get_found_returnsEntity() {
        PipelineEntity entity = new PipelineEntity();
        entity.setId("pipe_found");
        entity.setTenantId(TENANT_ID);

        when(pipelineRepository.findByIdAndTenantId("pipe_found", TENANT_ID))
                .thenReturn(Optional.of(entity));

        PipelineEntity result = pipelineService.get(TENANT_ID, "pipe_found");
        assertThat(result.getId()).isEqualTo("pipe_found");
    }

    // ─── UT-SVC-PIPE-005: createVersion — version auto-increment ──

    @Test
    @DisplayName("UT-SVC-PIPE-005: createVersion increments version number")
    void createVersion_incrementsVersion() {
        PipelineEntity pipeline = new PipelineEntity();
        pipeline.setId("pipe_ver001");
        pipeline.setTenantId(TENANT_ID);
        pipeline.setLatestVersion(2);

        when(pipelineRepository.findByIdAndTenantId("pipe_ver001", TENANT_ID))
                .thenReturn(Optional.of(pipeline));
        when(pipelineRepository.save(any(PipelineEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(pipelineVersionRepository.save(any(PipelineVersionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        PipelineVersionEntity result = pipelineService.createVersion(
                TENANT_ID, "pipe_ver001", "steps:\n  - embed", "Added embedding step");

        assertThat(result.getVersion()).isEqualTo(3);
        assertThat(result.getDagYaml()).isEqualTo("steps:\n  - embed");
        assertThat(result.getChangelog()).isEqualTo("Added embedding step");
        assertThat(pipeline.getLatestVersion()).isEqualTo(3);
    }

    // ─── UT-SVC-PIPE-006: listTemplates ───────────────────────────

    @Test
    @DisplayName("UT-SVC-PIPE-006: listTemplates returns template pipelines")
    void listTemplates_returnsTemplates() {
        PipelineEntity t1 = new PipelineEntity();
        t1.setId("pipe_tpl001");
        t1.setIsTemplate(true);
        PipelineEntity t2 = new PipelineEntity();
        t2.setId("pipe_tpl002");
        t2.setIsTemplate(true);

        when(pipelineRepository.findByIsTemplateTrue()).thenReturn(List.of(t1, t2));

        List<PipelineEntity> result = pipelineService.listTemplates();
        assertThat(result).hasSize(2);
        assertThat(result).extracting(PipelineEntity::getId)
                .containsExactly("pipe_tpl001", "pipe_tpl002");
    }
}
