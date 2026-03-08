<template>
  <div class="cloud-resources">
    <div class="page-header">
      <h2>云资源</h2>
      <button class="refresh-btn" @click="loadData" :disabled="loading">
        {{ loading ? '刷新中...' : '刷新' }}
      </button>
    </div>

    <!-- Architecture Diagram -->
    <div class="section">
      <h3 class="section-title">部署架构</h3>
      <div class="arch-diagram" v-if="topology">
        <!-- Railway Layer (External) -->
        <div class="arch-layer arch-layer-storage">
          <div class="arch-box arch-box-railway">
            <div class="arch-box-label">Railway (海外)</div>
            <div class="arch-box-value">dbay.cloud</div>
            <div class="arch-box-pods">Web 控制台 &middot; SRE Admin</div>
          </div>
        </div>

        <div class="arch-arrow">&#8595; 浏览器直连 API</div>

        <!-- EIP Layer -->
        <div class="arch-layer arch-layer-network" v-if="topology.eip">
          <a :href="topology.eip.console_url" target="_blank" class="arch-box arch-box-network">
            <div class="arch-box-label">EIP</div>
            <div class="arch-box-value">{{ topology.eip.ip }}</div>
          </a>
        </div>

        <div class="arch-arrow">&#8595;</div>

        <!-- ELB Layer -->
        <div class="arch-layer" v-if="topology.elb">
          <a :href="topology.elb.console_url" target="_blank" class="arch-box arch-box-network">
            <div class="arch-box-label">ELB</div>
            <div class="arch-box-value">{{ topology.elb.name }}</div>
            <div class="arch-box-ports">:8443 API (HTTPS) &middot; :4432 PG Proxy &middot; :8080 API (内部)</div>
          </a>
        </div>

        <div class="arch-arrow">&#8595;</div>

        <!-- CCE Cluster Layer -->
        <div class="arch-layer" v-if="topology.cce">
          <a :href="topology.cce.console_url" target="_blank" class="arch-box arch-box-compute arch-box-cluster">
            <div class="arch-box-label">CCE 集群</div>
            <div class="arch-box-value">{{ topology.cce.name }}</div>
          </a>
          <div class="arch-nodes">
            <a v-for="node in topology.cce.nodes" :key="node.name"
               :href="node.console_url" target="_blank"
               class="arch-box arch-box-compute arch-box-node">
              <div class="arch-box-label">节点</div>
              <div class="arch-box-value">{{ node.flavor }}</div>
              <div class="arch-box-status" :class="node.phase === 'Active' ? 'status-ok' : 'status-error'">
                {{ node.phase }}
              </div>
              <div class="arch-box-pods">
                pageserver &middot; safekeeper &middot; storage-broker<br>
                proxy &middot; lakeon-api (HTTPS, hostNetwork)
              </div>
            </a>
          </div>
        </div>

        <div class="arch-arrow">&#8595;</div>

        <!-- Storage Layer -->
        <div class="arch-layer arch-layer-storage">
          <a v-if="topology.rds" :href="topology.rds.console_url" target="_blank"
             class="arch-box arch-box-storage">
            <div class="arch-box-label">RDS PostgreSQL</div>
            <div class="arch-box-value">{{ topology.rds.name }}</div>
            <div class="arch-box-status" :class="isRdsHealthy ? 'status-ok' : 'status-error'">
              {{ topology.rds.status }}
            </div>
          </a>
          <a v-if="topology.obs" :href="topology.obs.console_url" target="_blank"
             class="arch-box arch-box-storage">
            <div class="arch-box-label">OBS 对象存储</div>
            <div class="arch-box-value">{{ topology.obs.bucket }}</div>
            <div class="arch-box-status status-ok">Active</div>
          </a>
        </div>
      </div>
      <div v-else-if="!loading" class="empty-state">暂无拓扑数据</div>
    </div>

    <!-- Resource Table -->
    <div class="section">
      <h3 class="section-title">资源清单</h3>
      <div class="table-container">
        <table class="resource-table">
          <thead>
            <tr>
              <th>名称</th>
              <th>区域</th>
              <th>服务</th>
              <th>资源类型</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in resources" :key="r.id">
              <td class="td-name">{{ r.name }}</td>
              <td>{{ r.region_name }}</td>
              <td><span class="service-tag" :class="serviceClass(r.service)">{{ r.service }}</span></td>
              <td>{{ r.resource_type }}</td>
              <td>
                <span class="status-dot" :class="isHealthyStatus(r.status) ? 'dot-ok' : 'dot-error'"></span>
                {{ r.status }}
              </td>
              <td>
                <a :href="r.console_url" target="_blank" class="detail-link">查看详情 &rarr;</a>
              </td>
            </tr>
            <tr v-if="resources.length === 0 && !loading">
              <td colspan="6" class="empty-cell">暂无资源数据</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { adminApi } from '../../api/admin'

interface Resource {
  name: string
  id: string
  region: string
  region_name: string
  service: string
  resource_type: string
  status: string
  console_url: string
  spec?: string
}

interface Topology {
  eip?: { ip: string; id: string; console_url: string }
  elb?: { name: string; id: string; console_url: string }
  cce?: {
    name: string; id: string; console_url: string
    nodes: { name: string; flavor: string; phase: string; console_url: string }[]
  }
  rds?: { name: string; id: string; status: string; console_url: string }
  obs?: { bucket: string; console_url: string }
}

