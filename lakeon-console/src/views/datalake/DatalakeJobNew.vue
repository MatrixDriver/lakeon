<template>
  <div class="job-new-page">
    <div class="page-header">
      <div class="breadcrumb">
        <router-link to="/datalake" class="breadcrumb-link">数据湖</router-link>
        <span class="breadcrumb-sep"> / </span>
        <span>新建作业</span>
      </div>
    </div>

    <div class="job-new-body">
      <nav class="section-nav">
        <div class="section-nav-label">配置</div>
        <div
          v-for="s in visibleSections"
          :key="s.key"
          class="section-nav-item"
          :class="{ active: currentSection === s.key, done: isDone(s.key) }"
          @click="currentSection = s.key"
        >
          <span class="section-num">{{ isDone(s.key) ? '✓' : s.num }}</span>
          {{ s.label }}
        </div>
      </nav>

      <div class="section-content">
        <div v-if="form.name && form.type" class="summary-card">
          <div class="summary-left">
            <div class="summary-row">
              <span class="summary-field-label">作业名称</span>
              <strong>{{ form.name }}</strong>
            </div>
            <div class="summary-row" style="margin-top:8px;">
              <span class="summary-field-label">类型</span>
              <span class="type-pill" :class="'pill-' + form.type.toLowerCase()">
                {{ typeLabel(form.type) }}
              </span>
            </div>
          </div>
          <button class="btn-link" @click="currentSection = 'basic'">编辑</button>
        </div>

        <DatalakeJobNewBasic
          v-if="currentSection === 'basic'"
          :name="form.name"
          :type="form.type"
          @update:name="form.name = $event"
          @update:type="form.type = $event; currentSection = 'code'"
        />
        <DatalakeJobNewCode
          v-else-if="currentSection === 'code'"
          :script="form.inlineScript"
          :requirements="form.requirements"
          @update:script="form.inlineScript = $event"
          @update:requirements="form.requirements = $event"
          @update:usedDatasetIds="form.inputDatasetIds = $event"
        />
        <DatalakeJobNewDataset
          v-else-if="currentSection === 'dataset'"
          :input-dataset-ids="form.inputDatasetIds"
          :output-path="form.outputPath"
          @update:inputDatasetIds="form.inputDatasetIds = $event"
          @update:outputPath="form.outputPath = $event"
        />
        <DatalakeJobNewResources
          v-else-if="currentSection === 'resources'"
          :type="form.type"
          :cpu="form.cpu"
          :memory="form.memory"
          @update:cpu="form.cpu = $event"
          @update:memory="form.memory = $event"
        />
        <DatalakeJobNewEnvVars
          v-else-if="currentSection === 'envvars'"
          :input-dataset-ids="form.inputDatasetIds"
          :output-path="form.outputPath"
          :user-vars="form.userEnvVars"
          @update:userVars="form.userEnvVars = $event"
        />
        <DatalakeJobNewAdvanced
          v-else-if="currentSection === 'advanced'"
          :timeout-seconds="form.timeoutSeconds"
          :retry-count="form.retryCount"
          @update:timeoutSeconds="form.timeoutSeconds = $event"
          @update:retryCount="form.retryCount = $event"
        />
      </div>
    </div>

    <div class="submit-bar">
      <div class="submit-summary">
        <strong>{{ typeLabel(form.type) }}</strong>
        <template v-if="form.inlineScript"> · 内联脚本</template>
        <template v-if="form.inputDatasetIds.length"> · 输入数据集 ×{{ form.inputDatasetIds.length }}</template>
        · CPU {{ form.cpu }} / 内存 {{ form.memory }}
      </div>
      <div class="submit-actions">
        <router-link to="/datalake" class="btn btn-ghost">取消</router-link>
        <button class="btn btn-primary" :disabled="!canSubmit || submitting" @click="handleSubmit">
          {{ submitting ? '提交中...' : '提交作业' }}
        </button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { submitDatalakeJob, type DatalakeJobType } from '../../api/datalake'
import DatalakeJobNewBasic from './components/DatalakeJobNewBasic.vue'
import DatalakeJobNewCode from './components/DatalakeJobNewCode.vue'
import DatalakeJobNewDataset from './components/DatalakeJobNewDataset.vue'
import DatalakeJobNewResources from './components/DatalakeJobNewResources.vue'
import DatalakeJobNewEnvVars from './components/DatalakeJobNewEnvVars.vue'
import DatalakeJobNewAdvanced from './components/DatalakeJobNewAdvanced.vue'

const router = useRouter()

const form = ref({
  name: '',
  type: 'PYTHON' as DatalakeJobType,
  inlineScript: '',
  requirements: '',
  inputDatasetIds: [] as string[],
  outputPath: '',
  cpu: '1',
  memory: '2Gi',
  userEnvVars: [] as { key: string; value: string }[],
  timeoutSeconds: 3600,
  retryCount: 0,
})

const currentSection = ref('basic')
const submitting = ref(false)

type Section = { key: string; num: string; label: string; types?: DatalakeJobType[] }

