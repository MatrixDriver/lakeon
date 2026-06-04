package com.lakeon.agentstate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentStateService unit tests")
class AgentStateServiceTest {

    @Mock private AgentTaskRunRepository taskRunRepository;
    @Mock private AgentAppRepository agentAppRepository;
    @Mock private AgentStageRunRepository stageRunRepository;
    @Mock private AgentWorkspaceRepository workspaceRepository;
    @Mock private AgentWorkspaceBranchRepository branchRepository;
    @Mock private ContextNodeRepository contextNodeRepository;
    @Mock private ContextPackRepository contextPackRepository;
    @Mock private AgentCheckpointRepository checkpointRepository;
    @Mock private AgentStateCommitRepository stateCommitRepository;
    @Mock private AgentArtifactRefRepository artifactRefRepository;
    @Mock private AgentLineageEdgeRepository lineageEdgeRepository;
    @Mock private AgentEvidencePacketRepository evidencePacketRepository;
    @Mock private AgentPolicyDecisionRepository policyDecisionRepository;
    @Mock private AgentAuditEventRepository auditEventRepository;

    @Test
    @DisplayName("createAgentApp registers tenant-scoped agent app metadata")
    void createAgentApp_registersTenantScopedApp() {
        AgentStateService service = service();
        when(agentAppRepository.save(any(AgentAppEntity.class))).thenAnswer(inv -> {
            AgentAppEntity entity = inv.getArgument(0);
            entity.prePersist();
            return entity;
        });

        AgentStateDtos.AgentAppResponse response = service.createAgentApp(
                "tn_test001",
                new AgentStateDtos.CreateAgentAppRequest(
                        "paperbench",
                        "论文复现实验助手",
                        "benchmark",
                        "0.1.0",
                        "active",
                        List.of("paper_parse", "claim_extract", "experiment_run", "evidence_pack", "report_gate")));

        assertThat(response.id()).startsWith("app_");
        assertThat(response.key()).isEqualTo("paperbench");
        assertThat(response.displayName()).isEqualTo("论文复现实验助手");

        ArgumentCaptor<AgentAppEntity> appCaptor = ArgumentCaptor.forClass(AgentAppEntity.class);
        verify(agentAppRepository).save(appCaptor.capture());
        assertThat(appCaptor.getValue().getTenantId()).isEqualTo("tn_test001");
        assertThat(appCaptor.getValue().getStageSchemaJson()).contains("claim_extract");
    }

    @Test
    @DisplayName("listAgentApps returns tenant scoped app metadata")
    void listAgentApps_returnsTenantScopedApps() {
        AgentStateService service = service();
        AgentAppEntity app = new AgentAppEntity();
        app.setId("app_001");
        app.setKey("data");
        app.setDisplayName("数据发布检查助手");
        app.setType("data");
        app.setVersion("0.1.0");
        app.setStatus("active");
        when(agentAppRepository.findByTenantIdOrderByCreatedAtAsc("tn_test001")).thenReturn(List.of(app));

        List<AgentStateDtos.AgentAppResponse> response = service.listAgentApps("tn_test001");

        assertThat(response).hasSize(1);
        assertThat(response.get(0).id()).isEqualTo("app_001");
        assertThat(response.get(0).displayName()).isEqualTo("数据发布检查助手");
    }

    @Test
    @DisplayName("createTaskRun can bind an agent app while preserving harness id")
    void createTaskRun_bindsAgentAppAndHarnessId() {
        AgentStateService service = service();
        when(taskRunRepository.save(any(AgentTaskRunEntity.class))).thenAnswer(inv -> {
            AgentTaskRunEntity entity = inv.getArgument(0);
            entity.prePersist();
            return entity;
        });

        AgentStateDtos.TaskRunResponse response = service.createTaskRun(
                "tn_test001",
                new AgentStateDtos.CreateTaskRunRequest(
                        "verify a paper claim",
                        "paperbench",
                        "app_001"));

        assertThat(response.id()).startsWith("task_");
        assertThat(response.harnessId()).isEqualTo("paperbench");
        assertThat(response.agentAppId()).isEqualTo("app_001");

        ArgumentCaptor<AgentTaskRunEntity> taskCaptor = ArgumentCaptor.forClass(AgentTaskRunEntity.class);
        verify(taskRunRepository).save(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getAgentAppId()).isEqualTo("app_001");
    }

