<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { useStatusStore } from '@/stores/status'
import { useCognitionStore } from '@/stores/cognition'
import { useMemoryStore } from '@/stores/memory'
import { useUiStore } from '@/stores/ui'
import { useRouter } from 'vue-router'
import Tile from '@/components/Tile.vue'
import Card from '@/components/Card.vue'
import Tag from '@/components/Tag.vue'
import Button from '@/components/Button.vue'
import EmptyState from '@/components/EmptyState.vue'

const status = useStatusStore()
const cognition = useCognitionStore()
const memory = useMemoryStore()
const ui = useUiStore()
const router = useRouter()
const { counts } = storeToRefs(status)

onMounted(async () => {
  await Promise.allSettled([
    cognition.loadTimeline(),
    memory.loadInitial(),
  ])
})

const latest = computed(() => cognition.timeline[0] ?? null)

const recent = computed(() => {
  const events = cognition.timeline.slice(0, 5).map((e) => ({
    at: e.window_end, kind: 'cognition' as const, label: `timeline · "${e.title}"`, id: e.id,
  }))
  const mems = memory.items.slice(0, 5).map((m) => ({
    at: m.created_at, kind: 'memory' as const, label: `ingest · "${m.text.slice(0, 60)}"`, id: m.id,
  }))
  return [...events, ...mems].sort((a, b) => b.at - a.at).slice(0, 5)
})

function fmt(ts: number) {
  return new Date(ts).toLocaleString(undefined, {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  })
}

function openLineage(id: string) {
  router.push({ query: { lineage: id, kind: 'timeline' } })
}
</script>

<template>
  <div class="overview">
    <section class="tiles">
      <Tile :value="counts.memories"  label="记忆" />
      <Tile :value="counts.cognitions" label="认知" />
      <Tile :value="counts.entities"   label="实体" />
      <Tile :value="counts.skills"     label="Skills" />
    </section>

    <section class="stage">
      <template v-if="latest">
        <Card>
          <div class="label">最近一条认知 · TIMELINE · {{ fmt(latest.window_end) }}</div>
          <h2 class="title">{{ latest.title }}</h2>
          <p class="sum" v-if="latest.summary">{{ latest.summary }}</p>
          <p class="meta">由 {{ latest.member_memory_ids.length }} 条记忆合成</p>
          <div class="cta">
            <Button variant="primary" @click="openLineage(latest.id)">查看完整来源 →</Button>
          </div>
        </Card>
      </template>
      <template v-else-if="counts.memories === 0">
        <EmptyState
          title="echomem 是空的"
          body='试试看： echomem mem ingest "今天的笔记" --agent cli
或点右上角 + Quick ingest。'
        >
          <template #actions>
            <Button variant="primary" @click="ui.toggleQuickIngest(true)">+ Quick ingest</Button>
          </template>
        </EmptyState>
      </template>
      <template v-else>
        <EmptyState
          title="记忆已就位，AI worker 还在消化中"
          body="第一条认知通常在 ingest 后 30–90 秒出现。状态页可看队列进度。"
        />
      </template>
    </section>

    <section v-if="recent.length" class="recent">
      <h3 class="hd">最近活动</h3>
      <ul>
        <li v-for="r in recent" :key="r.kind + r.id">
          <span class="when">{{ fmt(r.at) }}</span>
          <Tag>{{ r.kind === 'cognition' ? '认知' : '记忆' }}</Tag>
          <span class="lbl">{{ r.label }}</span>
        </li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.overview { display: flex; flex-direction: column; gap: var(--space-2xl); }
.tiles { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--space-md); }
.stage { display: flex; flex-direction: column; }
.label { font-size: var(--fs-xs); color: var(--c-text-muted); letter-spacing: 0.5px; text-transform: uppercase; }
.title { font-size: var(--fs-h2); margin: var(--space-sm) 0; color: var(--c-primary); font-family: var(--font-display); }
.sum { color: var(--c-text); font-size: var(--fs-md); line-height: var(--lh-loose); margin: 0 0 var(--space-sm); }
.meta { color: var(--c-text-muted); font-size: var(--fs-sm); margin: 0 0 var(--space-md); }
.recent .hd { font-size: var(--fs-h3); margin-bottom: var(--space-md); }
.recent ul { list-style: none; padding: 0; margin: 0; }
.recent li { display: flex; gap: var(--space-md); align-items: center; padding: var(--space-sm) 0; border-bottom: 1px dashed var(--c-divider); font-size: var(--fs-sm); }
.recent li:last-child { border-bottom: none; }
.when { font-family: var(--font-mono); font-size: var(--fs-xs); color: var(--c-text-muted); width: 100px; }
.lbl { color: var(--c-text); }
</style>
