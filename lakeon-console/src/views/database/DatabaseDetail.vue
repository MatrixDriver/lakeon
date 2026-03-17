<template>
  <div class="page-db-detail" v-if="database">
    <div v-if="copyTip" class="copy-toast">{{ copyTip }}</div>
    <div class="breadcrumb">
      <router-link to="/databases" class="breadcrumb-link">数据库实例</router-link>
      <span class="breadcrumb-sep">/</span>
      <span class="breadcrumb-item active">{{ database.name }}</span>
    </div>

    <!-- Resource Summary -->
    <div class="summary-card">
      <div class="summary-top">
        <div class="summary-main">
          <h2 class="db-title">{{ database.name }}</h2>
          <div class="summary-meta">
            <span class="meta-item">ID: {{ database.id }}</span>
            <span class="meta-item">
              <span class="status-dot" :class="statusClass(database.status)"></span>
              {{ statusText(database.status) }}
            </span>
            <span class="meta-item">规格: {{ database.compute_size }}</span>
            <span class="meta-item">挂起超时: {{ database.suspend_timeout }}</span>
            <span class="meta-item" v-if="database.status === 'RUNNING'">连接数: {{ database.active_connections || 0 }}</span>
          </div>
        </div>
        <div class="summary-actions">
          <router-link
            :to="`/databases/${database.id}/manager`"
            class="btn btn-primary"
          >管理数据库</router-link>
          <button
            v-if="database.status === 'RUNNING'"
            class="btn btn-default"
            :disabled="actionLoading"
            @click="handleSuspend"
          >挂起</button>
          <button
            v-if="database.status === 'SUSPENDED'"
            class="btn btn-primary"
            :disabled="actionLoading"
            @click="handleResume"
          >恢复</button>
        </div>
      </div>

      <!-- Serverless tip -->
      <div v-if="database.status === 'SUSPENDED'" class="serverless-tip">
        数据库已挂起，收到连接请求时将自动唤醒。热唤醒 &lt; 1秒，冷启动约 5-15秒。数据始终安全保留。
        <router-link to="/docs#serverless" class="tip-link">了解详情</router-link>
      </div>

      <div class="summary-bottom">
        <div class="summary-field">
          <span class="field-label">连接地址</span>
          <div class="field-value-row">
            <code class="uri-text">{{ database.connection_uri || '-' }}</code>
            <button
              v-if="database.connection_uri"
              class="copy-btn"
              :class="{ 'copy-btn-ok': copiedField === 'uri' }"
              @click="handleCopy(database.connection_uri, 'uri')"
            >{{ copiedField === 'uri' ? '已复制 ✓' : '复制' }}</button>
          </div>
        </div>
        <div class="summary-field">
          <span class="field-label">存储用量</span>
          <span class="field-value">{{ database.storage_used_gb.toFixed(2) }} / {{ database.storage_limit_gb }} GB</span>
        </div>
      </div>
    </div>

    <!-- Tabs -->
    <div class="tabs-wrapper">
      <div class="tab-header">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          class="tab-btn"
          :class="{ active: activeTab === tab.key }"
          @click="activeTab = tab.key"
        >{{ tab.label }}</button>
      </div>

      <!-- Tab 1: Basic Info -->
      <div v-if="activeTab === 'info'" class="tab-content">
        <div class="info-card">
          <h4 class="info-title">连接信息</h4>
          <div class="info-grid">
            <div class="info-row" v-for="field in connectionFields" :key="field.label">
              <span class="info-label">{{ field.label }}</span>
              <div class="info-value-row">
                <code>{{ field.value }}</code>
                <button
                  class="copy-btn"
                  :class="{ 'copy-btn-ok': copiedField === field.label }"
                  @click="handleCopy(field.value, field.label)"
                >{{ copiedField === field.label ? '已复制 ✓' : '复制' }}</button>
              </div>
            </div>
            <div class="info-row">
              <span class="info-label">密码</span>
              <div class="info-value-row" v-if="newPassword">
                <code class="password-value">{{ newPassword }}</code>
                <button
                  class="copy-btn"
                  :class="{ 'copy-btn-ok': copiedField === 'password' }"
                  @click="handleCopy(newPassword!, 'password')"
                >{{ copiedField === 'password' ? '已复制 ✓' : '复制' }}</button>
              </div>
              <div class="info-value-row" v-else>
                <code class="password-masked">••••••••</code>
                <button class="btn btn-small btn-default" :disabled="resettingPassword" @click="handleResetPassword">
                  {{ resettingPassword ? '重置中...' : '重置密码' }}
                </button>
              </div>
            </div>
          </div>
          <div v-if="newPassword" class="password-warning">
            请立即复制密码，刷新页面后将无法再次查看。
          </div>
        </div>

        <!-- Quick links to top-level pages -->
        <div class="quick-links">
          <router-link :to="`/import?db=${database.id}`" class="quick-link">
            <span class="quick-link-icon">→</span> 导入数据到此数据库
          </router-link>
          <router-link :to="`/logs?db=${database.id}`" class="quick-link">
            <span class="quick-link-icon">→</span> 查看操作记录
          </router-link>
          <router-link :to="`/sql?db=${database.id}`" class="quick-link">
            <span class="quick-link-icon">→</span> 打开 SQL 编辑器
          </router-link>
          <router-link :to="`/timetravel?db=${database.id}`" class="quick-link">
            <span class="quick-link-icon">→</span> 时间旅行
          </router-link>
        </div>
      </div>

      <!-- Tab: Users -->
      <div v-if="activeTab === 'users'" class="tab-content">
        <div class="tab-toolbar">
          <button class="btn btn-primary btn-small" @click="showCreateUserDialog = true">添加用户</button>
        </div>
        <p class="tab-tip">管理数据库的访问用户。Owner 拥有全部权限，Admin 可管理表结构，Writer 可读写数据，Reader 仅可查询。密码重置后请立即保存，不会再次显示。<router-link to="/docs#users" class="tip-link">了解更多</router-link></p>
        <div class="section-card">
          <div class="table-wrapper">
            <table class="data-table" v-if="dbUsers.length > 0">
              <thead>
                <tr>
                  <th>用户名</th>
                  <th>角色</th>
                  <th>所有者</th>
                  <th>创建时间</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="u in dbUsers" :key="u.id">
                  <td>{{ u.username }}</td>
                  <td>
                    <span class="status-tag" :class="roleTagClass(u.role)">{{ u.role }}</span>
                  </td>
                  <td>{{ u.is_owner ? '是' : '-' }}</td>
                  <td>{{ formatDate(u.created_at) }}</td>
                  <td class="action-cell">
                    <template v-if="!u.is_owner">
                      <button class="btn btn-small btn-text" @click="openChangeRoleDialog(u)">修改角色</button>
                      <button class="btn btn-small btn-text" @click="handleResetUserPassword(u)">重置密码</button>
                      <button class="btn btn-small btn-text btn-danger-text" @click="handleDeleteUser(u)">删除</button>
                    </template>
                    <span v-else class="text-muted">-</span>
                  </td>
                </tr>
              </tbody>
            </table>
            <div v-else class="empty-state">
              <p v-if="usersLoading">加载中...</p>
              <p v-else>暂无用户</p>
            </div>
          </div>
        </div>

        <!-- Change Role Dialog -->
        <div v-if="showRoleDialog" class="dialog-overlay" @click.self="showRoleDialog = false">
          <div class="dialog-box dialog-confirm">
            <div class="dialog-header">
              <h3>修改角色</h3>
              <button class="dialog-close" @click="showRoleDialog = false">&times;</button>
            </div>
            <div class="dialog-body">
              <p>用户: <strong>{{ roleEditUser?.username }}</strong></p>
              <div class="form-group">
                <label class="form-label">新角色</label>
                <select v-model="newRole" class="form-input form-select">
                  <option value="ADMIN">Admin</option>
                  <option value="WRITER">Writer</option>
                  <option value="READER">Reader</option>
                </select>
              </div>
            </div>
            <div class="dialog-footer">
              <button class="btn btn-default" @click="showRoleDialog = false">取消</button>
              <button
                class="btn btn-primary"
                :disabled="updatingRole"
                @click="handleUpdateRole"
              >{{ updatingRole ? '更新中...' : '确定' }}</button>
            </div>
          </div>
        </div>

        <!-- Reset Password Result Dialog -->
        <div v-if="resetUserPasswordResult" class="dialog-overlay" @click.self="resetUserPasswordResult = ''">
          <div class="dialog-box dialog-confirm">
            <div class="dialog-header">
              <h3>密码已重置</h3>
              <button class="dialog-close" @click="resetUserPasswordResult = ''">&times;</button>
            </div>
            <div class="dialog-body">
              <div class="form-group">
                <label class="form-label">新密码</label>
                <div style="display: flex; align-items: center; gap: 8px;">
                  <code class="password-value" style="flex: 1; padding: 6px 10px; background: #f2f3f5; border-radius: 2px;">{{ resetUserPasswordResult }}</code>
                  <button
                    class="copy-btn"
                    :class="{ 'copy-btn-ok': copiedField === 'user-reset-pw' }"
                    @click="handleCopy(resetUserPasswordResult, 'user-reset-pw')"
                  >{{ copiedField === 'user-reset-pw' ? '已复制' : '复制' }}</button>
                </div>
              </div>
              <div class="password-warning">
                请立即复制密码，关闭对话框后将无法再次查看。
              </div>
            </div>
            <div class="dialog-footer">
              <button class="btn btn-primary" @click="resetUserPasswordResult = ''">确定</button>
            </div>
          </div>
        </div>

        <CreateUserDialog
          :visible="showCreateUserDialog"
          :dbId="dbId"
          @close="showCreateUserDialog = false"
          @created="handleUserCreated"
        />
      </div>

      <!-- Tab: Extensions -->
      <div v-if="activeTab === 'extensions'" class="tab-content">
        <p class="tab-tip">管理数据库扩展。启用扩展后即可在 SQL 中使用其提供的功能。禁用扩展会级联删除其关联的数据类型和函数，请谨慎操作。</p>
        <div class="ext-toolbar">
          <input v-model="extSearch" class="form-input ext-search" placeholder="搜索扩展..." />
          <select v-model="extCategoryFilter" class="form-input form-select ext-category-select">
            <option value="">全部分类</option>
            <option v-for="cat in extCategories" :key="cat" :value="cat">{{ cat }}</option>
          </select>
          <label class="ext-installed-toggle">
            <input type="checkbox" v-model="extShowInstalledOnly" /> 仅已启用
          </label>
        </div>
        <div v-if="extensionsLoading" class="empty-state"><p>加载中...</p></div>
        <div v-else-if="filteredExtensions.length === 0" class="empty-state"><p>无匹配扩展</p></div>
        <div v-else class="ext-grid">
          <div v-for="ext in filteredExtensions" :key="ext.name" class="ext-card" :class="{ installed: ext.installed }">
            <div class="ext-card-header">
              <span class="ext-name">{{ ext.name }}</span>
              <span v-if="ext.installed" class="ext-version">v{{ ext.installed_version }}</span>
            </div>
            <div class="ext-desc">{{ ext.description }}</div>
            <div class="ext-card-footer">
              <span class="ext-category-badge">{{ ext.category }}</span>
              <button
                v-if="ext.installed"
                class="btn btn-small btn-danger-text"
                :disabled="extBusy === ext.name"
                @click="handleDisableExt(ext)"
              >{{ extBusy === ext.name ? '处理中...' : '禁用' }}</button>
              <button
                v-else
                class="btn btn-small btn-primary"
                :disabled="extBusy === ext.name || database?.status !== 'RUNNING'"
                @click="handleEnableExt(ext)"
              >{{ extBusy === ext.name ? '处理中...' : '启用' }}</button>
            </div>
          </div>
        </div>
      </div>

      <!-- Tab: Parameters -->
      <div v-if="activeTab === 'parameters'" class="tab-content">
        <p class="tab-tip">查看数据库运行参数。标记为「可修改」的参数支持在线调整，修改后新连接生效。</p>
        <div v-if="database?.status !== 'RUNNING'" class="empty-state"><p>数据库未运行，启动后查看参数</p></div>
        <template v-else>
          <div class="ext-toolbar">
            <input v-model="paramSearch" class="form-input ext-search" placeholder="搜索参数..." />
            <label class="ext-installed-toggle">
              <input type="checkbox" v-model="paramEditableOnly" /> 仅可修改
            </label>
          </div>
          <div v-if="parametersLoading" class="empty-state"><p>加载中...</p></div>
          <div v-else class="section-card">
            <div class="table-wrapper">
              <table class="data-table" v-if="filteredParameters.length > 0">
                <thead>
                  <tr>
                    <th>参数名</th>
                    <th>当前值</th>
                    <th>单位</th>
                    <th>说明</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="p in filteredParameters" :key="p.name">
                    <td><code>{{ p.name }}</code></td>
                    <td>
                      <template v-if="paramEditing === p.name">
                        <div style="display: flex; gap: 4px;">
                          <input v-model="paramEditValue" class="form-input form-input-sm" @keyup.enter="handleSaveParam(p)" />
                          <button class="btn btn-small btn-primary" :disabled="paramSaving" @click="handleSaveParam(p)">保存</button>
                          <button class="btn btn-small btn-default" @click="paramEditing = ''">取消</button>
                        </div>
                      </template>
                      <template v-else>{{ p.setting }}</template>
                    </td>
                    <td>{{ p.unit || '-' }}</td>
                    <td class="td-desc">{{ p.description }}</td>
                    <td>
                      <button
                        v-if="p.editable && paramEditing !== p.name"
                        class="btn btn-small btn-text"
                        @click="startEditParam(p)"
                      >修改</button>
                      <span v-else-if="!p.editable" class="text-muted">只读</span>
                    </td>
                  </tr>
                </tbody>
              </table>
              <div v-else class="empty-state"><p>无匹配参数</p></div>
            </div>
          </div>
        </template>
      </div>

      <!-- Security Tab (IP Allowlist) -->
      <!-- Connections Tab -->
      <div v-if="activeTab === 'connections'" class="tab-content">
        <div style="display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px;">
          <p class="tab-tip" style="margin: 0;">当前活跃的客户端连接。数据每次进入此页签时实时查询。</p>
          <button class="btn btn-small btn-default" @click="loadConnections">刷新</button>
        </div>
        <div v-if="database?.status !== 'RUNNING'" class="empty-state"><p>数据库未运行</p></div>
        <template v-else>
          <div v-if="connectionsLoading" class="empty-state"><p>加载中...</p></div>
          <template v-else>
            <!-- Summary cards -->
            <div style="display: flex; gap: 16px; margin-bottom: 16px; flex-wrap: wrap;">
              <div class="conn-stat-card">
                <div class="conn-stat-value">{{ connectionsData.total }}</div>
                <div class="conn-stat-label">总连接数</div>
              </div>
              <div class="conn-stat-card" v-for="item in connectionsData.by_ip" :key="item.ip">
                <div class="conn-stat-value">{{ item.count }}</div>
                <div class="conn-stat-label">{{ item.ip }}</div>
              </div>
            </div>

            <!-- Connection list -->
            <div class="section-card">
              <div class="table-wrapper">
                <table class="data-table" v-if="connectionsData.connections.length > 0">
                  <thead>
                    <tr>
                      <th>PID</th>
                      <th>用户</th>
                      <th>客户端 IP</th>
                      <th>状态</th>
                      <th>连接时长</th>
                      <th>当前查询</th>
                      <th>应用</th>
                      <th>等待</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="c in connectionsData.connections" :key="c.pid">
                      <td><code>{{ c.pid }}</code></td>
                      <td>{{ c.user }}</td>
                      <td>{{ c.client_ip || 'local' }}</td>
                      <td>
                        <span class="conn-state" :class="'conn-' + (c.state || 'unknown')">{{ c.state || '-' }}</span>
                      </td>
                      <td>{{ formatDuration(c.connected_seconds) }}</td>
                      <td class="td-query">{{ c.current_query || '-' }}</td>
                      <td>{{ c.application_name || '-' }}</td>
                      <td>{{ c.wait_event || '-' }}</td>
                    </tr>
                  </tbody>
                </table>
                <div v-else class="empty-state"><p>无活跃连接</p></div>
              </div>
            </div>
          </template>
        </template>
      </div>

      <!-- Security Tab (IP Allowlist) -->
      <div v-if="activeTab === 'security'" class="tab-content">
        <p class="tab-tip">配置 IP 白名单后，只有列表中的 IP 地址才能连接此数据库。留空则允许所有 IP。</p>
        <div class="section-card" style="max-width: 600px;">
          <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 16px;">
            <input
              v-model="newIp"
              class="form-input"
              style="flex: 1;"
              placeholder="输入 IP 或 CIDR，如 202.96.1.0/24"
              @keyup.enter="addIp"
            />
            <button class="btn btn-primary" :disabled="!newIp.trim()" @click="addIp">添加</button>
          </div>
          <div v-if="ipError" style="color: #e6393d; font-size: 13px; margin-bottom: 12px;">{{ ipError }}</div>
          <div v-if="allowedIps.length === 0" class="empty-state" style="padding: 24px;">
            <p>未配置 IP 白名单（允许所有 IP 连接）</p>
          </div>
          <div v-else>
            <div v-for="(ip, i) in allowedIps" :key="i" class="ip-row">
              <code>{{ ip }}</code>
              <button class="btn btn-small btn-text" style="color: #e6393d;" @click="removeIp(i)">删除</button>
            </div>
            <div style="margin-top: 16px;">
              <button class="btn btn-small btn-default" @click="clearIps">清空白名单（允许所有）</button>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>

  <!-- Loading -->
  <div v-else class="page-loading">
    <p>{{ loadError ? '加载失败' : '加载中...' }}</p>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { databaseApi, type Database, type ConnectionsData } from '../../api/database'
