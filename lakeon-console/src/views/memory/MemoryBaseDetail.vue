<template>
  <div class="page-container">
    <!-- Breadcrumb -->
    <div class="breadcrumb" style="margin-bottom: 16px;">
      <router-link to="/memory" style="color: #0073e6; text-decoration: none;">记忆库</router-link>
      <span style="margin: 0 8px; color: #ccc;">/</span>
      <span style="color: #333;">{{ base?.name || '...' }}</span>
    </div>

    <div class="page-header">
      <h1 class="page-title">{{ base?.name || '加载中...' }}</h1>
    </div>

    <template v-if="base">
      <!-- Tabs -->
      <div class="tab-bar" style="margin-top: 20px; border-bottom: 1px solid #e5e5e5; display: flex; gap: 0;">
        <div v-for="tab in tabs" :key="tab.key"
             class="tab-item"
             :class="{ active: activeTab === tab.key }"
             @click="activeTab = tab.key">
          {{ tab.label }}
        </div>
      </div>

      <!-- Overview Tab -->
      <div v-if="activeTab === 'overview'" style="margin-top: 24px;">
        <div class="section-card" style="max-width: 600px;">
          <div class="section-header">概览</div>
          <div style="padding: 16px; display: grid; grid-template-columns: 120px 1fr; gap: 12px; font-size: 14px;">
            <span style="color: #999;">名称</span><span>{{ base.name }}</span>
            <span style="color: #999;">描述</span><span>{{ base.description || '-' }}</span>
            <span style="color: #999;">类型</span><span>{{ typeLabel }}</span>
            <span style="color: #999;">模式</span><span>{{ base.one_llm_mode ? 'Agent-Extract 模式' : '普通模式（服务端提取）' }}</span>
            <span style="color: #999;">Embedding 模型</span><span>{{ base.embedding_model || '-' }}</span>
            <span style="color: #999;">状态</span>
            <span>
              <span class="status-tag" :class="statusClass">{{ statusLabel }}</span>
            </span>
            <span style="color: #999;">记忆数</span><span>{{ stats?.total ?? 0 }}</span>
            <span style="color: #999;">特征数</span><span>{{ stats?.trait_count ?? 0 }}</span>
            <span style="color: #999;">创建时间</span><span>{{ base.created_at ? new Date(base.created_at).toLocaleString('zh-CN') : '-' }}</span>
            <span style="color: #999;">底层数据库</span>
            <span v-if="base.database_id">
              <router-link :to="'/database/' + base.database_id" style="color: #2563eb; text-decoration: none;">{{ base.database_id }}</router-link>
            </span>
            <span v-else>-</span>
          </div>
        </div>

        <!-- Type distribution -->
        <div v-if="stats && stats.total > 0" class="section-card" style="max-width: 600px; margin-top: 16px;">
          <div class="section-header">类型分布</div>
          <div style="padding: 16px; display: flex; flex-direction: column; gap: 8px;">
            <div v-for="(count, type) in stats.by_type" :key="type"
                 style="display: flex; align-items: center; gap: 12px;">
              <span style="width: 56px; font-size: 12px; text-align: right; color: #666;">{{ type }}</span>
              <div style="flex: 1; height: 16px; background: #f5f5f5; border-radius: 4px; overflow: hidden;">
                <div style="height: 100%; border-radius: 4px; background: #1890ff;"
                     :style="`width: ${stats.total ? (count / stats.total * 100) : 0}%`" />
              </div>
              <span style="width: 32px; font-size: 13px; font-weight: 500;">{{ count }}</span>
            </div>
          </div>
        </div>

        <!-- Error -->
        <div v-if="base.error" style="margin-top: 16px; padding: 12px; background: #fff2f0; border: 1px solid #ffccc7; border-radius: 6px; color: #e6393d; font-size: 13px;">
          <strong>错误信息：</strong>{{ base.error }}
        </div>
      </div>

      <!-- Settings tab -->
      <div v-if="activeTab === 'settings'" style="margin-top: 24px;">

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
            <label class="form-label">模式</label>
            <div style="font-size: 14px; color: #333;">
              {{ base.one_llm_mode ? 'Agent-Extract 模式 — 客户端提取记忆，零服务端 LLM 成本' : '普通模式 — 服务端自动提取记忆' }}
            </div>
          </div>
        </div>

        <!-- Claude Code / MCP integration -->
        <div v-if="base?.type === 'BUILTIN'" class="section-card" style="padding: 24px; margin-top: 16px;">
          <h3 style="font-size: 15px; font-weight: 600; margin-bottom: 20px; color: #333;">Claude Code 接入（MCP）</h3>

          <div class="form-group">
            <label class="form-label">1. 配置 MCP Server</label>
            <p style="font-size: 13px; color: #666; margin-bottom: 8px;">在项目根目录 <code>.mcp.json</code> 中添加：</p>
            <pre style="font-family: monospace; font-size: 13px; padding: 16px; background: #1e1e1e; border-radius: 6px; color: #d4d4d4; overflow-x: auto; margin: 0;">{
  "mcpServers": {
    "dbay": {
      "command": "uv",
      "args": ["run", "--directory", "/path/to/dbay-mcp", "server.py"],
      "env": {
        "DBAY_API_KEY": "your_api_key",
        "DBAY_MEMORY_BASE": "{{ base.id }}"
      }
    }
  }
}</pre>
          </div>

          <div class="form-group">
            <label class="form-label">2. 在 CLAUDE.md 中添加记忆指引</label>
            <pre style="font-family: monospace; font-size: 13px; padding: 16px; background: #1e1e1e; border-radius: 6px; color: #d4d4d4; overflow-x: auto; margin: 0; white-space: pre-wrap;"># Memory
