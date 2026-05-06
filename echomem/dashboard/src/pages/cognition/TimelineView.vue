<script setup lang="ts">
import { onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import { useCognitionStore } from '@/stores/cognition'
import EmptyState from '@/components/EmptyState.vue'
import Tag from '@/components/Tag.vue'

const cog = useCognitionStore()
const { timeline } = storeToRefs(cog)
const router = useRouter()

onMounted(() => cog.loadTimeline())

function fmt(ts: number) {
  return new Date(ts).toLocaleString(undefined, {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  })
}
function open(id: string) {
  router.push({ query: { ...router.currentRoute.value.query, lineage: id, kind: 'timeline' } })
}
</script>

<template>
  <div v-if="timeline.length" class="timeline">
    <article v-for="ev in timeline" :key="ev.id" class="row" @click="open(ev.id)">
      <time class="anchor">{{ fmt(ev.window_end) }}</time>
      <div class="card">
        <h3>{{ ev.title }}</h3>
        <p v-if="ev.summary" class="sum">{{ ev.summary }}</p>
        <div class="meta">
          <Tag>{{ ev.member_memory_ids.length }} 条记忆</Tag>
          <span v-if="ev.rationale" class="rat">{{ ev.rationale }}</span>
        </div>
      </div>
    </article>
  </div>
  <EmptyState v-else
    title="AI worker 待机中"
    body="当 agent 数据足够形成时间窗口时，对应的认知会自动出现。"
  />
</template>

<style scoped>
.timeline { display: flex; flex-direction: column; gap: var(--space-md); }
.row { display: grid; grid-template-columns: 120px 1fr; gap: var(--space-md); cursor: pointer; }
.anchor { font-family: var(--font-mono); font-size: var(--fs-xs); color: var(--c-text-muted); padding-top: var(--space-md); }
.card {
  background: var(--c-bg); border: 1px solid var(--c-border);
  border-radius: var(--radius-md); padding: var(--space-md) var(--space-lg);
}
.card:hover { border-color: var(--c-border-hover); }
.card h3 { font-size: var(--fs-lg); margin-bottom: 4px; }
.sum { color: var(--c-text); font-size: var(--fs-sm); margin: 4px 0 var(--space-sm); line-height: var(--lh-loose); }
.meta { display: flex; gap: var(--space-sm); align-items: center; }
.rat { font-size: var(--fs-xs); color: var(--c-text-muted); }
</style>
