<template>
  <div class="graph-container" ref="containerRef">
    <!-- Type filters -->
    <div class="graph-filters">
      <label v-for="(color, type) in nodeColors" :key="type" class="filter-label">
        <input type="checkbox" :checked="visibleTypes.has(type)" @change="toggleType(type)" />
        <span class="filter-dot" :style="{ background: color }"></span>
        {{ type }}
      </label>
    </div>

    <!-- SVG -->
    <svg ref="svgRef" :width="width" :height="height">
      <g ref="gRef">
        <line v-for="(edge, i) in filteredEdges" :key="'e'+i"
          :x1="nodePositions[edgeSourceKey(edge)]?.x || 0"
          :y1="nodePositions[edgeSourceKey(edge)]?.y || 0"
          :x2="nodePositions[edgeTargetKey(edge)]?.x || 0"
          :y2="nodePositions[edgeTargetKey(edge)]?.y || 0"
          stroke="#ddd" stroke-width="1" />
        <g v-for="node in filteredNodes" :key="nodeKey(node)"
          :transform="`translate(${nodePositions[nodeKey(node)]?.x || 0},${nodePositions[nodeKey(node)]?.y || 0})`"
          @click="selectNode(node)" style="cursor:pointer;">
          <circle r="8" :fill="nodeColors[node.node_type] || '#999'" stroke="#fff" stroke-width="1.5" />
          <text dx="12" dy="4" font-size="11" fill="#666">{{ node.node_id.length > 20 ? node.node_id.slice(0, 20) + '...' : node.node_id }}</text>
        </g>
      </g>
    </svg>

    <!-- Selected node panel -->
    <div v-if="selectedNode" class="node-panel">
      <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px;">
        <h4 style="margin:0;">{{ selectedNode.node_id }}</h4>
        <button class="dialog-close" @click="selectedNode = null" style="position:static;">&times;</button>
      </div>
      <div style="font-size:12px;color:#999;margin-bottom:8px;">类型: {{ selectedNode.node_type }}</div>
      <div v-if="Object.keys(selectedNode.properties).length" style="font-size:13px;">
        <div v-for="(val, key) in selectedNode.properties" :key="key" style="margin-bottom:4px;">
          <span style="color:#999;">{{ key }}:</span> {{ val }}
        </div>
      </div>
    </div>

    <!-- Empty state -->
    <div v-if="nodes.length === 0" style="position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);text-align:center;">
      <p style="color:#999;">知识图谱暂无数据</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch, reactive } from 'vue'
import * as d3 from 'd3'
import type { GraphData } from '../../api/memory'

const props = defineProps<{ data: GraphData }>()

const svgRef = ref<SVGSVGElement | null>(null)
const gRef = ref<SVGGElement | null>(null)
const containerRef = ref<HTMLDivElement | null>(null)
const width = ref(800)
const height = ref(500)
const selectedNode = ref<GraphData['nodes'][0] | null>(null)

const nodeColors: Record<string, string> = {
  person: '#1890ff',
  organization: '#389e0d',
  skill: '#d48806',
  location: '#e6393d',
  event: '#722ed1',
  entity: '#999',
}

const visibleTypes = ref(new Set(Object.keys(nodeColors)))

function toggleType(type: string) {
  const s = new Set(visibleTypes.value)
  if (s.has(type)) s.delete(type); else s.add(type)
  visibleTypes.value = s
}

const nodes = computed(() => props.data?.nodes || [])
const edges = computed(() => props.data?.edges || [])

const filteredNodes = computed(() =>
  nodes.value.filter(n => visibleTypes.value.has(n.node_type))
)
const filteredEdges = computed(() => {
  const nodeSet = new Set(filteredNodes.value.map(n => nodeKey(n)))
  return edges.value.filter(e => nodeSet.has(edgeSourceKey(e)) && nodeSet.has(edgeTargetKey(e)))
})

function nodeKey(n: { node_type: string; node_id: string }) { return `${n.node_type}:${n.node_id}` }
function edgeSourceKey(e: GraphData['edges'][0]) { return `${e.source_type}:${e.source_id}` }
function edgeTargetKey(e: GraphData['edges'][0]) { return `${e.target_type}:${e.target_id}` }

function selectNode(n: GraphData['nodes'][0]) { selectedNode.value = n }

const nodePositions = reactive<Record<string, { x: number; y: number }>>({})
let simulation: d3.Simulation<any, any> | null = null

function initSimulation() {
  if (!nodes.value.length) return

  const simNodes = nodes.value.map(n => ({
    id: nodeKey(n),
    ...n,
    x: width.value / 2 + (Math.random() - 0.5) * 200,
    y: height.value / 2 + (Math.random() - 0.5) * 200,
  }))

  const nodeMap = new Map(simNodes.map(n => [n.id, n]))

  const simLinks = edges.value
    .map(e => ({
      source: nodeMap.get(edgeSourceKey(e)),
      target: nodeMap.get(edgeTargetKey(e)),
    }))
    .filter((l): l is { source: (typeof simNodes)[0]; target: (typeof simNodes)[0] } => !!l.source && !!l.target)

  simulation = d3.forceSimulation(simNodes)
    .force('link', d3.forceLink(simLinks).distance(80))
    .force('charge', d3.forceManyBody().strength(-120))
    .force('center', d3.forceCenter(width.value / 2, height.value / 2))
    .on('tick', () => {
      for (const n of simNodes) {
        nodePositions[n.id] = { x: n.x!, y: n.y! }
      }
    })
}

function initZoom() {
  if (!svgRef.value || !gRef.value) return
  const svg = d3.select(svgRef.value)
  const g = d3.select(gRef.value)
  const zoom = d3.zoom<SVGSVGElement, unknown>()
    .scaleExtent([0.3, 5])
    .on('zoom', (event) => { g.attr('transform', event.transform) })
  svg.call(zoom)
}

onMounted(() => {
  if (containerRef.value) {
    width.value = containerRef.value.clientWidth || 800
    height.value = Math.max(400, containerRef.value.clientHeight || 500)
  }
  initSimulation()
  initZoom()
})

watch(() => props.data, () => {
  if (simulation) simulation.stop()
  initSimulation()
}, { deep: true })

onUnmounted(() => { if (simulation) simulation.stop() })
</script>

<style scoped>
.graph-container { position: relative; border: 1px solid #ebebeb; border-radius: 6px; overflow: hidden; min-height: 500px; }
.graph-filters { position: absolute; top: 12px; left: 12px; z-index: 1; display: flex; flex-wrap: wrap; gap: 8px; background: rgba(255,255,255,0.9); padding: 8px 12px; border-radius: 4px; border: 1px solid #ebebeb; }
.filter-label { display: flex; align-items: center; gap: 4px; font-size: 12px; color: #666; cursor: pointer; }
.filter-dot { width: 8px; height: 8px; border-radius: 50%; }
.node-panel { position: absolute; top: 12px; right: 12px; width: 240px; background: #fff; border: 1px solid #ebebeb; border-radius: 6px; padding: 16px; z-index: 1; box-shadow: 0 2px 8px rgba(0,0,0,0.08); }
</style>
