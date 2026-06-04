package com.lakeon.agentstate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentStateService unit tests")
class AgentStateServiceTest {

    @Mock private AgentTaskRunRepository taskRunRepository;
    @Mock private AgentAppRepository agentAppRepository;
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
                auditEventRepository);
    }
}