    @Test
    @DisplayName("createTaskRunForApp defaults harness id from app key")
    void createTaskRunForApp_defaultsHarnessIdFromAppKey() {
        AgentStateService service = service();
        AgentAppEntity app = new AgentAppEntity();
        app.setId("app_001");
        app.setTenantId("tn_test001");
        app.setKey("paperbench");
        app.setDisplayName("PaperBench");
        app.setType("benchmark");
        app.setVersion("0.1.0");
        app.setStatus("active");
        when(agentAppRepository.findByIdAndTenantId("app_001", "tn_test001")).thenReturn(Optional.of(app));
        when(taskRunRepository.save(any(AgentTaskRunEntity.class))).thenAnswer(inv -> {
            AgentTaskRunEntity entity = inv.getArgument(0);
            entity.prePersist();
            return entity;
        });

        AgentStateDtos.TaskRunResponse response = service.createTaskRunForApp(
                "tn_test001",
                "app_001",
                new AgentStateDtos.CreateAgentAppRunRequest("verify a paper claim", null));

        assertThat(response.id()).startsWith("task_");
        assertThat(response.harnessId()).isEqualTo("paperbench");
        assertThat(response.agentAppId()).isEqualTo("app_001");

        ArgumentCaptor<AgentTaskRunEntity> taskCaptor = ArgumentCaptor.forClass(AgentTaskRunEntity.class);
        verify(taskRunRepository).save(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getHarnessId()).isEqualTo("paperbench");
        assertThat(taskCaptor.getValue().getAgentAppId()).isEqualTo("app_001");
    }

    @Test
    @DisplayName("listTaskRuns returns console summary metrics for tenant task runs")
    void listTaskRuns_returnsConsoleSummaryMetrics() {
        AgentStateService service = service();
        AgentTaskRunEntity task = new AgentTaskRunEntity();
        task.setId("task_001");
        task.setTenantId("tn_test001");
        task.setGoal("verify quicksort");
        task.setHarnessId("paperbench");
        task.setStatus("running");
        task.setCreatedAt(java.time.Instant.parse("2026-06-04T00:00:00Z"));
        AgentStageRunEntity stage = new AgentStageRunEntity();
        stage.setId("stage_001");
        stage.setTaskRunId("task_001");
        stage.setStageId("experiment_run");
        AgentWorkspaceEntity workspace = new AgentWorkspaceEntity();
        workspace.setId("ws_001");
        workspace.setTaskRunId("task_001");
        AgentWorkspaceBranchEntity root = new AgentWorkspaceBranchEntity();
        root.setId("awb_root");
        root.setName("root");
        AgentWorkspaceBranchEntity branch = new AgentWorkspaceBranchEntity();
        branch.setId("awb_001");
        branch.setName("branch");
        AgentEvidencePacketEntity evidence = new AgentEvidencePacketEntity();
        evidence.setId("evidence_001");
        AgentAuditEventEntity audit = new AgentAuditEventEntity();
        audit.setId("audit_001");
        audit.setResult("allowed");
        when(taskRunRepository.findByTenantIdOrderByCreatedAtDesc("tn_test001")).thenReturn(List.of(task));
        when(stageRunRepository.findByTenantIdAndTaskRunIdOrderByCreatedAtAsc("tn_test001", "task_001")).thenReturn(List.of(stage));
        when(workspaceRepository.findByTenantIdAndTaskRunId("tn_test001", "task_001")).thenReturn(Optional.of(workspace));
        when(branchRepository.findByTenantIdAndWorkspaceIdOrderByCreatedAtAsc("tn_test001", "ws_001")).thenReturn(List.of(root, branch));
        when(evidencePacketRepository.findByTenantIdAndTaskRunIdOrderByCreatedAtAsc("tn_test001", "task_001")).thenReturn(List.of(evidence));
        when(auditEventRepository.findByTenantIdAndTaskRunIdOrderByCreatedAtAsc("tn_test001", "task_001")).thenReturn(List.of(audit));

        List<AgentStateDtos.TaskRunSummaryResponse> response = service.listTaskRuns("tn_test001");

        assertThat(response).hasSize(1);
        assertThat(response.get(0).id()).isEqualTo("task_001");
        assertThat(response.get(0).currentStageId()).isEqualTo("experiment_run");
        assertThat(response.get(0).workspaceId()).isEqualTo("ws_001");
        assertThat(response.get(0).branchCount()).isEqualTo(2);
        assertThat(response.get(0).evidenceCount()).isEqualTo(1);
        assertThat(response.get(0).latestBranchId()).isEqualTo("awb_001");
        assertThat(response.get(0).latestEvidencePacketId()).isEqualTo("evidence_001");
        assertThat(response.get(0).latestAuditResult()).isEqualTo("allowed");
    }

