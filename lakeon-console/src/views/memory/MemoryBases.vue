<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">记忆库</h1>
      <div class="page-header-actions">
        <button class="btn btn-primary" @click="showCreate = true; createStep = 1; resetCreateForm()">创建记忆库</button>
      </div>
    </div>

    <!-- Create dialog — two-step wizard -->
    <div v-if="showCreate" class="dialog-overlay" @click.self="showCreate = false">
      <div class="dialog-box" style="max-width: 520px;">
        <div class="dialog-header">
          <h3>{{ createStep === 1 ? '选择应用场景' : '配置记忆库' }}</h3>
          <button class="dialog-close" @click="showCreate = false">&times;</button>
        </div>
        <div class="dialog-body">
          <!-- Step 1: Scene selection -->
          <template v-if="createStep === 1">
            <div style="display: flex; flex-direction: column; gap: 12px;">
              <div class="scene-card" :class="{ selected: createForm.scene === 'DEVELOPER_TOOL' }"
                   @click="createForm.scene = 'DEVELOPER_TOOL'; createStep = 2">
                <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 6px;">
                  <span style="font-size: 20px;">🛠</span>
                  <span style="font-weight: 600; font-size: 15px;">开发者工具</span>
                </div>
                <div style="font-size: 13px; color: #666; line-height: 1.6;">
                  适用于 Claude Code、Cursor 等编码助手。记录事实、流程、决策和教训，不记录对话情景，不自动衰减。
                </div>
              </div>
              <div class="scene-card" :class="{ selected: createForm.scene === 'CHAT_ASSISTANT' }"
                   @click="createForm.scene = 'CHAT_ASSISTANT'; createStep = 2">
                <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 6px;">
                  <span style="font-size: 20px;">💬</span>
                  <span style="font-weight: 600; font-size: 15px;">对话助理</span>
                </div>
                <div style="font-size: 13px; color: #666; line-height: 1.6;">
                  适用于聊天机器人、个人助理、客服等。记录完整对话情景，自动提炼用户特征，支持时间衰减。
                </div>
              </div>
            </div>
          </template>

          <!-- Step 2: Details -->
          <template v-else>
            <!-- Scene summary (clickable to go back) -->
            <div class="scene-summary" @click="createStep = 1">
              <span>{{ createForm.scene === 'DEVELOPER_TOOL' ? '🛠 开发者工具' : '💬 对话助理' }}</span>
              <span style="color: #0073e6; font-size: 12px;">更改</span>
            </div>

            <div class="form-group">
              <label class="form-label">名称 <span style="color:#e6393d">*</span></label>
              <input v-model="createForm.name" class="form-input" placeholder="例如：用户偏好记忆库" />
            </div>
            <div class="form-group">
              <label class="form-label">描述</label>
              <input v-model="createForm.description" class="form-input" placeholder="可选，描述记忆库用途" />
            </div>

            <!-- Embedding model selector -->
            <div v-if="createForm.type === 'BUILTIN'" class="form-group">
              <label class="form-label">嵌入模型</label>
              <select v-model="createForm.embedding_model" class="form-input" style="cursor: pointer;">
                <option value="BAAI/bge-m3">BAAI/bge-m3</option>
                <option value="text-embedding-3-small">text-embedding-3-small</option>
              </select>
            </div>

            <!-- Agent-Extract mode toggle -->
            <div v-if="createForm.type === 'BUILTIN'" class="form-group">
              <label class="form-label">提取模式</label>
              <label style="display: flex; align-items: center; gap: 8px; cursor: pointer; font-size: 14px;">
                <input type="checkbox" v-model="createForm.agent_extract" style="width: 16px; height: 16px;" />
                Agent-Extract 模式
              </label>
              <p style="font-size: 12px; color: #999; margin-top: 4px;">
                {{ createForm.agent_extract
                  ? '客户端（如 Claude Code）自行提取记忆，零服务端 LLM 成本。'
                  : '服务端自动提取记忆（默认）。' }}
              </p>
            </div>
          </template>
        </div>
        <div v-if="createStep === 2" class="dialog-footer">
          <button class="btn btn-default" @click="createStep = 1">上一步</button>
          <button class="btn btn-primary" @click="handleCreate" :disabled="!createForm.name.trim()">创建</button>
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
const createStep = ref(1)
const loading = ref(false)

const createForm = ref({
  name: '',
  description: '',
  type: 'BUILTIN' as MemoryBase['type'],
  scene: '' as string,
  embedding_model: 'BAAI/bge-m3',
  agent_extract: false,
})

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
.scene-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  background: #f7f8fa;
  border: 1px solid #e8e8e8;
  border-radius: 6px;
  margin-bottom: 16px;
  cursor: pointer;
  font-size: 14px;
  font-weight: 500;
}
.scene-summary:hover {
  border-color: #0073e6;
}
</style>
