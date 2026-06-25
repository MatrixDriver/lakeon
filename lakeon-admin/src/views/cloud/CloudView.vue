<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">华为云控制台</h1>
    </div>

    <div class="section-card">
      <div class="section-header"><h3>部署架构</h3></div>
      <div v-if="loading" class="empty-text">加载中...</div>
      <div v-else-if="!topology" class="empty-text">暂无拓扑数据</div>
      <div class="arch-diagram" v-else>
        <div class="client-row">
          <ArchBox v-if="node('console')" :node="node('console')!" tone="access" compact />
          <ArchBox v-if="node('pg-client')" :node="node('pg-client')!" tone="access" compact />
          <ArchBox v-if="node('pg-client-pooled')" :node="node('pg-client-pooled')!" tone="access" compact />
        </div>

        <div class="down-link">
          <span>公网入口</span>
        </div>

        <div class="edge-row">
          <ArchBox v-if="node('api-elb')" :node="node('api-elb')!" tone="network" compact />
          <ArchBox v-if="node('pg-elb')" :node="node('pg-elb')!" tone="network" compact />
        </div>

        <div class="down-link split">
          <span>API / PG 分流</span>
        </div>

        <div class="cluster-row">
          <section class="cluster-panel control">
            <div class="cluster-title">
              <span>控制面 CCE</span>
              <small>dbay-control-cce</small>
            </div>
            <div class="cluster-grid control-grid">
              <ArchBox v-if="node('api-gateway')" :node="node('api-gateway')!" tone="network" compact />
              <ArchBox v-if="node('admin-api')" :node="node('admin-api')!" tone="control" compact />
              <ArchBox v-if="node('serving-api')" :node="node('serving-api')!" tone="control" compact />
              <ArchBox v-if="node('dicer')" :node="node('dicer')!" tone="control" compact />
            </div>
            <div class="cluster-note">admin / serving API 与 Dicer 都是集群内组件</div>
          </section>

          <section class="cluster-panel data">
            <div class="cluster-title">
              <span>数据面 CCE</span>
              <small>dbay-cce</small>
            </div>
            <div class="cluster-grid data-grid">
              <ArchBox v-if="node('data-proxy')" :node="node('data-proxy')!" tone="data" compact />
              <ArchBox v-if="node('compute-pool')" :node="node('compute-pool')!" tone="compute" compact />
              <ArchBox v-if="node('pageserver')" :node="node('pageserver')!" tone="storage" compact />
              <ArchBox v-if="node('safekeeper')" :node="node('safekeeper')!" tone="storage" compact />
              <ArchBox v-if="node('storage-broker')" :node="node('storage-broker')!" tone="storage" compact />
            </div>
            <div class="cluster-note">proxy 承接 PG；pageserver/safekeeper 提供 Neon 存储</div>
          </section>
        </div>

        <div class="private-link">
          <span>{{ edgeLabel('control-cce', 'data-cce') }}</span>
        </div>

        <div class="shared-row">
          <ArchBox v-if="node('rds')" :node="node('rds')!" tone="storage" compact />
          <ArchBox v-if="node('obs')" :node="node('obs')!" tone="storage" compact />
          <ArchBox v-if="node('aom')" :node="node('aom')!" tone="observability" compact />
          <ArchBox v-if="node('lts')" :node="node('lts')!" tone="observability" compact />
          <ArchBox v-if="node('swr')" :node="node('swr')!" tone="storage" compact />
        </div>
      </div>
    </div>

    <div class="section-card" style="margin-top: 16px;">
      <div class="section-header"><h3>资源清单</h3></div>
      <div v-if="!resources.length && !loading" class="empty-text">暂无资源数据</div>
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
              <th>说明</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in resources" :key="r.name">
              <td style="font-weight: 500;">{{ r.name }}</td>
              <td><span v-if="r.resourceId" class="resource-id-text" :title="r.resourceId">{{ r.resourceId }}</span><span v-else style="color:#ccc;">—</span></td>
              <td>{{ r.region }}</td>
              <td><span class="service-tag">{{ r.service }}</span></td>
              <td>{{ r.type }}</td>
              <td>
                <span class="status-dot" :class="r.status === 'ACTIVE' ? 'dot-green' : 'dot-red'"></span>
                {{ r.status }}
              </td>
              <td class="resource-desc">{{ r.description || '—' }}</td>
              <td>
                <a v-if="r.consoleUrl" :href="r.consoleUrl" target="_blank" rel="noopener" class="console-link">控制台</a>
                <span v-else style="color:#ccc;">—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { defineComponent, h, ref, computed, onMounted, type PropType } from 'vue'
