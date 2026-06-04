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

export const agentStateApi = {
  async listApps(): Promise<AgentApp[]> {
    const res = await client.get<AgentAppResponse[]>('/agent-state/apps')
    return res.data.map(normalizeApp)
  },
}
