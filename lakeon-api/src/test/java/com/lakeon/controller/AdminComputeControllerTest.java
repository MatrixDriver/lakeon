package com.lakeon.controller;

import com.lakeon.config.ApiKeyFilter;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.OperationLogEntity;
import com.lakeon.model.enums.OperationStatus;
import com.lakeon.model.enums.OperationType;
import com.lakeon.pageserver.PageserverPlacementService;
import com.lakeon.pageserver.PageserverRebalanceEventService;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.InviteCodeRepository;
import com.lakeon.repository.OperationLogRepository;
import com.lakeon.repository.TenantRepository;
import com.lakeon.service.AdminService;
import com.lakeon.service.DatabaseService;
import com.lakeon.service.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminController.class)
@Import(ApiKeyFilter.class)
class AdminComputeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TenantService tenantService;
    @MockBean
    private TenantRepository tenantRepository;
    @MockBean
    private DatabaseRepository databaseRepository;
    @MockBean
    private DatabaseService databaseService;
    @MockBean
    private AdminService adminService;
    @MockBean
    private InviteCodeRepository inviteCodeRepository;
    @MockBean
    private PageserverPlacementService pageserverPlacementService;
    @MockBean
    private PageserverRebalanceEventService pageserverRebalanceEventService;
    @MockBean
    private OperationLogRepository operationLogRepository;
    @MockBean
    private LakeonProperties lakeonProperties;

    private final LakeonProperties.AdminConfig adminConfig = new LakeonProperties.AdminConfig();
    private final LakeonProperties.ProxyConfig proxyConfig = new LakeonProperties.ProxyConfig();

    @BeforeEach
    void setUp() {
        adminConfig.setToken("test-admin-token");
        when(lakeonProperties.getAdmin()).thenReturn(adminConfig);
        when(lakeonProperties.getProxy()).thenReturn(proxyConfig);
    }

    @Test
    void coldStartAnalysisAggregatesResumeLogsForRequestedWindow() throws Exception {
        Instant base = Instant.parse("2026-06-25T00:00:00Z");
        when(operationLogRepository.findByOperationTypeAndStatusAndStartedAtAfter(
            OperationType.RESUME, OperationStatus.SUCCESS, base.minusSeconds(7L * 24 * 60 * 60)))
            .thenReturn(List.of(
                op("op_cold1", "db_a", "orders", "COLD", base.minusSeconds(60), 2_000L),
                op("op_cold2", "db_a", "orders", "COLD", base.minusSeconds(120), 4_000L),
                op("op_warm1", "db_b", "billing", "WARM", base.minusSeconds(180), 150L)
            ));

        mockMvc.perform(get("/api/v1/admin/compute/cold-start")
                .param("days", "7")
                .param("now", "2026-06-25T00:00:00Z")
                .header("Authorization", "Bearer test-admin-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.cold.count").value(2))
            .andExpect(jsonPath("$.cold.avg_ms").value(3000.0))
            .andExpect(jsonPath("$.cold.p50_ms").value(2000))
            .andExpect(jsonPath("$.cold.p90_ms").value(4000))
            .andExpect(jsonPath("$.cold.p99_ms").value(4000))
            .andExpect(jsonPath("$.warm.count").value(1))
            .andExpect(jsonPath("$.warm.avg_ms").value(150.0))
            .andExpect(jsonPath("$.trend", hasSize(1)))
            .andExpect(jsonPath("$.trend[0].date").value("2026-06-24"))
            .andExpect(jsonPath("$.trend[0].count").value(2))
            .andExpect(jsonPath("$.by_database", hasSize(1)))
            .andExpect(jsonPath("$.by_database[0].database").value("orders"))
            .andExpect(jsonPath("$.by_database[0].count").value(2))
            .andExpect(jsonPath("$.recent", hasSize(2)))
            .andExpect(jsonPath("$.recent[0].id").value("op_cold1"))
            .andExpect(jsonPath("$.recent[0].database_name").value("orders"))
            .andExpect(jsonPath("$.recent[0].duration_ms").value(2000));
    }

    private static OperationLogEntity op(String id, String databaseId, String databaseName,
                                         String resumeType, Instant startedAt, long durationMs) {
        OperationLogEntity entity = new OperationLogEntity();
        entity.setId(id);
        entity.setDatabaseId(databaseId);
        entity.setTenantId("tn_test");
        entity.setDatabaseName(databaseName);
        entity.setOperationType(OperationType.RESUME);
        entity.setStatus(OperationStatus.SUCCESS);
        entity.setStartedAt(startedAt);
        entity.setCompletedAt(startedAt.plusMillis(durationMs));
        entity.setDurationMs(durationMs);
        entity.setResumeType(resumeType);
        return entity;
    }
}
