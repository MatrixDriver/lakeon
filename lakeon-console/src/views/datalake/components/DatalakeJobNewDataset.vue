<template>
  <div>
    <div class="section-title">数据集</div>
    <div class="section-desc">选择输入数据集，系统自动将 OBS 路径注入 <code>DATASET_PATH_*</code> 环境变量。</div>

    <div class="field-group">
      <label class="field-label">输入数据集</label>
      <div class="dataset-list" v-if="datasets.length">
        <div v-for="d in datasets" :key="d.id" class="dataset-item"
             :class="{ selected: isSelected(d.id) }" @click="toggleDataset(d.id)">
          <input type="checkbox" :checked="isSelected(d.id)" @click.stop="toggleDataset(d.id)" />
          <span class="dataset-name">{{ d.name }}</span>
          <span class="dataset-meta">{{ d.rowCount?.toLocaleString() ?? '?' }} 行 · {{ formatSize(d.fileSizeBytes) }}</span>
        </div>
      </div>
      <div v-else-if="!loading" class="field-hint">暂无可用数据集</div>
      <div v-if="loading" class="field-hint">加载中...</div>
      <div v-if="inputDatasetIds.length" class="inject-hint">
        ✅ 已选 {{ inputDatasetIds.length }} 个数据集，<code>DATASET_PATH_*</code> 将自动注入
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
import { formatSize } from '../../../utils/format'

const props = defineProps<{ inputDatasetIds: string[]; outputPath: string }>()
const emit = defineEmits<{
  'update:inputDatasetIds': [value: string[]]
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


function isSelected(id: string) { return props.inputDatasetIds.includes(id) }
function toggleDataset(id: string) {
  const updated = isSelected(id)
    ? props.inputDatasetIds.filter(i => i !== id)
    : [...props.inputDatasetIds, id]
  emit('update:inputDatasetIds', updated)
}
</script>

<style scoped>
.section-title { font-size: 15px; font-weight: 700; color: #1e293b; margin-bottom: 4px; }
.section-desc { font-size: 12px; color: #64748b; margin-bottom: 20px; line-height: 1.5; }
code { background: #f1f5f9; padding: 1px 5px; border-radius: 3px; font-size: 11px; }
.field-group { margin-bottom: 18px; }
.field-label { display: block; font-size: 12px; font-weight: 600; color: #374151; margin-bottom: 6px; }
.optional { font-weight: 400; color: #94a3b8; font-size: 11px; }
.field-input { width: 100%; max-width: 480px; background: #fff; border: 1px solid #e2e8f0; border-radius: 6px; padding: 8px 12px; font-size: 13px; color: #1e293b; outline: none; }
.field-input:focus { border-color: #2563eb; }
.field-hint { font-size: 11px; color: #94a3b8; margin-top: 5px; }
.inject-hint { font-size: 11px; color: #15803d; margin-top: 5px; }
.dataset-list { border: 1px solid #e2e8f0; border-radius: 8px; max-height: 200px; overflow-y: auto; max-width: 480px; }
.dataset-item { display: flex; align-items: center; gap: 8px; padding: 8px 12px; cursor: pointer; border-bottom: 1px solid #f1f5f9; font-size: 12px; }
.dataset-item:last-child { border-bottom: none; }
.dataset-item:hover { background: #f8fafc; }
.dataset-item.selected { background: #eff6ff; }
.dataset-name { font-weight: 600; color: #1e293b; }
.dataset-meta { color: #94a3b8; font-size: 11px; margin-left: auto; }
</style>
