<template>
  <div class="page-container">
    <div class="page-header">
      <div>
        <div class="breadcrumb">
          <router-link to="/datalake/pipelines" class="breadcrumb-link">数据生产线</router-link>
          <span class="breadcrumb-sep"> / </span>
          <span>{{ pipeline?.name || '...' }}</span>
        </div>
        <div class="detail-meta" v-if="pipeline">
          <span class="meta-tag">{{ pipeline.data_type || '通用' }}</span>
          <span class="meta-id">{{ pipeline.id }}</span>
        </div>
      </div>
      <div class="page-header-actions">
        <button class="btn btn-secondary" @click="router.push(`/datalake/pipelines/${pipelineId}/edit`)">编辑</button>
        <button class="btn btn-primary" @click="openTriggerDialog">触发运行</button>
      </div>
    </div>

    <!-- Tab 切换 -->
    <div class="detail-tabs">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        class="detail-tab"
        :class="{ active: activeTab === tab.key }"
        @click="activeTab = tab.key"
      >{{ tab.label }}</button>
    </div>

    <!-- 版本列表 -->
    <div v-if="activeTab === 'versions'" class="section">
      <table class="data-table">
        <thead>
          <tr>
            <th>版本</th>
            <th>状态</th>
            <th>变更说明</th>
            <th>创建时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="v in versions" :key="v.id">
            <td><strong>v{{ v.version }}</strong></td>
            <td>
              <span class="version-status" :class="v.status.toLowerCase()">{{ versionStatusLabel(v.status) }}</span>
            </td>
            <td style="color: #666;">{{ v.changelog || '—' }}</td>
            <td style="color: #999;">{{ formatTime(v.created_at) }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="versions.length === 0 && !loading" class="empty-hint">暂无版本</div>
    </div>

    <!-- 运行历史 -->
    <div v-if="activeTab === 'runs'" class="section">
      <table class="data-table">
        <thead>
          <tr>
            <th>运行 ID</th>
            <th>版本</th>
            <th>状态</th>
            <th>开始时间</th>
            <th>耗时</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="run in runs" :key="run.id">
            <td>
              <router-link
                :to="`/datalake/pipelines/${pipelineId}/runs/${run.id}`"
                class="name-link"
              >{{ run.id }}</router-link>
            </td>
            <td>v{{ run.pipeline_version }}</td>
            <td>
              <span class="status-dot" :class="'dot-' + runDotClass(run.status)"></span>
              {{ runStatusLabel(run.status) }}
            </td>
            <td style="color: #999;">{{ formatTime(run.started_at || run.created_at) }}</td>
            <td style="color: #666;">{{ runDuration(run) }}</td>
            <td>
              <router-link
                :to="`/datalake/pipelines/${pipelineId}/runs/${run.id}`"
                class="btn btn-text btn-small btn-accent-text"
              >查看</router-link>
              <button
                v-if="run.status === 'RUNNING' || run.status === 'PAUSED'"
                class="btn btn-text btn-small btn-danger-text"
                @click="handleCancel(run.id)"
              >取消</button>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="runs.length === 0 && !loading" class="empty-hint">暂无运行记录</div>
    </div>

    <!-- 触发运行弹窗 -->
    <div v-if="showTriggerDialog" class="dialog-overlay" @click.self="closeTriggerDialog">
      <div class="dialog" :class="{ 'dialog-wide': triggerStep === 'preview' }">
        <!-- Step 1: 选择版本和数据集 -->
        <template v-if="triggerStep === 'form'">
          <h3>触发运行</h3>
          <div class="dialog-field">
            <label>Pipeline 版本</label>
            <select v-model="triggerForm.version">
              <option :value="undefined">最新版本 (v{{ pipeline?.latest_version }})</option>
              <option v-for="v in versions" :key="v.version" :value="v.version">v{{ v.version }}</option>
            </select>
          </div>
          <div class="dialog-field">
            <label>输入数据集 ID（可选）</label>
            <input v-model="triggerForm.inputDatasetId" placeholder="ds_xxx" />
          </div>
          <div class="dialog-actions">
            <button class="btn btn-secondary" @click="closeTriggerDialog">取消</button>
            <button class="btn btn-primary" @click="handlePreview" :disabled="previewLoading">
              {{ previewLoading ? '加载中...' : '下一步' }}
            </button>
          </div>
        </template>

        <!-- Step 2: 运行预览 -->
        <template v-if="triggerStep === 'preview'">
          <PipelineRunPreview
            :dag-yaml="previewDagYaml"
            :components="allComponents"
            :component-versions="componentVersionMap"
            :dataset-name="triggerForm.inputDatasetId || undefined"
          />
          <div class="dialog-actions">
            <button class="btn btn-secondary" @click="triggerStep = 'form'">上一步</button>
            <button class="btn btn-primary" @click="handleTrigger" :disabled="triggering">
              {{ triggering ? '提交中...' : '确认运行' }}
            </button>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  getPipeline, listPipelineVersions, listPipelineRuns,
  triggerPipelineRun, cancelPipelineRun,
  listComponents, getComponentLatestVersion,
  type Pipeline, type PipelineVersion, type PipelineRun,
  type PipelineComponent, type PipelineComponentVersion,
} from '@/api/pipeline'
import PipelineRunPreview from './components/pipeline/PipelineRunPreview.vue'

