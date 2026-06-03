package com.lakeon.agentstate;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        AgentStateService.class,
        AgentStateServiceSpringContextTest.MockRepositoryConfig.class
})
@DisplayName("AgentStateService Spring context tests")
class AgentStateServiceSpringContextTest {

    @Autowired
    private AgentStateService service;

    @Test
    @DisplayName("Spring can instantiate service with repository dependencies")
    void contextInstantiatesAgentStateService() {
        assertThat(service).isNotNull();
    }

    @Configuration
    static class MockRepositoryConfig {
        @Bean AgentTaskRunRepository taskRunRepository() { return Mockito.mock(AgentTaskRunRepository.class); }
        @Bean AgentStageRunRepository stageRunRepository() { return Mockito.mock(AgentStageRunRepository.class); }
        @Bean AgentWorkspaceRepository workspaceRepository() { return Mockito.mock(AgentWorkspaceRepository.class); }
        @Bean AgentWorkspaceBranchRepository branchRepository() { return Mockito.mock(AgentWorkspaceBranchRepository.class); }
        @Bean ContextNodeRepository contextNodeRepository() { return Mockito.mock(ContextNodeRepository.class); }
        @Bean ContextPackRepository contextPackRepository() { return Mockito.mock(ContextPackRepository.class); }
        @Bean AgentCheckpointRepository checkpointRepository() { return Mockito.mock(AgentCheckpointRepository.class); }
        @Bean AgentStateCommitRepository stateCommitRepository() { return Mockito.mock(AgentStateCommitRepository.class); }
        @Bean AgentArtifactRefRepository artifactRefRepository() { return Mockito.mock(AgentArtifactRefRepository.class); }
        @Bean AgentLineageEdgeRepository lineageEdgeRepository() { return Mockito.mock(AgentLineageEdgeRepository.class); }
        @Bean AgentEvidencePacketRepository evidencePacketRepository() { return Mockito.mock(AgentEvidencePacketRepository.class); }
        @Bean AgentPolicyDecisionRepository policyDecisionRepository() { return Mockito.mock(AgentPolicyDecisionRepository.class); }
        @Bean AgentAuditEventRepository auditEventRepository() { return Mockito.mock(AgentAuditEventRepository.class); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }
}
