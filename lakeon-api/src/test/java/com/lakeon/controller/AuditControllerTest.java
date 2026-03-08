package com.lakeon.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.ApiKeyFilter;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.dto.AuditConfigResponse;
import com.lakeon.model.dto.AuditLogResponse;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.AuditService;
import com.lakeon.service.TenantService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuditController.class)
@Import(ApiKeyFilter.class)
@DisplayName("AuditController API 集成测试")
class AuditControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuditService auditService;

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
    @DisplayName("IT-API-AU-001: 获取审计配置 — 返回 200")
    void getConfig_success() throws Exception {
        var response = new AuditConfigResponse(
                "ak_test001", "db_abc123", "tn_test001",
                true, true, false, false, 30,
                Instant.now(), Instant.now());
        when(auditService.getConfig(any(), eq("db_abc123"))).thenReturn(response);

        mockMvc.perform(get("/api/v1/databases/db_abc123/audit/config")
                        .header("Authorization", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("ak_test001"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.log_ddl").value(true))
                .andExpect(jsonPath("$.retention_days").value(30));
    }

    @Test
    @DisplayName("IT-API-AU-002: 获取审计配置 — 数据库不存在，返回 404")
    void getConfig_dbNotFound() throws Exception {
        when(auditService.getConfig(any(), eq("db_nonexist")))
                .thenThrow(new NotFoundException("Database not found"));

        mockMvc.perform(get("/api/v1/databases/db_nonexist/audit/config")
                        .header("Authorization", API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("IT-API-AU-003: 更新审计配置 — 返回 200")
    void updateConfig_success() throws Exception {
        var response = new AuditConfigResponse(
                "ak_test001", "db_abc123", "tn_test001",
                true, true, true, false, 60,
                Instant.now(), Instant.now());
        when(auditService.updateConfig(any(), eq("db_abc123"), any())).thenReturn(response);

        mockMvc.perform(put("/api/v1/databases/db_abc123/audit/config")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled": true, "log_dml": true, "retention_days": 60}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.log_dml").value(true))
                .andExpect(jsonPath("$.retention_days").value(60));
    }

    @Test
    @DisplayName("IT-API-AU-004: 查询审计日志 — 返回 200")
    void getLogs_success() throws Exception {
        var log1 = new AuditLogResponse(
                "al_001", "db_abc123", "tn_test001",
                Instant.now(), "cloud_admin", "CREATE TABLE users",
                "DDL", "users", null, 50L);
        Map<String, Object> responseMap = new LinkedHashMap<>();
        responseMap.put("data", List.of(log1));
        responseMap.put("total", 1L);
        responseMap.put("page", 0);
        responseMap.put("total_pages", 1);
        when(auditService.getLogs(any(), eq("db_abc123"), any(), eq(0), eq(20)))
                .thenReturn(responseMap);

        mockMvc.perform(get("/api/v1/databases/db_abc123/audit/logs")
                        .header("Authorization", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.data[0].statement_type").value("DDL"));
    }

    @Test
    @DisplayName("IT-API-AU-005: 按类型过滤日志 — 返回 200")
    void getLogs_withTypeFilter() throws Exception {
        Map<String, Object> responseMap = new LinkedHashMap<>();
        responseMap.put("data", List.of());
        responseMap.put("total", 0L);
        responseMap.put("page", 0);
        responseMap.put("total_pages", 0);
        when(auditService.getLogs(any(), eq("db_abc123"), eq("DDL"), eq(0), eq(20)))
                .thenReturn(responseMap);

        mockMvc.perform(get("/api/v1/databases/db_abc123/audit/logs")
                        .header("Authorization", API_KEY)
                        .param("type", "DDL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    @DisplayName("IT-API-AU-006: 无认证 — 返回 401")
    void getLogs_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/databases/db_abc123/audit/logs"))
                .andExpect(status().isUnauthorized());
    }
}
