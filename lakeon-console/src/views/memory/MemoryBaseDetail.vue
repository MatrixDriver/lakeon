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
      <div class="tab-bar" style="margin-top: 20px;">
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
              <router-link :to="'/databases/' + base.database_id" style="color: #2563eb; text-decoration: none;">{{ base.database_id }}</router-link>
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

        <div v-if="base?.type === 'BUILTIN'">
          <!-- Client cards grid -->
          <div style="display: grid; grid-template-columns: repeat(auto-fill, minmax(160px, 1fr)); gap: 12px; margin-bottom: 24px;">
            <div v-for="client in clients" :key="client.id"
                 class="client-card" :class="{ active: expandedClient === client.id }"
                 @click="expandedClient = expandedClient === client.id ? null : client.id">
              <div style="font-weight: 600; font-size: 14px;">{{ client.name }}</div>
              <div style="color: #999; font-size: 12px; margin-top: 2px;">{{ client.short }}</div>
            </div>
          </div>

          <!-- Expanded client detail -->
          <div v-for="client in clients" :key="'detail-' + client.id">
            <div v-if="expandedClient === client.id" class="section-card" style="padding: 24px; margin-bottom: 24px;">
              <h3 style="font-size: 15px; font-weight: 600; margin-bottom: 16px; color: #333;">{{ client.name }} 接入指南</h3>

              <div v-for="(step, i) in client.steps" :key="i" class="form-group">
                <label class="form-label">{{ i + 1 }}. {{ step.title }}</label>
                <p v-if="step.desc" style="font-size: 13px; color: #666; margin-bottom: 8px;">{{ step.desc }}</p>
                <div v-if="step.code" style="position: relative;">
                  <pre class="code-block">{{ step.code }}</pre>
                  <button class="copy-btn" @click.stop="copyCode(step.code)">{{ copied === step.code ? '已复制 ✓' : '复制' }}</button>
                </div>
              </div>

              <!-- MCP tools (same for all clients) -->
              <div class="form-group" style="margin-top: 16px; padding-top: 16px; border-top: 1px solid #f0f0f0;">
                <label class="form-label">提供的 MCP 工具</label>
                <div style="font-size: 13px; color: #666; line-height: 1.8;">
                  <div><strong>memory_recall</strong> — 语义检索相关记忆（决策、约定、事实等）</div>
                  <div><strong>memory_ingest</strong> — 存储一条记忆，自动分类</div>
                  <div><strong>memory_list</strong> — 浏览记忆列表，可按类型过滤</div>
                  <div><strong>memory_delete</strong> — 删除指定记忆</div>
                  <div style="margin-top: 4px; color: #999;">另有 knowledge_search / knowledge_list 用于知识库</div>
                </div>
              </div>
            </div>
          </div>

          <!-- Basic info -->
          <div class="section-card" style="padding: 24px;">
            <h3 style="font-size: 15px; font-weight: 600; margin-bottom: 16px; color: #333;">接入信息</h3>
            <div style="display: grid; grid-template-columns: 120px 1fr; gap: 12px; font-size: 14px;">
              <span style="color: #999;">记忆库 ID</span>
              <span style="font-family: monospace;">{{ base.id }}</span>
              <span style="color: #999;">API Endpoint</span>
              <span style="font-family: monospace; word-break: break-all;">https://api.dbay.cloud:8443/api/v1/memory/bases/{{ base.id }}</span>
              <span style="color: #999;">模式</span>
              <span>{{ base.one_llm_mode ? 'Agent-Extract 模式' : '普通模式（服务端提取）' }}</span>
            </div>
          </div>

          <!-- CLI -->
          <div class="section-card" style="padding: 24px; margin-top: 16px;">
            <h3 style="font-size: 15px; font-weight: 600; margin-bottom: 16px; color: #333;">CLI</h3>
            <div style="position: relative;">
              <pre class="code-block">pip install dbay-cli
dbay login</pre>
              <button class="copy-btn" @click="copyCode('pip install dbay-cli\ndbay login')">{{ copied === 'pip install dbay-cli\ndbay login' ? '已复制 ✓' : '复制' }}</button>
            </div>
          </div>
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

const expandedClient = ref<string | null>(null)
const copied = ref<string | null>(null)

function copyCode(code: string) {
  navigator.clipboard.writeText(code)
  copied.value = code
  setTimeout(() => { copied.value = null }, 2000)
}

