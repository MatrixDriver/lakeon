package com.lakeon.controller;

import com.lakeon.config.ApiKeyFilter;
import com.lakeon.config.LakeonProperties;
import com.lakeon.datalake.DatalakeJobRepository;
import com.lakeon.datalake.DatalakeLogService;
import com.lakeon.datalake.DatalakeService;
import com.lakeon.dataset.DatasetRepository;
import com.lakeon.dataset.DatasetService;
import com.lakeon.knowledge.DocumentRepository;
import com.lakeon.knowledge.KbWriteQueue;
import com.lakeon.knowledge.KbWriteTaskRepository;
import com.lakeon.knowledge.KnowledgeBaseRepository;
import com.lakeon.knowledge.KnowledgeService;
import com.lakeon.knowledge.WikiService;
import com.lakeon.memory.MemoryBaseRepository;
import com.lakeon.memory.MemoryService;
import com.lakeon.model.dto.TenantResponse;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.neon.NeonApiClient;
import com.lakeon.notebook.NotebookSessionRepository;
import com.lakeon.pipeline.PipelineComponentRepository;
import com.lakeon.pipeline.PipelineRepository;
import com.lakeon.pipeline.PipelineRunRepository;
import com.lakeon.pipeline.PipelineStepRunRepository;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.repository.InviteCodeRepository;
import com.lakeon.repository.OperationLogRepository;
import com.lakeon.repository.SystemConfigRepository;
import com.lakeon.repository.TenantRepository;
import com.lakeon.service.*;
import com.lakeon.service.admin.DataConsistencyCheckService;
import com.lakeon.service.admin.StuckTaskQueryService;
import com.lakeon.service.exception.NotFoundException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminController.class)
@Import(ApiKeyFilter.class)
@DisplayName("AdminController API 集成测试")
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean private TenantService tenantService;
    @MockBean private AdminService adminService;
    @MockBean private DatabaseService databaseService;
    @MockBean private DatabaseRepository databaseRepository;
    @MockBean private TenantRepository tenantRepository;
    @MockBean private OperationLogRepository operationLogRepository;
    @MockBean private UsageMeteringService usageMeteringService;
    @MockBean private CbcBillingService cbcBillingService;
    @MockBean private AlertService alertService;
    @MockBean private AuditService auditService;
    @MockBean private InviteCodeRepository inviteCodeRepository;
    @MockBean private LakeonProperties lakeonProperties;
    @MockBean private KnowledgeBaseRepository knowledgeBaseRepository;
    @MockBean private DocumentRepository documentRepository;
    @MockBean private KbWriteTaskRepository kbWriteTaskRepository;
    @MockBean private KbWriteQueue kbWriteQueue;
    @MockBean private KnowledgeService knowledgeService;
    @MockBean private MemoryBaseRepository memoryBaseRepository;
    @MockBean private MemoryService memoryService;
    @MockBean private DatalakeJobRepository datalakeJobRepository;
    @MockBean private DatalakeLogService datalakeLogService;
    @MockBean private DatalakeService datalakeService;
    @MockBean private DatasetRepository datasetRepository;
    @MockBean private DatasetService datasetService;
    @MockBean private NotebookSessionRepository notebookSessionRepository;
    @MockBean private SystemConfigRepository systemConfigRepository;
    @MockBean private LogQueryService logQueryService;
    @MockBean private PipelineRepository pipelineRepository;
    @MockBean private PipelineRunRepository pipelineRunRepository;
    @MockBean private PipelineStepRunRepository pipelineStepRunRepository;
    @MockBean private PipelineComponentRepository pipelineComponentRepository;
    @MockBean private WikiService wikiService;
    @MockBean private TenantReconcileService tenantReconcileService;
    @MockBean private NeonApiClient neonApiClient;
    @MockBean private DataConsistencyCheckService dataConsistencyCheckService;
    @MockBean private StuckTaskQueryService stuckTaskQueryService;

    private static final String ADMIN_TOKEN = "Bearer test-admin-token";

    @BeforeEach
    void setUp() {
        var adminConfig = new LakeonProperties.AdminConfig();
        adminConfig.setToken("test-admin-token");
        when(lakeonProperties.getAdmin()).thenReturn(adminConfig);
    }

    @Nested
    @DisplayName("租户禁用/启用")
    class TenantDisableEnable {

        @Test
        @DisplayName("IT-ADM-TN-001: 禁用租户 — 返回 200")
        void disableTenant_returns200() throws Exception {
            var response = TenantResponse.builder()
                    .id("tn_dis001").name("test-tenant")
                    .disabled(true).disabledAt(Instant.now())
                    .build();
            when(tenantService.disableTenant("tn_dis001")).thenReturn(response);

            mockMvc.perform(post("/api/v1/admin/tenants/tn_dis001/disable")
                            .header("Authorization", ADMIN_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.disabled").value(true))
                    .andExpect(jsonPath("$.disabled_at").isNotEmpty());
        }

        @Test
        @DisplayName("IT-ADM-TN-002: 启用租户 — 返回 200")
        void enableTenant_returns200() throws Exception {
            var response = TenantResponse.builder()
                    .id("tn_en001").name("test-tenant")
                    .disabled(false)
                    .build();
            when(tenantService.enableTenant("tn_en001")).thenReturn(response);

            mockMvc.perform(post("/api/v1/admin/tenants/tn_en001/enable")
                            .header("Authorization", ADMIN_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.disabled").value(false));
        }

        @Test
        @DisplayName("IT-ADM-TN-003: 禁用不存在的租户 — 返回 404")
        void disableTenant_notFound() throws Exception {
            when(tenantService.disableTenant("tn_ghost"))
                    .thenThrow(new NotFoundException("Tenant not found: tn_ghost"));

            mockMvc.perform(post("/api/v1/admin/tenants/tn_ghost/disable")
                            .header("Authorization", ADMIN_TOKEN))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("IT-ADM-TN-004: 无 admin token — 返回 403")
        void disableTenant_noAuth() throws Exception {
            mockMvc.perform(post("/api/v1/admin/tenants/tn_dis001/disable"))
                    .andExpect(status().isForbidden());
        }
    }

    @Nested
    @DisplayName("数据库详情")
    class DatabaseDetail {

        @Test
        @DisplayName("IT-ADM-DB-001: 获取数据库详情 — 返回 200")
        void getDatabase_returns200() throws Exception {
            var db = new DatabaseEntity();
            db.setId("db_detail01");
            db.setName("my-db");
            db.setTenantId("tn_test01");
            db.setStatus(DatabaseStatus.RUNNING);
            db.setComputeSize("2CU");
            db.setStorageLimitGb(10);
            db.setCreatedAt(Instant.now());
            when(databaseRepository.findById("db_detail01")).thenReturn(Optional.of(db));

            mockMvc.perform(get("/api/v1/admin/databases/db_detail01")
                            .header("Authorization", ADMIN_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value("db_detail01"))
                    .andExpect(jsonPath("$.name").value("my-db"))
                    .andExpect(jsonPath("$.tenant_id").value("tn_test01"))
                    .andExpect(jsonPath("$.status").value("RUNNING"));
        }

        @Test
        @DisplayName("IT-ADM-DB-002: 数据库不存在 — 返回 404")
        void getDatabase_notFound() throws Exception {
            when(databaseRepository.findById("db_ghost")).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/v1/admin/databases/db_ghost")
                            .header("Authorization", ADMIN_TOKEN))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("OBS 健康检查")
    class ObsHealth {

        @Test
        @DisplayName("IT-ADM-OBS-001: OBS 健康端点 — 返回 200")
        void obsHealth_returns200() throws Exception {
            Map<String, Object> obsResult = new LinkedHashMap<>();
            obsResult.put("status", "healthy");
            obsResult.put("endpoint", "obs.cn-north-4.myhuaweicloud.com");
            obsResult.put("bucket", "lakeon-storage");
            when(adminService.checkObs()).thenReturn(obsResult);

            mockMvc.perform(get("/api/v1/admin/system/health/obs")
                            .header("Authorization", ADMIN_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("healthy"))
                    .andExpect(jsonPath("$.endpoint").isNotEmpty())
                    .andExpect(jsonPath("$.bucket").isNotEmpty());
        }
    }

    @Nested
    @DisplayName("成本趋势")
    class CostTrend {

        @Test
        @DisplayName("IT-ADM-COST-001: 获取成本趋势 — 返回数组")
        void costTrend_returnsArray() throws Exception {
            Map<String, Object> day1 = new LinkedHashMap<>();
            day1.put("date", "2026-03-01");
            day1.put("fixed_cost", 154.67);
            day1.put("compute_cost", 5.0);
            day1.put("total_cost", 159.67);
            when(adminService.getCostTrend(30)).thenReturn(List.of(day1));

            mockMvc.perform(get("/api/v1/admin/cost/trend")
                            .header("Authorization", ADMIN_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].date").value("2026-03-01"))
                    .andExpect(jsonPath("$[0].fixed_cost").value(154.67))
                    .andExpect(jsonPath("$[0].compute_cost").value(5.0));
        }

        @Test
        @DisplayName("IT-ADM-COST-002: 自定义天数参数")
        void costTrend_customDays() throws Exception {
            when(adminService.getCostTrend(7)).thenReturn(List.of());

            mockMvc.perform(get("/api/v1/admin/cost/trend?days=7")
                            .header("Authorization", ADMIN_TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }
    }
}
