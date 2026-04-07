<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">Wiki Agent</h1>
    </div>

    <div class="tab-bar">
      <div class="tab-item" :class="{ active: activeTab === 'kbs' }" @click="activeTab = 'kbs'">知识库</div>
      <div class="tab-item" :class="{ active: activeTab === 'run-logs' }" @click="activeTab = 'run-logs'; loadRunLogs()">运行历史</div>
      <div class="tab-item" :class="{ active: activeTab === 'config' }" @click="activeTab = 'config'; loadWikiConfig()">Agent 配置</div>
      <div class="tab-item" :class="{ active: activeTab === 'connection' }" @click="activeTab = 'connection'">连接测试</div>
    </div>

    <!-- KB Tab -->
    <template v-if="activeTab === 'kbs'">
      <div class="action-toolbar">
        <button class="btn btn-default btn-small" @click="loadKbs">刷新</button>
      </div>
      <div class="table-wrapper">
        <table class="data-table">
          <thead>
            <tr>
              <th>知识库</th>
              <th>租户</th>
              <th>Wiki 页面数</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="kb in kbs" :key="kb.id">
              <td>
                <strong>{{ kb.name }}</strong>
                <br><span style="font-size: 11px; color: #999;">{{ kb.id }}</span>
              </td>
              <td style="font-size: 12px; font-family: monospace;">{{ kb.tenant_id }}</td>
              <td>
                <span v-if="kbWikiCounts[kb.id] != null">{{ kbWikiCounts[kb.id] }}</span>
                <span v-else style="color: #ccc;">-</span>
              </td>
              <td>
                <button class="btn btn-text btn-small" style="color: #1890ff;" @click="viewWikiPages(kb)">查看页面</button>
                <button class="btn btn-text btn-small" style="color: #722ed1;" @click="handleCurateWiki(kb)">整理</button>
                <button class="btn btn-text btn-small" style="color: #c25a3c;" @click="handleRebuildWiki(kb)">重建</button>
              </td>
            </tr>
            <tr v-if="kbLoading">
              <td colspan="4" style="text-align: center; padding: 32px; color: #94a3b8;">加载中...</td>
            </tr>
            <tr v-else-if="kbs.length === 0">
              <td colspan="4" class="empty-state">暂无知识库</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Wiki pages drawer -->
      <div v-if="selectedKb" style="margin-top: 20px; border: 1px solid #e5e5e5; border-radius: 8px; overflow: hidden;">
        <div style="display: flex; align-items: center; gap: 12px; padding: 12px 16px; background: #f9fafb; border-bottom: 1px solid #e5e5e5;">
          <strong style="font-size: 14px;">{{ selectedKb.name }} — Wiki 页面</strong>
          <span style="font-size: 12px; color: #999;">({{ wikiPages.length }} 页)</span>
          <button class="btn btn-default btn-small" @click="loadWikiPages(selectedKb.id, selectedKb.tenant_id)">刷新</button>
          <button class="btn btn-text btn-small" style="color: #999; margin-left: auto;" @click="selectedKb = null">关闭</button>
        </div>
        <div v-if="wikiPagesLoading" style="padding: 24px; text-align: center; color: #999;">加载中...</div>
        <table v-else-if="wikiPages.length > 0" class="data-table" style="margin: 0;">
          <thead>
            <tr>
              <th>文件名</th>
              <th>大小</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="page in wikiPages" :key="page.id">
              <td>{{ page.filename }}</td>
              <td>{{ page.size_bytes > 1024 ? ((page.size_bytes / 1024).toFixed(1) + ' KB') : (page.size_bytes + ' B') }}</td>
              <td>{{ page.created_at ? new Date(page.created_at).toLocaleString('zh-CN') : '-' }}</td>
              <td>
                <button class="btn btn-text btn-small" style="color: #e53e3e;" @click="handleDeleteWikiPage(page.id)">删除</button>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-else style="padding: 24px; text-align: center; color: #999; font-size: 13px;">暂无 Wiki 页面</div>
      </div>
    </template>

    <!-- Run Logs Tab -->
    <template v-if="activeTab === 'run-logs'">
      <div class="action-toolbar">
        <select class="form-select" v-model="logKbFilter" style="width: 200px;">
          <option value="">全部知识库</option>
          <option v-for="kb in kbs" :key="kb.id" :value="kb.id">{{ kb.name }}</option>
        </select>
        <button class="btn btn-default btn-small" @click="loadRunLogs">刷新</button>
      </div>
      <div class="table-wrapper">
        <table class="data-table">
          <thead>
            <tr>
              <th>时间</th>
              <th>知识库</th>
              <th>类型</th>
              <th>触发文档</th>
              <th style="text-align: center;">创建页</th>
              <th style="text-align: center;">更新页</th>
              <th style="text-align: center;">删除页</th>
              <th>耗时</th>
              <th>状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="entry in runLogs" :key="entry.id">
              <td style="white-space: nowrap; font-size: 12px;">{{ entry.created_at ? new Date(entry.created_at).toLocaleString('zh-CN') : '-' }}</td>
              <td style="font-size: 12px;">
                {{ kbNameById(entry.kb_id) }}
                <br><span style="font-size: 11px; color: #999; font-family: monospace;">{{ entry.kb_id }}</span>
              </td>
              <td>
                <span class="run-type-badge" :class="'run-type-' + entry.run_type">{{ entry.run_type }}</span>
              </td>
              <td style="font-size: 12px; max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">
                {{ entry.trigger_doc || '-' }}
              </td>
              <td style="text-align: center; color: #52c41a; font-weight: 600;">{{ entry.pages_created || 0 }}</td>
              <td style="text-align: center; color: #1890ff; font-weight: 600;">{{ entry.pages_updated || 0 }}</td>
              <td style="text-align: center; color: #f5222d; font-weight: 600;">{{ entry.pages_deleted || 0 }}</td>
              <td style="font-size: 12px;">{{ formatDuration(entry.duration_ms) }}</td>
              <td>
                <span class="status-badge" :class="'status-' + entry.status">{{ entry.status }}</span>
                <div v-if="entry.status === 'error' && entry.error_message" style="font-size: 11px; color: #f5222d; margin-top: 2px; max-width: 200px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;" :title="entry.error_message">
                  {{ entry.error_message }}
                </div>
              </td>
            </tr>
            <tr v-if="runLogsLoading">
              <td colspan="9" style="text-align: center; padding: 32px; color: #94a3b8;">加载中...</td>
            </tr>
            <tr v-else-if="runLogs.length === 0">
              <td colspan="9" class="empty-state">暂无运行记录</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- Agent Config Tab -->
    <template v-if="activeTab === 'config'">
      <div style="max-width: 800px; padding: 4px 0;">
        <!-- LLM Config -->
        <div style="background: #f9f9f9; border: 1px solid #e8e8e8; border-radius: 8px; padding: 16px; margin-bottom: 20px;">
          <div style="font-weight: 600; margin-bottom: 12px;">LLM 配置</div>
          <div style="display: grid; grid-template-columns: 100px 1fr; gap: 10px; align-items: center; font-size: 13px;">
            <span style="color: #666;">Model</span>
            <input v-model="wikiConfig.model" placeholder="deepseek-chat" style="padding: 6px 10px; border: 1px solid #d9d9d9; border-radius: 4px; font-size: 13px; max-width: 300px;" />
            <span style="color: #666;">Base URL</span>
            <input v-model="wikiConfig.base_url" placeholder="https://api.deepseek.com/v1" style="padding: 6px 10px; border: 1px solid #d9d9d9; border-radius: 4px; font-size: 13px; max-width: 400px;" />
          </div>
        </div>

        <!-- Prompt Tabs -->
        <div style="margin-bottom: 20px;">
          <div style="font-weight: 600; margin-bottom: 10px;">提示词配置</div>
          <div style="display: flex; gap: 0; margin-bottom: 8px;">
            <span v-for="pt in promptTabs" :key="pt.key"
              style="padding: 6px 14px; font-size: 12px; cursor: pointer; border: 1px solid #e0d8ce;"
              :style="{
                background: promptTab === pt.key ? '#c25a3c' : '#fff',
                color: promptTab === pt.key ? '#fff' : '#5a4a3a',
                borderRadius: pt.key === 'ingest' ? '4px 0 0 4px' : pt.key === 'answer' ? '0 4px 4px 0' : '0',
              }"
              @click="promptTab = pt.key">{{ pt.label }}</span>
          </div>
          <div style="font-size: 11px; color: #999; margin-bottom: 6px;">{{ currentPromptDesc }}</div>
          <textarea v-model="wikiConfig[currentPromptKey]"
            placeholder="（使用内置默认 prompt）"
            style="width: 100%; height: 280px; font-family: 'SF Mono', Monaco, Menlo, monospace; font-size: 12px; padding: 12px; border: 1px solid #d9d9d9; border-radius: 6px; resize: vertical; line-height: 1.6; box-sizing: border-box;" />
        </div>

        <!-- Curate Prompt -->
        <div style="margin-bottom: 20px;">
          <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 8px;">
            <span style="font-weight: 600;">Curate Prompt</span>
            <span style="font-size: 12px; color: #999;">定期整理/重组 Wiki 知识库</span>
          </div>
          <textarea v-model="wikiConfig.curate_prompt"
            placeholder="（使用内置默认 prompt）"
            style="width: 100%; height: 280px; font-family: 'SF Mono', Monaco, Menlo, monospace; font-size: 12px; padding: 12px; border: 1px solid #d9d9d9; border-radius: 6px; resize: vertical; line-height: 1.6; box-sizing: border-box;"
          ></textarea>
        </div>

        <div style="display: flex; gap: 8px; margin-bottom: 16px;">
          <button class="btn btn-primary" :disabled="wikiConfigSaving" @click="saveWikiConfig">
            {{ wikiConfigSaving ? '保存中...' : '保存配置' }}
          </button>
          <button class="btn btn-default" @click="loadWikiConfig">重新加载</button>
        </div>
        <div v-if="configSaveMsg" style="font-size: 13px;" :style="{ color: configSaveMsg.ok ? '#52c41a' : '#f5222d' }">
          {{ configSaveMsg.text }}
        </div>
      </div>
    </template>

    <!-- Connection Test Tab -->
    <template v-if="activeTab === 'connection'">
      <div style="max-width: 600px; padding: 4px 0;">
        <div style="background: #f9f9f9; border: 1px solid #e8e8e8; border-radius: 8px; padding: 20px; margin-bottom: 20px;">
          <div style="font-weight: 600; font-size: 15px; margin-bottom: 8px;">LLM 连接测试</div>
          <div style="font-size: 13px; color: #666; margin-bottom: 16px;">
            向配置的 LLM API 发送简单请求，验证连通性和响应时间。
          </div>
          <div style="display: flex; align-items: center; gap: 12px;">
            <button class="btn btn-primary" :disabled="testingConnection" @click="testConnection" style="min-width: 100px;">
              {{ testingConnection ? '测试中...' : '开始测试' }}
            </button>
            <span v-if="connectionResult" style="font-size: 13px;" :style="{ color: connectionResult.success ? '#52c41a' : '#f5222d' }">
              {{ connectionResult.success
                ? `连接成功 (${connectionResult.latency_ms}ms)`
                : `失败: ${connectionResult.error}` }}
            </span>
          </div>
        </div>

        <div v-if="connectionResult?.success" style="background: #f6ffed; border: 1px solid #b7eb8f; border-radius: 8px; padding: 16px;">
          <div style="font-weight: 600; color: #389e0d; margin-bottom: 8px;">响应详情</div>
          <div style="display: grid; grid-template-columns: 100px 1fr; gap: 6px; font-size: 13px;">
            <span style="color: #666;">延迟</span><span>{{ connectionResult.latency_ms }} ms</span>
            <span style="color: #666;">模型</span><span>{{ connectionResult.model || '-' }}</span>
            <span style="color: #666;">响应</span><span style="font-family: monospace;">{{ connectionResult.response || '-' }}</span>
          </div>
        </div>

        <div v-if="connectionResult && !connectionResult.success" style="background: #fff1f0; border: 1px solid #ffa39e; border-radius: 8px; padding: 16px;">
          <div style="font-weight: 600; color: #cf1322; margin-bottom: 8px;">连接失败</div>
          <div style="font-size: 13px; color: #a8071a; font-family: monospace;">{{ connectionResult.error }}</div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { adminApi } from '../../api/admin'

