<template>
  <div>
    <header class="page-header">
      <div>
        <h1 class="page-title">基础设施</h1>
        <p class="page-subtitle">CCE 弹性节点池、CCI Pod、Neon 数据层、存储与管控面总览</p>
      </div>
    </header>

    <!-- Tabs -->
    <div class="infra-tabs">
      <button class="infra-tab" :class="{ active: activeTab === 'control' }" @click="activeTab = 'control'">管控面</button>
      <button class="infra-tab" :class="{ active: activeTab === 'neon' }" @click="activeTab = 'neon'">Neon 数据层</button>
      <button class="infra-tab" :class="{ active: activeTab === 'cce' }" @click="activeTab = 'cce'">CCE 弹性节点池</button>
      <button class="infra-tab" :class="{ active: activeTab === 'cci' }" @click="activeTab = 'cci'">CCI Pod</button>
      <button class="infra-tab" :class="{ active: activeTab === 'storage' }" @click="activeTab = 'storage'">存储</button>
    </div>

    <!-- Tab: CCE 弹性节点池 -->
    <div v-if="activeTab === 'cce'">

      <!-- CCE Pressure Overview -->
      <div class="stats-row" v-if="pool" style="margin-bottom: 16px;">
        <div class="stat-card">
          <div class="stat-value" :style="{ color: progressColorVal(pool.cpu_percent ?? 0) }">{{ pool.cpu_percent ?? '-' }}<span class="stat-unit">%</span></div>
          <div class="stat-label">CPU 使用率</div>
        </div>
        <div class="stat-card">
          <div class="stat-value" :style="{ color: progressColorVal(pool.mem_percent ?? 0) }">{{ pool.mem_percent ?? '-' }}<span class="stat-unit">%</span></div>
          <div class="stat-label">内存使用率</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ pool.current_nodes }}<span class="stat-unit"> / {{ pool.max_nodes }}</span></div>
          <div class="stat-label">节点数</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ computeSummary?.total ?? '-' }}</div>
          <div class="stat-label">计算 Pod</div>
        </div>
        <div class="stat-card">
          <div class="stat-value stat-green">{{ computeSummary?.by_status?.running ?? 0 }}</div>
          <div class="stat-label">运行中</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ pool.used_cpu_cores }}<span class="stat-unit"> / {{ pool.total_cpu_cores }} cores</span></div>
          <div class="stat-label">CPU 分配</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ pool.used_mem_gb }}<span class="stat-unit"> / {{ pool.total_mem_gb }} GB</span></div>
          <div class="stat-label">内存分配</div>
        </div>
      </div>

      <!-- Sub tabs -->
      <div class="sub-tabs">
        <button class="sub-tab" :class="{ active: cceSubTab === 'nodes' }" @click="cceSubTab = 'nodes'">节点管理</button>
        <button class="sub-tab" :class="{ active: cceSubTab === 'pods' }" @click="cceSubTab = 'pods'">计算 Pod</button>
      </div>

      <!-- Sub: 节点管理 -->
      <div v-if="cceSubTab === 'nodes'">
      <!-- Node Count Chart -->
      <div class="section-card">
        <div class="section-header">
          <h3>节点数趋势</h3>
          <span v-if="pool" class="pool-name-tag">{{ pool.pool_name }}</span>
        </div>
        <MiniLineChart :data="nodeHistory" label="节点数" color="#2a4d6a" />
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

      <!-- Autoscaling Events (in nodes sub-tab) -->
      <div class="section-card" style="margin-top: 16px;">
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
      </div><!-- end cceSubTab === 'nodes' -->

      <!-- Sub: 计算 Pod -->
      <div v-if="cceSubTab === 'pods'">
      <!-- Pod Count Chart -->
      <div class="section-card">
        <div class="section-header">
          <h3>Pod 数量趋势</h3>
          <span class="chart-hint">每 30 秒采样，页面打开后开始记录</span>
        </div>
        <MiniLineChart :data="podHistory" label="Pod 数" color="#c67d3a" />
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
              <thead><tr><th>Pod</th><th>数据库</th><th>数据库状态</th><th>Pod 状态</th><th>内存</th><th>操作</th></tr></thead>
              <tbody>
                <tr v-for="p in computeSummary.pods" :key="p.pod_name"
                    :class="{ 'row-warn': p.db_status === 'suspended' || p.db_status === 'orphaned', 'row-clickable': !!p.db_id }"
                    @click="p.db_id && $router.push(`/databases/${p.db_id}`)"
                    :style="p.db_id ? 'cursor: pointer' : ''">
                  <td class="pod-name">{{ p.pod_name }}</td>
                  <td>{{ p.db_name || '-' }}</td>
                  <td><span class="phase-badge" :class="dbStatusBadge(p.db_status)">{{ DB_STATUS_LABELS[p.db_status] || p.db_status }}</span></td>
                  <td><span class="phase-badge" :class="phaseBadgeClass(p.phase, true)">{{ p.phase }}</span></td>
                  <td>{{ p.mem_request_mb }} MB</td>
                  <td @click.stop>
                    <button v-if="p.db_status === 'error'" class="btn btn-small btn-danger-outline" @click="restartPod(p.pod_name)" :disabled="restartingPod === p.pod_name">
                      {{ restartingPod === p.pod_name ? '重启中...' : '重启' }}
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>
      </div>
      <!-- Pod Events -->
      <div class="section-card" style="margin-top: 16px;">
        <div class="section-header"><h3>Pod 事件（近6小时）</h3></div>
        <div v-if="eventsLoading" class="empty-text">加载中...</div>
        <div v-else-if="!events.length" class="empty-text">近6小时无事件</div>
        <div class="table-wrapper" v-else>
          <table class="data-table">
            <thead><tr><th>类型</th><th>Pod 名称</th><th>原因</th><th>消息</th><th>次数</th><th>最后发生</th></tr></thead>
            <tbody>
              <tr v-for="(event, idx) in events" :key="idx">
                <td><span class="event-type-badge" :class="event.type === 'Warning' ? 'badge-warning' : 'badge-normal'">{{ event.type }}</span></td>
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
      </div><!-- end cceSubTab === 'pods' -->
    </div><!-- end activeTab === 'cce' -->

    <!-- Tab: Neon 数据层 -->
    <div v-if="activeTab === 'neon'">
      <!-- Neon Pressure Overview -->
      <div class="stats-row" v-if="psMetrics" style="margin-bottom: 16px;">
        <div class="stat-card">
          <div class="stat-value" :style="{ color: psMetrics.pressure === 'low' ? 'var(--cs-normal)' : psMetrics.pressure === 'medium' ? 'var(--cs-warn)' : 'var(--cs-severe)' }">
            {{ psMetrics.pressure === 'low' ? '低' : psMetrics.pressure === 'medium' ? '中' : '高' }}
          </div>
          <div class="stat-label">Pageserver 压力</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ psMetrics.tenants?.active ?? '-' }}</div>
          <div class="stat-label">活跃租户</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ psMetrics.memory?.rss_mb ?? '-' }}<span class="stat-unit">MB</span></div>
          <div class="stat-label">内存 RSS</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ psMetrics.wal_redo?.redo_count?.toLocaleString() ?? '-' }}</div>
          <div class="stat-label">WAL Redo 次数</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ psMetrics.remote_reads?.s3_request_count?.toLocaleString() ?? '-' }}</div>
          <div class="stat-label">S3/OBS 请求</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ psCachePercent }}<span class="stat-unit">%</span></div>
          <div class="stat-label">缓存使用率</div>
        </div>
      </div>

      <!-- Dicer 调度管理 -->
      <div class="section-card" style="margin-bottom: 16px;">
        <div class="section-header">
          <h3>Dicer 调度管理</h3>
          <div class="header-actions">
            <button class="action-btn" :disabled="dicerLoading || !!dicerActionLoading" @click="loadDicerTopology">
              刷新
            </button>
            <button class="action-btn" :disabled="!!dicerActionLoading" @click="dryRunDicerRebalance">
              {{ dicerActionLoading === 'rebalance' ? '分析中...' : 'Rebalance dry-run' }}
            </button>
          </div>
        </div>
        <div v-if="dicerLoading" class="empty-text">加载中...</div>
        <template v-else-if="pageserverTopology">
          <div class="stats-row" style="margin-bottom: 12px;">
            <div class="stat-card">
              <div class="stat-value" :style="{ color: dicerUnhealthyCount > 0 ? 'var(--cs-severe)' : 'var(--cs-normal)' }">
                {{ dicerUnhealthyCount > 0 ? '降级' : '正常' }}
              </div>
              <div class="stat-label">Dicer placement</div>
            </div>
            <div class="stat-card">
              <div class="stat-value">{{ dicerNodes.length }}</div>
              <div class="stat-label">Pageserver 节点</div>
            </div>
            <div class="stat-card">
              <div class="stat-value">{{ pageserverTopology.placements.length }}</div>
              <div class="stat-label">已记录 placement</div>
            </div>
            <div class="stat-card">
              <div class="stat-value">{{ dicerSources.join(' / ') || '-' }}</div>
              <div class="stat-label">决策来源</div>
            </div>
          </div>

          <div class="table-wrapper" style="margin-bottom: 12px;">
            <table class="data-table">
              <thead>
                <tr>
                  <th>节点</th>
                  <th>状态</th>
                  <th>Load score</th>
                  <th>Resident</th>
                  <th>Logical</th>
                  <th>Remote</th>
                  <th>HTTP</th>
                  <th>IO</th>
                  <th>Placement</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="node in dicerNodes" :key="node.id">
                  <td class="pod-name">{{ node.id }}</td>
                  <td>
                    <span class="phase-badge" :class="node.healthy ? 'phase-running' : 'phase-failed'">
                      {{ node.healthy ? 'healthy' : 'unavailable' }}
                    </span>
                  </td>
                  <td>{{ formatLoad(node.load_score) }}</td>
                  <td>{{ formatLoad(node.load_breakdown?.resident_physical_size) }}</td>
                  <td>{{ formatLoad(node.load_breakdown?.current_logical_size) }}</td>
                  <td>{{ formatLoad(node.load_breakdown?.remote_physical_size) }}</td>
                  <td>{{ formatLoad(node.load_breakdown?.http_requests) }}</td>
                  <td>{{ formatLoad(node.load_breakdown?.io_operations) }}</td>
                  <td>{{ placementCountByNode[node.id] || 0 }}</td>
                  <td>
                    <button class="link-btn danger" :disabled="dicerActionLoading === node.id" @click="failoverDicerNode(node.id)">
                      {{ dicerActionLoading === node.id ? '处理中...' : 'Failover' }}
                    </button>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <div v-if="dicerDryRunPlan" class="rebalance-plan">
            <div class="plan-title">Dry-run moves: {{ dicerDryRunPlan.moves.length }}</div>
            <div v-if="!dicerDryRunPlan.moves.length" class="empty-text compact">当前无需迁移</div>
            <div v-else class="table-wrapper">
              <table class="data-table compact-table">
                <thead><tr><th>Tenant</th><th>Shard</th><th>From</th><th>To</th><th>Epoch</th><th>Reason</th></tr></thead>
                <tbody>
                  <tr v-for="move in dicerDryRunPlan.moves" :key="`${move.tenant_id}:${move.shard_id}:${move.next_epoch}`">
                    <td class="pod-name">{{ truncate(move.tenant_id, 18) }}</td>
                    <td>{{ move.shard_id }}</td>
                    <td>{{ move.from_node_id }}</td>
                    <td>{{ move.to_node_id }}</td>
                    <td>{{ move.next_epoch }}</td>
                    <td>{{ move.reason }}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </template>
        <div v-else class="empty-text">暂无 Dicer topology 数据</div>
      </div>

      <!-- Tenant 挂靠健康 -->
      <div class="section-card" style="margin-bottom: 16px;">
        <div class="section-header">
          <h3>Tenant 挂靠状态</h3>
          <button class="action-btn" :disabled="reconcileLoading" @click="triggerReconcile">
            {{ reconcileLoading ? '修复中...' : '检查并修复' }}
          </button>
        </div>
        <div v-if="tenantHealthLoading" class="empty-text">加载中...</div>
        <template v-else-if="tenantHealth">
          <div class="stats-row" style="margin-bottom: 12px;">
            <div class="stat-card">
              <div class="stat-value" :style="{ color: tenantHealth.health === 'HEALTHY' ? 'var(--cs-normal)' : tenantHealth.health === 'DEGRADED' ? 'var(--cs-severe)' : '#94a3b8' }">
                {{ tenantHealth.health === 'HEALTHY' ? '正常' : tenantHealth.health === 'DEGRADED' ? '异常' : '不可达' }}
              </div>
              <div class="stat-label">健康状态</div>
            </div>
            <div class="stat-card">
              <div class="stat-value">{{ tenantHealth.expected_tenants }}</div>
              <div class="stat-label">控制面 DB 数</div>
            </div>
            <div class="stat-card">
              <div class="stat-value">{{ tenantHealth.attached_tenants }}</div>
              <div class="stat-label">已挂靠</div>
            </div>
            <div class="stat-card">
              <div class="stat-value" :style="{ color: tenantHealth.missing_count > 0 ? 'var(--cs-severe)' : 'var(--cs-normal)' }">
                {{ tenantHealth.missing_count }}
              </div>
              <div class="stat-label">缺失</div>
            </div>
          </div>
          <div v-if="tenantHealth.missing.length > 0">
            <div class="table-wrapper">
              <table class="data-table">
                <thead><tr><th>数据库</th><th>Neon Tenant ID</th><th>DB 状态</th></tr></thead>
                <tbody>
                  <tr v-for="m in tenantHealth.missing" :key="m.db_id">
                    <td>{{ m.db_name }}</td>
                    <td class="pod-name">{{ m.neon_tenant_id.substring(0, 16) }}...</td>
                    <td><span class="phase-badge" :class="dbStatusBadge(m.status.toLowerCase())">{{ m.status }}</span></td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
          <div v-if="tenantHealth.last_reconcile" style="margin-top: 8px; font-size: 12px; color: #94a3b8;">
            上次 reconcile: {{ new Date(tenantHealth.last_reconcile.timestamp).toLocaleString() }}
            <template v-if="tenantHealth.last_reconcile.reattached > 0">
              &mdash; 修复 {{ tenantHealth.last_reconcile.reattached }} 个
            </template>
          </div>
        </template>
      </div>

      <div class="section-card">
        <div class="section-header"><h3>Neon 组件 Pod</h3></div>
        <div v-if="!neonPods.length" class="empty-text">暂无数据</div>
        <div class="table-wrapper" v-else>
          <table class="data-table">
            <thead><tr><th>Pod 名称</th><th>组件</th><th>状态</th><th>重启次数</th><th>CPU (cores)</th><th>内存 (MB)</th></tr></thead>
            <tbody>
              <tr v-for="pod in neonPods" :key="pod.name + pod.namespace">
                <td class="pod-name">{{ pod.name }}</td>
                <td><span class="ns-tag">{{ neonComponentLabel(pod.name) }}</span></td>
                <td><span class="phase-badge" :class="phaseBadgeClass(pod.phase, pod.ready)">{{ pod.phase }}</span></td>
                <td :class="pod.restarts > 0 ? 'restarts-warn' : ''">{{ pod.restarts }}</td>
                <td>{{ pod.cpu_cores ?? '—' }}</td>
                <td>{{ pod.mem_mb ?? '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- Tab: CCI Pod -->
    <div v-if="activeTab === 'cci'">
      <div class="section-card">
        <div class="section-header"><h3>Job Pod（数据导入任务）</h3></div>
        <div v-if="!jobPods.length" class="empty-text">当前无运行中的 Job Pod</div>
        <div class="table-wrapper" v-else>
          <table class="data-table">
            <thead><tr><th>Pod 名称</th><th>命名空间</th><th>状态</th><th>重启次数</th><th>CPU (cores)</th><th>内存 (MB)</th></tr></thead>
            <tbody>
              <tr v-for="pod in jobPods" :key="pod.name + pod.namespace">
                <td class="pod-name">{{ pod.name }}</td>
                <td><span class="ns-tag">{{ pod.namespace }}</span></td>
                <td><span class="phase-badge" :class="phaseBadgeClass(pod.phase, pod.ready)">{{ pod.phase }}</span></td>
                <td :class="pod.restarts > 0 ? 'restarts-warn' : ''">{{ pod.restarts }}</td>
                <td>{{ pod.cpu_cores ?? '—' }}</td>
                <td>{{ pod.mem_mb ?? '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>

    <!-- Tab: 存储 -->
    <div v-if="activeTab === 'storage'" style="padding: 20px;">
      <StoragePanel />
    </div>

    <!-- Tab: 管控面 -->
    <div v-if="activeTab === 'control'">
      <!-- API Pressure Overview -->
      <div class="stats-row" v-if="metrics" style="margin-bottom: 16px;">
        <div class="stat-card">
          <div class="stat-value">{{ metrics.api?.request_rate_1m ?? '-' }}<span class="stat-unit">/min</span></div>
          <div class="stat-label">API 请求速率</div>
        </div>
        <div class="stat-card">
          <div class="stat-value" :style="{ color: metrics.api?.p95_ms > 500 ? 'var(--cs-severe)' : 'var(--c-text)' }">{{ metrics.api?.p95_ms ?? '-' }}<span class="stat-unit">ms</span></div>
          <div class="stat-label">API P95 响应</div>
        </div>
        <div class="stat-card">
          <div class="stat-value" :style="{ color: heapPercent > 80 ? 'var(--cs-severe)' : heapPercent > 60 ? 'var(--cs-warn)' : 'var(--cs-normal)' }">{{ heapPercent }}<span class="stat-unit">%</span></div>
          <div class="stat-label">JVM 堆使用率</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ metrics.jvm?.threads ?? '-' }}</div>
          <div class="stat-label">线程数</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ metrics.jvm?.gc_pause_ms?.toFixed(1) ?? '-' }}<span class="stat-unit">ms</span></div>
          <div class="stat-label">GC 停顿</div>
        </div>
      </div>

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
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, defineComponent, defineAsyncComponent, h } from 'vue'
import { useRoute } from 'vue-router'
import { adminApi } from '../../api/admin'

