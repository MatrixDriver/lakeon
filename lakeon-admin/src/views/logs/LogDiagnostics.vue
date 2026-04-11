<template>
  <div>
    <header class="page-header">
      <div>
        <h1 class="page-title">日志诊断</h1>
        <p class="page-subtitle">跨组件检索、调用链追踪、错误聚合与日志统计</p>
      </div>
    </header>

    <nav class="log-tab-bar" role="tablist">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        class="log-tab"
        :class="{ 'is-active': activeTab === tab.key }"
        role="tab"
        :aria-selected="activeTab === tab.key"
        @click="activeTab = tab.key"
      >{{ tab.label }}</button>
    </nav>

    <section class="log-tab-pane">
      <component :is="currentComponent" :request-id="requestId" />
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import LogSearch from './LogSearch.vue'
import LogTrace from './LogTrace.vue'
import LogErrors from './LogErrors.vue'
import LogStats from './LogStats.vue'
import LogViewer from '../system/LogViewer.vue'

const props = defineProps<{ requestId?: string }>()
const route = useRoute()

const tabs = [
  { key: 'errors', label: '错误概览' },
  { key: 'search', label: '日志搜索' },
  { key: 'trace', label: '调用链追踪' },
  { key: 'stats', label: '日志统计' },
  { key: 'pods', label: 'Pod 日志' },
]

const activeTab = ref('errors')

const componentMap: Record<string, any> = {
  errors: LogErrors,
  search: LogSearch,
  trace: LogTrace,
  stats: LogStats,
  pods: LogViewer,
}

const currentComponent = computed(() => componentMap[activeTab.value] || LogErrors)

// If opened with requestId route param, go to trace tab
onMounted(() => {
  if (props.requestId || route.params.requestId) {
    activeTab.value = 'trace'
  }
  // Support ?tab= query param
  const tabParam = route.query.tab as string
  if (tabParam && componentMap[tabParam]) {
    activeTab.value = tabParam
  }
})

watch(() => route.query.tab, (val) => {
  if (val && componentMap[val as string]) {
    activeTab.value = val as string
  }
})
</script>

<style scoped>
.log-tab-bar {
  display: flex;
  gap: var(--space-2xl);
  border-bottom: 1px solid var(--c-border);
  margin-top: var(--space-xl);
  margin-bottom: var(--space-2xl);
}

.log-tab {
  background: none;
  border: none;
  padding: var(--space-md) 0;
  font: inherit;
  font-family: var(--font-sans);
  font-size: 14px;
  font-weight: 500;
  color: var(--c-text-2);
  cursor: pointer;
  position: relative;
  transition: color 160ms ease-out;
}

.log-tab:hover {
  color: var(--c-text);
}

.log-tab.is-active {
  color: var(--c-primary);
}

.log-tab.is-active::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 2px;
  background: var(--c-accent);
  border-radius: 1px;
}

.log-tab:focus-visible {
  outline: 2px solid var(--c-accent);
  outline-offset: 4px;
  border-radius: 2px;
}

.log-tab-pane {
  animation: fade-in 220ms cubic-bezier(0.22, 0.61, 0.36, 1);
}

@keyframes fade-in {
  from { opacity: 0; transform: translateY(2px); }
  to { opacity: 1; transform: translateY(0); }
}

@media (prefers-reduced-motion: reduce) {
  .log-tab-pane { animation: none; }
}
</style>
