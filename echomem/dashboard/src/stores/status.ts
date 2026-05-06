import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api } from '@/api/client'
import type { DiagnosticResponse, OllamaHealth } from '@/api/types'

const EMPTY_OLLAMA: OllamaHealth = {
  status: 'unreachable', latency_ms: null,
  generate_model: '', embedding_model: '', embedding_dim: 0,
}

export const useStatusStore = defineStore('status', () => {
  const daemon = ref<DiagnosticResponse['daemon'] | null>(null)
  const ollama = ref<OllamaHealth>(EMPTY_OLLAMA)
  const workers = ref<DiagnosticResponse['workers']>({})
  const counts = ref({ memories: 0, cognitions: 0, entities: 0, skills: 0 })
  const deadLetter = ref<DiagnosticResponse['dead_letter']>([])
  const lastError = ref<string | null>(null)

  const queueDepth = computed(() =>
    Object.values(workers.value).reduce((s, w) => s + (w?.queue_depth ?? 0), 0)
  )

  function applyDiagnostic(d: DiagnosticResponse) {
    daemon.value = d.daemon
    ollama.value = d.ollama
    workers.value = d.workers
    counts.value = d.counts
    deadLetter.value = d.dead_letter
    lastError.value = null
  }

  async function refresh(client = api) {
    try {
      const data = await client.get<DiagnosticResponse>('/health/diagnostic')
      applyDiagnostic(data)
    } catch (e) {
      lastError.value = (e as { message?: string }).message ?? 'unknown'
      throw e
    }
  }

  return { daemon, ollama, workers, counts, deadLetter, lastError, queueDepth, applyDiagnostic, refresh }
})
