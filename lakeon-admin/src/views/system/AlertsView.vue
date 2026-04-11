<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">告警管理</h1>
    </div>

    <!-- Alert Rules -->
    <div class="section-card">
      <div class="section-header"><h3>告警规则</h3></div>
      <div class="table-wrapper">
        <table class="data-table">
          <thead>
            <tr>
              <th>规则名称</th>
              <th>检查类型</th>
              <th>阈值</th>
              <th>冷却时间</th>
              <th>Webhook</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="rule in rules" :key="rule.id">
              <td>{{ rule.name }}</td>
              <td><span class="code-tag">{{ rule.checkType }}</span></td>
              <td>
                <input v-if="editingRule === rule.id" v-model.number="editForm.threshold" class="form-input inline-input" type="number" />
                <span v-else>{{ rule.threshold }}</span>
              </td>
              <td>
                <input v-if="editingRule === rule.id" v-model.number="editForm.cooldownMinutes" class="form-input inline-input" type="number" />
                <span v-else>{{ rule.cooldownMinutes }}分钟</span>
              </td>
              <td>
                <input v-if="editingRule === rule.id" v-model="editForm.webhookUrl" class="form-input inline-input-wide" placeholder="Webhook URL" />
                <span v-else class="webhook-text">{{ rule.webhookUrl || '-' }}</span>
              </td>
              <td>
                <span class="status-tag" :class="rule.enabled ? 'tag-green' : 'tag-gray'">
                  {{ rule.enabled ? '启用' : '禁用' }}
                </span>
              </td>
              <td>
                <template v-if="editingRule === rule.id">
                  <button class="btn btn-primary btn-small" @click="saveRule(rule.id)">保存</button>
                  <button class="btn btn-default btn-small" @click="editingRule = null">取消</button>
                </template>
                <template v-else>
                  <button class="btn btn-text btn-small" @click="startEdit(rule)">编辑</button>
                  <button class="btn btn-text btn-small" @click="toggleRule(rule)">
                    {{ rule.enabled ? '禁用' : '启用' }}
                  </button>
                </template>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Test Webhook -->
      <div class="webhook-test">
        <input v-model="testUrl" class="form-input" placeholder="输入 Webhook URL 进行测试" style="flex:1" />
        <button class="btn btn-default btn-small" @click="doTestWebhook" :disabled="!testUrl || testingWebhook">
          {{ testingWebhook ? '发送中...' : '测试发送' }}
        </button>
        <span v-if="testResult" class="test-result" :class="testResult.ok ? 'text-green' : 'text-red'">
          {{ testResult.message }}
        </span>
      </div>
    </div>

    <!-- Alert History -->
    <div class="section-card">
      <div class="section-header"><h3>告警历史</h3></div>
      <div v-if="!alerts.length" class="empty-text">暂无告警记录</div>
      <div class="table-wrapper" v-else>
        <table class="data-table">
          <thead>
            <tr>
              <th>规则</th>
              <th>级别</th>
              <th>消息</th>
              <th>状态</th>
              <th>触发时间</th>
              <th>恢复时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="alert in alerts" :key="alert.id">
              <td>{{ alert.ruleName }}</td>
              <td>
                <span class="status-tag" :class="alert.severity === 'critical' ? 'tag-red' : 'tag-orange'">
                  {{ alert.severity }}
                </span>
              </td>
              <td>{{ alert.message }}</td>
              <td>
                <span class="status-tag" :class="alert.status === 'firing' ? 'tag-red' : 'tag-green'">
                  {{ alert.status === 'firing' ? '告警中' : '已恢复' }}
                </span>
              </td>
              <td>{{ formatDate(alert.firedAt) }}</td>
              <td>{{ alert.resolvedAt ? formatDate(alert.resolvedAt) : '-' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { adminApi } from '../../api/admin'
import { formatDate } from '../../utils/format'

interface AlertRule {
  id: string
  name: string
  checkType: string
  threshold: number
  enabled: boolean
  webhookUrl: string | null
  cooldownMinutes: number
}

interface Alert {
  id: string
  ruleName: string
  severity: string
  message: string
  status: string
  firedAt: string
  resolvedAt: string | null
}

const rules = ref<AlertRule[]>([])
const alerts = ref<Alert[]>([])
const editingRule = ref<string | null>(null)
const editForm = ref({ threshold: 0, webhookUrl: '', cooldownMinutes: 10 })
const testUrl = ref('')
const testingWebhook = ref(false)
const testResult = ref<{ ok: boolean; message: string } | null>(null)

async function loadData() {
  try {
    const [rulesRes, alertsRes] = await Promise.all([
      adminApi.alertRules(),
      adminApi.alerts(),
    ])
    rules.value = rulesRes.data
    alerts.value = alertsRes.data
  } catch (e) {
    console.error('Failed to load alerts', e)
  }
}

function startEdit(rule: AlertRule) {
  editingRule.value = rule.id
  editForm.value = {
    threshold: rule.threshold,
    webhookUrl: rule.webhookUrl || '',
    cooldownMinutes: rule.cooldownMinutes,
  }
}

async function saveRule(id: string) {
  try {
    await adminApi.updateAlertRule(id, {
      threshold: editForm.value.threshold,
      webhook_url: editForm.value.webhookUrl || null,
      cooldown_minutes: editForm.value.cooldownMinutes,
    })
    editingRule.value = null
    await loadData()
  } catch (e) {
    console.error('Failed to save rule', e)
  }
}

async function toggleRule(rule: AlertRule) {
  try {
    await adminApi.updateAlertRule(rule.id, { enabled: !rule.enabled })
    await loadData()
  } catch (e) {
    console.error('Failed to toggle rule', e)
  }
}

async function doTestWebhook() {
  testingWebhook.value = true
  testResult.value = null
  try {
    const { data } = await adminApi.testWebhook(testUrl.value)
    if (data.status === 'error') {
      testResult.value = { ok: false, message: data.message }
    } else {
      testResult.value = { ok: true, message: `HTTP ${data.status}` }
    }
  } catch (e) {
    testResult.value = { ok: false, message: 'Request failed' }
  } finally {
    testingWebhook.value = false
  }
}

onMounted(() => { loadData() })
</script>

<style scoped>
.code-tag {
  background: #f0f2f5;
  padding: 2px 8px;
  border-radius: 3px;
  font-family: monospace;
  font-size: 12px;
}
.inline-input { width: 80px; height: 28px; font-size: 13px; }
.inline-input-wide { width: 200px; height: 28px; font-size: 13px; }
.webhook-text {
  font-size: 12px;
  color: #999;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: inline-block;
}
.status-tag {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 3px;
  font-size: 12px;
  font-weight: 500;
}
.tag-green { background: color-mix(in oklch, var(--c-success) 12%, #fff); color: #386b47; }
.tag-gray { background: var(--c-bg-alt); color: var(--c-text-3); }
.tag-red { background: color-mix(in oklch, var(--cs-severe) 10%, #fff); color: var(--cs-severe); }
.tag-orange { background: color-mix(in oklch, var(--c-accent) 12%, #fff); color: var(--c-accent-text); }
.webhook-test {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid #ebebeb;
}
.test-result { font-size: 13px; }
.text-green { color: #386b47; }
.text-red { color: var(--cs-severe); }
.empty-text { color: #999; font-size: 14px; padding: 20px 0; }
</style>
