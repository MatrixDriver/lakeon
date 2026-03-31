<template>
  <div class="branch-tree-container">
    <svg :width="svgWidth" :height="svgHeight" class="branch-tree-svg">
      <!-- Connection lines -->
      <path
        v-for="line in lines"
        :key="line.key"
        :d="line.d"
        fill="none"
        stroke="#c2c6cc"
        stroke-width="2"
      />
      <!-- Nodes -->
      <g
        v-for="node in layoutNodes"
        :key="node.id"
        :transform="`translate(${node.x}, ${node.y})`"
        class="tree-node"
        @click="handleSelect(node.id)"
      >
        <rect
          :width="nodeW"
          :height="nodeH"
          :x="-nodeW / 2"
          :y="-nodeH / 2"
          rx="6"
          ry="6"
          :fill="node.id === selectedId ? '#e6f0ff' : '#fff'"
          :stroke="node.id === activeBranchId ? '#9a5b25' : '#e5e5e5'"
          :stroke-width="node.id === activeBranchId ? 2.5 : 1.5"
        />
        <!-- Branch name -->
        <text
          :x="0"
          :y="-6"
          text-anchor="middle"
          class="node-name"
        >{{ truncate(node.name, 16) }}</text>
        <!-- LSN info -->
        <text
          :x="0"
          :y="12"
          text-anchor="middle"
          class="node-lsn"
        >{{ node.lastRecordLsn ? truncate(node.lastRecordLsn, 18) : '-' }}</text>
        <!-- Default badge -->
        <rect
          v-if="node.isDefault"
          :x="nodeW / 2 - 30"
          :y="-nodeH / 2 - 8"
          width="28"
          height="16"
          rx="3"
          fill="#fdf5ed"
          stroke="#c67d3a"
          stroke-width="0.5"
        />
        <text
          v-if="node.isDefault"
          :x="nodeW / 2 - 16"
          :y="-nodeH / 2 + 4"
          text-anchor="middle"
          class="node-badge"
        >main</text>
        <!-- Action buttons (right side) -->
        <g v-if="node.id === selectedId" class="node-actions">
          <!-- Activate button -->
          <g
            v-if="node.id !== activeBranchId"
            class="action-btn"
            @click.stop="emit('activate', node.id)"
          >
            <rect :x="nodeW / 2 + 4" :y="-20" width="40" height="20" rx="3" fill="#c67d3a" />
            <text :x="nodeW / 2 + 24" :y="-6" text-anchor="middle" class="action-text">切换</text>
          </g>
          <!-- Create child branch button -->
          <g
            class="action-btn"
            @click.stop="emit('create', node.id)"
          >
            <rect :x="nodeW / 2 + 4" :y="4" width="40" height="20" rx="3" fill="#f0f5ff" stroke="#c67d3a" stroke-width="0.5" />
            <text :x="nodeW / 2 + 24" :y="18" text-anchor="middle" class="action-text-outline">分支</text>
          </g>
          <!-- Delete button (non-default only) -->
          <g
            v-if="!node.isDefault"
            class="action-btn"
            @click.stop="emit('delete', node.id)"
          >
            <rect :x="nodeW / 2 + 4" :y="28" width="40" height="20" rx="3" fill="#fff1f0" stroke="#ff4d4f" stroke-width="0.5" />
            <text :x="nodeW / 2 + 24" :y="42" text-anchor="middle" class="action-text-danger">删除</text>
          </g>
        </g>
      </g>
    </svg>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { BranchTreeNode } from '../api/branch'

const props = defineProps<{
  nodes: BranchTreeNode[]
  activeBranchId: string
}>()

const emit = defineEmits<{
  select: [branchId: string]
  activate: [branchId: string]
  create: [parentBranchId: string]
  delete: [branchId: string]
}>()

const selectedId = ref('')

watch(() => props.nodes, () => { selectedId.value = '' })

function handleSelect(id: string) {
  selectedId.value = selectedId.value === id ? '' : id
  emit('select', id)
}

const nodeW = 180
const nodeH = 60
const levelGap = 100
const siblingGap = 200
const padding = 80

interface LayoutNode {
  id: string
  name: string
  isDefault: boolean
  lastRecordLsn: string | null
  parentBranchId: string | null
  x: number
  y: number
  children: LayoutNode[]
}

