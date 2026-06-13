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
    resources.value = data.resources || []
    topology.value = data.topology || null
  } catch (e) {
    console.error('Failed to load cloud resources', e)
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
