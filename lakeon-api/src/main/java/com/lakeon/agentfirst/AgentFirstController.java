package com.lakeon.agentfirst;

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
@RequestMapping("/api/v1/agentfirst")
public class AgentFirstController {
    private final AgentFirstService agentFirstService;

    public AgentFirstController(AgentFirstService agentFirstService) {
        this.agentFirstService = agentFirstService;
    }

    @PostMapping("/task-runs")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentFirstDtos.TaskRunResponse createTaskRun(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AgentFirstDtos.CreateTaskRunRequest request) {
        return agentFirstService.createTaskRun(tenantId(httpRequest), request);
    }

    @PostMapping("/task-runs/{taskRunId}/stages")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentFirstDtos.StageRunResponse createStageRun(
            HttpServletRequest httpRequest,
            @PathVariable String taskRunId,
            @Valid @RequestBody AgentFirstDtos.CreateStageRunRequest request) {
        return agentFirstService.createStageRun(tenantId(httpRequest), taskRunId, request);
    }

    @PostMapping("/workspaces")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentFirstDtos.WorkspaceResponse createWorkspace(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AgentFirstDtos.CreateWorkspaceRequest request) {
        return agentFirstService.createWorkspace(tenantId(httpRequest), request);
    }

    @PostMapping("/branches")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentFirstDtos.BranchResponse forkBranch(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AgentFirstDtos.ForkBranchRequest request) {
        return agentFirstService.forkBranch(tenantId(httpRequest), request);
    }

    @PostMapping("/context/resolve")
    public AgentFirstDtos.ResolveContextResponse resolveContext(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AgentFirstDtos.ResolveContextRequest request) {
        return agentFirstService.resolveContext(tenantId(httpRequest), request);
    }

    @PostMapping("/context/sources")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentFirstDtos.IngestContextResponse ingestContextSource(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AgentFirstDtos.IngestContextSourceRequest request) {
        return agentFirstService.ingestContextSource(tenantId(httpRequest), request);
    }

    @PostMapping("/context/packs")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentFirstDtos.ContextPackResponse buildContextPack(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AgentFirstDtos.BuildContextPackRequest request) {
        return agentFirstService.buildContextPack(tenantId(httpRequest), request);
    }

    @PostMapping("/state-commits")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentFirstDtos.IdResponse appendStateCommit(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AgentFirstDtos.AppendStateCommitRequest request) {
        return agentFirstService.appendStateCommit(tenantId(httpRequest), request);
    }

    @PostMapping("/artifacts")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentFirstDtos.IdResponse recordArtifact(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AgentFirstDtos.RecordArtifactRequest request) {
        return agentFirstService.recordArtifact(tenantId(httpRequest), request);
    }

    @PostMapping("/lineage")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentFirstDtos.IdResponse recordLineage(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AgentFirstDtos.RecordLineageRequest request) {
        return agentFirstService.recordLineage(tenantId(httpRequest), request);
    }

    @PostMapping("/checkpoints")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentFirstDtos.CheckpointResponse createCheckpoint(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AgentFirstDtos.CreateCheckpointRequest request) {
        return agentFirstService.createCheckpoint(tenantId(httpRequest), request);
    }

    @PostMapping("/checkpoints/{checkpointId}/restore")
    public AgentFirstDtos.RestorePlanResponse restoreCheckpoint(
            HttpServletRequest httpRequest,
            @PathVariable String checkpointId) {
        return agentFirstService.restoreCheckpoint(tenantId(httpRequest), checkpointId);
    }

    @PostMapping("/evidence-packets")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentFirstDtos.EvidencePacketResponse createEvidencePacket(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AgentFirstDtos.CreateEvidencePacketRequest request) {
        return agentFirstService.createEvidencePacket(tenantId(httpRequest), request);
    }

    @PostMapping("/evidence-packets/{evidencePacketId}/evaluate")
    public AgentFirstDtos.PolicyDecisionResponse evaluateEvidence(
            HttpServletRequest httpRequest,
            @PathVariable String evidencePacketId) {
        return agentFirstService.evaluateEvidence(tenantId(httpRequest), evidencePacketId);
    }

    @PostMapping("/policy/check")
    public AgentFirstDtos.PolicyDecisionResponse checkPermission(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AgentFirstDtos.CheckPermissionRequest request) {
        return agentFirstService.checkPermission(tenantId(httpRequest), request);
    }

    @PostMapping("/audit-events")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentFirstDtos.IdResponse appendAuditEvent(
            HttpServletRequest httpRequest,
            @Valid @RequestBody AgentFirstDtos.AppendAuditEventRequest request) {
        return agentFirstService.appendAuditEvent(tenantId(httpRequest), request);
    }

    @GetMapping("/task-runs/{taskRunId}/audit-events")
    public List<AgentFirstDtos.IdResponse> listAuditEvents(HttpServletRequest httpRequest, @PathVariable String taskRunId) {
        return agentFirstService.listAuditEvents(tenantId(httpRequest), taskRunId);
    }

    private String tenantId(HttpServletRequest httpRequest) {
        TenantEntity tenant = (TenantEntity) httpRequest.getAttribute("tenant");
        return tenant.getId();
    }
}
