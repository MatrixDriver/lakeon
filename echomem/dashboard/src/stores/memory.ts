import { defineStore } from 'pinia'
import { computed, reactive, ref } from 'vue'
import { api } from '@/api/client'
import type { Memory, MemoryListResponse } from '@/api/types'

const PAGE_SIZE = 50

export const useMemoryStore = defineStore('memory', () => {
  const items = ref<Memory[]>([])
  const cursor = ref<number | null>(null)
  const hasMore = ref(true)
  const loading = ref(false)
  const filters = reactive({ agent: '' as string, sourceKind: '' as string, query: '' })

  async function loadInitial(client = api) {
    loading.value = true
    try {
      const data = await client.get<MemoryListResponse>('/memory/list', {
        agent_id: filters.agent || undefined,
        limit: PAGE_SIZE,
      })
      items.value = data.items
      cursor.value = data.items.at(-1)?.created_at ?? null
      hasMore.value = data.items.length > 0
    } finally { loading.value = false }
  }

  async function loadMore(client = api) {
    if (loading.value || !hasMore.value || cursor.value == null) return
    loading.value = true
    try {
      const data = await client.get<MemoryListResponse>('/memory/list', {
        agent_id: filters.agent || undefined,
        before: cursor.value,
        limit: PAGE_SIZE,
      })
      items.value.push(...data.items)
      cursor.value = data.items.at(-1)?.created_at ?? cursor.value
      hasMore.value = data.items.length > 0
    } finally { loading.value = false }
  }

  const filteredItems = computed(() => {
    let out = items.value
    if (filters.sourceKind) out = out.filter((m) => m.source_kind === filters.sourceKind)
    if (filters.query) {
      const q = filters.query.toLowerCase()
      out = out.filter((m) => m.text.toLowerCase().includes(q))
    }
    return out
  })

  return { items, cursor, hasMore, loading, filters, filteredItems, loadInitial, loadMore }
})
