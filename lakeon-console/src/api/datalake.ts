import api from './client'

export type DatalakeJobStatus = 'PENDING' | 'STARTING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
export type DatalakeJobType = 'PYTHON' | 'RAY' | 'FINETUNE'

export interface DatalakeJob {
  id: string
  tenantId: string
  name: string
  type: DatalakeJobType
  status: DatalakeJobStatus
  entrypoint: string | null
  cciNamespace: string | null
  rayJobName: string | null
  k8sJobName: string | null
  baseImage: string | null
  logObsPath: string | null
  coreHours: number | null
  gpuHours: number | null
  errorMessage: string | null
  startedAt: string | null
  finishedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface DatalakeJobSubmitRequest {
  name: string
  type: DatalakeJobType
  // Code
  inline_script?: string
  entrypoint?: string
  requirements?: string
  // Data
  input_dataset_ids?: string[]
  output_path?: string
  // Resources
  env_vars?: Record<string, string>
  resources?: { cpu?: string; memory?: string; gpu?: string }
  timeout_seconds?: number
  retry_count?: number
  // Ray
  head?: { cpu?: string; memory?: string }
  workers?: { replicas?: number; cpu?: string; memory?: string; gpu?: string }
  // Finetune
  base_model?: string
  dataset_path?: string
  hyperparams?: Record<string, any>
  gpu?: string
  image_key?: string
}

export interface AiScriptResult {
  script?: string
  error?: string
  model?: string
  input_tokens?: number
  output_tokens?: number
  used_dataset_ids?: string[]
}

export function generateDatalakeScript(prompt: string, model: string) {
  return api.post<AiScriptResult>('/datalake/ai-script/generate', { prompt, model })
}

export function listDatalakeJobs(status?: string) {
  const params: Record<string, string> = {}
  if (status) params.status = status
  return api.get<DatalakeJob[]>('/datalake/jobs', { params })
}

export function getDatalakeJob(jobId: string) {
  return api.get<DatalakeJob>(`/datalake/jobs/${jobId}`)
}

export function submitDatalakeJob(body: DatalakeJobSubmitRequest) {
  return api.post<DatalakeJob>('/datalake/jobs', body)
}

export function cancelDatalakeJob(jobId: string) {
  return api.delete(`/datalake/jobs/${jobId}`)
}

export function streamDatalakeLogsUrl(jobId: string): string {
  const apiKey = localStorage.getItem('lakeon_api_key') || ''
  return `https://api.dbay.cloud:8443/api/v1/datalake/jobs/${jobId}/logs?token=${encodeURIComponent(apiKey)}`
}