function uvxMcpJson() {
  return `{
  "mcpServers": {
    "dbay": {
      "command": "uvx",
      "args": ["dbay-mcp"]
    }
  }
}`
}

const loginStep = { title: '安装并登录（只需一次）', desc: '安装 dbay-cli（自动包含 MCP server），凭据保存在 ~/.dbay/config.json。', code: 'pip install dbay-cli\ndbay login' }

const clients = computed(() => {
  return [
    {
      id: 'claude-code', name: 'Claude Code', short: 'claude mcp add',
      steps: [
        loginStep,
        { title: '注册 MCP Server', desc: '在终端执行以下命令，全局生效：', code: 'claude mcp add --scope user dbay -- uvx dbay-mcp' },
        { title: '安装记忆 Skill（推荐）', desc: '在 Claude Code 中执行以下命令，安装后说"记住"时会自动调用 DBay 记忆库：', code: '/plugin marketplace add jackylk/dbay-plugins\n/plugin install memory' },
        { title: '验证', desc: '重启 Claude Code，输入 /mcp 查看 dbay server 是否已连接。', code: '' },
      ],
    },
    {
      id: 'claude-desktop', name: 'Claude Desktop', short: 'MCP via config.json',
      steps: [
        loginStep,
        { title: '打开配置文件', desc: 'macOS: ~/Library/Application Support/Claude/claude_desktop_config.json\nWindows: %APPDATA%\\Claude\\claude_desktop_config.json', code: '' },
        { title: '添加 dbay MCP server', desc: '在 mcpServers 中添加：', code: uvxMcpJson() },
        { title: '重启 Claude Desktop', desc: '关闭并重新打开 Claude Desktop，在工具图标中确认 dbay 已连接。', code: '' },
      ],
    },
    {
      id: 'cursor', name: 'Cursor', short: 'MCP via .cursor/',
      steps: [
        loginStep,
        { title: '创建 MCP 配置', desc: '在项目根目录创建 .cursor/mcp.json：', code: uvxMcpJson() },
        { title: '启用记忆提示（推荐）', desc: '', code: 'dbay setup cursor' },
        { title: '启用 MCP', desc: '打开 Cursor Settings → Features → 确保 MCP 已启用。', code: '' },
      ],
    },
    {
      id: 'gemini-cli', name: 'Gemini CLI', short: 'MCP via settings.json',
      steps: [
        loginStep,
        { title: '编辑 Gemini CLI 配置', desc: '编辑 ~/.gemini/settings.json，添加 MCP server：', code: uvxMcpJson() },
        { title: '启用记忆提示（推荐）', desc: '', code: 'dbay setup gemini' },
      ],
    },
    {
      id: 'openclaw', name: 'OpenClaw', short: '原生集成',
      steps: [
        { title: '无需额外配置', desc: 'OpenClaw 原生支持 DBay 记忆库，每次对话自动回忆、自动捕获、自动反思。', code: '' },
        { title: '关联记忆库', desc: '在 OpenClaw 设置中选择记忆库：', code: `记忆库 ID: ${base.value?.id || 'mem_xxx'}
API Endpoint: https://api.dbay.cloud:8443` },
      ],
    },
  ]
})

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

<style scoped>
.tab-bar {
  display: flex;
  gap: 0;
  border-bottom: 1px solid #e5e5e5;
}
.tab-item {
  padding: 10px 20px;
  font-size: 14px;
  color: #666;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.15s;
}
.tab-item:hover {
  color: #333;
}
.tab-item.active {
  color: #0073e6;
  font-weight: 600;
  border-bottom-color: #0073e6;
}
.client-card {
  padding: 14px 16px;
  border: 1px solid #e5e5e5;
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.15s;
}
.client-card:hover {
  border-color: #b3d4fc;
  background: #f0f7ff;
}
.client-card.active {
  border-color: #0073e6;
  background: #e6f0ff;
}
.code-block {
  font-family: monospace;
  font-size: 13px;
  padding: 16px;
  background: #1e1e1e;
  border-radius: 6px;
  color: #d4d4d4;
  overflow-x: auto;
  margin: 0;
  white-space: pre-wrap;
  line-height: 1.5;
}
.copy-btn {
  position: absolute;
  top: 8px;
  right: 8px;
  background: #333;
  color: #ccc;
  border: none;
  border-radius: 4px;
  padding: 4px 10px;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s;
}
.copy-btn:hover {
  background: #555;
  color: #fff;
}
</style>
