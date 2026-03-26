<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">记忆库</h1>
      <div class="page-header-actions">
        <button class="btn btn-primary" @click="showCreate = true">创建记忆库</button>
      </div>
    </div>

    <!-- Create dialog -->
    <div v-if="showCreate" class="dialog-overlay" @click.self="showCreate = false">
      <div class="dialog-box" style="max-width: 500px;">
        <div class="dialog-header">
          <h3>创建记忆库</h3>
          <button class="dialog-close" @click="showCreate = false">&times;</button>
        </div>
        <div class="dialog-body">
          <!-- Scene selector -->
          <div class="form-group">
            <label class="form-label">应用场景 <span style="color:#e6393d">*</span></label>
            <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 12px;">
              <div class="scene-card" :class="{ selected: createForm.scene === 'DEVELOPER_TOOL' }"
                   @click="createForm.scene = 'DEVELOPER_TOOL'">
                <div style="font-weight: 600; font-size: 14px; margin-bottom: 4px;">开发者工具</div>
                <div style="font-size: 12px; color: #666; line-height: 1.5;">
                  适用于 Claude Code、Cursor 等编码助手。记录事实、流程、决策和教训，不记录对话情景，不自动衰减。
                </div>
              </div>
              <div class="scene-card" :class="{ selected: createForm.scene === 'CHAT_ASSISTANT' }"
                   @click="createForm.scene = 'CHAT_ASSISTANT'">
                <div style="font-weight: 600; font-size: 14px; margin-bottom: 4px;">对话助理</div>
                <div style="font-size: 12px; color: #666; line-height: 1.5;">
                  适用于聊天机器人、个人助理、客服等。记录完整对话情景，自动提炼用户特征，支持时间衰减。
                </div>
              </div>
            </div>
          </div>

          <!-- Type selector -->
          <div class="form-group">
            <label class="form-label">类型 <span style="color:#e6393d">*</span></label>
            <div style="display: flex; gap: 10px; flex-wrap: wrap;">
              <label class="type-radio" :class="{ selected: createForm.type === 'BUILTIN' }">
                <input type="radio" v-model="createForm.type" value="BUILTIN" style="display: none;" />
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink: 0;">
                  <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"/>
                  <circle cx="12" cy="12" r="10"/>
                  <line x1="12" y1="17" x2="12.01" y2="17"/>
                </svg>
                <span>DBay记忆库</span>
              </label>
              <label class="type-radio" :class="{ selected: createForm.type === 'MEM0' }">
                <input type="radio" v-model="createForm.type" value="MEM0" style="display: none;" />
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink: 0;">
                  <circle cx="12" cy="12" r="10"/>
                </svg>
                <span>mem0</span>
              </label>
              <label class="type-radio" :class="{ selected: createForm.type === 'HINDSIGHT' }">
                <input type="radio" v-model="createForm.type" value="HINDSIGHT" style="display: none;" />
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink: 0;">
                  <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
                  <circle cx="12" cy="12" r="3"/>
                </svg>
                <span>hindsight</span>
              </label>
              <label class="type-radio" :class="{ selected: createForm.type === 'CUSTOM' }">
                <input type="radio" v-model="createForm.type" value="CUSTOM" style="display: none;" />
                <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink: 0;">
                  <circle cx="12" cy="12" r="3"/>
                  <path d="M19.07 4.93a10 10 0 0 1 0 14.14M4.93 4.93a10 10 0 0 0 0 14.14"/>
                </svg>
                <span>自定义</span>
              </label>
            </div>
          </div>

          <div class="form-group">
            <label class="form-label">名称 <span style="color:#e6393d">*</span></label>
            <input v-model="createForm.name" class="form-input" placeholder="例如：用户偏好记忆库" />
          </div>
          <div class="form-group">
            <label class="form-label">描述</label>
            <input v-model="createForm.description" class="form-input" placeholder="可选，描述记忆库用途" />
          </div>

          <!-- Embedding model selector (BUILTIN type only) -->
          <div v-if="createForm.type === 'BUILTIN'" class="form-group">
            <label class="form-label">嵌入模型</label>
            <select v-model="createForm.embedding_model" class="form-input" style="cursor: pointer;">
              <option value="BAAI/bge-m3">BAAI/bge-m3</option>
              <option value="text-embedding-3-small">text-embedding-3-small</option>
            </select>
            <p style="font-size: 12px; color: #999; margin-top: 4px;">
              不同模型的向量维度不同，创建后不可更改
            </p>
          </div>

          <!-- Agent-Extract mode toggle (BUILTIN only) -->
          <div v-if="createForm.type === 'BUILTIN'" class="form-group">
            <label class="form-label">提取模式</label>
            <label style="display: flex; align-items: center; gap: 8px; cursor: pointer; font-size: 14px;">
              <input type="checkbox" v-model="createForm.agent_extract" style="width: 16px; height: 16px;" />
              Agent-Extract 模式
            </label>
            <p style="font-size: 12px; color: #999; margin-top: 4px;">
              {{ createForm.agent_extract
                ? '客户端（如 Claude Code）自行提取记忆，零服务端 LLM 成本。适用于自带 LLM 的客户端。'
                : '服务端自动提取记忆（默认）。适用于没有 LLM 的客户端（如 OpenClaw）。' }}
            </p>
          </div>

          <!-- Info text for non-BUILTIN types -->
          <p v-if="createForm.type !== 'BUILTIN'" style="font-size: 12px; color: #999; margin-top: 12px;">
            请参考文档在您的 DBay 数据库上配置 {{ typeNameMap[createForm.type] }}
          </p>
          <p v-else style="font-size: 12px; color: #999; margin-top: 12px;">
            系统将自动创建专用数据库，使用所选向量模型生成嵌入并建立检索索引。
          </p>
        </div>
        <div class="dialog-footer">
          <button class="btn btn-default" @click="showCreate = false">取消</button>
          <button class="btn btn-primary" @click="handleCreate" :disabled="!createForm.name.trim() || !createForm.scene">创建</button>
        </div>
      </div>
    </div>

    <!-- Memory base list -->
    <div v-if="memoryBases.length > 0" style="margin-top: 20px;">
      <table class="data-table">
        <thead>
          <tr>
            <th>名称</th>
            <th>类型</th>
            <th>记忆数</th>
            <th>特征数</th>
            <th>状态</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in memoryBases" :key="item.id" style="cursor: pointer;" @click="handleRowClick(item)">
            <td style="font-weight: 500; color: #0073e6;">
              {{ item.name }}
              <span v-if="item.scene" style="font-size: 11px; padding: 1px 6px; border-radius: 3px; margin-left: 8px;"
                    :style="item.scene === 'DEVELOPER_TOOL' ? 'background:#e8f5e9;color:#2e7d32' : 'background:#e3f2fd;color:#1565c0'">
                {{ item.scene === 'DEVELOPER_TOOL' ? '开发者工具' : '对话助理' }}
              </span>
            </td>
            <td>
              <span v-if="item.type === 'BUILTIN'" style="display:inline-block;padding:1px 8px;border-radius:4px;font-size:12px;background:#fef2f0;color:#e6393d;">自研</span>
              <span v-else-if="item.type === 'MEM0'" style="display:inline-block;padding:1px 8px;border-radius:4px;font-size:12px;background:#e6f7ff;color:#1890ff;">mem0</span>
              <span v-else-if="item.type === 'HINDSIGHT'" style="display:inline-block;padding:1px 8px;border-radius:4px;font-size:12px;background:#f0fff4;color:#389e0d;">hindsight</span>
              <span v-else style="display:inline-block;padding:1px 8px;border-radius:4px;font-size:12px;background:#f5f5f5;color:#666;">自定义</span>
            </td>
            <td>{{ item.memory_count ?? 0 }}</td>
            <td>{{ item.trait_count ?? 0 }}</td>
            <td>
              <span class="status-tag" :class="item.status === 'READY' ? 'tag-green' : item.status === 'FAILED' ? 'tag-red' : 'tag-gray'">
                {{ statusText(item.status) }}
              </span>
            </td>
            <td style="color: #999;">{{ formatTime(item.created_at) }}</td>
            <td @click.stop>
              <button class="btn btn-text btn-small" style="color: #e6393d;" @click="handleDelete(item)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Empty state -->
    <div v-else-if="!loading" class="empty-state" style="margin-top: 64px; text-align: center;">
      <svg viewBox="0 0 24 24" width="48" height="48" fill="none" stroke="#ccc" stroke-width="1.5">
        <path d="M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3"/>
        <circle cx="12" cy="12" r="10"/>
        <line x1="12" y1="17" x2="12.01" y2="17"/>
      </svg>
      <p style="color: #666; margin-top: 12px;">还没有记忆库</p>
      <p style="color: #999; font-size: 13px;">创建记忆库后，AI 将自动管理用户记忆与特征</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { listMemoryBases, createMemoryBase, deleteMemoryBase, type MemoryBase } from '../../api/memory'

