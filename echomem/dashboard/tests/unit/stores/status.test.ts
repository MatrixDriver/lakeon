import { setActivePinia, createPinia } from 'pinia'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { useStatusStore } from '@/stores/status'
import type { DiagnosticResponse } from '@/api/types'

const fixture: DiagnosticResponse = {
  daemon: { status: 'ok', version: '0.1.0', data_dir: '/tmp', db_size_bytes: 100 },
  ollama: { status: 'ok', latency_ms: 5, generate_model: 'g', embedding_model: 'e', embedding_dim: 768 },
  workers: {
    summarize: { queue_depth: 0, last_run_at: 0, processed_total: 0, throttle: null },
    extract_entity: { queue_depth: 1, last_run_at: 0, processed_total: 0, throttle: null },
    aggregate_timeline: { queue_depth: 0, last_run_at: 0, processed_total: 0, throttle: null },
    reflect: { queue_depth: 0, last_run_at: 0, processed_total: 0, throttle: null },
    summarize_blob: { queue_depth: 0, last_run_at: 0, processed_total: 0, throttle: null },
    extract_blob: { queue_depth: 0, last_run_at: 0, processed_total: 0, throttle: null },
  },
  counts: { memories: 5, cognitions: 2, entities: 3, skills: 1 },
  dead_letter: [],
}

describe('useStatusStore', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('queueDepth aggregates across workers', () => {
    const s = useStatusStore()
    s.applyDiagnostic(fixture)
    expect(s.queueDepth).toBe(1)
  })

  it('counts available after applyDiagnostic', () => {
    const s = useStatusStore()
    s.applyDiagnostic(fixture)
    expect(s.counts.memories).toBe(5)
    expect(s.counts.cognitions).toBe(2)
  })

  it('refresh() calls /health/diagnostic and applies', async () => {
    const fetchSpy = vi.fn().mockResolvedValue(fixture)
    const s = useStatusStore()
    await s.refresh({ get: fetchSpy } as never)
    expect(fetchSpy).toHaveBeenCalledWith('/health/diagnostic')
    expect(s.counts.memories).toBe(5)
  })
})
