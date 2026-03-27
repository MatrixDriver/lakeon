<template>
  <div>
    <h2>记忆库管理</h2>

    <!-- Tabs -->
    <div class="tabs" style="display: flex; gap: 0; margin-bottom: 24px; border-bottom: 2px solid #f0f0f0;">
      <button v-for="tab in tabs" :key="tab.key" @click="activeTab = tab.key"
              :style="{ padding: '8px 20px', border: 'none', background: 'none', cursor: 'pointer', fontSize: '14px', fontWeight: activeTab === tab.key ? '600' : '400', color: activeTab === tab.key ? '#1890ff' : '#666', borderBottom: activeTab === tab.key ? '2px solid #1890ff' : '2px solid transparent', marginBottom: '-2px' }">
        {{ tab.label }}
      </button>
    </div>

    <!-- Tab: 记忆库列表 -->
    <div v-if="activeTab === 'bases'">

    <!-- Stats cards -->
    <div class="stats-row" style="display: flex; gap: 16px; margin-bottom: 24px;">
      <div class="section-card" style="flex: 1; text-align: center; padding: 16px;">
        <div style="font-size: 28px; font-weight: 600; color: #1890ff;">{{ stats.base_count ?? '-' }}</div>
        <div style="font-size: 12px; color: #999;">记忆库</div>
      </div>
      <div class="section-card" style="flex: 1; text-align: center; padding: 16px;">
        <div style="font-size: 28px; font-weight: 600; color: #52c41a;">{{ stats.total_memories ?? '-' }}</div>
        <div style="font-size: 12px; color: #999;">总记忆</div>
      </div>
      <div class="section-card" style="flex: 1; text-align: center; padding: 16px;">
        <div style="font-size: 28px; font-weight: 600; color: #722ed1;">{{ stats.total_traits ?? '-' }}</div>
        <div style="font-size: 12px; color: #999;">Traits</div>
      </div>
      <div class="section-card" style="flex: 1; text-align: center; padding: 16px;">
        <div style="font-size: 28px; font-weight: 600; color: #e53e3e;">{{ errorCount }}</div>
        <div style="font-size: 12px; color: #999;">异常</div>
      </div>
    </div>

    <!-- Filters -->
    <div style="display: flex; gap: 12px; margin-bottom: 16px; align-items: center;">
      <select v-model="statusFilter" @change="loadBases" style="padding: 6px 12px; border: 1px solid #d9d9d9; border-radius: 4px;">
        <option value="">全部状态</option>
        <option value="READY">READY</option>
        <option value="PROVISIONING">PROVISIONING</option>
        <option value="ERROR">ERROR</option>
      </select>
      <input v-model="tenantFilter" @keyup.enter="loadBases" placeholder="租户 ID..."
             style="padding: 6px 12px; border: 1px solid #d9d9d9; border-radius: 4px; width: 200px;" />
      <button class="btn" @click="loadBases">筛选</button>
      <div style="flex: 1;"></div>
      <button v-if="selectedIds.length > 0" class="btn btn-danger" @click="batchDelete">
        批量删除 ({{ selectedIds.length }})
      </button>
    </div>

    <!-- Table -->
    <table class="data-table" style="width: 100%;">
      <thead>
        <tr>
          <th style="width: 32px;"><input type="checkbox" @change="toggleAll" :checked="allSelected" /></th>
          <th>ID</th>
          <th>名称</th>
          <th>租户</th>
          <th>状态</th>
          <th>模式</th>
          <th>记忆数</th>
          <th>Traits</th>
          <th>创建时间</th>
        </tr>
      </thead>
      <tbody>
        <template v-for="base in bases" :key="base.id">
          <tr @click="toggleExpand(base.id)" style="cursor: pointer;">
            <td @click.stop><input type="checkbox" :value="base.id" v-model="selectedIds" /></td>
            <td style="font-family: monospace; font-size: 12px;">{{ base.id }}</td>
            <td>{{ base.name }}</td>
            <td>
              {{ tenantStore.name(base.tenant_id) }}
              <br><span style="font-size: 11px; color: #999; font-family: monospace;">{{ base.tenant_id }}</span>
            </td>
            <td>
              <span class="status-dot" :class="statusColor(base.status)"></span>
              {{ base.status }}
            </td>
            <td>{{ base.one_llm_mode ? 'Agent-Extract' : '普通' }}</td>
            <td>{{ base.memory_count ?? 0 }}</td>
            <td>{{ base.trait_count ?? 0 }}</td>
            <td>{{ formatDate(base.created_at) }}</td>
          </tr>
          <!-- Expanded row -->
          <tr v-if="expandedId === base.id">
            <td colspan="9" style="padding: 16px; background: #fafafa;">
              <div v-if="detailLoading" style="color: #999;">加载中...</div>
              <div v-else-if="detail">
                <div style="display: flex; gap: 16px; margin-bottom: 12px; font-size: 13px; color: #666;">
                  <span>数据库: {{ detail.database_id || '无' }}</span>
                  <span>嵌入模型: {{ detail.embedding_model }}</span>
                  <span v-if="detail.error" style="color: #e53e3e;">错误: {{ detail.error }}</span>
                </div>

                <!-- Recent memories -->
                <div v-if="detail.recent_memories && detail.recent_memories.memories" style="margin-bottom: 12px;">
                  <h4 style="font-size: 13px; font-weight: 600; margin: 0 0 8px;">最近记忆 ({{ detail.recent_memories.memories.length }})</h4>
                  <div v-for="m in detail.recent_memories.memories" :key="m.id"
                       style="padding: 6px 8px; border-left: 3px solid #d9d9d9; margin-bottom: 4px; font-size: 12px;">
                    <span style="display: inline-block; padding: 1px 6px; border-radius: 3px; font-size: 11px; margin-right: 6px;"
                          :style="`background: ${typeColor(m.memory_type)}20; color: ${typeColor(m.memory_type)};`">
                      {{ m.memory_type }}
                    </span>
                    {{ m.content?.substring(0, 100) }}{{ (m.content?.length ?? 0) > 100 ? '...' : '' }}
                    <span v-if="m.metadata?.source" style="color: #999; margin-left: 8px;">({{ m.metadata.source }})</span>
                  </div>
                  <div v-if="detail.recent_memories.memories.length === 0" style="color: #999; font-size: 12px;">暂无记忆</div>
                </div>

                <!-- Actions -->
                <div style="display: flex; gap: 8px;">
                  <button class="btn btn-sm" @click.stop="triggerDigest(base.id)">触发 Digest</button>
                  <button class="btn btn-sm btn-danger" @click.stop="deleteBase(base.id)">删除</button>
                </div>
              </div>
            </td>
          </tr>
        </template>
      </tbody>
    </table>

    <p v-if="bases.length === 0" style="text-align: center; color: #999; padding: 32px;">暂无记忆库</p>

    </div><!-- end tab: bases -->

    <!-- Tab: MCP 工具描述 -->
    <div v-if="activeTab === 'mcp'">
      <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px;">
        <p style="color: #666; font-size: 13px; margin: 0;">
          编辑 MCP 工具描述（YAML 格式）。保存后 MCP server 下次启动时自动加载，无需重新发布 pip 包。
        </p>
        <div style="display: flex; gap: 8px;">
          <button class="btn" @click="loadMcpDescriptions">刷新</button>
          <button class="btn" style="background: #1890ff; color: #fff;" @click="saveMcpDescriptions" :disabled="mcpSaving">
            {{ mcpSaving ? '保存中...' : '保存' }}
          </button>
        </div>
      </div>
      <div v-if="mcpUpdatedAt" style="font-size: 11px; color: #999; margin-bottom: 8px;">
        上次更新: {{ mcpUpdatedAt }}
      </div>
      <textarea v-model="mcpContent" style="width: 100%; min-height: 500px; font-family: monospace; font-size: 12px; padding: 12px; border: 1px solid #d9d9d9; border-radius: 4px; line-height: 1.6; background: #fafafa; resize: vertical;" spellcheck="false"></textarea>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { adminApi } from '../../api/admin'
