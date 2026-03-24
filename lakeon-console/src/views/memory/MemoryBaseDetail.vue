<template>
  <div class="page-container">
    <!-- Breadcrumb -->
    <div class="breadcrumb" style="margin-bottom: 16px;">
      <router-link to="/memory" style="color: #0073e6; text-decoration: none;">记忆库</router-link>
      <span class="breadcrumb-sep">/</span>
      <span>{{ base?.name || '...' }}</span>
    </div>

    <!-- Page header -->
    <div class="page-header">
      <h1 class="page-title">
        {{ base?.name || '加载中...' }}
        <span v-if="base" class="status-tag" :class="base.status === 'READY' ? 'tag-green' : base.status === 'FAILED' ? 'tag-red' : 'tag-gray'" style="margin-left: 10px; font-size: 13px; vertical-align: middle;">
          {{ statusText(base.status) }}
        </span>
      </h1>
    </div>

    <!-- Tab bar -->
    <div v-if="base" class="tab-bar" style="margin-top: 20px;">
      <button class="tab-item" :class="{ active: activeTab === 'overview' }" @click="activeTab = 'overview'">概览</button>
      <button v-if="base.type === 'BUILTIN'" class="tab-item" :class="{ active: activeTab === 'memories' }" @click="activeTab = 'memories'">记忆</button>
      <button v-if="base.type === 'BUILTIN'" class="tab-item" :class="{ active: activeTab === 'traits' }" @click="activeTab = 'traits'">特征</button>
      <button v-if="base.type === 'BUILTIN'" class="tab-item" :class="{ active: activeTab === 'graph' }" @click="activeTab = 'graph'">图谱</button>
      <button class="tab-item" :class="{ active: activeTab === 'settings' }" @click="activeTab = 'settings'">接入</button>
    </div>

    <!-- Overview tab -->
    <div v-if="base && activeTab === 'overview'" style="margin-top: 24px;">
      <div class="section-card">
        <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap: 24px; padding: 24px;">
          <div>
            <div style="font-size: 28px; font-weight: 700;">{{ stats?.total || 0 }}</div>
            <div style="color: #999; margin-top: 4px;">记忆总数</div>
          </div>
          <div v-for="(count, type) in (stats?.by_type || {})" :key="type">
            <div style="font-size: 28px; font-weight: 700;">{{ count }}</div>
            <div style="color: #999; margin-top: 4px;">{{ type }}</div>
          </div>
          <div>
            <div style="font-size: 28px; font-weight: 700;">{{ stats?.trait_count || 0 }}</div>
            <div style="color: #999; margin-top: 4px;">特征</div>
          </div>
          <div>
            <div style="font-size: 28px; font-weight: 700;">{{ typeLabel }}</div>
            <div style="color: #999; margin-top: 4px;">类型</div>
          </div>
        </div>
      </div>
      <div v-if="base.description" style="margin-top: 16px; color: #666; font-size: 14px;">{{ base.description }}</div>
      <div v-if="base.embedding_model" style="margin-top: 8px; color: #999; font-size: 13px;">嵌入模型：{{ base.embedding_model }}</div>
      <div v-if="base.error" style="margin-top: 12px; padding: 12px; background: #fff2f0; border: 1px solid #ffccc7; border-radius: 6px; color: #e6393d; font-size: 13px;">
        错误：{{ base.error }}
      </div>
    </div>

    <!-- Memories tab -->
    <div v-if="base && activeTab === 'memories'" style="margin-top: 24px;">
      <div v-if="activeTab === 'memories'">
        <!-- Filter + Search bar -->
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;">
          <div style="display: flex; gap: 8px;">
            <button class="btn" :class="memoryTypeFilter === '' ? 'btn-primary' : 'btn-default'" @click="setTypeFilter('')" style="height:28px;font-size:12px;padding:0 12px;">全部</button>
            <button class="btn" :class="memoryTypeFilter === 'fact' ? 'btn-primary' : 'btn-default'" @click="setTypeFilter('fact')" style="height:28px;font-size:12px;padding:0 12px;">fact</button>
            <button class="btn" :class="memoryTypeFilter === 'episode' ? 'btn-primary' : 'btn-default'" @click="setTypeFilter('episode')" style="height:28px;font-size:12px;padding:0 12px;">episode</button>
            <button class="btn" :class="memoryTypeFilter === 'procedural' ? 'btn-primary' : 'btn-default'" @click="setTypeFilter('procedural')" style="height:28px;font-size:12px;padding:0 12px;">procedural</button>
          </div>
          <div style="display: flex; gap: 8px; align-items: center;">
            <input v-model="memorySearch" class="form-input" placeholder="语义搜索..." style="width: 240px; height: 32px;" @keyup.enter="onSearchEnter" />
          </div>
        </div>

        <!-- Table -->
        <div v-if="memories.length > 0" class="table-wrapper">
          <table class="data-table">
            <thead>
              <tr>
                <th style="width:45%">内容</th>
                <th>类型</th>
                <th>重要度</th>
                <th>访问</th>
                <th>创建时间</th>
                <th style="width:60px">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="mem in memories" :key="mem.id" @click="showDetail(mem)" style="cursor:pointer;">
                <td>{{ mem.content.length > 100 ? mem.content.slice(0, 100) + '...' : mem.content }}</td>
                <td>
                  <span style="padding:2px 8px;border-radius:4px;font-size:12px;"
                    :style="mem.memory_type === 'fact' ? 'background:#e6f7ff;color:#1890ff' : mem.memory_type === 'episode' ? 'background:#f9f0ff;color:#722ed1' : 'background:#fff7e6;color:#d48806'">
                    {{ mem.memory_type }}
                  </span>
                </td>
                <td>{{ (mem.importance * 100).toFixed(0) }}%</td>
                <td>{{ mem.access_count }}</td>
                <td>{{ new Date(mem.created_at).toLocaleDateString() }}</td>
                <td @click.stop>
                  <button class="btn btn-default" style="height:24px;font-size:11px;padding:0 8px;color:#e6393d;" @click="confirmDelete(mem.id)">删除</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- Empty state -->
        <div v-else-if="!memoryLoading" class="empty-state" style="padding: 60px 0;">
          <svg viewBox="0 0 24 24" width="48" height="48" fill="none" stroke="#ccc" stroke-width="1.5">
            <path d="M12 2a7 7 0 0 1 7 7c0 2.38-1.19 4.47-3 5.74V17a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2v-2.26C6.19 13.47 5 11.38 5 9a7 7 0 0 1 7-7z"/>
            <line x1="10" y1="21" x2="14" y2="21"/>
          </svg>
          <p style="color: #999; margin-top: 12px;">暂无记忆数据</p>
        </div>

        <!-- Pagination -->
        <div v-if="memoryTotal > PAGE_SIZE" style="display:flex;justify-content:flex-end;align-items:center;gap:12px;margin-top:16px;">
          <button class="btn btn-default" :disabled="memoryPage === 0" @click="prevPage" style="height:28px;font-size:12px;">上一页</button>
          <span style="font-size:13px;color:#666;">第 {{ memoryPage + 1 }} / {{ Math.ceil(memoryTotal / PAGE_SIZE) }} 页</span>
          <button class="btn btn-default" :disabled="(memoryPage + 1) * PAGE_SIZE >= memoryTotal" @click="nextPage" style="height:28px;font-size:12px;">下一页</button>
        </div>

        <!-- Memory detail dialog -->
        <div v-if="showMemoryDetail" class="dialog-overlay" @click.self="showMemoryDetail = false">
          <div class="dialog-box" style="max-width: 600px;">
            <div class="dialog-header">
              <h3>记忆详情</h3>
              <button class="dialog-close" @click="showMemoryDetail = false">&times;</button>
            </div>
            <div class="dialog-body" style="max-height: 400px; overflow-y: auto;">
              <div style="margin-bottom: 12px;">
                <span style="padding:2px 8px;border-radius:4px;font-size:12px;"
                  :style="selectedMemory?.memory_type === 'fact' ? 'background:#e6f7ff;color:#1890ff' : selectedMemory?.memory_type === 'episode' ? 'background:#f9f0ff;color:#722ed1' : 'background:#fff7e6;color:#d48806'">
                  {{ selectedMemory?.memory_type }}
                </span>
                <span style="margin-left:12px;font-size:12px;color:#999;">重要度: {{ ((selectedMemory?.importance || 0) * 100).toFixed(0) }}%</span>
                <span style="margin-left:12px;font-size:12px;color:#999;">访问: {{ selectedMemory?.access_count }}</span>
              </div>
              <p style="font-size:14px;line-height:1.8;white-space:pre-wrap;">{{ selectedMemory?.content }}</p>
              <div v-if="selectedMemory?.event_time" style="margin-top:12px;font-size:12px;color:#999;">
                事件时间: {{ new Date(selectedMemory.event_time).toLocaleString() }}
              </div>
              <div style="margin-top:8px;font-size:12px;color:#999;">
                创建时间: {{ selectedMemory?.created_at ? new Date(selectedMemory.created_at).toLocaleString() : '' }}
              </div>
            </div>
          </div>
        </div>

        <!-- Delete confirm dialog -->
        <div v-if="showDeleteConfirm" class="dialog-overlay" @click.self="showDeleteConfirm = false">
          <div class="dialog-box" style="max-width: 400px;">
            <div class="dialog-header">
              <h3>确认删除</h3>
              <button class="dialog-close" @click="showDeleteConfirm = false">&times;</button>
            </div>
            <div class="dialog-body">
              <p>确定要删除这条记忆吗？此操作不可撤销。</p>
            </div>
            <div class="dialog-footer">
              <button class="btn btn-default" @click="showDeleteConfirm = false">取消</button>
              <button class="btn" style="background:#e6393d;color:#fff;" @click="doDelete">删除</button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Traits tab -->
    <div v-if="base && activeTab === 'traits'" style="margin-top: 24px;">
      <div v-if="activeTab === 'traits'">
        <!-- Main stages: core, established, emerging -->
        <template v-for="stage in ['core', 'established', 'emerging']" :key="stage">
          <div v-if="traitsByStage[stage]?.length" style="margin-bottom: 24px;">
            <div style="display:flex;align-items:center;gap:8px;margin-bottom:12px;">
              <h3 style="margin:0;font-size:15px;">{{ {core:'核心特征',established:'稳定特征',emerging:'新兴特征'}[stage] }}</h3>
              <span style="background:#f0f0f0;padding:1px 8px;border-radius:10px;font-size:12px;color:#666;">{{ traitsByStage[stage].length }}</span>
            </div>
            <TraitCard v-for="t in traitsByStage[stage]" :key="t.id" :trait="t" />
          </div>
        </template>

        <!-- Early stages (collapsible) -->
        <div v-if="earlyStageCount > 0" style="margin-top:16px;">
          <button class="btn btn-default" style="font-size:12px;height:28px;" @click="showEarlyStages = !showEarlyStages">
            {{ showEarlyStages ? '收起' : '展开' }}早期特征 ({{ earlyStageCount }})
          </button>
          <div v-if="showEarlyStages" style="margin-top:12px;">
            <template v-for="stage in ['candidate', 'trend']" :key="stage">
              <div v-if="traitsByStage[stage]?.length" style="margin-bottom: 24px;">
                <div style="display:flex;align-items:center;gap:8px;margin-bottom:12px;">
                  <h3 style="margin:0;font-size:15px;color:#999;">{{ {candidate:'候选特征',trend:'趋势特征'}[stage] }}</h3>
                  <span style="background:#f0f0f0;padding:1px 8px;border-radius:10px;font-size:12px;color:#666;">{{ traitsByStage[stage].length }}</span>
                </div>
                <TraitCard v-for="t in traitsByStage[stage]" :key="t.id" :trait="t" />
              </div>
            </template>
          </div>
        </div>

        <!-- Empty state -->
        <div v-if="traits.length === 0 && !traitsLoading" class="empty-state" style="padding: 60px 0;">
          <svg viewBox="0 0 24 24" width="48" height="48" fill="none" stroke="#ccc" stroke-width="1.5">
            <path d="M12 2a7 7 0 0 1 7 7c0 2.38-1.19 4.47-3 5.74V17a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2v-2.26C6.19 13.47 5 11.38 5 9a7 7 0 0 1 7-7z"/>
          </svg>
          <p style="color:#999;margin-top:12px;">暂无特征，记忆库积累足够记忆后系统会自动发现行为特征</p>
        </div>
      </div>
    </div>

    <!-- Graph tab -->
    <div v-if="activeTab === 'graph'" style="min-height: 500px; margin-top: 24px;">
      <KnowledgeGraph :data="graphData" />
    </div>

    <!-- Settings tab -->
    <div v-if="base && activeTab === 'settings'" style="margin-top: 24px;">

      <!-- BUILTIN type -->
      <div v-if="base?.type === 'BUILTIN'" class="section-card" style="padding: 24px;">
        <h3 style="font-size: 15px; font-weight: 600; margin-bottom: 20px; color: #333;">接入信息</h3>

        <div class="form-group">
          <label class="form-label">记忆库 ID</label>
          <div style="font-family: monospace; font-size: 13px; padding: 8px 12px; background: #f5f5f5; border-radius: 4px; color: #333;">{{ base.id }}</div>
        </div>

        <div class="form-group">
          <label class="form-label">API Endpoint</label>
          <div style="font-family: monospace; font-size: 13px; padding: 8px 12px; background: #f5f5f5; border-radius: 4px; color: #333; word-break: break-all;">
            https://api.dbay.cloud:8443/api/v1/memory/bases/{{ base.id }}
          </div>
        </div>

        <div class="form-group">
          <label class="form-label">Python SDK</label>
          <pre style="font-family: monospace; font-size: 13px; padding: 16px; background: #1e1e1e; border-radius: 6px; color: #d4d4d4; overflow-x: auto; margin: 0;">pip install dbay

