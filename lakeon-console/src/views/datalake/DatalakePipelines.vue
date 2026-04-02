<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">数据生产线</h1>
      <div class="page-header-actions">
        <ViewToggle v-model="viewMode" />
        <button class="btn btn-primary" @click="showCreateMenu = !showCreateMenu">
          新建生产线
        </button>
        <!-- 创建下拉菜单 -->
        <div v-if="showCreateMenu" class="create-menu" @mouseleave="showCreateMenu = false">
          <div class="create-menu-section">选择数据类型</div>
          <div class="create-menu-item" @click="createNew('VIDEO')">
            <span class="create-icon">🎬</span>
            <div>
              <div class="create-label">视频数据生产线</div>
              <div class="create-desc">视频切片、清洗、标注等</div>
            </div>
          </div>
          <div class="create-menu-item" @click="createNew('TEXT')">
            <span class="create-icon">📄</span>
            <div>
              <div class="create-label">文本数据生产线</div>
              <div class="create-desc">去重、清洗、分词、质量评分等</div>
            </div>
          </div>
          <div v-if="templates.length > 0" class="create-menu-divider"></div>
          <div v-if="templates.length > 0" class="create-menu-section">从模板创建</div>
          <div
            v-for="tpl in templates"
            :key="tpl.id"
            class="create-menu-item"
            @click="createFromTemplate(tpl)"
          >
            <span class="create-icon tpl-icon">{{ dataTypeIcon(tpl.dataType) }}</span>
            <div>
              <div class="create-label">{{ tpl.name }}</div>
              <div class="create-desc">{{ tpl.description || tpl.dataType }}</div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Card view -->
    <div v-if="viewMode === 'card' && filteredPipelines.length > 0" class="card-grid">
      <ResourceCard
        v-for="p in filteredPipelines"
        :key="p.id"
        :name="p.name"
        :status="latestRunStatus(p.id)"
        :statusLabel="latestRunStatusLabel(p.id)"
        :meta="[p.dataType || '通用', `v${p.latestVersion}`, formatTime(p.updatedAt)]"
        @click="router.push(`/datalake/pipelines/${p.id}`)"
      />
    </div>

    <!-- Table view -->
    <div v-if="viewMode === 'table' && filteredPipelines.length > 0" class="table-wrapper">
      <table class="data-table">
        <thead>
          <tr>
            <th>名称</th>
            <th>数据类型</th>
            <th>最新版本</th>
            <th>最近运行</th>
            <th>更新时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in filteredPipelines" :key="p.id">
            <td>
              <router-link :to="`/datalake/pipelines/${p.id}`" class="name-link">{{ p.name }}</router-link>
              <div class="id-hint">{{ p.id }}</div>
            </td>
            <td><span class="type-tag">{{ p.dataType || '通用' }}</span></td>
            <td>v{{ p.latestVersion }}</td>
            <td>
              <span class="status-dot" :class="'dot-' + statusDotClass(latestRunStatus(p.id))"></span>
              {{ latestRunStatusLabel(p.id) || '—' }}
            </td>
            <td style="color: #999;">{{ formatTime(p.updatedAt) }}</td>
            <td>
              <router-link :to="`/datalake/pipelines/${p.id}/edit`" class="btn btn-text btn-small btn-accent-text">编辑</router-link>
              <button class="btn btn-text btn-small btn-danger-text" @click="handleDelete(p)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Empty state -->
    <div v-if="filteredPipelines.length === 0 && !loading" class="empty-state" style="margin-top: 64px; text-align: center;">
      <svg viewBox="0 0 24 24" width="48" height="48" fill="none" stroke="#ccc" stroke-width="1.5">
        <path d="M4 5a1 1 0 0 1 1-1h4l2 2h8a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5z"/>
        <line x1="12" y1="10" x2="12" y2="16"/><line x1="9" y1="13" x2="15" y2="13"/>
      </svg>
      <div style="margin-top: 12px; color: #999;">
        尚未创建数据生产线
      </div>
      <button class="btn btn-primary" style="margin-top: 16px;" @click="showCreateMenu = true">
        创建第一条生产线
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import ViewToggle from '@/components/ViewToggle.vue'
import ResourceCard from '@/components/ResourceCard.vue'
import { listPipelines, deletePipeline, type Pipeline } from '@/api/pipeline'

