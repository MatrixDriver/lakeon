<template>
  <div class="page-container">
    <div class="nb-toolbar">
      <div class="nb-toolbar-left">
        <a class="nb-back" @click="$router.push('/datalake/notebook')">←</a>
        <span class="nb-title">{{ notebookName || 'Notebook' }}</span>
        <span class="nb-status" :class="kernelStatus">{{ statusLabel }}</span>
        <span v-if="saveStatus === 'saving'" class="nb-save-status saving">Saving...</span>
        <span v-else-if="saveStatus === 'saved'" class="nb-save-status saved">Saved ✓</span>
        <span v-else-if="saveStatus === 'error'" class="nb-save-status error">Save failed</span>
      </div>
      <div class="nb-toolbar-right">
        <select v-model="imageKey" class="nb-select" :disabled="kernelStatus === 'running'">
          <option value="python-data">python-data</option>
          <option value="ray">ray</option>
        </select>
        <template v-if="imageKey === 'ray'">
          <input type="number" v-model.number="workerCount" min="1" max="5"
                 class="nb-select" style="width: 50px;" title="Worker count" :disabled="kernelStatus === 'running'" />
          <select v-model="workerSize" class="nb-select" :disabled="kernelStatus === 'running'" title="Worker size">
            <option value="small">Small 1C2G</option>
            <option value="medium">Medium 2C4G</option>
            <option value="large">Large 4C8G</option>
          </select>
        </template>
        <select v-model="selectedDatasetId" class="nb-select">
          <option value="">-- 选择数据集 --</option>
          <option v-for="ds in datasets" :key="ds.id" :value="ds.id">{{ ds.name }}</option>
        </select>
        <button class="nb-btn" @click="requestVars" :disabled="kernelStatus !== 'running'">Variables</button>
        <button class="nb-btn" @click="toggleHistory" :disabled="!notebookId">History</button>
        <button v-if="!showRef" class="nb-btn" @click="showRef = true" title="Show reference">?</button>
        <button class="nb-btn nb-btn-primary" @click="submitAsJob" :disabled="cells.length === 0">Submit as Job</button>
        <button v-if="kernelStatus !== 'stopped'" class="nb-btn nb-btn-danger" @click="stopKernel">Stop Kernel</button>
        <button v-else class="nb-btn" @click="startKernel">Start Kernel</button>
      </div>
    </div>

    <div v-if="showVars" class="nb-vars-panel">
      <div v-if="variables.length === 0" style="color: #9ca3af; font-size: 12px; padding: 8px;">No variables defined</div>
      <table v-else class="nb-vars-table">
        <thead><tr><th>Name</th><th>Type</th><th>Value</th></tr></thead>
        <tbody>
          <tr v-for="v in variables" :key="v.name">
            <td style="font-family: monospace; font-weight: 500;">{{ v.name }}</td>
            <td style="font-family: monospace; color: #6b7280;">{{ v.type }}</td>
            <td style="font-family: monospace; color: #334155; max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">{{ v.repr }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="nb-body">
      <div class="nb-cells">
        <NotebookCell
          v-for="(cell, i) in cells" :key="cell.id"
          :code="cell.code" :is-active="activeIndex === i" :is-running="cell.running"
          :exec-count="cell.execCount" :duration-ms="cell.durationMs" :outputs="cell.outputs"
          :cell-type="cell.cellType"
          @update:code="cell.code = $event; scheduleSave()"
          @run="runCell(i)" @delete="deleteCell(i)"
          @focus="activeIndex = i" @advance="advanceCell(i)"
          @toggle-type="toggleCellType(i)"
        />
        <button class="nb-add-btn" @click="addCell()">+ Add Cell</button>
      </div>

      <!-- History Panel -->
      <aside v-if="showHistory" class="nb-history-panel">
        <div class="nb-ref-header">
          <h3>Version History</h3>
          <button class="nb-ref-close" @click="showHistory = false">&times;</button>
        </div>
        <p style="font-size:11px;color:#9ca3af;margin:0 0 8px;">Press Ctrl+S to save a version</p>
        <div v-if="versions.length === 0" style="color:#9ca3af;font-size:12px;padding:8px;">No versions yet</div>
        <div v-for="v in versions" :key="v" class="nb-version-item">
          <span class="nb-version-time">{{ formatVersionTime(v) }}</span>
          <button class="nb-cell-btn" @click="restoreVersion(v)">Restore</button>
        </div>
      </aside>

      <!-- Reference Panel -->
      <aside v-if="showRef" class="nb-ref">
        <div class="nb-ref-content">
          <div class="nb-ref-header">
            <h3>Quick Reference</h3>
            <button class="nb-ref-close" @click="showRef = false" title="Close">&times;</button>
          </div>

          <div class="nb-ref-section">
            <h4>Keyboard</h4>
            <div class="nb-ref-row"><kbd>Shift+Enter</kbd> Run & advance</div>
            <div class="nb-ref-row"><kbd>Ctrl+Enter</kbd> Run in place</div>
          </div>

          <div class="nb-ref-section">
            <h4>Magic Commands</h4>
            <div class="nb-ref-row"><code>%pip install pkg</code> Install packages</div>
            <div class="nb-ref-row"><code>%sh command</code> Run shell command</div>
            <div class="nb-ref-row"><code>%sql SELECT ...</code> Query database</div>
            <div class="nb-ref-row"><code>%md # Title</code> Markdown cell</div>
          </div>

          <div class="nb-ref-section">
            <h4>Environment Variables</h4>
            <div class="nb-ref-row"><code>DATASET_PATH</code> Selected dataset path</div>
            <div class="nb-ref-row"><code>OUTPUT_PATH</code> Job output path</div>
            <div class="nb-ref-row"><code>OBS_ENDPOINT</code> OBS endpoint URL</div>
            <div class="nb-ref-row"><code>OBS_BUCKET</code> OBS bucket name</div>
          </div>

          <div class="nb-ref-section">
            <h4>Common Patterns</h4>
            <pre class="nb-ref-code">import pandas as pd
import os

# Read dataset
path = os.environ["DATASET_PATH"]
df = pd.read_parquet(path)
df.head()</pre>
            <pre class="nb-ref-code"># Plotly chart
import plotly.express as px
fig = px.bar(df, x="col", y="val")
fig.show()</pre>
          </div>

          <div v-if="imageKey === 'ray'" class="nb-ref-section">
            <h4>Ray Distributed</h4>
            <p style="color:#6b7280;margin:0 0 4px;">ray.init() auto-connects to the cluster — no address needed.</p>
            <pre class="nb-ref-code">import ray
ray.init(ignore_reinit_error=True)  # auto-connects
print(ray.cluster_resources())

@ray.remote
def task(x):
    return x * 2

results = ray.get(
  [task.remote(i) for i in range(10)]
)
print(results)</pre>
          </div>
        </div>
      </aside>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import NotebookCell from './components/NotebookCell.vue'
import { createSession, getCurrentSession, stopSession as apiStopSession, NotebookSocket, type NotebookMessage } from '../../api/notebook'
import client from '../../api/client'
import { notebooksApi } from '../../api/notebooks'

const router = useRouter()
const route = useRoute()
const notebookId = computed(() => route.params.id as string)

interface Cell {
  id: string; code: string; outputs: NotebookMessage[]
  running: boolean; execCount: number | null; durationMs: number | null
  cellType: 'code' | 'markdown'
}

const cells = ref<Cell[]>([])
const activeIndex = ref(0)
const showVars = ref(false)
const showRef = ref(true)
const variables = ref<Array<{ name: string; type: string; repr: string }>>([])
const notebookName = ref('')
const saveStatus = ref<'idle' | 'saving' | 'saved' | 'error'>('idle')
let saveTimer: ReturnType<typeof setTimeout> | null = null

const imageKey = ref('python-data')
const workerCount = ref(2)
const workerSize = ref('small')
const selectedDatasetId = ref('')
const datasets = ref<Array<{ id: string; name: string }>>([])
const sessionId = ref<string | null>(null)
const kernelStatus = ref<'stopped' | 'starting' | 'running' | 'disconnected'>('stopped')
const progressText = ref('')
let socket: NotebookSocket | null = null

const statusLabel = computed(() => {
  if (kernelStatus.value === 'starting' && progressText.value) return progressText.value
  return { stopped: 'Stopped', starting: 'Starting kernel...', running: 'Running', disconnected: 'Reconnecting...' }[kernelStatus.value] || kernelStatus.value
})

function newCell(code = '', cellType: 'code' | 'markdown' = 'code'): Cell {
  return { id: 'cell_' + Math.random().toString(36).slice(2, 8), code, outputs: [], running: false, execCount: null, durationMs: null, cellType }
}

function addCell(code = '') { cells.value.push(newCell(code)); activeIndex.value = cells.value.length - 1; scheduleSave() }
function deleteCell(i: number) {
  if (cells.value.length <= 1) return
  cells.value.splice(i, 1)
  if (activeIndex.value >= cells.value.length) activeIndex.value = cells.value.length - 1
  scheduleSave()
}
function advanceCell(i: number) { if (i + 1 >= cells.value.length) addCell(); else activeIndex.value = i + 1 }

function runCell(i: number) {
  const cell = cells.value[i]
  if (cell == null || !cell.code.trim() || cell.running) return
  if (kernelStatus.value !== 'running') { startKernel(); return }
  cell.outputs = []
  cell.running = true
  cell.durationMs = null
  socket?.execute(cell.id, cell.code)
}

function toggleCellType(i: number) {
  const cell = cells.value[i]
  if (cell) {
    cell.cellType = cell.cellType === 'code' ? 'markdown' : 'code'
    scheduleSave()
  }
}

function requestVars() {
  showVars.value = !showVars.value
  if (showVars.value) {
    socket?.send({ type: 'vars', id: 'vars' })
  }
}

function handleMessage(msg: NotebookMessage) {
  if (msg.type === 'progress') {
    const elapsed = (msg as any).elapsed
    progressText.value = elapsed ? `${msg.text || ''} (${elapsed})` : (msg.text || '')
    return
  }
  if (msg.type === 'ready') {
    kernelStatus.value = 'running'
    progressText.value = ''
    return
  }
  if (msg.type === 'vars') {
    variables.value = msg.variables || []
    return
  }
  const cell = cells.value.find(c => c.id === msg.id)
  if (cell == null) return
  if (msg.type === 'done') {
    cell.running = false
    cell.durationMs = msg.duration_ms ?? null
    cell.execCount = msg.exec_count ?? null
    scheduleSave()
  } else {
    cell.outputs.push(msg)
  }
}

async function startKernel() {
  if (kernelStatus.value === 'starting') return  // prevent double-start
  kernelStatus.value = 'starting'
  try {
    const dsIds = selectedDatasetId.value ? [selectedDatasetId.value] : undefined
    const isRay = imageKey.value === 'ray'
    const { data } = await createSession(imageKey.value, dsIds, isRay ? workerCount.value : 0, isRay ? workerSize.value : undefined)
    sessionId.value = data.id
    socket = new NotebookSocket(handleMessage, (s) => {
      // Keep 'starting' until we get 'ready' message from repl_server
      if (s === 'connected' && kernelStatus.value === 'disconnected') kernelStatus.value = 'starting'
      else if (s === 'disconnected' && kernelStatus.value === 'running') kernelStatus.value = 'disconnected'
    })
    socket.connect()
  } catch (e: any) {
    kernelStatus.value = 'stopped'
    alert('Failed to start kernel: ' + (e.response?.data?.message || e.message))
  }
}

async function stopKernel() {
  if (sessionId.value) try { await apiStopSession(sessionId.value) } catch {}
  socket?.disconnect(); socket = null; sessionId.value = null; kernelStatus.value = 'stopped'; progressText.value = ''
}

function submitAsJob() {
  const script = cells.value.map(c => c.code).filter(c => c.trim()).join('\n\n')
  sessionStorage.setItem('datalake_job_prefill', JSON.stringify({
    name: 'notebook-export', type: imageKey.value === 'ray' ? 'RAY' : 'PYTHON', inline_script: script,
  }))
  router.push('/datalake/jobs/new')
}

function scheduleSave() {
  if (saveTimer) clearTimeout(saveTimer)
  saveStatus.value = 'idle'
  saveTimer = setTimeout(() => doSave(false), 3000)
}

function buildNotebookJson(): string {
  return JSON.stringify({
    cells: cells.value.map(c => ({
      id: c.id, code: c.code, cellType: c.cellType,
      outputs: c.outputs, execCount: c.execCount, durationMs: c.durationMs,
    })),
    image: imageKey.value,
    datasetIds: selectedDatasetId.value ? [selectedDatasetId.value] : [],
  })
}

async function doSave(version: boolean) {
  if (!notebookId.value) return
  saveStatus.value = 'saving'
  try {
    await notebooksApi.save(notebookId.value, buildNotebookJson(), version)
    saveStatus.value = 'saved'
    setTimeout(() => { if (saveStatus.value === 'saved') saveStatus.value = 'idle' }, 2000)
  } catch {
    saveStatus.value = 'error'
  }
}

function loadNotebookContent(content: any) {
  if (content.cells) {
    cells.value = content.cells.map((c: any) => ({
      id: c.id || 'cell_' + Math.random().toString(36).slice(2, 8),
      code: c.code || '',
      cellType: c.cellType || 'code',
      outputs: c.outputs || [],
      running: false,
      execCount: c.execCount || null,
      durationMs: c.durationMs || null,
    }))
  }
  if (content.image) imageKey.value = content.image
  if (content.datasetIds?.length) selectedDatasetId.value = content.datasetIds[0]
}

async function loadNotebook() {
  if (!notebookId.value) { addCell(); return }
  try {
    const { data } = await notebooksApi.get(notebookId.value)
    notebookName.value = data.name || ''
    if (data.content) {
      const content = typeof data.content === 'string' ? JSON.parse(data.content) : data.content
      loadNotebookContent(content)
    }
  } catch {}
  if (cells.value.length === 0) addCell()
}

async function loadDatasets() {
  try { const { data } = await client.get('/datalake/datasets', { params: { status: 'READY' } }); datasets.value = data.map((d: any) => ({ id: d.id, name: d.name })) } catch {}
}

const showHistory = ref(false)
const versions = ref<string[]>([])

async function toggleHistory() {
  showHistory.value = !showHistory.value
  if (showHistory.value && notebookId.value) {
    try {
      const { data } = await notebooksApi.listVersions(notebookId.value)
      versions.value = data
    } catch { versions.value = [] }
  }
}

function formatVersionTime(ts: string): string {
  // ts is like "2026-03-28T12-00-00Z" — convert dashes in time part back to colons
  const iso = ts.replace(/(\d{2})-(\d{2})-(\d{2})Z$/, '$1:$2:$3Z')
  return new Date(iso).toLocaleString('zh-CN')
}

async function restoreVersion(ts: string) {
  if (!confirm('Restore this version? Current content will be replaced.')) return
  if (!notebookId.value) return
  try {
    const { data } = await notebooksApi.restore(notebookId.value, ts)
    const content = typeof data === 'string' ? JSON.parse(data) : data
    loadNotebookContent(content)
    showHistory.value = false
  } catch (e: any) {
    alert('Restore failed: ' + (e.message || 'Unknown error'))
  }
}

function handleKeydown(e: KeyboardEvent) {
  if ((e.ctrlKey || e.metaKey) && e.key === 's') {
    e.preventDefault()
    doSave(true)
  }
}

async function checkExistingSession() {
  try {
    const { data } = await getCurrentSession()
    if (data.id && data.status === 'RUNNING') {
      sessionId.value = data.id
      if (data.image?.includes('ray')) imageKey.value = 'ray'
      if (data.worker_count) workerCount.value = data.worker_count
      kernelStatus.value = 'starting'
      socket = new NotebookSocket(handleMessage, (s) => {
        if (s === 'connected' && kernelStatus.value === 'disconnected') kernelStatus.value = 'starting'
        else if (s === 'disconnected' && kernelStatus.value === 'running') kernelStatus.value = 'disconnected'
      })
      socket.connect()
    }
  } catch {}
}

onMounted(() => { Promise.all([loadNotebook(), loadDatasets(), checkExistingSession()]); window.addEventListener('keydown', handleKeydown) })
onUnmounted(() => { socket?.disconnect(); window.removeEventListener('keydown', handleKeydown) })
</script>

<style scoped>
.nb-toolbar { display: flex; align-items: center; justify-content: space-between; padding: 12px 0; margin-bottom: 16px; border-bottom: 1px solid #e5e7eb; }
.nb-toolbar-left { display: flex; align-items: center; gap: 10px; }
.nb-toolbar-right { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.nb-title { font-size: 16px; font-weight: 700; color: #1e293b; }
.nb-status { font-size: 11px; padding: 2px 10px; border-radius: 10px; background: #f1f5f9; color: #64748b; }
.nb-status.running { background: #dcfce7; color: #16a34a; }
.nb-status.starting { background: #fef9c3; color: #a16207; }
.nb-status.disconnected { background: #fee2e2; color: #dc2626; }
.nb-select { font-size: 12px; padding: 5px 8px; border: 1px solid #d1d5db; border-radius: 4px; color: #374151; }
.nb-btn { font-size: 12px; padding: 5px 14px; border-radius: 6px; border: 1px solid #e5e7eb; background: white; color: #374151; cursor: pointer; }
.nb-btn:hover { background: #f9fafb; }
.nb-btn-primary { background: #2563eb; color: white; border: none; }
.nb-btn-primary:hover { background: #1d4ed8; }
.nb-btn-primary:disabled { background: #93c5fd; cursor: default; }
.nb-btn-danger { color: #ef4444; border-color: #fecaca; }
.nb-btn-danger:hover { background: #fef2f2; }
.nb-body { display: flex; gap: 16px; align-items: flex-start; }
.nb-cells { flex: 1; min-width: 0; max-width: 960px; }
.nb-add-btn { display: block; width: 100%; padding: 10px; margin-top: 4px; background: none; border: 2px dashed #e5e7eb; border-radius: 8px; color: #9ca3af; font-size: 13px; cursor: pointer; text-align: center; }
.nb-add-btn:hover { border-color: #2563eb; color: #2563eb; }
.nb-vars-panel { border: 1px solid #e5e7eb; border-radius: 8px; padding: 8px; margin-bottom: 16px; background: #f9fafb; max-height: 200px; overflow-y: auto; }
.nb-vars-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.nb-vars-table th { text-align: left; padding: 4px 10px; color: #6b7280; border-bottom: 1px solid #e5e7eb; font-weight: 600; }
.nb-vars-table td { padding: 3px 10px; border-bottom: 1px solid #f1f5f9; }

/* Reference Panel */
.nb-ref { position: sticky; top: 16px; flex-shrink: 0; }
.nb-ref-content {
  width: 260px; margin-top: 8px; padding: 14px; background: #f9fafb;
  border: 1px solid #e5e7eb; border-radius: 8px; font-size: 12px; color: #374151;
}
.nb-ref-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.nb-ref-header h3 { font-size: 14px; font-weight: 700; color: #1e293b; margin: 0; }
.nb-ref-close { background: none; border: none; font-size: 20px; color: #9ca3af; cursor: pointer; padding: 0 2px; line-height: 1; }
.nb-ref-close:hover { color: #374151; }
.nb-ref-section { margin-bottom: 14px; }
.nb-ref-section:last-child { margin-bottom: 0; }
.nb-ref-section h4 { font-size: 11px; font-weight: 700; color: #6b7280; text-transform: uppercase; letter-spacing: 0.5px; margin: 0 0 6px; }
.nb-ref-row { display: flex; gap: 8px; align-items: baseline; margin-bottom: 3px; line-height: 1.5; }
.nb-ref-row code { background: #e5e7eb; padding: 1px 5px; border-radius: 3px; font-size: 11px; font-family: monospace; white-space: nowrap; flex-shrink: 0; }
.nb-ref-row kbd { background: #1e293b; color: #fff; padding: 1px 5px; border-radius: 3px; font-size: 10px; font-family: monospace; white-space: nowrap; flex-shrink: 0; }
.nb-ref-code { background: #1e1e2e; color: #cdd6f4; padding: 8px 10px; border-radius: 6px; font-size: 11px; font-family: monospace; line-height: 1.5; overflow-x: auto; margin: 6px 0 0; white-space: pre; }
@media (max-width: 1100px) { .nb-ref { display: none; } }

/* Save status */
.nb-save-status { font-size: 11px; padding: 2px 8px; border-radius: 4px; }
.nb-save-status.saving { color: #a16207; background: #fef9c3; }
.nb-save-status.saved { color: #16a34a; background: #dcfce7; }
.nb-save-status.error { color: #dc2626; background: #fee2e2; }

/* Back link */
.nb-back { cursor: pointer; color: #6b7280; font-size: 18px; text-decoration: none; margin-right: 4px; }
.nb-back:hover { color: #2563eb; }

/* History panel */
.nb-history-panel { width: 240px; position: sticky; top: 16px; flex-shrink: 0; padding: 14px; background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 8px; font-size: 12px; max-height: 80vh; overflow-y: auto; }
.nb-version-item { display: flex; justify-content: space-between; align-items: center; padding: 6px 0; border-bottom: 1px solid #f1f5f9; }
.nb-version-item:hover { background: #f1f5f9; border-radius: 4px; }
.nb-version-time { color: #374151; font-size: 12px; }
.nb-cell-btn { font-size: 11px; padding: 2px 8px; border: 1px solid #e5e7eb; border-radius: 4px; background: white; color: #374151; cursor: pointer; }
.nb-cell-btn:hover { background: #f1f5f9; }
</style>
