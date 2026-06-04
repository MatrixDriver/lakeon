package com.lakeon.agentstate;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

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

    public record ForkBranchRequest(
            @JsonProperty("workspace_id") @JsonAlias("workspaceId") @NotBlank String workspaceId,
            @JsonProperty("stage_run_id") @JsonAlias("stageRunId") String stageRunId,
            String hypothesis) {}

    public record BranchResponse(String id) {}

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

    public record IdResponse(String id) {}
}
