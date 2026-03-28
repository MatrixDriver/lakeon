<template>
  <div class="page-container">
    <div class="nb-toolbar">
      <div class="nb-toolbar-left">
        <span class="nb-title">Notebook</span>
        <span class="nb-status" :class="kernelStatus">{{ statusLabel }}</span>
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
          @update:code="cell.code = $event; saveCells()"
          @run="runCell(i)" @delete="deleteCell(i)"
          @focus="activeIndex = i" @advance="advanceCell(i)"
          @toggle-type="toggleCellType(i)"
        />
        <button class="nb-add-btn" @click="addCell()">+ Add Cell</button>
      </div>

      <!-- Reference Panel -->
      <aside class="nb-ref" :class="{ collapsed: !showRef }">
        <button class="nb-ref-toggle" @click="showRef = !showRef" :title="showRef ? 'Hide reference' : 'Show reference'">?</button>
        <div v-if="showRef" class="nb-ref-content">
          <h3>Quick Reference</h3>

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
ray.init()  # auto-connects
print(ray.cluster_resources())

@ray.remote
def task(x):
    return x * 2

results = ray.get(
  [task.remote(i) for i in range(10)]
)</pre>
          </div>
        </div>
      </aside>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import NotebookCell from './components/NotebookCell.vue'
import { createSession, stopSession as apiStopSession, NotebookSocket, type NotebookMessage } from '../../api/notebook'
import client from '../../api/client'

const router = useRouter()

interface Cell {
  id: string; code: string; outputs: NotebookMessage[]
  running: boolean; execCount: number | null; durationMs: number | null
  cellType: 'code' | 'markdown'
}

const cells = ref<Cell[]>([])
const activeIndex = ref(0)
const showVars = ref(false)
const showRef = ref(false)
const variables = ref<Array<{ name: string; type: string; repr: string }>>([])

const imageKey = ref('python-data')
const workerCount = ref(2)
const workerSize = ref('small')
const selectedDatasetId = ref('')
const datasets = ref<Array<{ id: string; name: string }>>([])
const sessionId = ref<string | null>(null)
const kernelStatus = ref<'stopped' | 'starting' | 'running' | 'disconnected'>('stopped')
let socket: NotebookSocket | null = null

const statusLabel = computed(() => ({
  stopped: 'Stopped', starting: 'Starting...', running: 'Running', disconnected: 'Disconnected'
}[kernelStatus.value] || kernelStatus.value))

function newCell(code = '', cellType: 'code' | 'markdown' = 'code'): Cell {
  return { id: 'cell_' + Math.random().toString(36).slice(2, 8), code, outputs: [], running: false, execCount: null, durationMs: null, cellType }
}

function addCell(code = '') { cells.value.push(newCell(code)); activeIndex.value = cells.value.length - 1; saveCells() }
function deleteCell(i: number) {
  if (cells.value.length <= 1) return
  cells.value.splice(i, 1)
  if (activeIndex.value >= cells.value.length) activeIndex.value = cells.value.length - 1
  saveCells()
}
function advanceCell(i: number) { if (i + 1 >= cells.value.length) addCell(); else activeIndex.value = i + 1 }

function runCell(i: number) {
  const cell = cells.value[i]
  if (cell == null || !cell.code.trim() || cell.running) return
  if (kernelStatus.value !== 'running') { startKernel().then(() => runCell(i)); return }
  cell.outputs = []
  cell.running = true
  cell.durationMs = null
  socket?.execute(cell.id, cell.code)
}

function toggleCellType(i: number) {
  const cell = cells.value[i]
  if (cell) {
    cell.cellType = cell.cellType === 'code' ? 'markdown' : 'code'
    saveCells()
  }
}

function requestVars() {
  showVars.value = !showVars.value
  if (showVars.value) {
    socket?.send({ type: 'vars', id: 'vars' })
  }
}

function handleMessage(msg: NotebookMessage) {
  if (msg.type === 'ready') return
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
    saveCells()
  } else {
    cell.outputs.push(msg)
  }
}