import { adminApi } from '../../api/admin'

interface TopologyNode {
  id: string
  label: string
  sublabel: string
  desc: string
  type: string
}

interface TopologyEdge {
  from: string
  to: string
  label: string
}

interface Topology {
  nodes: TopologyNode[]
  edges: TopologyEdge[]
}

interface Resource {
  name: string
  resourceId: string
  region: string
  service: string
  type: string
  status: string
  consoleUrl: string
  description?: string
}

const latestTopology: Topology = {
  nodes: [
    { id: 'console', label: 'Console / Admin', sublabel: 'Railway', desc: 'Web UI', type: 'access' },
    { id: 'pg-client', label: 'PG Direct', sublabel: '<db>', desc: '长连接', type: 'access' },
    { id: 'pg-client-pooled', label: 'PG Pooled', sublabel: '<db>-pooler', desc: '短连接', type: 'access' },
    { id: 'api-elb', label: 'api.dbay.cloud', sublabel: 'ELB :8443', desc: 'HTTPS', type: 'network' },
    { id: 'pg-elb', label: 'pg.dbay.cloud', sublabel: 'ELB :4432', desc: 'PG wire', type: 'network' },
    { id: 'pooler-route', label: 'Pooled Route', sublabel: '-pooler', desc: 'PG suffix', type: 'network' },
    { id: 'control-cce', label: '控制面 CCE', sublabel: 'dbay-control-cce', desc: 'API + Dicer', type: 'control' },
    { id: 'api-gateway', label: 'api-gateway', sublabel: 'nginx 2/2', desc: ':8443', type: 'control' },
    { id: 'admin-api', label: 'admin-api', sublabel: 'pod 1/1', desc: '/api/v1/admin', type: 'control' },
    { id: 'serving-api', label: 'serving-api', sublabel: 'pod 1/1', desc: '/api/v1', type: 'control' },
    { id: 'split-api', label: 'Split API', sublabel: 'admin + serving', desc: '同镜像不同角色', type: 'control' },
    { id: 'dicer', label: 'dicer-assigner', sublabel: 'pod 1/1', desc: 'placement', type: 'control' },
    { id: 'data-proxy', label: 'proxy / pooler', sublabel: 'pod 2/2', desc: 'PG route', type: 'data' },
    { id: 'compute-pool', label: 'compute pods', sublabel: '按 DB 启动', desc: 'serverless PG', type: 'compute' },
    { id: 'data-cce', label: '数据面 CCE', sublabel: 'dbay-cce', desc: 'Neon data plane', type: 'data' },
    { id: 'storage-plane', label: 'Neon 存储', sublabel: 'page/WAL', desc: 'durable data', type: 'storage' },
    { id: 'pageserver', label: 'pageserver', sublabel: 'statefulset 3', desc: 'page cache', type: 'storage' },
    { id: 'safekeeper', label: 'safekeeper', sublabel: 'statefulset 3', desc: 'WAL quorum', type: 'storage' },
    { id: 'storage-broker', label: 'storage-broker', sublabel: 'service', desc: 'membership', type: 'storage' },
    { id: 'rds', label: 'RDS', sublabel: 'PostgreSQL', desc: 'metadata', type: 'storage' },
    { id: 'obs', label: 'OBS', sublabel: 'mainstore', desc: 'pages / WAL', type: 'storage' },
    { id: 'aom', label: 'AOM', sublabel: 'metrics', desc: 'monitoring', type: 'observability' },
    { id: 'lts', label: 'LTS', sublabel: 'logs', desc: 'diagnostics', type: 'observability' },
    { id: 'swr', label: 'SWR', sublabel: 'images', desc: 'registry', type: 'storage' },
  ],
  edges: [
    { from: 'console', to: 'api-elb', label: 'HTTPS API' },
    { from: 'api-elb', to: 'api-gateway', label: 'TCP TLS :8443' },
    { from: 'api-gateway', to: 'admin-api', label: 'admin path' },
    { from: 'api-gateway', to: 'serving-api', label: 'serving path' },
    { from: 'serving-api', to: 'dicer', label: 'placement / load' },
    { from: 'pg-client', to: 'pg-elb', label: 'Direct PG' },
    { from: 'pg-client-pooled', to: 'pg-elb', label: 'Pooled PG' },
    { from: 'pg-elb', to: 'data-proxy', label: 'Service LB' },
    { from: 'pooler-route', to: 'data-proxy', label: 'endpoint route' },
    { from: 'dicer', to: 'data-cce', label: 'pageserver load / membership' },
    { from: 'control-cce', to: 'data-cce', label: '私网 kube API · Dicer 读取 pageserver 负载' },
    { from: 'data-proxy', to: 'compute-pool', label: 'wake / route' },
    { from: 'compute-pool', to: 'storage-plane', label: 'Neon page/WAL' },
    { from: 'control-cce', to: 'rds', label: '元数据读写' },
    { from: 'data-cce', to: 'obs', label: '远端存储' },
    { from: 'data-cce', to: 'aom', label: 'metrics 写入' },
    { from: 'control-cce', to: 'aom', label: 'metrics 读取' },
    { from: 'control-cce', to: 'lts', label: '日志写入' },
    { from: 'data-cce', to: 'lts', label: '日志写入' },
    { from: 'control-cce', to: 'swr', label: '镜像拉取' },
    { from: 'data-cce', to: 'swr', label: '镜像拉取' },
  ],
}