// branch management moved to TimeTravelView
import { dbuserApi, type DatabaseUser } from '../../api/dbuser'
import CreateUserDialog from './CreateUserDialog.vue'
// TableToolbar, TableFooter removed (backups tab moved)
import { extensionApi, type ExtensionInfo, type ParameterInfo } from '../../api/extension'
import { copyToClipboard } from '../../utils/clipboard'
import { formatDate } from '../../utils/format'
import { useToast } from '../../composables/useToast'

const route = useRoute()
const dbId = computed(() => route.params.id as string)

const database = ref<Database | null>(null)
const loadError = ref(false)
const actionLoading = ref(false)
let pollTimer: ReturnType<typeof setInterval> | null = null

const newPassword = ref<string | null>(null)
const resettingPassword = ref(false)

const activeTab = ref('info')
const tabs = [
  { key: 'info', label: '基本信息' },
  { key: 'extensions', label: '扩展' },
  { key: 'parameters', label: '参数' },
  { key: 'users', label: '用户权限' },
  { key: 'connections', label: '活跃会话' },
  { key: 'security', label: '访问控制' },
]

// Connections
const connectionsLoading = ref(false)
const connectionsData = reactive<ConnectionsData>({ total: 0, connections: [], by_ip: [] })

async function loadConnections() {
  connectionsLoading.value = true
  try {
    const res = await databaseApi.getConnections(route.params.id as string)
    Object.assign(connectionsData, res.data)
  } catch { /* ignore */ }
  connectionsLoading.value = false
}

