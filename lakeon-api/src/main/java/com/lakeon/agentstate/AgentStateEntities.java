package com.lakeon.agentstate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@MappedSuperclass
abstract class AgentStateEntity {
    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    public void prePersist() {
        if (id == null) {
            id = idPrefix() + UUID.randomUUID().toString().substring(0, 8);
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    protected abstract String idPrefix();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

@Entity
@Table(name = "agent_app")
class AgentAppEntity extends AgentStateEntity {
    @Column(name = "app_key", nullable = false, length = 128)
    private String key;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Column(name = "type", nullable = false, length = 64)
    private String type = "custom";

    @Column(name = "version", nullable = false, length = 64)
    private String version = "0.1.0";

    @Column(name = "status", nullable = false, length = 32)
    private String status = "active";

    @Column(name = "stage_schema_json", columnDefinition = "TEXT")
    private String stageSchemaJson;

    @Override protected String idPrefix() { return "app_"; }
    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStageSchemaJson() { return stageSchemaJson; }
    public void setStageSchemaJson(String stageSchemaJson) { this.stageSchemaJson = stageSchemaJson; }
}

@Entity
@Table(name = "agent_task_run")
class AgentTaskRunEntity extends AgentStateEntity {
    @Column(name = "goal", nullable = false, columnDefinition = "TEXT")
    private String goal;

    @Column(name = "harness_id", nullable = false, length = 64)
    private String harnessId;

    @Column(name = "agent_app_id", length = 64)
    private String agentAppId;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "running";

    @Override protected String idPrefix() { return "task_"; }
    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }
    public String getHarnessId() { return harnessId; }
    public void setHarnessId(String harnessId) { this.harnessId = harnessId; }
    public String getAgentAppId() { return agentAppId; }
    public void setAgentAppId(String agentAppId) { this.agentAppId = agentAppId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}

@Entity
@Table(name = "agent_stage_run")
class AgentStageRunEntity extends AgentStateEntity {
    @Column(name = "task_run_id", nullable = false, length = 64)
    private String taskRunId;

    @Column(name = "stage_id", nullable = false, length = 128)
    private String stageId;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "running";

    @Column(name = "branch_id", length = 64)
    private String branchId;

    @Column(name = "context_pack_id", length = 64)
    private String contextPackId;

    @Override protected String idPrefix() { return "stage_"; }
    public String getTaskRunId() { return taskRunId; }
    public void setTaskRunId(String taskRunId) { this.taskRunId = taskRunId; }
    public String getStageId() { return stageId; }
    public void setStageId(String stageId) { this.stageId = stageId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getBranchId() { return branchId; }
    public void setBranchId(String branchId) { this.branchId = branchId; }
    public String getContextPackId() { return contextPackId; }
    public void setContextPackId(String contextPackId) { this.contextPackId = contextPackId; }
}

@Entity
@Table(name = "agent_workspace")
class AgentWorkspaceEntity extends AgentStateEntity {
    @Column(name = "task_run_id", nullable = false, length = 64)
    private String taskRunId;

    @Override protected String idPrefix() { return "ws_"; }
    public String getTaskRunId() { return taskRunId; }
    public void setTaskRunId(String taskRunId) { this.taskRunId = taskRunId; }
}

@Entity
@Table(name = "agent_workspace_branch")
class AgentWorkspaceBranchEntity extends AgentStateEntity {
    @Column(name = "workspace_id", nullable = false, length = 64)
    private String workspaceId;

    @Column(name = "parent_branch_id", length = 64)
    private String parentBranchId;

    @Column(name = "stage_run_id", length = 64)
    private String stageRunId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "hypothesis", columnDefinition = "TEXT")
    private String hypothesis;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "active";

    @Override protected String idPrefix() { return "awb_"; }
    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getParentBranchId() { return parentBranchId; }
    public void setParentBranchId(String parentBranchId) { this.parentBranchId = parentBranchId; }
    public String getStageRunId() { return stageRunId; }
    public void setStageRunId(String stageRunId) { this.stageRunId = stageRunId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getHypothesis() { return hypothesis; }
    public void setHypothesis(String hypothesis) { this.hypothesis = hypothesis; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}

@Entity
@Table(name = "context_node")
class ContextNodeEntity extends AgentStateEntity {
    @Column(name = "type", nullable = false, length = 64)
    private String type = "schema";

    @Column(name = "name", nullable = false, length = 255)
    private String name = "unnamed";

    @Column(name = "source_ref", length = 512)
    private String sourceRef;

    @Override protected String idPrefix() { return "ctx_node_"; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSourceRef() { return sourceRef; }
    public void setSourceRef(String sourceRef) { this.sourceRef = sourceRef; }
}

@Entity
@Table(name = "context_pack")
class ContextPackEntity extends AgentStateEntity {
    @Column(name = "task_run_id", nullable = false, length = 64)
    private String taskRunId;

    @Column(name = "stage_run_id", nullable = false, length = 64)
    private String stageRunId;

    @Column(name = "selected_nodes_json", columnDefinition = "TEXT")
    private String selectedNodesJson;

    @Override protected String idPrefix() { return "ctx_pack_"; }
    public String getTaskRunId() { return taskRunId; }
    public void setTaskRunId(String taskRunId) { this.taskRunId = taskRunId; }
    public String getStageRunId() { return stageRunId; }
    public void setStageRunId(String stageRunId) { this.stageRunId = stageRunId; }
    public String getSelectedNodesJson() { return selectedNodesJson; }
    public void setSelectedNodesJson(String selectedNodesJson) { this.selectedNodesJson = selectedNodesJson; }
}

@Entity
@Table(name = "agent_state_commit")
class AgentStateCommitEntity extends AgentStateEntity {
    @Column(name = "task_run_id", nullable = false, length = 64)
    private String taskRunId;

    @Column(name = "stage_run_id", nullable = false, length = 64)
    private String stageRunId;

    @Column(name = "branch_id", nullable = false, length = 64)
    private String branchId;

    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;

    @Override protected String idPrefix() { return "commit_"; }
    public String getTaskRunId() { return taskRunId; }
    public void setTaskRunId(String taskRunId) { this.taskRunId = taskRunId; }
    public String getStageRunId() { return stageRunId; }
    public void setStageRunId(String stageRunId) { this.stageRunId = stageRunId; }
    public String getBranchId() { return branchId; }
    public void setBranchId(String branchId) { this.branchId = branchId; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
}

@Entity
@Table(name = "agent_artifact_ref")
class AgentArtifactRefEntity extends AgentStateEntity {
    @Column(name = "task_run_id", nullable = false, length = 64)
    private String taskRunId;

    @Column(name = "stage_run_id", nullable = false, length = 64)
    private String stageRunId;

    @Column(name = "branch_id", nullable = false, length = 64)
    private String branchId;

    @Column(name = "kind", nullable = false, length = 64)
    private String kind;

    @Override protected String idPrefix() { return "artifact_"; }
    public String getTaskRunId() { return taskRunId; }
    public void setTaskRunId(String taskRunId) { this.taskRunId = taskRunId; }
    public String getStageRunId() { return stageRunId; }
    public void setStageRunId(String stageRunId) { this.stageRunId = stageRunId; }
    public String getBranchId() { return branchId; }
    public void setBranchId(String branchId) { this.branchId = branchId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
}

@Entity
@Table(name = "agent_lineage_edge")
class AgentLineageEdgeEntity extends AgentStateEntity {
    @Column(name = "task_run_id", nullable = false, length = 64)
    private String taskRunId;

    @Column(name = "stage_run_id", nullable = false, length = 64)
    private String stageRunId;

    @Column(name = "branch_id", nullable = false, length = 64)
    private String branchId;

    @Column(name = "artifact_id", nullable = false, length = 64)
    private String artifactId;

    @Override protected String idPrefix() { return "lineage_"; }
    public String getTaskRunId() { return taskRunId; }
    public void setTaskRunId(String taskRunId) { this.taskRunId = taskRunId; }
    public String getStageRunId() { return stageRunId; }
    public void setStageRunId(String stageRunId) { this.stageRunId = stageRunId; }
    public String getBranchId() { return branchId; }
    public void setBranchId(String branchId) { this.branchId = branchId; }
    public String getArtifactId() { return artifactId; }
    public void setArtifactId(String artifactId) { this.artifactId = artifactId; }
}

@Entity
@Table(name = "agent_checkpoint")
class AgentCheckpointEntity extends AgentStateEntity {
    @Column(name = "branch_id", nullable = false, length = 64)
    private String branchId;

    @Column(name = "stage_run_id", length = 64)
    private String stageRunId;

    @Column(name = "manifest_json", columnDefinition = "TEXT")
    private String manifestJson;

    @Override protected String idPrefix() { return "ckpt_"; }
    public String getBranchId() { return branchId; }
    public void setBranchId(String branchId) { this.branchId = branchId; }
    public String getStageRunId() { return stageRunId; }
    public void setStageRunId(String stageRunId) { this.stageRunId = stageRunId; }
    public String getManifestJson() { return manifestJson; }
    public void setManifestJson(String manifestJson) { this.manifestJson = manifestJson; }
}

@Entity
@Table(name = "agent_evidence_packet")
class AgentEvidencePacketEntity extends AgentStateEntity {
    @Column(name = "task_run_id", nullable = false, length = 64)
    private String taskRunId;

    @Column(name = "branch_id", length = 64)
    private String branchId;

    @Column(name = "claim", columnDefinition = "TEXT")
    private String claim;

    @Column(name = "status", nullable = false, length = 32)
    private String status = "pending";

    @Column(name = "evidence_refs_json", columnDefinition = "TEXT")
    private String evidenceRefsJson;

    @Override protected String idPrefix() { return "evidence_"; }
    public String getTaskRunId() { return taskRunId; }
    public void setTaskRunId(String taskRunId) { this.taskRunId = taskRunId; }
    public String getBranchId() { return branchId; }
    public void setBranchId(String branchId) { this.branchId = branchId; }
    public String getClaim() { return claim; }
    public void setClaim(String claim) { this.claim = claim; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getEvidenceRefsJson() { return evidenceRefsJson; }
    public void setEvidenceRefsJson(String evidenceRefsJson) { this.evidenceRefsJson = evidenceRefsJson; }
}

@Entity
@Table(name = "agent_policy_decision")
class AgentPolicyDecisionEntity extends AgentStateEntity {
    @Column(name = "task_run_id", nullable = false, length = 64)
    private String taskRunId;

    @Column(name = "branch_id", length = 64)
    private String branchId;

    @Column(name = "action", nullable = false, length = 128)
    private String action;

    @Column(name = "allowed", nullable = false)
    private boolean allowed;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Override protected String idPrefix() { return "policy_"; }
    public String getTaskRunId() { return taskRunId; }
    public void setTaskRunId(String taskRunId) { this.taskRunId = taskRunId; }
    public String getBranchId() { return branchId; }
    public void setBranchId(String branchId) { this.branchId = branchId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public boolean isAllowed() { return allowed; }
    public void setAllowed(boolean allowed) { this.allowed = allowed; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}

@Entity
@Table(name = "agent_audit_event")
class AgentAuditEventEntity extends AgentStateEntity {
    @Column(name = "task_run_id", nullable = false, length = 64)
    private String taskRunId;

    @Column(name = "branch_id", length = 64)
    private String branchId;

    @Column(name = "action", nullable = false, length = 128)
    private String action;

    @Column(name = "result", nullable = false, length = 64)
    private String result;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Override protected String idPrefix() { return "audit_"; }
    public String getTaskRunId() { return taskRunId; }
    public void setTaskRunId(String taskRunId) { this.taskRunId = taskRunId; }
    public String getBranchId() { return branchId; }
    public void setBranchId(String branchId) { this.branchId = branchId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