    @Test
    @DisplayName("getTaskRun returns stages branches artifacts evidence and audit detail")
    void getTaskRun_returnsConsoleDetail() {
        AgentStateService service = service();
        AgentTaskRunEntity task = new AgentTaskRunEntity();
        task.setId("task_001");
        task.setTenantId("tn_test001");
        task.setGoal("verify quicksort");
        task.setHarnessId("paperbench");
        task.setStatus("running");
        task.setCreatedAt(java.time.Instant.parse("2026-06-04T00:00:00Z"));
        AgentStageRunEntity stage = new AgentStageRunEntity();
        stage.setId("stage_001");
        stage.setTaskRunId("task_001");
        stage.setStageId("experiment_run");
        AgentWorkspaceEntity workspace = new AgentWorkspaceEntity();
        workspace.setId("ws_001");
        workspace.setTaskRunId("task_001");
        AgentWorkspaceBranchEntity root = new AgentWorkspaceBranchEntity();
        root.setId("awb_root");
        root.setName("root");
        AgentWorkspaceBranchEntity branch = new AgentWorkspaceBranchEntity();
        branch.setId("awb_001");
        branch.setName("branch");
        branch.setHypothesis("attempt 1");
        AgentStateCommitEntity commit = new AgentStateCommitEntity();
        commit.setId("commit_001");
        commit.setTaskRunId("task_001");
        commit.setStageRunId("stage_001");
        commit.setBranchId("awb_001");
        commit.setSummary("verification passed");
        AgentArtifactRefEntity artifact = new AgentArtifactRefEntity();
        artifact.setId("artifact_001");
        artifact.setTaskRunId("task_001");
        artifact.setStageRunId("stage_001");
        artifact.setBranchId("awb_001");
        artifact.setKind("experiment_run");
        AgentEvidencePacketEntity evidence = new AgentEvidencePacketEntity();
        evidence.setId("evidence_001");
        evidence.setTaskRunId("task_001");
        evidence.setBranchId("awb_001");
        evidence.setClaim("claim");
        evidence.setStatus("pending");
        evidence.setEvidenceRefsJson("[\"artifact_001\"]");
        AgentAuditEventEntity audit = new AgentAuditEventEntity();
        audit.setId("audit_001");
        audit.setTaskRunId("task_001");
        audit.setAction("paperbench_report_gate");
        audit.setResult("allowed");
        when(taskRunRepository.findByIdAndTenantId("task_001", "tn_test001")).thenReturn(Optional.of(task));
        when(stageRunRepository.findByTenantIdAndTaskRunIdOrderByCreatedAtAsc("tn_test001", "task_001")).thenReturn(List.of(stage));
        when(workspaceRepository.findByTenantIdAndTaskRunId("tn_test001", "task_001")).thenReturn(Optional.of(workspace));
        when(branchRepository.findByTenantIdAndWorkspaceIdOrderByCreatedAtAsc("tn_test001", "ws_001")).thenReturn(List.of(root, branch));
        when(stateCommitRepository.findByTenantIdAndTaskRunIdOrderByCreatedAtAsc("tn_test001", "task_001")).thenReturn(List.of(commit));
        when(artifactRefRepository.findByTenantIdAndTaskRunIdOrderByCreatedAtAsc("tn_test001", "task_001")).thenReturn(List.of(artifact));
        when(evidencePacketRepository.findByTenantIdAndTaskRunIdOrderByCreatedAtAsc("tn_test001", "task_001")).thenReturn(List.of(evidence));
        when(auditEventRepository.findByTenantIdAndTaskRunIdOrderByCreatedAtAsc("tn_test001", "task_001")).thenReturn(List.of(audit));

        AgentStateDtos.TaskRunDetailResponse response = service.getTaskRun("tn_test001", "task_001");

        assertThat(response.task().id()).isEqualTo("task_001");
        assertThat(response.workspace().rootBranchId()).isEqualTo("awb_root");
        assertThat(response.stages()).hasSize(1);
        assertThat(response.branches()).hasSize(2);
        assertThat(response.commits().get(0).summary()).isEqualTo("verification passed");
        assertThat(response.artifacts().get(0).kind()).isEqualTo("experiment_run");
        assertThat(response.evidencePackets().get(0).evidenceRefs()).containsExactly("artifact_001");
        assertThat(response.auditEvents().get(0).result()).isEqualTo("allowed");
    }

