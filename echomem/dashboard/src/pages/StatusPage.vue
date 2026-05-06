<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useStatusStore } from '@/stores/status'
import Card from '@/components/Card.vue'
import Tag from '@/components/Tag.vue'

const status = useStatusStore()
const { daemon, ollama, workers, deadLetter } = storeToRefs(status)

onMounted(() => status.refresh())

function fmtBytes(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(1)} MB`
}
function fmtTs(ts: number | null): string {
  if (!ts) return '—'
  return new Date(ts).toLocaleString()
}
const expanded = ref<Record<string, boolean>>({})
function toggle(id: string) { expanded.value[id] = !expanded.value[id] }
</script>

<template>
  <div class="status">
    <Card class="section">
      <h3>服务健康</h3>
      <div class="kv-row">
        <div class="kv"><span class="lbl">daemon</span><Tag tone="accent">{{ daemon?.status ?? '—' }}</Tag></div>
        <div class="kv"><span class="lbl">version</span><span class="mono">{{ daemon?.version ?? '—' }}</span></div>
        <div class="kv"><span class="lbl">data dir</span><span class="mono">{{ daemon?.data_dir ?? '—' }}</span></div>
        <div class="kv"><span class="lbl">db.sqlite</span><span>{{ daemon ? fmtBytes(daemon.db_size_bytes) : '—' }}</span></div>
      </div>
      <hr />
      <div class="kv-row">
        <div class="kv"><span class="lbl">ollama</span>
          <Tag :tone="ollama.status === 'ok' ? 'accent' : 'default'">{{ ollama.status }}</Tag>
        </div>
        <div class="kv"><span class="lbl">latency</span><span>{{ ollama.latency_ms ?? '—' }} ms</span></div>
        <div class="kv"><span class="lbl">generate</span><span class="mono">{{ ollama.generate_model || '—' }}</span></div>
        <div class="kv"><span class="lbl">embedding</span><span class="mono">{{ ollama.embedding_model || '—' }}</span></div>
        <div class="kv"><span class="lbl">dim</span><span>{{ ollama.embedding_dim }}</span></div>
      </div>
    </Card>

    <Card class="section">
      <h3>衍生物 pipeline</h3>
      <table class="workers">
        <thead><tr><th>worker</th><th>queue</th><th>processed</th><th>last_run</th><th>throttle</th></tr></thead>
        <tbody>
          <tr v-for="(w, kind) in workers" :key="kind">
            <td class="mono">{{ kind }}</td>
            <td>{{ w.queue_depth }}</td>
            <td>{{ w.processed_total }}</td>
            <td class="mono">{{ fmtTs(w.last_run_at) }}</td>
            <td>{{ w.throttle ?? '—' }}</td>
          </tr>
        </tbody>
      </table>
    </Card>

    <Card class="section">
      <h3>Dead letter</h3>
      <table v-if="deadLetter.length" class="dl">
        <thead><tr><th>at</th><th>worker</th><th>mem_id</th><th>error</th></tr></thead>
        <tbody>
          <template v-for="(d, i) in deadLetter" :key="i">
            <tr @click="toggle(String(i))">
              <td class="mono">{{ fmtTs(d.at) }}</td>
              <td>{{ d.worker }}</td>
              <td class="mono">{{ d.mem_id ?? '—' }}</td>
              <td class="err-summary">{{ (d.traceback ?? '').split('\n')[0].slice(0, 80) }}</td>
            </tr>
            <tr v-if="expanded[String(i)]" class="trace-row">
              <td colspan="4"><pre class="trace">{{ d.traceback }}</pre></td>
            </tr>
          </template>
        </tbody>
      </table>
      <p v-else class="empty">管道空闲，没有失败任务。</p>
    </Card>
  </div>
</template>

<style scoped>
.status { display: flex; flex-direction: column; gap: var(--space-lg); }
.section h3 { margin-bottom: var(--space-md); }
.kv-row { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: var(--space-md); }
.kv { display: flex; flex-direction: column; gap: 2px; }
.kv .lbl { font-size: var(--fs-xs); color: var(--c-text-muted); text-transform: uppercase; letter-spacing: 0.5px; }
.kv .mono { font-family: var(--font-mono); font-size: var(--fs-xs); }
hr { border: none; border-top: 1px solid var(--c-divider); margin: var(--space-md) 0; }
table { width: 100%; border-collapse: collapse; font-size: var(--fs-sm); }
th { text-align: left; padding: var(--space-sm); border-bottom: 1px solid var(--c-border); color: var(--c-text-muted); font-weight: var(--fw-medium); font-size: var(--fs-xs); text-transform: uppercase; }
td { padding: var(--space-sm); border-bottom: 1px solid var(--c-divider); }
.mono { font-family: var(--font-mono); font-size: var(--fs-xs); }
.err-summary { color: var(--c-danger); }
.trace-row td { background: var(--c-bg-alt); }
.trace { white-space: pre-wrap; font-family: var(--font-mono); font-size: var(--fs-xs); margin: 0; }
.dl tbody tr { cursor: pointer; }
.empty { color: var(--c-text-muted); font-size: var(--fs-sm); padding: var(--space-md) 0; }
</style>
