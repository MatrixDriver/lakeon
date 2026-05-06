<script setup lang="ts">
import Button from './Button.vue'
defineProps<{ kind: 'error' | 'warning' | 'info'; text: string; retryLabel?: string }>()
defineEmits<{ retry: [] }>()
</script>

<template>
  <div :class="['banner', kind]" role="status">
    <span class="text">{{ text }}</span>
    <Button v-if="retryLabel" variant="subtle" @click="$emit('retry')">{{ retryLabel }}</Button>
  </div>
</template>

<style scoped>
.banner {
  display: flex; align-items: center; justify-content: space-between;
  padding: var(--space-sm) var(--space-lg);
  border-top: 1px solid var(--c-border);
  background: var(--c-bg-alt);
  font-size: var(--fs-sm);
}
.banner.error { border-top-color: var(--c-danger); }
.banner.warning { border-top-color: var(--c-warning); }
.banner.info { border-top-color: var(--c-primary); }
.text { color: var(--c-text); }
</style>
