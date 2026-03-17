package com.lakeon.controller;

import com.lakeon.config.ApiKeyFilter;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.dto.SchemaDiffResponse;
import com.lakeon.model.dto.SchemaDiffResponse.*;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.DiffService;
import com.lakeon.service.TenantService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DiffController.class)
@Import(ApiKeyFilter.class)
@DisplayName("DiffController API 集成测试")
class DiffControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DiffService diffService;

    @MockBean
    private TenantService tenantService;

    @MockBean
    private LakeonProperties lakeonProperties;

    private static final String API_KEY = "Bearer test-api-key-valid-32chars!!!";

    @BeforeEach
    void setUp() {
        TenantEntity tenant = new TenantEntity();
        tenant.setId("tn_test001");
        tenant.setName("test-tenant");
        tenant.setApiKey("test-api-key-valid-32chars!!!");
        when(tenantService.authenticateByApiKey("test-api-key-valid-32chars!!!"))
                .thenReturn(tenant);
    }

    @Test
    @DisplayName("IT-API-DIFF-001: schema diff — 正常参数，返回 200")
    void schemaDiff_success_returns200() throws Exception {
        SchemaDiffResponse response = new SchemaDiffResponse(
            new TableDiffs(
                List.of(new TableInfo("orders", "public", List.of(
                    new ColumnInfo("id", "integer", false, null)
                ))),
                List.of(),
                List.of()
            ),
            new IndexDiffs(List.of(), List.of())
        );
        when(diffService.schemaDiff(any(), eq("db_abc123"),
                eq("branch"), eq("br_main"), eq("branch"), eq("br_feat")))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/databases/db_abc123/diff/schema")
                        .header("Authorization", API_KEY)
                        .param("source_type", "branch")
                        .param("source_id", "br_main")
                        .param("target_type", "branch")
                        .param("target_id", "br_feat"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables.added[0].name").value("orders"))
                .andExpect(jsonPath("$.tables.removed").isEmpty())
                .andExpect(jsonPath("$.indexes.added").isEmpty());
    }

    @Test
    @DisplayName("IT-API-DIFF-002: schema diff — 缺少必填参数，返回错误")
    void schemaDiff_missingParam_returnsError() throws Exception {
        mockMvc.perform(get("/api/v1/databases/db_abc123/diff/schema")
                        .header("Authorization", API_KEY)
                        .param("source_type", "branch")
                        .param("source_id", "br_main"))
                // missing target_type and target_id
                .andExpect(status().is5xxServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_ERROR"));
    }
}
