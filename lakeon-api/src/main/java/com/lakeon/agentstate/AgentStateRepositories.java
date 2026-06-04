package com.lakeon.agentstate;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface AgentTaskRunRepository extends JpaRepository<AgentTaskRunEntity, String> {
    List<AgentTaskRunEntity> findByTenantIdOrderByCreatedAtDesc(String tenantId);
    java.util.Optional<AgentTaskRunEntity> findByIdAndTenantId(String id, String tenantId);
}

interface AgentAppRepository extends JpaRepository<AgentAppEntity, String> {
    List<AgentAppEntity> findByTenantIdOrderByCreatedAtAsc(String tenantId);
    java.util.Optional<AgentAppEntity> findByIdAndTenantId(String id, String tenantId);
}

interface AgentStageRunRepository extends JpaRepository<AgentStageRunEntity, String> {
    List<AgentStageRunEntity> findByTenantIdAndTaskRunIdOrderByCreatedAtAsc(String tenantId, String taskRunId);
}

interface AgentWorkspaceRepository extends JpaRepository<AgentWorkspaceEntity, String> {
    java.util.Optional<AgentWorkspaceEntity> findByTenantIdAndTaskRunId(String tenantId, String taskRunId);
}

interface AgentWorkspaceBranchRepository extends JpaRepository<AgentWorkspaceBranchEntity, String> {
    List<AgentWorkspaceBranchEntity> findByTenantIdAndWorkspaceIdOrderByCreatedAtAsc(String tenantId, String workspaceId);
}

interface ContextNodeRepository extends JpaRepository<ContextNodeEntity, String> {
    List<ContextNodeEntity> findByTenantIdOrderByCreatedAtAsc(String tenantId);
}

interface ContextPackRepository extends JpaRepository<ContextPackEntity, String> {}

interface AgentStateCommitRepository extends JpaRepository<AgentStateCommitEntity, String> {
    List<AgentStateCommitEntity> findByTenantIdAndTaskRunIdOrderByCreatedAtAsc(String tenantId, String taskRunId);
}

interface AgentArtifactRefRepository extends JpaRepository<AgentArtifactRefEntity, String> {
    List<AgentArtifactRefEntity> findByTenantIdAndTaskRunIdOrderByCreatedAtAsc(String tenantId, String taskRunId);
}

interface AgentLineageEdgeRepository extends JpaRepository<AgentLineageEdgeEntity, String> {}

interface AgentCheckpointRepository extends JpaRepository<AgentCheckpointEntity, String> {
    java.util.Optional<AgentCheckpointEntity> findByIdAndTenantId(String id, String tenantId);
}

interface AgentEvidencePacketRepository extends JpaRepository<AgentEvidencePacketEntity, String> {
    java.util.Optional<AgentEvidencePacketEntity> findByIdAndTenantId(String id, String tenantId);
    List<AgentEvidencePacketEntity> findByTenantIdAndTaskRunIdOrderByCreatedAtAsc(String tenantId, String taskRunId);
}

interface AgentPolicyDecisionRepository extends JpaRepository<AgentPolicyDecisionEntity, String> {}

interface AgentAuditEventRepository extends JpaRepository<AgentAuditEventEntity, String> {
    List<AgentAuditEventEntity> findByTenantIdAndTaskRunIdOrderByCreatedAtAsc(String tenantId, String taskRunId);
}
