<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">知识库</h1>
      <div class="page-header-actions">
        <ViewToggle v-model="viewMode" />
        <button class="btn btn-primary" @click="showCreate = true">创建知识库</button>
      </div>
    </div>

    <!-- Create dialog -->
    <div v-if="showCreate" class="dialog-overlay" @click.self="showCreate = false">
      <div class="dialog-box" style="max-width: 500px;">
        <div class="dialog-header">
          <h3>创建知识库</h3>
          <button class="dialog-close" @click="showCreate = false">&times;</button>
        </div>
        <div class="dialog-body">
          <!-- Type selector -->
          <div class="form-group">
            <label class="form-label">类型 <span style="color:#e6393d">*</span></label>
            <div style="display: flex; gap: 10px;">
              <label class="type-radio" :class="{ selected: createForm.type === 'DOCUMENT' }">
                <input type="radio" v-model="createForm.type" value="DOCUMENT" style="display: none;" />
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink: 0;">
                  <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
                  <polyline points="14 2 14 8 20 8"/>
                </svg>
                <span>文档知识库</span>
              </label>
              <label class="type-radio" :class="{ selected: createForm.type === 'TABLE' }">
                <input type="radio" v-model="createForm.type" value="TABLE" style="display: none;" />
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink: 0;">
                  <rect x="3" y="3" width="18" height="18" rx="2" ry="2"/>
                  <line x1="3" y1="9" x2="21" y2="9"/>
                  <line x1="3" y1="15" x2="21" y2="15"/>
                  <line x1="9" y1="3" x2="9" y2="21"/>
                </svg>
                <span>数据表知识库</span>
              </label>
            </div>
          </div>

          <div class="form-group">
            <label class="form-label">名称 <span style="color:#e6393d">*</span></label>
            <input v-model="createForm.name" class="form-input" :placeholder="createForm.type === 'TABLE' ? '例如：销售数据库' : '例如：产品文档库'" />
          </div>
          <div class="form-group">
            <label class="form-label">描述</label>
            <input v-model="createForm.description" class="form-input" placeholder="可选，描述知识库用途" />
          </div>

          <!-- TABLE-specific fields -->
          <template v-if="createForm.type === 'TABLE'">
            <div class="form-group">
              <label class="form-label">关联数据库 <span style="color:#e6393d">*</span></label>
              <select v-model="createForm.source_database_id" class="form-input" style="cursor: pointer;">
                <option value="">-- 选择数据库 --</option>
                <option v-for="db in databases" :key="db.id" :value="db.id">{{ db.name }}</option>
              </select>
              <div v-if="dbLoadError" style="font-size: 12px; color: #e6393d; margin-top: 4px;">{{ dbLoadError }}</div>
            </div>
            <div class="form-group">
              <label class="form-label">数据表（逗号分隔）</label>
              <input v-model="createForm.table_names_raw" class="form-input" placeholder="例如：orders, users, products（留空表示全部）" />
              <p style="font-size: 12px; color: #999; margin-top: 4px;">多个表名用英文逗号分隔，留空则自动加载所有表</p>
            </div>
          </template>

          <!-- Embedding model selector (DOCUMENT type only) -->
          <div v-if="createForm.type === 'DOCUMENT'" class="form-group">
            <label class="form-label">嵌入模型</label>
            <select v-model="createForm.embedding_model" class="form-input" style="cursor: pointer;">
              <option v-for="m in embeddingModels" :key="m.value" :value="m.value">{{ m.label }}</option>
            </select>
            <p style="font-size: 12px; color: #999; margin-top: 4px;">
              不同模型的向量维度不同，创建后不可更改
            </p>
          </div>

          <p v-if="createForm.type === 'DOCUMENT'" style="font-size: 12px; color: #999; margin-top: 12px;">
            系统将自动创建专用数据库，使用所选向量模型生成嵌入并建立检索索引。
          </p>
          <p v-else style="font-size: 12px; color: #999; margin-top: 12px;">
            AI 将为所选数据表建立 schema 索引，支持用自然语言查询数据。
          </p>
        </div>
        <div class="dialog-footer">
          <button class="btn btn-default" @click="showCreate = false">取消</button>
          <button class="btn btn-primary" @click="handleCreate" :disabled="!createFormValid">创建</button>
        </div>
      </div>
    </div>

    <!-- Journey guide banner (collapsible) -->
    <div v-if="showGuide" style="margin-bottom: 20px; background: #faf8f5; border: 1px solid #e8e0d8; border-radius: 10px; padding: 20px 24px; position: relative;">
      <button @click="showGuide = false" style="position: absolute; right: 12px; top: 10px; background: none; border: none; color: #bbb; cursor: pointer; font-size: 16px; padding: 2px 6px;" title="收起">&times;</button>
      <div style="text-align: center; margin-bottom: 14px;">
        <span style="font-size: 15px; font-weight: 600; color: #2c2420;">知识构建流程</span>
        <span style="font-size: 12px; color: #999; margin-left: 12px;">上传文档，AI 自动整理为结构化的 Wiki 知识体系</span>
      </div>
      <div style="display: flex; gap: 10px; max-width: 640px; margin: 0 auto;">
        <div class="guide-card">
          <div class="guide-num" style="background: #c25a3c;">1</div>
          <div class="guide-title">导入</div>
          <div class="guide-desc">上传文件、目录或 URL</div>
        </div>
        <div style="display: flex; align-items: center; color: #d4c4b0;">&rarr;</div>
        <div class="guide-card">
          <div class="guide-num" style="background: #d4885a;">2</div>
          <div class="guide-title">Wiki</div>
          <div class="guide-desc">AI 自动生成 Wiki 和知识图谱</div>
        </div>
        <div style="display: flex; align-items: center; color: #d4c4b0;">&rarr;</div>
        <div class="guide-card">
          <div class="guide-num" style="background: #8c7a68;">3</div>
          <div class="guide-title">对话</div>
          <div class="guide-desc">向知识库提问，深度探索</div>
        </div>
        <div style="display: flex; align-items: center; color: #d4c4b0;">&rarr;</div>
        <div class="guide-card">
          <div class="guide-num" style="background: #a89080;">4</div>
          <div class="guide-title">沉淀</div>
          <div class="guide-desc">洞察保存回 Wiki</div>
        </div>
      </div>
    </div>
    <div v-if="!showGuide" style="margin-bottom: 12px;">
      <button @click="showGuide = true" style="background: none; border: none; color: #9a5b25; cursor: pointer; font-size: 12px; padding: 0;">显示知识构建流程引导</button>
    </div>

    <div style="border-bottom: 1px solid #e8e0d8; margin-bottom: 20px;"></div>

    <!-- Knowledge base list -->

    <!-- Card view -->
    <div v-if="viewMode === 'card' && filteredKBs.length > 0" class="card-grid">
      <ResourceCard
        v-for="kb in filteredKBs"
        :key="kb.id"
        :name="kb.name"
        :status="kb.status"
        :statusLabel="statusText(kb.status)"
        :meta="[kb.type === 'TABLE' ? '数据表' : '文档', kb.embedding_model || '-', kb.type === 'TABLE' ? '-' : `${kb.document_count ?? 0} 文档`]"
        @click="$router.push(`/knowledge/${kb.id}`)"
      >
        <template #actions>
          <CardMenu @delete="handleDelete(kb)" />
        </template>
      </ResourceCard>
      <div class="card-create" @click="showCreate = true">
        + 创建知识库
      </div>
    </div>

    <!-- Table view -->
    <div v-if="viewMode === 'table' && filteredKBs.length > 0" class="table-wrapper">
      <table class="data-table">
        <thead>
          <tr>
            <th>名称</th>
            <th>类型</th>
            <th>嵌入模型</th>
            <th>描述</th>
            <th>文档数</th>
            <th>状态</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="kb in filteredKBs" :key="kb.id">
            <td>
              <router-link :to="`/knowledge/${kb.id}`" style="color: #9a5b25; text-decoration: none; font-weight: 500;">
                {{ kb.name }}
              </router-link>
            </td>
            <td>
              <span v-if="kb.type === 'TABLE'" class="type-tag type-tag-table">数据表</span>
              <span v-else class="type-tag type-tag-doc">文档</span>
            </td>
            <td style="color: #666; font-size: 12px;">{{ kb.embedding_model || '-' }}</td>
            <td style="color: #666;">{{ kb.description || '-' }}</td>
            <td>{{ kb.type === 'TABLE' ? '-' : (kb.document_count ?? 0) }}</td>
            <td>
              <span class="status-tag" :class="'tag-' + statusColor(kb.status)">{{ statusText(kb.status) }}</span>
            </td>
            <td style="color: #999;">{{ formatTime(kb.created_at) }}</td>
            <td>
              <button class="btn btn-text btn-small btn-danger-text" @click="handleDelete(kb)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Empty state -->
    <div v-if="filteredKBs.length === 0 && !loading" class="empty-state" style="margin-top: 64px; text-align: center;">
      <svg viewBox="0 0 24 24" width="48" height="48" fill="none" stroke="#ccc" stroke-width="1.5">
        <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/>
        <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/>
      </svg>
      <p style="color: #666; margin-top: 12px;">还没有知识库</p>
      <p style="color: #999; font-size: 13px;">创建知识库后，上传文档即可自动建立检索索引</p>
    </div>

    <!-- Quick tips -->
    <div v-if="!loading && knowledgeBases.length > 0 && knowledgeBases.length < 3" class="page-tips">
      <div class="page-tips-title">快速上手</div>
      <div class="page-tips-items">
        <span>上传文档到知识库，支持 PDF、Markdown、TXT 等格式</span>
        <span class="tips-sep">·</span>
        <span>通过 MCP 集成让 AI Agent 直接检索知识库</span>
        <span class="tips-sep">·</span>
        <router-link to="/docs#knowledge" class="tips-link">查看文档</router-link>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { listKnowledgeBases, createKnowledgeBase, deleteKnowledgeBase, type KnowledgeBase } from '../../api/knowledge'
