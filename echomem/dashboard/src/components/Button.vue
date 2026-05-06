<script setup lang="ts">
defineProps<{ variant?: 'primary' | 'subtle' | 'danger'; disabled?: boolean }>()
defineEmits<{ click: [MouseEvent] }>()
</script>

<template>
  <button
    type="button"
    :class="['btn', variant ?? 'subtle']"
    :disabled="disabled"
    @click="$emit('click', $event)"
  >
    <slot />
  </button>
</template>

<style scoped>
.btn {
  font-family: var(--font-body);
  font-size: var(--fs-sm);
  font-weight: var(--fw-medium);
  padding: var(--space-sm) var(--space-md);
  border-radius: var(--radius-md);
  border: 1px solid transparent;
  cursor: pointer;
  transition: background var(--t-fast) var(--ease-out),
              border-color var(--t-fast) var(--ease-out);
}
.btn.primary { background: var(--c-accent); color: white; }
.btn.primary:hover { background: var(--c-accent-hover); }
.btn.subtle { background: var(--c-bg); color: var(--c-text); border-color: var(--c-border); }
.btn.subtle:hover { border-color: var(--c-border-hover); background: var(--c-bg-alt); }
.btn.danger { background: var(--c-bg); color: var(--c-danger); border-color: var(--c-danger); }
.btn.danger:hover { background: rgba(194, 97, 74, 0.06); }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
