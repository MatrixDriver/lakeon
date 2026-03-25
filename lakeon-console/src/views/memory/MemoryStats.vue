<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">用量统计</h1>
    </div>

    <MemoryBaseSelector @change="onBaseChange" />

    <div v-if="baseId" style="margin-top: 20px;">
      <p v-if="loading" style="text-align: center; color: #999; padding: 40px 0;">加载中...</p>

      <template v-else-if="stats">
        <!-- Summary cards -->
        <div style="display: flex; gap: 16px; margin-bottom: 32px;">
          <div class="card" style="flex: 1; padding: 20px; text-align: center;">
            <div style="font-size: 32px; font-weight: 600; color: #1890ff;">{{ stats.total }}</div>
            <div style="font-size: 13px; color: #999; margin-top: 4px;">总记忆数</div>
          </div>
          <div class="card" style="flex: 1; padding: 20px; text-align: center;">
            <div style="font-size: 32px; font-weight: 600; color: #722ed1;">{{ stats.trait_count }}</div>
            <div style="font-size: 13px; color: #999; margin-top: 4px;">Trait 数</div>
          </div>
        </div>

        <!-- Type distribution -->
        <div class="card" style="padding: 20px;">
          <h3 style="font-size: 15px; font-weight: 600; margin: 0 0 16px;">类型分布</h3>
          <div v-if="sortedTypes.length === 0" style="text-align: center; color: #999; padding: 20px;">暂无数据</div>
          <div v-else style="display: flex; flex-direction: column; gap: 10px;">
            <div v-for="item in sortedTypes" :key="item.type" style="display: flex; align-items: center; gap: 12px;">
              <span style="width: 56px; font-size: 12px; text-align: right;"
                    :style="`color: ${MEMORY_TYPE_COLORS[item.type]?.text || '#666'};`">
                {{ MEMORY_TYPE_LABELS[item.type] || item.type }}
              </span>
              <div style="flex: 1; height: 20px; background: #f5f5f5; border-radius: 4px; overflow: hidden;">
                <div style="height: 100%; border-radius: 4px; transition: width 0.5s;"
                     :style="`width: ${maxCount ? (item.count / maxCount * 100) : 0}%; background: ${MEMORY_TYPE_COLORS[item.type]?.text || '#999'};`" />
              </div>
              <span style="width: 32px; font-size: 13px; color: #333; font-weight: 500;">{{ item.count }}</span>
            </div>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import MemoryBaseSelector from '@/components/MemoryBaseSelector.vue'
import { getMemoryStats, type MemoryStats } from '@/api/memory'
import { MEMORY_TYPE_COLORS, MEMORY_TYPE_LABELS } from '@/constants/memory'

const baseId = ref('')
const stats = ref<MemoryStats | null>(null)
const loading = ref(false)

function onBaseChange(id: string) {
  baseId.value = id
  loadStats()
}

async function loadStats() {
  if (!baseId.value) return
  loading.value = true
  try {
    const { data } = await getMemoryStats(baseId.value)
    stats.value = data
  } catch (e) {
    console.error('Failed to load stats', e)
    stats.value = null
  } finally {
    loading.value = false
  }
}

const sortedTypes = computed(() => {
  if (!stats.value?.by_type) return []
  return Object.entries(stats.value.by_type)
    .map(([type, count]) => ({ type, count }))
    .sort((a, b) => b.count - a.count)
})

const maxCount = computed(() => {
  if (sortedTypes.value.length === 0) return 0
  return sortedTypes.value[0]!.count
})
</script>
