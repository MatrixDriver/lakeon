<script setup lang="ts">
import { onMounted, onUnmounted, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { RouterView } from 'vue-router'
import AppShell from '@/components/AppShell.vue'
import LineageDrawer from '@/lineage/LineageDrawer.vue'
import QuickIngestDialog from '@/components/QuickIngestDialog.vue'
import { useStatusStore } from '@/stores/status'
import { useUiStore } from '@/stores/ui'

const POLL_BASE_MS = 10_000
const POLL_BURST_MS = 2_000

const status = useStatusStore()
const ui = useUiStore()
const { queueDepth, ollama } = storeToRefs(status)

let timer: number | null = null

async function tick() {
  if (document.hidden) { schedule(); return }
  try {
    await status.refresh()
    ui.clearBanner()
  } catch (e) {
    const err = e as { kind?: string; message?: string }
    if (err.kind === 'network' || err.kind === 'server') {
      ui.setBanner({
        kind: 'error',
        text: '无法连接 echomem daemon (127.0.0.1:8473)。请确认 echomem start 在跑。',
        retry: () => tick(),
      })
    }
  }
  schedule()
}

function schedule() {
  if (timer != null) clearTimeout(timer)
  const interval = queueDepth.value > 0 ? POLL_BURST_MS : POLL_BASE_MS
  timer = window.setTimeout(tick, interval)
}

function onVisibility() { if (!document.hidden) tick() }

watch(ollama, (o) => {
  if (o.status !== 'ok' && status.daemon) {
    ui.setBanner({
      kind: 'warning',
      text: 'AI worker 暂停——ollama 离线。已 ingest 的记忆会在恢复后自动消化。',
    })
  }
})

onMounted(() => {
  tick()
  document.addEventListener('visibilitychange', onVisibility)
})
onUnmounted(() => {
  if (timer) clearTimeout(timer)
  document.removeEventListener('visibilitychange', onVisibility)
})
</script>

<template>
  <AppShell>
    <RouterView />
  </AppShell>
  <LineageDrawer />
  <QuickIngestDialog />
</template>
