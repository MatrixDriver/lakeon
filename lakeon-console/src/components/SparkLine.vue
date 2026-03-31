<template>
  <svg :width="width" :height="height" class="sparkline">
    <defs>
      <linearGradient :id="gradientId" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0%" :stop-color="color" stop-opacity="0.2" />
        <stop offset="100%" :stop-color="color" stop-opacity="0.02" />
      </linearGradient>
    </defs>
    <path v-if="areaPath" :d="areaPath" :fill="`url(#${gradientId})`" />
    <polyline
      v-if="points.length > 1"
      :points="pointsStr"
      fill="none"
      :stroke="color"
      stroke-width="1.5"
      stroke-linejoin="round"
      stroke-linecap="round"
    />
  </svg>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  data: number[]
  width?: number
  height?: number
  color?: string
  max?: number | null
}>(), {
  width: 160,
  height: 40,
  color: '#9a5b25',
  max: null,
})

const gradientId = computed(() => 'spark-grad-' + Math.random().toString(36).slice(2, 8))

const points = computed(() => {
  if (props.data.length < 2) return []
  const pad = 2
  const w = props.width - pad * 2
  const h = props.height - pad * 2
  const maxVal = props.max ?? Math.max(...props.data, 0.001)
  const step = w / (props.data.length - 1)
  return props.data.map((v, i) => ({
    x: pad + i * step,
    y: pad + h - (v / maxVal) * h,
  }))
})

const pointsStr = computed(() => points.value.map(p => `${p.x},${p.y}`).join(' '))

const areaPath = computed(() => {
  if (points.value.length < 2) return ''
  const pad = 2
  const h = props.height
  const pts = points.value
  let d = `M ${pts[0]!.x},${h - pad}`
  for (const p of pts) d += ` L ${p.x},${p.y}`
  d += ` L ${pts[pts.length - 1]!.x},${h - pad} Z`
  return d
})
</script>

<style scoped>
.sparkline {
  display: block;
}
</style>
