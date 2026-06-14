<template>
  <LBFSPendingBanner />
  <router-view />
  <ToastNotification />
</template>

<script setup lang="ts">
import ToastNotification from './components/ToastNotification.vue'
import LBFSPendingBanner from './components/memory/LBFSPendingBanner.vue'
</script>
