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
const variables = ref<Array<{ name: string; type: string; repr: string }>>([])

const imageKey = ref('python-data')
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
    const { data } = await createSession(imageKey.value, dsIds)
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
.nb-cells { max-width: 960px; }
.nb-add-btn { display: block; width: 100%; padding: 10px; margin-top: 4px; background: none; border: 2px dashed #e5e7eb; border-radius: 8px; color: #9ca3af; font-size: 13px; cursor: pointer; text-align: center; }
.nb-add-btn:hover { border-color: #2563eb; color: #2563eb; }
.nb-vars-panel { border: 1px solid #e5e7eb; border-radius: 8px; padding: 8px; margin-bottom: 16px; background: #f9fafb; max-height: 200px; overflow-y: auto; }
.nb-vars-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.nb-vars-table th { text-align: left; padding: 4px 10px; color: #6b7280; border-bottom: 1px solid #e5e7eb; font-weight: 600; }
.nb-vars-table td { padding: 3px 10px; border-bottom: 1px solid #f1f5f9; }
</style>
