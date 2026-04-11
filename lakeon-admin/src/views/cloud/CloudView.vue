<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">华为云控制台</h1>
    </div>

    <!-- Architecture Diagram -->
    <div class="section-card">
      <div class="section-header"><h3>部署架构</h3></div>
      <div v-if="loading" class="empty-text">加载中...</div>
      <div v-else-if="!topology" class="empty-text">暂无拓扑数据</div>
      <div class="arch-diagram" v-else>
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
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { adminApi } from '../../api/admin'

const loading = ref(true)
const resources = ref<any[]>([])
const topology = ref<any>(null)

const topoGroups = computed(() => {
  if (!topology.value?.nodes) return []
  const groups: Record<string, { type: string; nodes: any[] }> = {}
  const order = ['railway', 'network', 'compute', 'storage']
  for (const node of topology.value.nodes) {
    const t = node.type || 'other'
    if (!groups[t]) groups[t] = { type: t, nodes: [] }
    groups[t].nodes.push(node)
  }
  return order.filter(t => groups[t]).map(t => groups[t]!)
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
.service-tag { font-size: 11px; padding: 2px 10px; border-radius: 10px; background: color-mix(in oklch, var(--c-primary) 10%, #fff); color: var(--c-primary); font-weight: 500; }
.console-link { color: var(--c-accent-text); text-decoration: none; font-size: 13px; }
.console-link:hover { color: var(--c-accent-hover); text-decoration: underline; text-underline-offset: 3px; }
.arch-diagram { padding: var(--space-lg); display: flex; flex-direction: column; gap: var(--space-md); }
.arch-row { display: flex; gap: var(--space-md); justify-content: center; flex-wrap: wrap; }
.arch-box { border: 1px solid var(--c-border); background: #fff; border-radius: 6px; padding: var(--space-md) var(--space-lg); min-width: 140px; text-align: center; }
.arch-box-label { font-family: var(--font-display); font-weight: 500; font-size: 14px; color: var(--c-text); }
.arch-box-value { font-size: 12px; color: var(--c-text-2); margin-top: 2px; }
.arch-box-desc { font-size: 11px; color: var(--c-text-3); margin-top: 2px; }
.arch-box-railway { border-color: color-mix(in oklch, var(--cs-warn) 30%, var(--c-border)); }
.arch-box-network { border-color: color-mix(in oklch, var(--c-accent) 30%, var(--c-border)); }
.arch-box-compute { border-color: color-mix(in oklch, var(--c-primary) 30%, var(--c-border)); }
.arch-box-storage { border-color: color-mix(in oklch, var(--c-success) 30%, var(--c-border)); }
</style>
