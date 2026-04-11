<template>
  <div>
    <nav class="detail-breadcrumb">
      <router-link to="/databases" class="breadcrumb-back">← 数据库</router-link>
      <span class="breadcrumb-sep" aria-hidden="true">/</span>
      <span class="breadcrumb-current">{{ db?.name || '详情' }}</span>
    </nav>

    <header class="page-header">
      <div>
        <h1 class="page-title">{{ db?.name || '数据库详情' }}</h1>
        <p class="page-subtitle" v-if="db">
          {{ statusLabel(db.status) }} · {{ db.compute_size || '未指定规格' }}
          <template v-if="db.storage_limit_gb"> · {{ db.storage_limit_gb }} GB 存储上限</template>
        </p>
      </div>
      <div class="page-header-actions" v-if="db">
        <span class="status-chip" :class="'chip-' + db.status.toLowerCase()">
          <span class="chip-dot"></span>{{ db.status }}
        </span>
      </div>
    </header>

    <section class="detail-section" v-if="db">
      <h2 class="detail-section-title">基本信息</h2>
      <dl class="detail-grid">
        <div class="detail-row">
          <dt>数据库 ID</dt>
          <dd class="mono">{{ db.id }}</dd>
        </div>
        <div class="detail-row">
          <dt>名称</dt>
          <dd>{{ db.name }}</dd>
        </div>
        <div class="detail-row">
          <dt>租户 ID</dt>
          <dd class="mono">{{ db.tenant_id }}</dd>
        </div>
        <div class="detail-row">
          <dt>状态</dt>
          <dd>
            <span class="status-dot" :class="statusClass(db.status)"></span>
            {{ db.status }}
          </dd>
        </div>
        <div class="detail-row">
          <dt>计算规格</dt>
          <dd>{{ db.compute_size || '—' }}</dd>
        </div>
        <div class="detail-row">
          <dt>存储上限</dt>
          <dd>{{ db.storage_limit_gb ? db.storage_limit_gb + ' GB' : '—' }}</dd>
        </div>
        <div class="detail-row">
          <dt>Compute Pod</dt>
          <dd class="mono">{{ db.compute_pod_name || '—' }}</dd>
        </div>
        <div class="detail-row detail-row-wide">
          <dt>连接地址</dt>
          <dd class="mono mono-sm">{{ db.connection_uri || '—' }}</dd>
        </div>
        <div class="detail-row">
          <dt>最后活跃</dt>
          <dd>{{ db.last_active_at ? formatDate(db.last_active_at) : '—' }}</dd>
        </div>
        <div class="detail-row">
          <dt>创建时间</dt>
          <dd>{{ formatDate(db.created_at) }}</dd>
        </div>
      </dl>
    </section>

    <div v-if="!db && !loading" class="detail-missing">
      <div class="detail-missing-dash">—</div>
      <div class="detail-missing-title">数据库未找到</div>
      <div class="detail-missing-sub">该 ID 对应的数据库可能已被删除或从未存在</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { adminApi } from '../../api/admin'
import { formatDate } from '../../utils/format'

interface DatabaseInfo {
  id: string
  name: string
  tenant_id: string
  status: string
  compute_size?: string
  storage_limit_gb?: number
  compute_pod_name?: string
  connection_uri?: string
  last_active_at?: string
  created_at: string
}

const route = useRoute()
const db = ref<DatabaseInfo | null>(null)
const loading = ref(true)

function statusClass(status: string): string {
  switch (status) {
    case 'RUNNING': return 'dot-green'
    case 'SUSPENDED': return 'dot-gray'
    case 'CREATING': return 'dot-blue'
    case 'ERROR': return 'dot-red'
    case 'FAILED': return 'dot-red'
    default: return 'dot-gray'
  }
}

function statusLabel(status: string): string {
  switch (status) {
    case 'RUNNING': return '运行中'
    case 'SUSPENDED': return '已挂起'
    case 'CREATING': return '创建中'
    case 'ERROR': return '错误'
    case 'FAILED': return '已失败'
    case 'DELETED': return '已删除'
    default: return status
  }
}

