<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">数据湖</h1>
    </div>

    <!-- Tabs -->
    <div class="tab-bar">
      <div class="tab-item" :class="{ active: activeTab === 'jobs' }" @click="activeTab = 'jobs'">作业列表</div>
      <div class="tab-item" :class="{ active: activeTab === 'pipelines' }" @click="activeTab = 'pipelines'; loadPipelines()">生产线</div>
      <div class="tab-item" :class="{ active: activeTab === 'datasets' }" @click="activeTab = 'datasets'; loadDatasets()">数据集管理</div>
      <div class="tab-item" :class="{ active: activeTab === 'warmpool' }" @click="activeTab = 'warmpool'; loadWarmPool()">Ray Head 热池</div>
    </div>

    <!-- Jobs Tab -->
    <template v-if="activeTab === 'jobs'">
      <!-- Stats -->
      <div class="stats-row" v-if="stats">
        <div class="stat-card">
          <div class="stat-value">{{ stats.job_count }}</div>
          <div class="stat-label">作业总数</div>
        </div>
        <div class="stat-card">
          <div class="stat-value" style="color: var(--c-primary);">{{ stats.running_count }}</div>
          <div class="stat-label">运行中</div>
        </div>
        <div class="stat-card">
          <div class="stat-value" style="color: var(--cs-severe);">{{ stats.failed_count }}</div>
          <div class="stat-label">失败</div>
        </div>
        <div class="stat-card">
          <div class="stat-value" style="color: #386b47;">{{ datasetCount }}</div>
          <div class="stat-label">数据集</div>
        </div>
      </div>
      <div class="action-toolbar">
        <input type="text" class="search-input" placeholder="按租户 ID 筛选..." v-model="tenantFilter" style="width: 220px;" @keyup.enter="loadJobs" />
        <select class="form-select" v-model="typeFilter" style="width: 140px;">
          <option value="">全部类型</option>
          <option value="PYTHON">Python</option>
          <option value="RAY">Ray</option>
          <option value="FINETUNE">微调</option>
        </select>
        <select class="form-select" v-model="statusFilter" style="width: 140px;">
          <option value="">全部状态</option>
          <option value="PENDING">等待中</option>
          <option value="STARTING">启动中</option>
          <option value="RUNNING">运行中</option>
          <option value="SUCCEEDED">成功</option>
          <option value="FAILED">失败</option>
          <option value="CANCELLED">已取消</option>
        </select>
        <button class="btn btn-default btn-small" @click="loadJobs">筛选</button>
      </div>

      <div class="table-wrapper">
        <table class="data-table">
          <thead>
            <tr>
              <th style="width: 30px;"></th>
              <th>作业名</th>
              <th>租户</th>
              <th>类型</th>
              <th>状态</th>
              <th>资源消耗</th>
              <th>开始时间</th>
              <th>耗时</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="job in jobs" :key="job.id">
              <tr>
                <td>
                  <button class="btn-icon-small" @click="toggleExpand(job.id)">
                    {{ expandedId === job.id ? '▼' : '▶' }}
                  </button>
                </td>
                <td>
                  <strong>{{ job.name }}</strong>
                  <br><span style="font-size: 11px; color: #999;">{{ job.id }}</span>
                </td>
                <td>
                  {{ tenantStore.name(job.tenant_id) }}
                  <br><span style="font-size: 11px; color: #999; font-family: monospace;">{{ job.tenant_id }}</span>
                </td>
                <td>
                  <span class="type-tag" :class="'type-' + job.type.toLowerCase()">{{ TYPE_LABELS[job.type] || job.type }}</span>
                </td>
                <td>
                  <span class="status-dot" :class="jobStatusClass(job.status)"></span>
                  {{ STATUS_LABELS[job.status] || job.status }}
                </td>
                <td style="font-size: 12px;">
                  <span v-if="job.core_hours">CPU: {{ Number(job.core_hours).toFixed(2) }}h</span>
                  <span v-if="job.gpu_hours"> GPU: {{ Number(job.gpu_hours).toFixed(2) }}h</span>
                  <span v-if="!job.core_hours && !job.gpu_hours">-</span>
                </td>
                <td>{{ formatDate(job.started_at) }}</td>
                <td>{{ formatDuration(job) }}</td>
                <td>
                  <button v-if="!isTerminal(job.status)" class="btn btn-text btn-small" style="color: var(--cs-severe);" @click="cancelJob(job)">取消</button>
                  <button class="btn btn-text btn-small" style="color: var(--c-primary);" @click="viewLogs(job)">日志</button>
                </td>
              </tr>
              <!-- Expanded detail row -->
              <tr v-if="expandedId === job.id" class="expanded-row">
                <td colspan="9" style="padding: 0;">
                  <div class="detail-panel">
                    <div v-if="detailLoading" style="color: #999;">加载中...</div>
                    <div v-else-if="detail">
                      <div class="detail-grid">
                        <div><strong>镜像:</strong> {{ detail.base_image || '-' }}</div>
                        <div><strong>命名空间:</strong> {{ detail.cci_namespace || '-' }}</div>
                        <div><strong>K8s Job:</strong> {{ detail.k8s_job_name || '-' }}</div>
                        <div><strong>Ray Job:</strong> {{ detail.ray_job_name || '-' }}</div>
                        <div><strong>日志路径:</strong> {{ detail.log_obs_path || '-' }}</div>
                        <div v-if="detail.error_message" style="color: var(--cs-severe); grid-column: 1 / -1;">
                          <strong>错误信息:</strong> {{ detail.error_message }}
                        </div>
                      </div>
                      <details v-if="detail.spec" style="margin-top: 8px;">
                        <summary style="cursor: pointer; font-size: 13px; color: #666;">查看完整 Spec</summary>
                        <pre style="background: #f5f5f5; padding: 8px; border-radius: 4px; font-size: 12px; max-height: 300px; overflow: auto;">{{ formatSpec(detail.spec) }}</pre>
                      </details>
                    </div>
                  </div>
                </td>
              </tr>
            </template>
            <tr v-if="jobsLoading">
              <td colspan="9" style="text-align:center;padding:32px;color:#94a3b8">加载中...</td>
            </tr>
            <tr v-else-if="jobs.length === 0">
              <td colspan="9" class="empty-state">暂无数据</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- Pipelines Tab -->
    <template v-if="activeTab === 'pipelines'">
      <!-- Pipeline Stats -->
      <div class="stats-row" v-if="plStats">
        <div class="stat-card">
          <div class="stat-value">{{ plStats.pipeline_count }}</div>
          <div class="stat-label">生产线</div>
        </div>
        <div class="stat-card">
          <div class="stat-value" style="color: var(--c-primary);">{{ plStats.running_count }}</div>
          <div class="stat-label">运行中</div>
        </div>
        <div class="stat-card">
          <div class="stat-value" style="color: #386b47;">{{ plStats.succeeded_count }}</div>
          <div class="stat-label">成功</div>
        </div>
        <div class="stat-card">
          <div class="stat-value" style="color: var(--cs-severe);">{{ plStats.failed_count }}</div>
          <div class="stat-label">失败</div>
        </div>
        <div class="stat-card">
          <div class="stat-value">{{ plStats.component_count }}</div>
          <div class="stat-label">组件</div>
        </div>
      </div>

      <!-- Sub-tabs: Pipelines / Runs / Components -->
      <div class="sub-tab-bar">
        <div class="sub-tab" :class="{ active: plSubTab === 'runs' }" @click="plSubTab = 'runs'; loadPipelineRuns()">运行记录</div>
        <div class="sub-tab" :class="{ active: plSubTab === 'list' }" @click="plSubTab = 'list'; loadPipelineList()">生产线列表</div>
        <div class="sub-tab" :class="{ active: plSubTab === 'components' }" @click="plSubTab = 'components'; loadComponents()">组件库</div>
      </div>

      <!-- Pipeline Runs Sub-tab -->
      <template v-if="plSubTab === 'runs'">
        <div class="action-toolbar">
          <input type="text" class="search-input" placeholder="按租户 ID 筛选..." v-model="plRunTenantFilter" style="width: 220px;" @keyup.enter="loadPipelineRuns" />
          <select class="form-select" v-model="plRunStatusFilter" style="width: 140px;">
            <option value="">全部状态</option>
            <option value="PENDING">等待中</option>
            <option value="RUNNING">运行中</option>
            <option value="PAUSED">已暂停</option>
            <option value="SUCCEEDED">成功</option>
            <option value="FAILED">失败</option>
            <option value="CANCELLED">已取消</option>
          </select>
          <button class="btn btn-default btn-small" @click="loadPipelineRuns">筛选</button>
        </div>

        <div class="table-wrapper">
          <table class="data-table">
            <thead>
              <tr>
                <th style="width: 30px;"></th>
                <th>运行 ID</th>
                <th>生产线</th>
                <th>租户</th>
                <th>版本</th>
                <th>状态</th>
                <th>开始时间</th>
                <th>耗时</th>
              </tr>
            </thead>
            <tbody>
              <template v-for="run in plRuns" :key="run.id">
                <tr>
                  <td>
                    <button class="btn-icon-small" @click="toggleRunExpand(run.id)">
                      {{ plExpandedRunId === run.id ? '&#9660;' : '&#9654;' }}
                    </button>
                  </td>
                  <td>
                    <span style="font-family: monospace; font-size: 12px;">{{ run.id }}</span>
                  </td>
                  <td>
                    {{ pipelineNameMap[run.pipeline_id] || run.pipeline_id }}
                  </td>
                  <td>
                    {{ tenantStore.name(run.tenant_id) }}
                    <br><span style="font-size: 11px; color: #999; font-family: monospace;">{{ run.tenant_id }}</span>
                  </td>
                  <td>v{{ run.pipeline_version }}</td>
                  <td>
                    <span class="status-dot" :class="plRunStatusClass(run.status)"></span>
                    {{ PL_RUN_STATUS[run.status] || run.status }}
                  </td>
                  <td>{{ formatDate(run.started_at || run.created_at) }}</td>
                  <td>{{ plRunDuration(run) }}</td>
                </tr>
                <!-- Expanded: step runs -->
                <tr v-if="plExpandedRunId === run.id" class="expanded-row">
                  <td colspan="8" style="padding: 0;">
                    <div class="detail-panel">
                      <div v-if="plRunDetailLoading" style="color: #999;">加载中...</div>
                      <div v-else-if="plRunDetail">
                        <div class="detail-grid" style="grid-template-columns: 1fr 1fr 1fr;">
                          <div><strong>输入数据集:</strong> {{ plRunDetail.input_dataset_id || '-' }}</div>
                          <div><strong>输出数据集:</strong> {{ plRunDetail.output_dataset_version_id || '-' }}</div>
                          <div><strong>创建时间:</strong> {{ formatDate(plRunDetail.created_at) }}</div>
                        </div>
                        <div v-if="plRunDetail.steps && plRunDetail.steps.length > 0" style="margin-top: 12px;">
                          <strong style="font-size: 13px;">步骤执行:</strong>
                          <table class="data-table" style="margin-top: 6px;">
                            <thead>
                              <tr>
                                <th>步骤</th>
                                <th>组件</th>
                                <th>状态</th>
                                <th>开始</th>
                                <th>结束</th>
                                <th>错误</th>
                              </tr>
                            </thead>
                            <tbody>
                              <tr v-for="step in plRunDetail.steps" :key="step.id">
                                <td><span style="font-family: monospace;">{{ step.step_id }}</span></td>
                                <td>{{ step.component_id || '-' }}</td>
                                <td>
                                  <span class="status-dot" :class="plRunStatusClass(step.status)"></span>
                                  {{ step.status }}
                                </td>
                                <td>{{ formatDate(step.started_at) }}</td>
                                <td>{{ formatDate(step.finished_at) }}</td>
                                <td style="max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--cs-severe);">
                                  {{ step.error || '-' }}
                                </td>
                              </tr>
                            </tbody>
                          </table>
                        </div>
                        <div v-else style="margin-top: 8px; color: #999; font-size: 13px;">暂无步骤执行记录</div>
                      </div>
                    </div>
                  </td>
                </tr>
              </template>
              <tr v-if="plRunsLoading">
                <td colspan="8" style="text-align:center;padding:32px;color:#94a3b8">加载中...</td>
              </tr>
              <tr v-else-if="plRuns.length === 0">
                <td colspan="8" class="empty-state">暂无运行记录</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <!-- Pipeline List Sub-tab -->
      <template v-if="plSubTab === 'list'">
        <div class="action-toolbar">
          <input type="text" class="search-input" placeholder="按租户 ID 筛选..." v-model="plTenantFilter" style="width: 220px;" @keyup.enter="loadPipelineList" />
          <button class="btn btn-default btn-small" @click="loadPipelineList">筛选</button>
        </div>

        <div class="table-wrapper">
          <table class="data-table">
            <thead>
              <tr>
                <th>名称</th>
                <th>租户</th>
                <th>数据类型</th>
                <th>模板</th>
                <th>最新版本</th>
                <th>创建时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="pl in plList" :key="pl.id">
                <td>
                  <strong>{{ pl.name }}</strong>
                  <br><span style="font-size: 11px; color: #999;">{{ pl.id }}</span>
                </td>
                <td>
                  {{ tenantStore.name(pl.tenant_id) }}
                  <br><span style="font-size: 11px; color: #999; font-family: monospace;">{{ pl.tenant_id }}</span>
                </td>
                <td>
                  <span class="type-tag" :class="'type-' + (pl.data_type || '').toLowerCase()">{{ pl.data_type }}</span>
                </td>
                <td>{{ pl.is_template ? 'Yes' : '-' }}</td>
                <td>v{{ pl.latest_version }}</td>
                <td>{{ formatDate(pl.created_at) }}</td>
              </tr>
              <tr v-if="plList.length === 0">
                <td colspan="6" class="empty-state">暂无生产线</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <!-- Components Sub-tab -->
      <template v-if="plSubTab === 'components'">
        <div class="table-wrapper">
          <table class="data-table">
            <thead>
              <tr>
                <th>名称</th>
                <th>显示名</th>
                <th>类别</th>
                <th>数据类型</th>
                <th>归属</th>
                <th>描述</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="comp in plComponents" :key="comp.id">
                <td>
                  <span style="font-family: monospace;">{{ comp.name }}</span>
                  <br><span style="font-size: 11px; color: #999;">{{ comp.id }}</span>
                </td>
                <td><strong>{{ comp.display_name }}</strong></td>
                <td>
                  <span class="category-tag" :class="'cat-' + (comp.category || '').toLowerCase()">
                    {{ CATEGORY_LABELS[comp.category] || comp.category }}
                  </span>
                </td>
                <td>
                  <span class="type-tag" :class="'type-' + (comp.data_type || '').toLowerCase()">{{ comp.data_type }}</span>
                </td>
                <td>
                  <span v-if="!comp.tenant_id" style="color: #999;">平台内置</span>
                  <span v-else>{{ tenantStore.name(comp.tenant_id) }}</span>
                </td>
                <td style="max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">
                  {{ comp.description || '-' }}
                </td>
              </tr>
              <tr v-if="plComponents.length === 0">
                <td colspan="6" class="empty-state">暂无组件</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
    </template>

    <!-- Datasets Tab -->
    <template v-if="activeTab === 'datasets'">
      <div class="action-toolbar">
        <input type="text" class="search-input" placeholder="按租户 ID 筛选..." v-model="dstenantFilter" style="width: 220px;" @keyup.enter="loadDatasets" />
        <select class="form-select" v-model="dsStatusFilter" style="width: 140px;">
          <option value="">全部状态</option>
          <option value="DRAFT">DRAFT</option>
          <option value="EXPORTING">EXPORTING</option>
          <option value="READY">READY</option>
          <option value="FAILED">FAILED</option>
        </select>
        <button class="btn btn-default btn-small" @click="loadDatasets">筛选</button>
      </div>

      <div class="table-wrapper">
        <table class="data-table">
          <thead>
            <tr>
              <th>名称</th>
              <th>租户</th>
              <th>来源</th>
              <th>状态</th>
              <th>行数</th>
              <th>大小</th>
              <th>OBS 路径</th>
              <th>创建时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="ds in datasets" :key="ds.id">
              <td>
                <strong>{{ ds.name }}</strong>
                <br><span style="font-size: 11px; color: #999;">{{ ds.id }}</span>
              </td>
              <td>
                {{ tenantStore.name(ds.tenant_id) }}
                <br><span style="font-size: 11px; color: #999; font-family: monospace;">{{ ds.tenant_id }}</span>
              </td>
              <td>
                <span class="source-tag">{{ SOURCE_LABELS[ds.source_type] || ds.source_type }}</span>
                <span v-if="ds.job_id" style="font-size: 11px; color: #999;"><br>{{ ds.job_id }}</span>
              </td>
              <td>
                <span class="status-dot" :class="dsStatusClass(ds.status)"></span>
                {{ ds.status }}
              </td>
              <td>{{ ds.row_count != null ? ds.row_count.toLocaleString() : '-' }}</td>
              <td>{{ formatSize(ds.file_size) }}</td>
              <td class="obs-path-cell">{{ ds.obs_path || '-' }}</td>
              <td>{{ formatDate(ds.created_at) }}</td>
              <td>
                <button class="btn btn-text btn-small" style="color: var(--cs-severe);" @click="deleteDataset(ds)">删除</button>
              </td>
            </tr>
            <tr v-if="datasets.length === 0">
              <td colspan="9" class="empty-state">暂无数据</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- Warm Pool Tab -->
    <template v-if="activeTab === 'warmpool'">
      <div v-if="!warmPool">
        <div class="empty-state">加载中...</div>
      </div>
      <div v-else-if="!warmPool.enabled">
        <div class="empty-state">热池未启用</div>
      </div>
      <template v-else>
        <div class="stats-row">
          <div class="stat-card">
            <div class="stat-value" :style="{ color: warmPool.idle >= warmPool.target_size ? '#386b47' : 'var(--cs-warn)' }">{{ warmPool.idle }}</div>
            <div class="stat-label">空闲</div>
          </div>
          <div class="stat-card">
            <div class="stat-value" style="color: var(--c-primary);">{{ warmPool.claimed }}</div>
            <div class="stat-label">已分配</div>
          </div>
          <div class="stat-card">
            <div class="stat-value">{{ warmPool.pending }}</div>
            <div class="stat-label">启动中</div>
          </div>
          <div class="stat-card">
            <div class="stat-value">{{ warmPool.target_size }}</div>
            <div class="stat-label">目标池大小</div>
          </div>
        </div>

        <div class="table-wrapper">
          <table class="data-table">
            <thead>
              <tr>
                <th>Pod 名称</th>
                <th>池状态</th>
                <th>Phase</th>
                <th>IP</th>
                <th>租户</th>
                <th>会话</th>
                <th>创建时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="pod in warmPool.pods" :key="pod.name">
                <td><span style="font-family: monospace; font-size: 12px;">{{ pod.name }}</span></td>
                <td>
                  <span class="pool-status-tag" :class="'ps-' + pod.pool_status">{{ pod.pool_status }}</span>
                </td>
                <td><span style="font-family: monospace;">{{ pod.phase }}</span></td>
                <td><span style="font-family: monospace;">{{ pod.ip || '—' }}</span></td>
                <td>
                  <template v-if="pod.tenant_id">
                    {{ tenantStore.name(pod.tenant_id) }}
                    <br><span style="font-size: 11px; color: #999; font-family: monospace;">{{ pod.tenant_id }}</span>
                  </template>
                  <span v-else style="color: #ccc;">—</span>
                </td>
                <td><span style="font-family: monospace; font-size: 11px;">{{ pod.session_id || '—' }}</span></td>
                <td>{{ formatDate(pod.created_at) }}</td>
              </tr>
              <tr v-if="warmPool.pods.length === 0">
                <td colspan="7" class="empty-state">暂无热池 Pod</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="warmpool-config">
          <span>镜像: <code>{{ warmPool.image?.split('/').pop() }}</code></span>
          <span>Namespace: <code>{{ warmPool.namespace }}</code></span>
          <span>空闲超时: {{ warmPool.idle_timeout_minutes }} 分钟</span>
          <button class="btn btn-default btn-small" @click="loadWarmPool" style="margin-left: auto;">刷新</button>
        </div>
      </template>
    </template>

    <!-- Log Viewer Dialog -->
    <div v-if="logDialogVisible" class="dialog-overlay" @click.self="closeLogDialog">
      <div class="dialog-box" style="width: 80vw; max-width: 900px; max-height: 80vh;">
        <div class="dialog-header">
          <h3>作业日志: {{ logJobName }}</h3>
          <button class="dialog-close" @click="closeLogDialog">&times;</button>
        </div>
        <div class="dialog-body">
          <pre class="log-content" ref="logContainer">{{ logContent }}</pre>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, nextTick } from 'vue'
