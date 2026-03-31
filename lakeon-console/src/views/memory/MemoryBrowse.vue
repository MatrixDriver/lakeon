<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">记忆浏览</h1>
    </div>

    <MemoryBaseSelector @change="onBaseChange" />

    <div v-if="baseId" style="margin-top: 20px;">
      <!-- Traits section -->
      <div v-if="traits.length > 0" class="traits-section">
        <div class="traits-header" @click="traitsExpanded = !traitsExpanded">
          <span class="traits-title">反思洞察</span>
          <span class="traits-count">{{ traits.length }}</span>
          <span class="traits-toggle">{{ traitsExpanded ? '收起' : '展开' }}</span>
        </div>
        <div v-if="traitsExpanded" class="traits-grid">
          <TraitCard v-for="t in traits" :key="t.id" :trait="t" />
        </div>
      </div>

      <!-- Type filters -->
      <div style="display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 16px;">
        <button
          v-for="t in ['all', ...MEMORY_TYPES]" :key="t"
          @click="typeFilter = t === 'all' ? '' : t; currentPage = 1; load()"
          class="btn btn-sm"
          :style="typeFilter === (t === 'all' ? '' : t)
            ? `background: ${t === 'all' ? '#1890ff' : MEMORY_TYPE_COLORS[t]?.text}; color: #fff;`
            : `background: ${t === 'all' ? '#f5f5f5' : MEMORY_TYPE_COLORS[t]?.bg}; color: ${t === 'all' ? '#333' : MEMORY_TYPE_COLORS[t]?.text};`"
        >
          {{ t === 'all' ? '全部' : MEMORY_TYPE_LABELS[t] || t }}
        </button>
      </div>

      <!-- Search -->
      <div style="display: flex; gap: 8px; margin-bottom: 16px;">
        <input v-model="searchQuery" class="form-input" placeholder="语义搜索记忆..." style="flex: 1;"
               @keyup.enter="currentPage = 1; load()" />
        <button class="btn btn-primary" @click="currentPage = 1; load()">搜索</button>
        <button v-if="searchQuery" class="btn" @click="searchQuery = ''; currentPage = 1; load()">清除</button>
      </div>

      <!-- Loading -->
      <p v-if="loading" style="text-align: center; color: #999; padding: 40px 0;">加载中...</p>

      <!-- Empty -->
      <p v-else-if="memories.length === 0" style="text-align: center; color: #999; padding: 40px 0;">
        {{ searchQuery ? '未找到匹配的记忆' : '暂无记忆' }}
      </p>

      <!-- Memory cards -->
      <div v-else style="display: flex; flex-direction: column; gap: 12px;">
        <div v-for="m in memories" :key="m.id"
             class="card" style="padding: 16px; cursor: pointer;"
             @click="expandedId = expandedId === m.id ? null : m.id">
          <div style="display: flex; align-items: flex-start; justify-content: space-between;">
            <div style="flex: 1; min-width: 0;">
              <!-- Type badge -->
              <span style="display: inline-block; padding: 2px 8px; border-radius: 4px; font-size: 12px; margin-bottom: 8px;"
                    :style="`background: ${MEMORY_TYPE_COLORS[m.memory_type]?.bg}; color: ${MEMORY_TYPE_COLORS[m.memory_type]?.text};`">
                {{ MEMORY_TYPE_LABELS[m.memory_type] || m.memory_type }}
              </span>

              <!-- Content -->
              <p style="margin: 0; font-size: 14px; line-height: 1.6;"
                 :style="expandedId !== m.id ? 'display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden;' : ''">
                {{ m.content }}
              </p>

              <!-- Metadata (expanded) -->
              <div v-if="expandedId === m.id && m.metadata && Object.keys(m.metadata).length > 0"
                   style="margin-top: 8px; padding: 8px 12px; background: #fafafa; border-radius: 4px; font-size: 12px; color: #666;">
                <div v-for="(v, k) in m.metadata" :key="k" style="margin-bottom: 2px;">
                  <strong>{{ k }}:</strong> {{ v }}
                </div>
              </div>

              <!-- Footer -->
              <div style="display: flex; gap: 16px; flex-wrap: wrap; margin-top: 8px; font-size: 12px; color: #999;">
                <span v-if="m.metadata?.source" style="background: #f0f5ff; color: #1890ff; padding: 0 6px; border-radius: 3px;">{{ m.metadata.source }}</span>
                <span>创建: {{ new Date(m.created_at).toLocaleString('zh-CN') }}</span>
                <span v-if="m.last_accessed_at">最近访问: {{ new Date(m.last_accessed_at).toLocaleString('zh-CN') }}</span>
                <span>访问 {{ m.access_count }} 次</span>
                <span>重要性 {{ m.importance <= 1 ? Math.round(m.importance * 100) : Math.round(m.importance * 10) }}%</span>
              </div>
            </div>

            <!-- Delete -->
            <button class="btn btn-sm" style="color: #cf1322; margin-left: 12px; flex-shrink: 0;"
                    @click.stop="handleDelete(m.id)">删除</button>
          </div>
        </div>
      </div>

      <!-- Pagination (list mode only) -->
      <div v-if="!searchQuery && total > PAGE_SIZE" style="display: flex; justify-content: center; gap: 8px; margin-top: 20px;">
        <button class="btn btn-sm" :disabled="currentPage <= 1" @click="currentPage--; load()">上一页</button>
        <span style="line-height: 32px; font-size: 13px; color: #666;">
          {{ (currentPage - 1) * PAGE_SIZE + 1 }}-{{ Math.min(currentPage * PAGE_SIZE, total) }} / {{ total }}
        </span>
        <button class="btn btn-sm" :disabled="currentPage * PAGE_SIZE >= total" @click="currentPage++; load()">下一页</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import MemoryBaseSelector from '@/components/MemoryBaseSelector.vue'