from dbay import MemoryClient
client = MemoryClient(api_key="your_api_key", base_id="{{ base.id }}")
client.ingest("用户喜欢使用 TypeScript")
results = client.recall("用户的技术偏好")</pre>
        </div>
      </div>

      <!-- MEM0 type -->
      <div v-else-if="base?.type === 'MEM0'" class="section-card" style="padding: 24px;">
        <h3 style="margin-bottom: 16px;">mem0 + DBay 集成指南</h3>
        <p style="color: #666; font-size: 14px; line-height: 1.6;">在您的 DBay 数据库上运行 mem0，享受 Serverless PostgreSQL 的便利。</p>

        <h4 style="margin: 20px 0 8px; font-size: 14px;">1. 安装 mem0</h4>
        <pre style="background: #f5f5f5; padding: 16px; border-radius: 4px; font-size: 13px; overflow-x: auto; white-space: pre-wrap;">pip install mem0ai</pre>

        <h4 style="margin: 20px 0 8px; font-size: 14px;">2. 获取数据库连接信息</h4>
        <p style="color: #666; font-size: 14px; line-height: 1.6;">在「数据库」页面找到您的数据库，复制连接串：</p>
        <pre style="background: #f5f5f5; padding: 16px; border-radius: 4px; font-size: 13px; overflow-x: auto; white-space: pre-wrap;">postgresql://&lt;user&gt;:&lt;password&gt;@&lt;host&gt;:5432/&lt;dbname&gt;</pre>

        <h4 style="margin: 20px 0 8px; font-size: 14px;">3. 配置 mem0 使用 DBay</h4>
        <pre style="background: #f5f5f5; padding: 16px; border-radius: 4px; font-size: 13px; overflow-x: auto; white-space: pre-wrap;">from mem0 import Memory

