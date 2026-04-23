<template>
  <AgentFSPendingBanner />
  <router-view />
  <ToastNotification />
</template>

<script setup lang="ts">
import ToastNotification from './components/ToastNotification.vue'
import AgentFSPendingBanner from './components/memory/AgentFSPendingBanner.vue'
</script>
