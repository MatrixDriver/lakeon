import { setActivePinia, createPinia } from 'pinia'
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { useMemoryStore } from '@/stores/memory'
import type { Memory } from '@/api/types'

const m = (id: string, t = 1): Memory => ({
  id, agent_id: 'cli', source_kind: 'explicit', source_ref: null,
  text: 't' + id, meta: null, created_at: t, updated_at: t,
})

describe('useMemoryStore', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('loadInitial replaces items and sets cursor', async () => {
    const get = vi.fn().mockResolvedValue({ items: [m('a', 10), m('b', 5)] })
    const s = useMemoryStore()
    await s.loadInitial({ get } as never)
    expect(s.items.length).toBe(2)
    expect(s.cursor).toBe(5)
    expect(s.hasMore).toBe(true)
  })

  it('loadMore appends and updates cursor', async () => {
    const get = vi.fn()
      .mockResolvedValueOnce({ items: [m('a', 10), m('b', 5)] })
      .mockResolvedValueOnce({ items: [m('c', 3)] })
    const s = useMemoryStore()
    await s.loadInitial({ get } as never)
    await s.loadMore({ get } as never)
    expect(s.items.map((it) => it.id)).toEqual(['a', 'b', 'c'])
    expect(s.cursor).toBe(3)
  })

  it('hasMore false when result is shorter than limit', async () => {
    const get = vi.fn().mockResolvedValue({ items: [] })
    const s = useMemoryStore()
    await s.loadInitial({ get } as never)
    expect(s.hasMore).toBe(false)
  })

  it('filteredItems applies client-side text search', () => {
    const s = useMemoryStore()
    s.items = [m('a'), { ...m('b'), text: 'hello world' }]
    s.filters.query = 'hello'
    expect(s.filteredItems.map((it) => it.id)).toEqual(['b'])
  })
})
