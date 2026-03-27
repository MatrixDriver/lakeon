import client from './client'

export function createSession(image?: string, datasetIds?: string[]) {
  return client.post('/datalake/notebook/sessions', { image, dataset_ids: datasetIds })
}

export function getCurrentSession() {
  return client.get('/datalake/notebook/sessions/current')
}

export function stopSession(id: string) {
  return client.delete(`/datalake/notebook/sessions/${id}`)
}

export interface NotebookMessage {
  id?: string
  type: string
  code?: string
  text?: string
  html?: string
  data?: any
  traceback?: string
  duration_ms?: number
  exec_count?: number
  mime?: string
}

export class NotebookSocket {
  private ws: WebSocket | null = null
  private url: string
  private onMessage: (msg: NotebookMessage) => void
  private onStatus: (status: 'connecting' | 'connected' | 'disconnected') => void
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private reconnectAttempts = 0

  constructor(
    onMessage: (msg: NotebookMessage) => void,
    onStatus: (status: 'connecting' | 'connected' | 'disconnected') => void,
  ) {
    const apiKey = localStorage.getItem('lakeon_api_key') || ''
    this.url = `wss://api.dbay.cloud:8443/ws/notebook?token=${apiKey}`
    this.onMessage = onMessage
    this.onStatus = onStatus
  }

  connect() {
    if (this.ws) return
    this.onStatus('connecting')
    this.ws = new WebSocket(this.url)
    this.ws.onopen = () => { this.reconnectAttempts = 0; this.onStatus('connected') }
    this.ws.onmessage = (event) => {
      try { this.onMessage(JSON.parse(event.data)) } catch { /* not JSON */ }
    }
    this.ws.onclose = () => { this.ws = null; this.onStatus('disconnected'); this.scheduleReconnect() }
    this.ws.onerror = () => { this.ws?.close() }
  }

  send(msg: NotebookMessage) {
    if (this.ws?.readyState === WebSocket.OPEN) this.ws.send(JSON.stringify(msg))
  }

  execute(id: string, code: string) { this.send({ type: 'execute', id, code }) }

  disconnect() {
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer)
    this.reconnectTimer = null
    this.ws?.close()
    this.ws = null
  }

  private scheduleReconnect() {
    if (this.reconnectAttempts >= 5) return
    const delay = Math.min(1000 * 2 ** this.reconnectAttempts, 10000)
    this.reconnectAttempts++
    this.reconnectTimer = setTimeout(() => this.connect(), delay)
  }
}
