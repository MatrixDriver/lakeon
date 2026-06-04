package com.lakeon.agentstate;

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

@WebMvcTest(AgentStateController.class)
@Import(ApiKeyFilter.class)
@DisplayName("AgentStateController API tests")
class AgentStateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AgentStateService agentStateService;

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
    @DisplayName("Agent app registry creates and lists app metadata")
    void agentAppRegistry_createsAndListsApps() throws Exception {
        AgentStateDtos.AgentAppResponse app = new AgentStateDtos.AgentAppResponse(
                "app_001",
                "paperbench",
                "论文复现实验助手",
                "benchmark",
                "0.1.0",
                "active",
                List.of("paper_parse", "claim_extract", "experiment_run", "evidence_pack", "report_gate"));
        when(agentStateService.createAgentApp(eq(TENANT_ID), any())).thenReturn(app);
        when(agentStateService.listAgentApps(eq(TENANT_ID))).thenReturn(List.of(app));
        when(agentStateService.getAgentApp(eq(TENANT_ID), eq("app_001"))).thenReturn(app);

        mockMvc.perform(post("/api/v1/agent-state/apps")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "key": "paperbench",
                                  "displayName": "论文复现实验助手",
                                  "type": "benchmark",
                                  "version": "0.1.0",
                                  "stageSchema": ["paper_parse", "claim_extract", "experiment_run", "evidence_pack", "report_gate"]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("app_001"))
                .andExpect(jsonPath("$.key").value("paperbench"))
                .andExpect(jsonPath("$.displayName").value("论文复现实验助手"))
                .andExpect(jsonPath("$.stageSchema[1]").value("claim_extract"));

        mockMvc.perform(get("/api/v1/agent-state/apps")
                        .header("Authorization", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("app_001"))
                .andExpect(jsonPath("$[0].display_name").value("论文复现实验助手"));

        mockMvc.perform(get("/api/v1/agent-state/apps/app_001")
                        .header("Authorization", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("app_001"))
                .andExpect(jsonPath("$.key").value("paperbench"));
    }

    @Test
    @DisplayName("POST /api/v1/agent-state/apps/{appId}/runs creates task run bound to app")
    void createAgentAppRun_bindsTaskRunToApp() throws Exception {
        when(agentStateService.createTaskRunForApp(eq(TENANT_ID), eq("app_001"), any()))
                .thenReturn(new AgentStateDtos.TaskRunResponse("task_001", "paperbench", "running", "app_001"));

        mockMvc.perform(post("/api/v1/agent-state/apps/app_001/runs")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "goal": "verify a paper claim"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("task_001"))
                .andExpect(jsonPath("$.harness_id").value("paperbench"))
                .andExpect(jsonPath("$.agent_app_id").value("app_001"));
    }

    @Test
    @DisplayName("POST /api/v1/agent-state/task-runs creates task run")
    void createTaskRun_returnsCreatedTaskRun() throws Exception {
        when(agentStateService.createTaskRun(eq(TENANT_ID), any()))
                .thenReturn(new AgentStateDtos.TaskRunResponse("task_001", "data", "running", "app_001"));

        mockMvc.perform(post("/api/v1/agent-state/task-runs")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "goal": "publish a dbt model",
                                  "harness_id": "data",
                                  "agent_app_id": "app_001"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("task_001"))
                .andExpect(jsonPath("$.harness_id").value("data"))
                .andExpect(jsonPath("$.agent_app_id").value("app_001"))
                .andExpect(jsonPath("$.agentAppId").value("app_001"))
                .andExpect(jsonPath("$.status").value("running"));
    }

    @Test
    @DisplayName("POST /api/v1/agent-state/workspaces creates logical workspace and root branch")
    void createWorkspace_returnsWorkspaceWithRootBranch() throws Exception {
        when(agentStateService.createWorkspace(eq(TENANT_ID), any()))
                .thenReturn(new AgentStateDtos.WorkspaceResponse("ws_001", "branch_root"));

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
    @DisplayName("OpenCode DataAgent HTTP contract accepts camelCase payloads under agent-state prefix")
    void opencodeDataAgentContract_acceptsCamelCasePayloadsAndRouteAliases() throws Exception {
        when(agentStateService.createTaskRun(eq(TENANT_ID), any()))
                .thenReturn(new AgentStateDtos.TaskRunResponse("task_001", "data", "running"));
        when(agentStateService.createWorkspace(eq(TENANT_ID), any()))
                .thenReturn(new AgentStateDtos.WorkspaceResponse("ws_001", "branch_root"));
        when(agentStateService.resolveContext(eq(TENANT_ID), any()))
                .thenReturn(new AgentStateDtos.ResolveContextResponse(List.of("schema_orders")));
        when(agentStateService.buildContextPack(eq(TENANT_ID), any()))
                .thenReturn(new AgentStateDtos.ContextPackResponse("ctx_pack_001"));
        when(agentStateService.checkPermission(eq(TENANT_ID), any()))
                .thenReturn(new AgentStateDtos.PolicyDecisionResponse(true, "allowed"));
        when(agentStateService.forkBranch(eq(TENANT_ID), any()))
                .thenReturn(new AgentStateDtos.BranchResponse("branch_001"));
        when(agentStateService.appendStateCommit(eq(TENANT_ID), any()))
                .thenReturn(new AgentStateDtos.IdResponse("commit_001"));
        when(agentStateService.recordArtifact(eq(TENANT_ID), any()))
                .thenReturn(new AgentStateDtos.IdResponse("artifact_001"));
        when(agentStateService.recordLineage(eq(TENANT_ID), any()))
                .thenReturn(new AgentStateDtos.IdResponse("lineage_001"));
        when(agentStateService.snapshotManifest(eq(TENANT_ID), any()))
                .thenReturn(new AgentStateDtos.IdResponse("manifest_001"));
        when(agentStateService.appendAuditEvent(eq(TENANT_ID), any()))
                .thenReturn(new AgentStateDtos.IdResponse("audit_001"));

        mockMvc.perform(post("/api/v1/agent-state/task-runs")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"goal": "publish daily order revenue", "harnessId": "data"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("task_001"));

        mockMvc.perform(post("/api/v1/agent-state/workspaces")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskRunId": "task_001"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("ws_001"))
                .andExpect(jsonPath("$.rootBranchId").value("branch_root"));

        mockMvc.perform(post("/api/v1/agent-state/context/resolve")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskRunId": "task_001", "stageRunId": "stage_context", "query": "orders"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeIds[0]").value("schema_orders"));

        mockMvc.perform(post("/api/v1/agent-state/context/packs")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskRunId": "task_001", "stageRunId": "stage_context", "selectedNodeIds": ["schema_orders"]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("ctx_pack_001"));

        mockMvc.perform(post("/api/v1/agent-state/policy/check")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "taskRunId": "task_001",
                                  "stageRunId": "stage_sql",
                                  "action": "validate_sql",
                                  "riskLevel": "medium",
                                  "branchId": "branch_001",
                                  "intendedReadWriteSet": {"reads": ["ctx_pack_001"], "writes": ["compiled_sql"]}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true));

        mockMvc.perform(post("/api/v1/agent-state/workspaces/branches/fork")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workspaceId": "ws_001", "stageRunId": "stage_sql", "hypothesis": "publish daily order revenue"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("branch_001"));

        mockMvc.perform(post("/api/v1/agent-state/artifacts/state-commits")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskRunId": "task_001", "stageRunId": "stage_sql", "branchId": "branch_001", "summary": "validated SQL fixture"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("commit_001"));

        mockMvc.perform(post("/api/v1/agent-state/artifacts")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskRunId": "task_001", "stageRunId": "stage_sql", "branchId": "branch_001", "kind": "compiled_sql"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("artifact_001"));

        mockMvc.perform(post("/api/v1/agent-state/lineage")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskRunId": "task_001", "stageRunId": "stage_sql", "branchId": "branch_001", "artifactId": "artifact_001"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("lineage_001"));

        mockMvc.perform(post("/api/v1/agent-state/artifacts/manifests/snapshot")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskRunId": "task_001", "stageRunId": "stage_sql", "branchId": "branch_001", "artifactIds": ["artifact_001"]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("manifest_001"));

        mockMvc.perform(post("/api/v1/agent-state/audit/events")
                        .header("Authorization", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"taskRunId": "task_001", "action": "publish_data_task", "result": "allowed"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("audit_001"));
    }

    @Test
    @DisplayName("Context API resolves nodes and builds context pack")
    void contextEndpoints_matchOpenCodeClientContract() throws Exception {
        when(agentStateService.ingestContextSource(eq(TENANT_ID), any()))
                .thenReturn(new AgentStateDtos.IngestContextResponse(List.of("schema_orders", "column_customer_email")));
        when(agentStateService.resolveContext(eq(TENANT_ID), any()))
                .thenReturn(new AgentStateDtos.ResolveContextResponse(List.of("schema_orders", "column_customer_email")));
        when(agentStateService.buildContextPack(eq(TENANT_ID), any()))
                .thenReturn(new AgentStateDtos.ContextPackResponse("ctx_pack_001"));

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
        when(agentStateService.createCheckpoint(eq(TENANT_ID), any()))
                .thenReturn(new AgentStateDtos.CheckpointResponse("ckpt_001"));
        when(agentStateService.restoreCheckpoint(eq(TENANT_ID), eq("ckpt_001")))
                .thenReturn(new AgentStateDtos.RestorePlanResponse(
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
        when(agentStateService.createEvidencePacket(eq(TENANT_ID), any()))
                .thenReturn(new AgentStateDtos.EvidencePacketResponse("evidence_001", "pending"));
        when(agentStateService.evaluateEvidence(eq(TENANT_ID), eq("evidence_001")))
                .thenReturn(new AgentStateDtos.PolicyDecisionResponse(false, "missing verified evidence"));

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
        when(agentStateService.checkPermission(eq(TENANT_ID), any()))
                .thenReturn(new AgentStateDtos.PolicyDecisionResponse(true, "allowed"));
        when(agentStateService.appendAuditEvent(eq(TENANT_ID), any()))
                .thenReturn(new AgentStateDtos.IdResponse("audit_001"));

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