import { useTenantStore } from '../../stores/tenants'

const tenantStore = useTenantStore()

const tabs = [
  { key: 'bases', label: '记忆库列表' },
  { key: 'mcp', label: 'MCP 工具描述' },
]
const activeTab = ref('bases')

// MCP descriptions
const mcpContent = ref('')
const mcpUpdatedAt = ref('')
const mcpSaving = ref(false)

async function loadMcpDescriptions() {
  try {
    const { data } = await adminApi.getMcpDescriptions()
    mcpContent.value = data.content || ''
    mcpUpdatedAt.value = data.updated_at ? new Date(data.updated_at).toLocaleString() : ''
  } catch (e) {
    console.error('Failed to load MCP descriptions', e)
  }
}

async function saveMcpDescriptions() {
  mcpSaving.value = true
  try {
    await adminApi.updateMcpDescriptions(mcpContent.value)
    mcpUpdatedAt.value = new Date().toLocaleString()
    alert('保存成功')
  } catch (e: any) {
    alert(`保存失败: ${e.message}`)
  } finally {
    mcpSaving.value = false
  }
}

const stats = ref<Record<string, any>>({})
const bases = ref<any[]>([])
const statusFilter = ref('')
const tenantFilter = ref('')
const selectedIds = ref<string[]>([])
const expandedId = ref<string | null>(null)
const detail = ref<any>(null)
const detailLoading = ref(false)