import { adminApi } from '../../api/admin'
import { formatDate } from '../../utils/format'
import { useTenantStore } from '../../stores/tenants'

const tenantStore = useTenantStore()

interface DatalakeJob {
  id: string; tenant_id: string; name: string; type: string; status: string
  base_image?: string; cci_namespace?: string; k8s_job_name?: string; ray_job_name?: string
  log_obs_path?: string; core_hours?: number; gpu_hours?: number
  error_message?: string; started_at?: string; finished_at?: string; created_at: string
  spec?: string
}

interface Dataset {
  id: string; tenant_id: string; name: string; description?: string
  source_type: string; database_id?: string; obs_path?: string
  row_count?: number; file_size?: number; status: string
  job_id?: string; error?: string; created_at: string
}

const TYPE_LABELS: Record<string, string> = { PYTHON: 'Python', RAY: 'Ray', FINETUNE: '微调' }
const STATUS_LABELS: Record<string, string> = {
  PENDING: '等待中', STARTING: '启动中', RUNNING: '运行中',
  SUCCEEDED: '成功', FAILED: '失败', CANCELLED: '已取消',
}
const SOURCE_LABELS: Record<string, string> = { DB_EXPORT: '数据库导出', JOB_OUTPUT: '作业输出' }
const TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED'])