import TraitCard from '@/components/memory/TraitCard.vue'
import { listMemories, recallMemories, deleteMemory, listTraits, type MemoryItem, type Trait } from '@/api/memory'
import { MEMORY_TYPES, MEMORY_TYPE_COLORS, MEMORY_TYPE_LABELS } from '@/constants/memory'

const PAGE_SIZE = 20

const baseId = ref('')
const traits = ref<Trait[]>([])
const traitsExpanded = ref(true)
const typeFilter = ref('')
const searchQuery = ref('')
const memories = ref<MemoryItem[]>([])
const total = ref(0)
const currentPage = ref(1)
const loading = ref(false)
const expandedId = ref<number | null>(null)

function onBaseChange(id: string) {
  baseId.value = id
  currentPage.value = 1
  load()
  loadTraits()
}

async function loadTraits() {
  if (!baseId.value) return
  try {
    const { data } = await listTraits(baseId.value)
    traits.value = data
  } catch {
    traits.value = []
  }
}

async function load() {
  if (!baseId.value) return
  loading.value = true
  try {
    if (searchQuery.value.trim()) {
      const types = typeFilter.value ? [typeFilter.value] : undefined
      const { data } = await recallMemories(baseId.value, searchQuery.value, PAGE_SIZE, types)
      memories.value = data.memories
      total.value = data.memories.length
    } else {
      const { data } = await listMemories(baseId.value, {
        memory_type: typeFilter.value || undefined,
        offset: (currentPage.value - 1) * PAGE_SIZE,
        limit: PAGE_SIZE,
      })
      memories.value = data.memories
      total.value = data.total
    }
  } catch (e) {
    console.error('Failed to load memories', e)
    memories.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function handleDelete(memoryId: number) {
  if (!window.confirm('确定删除该记忆？')) return
  try {
    await deleteMemory(baseId.value, memoryId)
    memories.value = memories.value.filter(m => m.id !== memoryId)
    total.value--
  } catch (e) {
    console.error('Delete failed', e)
  }
}
</script>

<style scoped>
.traits-section {
  margin-bottom: 20px;
  border: 1px solid #e8e4df;
  border-left: 3px solid #c67d3a;
  border-radius: 6px;
  overflow: hidden;
}
.traits-header {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 14px; cursor: pointer; user-select: none;
}
.traits-title { font-size: 13px; font-weight: 600; color: #2c3e50; }
.traits-count { font-size: 11px; color: #94a3b8; }
.traits-toggle { margin-left: auto; font-size: 12px; color: #9a5b25; }
.traits-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 8px; padding: 0 14px 14px;
}
</style>