onMounted(async () => {
  try {
    const res = await adminApi.getDatabase(route.params.id as string)
    db.value = res.data
  } catch (e) {
    console.error('Failed to load database', e)
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.detail-breadcrumb {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  margin-bottom: var(--space-md);
  font-size: 13px;
  color: var(--c-text-3);
}

.breadcrumb-back {
  color: var(--c-accent-text);
  text-decoration: none;
  transition: color 160ms ease-out;
}

.breadcrumb-back:hover {
  color: var(--c-accent-hover);
  text-decoration: underline;
  text-underline-offset: 3px;
}

.breadcrumb-sep {
  color: var(--c-border);
}

.breadcrumb-current {
  color: var(--c-text-2);
}

.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-xl);
  margin-bottom: var(--space-2xl);
  padding-bottom: var(--space-xl);
  border-bottom: 1px solid var(--c-border-light);
}

.status-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.04em;
  padding: 4px 12px;
  border-radius: 10px;
  background: var(--c-bg-alt);
  color: var(--c-text-2);
  white-space: nowrap;
}

.chip-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--c-text-3);
}

.chip-running {
  background: color-mix(in oklch, var(--c-success) 12%, #fff);
  color: #386b47;
}
.chip-running .chip-dot { background: var(--c-success); }

.chip-suspended .chip-dot { background: var(--c-text-3); }

.chip-creating {
  background: color-mix(in oklch, var(--c-primary) 10%, #fff);
  color: var(--c-primary);
}
.chip-creating .chip-dot { background: var(--c-primary); }

.chip-failed,
.chip-error {
  background: color-mix(in oklch, var(--cs-severe) 10%, #fff);
  color: var(--cs-severe);
}
.chip-failed .chip-dot,
.chip-error .chip-dot { background: var(--cs-severe); }

.chip-deleted {
  background: color-mix(in oklch, var(--c-accent) 10%, #fff);
  color: var(--c-accent-text);
}
.chip-deleted .chip-dot { background: var(--c-accent); }

.detail-section {
  background: #fff;
  border: 1px solid var(--c-border-light);
  border-radius: 8px;
  padding: var(--space-xl) var(--space-2xl) var(--space-2xl);
}

.detail-section-title {
  font-family: var(--font-display);
  font-size: 18px;
  font-weight: 500;
  color: var(--c-text);
  margin: 0 0 var(--space-lg);
  letter-spacing: -0.005em;
}

.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  column-gap: var(--space-3xl);
  row-gap: 0;
  margin: 0;
}

.detail-row {
  display: grid;
  grid-template-columns: 120px 1fr;
  align-items: baseline;
  gap: var(--space-lg);
  padding: var(--space-md) 0;
  border-bottom: 1px solid var(--c-border-light);
}

.detail-row-wide {
  grid-column: 1 / -1;
  grid-template-columns: 120px 1fr;
}

.detail-row:last-child,
.detail-row:nth-last-child(2):not(.detail-row-wide) {
  border-bottom: none;
}

.detail-row dt {
  font-size: 11px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--c-text-3);
}

.detail-row dd {
  font-size: 14px;
  color: var(--c-text);
  margin: 0;
  min-width: 0;
  word-break: break-word;
}

.detail-row dd.mono {
  font-family: var(--font-mono);
  font-size: 12px;
  letter-spacing: -0.01em;
  color: var(--c-text-2);
}

.detail-row dd.mono-sm {
  font-size: 11px;
}

/* Missing state */
.detail-missing {
  padding: var(--space-4xl) var(--space-xl);
  text-align: center;
  background: #fff;
  border: 1px solid var(--c-border-light);
  border-radius: 8px;
}

.detail-missing-dash {
  font-family: var(--font-display);
  font-weight: 300;
  font-size: 72px;
  line-height: 1;
  color: var(--c-border);
}

.detail-missing-title {
  margin-top: var(--space-md);
  font-family: var(--font-display);
  font-size: 18px;
  font-weight: 500;
  color: var(--c-text);
}

.detail-missing-sub {
  margin-top: var(--space-xs);
  font-size: 13px;
  color: var(--c-text-3);
}

@media (max-width: 768px) {
  .detail-section {
    padding: var(--space-lg);
  }

  .detail-grid {
    grid-template-columns: 1fr;
  }

  .detail-row,
  .detail-row-wide {
    grid-template-columns: 1fr;
    gap: 2px;
  }

  .detail-row dt {
    margin-bottom: 2px;
  }
}
</style>