const StoragePanel = defineAsyncComponent(() => import('./StoragePanel.vue'))

// ── Inline SVG Line Chart ──
interface ChartPoint { time: string; value: number }

const MiniLineChart = defineComponent({
  name: 'MiniLineChart',
  props: {
    data: { type: Array as () => ChartPoint[], required: true },
    label: { type: String, default: '' },
    color: { type: String, default: '#2a4d6a' },
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
          stroke: '#e8e4df', 'stroke-dasharray': '2,4'
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
          style: 'font-size:10px;fill:#94a3b8;font-family:var(--font-mono);'
        }, String(v))),
        // X labels
        ...xLabels.map((p, i) => h('text', {
          x: pts.length === 1 ? toX(0) : (i === 0 ? PX : W - PX),
          y: H - 2,
          'text-anchor': pts.length === 1 ? 'end' : (i === 0 ? 'start' : 'end'),
          style: 'font-size:10px;fill:#94a3b8;font-family:var(--font-mono);'
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

interface PageserverTopologyNode {
  id: string
  http_url: string
  pg_connstring: string
  healthy: boolean
  load_score: number
  load_breakdown?: Record<string, number>
  source: string
}

interface PageserverPlacement {
  tenant_id: string
  shard_id: number
  node_id: string
  epoch: number
  source: string
}

interface PageserverMove {
  tenant_id: string
  shard_id: number
  from_node_id: string
  to_node_id: string
  next_epoch: number
  reason: string
}

interface PageserverRebalancePlan {
  dry_run: boolean
  moves: PageserverMove[]
}

interface PageserverTopology {
  nodes: PageserverTopologyNode[]
  placements: PageserverPlacement[]
}

const route = useRoute()
const activeTab = ref((route.query.tab as string) || 'control')
const cceSubTab = ref('nodes')
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
const restartingPod = ref<string | null>(null)
const metrics = ref<any>(null)
const psMetrics = ref<any>(null)
const pageserverTopology = ref<PageserverTopology | null>(null)
const dicerLoading = ref(false)
const dicerActionLoading = ref<string | null>(null)
const dicerDryRunPlan = ref<PageserverRebalancePlan | null>(null)
const psCachePercent = computed(() => {
  if (!psMetrics.value?.cache) return 0
  const { current_bytes, max_bytes } = psMetrics.value.cache
  if (!max_bytes) return 0
  return Math.round(current_bytes / max_bytes * 100)
})

const dicerNodes = computed(() => pageserverTopology.value?.nodes || [])
const dicerUnhealthyCount = computed(() => dicerNodes.value.filter(node => !node.healthy).length)
const dicerSources = computed(() => Array.from(new Set([
  ...dicerNodes.value.map(node => node.source),
  ...(pageserverTopology.value?.placements || []).map(item => item.source),
])).filter(Boolean))
const placementCountByNode = computed<Record<string, number>>(() => {
  const counts: Record<string, number> = {}
  for (const placement of pageserverTopology.value?.placements || []) {
    counts[placement.node_id] = (counts[placement.node_id] || 0) + 1
  }
  return counts
})
interface TenantHealthData {
  health: 'HEALTHY' | 'DEGRADED' | 'UNREACHABLE'
  expected_tenants: number
  attached_tenants: number
  missing_count: number
  missing: { db_name: string; db_id: string; neon_tenant_id: string; status: string }[]
  pageserver_reachable: boolean
  last_reconcile?: { timestamp: string; reattached: number; failed: number }
}

const tenantHealth = ref<TenantHealthData | null>(null)
const tenantHealthLoading = ref(false)
const reconcileLoading = ref(false)

async function loadTenantHealth() {
  tenantHealthLoading.value = true
  try {
    const res = await adminApi.tenantHealth()
    tenantHealth.value = res.data
  } catch (e) { console.error('Failed to load tenant health', e) }
  finally { tenantHealthLoading.value = false }
}

async function triggerReconcile() {
  if (!confirm('确定手动触发 tenant reconcile？\n将检查并修复 pageserver 上缺失的 tenant 挂靠。')) return
  reconcileLoading.value = true
  try {
    const res = await adminApi.triggerReconcile()
    const d = res.data
    if (d.success) {
      const msg = d.reattached > 0
        ? `修复完成：重新挂靠 ${d.reattached} 个 tenant` + (d.failed > 0 ? `，失败 ${d.failed} 个` : '')
        : `检查完成：所有 ${d.expected} 个 tenant 均已挂靠`
      alert(msg)
    } else {
      alert('Reconcile 失败: ' + d.error)
    }
    await loadTenantHealth()
  } catch (e) {
    alert('请求失败')
    console.error(e)
  } finally {
    reconcileLoading.value = false
  }
}

async function loadDicerTopology() {
  dicerLoading.value = true
  try {
    const res = await adminApi.pageserverTopology()
    pageserverTopology.value = res.data
  } catch (e) {
    console.error('Failed to load Dicer topology', e)
  } finally {
    dicerLoading.value = false
  }
}

async function dryRunDicerRebalance() {
  dicerActionLoading.value = 'rebalance'
  try {
    const res = await adminApi.pageserverRebalanceDryRun()
    dicerDryRunPlan.value = res.data
    await loadDicerTopology()
  } catch (e) {
    alert('Rebalance dry-run 失败')
    console.error(e)
  } finally {
    dicerActionLoading.value = null
  }
}

async function failoverDicerNode(nodeId: string) {
  if (!confirm(`确定对 ${nodeId} 触发 failover？\n该节点上的 placement 会迁移到其它 pageserver。`)) return
  dicerActionLoading.value = nodeId
  try {
    const res = await adminApi.failoverPageserverNode(nodeId)
    const plan = res.data as PageserverRebalancePlan
    alert(`Failover 完成：迁移 ${plan.moves.length} 个 placement`)
    dicerDryRunPlan.value = plan
    await loadDicerTopology()
  } catch (e) {
    alert('Failover 请求失败')
    console.error(e)
  } finally {
    dicerActionLoading.value = null
  }
}

function formatLoad(value: number | undefined): string {
  if (value == null || Number.isNaN(value)) return '-'
  if (value >= 1024 * 1024 * 1024) return `${(value / 1024 / 1024 / 1024).toFixed(1)}G`
  if (value >= 1024 * 1024) return `${(value / 1024 / 1024).toFixed(1)}M`
  if (value >= 1024) return `${(value / 1024).toFixed(1)}K`
  return value.toLocaleString(undefined, { maximumFractionDigits: 1 })
}

function progressColorVal(pct: number): string {
  if (pct < 60) return '#2a4d6a'
  if (pct < 80) return '#9a5b25'
  return '#c6333a'
}
const heapPercent = computed(() => {
  if (!metrics.value?.jvm) return 0
  return Math.round(metrics.value.jvm.heap_used_mb / metrics.value.jvm.heap_max_mb * 100)
})

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
  await Promise.allSettled([loadData(), loadComputeSummary(), loadTenantHealth(), loadDicerTopology()])
  recordSnapshot()
}

const controlPlaneNodes = computed(() => {
  const poolNodeNames = new Set(pool.value?.nodes?.map(n => n.name) || [])
  return nodes.value.filter(n => !poolNodeNames.has(n.name))
})

const NEON_COMPONENTS = ['pageserver', 'safekeeper', 'storage-broker']

const controlPlanePods = computed(() => {
  return pods.value.filter(p =>
    p.namespace !== 'lakeon-compute'
    && !NEON_COMPONENTS.some(c => p.name.startsWith(c))
    && p.namespace !== 'lakeon-jobs')
})

const neonPods = computed(() => {
  return pods.value.filter(p => NEON_COMPONENTS.some(c => p.name.startsWith(c)))
})

const jobPods = computed(() => {
  return pods.value.filter(p => p.namespace === 'lakeon-jobs')
})

function neonComponentLabel(podName: string): string {
  if (podName.startsWith('pageserver')) return 'Pageserver'
  if (podName.startsWith('safekeeper')) return 'Safekeeper'
  if (podName.startsWith('storage-broker')) return 'Storage Broker'
  return podName
}

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


async function restartPod(podName: string) {
  if (!confirm(`确定重启 Pod ${podName}？\n这会删除当前 Pod 并在下次访问时重建。`)) return
  restartingPod.value = podName
  try {
    const res = await adminApi.restartPod(podName)
    if (res.data.success) {
      alert(`Pod ${podName} 已重启。数据库将在下次访问时自动恢复。`)
      await loadComputeSummary()
    } else {
      alert(`重启失败: ${res.data.error}`)
    }
  } catch (e) {
    alert('重启请求失败')
    console.error(e)
  } finally {
    restartingPod.value = null
  }
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

async function loadMetrics() {
  try {
    const [mRes, psRes] = await Promise.allSettled([
      adminApi.metricsSummary(),
      adminApi.pageserverMetrics(),
    ])
    if (mRes.status === 'fulfilled') metrics.value = mRes.value.data
    if (psRes.status === 'fulfilled') psMetrics.value = psRes.value.data
  } catch (e) { console.error('Failed to load metrics', e) }
}

onMounted(async () => {
  await Promise.allSettled([loadData(), loadComputeSummary(), loadMetrics(), loadTenantHealth(), loadDicerTopology()])
  recordSnapshot()
  pollTimer = setInterval(pollData, 30000)
})

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<style scoped>
/* ══════════════════════════════════════════
   Tabs (harbor editorial)
   ══════════════════════════════════════════ */
.infra-tabs {
  display: flex;
  gap: var(--space-2xl);
  border-bottom: 1px solid var(--c-border);
  margin-top: var(--space-xl);
  margin-bottom: var(--space-2xl);
}

.infra-tab {
  padding: var(--space-md) 0;
  font-family: var(--font-sans);
  font-size: 14px;
  font-weight: 500;
  color: var(--c-text-2);
  background: none;
  border: none;
  cursor: pointer;
  position: relative;
  transition: color 160ms ease-out;
}

.infra-tab:hover {
  color: var(--c-text);
}

.infra-tab.active {
  color: var(--c-primary);
}

.infra-tab.active::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 2px;
  background: var(--c-accent);
  border-radius: 1px;
}

.infra-tab:focus-visible {
  outline: 2px solid var(--c-accent);
  outline-offset: 4px;
  border-radius: 2px;
}

/* .stats-row, .stat-card, .stat-value, .stat-unit, .stat-label now live in shared style.css */

/* Sub tabs — segmented pill, warm */
.sub-tabs {
  display: inline-flex;
  gap: 0;
  margin-bottom: var(--space-lg);
  border: 1px solid var(--c-border);
  border-radius: 6px;
  background: #fff;
  padding: 3px;
  width: fit-content;
}

.sub-tab {
  padding: 6px var(--space-md);
  border: none;
  background: transparent;
  cursor: pointer;
  font-family: var(--font-sans);
  font-size: 12px;
  font-weight: 500;
  color: var(--c-text-2);
  border-radius: 4px;
  transition: background 160ms ease-out, color 160ms ease-out;
}

.sub-tab:hover:not(.active) {
  color: var(--c-text);
}

.sub-tab.active {
  background: var(--c-accent);
  color: #fff;
}

/* ══════════════════════════════════════════
   Pool gauge & resources
   ══════════════════════════════════════════ */
.pool-summary { margin-bottom: var(--space-lg); padding: var(--space-md) var(--space-lg); }

.pool-name-tag {
  background: color-mix(in oklch, var(--c-primary) 10%, #fff);
  color: var(--c-primary);
  padding: 3px 10px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.04em;
}

.pool-gauge { margin-bottom: var(--space-lg); }

.gauge-label {
  font-family: var(--font-sans);
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--c-text-3);
  margin-bottom: var(--space-sm);
}

.gauge-bar {
  display: flex;
  gap: 4px;
  margin-bottom: var(--space-sm);
}

.gauge-segment {
  flex: 1;
  height: 24px;
  border-radius: 3px;
  transition: background 240ms ease-out;
}

.seg-ready { background: color-mix(in oklch, var(--c-success) 80%, var(--c-primary)); }
.seg-notready { background: var(--cs-warn); }
.seg-empty { background: var(--c-border); }

.gauge-text { font-size: 13px; color: var(--c-text-2); }

.gauge-current {
  font-family: var(--font-display);
  font-size: 26px;
  font-weight: 500;
  color: var(--c-text);
  font-variant-numeric: tabular-nums;
}

.gauge-range {
  font-size: 14px;
  color: var(--c-text-3);
  margin-left: 4px;
  font-variant-numeric: tabular-nums;
}

.gauge-detail {
  margin-left: var(--space-sm);
  font-size: 12px;
  color: var(--c-text-3);
}

.pool-resources { margin-top: var(--space-md); }

/* ══════════════════════════════════════════
   Pool node cards
   ══════════════════════════════════════════ */
.pool-node-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: var(--space-md);
  margin-top: var(--space-lg);
  padding: 0 var(--space-lg) var(--space-lg);
}

.pool-node-card {
  padding: var(--space-lg);
  border: 1px solid var(--c-border-light);
  border-radius: 6px;
  background: #fff;
  transition: border-color 240ms ease-out;
}

.pool-node-card.node-idle {
  border-color: color-mix(in oklch, var(--cs-warn) 40%, var(--c-border));
  background: color-mix(in oklch, var(--cs-warn) 3%, #fff);
}

.pool-node-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-sm);
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
  background: color-mix(in oklch, var(--cs-warn) 10%, #fff);
  color: var(--cs-warn);
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 10px;
  font-weight: 500;
  letter-spacing: 0.04em;
}

.scaledown-hint {
  font-size: 11px;
  color: var(--cs-warn);
}

.pool-node-stats {
  font-size: 12px;
  color: var(--c-text-2);
  margin-bottom: var(--space-sm);
}

.pool-node-pods { font-weight: 500; }

/* ══════════════════════════════════════════
   Autoscaling timeline
   ══════════════════════════════════════════ */
.autoscale-summary {
  display: flex;
  gap: var(--space-lg);
}

.as-stat {
  font-size: 12px;
  font-weight: 500;
  padding: 3px 10px;
  border-radius: 10px;
}

.as-up {
  background: color-mix(in oklch, var(--c-success) 10%, #fff);
  color: #386b47;
}

.as-down {
  background: color-mix(in oklch, var(--c-primary) 10%, #fff);
  color: var(--c-primary);
}

.timeline {
  position: relative;
  padding: var(--space-md) var(--space-lg) var(--space-lg) calc(var(--space-lg) + 24px);
}

.timeline::before {
  content: '';
  position: absolute;
  left: calc(var(--space-lg) + 8px);
  top: var(--space-md);
  bottom: var(--space-lg);
  width: 1px;
  background: var(--c-border);
}

.timeline-item {
  position: relative;
  padding-bottom: var(--space-lg);
}

.timeline-item:last-child {
  padding-bottom: 0;
}

.timeline-dot {
  position: absolute;
  left: -20px;
  top: 4px;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  border: 2px solid #fff;
  box-shadow: 0 0 0 1px var(--c-border);
}

.dot-up { background: var(--c-success); }
.dot-down { background: var(--c-primary); }

.timeline-content { padding-left: 4px; }

.timeline-header {
  display: flex;
  align-items: center;
  gap: var(--space-md);
  margin-bottom: var(--space-xs);
}

.timeline-reason {
  font-size: 12px;
  font-weight: 500;
  padding: 3px 10px;
  border-radius: 10px;
  letter-spacing: 0.02em;
}

.reason-up {
  background: color-mix(in oklch, var(--c-success) 10%, #fff);
  color: #386b47;
}

.reason-down {
  background: color-mix(in oklch, var(--c-primary) 10%, #fff);
  color: var(--c-primary);
}

.timeline-time {
  font-size: 11px;
  color: var(--c-text-3);
  font-variant-numeric: tabular-nums;
}

.timeline-message {
  font-size: 12px;
  color: var(--c-text-2);
  line-height: 1.55;
}

/* ══════════════════════════════════════════
   Node cards (generic)
   ══════════════════════════════════════════ */
.node-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(400px, 1fr));
  gap: var(--space-lg);
  padding: var(--space-lg);
}

.node-card {
  padding: var(--space-lg);
  border: 1px solid var(--c-border-light);
  border-radius: 6px;
  background: #fff;
}

.node-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-lg);
}

.node-name {
  font-family: var(--font-sans);
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text);
}