const latestResources: Resource[] = [
  {
    name: '控制面 CCE dbay-control-cce',
    resourceId: '377df1d7-663c-11f1-b0ad-0255ac100240',
    region: 'cn-north-4',
    service: 'CCE',
    type: '容器集群',
    status: 'ACTIVE',
    consoleUrl: 'https://console.huaweicloud.com/cce2.0/?region=cn-north-4#/app/cluster/detail?id=377df1d7-663c-11f1-b0ad-0255ac100240',
    description: '运行 api-gateway、admin-api、serving-api、dicer-assigner；无状态副本，状态写入 RDS。',
  },
  {
    name: 'Dicer Assigner',
    resourceId: 'deployment/dicer-assigner',
    region: 'cn-north-4',
    service: 'CCE',
    type: 'Deployment',
    status: 'ACTIVE',
    consoleUrl: 'https://console.huaweicloud.com/cce2.0/?region=cn-north-4#/app/cluster/detail/workload?clusterId=377df1d7-663c-11f1-b0ad-0255ac100240&namespace=lakeon&workloadType=Deployment&workloadName=dicer-assigner',
    description: 'Databricks Dicer 负载感知 assigner，提供 pageserver placement 决策输入。',
  },
  {
    name: '数据面 CCE dbay-cce',
    resourceId: '9fa5350b-1780-11f1-842d-0255ac100249',
    region: 'cn-north-4',
    service: 'CCE',
    type: '容器集群',
    status: 'ACTIVE',
    consoleUrl: 'https://console.huaweicloud.com/cce2.0/?region=cn-north-4#/app/cluster/detail?id=9fa5350b-1780-11f1-842d-0255ac100249',
    description: '运行 proxy、Neon compute pods、pageserver、safekeeper、storage-broker/controller。',
  },
  {
    name: '数据面 CCE 弹性节点池 dbay-compute-pool',
    resourceId: '55859b9b',
    region: 'cn-north-4',
    service: 'CCE',
    type: '节点池',
    status: 'ACTIVE',
    consoleUrl: 'https://console.huaweicloud.com/cce2.0/?region=cn-north-4#/app/cluster/detail/nodePool?id=9fa5350b-1780-11f1-842d-0255ac100249&poolId=55859b9b',
    description: '承载 Neon compute pod；每个 DB / branch 可启动独立 compute pod。',
  },
  {
    name: '公网 ELB api.dbay.cloud / pg.dbay.cloud',
    resourceId: '46b20c38-5c54-4781-9f00-d25d8c1717a8',
    region: 'cn-north-4',
    service: 'ELB',
    type: '弹性负载均衡',
    status: 'ACTIVE',
    consoleUrl: 'https://console.huaweicloud.com/elb/?region=cn-north-4#/elb/detail/46b20c38-5c54-4781-9f00-d25d8c1717a8',
    description: '同一独享 ELB 提供 API :8443 到控制面、PG :4432 到数据面 proxy。',
  },
  {
    name: 'EIP 122.9.12.37',
    resourceId: '0068faa1-8e37-46d0-b6b6-683ab9e4c085',
    region: 'cn-north-4',
    service: 'EIP',
    type: '弹性公网IP',
    status: 'ACTIVE',
    consoleUrl: 'https://console.huaweicloud.com/vpc/?region=cn-north-4#/eips/detail/0068faa1-8e37-46d0-b6b6-683ab9e4c085',
    description: '公网入口 IP；DNS 解析到 api.dbay.cloud 与 pg.dbay.cloud。',
  },
  {
    name: 'RDS PostgreSQL',
    resourceId: 'f7e6a949fe8c4177bf074a03bb747d87in03',
    region: 'cn-north-4',
    service: 'RDS',
    type: '关系型数据库',
    status: 'ACTIVE',
    consoleUrl: 'https://console.huaweicloud.com/rds/?region=cn-north-4#/rds/management/list/pg/f7e6a949fe8c4177bf074a03bb747d87in03/summary',
    description: '控制面元数据库，保存租户、DB、branch、Placement、审计和操作状态。',
  },
  {
    name: 'OBS dbay-mainstore',
    resourceId: 'dbay-mainstore',
    region: 'cn-north-4',
    service: 'OBS',
    type: '对象存储桶',
    status: 'ACTIVE',
    consoleUrl: 'https://console.huaweicloud.com/obs/?region=cn-north-4#/obs/manage/dbay-mainstore/overview',
    description: '保存数据页文件、索引文件、WAL 归档和备份快照。',
  },
  {
    name: 'AOM / Prometheus',
    resourceId: '',
    region: 'cn-north-4',
    service: 'AOM',
    type: '指标服务',
    status: 'ACTIVE',
    consoleUrl: 'https://console.huaweicloud.com/aom/?region=cn-north-4',
    description: '数据面 CCE 写入 metrics；控制面读取指标并驱动 DBay Scale Controller。',
  },
  {
    name: 'LTS 日志服务',
    resourceId: '',
    region: 'cn-north-4',
    service: 'LTS',
    type: '日志服务',
    status: 'ACTIVE',
    consoleUrl: 'https://console.huaweicloud.com/lts/?region=cn-north-4',
    description: '控制面与数据面 CCE 写入应用日志、审计日志，用于排障和运营分析。',
  },
  {
    name: 'SWR 镜像仓库 (flex)',
    resourceId: '',
    region: 'cn-north-4',
    service: 'SWR',
    type: '容器镜像仓库',
    status: 'ACTIVE',
    consoleUrl: 'https://console.huaweicloud.com/swr/?region=cn-north-4#/swr/organization/list',
    description: '存放控制面、数据面、Neon compute 和工具镜像。',
  },
  {
    name: 'Railway Console',
    resourceId: '',
    region: '海外 (Singapore)',
    service: 'Railway',
    type: 'Web 托管',
    status: 'ACTIVE',
    consoleUrl: 'https://railway.com/dashboard',
    description: 'dbay.cloud 用户控制台，通过 api.dbay.cloud 访问控制面 API。',
  },
  {
    name: 'Railway Admin',
    resourceId: '',
    region: '海外 (Singapore)',
    service: 'Railway',
    type: 'Web 托管',
    status: 'ACTIVE',
    consoleUrl: 'https://railway.com/dashboard',
    description: 'SRE 控制台，通过 api.dbay.cloud 访问 admin API。',
  },
]