interface WarmPoolPod {
  name: string; phase: string; pool_status: string; ip: string | null
  created_at: string | null; tenant_id: string | null; session_id: string | null
}
interface WarmPoolState {
  enabled: boolean; target_size: number; namespace: string; image: string
  idle_timeout_minutes: number; idle: number; claimed: number; pending: number
  total: number; pods: WarmPoolPod[]
}

const activeTab = ref('jobs')
const stats = ref<any>(null)
const datasetCount = ref(0)
const warmPool = ref<WarmPoolState | null>(null)

// Pipeline state
const PL_RUN_STATUS: Record<string, string> = {
  PENDING: '等待中', RUNNING: '运行中', PAUSED: '已暂停',
  SUCCEEDED: '成功', FAILED: '失败', CANCELLED: '已取消',
}
const CATEGORY_LABELS: Record<string, string> = {
  DATA_PREP: '数据准备', EXTRACT: '提取', CLEAN: '清洗',
  FILTER: '过滤', QC: '质量检查', LABEL: '标注', PUBLISH: '发布',
}
const plStats = ref<any>(null)
const plSubTab = ref('runs')
const plList = ref<any[]>([])
const plRuns = ref<any[]>([])
const plRunsLoading = ref(false)
const plComponents = ref<any[]>([])
const plTenantFilter = ref('')
const plRunTenantFilter = ref('')
const plRunStatusFilter = ref('')
const plExpandedRunId = ref<string | null>(null)
const plRunDetail = ref<any>(null)
const plRunDetailLoading = ref(false)
const pipelineNameMap = ref<Record<string, string>>({})

