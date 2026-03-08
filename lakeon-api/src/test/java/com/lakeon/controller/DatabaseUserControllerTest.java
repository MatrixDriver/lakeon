package com.lakeon.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.ApiKeyFilter;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.dto.*;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.enums.DatabaseRole;
import com.lakeon.service.DatabaseUserService;
import com.lakeon.service.TenantService;
import com.lakeon.service.exception.BadRequestException;
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
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DatabaseUserController.class)
@Import(ApiKeyFilter.class)
@DisplayName("DatabaseUserController API 集成测试")
class DatabaseUserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private DatabaseUserService databaseUserService;

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
    @DisplayName("IT-API-DU-001: 创建用户 — 正常，返回 201 含密码")
    void createUser_success_returns201() throws Exception {
        Instant now = Instant.now();
        var response = new DatabaseUserCreatedResponse(
                "du_test001", "db_abc123", "reader1", DatabaseRole.READER,
                false, now, now, "RandomPass12345a");
        when(databaseUserService.createUser(any(), eq("db_abc123"), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/databases/db_abc123/users")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "reader1", "role": "READER"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("du_test001"))
                .andExpect(jsonPath("$.username").value("reader1"))
                .andExpect(jsonPath("$.role").value("READER"))
                .andExpect(jsonPath("$.password").value("RandomPass12345a"))
                .andExpect(jsonPath("$.is_owner").value(false));
    }

    @Test
    @DisplayName("IT-API-DU-002: 创建用户 — 用户名重复，返回 409")
    void createUser_conflict_returns409() throws Exception {
        when(databaseUserService.createUser(any(), eq("db_abc123"), any()))
                .thenThrow(new ConflictException("User 'reader1' already exists"));

        mockMvc.perform(post("/api/v1/databases/db_abc123/users")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "reader1", "role": "READER"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"));
    }

    @Test
    @DisplayName("IT-API-DU-003: 列出用户 — 返回 200")
    void listUsers_success_returns200() throws Exception {
        Instant now = Instant.now();
        var u1 = new DatabaseUserResponse("du_001", "db_abc123", "cloud_admin", DatabaseRole.ADMIN, true, now, now);
        var u2 = new DatabaseUserResponse("du_002", "db_abc123", "reader1", DatabaseRole.READER, false, now, now);
        when(databaseUserService.listUsers(any(), eq("db_abc123")))
                .thenReturn(List.of(u1, u2));

        mockMvc.perform(get("/api/v1/databases/db_abc123/users")
                        .header("Authorization", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].username").value("cloud_admin"))
                .andExpect(jsonPath("$[0].is_owner").value(true))
                .andExpect(jsonPath("$[1].username").value("reader1"));
    }

    @Test
    @DisplayName("IT-API-DU-004: 修改角色 — 返回 200")
    void updateRole_success_returns200() throws Exception {
        Instant now = Instant.now();
        var response = new DatabaseUserResponse("du_001", "db_abc123", "reader1", DatabaseRole.WRITER, false, now, now);
        when(databaseUserService.updateUserRole(any(), eq("db_abc123"), eq("du_001"), any()))
                .thenReturn(response);

        mockMvc.perform(put("/api/v1/databases/db_abc123/users/du_001/role")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role": "WRITER"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("WRITER"));
    }

    @Test
    @DisplayName("IT-API-DU-005: 修改 owner 角色 — 返回 400")
    void updateRole_owner_returns400() throws Exception {
        when(databaseUserService.updateUserRole(any(), eq("db_abc123"), eq("du_owner"), any()))
                .thenThrow(new BadRequestException("Cannot modify the owner user's role"));

        mockMvc.perform(put("/api/v1/databases/db_abc123/users/du_owner/role")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role": "READER"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("IT-API-DU-006: 删除用户 — 返回 204")
    void deleteUser_success_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/databases/db_abc123/users/du_001")
                        .header("Authorization", API_KEY))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("IT-API-DU-007: 删除 owner — 返回 400")
    void deleteUser_owner_returns400() throws Exception {
        doThrow(new BadRequestException("Cannot delete the owner user"))
                .when(databaseUserService).deleteUser(any(), eq("db_abc123"), eq("du_owner"));

        mockMvc.perform(delete("/api/v1/databases/db_abc123/users/du_owner")
                        .header("Authorization", API_KEY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("IT-API-DU-008: 重置密码 — 返回 200 含新密码")
    void resetPassword_success_returns200() throws Exception {
        Instant now = Instant.now();
        var response = new DatabaseUserCreatedResponse(
                "du_001", "db_abc123", "reader1", DatabaseRole.READER,
                false, now, now, "NewPassword123ab");
        when(databaseUserService.resetPassword(any(), eq("db_abc123"), eq("du_001")))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/databases/db_abc123/users/du_001/reset-password")
                        .header("Authorization", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.password").value("NewPassword123ab"));
    }

    @Test
    @DisplayName("IT-API-DU-009: 用户不存在 — 返回 404")
    void getUser_notFound_returns404() throws Exception {
        when(databaseUserService.updateUserRole(any(), eq("db_abc123"), eq("du_nonexist"), any()))
                .thenThrow(new NotFoundException("User not found"));

        mockMvc.perform(put("/api/v1/databases/db_abc123/users/du_nonexist/role")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"role": "WRITER"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }
}
