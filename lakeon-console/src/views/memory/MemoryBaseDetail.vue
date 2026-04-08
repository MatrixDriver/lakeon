<template>
  <div class="page-container">
    <!-- Breadcrumb + Title in one line -->
    <div style="display: flex; align-items: baseline; gap: 8px; margin-bottom: 4px;">
      <router-link to="/memory" style="color: #9a5b25; text-decoration: none; font-size: 13px;">记忆库</router-link>
      <span style="color: #ccc; font-size: 13px;">/</span>
      <h1 class="page-title" style="margin: 0; font-size: 18px;">{{ base?.name || '...' }}</h1>
    </div>

    <template v-if="base">
      <!-- Tabs -->
      <div class="tab-bar" style="margin-top: 8px;">
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
            <template v-if="base.encrypted">
              <span style="color: #999;">加密</span>
              <span><span style="display: inline-block; padding: 1px 8px; border-radius: 4px; font-size: 12px; background: #f0f0f0; color: #666;">&#x1f512; 端到端加密</span></span>
              <span style="color: #999;">Embedding 维度</span><span>{{ base.embedding_dim || '-' }}</span>
            </template>
            <span style="color: #999;">状态</span>
            <span>
              <span class="status-tag" :class="statusClass">{{ statusLabel }}</span>
            </span>
            <span style="color: #999;">存储大小</span><span>{{ storageDisplay }}</span>
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

      <!-- Messages tab -->
      <div v-if="activeTab === 'messages'" style="margin-top: 24px;">
        <p v-if="msgLoading" style="text-align: center; color: #999; padding: 40px 0;">加载中...</p>
        <p v-else-if="messages.length === 0" style="text-align: center; color: #999; padding: 40px 0;">暂无消息</p>

        <div v-else class="table-wrapper">
          <table class="data-table">
            <thead>
              <tr>
                <th style="width: 160px;">时间</th>
                <th style="width: 80px;">角色</th>
                <th style="width: 80px;">来源</th>
                <th>内容</th>
                <th style="width: 60px;">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="msg in messages" :key="msg.id" style="cursor: pointer;" @click="openMsgDetail(msg.id)">
                <td style="color: #999; font-size: 13px;">{{ new Date(msg.created_at).toLocaleString('zh-CN') }}</td>
                <td>
                  <span style="display: inline-block; padding: 1px 6px; border-radius: 3px; font-size: 11px;"
                        :style="msg.role === 'user' ? 'background:#fdf5ed;color:#1565c0' : 'background:#f3e5f5;color:#7b1fa2'">
                    {{ msg.role }}
                  </span>
                </td>
                <td>
                  <span v-if="msg.source" style="display: inline-block; padding: 1px 6px; border-radius: 3px; font-size: 11px; background:#f0f5ff; color:#1890ff;">
                    {{ msg.source }}
                  </span>
                  <span v-else style="color: #ccc;">-</span>
                </td>
                <td style="font-size: 13px; color: #333; max-width: 400px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">
                  {{ msg.content_preview || msg.content }}
                </td>
                <td>
                  <button class="btn btn-text btn-small" style="color: #9a5b25;" @click.stop="openMsgDetail(msg.id)">详情</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- Pagination -->
        <div v-if="msgTotal > MSG_PAGE_SIZE" style="display: flex; justify-content: center; gap: 8px; margin-top: 16px;">
          <button class="btn btn-sm" :disabled="msgPage <= 1" @click="msgPage--; loadMessages()">上一页</button>
          <span style="line-height: 32px; font-size: 13px; color: #666;">
            {{ (msgPage - 1) * MSG_PAGE_SIZE + 1 }}-{{ Math.min(msgPage * MSG_PAGE_SIZE, msgTotal) }} / {{ msgTotal }}
          </span>
          <button class="btn btn-sm" :disabled="msgPage * MSG_PAGE_SIZE >= msgTotal" @click="msgPage++; loadMessages()">下一页</button>
        </div>

        <!-- Detail side panel -->
        <div v-if="msgDetailVisible" class="panel-overlay" @click="msgDetailVisible = false"></div>
        <transition name="slide">
          <div v-if="msgDetailVisible" class="detail-panel">
            <div class="detail-header">
              <span style="font-size: 16px; font-weight: 600;">消息详情</span>
              <button class="detail-close" @click="msgDetailVisible = false">&times;</button>
            </div>

            <div v-if="msgDetailLoading" style="padding: 40px; text-align: center; color: #999;">加载中...</div>
            <div v-else-if="msgDetail" class="detail-body">
              <div class="detail-section">
                <div class="detail-row"><span class="detail-label">时间</span><span>{{ new Date(msgDetail.message.created_at).toLocaleString('zh-CN') }}</span></div>
                <div class="detail-row"><span class="detail-label">角色</span><span>{{ msgDetail.message.role }}</span></div>
                <div class="detail-row"><span class="detail-label">来源</span><span>{{ msgDetail.message.source || '-' }}</span></div>
                <div class="detail-row"><span class="detail-label">ID</span><span style="font-family: monospace; font-size: 12px;">{{ msgDetail.message.id }}</span></div>
              </div>
              <div class="detail-section">
                <div style="font-size: 13px; font-weight: 600; margin-bottom: 8px;">消息内容</div>
                <pre class="detail-code">{{ msgDetail.message.content }}</pre>
              </div>
              <div class="detail-section">
                <div style="font-size: 13px; font-weight: 600; margin-bottom: 8px;">
                  提取的记忆 ({{ msgDetail.extracted_memories.length }})
                </div>
                <div v-if="msgDetail.extracted_memories.length === 0" style="color: #999; font-size: 13px;">
                  暂无（可能正在提取中）
                </div>
                <div v-for="mem in msgDetail.extracted_memories" :key="mem.id"
                     style="padding: 10px 12px; background: #fafafa; border-radius: 6px; margin-bottom: 8px; font-size: 13px;">
                  <div style="display: flex; align-items: center; gap: 6px; margin-bottom: 4px;">
                    <span style="padding: 1px 6px; border-radius: 3px; font-size: 11px; background: #e8f5e9; color: #2e7d32;">
                      {{ mem.memory_type }}
                    </span>
                    <span style="color: #999; font-size: 11px;">重要性 {{ Math.round(mem.importance * 100) }}%</span>
                  </div>
                  <div style="color: #333; line-height: 1.5;">{{ mem.content }}</div>
                </div>
              </div>
            </div>
          </div>
        </transition>
      </div>

      <!-- Usage Stats tab -->
      <div v-if="activeTab === 'usage'" style="margin-top: 24px;">
        <p v-if="usageLoading" style="text-align: center; color: #999; padding: 40px 0;">加载中...</p>

        <template v-else-if="usageStats">
          <div style="display: flex; gap: 16px; margin-bottom: 32px;">
            <div class="section-card" style="flex: 1; padding: 20px; text-align: center;">
              <div style="font-size: 32px; font-weight: 600; color: #1890ff;">{{ usageStats.total }}</div>
              <div style="font-size: 13px; color: #999; margin-top: 4px;">总记忆数</div>
            </div>
            <div class="section-card" style="flex: 1; padding: 20px; text-align: center;">
              <div style="font-size: 32px; font-weight: 600; color: #722ed1;">{{ usageStats.trait_count }}</div>
              <div style="font-size: 13px; color: #999; margin-top: 4px;">Trait 数</div>
            </div>
          </div>

          <div class="section-card" style="padding: 20px;">
            <h3 style="font-size: 15px; font-weight: 600; margin: 0 0 16px;">类型分布</h3>
            <div v-if="sortedTypes.length === 0" style="text-align: center; color: #999; padding: 20px;">暂无数据</div>
            <div v-else style="display: flex; flex-direction: column; gap: 10px;">
              <div v-for="item in sortedTypes" :key="item.type" style="display: flex; align-items: center; gap: 12px;">
                <span style="width: 56px; font-size: 12px; text-align: right;"
                      :style="`color: ${MEMORY_TYPE_COLORS[item.type]?.text || '#666'};`">
                  {{ MEMORY_TYPE_LABELS[item.type] || item.type }}
                </span>
                <div style="flex: 1; height: 20px; background: #f5f5f5; border-radius: 4px; overflow: hidden;">
                  <div style="height: 100%; border-radius: 4px; transition: width 0.5s;"
                       :style="`width: ${maxCount ? (item.count / maxCount * 100) : 0}%; background: ${MEMORY_TYPE_COLORS[item.type]?.text || '#999'};`" />
                </div>
                <span style="width: 32px; font-size: 13px; color: #333; font-weight: 500;">{{ item.count }}</span>
              </div>
            </div>
          </div>
        </template>
      </div>

      <!-- Settings tab -->
      <div v-if="activeTab === 'settings'" style="margin-top: 24px;">

        <!-- Encryption info card -->
        <div v-if="base?.encrypted" class="section-card" style="padding: 20px 24px; margin-bottom: 24px; border-left: 3px solid #8c7a68;">
          <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 12px;">
            <span style="font-size: 18px;">&#x1f512;</span>
            <h3 style="font-size: 15px; font-weight: 600; margin: 0; color: #333;">端到端加密</h3>
          </div>
          <p style="font-size: 13px; color: #666; line-height: 1.8; margin: 0 0 12px;">
            此记忆库已启用客户端加密。记忆内容在本地加密后上传，服务端无法查看明文。
          </p>
          <div style="font-size: 13px; color: #555; line-height: 2;">
            <div><strong>创建加密记忆库：</strong><code style="background: #f5f3f0; padding: 1px 6px; border-radius: 3px;">dbay mem create --encrypted</code></div>
            <div><strong>密码存储：</strong><code style="background: #f5f3f0; padding: 1px 6px; border-radius: 3px;">~/.dbay/secret</code></div>
            <div><strong>密钥配置：</strong><code style="background: #f5f3f0; padding: 1px 6px; border-radius: 3px;">~/.dbay/encrypted_bases.json</code>（可安全传播）</div>
            <div><strong>MCP 使用：</strong>自动加解密，无需额外操作</div>
          </div>
          <div style="margin-top: 12px; padding: 10px 14px; background: #faf8f5; border-radius: 6px; font-size: 12px; color: #8c7a68; line-height: 1.6;">
            <strong>跨设备使用：</strong>复制 <code>~/.dbay/encrypted_bases.json</code> 到新设备，并创建 <code>~/.dbay/secret</code> 写入密码即可。
          </div>
        </div>

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
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { getMemoryBase, type MemoryBase, type MemoryStats, getMemoryStats, listRawMessages, getRawMessage, type RawMessage, type RawMessageDetail } from '../../api/memory'
import { MEMORY_TYPE_COLORS, MEMORY_TYPE_LABELS } from '../../constants/memory'
import { databaseApi } from '../../api/database'

