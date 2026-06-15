<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <h1 class="page-title">LakebaseFS</h1>
        <p class="page-subtitle">
          通用文件系统（lbfs），接入本地目录、数据目录和工具工作区。这里保存原始字节与目录 profile，后台处理由目录类型决定。
        </p>
      </div>
    </div>

    <div v-if="stats" class="status-bar">
      <div class="status-bar-item">
        <span class="status-bar-label">文件数</span>
        <span class="status-bar-count">{{ stats.file_count }}</span>
      </div>
      <div class="status-bar-item">
        <span class="status-bar-label">总大小</span>
        <span class="status-bar-count">{{ formatBytes(stats.total_bytes) }}</span>
      </div>
      <div class="status-bar-item">
        <span class="status-bar-label">最后写入</span>
        <span class="status-bar-count">{{ stats.last_write_ns ? formatTime(stats.last_write_ns) : '—' }}</span>
      </div>
    </div>

    <div class="lbfs-control-grid">
      <div class="section-card">
        <div class="section-header">
          <h3>目录 Profile</h3>
          <span class="section-meta">{{ foldersLoading ? '加载中' : `${folders.length} 个目录` }}</span>
        </div>
        <div class="table-wrapper lbfs-table-wrapper">
          <table class="data-table">
            <thead>
              <tr>
                <th>名称</th>
                <th>目录类型</th>
                <th>存储策略</th>
                <th>后台处理</th>
                <th>状态</th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="foldersLoading && folders.length === 0">
                <td colspan="5" class="empty-cell">加载中...</td>
              </tr>
              <tr v-else-if="!foldersLoading && folders.length === 0">
                <td colspan="5" class="empty-cell">还没有目录 profile。创建后，LakebaseFS 会按目录类型选择存储和后台处理策略。</td>
              </tr>
              <tr v-for="folder in folders" :key="folder.id">
                <td class="folder-name-cell">
                  <span>{{ folder.display_name }}</span>
                  <small>{{ folder.id }}</small>
                </td>
                <td>{{ kindLabel(folder.directory_kind) }}</td>
                <td><span class="status-tag tag-blue">{{ folder.storage_policy }}</span></td>
                <td><span class="status-tag" :class="processingClass(folder.processing_profile)">{{ folder.processing_profile }}</span></td>
                <td><span class="status-tag tag-green">{{ folder.status }}</span></td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <form class="section-card lbfs-create-card" @submit.prevent="createFolder">
        <div class="section-header">
          <h3>添加目录</h3>
        </div>
        <div class="lbfs-create-body">
          <div class="form-group">
            <label class="form-label">名称</label>
            <input v-model="createForm.display_name" class="form-input" placeholder="例如：warehouse" />
          </div>
          <div class="form-group">
            <label class="form-label">目录类型</label>
            <select v-model="createForm.directory_kind" class="form-select">
              <option v-for="kind in directoryKinds" :key="kind.value" :value="kind.value">
                {{ kind.label }}
              </option>
            </select>
          </div>
          <div class="create-policy-preview">
            <div>
              <span>默认存储</span>
              <strong>{{ defaultPolicy.storage }}</strong>
            </div>
            <div>
              <span>后台处理</span>
              <strong>{{ defaultPolicy.processing }}</strong>
            </div>
          </div>
          <button class="btn btn-primary" type="submit" :disabled="creatingFolder || !createForm.display_name.trim()">
            {{ creatingFolder ? '创建中...' : '创建目录' }}
          </button>
          <div v-if="folderError" class="form-error">{{ folderError }}</div>
        </div>
      </form>
    </div>

    <div class="lbfs-breadcrumb" aria-label="当前路径">
      <span>路径</span>
      <button type="button" @click="goTo('/')">/</button>
      <template v-for="(seg, i) in breadcrumbs" :key="i">
        <span>/</span>
        <button type="button" @click="goTo(breadcrumbPath(i))">{{ seg }}</button>
      </template>
    </div>

    <div class="section-card listing-card">
      <div v-if="loading" class="loading-bar" aria-hidden="true"></div>
      <div class="section-header">
        <h3>文件</h3>
        <span class="section-meta">{{ currentPath }}</span>
      </div>
      <div class="table-wrapper lbfs-table-wrapper">
        <table class="data-table">
          <thead>
            <tr>
              <th class="th-sortable" @click="setSort('name')">
                <span>名称</span><span class="sort-indicator">{{ sortIcon('name') }}</span>
              </th>
              <th class="th-sortable" @click="setSort('kind')">
                <span>类型</span><span class="sort-indicator">{{ sortIcon('kind') }}</span>
              </th>
              <th class="th-sortable" @click="setSort('size')">
                <span>大小</span><span class="sort-indicator">{{ sortIcon('size') }}</span>
              </th>
              <th class="th-sortable" @click="setSort('mtime_ns')">
                <span>最后修改</span><span class="sort-indicator">{{ sortIcon('mtime_ns') }}</span>
              </th>
              <th class="th-sortable" @click="setSort('etag')">
                <span>ETag</span><span class="sort-indicator">{{ sortIcon('etag') }}</span>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="loading && entries.length === 0">
              <td colspan="5" class="empty-cell">加载中...</td>
            </tr>
            <tr v-else-if="!loading && entries.length === 0">
              <td colspan="5" class="empty-cell">
                空目录。LakebaseFS 客户端写入或同步目录后，这里会显示文件。
              </td>
            </tr>
            <tr
              v-for="e in sortedEntries"
              :key="e.path"
              class="file-row"
              @click="onRowClick(e)"
            >
              <td>
                <div class="file-name-cell">
                  <span class="file-kind-mark">{{ e.kind === 'dir' ? 'DIR' : 'FILE' }}</span>
                  <span>{{ basename(e.path) }}</span>
                </div>
              </td>
              <td>{{ e.kind === 'dir' ? '目录' : '文件' }}</td>
              <td>{{ e.kind === 'dir' ? '—' : formatBytes(e.size) }}</td>
              <td>{{ formatTime(e.mtime_ns) }}</td>
              <td class="etag-cell">{{ e.etag.substring(0, 12) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div v-if="previewFile"
         @click="previewFile = null"
         class="dialog-overlay">
      <div @click.stop class="dialog-box file-preview-dialog">
        <div class="dialog-header">
          <h3>{{ previewFile.path }}</h3>
          <button class="dialog-close" @click="previewFile = null">x</button>
        </div>
        <div class="preview-meta">
          {{ formatBytes(previewFile.size) }} · ETag {{ previewFile.etag.substring(0, 16) }} · {{ formatTime(previewFile.mtime_ns) }}
        </div>
        <pre v-if="previewContent !== null" class="preview-content">{{ previewContent }}</pre>
        <div v-else class="empty-state">加载中...</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import {
  createLBFSFolder,
  getLBFSStats,
  listLBFSFiles,
  listLBFSFolders,
  readLBFSFile,
  type LBFSFileEntry,
  type LBFSStats,
  type LBFSDirectoryKind,
  type LBFSFolder,
} from '@/api/lbfs'

type SortKey = 'name' | 'kind' | 'size' | 'mtime_ns' | 'etag'

const stats = ref<LBFSStats | null>(null)
const folders = ref<LBFSFolder[]>([])
const foldersLoading = ref(false)
const creatingFolder = ref(false)
const folderError = ref('')
const entries = ref<LBFSFileEntry[]>([])
const loading = ref(false)
const currentPath = ref('/')
const previewFile = ref<LBFSFileEntry | null>(null)
const previewContent = ref<string | null>(null)
const sortKey = ref<SortKey>('name')
const sortDir = ref<'asc' | 'desc'>('asc')
let listSeq = 0

const directoryKinds: Array<{ value: LBFSDirectoryKind; label: string }> = [
  { value: 'files', label: '通用文件' },
  { value: 'data-dir', label: '数据目录' },
  { value: 'codex-home', label: '开发工具用户目录' },
  { value: 'claude-home', label: 'AI 工具用户目录' },
  { value: 'openclaw-home', label: '工作区用户目录' },
  { value: 'opencode-home', label: '代码工具用户目录' },
  { value: 'iceberg-table', label: 'Iceberg 表目录' },
  { value: 'lance-table', label: 'Lance 表目录' },
]

const createForm = ref({
  display_name: '',
  directory_kind: 'files' as LBFSDirectoryKind,
})

const defaultPolicy = computed(() => {
  switch (createForm.value.directory_kind) {
    case 'codex-home':
    case 'claude-home':
    case 'openclaw-home':
    case 'opencode-home':
      return { storage: 'auto', processing: 'agent-home' }
    case 'data-dir':
      return { storage: 'object-first', processing: 'dataset' }
    case 'iceberg-table':
      return { storage: 'table-native', processing: 'iceberg' }
    case 'lance-table':
      return { storage: 'table-native', processing: 'lance' }
    default:
      return { storage: 'auto', processing: 'none' }
  }
})

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
    const { data } = await getLBFSStats()
    stats.value = data
  } catch (e) {
    console.error('load stats failed', e)
  }
}

