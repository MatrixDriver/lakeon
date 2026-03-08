package com.lakeon.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.ApiKeyFilter;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.dto.BackupResponse;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.enums.BackupStatus;
import com.lakeon.model.enums.BackupType;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.service.BackupService;
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
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BackupController.class)
@Import(ApiKeyFilter.class)
@DisplayName("BackupController API 集成测试")
class BackupControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private BackupService backupService;

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
    @DisplayName("IT-API-BK-001: 创建备份 — 正常，返回 201")
    void createBackup_success_returns201() throws Exception {
        var response = new BackupResponse(
                "bk_test001", "db_abc123", "tn_test001", "my-backup",
                BackupStatus.COMPLETED, BackupType.MANUAL,
                "neon-t", "neon-tl-bk", "neon-t", "neon-tl-main",
                "0/1A2B3C0", 1024000L, Instant.now(), Instant.now(), null);
        when(backupService.createBackup(any(), eq("db_abc123"), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/databases/db_abc123/backups")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "my-backup"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("bk_test001"))
                .andExpect(jsonPath("$.name").value("my-backup"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.lsn").value("0/1A2B3C0"));
    }

    @Test
    @DisplayName("IT-API-BK-002: 列出备份 — 返回 200")
    void listBackups_success_returns200() throws Exception {
        var bk1 = new BackupResponse(
                "bk_1", "db_abc123", "tn_test001", "backup-1",
                BackupStatus.COMPLETED, BackupType.MANUAL,
                null, null, null, null, "0/1000", 500L,
                Instant.now(), Instant.now(), null);
        var bk2 = new BackupResponse(
                "bk_2", "db_abc123", "tn_test001", "backup-2",
                BackupStatus.COMPLETED, BackupType.SCHEDULED,
                null, null, null, null, "0/2000", 1000L,
                Instant.now(), Instant.now(), null);
        when(backupService.listBackups(any(), eq("db_abc123")))
                .thenReturn(List.of(bk1, bk2));

        mockMvc.perform(get("/api/v1/databases/db_abc123/backups")
                        .header("Authorization", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].name").value("backup-1"))
                .andExpect(jsonPath("$[1].name").value("backup-2"));
    }

    @Test
    @DisplayName("IT-API-BK-003: 获取备份 — 返回 200")
    void getBackup_success_returns200() throws Exception {
        var response = new BackupResponse(
                "bk_get001", "db_abc123", "tn_test001", "my-backup",
                BackupStatus.COMPLETED, BackupType.MANUAL,
                null, null, null, null, "0/3000", 2048L,
                Instant.now(), Instant.now(), null);
        when(backupService.getBackup(any(), eq("db_abc123"), eq("bk_get001")))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/databases/db_abc123/backups/bk_get001")
                        .header("Authorization", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("bk_get001"))
                .andExpect(jsonPath("$.name").value("my-backup"));
    }

    @Test
    @DisplayName("IT-API-BK-004: 获取不存在的备份 — 返回 404")
    void getBackup_notFound_returns404() throws Exception {
        when(backupService.getBackup(any(), eq("db_abc123"), eq("bk_nonexist")))
                .thenThrow(new NotFoundException("Backup not found"));

        mockMvc.perform(get("/api/v1/databases/db_abc123/backups/bk_nonexist")
                        .header("Authorization", API_KEY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    @DisplayName("IT-API-BK-005: 删除备份 — 返回 204")
    void deleteBackup_success_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/databases/db_abc123/backups/bk_del001")
                        .header("Authorization", API_KEY))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("IT-API-BK-006: 从备份恢复 — 返回 201")
    void restoreFromBackup_success_returns201() throws Exception {
        DatabaseEntity restoredDb = new DatabaseEntity();
        restoredDb.setId("db_restored001");
        restoredDb.setName("restored-db");
        restoredDb.setStatus(DatabaseStatus.SUSPENDED);

        when(backupService.restoreFromBackup(any(), eq("db_abc123"), eq("bk_restore"), any()))
                .thenReturn(restoredDb);

        mockMvc.perform(post("/api/v1/databases/db_abc123/backups/bk_restore/restore")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "restored-db"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("db_restored001"))
                .andExpect(jsonPath("$.name").value("restored-db"))
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
    }

    @Test
    @DisplayName("IT-API-BK-007: 恢复 — 备份不存在，返回 404")
    void restoreFromBackup_notFound_returns404() throws Exception {
        when(backupService.restoreFromBackup(any(), eq("db_abc123"), eq("bk_nonexist"), any()))
                .thenThrow(new NotFoundException("Backup not found"));

        mockMvc.perform(post("/api/v1/databases/db_abc123/backups/bk_nonexist/restore")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "restored-db"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }
}
