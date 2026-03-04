import client from './client'

export interface OperationLog {
  id: string
  databaseId: string
  databaseName: string
  operationType: string
  status: string
  startedAt: string
  completedAt: string | null
  durationMs: number | null
  errorMessage: string | null
}

export interface PageResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export const operationApi = {
  getByDatabase: (dbId: string, params?: { type?: string; page?: number; size?: number }) =>
    client.get<PageResponse<OperationLog>>(`/databases/${dbId}/operations`, { params }),
  getRecent: () => client.get<OperationLog[]>('/operations/recent'),
}