async function loadFolders() {
  foldersLoading.value = true
  try {
    const { data } = await listLBFSFolders()
    folders.value = data.folders
  } catch (e) {
    console.error('load lbfs folders failed', e)
    folders.value = []
  } finally {
    foldersLoading.value = false
  }
}

async function createFolder() {
  const name = createForm.value.display_name.trim()
  if (!name || creatingFolder.value) return
  creatingFolder.value = true
  folderError.value = ''
  try {
    await createLBFSFolder({
      display_name: name,
      directory_kind: createForm.value.directory_kind,
    })
    createForm.value.display_name = ''
    await loadFolders()
  } catch (e: any) {
    folderError.value = e?.response?.data?.error?.message || e?.response?.data?.message || '创建目录失败'
  } finally {
    creatingFolder.value = false
  }
}

async function loadList(path: string) {
  const seq = ++listSeq
  loading.value = true
  try {
    const { data } = await listLBFSFiles(path, false)
    if (seq !== listSeq) return
    entries.value = data.entries
  } catch (e) {
    if (seq !== listSeq) return
    console.error('load list failed', e)
    entries.value = []
  } finally {
    if (seq === listSeq) loading.value = false
  }
}

const sortedEntries = computed(() => {
  const arr = entries.value.slice()
  const dir = sortDir.value === 'asc' ? 1 : -1
  const key = sortKey.value
  arr.sort((a, b) => {
    let av: string | number
    let bv: string | number
    switch (key) {
      case 'name':
        av = basename(a.path).toLowerCase()
        bv = basename(b.path).toLowerCase()
        break
      case 'kind':
        av = a.kind
        bv = b.kind
        break
      case 'size':
        av = a.kind === 'dir' ? -1 : a.size
        bv = b.kind === 'dir' ? -1 : b.size
        break
      case 'mtime_ns':
        av = a.mtime_ns
        bv = b.mtime_ns
        break
      case 'etag':
        av = a.etag
        bv = b.etag
        break
    }
    if (av < bv) return -1 * dir
    if (av > bv) return 1 * dir
    const an = basename(a.path).toLowerCase()
    const bn = basename(b.path).toLowerCase()
    return an < bn ? -1 : an > bn ? 1 : 0
  })
  return arr
})

