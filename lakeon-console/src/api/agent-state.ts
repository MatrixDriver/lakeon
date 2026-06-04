import client from './client'

export interface AgentApp {
  id: string
  key: string
  displayName: string
  type: string
  version: string
  status: string
  stageSchema: string[]
}

interface AgentAppResponse {
  id: string
  key: string
  display_name?: string
  displayName?: string
  type: string
  version: string
  status: string
  stage_schema?: string[]
  stageSchema?: string[]
}

export interface TaskRunSummary {
  id: string
  goal: string
  harnessId: string
  status: string
  agentAppId?: string | null
  currentStageId?: string | null
  workspaceId?: string | null
  branchCount: number
  evidenceCount: number
  latestBranchId?: string | null
  latestEvidencePacketId?: string | null
  latestAuditResult?: string | null
  createdAt?: string | null
}

export interface StageRunDetail {
  id: string
  taskRunId: string
  stageId: string
  status: string
  branchId?: string | null
  contextPackId?: string | null
  createdAt?: string | null
}

export interface WorkspaceDetail {
  id: string
  taskRunId: string
  rootBranchId?: string | null
  createdAt?: string | null
}

export interface BranchDetail {
  id: string
  workspaceId: string
  parentBranchId?: string | null
  stageRunId?: string | null
  name: string
  hypothesis?: string | null
  status: string
  createdAt?: string | null
}

export interface StateCommitDetail {
  id: string
  taskRunId: string
  stageRunId: string
  branchId: string
  summary?: string | null
  createdAt?: string | null
}

export interface ArtifactDetail {
  id: string
  taskRunId: string
  stageRunId: string
  branchId: string
  kind: string
  createdAt?: string | null
}

export interface EvidencePacketDetail {
  id: string
  taskRunId: string
  branchId?: string | null
  claim?: string | null
  status: string
  evidenceRefs: string[]
  createdAt?: string | null
}

export interface AuditEventDetail {
  id: string
  taskRunId: string
  branchId?: string | null
  action: string
  result: string
  reason?: string | null
  createdAt?: string | null
}

export interface TaskRunDetail {
  task: TaskRunSummary
  stages: StageRunDetail[]
  workspace?: WorkspaceDetail | null
  branches: BranchDetail[]
  commits: StateCommitDetail[]
  artifacts: ArtifactDetail[]
  evidencePackets: EvidencePacketDetail[]
  auditEvents: AuditEventDetail[]
}

interface TaskRunSummaryResponse {
  id: string
  goal?: string
  harness_id?: string
  harnessId?: string
  status?: string
  agent_app_id?: string | null
  agentAppId?: string | null
  current_stage_id?: string | null
  currentStageId?: string | null
  workspace_id?: string | null
  workspaceId?: string | null
  branch_count?: number
  branchCount?: number
  evidence_count?: number
  evidenceCount?: number
  latest_branch_id?: string | null
  latestBranchId?: string | null
  latest_evidence_packet_id?: string | null
  latestEvidencePacketId?: string | null
  latest_audit_result?: string | null
  latestAuditResult?: string | null
  created_at?: string | null
  createdAt?: string | null
}

interface StageRunDetailResponse {
  id: string
  task_run_id?: string
  taskRunId?: string
  stage_id?: string
  stageId?: string
  status?: string
  branch_id?: string | null
  branchId?: string | null
  context_pack_id?: string | null
  contextPackId?: string | null
  created_at?: string | null
  createdAt?: string | null
}

interface WorkspaceDetailResponse {
  id: string
  task_run_id?: string
  taskRunId?: string
  root_branch_id?: string | null
  rootBranchId?: string | null
  created_at?: string | null
  createdAt?: string | null
}

interface BranchDetailResponse {
  id: string
  workspace_id?: string
  workspaceId?: string
  parent_branch_id?: string | null
  parentBranchId?: string | null
  stage_run_id?: string | null
  stageRunId?: string | null
  name?: string
  hypothesis?: string | null
  status?: string
  created_at?: string | null
  createdAt?: string | null
}

interface StateCommitDetailResponse {
  id: string
  task_run_id?: string
  taskRunId?: string
  stage_run_id?: string
  stageRunId?: string
  branch_id?: string
  branchId?: string
  summary?: string | null
  created_at?: string | null
  createdAt?: string | null
}

interface ArtifactDetailResponse {
  id: string
  task_run_id?: string
  taskRunId?: string
  stage_run_id?: string
  stageRunId?: string
  branch_id?: string
  branchId?: string
  kind?: string
  created_at?: string | null
  createdAt?: string | null
}

interface EvidencePacketDetailResponse {
  id: string
  task_run_id?: string
  taskRunId?: string
  branch_id?: string | null
  branchId?: string | null
  claim?: string | null
  status?: string
  evidence_refs?: string[]
  evidenceRefs?: string[]
  created_at?: string | null
  createdAt?: string | null
}

interface AuditEventDetailResponse {
  id: string
  task_run_id?: string
  taskRunId?: string
  branch_id?: string | null
  branchId?: string | null
  action?: string
  result?: string
  reason?: string | null
  created_at?: string | null
  createdAt?: string | null
}

interface TaskRunDetailResponse {
  task: TaskRunSummaryResponse
  stages?: StageRunDetailResponse[]
  workspace?: WorkspaceDetailResponse | null
  branches?: BranchDetailResponse[]
  commits?: StateCommitDetailResponse[]
  artifacts?: ArtifactDetailResponse[]
  evidence_packets?: EvidencePacketDetailResponse[]
  evidencePackets?: EvidencePacketDetailResponse[]
  audit_events?: AuditEventDetailResponse[]
  auditEvents?: AuditEventDetailResponse[]
}

