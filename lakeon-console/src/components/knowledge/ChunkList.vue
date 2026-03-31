<template>
  <div class="chunk-list">
    <!-- Stats summary -->
    <div v-if="stats" class="chunk-stats">
      <span>{{ stats.total_chunks }} 个切片</span>
      <span v-if="stats.anomaly_count > 0" style="color: #fa8c16;">{{ stats.anomaly_count }} 异常</span>
      <span v-if="stats.duplicate_count > 0" style="color: #e6393d;">{{ stats.duplicate_count }} 重复</span>
    </div>

    <!-- Chunk cards -->
    <div
      v-for="chunk in chunks"
      :key="chunk.id"
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
      <div v-if="chunk.overlap_prev > 0" class="chunk-overlap">overlap: {{ chunk.overlap_prev }}</div>
      <div class="chunk-preview">{{ chunk.content }}</div>
    </div>

    <div v-if="chunks.length === 0" class="empty-state" style="padding: 32px 12px;">
      <p style="color: #999;">暂无切片</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { Chunk, ChunkStats } from '../../api/knowledge'

defineProps<{
  chunks: Chunk[]
  selectedIndex: number
  stats: ChunkStats | null
}>()

defineEmits<{
  select: [chunkIndex: number]
}>()

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
  padding: 0 4px 12px;
  border-bottom: 1px solid #ebebeb;
  margin-bottom: 12px;
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

.chunk-overlap {
  font-size: 11px;
  color: #adb0b8;
  margin-bottom: 4px;
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
</style>
