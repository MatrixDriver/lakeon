import { setActivePinia, createPinia } from 'pinia'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { useCognitionStore, type CognitionSub } from '@/stores/cognition'

describe('useCognitionStore', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('loadTimeline populates events', async () => {
    const get = vi.fn().mockResolvedValue({
      events: [{ id: 'e1', window_start: 0, window_end: 1, agent_id: 'cli',
                 title: 't', summary: null, member_memory_ids: [], rationale: null }],
    })
    const s = useCognitionStore()
    await s.loadTimeline({ get } as never)
    expect(s.timeline.length).toBe(1)
  })

  it('loadGraph stores nodes/edges', async () => {
    const get = vi.fn().mockResolvedValue({
      nodes: [{ id: 'n1', name: 'jacky', kind: 'person' }],
      edges: [],
    })
    const s = useCognitionStore()
    s.graphSeed = 'ent:jacky'
    await s.loadGraph({ get } as never)
    expect(s.graph.nodes).toHaveLength(1)
  })

  it('setActiveSub updates active', () => {
    const s = useCognitionStore()
    const subs: CognitionSub[] = ['timeline', 'summary', 'graph', 'skill']
    for (const sub of subs) {
      s.setActiveSub(sub)
      expect(s.activeSub).toBe(sub)
    }
  })
})