// Jobs
const jobs = ref<DatalakeJob[]>([])
const jobsLoading = ref(true)
const tenantFilter = ref('')
const typeFilter = ref('')
const statusFilter = ref('')
const expandedId = ref<string | null>(null)
const detail = ref<DatalakeJob | null>(null)
const detailLoading = ref(false)

// Datasets
const datasets = ref<Dataset[]>([])
const dstenantFilter = ref('')
const dsStatusFilter = ref('')

// Log dialog
const logDialogVisible = ref(false)
const logJobName = ref('')
const logContent = ref('')
const logContainer = ref<HTMLElement | null>(null)
let activeEventSource: EventSource | null = null

function isTerminal(status: string) { return TERMINAL.has(status) }

function jobStatusClass(status: string) {
  switch (status) {
    case 'SUCCEEDED': return 'dot-green'
    case 'FAILED': return 'dot-red'
    case 'RUNNING': return 'dot-blue'
    case 'STARTING': return 'dot-yellow'
    case 'CANCELLED': return 'dot-gray'
    default: return 'dot-gray'
  }
}

function dsStatusClass(status: string) {
  switch (status) {
    case 'READY': return 'dot-green'
    case 'EXPORTING': return 'dot-blue'
    case 'FAILED': return 'dot-red'
    default: return 'dot-gray'
  }
}

