<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">基础设施</h1>
    </div>

    <!-- Nodes -->
    <div class="section-card">
      <div class="section-header"><h3>节点资源</h3></div>
      <div v-if="loading" class="empty-text">加载中...</div>
      <div v-else-if="!nodes.length" class="empty-text">无法获取节点指标（需要 metrics-server）</div>
      <div class="node-grid" v-else>
        <div class="node-card" v-for="node in nodes" :key="node.name">
          <div class="node-name">{{ node.name }}</div>
          <div class="resource-row">
            <span class="resource-label">CPU</span>
            <div class="progress-bar">
              <div class="progress-fill" :class="progressColor(node.cpu_percent)" :style="{ width: node.cpu_percent + '%' }"></div>
            </div>
            <span class="resource-value">{{ node.cpu_percent }}%</span>
            <span class="resource-detail">{{ node.cpu_used_cores }} / {{ node.cpu_total_cores }} cores</span>
          </div>
          <div class="resource-row">
            <span class="resource-label">内存</span>
            <div class="progress-bar">
              <div class="progress-fill" :class="progressColor(node.mem_percent)" :style="{ width: node.mem_percent + '%' }"></div>
            </div>
            <span class="resource-value">{{ node.mem_percent }}%</span>
            <span class="resource-detail">{{ node.mem_used_gb }} / {{ node.mem_total_gb }} GB</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Pods -->
    <div class="section-card">
      <div class="section-header"><h3>Pod 资源占用（按内存排序）</h3></div>
      <div v-if="!pods.length" class="empty-text">暂无数据</div>
      <div class="table-wrapper" v-else>
        <table class="data-table">
          <thead>
            <tr>
              <th>Pod 名称</th>
              <th>命名空间</th>
              <th>CPU (cores)</th>
              <th>内存 (MB)</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="pod in pods" :key="pod.name">
              <td>{{ pod.name }}</td>
              <td><span class="ns-tag">{{ pod.namespace }}</span></td>
              <td>{{ pod.cpu_cores }}</td>
              <td>{{ pod.mem_mb }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- Pod Events -->
    <div class="section-card">
      <div class="section-header"><h3>Pod 事件（近1小时 · lakeon-compute）</h3></div>
      <div v-if="eventsLoading" class="empty-text">加载中...</div>
      <div v-else-if="!events.length" class="empty-text">近1小时无事件</div>
      <div class="table-wrapper" v-else>
        <table class="data-table">
          <thead>
            <tr>
              <th>类型</th>
              <th>Pod 名称</th>
              <th>原因</th>
              <th>消息</th>
              <th>次数</th>
              <th>最后发生</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(event, idx) in events" :key="idx">
              <td>
                <span class="event-type-badge" :class="event.type === 'Warning' ? 'badge-warning' : 'badge-normal'">
                  {{ event.type }}
                </span>
              </td>
              <td class="event-object">{{ event.object }}</td>
              <td class="event-reason">{{ event.reason }}</td>
              <td class="event-message" :title="event.message">{{ truncate(event.message, 80) }}</td>
              <td>{{ event.count }}</td>
              <td class="event-time">{{ formatTime(event.last_time) }}</td>
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

interface NodeInfo {
  name: string
  cpu_used_cores: number
  cpu_total_cores: number
  cpu_percent: number
  mem_used_gb: number
  mem_total_gb: number
  mem_percent: number
}

interface PodInfo {
  name: string
  namespace: string
  cpu_cores: number
  mem_mb: number
}

interface PodEvent {
  type: string
  reason: string
  message: string
  object: string
  last_time: string
  count: number
}

const nodes = ref<NodeInfo[]>([])
const pods = ref<PodInfo[]>([])
const events = ref<PodEvent[]>([])
const loading = ref(true)
const eventsLoading = ref(true)

function progressColor(percent: number): string {
  if (percent >= 90) return 'fill-red'
  if (percent >= 70) return 'fill-orange'
  return 'fill-green'
}

function truncate(text: string, maxLen: number): string {
  if (!text) return ''
  return text.length > maxLen ? text.slice(0, maxLen) + '…' : text
}

function formatTime(isoStr: string): string {
  if (!isoStr) return ''
  try {
    const d = new Date(isoStr)
    const pad = (n: number) => String(n).padStart(2, '0')
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
  } catch {
    return isoStr
  }
}

async function loadData() {
  loading.value = true
  eventsLoading.value = true
  try {
    const { data } = await adminApi.infraNodes()
    nodes.value = data.nodes || []
    pods.value = data.pods || []
  } catch (e) {
    console.error('Failed to load infra data', e)
  } finally {
    loading.value = false
  }
  try {
    const { data } = await adminApi.infraEvents()
    events.value = data.events || []
  } catch (e) {
    console.error('Failed to load pod events', e)
  } finally {
    eventsLoading.value = false
  }
}

onMounted(() => { loadData() })
</script>

<style scoped>
.node-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(400px, 1fr));
  gap: 16px;
}
.node-card {
  padding: 20px;
  border: 1px solid #ebebeb;
  border-radius: 6px;
  background: #fafbfc;
}
.node-name {
  font-size: 14px;
  font-weight: 600;
  color: #191919;
  margin-bottom: 16px;
}
.resource-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.resource-label {
  font-size: 13px;
  color: #575d6c;
  width: 36px;
  flex-shrink: 0;
}
.progress-bar {
  flex: 1;
  height: 8px;
  background: #e5e5e5;
  border-radius: 4px;
  overflow: hidden;
}
.progress-fill {
  height: 100%;
  border-radius: 4px;
  transition: width 0.3s;
}
.fill-green { background: #52c41a; }
.fill-orange { background: #e37318; }
.fill-red { background: #e6393d; }
.resource-value {
  font-size: 13px;
  font-weight: 600;
  color: #191919;
  width: 44px;
  text-align: right;
  flex-shrink: 0;
}
.resource-detail {
  font-size: 12px;
  color: #999;
  white-space: nowrap;
  flex-shrink: 0;
}
.ns-tag {
  background: #f0f2f5;
  padding: 2px 8px;
  border-radius: 3px;
  font-size: 12px;
  font-family: monospace;
}
.empty-text { color: #999; font-size: 14px; padding: 20px 0; }

.event-type-badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 3px;
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
}
.badge-warning {
  background: #fff1f0;
  color: #e6393d;
}
.badge-normal {
  background: #f0f2f5;
  color: #575d6c;
}
.event-object {
  font-family: monospace;
  font-size: 12px;
  max-width: 200px;
  word-break: break-all;
}
.event-reason {
  font-size: 13px;
  white-space: nowrap;
}
.event-message {
  font-size: 12px;
  color: #575d6c;
  max-width: 320px;
  cursor: default;
}
.event-time {
  font-size: 12px;
  color: #999;
  white-space: nowrap;
}

@media (max-width: 768px) {
  .node-grid {
    grid-template-columns: 1fr;
  }

  .node-card {
    padding: 16px;
  }
}
</style>