    @Test
    @DisplayName("createWorkspace persists workspace plus root branch for a tenant task")
    void createWorkspace_persistsWorkspaceAndRootBranch() {
        AgentStateService service = service();
        when(workspaceRepository.save(any(AgentWorkspaceEntity.class))).thenAnswer(inv -> {
            AgentWorkspaceEntity entity = inv.getArgument(0);
            entity.prePersist();
            return entity;
        });
        when(branchRepository.save(any(AgentWorkspaceBranchEntity.class))).thenAnswer(inv -> {
            AgentWorkspaceBranchEntity entity = inv.getArgument(0);
            entity.prePersist();
            return entity;
        });

        AgentStateDtos.WorkspaceResponse response = service.createWorkspace(
                "tn_test001", new AgentStateDtos.CreateWorkspaceRequest("task_001"));

        assertThat(response.id()).startsWith("ws_");
        assertThat(response.rootBranchId()).startsWith("awb_");

        ArgumentCaptor<AgentWorkspaceEntity> workspaceCaptor = ArgumentCaptor.forClass(AgentWorkspaceEntity.class);
        ArgumentCaptor<AgentWorkspaceBranchEntity> branchCaptor = ArgumentCaptor.forClass(AgentWorkspaceBranchEntity.class);
        verify(workspaceRepository).save(workspaceCaptor.capture());
        verify(branchRepository).save(branchCaptor.capture());
        assertThat(workspaceCaptor.getValue().getTenantId()).isEqualTo("tn_test001");
        assertThat(workspaceCaptor.getValue().getTaskRunId()).isEqualTo("task_001");
        assertThat(branchCaptor.getValue().getName()).isEqualTo("root");
    }

    @Test
    @DisplayName("resolveContext returns tenant scoped context node ids")
    void resolveContext_returnsTenantScopedNodeIds() {
        AgentStateService service = service();
        ContextNodeEntity table = new ContextNodeEntity();
        table.setId("schema_orders");
        ContextNodeEntity column = new ContextNodeEntity();
        column.setId("column_customer_email");
        when(contextNodeRepository.findByTenantIdOrderByCreatedAtAsc("tn_test001"))
                .thenReturn(List.of(table, column));

        AgentStateDtos.ResolveContextResponse response = service.resolveContext(
                "tn_test001",
                new AgentStateDtos.ResolveContextRequest("task_001", "stage_schema", "orders"));

        assertThat(response.nodeIds()).containsExactly("schema_orders", "column_customer_email");
    }

