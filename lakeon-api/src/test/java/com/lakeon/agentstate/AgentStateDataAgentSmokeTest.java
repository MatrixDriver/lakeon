package com.lakeon.agentstate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@DisplayName("AgentState Data Agent smoke fixture")
class AgentStateDataAgentSmokeTest {

    @Autowired private AgentTaskRunRepository taskRunRepository;
    @Autowired private AgentStageRunRepository stageRunRepository;
    @Autowired private AgentWorkspaceRepository workspaceRepository;
    @Autowired private AgentWorkspaceBranchRepository branchRepository;
    @Autowired private ContextNodeRepository contextNodeRepository;
    @Autowired private ContextPackRepository contextPackRepository;
    @Autowired private AgentCheckpointRepository checkpointRepository;
    @Autowired private AgentStateCommitRepository stateCommitRepository;
    @Autowired private AgentArtifactRefRepository artifactRefRepository;
    @Autowired private AgentLineageEdgeRepository lineageEdgeRepository;
    @Autowired private AgentEvidencePacketRepository evidencePacketRepository;
    @Autowired private AgentPolicyDecisionRepository policyDecisionRepository;
    @Autowired private AgentAuditEventRepository auditEventRepository;

    @Test
    @DisplayName("fixture: SQL/dbt publish flow requires context, branch state, checkpoint, and evidence gate")
    void dataAgentFixture_runsStateClosure() {
        AgentStateService service = service();
        String tenantId = "tn_data_agent_fixture";

        AgentStateDtos.TaskRunResponse task = service.createTaskRun(
                tenantId,
                new AgentStateDtos.CreateTaskRunRequest(
                        "publish daily_revenue_by_region dbt model",
                        "data"));
        AgentStateDtos.StageRunResponse contextStage = service.createStageRun(
                tenantId,
                task.id(),
                new AgentStateDtos.CreateStageRunRequest("context_pack", null, null));

        AgentStateDtos.IngestContextResponse ingested = service.ingestContextSource(
                tenantId,
                new AgentStateDtos.IngestContextSourceRequest(
                        "dbt_manifest",
                        "fixtures/data-agent/manifest.json",
                        List.of(
                                new AgentStateDtos.ContextNodeInput("schema_orders", "table", "orders"),
                                new AgentStateDtos.ContextNodeInput("schema_payments", "table", "payments"),
                                new AgentStateDtos.ContextNodeInput("column_customer_email", "column", "customers.email"))));
        AgentStateDtos.ResolveContextResponse resolved = service.resolveContext(
                tenantId,
                new AgentStateDtos.ResolveContextRequest(task.id(), contextStage.id(), "daily revenue schema"));
        AgentStateDtos.ContextPackResponse contextPack = service.buildContextPack(
                tenantId,
                new AgentStateDtos.BuildContextPackRequest(task.id(), contextStage.id(), resolved.nodeIds()));

        AgentStateDtos.WorkspaceResponse workspace = service.createWorkspace(
                tenantId,
                new AgentStateDtos.CreateWorkspaceRequest(task.id()));
        AgentStateDtos.StageRunResponse sqlStage = service.createStageRun(
                tenantId,
                task.id(),
                new AgentStateDtos.CreateStageRunRequest("sql_validate", null, contextPack.id()));
        AgentStateDtos.BranchResponse branch = service.forkBranch(
                tenantId,
                new AgentStateDtos.ForkBranchRequest(
                        workspace.id(),
                        sqlStage.id(),
                        "safe aggregate without PII output"));

        AgentStateDtos.IdResponse artifact = service.recordArtifact(
                tenantId,
                new AgentStateDtos.RecordArtifactRequest(task.id(), sqlStage.id(), branch.id(), "compiled_sql"));
        AgentStateDtos.IdResponse lineage = service.recordLineage(
                tenantId,
                new AgentStateDtos.RecordLineageRequest(task.id(), sqlStage.id(), branch.id(), artifact.id()));
        service.appendStateCommit(
                tenantId,
                new AgentStateDtos.AppendStateCommitRequest(
                        task.id(),
                        sqlStage.id(),
                        branch.id(),
                        "validated SQL with context pack " + contextPack.id()));

        AgentStateDtos.CheckpointResponse checkpoint = service.createCheckpoint(
                tenantId,
                new AgentStateDtos.CreateCheckpointRequest(
                        branch.id(),
                        sqlStage.id(),
                        Map.of("artifacts", List.of(artifact.id()), "missing", List.of())));
        AgentStateDtos.RestorePlanResponse restorePlan = service.restoreCheckpoint(tenantId, checkpoint.id());

        AgentStateDtos.EvidencePacketResponse missingEvidence = service.createEvidencePacket(
                tenantId,
                new AgentStateDtos.CreateEvidencePacketRequest(
                        task.id(),
                        branch.id(),
                        "daily_revenue_by_region is publishable",
                        List.of()));
        AgentStateDtos.PolicyDecisionResponse blocked = service.evaluateEvidence(tenantId, missingEvidence.id());

        AgentStateDtos.EvidencePacketResponse verifiedEvidence = service.createEvidencePacket(
                tenantId,
                new AgentStateDtos.CreateEvidencePacketRequest(
                        task.id(),
                        branch.id(),
                        "daily_revenue_by_region is publishable",
                        List.of(artifact.id(), lineage.id())));
        AgentStateDtos.PolicyDecisionResponse allowed = service.evaluateEvidence(tenantId, verifiedEvidence.id());

        assertThat(ingested.nodeIds()).hasSize(3);
        assertThat(contextPack.id()).startsWith("ctx_pack_");
        assertThat(branch.id()).startsWith("awb_");
        assertThat(restorePlan.complete()).isTrue();
        assertThat(restorePlan.restorableRefs()).containsExactly(artifact.id());
        assertThat(blocked.allowed()).isFalse();
        assertThat(allowed.allowed()).isTrue();
    }

    private AgentStateService service() {
        return new AgentStateService(
                taskRunRepository,
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
