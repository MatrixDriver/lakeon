package com.lakeon.agentstate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.repository.DatabaseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
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
    @Mock private DatabaseRepository databaseRepository;
    @Mock private AgentStateDataPlaneStore dataPlaneStore;

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
    @DisplayName("listAgentApps registers built-in app templates when tenant has none")
    void listAgentApps_registersBuiltInTemplatesWhenEmpty() {
        AgentStateService service = service();
        when(agentAppRepository.findByTenantIdOrderByCreatedAtAsc("tn_test001")).thenReturn(List.of());
        when(agentAppRepository.save(any(AgentAppEntity.class))).thenAnswer(inv -> {
            AgentAppEntity entity = inv.getArgument(0);
            entity.prePersist();
            return entity;
        });

        List<AgentStateDtos.AgentAppResponse> response = service.listAgentApps("tn_test001");

        assertThat(response).extracting(AgentStateDtos.AgentAppResponse::key)
                .containsExactly("paperbench", "data");
        assertThat(response).extracting(AgentStateDtos.AgentAppResponse::displayName)
                .containsExactly("PaperBench 论文复现", "数据发布检查");
        assertThat(response.get(0).stageSchema()).contains("paper_parse", "report_gate");
        assertThat(response.get(1).stageSchema()).contains("schema_resolve", "publish_gate");

        ArgumentCaptor<AgentAppEntity> appCaptor = ArgumentCaptor.forClass(AgentAppEntity.class);
        verify(agentAppRepository, times(2)).save(appCaptor.capture());
        assertThat(appCaptor.getAllValues()).allSatisfy(app -> assertThat(app.getTenantId()).isEqualTo("tn_test001"));
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
        AgentWorkspaceEntity workspace = new AgentWorkspaceEntity();
        workspace.setId("ws_001");
        workspace.setTaskRunId("task_001");
        workspace.setDatabaseId("db_001");
        workspace.setRootBranchId("awb_root");
        AgentStateDtos.StageRunDetailResponse stageDetail = new AgentStateDtos.StageRunDetailResponse(
                "stage_001", "task_001", "experiment_run", "running", "awb_001", null, null);
        AgentStateDtos.BranchDetailResponse root = new AgentStateDtos.BranchDetailResponse(
                "awb_root", "ws_001", null, null, "root", null, "active", null);
        AgentStateDtos.BranchDetailResponse branch = new AgentStateDtos.BranchDetailResponse(
                "awb_001", "ws_001", "awb_root", "stage_001", "branch", null, "active", null);
        AgentStateDtos.EvidencePacketDetailResponse evidence = new AgentStateDtos.EvidencePacketDetailResponse(
                "evidence_001", "task_001", "awb_001", "claim", "pending", List.of("artifact_001"), null);
        AgentStateDtos.AuditEventDetailResponse audit = new AgentStateDtos.AuditEventDetailResponse(
                "audit_001", "task_001", "awb_001", "paperbench_report_gate", "allowed", null, null);
        AgentStateDtos.AuditEventDetailResponse traceAudit = new AgentStateDtos.AuditEventDetailResponse(
                "audit_trace", "task_001", "awb_001", "workflow_trace:report_gate", "started", null, null);
        when(taskRunRepository.findByTenantIdOrderByCreatedAtDesc("tn_test001")).thenReturn(List.of(task));
        when(workspaceRepository.findByTenantIdAndTaskRunId("tn_test001", "task_001")).thenReturn(Optional.of(workspace));
        DatabaseEntity database = database("db_001");
        when(databaseRepository.findByIdAndTenantId("db_001", "tn_test001")).thenReturn(Optional.of(database));
        when(dataPlaneStore.loadDetail(database, "task_001", workspace)).thenReturn(new AgentStateDtos.DataPlaneDetail(
                List.of(stageDetail), List.of(root, branch), List.of(), List.of(), List.of(evidence), List.of(audit, traceAudit)));

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
        assertThat(response.get(0).status()).isEqualTo("completed");
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
        AgentWorkspaceEntity workspace = new AgentWorkspaceEntity();
        workspace.setId("ws_001");
        workspace.setTaskRunId("task_001");
        workspace.setDatabaseId("db_001");
        workspace.setRootBranchId("awb_root");
        AgentStateDtos.StageRunDetailResponse stageDetail = new AgentStateDtos.StageRunDetailResponse(
                "stage_001", "task_001", "experiment_run", "running", "awb_001", null, null);
        AgentStateDtos.BranchDetailResponse root = new AgentStateDtos.BranchDetailResponse(
                "awb_root", "ws_001", null, null, "root", null, "active", null);
        AgentStateDtos.BranchDetailResponse branch = new AgentStateDtos.BranchDetailResponse(
                "awb_001", "ws_001", "awb_root", "stage_001", "branch", "attempt 1", "active", null);
        AgentStateDtos.StateCommitDetailResponse commit = new AgentStateDtos.StateCommitDetailResponse(
                "commit_001", "task_001", "stage_001", "awb_001", "verification passed", null);
        AgentStateDtos.ArtifactDetailResponse artifact = new AgentStateDtos.ArtifactDetailResponse(
                "artifact_001", "task_001", "stage_001", "awb_001", "experiment_run", null);
        AgentStateDtos.EvidencePacketDetailResponse evidence = new AgentStateDtos.EvidencePacketDetailResponse(
                "evidence_001", "task_001", "awb_001", "claim", "pending", List.of("artifact_001"), null);
        AgentStateDtos.AuditEventDetailResponse audit = new AgentStateDtos.AuditEventDetailResponse(
                "audit_001", "task_001", "awb_001", "paperbench_report_gate", "allowed", null, null);
        when(taskRunRepository.findByIdAndTenantId("task_001", "tn_test001")).thenReturn(Optional.of(task));
        when(workspaceRepository.findByTenantIdAndTaskRunId("tn_test001", "task_001")).thenReturn(Optional.of(workspace));
        DatabaseEntity database = database("db_001");
        when(databaseRepository.findByIdAndTenantId("db_001", "tn_test001")).thenReturn(Optional.of(database));
        when(dataPlaneStore.loadDetail(database, "task_001", workspace)).thenReturn(new AgentStateDtos.DataPlaneDetail(
                List.of(stageDetail), List.of(root, branch), List.of(commit), List.of(artifact), List.of(evidence), List.of(audit)));

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
        DatabaseEntity database = database("db_001");
        when(databaseRepository.findAllByTenantIdAndStatus("tn_test001", DatabaseStatus.RUNNING)).thenReturn(List.of(database));
        when(dataPlaneStore.createWorkspace(database, "task_001")).thenReturn(
                new AgentStateDataPlaneStore.DataPlaneWorkspace("ws_dp", "awb_root", "db_001", java.time.Instant.parse("2026-06-04T00:00:00Z")));
        when(workspaceRepository.save(any(AgentWorkspaceEntity.class))).thenAnswer(inv -> {
            AgentWorkspaceEntity entity = inv.getArgument(0);
            entity.prePersist();
            return entity;
        });

        AgentStateDtos.WorkspaceResponse response = service.createWorkspace(
                "tn_test001", new AgentStateDtos.CreateWorkspaceRequest("task_001"));

        assertThat(response.id()).isEqualTo("ws_dp");
        assertThat(response.rootBranchId()).isEqualTo("awb_root");

        ArgumentCaptor<AgentWorkspaceEntity> workspaceCaptor = ArgumentCaptor.forClass(AgentWorkspaceEntity.class);
        verify(workspaceRepository).save(workspaceCaptor.capture());
        assertThat(workspaceCaptor.getValue().getTenantId()).isEqualTo("tn_test001");
        assertThat(workspaceCaptor.getValue().getTaskRunId()).isEqualTo("task_001");
        assertThat(workspaceCaptor.getValue().getDatabaseId()).isEqualTo("db_001");
        assertThat(workspaceCaptor.getValue().getRootBranchId()).isEqualTo("awb_root");
    }

    @Test
    @DisplayName("forkBranch links new branches to the workspace root when parent is omitted")
    void forkBranch_defaultsParentToWorkspaceRoot() {
        AgentStateService service = service();
        AgentWorkspaceEntity workspace = workspace("ws_001", "task_001", "db_001", "awb_root");
        DatabaseEntity database = database("db_001");
        when(workspaceRepository.findById("ws_001")).thenReturn(Optional.of(workspace));
        when(databaseRepository.findByIdAndTenantId("db_001", "tn_test001")).thenReturn(Optional.of(database));
        when(dataPlaneStore.forkBranch(database, "ws_001", null, "stage_sql", "validate SQL")).thenReturn(
                new AgentStateDtos.BranchDetailResponse("awb_001", "ws_001", "awb_root", "stage_sql", "branch", "validate SQL", "active", null));

        AgentStateDtos.BranchResponse response = service.forkBranch(
                "tn_test001",
                new AgentStateDtos.ForkBranchRequest("ws_001", null, "stage_sql", "validate SQL"));

        assertThat(response.id()).isEqualTo("awb_001");
        verify(dataPlaneStore).forkBranch(database, "ws_001", null, "stage_sql", "validate SQL");
    }

    @Test
    @DisplayName("forkBranch honors an explicit parent branch id")
    void forkBranch_usesExplicitParentBranch() {
        AgentStateService service = service();
        AgentWorkspaceEntity workspace = workspace("ws_001", "task_001", "db_001", "awb_root");
        DatabaseEntity database = database("db_001");
        when(workspaceRepository.findById("ws_001")).thenReturn(Optional.of(workspace));
        when(databaseRepository.findByIdAndTenantId("db_001", "tn_test001")).thenReturn(Optional.of(database));
        when(dataPlaneStore.forkBranch(database, "ws_001", "awb_parent", "stage_sql", "validate SQL")).thenReturn(
                new AgentStateDtos.BranchDetailResponse("awb_002", "ws_001", "awb_parent", "stage_sql", "branch", "validate SQL", "active", null));

        service.forkBranch(
                "tn_test001",
                new AgentStateDtos.ForkBranchRequest("ws_001", "awb_parent", "stage_sql", "validate SQL"));

        verify(dataPlaneStore).forkBranch(database, "ws_001", "awb_parent", "stage_sql", "validate SQL");
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
        DatabaseEntity database = database("db_001");
        when(databaseRepository.findAllByTenantIdAndStatus("tn_test001", DatabaseStatus.RUNNING)).thenReturn(List.of(database));
        when(dataPlaneStore.appendManifestVersion(
                database,
                "checkpoint:branch_001",
                "branch_001",
                "stage_sql",
                "checkpoint",
                "checkpoint",
                Map.of("artifacts", List.of("artifact_sql_001"))))
                .thenReturn(new AgentStateDtos.IdResponse("ckpt_001"));
        when(dataPlaneStore.restorePlan(database, "ckpt_001")).thenReturn(Optional.of(
                new AgentStateDtos.RestorePlanResponse(
                        "ckpt_001",
                        List.of("artifact_sql_001"),
                        List.of("lineage_snapshot_001"),
                        false)));

        AgentStateDtos.CheckpointResponse checkpointResponse = service.createCheckpoint(
                "tn_test001",
                new AgentStateDtos.CreateCheckpointRequest(
                        "branch_001",
                        "stage_sql",
                        java.util.Map.of("artifacts", List.of("artifact_sql_001"))));
        AgentStateDtos.RestorePlanResponse restorePlan = service.restoreCheckpoint("tn_test001", "ckpt_001");

        assertThat(checkpointResponse.id()).isEqualTo("ckpt_001");
        assertThat(restorePlan.restorableRefs()).containsExactly("artifact_sql_001");
        assertThat(restorePlan.missingRefs()).containsExactly("lineage_snapshot_001");
        assertThat(restorePlan.complete()).isFalse();
    }

    @Test
    @DisplayName("snapshotManifest stores OpenCode artifact ids as checkpoint manifest refs")
    void snapshotManifest_storesArtifactRefsForRestore() {
        AgentStateService service = service();
        AgentWorkspaceEntity workspace = workspace("ws_001", "task_001", "db_001", "awb_root");
        DatabaseEntity database = database("db_001");
        when(workspaceRepository.findByTenantIdAndTaskRunId("tn_test001", "task_001")).thenReturn(Optional.of(workspace));
        when(databaseRepository.findByIdAndTenantId("db_001", "tn_test001")).thenReturn(Optional.of(database));
        when(dataPlaneStore.appendManifestVersion(
                any(DatabaseEntity.class),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()))
                .thenReturn(new AgentStateDtos.IdResponse("ver_manifest"));

        AgentStateDtos.IdResponse response = service.snapshotManifest(
                "tn_test001",
                new AgentStateDtos.SnapshotManifestRequest(
                        "task_001",
                        "stage_sql",
                        "branch_001",
                        List.of("artifact_sql_001")));

        ArgumentCaptor<Map<String, Object>> manifestCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dataPlaneStore).appendManifestVersion(
                org.mockito.ArgumentMatchers.eq(database),
                org.mockito.ArgumentMatchers.eq("task_001"),
                org.mockito.ArgumentMatchers.eq("branch_001"),
                org.mockito.ArgumentMatchers.eq("stage_sql"),
                org.mockito.ArgumentMatchers.eq("artifact_manifest"),
                org.mockito.ArgumentMatchers.eq("snapshot manifest"),
                manifestCaptor.capture());
        assertThat(response.id()).isEqualTo("ver_manifest");
        assertThat(manifestCaptor.getValue()).containsEntry("task_run_id", "task_001");
        assertThat(manifestCaptor.getValue()).containsEntry("branch_id", "branch_001");
        assertThat((List<String>) manifestCaptor.getValue().get("artifacts")).containsExactly("artifact_sql_001");
    }

    @Test
    @DisplayName("recordBranchVersion binds workspace, commit, artifacts, manifest, and lineage into a checkpoint manifest")
    void recordBranchVersion_storesUnifiedVersionManifest() {
        AgentStateService service = service();
        AgentWorkspaceEntity workspace = workspace("ws_001", "task_001", "db_001", "awb_root");
        DatabaseEntity database = database("db_001");
        when(workspaceRepository.findById("ws_001")).thenReturn(Optional.of(workspace));
        when(databaseRepository.findByIdAndTenantId("db_001", "tn_test001")).thenReturn(Optional.of(database));
        when(dataPlaneStore.appendManifestVersion(
                any(DatabaseEntity.class),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()))
                .thenReturn(new AgentStateDtos.IdResponse("ver_branch"));

        AgentStateDtos.IdResponse response = service.recordBranchVersion(
                "tn_test001",
                new AgentStateDtos.RecordBranchVersionRequest(
                        "ws_001",
                        "branch_001",
                        "stage_sql",
                        "commit_001",
                        List.of("artifact_sql_001"),
                        "manifest_001",
                        List.of("lineage_001"),
                        "validated SQL fixture"));

        ArgumentCaptor<Map<String, Object>> manifestCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dataPlaneStore).appendManifestVersion(
                org.mockito.ArgumentMatchers.eq(database),
                org.mockito.ArgumentMatchers.eq("task_001"),
                org.mockito.ArgumentMatchers.eq("branch_001"),
                org.mockito.ArgumentMatchers.eq("stage_sql"),
                org.mockito.ArgumentMatchers.eq("branch_version"),
                org.mockito.ArgumentMatchers.eq("validated SQL fixture"),
                manifestCaptor.capture());
        assertThat(response.id()).isEqualTo("ver_branch");
        assertThat(manifestCaptor.getValue()).containsEntry("workspace_id", "ws_001");
        assertThat(manifestCaptor.getValue()).containsEntry("state_commit_id", "commit_001");
        assertThat((List<String>) manifestCaptor.getValue().get("artifacts")).containsExactly("artifact_sql_001");
        assertThat(manifestCaptor.getValue()).containsEntry("manifest_id", "manifest_001");
        assertThat((List<String>) manifestCaptor.getValue().get("lineage_ids")).containsExactly("lineage_001");
    }

    @Test
    @DisplayName("recordRuntimeEvent stores OpenCode session and tool event manifests")
    void recordRuntimeEvent_storesSessionEventManifest() {
        AgentStateService service = service();
        DatabaseEntity database = database("db_001");
        when(databaseRepository.findAllByTenantIdAndStatus("tn_test001", DatabaseStatus.RUNNING)).thenReturn(List.of(database));
        when(dataPlaneStore.appendManifestVersion(
                any(DatabaseEntity.class),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()))
                .thenReturn(new AgentStateDtos.IdResponse("runtime_001"));

        AgentStateDtos.IdResponse response = service.recordRuntimeEvent(
                "tn_test001",
                new AgentStateDtos.RecordRuntimeEventRequest(
                        "tool_call_completed",
                        "ses_001",
                        "msg_001",
                        "call_001",
                        "edit",
                        null,
                        null,
                        null,
                        "completed",
                        "edited file",
                        Map.of("file_path", "src/app.ts"),
                        Map.of("hash", "sha256:test", "size", 12),
                        Map.of("path", "src/app.ts", "hash", "sha256:patch"),
                        Map.of("agent", "build")));

        ArgumentCaptor<Map<String, Object>> manifestCaptor = ArgumentCaptor.forClass(Map.class);
        verify(dataPlaneStore).appendManifestVersion(
                org.mockito.ArgumentMatchers.eq(database),
                org.mockito.ArgumentMatchers.eq("runtime:ses_001"),
                org.mockito.ArgumentMatchers.eq("session:ses_001"),
                org.mockito.ArgumentMatchers.eq("msg_001"),
                org.mockito.ArgumentMatchers.eq("runtime_event"),
                org.mockito.ArgumentMatchers.eq("edited file"),
                manifestCaptor.capture());
        assertThat(response.id()).isEqualTo("runtime_001");
        assertThat(manifestCaptor.getValue()).containsEntry("kind", "tool_call_completed");
        assertThat(manifestCaptor.getValue()).containsEntry("session_id", "ses_001");
        assertThat(manifestCaptor.getValue()).containsEntry("call_id", "call_001");
        assertThat((Map<String, Object>) manifestCaptor.getValue().get("artifact")).containsEntry("hash", "sha256:patch");
    }

    @Test
    @DisplayName("evaluateEvidence blocks packets without evidence refs")
    void evaluateEvidence_blocksMissingEvidenceRefs() {
        AgentStateService service = service();
        DatabaseEntity database = database("db_001");
        when(databaseRepository.findAllByTenantIdAndStatus("tn_test001", DatabaseStatus.RUNNING)).thenReturn(List.of(database));
        when(dataPlaneStore.findEvidencePacket(database, "evidence_001")).thenReturn(Optional.of(
                new AgentStateDtos.EvidencePacketDetailResponse(
                        "evidence_001",
                        "task_001",
                        "branch_001",
                        "claim",
                        "pending",
                        List.of(),
                        null)));

        AgentStateDtos.PolicyDecisionResponse response = service.evaluateEvidence("tn_test001", "evidence_001");

        assertThat(response.allowed()).isFalse();
        assertThat(response.reason()).contains("missing verified evidence");
    }

    @Test
    @DisplayName("listAuditEvents reads audit ids from data-plane versions")
    void listAuditEvents_readsDataPlaneAuditVersions() {
        AgentStateService service = service();
        AgentWorkspaceEntity workspace = workspace("ws_001", "task_001", "db_001", "awb_root");
        DatabaseEntity database = database("db_001");
        AgentStateDtos.AuditEventDetailResponse audit = new AgentStateDtos.AuditEventDetailResponse(
                "audit_001", "task_001", "branch_001", "paperbench_report_gate", "allowed", null, null);
        when(workspaceRepository.findByTenantIdAndTaskRunId("tn_test001", "task_001")).thenReturn(Optional.of(workspace));
        when(databaseRepository.findByIdAndTenantId("db_001", "tn_test001")).thenReturn(Optional.of(database));
        when(dataPlaneStore.loadDetail(database, "task_001", workspace)).thenReturn(new AgentStateDtos.DataPlaneDetail(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(audit)));

        List<AgentStateDtos.IdResponse> response = service.listAuditEvents("tn_test001", "task_001");

        assertThat(response).extracting(AgentStateDtos.IdResponse::id).containsExactly("audit_001");
        verify(dataPlaneStore).loadDetail(database, "task_001", workspace);
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
                databaseRepository,
                dataPlaneStore,
                new ObjectMapper());
    }

    private DatabaseEntity database(String id) {
        DatabaseEntity database = new DatabaseEntity();
        database.setId(id);
        database.setTenantId("tn_test001");
        database.setName("agent_state_db");
        database.setStatus(DatabaseStatus.RUNNING);
        database.setComputeHost("127.0.0.1");
        database.setComputePort(55433);
        return database;
    }

    private AgentWorkspaceEntity workspace(String id, String taskRunId, String databaseId, String rootBranchId) {
        AgentWorkspaceEntity workspace = new AgentWorkspaceEntity();
        workspace.setId(id);
        workspace.setTenantId("tn_test001");
        workspace.setTaskRunId(taskRunId);
        workspace.setDatabaseId(databaseId);
        workspace.setRootBranchId(rootBranchId);
        return workspace;
    }
}
