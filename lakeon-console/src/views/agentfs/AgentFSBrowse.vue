<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">Agent 文件 · AgentFS</h1>
      <p style="font-size: 13px; color: var(--c-text-3); margin: 6px 0 0;">
        Claude Code / OpenClaw 等 agent 通过 FUSE 上云的工作目录。
        与记忆库分开——这里是<strong>原始字节</strong>，不做 embedding 索引。
      </p>
    </div>

    <!-- Summary cards -->
    <div v-if="stats" style="display: flex; gap: 16px; margin-bottom: 24px;">
      <div class="card" style="flex: 1; padding: 20px; text-align: center;">
        <div style="font-family: var(--font-display); font-size: 32px; font-weight: 500; color: var(--c-primary);">{{ stats.file_count }}</div>
        <div style="font-size: 13px; color: var(--c-text-3); margin-top: 4px;">文件数</div>
      </div>
      <div class="card" style="flex: 1; padding: 20px; text-align: center;">
        <div style="font-family: var(--font-display); font-size: 32px; font-weight: 500; color: var(--c-accent-text);">{{ formatBytes(stats.total_bytes) }}</div>
        <div style="font-size: 13px; color: #999; margin-top: 4px;">总大小</div>
      </div>
      <div class="card" style="flex: 1; padding: 20px; text-align: center;">
        <div style="font-family: var(--font-display); font-size: 16px; font-weight: 500; color: var(--c-text-1); margin-top: 8px;">{{ stats.last_write_ns ? formatTime(stats.last_write_ns) : '—' }}</div>
        <div style="font-size: 13px; color: #999; margin-top: 4px;">最后写入</div>
      </div>
    </div>

    <!-- Breadcrumb -->
    <div style="display: flex; align-items: center; gap: 6px; margin-bottom: 12px; font-size: 14px;">
      <span style="color: var(--c-text-3);">路径:</span>
      <a href="#" @click.prevent="goTo('/')" style="color: var(--c-primary); text-decoration: none;">/</a>
      <template v-for="(seg, i) in breadcrumbs" :key="i">
        <span style="color: var(--c-text-3);">/</span>
        <a href="#" @click.prevent="goTo(breadcrumbPath(i))" style="color: var(--c-primary); text-decoration: none;">{{ seg }}</a>
      </template>
    </div>

    <!-- Directory listing -->
    <div class="card" style="padding: 0; overflow: hidden;">
      <table style="width: 100%; border-collapse: collapse; font-size: 13px;">
        <thead>
          <tr style="background: var(--c-bg-soft); text-align: left;">
            <th style="padding: 10px 16px; font-weight: 600;">名称</th>
            <th style="padding: 10px 16px; font-weight: 600; width: 80px;">类型</th>
            <th style="padding: 10px 16px; font-weight: 600; width: 120px;">大小</th>
            <th style="padding: 10px 16px; font-weight: 600; width: 180px;">最后修改</th>
            <th style="padding: 10px 16px; font-weight: 600; width: 140px;">ETag</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading">
            <td colspan="5" style="padding: 30px; text-align: center; color: #999;">加载中...</td>
          </tr>
          <tr v-else-if="entries.length === 0">
            <td colspan="5" style="padding: 30px; text-align: center; color: #999;">
              空目录。FUSE 客户端写入后这里会显示文件。参见
              <a href="https://github.com/MatrixDriver/lakeon/blob/main/docs/agentfs-user-guide.md" target="_blank" style="color: var(--c-primary);">使用指南</a>。
            </td>
          </tr>
          <tr v-for="e in entries" :key="e.path"
              @click="onRowClick(e)"
              :style="{cursor: 'pointer'}"
              class="file-row">
            <td style="padding: 10px 16px;">
              <span style="margin-right: 6px;">{{ e.kind === 'dir' ? '📁' : '📄' }}</span>
              {{ basename(e.path) }}
            </td>
            <td style="padding: 10px 16px; color: #666;">{{ e.kind === 'dir' ? '目录' : '文件' }}</td>
            <td style="padding: 10px 16px; color: #666;">{{ e.kind === 'dir' ? '—' : formatBytes(e.size) }}</td>
            <td style="padding: 10px 16px; color: #666;">{{ formatTime(e.mtime_ns) }}</td>
            <td style="padding: 10px 16px; color: #666; font-family: monospace; font-size: 11px;">{{ e.etag.substring(0, 12) }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- File preview modal -->
    <div v-if="previewFile"
         @click="previewFile = null"
         style="position: fixed; inset: 0; background: rgba(0,0,0,0.4); display: flex; align-items: center; justify-content: center; z-index: 50;">
      <div @click.stop
           style="background: white; border-radius: 8px; padding: 24px; max-width: 80vw; max-height: 80vh; width: 800px; display: flex; flex-direction: column;">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;">
          <h3 style="margin: 0; font-size: 15px; font-weight: 600;">{{ previewFile.path }}</h3>
          <button @click="previewFile = null" style="background: none; border: none; font-size: 20px; cursor: pointer;">✕</button>
        </div>
        <div style="font-size: 12px; color: #999; margin-bottom: 12px;">
          {{ formatBytes(previewFile.size) }} · ETag {{ previewFile.etag.substring(0, 16) }} · {{ formatTime(previewFile.mtime_ns) }}
        </div>
        <pre v-if="previewContent !== null"
             style="flex: 1; overflow: auto; background: #f8f9fa; padding: 16px; border-radius: 4px; font-size: 12px; font-family: monospace; white-space: pre-wrap;">{{ previewContent }}</pre>
        <div v-else style="text-align: center; color: #999; padding: 40px;">加载中...</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { getAgentFSStats, listAgentFiles, readAgentFile,
         type AgentFileEntry, type AgentFSStats } from '@/api/agentfs'

const stats = ref<AgentFSStats | null>(null)
const entries = ref<AgentFileEntry[]>([])
const loading = ref(false)
const currentPath = ref('/')
const previewFile = ref<AgentFileEntry | null>(null)
const previewContent = ref<string | null>(null)

const breadcrumbs = computed(() => {
  return currentPath.value.split('/').filter(Boolean)
})

function breadcrumbPath(idx: number): string {
  return '/' + breadcrumbs.value.slice(0, idx + 1).join('/')
}

function basename(p: string): string {
  const parts = p.split('/')
  return parts[parts.length - 1] || p
}

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / 1024 / 1024).toFixed(2)} MB`
}

function formatTime(ns: number): string {
  if (!ns) return '—'
  const d = new Date(ns / 1e6)
  return d.toLocaleString('zh-CN', { hour12: false })
}

async function loadStats() {
  try {
    const { data } = await getAgentFSStats()
    stats.value = data
  } catch (e) {
    console.error('load stats failed', e)
  }
}

async function loadList(path: string) {
  loading.value = true
  try {
    const { data } = await listAgentFiles(path, false)
    entries.value = data.entries
  } catch (e) {
    console.error('load list failed', e)
    entries.value = []
  } finally {
    loading.value = false
  }
}

function goTo(path: string) {
  currentPath.value = path
  loadList(path)
}

async function onRowClick(e: AgentFileEntry) {
  if (e.kind === 'dir') {
    goTo(e.path)
  } else {
    previewFile.value = e
    previewContent.value = null
    if (e.size > 1_000_000) {
      previewContent.value = `(文件 > 1MB, 不预览)`
      return
    }
    try {
      previewContent.value = await readAgentFile(e.path)
    } catch (err) {
      previewContent.value = `加载失败: ${err}`
    }
  }
}

onMounted(() => {
  loadStats()
  loadList('/')
})
</script>

<style scoped>
.file-row:hover {
  background: var(--c-bg-soft);
}
.file-row td {
  border-top: 1px solid var(--c-border);
}
</style>
