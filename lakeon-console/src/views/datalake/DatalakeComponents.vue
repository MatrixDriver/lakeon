<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">组件库</h1>
      <div class="page-header-actions">
        <button class="btn btn-primary" @click="router.push('/datalake/components/register')">注册组件</button>
      </div>
    </div>

    <!-- 筛选 -->
    <div class="filter-row">
      <select v-model="categoryFilter" class="filter-select">
        <option value="">全部类别</option>
        <option v-for="cat in categories" :key="cat" :value="cat">{{ categoryLabel(cat) }}</option>
      </select>
      <select v-model="dataTypeFilter" class="filter-select">
        <option value="">全部数据类型</option>
        <option value="TEXT">文本</option>
        <option value="VIDEO">视频</option>
        <option value="IMAGE">图片</option>
        <option value="AUDIO">音频</option>
        <option value="DOCUMENT">文档</option>
        <option value="UNIVERSAL">通用</option>
      </select>
      <input v-model="search" class="filter-search" placeholder="搜索组件..." />
    </div>

    <!-- 组件网格 -->
    <div class="comp-grid" v-if="filtered.length > 0">
      <div
        v-for="comp in filtered"
        :key="comp.id"
        class="comp-card"
        :style="{ borderLeftColor: catColor(comp.category) }"
        @click="selectComponent(comp)"
      >
        <div class="comp-card-header">
          <span class="comp-card-icon">{{ catIcon(comp.category) }}</span>
          <span class="comp-card-name">{{ comp.displayName }}</span>
          <span v-if="!comp.tenantId" class="builtin-badge">内置</span>
        </div>
        <div class="comp-card-desc">{{ comp.description || comp.name }}</div>
        <div class="comp-card-meta">
          <span class="meta-tag">{{ comp.dataType }}</span>
          <span class="meta-tag">v{{ comp.latestVersion }}</span>
          <span class="meta-tag">{{ categoryLabel(comp.category) }}</span>
        </div>
      </div>
    </div>

    <div v-if="filtered.length === 0 && !loading" class="empty-state" style="margin-top: 48px; text-align: center; color: #999;">
      暂无匹配的组件
    </div>

    <!-- 组件详情弹窗 -->
    <div v-if="selected" class="dialog-overlay" @click.self="selected = null">
      <div class="dialog comp-detail-dialog">
        <h3>{{ selected.displayName }}</h3>
        <div class="comp-detail-row"><label>名称</label><span>{{ selected.name }}</span></div>
        <div class="comp-detail-row"><label>类别</label><span>{{ categoryLabel(selected.category) }}</span></div>
        <div class="comp-detail-row"><label>数据类型</label><span>{{ selected.dataType }}</span></div>
        <div class="comp-detail-row"><label>版本</label><span>v{{ selected.latestVersion }}</span></div>
        <div class="comp-detail-row"><label>来源</label><span>{{ selected.tenantId ? '自定义' : '平台内置' }}</span></div>
        <div class="comp-detail-row" v-if="selected.description">
          <label>描述</label><span>{{ selected.description }}</span>
        </div>
        <div class="dialog-actions">
          <button class="btn btn-secondary" @click="selected = null">关闭</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { listComponents, type PipelineComponent, type ComponentCategory } from '@/api/pipeline'
import { categoryColors, categoryLabels } from './components/pipeline/nodeStyles'

const router = useRouter()
const loading = ref(true)
const components = ref<PipelineComponent[]>([])
const categoryFilter = ref('')
const dataTypeFilter = ref('')
const search = ref('')
const selected = ref<PipelineComponent | null>(null)

const categories: ComponentCategory[] = ['DATA_PREP', 'EXTRACT', 'CLEAN', 'FILTER', 'QC', 'LABEL', 'PUBLISH']

function categoryLabel(cat: string): string { return categoryLabels[cat as ComponentCategory] || cat }
function catColor(cat: string): string { return categoryColors[cat as ComponentCategory]?.border || '#ccc' }
function catIcon(cat: string): string { return categoryColors[cat as ComponentCategory]?.icon || '?' }

const filtered = computed(() => {
  let list = components.value
  if (categoryFilter.value) list = list.filter(c => c.category === categoryFilter.value)
  if (dataTypeFilter.value) list = list.filter(c => c.dataType === dataTypeFilter.value)
  if (search.value.trim()) {
    const q = search.value.trim().toLowerCase()
    list = list.filter(c =>
      c.displayName.toLowerCase().includes(q) ||
      c.name.toLowerCase().includes(q) ||
      (c.description || '').toLowerCase().includes(q)
    )
  }
  return list
})

function selectComponent(comp: PipelineComponent) {
  selected.value = comp
}

onMounted(async () => {
  loading.value = true
  try {
    const res = await listComponents()
    components.value = res.data
  } catch (err) {
    console.error('Failed to load components', err)
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.filter-row { display: flex; gap: 8px; margin-bottom: 16px; }
.filter-select {
  padding: 5px 10px; border: 1px solid #e8e4df; border-radius: 4px;
  font-size: 12px; background: #fff; color: #666; cursor: pointer;
}
.filter-search {
  flex: 1; padding: 5px 10px; border: 1px solid #e8e4df; border-radius: 4px;
  font-size: 12px; outline: none;
}
.filter-search:focus { border-color: #2a4d6a; }

.comp-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }
.comp-card {
  border: 1px solid #e8e4df; border-left: 3px solid; border-radius: 8px;
  padding: 14px; cursor: pointer; background: #fff; transition: box-shadow 0.15s;
}
.comp-card:hover { box-shadow: 0 2px 8px rgba(0,0,0,0.05); }
.comp-card-header { display: flex; align-items: center; gap: 6px; }
.comp-card-icon { font-size: 16px; }
.comp-card-name { font-size: 13px; font-weight: 600; color: #2c3e50; flex: 1; }
.builtin-badge { font-size: 9px; padding: 1px 6px; border-radius: 3px; background: #eef6fe; color: #1a5276; }
.comp-card-desc { font-size: 12px; color: #999; margin-top: 4px; }
.comp-card-meta { display: flex; gap: 6px; margin-top: 8px; }
.meta-tag { font-size: 10px; padding: 1px 6px; border-radius: 3px; background: #f5f3f0; color: #666; }

/* 详情弹窗 */
.dialog-overlay {
  position: fixed; inset: 0; background: rgba(0,0,0,0.3); display: flex;
  align-items: center; justify-content: center; z-index: 100;
}
.comp-detail-dialog { width: 480px; }
.dialog { background: #fff; border-radius: 10px; padding: 24px; box-shadow: 0 8px 32px rgba(0,0,0,0.12); }
.dialog h3 { margin: 0 0 16px; font-size: 16px; color: #2c3e50; }
.comp-detail-row { display: flex; padding: 4px 0; font-size: 13px; }
.comp-detail-row label { width: 80px; color: #999; flex-shrink: 0; }
.comp-detail-row span { color: #2c3e50; }
.dialog-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }
</style>
