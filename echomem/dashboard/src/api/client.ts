import type { ApiError, ApiErrorKind } from './types'

export class ApiClient {
  constructor(private base = '') {}

  async get<T>(path: string, params?: Record<string, string | number | undefined>): Promise<T> {
    const qs = params
      ? '?' + new URLSearchParams(
          Object.entries(params)
            .filter(([, v]) => v !== undefined)
            .map(([k, v]) => [k, String(v)])
        ).toString()
      : ''
    return this.request<T>('GET', path + qs)
  }

  async post<T>(path: string, body: unknown): Promise<T> {
    return this.request<T>('POST', path, JSON.stringify(body), {
      'Content-Type': 'application/json',
    })
  }

  async delete<T>(path: string): Promise<T> {
    return this.request<T>('DELETE', path)
  }

  private async request<T>(
    method: string,
    path: string,
    body?: string,
    headers?: Record<string, string>
  ): Promise<T> {
    let resp: Response
    try {
      resp = await fetch(this.base + path, { method, headers, body })
    } catch (e) {
      throw apiErr('network', `network error: ${(e as Error).message}`)
    }
    if (resp.status >= 500) {
      throw apiErr('server', `server ${resp.status}`, resp.status, await resp.text())
    }
    if (resp.status >= 400) {
      throw apiErr('client', `client ${resp.status}`, resp.status, await resp.text())
    }
    if (resp.status === 204) return undefined as T
    try {
      return (await resp.json()) as T
    } catch (e) {
      throw apiErr('parse', `invalid JSON: ${(e as Error).message}`)
    }
  }
}

function apiErr(kind: ApiErrorKind, message: string, status?: number, body?: string): ApiError {
  return { kind, message, status, body }
}

export const api = new ApiClient()
