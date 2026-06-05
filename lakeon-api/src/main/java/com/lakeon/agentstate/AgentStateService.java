package com.lakeon.agentstate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.service.exception.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;

@Service
public class AgentStateService {
    private final AgentTaskRunRepository taskRunRepository;
    private final AgentAppRepository agentAppRepository;
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

    @Autowired
    public AgentStateService(AgentTaskRunRepository taskRunRepository,
                             AgentAppRepository agentAppRepository,
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
        this.agentAppRepository = agentAppRepository;
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

    AgentStateService(AgentTaskRunRepository taskRunRepository,
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
        this(taskRunRepository, null, stageRunRepository, workspaceRepository, branchRepository,
                contextNodeRepository, contextPackRepository, checkpointRepository, stateCommitRepository,
                artifactRefRepository, lineageEdgeRepository, evidencePacketRepository, policyDecisionRepository,
                auditEventRepository, objectMapper);
    }

    AgentStateService(AgentTaskRunRepository taskRunRepository,
                      AgentAppRepository agentAppRepository,
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
        this(taskRunRepository, agentAppRepository, null, workspaceRepository, branchRepository, contextNodeRepository,
                contextPackRepository, checkpointRepository, stateCommitRepository, artifactRefRepository,
                lineageEdgeRepository, evidencePacketRepository, policyDecisionRepository, auditEventRepository,
                new ObjectMapper());
    }

    @Transactional
    public AgentStateDtos.AgentAppResponse createAgentApp(String tenantId, AgentStateDtos.CreateAgentAppRequest request) {
        AgentAppEntity entity = new AgentAppEntity();
        entity.setTenantId(tenantId);
        entity.setKey(request.key());
        entity.setDisplayName(request.displayName());
        entity.setType(blankDefault(request.type(), "custom"));
        entity.setVersion(blankDefault(request.version(), "0.1.0"));
        entity.setStatus(blankDefault(request.status(), "active"));
        entity.setStageSchemaJson(toJson(request.stageSchema() == null ? List.of() : request.stageSchema()));
        return agentAppResponse(agentAppRepository.save(entity));
    }

    @Transactional
    public List<AgentStateDtos.AgentAppResponse> listAgentApps(String tenantId) {
        List<AgentAppEntity> apps = new ArrayList<>(agentAppRepository.findByTenantIdOrderByCreatedAtAsc(tenantId));
        if (apps.isEmpty()) {
            for (BuiltInAgentApp app : BUILT_IN_AGENT_APPS) {
                apps.add(agentAppRepository.save(toEntity(tenantId, app)));
            }
        }
        return apps
                .stream()
                .map(this::agentAppResponse)
                .toList();
    }

    public AgentStateDtos.AgentAppResponse getAgentApp(String tenantId, String appId) {
        return agentAppResponse(findAgentApp(tenantId, appId));
    }

    public List<AgentStateDtos.TaskRunSummaryResponse> listTaskRuns(String tenantId) {
        return taskRunRepository.findByTenantIdOrderByCreatedAtDesc(tenantId)
                .stream()
                .map(task -> taskRunSummary(tenantId, task))
                .toList();
    }

    public AgentStateDtos.TaskRunDetailResponse getTaskRun(String tenantId, String taskRunId) {
        AgentTaskRunEntity task = taskRunRepository.findByIdAndTenantId(taskRunId, tenantId)
                .orElseThrow(() -> new NotFoundException("Task run not found: " + taskRunId));
        List<AgentStageRunEntity> stages = stageRunRepository.findByTenantIdAndTaskRunIdOrderByCreatedAtAsc(tenantId, taskRunId);
        AgentWorkspaceEntity workspace = workspaceRepository.findByTenantIdAndTaskRunId(tenantId, taskRunId).orElse(null);
        List<AgentWorkspaceBranchEntity> branches = workspace == null
                ? List.of()
                : branchRepository.findByTenantIdAndWorkspaceIdOrderByCreatedAtAsc(tenantId, workspace.getId());
        return new AgentStateDtos.TaskRunDetailResponse(
                taskRunSummary(task, stages, workspace, branches,
                        evidencePacketRepository.findByTenantIdAndTaskRunIdOrderByCreatedAtAsc(tenantId, taskRunId),
                        auditEventRepository.findByTenantIdAndTaskRunIdOrderByCreatedAtAsc(tenantId, taskRunId)),
                stages.stream().map(this::stageRunDetail).toList(),
                workspace == null ? null : workspaceDetail(workspace, branches),
                branches.stream().map(this::branchDetail).toList(),
                stateCommitRepository.findByTenantIdAndTaskRunIdOrderByCreatedAtAsc(tenantId, taskRunId)
                        .stream().map(this::stateCommitDetail).toList(),
                artifactRefRepository.findByTenantIdAndTaskRunIdOrderByCreatedAtAsc(tenantId, taskRunId)
                        .stream().map(this::artifactDetail).toList(),
                evidencePacketRepository.findByTenantIdAndTaskRunIdOrderByCreatedAtAsc(tenantId, taskRunId)
                        .stream().map(this::evidencePacketDetail).toList(),
                auditEventRepository.findByTenantIdAndTaskRunIdOrderByCreatedAtAsc(tenantId, taskRunId)
                        .stream().map(this::auditEventDetail).toList());
    }

    @Transactional
    public AgentStateDtos.TaskRunResponse createTaskRunForApp(
            String tenantId, String appId, AgentStateDtos.CreateAgentAppRunRequest request) {
        AgentAppEntity app = findAgentApp(tenantId, appId);
        String harnessId = request.harnessId() == null || request.harnessId().isBlank() ? app.getKey() : request.harnessId();
        return createTaskRun(
                tenantId,
                new AgentStateDtos.CreateTaskRunRequest(request.goal(), harnessId, app.getId()));
    }

    @Transactional
    public AgentStateDtos.TaskRunResponse createTaskRun(String tenantId, AgentStateDtos.CreateTaskRunRequest request) {
        AgentTaskRunEntity entity = new AgentTaskRunEntity();
        entity.setTenantId(tenantId);
        entity.setGoal(request.goal());
        entity.setHarnessId(request.harnessId());
        entity.setAgentAppId(request.agentAppId());
        entity.setStatus("running");
        AgentTaskRunEntity saved = taskRunRepository.save(entity);
        return new AgentStateDtos.TaskRunResponse(saved.getId(), saved.getHarnessId(), saved.getStatus(), saved.getAgentAppId());
    }

    @Transactional
    public AgentStateDtos.StageRunResponse createStageRun(
            String tenantId, String taskRunId, AgentStateDtos.CreateStageRunRequest request) {
        AgentStageRunEntity entity = new AgentStageRunEntity();
        entity.setTenantId(tenantId);
        entity.setTaskRunId(taskRunId);
        entity.setStageId(request.stageId());
        entity.setBranchId(request.branchId());
        entity.setContextPackId(request.contextPackId());
        AgentStageRunEntity saved = stageRunRepository.save(entity);
        return new AgentStateDtos.StageRunResponse(
                saved.getId(), saved.getTaskRunId(), saved.getStageId(), saved.getStatus(),
                saved.getBranchId(), saved.getContextPackId());
    }

    @Transactional
    public AgentStateDtos.WorkspaceResponse createWorkspace(String tenantId, AgentStateDtos.CreateWorkspaceRequest request) {
        AgentWorkspaceEntity workspace = new AgentWorkspaceEntity();
        workspace.setTenantId(tenantId);
        workspace.setTaskRunId(request.taskRunId());
        AgentWorkspaceEntity savedWorkspace = workspaceRepository.save(workspace);

        AgentWorkspaceBranchEntity root = new AgentWorkspaceBranchEntity();
        root.setTenantId(tenantId);
        root.setWorkspaceId(savedWorkspace.getId());
        root.setName("root");
        AgentWorkspaceBranchEntity savedRoot = branchRepository.save(root);
        return new AgentStateDtos.WorkspaceResponse(savedWorkspace.getId(), savedRoot.getId());
    }

    @Transactional
    public AgentStateDtos.BranchResponse forkBranch(String tenantId, AgentStateDtos.ForkBranchRequest request) {
        AgentWorkspaceBranchEntity branch = new AgentWorkspaceBranchEntity();
        branch.setTenantId(tenantId);
        branch.setWorkspaceId(request.workspaceId());
        branch.setParentBranchId(resolveParentBranchId(tenantId, request.workspaceId(), request.parentBranchId()));
        branch.setStageRunId(request.stageRunId());
        branch.setName("branch");
        branch.setHypothesis(request.hypothesis());
        AgentWorkspaceBranchEntity saved = branchRepository.save(branch);
        return new AgentStateDtos.BranchResponse(saved.getId());
    }

    @Transactional
    public AgentStateDtos.IngestContextResponse ingestContextSource(
            String tenantId, AgentStateDtos.IngestContextSourceRequest request) {
        List<String> nodeIds = (request.nodes() == null ? List.<AgentStateDtos.ContextNodeInput>of() : request.nodes())
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
        return new AgentStateDtos.IngestContextResponse(nodeIds);
    }

    public AgentStateDtos.ResolveContextResponse resolveContext(
            String tenantId, AgentStateDtos.ResolveContextRequest request) {
        List<String> nodeIds = contextNodeRepository.findByTenantIdOrderByCreatedAtAsc(tenantId)
                .stream()
                .map(ContextNodeEntity::getId)
                .toList();
        return new AgentStateDtos.ResolveContextResponse(nodeIds);
    }

    @Transactional
    public AgentStateDtos.ContextPackResponse buildContextPack(
            String tenantId, AgentStateDtos.BuildContextPackRequest request) {
        ContextPackEntity pack = new ContextPackEntity();
        pack.setTenantId(tenantId);
        pack.setTaskRunId(request.taskRunId());
        pack.setStageRunId(request.stageRunId());
        pack.setSelectedNodesJson(toJson(request.selectedNodeIds() == null ? List.of() : request.selectedNodeIds()));
        ContextPackEntity saved = contextPackRepository.save(pack);
        return new AgentStateDtos.ContextPackResponse(saved.getId());
    }

    @Transactional
    public AgentStateDtos.IdResponse appendStateCommit(String tenantId, AgentStateDtos.AppendStateCommitRequest request) {
        AgentStateCommitEntity commit = new AgentStateCommitEntity();
        commit.setTenantId(tenantId);
        commit.setTaskRunId(request.taskRunId());
        commit.setStageRunId(request.stageRunId());
        commit.setBranchId(request.branchId());
        commit.setSummary(request.summary());
        return new AgentStateDtos.IdResponse(stateCommitRepository.save(commit).getId());
    }

    @Transactional
    public AgentStateDtos.IdResponse recordArtifact(String tenantId, AgentStateDtos.RecordArtifactRequest request) {
        AgentArtifactRefEntity artifact = new AgentArtifactRefEntity();
        artifact.setTenantId(tenantId);
        artifact.setTaskRunId(request.taskRunId());
        artifact.setStageRunId(request.stageRunId());
        artifact.setBranchId(request.branchId());
        artifact.setKind(request.kind());
        return new AgentStateDtos.IdResponse(artifactRefRepository.save(artifact).getId());
    }

    @Transactional
    public AgentStateDtos.IdResponse recordLineage(String tenantId, AgentStateDtos.RecordLineageRequest request) {
        AgentLineageEdgeEntity lineage = new AgentLineageEdgeEntity();
        lineage.setTenantId(tenantId);
        lineage.setTaskRunId(request.taskRunId());
        lineage.setStageRunId(request.stageRunId());
        lineage.setBranchId(request.branchId());
        lineage.setArtifactId(request.artifactId());
        return new AgentStateDtos.IdResponse(lineageEdgeRepository.save(lineage).getId());
    }

    @Transactional
    public AgentStateDtos.CheckpointResponse createCheckpoint(String tenantId, AgentStateDtos.CreateCheckpointRequest request) {
        AgentCheckpointEntity checkpoint = new AgentCheckpointEntity();
        checkpoint.setTenantId(tenantId);
        checkpoint.setBranchId(request.branchId());
        checkpoint.setStageRunId(request.stageRunId());
        checkpoint.setManifestJson(toJson(request.manifest() == null ? Map.of() : request.manifest()));
        return new AgentStateDtos.CheckpointResponse(checkpointRepository.save(checkpoint).getId());
    }

    @Transactional
    public AgentStateDtos.IdResponse snapshotManifest(String tenantId, AgentStateDtos.SnapshotManifestRequest request) {
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
        return new AgentStateDtos.IdResponse(checkpointRepository.save(checkpoint).getId());
    }

    @Transactional
    public AgentStateDtos.IdResponse recordBranchVersion(String tenantId, AgentStateDtos.RecordBranchVersionRequest request) {
        AgentCheckpointEntity checkpoint = new AgentCheckpointEntity();
        checkpoint.setTenantId(tenantId);
        checkpoint.setBranchId(request.branchId());
        checkpoint.setStageRunId(request.stageRunId());
        checkpoint.setManifestJson(toJson(Map.of(
                "kind", "branch_version",
                "workspace_id", request.workspaceId(),
                "branch_id", request.branchId(),
                "stage_run_id", request.stageRunId(),
                "state_commit_id", request.stateCommitId(),
                "artifacts", request.artifactIds() == null ? List.of() : request.artifactIds(),
                "manifest_id", request.manifestId(),
                "lineage_ids", request.lineageIds() == null ? List.of() : request.lineageIds(),
                "summary", request.summary() == null ? "" : request.summary()
        )));
        return new AgentStateDtos.IdResponse(checkpointRepository.save(checkpoint).getId());
    }

    @Transactional
    public AgentStateDtos.IdResponse recordRuntimeEvent(String tenantId, AgentStateDtos.RecordRuntimeEventRequest request) {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("kind", request.kind());
        manifest.put("session_id", request.sessionId());
        putIfPresent(manifest, "message_id", request.messageId());
        putIfPresent(manifest, "call_id", request.callId());
        putIfPresent(manifest, "tool", request.tool());
        putIfPresent(manifest, "parent_session_id", request.parentSessionId());
        putIfPresent(manifest, "child_session_id", request.childSessionId());
        putIfPresent(manifest, "branch_id", request.branchId());
        putIfPresent(manifest, "status", request.status());
        putIfPresent(manifest, "summary", request.summary());
        putIfPresent(manifest, "input", request.input());
        putIfPresent(manifest, "output", request.output());
        putIfPresent(manifest, "artifact", request.artifact());
        putIfPresent(manifest, "metadata", request.metadata());

        AgentCheckpointEntity checkpoint = new AgentCheckpointEntity();
        checkpoint.setTenantId(tenantId);
        checkpoint.setBranchId(request.branchId() == null ? "session:" + request.sessionId() : request.branchId());
        checkpoint.setStageRunId(request.messageId());
        checkpoint.setManifestJson(toJson(manifest));
        return new AgentStateDtos.IdResponse(checkpointRepository.save(checkpoint).getId());
    }

    public AgentStateDtos.RestorePlanResponse restoreCheckpoint(String tenantId, String checkpointId) {
        AgentCheckpointEntity checkpoint = checkpointRepository.findByIdAndTenantId(checkpointId, tenantId)
                .orElseThrow(() -> new NotFoundException("Checkpoint not found: " + checkpointId));
        Map<String, Object> manifest = fromJsonObject(checkpoint.getManifestJson());
        List<String> restorableRefs = stringList(manifest.get("artifacts"));
        List<String> missingRefs = stringList(manifest.get("missing"));
        return new AgentStateDtos.RestorePlanResponse(
                checkpoint.getId(), restorableRefs, missingRefs, missingRefs.isEmpty());
    }

    @Transactional
    public AgentStateDtos.EvidencePacketResponse createEvidencePacket(
            String tenantId, AgentStateDtos.CreateEvidencePacketRequest request) {
        AgentEvidencePacketEntity packet = new AgentEvidencePacketEntity();
        packet.setTenantId(tenantId);
        packet.setTaskRunId(request.taskRunId());
        packet.setBranchId(request.branchId());
        packet.setClaim(request.claim());
        packet.setEvidenceRefsJson(toJson(request.evidenceRefs() == null ? List.of() : request.evidenceRefs()));
        AgentEvidencePacketEntity saved = evidencePacketRepository.save(packet);
        return new AgentStateDtos.EvidencePacketResponse(saved.getId(), saved.getStatus());
    }

    public AgentStateDtos.PolicyDecisionResponse evaluateEvidence(String tenantId, String evidencePacketId) {
        AgentEvidencePacketEntity packet = evidencePacketRepository.findByIdAndTenantId(evidencePacketId, tenantId)
                .orElseThrow(() -> new NotFoundException("Evidence packet not found: " + evidencePacketId));
        List<String> evidenceRefs = fromJsonList(packet.getEvidenceRefsJson());
        if (evidenceRefs.isEmpty()) {
            return new AgentStateDtos.PolicyDecisionResponse(false, "missing verified evidence");
        }
        return new AgentStateDtos.PolicyDecisionResponse(true, "evidence verified");
    }

    @Transactional
    public AgentStateDtos.PolicyDecisionResponse checkPermission(
            String tenantId, AgentStateDtos.CheckPermissionRequest request) {
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

        return new AgentStateDtos.PolicyDecisionResponse(!blocked, reason);
    }

    @Transactional
    public AgentStateDtos.IdResponse appendAuditEvent(String tenantId, AgentStateDtos.AppendAuditEventRequest request) {
        AgentAuditEventEntity event = new AgentAuditEventEntity();
        event.setTenantId(tenantId);
        event.setTaskRunId(request.taskRunId());
        event.setBranchId(request.branchId());
        event.setAction(request.action());
        event.setResult(request.result());
        event.setReason(request.reason());
        return new AgentStateDtos.IdResponse(auditEventRepository.save(event).getId());
    }

    public List<AgentStateDtos.IdResponse> listAuditEvents(String tenantId, String taskRunId) {
        return auditEventRepository.findByTenantIdAndTaskRunIdOrderByCreatedAtAsc(tenantId, taskRunId)
                .stream()
                .map(event -> new AgentStateDtos.IdResponse(event.getId()))
                .toList();
    }

    private boolean isHighRisk(String riskLevel) {
        return "high".equalsIgnoreCase(riskLevel);
    }

    private boolean isDestructive(String action) {
        String normalized = action == null ? "" : action.toLowerCase();
        return normalized.contains("drop") || normalized.contains("delete") || normalized.contains("truncate");
    }

    private AgentStateDtos.AgentAppResponse agentAppResponse(AgentAppEntity entity) {
        return new AgentStateDtos.AgentAppResponse(
                entity.getId(),
                entity.getKey(),
                entity.getDisplayName(),
                entity.getType(),
                entity.getVersion(),
                entity.getStatus(),
                fromJsonList(entity.getStageSchemaJson()));
    }

    private static final List<BuiltInAgentApp> BUILT_IN_AGENT_APPS = List.of(
            new BuiltInAgentApp(
                    "paperbench",
                    "PaperBench 论文复现",
                    "benchmark",
                    "0.1.0",
                    List.of("paper_parse", "claim_extract", "experiment_run", "evidence_pack", "report_gate")),
            new BuiltInAgentApp(
                    "data",
                    "数据发布检查",
                    "data",
                    "0.1.0",
                    List.of("schema_resolve", "context_pack", "sql_validate", "policy_check", "publish_gate")));

    private AgentAppEntity toEntity(String tenantId, BuiltInAgentApp app) {
        AgentAppEntity entity = new AgentAppEntity();
        entity.setTenantId(tenantId);
        entity.setKey(app.key());
        entity.setDisplayName(app.displayName());
        entity.setType(app.type());
        entity.setVersion(app.version());
        entity.setStatus("active");
        entity.setStageSchemaJson(toJson(app.stageSchema()));
        return entity;
    }

    private record BuiltInAgentApp(String key, String displayName, String type, String version, List<String> stageSchema) {}

    private AgentStateDtos.TaskRunSummaryResponse taskRunSummary(String tenantId, AgentTaskRunEntity task) {
        List<AgentStageRunEntity> stages = stageRunRepository.findByTenantIdAndTaskRunIdOrderByCreatedAtAsc(tenantId, task.getId());
        AgentWorkspaceEntity workspace = workspaceRepository.findByTenantIdAndTaskRunId(tenantId, task.getId()).orElse(null);
        List<AgentWorkspaceBranchEntity> branches = workspace == null
                ? List.of()
                : branchRepository.findByTenantIdAndWorkspaceIdOrderByCreatedAtAsc(tenantId, workspace.getId());
        return taskRunSummary(
                task,
                stages,
                workspace,
                branches,
                evidencePacketRepository.findByTenantIdAndTaskRunIdOrderByCreatedAtAsc(tenantId, task.getId()),
                auditEventRepository.findByTenantIdAndTaskRunIdOrderByCreatedAtAsc(tenantId, task.getId()));
    }

    private AgentStateDtos.TaskRunSummaryResponse taskRunSummary(
            AgentTaskRunEntity task,
            List<AgentStageRunEntity> stages,
            AgentWorkspaceEntity workspace,
            List<AgentWorkspaceBranchEntity> branches,
            List<AgentEvidencePacketEntity> evidencePackets,
            List<AgentAuditEventEntity> auditEvents) {
        AgentStageRunEntity currentStage = stages.isEmpty() ? null : stages.get(stages.size() - 1);
        AgentWorkspaceBranchEntity latestBranch = branches.isEmpty() ? null : branches.get(branches.size() - 1);
        AgentEvidencePacketEntity latestEvidence = evidencePackets.isEmpty() ? null : evidencePackets.get(evidencePackets.size() - 1);
        String derivedAuditResult = derivedAuditResult(auditEvents);
        return new AgentStateDtos.TaskRunSummaryResponse(
                task.getId(),
                task.getGoal(),
                task.getHarnessId(),
                derivedStatus(task.getStatus(), derivedAuditResult),
                task.getAgentAppId(),
                currentStage == null ? null : currentStage.getStageId(),
                workspace == null ? null : workspace.getId(),
                branches.size(),
                evidencePackets.size(),
                latestBranch == null ? null : latestBranch.getId(),
                latestEvidence == null ? null : latestEvidence.getId(),
                derivedAuditResult,
                task.getCreatedAt());
    }

    private String derivedStatus(String storedStatus, String auditResult) {
        if ("allowed".equals(auditResult) || "completed".equals(auditResult)) return "completed";
        if ("blocked".equals(auditResult) || "failed".equals(auditResult)) return "blocked";
        return storedStatus;
    }

    private String derivedAuditResult(List<AgentAuditEventEntity> auditEvents) {
        return auditEvents.stream()
                .filter(event -> "paperbench_report_gate".equals(event.getAction()))
                .reduce((first, second) -> second)
                .map(AgentAuditEventEntity::getResult)
                .or(() -> auditEvents.stream()
                        .filter(event -> "workflow_trace:workflow_run".equals(event.getAction()) && !"started".equals(event.getResult()))
                        .reduce((first, second) -> second)
                        .map(AgentAuditEventEntity::getResult))
                .or(() -> auditEvents.stream()
                        .filter(event -> !event.getAction().startsWith("workflow_trace:"))
                        .reduce((first, second) -> second)
                        .map(AgentAuditEventEntity::getResult))
                .orElseGet(() -> auditEvents.isEmpty() ? null : auditEvents.get(auditEvents.size() - 1).getResult());
    }

    private AgentStateDtos.StageRunDetailResponse stageRunDetail(AgentStageRunEntity stage) {
        return new AgentStateDtos.StageRunDetailResponse(
                stage.getId(),
                stage.getTaskRunId(),
                stage.getStageId(),
                stage.getStatus(),
                stage.getBranchId(),
                stage.getContextPackId(),
                stage.getCreatedAt());
    }

    private AgentStateDtos.WorkspaceDetailResponse workspaceDetail(AgentWorkspaceEntity workspace, List<AgentWorkspaceBranchEntity> branches) {
        return new AgentStateDtos.WorkspaceDetailResponse(
                workspace.getId(),
                workspace.getTaskRunId(),
                branches.stream()
                        .filter(branch -> "root".equals(branch.getName()))
                        .findFirst()
                        .map(AgentWorkspaceBranchEntity::getId)
                        .orElse(null),
                workspace.getCreatedAt());
    }

    private AgentStateDtos.BranchDetailResponse branchDetail(AgentWorkspaceBranchEntity branch) {
        return new AgentStateDtos.BranchDetailResponse(
                branch.getId(),
                branch.getWorkspaceId(),
                branch.getParentBranchId(),
                branch.getStageRunId(),
                branch.getName(),
                branch.getHypothesis(),
                branch.getStatus(),
                branch.getCreatedAt());
    }

    private AgentStateDtos.StateCommitDetailResponse stateCommitDetail(AgentStateCommitEntity commit) {
        return new AgentStateDtos.StateCommitDetailResponse(
                commit.getId(),
                commit.getTaskRunId(),
                commit.getStageRunId(),
                commit.getBranchId(),
                commit.getSummary(),
                commit.getCreatedAt());
    }

    private AgentStateDtos.ArtifactDetailResponse artifactDetail(AgentArtifactRefEntity artifact) {
        return new AgentStateDtos.ArtifactDetailResponse(
                artifact.getId(),
                artifact.getTaskRunId(),
                artifact.getStageRunId(),
                artifact.getBranchId(),
                artifact.getKind(),
                artifact.getCreatedAt());
    }

    private AgentStateDtos.EvidencePacketDetailResponse evidencePacketDetail(AgentEvidencePacketEntity packet) {
        return new AgentStateDtos.EvidencePacketDetailResponse(
                packet.getId(),
                packet.getTaskRunId(),
                packet.getBranchId(),
                packet.getClaim(),
                packet.getStatus(),
                fromJsonList(packet.getEvidenceRefsJson()),
                packet.getCreatedAt());
    }

    private AgentStateDtos.AuditEventDetailResponse auditEventDetail(AgentAuditEventEntity event) {
        return new AgentStateDtos.AuditEventDetailResponse(
                event.getId(),
                event.getTaskRunId(),
                event.getBranchId(),
                event.getAction(),
                event.getResult(),
                event.getReason(),
                event.getCreatedAt());
    }

    private AgentAppEntity findAgentApp(String tenantId, String appId) {
        return agentAppRepository.findByIdAndTenantId(appId, tenantId)
                .orElseThrow(() -> new NotFoundException("Agent app not found: " + appId));
    }

    private String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String resolveParentBranchId(String tenantId, String workspaceId, String requestedParentBranchId) {
        if (requestedParentBranchId != null && !requestedParentBranchId.isBlank()) {
            return requestedParentBranchId;
        }
        return branchRepository.findByTenantIdAndWorkspaceIdOrderByCreatedAtAsc(tenantId, workspaceId).stream()
                .filter(branch -> "root".equals(branch.getName()))
                .findFirst()
                .map(AgentWorkspaceBranchEntity::getId)
                .orElse(null);
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
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
