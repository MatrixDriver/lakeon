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
  tenant_id: string
  name: string
  description: string | null
  data_type: string | null
  is_template: boolean
  source_template_id: string | null
  latest_version: number
  created_at: string
  updated_at: string
}

export interface PipelineVersion {
  id: string
  pipeline_id: string
  version: number
  dag_yaml: string
  status: string
  changelog: string | null
  created_at: string
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
  tenant_id: string | null
  name: string
  display_name: string
  category: ComponentCategory
  data_type: ComponentDataType
  description: string | null
  latest_version: number
  created_at: string
  updated_at: string
}

export interface PipelineComponentVersion {
  id: string
  component_id: string
  version: number
  entrypoint: string
  params_schema: string | null
  input_schema: string | null
  output_schema: string | null
  output_branches: string | null
  requires_gpu: boolean
  requires_model: string | null
  execution_mode: ComponentExecutionMode
  status: string
  changelog: string | null
  created_at: string
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
  pipeline_id: string
  pipeline_version: number
  tenant_id: string
  input_dataset_id: string | null
  input_dataset_version: number | null
  output_dataset_version_id: string | null
  status: PipelineRunStatus
  started_at: string | null
  finished_at: string | null
  created_at: string
}

export interface PipelineStepRun {
  id: string
  run_id: string
  step_id: string
  component_id: string | null
  component_version: number | null
  status: StepRunStatus
  input_ref: string | null
  output_ref: string | null
  checkpoint_path: string | null
  metrics: string | null
  error: string | null
  started_at: string | null
  finished_at: string | null
  created_at: string
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