const route = useRoute()
const base = ref<MemoryBase | null>(null)
const _validTabs = ['overview', 'messages', 'usage', 'settings']
const _hashTab = window.location.hash.replace('#', '')
const activeTab = ref(_validTabs.includes(_hashTab) ? _hashTab : 'overview')
watch(activeTab, (tab) => { window.location.hash = tab })
const stats = ref<MemoryStats | null>(null)
const storageDisplay = ref('-')

const tabs = [
  { key: 'overview', label: '概览' },
  { key: 'messages', label: '消息日志' },
  { key: 'usage', label: '用量统计' },
  { key: 'settings', label: '接入' },
]

// ── Messages tab state ──
const MSG_PAGE_SIZE = 20
const messages = ref<RawMessage[]>([])
const msgTotal = ref(0)
const msgPage = ref(1)
const msgLoading = ref(false)

const msgDetailVisible = ref(false)
const msgDetailLoading = ref(false)
const msgDetail = ref<RawMessageDetail | null>(null)

async function loadMessages() {
  const memId = route.params.memId as string
  msgLoading.value = true
  try {
    const { data } = await listRawMessages(memId, {
      offset: (msgPage.value - 1) * MSG_PAGE_SIZE,
      limit: MSG_PAGE_SIZE,
    })
    messages.value = data.messages
    msgTotal.value = data.total
  } catch (e) {
    console.error('Failed to load messages', e)
    messages.value = []
    msgTotal.value = 0
  } finally {
    msgLoading.value = false
  }
}