config = {
    "vector_store": {
        "provider": "pgvector",
        "config": {
            "connection_string": "your_dbay_connection_string",
            "collection_name": "memories"
        }
    },
    "llm": {
        "provider": "openai",
        "config": {
            "model": "gpt-4o-mini",
            "api_key": "your_openai_key"
        }
    }
}

m = Memory.from_config(config)
m.add("用户喜欢使用 Python", user_id="user1")
results = m.search("编程语言偏好", user_id="user1")</pre>

        <h4 style="margin: 20px 0 8px; font-size: 14px;">4. 参考文档</h4>
        <p style="color: #666; font-size: 14px; line-height: 1.6;"><a href="https://docs.mem0.ai" target="_blank" style="color: #1890ff;">mem0 官方文档</a></p>
      </div>

      <!-- HINDSIGHT type -->
      <div v-else-if="base?.type === 'HINDSIGHT'" class="section-card" style="padding: 24px;">
        <h3 style="margin-bottom: 16px;">Hindsight + DBay 集成指南</h3>
        <p style="color: #666; font-size: 14px; line-height: 1.6;">Hindsight 是一个开源的 AI 记忆框架，支持 PostgreSQL 后端。</p>

        <h4 style="margin: 20px 0 8px; font-size: 14px;">1. 安装 Hindsight</h4>
        <pre style="background: #f5f5f5; padding: 16px; border-radius: 4px; font-size: 13px; overflow-x: auto; white-space: pre-wrap;">pip install hindsight-ai</pre>

        <h4 style="margin: 20px 0 8px; font-size: 14px;">2. 获取数据库连接信息</h4>
        <p style="color: #666; font-size: 14px; line-height: 1.6;">在「数据库」页面找到您的数据库，复制连接串。</p>

        <h4 style="margin: 20px 0 8px; font-size: 14px;">3. 配置 Hindsight</h4>
        <pre style="background: #f5f5f5; padding: 16px; border-radius: 4px; font-size: 13px; overflow-x: auto; white-space: pre-wrap;">from hindsight import Hindsight

