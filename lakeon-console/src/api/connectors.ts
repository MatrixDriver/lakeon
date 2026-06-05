import client from './client'

export type ConnectorType = 'POSTGRESQL' | 'OBS'
export type ConnectorStatus = 'UNTESTED' | 'CONNECTED' | 'FAILED'

export interface Connector {
  id: string
  type: ConnectorType
  name: string
  status: ConnectorStatus
  config: Record<string, unknown>
  target_summary: string | null
  last_tested_at: string | null
  last_error: string | null
  created_at: string
  updated_at: string
  usage_count: number
  usage_hint: string | null
}

export interface PostgresTableInfo {
  schema: string
  table: string
  estimated_rows: number
}

export interface ConnectorTestResponse {
  ok: boolean
  error?: string | null
  metadata: Record<string, any>
}

export const connectorsApi = {
  createPostgres: (input: {
    name: string
    host: string
    port: number
    dbname: string
    user: string
    password: string
  }) => client.post<Connector>('/connectors', {
    type: 'POSTGRESQL',
    name: input.name,
    config: {
      host: input.host,
      port: input.port,
      dbname: input.dbname,
    },
    secret: {
      user: input.user,
      password: input.password,
    },
  }),

  list: () =>
    client.get<Connector[]>('/connectors'),

  test: (id: string) =>
    client.post<ConnectorTestResponse>(`/connectors/${id}/test`),

  listPostgresTables: (id: string) =>
    client.get<PostgresTableInfo[]>(`/connectors/${id}/postgres/tables`, { timeout: 60000 }),
}