function setSort(key: SortKey) {
  if (sortKey.value === key) {
    sortDir.value = sortDir.value === 'asc' ? 'desc' : 'asc'
  } else {
    sortKey.value = key
    sortDir.value = key === 'mtime_ns' || key === 'size' ? 'desc' : 'asc'
  }
}

function kindLabel(kind: LBFSDirectoryKind): string {
  return directoryKinds.find((item) => item.value === kind)?.label || kind
}

function processingClass(profile: string): string {
  if (profile === 'none') return 'tag-gray'
  if (profile === 'agent-home') return 'tag-orange'
  if (profile === 'dataset') return 'tag-blue'
  return 'tag-green'
}

function sortIcon(key: SortKey): string {
  if (sortKey.value !== key) return ''
  return sortDir.value === 'asc' ? '↑' : '↓'
}

function goTo(path: string) {
  currentPath.value = path
  loadList(path)
}

async function onRowClick(e: LBFSFileEntry) {
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
      previewContent.value = await readLBFSFile(e.path)
    } catch (err) {
      previewContent.value = `加载失败: ${err}`
    }
  }
}

onMounted(() => {
  loadFolders()
  loadStats()
  loadList('/')
})
</script>

<style scoped>
.file-row:hover {
  cursor: pointer;
}

.th-sortable {
  cursor: pointer;
  user-select: none;
}