const router = useRouter()
const memoryBases = ref<MemoryBase[]>([])
const showCreate = ref(false)
const loading = ref(false)

const createForm = ref({
  name: '',
  description: '',
  type: 'BUILTIN' as MemoryBase['type'],
  scene: '' as string,
  embedding_model: 'BAAI/bge-m3',
  agent_extract: false,
})

const typeNameMap: Record<string, string> = {
  MEM0: 'mem0',
  HINDSIGHT: 'hindsight',
  CUSTOM: '自定义',
}

function statusText(status: string) {
  const map: Record<string, string> = { READY: '就绪', CREATING: '创建中', FAILED: '失败' }
  return map[status] || status
}

function formatTime(t: string) {
  if (!t) return '-'
  return new Date(t).toLocaleString('zh-CN')
}

function resetCreateForm() {
  createForm.value = {
    name: '',
    description: '',
    type: 'BUILTIN',
    scene: '',
    embedding_model: 'BAAI/bge-m3',
    agent_extract: false,
  }
}

async function loadMemoryBases() {
  loading.value = true
  try {
    const res = await listMemoryBases()
    memoryBases.value = res.data
  } catch (e: any) {
    console.error('Failed to load memory bases:', e)
  } finally {
    loading.value = false
  }
}

async function handleCreate() {
  try {
    const { name, description, type, scene, embedding_model, agent_extract } = createForm.value
    const options: { type?: MemoryBase['type']; scene?: MemoryBase['scene']; embedding_model?: string; one_llm_mode?: boolean } = { type, scene: scene as MemoryBase['scene'] }
    if (type === 'BUILTIN' && embedding_model) {
      options.embedding_model = embedding_model
    }
    if (type === 'BUILTIN' && agent_extract) {
      options.one_llm_mode = true
    }
    await createMemoryBase(name, description || undefined, options)
    showCreate.value = false
    resetCreateForm()
    await loadMemoryBases()
  } catch (e: any) {
    alert('创建失败: ' + (e.response?.data?.error?.message || e.message))
  }
}

async function handleDelete(item: MemoryBase) {
  if (!confirm(`确认删除记忆库"${item.name}"？所有记忆和特征数据将被永久删除。`)) return
  try {
    await deleteMemoryBase(item.id)
    await loadMemoryBases()
  } catch (e: any) {
    alert('删除失败: ' + (e.response?.data?.error?.message || e.message))
  }
}

function handleRowClick(item: MemoryBase) {
  router.push('/memory/' + item.id)
}

onMounted(loadMemoryBases)
</script>

<style scoped>
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
  border-color: #0073e6;
  color: #0073e6;
}
.type-radio.selected {
  border-color: #0073e6;
  background: #e8f3ff;
  color: #0073e6;
  font-weight: 500;
}
.scene-card {
  border: 1px solid #d9d9d9;
  border-radius: 8px;
  padding: 12px;
  cursor: pointer;
  transition: all 0.15s;
}
.scene-card:hover {
  border-color: #0073e6;
}
.scene-card.selected {
  border-color: #0073e6;
  background: #f0f7ff;
  box-shadow: 0 0 0 1px #0073e6;
}
</style>