async function openMsgDetail(messageId: string) {
  const memId = route.params.memId as string
  msgDetailVisible.value = true
  msgDetailLoading.value = true
  msgDetail.value = null
  try {
    const { data } = await getRawMessage(memId, messageId)
    msgDetail.value = data
  } catch (e) {
    console.error('Failed to load message detail', e)
  } finally {
    msgDetailLoading.value = false
  }
}

// ── Usage stats tab state ──
const usageStats = ref<MemoryStats | null>(null)
const usageLoading = ref(false)

async function loadUsageStats() {
  const memId = route.params.memId as string
  usageLoading.value = true
  try {
    const { data } = await getMemoryStats(memId)
    usageStats.value = data
  } catch (e) {
    console.error('Failed to load usage stats', e)
    usageStats.value = null
  } finally {
    usageLoading.value = false
  }
}

const sortedTypes = computed(() => {
  if (!usageStats.value?.by_type) return []
  return Object.entries(usageStats.value.by_type)
    .map(([type, count]) => ({ type, count }))
    .sort((a, b) => b.count - a.count)
})

const maxCount = computed(() => {
  if (sortedTypes.value.length === 0) return 0
  return sortedTypes.value[0]!.count
})

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
        { title: '配置自动召回（推荐）', desc: '每次新会话启动时自动加载你的惯例和决策，Claude 从第一句话就知道你的做事规矩。\n\n第1步：创建 Hook 脚本：', code: `cat > ~/.claude/hooks/session-recall.sh << 'SCRIPT'
#!/usr/bin/env bash
set -euo pipefail
CONFIG="$HOME/.dbay/config.json"
[ -f "$CONFIG" ] || exit 0
MB=$(python3 -c "import json; print(json.load(open('$CONFIG')).get('memory_base',''))" 2>/dev/null) || exit 0
[ -n "$MB" ] || exit 0
M=$(dbay mem recall "$MB" "conventions decisions preferences feedback procedures" --limit 20 2>/dev/null) || exit 0
[ -n "$M" ] || exit 0
python3 -c "
import json, sys
m = sys.stdin.read().strip()
if not m: sys.exit(0)
print(json.dumps({'hookSpecificOutput':{'hookEventName':'SessionStart','additionalContext':'## DBay Memory (auto)\\\\n'+m}}))
" <<< "$M"
SCRIPT
chmod +x ~/.claude/hooks/session-recall.sh` },
        { title: '', desc: '第2步：在 ~/.claude/settings.json 中添加 Hook（合并到已有配置中）：', code: `// 在 settings.json 中添加 hooks 字段：
{
  "hooks": {
    "SessionStart": [{
      "hooks": [{
        "type": "command",
        "command": "bash ~/.claude/hooks/session-recall.sh",
        "timeout": 10
      }]
    }]
  }
}` },
        { title: '验证', desc: '重启 Claude Code，输入 /mcp 查看 dbay server 是否已连接。如果配置了自动召回，新会话启动时会显示 "Recalling dbay memories..."。', code: '' },
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
  if (resp.data.database_id) {
    try {
      const dbResp = await databaseApi.get(resp.data.database_id)
      const gb = dbResp.data.storage_used_gb
      if (gb < 0.01) {
        storageDisplay.value = `${Math.round(gb * 1024 * 1024)} KB`
      } else if (gb < 1) {
        storageDisplay.value = `${(gb * 1024).toFixed(1)} MB`
      } else {
        storageDisplay.value = `${gb.toFixed(2)} GB`
      }
    } catch {}
  }
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
    loadMessages()
    loadUsageStats()
  } else if (base.value?.status === 'PROVISIONING' || base.value?.status === 'CREATING') {
    // Poll until ready
    pollTimer = setInterval(async () => {
      await loadBase()
      if (base.value?.status === 'READY') {
        if (pollTimer) clearInterval(pollTimer)
        pollTimer = null
        loadStats()
        loadMessages()
        loadUsageStats()
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
  color: #9a5b25;
  font-weight: 600;
  border-bottom-color: #c67d3a;
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
  border-color: #c67d3a;
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
/* Message detail panel */
.panel-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.15);
  z-index: 999;
}
.detail-panel {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  width: 480px;
  background: #fff;
  box-shadow: -4px 0 16px rgba(0, 0, 0, 0.08);
  z-index: 1000;
  display: flex;
  flex-direction: column;
}
.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 24px 16px;
  border-bottom: 1px solid #f0f0f0;
}
.detail-close {
  background: none;
  border: none;
  font-size: 22px;
  color: #999;
  cursor: pointer;
  padding: 0 4px;
  line-height: 1;
}
.detail-close:hover {
  color: #333;
}
.detail-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px 24px;
}
.detail-section {
  margin-bottom: 20px;
}
.detail-row {
  display: flex;
  gap: 16px;
  padding: 8px 0;
  border-bottom: 1px solid #f5f5f5;
  font-size: 13px;
}
.detail-label {
  color: #999;
  min-width: 60px;
  flex-shrink: 0;
}
.detail-code {
  background: #f7f8fa;
  border: 1px solid #e8e8e8;
  border-radius: 4px;
  padding: 12px 16px;
  font-size: 13px;
  font-family: monospace;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 300px;
  overflow-y: auto;
  margin: 0;
  color: #333;
  line-height: 1.6;
}
.slide-enter-active,
.slide-leave-active {
  transition: transform 0.2s ease;
}
.slide-enter-from,
.slide-leave-to {
  transform: translateX(100%);
}
@media (max-width: 768px) {
  .detail-panel {
    width: 100%;
  }
}
</style>
