package com.lakeon.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.config.ApiKeyFilter;
import com.lakeon.model.dto.PitrResponse;
import com.lakeon.model.dto.PitrWindow;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.RecoveryService;
import com.lakeon.service.exception.NotFoundException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
    controllers = RecoveryController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE,
        classes = ApiKeyFilter.class
    )
)
@DisplayName("RecoveryController PITR API 测试")
class RecoveryControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper om;

    @MockBean
    private RecoveryService recoveryService;

    private static TenantEntity tenant() {
        TenantEntity t = new TenantEntity();
        t.setId("tn_test001");
        t.setName("test-tenant");
        return t;
    }

    @Test
    @DisplayName("POST /databases/{id}/pitr — 正常，返回 200 + 响应体（snake_case）")
    void pitr_returns200WithResponseBody() throws Exception {
        when(recoveryService.pitr(any(TenantEntity.class), eq("db1"), any()))
            .thenReturn(new PitrResponse("db_new", "tl_new", "0/AB12", null, "ready"));

        mvc.perform(post("/api/v1/databases/db1/pitr")
                .requestAttr("tenant", tenant())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"target_time": "2026-05-21T14:30:00Z", "new_db_name": "restored"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.new_db_id").value("db_new"))
            .andExpect(jsonPath("$.branch_id").value("tl_new"))
            .andExpect(jsonPath("$.lsn").value("0/AB12"))
            .andExpect(jsonPath("$.status").value("ready"));
    }

    @Test
    @DisplayName("GET /databases/{id}/pitr-window — 正常，返回 200")
    void pitrWindow_returns200() throws Exception {
        when(recoveryService.getPitrWindow(any(TenantEntity.class), eq("db1")))
            .thenReturn(new PitrWindow(
                Instant.parse("2026-04-01T00:00:00Z"),
                Instant.parse("2026-05-21T15:00:00Z"),
                "0/AAAA", "0/FFFF"));

        mvc.perform(get("/api/v1/databases/db1/pitr-window")
                .requestAttr("tenant", tenant()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.earliest_lsn").value("0/AAAA"))
            .andExpect(jsonPath("$.latest_lsn").value("0/FFFF"));
    }

    @Test
    @DisplayName("POST /databases/{id}/pitr — 数据库不存在，返回 404")
    void pitr_returns404OnDatabaseNotFound() throws Exception {
        when(recoveryService.pitr(any(TenantEntity.class), eq("db1"), any()))
            .thenThrow(new NotFoundException("Database not found: db1"));

        mvc.perform(post("/api/v1/databases/db1/pitr")
                .requestAttr("tenant", tenant())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"target_time": "2026-05-21T14:30:00Z"}
                    """))
            .andExpect(status().isNotFound());
    }
}
