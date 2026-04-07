<script setup lang="ts">
// @ts-nocheck — d3 module-level vars are assigned in loadAndRender and read in navigateToNode
import { ref, onMounted, watch, onUnmounted } from 'vue'
import * as d3 from 'd3'
import { getWikiGraph } from '@/api/knowledge'

const props = defineProps<{ kbId: string }>()
const emit = defineEmits<{ (e: 'navigate', title: string): void }>()

const svgRef = ref<SVGSVGElement>()
let simulation: d3.Simulation<any, any> | null = null
let nodesData: any[] = []
let svgSelection: d3.Selection<SVGSVGElement, unknown, null, undefined> | null = null
let gSelection: d3.Selection<SVGGElement, unknown, null, undefined> | null = null
let zoomBehavior: d3.ZoomBehavior<SVGSVGElement, unknown> | null = null
let nodeSelection: d3.Selection<any, any, any, any> | null = null
let activeNodeLabel: string | null = null

async function loadAndRender() {
  if (!svgRef.value) return

  let data
  try {
    const resp = await getWikiGraph(props.kbId)
    data = resp.data
  } catch (e) {
    return
  }

  if (!data.nodes.length) return

  // Clean up previous simulation
  if (simulation) simulation.stop()

  const svg = d3.select(svgRef.value)
  svg.selectAll('*').remove()

  const width = svgRef.value.clientWidth || 800
  const height = svgRef.value.clientHeight || 500

  const g = svg.append('g')
  svgSelection = svg
  gSelection = g

  // Zoom
  zoomBehavior = d3.zoom<SVGSVGElement, unknown>()
    .scaleExtent([0.2, 4])
    .on('zoom', (event) => g.attr('transform', event.transform))
  svg.call(zoomBehavior)

  const nodes = data.nodes.map((n: any) => ({ ...n }))
  nodesData = nodes
  const edges = data.edges.map((e: any) => ({ ...e }))

  simulation = d3.forceSimulation(nodes)
    .force('link', d3.forceLink(edges).id((d: any) => d.id).distance(120))
    .force('charge', d3.forceManyBody().strength(-300))
    .force('center', d3.forceCenter(width / 2, height / 2))
    .force('collision', d3.forceCollide().radius(40))

  // Edges
  const link = g.append('g')
    .selectAll('line')
    .data(edges)
    .join('line')
    .attr('stroke', '#d4c4b0')
    .attr('stroke-width', 1.5)
    .attr('stroke-opacity', 0.6)

  // Nodes group
  const node = g.append('g')
    .selectAll('g')
    .data(nodes)
    .join('g')
    .attr('cursor', 'pointer')
    .call(d3.drag<any, any>()
      .on('start', (event, d: any) => {
        if (!event.active) simulation!.alphaTarget(0.3).restart()
        d.fx = d.x; d.fy = d.y
      })
      .on('drag', (event, d: any) => { d.fx = event.x; d.fy = event.y })
      .on('end', (event, d: any) => {
        if (!event.active) simulation!.alphaTarget(0)
        d.fx = null; d.fy = null
      })
    )
    .on('click', (_event, d: any) => emit('navigate', d.label))

  nodeSelection = node

  // Node circles
  node.append('circle')
    .attr('r', (d: any) => d.document_id ? 8 : 5)
    .attr('fill', (d: any) => d.document_id ? '#c25a3c' : '#d4c4b0')
    .attr('stroke', '#fff')
    .attr('stroke-width', 2)

  // Node labels
  node.append('text')
    .text((d: any) => d.label)
    .attr('dx', 12)
    .attr('dy', 4)
    .attr('font-size', '12px')
    .attr('fill', '#5a4a3a')
    .attr('font-family', 'inherit')

  // Hover effect
  node.on('mouseenter', function() {
    d3.select(this).select('circle').transition().duration(150).attr('r', 11)
  }).on('mouseleave', function(_, d: any) {
    d3.select(this).select('circle').transition().duration(150)
      .attr('r', d.document_id ? 8 : 5)
  })

  simulation.on('tick', () => {
    link
      .attr('x1', (d: any) => d.source.x)
      .attr('y1', (d: any) => d.source.y)
      .attr('x2', (d: any) => d.target.x)
      .attr('y2', (d: any) => d.target.y)
    node.attr('transform', (d: any) => `translate(${d.x},${d.y})`)
  })
}

watch(() => props.kbId, loadAndRender)
onMounted(loadAndRender)
onUnmounted(() => { if (simulation) simulation.stop() })

function focusNode(title: string) {
  if (!svgSelection || !zoomBehavior || !nodeSelection) return
  const target = nodesData.find((n: any) => n.label === title)
  if (!target || target.x == null || target.y == null) return

  const width = svgRef.value?.clientWidth || 800
  const height = svgRef.value?.clientHeight || 500

  // Animate zoom to center on target node
  const scale = 1.5
  const tx = width / 2 - target.x * scale
  const ty = height / 2 - target.y * scale
  svgSelection.transition().duration(500)
    .call(zoomBehavior.transform, d3.zoomIdentity.translate(tx, ty).scale(scale))

  // Highlight the target node, reset others
  activeNodeLabel = title
  nodeSelection.select('circle')
    .transition().duration(300)
    .attr('r', (d: any) => d.label === title ? 12 : (d.document_id ? 8 : 5))
    .attr('fill', (d: any) => d.label === title ? '#e07020' : (d.document_id ? '#c25a3c' : '#d4c4b0'))
    .attr('stroke', (d: any) => d.label === title ? '#fff5e0' : '#fff')
    .attr('stroke-width', (d: any) => d.label === title ? 3 : 2)
}

defineExpose({ focusNode })
</script>

<template>
  <div style="position: relative; width: 100%; height: 500px; border: 1px solid #e8e0d8; border-radius: 8px; overflow: hidden; background: #fdfbf8;">
    <svg ref="svgRef" style="width: 100%; height: 100%;" />
  </div>
</template>