- 当做出技术/架构决策时，调用 memory_record 记录
- 当用户明确拒绝某方案时，调用 memory_record 记录
- 当需要查询历史决策时，调用 memory_recall
- memory_record 返回 extraction_prompt 后，执行提取并调用 memory_record_extracted</pre>
          </div>

          <div class="form-group">
            <label class="form-label">提供的 MCP 工具</label>
            <div style="font-size: 13px; color: #666; line-height: 1.8;">
              <div><strong>memory_recall</strong> — 检索记忆（决策、排除项、约定等）</div>
              <div><strong>memory_record</strong> — 存入对话，返回提取 prompt</div>
              <div><strong>memory_record_extracted</strong> — 存入提取后的结构化记忆</div>
              <div style="margin-top: 4px; color: #999;">另有 knowledge_search / knowledge_upload 用于知识库</div>
            </div>
          </div>
        </div>

        <!-- REST API -->
        <div v-if="base?.type === 'BUILTIN'" class="section-card" style="padding: 24px; margin-top: 16px;">
          <h3 style="font-size: 15px; font-weight: 600; margin-bottom: 20px; color: #333;">REST API</h3>
          <div class="form-group">
            <label class="form-label">CLI</label>
            <pre style="font-family: monospace; font-size: 13px; padding: 16px; background: #1e1e1e; border-radius: 6px; color: #d4d4d4; overflow-x: auto; margin: 0;">pip install dbay-cli
