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
    @DisplayName("POST /api/v1/agent-state/task-runs creates task run")
    void createTaskRun_returnsCreatedTaskRun() throws Exception {
        when(agentFirstService.createTaskRun(eq(TENANT_ID), any()))
                .thenReturn(new AgentFirstDtos.TaskRunResponse("task_001", "data", "running"));

        mockMvc.perform(post("/api/v1/agent-state/task-runs")
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
    @DisplayName("POST /api/v1/agent-state/workspaces creates logical workspace and root branch")
    void createWorkspace_returnsWorkspaceWithRootBranch() throws Exception {
        when(agentFirstService.createWorkspace(eq(TENANT_ID), any()))
                .thenReturn(new AgentFirstDtos.WorkspaceResponse("ws_001", "branch_root"));

        mockMvc.perform(post("/api/v1/agent-state/workspaces")
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
        when(agentFirstService.ingestContextSource(eq(TENANT_ID), any()))
                .thenReturn(new AgentFirstDtos.IngestContextResponse(List.of("schema_orders", "column_customer_email")));
        when(agentFirstService.resolveContext(eq(TENANT_ID), any()))
                .thenReturn(new AgentFirstDtos.ResolveContextResponse(List.of("schema_orders", "column_customer_email")));
        when(agentFirstService.buildContextPack(eq(TENANT_ID), any()))
                .thenReturn(new AgentFirstDtos.ContextPackResponse("ctx_pack_001"));

        mockMvc.perform(post("/api/v1/agent-state/context/sources")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "source_type": "dbt_manifest",
                                  "source_ref": "fixtures/data-agent/manifest.json",
                                  "nodes": [
                                    {"id": "schema_orders", "type": "table", "name": "orders"},
                                    {"id": "column_customer_email", "type": "column", "name": "customers.email"}
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.node_ids", hasSize(2)))
                .andExpect(jsonPath("$.node_ids[0]").value("schema_orders"));

        mockMvc.perform(post("/api/v1/agent-state/context/resolve")
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

        mockMvc.perform(post("/api/v1/agent-state/context/packs")
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
    @DisplayName("Checkpoint API creates restore plan for branch resume")
    void checkpointEndpoints_returnRestorePlan() throws Exception {
        when(agentFirstService.createCheckpoint(eq(TENANT_ID), any()))
                .thenReturn(new AgentFirstDtos.CheckpointResponse("ckpt_001"));
        when(agentFirstService.restoreCheckpoint(eq(TENANT_ID), eq("ckpt_001")))
                .thenReturn(new AgentFirstDtos.RestorePlanResponse(
                        "ckpt_001",
                        List.of("artifact_sql_001"),
                        List.of("lineage_snapshot_001"),
                        false));

        mockMvc.perform(post("/api/v1/agent-state/checkpoints")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "branch_id": "branch_001",
                                  "stage_run_id": "stage_sql",
                                  "manifest": {
                                    "artifacts": ["artifact_sql_001"],
                                    "lineage": ["lineage_snapshot_001"]
                                  }
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("ckpt_001"));

        mockMvc.perform(post("/api/v1/agent-state/checkpoints/ckpt_001/restore")
                        .header("Authorization", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpoint_id").value("ckpt_001"))
                .andExpect(jsonPath("$.restorable_refs[0]").value("artifact_sql_001"))
                .andExpect(jsonPath("$.missing_refs[0]").value("lineage_snapshot_001"))
                .andExpect(jsonPath("$.complete").value(false));
    }

    @Test
    @DisplayName("Evidence API creates packet and blocks missing evidence")
    void evidenceEndpoints_createPacketAndEvaluateGate() throws Exception {
        when(agentFirstService.createEvidencePacket(eq(TENANT_ID), any()))
                .thenReturn(new AgentFirstDtos.EvidencePacketResponse("evidence_001", "pending"));
        when(agentFirstService.evaluateEvidence(eq(TENANT_ID), eq("evidence_001")))
                .thenReturn(new AgentFirstDtos.PolicyDecisionResponse(false, "missing verified evidence"));

        mockMvc.perform(post("/api/v1/agent-state/evidence-packets")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "task_run_id": "task_001",
                                  "branch_id": "branch_001",
                                  "claim": "daily revenue SQL is publishable",
                                  "evidence_refs": []
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("evidence_001"))
                .andExpect(jsonPath("$.status").value("pending"));

        mockMvc.perform(post("/api/v1/agent-state/evidence-packets/evidence_001/evaluate")
                        .header("Authorization", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reason").value("missing verified evidence"));
    }

    @Test
    @DisplayName("Policy and audit endpoints support runtime gating")
    void policyAndAuditEndpoints_matchRuntimeGatingContract() throws Exception {
        when(agentFirstService.checkPermission(eq(TENANT_ID), any()))
                .thenReturn(new AgentFirstDtos.PolicyDecisionResponse(true, "allowed"));
        when(agentFirstService.appendAuditEvent(eq(TENANT_ID), any()))
                .thenReturn(new AgentFirstDtos.IdResponse("audit_001"));

        mockMvc.perform(post("/api/v1/agent-state/policy/check")
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

        mockMvc.perform(post("/api/v1/agent-state/audit-events")
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
