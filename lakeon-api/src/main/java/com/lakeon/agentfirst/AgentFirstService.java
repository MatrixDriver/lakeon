package com.lakeon.agentfirst;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.service.exception.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class AgentFirstService {
    private final AgentTaskRunRepository taskRunRepository;
    private final AgentStageRunRepository stageRunRepository;
    private final AgentWorkspaceRepository workspaceRepository;
    private final AgentWorkspaceBranchRepository branchRepository;
    private final ContextNodeRepository contextNodeRepository;
    private final ContextPackRepository contextPackRepository;
    private final AgentCheckpointRepository checkpointRepository;
    private final AgentStateCommitRepository stateCommitRepository;
    private final AgentArtifactRefRepository artifactRefRepository;
    private final AgentLineageEdgeRepository lineageEdgeRepository;
    private final AgentEvidencePacketRepository evidencePacketRepository;
    private final AgentPolicyDecisionRepository policyDecisionRepository;
    private final AgentAuditEventRepository auditEventRepository;
    private final ObjectMapper objectMapper;

    public AgentFirstService(AgentTaskRunRepository taskRunRepository,
                             AgentStageRunRepository stageRunRepository,
                             AgentWorkspaceRepository workspaceRepository,
                             AgentWorkspaceBranchRepository branchRepository,
                             ContextNodeRepository contextNodeRepository,
                             ContextPackRepository contextPackRepository,
                             AgentCheckpointRepository checkpointRepository,
                             AgentStateCommitRepository stateCommitRepository,
                             AgentArtifactRefRepository artifactRefRepository,
                             AgentLineageEdgeRepository lineageEdgeRepository,
                             AgentEvidencePacketRepository evidencePacketRepository,
                             AgentPolicyDecisionRepository policyDecisionRepository,
                             AgentAuditEventRepository auditEventRepository,
                             ObjectMapper objectMapper) {
        this.taskRunRepository = taskRunRepository;
        this.stageRunRepository = stageRunRepository;
        this.workspaceRepository = workspaceRepository;
        this.branchRepository = branchRepository;
        this.contextNodeRepository = contextNodeRepository;
        this.contextPackRepository = contextPackRepository;
        this.checkpointRepository = checkpointRepository;
        this.stateCommitRepository = stateCommitRepository;
        this.artifactRefRepository = artifactRefRepository;
        this.lineageEdgeRepository = lineageEdgeRepository;
        this.evidencePacketRepository = evidencePacketRepository;
        this.policyDecisionRepository = policyDecisionRepository;
        this.auditEventRepository = auditEventRepository;
        this.objectMapper = objectMapper;
    }

    AgentFirstService(AgentTaskRunRepository taskRunRepository,
                      AgentWorkspaceRepository workspaceRepository,
                      AgentWorkspaceBranchRepository branchRepository,
                      ContextNodeRepository contextNodeRepository,
                      ContextPackRepository contextPackRepository,
                      AgentCheckpointRepository checkpointRepository,
                      AgentStateCommitRepository stateCommitRepository,
                      AgentArtifactRefRepository artifactRefRepository,
                      AgentLineageEdgeRepository lineageEdgeRepository,
                      AgentEvidencePacketRepository evidencePacketRepository,
                      AgentPolicyDecisionRepository policyDecisionRepository,
                      AgentAuditEventRepository auditEventRepository) {
        this(taskRunRepository, null, workspaceRepository, branchRepository, contextNodeRepository,
                contextPackRepository, checkpointRepository, stateCommitRepository, artifactRefRepository,
                lineageEdgeRepository, evidencePacketRepository, policyDecisionRepository, auditEventRepository,
                new ObjectMapper());
    }

    @Transactional
    public AgentFirstDtos.TaskRunResponse createTaskRun(String tenantId, AgentFirstDtos.CreateTaskRunRequest request) {
        AgentTaskRunEntity entity = new AgentTaskRunEntity();
        entity.setTenantId(tenantId);
        entity.setGoal(request.goal());
        entity.setHarnessId(request.harnessId());
        entity.setStatus("running");
        AgentTaskRunEntity saved = taskRunRepository.save(entity);
        return new AgentFirstDtos.TaskRunResponse(saved.getId(), saved.getHarnessId(), saved.getStatus());
    }

    @Transactional
    public AgentFirstDtos.StageRunResponse createStageRun(
            String tenantId, String taskRunId, AgentFirstDtos.CreateStageRunRequest request) {
        AgentStageRunEntity entity = new AgentStageRunEntity();
        entity.setTenantId(tenantId);
        entity.setTaskRunId(taskRunId);
        entity.setStageId(request.stageId());
        entity.setBranchId(request.branchId());
        entity.setContextPackId(request.contextPackId());
        AgentStageRunEntity saved = stageRunRepository.save(entity);
        return new AgentFirstDtos.StageRunResponse(
                saved.getId(), saved.getTaskRunId(), saved.getStageId(), saved.getStatus(),
                saved.getBranchId(), saved.getContextPackId());
    }

    @Transactional
    public AgentFirstDtos.WorkspaceResponse createWorkspace(String tenantId, AgentFirstDtos.CreateWorkspaceRequest request) {
        AgentWorkspaceEntity workspace = new AgentWorkspaceEntity();
        workspace.setTenantId(tenantId);
        workspace.setTaskRunId(request.taskRunId());
        AgentWorkspaceEntity savedWorkspace = workspaceRepository.save(workspace);

        AgentWorkspaceBranchEntity root = new AgentWorkspaceBranchEntity();
        root.setTenantId(tenantId);
        root.setWorkspaceId(savedWorkspace.getId());
        root.setName("root");
        AgentWorkspaceBranchEntity savedRoot = branchRepository.save(root);
        return new AgentFirstDtos.WorkspaceResponse(savedWorkspace.getId(), savedRoot.getId());
    }

    @Transactional
    public AgentFirstDtos.BranchResponse forkBranch(String tenantId, AgentFirstDtos.ForkBranchRequest request) {
        AgentWorkspaceBranchEntity branch = new AgentWorkspaceBranchEntity();
        branch.setTenantId(tenantId);
        branch.setWorkspaceId(request.workspaceId());
        branch.setStageRunId(request.stageRunId());
        branch.setName("branch");
        branch.setHypothesis(request.hypothesis());
        AgentWorkspaceBranchEntity saved = branchRepository.save(branch);
        return new AgentFirstDtos.BranchResponse(saved.getId());
    }

    @Transactional
    public AgentFirstDtos.IngestContextResponse ingestContextSource(
            String tenantId, AgentFirstDtos.IngestContextSourceRequest request) {
        List<String> nodeIds = (request.nodes() == null ? List.<AgentFirstDtos.ContextNodeInput>of() : request.nodes())
                .stream()
                .map(node -> {
                    ContextNodeEntity entity = new ContextNodeEntity();
                    entity.setId(node.id());
                    entity.setTenantId(tenantId);
                    entity.setType(node.type());
                    entity.setName(node.name());
                    entity.setSourceRef(request.sourceRef());
                    return contextNodeRepository.save(entity).getId();
                })
                .toList();
        return new AgentFirstDtos.IngestContextResponse(nodeIds);
    }

    public AgentFirstDtos.ResolveContextResponse resolveContext(
            String tenantId, AgentFirstDtos.ResolveContextRequest request) {
        List<String> nodeIds = contextNodeRepository.findByTenantIdOrderByCreatedAtAsc(tenantId)
                .stream()
                .map(ContextNodeEntity::getId)
                .toList();
        return new AgentFirstDtos.ResolveContextResponse(nodeIds);
    }

    @Transactional
    public AgentFirstDtos.ContextPackResponse buildContextPack(
            String tenantId, AgentFirstDtos.BuildContextPackRequest request) {
        ContextPackEntity pack = new ContextPackEntity();
        pack.setTenantId(tenantId);
        pack.setTaskRunId(request.taskRunId());
        pack.setStageRunId(request.stageRunId());
        pack.setSelectedNodesJson(toJson(request.selectedNodeIds() == null ? List.of() : request.selectedNodeIds()));
        ContextPackEntity saved = contextPackRepository.save(pack);
        return new AgentFirstDtos.ContextPackResponse(saved.getId());
    }

    @Transactional
    public AgentFirstDtos.IdResponse appendStateCommit(String tenantId, AgentFirstDtos.AppendStateCommitRequest request) {
        AgentStateCommitEntity commit = new AgentStateCommitEntity();
        commit.setTenantId(tenantId);
        commit.setTaskRunId(request.taskRunId());
        commit.setStageRunId(request.stageRunId());
        commit.setBranchId(request.branchId());
        commit.setSummary(request.summary());
        return new AgentFirstDtos.IdResponse(stateCommitRepository.save(commit).getId());
    }

    @Transactional
    public AgentFirstDtos.IdResponse recordArtifact(String tenantId, AgentFirstDtos.RecordArtifactRequest request) {
        AgentArtifactRefEntity artifact = new AgentArtifactRefEntity();
        artifact.setTenantId(tenantId);
        artifact.setTaskRunId(request.taskRunId());
        artifact.setStageRunId(request.stageRunId());
        artifact.setBranchId(request.branchId());
        artifact.setKind(request.kind());
        return new AgentFirstDtos.IdResponse(artifactRefRepository.save(artifact).getId());
    }

    @Transactional
    public AgentFirstDtos.IdResponse recordLineage(String tenantId, AgentFirstDtos.RecordLineageRequest request) {
        AgentLineageEdgeEntity lineage = new AgentLineageEdgeEntity();
        lineage.setTenantId(tenantId);
        lineage.setTaskRunId(request.taskRunId());
        lineage.setStageRunId(request.stageRunId());
        lineage.setBranchId(request.branchId());
        lineage.setArtifactId(request.artifactId());
        return new AgentFirstDtos.IdResponse(lineageEdgeRepository.save(lineage).getId());
    }

    @Transactional
    public AgentFirstDtos.CheckpointResponse createCheckpoint(String tenantId, AgentFirstDtos.CreateCheckpointRequest request) {
        AgentCheckpointEntity checkpoint = new AgentCheckpointEntity();
        checkpoint.setTenantId(tenantId);
        checkpoint.setBranchId(request.branchId());
        checkpoint.setStageRunId(request.stageRunId());
        checkpoint.setManifestJson(toJson(request.manifest() == null ? Map.of() : request.manifest()));
        return new AgentFirstDtos.CheckpointResponse(checkpointRepository.save(checkpoint).getId());
    }

    @Transactional
    public AgentFirstDtos.IdResponse snapshotManifest(String tenantId, AgentFirstDtos.SnapshotManifestRequest request) {
        AgentCheckpointEntity checkpoint = new AgentCheckpointEntity();
        checkpoint.setTenantId(tenantId);
        checkpoint.setBranchId(request.branchId());
        checkpoint.setStageRunId(request.stageRunId());
        checkpoint.setManifestJson(toJson(Map.of(
                "task_run_id", request.taskRunId(),
                "stage_run_id", request.stageRunId(),
                "branch_id", request.branchId(),
                "artifacts", request.artifactIds() == null ? List.of() : request.artifactIds()
        )));
        return new AgentFirstDtos.IdResponse(checkpointRepository.save(checkpoint).getId());
    }

    public AgentFirstDtos.RestorePlanResponse restoreCheckpoint(String tenantId, String checkpointId) {
        AgentCheckpointEntity checkpoint = checkpointRepository.findByIdAndTenantId(checkpointId, tenantId)
                .orElseThrow(() -> new NotFoundException("Checkpoint not found: " + checkpointId));
        Map<String, Object> manifest = fromJsonObject(checkpoint.getManifestJson());
        List<String> restorableRefs = stringList(manifest.get("artifacts"));
        List<String> missingRefs = stringList(manifest.get("missing"));
        return new AgentFirstDtos.RestorePlanResponse(
                checkpoint.getId(), restorableRefs, missingRefs, missingRefs.isEmpty());
    }

    @Transactional
    public AgentFirstDtos.EvidencePacketResponse createEvidencePacket(
            String tenantId, AgentFirstDtos.CreateEvidencePacketRequest request) {
        AgentEvidencePacketEntity packet = new AgentEvidencePacketEntity();
        packet.setTenantId(tenantId);
        packet.setTaskRunId(request.taskRunId());
        packet.setBranchId(request.branchId());
        packet.setClaim(request.claim());
        packet.setEvidenceRefsJson(toJson(request.evidenceRefs() == null ? List.of() : request.evidenceRefs()));
        AgentEvidencePacketEntity saved = evidencePacketRepository.save(packet);
        return new AgentFirstDtos.EvidencePacketResponse(saved.getId(), saved.getStatus());
    }

    public AgentFirstDtos.PolicyDecisionResponse evaluateEvidence(String tenantId, String evidencePacketId) {
        AgentEvidencePacketEntity packet = evidencePacketRepository.findByIdAndTenantId(evidencePacketId, tenantId)
                .orElseThrow(() -> new NotFoundException("Evidence packet not found: " + evidencePacketId));
        List<String> evidenceRefs = fromJsonList(packet.getEvidenceRefsJson());
        if (evidenceRefs.isEmpty()) {
            return new AgentFirstDtos.PolicyDecisionResponse(false, "missing verified evidence");
        }
        return new AgentFirstDtos.PolicyDecisionResponse(true, "evidence verified");
    }

    @Transactional
    public AgentFirstDtos.PolicyDecisionResponse checkPermission(
            String tenantId, AgentFirstDtos.CheckPermissionRequest request) {
        boolean blocked = isHighRisk(request.riskLevel()) || isDestructive(request.action());
        String reason = blocked ? "Action requires approval before runtime execution" : "allowed";

        AgentPolicyDecisionEntity decision = new AgentPolicyDecisionEntity();
        decision.setTenantId(tenantId);
        decision.setTaskRunId(request.taskRunId());
        decision.setBranchId(request.branchId());
        decision.setAction(request.action());
        decision.setAllowed(!blocked);
        decision.setReason(reason);
        policyDecisionRepository.save(decision);

        return new AgentFirstDtos.PolicyDecisionResponse(!blocked, reason);
    }

    @Transactional
    public AgentFirstDtos.IdResponse appendAuditEvent(String tenantId, AgentFirstDtos.AppendAuditEventRequest request) {
        AgentAuditEventEntity event = new AgentAuditEventEntity();
        event.setTenantId(tenantId);
        event.setTaskRunId(request.taskRunId());
        event.setBranchId(request.branchId());
        event.setAction(request.action());
        event.setResult(request.result());
        event.setReason(request.reason());
        return new AgentFirstDtos.IdResponse(auditEventRepository.save(event).getId());
    }

    public List<AgentFirstDtos.IdResponse> listAuditEvents(String tenantId, String taskRunId) {
        return auditEventRepository.findByTenantIdAndTaskRunIdOrderByCreatedAtAsc(tenantId, taskRunId)
                .stream()
                .map(event -> new AgentFirstDtos.IdResponse(event.getId()))
                .toList();
    }

    private boolean isHighRisk(String riskLevel) {
        return "high".equalsIgnoreCase(riskLevel);
    }

    private boolean isDestructive(String action) {
        String normalized = action == null ? "" : action.toLowerCase();
        return normalized.contains("drop") || normalized.contains("delete") || normalized.contains("truncate");
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid context pack payload", e);
        }
    }

    private Map<String, Object> fromJsonObject(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid checkpoint manifest", e);
        }
    }

    private List<String> fromJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid evidence refs", e);
        }
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }
}
