package com.lakeon.controller;

import com.lakeon.config.ApiKeyFilter;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.OperationLogEntity;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.model.enums.OperationStatus;
import com.lakeon.model.enums.OperationType;
import com.lakeon.service.OperationLogService;
import com.lakeon.service.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OperationLogController.class)
@Import(ApiKeyFilter.class)
@DisplayName("OperationLogController API tests")
class OperationLogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OperationLogService operationLogService;

    @MockBean
    private TenantService tenantService;

    @MockBean
    private LakeonProperties lakeonProperties;

    private static final String API_KEY = "Bearer lk_testkey";

    private TenantEntity mockTenant() {
        TenantEntity tenant = new TenantEntity();
        tenant.setId("tn_test1");
        tenant.setName("test-tenant");
        tenant.setApiKey("lk_testkey");
        return tenant;
    }

    private OperationLogEntity mockLog() {
        Instant started = Instant.parse("2026-03-04T10:00:00Z");
        Instant completed = Instant.parse("2026-03-04T10:00:00.800Z");

        OperationLogEntity log = new OperationLogEntity();
        log.setId("op_abc12345");
        log.setDatabaseId("db_test001");
        log.setTenantId("tn_test1");
        log.setDatabaseName("my-app-db");
        log.setOperationType(OperationType.RESUME);
        log.setStatus(OperationStatus.SUCCESS);
        log.setStartedAt(started);
        log.setCompletedAt(completed);
        log.setDurationMs(800L);
        return log;
    }

    @BeforeEach
    void setUp() {
        when(tenantService.authenticateByApiKey("lk_testkey"))
                .thenReturn(mockTenant());
    }

    @Test
    @DisplayName("GET /api/v1/operations/recent returns 200 with correct JSON fields")
    void getRecentOperations_returns200() throws Exception {
        when(operationLogService.getRecent("tn_test1"))
                .thenReturn(List.of(mockLog()));

        mockMvc.perform(get("/api/v1/operations/recent")
                        .header("Authorization", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("op_abc12345"))
                .andExpect(jsonPath("$[0].databaseId").value("db_test001"))
                .andExpect(jsonPath("$[0].databaseName").value("my-app-db"))
                .andExpect(jsonPath("$[0].operationType").value("RESUME"))
                .andExpect(jsonPath("$[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$[0].durationMs").value(800))
                .andExpect(jsonPath("$[0].startedAt").isNotEmpty())
                .andExpect(jsonPath("$[0].completedAt").isNotEmpty())
                .andExpect(jsonPath("$[0].errorMessage").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/operations/recent without auth returns 401")
    void getRecentOperations_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/operations/recent"))
                .andExpect(status().isUnauthorized());
    }
}
