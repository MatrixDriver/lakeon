package com.lakeon.agentfirst;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test")
@DisplayName("AgentFirstRepository persistence tests")
class AgentFirstRepositoryTest {

    @Autowired private AgentTaskRunRepository taskRunRepository;
    @Autowired private AgentWorkspaceRepository workspaceRepository;
    @Autowired private AgentWorkspaceBranchRepository branchRepository;
    @Autowired private ContextNodeRepository contextNodeRepository;

    @Test
    @DisplayName("saves task, workspace, root branch, and tenant scoped context nodes")
    void saveAgentFirstState_generatesIdsAndSupportsTenantScopedContextLookup() {
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
}