const errorCount = computed(() => stats.value.by_status?.ERROR ?? 0)
const allSelected = computed(() => bases.value.length > 0 && selectedIds.value.length === bases.value.length)

onMounted(() => {
  tenantStore.load()
  loadStats()
  loadBases()
})

watch(activeTab, (tab) => {
  if (tab === 'mcp' && !mcpContent.value) loadMcpDescriptions()
})

async function loadStats() {
  try {
    const { data } = await adminApi.memoryStats()
    stats.value = data
  } catch (e) {
    console.error('Failed to load memory stats', e)
  }
}

async function loadBases() {
  try {
    const params: Record<string, string> = {}
    if (statusFilter.value) params.status = statusFilter.value
    if (tenantFilter.value.trim()) params.tenant_id = tenantFilter.value.trim()
    const { data } = await adminApi.listMemoryBases(params)
    bases.value = data
    selectedIds.value = []
  } catch (e) {
    console.error('Failed to load memory bases', e)
  }
}

async function toggleExpand(id: string) {
  if (expandedId.value === id) {
    expandedId.value = null
    return
  }
  expandedId.value = id
  detail.value = null
  detailLoading.value = true
  try {
    const { data } = await adminApi.getMemoryBase(id)
    detail.value = data
  } catch (e) {
    console.error('Failed to load detail', e)
  } finally {
    detailLoading.value = false
  }
}

function toggleAll(e: Event) {
  const checked = (e.target as HTMLInputElement).checked
  selectedIds.value = checked ? bases.value.map(b => b.id) : []
}

async function deleteBase(id: string) {
  if (!window.confirm(`确定删除记忆库 ${id}？`)) return
  try {
    await adminApi.deleteMemoryBase(id)
    bases.value = bases.value.filter(b => b.id !== id)
    if (expandedId.value === id) expandedId.value = null
    loadStats()
  } catch (e) {
    console.error('Delete failed', e)
  }
}

async function batchDelete() {
  if (!window.confirm(`确定删除 ${selectedIds.value.length} 个记忆库？`)) return
  try {
    await adminApi.batchDeleteMemoryBases(selectedIds.value)
    bases.value = bases.value.filter(b => !selectedIds.value.includes(b.id))
    selectedIds.value = []
    loadStats()
  } catch (e) {
    console.error('Batch delete failed', e)
  }
}

async function triggerDigest(id: string) {
  try {
    const { data } = await adminApi.triggerDigest(id)
    alert(`Digest 完成: ${JSON.stringify(data)}`)
  } catch (e: any) {
    alert(`Digest 失败: ${e.message}`)
  }
}

function statusColor(status: string): string {
  if (status === 'READY') return 'green'
  if (status === 'PROVISIONING') return 'yellow'
  return 'red'
}

const TYPE_COLORS: Record<string, string> = {
  fact: '#1890ff', episode: '#722ed1', procedural: '#d48806',
  decision: '#13c2c2', rejection: '#f5222d', convention: '#52c41a',
}
function typeColor(type: string): string { return TYPE_COLORS[type] || '#999' }

function formatDate(d: string | null): string {
  if (!d) return '-'
  return new Date(d).toLocaleString()
}
</script>