function normalizeApp(app: AgentAppResponse): AgentApp {
  return {
    id: app.id,
    key: app.key,
    displayName: app.displayName || app.display_name || app.key,
    type: app.type,
    version: app.version,
    status: app.status,
    stageSchema: app.stageSchema || app.stage_schema || [],
  }
}

function normalizeTask(task: TaskRunSummaryResponse): TaskRunSummary {
  return {
    id: task.id,
    goal: task.goal || task.id,
    harnessId: task.harnessId || task.harness_id || 'custom',
    status: task.status || 'running',
    agentAppId: task.agentAppId || task.agent_app_id || null,
    currentStageId: task.currentStageId || task.current_stage_id || null,
    workspaceId: task.workspaceId || task.workspace_id || null,
    branchCount: task.branchCount ?? task.branch_count ?? 0,
    evidenceCount: task.evidenceCount ?? task.evidence_count ?? 0,
    latestBranchId: task.latestBranchId || task.latest_branch_id || null,
    latestEvidencePacketId: task.latestEvidencePacketId || task.latest_evidence_packet_id || null,
    latestAuditResult: task.latestAuditResult || task.latest_audit_result || null,
    createdAt: task.createdAt || task.created_at || null,
  }
}

function normalizeStage(stage: StageRunDetailResponse): StageRunDetail {
  return {
    id: stage.id,
    taskRunId: stage.taskRunId || stage.task_run_id || '',
    stageId: stage.stageId || stage.stage_id || stage.id,
    status: stage.status || 'running',
    branchId: stage.branchId || stage.branch_id || null,
    contextPackId: stage.contextPackId || stage.context_pack_id || null,
    createdAt: stage.createdAt || stage.created_at || null,
  }
}

function normalizeWorkspace(workspace: WorkspaceDetailResponse | null | undefined): WorkspaceDetail | null {
  if (!workspace) return null
  return {
    id: workspace.id,
    taskRunId: workspace.taskRunId || workspace.task_run_id || '',
    rootBranchId: workspace.rootBranchId || workspace.root_branch_id || null,
    createdAt: workspace.createdAt || workspace.created_at || null,
  }
}

function normalizeBranch(branch: BranchDetailResponse): BranchDetail {
  return {
    id: branch.id,
    workspaceId: branch.workspaceId || branch.workspace_id || '',
    parentBranchId: branch.parentBranchId || branch.parent_branch_id || null,
    stageRunId: branch.stageRunId || branch.stage_run_id || null,
    name: branch.name || branch.id,
    hypothesis: branch.hypothesis || null,
    status: branch.status || 'active',
    createdAt: branch.createdAt || branch.created_at || null,
  }
}

function normalizeCommit(commit: StateCommitDetailResponse): StateCommitDetail {
  return {
    id: commit.id,
    taskRunId: commit.taskRunId || commit.task_run_id || '',
    stageRunId: commit.stageRunId || commit.stage_run_id || '',
    branchId: commit.branchId || commit.branch_id || '',
    summary: commit.summary || null,
    createdAt: commit.createdAt || commit.created_at || null,
  }
}

function normalizeArtifact(artifact: ArtifactDetailResponse): ArtifactDetail {
  return {
    id: artifact.id,
    taskRunId: artifact.taskRunId || artifact.task_run_id || '',
    stageRunId: artifact.stageRunId || artifact.stage_run_id || '',
    branchId: artifact.branchId || artifact.branch_id || '',
    kind: artifact.kind || 'artifact',
    createdAt: artifact.createdAt || artifact.created_at || null,
  }
}

function normalizeEvidencePacket(packet: EvidencePacketDetailResponse): EvidencePacketDetail {
  return {
    id: packet.id,
    taskRunId: packet.taskRunId || packet.task_run_id || '',
    branchId: packet.branchId || packet.branch_id || null,
    claim: packet.claim || null,
    status: packet.status || 'pending',
    evidenceRefs: packet.evidenceRefs || packet.evidence_refs || [],
    createdAt: packet.createdAt || packet.created_at || null,
  }
}

function normalizeAuditEvent(event: AuditEventDetailResponse): AuditEventDetail {
  return {
    id: event.id,
    taskRunId: event.taskRunId || event.task_run_id || '',
    branchId: event.branchId || event.branch_id || null,
    action: event.action || 'event',
    result: event.result || 'recorded',
    reason: event.reason || null,
    createdAt: event.createdAt || event.created_at || null,
  }
}

function normalizeTaskDetail(detail: TaskRunDetailResponse): TaskRunDetail {
  return {
    task: normalizeTask(detail.task),
    stages: (detail.stages || []).map(normalizeStage),
    workspace: normalizeWorkspace(detail.workspace),
    branches: (detail.branches || []).map(normalizeBranch),
    commits: (detail.commits || []).map(normalizeCommit),
    artifacts: (detail.artifacts || []).map(normalizeArtifact),
    evidencePackets: (detail.evidencePackets || detail.evidence_packets || []).map(normalizeEvidencePacket),
    auditEvents: (detail.auditEvents || detail.audit_events || []).map(normalizeAuditEvent),
  }
}

export const agentStateApi = {
  async listApps(): Promise<AgentApp[]> {
    const res = await client.get<AgentAppResponse[]>('/agent-state/apps')
    return res.data.map(normalizeApp)
  },

  async listTaskRuns(): Promise<TaskRunSummary[]> {
    const res = await client.get<TaskRunSummaryResponse[]>('/agent-state/task-runs')
    return res.data.map(normalizeTask)
  },

  async getTaskRun(taskRunId: string): Promise<TaskRunDetail> {
    const res = await client.get<TaskRunDetailResponse>(`/agent-state/task-runs/${taskRunId}`)
    return normalizeTaskDetail(res.data)
  },
}