function formatDuration(seconds: number): string {
  if (!seconds || seconds < 0) return '-'
  if (seconds < 60) return seconds + 's'
  if (seconds < 3600) return Math.floor(seconds / 60) + 'm ' + (seconds % 60) + 's'
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  return h + 'h ' + m + 'm'
}

// IP Allowlist
const allowedIps = ref<string[]>([])
const newIp = ref('')
const ipError = ref('')

async function loadAllowedIps() {
  try {
    const res = await databaseApi.getAllowedIps(route.params.id as string)
    allowedIps.value = res.data.ips || []
  } catch { /* ignore */ }
}

async function addIp() {
  const ip = newIp.value.trim()
  if (!ip) return
  ipError.value = ''
  const updated = [...allowedIps.value, ip]
  try {
    const res = await databaseApi.setAllowedIps(route.params.id as string, updated)
    allowedIps.value = res.data.ips || []
    newIp.value = ''
    toast.success('IP 已添加')
  } catch (e: any) {
    ipError.value = e?.response?.data?.error?.message || '添加失败'
  }
}

async function removeIp(index: number) {
  const updated = allowedIps.value.filter((_, i) => i !== index)
  try {
    if (updated.length === 0) {
      await databaseApi.clearAllowedIps(route.params.id as string)
      allowedIps.value = []
    } else {
      const res = await databaseApi.setAllowedIps(route.params.id as string, updated)
      allowedIps.value = res.data.ips || []
    }
    toast.success('IP 已删除')
  } catch { /* ignore */ }
}