import { databaseApi, type Database } from '../../api/database'
import ViewToggle from '../../components/ViewToggle.vue'
import ResourceCard from '../../components/ResourceCard.vue'
import CardMenu from '../../components/CardMenu.vue'

const viewMode = ref<'card' | 'table'>('card')
const knowledgeBases = ref<KnowledgeBase[]>([])
const showGuide = ref(true)
const showCreate = ref(false)
const createForm = ref({
  name: '',
  description: '',
  type: 'DOCUMENT' as 'DOCUMENT' | 'TABLE',
  source_database_id: '',
  table_names_raw: '',
  embedding_model: 'BAAI/bge-m3',
})
const loading = ref(false)
const kbSearch = ref('')
const databases = ref<Database[]>([])
const dbLoadError = ref('')

const embeddingModels = [
  { value: 'BAAI/bge-m3', label: 'BGE-M3 (1024维, 推荐)' },
  { value: 'BAAI/bge-large-zh-v1.5', label: 'BGE-Large-ZH v1.5 (1024维)' },
  { value: 'BAAI/bge-large-en-v1.5', label: 'BGE-Large-EN v1.5 (1024维)' },
  { value: 'Pro/BAAI/bge-m3', label: 'BGE-M3 Pro (1024维)' },
  { value: 'text-embedding-3-small', label: 'OpenAI text-embedding-3-small (1536维)' },
  { value: 'text-embedding-3-large', label: 'OpenAI text-embedding-3-large (3072维)' },
]

