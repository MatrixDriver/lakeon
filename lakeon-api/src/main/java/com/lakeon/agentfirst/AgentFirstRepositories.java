package com.lakeon.agentfirst;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface AgentTaskRunRepository extends JpaRepository<AgentTaskRunEntity, String> {}

interface AgentStageRunRepository extends JpaRepository<AgentStageRunEntity, String> {}

interface AgentWorkspaceRepository extends JpaRepository<AgentWorkspaceEntity, String> {}

interface AgentWorkspaceBranchRepository extends JpaRepository<AgentWorkspaceBranchEntity, String> {}

interface ContextNodeRepository extends JpaRepository<ContextNodeEntity, String> {
    List<ContextNodeEntity> findByTenantIdOrderByCreatedAtAsc(String tenantId);
}

interface ContextPackRepository extends JpaRepository<ContextPackEntity, String> {}

interface AgentStateCommitRepository extends JpaRepository<AgentStateCommitEntity, String> {}

interface AgentArtifactRefRepository extends JpaRepository<AgentArtifactRefEntity, String> {}

interface AgentLineageEdgeRepository extends JpaRepository<AgentLineageEdgeEntity, String> {}

interface AgentPolicyDecisionRepository extends JpaRepository<AgentPolicyDecisionEntity, String> {}

interface AgentAuditEventRepository extends JpaRepository<AgentAuditEventEntity, String> {
    List<AgentAuditEventEntity> findByTenantIdAndTaskRunIdOrderByCreatedAtAsc(String tenantId, String taskRunId);
}