dbay login
dbay mem ingest {{ base.id }} "我们决定用 asyncpg"
dbay mem recall {{ base.id }} "数据库选型"
dbay mem stats {{ base.id }}</pre>
          </div>
        </div>

        <!-- Supported clients -->
        <div v-if="base?.type === 'BUILTIN'" class="section-card" style="padding: 24px; margin-top: 16px;">
          <h3 style="font-size: 15px; font-weight: 600; margin-bottom: 16px; color: #333;">支持的客户端</h3>
          <p style="font-size: 13px; color: #666; margin-bottom: 16px;">DBay 记忆库通过 MCP 协议支持以下 AI 工具，配置方式相同——只需指向 dbay-mcp server。</p>
          <div style="display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 12px;">
            <div v-for="client in supportedClients" :key="client.name"
                 style="padding: 12px; border: 1px solid #e5e5e5; border-radius: 8px; font-size: 13px;">
              <div style="font-weight: 600; margin-bottom: 4px;">{{ client.name }}</div>
              <div style="color: #999; font-size: 12px;">{{ client.desc }}</div>
            </div>
          </div>
          <p style="font-size: 12px; color: #999; margin-top: 12px;">
            <router-link to="/integrations" style="color: #0073e6;">查看所有集成文档 →</router-link>
          </p>
        </div>

        <!-- MEM0 type -->
        <div v-else-if="base?.type === 'MEM0'" class="section-card" style="padding: 24px;">
          <h3 style="margin-bottom: 16px;">mem0 + DBay 集成指南</h3>
          <p style="color: #666; font-size: 14px; line-height: 1.6;">在您的 DBay 数据库上运行 mem0，享受 Serverless PostgreSQL 的便利。</p>
          <p style="color: #666; font-size: 14px; line-height: 1.6; margin-top: 12px;">
            <a href="https://docs.mem0.ai" target="_blank" style="color: #1890ff;">查看 mem0 官方文档 →</a>
          </p>
        </div>

        <!-- Other types -->
        <div v-else class="section-card" style="padding: 24px;">
          <h3 style="margin-bottom: 16px;">自定义记忆系统集成</h3>
          <p style="color: #666; font-size: 14px; line-height: 1.6;">您可以将任何支持 PostgreSQL 的记忆系统连接到 DBay 数据库。</p>
        </div>
      </div>
    </template>

    <!-- Loading state -->
    <div v-if="!base" style="padding: 60px 0; text-align: center; color: #999;">加载中...</div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { getMemoryBase, type MemoryBase, type MemoryStats, getMemoryStats } from '../../api/memory'

const route = useRoute()
const base = ref<MemoryBase | null>(null)
const activeTab = ref('overview')
const stats = ref<MemoryStats | null>(null)

const tabs = [
  { key: 'overview', label: '概览' },
  { key: 'settings', label: '接入' },
]

const supportedClients = [
  { name: 'Claude Code', desc: '通过 .mcp.json 配置' },
  { name: 'Claude Desktop', desc: '通过 claude_desktop_config.json' },
  { name: 'Cursor', desc: '通过 MCP 配置' },
  { name: 'Gemini CLI', desc: '通过 MCP 配置' },
  { name: 'ChatGPT', desc: 'Actions/GPTs 集成' },
  { name: 'OpenClaw', desc: '原生集成，自动记忆' },
]

const typeLabels: Record<string, string> = { BUILTIN: 'DBay记忆库', MEM0: 'mem0', HINDSIGHT: 'hindsight', CUSTOM: '自定义' }
const typeLabel = computed(() => typeLabels[base.value?.type || ''] || base.value?.type || '')

const statusMap: Record<string, { label: string; cls: string }> = {
  READY: { label: '就绪', cls: 'tag-green' },
  PROVISIONING: { label: '创建中', cls: 'tag-blue' },
  CREATING: { label: '创建中', cls: 'tag-blue' },
  FAILED: { label: '失败', cls: 'tag-red' },
  ERROR: { label: '异常', cls: 'tag-red' },
}
const statusLabel = computed(() => statusMap[base.value?.status || '']?.label || base.value?.status || '')
const statusClass = computed(() => statusMap[base.value?.status || '']?.cls || 'tag-gray')

let pollTimer: ReturnType<typeof setInterval> | null = null

async function loadBase() {
  const memId = route.params.memId as string
  const resp = await getMemoryBase(memId)
  base.value = resp.data
}

async function loadStats() {
  const memId = route.params.memId as string
  try {
    const resp = await getMemoryStats(memId)
    stats.value = resp.data
  } catch {}
}

onMounted(async () => {
  await loadBase()
  if (base.value?.status === 'READY') {
    loadStats()
  } else if (base.value?.status === 'PROVISIONING' || base.value?.status === 'CREATING') {
    // Poll until ready
    pollTimer = setInterval(async () => {
      await loadBase()
      if (base.value?.status === 'READY') {
        if (pollTimer) clearInterval(pollTimer)
        pollTimer = null
        loadStats()
      }
    }, 3000)
  }
})
</script>