const filteredKBs = computed(() => {
  if (!kbSearch.value) return knowledgeBases.value
  const q = kbSearch.value.toLowerCase()
  return knowledgeBases.value.filter(kb => kb.name.toLowerCase().includes(q) || (kb.description || '').toLowerCase().includes(q))
})

const createFormValid = computed(() => {
  if (!createForm.value.name.trim()) return false
  if (createForm.value.type === 'TABLE' && !createForm.value.source_database_id) return false
  return true
})

function resetCreateForm() {
  createForm.value = {
    name: '',
    description: '',
    type: 'DOCUMENT',
    source_database_id: '',
    table_names_raw: '',
    embedding_model: 'BAAI/bge-m3',
  }
}

async function loadDatabases() {
  dbLoadError.value = ''
  try {
    const res = await databaseApi.list()
    databases.value = res.data
  } catch (e: any) {
    dbLoadError.value = '加载数据库列表失败'
  }
}

// Load databases when dialog opens
watch(showCreate, (val) => {
  if (val) loadDatabases()
})

function statusColor(status: string) {
  if (status === 'READY') return 'green'
  if (status === 'CREATING') return 'blue'
  if (status === 'FAILED') return 'red'
  return 'blue'
}

function statusText(status: string) {
  const map: Record<string, string> = { READY: '就绪', CREATING: '创建中', FAILED: '失败' }
  return map[status] || status
}

