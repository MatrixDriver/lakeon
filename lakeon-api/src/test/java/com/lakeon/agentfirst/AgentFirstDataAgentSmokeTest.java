package com.lakeon.agentfirst;

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
@DisplayName("AgentFirst Data Agent smoke fixture")
class AgentFirstDataAgentSmokeTest {

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
        AgentFirstService service = service();
        String tenantId = "tn_data_agent_fixture";

        AgentFirstDtos.TaskRunResponse task = service.createTaskRun(
                tenantId,
                new AgentFirstDtos.CreateTaskRunRequest(
                        "publish daily_revenue_by_region dbt model",
                        "data"));
        AgentFirstDtos.StageRunResponse contextStage = service.createStageRun(
                tenantId,
                task.id(),
                new AgentFirstDtos.CreateStageRunRequest("context_pack", null, null));

        AgentFirstDtos.IngestContextResponse ingested = service.ingestContextSource(
                tenantId,
                new AgentFirstDtos.IngestContextSourceRequest(
                        "dbt_manifest",
                        "fixtures/data-agent/manifest.json",
                        List.of(
                                new AgentFirstDtos.ContextNodeInput("schema_orders", "table", "orders"),
                                new AgentFirstDtos.ContextNodeInput("schema_payments", "table", "payments"),
                                new AgentFirstDtos.ContextNodeInput("column_customer_email", "column", "customers.email"))));
        AgentFirstDtos.ResolveContextResponse resolved = service.resolveContext(
                tenantId,
                new AgentFirstDtos.ResolveContextRequest(task.id(), contextStage.id(), "daily revenue schema"));
        AgentFirstDtos.ContextPackResponse contextPack = service.buildContextPack(
                tenantId,
                new AgentFirstDtos.BuildContextPackRequest(task.id(), contextStage.id(), resolved.nodeIds()));

        AgentFirstDtos.WorkspaceResponse workspace = service.createWorkspace(
                tenantId,
                new AgentFirstDtos.CreateWorkspaceRequest(task.id()));
        AgentFirstDtos.StageRunResponse sqlStage = service.createStageRun(
                tenantId,
                task.id(),
                new AgentFirstDtos.CreateStageRunRequest("sql_validate", null, contextPack.id()));
        AgentFirstDtos.BranchResponse branch = service.forkBranch(
                tenantId,
                new AgentFirstDtos.ForkBranchRequest(
                        workspace.id(),
                        sqlStage.id(),
                        "safe aggregate without PII output"));

        AgentFirstDtos.IdResponse artifact = service.recordArtifact(
                tenantId,
                new AgentFirstDtos.RecordArtifactRequest(task.id(), sqlStage.id(), branch.id(), "compiled_sql"));
        AgentFirstDtos.IdResponse lineage = service.recordLineage(
                tenantId,
                new AgentFirstDtos.RecordLineageRequest(task.id(), sqlStage.id(), branch.id(), artifact.id()));
        service.appendStateCommit(
                tenantId,
                new AgentFirstDtos.AppendStateCommitRequest(
                        task.id(),
                        sqlStage.id(),
                        branch.id(),
                        "validated SQL with context pack " + contextPack.id()));

        AgentFirstDtos.CheckpointResponse checkpoint = service.createCheckpoint(
                tenantId,
                new AgentFirstDtos.CreateCheckpointRequest(
                        branch.id(),
                        sqlStage.id(),
                        Map.of("artifacts", List.of(artifact.id()), "missing", List.of())));
        AgentFirstDtos.RestorePlanResponse restorePlan = service.restoreCheckpoint(tenantId, checkpoint.id());

        AgentFirstDtos.EvidencePacketResponse missingEvidence = service.createEvidencePacket(
                tenantId,
                new AgentFirstDtos.CreateEvidencePacketRequest(
                        task.id(),
                        branch.id(),
                        "daily_revenue_by_region is publishable",
                        List.of()));
        AgentFirstDtos.PolicyDecisionResponse blocked = service.evaluateEvidence(tenantId, missingEvidence.id());

        AgentFirstDtos.EvidencePacketResponse verifiedEvidence = service.createEvidencePacket(
                tenantId,
                new AgentFirstDtos.CreateEvidencePacketRequest(
                        task.id(),
                        branch.id(),
                        "daily_revenue_by_region is publishable",
                        List.of(artifact.id(), lineage.id())));
        AgentFirstDtos.PolicyDecisionResponse allowed = service.evaluateEvidence(tenantId, verifiedEvidence.id());

        assertThat(ingested.nodeIds()).hasSize(3);
        assertThat(contextPack.id()).startsWith("ctx_pack_");
        assertThat(branch.id()).startsWith("awb_");
        assertThat(restorePlan.complete()).isTrue();
        assertThat(restorePlan.restorableRefs()).containsExactly(artifact.id());
        assertThat(blocked.allowed()).isFalse();
        assertThat(allowed.allowed()).isTrue();
    }

    private AgentFirstService service() {
        return new AgentFirstService(
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
