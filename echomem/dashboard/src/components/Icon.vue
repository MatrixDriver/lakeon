<script setup lang="ts">
import { computed } from 'vue'

type IconName = 'plus' | 'close' | 'arrow-right' | 'chevron-down' | 'refresh' | 'search' | 'dot'

const props = defineProps<{ name: IconName; size?: number }>()
const dims = computed(() => props.size ?? 16)

const PATHS: Record<IconName, string> = {
  'plus': 'M12 5v14M5 12h14',
  'close': 'M6 6l12 12M18 6l-12 12',
  'arrow-right': 'M5 12h14M13 6l6 6-6 6',
  'chevron-down': 'M6 9l6 6 6-6',
  'refresh': 'M4 12a8 8 0 0 1 14-5.3M20 12a8 8 0 0 1-14 5.3M20 4v6h-6M4 20v-6h6',
  'search': 'M21 21l-4.3-4.3M10 17a7 7 0 1 1 0-14 7 7 0 0 1 0 14z',
  'dot': '',
}
</script>

<template>
  <svg
    v-if="name in PATHS"
    :data-icon="name"
    :width="dims"
    :height="dims"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    stroke-width="1.6"
    stroke-linecap="round"
    stroke-linejoin="round"
  >
    <circle v-if="name === 'dot'" cx="12" cy="12" r="4" fill="currentColor" stroke="none" />
    <path v-else :d="PATHS[name]" />
  </svg>
</template>