function formatTime(t: string) {
  if (!t) return '-'
  return new Date(t).toLocaleString('zh-CN')
}

async function loadKBs() {
  loading.value = true
  try {
    const res = await listKnowledgeBases()
    knowledgeBases.value = res.data
  } catch (e: any) {
    console.error('Failed to load knowledge bases:', e)
  } finally {
    loading.value = false
  }
}

async function handleCreate() {
  try {
    const { name, description, type, source_database_id, table_names_raw } = createForm.value
    const tableNames = table_names_raw
      .split(',')
      .map(t => t.trim())
      .filter(t => t.length > 0)

    const options: { type?: 'DOCUMENT' | 'TABLE'; source_database_id?: string; table_names?: string[]; embedding_model?: string } = { type }
    if (type === 'TABLE') {
      options.source_database_id = source_database_id
      if (tableNames.length > 0) options.table_names = tableNames
    }
    if (type === 'DOCUMENT' && createForm.value.embedding_model) {
      options.embedding_model = createForm.value.embedding_model
    }

    await createKnowledgeBase(name, description || undefined, options)
    showCreate.value = false
    resetCreateForm()
    await loadKBs()
  } catch (e: any) {
    alert('创建失败: ' + (e.response?.data?.error?.message || e.message))
  }
}

async function handleDelete(kb: KnowledgeBase) {
  if (!confirm(`确认删除知识库"${kb.name}"？所有文档和索引数据将被永久删除。`)) return
  try {
    await deleteKnowledgeBase(kb.id)
    await loadKBs()
  } catch (e: any) {
    alert('删除失败: ' + (e.response?.data?.error?.message || e.message))
  }
}

onMounted(loadKBs)
</script>

<style scoped>
.guide-card {
  flex: 1;
  background: #fff;
  border: 1px solid #e8e0d8;
  border-radius: 8px;
  padding: 14px 10px;
  text-align: center;
}
.guide-num {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 6px;
}
.guide-title {
  font-size: 14px;
  font-weight: 600;
  color: #3d3d3d;
  margin-bottom: 2px;
}
.guide-desc {
  font-size: 11px;
  color: #8c7a68;
  line-height: 1.4;
}
.type-radio {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border: 1px solid #e0e0e0;
  border-radius: 6px;
  cursor: pointer;
  font-size: 13px;
  color: #555;
  flex: 1;
  transition: border-color 0.15s, background 0.15s, color 0.15s;
  user-select: none;
}
.type-radio:hover {
  border-color: #c67d3a;
  color: #9a5b25;
}
.type-radio.selected {
  border-color: #c67d3a;
  background: #e8f3ff;
  color: #9a5b25;
  font-weight: 500;
}
.type-tag {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 4px;
  font-size: 12px;
  white-space: nowrap;
}
.type-tag-doc {
  background: #f0f7ff;
  color: #9a5b25;
  border: 1px solid #b3d4f7;
}
.type-tag-table {
  background: #f0fff4;
  color: #389e0d;
  border: 1px solid #b7eb8f;
}
.page-tips {
  margin-top: 48px;
  padding: 16px 20px;
  background: #faf8f5;
  border-radius: 6px;
  border-left: 3px solid #c67d3a;
}
.page-tips-title {
  font-size: 13px;
  font-weight: 600;
  color: #2c3e50;
  margin-bottom: 6px;
}
.page-tips-items {
  font-size: 13px;
  color: #64748b;
  line-height: 1.6;
}
.tips-sep {
  margin: 0 8px;
  color: #d5d0ca;
}
.tips-link {
  color: #9a5b25;
  text-decoration: none;
}
.tips-link:hover {
  text-decoration: underline;
}
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 12px;
  margin-top: 16px;
}
.card-create {
  border: 1px dashed #d5d0ca;
  border-radius: 8px;
  padding: 14px 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #94a3b8;
  font-size: 13px;
  cursor: pointer;
  transition: border-color 0.15s;
}
.card-create:hover { border-color: #94a3b8; }
@media (max-width: 768px) {
  .card-grid { grid-template-columns: 1fr; }
}
</style>
