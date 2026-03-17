package com.lakeon.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.ApiKeyFilter;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.dto.VersionResponse;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.TenantService;
import com.lakeon.service.VersionService;
import com.lakeon.service.exception.ConflictException;
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

@WebMvcTest(VersionController.class)
@Import(ApiKeyFilter.class)
@DisplayName("VersionController API 集成测试")
class VersionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private VersionService versionService;

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
    @DisplayName("IT-API-VER-001: 创建版本 — 正常，返回 201")
    void createVersion_success_returns201() throws Exception {
        var response = VersionResponse.builder()
                .id("ver_abc001")
                .branchId("br_main001")
                .name("v1.0.0")
                .description("Initial release")
                .lsn("0/5000")
                .snapshotTimelineId("snapshot-timeline-id")
                .createdBy("api")
                .createdAt(Instant.now())
                .build();
        when(versionService.create(any(), eq("db_abc123"), eq("br_main001"), any()))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/databases/db_abc123/branches/br_main001/versions")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "v1.0.0",
                                  "description": "Initial release"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("ver_abc001"))
                .andExpect(jsonPath("$.name").value("v1.0.0"))
                .andExpect(jsonPath("$.branch_id").value("br_main001"))
                .andExpect(jsonPath("$.lsn").value("0/5000"));
    }

    @Test
    @DisplayName("IT-API-VER-002: 列出版本 — 返回 200")
    void listVersions_success_returns200() throws Exception {
        var v1 = VersionResponse.builder()
                .id("ver_abc001").branchId("br_main001").name("v1.0.0")
                .lsn("0/3000").createdAt(Instant.now()).build();
        var v2 = VersionResponse.builder()
                .id("ver_abc002").branchId("br_main001").name("v2.0.0")
                .lsn("0/5000").createdAt(Instant.now()).build();
        when(versionService.list(any(), eq("db_abc123"), eq("br_main001")))
                .thenReturn(List.of(v1, v2));

        mockMvc.perform(get("/api/v1/databases/db_abc123/branches/br_main001/versions")
                        .header("Authorization", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("v1.0.0"))
                .andExpect(jsonPath("$[1].name").value("v2.0.0"));
    }

    @Test
    @DisplayName("IT-API-VER-003: 获取版本 — 返回 200")
    void getVersion_success_returns200() throws Exception {
        var response = VersionResponse.builder()
                .id("ver_abc001").branchId("br_main001").name("v1.0.0")
                .lsn("0/3000").createdBy("api").createdAt(Instant.now()).build();
        when(versionService.get(any(), eq("db_abc123"), eq("br_main001"), eq("ver_abc001")))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/databases/db_abc123/branches/br_main001/versions/ver_abc001")
                        .header("Authorization", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("ver_abc001"))
                .andExpect(jsonPath("$.name").value("v1.0.0"));
    }

    @Test
    @DisplayName("IT-API-VER-004: 删除版本 — 正常，返回 204")
    void deleteVersion_success_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/databases/db_abc123/branches/br_main001/versions/ver_abc001")
                        .header("Authorization", API_KEY))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("IT-API-VER-005: 删除不存在的版本 — 返回 404")
    void deleteVersion_notFound_returns404() throws Exception {
        doThrow(new NotFoundException("Version not found: ver_nonexist"))
                .when(versionService).delete(any(), eq("db_abc123"), eq("br_main001"), eq("ver_nonexist"));

        mockMvc.perform(delete("/api/v1/databases/db_abc123/branches/br_main001/versions/ver_nonexist")
                        .header("Authorization", API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("IT-API-VER-006: Squash 版本 — 返回 200 含列表")
    void squashVersions_success_returns200() throws Exception {
        var v1 = VersionResponse.builder()
                .id("ver_abc001").branchId("br_main001").name("v1.0.0")
                .lsn("0/3000").createdAt(Instant.now()).build();
        var v3 = VersionResponse.builder()
                .id("ver_abc003").branchId("br_main001").name("v3.0.0")
                .lsn("0/7000").createdAt(Instant.now()).build();
        when(versionService.squash(any(), eq("db_abc123"), eq("br_main001"), any()))
                .thenReturn(List.of(v1, v3));

        mockMvc.perform(post("/api/v1/databases/db_abc123/branches/br_main001/versions/squash")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "from_version_id": "ver_abc001",
                                  "to_version_id": "ver_abc003"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("v1.0.0"))
                .andExpect(jsonPath("$[1].name").value("v3.0.0"));
    }

    @Test
    @DisplayName("IT-API-VER-007: 创建重复名称版本 — 返回 409")
    void createVersion_duplicateName_returns409() throws Exception {
        when(versionService.create(any(), eq("db_abc123"), eq("br_main001"), any()))
                .thenThrow(new ConflictException("Version 'v1.0.0' already exists"));

        mockMvc.perform(post("/api/v1/databases/db_abc123/branches/br_main001/versions")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "v1.0.0"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }
}
