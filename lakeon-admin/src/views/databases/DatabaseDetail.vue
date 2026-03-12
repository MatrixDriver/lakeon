<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">
        <router-link to="/databases" class="back-link">&larr; 数据库实例</router-link>
        <span v-if="db"> / {{ db.name }}</span>
      </h1>
    </div>

    <div class="section-card" v-if="db">
      <div class="section-header">
        <h3>基本信息</h3>
      </div>
      <div class="detail-grid">
        <div class="detail-item">
          <span class="detail-label">数据库 ID</span>
          <span class="detail-value mono">{{ db.id }}</span>
        </div>
        <div class="detail-item">
          <span class="detail-label">名称</span>
          <span class="detail-value">{{ db.name }}</span>
        </div>
        <div class="detail-item">
          <span class="detail-label">租户 ID</span>
          <span class="detail-value mono">{{ db.tenant_id }}</span>
        </div>
        <div class="detail-item">
          <span class="detail-label">状态</span>
          <span class="detail-value">
            <span class="status-dot" :class="statusClass(db.status)"></span>
            {{ db.status }}
          </span>
        </div>
        <div class="detail-item">
          <span class="detail-label">计算规格</span>
          <span class="detail-value">{{ db.compute_size || '-' }}</span>
        </div>
        <div class="detail-item">
          <span class="detail-label">存储上限</span>
          <span class="detail-value">{{ db.storage_limit_gb ? db.storage_limit_gb + ' GB' : '-' }}</span>
        </div>
        <div class="detail-item">
          <span class="detail-label">Compute Pod</span>
          <span class="detail-value mono">{{ db.compute_pod_name || '-' }}</span>
        </div>
        <div class="detail-item">
          <span class="detail-label">连接地址</span>
          <span class="detail-value mono" style="font-size: 12px;">{{ db.connection_uri || '-' }}</span>
        </div>
        <div class="detail-item">
          <span class="detail-label">最后活跃</span>
          <span class="detail-value">{{ db.last_active_at ? formatDate(db.last_active_at) : '-' }}</span>
        </div>
        <div class="detail-item">
          <span class="detail-label">创建时间</span>
          <span class="detail-value">{{ formatDate(db.created_at) }}</span>
        </div>
      </div>
    </div>

    <div v-if="!db && !loading" class="section-card" style="padding: 24px; text-align: center; color: #999;">
      数据库未找到
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
    default: return 'dot-gray'
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
.back-link {
  color: #0052d9;
  text-decoration: none;
  font-size: 16px;
}
.back-link:hover {
  text-decoration: underline;
}

.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0;
  padding: 0 16px 16px;
}

.detail-item {
  display: flex;
  flex-direction: column;
  padding: 12px 0;
  border-bottom: 1px solid #f0f0f0;
}

.detail-label {
  font-size: 13px;
  color: #8a8f99;
  margin-bottom: 4px;
}

.detail-value {
  font-size: 14px;
  color: #191919;
}

.mono {
  font-family: monospace;
  font-size: 13px;
}

@media (max-width: 768px) {
  .detail-grid {
    grid-template-columns: 1fr;
  }

  .mono {
    word-break: break-all;
  }
}
</style>