const loading = ref(true)
const resources = ref<Resource[]>([])
const topology = ref<Topology | null>(null)

const nodeMap = computed(() => {
  const map = new Map<string, TopologyNode>()
  for (const item of topology.value?.nodes || []) {
    map.set(item.id, item)
  }
  return map
})

function node(id: string) {
  return nodeMap.value.get(id)
}

function edgeLabel(from: string, to: string) {
  return topology.value?.edges.find(edge => edge.from === from && edge.to === to)?.label || ''
}

function normalizeTopology(data: Topology | null | undefined) {
  if (!data) {
    return latestTopology
  }
  const nodeIds = new Set(data?.nodes?.map(item => item.id) || [])
  if (
    nodeIds.has('admin-api') &&
    nodeIds.has('serving-api') &&
    nodeIds.has('pageserver') &&
    nodeIds.has('safekeeper')
  ) {
    return data
  }
  return latestTopology
}

function normalizeResources(data: Resource[] | null | undefined) {
  if (data?.some(item => item.name.includes('控制面 CCE')) && data.some(item => item.name.includes('数据面 CCE'))) {
    return data
  }
  return latestResources
}

const ArchBox = defineComponent({
  name: 'ArchBox',
  props: {
    node: { type: Object as PropType<TopologyNode>, required: true },
    tone: { type: String, default: 'default' },
    wide: { type: Boolean, default: false },
    compact: { type: Boolean, default: false },
  },
  setup(props) {
    return () => h('div', { class: ['arch-box', `tone-${props.tone}`, { wide: props.wide, compact: props.compact }] }, [
      h('div', { class: 'arch-box-label' }, props.node.label),
      h('div', { class: 'arch-box-value' }, props.node.sublabel),
      h('div', { class: 'arch-box-desc' }, props.node.desc),
    ])
  },
})

