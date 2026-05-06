import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { ApiClient } from '@/api/client'

describe('ApiClient', () => {
  let originalFetch: typeof fetch

  beforeEach(() => { originalFetch = globalThis.fetch })
  afterEach(() => { globalThis.fetch = originalFetch })

  function mockFetch(impl: typeof fetch) { globalThis.fetch = impl as typeof fetch }

  it('GET parses JSON on 2xx', async () => {
    mockFetch(async () => new Response(JSON.stringify({ ok: 1 }),
      { status: 200, headers: { 'Content-Type': 'application/json' } }))
    const c = new ApiClient()
    expect(await c.get('/health')).toEqual({ ok: 1 })
  })

  it('POST sends JSON body', async () => {
    let captured: { url: string; body: string } | null = null
    mockFetch(async (input, init) => {
      captured = { url: input.toString(), body: init?.body as string }
      return new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } })
    })
    const c = new ApiClient()
    await c.post('/memory/ingest', { text: 'hi', agent_id: 'cli' })
    expect(captured!.url).toContain('/memory/ingest')
    expect(JSON.parse(captured!.body)).toEqual({ text: 'hi', agent_id: 'cli' })
  })

  it('classifies network errors', async () => {
    mockFetch(async () => { throw new TypeError('failed to fetch') })
    const c = new ApiClient()
    await expect(c.get('/health')).rejects.toMatchObject({ kind: 'network' })
  })

  it('classifies 4xx as client error', async () => {
    mockFetch(async () => new Response('bad', { status: 400 }))
    const c = new ApiClient()
    await expect(c.get('/health')).rejects.toMatchObject({ kind: 'client', status: 400 })
  })

  it('classifies 5xx as server error', async () => {
    mockFetch(async () => new Response('boom', { status: 503 }))
    const c = new ApiClient()
    await expect(c.get('/health')).rejects.toMatchObject({ kind: 'server', status: 503 })
  })
})