    @Test
    @DisplayName("ingestContextSource persists tenant-scoped context nodes")
    void ingestContextSource_persistsContextNodes() {
        AgentStateService service = service();
        when(contextNodeRepository.save(any(ContextNodeEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        AgentStateDtos.IngestContextResponse response = service.ingestContextSource(
                "tn_test001",
                new AgentStateDtos.IngestContextSourceRequest(
                        "dbt_manifest",
                        "fixtures/manifest.json",
                        List.of(new AgentStateDtos.ContextNodeInput("schema_orders", "table", "orders"))));

        assertThat(response.nodeIds()).containsExactly("schema_orders");
        verify(contextNodeRepository).save(any(ContextNodeEntity.class));
    }

    @Test
    @DisplayName("createCheckpoint stores manifest and restoreCheckpoint returns restorable and missing refs")
    void checkpointRestore_returnsResumePlan() {
        AgentStateService service = service();
        when(checkpointRepository.save(any(AgentCheckpointEntity.class))).thenAnswer(inv -> {
            AgentCheckpointEntity entity = inv.getArgument(0);
            entity.prePersist();
            return entity;
        });
        AgentCheckpointEntity checkpoint = new AgentCheckpointEntity();
        checkpoint.setId("ckpt_001");
        checkpoint.setManifestJson("{\"artifacts\":[\"artifact_sql_001\"],\"missing\":[\"lineage_snapshot_001\"]}");
        when(checkpointRepository.findByIdAndTenantId("ckpt_001", "tn_test001")).thenReturn(java.util.Optional.of(checkpoint));

        AgentStateDtos.CheckpointResponse checkpointResponse = service.createCheckpoint(
                "tn_test001",
                new AgentStateDtos.CreateCheckpointRequest(
                        "branch_001",
                        "stage_sql",
                        java.util.Map.of("artifacts", List.of("artifact_sql_001"))));
        AgentStateDtos.RestorePlanResponse restorePlan = service.restoreCheckpoint("tn_test001", "ckpt_001");

        assertThat(checkpointResponse.id()).startsWith("ckpt_");
        assertThat(restorePlan.restorableRefs()).containsExactly("artifact_sql_001");
        assertThat(restorePlan.missingRefs()).containsExactly("lineage_snapshot_001");
        assertThat(restorePlan.complete()).isFalse();
    }

    @Test
    @DisplayName("snapshotManifest stores OpenCode artifact ids as checkpoint manifest refs")
    void snapshotManifest_storesArtifactRefsForRestore() {
        AgentStateService service = service();
        when(checkpointRepository.save(any(AgentCheckpointEntity.class))).thenAnswer(inv -> {
            AgentCheckpointEntity entity = inv.getArgument(0);
            entity.prePersist();
            return entity;
        });

        AgentStateDtos.IdResponse response = service.snapshotManifest(
                "tn_test001",
                new AgentStateDtos.SnapshotManifestRequest(
                        "task_001",
                        "stage_sql",
                        "branch_001",
                        List.of("artifact_sql_001")));

        ArgumentCaptor<AgentCheckpointEntity> checkpointCaptor = ArgumentCaptor.forClass(AgentCheckpointEntity.class);
        verify(checkpointRepository).save(checkpointCaptor.capture());
        assertThat(response.id()).startsWith("ckpt_");
        assertThat(checkpointCaptor.getValue().getTenantId()).isEqualTo("tn_test001");
        assertThat(checkpointCaptor.getValue().getBranchId()).isEqualTo("branch_001");
        assertThat(checkpointCaptor.getValue().getStageRunId()).isEqualTo("stage_sql");
        assertThat(checkpointCaptor.getValue().getManifestJson()).contains("artifact_sql_001");
    }

    @Test
    @DisplayName("evaluateEvidence blocks packets without evidence refs")
    void evaluateEvidence_blocksMissingEvidenceRefs() {
        AgentStateService service = service();
        AgentEvidencePacketEntity packet = new AgentEvidencePacketEntity();
        packet.setId("evidence_001");
        packet.setEvidenceRefsJson("[]");
        when(evidencePacketRepository.findByIdAndTenantId("evidence_001", "tn_test001"))
                .thenReturn(java.util.Optional.of(packet));

        AgentStateDtos.PolicyDecisionResponse response = service.evaluateEvidence("tn_test001", "evidence_001");

        assertThat(response.allowed()).isFalse();
        assertThat(response.reason()).contains("missing verified evidence");
    }

    @Test
    @DisplayName("checkPermission blocks destructive or high-risk SQL and records decision")
    void checkPermission_blocksHighRiskActionAndPersistsDecision() {
        AgentStateService service = service();
        when(policyDecisionRepository.save(any(AgentPolicyDecisionEntity.class))).thenAnswer(inv -> {
            AgentPolicyDecisionEntity entity = inv.getArgument(0);
            entity.prePersist();
            return entity;
        });

        AgentStateDtos.PolicyDecisionResponse response = service.checkPermission(
                "tn_test001",
                new AgentStateDtos.CheckPermissionRequest(
                        "task_001", "drop table customers", "high", "branch_001"));

        assertThat(response.allowed()).isFalse();
        assertThat(response.reason()).contains("requires approval");
        verify(policyDecisionRepository).save(any(AgentPolicyDecisionEntity.class));
    }

    private AgentStateService service() {
        return new AgentStateService(
                taskRunRepository,
                agentAppRepository,
                stageRunRepository,
                workspaceRepository,
                branchRepository,
                contextNodeRepository,
                contextPackRepository,
                checkpointRepository,
                stateCommitRepository,
                artifactRefRepository,
                lineageEdgeRepository,
                evidencePacketRepository,
                policyDecisionRepository,
                auditEventRepository,
                new ObjectMapper());
    }
}
