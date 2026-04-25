package com.lakeon.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.lakeon.service.AdminService;
import com.lakeon.service.AlertService;
import com.lakeon.service.AuditService;
import com.lakeon.service.CbcBillingService;
import com.lakeon.service.DatabaseService;
import com.lakeon.service.LogQueryService;
import com.lakeon.service.TenantReconcileService;
import com.lakeon.service.TenantService;
import com.lakeon.service.UsageMeteringService;
import com.lakeon.service.admin.DataConsistencyCheckService;
import com.lakeon.service.admin.StuckTaskQueryService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminController.class)
@Import(ApiKeyFilter.class)
class AdminControllerDataConsistencyTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;

    // === All @MockBeans from AdminControllerTest ===
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

    // === 2 new services ===
    @MockBean private DataConsistencyCheckService dccService;
    @MockBean private StuckTaskQueryService stqService;

    private static final String ADMIN_TOKEN = "Bearer test-token";

    @BeforeEach
    void setUp() {
        var adminConfig = new LakeonProperties.AdminConfig();
        adminConfig.setToken("test-token");
        when(lakeonProperties.getAdmin()).thenReturn(adminConfig);
    }

    @Test
    void dataConsistencyEndpointReturnsServiceJson() throws Exception {
        when(dccService.run(eq("kb_implies_db_id"), anyInt())).thenReturn(Map.of(
                "ok", false, "rule", "kb_implies_db_id", "count", 2,
                "violations", List.of(Map.of("kb_id", "kb_a"), Map.of("kb_id", "kb_b"))
        ));
        mvc.perform(get("/api/v1/admin/data-consistency/kb_implies_db_id")
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.violations[0].kb_id").value("kb_a"));
    }

    @Test
    void stuckTasksEndpointReturnsServiceJson() throws Exception {
        when(stqService.run(eq(10), eq(""))).thenReturn(Map.of(
                "count", 1, "threshold_minutes", 10,
                "tasks", List.of(Map.of("task_id", "t_42", "task_type", "WIKI_UPDATE",
                        "source", "wiki_run_logs", "age_sec", 700))
        ));
        mvc.perform(get("/api/v1/admin/stuck-tasks?threshold_minutes=10")
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.tasks[0].task_type").value("WIKI_UPDATE"));
    }
}