interface KnowledgeBase {
  id: string
  tenant_id: string
  name: string
  status: string
  type: string
}

interface WikiRunLog {
  id: string
  tenant_id: string
  kb_id: string
  run_type: string
  trigger_doc: string | null
  pages_created: number
  pages_updated: number
  pages_deleted: number
  duration_ms: number
  status: string
  error_message: string | null
  created_at: string
}

const activeTab = ref('kbs')

// KBs
const kbs = ref<KnowledgeBase[]>([])
const kbLoading = ref(false)
const kbWikiCounts = ref<Record<string, number>>({})

// Selected KB wiki pages
const selectedKb = ref<KnowledgeBase | null>(null)
const wikiPages = ref<any[]>([])
const wikiPagesLoading = ref(false)

// Run logs
const runLogs = ref<WikiRunLog[]>([])
const runLogsLoading = ref(false)
const logKbFilter = ref('')

// Wiki config
const wikiConfig = ref<Record<string, string>>({
  ingest_prompt: '', chat_routing_prompt: '', chat_answer_prompt: '',
  model: '', base_url: '', curate_prompt: ''
})
const wikiConfigSaving = ref(false)
const configSaveMsg = ref<{ ok: boolean; text: string } | null>(null)

// Connection test
const testingConnection = ref(false)
const connectionResult = ref<{
  success: boolean; latency_ms: number; model?: string; response?: string; error?: string
} | null>(null)

