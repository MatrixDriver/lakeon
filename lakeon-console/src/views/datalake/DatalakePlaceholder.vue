<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">{{ title }}</h1>
    </div>
    <div style="margin-top: 64px; text-align: center;">
      <svg viewBox="0 0 24 24" width="48" height="48" fill="none" stroke="#ccc" stroke-width="1.5">
        <rect x="2" y="3" width="20" height="14" rx="2" ry="2"/>
        <line x1="8" y1="21" x2="16" y2="21"/>
        <line x1="12" y1="17" x2="12" y2="21"/>
      </svg>
      <p style="color: #666; margin-top: 12px;">{{ subtitle }}</p>
      <p style="color: #999; font-size: 13px;">{{ description }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  title: string
  subtitle: string
  description: string
}>()
</script>