async function clearIps() {
  try {
    await databaseApi.clearAllowedIps(route.params.id as string)
    allowedIps.value = []
    toast.success('白名单已清空')
  } catch { /* ignore */ }
}

// Users
const dbUsers = ref<DatabaseUser[]>([])
const usersLoading = ref(false)
const showCreateUserDialog = ref(false)
const showRoleDialog = ref(false)
const roleEditUser = ref<DatabaseUser | null>(null)
const newRole = ref('READER')
const updatingRole = ref(false)
const resetUserPasswordResult = ref('')

// Connection info parsing
const connectionFields = computed(() => {
  const uri = database.value?.connection_uri || ''
  if (!uri) return []

  try {
    const url = new URL(uri)
    return [
      { label: '主机', value: url.hostname },
      { label: '端口', value: url.port || '5432' },
      { label: '用户名', value: decodeURIComponent(url.username) },
      { label: '数据库', value: url.pathname.replace(/^\//, '') || 'postgres' },
      { label: '连接字符串', value: uri },
    ]
  } catch {
    return [{ label: '连接字符串', value: uri }]
  }
})

function statusClass(status: string): string {
  switch (status) {
    case 'RUNNING': return 'dot-green'
    case 'SUSPENDED': return 'dot-gray'
    case 'CREATING': return 'dot-blue'
    default: return 'dot-red'
  }
}

function statusText(status: string): string {
  switch (status) {
    case 'RUNNING': return '运行中'
    case 'SUSPENDED': return '已挂起'
    case 'CREATING': return '创建中'
    default: return '异常'
  }
}

const copyTip = ref('')
let copyTipTimer: ReturnType<typeof setTimeout> | null = null

// Track which field was just copied (for inline button feedback)
const copiedField = ref('')
let copiedTimer: ReturnType<typeof setTimeout> | null = null

async function handleCopy(text: string, fieldKey?: string) {
  const ok = await copyToClipboard(text)
  copyTip.value = ok ? '已复制' : '复制失败'
  if (copyTipTimer) clearTimeout(copyTipTimer)
  copyTipTimer = setTimeout(() => { copyTip.value = '' }, 2000)

  if (ok && fieldKey) {
    copiedField.value = fieldKey
    if (copiedTimer) clearTimeout(copiedTimer)
    copiedTimer = setTimeout(() => { copiedField.value = '' }, 1500)
  }
}

async function handleResetPassword() {
  if (!confirm('确定要重置密码吗？旧密码将立即失效。')) return
  resettingPassword.value = true
  try {
    const res = await databaseApi.resetPassword(dbId.value)
    newPassword.value = res.data.password
  } catch (e) {
    console.error('Failed to reset password', e)
  } finally {
    resettingPassword.value = false
  }
}

async function fetchDatabase() {
  try {
    const res = await databaseApi.get(dbId.value)
    database.value = res.data
  } catch {
    loadError.value = true
  }
}

async function pollUntilReady() {
  for (let i = 0; i < 60; i++) {
    await new Promise(r => setTimeout(r, 2000))
    try {
      const res = await databaseApi.get(dbId.value)
      database.value = res.data
      if (['RUNNING', 'SUSPENDED', 'ERROR'].includes(res.data.status)) break
    } catch {
      break
    }
  }
}

async function handleSuspend() {
  actionLoading.value = true
  try {
    await databaseApi.suspend(dbId.value)
    await fetchDatabase()
    pollUntilReady()
  } catch (e) {
    console.error('Failed to suspend', e)
  } finally {
    actionLoading.value = false
  }
}

async function handleResume() {
  actionLoading.value = true
  try {
    await databaseApi.resume(dbId.value)
    await fetchDatabase()
    pollUntilReady()
  } catch (e) {
    console.error('Failed to resume', e)
  } finally {
    actionLoading.value = false
  }
}

// Users
async function fetchUsers() {
  usersLoading.value = true
  try {
    const res = await dbuserApi.listUsers(dbId.value)
    dbUsers.value = res.data
  } catch (e) {
    console.error('Failed to load users', e)
  } finally {
    usersLoading.value = false
  }
}

function roleTagClass(role: string): string {
  switch (role) {
    case 'ADMIN': return 'tag-blue'
    case 'WRITER': return 'tag-green'
    case 'READER': return 'tag-gray'
    default: return ''
  }
}

function openChangeRoleDialog(user: DatabaseUser) {
  roleEditUser.value = user
  newRole.value = user.role
  showRoleDialog.value = true
}

async function handleUpdateRole() {
  if (!roleEditUser.value) return
  updatingRole.value = true
  try {
    await dbuserApi.updateRole(dbId.value, roleEditUser.value.id, { role: newRole.value })
    showRoleDialog.value = false
    await fetchUsers()
  } catch (e) {
    console.error('Failed to update role', e)
    alert('修改角色失败，请重试。')
  } finally {
    updatingRole.value = false
  }
}

async function handleResetUserPassword(user: DatabaseUser) {
  if (!confirm(`确定要重置用户 ${user.username} 的密码吗？`)) return
  try {
    const res = await dbuserApi.resetPassword(dbId.value, user.id)
    resetUserPasswordResult.value = res.data.password
  } catch (e) {
    console.error('Failed to reset password', e)
    alert('重置密码失败，请重试。')
  }
}

async function handleDeleteUser(user: DatabaseUser) {
  if (!confirm(`确定要删除用户 ${user.username} 吗？此操作不可恢复。`)) return
  try {
    await dbuserApi.deleteUser(dbId.value, user.id)
    await fetchUsers()
  } catch (e) {
    console.error('Failed to delete user', e)
    alert('删除用户失败，请重试。')
  }
}

function handleUserCreated() {
  fetchUsers()
}

// Extensions
const toast = useToast()
const extensions = ref<ExtensionInfo[]>([])
const extensionsLoading = ref(false)
const extSearch = ref('')
const extCategoryFilter = ref('')
const extShowInstalledOnly = ref(false)
const extBusy = ref('')

const extCategories = computed(() => {
  const cats = new Set(extensions.value.map(e => e.category))
  return [...cats].sort()
})

const filteredExtensions = computed(() => {
  let list = extensions.value
  if (extSearch.value) {
    const q = extSearch.value.toLowerCase()
    list = list.filter(e => e.name.toLowerCase().includes(q) || e.description.toLowerCase().includes(q))
  }
  if (extCategoryFilter.value) {
    list = list.filter(e => e.category === extCategoryFilter.value)
  }
  if (extShowInstalledOnly.value) {
    list = list.filter(e => e.installed)
  }
  return list
})

async function fetchExtensions() {
  extensionsLoading.value = true
  try {
    const res = await extensionApi.list(dbId.value)
    extensions.value = res.data
  } catch (e) { console.error('Failed to load extensions', e) }
  finally { extensionsLoading.value = false }
}

async function handleEnableExt(ext: ExtensionInfo) {
  extBusy.value = ext.name
  try {
    await extensionApi.enable(dbId.value, ext.name)
    toast.success(`扩展 ${ext.name} 已启用`)
    await fetchExtensions()
  } catch (e: any) {
    toast.error(`启用失败: ${e.response?.data?.message || e.message}`)
  } finally { extBusy.value = '' }
}

async function handleDisableExt(ext: ExtensionInfo) {
  if (!confirm(`确定禁用扩展 ${ext.name}？这会删除其关联的数据类型和函数。`)) return
  extBusy.value = ext.name
  try {
    await extensionApi.disable(dbId.value, ext.name)
    toast.success(`扩展 ${ext.name} 已禁用`)
    await fetchExtensions()
  } catch (e: any) {
    toast.error(`禁用失败: ${e.response?.data?.message || e.message}`)
  } finally { extBusy.value = '' }
}

// Parameters
const parameters = ref<ParameterInfo[]>([])
const parametersLoading = ref(false)
const paramSearch = ref('')
const paramEditableOnly = ref(false)
const paramEditing = ref('')
const paramEditValue = ref('')
const paramSaving = ref(false)

const filteredParameters = computed(() => {
  let list = parameters.value
  if (paramSearch.value) {
    const q = paramSearch.value.toLowerCase()
    list = list.filter(p => p.name.toLowerCase().includes(q) || p.description.toLowerCase().includes(q))
  }
  if (paramEditableOnly.value) {
    list = list.filter(p => p.editable)
  }
  return list
})

async function fetchParameters() {
  parametersLoading.value = true
  try {
    const res = await extensionApi.listParameters(dbId.value)
    parameters.value = res.data
  } catch (e) { console.error('Failed to load parameters', e) }
  finally { parametersLoading.value = false }
}

function startEditParam(p: ParameterInfo) {
  paramEditing.value = p.name
  paramEditValue.value = p.setting
}

async function handleSaveParam(p: ParameterInfo) {
  paramSaving.value = true
  try {
    await extensionApi.updateParameter(dbId.value, p.name, paramEditValue.value)
    toast.success(`参数 ${p.name} 已更新，新连接生效`)
    paramEditing.value = ''
    await fetchParameters()
  } catch (e: any) {
    toast.error(`更新失败: ${e.response?.data?.message || e.message}`)
  } finally { paramSaving.value = false }
}

watch(activeTab, (tab) => {
  if (tab === 'users' && dbUsers.value.length === 0) fetchUsers()
  if (tab === 'extensions' && extensions.value.length === 0) fetchExtensions()
  if (tab === 'parameters' && parameters.value.length === 0) fetchParameters()
  if (tab === 'connections') loadConnections()
  if (tab === 'security') loadAllowedIps()
})

onMounted(() => {
  fetchDatabase()
  pollTimer = setInterval(fetchDatabase, 15000)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<style scoped>
.page-db-detail {
  padding: 4px;
}

.quick-links {
  display: flex;
  gap: 24px;
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid #f0f0f0;
}

.quick-link {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 14px;
  color: #0073e6;
  text-decoration: none;
  transition: color 0.15s;
}

.quick-link:hover {
  color: #005bb5;
  text-decoration: underline;
}

.quick-link-icon {
  font-size: 12px;
}

.page-loading {
  text-align: center;
  padding: 60px 20px;
  color: #8a8e99;
  font-size: 14px;
}

/* Summary Card */
.summary-card {
  background: #fff;
  border-radius: 2px;
  border: 1px solid #dfe1e6;
  padding: 24px;
  margin-bottom: 20px;
}

.serverless-tip {
  background: #f0f7ff;
  border: 1px solid #bfdcff;
  border-radius: 4px;
  padding: 10px 16px;
  font-size: 13px;
  color: #1a4a7a;
  line-height: 1.6;
  margin-bottom: 16px;
}

.tip-link {
  color: #0073e6;
  text-decoration: none;
  margin-left: 4px;
}

.tip-link:hover {
  text-decoration: underline;
}

.summary-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 20px;
}

.db-title {
  font-size: 18px;
  font-weight: 700;
  color: #191919;
  margin: 0 0 8px;
}

.summary-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}

.meta-item {
  font-size: 14px;
  color: #575d6c;
}

.summary-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

.summary-bottom {
  border-top: 1px solid #dfe1e6;
  padding-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.summary-field {
  display: flex;
  align-items: center;
  gap: 12px;
}

.field-label {
  font-size: 14px;
  color: #8a8e99;
  min-width: 72px;
  flex-shrink: 0;
}

.field-value {
  font-size: 14px;
  color: #191919;
}

.field-value-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
}

.uri-text {
  font-size: 13px;
  color: #191919;
  background: #f2f3f5;
  padding: 4px 8px;
  border-radius: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
}

/* Tabs */
.tabs-wrapper {
  background: #fff;
  border-radius: 2px;
  border: 1px solid #ebebeb;
  overflow: hidden;
}

.tab-header {
  display: flex;
  border-bottom: 1px solid #ebebeb;
  padding: 0 20px;
}

.tab-btn {
  background: none;
  border: none;
  padding: 14px 20px;
  font-size: 14px;
  color: #575d6c;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.2s;
  margin-bottom: -1px;
}

.tab-btn:hover {
  color: #0073e6;
}

.tab-btn.active {
  color: #191919;
  border-bottom-color: #0073e6;
  font-weight: 600;
}

.tab-content {
  padding: 20px;
}

.tab-tip {
  color: #6b7280;
  font-size: 13px;
  line-height: 1.5;
  margin: 0 0 16px;
  padding: 8px 12px;
  background: #f8f9fa;
  border-radius: 6px;
  border-left: 3px solid #d1d5db;
}
.tip-link {
  margin-left: 6px;
  color: #3b82f6;
  text-decoration: none;
  &:hover { text-decoration: underline; }
}

.tab-toolbar {
  margin-bottom: 16px;
}

.tab-empty-redirect {
  text-align: center;
  padding: 48px 20px;
  color: #575d6c;
  font-size: 14px;
  line-height: 1.8;
}

.tab-empty-redirect p {
  margin: 0 0 16px;
}

.tab-empty-redirect .btn {
  display: inline-block;
  text-decoration: none;
}

/* Info card */
.info-card {
  border: 1px solid #dfe1e6;
  border-radius: 2px;
  padding: 20px;
}

.info-title {
  font-size: 14px;
  font-weight: 600;
  color: #191919;
  margin: 0 0 16px;
}

.info-grid {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.info-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.info-label {
  font-size: 14px;
  color: #8a8e99;
  min-width: 80px;
  flex-shrink: 0;
}

.info-value-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
}

.info-value-row code {
  font-size: 13px;
  color: #191919;
  background: #f2f3f5;
  padding: 4px 8px;
  border-radius: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
}

.copy-toast {
  position: fixed;
  top: 20px;
  left: 50%;
  transform: translateX(-50%);
  background: #222;
  color: #fff;
  padding: 8px 20px;
  border-radius: 4px;
  font-size: 14px;
  z-index: 9999;
  pointer-events: none;
}

.copy-btn-ok {
  background: #f6ffed !important;
  border-color: #52c41a !important;
  color: #52c41a !important;
}

.password-masked {
  color: #8a8e99;
}

.password-value {
  color: #d4380d;
  font-weight: 600;
}

.password-warning {
  margin-top: 12px;
  padding: 8px 12px;
  background: #fff7e6;
  border: 1px solid #ffd591;
  border-radius: 2px;
  color: #d46b08;
  font-size: 13px;
}

.default-tag {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 2px;
  font-size: 12px;
  background-color: #e6f7ff;
  color: #0073e6;
  margin-left: 6px;
}

.active-tag {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 2px;
  font-size: 12px;
  background-color: #f6ffed;
  color: #52c41a;
  margin-left: 6px;
}

.row-highlight {
  background-color: #f0f5ff !important;
}

.mono-text {
  font-family: monospace;
  font-size: 12px;
}

.action-cell {
  display: flex;
  gap: 4px;
  align-items: center;
}

/* Import tab */
.clickable-row { cursor: pointer; }
.clickable-row:hover { background: #f5f5f5; }
.tag-blue { background-color: #e6f7ff; color: #0073e6; }
.tag-orange { background-color: #fff7e6; color: #d46b08; }
.tag-gray { background-color: #f0f0f0; color: #8a8e99; }

/* Audit tab */
.audit-config-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 16px 32px;
}

.audit-config-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.audit-label {
  font-size: 14px;
  color: #575d6c;
  min-width: 80px;
}

.toggle-switch {
  position: relative;
  display: inline-block;
  width: 36px;
  height: 20px;
  cursor: pointer;
}

.toggle-switch input {
  opacity: 0;
  width: 0;
  height: 0;
}

.toggle-slider {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background-color: #c2c6cc;
  border-radius: 20px;
  transition: 0.2s;
}

.toggle-slider::before {
  content: '';
  position: absolute;
  height: 16px;
  width: 16px;
  left: 2px;
  bottom: 2px;
  background-color: #fff;
  border-radius: 50%;
  transition: 0.2s;
}

.toggle-switch input:checked + .toggle-slider {
  background-color: #0073e6;
}

.toggle-switch input:checked + .toggle-slider::before {
  transform: translateX(16px);
}

.retention-input {
  width: 80px;
  height: 32px;
  font-size: 14px;
}

.sql-cell {
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: monospace;
  font-size: 12px;
}

/* Filter select */
.filter-select {
  width: 180px;
  height: 32px;
  font-size: 14px;
  border: 1px solid #c2c6cc;
  border-radius: 2px;
}

/* Operations refresh bar */
.ops-refresh-bar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: 6px 10px;
  border-bottom: 1px solid #ebebeb;
}

.toolbar-icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: none;
  border-radius: 2px;
  background: none;
  color: #575d6c;
  cursor: pointer;
  transition: all 0.15s;
}

.toolbar-icon-btn:hover:not(:disabled) {
  color: #0073e6;
  background: #f0f5ff;
}

.toolbar-icon-btn:disabled {
  color: #c2c6cc;
  cursor: not-allowed;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.spinning {
  animation: spin 0.8s linear infinite;
}

.section-card {
  border: 1px solid #ebebeb;
  border-radius: 2px;
  overflow: hidden;
  background: #fff;
}

@media (max-width: 768px) {
  .summary-top {
    flex-direction: column;
    gap: 16px;
  }

  .summary-actions {
    width: 100%;
  }

  .summary-meta {
    gap: 8px 16px;
  }

  .summary-field {
    flex-direction: column;
    align-items: flex-start;
    gap: 4px;
  }

  .uri-text {
    font-size: 12px;
    max-width: 100%;
  }

  .tab-header {
    padding: 0 12px;
    overflow-x: auto;
    -webkit-overflow-scrolling: touch;
  }

  .tab-btn {
    padding: 12px 14px;
    font-size: 13px;
    white-space: nowrap;
  }

  .tab-content {
    padding: 16px 12px;
  }

  .info-row {
    flex-direction: column;
    align-items: flex-start;
    gap: 4px;
  }

  .info-label {
    min-width: auto;
  }

  .filter-select {
    width: 100%;
  }

  .summary-card {
    padding: 16px;
  }
}

.td-actions {
  white-space: nowrap;
}

.action-link {
  background: none;
  border: none;
  color: #0073e6;
  font-size: 13px;
  cursor: pointer;
  padding: 2px 8px;
}

.action-link:hover {
  text-decoration: underline;
}

.action-danger {
  color: #e6393d;
}

.action-none {
  color: #ccc;
  font-size: 13px;
}

/* Extensions tab */
.ext-toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.ext-search {
  width: 200px;
  padding: 6px 10px;
  font-size: 13px;
}

.ext-category-select {
  width: 160px;
  padding: 6px 10px;
  font-size: 13px;
}

.ext-installed-toggle {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: #575d6c;
  cursor: pointer;
  white-space: nowrap;
}

.ext-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}

.ext-card {
  border: 1px solid #e5e5e5;
  border-radius: 6px;
  padding: 14px 16px;
  background: #fff;
  transition: border-color 0.15s;
}

.ext-card.installed {
  border-color: #b7eb8f;
  background: #f6ffed;
}

.ext-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.ext-name {
  font-size: 14px;
  font-weight: 600;
  color: #191919;
}

.ext-version {
  font-size: 12px;
  color: #52c41a;
  font-weight: 500;
}

.ext-desc {
  font-size: 12px;
  color: #8a8e99;
  line-height: 1.4;
  margin-bottom: 10px;
}

.ext-card-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.ext-category-badge {
  font-size: 11px;
  color: #575d6c;
  background: #f0f0f0;
  padding: 2px 8px;
  border-radius: 10px;
}

.ext-card.installed .ext-category-badge {
  background: #d9f7be;
  color: #389e0d;
}

/* Parameters tab */
.td-desc {
  max-width: 300px;
  font-size: 12px;
  color: #8a8e99;
  line-height: 1.4;
}

.form-input-sm {
  width: 140px;
  padding: 4px 8px;
  font-size: 13px;
  border: 1px solid #d9d9d9;
  border-radius: 2px;
}

@media (max-width: 768px) {
  .ext-grid {
    grid-template-columns: 1fr;
  }
  .ext-toolbar {
    flex-direction: column;
    align-items: stretch;
  }
  .ext-search, .ext-category-select {
    width: 100%;
  }
}

.conn-stat-card {
  padding: 12px 20px;
  background: #fafbfc;
  border: 1px solid #ebebeb;
  border-radius: 6px;
  text-align: center;
  min-width: 100px;
}

.conn-stat-value {
  font-size: 24px;
  font-weight: 700;
  color: #191919;
}

.conn-stat-label {
  font-size: 12px;
  color: #888;
  margin-top: 2px;
}

.conn-state {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 10px;
  font-size: 12px;
  font-weight: 500;
}

.conn-active { background: #f0faf0; color: #52c41a; }
.conn-idle { background: #f5f5f5; color: #999; }
.conn-idle\ in\ transaction { background: #fff7e6; color: #e37318; }
.conn-unknown { background: #f5f5f5; color: #ccc; }

.td-query {
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 12px;
}

.ip-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 12px;
  border-bottom: 1px solid #f0f0f0;
  font-size: 14px;
}

.ip-row:last-child {
  border-bottom: none;
}

.ip-row code {
  font-family: 'JetBrains Mono', 'Fira Code', 'SF Mono', monospace;
  font-size: 13px;
}

/* Branch + Version split layout */
.branch-version-layout {
  display: flex;
  gap: 0;
  border: 1px solid #ebebeb;
  border-radius: 2px;
  background: #fff;
  min-height: 400px;
  margin-bottom: 16px;
}

.branch-list-panel {
  width: 260px;
  flex-shrink: 0;
  border-right: 1px solid #ebebeb;
  display: flex;
  flex-direction: column;
}

.branch-list-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid #ebebeb;
}

.branch-list-title {
  font-size: 14px;
  font-weight: 600;
  color: #191919;
}

.branch-list-loading {
  padding: 24px 16px;
  text-align: center;
  color: #8a8e99;
  font-size: 13px;
}

.branch-list-items {
  flex: 1;
  overflow-y: auto;
}

.branch-list-item {
  padding: 10px 16px;
  cursor: pointer;
  border-bottom: 1px solid #f5f5f5;
  transition: background 0.15s;
}

.branch-list-item:hover {
  background: #f5f7fa;
}

.branch-list-item-selected {
  background: #e6f0ff;
  border-left: 3px solid #0073e6;
  padding-left: 13px;
}

.branch-item-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 4px;
}

.branch-status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.branch-item-name {
  font-size: 13px;
  font-weight: 500;
  color: #191919;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.branch-item-meta {
  display: flex;
  gap: 8px;
  font-size: 11px;
  color: #8a8e99;
  padding-left: 14px;
}

.branch-item-actions {
  display: flex;
  gap: 4px;
  padding-left: 14px;
  margin-top: 6px;
}

.branch-list-footer {
  padding: 12px 16px;
  border-top: 1px solid #ebebeb;
}

/* Version timeline panel */
.version-timeline-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.version-timeline-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 20px;
  border-bottom: 1px solid #ebebeb;
}

.version-timeline-title {
  font-size: 14px;
  font-weight: 600;
  color: #191919;
}

.version-timeline-loading,
.version-timeline-empty,
.version-timeline-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 1;
  color: #8a8e99;
  font-size: 13px;
  padding: 40px 20px;
}

.version-list {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px;
}

.version-item {
  display: flex;
  gap: 12px;
  position: relative;
}

.version-timeline-dot-line {
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 16px;
  flex-shrink: 0;
  padding-top: 4px;
}

.version-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #c2c6cc;
  border: 2px solid #fff;
  box-shadow: 0 0 0 1px #c2c6cc;
  flex-shrink: 0;
  z-index: 1;
}

.version-dot-latest {
  background: #0073e6;
  box-shadow: 0 0 0 1px #0073e6;
}

.version-line {
  width: 2px;
  flex: 1;
  background: #ebebeb;
  min-height: 20px;
}

.version-content {
  flex: 1;
  padding-bottom: 20px;
  cursor: pointer;
  min-width: 0;
}

.version-content:hover {
  background: #f9fafb;
  border-radius: 4px;
  margin: -4px -8px;
  padding: 4px 8px 24px;
}

.version-header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 4px;
}

