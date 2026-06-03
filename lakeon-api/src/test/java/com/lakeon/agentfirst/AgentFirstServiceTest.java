package com.lakeon.agentfirst;

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
@DisplayName("AgentFirstService unit tests")
class AgentFirstServiceTest {

    @Mock private AgentTaskRunRepository taskRunRepository;
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
    @DisplayName("createWorkspace persists workspace plus root branch for a tenant task")
    void createWorkspace_persistsWorkspaceAndRootBranch() {
        AgentFirstService service = service();
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

        AgentFirstDtos.WorkspaceResponse response = service.createWorkspace(
                "tn_test001", new AgentFirstDtos.CreateWorkspaceRequest("task_001"));

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
        AgentFirstService service = service();
        ContextNodeEntity table = new ContextNodeEntity();
        table.setId("schema_orders");
        ContextNodeEntity column = new ContextNodeEntity();
        column.setId("column_customer_email");
        when(contextNodeRepository.findByTenantIdOrderByCreatedAtAsc("tn_test001"))
                .thenReturn(List.of(table, column));

        AgentFirstDtos.ResolveContextResponse response = service.resolveContext(
                "tn_test001",
                new AgentFirstDtos.ResolveContextRequest("task_001", "stage_schema", "orders"));

        assertThat(response.nodeIds()).containsExactly("schema_orders", "column_customer_email");
    }

    @Test
    @DisplayName("ingestContextSource persists tenant-scoped context nodes")
    void ingestContextSource_persistsContextNodes() {
        AgentFirstService service = service();
        when(contextNodeRepository.save(any(ContextNodeEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        AgentFirstDtos.IngestContextResponse response = service.ingestContextSource(
                "tn_test001",
                new AgentFirstDtos.IngestContextSourceRequest(
                        "dbt_manifest",
                        "fixtures/manifest.json",
                        List.of(new AgentFirstDtos.ContextNodeInput("schema_orders", "table", "orders"))));

        assertThat(response.nodeIds()).containsExactly("schema_orders");
        verify(contextNodeRepository).save(any(ContextNodeEntity.class));
    }

    @Test
    @DisplayName("createCheckpoint stores manifest and restoreCheckpoint returns restorable and missing refs")
    void checkpointRestore_returnsResumePlan() {
        AgentFirstService service = service();
        when(checkpointRepository.save(any(AgentCheckpointEntity.class))).thenAnswer(inv -> {
            AgentCheckpointEntity entity = inv.getArgument(0);
            entity.prePersist();
            return entity;
        });
        AgentCheckpointEntity checkpoint = new AgentCheckpointEntity();
        checkpoint.setId("ckpt_001");
        checkpoint.setManifestJson("{\"artifacts\":[\"artifact_sql_001\"],\"missing\":[\"lineage_snapshot_001\"]}");
        when(checkpointRepository.findByIdAndTenantId("ckpt_001", "tn_test001")).thenReturn(java.util.Optional.of(checkpoint));

        AgentFirstDtos.CheckpointResponse checkpointResponse = service.createCheckpoint(
                "tn_test001",
                new AgentFirstDtos.CreateCheckpointRequest(
                        "branch_001",
                        "stage_sql",
                        java.util.Map.of("artifacts", List.of("artifact_sql_001"))));
        AgentFirstDtos.RestorePlanResponse restorePlan = service.restoreCheckpoint("tn_test001", "ckpt_001");

        assertThat(checkpointResponse.id()).startsWith("ckpt_");
        assertThat(restorePlan.restorableRefs()).containsExactly("artifact_sql_001");
        assertThat(restorePlan.missingRefs()).containsExactly("lineage_snapshot_001");
        assertThat(restorePlan.complete()).isFalse();
    }

    @Test
    @DisplayName("evaluateEvidence blocks packets without evidence refs")
    void evaluateEvidence_blocksMissingEvidenceRefs() {
        AgentFirstService service = service();
        AgentEvidencePacketEntity packet = new AgentEvidencePacketEntity();
        packet.setId("evidence_001");
        packet.setEvidenceRefsJson("[]");
        when(evidencePacketRepository.findByIdAndTenantId("evidence_001", "tn_test001"))
                .thenReturn(java.util.Optional.of(packet));

        AgentFirstDtos.PolicyDecisionResponse response = service.evaluateEvidence("tn_test001", "evidence_001");

        assertThat(response.allowed()).isFalse();
        assertThat(response.reason()).contains("missing verified evidence");
    }

    @Test
    @DisplayName("checkPermission blocks destructive or high-risk SQL and records decision")
    void checkPermission_blocksHighRiskActionAndPersistsDecision() {
        AgentFirstService service = service();
        when(policyDecisionRepository.save(any(AgentPolicyDecisionEntity.class))).thenAnswer(inv -> {
            AgentPolicyDecisionEntity entity = inv.getArgument(0);
            entity.prePersist();
            return entity;
        });

        AgentFirstDtos.PolicyDecisionResponse response = service.checkPermission(
                "tn_test001",
                new AgentFirstDtos.CheckPermissionRequest(
                        "task_001", "drop table customers", "high", "branch_001"));

        assertThat(response.allowed()).isFalse();
        assertThat(response.reason()).contains("requires approval");
        verify(policyDecisionRepository).save(any(AgentPolicyDecisionEntity.class));
    }

    private AgentFirstService service() {
        return new AgentFirstService(
                taskRunRepository,
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
