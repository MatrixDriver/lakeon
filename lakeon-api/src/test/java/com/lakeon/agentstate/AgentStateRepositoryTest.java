package com.lakeon.agentstate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@DisplayName("AgentStateRepository persistence tests")
class AgentStateRepositoryTest {

    @Autowired private AgentTaskRunRepository taskRunRepository;
    @Autowired private AgentWorkspaceRepository workspaceRepository;
    @Autowired private AgentWorkspaceBranchRepository branchRepository;
    @Autowired private ContextNodeRepository contextNodeRepository;
    @Autowired private AgentCheckpointRepository checkpointRepository;
    @Autowired private AgentEvidencePacketRepository evidencePacketRepository;

    @Test
    @DisplayName("saves task, workspace, root branch, and tenant scoped context nodes")
    void saveAgentStateState_generatesIdsAndSupportsTenantScopedContextLookup() {
        AgentTaskRunEntity task = new AgentTaskRunEntity();
        task.setTenantId("tn_test001");
        task.setGoal("publish dbt model");
        task.setHarnessId("data");
        AgentTaskRunEntity savedTask = taskRunRepository.save(task);

        AgentWorkspaceEntity workspace = new AgentWorkspaceEntity();
        workspace.setTenantId("tn_test001");
        workspace.setTaskRunId(savedTask.getId());
        AgentWorkspaceEntity savedWorkspace = workspaceRepository.save(workspace);

        AgentWorkspaceBranchEntity root = new AgentWorkspaceBranchEntity();
        root.setTenantId("tn_test001");
        root.setWorkspaceId(savedWorkspace.getId());
        root.setName("root");
        AgentWorkspaceBranchEntity savedRoot = branchRepository.save(root);

        ContextNodeEntity node = new ContextNodeEntity();
        node.setTenantId("tn_test001");
        node.setId("schema_orders");
        node.setName("orders");
        contextNodeRepository.save(node);

        List<ContextNodeEntity> nodes = contextNodeRepository.findByTenantIdOrderByCreatedAtAsc("tn_test001");

        assertThat(savedTask.getId()).startsWith("task_");
        assertThat(savedWorkspace.getId()).startsWith("ws_");
        assertThat(savedRoot.getId()).startsWith("awb_");
        assertThat(nodes).extracting(ContextNodeEntity::getId).containsExactly("schema_orders");
    }

    @Test
    @DisplayName("saves checkpoint and evidence packet with tenant lookups")
    void saveCheckpointAndEvidencePacket_supportsTenantScopedLookup() {
        AgentCheckpointEntity checkpoint = new AgentCheckpointEntity();
        checkpoint.setTenantId("tn_test001");
        checkpoint.setBranchId("branch_001");
        checkpoint.setManifestJson("{\"artifacts\":[\"artifact_sql_001\"]}");
        AgentCheckpointEntity savedCheckpoint = checkpointRepository.save(checkpoint);

        AgentEvidencePacketEntity evidence = new AgentEvidencePacketEntity();
        evidence.setTenantId("tn_test001");
        evidence.setTaskRunId("task_001");
        evidence.setBranchId("branch_001");
        evidence.setClaim("daily revenue SQL is publishable");
        evidence.setEvidenceRefsJson("[\"artifact_sql_001\"]");
        AgentEvidencePacketEntity savedEvidence = evidencePacketRepository.save(evidence);

        assertThat(savedCheckpoint.getId()).startsWith("ckpt_");
        assertThat(savedEvidence.getId()).startsWith("evidence_");
        assertThat(checkpointRepository.findByIdAndTenantId(savedCheckpoint.getId(), "tn_test001")).isPresent();
        assertThat(evidencePacketRepository.findByIdAndTenantId(savedEvidence.getId(), "tn_test001")).isPresent();
    }
}
