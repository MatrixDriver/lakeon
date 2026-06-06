package com.lakeon.agentstate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lakeon.model.entity.DatabaseEntity;
import com.lakeon.model.enums.DatabaseStatus;
import com.lakeon.repository.DatabaseRepository;
import com.lakeon.service.exception.NotFoundException;
import com.lakeon.service.exception.BadRequestException;
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
    private final DatabaseRepository databaseRepository;
    private final AgentStateDataPlaneStore dataPlaneStore;
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
                             DatabaseRepository databaseRepository,
                             AgentStateDataPlaneStore dataPlaneStore,
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
        this.databaseRepository = databaseRepository;
        this.dataPlaneStore = dataPlaneStore;
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
                      DatabaseRepository databaseRepository,
                      AgentStateDataPlaneStore dataPlaneStore,
                      ObjectMapper objectMapper) {
        this(taskRunRepository, null, stageRunRepository, workspaceRepository, branchRepository,
                contextNodeRepository, contextPackRepository, checkpointRepository, stateCommitRepository,
                artifactRefRepository, lineageEdgeRepository, evidencePacketRepository, policyDecisionRepository,
                auditEventRepository, databaseRepository, dataPlaneStore, objectMapper);
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
                      AgentAuditEventRepository auditEventRepository,
                      DatabaseRepository databaseRepository,
                      AgentStateDataPlaneStore dataPlaneStore) {
        this(taskRunRepository, agentAppRepository, null, workspaceRepository, branchRepository, contextNodeRepository,
                contextPackRepository, checkpointRepository, stateCommitRepository, artifactRefRepository,
                lineageEdgeRepository, evidencePacketRepository, policyDecisionRepository, auditEventRepository,
                databaseRepository, dataPlaneStore,
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
        AgentWorkspaceEntity workspace = workspaceRepository.findByTenantIdAndTaskRunId(tenantId, taskRunId).orElse(null);
        AgentStateDtos.DataPlaneDetail detail = workspace == null || workspace.getDatabaseId() == null
                ? new AgentStateDtos.DataPlaneDetail(List.of(), List.of(), List.of(), List.of(), List.of(), List.of())
                : dataPlaneStore.loadDetail(findDataPlaneDatabase(tenantId, workspace.getDatabaseId()), taskRunId, workspace);
        return new AgentStateDtos.TaskRunDetailResponse(
                taskRunSummary(task, detail.stages(), workspace, detail.branches(), detail.evidencePackets(), detail.auditEvents()),
                detail.stages(),
                workspace == null ? null : workspaceDetail(workspace),
                detail.branches(),
                detail.commits(),
                detail.artifacts(),
                detail.evidencePackets(),
                detail.auditEvents());
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
        AgentWorkspaceEntity workspace = workspaceRepository.findByTenantIdAndTaskRunId(tenantId, taskRunId).orElse(null);
        DatabaseEntity database = workspace == null || workspace.getDatabaseId() == null
                ? resolveDataPlaneDatabase(tenantId, null)
                : findDataPlaneDatabase(tenantId, workspace.getDatabaseId());
        AgentStateDtos.StageRunDetailResponse dataPlaneStage = dataPlaneStore.createStageRun(
                database,
                taskRunId,
                request.stageId(),
                request.branchId(),
                request.contextPackId());
        return new AgentStateDtos.StageRunResponse(
                dataPlaneStage.id(), dataPlaneStage.taskRunId(), dataPlaneStage.stageId(), dataPlaneStage.status(),
                dataPlaneStage.branchId(), dataPlaneStage.contextPackId());
    }

    @Transactional
    public AgentStateDtos.WorkspaceResponse createWorkspace(String tenantId, AgentStateDtos.CreateWorkspaceRequest request) {
        DatabaseEntity database = resolveDataPlaneDatabase(tenantId, request.databaseId());
        AgentStateDataPlaneStore.DataPlaneWorkspace dataPlaneWorkspace = dataPlaneStore.createWorkspace(database, request.taskRunId());
        AgentWorkspaceEntity workspace = new AgentWorkspaceEntity();
        workspace.setId(dataPlaneWorkspace.id());
        workspace.setTenantId(tenantId);
        workspace.setTaskRunId(request.taskRunId());
        workspace.setDatabaseId(database.getId());
        workspace.setRootBranchId(dataPlaneWorkspace.rootBranchId());
        AgentWorkspaceEntity savedWorkspace = workspaceRepository.save(workspace);

        return new AgentStateDtos.WorkspaceResponse(savedWorkspace.getId(), dataPlaneWorkspace.rootBranchId());
    }

    @Transactional
    public AgentStateDtos.BranchResponse forkBranch(String tenantId, AgentStateDtos.ForkBranchRequest request) {
        AgentWorkspaceEntity workspace = findWorkspace(tenantId, request.workspaceId());
        AgentStateDtos.BranchDetailResponse branch = dataPlaneStore.forkBranch(
                findDataPlaneDatabase(tenantId, workspace.getDatabaseId()),
                request.workspaceId(),
                request.parentBranchId(),
                request.stageRunId(),
                request.hypothesis());
        return new AgentStateDtos.BranchResponse(branch.id());
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
        return dataPlaneStore.appendStateCommit(
                dataPlaneDatabaseForTask(tenantId, request.taskRunId()),
                request.taskRunId(),
                request.stageRunId(),
                request.branchId(),
                request.summary());
    }

    @Transactional
    public AgentStateDtos.IdResponse recordArtifact(String tenantId, AgentStateDtos.RecordArtifactRequest request) {
        return dataPlaneStore.recordArtifact(
                dataPlaneDatabaseForTask(tenantId, request.taskRunId()),
                request.taskRunId(),
                request.stageRunId(),
                request.branchId(),
                request.kind());
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
        String taskRunId = "checkpoint:" + request.branchId();
        AgentStateDtos.IdResponse response = dataPlaneStore.appendManifestVersion(
                resolveDataPlaneDatabase(tenantId, null),
                taskRunId,
                request.branchId(),
                request.stageRunId(),
                "checkpoint",
                "checkpoint",
                request.manifest() == null ? Map.of() : request.manifest());
        return new AgentStateDtos.CheckpointResponse(response.id());
    }

    @Transactional
    public AgentStateDtos.IdResponse snapshotManifest(String tenantId, AgentStateDtos.SnapshotManifestRequest request) {
        return dataPlaneStore.appendManifestVersion(
                dataPlaneDatabaseForTask(tenantId, request.taskRunId()),
                request.taskRunId(),
                request.branchId(),
                request.stageRunId(),
                "artifact_manifest",
                "snapshot manifest",
                Map.of(
                "task_run_id", request.taskRunId(),
                "stage_run_id", request.stageRunId(),
                "branch_id", request.branchId(),
                "artifacts", request.artifactIds() == null ? List.of() : request.artifactIds()
        ));
    }

    @Transactional
    public AgentStateDtos.IdResponse recordBranchVersion(String tenantId, AgentStateDtos.RecordBranchVersionRequest request) {
        AgentWorkspaceEntity workspace = findWorkspace(tenantId, request.workspaceId());
        return dataPlaneStore.appendManifestVersion(
                findDataPlaneDatabase(tenantId, workspace.getDatabaseId()),
                workspace.getTaskRunId(),
                request.branchId(),
                request.stageRunId(),
                "branch_version",
                request.summary() == null ? "" : request.summary(),
                Map.of(
                "kind", "branch_version",
                "workspace_id", request.workspaceId(),
                "branch_id", request.branchId(),
                "stage_run_id", request.stageRunId(),
                "state_commit_id", request.stateCommitId(),
                "artifacts", request.artifactIds() == null ? List.of() : request.artifactIds(),
                "manifest_id", request.manifestId(),
                "lineage_ids", request.lineageIds() == null ? List.of() : request.lineageIds(),
                "summary", request.summary() == null ? "" : request.summary()
        ));
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

        String taskRunId = "runtime:" + request.sessionId();
        return dataPlaneStore.appendManifestVersion(
                resolveDataPlaneDatabase(tenantId, null),
                taskRunId,
                request.branchId() == null ? "session:" + request.sessionId() : request.branchId(),
                request.messageId(),
                "runtime_event",
                request.summary(),
                manifest);
    }

    public AgentStateDtos.RestorePlanResponse restoreCheckpoint(String tenantId, String checkpointId) {
        return databaseRepository.findAllByTenantIdAndStatus(tenantId, DatabaseStatus.RUNNING)
                .stream()
                .map(database -> dataPlaneStore.restorePlan(database, checkpointId))
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Checkpoint not found: " + checkpointId));
    }

    @Transactional
    public AgentStateDtos.EvidencePacketResponse createEvidencePacket(
            String tenantId, AgentStateDtos.CreateEvidencePacketRequest request) {
        AgentStateDtos.IdResponse response = dataPlaneStore.createEvidencePacket(
                dataPlaneDatabaseForTask(tenantId, request.taskRunId()),
                request.taskRunId(),
                request.branchId(),
                request.claim(),
                request.evidenceRefs());
        return new AgentStateDtos.EvidencePacketResponse(response.id(), "pending");
    }

    public AgentStateDtos.PolicyDecisionResponse evaluateEvidence(String tenantId, String evidencePacketId) {
        AgentStateDtos.EvidencePacketDetailResponse packet = databaseRepository.findAllByTenantIdAndStatus(tenantId, DatabaseStatus.RUNNING)
                .stream()
                .map(database -> dataPlaneStore.findEvidencePacket(database, evidencePacketId))
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Evidence packet not found: " + evidencePacketId));
        List<String> evidenceRefs = packet.evidenceRefs();
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
        return dataPlaneStore.appendAuditEvent(
                dataPlaneDatabaseForTask(tenantId, request.taskRunId()),
                request.taskRunId(),
                request.branchId(),
                request.action(),
                request.result(),
                request.reason());
    }

    public List<AgentStateDtos.IdResponse> listAuditEvents(String tenantId, String taskRunId) {
        AgentWorkspaceEntity workspace = workspaceRepository.findByTenantIdAndTaskRunId(tenantId, taskRunId).orElse(null);
        if (workspace == null || workspace.getDatabaseId() == null) {
            return List.of();
        }
        return dataPlaneStore.loadDetail(findDataPlaneDatabase(tenantId, workspace.getDatabaseId()), taskRunId, workspace)
                .auditEvents()
                .stream()
                .map(event -> new AgentStateDtos.IdResponse(event.id()))
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
        AgentWorkspaceEntity workspace = workspaceRepository.findByTenantIdAndTaskRunId(tenantId, task.getId()).orElse(null);
        AgentStateDtos.DataPlaneDetail detail = workspace == null || workspace.getDatabaseId() == null
                ? new AgentStateDtos.DataPlaneDetail(List.of(), List.of(), List.of(), List.of(), List.of(), List.of())
                : dataPlaneStore.loadDetail(findDataPlaneDatabase(tenantId, workspace.getDatabaseId()), task.getId(), workspace);
        return taskRunSummary(
                task,
                detail.stages(),
                workspace,
                detail.branches(),
                detail.evidencePackets(),
                detail.auditEvents());
    }

    private AgentStateDtos.TaskRunSummaryResponse taskRunSummary(
            AgentTaskRunEntity task,
            List<AgentStateDtos.StageRunDetailResponse> stages,
            AgentWorkspaceEntity workspace,
            List<AgentStateDtos.BranchDetailResponse> branches,
            List<AgentStateDtos.EvidencePacketDetailResponse> evidencePackets,
            List<AgentStateDtos.AuditEventDetailResponse> auditEvents) {
        AgentStateDtos.StageRunDetailResponse currentStage = stages.isEmpty() ? null : stages.get(stages.size() - 1);
        AgentStateDtos.BranchDetailResponse latestBranch = branches.isEmpty() ? null : branches.get(branches.size() - 1);
        AgentStateDtos.EvidencePacketDetailResponse latestEvidence = evidencePackets.isEmpty() ? null : evidencePackets.get(evidencePackets.size() - 1);
        String derivedAuditResult = derivedAuditResult(auditEvents);
        return new AgentStateDtos.TaskRunSummaryResponse(
                task.getId(),
                task.getGoal(),
                task.getHarnessId(),
                derivedStatus(task.getStatus(), derivedAuditResult),
                task.getAgentAppId(),
                currentStage == null ? null : currentStage.stageId(),
                workspace == null ? null : workspace.getId(),
                branches.size(),
                evidencePackets.size(),
                latestBranch == null ? null : latestBranch.id(),
                latestEvidence == null ? null : latestEvidence.id(),
                derivedAuditResult,
                task.getCreatedAt());
    }

    private String derivedStatus(String storedStatus, String auditResult) {
        if ("allowed".equals(auditResult) || "completed".equals(auditResult)) return "completed";
        if ("blocked".equals(auditResult) || "failed".equals(auditResult)) return "blocked";
        return storedStatus;
    }

    private String derivedAuditResult(List<AgentStateDtos.AuditEventDetailResponse> auditEvents) {
        return auditEvents.stream()
                .filter(event -> "paperbench_report_gate".equals(event.action()))
                .reduce((first, second) -> second)
                .map(AgentStateDtos.AuditEventDetailResponse::result)
                .or(() -> auditEvents.stream()
                        .filter(event -> "workflow_trace:workflow_run".equals(event.action()) && !"started".equals(event.result()))
                        .reduce((first, second) -> second)
                        .map(AgentStateDtos.AuditEventDetailResponse::result))
                .or(() -> auditEvents.stream()
                        .filter(event -> !event.action().startsWith("workflow_trace:"))
                        .reduce((first, second) -> second)
                        .map(AgentStateDtos.AuditEventDetailResponse::result))
                .orElseGet(() -> auditEvents.isEmpty() ? null : auditEvents.get(auditEvents.size() - 1).result());
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

    private AgentStateDtos.WorkspaceDetailResponse workspaceDetail(AgentWorkspaceEntity workspace) {
        return new AgentStateDtos.WorkspaceDetailResponse(
                workspace.getId(),
                workspace.getTaskRunId(),
                workspace.getRootBranchId(),
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

    private AgentWorkspaceEntity findWorkspace(String tenantId, String workspaceId) {
        return workspaceRepository.findById(workspaceId)
                .filter(workspace -> tenantId.equals(workspace.getTenantId()))
                .orElseThrow(() -> new NotFoundException("Agent workspace not found: " + workspaceId));
    }

    private DatabaseEntity dataPlaneDatabaseForTask(String tenantId, String taskRunId) {
        AgentWorkspaceEntity workspace = workspaceRepository.findByTenantIdAndTaskRunId(tenantId, taskRunId)
                .orElseThrow(() -> new NotFoundException("Agent workspace not found for task run: " + taskRunId));
        return findDataPlaneDatabase(tenantId, workspace.getDatabaseId());
    }

    private DatabaseEntity resolveDataPlaneDatabase(String tenantId, String requestedDatabaseId) {
        if (requestedDatabaseId != null && !requestedDatabaseId.isBlank()) {
            return findDataPlaneDatabase(tenantId, requestedDatabaseId);
        }
        return databaseRepository.findAllByTenantIdAndStatus(tenantId, DatabaseStatus.RUNNING).stream()
                .findFirst()
                .orElseThrow(() -> new BadRequestException("Agent state requires a running DBay data-plane database"));
    }

    private DatabaseEntity findDataPlaneDatabase(String tenantId, String databaseId) {
        if (databaseId == null || databaseId.isBlank()) {
            throw new BadRequestException("Agent state workspace is not bound to a data-plane database");
        }
        return databaseRepository.findByIdAndTenantId(databaseId, tenantId)
                .orElseThrow(() -> new NotFoundException("Data-plane database not found: " + databaseId));
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
