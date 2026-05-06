<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import { useCognitionStore } from '@/stores/cognition'
import { useMemoryStore } from '@/stores/memory'
import EmptyState from '@/components/EmptyState.vue'
import Tag from '@/components/Tag.vue'

const cog = useCognitionStore()
const mem = useMemoryStore()
const router = useRouter()
const { summaryGroups } = storeToRefs(cog)

onMounted(async () => {
  if (!mem.items.length) await mem.loadInitial()
  const seen = new Set<string>()
  const targets: { kind: string; ref: string }[] = []
  for (const m of mem.items) {
    if (m.source_ref) {
      const key = `${m.source_kind}:${m.source_ref}`
      if (!seen.has(key)) {
        seen.add(key)
        targets.push({ kind: m.source_kind, ref: m.source_ref })
      }
    }
  }
  // Also include direct memory-rooted trees
  for (const m of mem.items) {
    const key = `memory:${m.id}`
    if (!seen.has(key)) {
      seen.add(key)
      targets.push({ kind: 'memory', ref: m.id })
    }
    if (targets.length >= 12) break
  }
  await Promise.all(targets.map((t) => cog.loadSummaryGroup(t.kind, t.ref).catch(() => undefined)))
})

const groups = computed(() => Object.entries(summaryGroups.value).filter(([, n]) => n.length))

function open(id: string) {
  router.push({ query: { ...router.currentRoute.value.query, lineage: id, kind: 'summary' } })
}
</script>

<template>
  <div v-if="groups.length" class="summary">
    <details v-for="[key, nodes] in groups" :key="key" open>
      <summary>
        <Tag tone="mono">{{ key }}</Tag>
        <span class="count">· {{ nodes.length }} 节点</span>
      </summary>
      <ul class="tree">
        <li v-for="n in nodes" :key="n.id" :style="{ paddingLeft: `${n.level * 16}px` }" @click="open(n.id)">
          <span class="lvl">L{{ n.level }}</span>
          <span class="text">{{ n.text }}</span>
          <Tag v-if="n.token_estimate">{{ n.token_estimate }} tok</Tag>
        </li>
      </ul>
    </details>
  </div>
  <EmptyState v-else
    title="AI worker 待机中"
    body="当摘要 worker 完成 L0/L1/L2 层级时，对应的认知会自动出现。"
  />
</template>

<style scoped>
.summary { display: flex; flex-direction: column; gap: var(--space-md); }
details { border: 1px solid var(--c-border); border-radius: var(--radius-md); padding: var(--space-md); background: var(--c-bg); }
summary { cursor: pointer; font-size: var(--fs-sm); display: flex; align-items: center; gap: var(--space-sm); }
.count { color: var(--c-text-muted); }
.tree { list-style: none; margin: var(--space-sm) 0 0; padding: 0; }
.tree li { display: flex; gap: var(--space-sm); align-items: flex-start; padding: 4px 0; font-size: var(--fs-sm); cursor: pointer; }
.tree li:hover { background: var(--c-primary-soft); }
.lvl { font-family: var(--font-mono); font-size: 10px; color: var(--c-text-muted); padding-top: 2px; min-width: 22px; }
.text { flex: 1; color: var(--c-text); }
</style>
