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
            <span class="create-icon tpl-icon">{{ dataTypeIcon(tpl.data_type) }}</span>
            <div>
              <div class="create-label">{{ tpl.name }}</div>
              <div class="create-desc">{{ tpl.description || tpl.data_type }}</div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Card view -->
    <div v-if="viewMode === 'card' && filteredPipelines.length > 0" class="card-grid">
      <div
        v-for="p in filteredPipelines"
        :key="p.id"
        class="pipeline-card"
        @click="router.push(`/datalake/pipelines/${p.id}`)"
      >
        <div class="pipeline-card-body">
          <div class="pipeline-card-name">{{ p.name }}</div>
          <div class="pipeline-card-meta">
            <span class="meta-tag">{{ p.data_type || '通用' }}</span>
            <span class="meta-tag">v{{ p.latest_version }}</span>
            <span class="meta-time">{{ formatTime(p.updated_at) }}</span>
          </div>
        </div>
        <div class="pipeline-card-actions" @click.stop>
          <button class="card-action-btn" title="编辑" @click="router.push(`/datalake/pipelines/${p.id}/edit`)">
            <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M11.5 1.5l3 3L5 14H2v-3z"/></svg>
          </button>
          <button class="card-action-btn run-btn" title="运行" @click="router.push(`/datalake/pipelines/${p.id}`)">
            <svg viewBox="0 0 16 16" width="14" height="14" fill="currentColor"><path d="M4 2l10 6-10 6z"/></svg>
          </button>
          <button class="card-action-btn del-btn" title="删除" @click="handleDelete(p)">
            <svg viewBox="0 0 16 16" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M2 4h12M5 4V3h6v1M6 7v5M10 7v5M3 4l1 10h8l1-10"/></svg>
          </button>
        </div>
      </div>
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
            <td><span class="type-tag">{{ p.data_type || '通用' }}</span></td>
            <td>v{{ p.latest_version }}</td>
            <td>
              <span class="status-dot" :class="'dot-' + statusDotClass(latestRunStatus(p.id))"></span>
              {{ latestRunStatusLabel(p.id) || '—' }}
            </td>
            <td style="color: #999;">{{ formatTime(p.updated_at) }}</td>
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
        尚未创建数据生产线，点击右上角「新建生产线」开始
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import ViewToggle from '@/components/ViewToggle.vue'
import { listPipelines, listTemplates, deletePipeline, type Pipeline } from '@/api/pipeline'

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
      listTemplates(),
    ])
    pipelines.value = allRes.data
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
  router.push({ path: '/datalake/pipelines/new', query: { template: tpl.id, dataType: tpl.data_type || '' } })
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

/* Pipeline 卡片 */
.pipeline-card {
  display: flex; align-items: center; justify-content: space-between;
  background: #fff; border: 1px solid #e8e4df; border-radius: 8px;
  padding: 14px 18px; cursor: pointer; transition: all 0.15s;
}
.pipeline-card:hover { border-color: #c5bfb5; box-shadow: 0 2px 8px rgba(0,0,0,0.05); }
.pipeline-card-name { font-size: 14px; font-weight: 500; color: #2c3e50; }
.pipeline-card-meta { display: flex; align-items: center; gap: 8px; margin-top: 6px; }
.meta-tag { font-size: 11px; padding: 1px 7px; border-radius: 3px; background: #f5f3f0; color: #777; }
.meta-time { font-size: 11px; color: #bbb; }

.pipeline-card-actions { display: flex; gap: 4px; opacity: 0; transition: opacity 0.15s; }
.pipeline-card:hover .pipeline-card-actions { opacity: 1; }
.card-action-btn {
  width: 32px; height: 32px; border: 1px solid #e8e4df; border-radius: 6px;
  background: #fff; cursor: pointer; display: flex; align-items: center;
  justify-content: center; color: #777; transition: all 0.12s;
}
.card-action-btn:hover { background: #f5f3f0; color: #2a4d6a; border-color: #c5bfb5; }
.run-btn:hover { color: #16a34a; border-color: #86efac; background: #f0fdf4; }
.del-btn:hover { color: #dc2626; border-color: #fca5a5; background: #fef2f2; }
</style>