const route = useRoute()
const router = useRouter()
const pipelineId = computed(() => route.params.id as string)

const loading = ref(true)
const pipeline = ref<Pipeline | null>(null)
const versions = ref<PipelineVersion[]>([])
const runs = ref<PipelineRun[]>([])
const activeTab = ref('runs')
const showTriggerDialog = ref(false)
const triggering = ref(false)
const triggerForm = ref<{ version?: number; inputDatasetId?: string }>({})
const triggerStep = ref<'form' | 'preview'>('form')
const previewLoading = ref(false)
const previewDagYaml = ref('')
const allComponents = ref<PipelineComponent[]>([])
const componentVersionMap = ref<Map<string, PipelineComponentVersion>>(new Map())

const tabs = [
  { key: 'runs', label: '运行历史' },
  { key: 'versions', label: '版本列表' },
]

function versionStatusLabel(s: string): string {
  const m: Record<string, string> = { DRAFT: '草稿', PUBLISHED: '已发布', DEPRECATED: '已废弃' }
  return m[s] || s
}

function runStatusLabel(s: string): string {
  const m: Record<string, string> = {
    PENDING: '等待中', RUNNING: '运行中', PAUSED: '已暂停',
    SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消',
  }
  return m[s] || s
}

function runDotClass(s: string): string {
  if (s === 'RUNNING') return 'blue'
  if (s === 'SUCCEEDED') return 'green'
  if (s === 'FAILED') return 'red'
  if (s === 'PAUSED') return 'yellow'
  return 'gray'
}

function runDuration(run: PipelineRun): string {
  if (!run.started_at) return '—'
  const start = new Date(run.started_at).getTime()
  const end = run.finished_at ? new Date(run.finished_at).getTime() : Date.now()
  const sec = Math.round((end - start) / 1000)
  if (sec < 60) return `${sec}s`
  if (sec < 3600) return `${Math.floor(sec / 60)}m ${sec % 60}s`
  return `${Math.floor(sec / 3600)}h ${Math.floor((sec % 3600) / 60)}m`
}

