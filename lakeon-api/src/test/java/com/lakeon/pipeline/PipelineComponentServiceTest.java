package com.lakeon.pipeline;

import com.lakeon.service.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PipelineComponentService unit tests")
class PipelineComponentServiceTest {

    @Mock private PipelineComponentRepository componentRepository;
    @Mock private PipelineComponentVersionRepository componentVersionRepository;

    private PipelineComponentService componentService;

    private static final String TENANT_ID = "tn_test001";

    @BeforeEach
    void setUp() {
        componentService = new PipelineComponentService(componentRepository, componentVersionRepository);
    }

    // --- UT-SVC-COMP-001: register --- normal ---

    @Test
    @DisplayName("UT-SVC-COMP-001: register component + v1")
    void register_normalFlow_createsComponentAndVersion() {
        when(componentRepository.save(any(PipelineComponentEntity.class)))
                .thenAnswer(inv -> {
                    PipelineComponentEntity e = inv.getArgument(0);
                    e.setId("comp_test001");
                    e.setCreatedAt(Instant.now());
                    e.setUpdatedAt(Instant.now());
                    return e;
                });
        when(componentVersionRepository.save(any(PipelineComponentVersionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        PipelineComponentEntity result = componentService.register(
                TENANT_ID, "chunker", "Chunker", "TRANSFORM", "text",
                "Splits text into chunks", "pipeline.steps.chunker:run",
                null, null, null, null, false, null, "FUNCTION");

        assertThat(result.getId()).isEqualTo("comp_test001");
        assertThat(result.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(result.getName()).isEqualTo("chunker");
        assertThat(result.getDisplayName()).isEqualTo("Chunker");
        assertThat(result.getLatestVersion()).isEqualTo(1);

        // Verify version was created
        ArgumentCaptor<PipelineComponentVersionEntity> versionCaptor =
                ArgumentCaptor.forClass(PipelineComponentVersionEntity.class);
        verify(componentVersionRepository).save(versionCaptor.capture());
        PipelineComponentVersionEntity v1 = versionCaptor.getValue();
        assertThat(v1.getComponentId()).isEqualTo("comp_test001");
        assertThat(v1.getVersion()).isEqualTo(1);
        assertThat(v1.getEntrypoint()).isEqualTo("pipeline.steps.chunker:run");
        assertThat(v1.getStatus()).isEqualTo("PUBLISHED");
    }

    // --- UT-SVC-COMP-002: listAvailable ---

    @Test
    @DisplayName("UT-SVC-COMP-002: listAvailable returns builtin + tenant components")
    void listAvailable_returnsCombined() {
        PipelineComponentEntity builtin = new PipelineComponentEntity();
        builtin.setId("comp_builtin");
        builtin.setName("embed");
        PipelineComponentEntity custom = new PipelineComponentEntity();
        custom.setId("comp_custom");
        custom.setTenantId(TENANT_ID);
        custom.setName("my_step");

        when(componentRepository.findByTenantIdIsNullOrTenantId(TENANT_ID))
                .thenReturn(List.of(builtin, custom));

        List<PipelineComponentEntity> result = componentService.listAvailable(TENANT_ID);
        assertThat(result).hasSize(2);
        assertThat(result).extracting(PipelineComponentEntity::getId)
                .containsExactly("comp_builtin", "comp_custom");
    }

    // --- UT-SVC-COMP-003: register --- empty name throws ---

    @Test
    @DisplayName("UT-SVC-COMP-003: register with empty name throws BadRequestException")
    void register_emptyName_throwsBadRequest() {
        assertThatThrownBy(() ->
                componentService.register(TENANT_ID, "", "Display", "TRANSFORM", "text",
                        null, "entry:run", null, null, null, null, false, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("name is required");
    }
}
