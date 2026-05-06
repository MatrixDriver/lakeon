<script setup lang="ts">
import Icon from './Icon.vue'
defineProps<{ open: boolean; title?: string; widthVw?: number }>()
defineEmits<{ close: [] }>()
</script>

<template>
  <Transition name="drawer">
    <aside v-if="open" class="drawer" :style="{ width: `min(${widthVw ?? 70}vw, 960px)` }">
      <header class="head">
        <h3 v-if="title">{{ title }}</h3>
        <button class="close" @click="$emit('close')" aria-label="close"><Icon name="close" /></button>
      </header>
      <div class="body"><slot /></div>
    </aside>
  </Transition>
</template>

<style scoped>
.drawer {
  position: fixed; right: 0; top: 0; bottom: 0;
  background: var(--c-bg);
  border-left: 1px solid var(--c-border);
  box-shadow: var(--shadow-drawer);
  display: flex; flex-direction: column;
  z-index: 50;
}
.head {
  display: flex; align-items: center; justify-content: space-between;
  padding: var(--space-md) var(--space-lg);
  border-bottom: 1px solid var(--c-border);
}
.head h3 { font-size: var(--fs-md); margin: 0; }
.close { background: transparent; border: none; cursor: pointer; color: var(--c-text-muted); padding: 4px; }
.close:hover { color: var(--c-text); }
.body { flex: 1; overflow-y: auto; }

.drawer-enter-active, .drawer-leave-active {
  transition: transform var(--t-drawer) var(--ease-out);
}
.drawer-enter-from, .drawer-leave-to { transform: translateX(100%); }
</style>
