<template>
  <div>
    <div class="section-title">数据集</div>
    <div class="section-desc">选择输入数据集，系统自动将 OBS 路径注入 <code>DATASET_PATH</code> 环境变量。</div>

    <div class="field-group">
      <label class="field-label">输入数据集</label>
      <select
        class="field-select"
        :value="inputDatasetId"
        @change="$emit('update:inputDatasetId', ($event.target as HTMLSelectElement).value)"
      >
        <option value="">— 不绑定数据集 —</option>
        <option v-for="d in datasets" :key="d.id" :value="d.id">
          {{ d.name }} ({{ d.rowCount?.toLocaleString() ?? '?' }} 行 · {{ formatSize(d.fileSizeBytes) }})
        </option>
      </select>
      <div v-if="loading" class="field-hint">加载中...</div>
      <div v-if="inputDatasetId" class="inject-hint">
        ✅ <code>DATASET_PATH</code> 将自动注入 OBS 路径
      </div>
    </div>

    <div class="field-group">
      <label class="field-label">输出 OBS 路径 <span class="optional">（可选）</span></label>
      <input
        class="field-input"
        :value="outputPath"
        @input="$emit('update:outputPath', ($event.target as HTMLInputElement).value)"
        placeholder="obs://my-bucket/output/ （留空自动生成）"
        style="font-family: monospace; font-size: 12px;"
      />
      <div class="field-hint">留空时自动生成路径并注入 <code>OUTPUT_PATH</code> 环境变量</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import api from '../../../api/client'

defineProps<{ inputDatasetId: string; outputPath: string }>()
defineEmits<{
  'update:inputDatasetId': [value: string]
  'update:outputPath': [value: string]
}>()

interface Dataset { id: string; name: string; status: string; rowCount?: number; fileSizeBytes?: number }
const datasets = ref<Dataset[]>([])
const loading = ref(false)

onMounted(async () => {
  loading.value = true
  try {
    const res = await api.get('/datasets')
    const all: Dataset[] = (res.data?.data ?? res.data) || []
    datasets.value = all.filter(d => d.status === 'READY')
  } catch {
    // non-fatal
  } finally {
    loading.value = false
  }
})

function formatSize(bytes?: number): string {
  if (!bytes) return '?'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(0) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}
</script>

<style scoped>
.section-title { font-size: 15px; font-weight: 700; color: #1e293b; margin-bottom: 4px; }
.section-desc { font-size: 12px; color: #64748b; margin-bottom: 20px; line-height: 1.5; }
code { background: #f1f5f9; padding: 1px 5px; border-radius: 3px; font-size: 11px; }
.field-group { margin-bottom: 18px; }
.field-label { display: block; font-size: 12px; font-weight: 600; color: #374151; margin-bottom: 6px; }
.optional { font-weight: 400; color: #94a3b8; font-size: 11px; }
.field-select, .field-input { width: 100%; max-width: 480px; background: #fff; border: 1px solid #e2e8f0; border-radius: 6px; padding: 8px 12px; font-size: 13px; color: #1e293b; outline: none; }
.field-select:focus, .field-input:focus { border-color: #2563eb; }
.field-hint { font-size: 11px; color: #94a3b8; margin-top: 5px; }
.inject-hint { font-size: 11px; color: #15803d; margin-top: 5px; }
</style>
