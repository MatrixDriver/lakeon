package com.lakeon.agentfirst;

import com.lakeon.config.ApiKeyFilter;
import com.lakeon.config.LakeonProperties;
import com.lakeon.model.entity.TenantEntity;
import com.lakeon.service.TenantService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AgentFirstController.class)
@Import(ApiKeyFilter.class)
@DisplayName("AgentFirstController API tests")
class AgentFirstControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgentFirstService agentFirstService;

    @MockBean
    private TenantService tenantService;

    @MockBean
    private LakeonProperties lakeonProperties;

    private static final String API_KEY = "Bearer test-api-key-valid-32chars!!!";
    private static final String TENANT_ID = "tn_test001";

    @BeforeEach
    void setUp() {
        TenantEntity tenant = new TenantEntity();
        tenant.setId(TENANT_ID);
        tenant.setName("test-tenant");
        tenant.setApiKey("test-api-key-valid-32chars!!!");
        when(tenantService.authenticateByApiKey("test-api-key-valid-32chars!!!"))
                .thenReturn(tenant);
    }

    @Test
    @DisplayName("POST /api/v1/agentfirst/task-runs creates task run")
    void createTaskRun_returnsCreatedTaskRun() throws Exception {
        when(agentFirstService.createTaskRun(eq(TENANT_ID), any()))
                .thenReturn(new AgentFirstDtos.TaskRunResponse("task_001", "data", "running"));

        mockMvc.perform(post("/api/v1/agentfirst/task-runs")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "goal": "publish a dbt model",
                                  "harness_id": "data"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("task_001"))
                .andExpect(jsonPath("$.harness_id").value("data"))
                .andExpect(jsonPath("$.status").value("running"));
    }

    @Test
    @DisplayName("POST /api/v1/agentfirst/workspaces creates logical workspace and root branch")
    void createWorkspace_returnsWorkspaceWithRootBranch() throws Exception {
        when(agentFirstService.createWorkspace(eq(TENANT_ID), any()))
                .thenReturn(new AgentFirstDtos.WorkspaceResponse("ws_001", "branch_root"));

        mockMvc.perform(post("/api/v1/agentfirst/workspaces")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "task_run_id": "task_001"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("ws_001"))
                .andExpect(jsonPath("$.root_branch_id").value("branch_root"));
    }

    @Test
    @DisplayName("Context API resolves nodes and builds context pack")
    void contextEndpoints_matchOpenCodeClientContract() throws Exception {
        when(agentFirstService.resolveContext(eq(TENANT_ID), any()))
                .thenReturn(new AgentFirstDtos.ResolveContextResponse(List.of("schema_orders", "column_customer_email")));
        when(agentFirstService.buildContextPack(eq(TENANT_ID), any()))
                .thenReturn(new AgentFirstDtos.ContextPackResponse("ctx_pack_001"));

        mockMvc.perform(post("/api/v1/agentfirst/context/resolve")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "task_run_id": "task_001",
                                  "stage_run_id": "stage_context",
                                  "query": "orders revenue schema"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.node_ids", hasSize(2)))
                .andExpect(jsonPath("$.node_ids[0]").value("schema_orders"));

        mockMvc.perform(post("/api/v1/agentfirst/context/packs")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "task_run_id": "task_001",
                                  "stage_run_id": "stage_context",
                                  "selected_node_ids": ["schema_orders"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("ctx_pack_001"));
    }

    @Test
    @DisplayName("Policy and audit endpoints support runtime gating")
    void policyAndAuditEndpoints_matchRuntimeGatingContract() throws Exception {
        when(agentFirstService.checkPermission(eq(TENANT_ID), any()))
                .thenReturn(new AgentFirstDtos.PolicyDecisionResponse(true, "allowed"));
        when(agentFirstService.appendAuditEvent(eq(TENANT_ID), any()))
                .thenReturn(new AgentFirstDtos.IdResponse("audit_001"));

        mockMvc.perform(post("/api/v1/agentfirst/policy/check")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "task_run_id": "task_001",
                                  "action": "validate_sql",
                                  "risk_level": "medium",
                                  "branch_id": "branch_001"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.reason").value("allowed"));

        mockMvc.perform(post("/api/v1/agentfirst/audit-events")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "task_run_id": "task_001",
                                  "action": "validate_sql",
                                  "result": "allowed"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("audit_001"));
    }
}