const router = useRouter()
const loading = ref(true)
const pipelines = ref<Pipeline[]>([])
const templates = ref<Pipeline[]>([])
const viewMode = ref<'card' | 'table'>('card')
const showCreateMenu = ref(false)

// latestRunStatus 需要缓存每个 pipeline 的最近运行状态
// 实际实现需要额外 API，这里预留
const runStatusMap = ref<Record<string, string>>({})

const filteredPipelines = computed(() => {
  return pipelines.value
})

function latestRunStatus(pipelineId: string): string {
  return runStatusMap.value[pipelineId] || ''
}

function latestRunStatusLabel(pipelineId: string): string {
  const s = latestRunStatus(pipelineId)
  const labels: Record<string, string> = {
    PENDING: '等待中', RUNNING: '运行中', PAUSED: '已暂停',
    SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消',
  }
  return labels[s] || ''
}

function statusDotClass(status: string): string {
  if (['RUNNING'].includes(status)) return 'blue'
  if (['SUCCEEDED'].includes(status)) return 'green'
  if (['FAILED'].includes(status)) return 'red'
  if (['PAUSED'].includes(status)) return 'yellow'
  return 'gray'
}

function dataTypeIcon(dt: string | null): string {
  const icons: Record<string, string> = { VIDEO: '🎬', TEXT: '📄', IMAGE: '🖼', AUDIO: '🔊', DOCUMENT: '📑' }
  return icons[dt || ''] || '📊'
}

function formatTime(iso: string): string {
  if (!iso) return ''
  const d = new Date(iso)
  return d.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

async function loadData() {
  loading.value = true
  try {
    const [allRes, tplRes] = await Promise.all([
      listPipelines(),
      listPipelines({ is_template: true }),
    ])
    pipelines.value = allRes.data.filter(p => !p.isTemplate)
    templates.value = tplRes.data
  } catch (err) {
    console.error('Failed to load pipelines', err)
  } finally {
    loading.value = false
  }
}

function createNew(dt: string) {
  showCreateMenu.value = false
  router.push({ path: '/datalake/pipelines/new', query: { dataType: dt } })
}

function createFromTemplate(tpl: Pipeline) {
  showCreateMenu.value = false
  router.push({ path: '/datalake/pipelines/new', query: { template: tpl.id, dataType: tpl.dataType || '' } })
}

async function handleDelete(p: Pipeline) {
  if (!confirm(`确认删除生产线「${p.name}」？`)) return
  try {
    await deletePipeline(p.id)
    pipelines.value = pipelines.value.filter(x => x.id !== p.id)
  } catch (err) {
    console.error('Failed to delete pipeline', err)
  }
}

onMounted(loadData)
</script>

<style scoped>
/* 复用项目全局 .page-container / .page-header / .data-table 等样式 */
/* 仅补充此页面特有的样式 */

.page-header-actions { position: relative; }
.create-menu {
  position: absolute; right: 0; top: 100%; margin-top: 4px;
  background: #fff; border: 1px solid #e8e4df; border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0,0,0,0.12); min-width: 260px; z-index: 100;
  padding: 6px 0;
}
.create-menu-item {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 14px; cursor: pointer; transition: background 0.12s;
}
.create-menu-item:hover { background: #f8f5f1; }
.create-icon { font-size: 18px; width: 28px; text-align: center; }
.create-label { font-size: 13px; font-weight: 500; color: #2c3e50; }
.create-desc { font-size: 11px; color: #999; margin-top: 1px; }
.create-menu-divider { height: 1px; background: #e8e4df; margin: 4px 0; }
.create-menu-section { font-size: 11px; color: #94a3b8; padding: 6px 14px 2px; }
.create-menu-empty { font-size: 12px; color: #ccc; padding: 8px 14px; }

.name-link { color: #2a4d6a; text-decoration: none; font-weight: 500; }
.name-link:hover { text-decoration: underline; }
.id-hint { font-size: 11px; color: #bbb; margin-top: 2px; }
.type-tag { font-size: 11px; padding: 2px 8px; border-radius: 3px; background: #f5f3f0; color: #666; }
</style>
