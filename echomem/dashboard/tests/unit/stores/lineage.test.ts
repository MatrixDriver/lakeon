import { setActivePinia, createPinia } from 'pinia'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { useLineageStore } from '@/stores/lineage'
import { useCognitionStore } from '@/stores/cognition'
import type { Memory } from '@/api/types'

function memWith(id: string, sourceRef: string | null = null): Memory {
  return {
    id, agent_id: 'cli', source_kind: 'explicit', source_ref: sourceRef,
    text: 't', meta: null, created_at: 0, updated_at: 0,
  }
}

describe('useLineageStore', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('loadTimeline gathers memories and unique blob refs', async () => {
    const cog = useCognitionStore()
    cog.timeline = [{
      id: 'e1', window_start: 0, window_end: 1, agent_id: 'cli',
      title: 'x', summary: null, rationale: null,
      member_memory_ids: ['m1', 'm2'],
    }]
    const get = vi.fn(async (path: string) => {
      if (path === '/memory/m1') return memWith('m1', 'sha256:abc')
      if (path === '/memory/m2') return memWith('m2', 'sha256:abc')
      throw new Error('unexpected ' + path)
    })
    const s = useLineageStore()
    await s.load('e1', 'timeline', { get } as never)
    expect(s.current?.cognition.id).toBe('e1')
    expect(s.current?.memories.map((m) => m.id)).toEqual(['m1', 'm2'])
    expect(s.current?.blobs).toEqual(['sha256:abc'])
  })

  it('clear resets state', () => {
    const s = useLineageStore()
    s.current = { cognition: { id: 'e1', kind: 'timeline', label: 'x' },
                  memories: [], blobs: [] }
    s.clear()
    expect(s.current).toBeNull()
  })
})