const treeData = computed(() => {
  const nodeMap = new Map<string, LayoutNode>()
  const nodesArr = props.nodes

  // Find default (root) branch
  let rootId: string | null = null
  for (const n of nodesArr) {
    nodeMap.set(n.id, {
      id: n.id,
      name: n.name,
      isDefault: n.is_default,
      lastRecordLsn: n.last_record_lsn,
      parentBranchId: n.parent_branch_id,
      x: 0,
      y: 0,
      children: [],
    })
    if (n.is_default) rootId = n.id
  }

  if (!rootId && nodesArr.length > 0) rootId = nodesArr[0]!.id

  // Build tree
  for (const n of nodeMap.values()) {
    if (n.parentBranchId && nodeMap.has(n.parentBranchId) && n.id !== rootId) {
      nodeMap.get(n.parentBranchId)!.children.push(n)
    } else if (n.id !== rootId && rootId) {
      // Orphan -> attach to root
      nodeMap.get(rootId)!.children.push(n)
    }
  }

  return rootId ? nodeMap.get(rootId)! : null
})

const layoutNodes = computed(() => {
  if (!treeData.value) return []
  const result: LayoutNode[] = []

  function subtreeWidth(node: LayoutNode): number {
    if (node.children.length === 0) return siblingGap
    return node.children.reduce((sum, c) => sum + subtreeWidth(c), 0)
  }

  function layout(node: LayoutNode, depth: number, leftX: number) {
    const w = subtreeWidth(node)
    node.x = leftX + w / 2
    node.y = padding + depth * levelGap
    result.push(node)

    let childLeft = leftX
    for (const child of node.children) {
      layout(child, depth + 1, childLeft)
      childLeft += subtreeWidth(child)
    }
  }

  layout(treeData.value, 0, 0)
  return result
})

const lines = computed(() => {
  const result: { key: string; d: string }[] = []
  for (const node of layoutNodes.value) {
    if (node.parentBranchId || (!node.isDefault && treeData.value)) {
      const parent = layoutNodes.value.find(
        n => n.id === node.parentBranchId || (n.isDefault && !node.parentBranchId && !node.isDefault)
      )
      if (parent) {
        const midY = (parent.y + nodeH / 2 + node.y - nodeH / 2) / 2
        result.push({
          key: `${parent.id}-${node.id}`,
          d: `M${parent.x},${parent.y + nodeH / 2} C${parent.x},${midY} ${node.x},${midY} ${node.x},${node.y - nodeH / 2}`,
        })
      }
    }
  }
  return result
})

const svgWidth = computed(() => {
  if (layoutNodes.value.length === 0) return 400
  const maxX = Math.max(...layoutNodes.value.map(n => n.x)) + nodeW / 2 + padding + 50
  return Math.max(400, maxX)
})

const svgHeight = computed(() => {
  if (layoutNodes.value.length === 0) return 200
  const maxY = Math.max(...layoutNodes.value.map(n => n.y)) + nodeH / 2 + padding
  return Math.max(200, maxY)
})

function truncate(s: string, max: number): string {
  return s.length > max ? s.slice(0, max - 1) + '...' : s
}
</script>

<style scoped>
.branch-tree-container {
  overflow-x: auto;
  border: 1px solid #e5e5e5;
  border-radius: 4px;
  background: #fafafa;
  padding: 8px;
  margin-bottom: 16px;
  min-height: 160px;
}

.branch-tree-svg {
  display: block;
}

.tree-node {
  cursor: pointer;
}

.tree-node:hover rect:first-child {
  stroke: #9a5b25;
}

.node-name {
  font-size: 13px;
  font-weight: 600;
  fill: #2c3e50;
  pointer-events: none;
}

.node-lsn {
  font-size: 11px;
  fill: #8a8e99;
  pointer-events: none;
}

.node-badge {
  font-size: 9px;
  fill: #9a5b25;
  font-weight: 600;
  pointer-events: none;
}

.action-btn {
  cursor: pointer;
}

.action-btn:hover rect {
  opacity: 0.85;
}

.action-text {
  font-size: 11px;
  fill: #fff;
  font-weight: 500;
  pointer-events: none;
}

.action-text-outline {
  font-size: 11px;
  fill: #9a5b25;
  font-weight: 500;
  pointer-events: none;
}

.action-text-danger {
  font-size: 11px;
  fill: #ff4d4f;
  font-weight: 500;
  pointer-events: none;
}
</style>
