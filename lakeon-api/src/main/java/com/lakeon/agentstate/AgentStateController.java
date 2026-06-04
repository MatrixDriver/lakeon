package com.lakeon.agentstate;

import com.lakeon.model.entity.TenantEntity;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/agent-state")
public class AgentStateController {
    private final AgentStateService agentStateService;

    public AgentStateController(AgentStateService agentStateService) {
        this.agentStateService = agentStateService;
    }

    @PostMapping("/apps")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentStateDtos.AgentAppResponse createAgentApp(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AgentStateDtos.CreateAgentAppRequest request) {
        return agentStateService.createAgentApp(tenantId(httpRequest), request);
    }

    @GetMapping("/apps")
    public List<AgentStateDtos.AgentAppResponse> listAgentApps(HttpServletRequest httpRequest) {
        return agentStateService.listAgentApps(tenantId(httpRequest));
    }

    @GetMapping("/apps/{appId}")
    public AgentStateDtos.AgentAppResponse getAgentApp(HttpServletRequest httpRequest, @PathVariable String appId) {
        return agentStateService.getAgentApp(tenantId(httpRequest), appId);
    }

    @PostMapping("/apps/{appId}/runs")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentStateDtos.TaskRunResponse createAgentAppRun(
            HttpServletRequest httpRequest,
            @PathVariable String appId,
            @Valid @RequestBody AgentStateDtos.CreateTaskRunRequest request) {
        return agentStateService.createTaskRunForApp(tenantId(httpRequest), appId, request);
    }

    @PostMapping("/task-runs")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentStateDtos.TaskRunResponse createTaskRun(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AgentStateDtos.CreateTaskRunRequest request) {
        return agentStateService.createTaskRun(tenantId(httpRequest), request);
    }

    @PostMapping("/task-runs/{taskRunId}/stages")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentStateDtos.StageRunResponse createStageRun(
            HttpServletRequest httpRequest,
            @PathVariable String taskRunId,
            @Valid @RequestBody AgentStateDtos.CreateStageRunRequest request) {
        return agentStateService.createStageRun(tenantId(httpRequest), taskRunId, request);
    }

    @PostMapping("/workspaces")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentStateDtos.WorkspaceResponse createWorkspace(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AgentStateDtos.CreateWorkspaceRequest request) {
        return agentStateService.createWorkspace(tenantId(httpRequest), request);
    }

    @PostMapping("/branches")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentStateDtos.BranchResponse forkBranch(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AgentStateDtos.ForkBranchRequest request) {
        return agentStateService.forkBranch(tenantId(httpRequest), request);
    }

    @PostMapping("/workspaces/branches/fork")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentStateDtos.BranchResponse forkWorkspaceBranch(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AgentStateDtos.ForkBranchRequest request) {
        return agentStateService.forkBranch(tenantId(httpRequest), request);
    }

    @PostMapping("/context/resolve")
    public AgentStateDtos.ResolveContextResponse resolveContext(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AgentStateDtos.ResolveContextRequest request) {
        return agentStateService.resolveContext(tenantId(httpRequest), request);
    }

    @PostMapping("/context/sources")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentStateDtos.IngestContextResponse ingestContextSource(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AgentStateDtos.IngestContextSourceRequest request) {
        return agentStateService.ingestContextSource(tenantId(httpRequest), request);
    }

    @PostMapping("/context/packs")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentStateDtos.ContextPackResponse buildContextPack(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AgentStateDtos.BuildContextPackRequest request) {
        return agentStateService.buildContextPack(tenantId(httpRequest), request);
    }

    @PostMapping("/state-commits")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentStateDtos.IdResponse appendStateCommit(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AgentStateDtos.AppendStateCommitRequest request) {
        return agentStateService.appendStateCommit(tenantId(httpRequest), request);
    }

    @PostMapping("/artifacts/state-commits")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentStateDtos.IdResponse appendArtifactStateCommit(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AgentStateDtos.AppendStateCommitRequest request) {
        return agentStateService.appendStateCommit(tenantId(httpRequest), request);
    }

    @PostMapping("/artifacts")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentStateDtos.IdResponse recordArtifact(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AgentStateDtos.RecordArtifactRequest request) {
        return agentStateService.recordArtifact(tenantId(httpRequest), request);
    }

    @PostMapping("/lineage")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentStateDtos.IdResponse recordLineage(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AgentStateDtos.RecordLineageRequest request) {
        return agentStateService.recordLineage(tenantId(httpRequest), request);
    }

    @PostMapping("/checkpoints")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentStateDtos.CheckpointResponse createCheckpoint(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AgentStateDtos.CreateCheckpointRequest request) {
        return agentStateService.createCheckpoint(tenantId(httpRequest), request);
    }

    @PostMapping("/artifacts/manifests/snapshot")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentStateDtos.IdResponse snapshotManifest(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AgentStateDtos.SnapshotManifestRequest request) {
        return agentStateService.snapshotManifest(tenantId(httpRequest), request);
    }

    @PostMapping("/checkpoints/{checkpointId}/restore")
    public AgentStateDtos.RestorePlanResponse restoreCheckpoint(
            HttpServletRequest httpRequest,
            @PathVariable String checkpointId) {
        return agentStateService.restoreCheckpoint(tenantId(httpRequest), checkpointId);
    }

    @PostMapping("/evidence-packets")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentStateDtos.EvidencePacketResponse createEvidencePacket(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AgentStateDtos.CreateEvidencePacketRequest request) {
        return agentStateService.createEvidencePacket(tenantId(httpRequest), request);
    }

    @PostMapping("/evidence-packets/{evidencePacketId}/evaluate")
    public AgentStateDtos.PolicyDecisionResponse evaluateEvidence(
            HttpServletRequest httpRequest,
            @PathVariable String evidencePacketId) {
        return agentStateService.evaluateEvidence(tenantId(httpRequest), evidencePacketId);
    }

    @PostMapping("/policy/check")
    public AgentStateDtos.PolicyDecisionResponse checkPermission(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AgentStateDtos.CheckPermissionRequest request) {
        return agentStateService.checkPermission(tenantId(httpRequest), request);
    }

    @PostMapping("/audit-events")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentStateDtos.IdResponse appendAuditEvent(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AgentStateDtos.AppendAuditEventRequest request) {
        return agentStateService.appendAuditEvent(tenantId(httpRequest), request);
    }

    @PostMapping("/audit/events")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentStateDtos.IdResponse appendAuditEventAlias(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AgentStateDtos.AppendAuditEventRequest request) {
        return agentStateService.appendAuditEvent(tenantId(httpRequest), request);
    }

    @GetMapping("/task-runs/{taskRunId}/audit-events")
    public List<AgentStateDtos.IdResponse> listAuditEvents(HttpServletRequest httpRequest, @PathVariable String taskRunId) {
        return agentStateService.listAuditEvents(tenantId(httpRequest), taskRunId);
    }

    private String tenantId(HttpServletRequest httpRequest) {
        TenantEntity tenant = (TenantEntity) httpRequest.getAttribute("tenant");
        return tenant.getId();
    }
}