hs = Hindsight(
    database_url="your_dbay_connection_string"
)
hs.remember("用户偏好 TypeScript")
results = hs.recall("编程语言")</pre>

        <h4 style="margin: 20px 0 8px; font-size: 14px;">4. 参考文档</h4>
        <p style="color: #666; font-size: 14px; line-height: 1.6;"><a href="https://github.com/anthropics/hindsight" target="_blank" style="color: #1890ff;">Hindsight GitHub</a></p>
      </div>

      <!-- CUSTOM type (fallback) -->
      <div v-else class="section-card" style="padding: 24px;">
        <h3 style="margin-bottom: 16px;">自定义记忆系统集成</h3>
        <p style="color: #666; font-size: 14px; line-height: 1.6;">您可以将任何支持 PostgreSQL 的记忆系统连接到 DBay 数据库。</p>

        <h4 style="margin: 20px 0 8px; font-size: 14px;">数据库连接信息</h4>
        <p style="color: #666; font-size: 14px; line-height: 1.6;">在「数据库」页面创建或选择一个数据库，获取连接信息：</p>
        <pre style="background: #f5f5f5; padding: 16px; border-radius: 4px; font-size: 13px; overflow-x: auto; white-space: pre-wrap;">Host:     proxy.dbay.cloud
Port:     5432
Database: your_db_name
User:     your_username
Password: your_password
SSL:      require</pre>

        <h4 style="margin: 20px 0 8px; font-size: 14px;">推荐扩展</h4>
        <p style="color: #666; font-size: 14px; line-height: 1.6;">DBay 数据库已预装以下扩展，适合记忆系统使用：</p>
        <ul style="color: #666; font-size: 14px; line-height: 1.8;">
          <li><strong>pgvector</strong> — 向量相似度搜索</li>
          <li><strong>pg_search</strong> — BM25 全文检索</li>
        </ul>

        <h4 style="margin: 20px 0 8px; font-size: 14px;">建议</h4>
        <p style="color: #666; font-size: 14px; line-height: 1.6;">确保您的记忆系统使用 <code>pgvector</code> 存储嵌入向量，并利用 <code>pg_search</code> 进行混合检索以获得最佳效果。</p>
      </div>

    </div>

    <!-- Loading state -->
    <div v-if="!base" style="padding: 60px 0; text-align: center; color: #999;">
      加载中...
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { getMemoryBase, type MemoryBase, type MemoryItem, type MemoryStats, type Trait, getMemoryStats, listMemories, deleteMemory, recallMemories, listTraits, getGraph, type GraphData } from '../../api/memory'
import TraitCard from '../../components/memory/TraitCard.vue'
import KnowledgeGraph from '../../components/memory/KnowledgeGraph.vue'