async function startKernel() {
  kernelStatus.value = 'starting'
  try {
    const dsIds = selectedDatasetId.value ? [selectedDatasetId.value] : undefined
    const isRay = imageKey.value === 'ray'
    const { data } = await createSession(imageKey.value, dsIds, isRay ? workerCount.value : 0, isRay ? workerSize.value : undefined)
    sessionId.value = data.id
    socket = new NotebookSocket(handleMessage, (s) => {
      if (s === 'connected') kernelStatus.value = 'running'
      else if (s === 'disconnected') kernelStatus.value = 'disconnected'
    })
    socket.connect()
  } catch (e: any) {
    kernelStatus.value = 'stopped'
    alert('Failed to start kernel: ' + (e.response?.data?.message || e.message))
  }
}

async function stopKernel() {
  if (sessionId.value) try { await apiStopSession(sessionId.value) } catch {}
  socket?.disconnect(); socket = null; sessionId.value = null; kernelStatus.value = 'stopped'
}

function submitAsJob() {
  const script = cells.value.map(c => c.code).filter(c => c.trim()).join('\n\n')
  sessionStorage.setItem('datalake_job_prefill', JSON.stringify({
    name: 'notebook-export', type: imageKey.value === 'ray' ? 'RAY' : 'PYTHON', inline_script: script,
  }))
  router.push('/datalake/jobs/new')
}

function saveCells() {
  localStorage.setItem('notebook_cells', JSON.stringify(cells.value.map(c => ({ id: c.id, code: c.code, cellType: c.cellType }))))
}
function loadCells() {
  try {
    const raw = localStorage.getItem('notebook_cells')
    if (raw) { const data = JSON.parse(raw); cells.value = data.map((d: any) => newCell(d.code, d.cellType || 'code')) }
  } catch {}
  if (cells.value.length === 0) addCell()
}

async function loadDatasets() {
  try { const { data } = await client.get('/datalake/datasets', { params: { status: 'READY' } }); datasets.value = data.map((d: any) => ({ id: d.id, name: d.name })) } catch {}
}

onMounted(() => { loadCells(); loadDatasets() })
onUnmounted(() => { socket?.disconnect() })
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
.nb-ref.collapsed { width: auto; }
.nb-ref-toggle {
  width: 28px; height: 28px; border-radius: 50%; border: 1px solid #d1d5db;
  background: #fff; color: #6b7280; font-size: 14px; font-weight: 700;
  cursor: pointer; display: flex; align-items: center; justify-content: center;
}
.nb-ref-toggle:hover { background: #f3f4f6; color: #2563eb; border-color: #2563eb; }
.nb-ref-content {
  width: 260px; margin-top: 8px; padding: 14px; background: #f9fafb;
  border: 1px solid #e5e7eb; border-radius: 8px; font-size: 12px; color: #374151;
}
.nb-ref-content h3 { font-size: 14px; font-weight: 700; color: #1e293b; margin: 0 0 12px; }
.nb-ref-section { margin-bottom: 14px; }
.nb-ref-section:last-child { margin-bottom: 0; }
.nb-ref-section h4 { font-size: 11px; font-weight: 700; color: #6b7280; text-transform: uppercase; letter-spacing: 0.5px; margin: 0 0 6px; }
.nb-ref-row { display: flex; gap: 8px; align-items: baseline; margin-bottom: 3px; line-height: 1.5; }
.nb-ref-row code { background: #e5e7eb; padding: 1px 5px; border-radius: 3px; font-size: 11px; font-family: monospace; white-space: nowrap; flex-shrink: 0; }
.nb-ref-row kbd { background: #1e293b; color: #fff; padding: 1px 5px; border-radius: 3px; font-size: 10px; font-family: monospace; white-space: nowrap; flex-shrink: 0; }
.nb-ref-code { background: #1e1e2e; color: #cdd6f4; padding: 8px 10px; border-radius: 6px; font-size: 11px; font-family: monospace; line-height: 1.5; overflow-x: auto; margin: 6px 0 0; white-space: pre; }
@media (max-width: 1100px) { .nb-ref { display: none; } }
</style>
