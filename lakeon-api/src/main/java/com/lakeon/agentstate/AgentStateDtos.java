package com.lakeon.agentstate;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AgentStateDtos {
    private AgentStateDtos() {}

    public record CreateAgentAppRequest(
            @NotBlank String key,
            @JsonProperty("display_name") @JsonAlias("displayName") @NotBlank String displayName,
            String type,
            String version,
            String status,
            @JsonProperty("stage_schema") @JsonAlias("stageSchema") List<String> stageSchema) {}

    public record AgentAppResponse(
            String id,
            String key,
            @JsonProperty("display_name") String displayName,
            String type,
            String version,
            String status,
            @JsonProperty("stage_schema") List<String> stageSchema) {
        @JsonProperty("displayName")
        public String displayNameCamel() {
            return displayName;
        }

        @JsonProperty("stageSchema")
        public List<String> stageSchemaCamel() {
            return stageSchema;
        }
    }

    public record CreateTaskRunRequest(
            @NotBlank String goal,
            @JsonProperty("harness_id") @JsonAlias("harnessId") @NotBlank String harnessId,
            @JsonProperty("agent_app_id") @JsonAlias("agentAppId") String agentAppId) {
        public CreateTaskRunRequest(String goal, String harnessId) {
            this(goal, harnessId, null);
        }
    }

    public record CreateAgentAppRunRequest(
            @NotBlank String goal,
            @JsonProperty("harness_id") @JsonAlias("harnessId") String harnessId) {}

    public record TaskRunResponse(
            String id,
            @JsonProperty("harness_id") String harnessId,
            String status,
            @JsonProperty("agent_app_id") String agentAppId) {
        public TaskRunResponse(String id, String harnessId, String status) {
            this(id, harnessId, status, null);
        }

        @JsonProperty("agentAppId")
        public String agentAppIdCamel() {
            return agentAppId;
        }
    }

    public record TaskRunSummaryResponse(
            String id,
            String goal,
            @JsonProperty("harness_id") String harnessId,
            String status,
            @JsonProperty("agent_app_id") String agentAppId,
            @JsonProperty("current_stage_id") String currentStageId,
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("branch_count") long branchCount,
            @JsonProperty("evidence_count") long evidenceCount,
            @JsonProperty("latest_branch_id") String latestBranchId,
            @JsonProperty("latest_evidence_packet_id") String latestEvidencePacketId,
            @JsonProperty("latest_audit_result") String latestAuditResult,
            @JsonProperty("created_at") Instant createdAt) {
        @JsonProperty("harnessId")
        public String harnessIdCamel() { return harnessId; }
        @JsonProperty("agentAppId")
        public String agentAppIdCamel() { return agentAppId; }
        @JsonProperty("currentStageId")
        public String currentStageIdCamel() { return currentStageId; }
        @JsonProperty("workspaceId")
        public String workspaceIdCamel() { return workspaceId; }
        @JsonProperty("branchCount")
        public long branchCountCamel() { return branchCount; }
        @JsonProperty("evidenceCount")
        public long evidenceCountCamel() { return evidenceCount; }
        @JsonProperty("latestBranchId")
        public String latestBranchIdCamel() { return latestBranchId; }
        @JsonProperty("latestEvidencePacketId")
        public String latestEvidencePacketIdCamel() { return latestEvidencePacketId; }
        @JsonProperty("latestAuditResult")
        public String latestAuditResultCamel() { return latestAuditResult; }
        @JsonProperty("createdAt")
        public Instant createdAtCamel() { return createdAt; }
    }

    public record TaskRunDetailResponse(
            TaskRunSummaryResponse task,
            List<StageRunDetailResponse> stages,
            WorkspaceDetailResponse workspace,
            List<BranchDetailResponse> branches,
            List<StateCommitDetailResponse> commits,
            List<ArtifactDetailResponse> artifacts,
            @JsonProperty("evidence_packets") List<EvidencePacketDetailResponse> evidencePackets,
            @JsonProperty("audit_events") List<AuditEventDetailResponse> auditEvents) {
        @JsonProperty("evidencePackets")
        public List<EvidencePacketDetailResponse> evidencePacketsCamel() { return evidencePackets; }
        @JsonProperty("auditEvents")
        public List<AuditEventDetailResponse> auditEventsCamel() { return auditEvents; }
    }

    public record CreateStageRunRequest(
            @JsonProperty("stage_id") @NotBlank String stageId,
            @JsonProperty("branch_id") String branchId,
            @JsonProperty("context_pack_id") String contextPackId) {}

    public record StageRunResponse(
            String id,
            @JsonProperty("task_run_id") String taskRunId,
            @JsonProperty("stage_id") String stageId,
            String status,
            @JsonProperty("branch_id") String branchId,
            @JsonProperty("context_pack_id") String contextPackId) {}

    public record StageRunDetailResponse(
            String id,
            @JsonProperty("task_run_id") String taskRunId,
            @JsonProperty("stage_id") String stageId,
            String status,
            @JsonProperty("branch_id") String branchId,
            @JsonProperty("context_pack_id") String contextPackId,
            @JsonProperty("created_at") Instant createdAt) {
        @JsonProperty("taskRunId")
        public String taskRunIdCamel() { return taskRunId; }
        @JsonProperty("stageId")
        public String stageIdCamel() { return stageId; }
        @JsonProperty("branchId")
        public String branchIdCamel() { return branchId; }
        @JsonProperty("contextPackId")
        public String contextPackIdCamel() { return contextPackId; }
        @JsonProperty("createdAt")
        public Instant createdAtCamel() { return createdAt; }
    }

    public record CreateWorkspaceRequest(
            @JsonProperty("task_run_id") @JsonAlias("taskRunId") @NotBlank String taskRunId) {}

    public record WorkspaceResponse(
            String id,
            @JsonProperty("root_branch_id") String rootBranchId) {
        @JsonProperty("rootBranchId")
        public String rootBranchIdCamel() {
            return rootBranchId;
        }
    }

    public record WorkspaceDetailResponse(
            String id,
            @JsonProperty("task_run_id") String taskRunId,
            @JsonProperty("root_branch_id") String rootBranchId,
            @JsonProperty("created_at") Instant createdAt) {
        @JsonProperty("taskRunId")
        public String taskRunIdCamel() { return taskRunId; }
        @JsonProperty("rootBranchId")
        public String rootBranchIdCamel() { return rootBranchId; }
        @JsonProperty("createdAt")
        public Instant createdAtCamel() { return createdAt; }
    }

    public record ForkBranchRequest(
            @JsonProperty("workspace_id") @JsonAlias("workspaceId") @NotBlank String workspaceId,
            @JsonProperty("stage_run_id") @JsonAlias("stageRunId") String stageRunId,
            String hypothesis) {}

    public record BranchResponse(String id) {}

    public record BranchDetailResponse(
            String id,
            @JsonProperty("workspace_id") String workspaceId,
            @JsonProperty("parent_branch_id") String parentBranchId,
            @JsonProperty("stage_run_id") String stageRunId,
            String name,
            String hypothesis,
            String status,
            @JsonProperty("created_at") Instant createdAt) {
        @JsonProperty("workspaceId")
        public String workspaceIdCamel() { return workspaceId; }
        @JsonProperty("parentBranchId")
        public String parentBranchIdCamel() { return parentBranchId; }
        @JsonProperty("stageRunId")
        public String stageRunIdCamel() { return stageRunId; }
        @JsonProperty("createdAt")
        public Instant createdAtCamel() { return createdAt; }
    }

    public record ContextNodeInput(
            @NotBlank String id,
            @NotBlank String type,
            @NotBlank String name) {}

    public record IngestContextSourceRequest(
            @JsonProperty("source_type") @NotBlank String sourceType,
            @JsonProperty("source_ref") @NotBlank String sourceRef,
            List<ContextNodeInput> nodes) {}

    public record IngestContextResponse(
            @JsonProperty("node_ids") List<String> nodeIds) {
        @JsonProperty("nodeIds")
        public List<String> nodeIdsCamel() {
            return nodeIds;
        }
    }

    public record ResolveContextRequest(
            @JsonProperty("task_run_id") @JsonAlias("taskRunId") @NotBlank String taskRunId,
            @JsonProperty("stage_run_id") @JsonAlias("stageRunId") @NotBlank String stageRunId,
            String query) {}

    public record ResolveContextResponse(
            @JsonProperty("node_ids") List<String> nodeIds) {
        @JsonProperty("nodeIds")
        public List<String> nodeIdsCamel() {
            return nodeIds;
        }
    }

    public record BuildContextPackRequest(
            @JsonProperty("task_run_id") @JsonAlias("taskRunId") @NotBlank String taskRunId,
            @JsonProperty("stage_run_id") @JsonAlias("stageRunId") @NotBlank String stageRunId,
            @JsonProperty("selected_node_ids") @JsonAlias("selectedNodeIds") List<String> selectedNodeIds) {}

    public record ContextPackResponse(String id) {}

    public record AppendStateCommitRequest(
            @JsonProperty("task_run_id") @JsonAlias("taskRunId") @NotBlank String taskRunId,
            @JsonProperty("stage_run_id") @JsonAlias("stageRunId") @NotBlank String stageRunId,
            @JsonProperty("branch_id") @JsonAlias("branchId") @NotBlank String branchId,
            String summary) {}

    public record RecordArtifactRequest(
            @JsonProperty("task_run_id") @JsonAlias("taskRunId") @NotBlank String taskRunId,
            @JsonProperty("stage_run_id") @JsonAlias("stageRunId") @NotBlank String stageRunId,
            @JsonProperty("branch_id") @JsonAlias("branchId") @NotBlank String branchId,
            @NotBlank String kind) {}

    public record RecordLineageRequest(
            @JsonProperty("task_run_id") @JsonAlias("taskRunId") @NotBlank String taskRunId,
            @JsonProperty("stage_run_id") @JsonAlias("stageRunId") @NotBlank String stageRunId,
            @JsonProperty("branch_id") @JsonAlias("branchId") @NotBlank String branchId,
            @JsonProperty("artifact_id") @JsonAlias("artifactId") @NotBlank String artifactId) {}

    public record CreateCheckpointRequest(
            @JsonProperty("branch_id") @JsonAlias("branchId") @NotBlank String branchId,
            @JsonProperty("stage_run_id") @JsonAlias("stageRunId") String stageRunId,
            Map<String, Object> manifest) {}

    public record SnapshotManifestRequest(
            @JsonProperty("task_run_id") @JsonAlias("taskRunId") @NotBlank String taskRunId,
            @JsonProperty("stage_run_id") @JsonAlias("stageRunId") @NotBlank String stageRunId,
            @JsonProperty("branch_id") @JsonAlias("branchId") @NotBlank String branchId,
            @JsonProperty("artifact_ids") @JsonAlias("artifactIds") List<String> artifactIds) {}

    public record CheckpointResponse(String id) {}

    public record RecordBranchVersionRequest(
            @JsonProperty("workspace_id") @JsonAlias("workspaceId") @NotBlank String workspaceId,
            @JsonProperty("branch_id") @JsonAlias("branchId") @NotBlank String branchId,
            @JsonProperty("stage_run_id") @JsonAlias("stageRunId") @NotBlank String stageRunId,
            @JsonProperty("state_commit_id") @JsonAlias("stateCommitId") @NotBlank String stateCommitId,
            @JsonProperty("artifact_ids") @JsonAlias("artifactIds") List<String> artifactIds,
            @JsonProperty("manifest_id") @JsonAlias("manifestId") @NotBlank String manifestId,
            @JsonProperty("lineage_ids") @JsonAlias("lineageIds") List<String> lineageIds,
            String summary) {}

    public record RecordRuntimeEventRequest(
            @NotBlank String kind,
            @JsonProperty("session_id") @JsonAlias("sessionId") @NotBlank String sessionId,
            @JsonProperty("message_id") @JsonAlias("messageId") String messageId,
            @JsonProperty("call_id") @JsonAlias("callId") String callId,
            String tool,
            @JsonProperty("parent_session_id") @JsonAlias("parentSessionId") String parentSessionId,
            @JsonProperty("child_session_id") @JsonAlias("childSessionId") String childSessionId,
            @JsonProperty("branch_id") @JsonAlias("branchId") String branchId,
            String status,
            String summary,
            Map<String, Object> input,
            Map<String, Object> output,
            Map<String, Object> artifact,
            Map<String, Object> metadata) {}

    public record RestorePlanResponse(
            @JsonProperty("checkpoint_id") String checkpointId,
            @JsonProperty("restorable_refs") List<String> restorableRefs,
            @JsonProperty("missing_refs") List<String> missingRefs,
            boolean complete) {}

    public record CreateEvidencePacketRequest(
            @JsonProperty("task_run_id") @JsonAlias("taskRunId") @NotBlank String taskRunId,
            @JsonProperty("branch_id") @JsonAlias("branchId") String branchId,
            @NotBlank String claim,
            @JsonProperty("evidence_refs") @JsonAlias("evidenceRefs") List<String> evidenceRefs) {}

    public record EvidencePacketResponse(String id, String status) {}

    public record StateCommitDetailResponse(
            String id,
            @JsonProperty("task_run_id") String taskRunId,
            @JsonProperty("stage_run_id") String stageRunId,
            @JsonProperty("branch_id") String branchId,
            String summary,
            @JsonProperty("created_at") Instant createdAt) {
        @JsonProperty("taskRunId")
        public String taskRunIdCamel() { return taskRunId; }
        @JsonProperty("stageRunId")
        public String stageRunIdCamel() { return stageRunId; }
        @JsonProperty("branchId")
        public String branchIdCamel() { return branchId; }
        @JsonProperty("createdAt")
        public Instant createdAtCamel() { return createdAt; }
    }

    public record ArtifactDetailResponse(
            String id,
            @JsonProperty("task_run_id") String taskRunId,
            @JsonProperty("stage_run_id") String stageRunId,
            @JsonProperty("branch_id") String branchId,
            String kind,
            @JsonProperty("created_at") Instant createdAt) {
        @JsonProperty("taskRunId")
        public String taskRunIdCamel() { return taskRunId; }
        @JsonProperty("stageRunId")
        public String stageRunIdCamel() { return stageRunId; }
        @JsonProperty("branchId")
        public String branchIdCamel() { return branchId; }
        @JsonProperty("createdAt")
        public Instant createdAtCamel() { return createdAt; }
    }

    public record EvidencePacketDetailResponse(
            String id,
            @JsonProperty("task_run_id") String taskRunId,
            @JsonProperty("branch_id") String branchId,
            String claim,
            String status,
            @JsonProperty("evidence_refs") List<String> evidenceRefs,
            @JsonProperty("created_at") Instant createdAt) {
        @JsonProperty("taskRunId")
        public String taskRunIdCamel() { return taskRunId; }
        @JsonProperty("branchId")
        public String branchIdCamel() { return branchId; }
        @JsonProperty("evidenceRefs")
        public List<String> evidenceRefsCamel() { return evidenceRefs; }
        @JsonProperty("createdAt")
        public Instant createdAtCamel() { return createdAt; }
    }

    public record CheckPermissionRequest(
            @JsonProperty("task_run_id") @JsonAlias("taskRunId") @NotBlank String taskRunId,
            @NotBlank String action,
            @JsonProperty("risk_level") @JsonAlias("riskLevel") String riskLevel,
            @JsonProperty("branch_id") @JsonAlias("branchId") String branchId) {}

    public record PolicyDecisionResponse(boolean allowed, String reason) {}

    public record AppendAuditEventRequest(
            @JsonProperty("task_run_id") @JsonAlias("taskRunId") @NotBlank String taskRunId,
            @NotBlank String action,
            @NotBlank String result,
            String reason,
            @JsonProperty("branch_id") @JsonAlias("branchId") String branchId) {}

    public record AuditEventDetailResponse(
            String id,
            @JsonProperty("task_run_id") String taskRunId,
            @JsonProperty("branch_id") String branchId,
            String action,
            String result,
            String reason,
            @JsonProperty("created_at") Instant createdAt) {
        @JsonProperty("taskRunId")
        public String taskRunIdCamel() { return taskRunId; }
        @JsonProperty("branchId")
        public String branchIdCamel() { return branchId; }
        @JsonProperty("createdAt")
        public Instant createdAtCamel() { return createdAt; }
    }

    public record IdResponse(String id) {}
}