function formatDuration(job: DatalakeJob): string {
  if (!job.started_at) return '-'
  const start = new Date(job.started_at).getTime()
  const end = job.finished_at ? new Date(job.finished_at).getTime() : Date.now()
  const sec = Math.round((end - start) / 1000)
  if (sec < 60) return `${sec}s`
  if (sec < 3600) return `${Math.floor(sec / 60)}m ${sec % 60}s`
  return `${Math.floor(sec / 3600)}h ${Math.floor((sec % 3600) / 60)}m`
}

function formatSize(bytes?: number): string {
  if (bytes == null) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
  return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB'
}

function formatSpec(spec: string): string {
  try { return JSON.stringify(JSON.parse(spec), null, 2) } catch { return spec }
}

async function loadStats() {
  try {
    const { data } = await adminApi.datalakeStats()
    stats.value = data
  } catch { /* ignore */ }
}

async function loadJobs() {
  jobsLoading.value = true
  try {
    const params: Record<string, string> = {}
    if (tenantFilter.value.trim()) params.tenant_id = tenantFilter.value.trim()
    if (typeFilter.value) params.type = typeFilter.value
    if (statusFilter.value) params.status = statusFilter.value
    const { data } = await adminApi.listDatalakeJobs(params)
    jobs.value = data
  } catch { /* ignore */ }
  jobsLoading.value = false
}

