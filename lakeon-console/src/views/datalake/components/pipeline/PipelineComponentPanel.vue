<template>
  <div class="component-panel">
    <div class="panel-header">
      <span class="panel-title">组件库</span>
    </div>
    <input
      v-model="search"
      class="panel-search"
      placeholder="搜索组件..."
    />
    <div class="panel-groups">
      <div v-for="group in filteredGroups" :key="group.category" class="comp-group">
        <div
          class="group-header"
          @click="toggleGroup(group.category)"
        >
          <svg class="group-icon-svg" viewBox="0 0 24 24" width="13" height="13" fill="none" :stroke="groupColor(group.category)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path :d="groupIconPath(group.category)" /></svg>
          <span class="group-label">{{ groupLabel(group.category) }}</span>
          <span class="group-count">{{ group.items.length }}</span>
          <span class="group-chevron" :class="{ expanded: expandedGroups.has(group.category) }">&#9654;</span>
        </div>
        <div v-if="expandedGroups.has(group.category)" class="group-items">
          <div
            v-for="comp in group.items"
            :key="comp.id"
            class="comp-item"
            draggable="true"
            @dragstart="onDragStart($event, comp)"
          >
            <div class="comp-name">{{ comp.display_name }}</div>
            <div class="comp-desc">{{ comp.description || comp.name }}</div>
            <div v-if="!comp.tenant_id" class="comp-badge">内置</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import type { PipelineComponent, ComponentCategory } from '@/api/pipeline'
import { categoryColors, categoryLabels, categoryIcons } from './nodeStyles'

const props = defineProps<{
  components: PipelineComponent[]
}>()

const emit = defineEmits<{
  dragStart: [component: PipelineComponent]
}>()

const search = ref('')
const expandedGroups = ref(new Set<string>(['DATA_PREP', 'EXTRACT', 'CLEAN', 'FILTER', 'QC', 'LABEL', 'PUBLISH']))

interface CompGroup {
  category: string
  items: PipelineComponent[]
}

const allGroups = computed<CompGroup[]>(() => {
  const map = new Map<string, PipelineComponent[]>()
  const order: ComponentCategory[] = ['DATA_PREP', 'EXTRACT', 'CLEAN', 'FILTER', 'QC', 'LABEL', 'PUBLISH']
  for (const cat of order) map.set(cat, [])
  for (const comp of props.components) {
    const list = map.get(comp.category) || []
    list.push(comp)
    map.set(comp.category, list)
  }
  return order.filter(cat => (map.get(cat) || []).length > 0).map(cat => ({
    category: cat,
    items: map.get(cat)!,
  }))
})

const filteredGroups = computed<CompGroup[]>(() => {
  if (!search.value.trim()) return allGroups.value
  const q = search.value.trim().toLowerCase()
  return allGroups.value
    .map(g => ({
      ...g,
      items: g.items.filter(c =>
        c.display_name.toLowerCase().includes(q) ||
        c.name.toLowerCase().includes(q) ||
        (c.description || '').toLowerCase().includes(q)
      ),
    }))
    .filter(g => g.items.length > 0)
})

function groupColor(cat: string): string {
  return categoryColors[cat as ComponentCategory]?.border || '#ccc'
}
function groupIconPath(cat: string): string {
  return categoryIcons[cat as ComponentCategory] || categoryIcons.DATA_PREP
}
function groupLabel(cat: string): string {
  return categoryLabels[cat as ComponentCategory] || cat
}

function toggleGroup(cat: string) {
  if (expandedGroups.value.has(cat)) expandedGroups.value.delete(cat)
  else expandedGroups.value.add(cat)
}

function onDragStart(event: DragEvent, comp: PipelineComponent) {
  event.dataTransfer?.setData('application/pipeline-component', comp.id)
  emit('dragStart', comp)
}
</script>

<style scoped>
.component-panel {
  width: 220px; border-right: 1px solid #e8e4df; background: #fff;
  display: flex; flex-direction: column; overflow: hidden;
}
.panel-header { padding: 12px 14px 8px; }
.panel-title { font-size: 13px; font-weight: 600; color: #2c3e50; }
.panel-search {
  margin: 0 10px 8px; padding: 5px 8px; border: 1px solid #e8e4df;
  border-radius: 4px; font-size: 12px; outline: none;
}
.panel-search:focus { border-color: #2a4d6a; }
.panel-groups { flex: 1; overflow-y: auto; padding-bottom: 12px; }

.group-header {
  display: flex; align-items: center; gap: 6px; padding: 6px 12px;
  cursor: pointer; font-size: 12px;
  transition: background 0.12s;
}
.group-header:hover { background: #f8f5f1; }
.group-icon { font-size: 13px; }
.group-label { font-weight: 500; color: #2c3e50; flex: 1; }
.group-count { font-size: 10px; color: #999; }
.group-chevron {
  font-size: 8px; color: #999; transition: transform 0.15s; display: inline-block;
}
.group-chevron.expanded { transform: rotate(90deg); }

.group-items { padding: 2px 0; }
.comp-item {
  padding: 6px 12px 6px 24px; cursor: grab; transition: background 0.12s;
  position: relative;
}
.comp-item:hover { background: #f5f3f0; }
.comp-item:active { cursor: grabbing; }
.comp-name { font-size: 12px; font-weight: 500; color: #2c3e50; }
.comp-desc { font-size: 10px; color: #999; margin-top: 1px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.comp-badge {
  position: absolute; right: 10px; top: 8px;
  font-size: 9px; padding: 1px 4px; border-radius: 2px;
  background: #eef6fe; color: #1a5276;
}
</style>