// Prompt tabs
const promptTab = ref('ingest')
const promptTabs = [
  { key: 'ingest', label: 'Ingest Prompt' },
  { key: 'routing', label: 'Chat Routing' },
  { key: 'answer', label: 'Chat Answer' },
]

const currentPromptKey = computed(() => {
  if (promptTab.value === 'ingest') return 'ingest_prompt'
  if (promptTab.value === 'routing') return 'chat_routing_prompt'
  return 'chat_answer_prompt'
})

const currentPromptDesc = computed(() => {
  if (promptTab.value === 'ingest') return '文档导入后生成/更新 Wiki 页面的系统提示词'
  if (promptTab.value === 'routing') return 'Query Router 路由策略：判断问题深度和相关页面'
  return '回答生成的系统提示词：控制回答风格和格式'
})

function kbNameById(kbId: string): string {
  return kbs.value.find(k => k.id === kbId)?.name || kbId
}

function formatDuration(ms: number | null | undefined): string {
  if (ms == null || ms === 0) return '-'
  if (ms < 1000) return ms + 'ms'
  if (ms < 60000) return (ms / 1000).toFixed(1) + 's'
  return (ms / 60000).toFixed(1) + 'min'
}

async function loadKbs() {
  kbLoading.value = true
  try {
    const resp = await adminApi.listKnowledgeBases()
    kbs.value = (resp.data as KnowledgeBase[]).filter(k => k.type === 'DOCUMENT')
    // Load wiki page counts for each KB
    for (const kb of kbs.value) {
      try {
        const pagesResp = await adminApi.listWikiPages(kb.id)
        const pages = (pagesResp.data as any[])
        kbWikiCounts.value[kb.id] = pages.filter((p: any) =>
          p.filename !== 'index.md' && p.filename !== 'log.md'
        ).length
      } catch {
        kbWikiCounts.value[kb.id] = 0
      }
    }
  } catch { /* ignore */ }
  kbLoading.value = false
}