async function loadDatasets() {
  try {
    const params: Record<string, string> = {}
    if (dstenantFilter.value.trim()) params.tenant_id = dstenantFilter.value.trim()
    if (dsStatusFilter.value) params.status = dsStatusFilter.value
    const { data } = await adminApi.listDatasets(params)
    datasets.value = data
    datasetCount.value = data.length
  } catch { /* ignore */ }
}

async function toggleExpand(jobId: string) {
  if (expandedId.value === jobId) {
    expandedId.value = null
    return
  }
  expandedId.value = jobId
  detail.value = null
  detailLoading.value = true
  try {
    const { data } = await adminApi.getDatalakeJob(jobId)
    detail.value = data
  } catch { /* ignore */ }
  detailLoading.value = false
}

async function cancelJob(job: DatalakeJob) {
  if (!confirm(`确认取消作业 "${job.name}" (${job.id})？`)) return
  try {
    await adminApi.cancelDatalakeJob(job.id)
    await loadJobs()
    await loadStats()
  } catch (e: any) {
    alert(`取消失败: ${e.response?.data?.message || e.message}`)
  }
}

async function viewLogs(job: DatalakeJob) {
  logJobName.value = job.name
  logContent.value = '加载中...'
  logDialogVisible.value = true

  try {
    const baseUrl = (await import('../../api/client')).default.defaults.baseURL || ''
    const url = `${baseUrl}/datalake/jobs/${job.id}/logs`

    activeEventSource = new EventSource(url)
    logContent.value = ''

    activeEventSource.onmessage = (event) => {
      logContent.value += event.data + '\n'
      nextTick(() => {
        if (logContainer.value) {
          logContainer.value.scrollTop = logContainer.value.scrollHeight
        }
      })
    }

    activeEventSource.onerror = () => {
      if (activeEventSource) activeEventSource.close()
      activeEventSource = null
      if (!logContent.value) {
        logContent.value = '[无法获取日志 — 作业可能尚未分配 Pod 或日志已过期]'
      }
    }
  } catch {
    logContent.value = '[日志加载失败]'
  }
}