const allSections: Section[] = [
  { key: 'basic',     num: '1', label: '基本信息' },
  { key: 'code',      num: '2', label: '代码',      types: ['PYTHON', 'RAY'] },
  { key: 'dataset',   num: '3', label: '数据集' },
  { key: 'resources', num: '4', label: '资源' },
  { key: 'envvars',   num: '5', label: '环境变量' },
  { key: 'advanced',  num: '6', label: '超时 & 重试' },
]

const visibleSections = computed(() =>
  allSections.filter(s => !s.types || s.types.includes(form.value.type))
)

const isDone = (key: string) => {
  if (key === 'basic') return !!(form.value.name && form.value.type)
  if (key === 'code') return !!form.value.inlineScript.trim()
  return false
}

const canSubmit = computed(() => !!form.value.name.trim())

const typeLabel = (t: DatalakeJobType) =>
  ({ PYTHON: '🐍 Python', RAY: '⚡ Ray', FINETUNE: '🧠 微调' })[t] ?? t

async function handleSubmit() {
  if (!canSubmit.value) return
  submitting.value = true
  try {
    const envVars: Record<string, string> = {}
    form.value.userEnvVars.forEach(({ key, value }) => {
      if (key.trim()) envVars[key.trim()] = value
    })

    const body: Parameters<typeof submitDatalakeJob>[0] = {
      name: form.value.name,
      type: form.value.type,
      inline_script: form.value.inlineScript || undefined,
      requirements: form.value.requirements || undefined,
      input_dataset_ids: form.value.inputDatasetIds.length ? form.value.inputDatasetIds : undefined,
      output_path: form.value.outputPath || undefined,
      resources: { cpu: form.value.cpu, memory: form.value.memory },
      env_vars: Object.keys(envVars).length ? envVars : undefined,
      timeout_seconds: form.value.timeoutSeconds,
      retry_count: form.value.retryCount,
    }

    const res = await submitDatalakeJob(body)
    const job = (res.data as any)?.data ?? res.data
    router.push(`/datalake/jobs/${job.id}`)
  } catch (e: any) {
    alert('提交失败: ' + (e.response?.data?.error?.message || e.message))
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.job-new-page { display: flex; flex-direction: column; height: 100%; background: #f8fafc; }
.page-header { background: #fff; border-bottom: 1px solid #e2e8f0; padding: 12px 24px; }
.breadcrumb { font-size: 13px; color: #94a3b8; }
.breadcrumb-link { color: #94a3b8; text-decoration: none; }
.breadcrumb-link:hover { color: #2563eb; }
.breadcrumb-sep { margin: 0 6px; }
.job-new-body { display: flex; flex: 1; overflow: hidden; }
.section-nav { width: 168px; background: #fff; border-right: 1px solid #e2e8f0; padding: 16px 0; flex-shrink: 0; }
.section-nav-label { font-size: 10px; font-weight: 700; color: #94a3b8; text-transform: uppercase; letter-spacing: .6px; padding: 0 16px 8px; }
.section-nav-item { display: flex; align-items: center; gap: 8px; padding: 8px 16px; font-size: 12px; color: #64748b; cursor: pointer; position: relative; }
.section-nav-item:hover { background: #f8fafc; }
.section-nav-item.active { color: #2563eb; font-weight: 700; background: #eff6ff; }
.section-nav-item.active::before { content: ''; position: absolute; left: 0; top: 0; bottom: 0; width: 3px; background: #2563eb; border-radius: 0 2px 2px 0; }
.section-num { width: 18px; height: 18px; border-radius: 50%; background: #e2e8f0; color: #64748b; font-size: 9px; font-weight: 700; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.section-nav-item.active .section-num { background: #2563eb; color: #fff; }
.section-nav-item.done .section-num { background: #22c55e; color: #fff; font-size: 10px; }
.section-content { flex: 1; overflow-y: auto; padding: 20px 24px; }
.summary-card { background: #fff; border: 1px solid #e2e8f0; border-radius: 8px; padding: 12px 16px; display: flex; align-items: center; justify-content: space-between; margin-bottom: 16px; }
.summary-left { display: flex; gap: 24px; align-items: center; }
.summary-row { display: flex; align-items: center; gap: 8px; }
.summary-field-label { font-size: 11px; color: #94a3b8; min-width: 55px; }
.type-pill { display: inline-block; padding: 3px 10px; border-radius: 12px; font-size: 11px; font-weight: 600; }
.pill-python { background: #fef3c7; color: #92400e; }
.pill-ray { background: #ede9fe; color: #6d28d9; }
.pill-finetune { background: #fce7f3; color: #9d174d; }
.btn-link { background: none; border: none; color: #2563eb; font-size: 12px; cursor: pointer; padding: 0; }
.submit-bar { background: #fff; border-top: 1px solid #e2e8f0; padding: 12px 24px; display: flex; align-items: center; justify-content: space-between; flex-shrink: 0; }
.submit-summary { font-size: 12px; color: #64748b; }
.submit-actions { display: flex; gap: 8px; align-items: center; }
.btn { padding: 7px 16px; border-radius: 6px; font-size: 12px; font-weight: 600; cursor: pointer; border: none; text-decoration: none; display: inline-flex; align-items: center; }
.btn-primary { background: #2563eb; color: #fff; }
.btn-primary:disabled { opacity: .5; cursor: default; }
.btn-ghost { color: #64748b; background: none; }
</style>
