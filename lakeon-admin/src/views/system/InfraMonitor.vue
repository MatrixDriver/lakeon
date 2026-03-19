<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">基础设施</h1>
    </div>

    <!-- Tabs -->
    <div class="infra-tabs">
      <button class="infra-tab" :class="{ active: activeTab === 'pods' }" @click="activeTab = 'pods'">计算 Pod</button>
      <button class="infra-tab" :class="{ active: activeTab === 'pool' }" @click="activeTab = 'pool'">弹性节点池</button>
      <button class="infra-tab" :class="{ active: activeTab === 'control' }" @click="activeTab = 'control'">管控面</button>
      <button class="infra-tab" :class="{ active: activeTab === 'events' }" @click="activeTab = 'events'">事件日志</button>
      <button class="infra-tab" :class="{ active: activeTab === 'cloud' }" @click="activeTab = 'cloud'; loadCloudResources()">云资源</button>
    </div>

    <!-- Tab 1: 计算 Pod -->
    <div v-if="activeTab === 'pods'">
      <!-- Pod Count Chart -->
      <div class="section-card">
        <div class="section-header">
          <h3>Pod 数量趋势</h3>
          <span class="chart-hint">每 30 秒采样，页面打开后开始记录</span>
        </div>
        <MiniLineChart :data="podHistory" label="Pod 数" color="#52c41a" />
      </div>

      <!-- Compute Pod Overview -->
      <div class="section-card">
        <div class="section-header">
          <h3>Compute Pod 概览</h3>
          <button class="btn btn-small btn-danger-outline" @click="confirmCleanup" :disabled="cleanupLoading">
            {{ cleanupLoading ? '清理中...' : '清理闲置 Pod' }}
          </button>
        </div>
        <div v-if="computeLoading" class="empty-text">加载中...</div>
        <div v-else-if="!computeSummary" class="empty-text">无法获取数据</div>
        <template v-else>
          <div class="compute-stats">
            <div class="stat-item">
              <span class="stat-value">{{ computeSummary.total }}</span>
              <span class="stat-label">Pod 总数</span>
            </div>
            <div class="stat-item stat-green">
              <span class="stat-value">{{ computeSummary.by_status.running }}</span>
              <span class="stat-label">运行中</span>
            </div>
            <div class="stat-item stat-gray">
              <span class="stat-value">{{ computeSummary.by_status.suspended }}</span>
              <span class="stat-label">已挂起(保留)</span>
            </div>
            <div class="stat-item stat-blue">
              <span class="stat-value">{{ computeSummary.by_status.creating }}</span>
              <span class="stat-label">创建中</span>
            </div>
            <div class="stat-item stat-red" v-if="computeSummary.by_status.error > 0">
              <span class="stat-value">{{ computeSummary.by_status.error }}</span>
              <span class="stat-label">异常</span>
            </div>
            <div class="stat-item stat-orange" v-if="computeSummary.by_status.orphaned > 0">
              <span class="stat-value">{{ computeSummary.by_status.orphaned }}</span>
              <span class="stat-label">孤儿 Pod</span>
            </div>
            <div class="stat-item">
              <span class="stat-value">{{ computeSummary.total_mem_request_gb }} GB</span>
              <span class="stat-label">内存占用</span>
            </div>
          </div>
          <div class="table-wrapper" v-if="computeSummary.pods.length > 0" style="margin-top: 12px;">
            <table class="data-table">
              <thead>
                <tr>
                  <th>Pod</th>
                  <th>数据库</th>
                  <th>数据库状态</th>
                  <th>Pod 状态</th>
                  <th>内存</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="p in computeSummary.pods" :key="p.pod_name"
                  :class="{ 'row-warn': p.db_status === 'suspended' || p.db_status === 'orphaned' }">
                  <td class="pod-name">{{ p.pod_name }}</td>
                  <td>{{ p.db_name || '-' }}</td>
                  <td>
                    <span class="phase-badge" :class="dbStatusBadge(p.db_status)">
                      {{ DB_STATUS_LABELS[p.db_status] || p.db_status }}
                    </span>
                  </td>
                  <td>
                    <span class="phase-badge" :class="phaseBadgeClass(p.phase, true)">{{ p.phase }}</span>
                  </td>
                  <td>{{ p.mem_request_mb }} MB</td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>
      </div>
    </div>

    <!-- Tab 2: 弹性节点池 -->
    <div v-if="activeTab === 'pool'">
      <!-- Node Count Chart -->
      <div class="section-card">
        <div class="section-header">
          <h3>节点数趋势</h3>
          <span v-if="pool" class="pool-name-tag">{{ pool.pool_name }}</span>
        </div>
        <MiniLineChart :data="nodeHistory" label="节点数" color="#1890ff" />
      </div>

      <!-- Node Pool Info -->
      <div class="section-card">
        <div class="section-header">
          <h3>节点池概览</h3>
        </div>
        <div v-if="poolLoading" class="empty-text">加载中...</div>
        <div v-else-if="!pool" class="empty-text">无法获取节点池信息</div>
        <template v-else>
          <div class="pool-summary">
            <div class="pool-gauge">
              <div class="gauge-label">节点数</div>
              <div class="gauge-bar">
                <div class="gauge-segment"
                  v-for="i in pool.max_nodes" :key="i"
                  :class="{
                    'seg-ready': i <= pool.ready_nodes,
                    'seg-notready': i > pool.ready_nodes && i <= pool.current_nodes,
                    'seg-empty': i > pool.current_nodes
                  }"
                ></div>
              </div>
              <div class="gauge-text">
                <span class="gauge-current">{{ pool.current_nodes }}</span>
                <span class="gauge-range">/ {{ pool.max_nodes }}</span>
                <span class="gauge-detail">（范围 {{ pool.min_nodes }}~{{ pool.max_nodes }}，就绪 {{ pool.ready_nodes }}）</span>
              </div>
            </div>
            <div class="pool-resources" v-if="pool.cpu_percent !== undefined">
              <div class="resource-row">
                <span class="resource-label">CPU</span>
                <div class="progress-bar">
                  <div class="progress-fill" :class="progressColor(pool.cpu_percent)" :style="{ width: pool.cpu_percent + '%' }"></div>
                </div>
                <span class="resource-value">{{ pool.cpu_percent }}%</span>
                <span class="resource-detail">{{ pool.used_cpu_cores }} / {{ pool.total_cpu_cores }} cores</span>
              </div>
              <div class="resource-row">
                <span class="resource-label">内存</span>
                <div class="progress-bar">
                  <div class="progress-fill" :class="progressColor(pool.mem_percent)" :style="{ width: pool.mem_percent + '%' }"></div>
                </div>
                <span class="resource-value">{{ pool.mem_percent }}%</span>
                <span class="resource-detail">{{ pool.used_mem_gb }} / {{ pool.total_mem_gb }} GB</span>
              </div>
            </div>
            <div class="pool-resources" v-else>
              <div class="resource-detail-row">
                <span class="resource-label">CPU</span>
                <span class="resource-cap">{{ pool.total_cpu_cores }} cores</span>
                <span class="resource-label" style="margin-left:16px">内存</span>
                <span class="resource-cap">{{ pool.total_mem_gb }} GB</span>
              </div>
            </div>
          </div>

          <!-- Per-node cards -->
          <div class="pool-node-grid">
            <div class="pool-node-card" v-for="node in pool.nodes" :key="node.name"
              :class="{ 'node-idle': node.idle }">
              <div class="pool-node-header">
                <span class="node-name">{{ node.name }}</span>
                <div class="pool-node-tags">
                  <span class="status-badge" :class="node.status === 'Ready' ? 'badge-ready' : 'badge-notready'">
                    {{ node.status }}
                  </span>
                  <span v-if="node.idle" class="idle-badge">空闲</span>
                  <span v-if="node.scale_down_eligible" class="scaledown-hint">
                    可缩容（{{ pool.scale_down_unneeded_minutes }}min 后）
                  </span>
                </div>
              </div>
              <div class="pool-node-stats">
                <span class="pool-node-pods">{{ node.pod_count }} 个 compute pod</span>
              </div>
              <template v-if="node.cpu_percent !== undefined">
                <div class="resource-row">
                  <span class="resource-label">CPU</span>
                  <div class="progress-bar">
                    <div class="progress-fill" :class="progressColor(node.cpu_percent)" :style="{ width: node.cpu_percent + '%' }"></div>
                  </div>
                  <span class="resource-value">{{ node.cpu_percent }}%</span>
                </div>
                <div class="resource-row">
                  <span class="resource-label">内存</span>
                  <div class="progress-bar">
                    <div class="progress-fill" :class="progressColor(node.mem_percent)" :style="{ width: node.mem_percent + '%' }"></div>
                  </div>
                  <span class="resource-value">{{ node.mem_percent }}%</span>
                </div>
              </template>
            </div>
          </div>
        </template>
      </div>
    </div>

    <!-- Tab 2: 管控面 -->
    <div v-if="activeTab === 'control'">
      <!-- Fixed Nodes (control plane) -->
      <div class="section-card">
        <div class="section-header"><h3>固定节点</h3></div>
        <div v-if="loading" class="empty-text">加载中...</div>
        <div v-else-if="!controlPlaneNodes.length" class="empty-text">无固定节点</div>
        <div class="node-grid" v-else>
          <div class="node-card" v-for="node in controlPlaneNodes" :key="node.name">
            <div class="node-header">
              <span class="node-name">{{ node.name }}</span>
              <span class="status-badge" :class="node.status === 'Ready' ? 'badge-ready' : 'badge-notready'">
                {{ node.status }}
              </span>
            </div>
            <template v-if="node.cpu_percent !== undefined">
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
            </template>
            <template v-else>
              <div class="resource-detail-row">
                <span class="resource-label">CPU</span>
                <span class="resource-cap">{{ node.cpu_total_cores }} cores</span>
                <span class="resource-label" style="margin-left:16px">内存</span>
                <span class="resource-cap">{{ node.mem_total_gb }} GB</span>
              </div>
            </template>
          </div>
        </div>
      </div>

      <!-- Control Plane Pods -->
      <div class="section-card">
        <div class="section-header"><h3>管控面 Pod</h3></div>
        <div v-if="!controlPlanePods.length" class="empty-text">暂无数据</div>
        <div class="table-wrapper" v-else>
          <table class="data-table">
            <thead>
              <tr>
                <th>Pod 名称</th>
                <th>命名空间</th>
                <th>状态</th>
                <th>重启次数</th>
                <th>CPU (cores)</th>
                <th>内存 (MB)</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="pod in controlPlanePods" :key="pod.name + pod.namespace">
                <td class="pod-name">{{ pod.name }}</td>
                <td><span class="ns-tag">{{ pod.namespace }}</span></td>
                <td>
                  <span class="phase-badge" :class="phaseBadgeClass(pod.phase, pod.ready)">
                    {{ pod.phase }}
                  </span>
                </td>
                <td :class="pod.restarts > 0 ? 'restarts-warn' : ''">{{ pod.restarts }}</td>
                <td>{{ pod.cpu_cores ?? '—' }}</td>
                <td>{{ pod.mem_mb ?? '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- Tab 3: 事件日志 -->
    <div v-if="activeTab === 'events'">
      <!-- Autoscaling Events -->
      <div class="section-card">
        <div class="section-header">
          <h3>弹性伸缩事件</h3>
          <div class="autoscale-summary" v-if="autoscaleSummary">
            <span class="as-stat as-up">24h 扩容 {{ autoscaleSummary.scale_up_count_24h }} 次</span>
            <span class="as-stat as-down">24h 缩容 {{ autoscaleSummary.scale_down_count_24h }} 次</span>
          </div>
        </div>
        <div v-if="autoscaleLoading" class="empty-text">加载中...</div>
        <div v-else-if="!autoscaleEvents.length" class="empty-text">近48小时无伸缩事件</div>
        <div class="timeline" v-else>
          <div class="timeline-item" v-for="(event, idx) in autoscaleEvents" :key="idx">
            <div class="timeline-dot" :class="isScaleUp(event.reason) ? 'dot-up' : 'dot-down'"></div>
            <div class="timeline-content">
              <div class="timeline-header">
                <span class="timeline-reason" :class="isScaleUp(event.reason) ? 'reason-up' : 'reason-down'">
                  {{ event.reason }}
                </span>
                <span class="timeline-time">{{ formatTime(event.last_time) }}</span>
              </div>
              <div class="timeline-message">{{ event.message }}</div>
            </div>
          </div>
        </div>
      </div>

      <!-- Pod Events -->
      <div class="section-card">
        <div class="section-header"><h3>Pod 事件（近6小时 · lakeon-compute）</h3></div>
        <div v-if="eventsLoading" class="empty-text">加载中...</div>
        <div v-else-if="!events.length" class="empty-text">近6小时无事件</div>
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

    <!-- Tab 4: 云资源 -->
    <div v-if="activeTab === 'cloud'">
      <!-- Architecture Diagram -->
      <div class="section-card">
        <div class="section-header"><h3>部署架构</h3></div>
        <div v-if="cloudLoading" class="empty-text">加载中...</div>
        <div v-else-if="!cloudTopology" class="empty-text">暂无拓扑数据</div>
        <div class="arch-diagram" v-else>
          <!-- Render nodes grouped by type rows: railway → network → compute → storage -->
          <div class="arch-row" v-for="group in topoGroups" :key="group.type">
            <div class="arch-box" :class="'arch-box-' + group.type" v-for="node in group.nodes" :key="node.id">
              <div class="arch-box-label">{{ node.label }}</div>
              <div class="arch-box-value">{{ node.sublabel }}</div>
              <div class="arch-box-desc">{{ node.desc }}</div>
            </div>
          </div>
        </div>
      </div>

      <!-- Resource Table -->
      <div class="section-card">
        <div class="section-header"><h3>资源清单</h3></div>
        <div v-if="!cloudResources.length && !cloudLoading" class="empty-text">暂无资源数据</div>
        <div class="table-wrapper" v-else>
          <table class="data-table">
            <thead>
              <tr>
                <th>名称</th>
                <th>资源 ID</th>
                <th>区域</th>
                <th>服务</th>
                <th>资源类型</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="r in cloudResources" :key="r.name">
                <td style="font-weight: 500;">{{ r.name }}</td>
                <td><span v-if="r.resourceId" class="resource-id-text" :title="r.resourceId">{{ r.resourceId }}</span><span v-else style="color:#ccc;">—</span></td>
                <td>{{ r.region }}</td>
                <td><span class="service-tag" :class="serviceTagClass(r.service)">{{ r.service }}</span></td>
                <td>{{ r.type }}</td>
                <td>
                  <span class="status-dot" :class="r.status === 'ACTIVE' ? 'dot-green' : 'dot-red'"></span>
                  {{ r.status }}
                </td>
                <td>
                  <a v-if="r.consoleUrl" :href="r.consoleUrl" target="_blank" rel="noopener" class="console-link">控制台 ↗</a>
                  <span v-else style="color:#ccc;">—</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, defineComponent, h } from 'vue'
import { adminApi } from '../../api/admin'

// ── Inline SVG Line Chart ──
interface ChartPoint { time: string; value: number }

const MiniLineChart = defineComponent({
  name: 'MiniLineChart',
  props: {
    data: { type: Array as () => ChartPoint[], required: true },
    label: { type: String, default: '' },
    color: { type: String, default: '#52c41a' },
  },
  setup(props) {
    const W = 600, H = 120, PX = 40, PY = 20
    return () => {
      const pts = props.data
      if (pts.length === 0) return h('div', { class: 'empty-text' }, '等待数据采集...')
      const vals = pts.map(p => p.value)
      const maxV = Math.max(...vals, 1) // at least 1 so chart isn't flat at 0
      const minV = 0 // always start Y-axis from 0
      const rangeV = maxV - minV || 1
      const chartW = W - PX * 2, chartH = H - PY * 2
      const toX = (i: number) => PX + (pts.length === 1 ? chartW : (i / (pts.length - 1)) * chartW)
      const toY = (v: number) => PY + chartH - ((v - minV) / rangeV) * chartH

      // For single point, draw a horizontal line from left edge to the point
      const polyPoints = pts.length === 1
        ? `${PX},${toY(pts[0]!.value)} ${toX(0)},${toY(pts[0]!.value)}`
        : pts.map((p, i) => `${toX(i)},${toY(p.value)}`).join(' ')

      const first = pts[0]!, last = pts[pts.length - 1]!

      // Area path
      const areaPoints = pts.length === 1
        ? `M${PX},${toY(first.value)} L${toX(0)},${toY(first.value)} L${toX(0)},${PY + chartH} L${PX},${PY + chartH} Z`
        : `M${toX(0)},${toY(first.value)} ` +
          pts.slice(1).map((p, i) => `L${toX(i + 1)},${toY(p.value)}`).join(' ') +
          ` L${toX(pts.length - 1)},${PY + chartH} L${toX(0)},${PY + chartH} Z`

      // Y-axis labels: 0, mid, max
      const yLabels = [0, Math.round(maxV / 2), maxV]
        .filter((v, i, a) => a.indexOf(v) === i)
      // X-axis: first and last time
      const xLabels = pts.length === 1 ? [first] : [first, last]
      const children = [
        // area fill
        h('path', { d: areaPoints, fill: props.color, opacity: 0.08 }),
        // grid lines
        ...yLabels.map(v => h('line', {
          x1: PX, x2: W - PX, y1: toY(v), y2: toY(v),
          stroke: '#e5e5e5', 'stroke-dasharray': '3,3'
        })),
        // line
        h('polyline', { points: polyPoints, fill: 'none', stroke: props.color, 'stroke-width': 2 }),
        // dots (only the actual data points)
        ...pts.map((p, i) => h('circle', {
          cx: toX(i), cy: toY(p.value), r: 3, fill: '#fff', stroke: props.color, 'stroke-width': 2
        })),
        // Y labels
        ...yLabels.map(v => h('text', {
          x: PX - 6, y: toY(v) + 4, 'text-anchor': 'end',
          style: 'font-size:11px;fill:#999;'
        }, String(v))),
        // X labels
        ...xLabels.map((p, i) => h('text', {
          x: pts.length === 1 ? toX(0) : (i === 0 ? PX : W - PX),
          y: H - 2,
          'text-anchor': pts.length === 1 ? 'end' : (i === 0 ? 'start' : 'end'),
          style: 'font-size:10px;fill:#999;'
        }, p.time)),
        // current value
        h('text', {
          x: toX(pts.length - 1) + 8, y: toY(last.value) - 6,
          style: `font-size:13px;fill:${props.color};font-weight:600;`
        }, String(last.value)),
      ]
      return h('svg', {
        viewBox: `0 0 ${W} ${H}`, style: 'width:100%;height:auto;max-height:140px;',
        'aria-label': `${props.label} 趋势图`
      }, children)
    }
  }
})

interface NodeInfo {
  name: string
  status: string
  cpu_total_cores?: number
  mem_total_gb?: number
  cpu_used_cores?: number
  cpu_percent?: number
  mem_used_gb?: number
  mem_percent?: number
}

interface PodInfo {
  name: string
  namespace: string
  phase: string
  ready: boolean
  restarts: number
  cpu_cores?: number
  mem_mb?: number
}

interface PodEvent {
  type: string
  reason: string
  message: string
  object: string
  last_time: string
  count: number
}

interface PoolNodeInfo {
  name: string
  status: string
  cpu_total_cores: number
  mem_total_gb: number
  cpu_used_cores?: number
  cpu_percent?: number
  mem_used_gb?: number
  mem_percent?: number
  pod_count: number
  idle: boolean
  scale_down_eligible: boolean
}

interface NodePoolInfo {
  pool_name: string
  min_nodes: number
  max_nodes: number
  current_nodes: number
  ready_nodes: number
  total_cpu_cores: number
  total_mem_gb: number
  used_cpu_cores?: number
  used_mem_gb?: number
  cpu_percent?: number
  mem_percent?: number
  scale_down_unneeded_minutes: number
  nodes: PoolNodeInfo[]
}

interface AutoscaleEvent {
  type: string
  reason: string
  message: string
  object: string
  last_time: string
  count: number
}

interface AutoscaleSummary {
  scale_up_count_24h: number
  scale_down_count_24h: number
  last_scale_up: string | null
  last_scale_down: string | null
}

const activeTab = ref('pods')
const nodes = ref<NodeInfo[]>([])
const pods = ref<PodInfo[]>([])
const events = ref<PodEvent[]>([])
const pool = ref<NodePoolInfo | null>(null)
const autoscaleEvents = ref<AutoscaleEvent[]>([])
const autoscaleSummary = ref<AutoscaleSummary | null>(null)
const computeSummary = ref<any>(null)
const loading = ref(true)
const eventsLoading = ref(true)
const poolLoading = ref(true)
const autoscaleLoading = ref(true)
const computeLoading = ref(true)
const cleanupLoading = ref(false)

// ── Time-series history (accumulated in-browser) ──
const MAX_HISTORY = 60 // keep last 60 data points (~30 min at 30s interval)
const podHistory = ref<ChartPoint[]>([])
const nodeHistory = ref<ChartPoint[]>([])
let pollTimer: ReturnType<typeof setInterval> | null = null

function timeLabel(): string {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function recordSnapshot() {
  const t = timeLabel()
  if (computeSummary.value) {
    podHistory.value = [...podHistory.value, { time: t, value: computeSummary.value.total }].slice(-MAX_HISTORY)
  }
  if (pool.value) {
    nodeHistory.value = [...nodeHistory.value, { time: t, value: pool.value.current_nodes }].slice(-MAX_HISTORY)
  }
}

async function pollData() {
  await Promise.allSettled([loadData(), loadComputeSummary()])
  recordSnapshot()
}

const controlPlaneNodes = computed(() => {
  const poolNodeNames = new Set(pool.value?.nodes?.map(n => n.name) || [])
  return nodes.value.filter(n => !poolNodeNames.has(n.name))
})

const controlPlanePods = computed(() => {
  return pods.value.filter(p => p.namespace !== 'lakeon-compute')
})

const DB_STATUS_LABELS: Record<string, string> = {
  running: '运行中',
  suspended: '已挂起',
  error: '异常',
  creating: '创建中',
  orphaned: '孤儿',
}

function dbStatusBadge(status: string): string {
  switch (status) {
    case 'running': return 'phase-running'
    case 'suspended': return 'phase-pending'
    case 'error': return 'phase-failed'
    case 'creating': return 'phase-starting'
    case 'orphaned': return 'phase-failed'
    default: return 'phase-unknown'
  }
}

async function confirmCleanup() {
  const idle = (computeSummary.value?.by_status?.suspended || 0)
    + (computeSummary.value?.by_status?.orphaned || 0)
    + (computeSummary.value?.by_status?.error || 0)
  if (idle === 0) {
    alert('没有需要清理的闲置 Pod')
    return
  }
  if (!confirm(`确定清理 ${idle} 个闲置 Pod（已挂起 + 孤儿 + 异常）？\n这会释放节点内存，允许创建新数据库。`)) return
  cleanupLoading.value = true
  try {
    const res = await adminApi.cleanupIdlePods()
    const d = res.data
    alert(`清理完成：删除 ${d.deleted} 个 Pod` + (d.errors?.length ? `\n失败: ${d.errors.length}` : ''))
    await loadComputeSummary()
    await loadData()
  } catch (e) {
    alert('清理失败')
    console.error(e)
  } finally {
    cleanupLoading.value = false
  }
}

// ── Cloud Resources Tab ──
const cloudTopology = ref<any>(null)
const cloudResources = ref<any[]>([])
const cloudLoading = ref(false)

const TOPO_TYPE_ORDER = ['railway', 'network', 'compute', 'storage']

const topoGroups = computed(() => {
  if (!cloudTopology.value?.nodes) return []
  const groups: Record<string, any[]> = {}
  for (const node of cloudTopology.value.nodes) {
    const t = node.type || 'other'
    if (!groups[t]) groups[t] = []
    groups[t].push(node)
  }
  return TOPO_TYPE_ORDER
    .filter(t => groups[t])
    .map(t => ({ type: t, nodes: groups[t] }))
})

function serviceTagClass(service: string): string {
  const map: Record<string, string> = {
    CCE: 'svc-compute', ECS: 'svc-compute',
    RDS: 'svc-storage', OBS: 'svc-storage',
    ELB: 'svc-network', EIP: 'svc-network',
    Railway: 'svc-railway',
  }
  return map[service] || ''
}

async function loadCloudResources() {
  if (cloudTopology.value) return // already loaded
  cloudLoading.value = true
  try {
    const res = await adminApi.cloudResources()
    cloudTopology.value = res.data.topology || null
    cloudResources.value = res.data.resources || []
  } catch (e) { console.error('Failed to load cloud resources', e) }
  finally { cloudLoading.value = false }
}

async function loadComputeSummary() {
  computeLoading.value = true
  try {
    const res = await adminApi.computeSummary()
    computeSummary.value = res.data
  } catch (e) { console.error('Failed to load compute summary', e) }
  finally { computeLoading.value = false }
}

function progressColor(percent: number | undefined): string {
  if (percent == null) return 'fill-green'
  if (percent >= 90) return 'fill-red'
  if (percent >= 70) return 'fill-orange'
  return 'fill-green'
}

function phaseBadgeClass(phase: string, ready: boolean): string {
  if (phase === 'Running' && ready) return 'phase-running'
  if (phase === 'Running' && !ready) return 'phase-starting'
  if (phase === 'Pending') return 'phase-pending'
  if (phase === 'Failed') return 'phase-failed'
  return 'phase-unknown'
}

function truncate(text: string, maxLen: number): string {
  if (!text) return ''
  return text.length > maxLen ? text.slice(0, maxLen) + '…' : text
}

function isScaleUp(reason: string): boolean {
  return reason.includes('Up') || reason.includes('ScaledUp')
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
  poolLoading.value = true
  autoscaleLoading.value = true

  // Load all in parallel
  const [infraRes, eventsRes, poolRes, asRes] = await Promise.allSettled([
    adminApi.infraNodes(),
    adminApi.infraEvents(),
    adminApi.nodePoolStatus(),
    adminApi.autoscalingEvents(),
  ])

  if (infraRes.status === 'fulfilled') {
    nodes.value = infraRes.value.data.nodes || []
    pods.value = infraRes.value.data.pods || []
  }
  loading.value = false

  if (eventsRes.status === 'fulfilled') {
    events.value = eventsRes.value.data.events || []
  }
  eventsLoading.value = false

  if (poolRes.status === 'fulfilled') {
    pool.value = poolRes.value.data as NodePoolInfo
  }
  poolLoading.value = false

  if (asRes.status === 'fulfilled') {
    autoscaleEvents.value = asRes.value.data.events || []
    autoscaleSummary.value = asRes.value.data.summary || null
  }
  autoscaleLoading.value = false
}

onMounted(async () => {
  await Promise.allSettled([loadData(), loadComputeSummary()])
  recordSnapshot()
  pollTimer = setInterval(pollData, 30000)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<style scoped>
/* Tabs */
.infra-tabs {
  display: flex;
  gap: 0;
  border-bottom: 2px solid #e5e5e5;
  margin-bottom: 20px;
}
.infra-tab {
  padding: 10px 24px;
  font-size: 14px;
  color: #575d6c;
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  margin-bottom: -2px;
  cursor: pointer;
  transition: all 0.15s;
}
.infra-tab:hover { color: #0073e6; }
.infra-tab.active {
  color: #191919;
  font-weight: 600;
  border-bottom-color: #191919;
}

/* Pool Summary */
.pool-summary {
  margin-bottom: 20px;
}
.pool-name-tag {
  background: #e6f7ff;
  color: #096dd9;
  padding: 2px 10px;
  border-radius: 3px;
  font-size: 12px;
  font-weight: 500;
}
.pool-gauge {
  margin-bottom: 16px;
}
.gauge-label {
  font-size: 13px;
  color: #575d6c;
  margin-bottom: 6px;
}
.gauge-bar {
  display: flex;
  gap: 4px;
  margin-bottom: 6px;
}
.gauge-segment {
  flex: 1;
  height: 28px;
  border-radius: 4px;
  transition: background 0.3s;
}
.seg-ready { background: #52c41a; }
.seg-notready { background: #e37318; }
.seg-empty { background: #e5e5e5; }
.gauge-text {
  font-size: 13px;
  color: #575d6c;
}
.gauge-current {
  font-size: 24px;
  font-weight: 700;
  color: #191919;
}
.gauge-range {
  font-size: 16px;
  color: #999;
  margin-left: 2px;
}
.gauge-detail {
  margin-left: 8px;
  font-size: 13px;
  color: #999;
}
.pool-resources {
  margin-top: 12px;
}

/* Pool Node Grid */
.pool-node-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 12px;
  margin-top: 16px;
}
.pool-node-card {
  padding: 16px;
  border: 1px solid #ebebeb;
  border-radius: 6px;
  background: #fafbfc;
  transition: border-color 0.3s;
}
.pool-node-card.node-idle {
  border-color: #faad14;
  background: #fffbe6;
}
.pool-node-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  flex-wrap: wrap;
  gap: 6px;
}
.pool-node-tags {
  display: flex;
  gap: 6px;
  align-items: center;
  flex-wrap: wrap;
}
.idle-badge {
  background: #fffbe6;
  color: #d48806;
  border: 1px solid #ffe58f;
  padding: 1px 8px;
  border-radius: 3px;
  font-size: 11px;
  font-weight: 500;
}
.scaledown-hint {
  font-size: 11px;
  color: #d48806;
}
.pool-node-stats {
  font-size: 12px;
  color: #575d6c;
  margin-bottom: 10px;
}
.pool-node-pods {
  font-weight: 500;
}

/* Autoscaling Timeline */
.autoscale-summary {
  display: flex;
  gap: 16px;
}
.as-stat {
  font-size: 13px;
  font-weight: 500;
  padding: 2px 10px;
  border-radius: 3px;
}
.as-up { background: #f6ffed; color: #389e0d; }
.as-down { background: #e6f7ff; color: #096dd9; }

.timeline {
  position: relative;
  padding-left: 24px;
}
.timeline::before {
  content: '';
  position: absolute;
  left: 8px;
  top: 4px;
  bottom: 4px;
  width: 2px;
  background: #e5e5e5;
}
.timeline-item {
  position: relative;
  padding-bottom: 20px;
}
.timeline-item:last-child {
  padding-bottom: 0;
}
.timeline-dot {
  position: absolute;
  left: -20px;
  top: 4px;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  border: 2px solid #fff;
}
.dot-up { background: #52c41a; }
.dot-down { background: #1890ff; }
.timeline-content {
  padding-left: 4px;
}
.timeline-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 4px;
}
.timeline-reason {
  font-size: 13px;
  font-weight: 600;
  padding: 1px 8px;
  border-radius: 3px;
}
.reason-up { background: #f6ffed; color: #389e0d; }
.reason-down { background: #e6f7ff; color: #096dd9; }
.timeline-time {
  font-size: 12px;
  color: #999;
}
.timeline-message {
  font-size: 12px;
  color: #575d6c;
  line-height: 1.5;
}

/* Original styles */
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
.node-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.node-name {
  font-size: 14px;
  font-weight: 600;
  color: #191919;
}
.status-badge {
  padding: 2px 10px;
  border-radius: 3px;
  font-size: 12px;
  font-weight: 500;
}
.badge-ready { background: #f6ffed; color: #389e0d; }
.badge-notready { background: #fff1f0; color: #e6393d; }

.resource-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.resource-detail-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #575d6c;
}
.resource-cap { font-weight: 500; color: #191919; }
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
.pod-name {
  font-family: monospace;
  font-size: 12px;
  max-width: 220px;
  word-break: break-all;
}
.phase-badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 3px;
  font-size: 12px;
  font-weight: 500;
}
.phase-running  { background: #f6ffed; color: #389e0d; }
.phase-starting { background: #fffbe6; color: #d48806; }
.phase-pending  { background: #e6f7ff; color: #096dd9; }
.phase-failed   { background: #fff1f0; color: #e6393d; }
.phase-unknown  { background: #f0f2f5; color: #575d6c; }
.restarts-warn  { color: #e37318; font-weight: 600; }

.empty-text { color: #999; font-size: 14px; padding: 20px 0; }
.chart-hint { font-size: 12px; color: #999; font-weight: 400; }

.event-type-badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 3px;
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
}
.badge-warning { background: #fff1f0; color: #e6393d; }
.badge-normal  { background: #f0f2f5; color: #575d6c; }
.event-object  { font-family: monospace; font-size: 12px; max-width: 200px; word-break: break-all; }
.event-reason  { font-size: 13px; white-space: nowrap; }
.event-message { font-size: 12px; color: #575d6c; max-width: 320px; cursor: default; }
.event-time    { font-size: 12px; color: #999; white-space: nowrap; }

/* Compute Pod Stats */
.compute-stats {
  display: flex;
  gap: 24px;
  flex-wrap: wrap;
}
.stat-item {
  text-align: center;
  min-width: 80px;
}
.stat-value {
  display: block;
  font-size: 24px;
  font-weight: 700;
  color: #191919;
}
.stat-label {
  display: block;
  font-size: 12px;
  color: #8a8e99;
  margin-top: 2px;
}
.stat-green .stat-value { color: #389e0d; }
.stat-gray .stat-value { color: #8a8e99; }
.stat-blue .stat-value { color: #0073e6; }
.stat-red .stat-value { color: #e53e3e; }
.stat-orange .stat-value { color: #d48806; }
.row-warn { background: #fffbe6; }
.btn-danger-outline {
  background: #fff;
  color: #e53e3e;
  border: 1px solid #e53e3e;
  padding: 4px 14px;
  border-radius: 4px;
  font-size: 13px;
  cursor: pointer;
}
.btn-danger-outline:hover:not(:disabled) { background: #fff1f0; }
.btn-danger-outline:disabled { opacity: 0.5; cursor: not-allowed; }

/* Architecture Diagram */
.arch-diagram {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
  padding: 24px;
  background: #f9fafb;
  border: 1px solid #e5e5e5;
  border-radius: 8px;
}
.arch-row {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
  justify-content: center;
}
.arch-box {
  padding: 12px 20px;
  border-radius: 6px;
  border: 2px solid;
  text-align: center;
  min-width: 160px;
}
.arch-box-compute { background: #eff6ff; border-color: #3b82f6; }
.arch-box-storage { background: #f0fdf4; border-color: #22c55e; }
.arch-box-network { background: #fff7ed; border-color: #f97316; }
.arch-box-railway { background: #faf5ff; border-color: #a855f7; }
.arch-box-label { font-size: 11px; font-weight: 600; color: #6b7280; }
.arch-box-value { font-size: 14px; font-weight: 600; color: #191919; }
.arch-box-desc { font-size: 10px; color: #6b7280; margin-top: 4px; }

/* Service tags */
.service-tag {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 3px;
  font-size: 12px;
  font-weight: 500;
}
.svc-compute { background: #dbeafe; color: #1d4ed8; }
.svc-storage { background: #dcfce7; color: #15803d; }
.svc-network { background: #ffedd5; color: #c2410c; }
.svc-railway { background: #f3e8ff; color: #7c3aed; }

.resource-id-text {
  font-family: monospace;
  font-size: 12px;
  color: #575d6c;
  max-width: 180px;
  display: inline-block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
  cursor: default;
}
.console-link {
  color: #0073e6;
  text-decoration: none;
  font-size: 13px;
  white-space: nowrap;
}
.console-link:hover { text-decoration: underline; }

@media (max-width: 768px) {
  .node-grid { grid-template-columns: 1fr; }
  .node-card { padding: 16px; }
  .pool-node-grid { grid-template-columns: 1fr; }
}
</style>