.version-name {
  font-size: 14px;
  font-weight: 500;
  color: #191919;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.version-time {
  font-size: 12px;
  color: #8a8e99;
  flex-shrink: 0;
}

.version-meta-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 4px;
}

.version-lsn {
  font-family: 'JetBrains Mono', 'Fira Code', monospace;
  font-size: 12px;
  color: #575d6c;
  background: #f2f3f5;
  padding: 1px 6px;
  border-radius: 2px;
}

.version-author {
  font-size: 12px;
  color: #8a8e99;
}

.version-desc {
  font-size: 12px;
  color: #6b7280;
  line-height: 1.4;
  margin-top: 2px;
}

.version-actions {
  display: flex;
  gap: 8px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid #f0f0f0;
}

.version-item-expanded .version-content {
  background: #f9fafb;
  border-radius: 4px;
  margin: -4px -8px;
  padding: 4px 8px 24px;
}

/* Branch table details toggle */
.branch-table-details {
  margin-top: 16px;
}

.branch-table-summary {
  cursor: pointer;
  font-size: 13px;
  color: #575d6c;
  padding: 8px 0;
  user-select: none;
}

.branch-table-summary:hover {
  color: #0073e6;
}

@media (max-width: 768px) {
  .branch-version-layout {
    flex-direction: column;
  }
  .branch-list-panel {
    width: 100%;
    border-right: none;
    border-bottom: 1px solid #ebebeb;
    max-height: 200px;
  }
}

