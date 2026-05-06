<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useMemoryStore } from '@/stores/memory'
import EmptyState from '@/components/EmptyState.vue'
import Button from '@/components/Button.vue'
import Tag from '@/components/Tag.vue'
import Drawer from '@/components/Drawer.vue'

const store = useMemoryStore()
const { items, hasMore, loading, filteredItems } = storeToRefs(store)

const sourceKindFilter = ref('')
const queryFilter = ref('')

onMounted(() => store.loadInitial())

let debounceTimer: number | null = null
function onQueryInput(e: Event) {
  queryFilter.value = (e.target as HTMLInputElement).value
  if (debounceTimer) clearTimeout(debounceTimer)
  debounceTimer = window.setTimeout(() => { store.filters.query = queryFilter.value }, 200)
}
function applySourceKind(v: string) {
  sourceKindFilter.value = v
  store.filters.sourceKind = v
}

const detail = ref<typeof items.value[number] | null>(null)
function openDetail(m: typeof items.value[number]) { detail.value = m }

function fmtTime(ts: number) {
  return new Date(ts).toLocaleString(undefined, {
    month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit',
  })
}
</script>

<template>
  <div class="memory">
    <header class="hd">
      <input class="search" type="search" placeholder="搜索文本..."
             :value="queryFilter" @input="onQueryInput" />
      <div class="kinds">
        <button :class="['chip', { active: sourceKindFilter === '' }]" @click="applySourceKind('')">全部</button>
        <button :class="['chip', { active: sourceKindFilter === 'explicit' }]" @click="applySourceKind('explicit')">explicit</button>
        <button :class="['chip', { active: sourceKindFilter === 'session' }]" @click="applySourceKind('session')">session</button>
        <button :class="['chip', { active: sourceKindFilter === 'document' }]" @click="applySourceKind('document')">document</button>
      </div>
    </header>

    <table v-if="filteredItems.length">
      <thead>
        <tr><th>时间</th><th>agent</th><th>kind</th><th>文本</th></tr>
      </thead>
      <tbody>
        <tr v-for="m in filteredItems" :key="m.id" @click="openDetail(m)">
          <td class="time">{{ fmtTime(m.created_at) }}</td>
          <td><Tag>{{ m.agent_id }}</Tag></td>
          <td>{{ m.source_kind }}</td>
          <td class="text">{{ m.text }}</td>
        </tr>
      </tbody>
    </table>

    <EmptyState v-else
      title="还没有记忆"
      body="用 CLI、HTTP 或 MCP 任一入口都可以。
curl 127.0.0.1:8473/memory/ingest -d '...'"
    />

    <div v-if="hasMore && filteredItems.length" class="load-more">
      <Button :disabled="loading" @click="store.loadMore()">{{ loading ? '加载中...' : 'Load more' }}</Button>
    </div>

    <Drawer :open="!!detail" :title="detail?.id" @close="detail = null">
      <div v-if="detail" class="detail">
        <div class="row"><span class="lbl">agent</span><span>{{ detail.agent_id }}</span></div>
        <div class="row"><span class="lbl">source_kind</span><span>{{ detail.source_kind }}</span></div>
        <div class="row" v-if="detail.source_ref"><span class="lbl">source_ref</span><span class="mono">{{ detail.source_ref }}</span></div>
        <div class="row"><span class="lbl">created</span><span>{{ fmtTime(detail.created_at) }}</span></div>
        <h4>文本</h4>
        <pre class="text-block">{{ detail.text }}</pre>
        <h4 v-if="detail.meta">meta</h4>
        <pre v-if="detail.meta" class="text-block">{{ JSON.stringify(detail.meta, null, 2) }}</pre>
      </div>
    </Drawer>
  </div>
</template>

<style scoped>
.memory { display: flex; flex-direction: column; gap: var(--space-md); }
.hd { display: flex; gap: var(--space-md); align-items: center; }
.search { padding: 6px 10px; border: 1px solid var(--c-border); border-radius: var(--radius-md); font-size: var(--fs-sm); width: 280px; }
.kinds { display: flex; gap: var(--space-xs); }
.chip {
  background: var(--c-bg); border: 1px solid var(--c-border); border-radius: 12px;
  padding: 2px var(--space-sm); font-size: var(--fs-xs); color: var(--c-text-muted); cursor: pointer;
}
.chip.active { background: var(--c-accent-light); color: var(--c-accent-text); border-color: var(--c-accent); }
table { width: 100%; border-collapse: collapse; font-size: 13px; }
th { text-align: left; padding: var(--space-sm) var(--table-cell-px); border-bottom: 1px solid var(--c-border); color: var(--c-text-muted); font-weight: var(--fw-medium); font-size: var(--fs-xs); text-transform: uppercase; letter-spacing: 0.5px; }
td { padding: 0 var(--table-cell-px); height: var(--table-row-h); border-bottom: 1px solid var(--c-divider); }
tbody tr { cursor: pointer; }
tbody tr:hover { background: var(--c-primary-soft); }
.time { font-family: var(--font-mono); color: var(--c-text-muted); white-space: nowrap; }
.text { color: var(--c-text); max-width: 600px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.load-more { text-align: center; padding: var(--space-md); }
.detail { padding: var(--space-lg); }
.detail .row { display: flex; gap: var(--space-md); padding: 4px 0; font-size: var(--fs-sm); }
.detail .lbl { width: 100px; color: var(--c-text-muted); font-size: var(--fs-xs); text-transform: uppercase; }
.text-block { background: var(--c-bg-alt); padding: var(--space-md); border-radius: var(--radius-sm); white-space: pre-wrap; font-family: var(--font-mono); font-size: var(--fs-xs); }
.mono { font-family: var(--font-mono); font-size: var(--fs-xs); }
</style>
