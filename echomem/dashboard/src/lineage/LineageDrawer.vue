<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useLineageStore } from '@/stores/lineage'
import type { CognitionSub } from '@/stores/cognition'
import Drawer from '@/components/Drawer.vue'
import ColumnList from '@/components/ColumnList.vue'

const route = useRoute()
const router = useRouter()
const store = useLineageStore()
const { current, loading } = storeToRefs(store)

const id = computed(() => (route.query.lineage as string) || '')
const kind = computed<CognitionSub | ''>(() => (route.query.kind as CognitionSub) || '')
const open = computed(() => !!id.value)

watch([id, kind], async ([nid, nkind]) => {
  if (nid && nkind) {
    try { await store.load(nid, nkind) } catch { /* error already in store */ }
  } else {
    store.clear()
  }
}, { immediate: true })

const activeMemId = ref<string | null>(null)
const activeBlob = ref<string | null>(null)

watch(current, (c) => {
  activeMemId.value = c?.memories[0]?.id ?? null
  activeBlob.value = c?.blobs[0] ?? null
})

const cogItems = computed(() => current.value
  ? [{ id: current.value.cognition.id, label: current.value.cognition.label,
       sub: current.value.cognition.kind }]
  : [])

const memItems = computed(() =>
  (current.value?.memories ?? []).map((m) => ({
    id: m.id, label: m.text.slice(0, 80), sub: m.agent_id,
  }))
)

const blobItems = computed(() =>
  (current.value?.blobs ?? []).map((b) => ({ id: b, label: b.replace('sha256:', '').slice(0, 12) + '…', sub: 'blob' }))
)

function close() {
  const q = { ...route.query }
  delete q.lineage; delete q.kind
  router.replace({ path: route.path, query: q })
}
</script>

<template>
  <Drawer :open="open" :title="`来源 · ${current?.cognition.label ?? ''}`" :width-vw="70" @close="close">
    <div v-if="loading" class="loading">加载中...</div>
    <div v-else-if="current" class="cols">
      <ColumnList title="认知" :items="cogItems" :active-id="current.cognition.id" />
      <ColumnList title="记忆" :items="memItems" :active-id="activeMemId" @select="activeMemId = $event" />
      <ColumnList title="来源 blob / URL" :items="blobItems" :active-id="activeBlob" @select="activeBlob = $event" />
    </div>
  </Drawer>
</template>

<style scoped>
.loading { padding: var(--space-2xl); text-align: center; color: var(--c-text-muted); }
.cols { display: grid; grid-template-columns: 1fr 1fr 1fr; height: 100%; }
</style>