const loading = ref(false)
const resources = ref<Resource[]>([])
const topology = ref<Topology | null>(null)

const isRdsHealthy = computed(() => {
  const s = topology.value?.rds?.status?.toUpperCase()
  return s === 'ACTIVE' || s === 'NORMAL'
})

function isHealthyStatus(status: string): boolean {
  const s = status?.toUpperCase()
  return s === 'ACTIVE' || s === 'NORMAL' || s === 'ONLINE' || s === 'OK'
}

function serviceClass(service: string): string {
  const map: Record<string, string> = {
    CCE: 'tag-compute', ECS: 'tag-compute', EVS: 'tag-compute',
    RDS: 'tag-storage', OBS: 'tag-storage',
    ELB: 'tag-network', VPC: 'tag-network',
  }
  return map[service] || ''
}

async function loadData() {
  loading.value = true
  try {
    const { data } = await adminApi.cloudResources()
    resources.value = data.resources || []
    topology.value = data.topology || null
  } catch (e) {
    console.error('Failed to load cloud resources', e)
  } finally {
    loading.value = false
  }
}

onMounted(loadData)
</script>

<style scoped>
.cloud-resources {
  max-width: 1200px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24px;
}

.page-header h2 {
  font-size: 20px;
  font-weight: 700;
  color: #191919;
  margin: 0;
}

.refresh-btn {
  padding: 6px 16px;
  border: 1px solid #d1d5db;
  border-radius: 4px;
  background: #fff;
  color: #333;
  font-size: 13px;
  cursor: pointer;
}

.refresh-btn:hover:not(:disabled) {
  border-color: #0073e6;
  color: #0073e6;
}

.refresh-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.section {
  margin-bottom: 32px;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  color: #191919;
  margin: 0 0 16px;
  padding-bottom: 8px;
  border-bottom: 1px solid #e5e5e5;
}

/* Architecture Diagram */
.arch-diagram {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 24px;
  background: #f9fafb;
  border: 1px solid #e5e5e5;
  border-radius: 8px;
}

.arch-layer {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.arch-layer-storage {
  flex-direction: row;
  gap: 16px;
}

.arch-arrow {
  color: #9ca3af;
  font-size: 18px;
  line-height: 1;
}

.arch-nodes {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  justify-content: center;
}

.arch-box {
  display: block;
  padding: 12px 20px;
  border-radius: 6px;
  border: 2px solid;
  text-decoration: none;
  text-align: center;
  min-width: 160px;
  transition: all 0.15s;
}

.arch-box:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}

.arch-box-compute {
  background: #eff6ff;
  border-color: #3b82f6;
}

.arch-box-storage {
  background: #f0fdf4;
  border-color: #22c55e;
}

.arch-box-network {
  background: #fff7ed;
  border-color: #f97316;
}

.arch-box-railway {
  background: #faf5ff;
  border-color: #a855f7;
}

.arch-box-label {
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  color: #6b7280;
  margin-bottom: 2px;
}

.arch-box-value {
  font-size: 14px;
  font-weight: 600;
  color: #191919;
}

.arch-box-ports {
  font-size: 11px;
  color: #6b7280;
  margin-top: 4px;
}

.arch-box-pods {
  font-size: 10px;
  color: #6b7280;
  margin-top: 6px;
  line-height: 1.5;
}

.arch-box-status {
  font-size: 11px;
  font-weight: 500;
  margin-top: 4px;
}

.status-ok {
  color: #16a34a;
}

.status-error {
  color: #dc2626;
}

.arch-box-cluster {
  min-width: 240px;
}

.arch-box-node {
  min-width: 200px;
}

/* Resource Table */
.table-container {
  border: 1px solid #e5e5e5;
  border-radius: 6px;
  overflow: hidden;
}

.resource-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}

.resource-table th {
  background: #f9fafb;
  padding: 10px 16px;
  text-align: left;
  font-weight: 600;
  color: #374151;
  border-bottom: 1px solid #e5e5e5;
}

.resource-table td {
  padding: 10px 16px;
  border-bottom: 1px solid #f3f4f6;
  color: #333;
}

.resource-table tr:last-child td {
  border-bottom: none;
}

.resource-table tr:hover {
  background: #f9fafb;
}

.td-name {
  font-weight: 500;
  max-width: 280px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.service-tag {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 3px;
  font-size: 12px;
  font-weight: 500;
}

.tag-compute {
  background: #dbeafe;
  color: #1d4ed8;
}

.tag-storage {
  background: #dcfce7;
  color: #15803d;
}

.tag-network {
  background: #ffedd5;
  color: #c2410c;
}

.status-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  margin-right: 6px;
  vertical-align: middle;
}

.dot-ok {
  background: #22c55e;
}

.dot-error {
  background: #ef4444;
}

.detail-link {
  color: #0073e6;
  text-decoration: none;
  font-size: 13px;
}

.detail-link:hover {
  text-decoration: underline;
}

.empty-state {
  text-align: center;
  color: #9ca3af;
  padding: 40px;
}

.empty-cell {
  text-align: center;
  color: #9ca3af;
  padding: 24px !important;
}
</style>
