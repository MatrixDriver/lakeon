import api from './client'

// ── 枚举类型 ──

export type PipelineRunStatus = 'PENDING' | 'RUNNING' | 'PAUSED' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
export type StepRunStatus = 'PENDING' | 'RUNNING' | 'PAUSED' | 'SUCCEEDED' | 'FAILED' | 'SKIPPED'
export type ComponentCategory = 'DATA_PREP' | 'EXTRACT' | 'CLEAN' | 'FILTER' | 'QC' | 'LABEL' | 'PUBLISH'
export type ComponentDataType = 'TEXT' | 'VIDEO' | 'IMAGE' | 'AUDIO' | 'DOCUMENT' | 'UNIVERSAL'
export type ComponentExecutionMode = 'FUNCTION' | 'HUMAN_REVIEW'

// ── Pipeline ──

export interface Pipeline {
  id: string
  tenantId: string
  name: string
  description: string | null
  dataType: string | null
  isTemplate: boolean
  sourceTemplateId: string | null
  latestVersion: number
  createdAt: string
  updatedAt: string
}

export interface PipelineVersion {
  id: string
  pipelineId: string
  version: number
  dagYaml: string
  status: string
  changelog: string | null
  createdAt: string
}

export interface CreatePipelineRequest {
  name: string
  description?: string
  data_type?: string
  is_template?: boolean
  source_template_id?: string
  dag_yaml: string
}

export interface UpdatePipelineRequest {
  name?: string
  description?: string
}

export interface PublishVersionRequest {
  dag_yaml: string
  changelog?: string
}

// ── Pipeline Component ──

export interface PipelineComponent {
  id: string
  tenantId: string | null
  name: string
  displayName: string
  category: ComponentCategory
  dataType: ComponentDataType
  description: string | null
  latestVersion: number
  createdAt: string
  updatedAt: string
}

export interface PipelineComponentVersion {
  id: string
  componentId: string
  version: number
  entrypoint: string
  paramsSchema: string | null
  inputSchema: string | null
  outputSchema: string | null
  outputBranches: string | null
  requiresGpu: boolean
  requiresModel: string | null
  executionMode: ComponentExecutionMode
  status: string
  changelog: string | null
  createdAt: string
}

export interface RegisterComponentRequest {
  name: string
  display_name: string
  category: ComponentCategory
  data_type: ComponentDataType
  description?: string
  entrypoint: string
  params_schema?: string
  input_schema?: string
  output_schema?: string
  output_branches?: string[]
  requires_gpu?: boolean
  requires_model?: string
  execution_mode?: ComponentExecutionMode
}

// ── Pipeline Run ──

export interface PipelineRun {
  id: string
  pipelineId: string
  pipelineVersion: number
  tenantId: string
  inputDatasetId: string | null
  inputDatasetVersion: number | null
  outputDatasetVersionId: string | null
  status: PipelineRunStatus
  startedAt: string | null
  finishedAt: string | null
  createdAt: string
}

export interface PipelineStepRun {
  id: string
  runId: string
  stepId: string
  componentId: string | null
  componentVersion: number | null
  status: StepRunStatus
  inputRef: string | null
  outputRef: string | null
  checkpointPath: string | null
  metrics: string | null
  error: string | null
  startedAt: string | null
  finishedAt: string | null
  createdAt: string
}

export interface TriggerRunRequest {
  pipeline_version?: number
  input_dataset_id?: string
  input_dataset_version?: number
}

// ── 辅助类型：解析后的 metrics ──

export interface StepMetrics {
  input_count?: number
  output_count?: number
  drop_count?: number
  retention?: string
  duration_ms?: number
  [key: string]: unknown
}

// ── API 函数 ──

// Pipeline CRUD
export function listPipelines(params?: { is_template?: boolean }) {
  return api.get<Pipeline[]>('/pipelines', { params })
}

export function getPipeline(id: string) {
  return api.get<Pipeline>(`/pipelines/${id}`)
}

export function createPipeline(body: CreatePipelineRequest) {
  return api.post<Pipeline>('/pipelines', body)
}

export function updatePipeline(id: string, body: UpdatePipelineRequest) {
  return api.put<Pipeline>(`/pipelines/${id}`, body)
}

export function deletePipeline(id: string) {
  return api.delete(`/pipelines/${id}`)
}

// Pipeline Versions
export function listPipelineVersions(pipelineId: string) {
  return api.get<PipelineVersion[]>(`/pipelines/${pipelineId}/versions`)
}

export function getPipelineVersion(pipelineId: string, version: number) {
  return api.get<PipelineVersion>(`/pipelines/${pipelineId}/versions/${version}`)
}

export function publishPipelineVersion(pipelineId: string, body: PublishVersionRequest) {
  return api.post<PipelineVersion>(`/pipelines/${pipelineId}/versions`, body)
}

// Pipeline Components
export function listComponents(params?: { category?: string; data_type?: string }) {
  return api.get<PipelineComponent[]>('/pipeline-components', { params })
}

export function getComponent(id: string) {
  return api.get<PipelineComponent>(`/pipeline-components/${id}`)
}

export function getComponentVersions(componentId: string) {
  return api.get<PipelineComponentVersion[]>(`/pipeline-components/${componentId}/versions`)
}

export function getComponentLatestVersion(componentId: string) {
  return api.get<PipelineComponentVersion>(`/pipeline-components/${componentId}/versions/latest`)
}

export function registerComponent(body: RegisterComponentRequest) {
  return api.post<PipelineComponent>('/pipeline-components', body)
}

// Pipeline Runs
export function listPipelineRuns(pipelineId: string, params?: { status?: string }) {
  return api.get<PipelineRun[]>(`/pipeline-runs`, { params: { pipeline_id: pipelineId, ...params } })
}

export function getPipelineRun(runId: string) {
  return api.get<PipelineRun>(`/pipeline-runs/${runId}`)
}

export function triggerPipelineRun(pipelineId: string, body: TriggerRunRequest) {
  return api.post<PipelineRun>(`/pipeline-runs`, { pipeline_id: pipelineId, ...body })
}

export function cancelPipelineRun(runId: string) {
  return api.post(`/pipeline-runs/${runId}/cancel`)
}

export function resumePipelineRun(runId: string, stepId: string, decision: 'approve' | 'reject') {
  return api.post(`/pipeline-runs/${runId}/resume`, { step_id: stepId, decision })
}

// Step Runs
export function listStepRuns(runId: string) {
  return api.get<PipelineStepRun[]>(`/pipeline-runs/${runId}/steps`)
}

export function getStepRunLogs(runId: string, stepId: string) {
  return api.get<{ logs: string }>(`/pipeline-runs/${runId}/steps/${stepId}/logs`)
}

// ── 辅助函数 ──

export function parseMetrics(raw: string | null): StepMetrics {
  if (!raw) return {}
  try { return JSON.parse(raw) } catch { return {} }
}

export function parseOutputBranches(raw: string | null): string[] {
  if (!raw) return []
  try { return JSON.parse(raw) } catch { return [] }
}

export function parseJsonSchema(raw: string | null): Record<string, any> {
  if (!raw) return {}
  try { return JSON.parse(raw) } catch { return {} }
}