async function viewWikiPages(kb: KnowledgeBase) {
  selectedKb.value = kb
  await loadWikiPages(kb.id, kb.tenant_id)
}

async function loadWikiPages(kbId: string, _tenantId: string) {
  wikiPagesLoading.value = true
  try {
    const resp = await adminApi.listWikiPages(kbId)
    wikiPages.value = resp.data || []
  } catch {
    wikiPages.value = []
  }
  wikiPagesLoading.value = false
}

async function handleDeleteWikiPage(docId: string) {
  if (!selectedKb.value) return
  if (!confirm('确认删除此 Wiki 页面？')) return
  await adminApi.adminDeleteWikiPage(selectedKb.value.id, docId)
  await loadWikiPages(selectedKb.value.id, selectedKb.value.tenant_id)
}

async function handleCurateWiki(kb: KnowledgeBase) {
  try {
    await adminApi.adminCurateWiki(kb.id)
    alert(`知识库 "${kb.name}" Wiki 整理已触发（后台运行）`)
  } catch (e: any) {
    alert('整理失败: ' + e.message)
  }
}

async function handleRebuildWiki(kb: KnowledgeBase) {
  if (!confirm(`确认全量重建 "${kb.name}" 的 Wiki？将清空现有页面并重新生成。`)) return
  try {
    await adminApi.adminRebuildWiki(kb.id)
    alert('Wiki 重建已触发')
    kbWikiCounts.value[kb.id] = 0
    if (selectedKb.value?.id === kb.id) {
      wikiPages.value = []
    }
  } catch (e: any) {
    alert('重建失败: ' + e.message)
  }
}

