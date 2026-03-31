<template>
  <div class="card-menu" @click.stop>
    <button class="card-menu-trigger" @click="open = !open" title="更多操作">
      <svg viewBox="0 0 16 16" width="14" height="14" fill="currentColor">
        <circle cx="8" cy="3" r="1.5"/>
        <circle cx="8" cy="8" r="1.5"/>
        <circle cx="8" cy="13" r="1.5"/>
      </svg>
    </button>
    <div v-if="open" class="card-menu-backdrop" @click="open = false"></div>
    <div v-if="open" class="card-menu-dropdown">
      <slot :close="() => open = false">
        <button class="card-menu-item danger" @click="$emit('delete'); open = false">删除</button>
      </slot>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'

defineEmits<{ delete: [] }>()

const open = ref(false)
</script>

<style scoped>
.card-menu { position: relative; }
.card-menu-trigger {
  background: none; border: none; cursor: pointer; padding: 2px 4px;
  color: #94a3b8; border-radius: 4px; transition: color 0.15s, background 0.15s;
  line-height: 1;
}
.card-menu-trigger:hover { color: #64748b; background: rgba(0,0,0,0.05); }
.card-menu-backdrop {
  position: fixed; inset: 0; z-index: 99;
}
.card-menu-dropdown {
  position: absolute; right: 0; top: calc(100% + 4px); z-index: 100;
  min-width: 120px; background: #fff; border: 1px solid #e8e4df;
  border-radius: 6px; box-shadow: 0 4px 16px rgba(0,0,0,0.1);
  padding: 4px 0;
}
.card-menu-item {
  display: block; width: 100%; text-align: left;
  padding: 7px 14px; font-size: 13px; border: none;
  background: none; cursor: pointer; color: #374151;
  transition: background 0.1s;
}
.card-menu-item:hover { background: #f9f8f6; }
.card-menu-item.danger { color: #e6393d; }
.card-menu-item.danger:hover { background: #fef2f2; }
</style>