const route = useRoute()
const base = ref<MemoryBase | null>(null)
const activeTab = ref('overview')

const memories = ref<MemoryItem[]>([])
const memoryTotal = ref(0)
const memoryPage = ref(0)
const memoryTypeFilter = ref('')
const memorySearch = ref('')
const memoryLoading = ref(false)
const showMemoryDetail = ref(false)
const selectedMemory = ref<MemoryItem | null>(null)
const showDeleteConfirm = ref(false)
const deletingMemoryId = ref<number | null>(null)
const stats = ref<MemoryStats | null>(null)
const graphData = ref<GraphData>({ nodes: [], edges: [] })
const PAGE_SIZE = 20

async function loadMemories() {
  const memId = route.params.memId as string
  memoryLoading.value = true
  try {
    if (memorySearch.value.trim()) {
      const resp = await recallMemories(memId, memorySearch.value)
      memories.value = resp.data.memories
      memoryTotal.value = resp.data.memories.length
    } else {
      const resp = await listMemories(memId, {
        memory_type: memoryTypeFilter.value || undefined,
        offset: memoryPage.value * PAGE_SIZE,
        limit: PAGE_SIZE,
      })
      memories.value = resp.data.memories
      memoryTotal.value = resp.data.total
    }
  } finally {
    memoryLoading.value = false
  }
}

