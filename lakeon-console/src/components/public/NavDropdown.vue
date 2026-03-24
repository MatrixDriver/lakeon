<template>
  <div class="nav-dropdown">
    <button class="nav-dropdown-trigger" @click="open = !open">
      {{ label }}
      <span class="nav-dropdown-chevron">▾</span>
    </button>
    <div v-if="open" class="nav-dropdown-panel">
      <slot />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'

defineProps<{ label: string }>()

const open = ref(false)
</script>

<style scoped>
.nav-dropdown {
  position: relative;
}
.nav-dropdown-trigger {
  background: none;
  border: none;
  font-size: 13px;
  color: #999;
  cursor: pointer;
  padding: 6px 12px;
  border-radius: 6px;
  display: flex;
  align-items: center;
  gap: 4px;
  transition: color 0.15s, background 0.15s;
}
.nav-dropdown-trigger:hover {
  color: #fff;
  background: #1a1a1a;
}
.nav-dropdown-chevron {
  font-size: 10px;
  color: #555;
}
.nav-dropdown-panel {
  position: absolute;
  top: calc(100% + 8px);
  left: 0;
  min-width: 260px;
  background: #111;
  border: 1px solid #222;
  border-radius: 10px;
  padding: 6px;
  box-shadow: 0 8px 32px #0008;
}
</style>
