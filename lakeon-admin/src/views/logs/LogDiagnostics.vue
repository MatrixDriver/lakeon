<template>
  <div>
    <div class="page-header">
      <h1 class="page-title">日志诊断</h1>
    </div>

    <!-- Tabs -->
    <div class="tab-bar">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        class="tab-btn"
        :class="{ active: activeTab === tab.key }"
        @click="activeTab = tab.key"
      >{{ tab.label }}</button>
    </div>

    <!-- Tab Content -->
    <component :is="currentComponent" :request-id="requestId" />
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
.tab-bar {
  display: flex;
  gap: 0;
  border-bottom: 1px solid #e8e0d8;
  margin-bottom: 20px;
}

.tab-btn {
  padding: 10px 20px;
  border: none;
  background: none;
  color: #7a6b5d;
  font-size: 13px;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.2s;
}

.tab-btn:hover {
  color: #4a3728;
  background: #faf7f3;
}

.tab-btn.active {
  color: #c67d3a;
  border-bottom-color: #c67d3a;
  font-weight: 600;
}
</style>
