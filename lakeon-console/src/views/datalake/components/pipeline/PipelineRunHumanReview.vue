<template>
  <div class="human-review">
    <div class="review-hint">
      此步骤需要人工审核确认后才能继续执行。
    </div>

    <!-- 数据预览区域（checkpoint 数据） -->
    <div v-if="previewItems.length > 0" class="preview-grid">
      <div
        v-for="(item, idx) in previewItems"
        :key="idx"
        class="preview-item"
        :class="{ selected: selectedItems.has(idx) }"
        @click="toggleItem(idx)"
      >
        <!-- 视频/图片缩略图 -->
        <div class="preview-thumb">
          <img v-if="item.thumbnail" :src="item.thumbnail" />
          <div v-else class="preview-placeholder">{{ item.name || `#${idx + 1}` }}</div>
        </div>
        <div class="preview-info">
          <span class="preview-name">{{ item.name || `Item ${idx + 1}` }}</span>
          <span v-if="item.meta" class="preview-meta">{{ item.meta }}</span>
        </div>
        <div class="preview-check">
          <svg v-if="selectedItems.has(idx)" viewBox="0 0 16 16" width="14" height="14" fill="#22c55e">
            <path d="M13.78 4.22a.75.75 0 0 1 0 1.06l-7.25 7.25a.75.75 0 0 1-1.06 0L2.22 9.28a.75.75 0 0 1 1.06-1.06L6 10.94l6.72-6.72a.75.75 0 0 1 1.06 0z"/>
          </svg>
        </div>
      </div>
    </div>

    <div v-else class="review-text-hint">
      暂无可预览的数据。请根据上游步骤结果决定是否通过。
    </div>

    <!-- 批量选择按钮 -->
    <div v-if="previewItems.length > 0" class="select-actions">
      <button class="btn btn-text btn-small" @click="selectAll">全选</button>
      <button class="btn btn-text btn-small" @click="deselectAll">全不选</button>
    </div>

    <!-- 操作按钮 -->
    <div class="review-actions">
      <button class="btn btn-danger" @click="$emit('reject')">
        拒绝全部
      </button>
      <button class="btn btn-primary" @click="$emit('approve')">
        通过选中项
      </button>
    </div>

    <div class="review-note">
      <span v-if="previewItems.length > 0">
        已选 {{ selectedItems.size }} / {{ previewItems.length }} 项。通过后将以选中项继续执行。
      </span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import type { PipelineStepRun } from '@/api/pipeline'

interface PreviewItem {
  name?: string
  thumbnail?: string
  meta?: string
}

const props = defineProps<{
  stepRun: PipelineStepRun
}>()

defineEmits<{
  approve: []
  reject: []
}>()

const previewItems = ref<PreviewItem[]>([])
const selectedItems = ref(new Set<number>())

function toggleItem(idx: number) {
  if (selectedItems.value.has(idx)) {
    selectedItems.value.delete(idx)
  } else {
    selectedItems.value.add(idx)
  }
}

function selectAll() {
  previewItems.value.forEach((_, i) => selectedItems.value.add(i))
}

function deselectAll() {
  selectedItems.value.clear()
}

onMounted(() => {
  // 尝试从 checkpoint 数据中加载预览
  // 实际实现需要从 OBS checkpoint 路径加载
  // Phase 1 使用 outputRef 中的简单列表
  if (props.stepRun.outputRef) {
    try {
      const refs = JSON.parse(props.stepRun.outputRef)
      if (Array.isArray(refs)) {
        previewItems.value = refs.map((r: any, i: number) => ({
          name: r.name || `Item ${i + 1}`,
          thumbnail: r.thumbnail,
          meta: r.meta || r.path,
        }))
        // 默认全选
        previewItems.value.forEach((_, i) => selectedItems.value.add(i))
      }
    } catch { /* ignore parse errors */ }
  }
})
</script>

<style scoped>
.human-review { padding: 0; }
.review-hint {
  font-size: 12px; color: #92700c; background: #fef9ee;
  padding: 8px 10px; border-radius: 4px; margin-bottom: 10px;
}

.preview-grid {
  display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px;
  max-height: 240px; overflow-y: auto; margin-bottom: 10px;
}
.preview-item {
  border: 2px solid #e8e4df; border-radius: 6px; padding: 4px;
  cursor: pointer; transition: all 0.12s; position: relative;
}
.preview-item.selected { border-color: #22c55e; background: #f0fdf4; }
.preview-item:hover { border-color: #2a4d6a; }
.preview-thumb { width: 100%; aspect-ratio: 1; overflow: hidden; border-radius: 4px; }
.preview-thumb img { width: 100%; height: 100%; object-fit: cover; }
.preview-placeholder {
  width: 100%; height: 100%; display: flex; align-items: center; justify-content: center;
  background: #f5f3f0; color: #999; font-size: 11px;
}
.preview-info { padding: 2px 0; }
.preview-name { font-size: 10px; color: #2c3e50; display: block; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.preview-meta { font-size: 9px; color: #999; }
.preview-check { position: absolute; top: 4px; right: 4px; }

.review-text-hint { font-size: 12px; color: #999; padding: 12px 0; text-align: center; }

.select-actions { display: flex; gap: 8px; margin-bottom: 6px; }

.review-actions { display: flex; gap: 8px; margin-top: 10px; }
.review-actions .btn { flex: 1; }
.btn-danger {
  background: #fef2f2; color: #ef4444; border: 1px solid #fca5a5;
  padding: 6px 12px; border-radius: 4px; cursor: pointer; font-size: 12px;
}
.btn-danger:hover { background: #fee2e2; }

.review-note { font-size: 11px; color: #999; margin-top: 8px; }
</style>