.version-header-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}

/* Squash mode */
.squash-hint {
  padding: 8px 16px;
  background: #fffbe6;
  border: 1px solid #ffe58f;
  border-radius: 4px;
  font-size: 13px;
  color: #d48806;
  margin-bottom: 12px;
}

.version-item-squash-range {
  opacity: 0.45;
}

.version-item-squash-range .version-content {
  background: #fff1f0;
  border-radius: 4px;
  margin: -4px -8px;
  padding: 4px 8px 24px;
}

.version-name-strikethrough {
  text-decoration: line-through;
  color: #8a8e99;
}

.version-item-squash-endpoint .version-content {
  background: #e6f7ff;
  border-radius: 4px;
  margin: -4px -8px;
  padding: 4px 8px 24px;
}

.squash-confirm-bar {
  margin-top: 16px;
  padding: 12px 16px;
  background: #fffbe6;
  border: 1px solid #ffe58f;
  border-radius: 4px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.squash-confirm-text {
  font-size: 13px;
  color: #d48806;
}

.squash-confirm-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
}

/* Diff overlay */
.diff-overlay {
  margin-top: 16px;
  padding: 16px 20px;
  background: #fafbfc;
  border: 1px solid #ebebeb;
  border-radius: 4px;
}

.diff-overlay-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.diff-overlay-title {
  font-size: 14px;
  font-weight: 600;
  color: #191919;
}

.diff-overlay-loading {
  text-align: center;
  padding: 24px;
  color: #8a8e99;
  font-size: 13px;
}
</style>
