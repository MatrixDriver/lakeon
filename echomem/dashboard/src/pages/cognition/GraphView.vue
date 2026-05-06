<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import * as d3 from 'd3-force'
import { useCognitionStore } from '@/stores/cognition'
import EmptyState from '@/components/EmptyState.vue'

interface SimNode extends d3.SimulationNodeDatum { id: string; name: string; kind: string | null }
interface SimLink extends d3.SimulationLinkDatum<SimNode> { predicate: string; confidence: number }

const cog = useCognitionStore()
const router = useRouter()
const { graph, graphSeed, graphHops } = storeToRefs(cog)

const seedInput = ref(graphSeed.value)
const hopsInput = ref(graphHops.value)

const W = 800, H = 500

const simNodes = ref<SimNode[]>([])
const simLinks = ref<SimLink[]>([])
let simulation: d3.Simulation<SimNode, SimLink> | null = null

watch(() => graph.value, rebuild, { immediate: true })

function rebuild() {
  const nodeMap = new Map<string, SimNode>()
  for (const n of graph.value.nodes) nodeMap.set(n.id, { ...n })
  const links: SimLink[] = graph.value.edges.map((e) => ({
    source: nodeMap.get(e.subject_id) ?? e.subject_id,
    target: nodeMap.get(e.object_id) ?? e.object_id,
    predicate: e.predicate, confidence: e.confidence,
  }))
  simNodes.value = Array.from(nodeMap.values())
  simLinks.value = links

  if (simulation) simulation.stop()
  simulation = d3.forceSimulation(simNodes.value)
    .force('link', d3.forceLink<SimNode, SimLink>(simLinks.value).id((d) => d.id).distance(80))
    .force('charge', d3.forceManyBody().strength(-220))
    .force('center', d3.forceCenter(W / 2, H / 2))
    .force('collide', d3.forceCollide(20))
    .on('tick', () => {})
}

async function search() {
  cog.graphSeed = seedInput.value
  cog.graphHops = hopsInput.value
  await cog.loadGraph()
}

function radius(d: SimNode) {
  return 8 + Math.min(20, simLinks.value.filter((l) =>
    (l.source as SimNode).id === d.id || (l.target as SimNode).id === d.id
  ).length * 2)
}

function open(id: string) {
  router.push({ query: { ...router.currentRoute.value.query, lineage: id, kind: 'graph' } })
}

onMounted(() => { if (graphSeed.value) rebuild() })
onUnmounted(() => simulation?.stop())

const hasGraph = computed(() => simNodes.value.length > 0)
</script>

<template>
  <div class="graph">
    <header class="hd">
      <input v-model="seedInput" type="text" placeholder="seed (e.g. ent:jacky)" />
      <select v-model.number="hopsInput">
        <option :value="1">1 hop</option>
        <option :value="2">2 hops</option>
        <option :value="3">3 hops</option>
      </select>
      <button class="go" @click="search">查询</button>
    </header>
    <svg v-if="hasGraph" :viewBox="`0 0 ${W} ${H}`" class="canvas">
      <line v-for="(l, i) in simLinks" :key="i"
            :x1="(l.source as SimNode).x ?? 0" :y1="(l.source as SimNode).y ?? 0"
            :x2="(l.target as SimNode).x ?? 0" :y2="(l.target as SimNode).y ?? 0"
            stroke="var(--c-border)" stroke-width="1" />
      <g v-for="n in simNodes" :key="n.id" :transform="`translate(${n.x ?? 0},${n.y ?? 0})`"
         @click="open(n.id)" style="cursor: pointer">
        <circle :r="radius(n)" fill="var(--c-primary)" stroke="var(--c-bg)" stroke-width="2" />
        <text dy="-12" text-anchor="middle" font-size="11" fill="var(--c-text)">{{ n.name }}</text>
      </g>
    </svg>
    <EmptyState v-else
      title="输入种子节点开始查询"
      body="例如 ent:jacky · 节点会以力导向布局展开"
    />
  </div>
</template>

<style scoped>
.graph { display: flex; flex-direction: column; gap: var(--space-md); }
.hd { display: flex; gap: var(--space-sm); align-items: center; }
.hd input { padding: 6px 10px; border: 1px solid var(--c-border); border-radius: var(--radius-md); font-size: var(--fs-sm); width: 240px; font-family: var(--font-mono); }
.hd select { padding: 6px 8px; border: 1px solid var(--c-border); border-radius: var(--radius-md); font-size: var(--fs-sm); }
.go { background: var(--c-accent); color: white; border: none; padding: 6px 14px; border-radius: var(--radius-md); cursor: pointer; }
.canvas { width: 100%; height: 500px; background: var(--c-bg-canvas); border: 1px solid var(--c-border); border-radius: var(--radius-md); }
</style>