.status-badge {
  padding: 3px 10px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.04em;
}

.badge-ready {
  background: color-mix(in oklch, var(--c-success) 12%, #fff);
  color: #386b47;
}

.badge-notready {
  background: color-mix(in oklch, var(--cs-severe) 10%, #fff);
  color: var(--cs-severe);
}

/* ══════════════════════════════════════════
   Resource bars
   ══════════════════════════════════════════ */
.resource-row {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  margin-bottom: var(--space-sm);
}

.resource-detail-row {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  font-size: 12px;
  color: var(--c-text-2);
}

.resource-cap {
  font-weight: 500;
  color: var(--c-text);
  font-variant-numeric: tabular-nums;
}

.resource-label {
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--c-text-3);
  width: 36px;
  flex-shrink: 0;
}

.progress-bar {
  flex: 1;
  height: 6px;
  background: var(--c-border-light);
  border-radius: 3px;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  border-radius: 3px;
  transition: width 320ms ease-out;
}

.fill-green { background: var(--c-primary); }
.fill-orange { background: var(--cs-warn); }
.fill-red { background: var(--cs-severe); }

.resource-value {
  font-family: var(--font-sans);
  font-size: 12px;
  font-weight: 600;
  color: var(--c-text);
  width: 44px;
  text-align: right;
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
}

.resource-detail {
  font-size: 11px;
  color: var(--c-text-3);
  white-space: nowrap;
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
}

/* ══════════════════════════════════════════
   Tags & badges
   ══════════════════════════════════════════ */
.ns-tag {
  background: var(--c-bg-alt);
  padding: 2px 8px;
  border-radius: 10px;
  font-size: 11px;
  font-family: var(--font-mono);
  color: var(--c-text-2);
}

.pod-name {
  font-family: var(--font-mono);
  font-size: 12px;
  max-width: 220px;
  word-break: break-all;
  color: var(--c-text-2);
}

.phase-badge {
  display: inline-flex;
  align-items: center;
  padding: 3px 10px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.02em;
}

.phase-running {
  background: color-mix(in oklch, var(--c-success) 12%, #fff);
  color: #386b47;
}

.phase-starting {
  background: color-mix(in oklch, var(--cs-warn) 10%, #fff);
  color: var(--cs-warn);
}

.phase-pending {
  background: color-mix(in oklch, var(--c-primary) 10%, #fff);
  color: var(--c-primary);
}

.phase-failed {
  background: color-mix(in oklch, var(--cs-severe) 10%, #fff);
  color: var(--cs-severe);
}

.phase-unknown {
  background: var(--c-bg-alt);
  color: var(--c-text-2);
}

.restarts-warn {
  color: var(--cs-warn);
  font-weight: 600;
}

.empty-text {
  color: var(--c-text-3);
  font-size: 13px;
  padding: var(--space-lg);
  text-align: center;
}

.chart-hint {
  font-size: 11px;
  color: var(--c-text-3);
  font-weight: 400;
}

.event-type-badge {
  display: inline-flex;
  align-items: center;
  padding: 3px 10px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.02em;
  white-space: nowrap;
}

.badge-warning {
  background: color-mix(in oklch, var(--cs-severe) 10%, #fff);
  color: var(--cs-severe);
}

.badge-normal {
  background: var(--c-bg-alt);
  color: var(--c-text-2);
}

.event-object {
  font-family: var(--font-mono);
  font-size: 12px;
  max-width: 200px;
  word-break: break-all;
  color: var(--c-text-2);
}

.event-reason {
  font-size: 13px;
  white-space: nowrap;
  color: var(--c-text);
}

.event-message {
  font-size: 12px;
  color: var(--c-text-2);
  max-width: 320px;
  cursor: default;
  line-height: 1.5;
}

.event-time {
  font-size: 11px;
  color: var(--c-text-3);
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

/* ══════════════════════════════════════════
   Compute pod mini-stats
   ══════════════════════════════════════════ */
.compute-stats {
  display: flex;
  gap: var(--space-3xl);
  flex-wrap: wrap;
  padding: var(--space-md) var(--space-lg) var(--space-lg);
}

.stat-item {
  text-align: left;
  min-width: 80px;
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.stat-item .stat-value {
  display: block;
  font-family: var(--font-display);
  font-size: 26px;
  font-weight: 500;
  color: var(--c-text);
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.005em;
}

.stat-item .stat-label {
  display: block;
  font-size: 11px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--c-text-3);
}

.stat-green .stat-value { color: #386b47; }
.stat-gray  .stat-value { color: var(--c-text-3); }
.stat-blue  .stat-value { color: var(--c-primary); }
.stat-red   .stat-value { color: var(--cs-severe); }
.stat-orange .stat-value { color: var(--cs-warn); }

/* Clickable / warn rows */
.row-warn {
  background: color-mix(in oklch, var(--cs-warn) 4%, #fff);
}

.row-clickable {
  cursor: pointer;
}

.row-clickable:hover {
  background: var(--c-hover);
  transition: background 120ms ease-out;
}

/* Outline danger button */
.btn-danger-outline {
  background: #fff;
  color: var(--cs-severe);
  border: 1px solid color-mix(in oklch, var(--cs-severe) 40%, var(--c-border));
  padding: 4px 14px;
  border-radius: 4px;
  font-family: var(--font-sans);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: background 160ms ease-out, border-color 160ms ease-out;
}

.btn-danger-outline:hover:not(:disabled) {
  background: color-mix(in oklch, var(--cs-severe) 5%, #fff);
  border-color: var(--cs-severe);
}

.btn-danger-outline:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

/* ══════════════════════════════════════════
   Architecture diagram — editorial, muted
   ══════════════════════════════════════════ */
.arch-diagram {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-lg);
  padding: var(--space-2xl) var(--space-xl);
  background: var(--c-bg-alt);
  border: 1px solid var(--c-border-light);
  border-radius: 8px;
}

.arch-row {
  display: flex;
  gap: var(--space-lg);
  flex-wrap: wrap;
  justify-content: center;
}

.arch-box {
  padding: var(--space-md) var(--space-lg);
  border-radius: 6px;
  border: 1px solid var(--c-border);
  background: #fff;
  text-align: center;
  min-width: 160px;
}

.arch-box-compute { border-color: color-mix(in oklch, var(--c-primary) 30%, var(--c-border)); }
.arch-box-storage { border-color: color-mix(in oklch, var(--c-success) 30%, var(--c-border)); }
.arch-box-network { border-color: color-mix(in oklch, var(--c-accent) 30%, var(--c-border)); }
.arch-box-railway { border-color: color-mix(in oklch, var(--cs-warn) 30%, var(--c-border)); }

.arch-box-label {
  font-size: 10px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--c-text-3);
}

.arch-box-value {
  font-family: var(--font-display);
  font-size: 16px;
  font-weight: 500;
  color: var(--c-text);
  margin-top: 2px;
}

.arch-box-desc {
  font-size: 11px;
  color: var(--c-text-3);
  margin-top: 4px;
}

/* ══════════════════════════════════════════
   Service tags — muted pills
   ══════════════════════════════════════════ */
.service-tag {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border-radius: 10px;
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.02em;
}

.svc-compute {
  background: color-mix(in oklch, var(--c-primary) 10%, #fff);
  color: var(--c-primary);
}

.svc-storage {
  background: color-mix(in oklch, var(--c-success) 12%, #fff);
  color: #386b47;
}

.svc-network {
  background: color-mix(in oklch, var(--c-accent) 12%, #fff);
  color: var(--c-accent-text);
}

.svc-railway {
  background: color-mix(in oklch, var(--cs-warn) 10%, #fff);
  color: var(--cs-warn);
}

.resource-id-text {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--c-text-2);
  max-width: 180px;
  display: inline-block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
  cursor: default;
}

.console-link {
  color: var(--c-accent-text);
  text-decoration: none;
  font-size: 13px;
  white-space: nowrap;
  transition: color 160ms ease-out;
}

.console-link:hover {
  color: var(--c-accent-hover);
  text-decoration: underline;
  text-underline-offset: 3px;
}

.header-actions {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.action-btn,
.link-btn {
  border: 1px solid var(--c-border);
  border-radius: 6px;
  background: #fff;
  color: var(--c-primary);
  cursor: pointer;
  font-family: var(--font-sans);
  font-size: 12px;
  font-weight: 500;
  line-height: 1;
  padding: 7px 10px;
}

.link-btn {
  border-color: transparent;
  padding-inline: 0;
}

.link-btn.danger {
  color: var(--cs-severe);
}

.action-btn:disabled,
.link-btn:disabled {
  color: var(--c-text-3);
  cursor: not-allowed;
}

.rebalance-plan {
  border: 1px dashed var(--c-border);
  border-radius: 8px;
  padding: 10px;
  background: color-mix(in oklch, var(--c-bg-alt) 72%, #fff);
}

.plan-title {
  color: var(--c-text);
  font-size: 12px;
  font-weight: 600;
  margin-bottom: 8px;
}

.empty-text.compact {
  padding: 8px;
}

.compact-table th,
.compact-table td {
  padding-block: 7px;
}

@media (max-width: 768px) {
  .node-grid,
  .pool-node-grid {
    grid-template-columns: 1fr;
  }
  .node-card,
  .pool-node-card {
    padding: var(--space-md);
  }
  .stats-row {
    gap: var(--space-xl);
  }
}
</style>
