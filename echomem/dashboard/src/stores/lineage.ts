import { defineStore } from 'pinia'
import { ref } from 'vue'
import { api } from '@/api/client'
import type { Memory } from '@/api/types'
import { useCognitionStore, type CognitionSub } from './cognition'

export interface LineageCognition { id: string; kind: CognitionSub; label: string }
export interface LineageBundle {
  cognition: LineageCognition
  memories: Memory[]
  blobs: string[]
}

export const useLineageStore = defineStore('lineage', () => {
  const current = ref<LineageBundle | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  function clear() { current.value = null; error.value = null }

  async function load(id: string, kind: CognitionSub, client = api) {
    loading.value = true; error.value = null
    try {
      const cog = useCognitionStore()
      if (kind === 'timeline') {
        const event = cog.timeline.find((e) => e.id === id)
        if (!event) throw new Error('timeline event not in cache')
        const memories = await Promise.all(
          event.member_memory_ids.map((mid) => client.get<Memory>(`/memory/${mid}`))
        )
        const blobs = Array.from(new Set(
          memories.map((m) => m.source_ref).filter((r): r is string => !!r && r.startsWith('sha256:'))
        ))
        current.value = {
          cognition: { id, kind, label: event.title },
          memories, blobs,
        }
      } else {
        // summary / graph / skill — v1 placeholders (see spec §4.3)
        current.value = {
          cognition: { id, kind, label: id },
          memories: [], blobs: [],
        }
      }
    } catch (e) {
      error.value = (e as { message?: string }).message ?? 'unknown'
      throw e
    } finally { loading.value = false }
  }

  return { current, loading, error, load, clear }
})
