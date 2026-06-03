package com.lakeon.agentfirst;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

public final class AgentFirstDtos {
    private AgentFirstDtos() {}

    public record CreateTaskRunRequest(
            @NotBlank String goal,
            @JsonProperty("harness_id") @NotBlank String harnessId) {}

    public record TaskRunResponse(
            String id,
            @JsonProperty("harness_id") String harnessId,
            String status) {}

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
            @JsonProperty("task_run_id") @NotBlank String taskRunId) {}

    public record WorkspaceResponse(
            String id,
            @JsonProperty("root_branch_id") String rootBranchId) {}

    public record ForkBranchRequest(
            @JsonProperty("workspace_id") @NotBlank String workspaceId,
            @JsonProperty("stage_run_id") String stageRunId,
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
            @JsonProperty("node_ids") List<String> nodeIds) {}

    public record ResolveContextRequest(
            @JsonProperty("task_run_id") @NotBlank String taskRunId,
            @JsonProperty("stage_run_id") @NotBlank String stageRunId,
            String query) {}

    public record ResolveContextResponse(
            @JsonProperty("node_ids") List<String> nodeIds) {}

    public record BuildContextPackRequest(
            @JsonProperty("task_run_id") @NotBlank String taskRunId,
            @JsonProperty("stage_run_id") @NotBlank String stageRunId,
            @JsonProperty("selected_node_ids") List<String> selectedNodeIds) {}

    public record ContextPackResponse(String id) {}

    public record AppendStateCommitRequest(
            @JsonProperty("task_run_id") @NotBlank String taskRunId,
            @JsonProperty("stage_run_id") @NotBlank String stageRunId,
            @JsonProperty("branch_id") @NotBlank String branchId,
            String summary) {}

    public record RecordArtifactRequest(
            @JsonProperty("task_run_id") @NotBlank String taskRunId,
            @JsonProperty("stage_run_id") @NotBlank String stageRunId,
            @JsonProperty("branch_id") @NotBlank String branchId,
            @NotBlank String kind) {}

    public record RecordLineageRequest(
            @JsonProperty("task_run_id") @NotBlank String taskRunId,
            @JsonProperty("stage_run_id") @NotBlank String stageRunId,
            @JsonProperty("branch_id") @NotBlank String branchId,
            @JsonProperty("artifact_id") @NotBlank String artifactId) {}

    public record CreateCheckpointRequest(
            @JsonProperty("branch_id") @NotBlank String branchId,
            @JsonProperty("stage_run_id") String stageRunId,
            Map<String, Object> manifest) {}

    public record CheckpointResponse(String id) {}

    public record RestorePlanResponse(
            @JsonProperty("checkpoint_id") String checkpointId,
            @JsonProperty("restorable_refs") List<String> restorableRefs,
            @JsonProperty("missing_refs") List<String> missingRefs,
            boolean complete) {}

    public record CreateEvidencePacketRequest(
            @JsonProperty("task_run_id") @NotBlank String taskRunId,
            @JsonProperty("branch_id") String branchId,
            @NotBlank String claim,
            @JsonProperty("evidence_refs") List<String> evidenceRefs) {}

    public record EvidencePacketResponse(String id, String status) {}

    public record CheckPermissionRequest(
            @JsonProperty("task_run_id") @NotBlank String taskRunId,
            @NotBlank String action,
            @JsonProperty("risk_level") String riskLevel,
            @JsonProperty("branch_id") String branchId) {}

    public record PolicyDecisionResponse(boolean allowed, String reason) {}

    public record AppendAuditEventRequest(
            @JsonProperty("task_run_id") @NotBlank String taskRunId,
            @NotBlank String action,
            @NotBlank String result,
            String reason,
            @JsonProperty("branch_id") String branchId) {}

    public record IdResponse(String id) {}
}