async function loadStats() {
  const memId = route.params.memId as string
  try {
    const resp = await getMemoryStats(memId)
    stats.value = resp.data
  } catch {}
}

function setTypeFilter(t: string) {
  memoryTypeFilter.value = t
  memoryPage.value = 0
  loadMemories()
}

function onSearchEnter() {
  memoryPage.value = 0
  loadMemories()
}

function prevPage() {
  if (memoryPage.value > 0) { memoryPage.value--; loadMemories() }
}
function nextPage() {
  if ((memoryPage.value + 1) * PAGE_SIZE < memoryTotal.value) { memoryPage.value++; loadMemories() }
}

function showDetail(mem: MemoryItem) {
  selectedMemory.value = mem
  showMemoryDetail.value = true
}

function confirmDelete(id: number) {
  deletingMemoryId.value = id
  showDeleteConfirm.value = true
}

async function doDelete() {
  if (deletingMemoryId.value == null) return
  const memId = route.params.memId as string
  await deleteMemory(memId, deletingMemoryId.value)
  showDeleteConfirm.value = false
  deletingMemoryId.value = null
  loadMemories()
  loadStats()
}

const traits = ref<Trait[]>([])
const traitsLoading = ref(false)
const showEarlyStages = ref(false)

const traitsByStage = computed(() => {
  const stages = ['core', 'established', 'emerging', 'candidate', 'trend']
  const grouped: Record<string, Trait[]> = {}
  for (const s of stages) grouped[s] = []
  for (const t of traits.value) {
    const stage = t.trait_stage
    if (grouped[stage]) grouped[stage]!.push(t)
    else grouped[stage] = [t]
  }
  return grouped
})

const earlyStageCount = computed(() =>
  (traitsByStage.value['candidate']?.length || 0) + (traitsByStage.value['trend']?.length || 0)
)

async function loadGraph() {
  const memId = route.params.memId as string
  try {
    const resp = await getGraph(memId)
    graphData.value = resp.data
  } catch {}
}

async function loadTraits() {
  const memId = route.params.memId as string
  traitsLoading.value = true
  try {
    const resp = await listTraits(memId)
    traits.value = resp.data
  } finally {
    traitsLoading.value = false
  }
}

const typeLabels: Record<string, string> = { BUILTIN: '自研', MEM0: 'mem0', HINDSIGHT: 'hindsight', CUSTOM: '自定义' }
const typeLabel = computed(() => typeLabels[base.value?.type || ''] || base.value?.type || '')

function statusText(status: string) {
  const map: Record<string, string> = { READY: '就绪', CREATING: '创建中', FAILED: '失败' }
  return map[status] || status
}

onMounted(async () => {
  const memId = route.params.memId as string
  const resp = await getMemoryBase(memId)
  base.value = resp.data
  loadStats()
  loadMemories()
  loadTraits()
  loadGraph()
})
</script>