onMounted(async () => {
  try {
    const res = await adminApi.cloudResources()
    const data = res.data
    resources.value = normalizeResources(data.resources)
    topology.value = normalizeTopology(data.topology)
  } catch (e) {
    console.error('Failed to load cloud resources', e)
    resources.value = latestResources
    topology.value = latestTopology
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.section-card { background: #fff; border: 1px solid #e5e5e5; border-radius: 6px; overflow: hidden; }
.section-header { padding: 12px 16px; border-bottom: 1px solid #f0f0f0; }
.section-header h3 { margin: 0; font-size: 14px; font-weight: 600; }
.empty-text { padding: 24px; text-align: center; color: #999; }
.resource-id-text { font-family: monospace; font-size: 12px; color: #666; }
.resource-desc { max-width: 300px; color: var(--c-text-2); line-height: 1.45; }
.service-tag { font-size: 11px; padding: 2px 10px; border-radius: 10px; background: color-mix(in oklch, var(--c-primary) 10%, #fff); color: var(--c-primary); font-weight: 500; }
.console-link { color: var(--c-accent-text); text-decoration: none; font-size: 13px; }
.console-link:hover { color: var(--c-accent-hover); text-decoration: underline; text-underline-offset: 3px; }
.arch-diagram {
  padding: 14px 16px 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  background: color-mix(in oklch, var(--c-bg-alt) 76%, #fff);
}
.client-row,
.edge-row,
.shared-row {
  display: grid;
  gap: 8px;
}
.client-row {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}
.edge-row {
  align-self: center;
  width: min(620px, 100%);
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
.shared-row {
  grid-template-columns: repeat(5, minmax(0, 1fr));
}
.cluster-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.18fr);
  gap: 10px;
}
.cluster-panel {
  border: 1px solid var(--c-border);
  border-radius: 8px;
  padding: 10px;
  background: rgba(255, 255, 255, 0.72);
  min-width: 0;
}
.cluster-panel.control {
  border-color: color-mix(in oklch, var(--c-primary) 32%, var(--c-border));
}
.cluster-panel.data {
  border-color: color-mix(in oklch, var(--c-accent) 28%, var(--c-border));
}
.cluster-title {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 8px;
  color: var(--c-text);
}
.cluster-title span {
  font-family: var(--font-display);
  font-size: 17px;
  font-weight: 600;
}
.cluster-title small,
.cluster-note {
  color: var(--c-text-2);
  font-size: 11px;
}
.cluster-note {
  margin-top: 8px;
  line-height: 1.35;
}
.cluster-grid {
  display: grid;
  gap: 8px;
}
.control-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
.data-grid {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}
.arch-box {
  border: 1px solid var(--c-border);
  background: color-mix(in oklch, #fff 88%, var(--c-bg-alt));
  border-radius: 7px;
  padding: 9px 10px;
  min-height: 68px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  box-shadow: 0 1px 0 rgba(42, 77, 106, 0.04);
  min-width: 0;
}
.arch-box.wide { min-width: 0; }
.arch-box.compact {
  min-height: 56px;
  padding: 7px 9px;
}
.arch-box-label {
  font-family: var(--font-sans);
  font-weight: 600;
  font-size: 13px;
  color: var(--c-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.arch-box-value {
  font-size: 11px;
  color: var(--c-text-2);
  margin-top: 2px;
  font-family: var(--font-mono);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.arch-box-desc {
  font-size: 11px;
  color: var(--c-text-2);
  margin-top: 3px;
  line-height: 1.25;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.tone-access { border-color: color-mix(in oklch, var(--c-primary) 20%, var(--c-border)); }
.tone-network { border-color: color-mix(in oklch, var(--c-accent) 38%, var(--c-border)); }
.tone-control {
  border-color: color-mix(in oklch, var(--c-primary) 38%, var(--c-border));
  background: color-mix(in oklch, var(--c-primary) 5%, #fff);
}
.tone-data {
  border-color: color-mix(in oklch, var(--c-primary) 30%, var(--c-border));
  background: color-mix(in oklch, var(--c-bg-alt) 58%, #fff);
}
.tone-compute {
  border-color: color-mix(in oklch, var(--c-accent) 32%, var(--c-border));
  background: color-mix(in oklch, var(--c-accent-light) 34%, #fff);
}
.tone-storage { border-color: color-mix(in oklch, var(--c-success) 30%, var(--c-border)); }
.tone-observability {
  border-color: color-mix(in oklch, var(--c-accent) 24%, var(--c-border));
  background: color-mix(in oklch, var(--c-accent-light) 24%, #fff);
}
.down-link {
  align-self: center;
  width: min(360px, 80%);
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--c-text-2);
  font-size: 11px;
  position: relative;
}
.down-link::before {
  content: "";
  position: absolute;
  top: 0;
  bottom: 0;
  left: 50%;
  border-left: 1px solid color-mix(in oklch, var(--c-text-3) 68%, transparent);
}
.down-link::after {
  content: "";
  position: absolute;
  bottom: 1px;
  left: calc(50% - 4px);
  width: 8px;
  height: 8px;
  border-right: 1px solid color-mix(in oklch, var(--c-text-3) 68%, transparent);
  border-bottom: 1px solid color-mix(in oklch, var(--c-text-3) 68%, transparent);
  transform: rotate(45deg);
}
.down-link span {
  position: relative;
  z-index: 1;
  background: color-mix(in oklch, var(--c-bg-alt) 86%, #fff);
  padding: 0 8px;
}
.private-link {
  align-self: center;
  width: min(620px, 100%);
  border: 1px dashed color-mix(in oklch, var(--c-primary) 38%, var(--c-border));
  border-radius: 8px;
  padding: 5px 12px;
  text-align: center;
  font-size: 11px;
  color: var(--c-primary);
  background: #fff;
}

@media (max-width: 1180px) {
  .cluster-row {
    grid-template-columns: 1fr;
  }
  .shared-row {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 760px) {
  .client-row,
  .edge-row,
  .control-grid,
  .data-grid,
  .shared-row {
    grid-template-columns: 1fr;
  }
}
</style>
