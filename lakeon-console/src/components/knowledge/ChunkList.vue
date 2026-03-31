<template>
  <div class="chunk-list" ref="listRef" @scroll="handleScroll">
    <!-- Stats summary -->
    <div v-if="stats" class="chunk-stats">
      <span>{{ stats.total_chunks }} 个切片</span>
      <span v-if="stats.anomaly_count > 0" style="color: #fa8c16;">{{ stats.anomaly_count }} 异常</span>
      <span v-if="stats.duplicate_count > 0" style="color: #e6393d;">{{ stats.duplicate_count }} 重复</span>
    </div>

    <!-- Jump to chunk -->
    <div class="chunk-jump">
      <input
        v-model="jumpInput"
        class="chunk-jump-input"
        placeholder="跳转到 #"
        @keyup.enter="handleJump"
      />
    </div>

    <!-- Chunk cards (incrementally rendered) -->
    <div
      v-for="chunk in visibleChunks"
      :key="chunk.id"
      :ref="el => { if (chunk.chunk_index === selectedIndex) selectedEl = el as HTMLElement }"
      class="chunk-card"
      :class="{
        selected: selectedIndex === chunk.chunk_index,
        'anomaly-short': isShort(chunk),
        'anomaly-long': isLong(chunk),
        'anomaly-duplicate': isDuplicate(chunk),
      }"
      @click="$emit('select', chunk.chunk_index)"
    >
      <div class="chunk-card-header">
        <span class="chunk-index">#{{ chunk.chunk_index }}</span>
        <span class="chunk-chars">{{ chunk.char_count }} 字</span>
        <span v-if="isDuplicate(chunk)" class="anomaly-icon anomaly-dup" title="疑似重复">
          <svg viewBox="0 0 16 16" width="14" height="14" fill="#e6393d"><path d="M8 1a7 7 0 1 0 0 14A7 7 0 0 0 8 1zm-.5 3h1v5h-1V4zm0 6h1v1h-1v-1z"/></svg>
        </span>
        <span v-else-if="isShort(chunk) || isLong(chunk)" class="anomaly-icon anomaly-warn" title="长度异常">
          <svg viewBox="0 0 16 16" width="14" height="14" fill="#fa8c16"><path d="M8 1a7 7 0 1 0 0 14A7 7 0 0 0 8 1zm-.5 3h1v5h-1V4zm0 6h1v1h-1v-1z"/></svg>
        </span>
      </div>
      <div v-if="chunk.metadata?.section" class="chunk-section">{{ chunk.metadata.section }}</div>
      <div class="chunk-preview">{{ chunk.content }}</div>
    </div>

    <!-- Load more indicator -->
    <div v-if="renderCount < chunks.length" class="load-more-hint">
      显示 {{ renderCount }}/{{ chunks.length }}，滚动加载更多
    </div>

    <div v-if="chunks.length === 0" class="empty-state" style="padding: 32px 12px;">
      <p style="color: #999;">暂无切片</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, nextTick } from 'vue'
import type { Chunk, ChunkStats } from '../../api/knowledge'

const PAGE_SIZE = 50

const props = defineProps<{
  chunks: Chunk[]
  selectedIndex: number
  stats: ChunkStats | null
}>()

const emit = defineEmits<{
  select: [chunkIndex: number]
}>()

const listRef = ref<HTMLElement | null>(null)
const selectedEl = ref<HTMLElement | null>(null)
const renderCount = ref(PAGE_SIZE)
const jumpInput = ref('')

const visibleChunks = computed(() => props.chunks.slice(0, renderCount.value))

// Reset render count when chunks change (new document)
watch(() => props.chunks.length, () => {
  renderCount.value = PAGE_SIZE
})

// When selected chunk is beyond rendered range, expand to include it
watch(() => props.selectedIndex, (idx) => {
  const pos = props.chunks.findIndex(c => c.chunk_index === idx)
  if (pos >= 0 && pos >= renderCount.value) {
    renderCount.value = Math.min(props.chunks.length, pos + PAGE_SIZE)
  }
  // Scroll selected card into view
  nextTick(() => {
    if (selectedEl.value) {
      selectedEl.value.scrollIntoView({ behavior: 'instant', block: 'nearest' })
    }
  })
})

function handleScroll() {
  const el = listRef.value
  if (!el) return
  // Load more when near bottom
  if (el.scrollTop + el.clientHeight >= el.scrollHeight - 100) {
    if (renderCount.value < props.chunks.length) {
      renderCount.value = Math.min(props.chunks.length, renderCount.value + PAGE_SIZE)
    }
  }
}

function handleJump() {
  const n = parseInt(jumpInput.value.replace('#', '').trim())
  if (isNaN(n)) return
  const chunk = props.chunks.find(c => c.chunk_index === n)
  if (chunk) {
    emit('select', chunk.chunk_index)
    jumpInput.value = ''
  }
}

function isShort(chunk: Chunk) {
  return chunk.char_count < 80
}

function isLong(chunk: Chunk) {
  return chunk.char_count > 800
}

function isDuplicate(chunk: Chunk) {
  return !!chunk.metadata?.duplicate_of
}
</script>

<style scoped>
.chunk-list {
  height: 100%;
  overflow-y: auto;
  padding: 12px;
}

.chunk-stats {
  display: flex;
  gap: 12px;
  font-size: 12px;
  color: #64748b;
  padding: 0 4px 8px;
  border-bottom: 1px solid #ebebeb;
  margin-bottom: 8px;
}

.chunk-jump {
  margin-bottom: 8px;
}
.chunk-jump-input {
  width: 100%;
  padding: 5px 10px;
  border: 1px solid #e5e5e5;
  border-radius: 4px;
  font-size: 12px;
  color: #333;
  outline: none;
  transition: border-color 0.15s;
}
.chunk-jump-input:focus {
  border-color: #c67d3a;
}

.chunk-card {
  border: 1px solid #e5e5e5;
  border-radius: 6px;
  padding: 10px 12px;
  margin-bottom: 8px;
  cursor: pointer;
  transition: all 0.15s;
  background: #fff;
}

.chunk-card:hover {
  border-color: #c67d3a;
  background: #f8f5f1;
}

.chunk-card.selected {
  border-color: #c67d3a;
  background: #fdf5ed;
}

.chunk-card.anomaly-short,
.chunk-card.anomaly-long {
  border-left: 3px solid #fa8c16;
}

.chunk-card.anomaly-duplicate {
  border-left: 3px solid #e6393d;
}

.chunk-card-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.chunk-index {
  font-size: 12px;
  font-weight: 600;
  color: #9a5b25;
}

.chunk-chars {
  font-size: 11px;
  color: #8a8e99;
}

.anomaly-icon {
  margin-left: auto;
  display: flex;
  align-items: center;
}

.chunk-section {
  font-size: 11px;
  color: #64748b;
  margin-bottom: 4px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.chunk-preview {
  font-size: 12px;
  color: #666;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  word-break: break-all;
}

.load-more-hint {
  text-align: center;
  padding: 12px 0;
  font-size: 12px;
  color: #bbb;
}
</style>