async function loadRunLogs() {
  runLogsLoading.value = true
  try {
    const resp = await adminApi.getWikiRunLogs(logKbFilter.value || undefined, 100)
    runLogs.value = resp.data || []
  } catch {
    runLogs.value = []
  }
  runLogsLoading.value = false
}

async function loadWikiConfig() {
  try {
    const { data } = await adminApi.getWikiConfig()
    wikiConfig.value = {
      ingest_prompt: data.ingest_prompt || '',
      chat_routing_prompt: data.chat_routing_prompt || '',
      chat_answer_prompt: data.chat_answer_prompt || '',
      curate_prompt: data.curate_prompt || '',
      model: data.model || '',
      base_url: data.base_url || ''
    }
  } catch (e: any) {
    console.warn('Failed to load wiki config', e)
  }
}

async function saveWikiConfig() {
  wikiConfigSaving.value = true
  configSaveMsg.value = null
  try {
    await adminApi.updateWikiConfig(wikiConfig.value)
    configSaveMsg.value = { ok: true, text: '配置已保存' }
  } catch (e: any) {
    configSaveMsg.value = { ok: false, text: '保存失败: ' + (e?.response?.data?.error || e.message || '未知错误') }
  } finally {
    wikiConfigSaving.value = false
  }
}

async function testConnection() {
  testingConnection.value = true
  connectionResult.value = null
  try {
    const { data } = await adminApi.testLlmConnection()
    connectionResult.value = data
  } catch (e: any) {
    connectionResult.value = { success: false, latency_ms: 0, error: e.message }
  } finally {
    testingConnection.value = false
  }
}

watch(logKbFilter, () => {
  loadRunLogs()
})

onMounted(() => {
  loadKbs()
})
</script>

<style scoped>
.page-header {
  margin-bottom: 20px;
}
.page-title {
  font-size: 22px;
  font-weight: 600;
  color: #1a2332;
  margin: 0;
}

.tab-bar {
  display: flex;
  border-bottom: 1px solid #e5e5e5;
  margin-bottom: 16px;
}
.tab-item {
  padding: 8px 16px;
  cursor: pointer;
  font-size: 14px;
  color: #666;
  border-bottom: 2px solid transparent;
  transition: all 0.15s;
}
.tab-item.active {
  color: #1890ff;
  border-bottom-color: #1890ff;
}

.action-toolbar {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
}

.table-wrapper {
  overflow-x: auto;
}

.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.data-table th {
  padding: 10px 12px;
  background: #f9fafb;
  border-bottom: 1px solid #e5e5e5;
  text-align: left;
  font-weight: 600;
  color: #555;
  white-space: nowrap;
}
.data-table td {
  padding: 10px 12px;
  border-bottom: 1px solid #f0f0f0;
  vertical-align: top;
}
.data-table tr:last-child td {
  border-bottom: none;
}
.data-table tr:hover td {
  background: #fafafa;
}
.empty-state {
  text-align: center;
  padding: 32px;
  color: #94a3b8;
}

.btn {
  padding: 6px 14px;
  border-radius: 4px;
  border: 1px solid #d9d9d9;
  background: #fff;
  cursor: pointer;
  font-size: 13px;
  transition: all 0.15s;
}
.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.btn-primary {
  background: #2a4d6a;
  color: #fff;
  border-color: #2a4d6a;
}
.btn-primary:hover:not(:disabled) {
  background: #1d3a52;
}
.btn-default {
  background: #fff;
  color: #333;
  border-color: #d9d9d9;
}
.btn-default:hover {
  border-color: #aaa;
}
.btn-text {
  background: none;
  border: none;
  padding: 2px 6px;
  color: #1890ff;
  cursor: pointer;
  font-size: 13px;
}
.btn-small {
  padding: 4px 10px;
  font-size: 12px;
}

.form-select {
  padding: 5px 10px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  font-size: 13px;
  background: #fff;
}

.run-type-badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 500;
}
.run-type-ingest {
  background: #e6f4ff;
  color: #0958d9;
}
.run-type-curate {
  background: #f9f0ff;
  color: #531dab;
}
.run-type-save_response {
  background: #fff7e6;
  color: #873800;
}
.run-type-rebuild {
  background: #fff1f0;
  color: #a8071a;
}

.status-badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 500;
}
.status-success {
  background: #f6ffed;
  color: #389e0d;
}
.status-error {
  background: #fff1f0;
  color: #cf1322;
}
</style>