.th-sortable:hover {
  color: var(--c-primary);
}

.sort-indicator {
  display: inline-block;
  width: 12px;
  margin-left: 4px;
  color: var(--c-primary);
  font-size: 11px;
}

.listing-card {
  position: relative;
}

.lbfs-control-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: var(--space-lg);
  margin-bottom: var(--space-xl);
  align-items: start;
}

.lbfs-table-wrapper {
  border: 0;
  border-radius: 0;
}

.folder-name-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  color: var(--c-accent-text);
  font-weight: 500;
}

.folder-name-cell small {
  color: var(--c-text-3);
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 400;
}

.lbfs-create-body {
  padding: var(--space-lg);
}

.create-policy-preview {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--space-md);
  padding: var(--space-md);
  margin-bottom: var(--space-lg);
  border-radius: 4px;
  background: var(--c-bg-alt);
}

.create-policy-preview span {
  display: block;
  color: var(--c-text-3);
  font-size: 11px;
  margin-bottom: 2px;
}

.create-policy-preview strong {
  color: var(--c-text);
  font-size: 12px;
  font-weight: 600;
}

.form-error {
  margin-top: var(--space-md);
  color: var(--cs-severe);
  font-size: 12px;
}

.lbfs-breadcrumb {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  margin-bottom: var(--space-lg);
  font-size: 13px;
  color: var(--c-text-3);
}

.lbfs-breadcrumb button {
  border: 0;
  background: none;
  color: var(--c-accent-text);
  font: inherit;
  cursor: pointer;
  padding: 2px 3px;
}

.lbfs-breadcrumb button:hover {
  color: var(--c-accent-hover);
}

.file-name-cell {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  min-width: 0;
}

.file-kind-mark {
  min-width: 34px;
  font-family: var(--font-mono);
  font-size: 10px;
  color: var(--c-text-3);
}

.etag-cell {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--c-text-2);
}

.empty-cell {
  text-align: center;
  color: var(--c-text-3);
  padding: var(--space-3xl) var(--space-xl) !important;
}

.file-preview-dialog {
  width: min(860px, 86vw);
  max-height: 82vh;
  display: flex;
  flex-direction: column;
}

.preview-meta {
  color: var(--c-text-3);
  font-size: 12px;
  margin-bottom: var(--space-md);
}

.preview-content {
  flex: 1;
  overflow: auto;
  min-height: 320px;
  margin: 0;
  padding: var(--space-lg);
  border-radius: 4px;
  background: var(--c-bg-alt);
  color: var(--c-text);
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
}

.listing-card .loading-bar {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 2px;
  background: linear-gradient(90deg, transparent 0%, var(--c-primary) 50%, transparent 100%);
  background-size: 50% 100%;
  background-repeat: no-repeat;
  animation: loading-slide 1.1s ease-in-out infinite;
  z-index: 1;
  pointer-events: none;
}
@keyframes loading-slide {
  0%   { background-position: -50% 0; }
  100% { background-position: 150% 0; }
}
</style>
