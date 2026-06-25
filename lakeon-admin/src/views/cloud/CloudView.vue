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
        <div class="arch-lane">
          <ArchBox v-if="node('console')" :node="node('console')!" tone="access" />
          <div class="arch-arrow">
            <span>{{ edgeLabel('console', 'api-elb') }}</span>
          </div>
          <ArchBox v-if="node('api-elb')" :node="node('api-elb')!" tone="network" />
          <div class="arch-arrow">
            <span>{{ edgeLabel('api-elb', 'control-cce') }}</span>
          </div>
          <ArchBox v-if="node('control-cce')" :node="node('control-cce')!" tone="control" wide />
        </div>

        <div class="arch-band control">
          <div class="band-title">控制面内部</div>
          <ArchBox v-if="node('api-gateway')" :node="node('api-gateway')!" tone="network" />
          <ArchBox v-if="node('split-api')" :node="node('split-api')!" tone="control" wide />
          <ArchBox v-if="node('dicer')" :node="node('dicer')!" tone="control" />
        </div>

        <div class="arch-lane">
          <ArchBox v-if="node('pg-client')" :node="node('pg-client')!" tone="access" />
          <div class="arch-arrow">
            <span>{{ edgeLabel('pg-client', 'pg-elb') }}</span>
          </div>
          <ArchBox v-if="node('pg-elb')" :node="node('pg-elb')!" tone="network" />
          <div class="arch-arrow">
            <span>{{ edgeLabel('pg-elb', 'data-cce') }}</span>
          </div>
          <ArchBox v-if="node('data-cce')" :node="node('data-cce')!" tone="data" wide />
        </div>

        <div class="private-link">
          <span>{{ edgeLabel('control-cce', 'data-cce') }}</span>
        </div>

        <div class="arch-band">
          <div class="band-title">数据面内部</div>
          <ArchBox v-if="node('compute-pool')" :node="node('compute-pool')!" tone="data" />
          <ArchBox v-if="node('storage-plane')" :node="node('storage-plane')!" tone="data" />
        </div>

        <div class="arch-band shared">
          <div class="band-title">同 VPC 共享云服务</div>
          <ArchBox v-if="node('rds')" :node="node('rds')!" tone="storage" />
          <ArchBox v-if="node('obs')" :node="node('obs')!" tone="storage" wide />
          <ArchBox v-if="node('aom')" :node="node('aom')!" tone="observability" />
          <ArchBox v-if="node('lts')" :node="node('lts')!" tone="observability" />
          <ArchBox v-if="node('swr')" :node="node('swr')!" tone="storage" />
        </div>

        <div class="edge-list">
          <div v-for="edge in topology.edges" :key="`${edge.from}-${edge.to}`" class="edge-item">
            <span class="edge-from">{{ labelOf(edge.from) }}</span>
            <span class="edge-line"></span>
            <span class="edge-to">{{ labelOf(edge.to) }}</span>
            <span class="edge-label">{{ edge.label }}</span>
          </div>
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
    { id: 'console', label: 'Console / SRE', sublabel: 'Railway', desc: '用户控制台与 SRE 控制台', type: 'access' },
    { id: 'pg-client', label: 'PG Client', sublabel: 'PostgreSQL 生态', desc: 'psql / JDBC / 现有 PG 应用', type: 'access' },
    { id: 'api-elb', label: 'api.dbay.cloud', sublabel: '公网 ELB :8443', desc: 'HTTPS API 入口，转发到控制面 CCE', type: 'network' },
    { id: 'pg-elb', label: 'pg.dbay.cloud', sublabel: '公网 ELB :4432', desc: 'PG 协议入口，转发到数据面 proxy', type: 'network' },
    { id: 'control-cce', label: '控制面 CCE', sublabel: 'dbay-control-cce', desc: 'api-gateway · admin-api · serving-api · dicer-assigner', type: 'control' },
    { id: 'api-gateway', label: 'API Gateway', sublabel: 'api.dbay.cloud :8443', desc: '按路径分流：SRE 请求到 admin-api，业务请求到 serving-api', type: 'control' },
    { id: 'split-api', label: 'Split API', sublabel: 'admin-api · serving-api', desc: 'admin-api 只服务 /api/v1/admin；serving-api 服务业务 API 与 proxy 授权', type: 'control' },
    { id: 'dicer', label: 'Dicer Assigner', sublabel: 'load-aware placement', desc: '消费 pageserver 实时负载，给租户与 shard 选择承载节点', type: 'control' },
    { id: 'data-cce', label: '数据面 CCE', sublabel: 'dbay-cce', desc: 'proxy · Neon compute pods · 3 pageserver · 3 safekeeper', type: 'data' },
    { id: 'compute-pool', label: 'CCE 弹性节点池', sublabel: 'dbay-compute-pool', desc: '每个 DB / branch 的 Neon compute pod 按需扩缩', type: 'data' },
    { id: 'storage-plane', label: 'Neon 存储层', sublabel: 'pageserver · safekeeper · broker', desc: 'safekeeper 常驻；pageserver 本地缓存，可从 OBS 恢复', type: 'data' },
    { id: 'rds', label: 'RDS PostgreSQL', sublabel: '元数据库', desc: '租户、DB、branch、Placement、审计状态', type: 'storage' },
    { id: 'obs', label: 'OBS 对象存储', sublabel: 'dbay-mainstore', desc: '数据页文件、索引文件、WAL 归档、备份快照', type: 'storage' },
    { id: 'aom', label: 'AOM / Prometheus', sublabel: 'metrics', desc: '数据面写入指标；控制面读取并驱动 Scale Controller', type: 'observability' },
    { id: 'lts', label: 'LTS 日志服务', sublabel: 'logs', desc: '控制面与数据面写入应用日志、审计日志', type: 'observability' },
    { id: 'swr', label: 'SWR 镜像仓库', sublabel: 'flex', desc: '控制面、数据面和 compute pod 镜像', type: 'storage' },
  ],
  edges: [
    { from: 'console', to: 'api-elb', label: 'HTTPS API' },
    { from: 'api-elb', to: 'api-gateway', label: 'TCP TLS :8443' },
    { from: 'api-gateway', to: 'split-api', label: 'Path routing' },
    { from: 'split-api', to: 'control-cce', label: 'K8s service' },
    { from: 'split-api', to: 'dicer', label: 'placement / load' },
    { from: 'pg-client', to: 'pg-elb', label: 'PG wire protocol' },
    { from: 'pg-elb', to: 'data-cce', label: 'Service LoadBalancer' },
    { from: 'dicer', to: 'data-cce', label: 'pageserver metrics' },
    { from: 'control-cce', to: 'data-cce', label: 'CCE 间私网 / 内网 ELB' },
    { from: 'data-cce', to: 'compute-pool', label: 'Kubernetes 调度' },
    { from: 'data-cce', to: 'storage-plane', label: 'Neon 热路径' },
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

function labelOf(id: string) {
  return node(id)?.label || id
}

function edgeLabel(from: string, to: string) {
  return topology.value?.edges.find(edge => edge.from === from && edge.to === to)?.label || ''
}

function normalizeTopology(data: Topology | null | undefined) {
  if (data?.nodes?.some(item => item.id === 'control-cce') && data.nodes.some(item => item.id === 'data-cce')) {
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
  },
  setup(props) {
    return () => h('div', { class: ['arch-box', `tone-${props.tone}`, { wide: props.wide }] }, [
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
  padding: var(--space-lg);
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
  background: color-mix(in oklch, var(--c-bg-alt) 78%, #fff);
}
.arch-lane {
  display: grid;
  grid-template-columns: minmax(170px, 1fr) minmax(92px, 0.55fr) minmax(170px, 1fr) minmax(92px, 0.55fr) minmax(260px, 1.35fr);
  gap: var(--space-sm);
  align-items: stretch;
}
.arch-box {
  border: 1px solid var(--c-border);
  background: #fff;
  border-radius: 6px;
  padding: var(--space-md);
  min-height: 112px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  box-shadow: 0 1px 0 rgba(42, 77, 106, 0.03);
}
.arch-box.wide { min-width: 0; }
.arch-box-label {
  font-family: var(--font-display);
  font-weight: 600;
  font-size: 16px;
  color: var(--c-text);
}
.arch-box-value {
  font-size: 12px;
  color: var(--c-text-2);
  margin-top: 4px;
  font-family: var(--font-mono);
}
.arch-box-desc {
  font-size: 12px;
  color: var(--c-text-2);
  margin-top: 8px;
  line-height: 1.45;
}
.tone-access { border-color: color-mix(in oklch, var(--cs-warn) 30%, var(--c-border)); }
.tone-network { border-color: color-mix(in oklch, var(--c-accent) 35%, var(--c-border)); }
.tone-control { border-color: color-mix(in oklch, var(--c-primary) 38%, var(--c-border)); background: color-mix(in oklch, var(--c-primary) 4%, #fff); }
.tone-data { border-color: color-mix(in oklch, var(--c-primary) 30%, var(--c-border)); background: color-mix(in oklch, var(--c-bg-alt) 55%, #fff); }
.tone-storage { border-color: color-mix(in oklch, var(--c-success) 30%, var(--c-border)); }
.tone-observability { border-color: color-mix(in oklch, var(--c-accent) 28%, var(--c-border)); background: color-mix(in oklch, var(--c-accent-light) 34%, #fff); }
.arch-arrow {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--c-text-2);
  font-size: 11px;
  text-align: center;
  position: relative;
  min-height: 112px;
}
.arch-arrow::before {
  content: "";
  position: absolute;
  left: 6px;
  right: 16px;
  top: 50%;
  border-top: 1px solid color-mix(in oklch, var(--c-text-3) 65%, transparent);
}
.arch-arrow::after {
  content: "";
  position: absolute;
  right: 8px;
  top: calc(50% - 4px);
  width: 8px;
  height: 8px;
  border-top: 1px solid color-mix(in oklch, var(--c-text-3) 65%, transparent);
  border-right: 1px solid color-mix(in oklch, var(--c-text-3) 65%, transparent);
  transform: rotate(45deg);
}
.arch-arrow span {
  position: relative;
  z-index: 1;
  background: color-mix(in oklch, var(--c-bg-alt) 78%, #fff);
  padding: 2px 6px;
}
.private-link {
  align-self: center;
  width: min(560px, 100%);
  border: 1px dashed color-mix(in oklch, var(--c-primary) 38%, var(--c-border));
  border-radius: 999px;
  padding: 7px 14px;
  text-align: center;
  font-size: 12px;
  color: var(--c-primary);
  background: #fff;
}
.arch-band {
  border: 1px solid var(--c-border);
  border-radius: 6px;
  padding: var(--space-md);
  display: grid;
  grid-template-columns: 150px repeat(2, minmax(220px, 1fr));
  gap: var(--space-sm);
  align-items: stretch;
  background: rgba(255, 255, 255, 0.64);
}
.arch-band.shared {
  grid-template-columns: 150px repeat(5, minmax(150px, 1fr));
}
.band-title {
  font-weight: 600;
  color: var(--c-text);
  align-self: center;
}
.edge-list {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--space-xs);
  padding-top: var(--space-xs);
}
.edge-item {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 6px;
  align-items: center;
  padding: 6px 8px;
  border: 1px solid var(--c-border);
  border-radius: 6px;
  background: #fff;
  font-size: 11px;
  color: var(--c-text-2);
}
.edge-line { border-top: 1px solid var(--c-border); min-width: 20px; }
.edge-label { grid-column: 1 / -1; color: var(--c-text-3); }
.edge-from,
.edge-to {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 1180px) {
  .arch-lane,
  .arch-band,
  .arch-band.shared,
  .edge-list {
    grid-template-columns: 1fr;
  }
  .arch-arrow {
    min-height: 42px;
  }
  .arch-arrow::before {
    left: 50%;
    right: auto;
    top: 5px;
    bottom: 13px;
    border-top: 0;
    border-left: 1px solid color-mix(in oklch, var(--c-text-3) 65%, transparent);
  }
  .arch-arrow::after {
    right: calc(50% - 4px);
    top: auto;
    bottom: 7px;
    transform: rotate(135deg);
  }
}
</style>