function formatTime(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

async function loadData() {
  loading.value = true
  try {
    const [pRes, vRes, rRes] = await Promise.allSettled([
      getPipeline(pipelineId.value),
      listPipelineVersions(pipelineId.value),
      listPipelineRuns(pipelineId.value),
    ])
    if (pRes.status === 'fulfilled') pipeline.value = pRes.value.data
    else console.error('Failed to load pipeline', pRes.reason)
    if (vRes.status === 'fulfilled') versions.value = vRes.value.data
    else console.error('Failed to load versions', vRes.reason)
    if (rRes.status === 'fulfilled') runs.value = rRes.value.data
    else console.error('Failed to load runs', rRes.reason)
  } catch (err) {
    console.error('Failed to load pipeline detail', err)
  } finally {
    loading.value = false
  }
}

function openTriggerDialog() {
  triggerForm.value = {}
  triggerStep.value = 'form'
  showTriggerDialog.value = true
}

function closeTriggerDialog() {
  showTriggerDialog.value = false
  triggerStep.value = 'form'
}

async function handlePreview() {
  previewLoading.value = true
  try {
    // Determine the version to preview
    const ver = triggerForm.value.version ?? pipeline.value?.latest_version
    if (!ver) {
      alert('未找到可用版本')
      return
    }

    // Load the DAG YAML for the selected version
    const versionObj = versions.value.find(v => v.version === ver)
    if (versionObj) {
      previewDagYaml.value = versionObj.dag_yaml
    } else {
      // Fetch from API
      const { getPipelineVersion } = await import('@/api/pipeline')
      const res = await getPipelineVersion(pipelineId.value, ver)
      previewDagYaml.value = res.data.dag_yaml
    }

    // Load components and their versions for display
    if (allComponents.value.length === 0) {
      const compRes = await listComponents()
      allComponents.value = compRes.data
    }

    // Parse DAG to find which components are used, then load their versions
    const { parseDagYaml } = await import('./components/pipeline/dagUtils')
    const dag = parseDagYaml(previewDagYaml.value)
    const newMap = new Map(componentVersionMap.value)
    const fetchPromises: Promise<void>[] = []
    for (const step of dag.steps) {
      if (step.component && !newMap.has(step.component)) {
        const comp = allComponents.value.find(c => c.name === step.component)
        if (comp) {
          fetchPromises.push(
            getComponentLatestVersion(comp.id).then(res => {
              newMap.set(step.component!, res.data)
            }).catch(() => { /* ignore */ })
          )
        }
      }
    }
    if (fetchPromises.length > 0) {
      await Promise.allSettled(fetchPromises)
      componentVersionMap.value = newMap
    }

    triggerStep.value = 'preview'
  } catch (err) {
    console.error('Failed to load preview', err)
    alert('加载预览失败')
  } finally {
    previewLoading.value = false
  }
}

async function handleTrigger() {
  triggering.value = true
  try {
    const res = await triggerPipelineRun(pipelineId.value, {
      pipeline_version: triggerForm.value.version,
      input_dataset_id: triggerForm.value.inputDatasetId || undefined,
    })
    closeTriggerDialog()
    router.push(`/datalake/pipelines/${pipelineId.value}/runs/${res.data.id}`)
  } catch (err) {
    console.error('Failed to trigger run', err)
    alert('触发运行失败')
  } finally {
    triggering.value = false
  }
}

async function handleCancel(runId: string) {
  if (!confirm('确认取消此运行？')) return
  try {
    await cancelPipelineRun(runId)
    await loadData()
  } catch (err) {
    console.error('Failed to cancel run', err)
  }
}

onMounted(loadData)
</script>

<style scoped>
.breadcrumb { font-size: 13px; color: #999; }
.breadcrumb-link { color: #2a4d6a; text-decoration: none; }
.breadcrumb-link:hover { text-decoration: underline; }
.breadcrumb-sep { margin: 0 4px; }
.detail-meta { margin-top: 4px; display: flex; gap: 8px; align-items: center; }
.meta-tag { font-size: 11px; padding: 2px 8px; border-radius: 3px; background: #f5f3f0; color: #666; }
.meta-id { font-size: 11px; color: #bbb; }

.detail-tabs { display: flex; gap: 0; border-bottom: 1px solid #e8e4df; margin-bottom: 16px; }
.detail-tab {
  padding: 8px 16px; border: none; background: none; cursor: pointer;
  font-size: 13px; color: #666; border-bottom: 2px solid transparent;
  transition: all 0.12s;
}
.detail-tab.active { color: #2a4d6a; border-bottom-color: #2a4d6a; font-weight: 500; }
.detail-tab:hover:not(.active) { color: #2c3e50; }

.section { margin-top: 8px; }
.name-link { color: #2a4d6a; text-decoration: none; font-weight: 500; }
.name-link:hover { text-decoration: underline; }
.empty-hint { text-align: center; color: #ccc; padding: 32px 0; font-size: 13px; }
.version-status { font-size: 11px; padding: 2px 8px; border-radius: 3px; }
.version-status.draft { background: #f5f3f0; color: #94a3b8; }
.version-status.published { background: #ecfdf5; color: #16a34a; }
.version-status.deprecated { background: #fef2f2; color: #ef4444; }

/* 弹窗 */
.dialog-overlay {
  position: fixed; inset: 0; background: rgba(0,0,0,0.3); display: flex;
  align-items: center; justify-content: center; z-index: 100;
}
.dialog {
  background: #fff; border-radius: 10px; padding: 24px; width: 400px;
  box-shadow: 0 8px 32px rgba(0,0,0,0.12);
  transition: width 0.2s ease;
}
.dialog.dialog-wide {
  width: 520px;
}
.dialog h3 { margin: 0 0 16px; font-size: 16px; color: #2c3e50; }
.dialog-field { margin-bottom: 12px; }
.dialog-field label { display: block; font-size: 12px; color: #666; margin-bottom: 4px; }
.dialog-field input, .dialog-field select {
  width: 100%; padding: 6px 10px; border: 1px solid #e8e4df; border-radius: 4px;
  font-size: 13px; outline: none;
}
.dialog-field input:focus, .dialog-field select:focus { border-color: #2a4d6a; }
.dialog-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }
</style>
