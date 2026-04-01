package com.lakeon.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.ApiKeyFilter;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.TenantService;
import com.lakeon.repository.TenantRepository;
import com.lakeon.service.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PipelineController.class)
@Import(ApiKeyFilter.class)
@DisplayName("PipelineController API tests")
class PipelineControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PipelineService pipelineService;

    @MockBean
    private TenantService tenantService;

    @MockBean
    private LakeonProperties lakeonProperties;

    @MockBean
    private TenantRepository tenantRepository;

    private static final String API_KEY = "Bearer test-api-key-valid-32chars!!!";
    private static final String TENANT_ID = "tn_test001";

    @BeforeEach
    void setUp() {
        TenantEntity tenant = new TenantEntity();
        tenant.setId(TENANT_ID);
        tenant.setName("test-tenant");
        tenant.setApiKey("test-api-key-valid-32chars!!!");
        when(tenantService.authenticateByApiKey("test-api-key-valid-32chars!!!"))
                .thenReturn(tenant);
    }

    // ─── IT-API-PIPE-001: POST /api/v1/pipelines — 201 ────────────

    @Test
    @DisplayName("IT-API-PIPE-001: POST /api/v1/pipelines returns 201")
    void create_success_returns201() throws Exception {
        PipelineEntity pipeline = buildPipeline("pipe_abc123", "My Pipeline");
        when(pipelineService.create(eq(TENANT_ID), eq("My Pipeline"), any(), any(), any(), any()))
                .thenReturn(pipeline);

        mockMvc.perform(post("/api/v1/pipelines")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "My Pipeline",
                                  "description": "Test pipeline",
                                  "data_type": "text",
                                  "dag_yaml": "steps:\\n  - chunk"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("pipe_abc123"))
                .andExpect(jsonPath("$.name").value("My Pipeline"))
                .andExpect(jsonPath("$.latest_version").value(1))
                .andExpect(jsonPath("$.created_at").isNotEmpty());
    }

    // ─── IT-API-PIPE-002: GET /api/v1/pipelines — list ─────────────

    @Test
    @DisplayName("IT-API-PIPE-002: GET /api/v1/pipelines returns list")
    void list_returns200() throws Exception {
        PipelineEntity p1 = buildPipeline("pipe_001", "Pipeline A");
        PipelineEntity p2 = buildPipeline("pipe_002", "Pipeline B");
        when(pipelineService.list(TENANT_ID)).thenReturn(List.of(p1, p2));

        mockMvc.perform(get("/api/v1/pipelines")
                        .header("Authorization", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value("pipe_001"))
                .andExpect(jsonPath("$[1].id").value("pipe_002"));
    }

    // ─── IT-API-PIPE-003: GET /api/v1/pipelines/{id} — 404 ────────

    @Test
    @DisplayName("IT-API-PIPE-003: GET /api/v1/pipelines/{id} returns 404 when not found")
    void get_notFound_returns404() throws Exception {
        when(pipelineService.get(eq(TENANT_ID), eq("pipe_nonexist")))
                .thenThrow(new NotFoundException("Pipeline not found: pipe_nonexist"));

        mockMvc.perform(get("/api/v1/pipelines/pipe_nonexist")
                        .header("Authorization", API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    // ─── Helper ────────────────────────────────────────────────────

    private PipelineEntity buildPipeline(String id, String name) {
        PipelineEntity p = new PipelineEntity();
        p.setId(id);
        p.setTenantId(TENANT_ID);
        p.setName(name);
        p.setDataType("text");
        p.setIsTemplate(false);
        p.setLatestVersion(1);
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return p;
    }
}
