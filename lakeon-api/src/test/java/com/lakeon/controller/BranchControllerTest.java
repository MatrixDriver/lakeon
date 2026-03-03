package com.lakeon.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.model.dto.BranchResponse;
import com.lakeon.config.ApiKeyFilter;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.BranchService;
import com.lakeon.service.TenantService;
import com.lakeon.service.exception.BadRequestException;
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

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BranchController.class)
@Import(ApiKeyFilter.class)
@DisplayName("BranchController API 集成测试")
class BranchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private BranchService branchService;

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
    @DisplayName("IT-API-BR-001: 创建分支 — 正常，返回 201")
    void createBranch_success_returns201() throws Exception {
        var response = BranchResponse.builder()
                .id("br_feat001")
                .name("feature-test")
                .parentBranch("main")
                .status("creating")
                .connectionUri("postgres://user:pass@proxy/my-db?branch=feature-test")
                .createdAt(Instant.now())
                .build();
        when(branchService.create(any(), eq("db_abc123"), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/databases/db_abc123/branches")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "feature-test",
                                  "start_compute": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("br_feat001"))
                .andExpect(jsonPath("$.name").value("feature-test"))
                .andExpect(jsonPath("$.parent_branch").value("main"))
                .andExpect(jsonPath("$.connection_uri").isNotEmpty());
    }

    @Test
    @DisplayName("IT-API-BR-002: 创建分支 — 父实例不存在，返回 404")
    void createBranch_parentNotFound_returns404() throws Exception {
        when(branchService.create(any(), eq("db_nonexist"), any()))
                .thenThrow(new NotFoundException("Database not found"));

        mockMvc.perform(post("/api/v1/databases/db_nonexist/branches")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "feature-test"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("IT-API-BR-003: 列出分支 — 返回 200")
    void listBranches_success_returns200() throws Exception {
        var mainBranch = BranchResponse.builder()
                .id("br_main").name("main").parentBranch(null)
                .status("active").isDefault(true).createdAt(Instant.now()).build();
        var featureBranch = BranchResponse.builder()
                .id("br_feat").name("feature-test").parentBranch("main")
                .status("active").isDefault(false).createdAt(Instant.now()).build();
        when(branchService.list(any(), eq("db_abc123")))
                .thenReturn(List.of(mainBranch, featureBranch));

        mockMvc.perform(get("/api/v1/databases/db_abc123/branches")
                        .header("Authorization", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("main"))
                .andExpect(jsonPath("$[1].name").value("feature-test"));
    }

    @Test
    @DisplayName("IT-API-BR-004: 删除分支 — 正常，返回 204")
    void deleteBranch_success_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/databases/db_abc123/branches/br_feat001")
                        .header("Authorization", API_KEY))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("IT-API-BR-005: 删除默认分支 — 返回 400")
    void deleteBranch_default_returns400() throws Exception {
        doThrow(new BadRequestException("Cannot delete default branch"))
                .when(branchService).delete(any(), eq("db_abc123"), eq("br_main"));

        mockMvc.perform(delete("/api/v1/databases/db_abc123/branches/br_main")
                        .header("Authorization", API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }
}