function closeLogDialog() {
  logDialogVisible.value = false
  if (activeEventSource) {
    activeEventSource.close()
    activeEventSource = null
  }
}

async function loadWarmPool() {
  try {
    const { data } = await adminApi.getWarmPoolStatus()
    warmPool.value = data
  } catch { /* ignore */ }
}

async function deleteDataset(ds: Dataset) {
  if (!confirm(`确认删除数据集 "${ds.name}" (${ds.id})？`)) return
  try {
    await adminApi.deleteDataset(ds.id)
    await loadDatasets()
  } catch (e: any) {
    alert(`删除失败: ${e.response?.data?.message || e.message}`)
  }
}

// Pipeline methods
function plRunStatusClass(status: string) {
  switch (status) {
    case 'SUCCEEDED': return 'dot-green'
    case 'FAILED': return 'dot-red'
    case 'RUNNING': return 'dot-blue'
    case 'PENDING': return 'dot-gray'
    case 'PAUSED': return 'dot-yellow'
    case 'CANCELLED': return 'dot-gray'
    default: return 'dot-gray'
  }
}

function plRunDuration(run: any): string {
  if (!run.started_at) return '-'
  const start = new Date(run.started_at).getTime()
  const end = run.finished_at ? new Date(run.finished_at).getTime() : Date.now()
  const sec = Math.round((end - start) / 1000)
  if (sec < 60) return `${sec}s`
  if (sec < 3600) return `${Math.floor(sec / 60)}m ${sec % 60}s`
  return `${Math.floor(sec / 3600)}h ${Math.floor((sec % 3600) / 60)}m`
}

async function loadPipelines() {
  try {
    const { data: statsData } = await adminApi.pipelineAdminStats()
    plStats.value = statsData
  } catch { /* ignore */ }
  // Pre-load pipeline names and runs
  await loadPipelineList()
  await loadPipelineRuns()
}

async function loadPipelineList() {
  try {
    const params: Record<string, string> = {}
    if (plTenantFilter.value.trim()) params.tenant_id = plTenantFilter.value.trim()
    const { data } = await adminApi.listAllPipelines(params)
    plList.value = data
    // Build name map for run display
    const nameMap: Record<string, string> = {}
    for (const p of data) {
      nameMap[p.id] = p.name
    }
    pipelineNameMap.value = { ...pipelineNameMap.value, ...nameMap }
  } catch { /* ignore */ }
}

async function loadPipelineRuns() {
  plRunsLoading.value = true
  try {
    const params: Record<string, string> = {}
    if (plRunTenantFilter.value.trim()) params.tenant_id = plRunTenantFilter.value.trim()
    if (plRunStatusFilter.value) params.status = plRunStatusFilter.value
    const { data } = await adminApi.listAllPipelineRuns(params)
    plRuns.value = data
  } catch { /* ignore */ }
  plRunsLoading.value = false
}

async function loadComponents() {
  try {
    const { data } = await adminApi.listAllPipelineComponents()
    plComponents.value = data
  } catch { /* ignore */ }
}

async function toggleRunExpand(runId: string) {
  if (plExpandedRunId.value === runId) {
    plExpandedRunId.value = null
    return
  }
  plExpandedRunId.value = runId
  plRunDetail.value = null
  plRunDetailLoading.value = true
  try {
    const { data } = await adminApi.getPipelineRunAdmin(runId)
    plRunDetail.value = data
  } catch { /* ignore */ }
  plRunDetailLoading.value = false
}

onMounted(() => {
  loadStats()
  loadJobs()
})
</script>

<style scoped>
/* .stats-row, .stat-card, .stat-value, .stat-label, .tab-bar, .tab-item now live in shared style.css */

