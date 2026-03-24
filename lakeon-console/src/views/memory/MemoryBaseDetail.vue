<template>
  <div class="page-container">
    <!-- Breadcrumb -->
    <div class="breadcrumb" style="margin-bottom: 16px;">
      <router-link to="/memory" style="color: #0073e6; text-decoration: none;">记忆库</router-link>
      <span class="breadcrumb-sep">/</span>
      <span>{{ base?.name || '...' }}</span>
    </div>

    <!-- Page header -->
    <div class="page-header">
      <h1 class="page-title">
        {{ base?.name || '加载中...' }}
        <span v-if="base" class="status-tag" :class="base.status === 'READY' ? 'tag-green' : base.status === 'FAILED' ? 'tag-red' : 'tag-gray'" style="margin-left: 10px; font-size: 13px; vertical-align: middle;">
          {{ statusText(base.status) }}
        </span>
      </h1>
    </div>

    <!-- Tab bar -->
    <div v-if="base" class="tab-bar" style="margin-top: 20px;">
      <button class="tab-item" :class="{ active: activeTab === 'overview' }" @click="activeTab = 'overview'">概览</button>
      <button v-if="base.type === 'BUILTIN'" class="tab-item" :class="{ active: activeTab === 'memories' }" @click="activeTab = 'memories'">记忆</button>
      <button v-if="base.type === 'BUILTIN'" class="tab-item" :class="{ active: activeTab === 'traits' }" @click="activeTab = 'traits'">特征</button>
      <button class="tab-item" :class="{ active: activeTab === 'settings' }" @click="activeTab = 'settings'">接入</button>
    </div>

    <!-- Overview tab -->
    <div v-if="base && activeTab === 'overview'" style="margin-top: 24px;">
      <div class="section-card" style="display: grid; grid-template-columns: repeat(3, 1fr); gap: 20px;">
        <div style="text-align: center; padding: 24px;">
          <div style="font-size: 32px; font-weight: 600; color: #0073e6;">{{ base.memory_count ?? 0 }}</div>
          <div style="color: #666; margin-top: 8px; font-size: 14px;">记忆数</div>
        </div>
        <div style="text-align: center; padding: 24px;">
          <div style="font-size: 32px; font-weight: 600; color: #0073e6;">{{ base.trait_count ?? 0 }}</div>
          <div style="color: #666; margin-top: 8px; font-size: 14px;">特征数</div>
        </div>
        <div style="text-align: center; padding: 24px;">
          <div style="font-size: 20px; font-weight: 600; color: #333;">{{ typeLabel }}</div>
          <div style="color: #666; margin-top: 8px; font-size: 14px;">类型</div>
        </div>
      </div>
      <div v-if="base.description" style="margin-top: 16px; color: #666; font-size: 14px;">{{ base.description }}</div>
      <div v-if="base.embedding_model" style="margin-top: 8px; color: #999; font-size: 13px;">嵌入模型：{{ base.embedding_model }}</div>
      <div v-if="base.error" style="margin-top: 12px; padding: 12px; background: #fff2f0; border: 1px solid #ffccc7; border-radius: 6px; color: #e6393d; font-size: 13px;">
        错误：{{ base.error }}
      </div>
    </div>

    <!-- Memories tab -->
    <div v-if="base && activeTab === 'memories'" style="margin-top: 24px;">
      <div class="section-card" style="padding: 40px; text-align: center; color: #999;">
        记忆列表（Phase 2 实现）
      </div>
    </div>

    <!-- Traits tab -->
    <div v-if="base && activeTab === 'traits'" style="margin-top: 24px;">
      <div class="section-card" style="padding: 40px; text-align: center; color: #999;">
        特征可视化（Phase 2 实现）
      </div>
    </div>

    <!-- Settings tab -->
    <div v-if="base && activeTab === 'settings'" style="margin-top: 24px;">
      <div class="section-card" style="padding: 24px;">
        <h3 style="font-size: 15px; font-weight: 600; margin-bottom: 20px; color: #333;">接入信息</h3>

        <div class="form-group">
          <label class="form-label">记忆库 ID</label>
          <div style="font-family: monospace; font-size: 13px; padding: 8px 12px; background: #f5f5f5; border-radius: 4px; color: #333;">{{ base.id }}</div>
        </div>

        <div class="form-group">
          <label class="form-label">API Endpoint</label>
          <div style="font-family: monospace; font-size: 13px; padding: 8px 12px; background: #f5f5f5; border-radius: 4px; color: #333; word-break: break-all;">
            https://api.dbay.cloud:8443/api/v1/memory/bases/{{ base.id }}
          </div>
        </div>

        <div class="form-group">
          <label class="form-label">Python SDK</label>
          <pre style="font-family: monospace; font-size: 13px; padding: 16px; background: #1e1e1e; border-radius: 6px; color: #d4d4d4; overflow-x: auto; margin: 0;">pip install dbay

from dbay import MemoryClient
client = MemoryClient(api_key="your_api_key", base_id="{{ base.id }}")
client.ingest("用户喜欢使用 TypeScript")
results = client.recall("用户的技术偏好")</pre>
        </div>
      </div>
    </div>

    <!-- Loading state -->
    <div v-if="!base" style="padding: 60px 0; text-align: center; color: #999;">
      加载中...
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { getMemoryBase, type MemoryBase } from '../../api/memory'

const route = useRoute()
const base = ref<MemoryBase | null>(null)
const activeTab = ref('overview')

const typeLabels: Record<string, string> = { BUILTIN: '自研', MEM0: 'mem0', HINDSIGHT: 'hindsight', CUSTOM: '自定义' }
const typeLabel = computed(() => typeLabels[base.value?.type || ''] || base.value?.type || '')

function statusText(status: string) {
  const map: Record<string, string> = { READY: '就绪', CREATING: '创建中', FAILED: '失败' }
  return map[status] || status
}

onMounted(async () => {
  const memId = route.params.memId as string
  const resp = await getMemoryBase(memId)
  base.value = resp.data
})
</script>
