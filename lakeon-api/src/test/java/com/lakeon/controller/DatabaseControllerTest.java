package com.lakeon.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.model.dto.CreateDatabaseRequest;
import com.lakeon.model.dto.DatabaseResponse;
import com.lakeon.model.dto.UpdateDatabaseRequest;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.service.DatabaseService;
import com.lakeon.service.TenantService;
import com.lakeon.service.exception.ConflictException;
import com.lakeon.service.exception.NotFoundException;

import com.lakeon.config.ApiKeyFilter;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.TenantEntity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DatabaseController.class)
@Import(ApiKeyFilter.class)
@DisplayName("DatabaseController API 集成测试")
class DatabaseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private DatabaseService databaseService;

    @MockBean
    private TenantService tenantService;

    @MockBean
    private LakeonProperties lakeonProperties;

    private static final String API_KEY = "Bearer test-api-key-valid-32chars!!!";

    @BeforeEach
    void setUp() {
        // Stub API key authentication for valid key
        TenantEntity tenant = new TenantEntity();
        tenant.setId("tn_test001");
        tenant.setName("test-tenant");
        tenant.setApiKey("test-api-key-valid-32chars!!!");
        when(tenantService.authenticateByApiKey("test-api-key-valid-32chars!!!"))
                .thenReturn(tenant);
    }

    @Nested
    @DisplayName("创建实例 — POST /api/v1/databases")
    class CreateDatabase {

        @Test
        @DisplayName("IT-API-DB-001: 正常创建 — 返回 201")
        void create_success_returns201() throws Exception {
            // Given
            var response = buildDatabaseResponse("db_abc123", "my-app-db", DatabaseStatus.CREATING);
            when(databaseService.create(any(), any())).thenReturn(response);

            // When / Then
            mockMvc.perform(post("/api/v1/databases")
                            .header("Authorization", API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "name": "my-app-db",
                                      "compute_size": "2cu",
                                      "suspend_timeout": "10m",
                                      "storage_limit_gb": 10
                                    }
                                    """))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.id").value("db_abc123"))
                    .andExpect(jsonPath("$.name").value("my-app-db"))
                    .andExpect(jsonPath("$.status").value("CREATING"))
                    .andExpect(jsonPath("$.connection_uri").isNotEmpty())
                    .andExpect(jsonPath("$.compute_size").value("2cu"))
                    .andExpect(jsonPath("$.branches", hasSize(1)))
                    .andExpect(jsonPath("$.branches[0].name").value("main"))
                    .andExpect(jsonPath("$.branches[0].is_default").value(true))
                    .andExpect(jsonPath("$.created_at").isNotEmpty());
        }

        @Test
        @DisplayName("IT-API-DB-002: 缺少 name — 返回 400")
        void create_missingName_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/databases")
                            .header("Authorization", API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "compute_size": "1cu"
                                    }
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("IT-API-DB-003: 名称重复 — 返回 409")
        void create_duplicateName_returns409() throws Exception {
            when(databaseService.create(any(), any()))
                    .thenThrow(new ConflictException("Database 'dup-db' already exists"));

            mockMvc.perform(post("/api/v1/databases")
                            .header("Authorization", API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "name": "dup-db"
                                    }
                                    """))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("CONFLICT"));
        }

        @Test
        @DisplayName("IT-API-DB-004: 无 Authorization header — 返回 401")
        void create_noAuth_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/databases")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "name": "my-db"
                                    }
                                    """))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        }

        @Test
        @DisplayName("IT-API-DB-005: 无效 API Key — 返回 401")
        void create_invalidApiKey_returns401() throws Exception {
            mockMvc.perform(post("/api/v1/databases")
                            .header("Authorization", "Bearer invalid-key")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "name": "my-db"
                                    }
                                    """))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
        }
    }

    @Nested
    @DisplayName("列出实例 — GET /api/v1/databases")
    class ListDatabases {

        @Test
        @DisplayName("IT-API-DB-006: 空列表 — 返回空数组")
        void list_empty_returns200() throws Exception {
            when(databaseService.list(any())).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/databases")
                            .header("Authorization", API_KEY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("IT-API-DB-007: 多个实例 — 返回列表")
        void list_multiple_returns200() throws Exception {
            var db1 = buildDatabaseResponse("db_001", "app-db-1", DatabaseStatus.RUNNING);
            var db2 = buildDatabaseResponse("db_002", "app-db-2", DatabaseStatus.SUSPENDED);
            when(databaseService.list(any())).thenReturn(List.of(db1, db2));

            mockMvc.perform(get("/api/v1/databases")
                            .header("Authorization", API_KEY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0].name").value("app-db-1"))
                    .andExpect(jsonPath("$[1].name").value("app-db-2"));
        }
    }

    @Nested
    @DisplayName("查看实例 — GET /api/v1/databases/{db_id}")
    class GetDatabase {

        @Test
        @DisplayName("IT-API-DB-008: 存在 — 返回 200")
        void get_found_returns200() throws Exception {
            var response = buildDatabaseResponse("db_get001", "my-db", DatabaseStatus.RUNNING);
            when(databaseService.get(any(), eq("db_get001"))).thenReturn(response);

            mockMvc.perform(get("/api/v1/databases/db_get001")
                            .header("Authorization", API_KEY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("db_get001"))
                    .andExpect(jsonPath("$.name").value("my-db"))
                    .andExpect(jsonPath("$.status").value("RUNNING"));
        }

        @Test
        @DisplayName("IT-API-DB-009: 不存在 — 返回 404")
        void get_notFound_returns404() throws Exception {
            when(databaseService.get(any(), eq("db_nonexist")))
                    .thenThrow(new NotFoundException("Database not found"));

            mockMvc.perform(get("/api/v1/databases/db_nonexist")
                            .header("Authorization", API_KEY))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("更新配置 — PATCH /api/v1/databases/{db_id}")
    class UpdateDatabase {

        @Test
        @DisplayName("IT-API-DB-011: 正常更新 — 返回 200")
        void update_success_returns200() throws Exception {
            var response = buildDatabaseResponse("db_upd001", "my-db", DatabaseStatus.RUNNING);
            when(databaseService.update(any(), eq("db_upd001"), any())).thenReturn(response);

            mockMvc.perform(patch("/api/v1/databases/db_upd001")
                            .header("Authorization", API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "compute_size": "4cu",
                                      "suspend_timeout": "15m"
                                    }
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("db_upd001"));
        }
    }

    @Nested
    @DisplayName("删除实例 — DELETE /api/v1/databases/{db_id}")
    class DeleteDatabase {

        @Test
        @DisplayName("IT-API-DB-012: 正常删除 — 返回 204")
        void delete_success_returns204() throws Exception {
            mockMvc.perform(delete("/api/v1/databases/db_del001")
                            .header("Authorization", API_KEY))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("IT-API-DB-013: 不存在 — 返回 404")
        void delete_notFound_returns404() throws Exception {
            doThrow(new NotFoundException("Database not found"))
                    .when(databaseService).delete(any(), eq("db_nonexist"));

            mockMvc.perform(delete("/api/v1/databases/db_nonexist")
                            .header("Authorization", API_KEY))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
        }
    }

    @Nested
    @DisplayName("休眠/唤醒 — POST /api/v1/databases/{db_id}/suspend|resume")
    class SuspendResume {

        @Test
        @DisplayName("IT-API-DB-014: 休眠 compute — 返回 200")
        void suspend_success_returns200() throws Exception {
            mockMvc.perform(post("/api/v1/databases/db_sus001/suspend")
                            .header("Authorization", API_KEY))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("IT-API-DB-015: 唤醒 compute — 返回 200")
        void resume_success_returns200() throws Exception {
            mockMvc.perform(post("/api/v1/databases/db_res001/resume")
                            .header("Authorization", API_KEY))
                    .andExpect(status().isOk());
        }
    }

    // ========== 辅助方法 ==========

    private DatabaseResponse buildDatabaseResponse(String id, String name, DatabaseStatus status) {
        return DatabaseResponse.builder()
                .id(id)
                .name(name)
                .status(status)
                .connectionUri("postgres://user:pass@proxy.lakeon.example.com/" + name)
                .computeSize("2cu")
                .suspendTimeout("10m")
                .storageLimitGb(10)
                .storageUsedGb(0.0)
                .branches(List.of(
                        DatabaseResponse.BranchSummary.builder()
                                .id("br_main")
                                .name("main")
                                .isDefault(true)
                                .build()
                ))
                .createdAt(Instant.now())
                .build();
    }
}