.type-tag {
  display: inline-block; padding: 2px 10px; border-radius: 10px; font-size: 11px; font-weight: 500;
}
.type-python { background: color-mix(in oklch, var(--c-accent) 12%, #fff); color: var(--c-accent-text); }
.type-ray { background: color-mix(in oklch, var(--c-success) 12%, #fff); color: #386b47; }
.type-finetune { background: color-mix(in oklch, var(--cs-warn) 10%, #fff); color: var(--cs-warn); }

.source-tag {
  display: inline-block; padding: 1px 6px; border-radius: 3px;
  font-size: 11px; background: #f0f0f0; color: #666;
}

.detail-panel { background: #f9fafb; padding: 12px 16px 12px 40px; }
.detail-grid {
  display: grid; grid-template-columns: 1fr 1fr; gap: 6px 24px;
  font-size: 13px; color: #333;
}

.btn-icon-small {
  background: none; border: none; cursor: pointer; font-size: 11px;
  color: #999; padding: 2px 4px;
}
.expanded-row td { border-top: none !important; }

.obs-path-cell {
  max-width: 200px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  font-family: monospace; font-size: 12px;
}

.log-content {
  background: #1e1e1e; color: #d4d4d4; padding: 12px; border-radius: 4px;
  font-family: monospace; font-size: 12px; line-height: 1.5;
  max-height: 60vh; overflow-y: auto; white-space: pre-wrap; word-break: break-all;
}

.dialog-overlay {
  position: fixed; inset: 0; background: rgba(0,0,0,0.5); z-index: 1000;
  display: flex; align-items: center; justify-content: center;
}
.dialog-box {
  background: #fff; border-radius: 8px; box-shadow: 0 4px 24px rgba(0,0,0,0.15);
  display: flex; flex-direction: column;
}
.dialog-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 16px 20px; border-bottom: 1px solid #e5e5e5;
}
.dialog-header h3 { margin: 0; font-size: 16px; }
.dialog-close {
  background: none; border: none; font-size: 24px; cursor: pointer; color: #999;
}
.dialog-body { padding: 16px 20px; overflow-y: auto; }

.pool-status-tag {
  display: inline-block; padding: 1px 8px; border-radius: 3px; font-size: 12px; font-weight: 500;
}
.ps-idle { background: color-mix(in oklch, var(--c-success) 12%, #fff); color: #386b47; }
.ps-claimed { background: color-mix(in oklch, var(--c-accent) 12%, #fff); color: var(--c-accent-text); }
.ps-unknown { background: var(--c-bg-alt); color: var(--c-text-3); }

.warmpool-config {
  display: flex; align-items: center; gap: 20px; font-size: 13px; color: #666;
  margin-top: 16px; padding: 12px 0; border-top: 1px solid #f0f0f0;
}
.warmpool-config code {
  background: #f5f5f5; padding: 1px 6px; border-radius: 3px; font-size: 12px;
}

.sub-tab-bar {
  display: flex; gap: 4px; margin-bottom: 16px;
}
.sub-tab {
  padding: 5px 14px; cursor: pointer; font-size: 13px; color: #666;
  border: 1px solid #e5e5e5; border-radius: 4px; background: #fafafa;
}
.sub-tab.active { color: #fff; border-color: var(--c-accent); background: var(--c-accent); }

.category-tag {
  display: inline-block; padding: 2px 10px; border-radius: 10px; font-size: 11px; font-weight: 500;
}
.cat-data_prep { background: color-mix(in oklch, var(--c-primary) 10%, #fff); color: var(--c-primary); }
.cat-extract { background: color-mix(in oklch, var(--c-success) 12%, #fff); color: #386b47; }
.cat-clean { background: color-mix(in oklch, var(--cs-warn) 10%, #fff); color: var(--cs-warn); }
.cat-filter { background: color-mix(in oklch, var(--cs-severe) 10%, #fff); color: var(--cs-severe); }
.cat-qc { background: color-mix(in oklch, var(--c-accent) 10%, #fff); color: var(--c-accent-text); }
.cat-label { background: var(--c-bg-alt); color: var(--c-text-2); }
.cat-publish { background: color-mix(in oklch, var(--c-accent) 12%, #fff); color: var(--c-accent-text); }

.type-text { background: color-mix(in oklch, var(--c-primary) 10%, #fff); color: var(--c-primary); }
.type-video { background: color-mix(in oklch, var(--cs-severe) 10%, #fff); color: var(--cs-severe); }
.type-image { background: color-mix(in oklch, var(--cs-warn) 10%, #fff); color: var(--cs-warn); }
.type-audio { background: color-mix(in oklch, var(--c-success) 12%, #fff); color: #386b47; }
.type-document { background: color-mix(in oklch, var(--c-accent) 12%, #fff); color: var(--c-accent-text); }
.type-universal { background: var(--c-bg-alt); color: var(--c-text-2); }
</style>
