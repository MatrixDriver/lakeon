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

      <!-- BUILTIN type -->
      <div v-if="base?.type === 'BUILTIN'" class="section-card" style="padding: 24px;">
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

      <!-- MEM0 type -->
      <div v-else-if="base?.type === 'MEM0'" class="section-card" style="padding: 24px;">
        <h3 style="margin-bottom: 16px;">mem0 + DBay 集成指南</h3>
        <p style="color: #666; font-size: 14px; line-height: 1.6;">在您的 DBay 数据库上运行 mem0，享受 Serverless PostgreSQL 的便利。</p>

        <h4 style="margin: 20px 0 8px; font-size: 14px;">1. 安装 mem0</h4>
        <pre style="background: #f5f5f5; padding: 16px; border-radius: 4px; font-size: 13px; overflow-x: auto; white-space: pre-wrap;">pip install mem0ai</pre>

        <h4 style="margin: 20px 0 8px; font-size: 14px;">2. 获取数据库连接信息</h4>
        <p style="color: #666; font-size: 14px; line-height: 1.6;">在「数据库」页面找到您的数据库，复制连接串：</p>
        <pre style="background: #f5f5f5; padding: 16px; border-radius: 4px; font-size: 13px; overflow-x: auto; white-space: pre-wrap;">postgresql://&lt;user&gt;:&lt;password&gt;@&lt;host&gt;:5432/&lt;dbname&gt;</pre>

        <h4 style="margin: 20px 0 8px; font-size: 14px;">3. 配置 mem0 使用 DBay</h4>
        <pre style="background: #f5f5f5; padding: 16px; border-radius: 4px; font-size: 13px; overflow-x: auto; white-space: pre-wrap;">from mem0 import Memory

config = {
    "vector_store": {
        "provider": "pgvector",
        "config": {
            "connection_string": "your_dbay_connection_string",
            "collection_name": "memories"
        }
    },
    "llm": {
        "provider": "openai",
        "config": {
            "model": "gpt-4o-mini",
            "api_key": "your_openai_key"
        }
    }
}

m = Memory.from_config(config)
m.add("用户喜欢使用 Python", user_id="user1")
results = m.search("编程语言偏好", user_id="user1")</pre>

        <h4 style="margin: 20px 0 8px; font-size: 14px;">4. 参考文档</h4>
        <p style="color: #666; font-size: 14px; line-height: 1.6;"><a href="https://docs.mem0.ai" target="_blank" style="color: #1890ff;">mem0 官方文档</a></p>
      </div>

      <!-- HINDSIGHT type -->
      <div v-else-if="base?.type === 'HINDSIGHT'" class="section-card" style="padding: 24px;">
        <h3 style="margin-bottom: 16px;">Hindsight + DBay 集成指南</h3>
        <p style="color: #666; font-size: 14px; line-height: 1.6;">Hindsight 是一个开源的 AI 记忆框架，支持 PostgreSQL 后端。</p>

        <h4 style="margin: 20px 0 8px; font-size: 14px;">1. 安装 Hindsight</h4>
        <pre style="background: #f5f5f5; padding: 16px; border-radius: 4px; font-size: 13px; overflow-x: auto; white-space: pre-wrap;">pip install hindsight-ai</pre>

        <h4 style="margin: 20px 0 8px; font-size: 14px;">2. 获取数据库连接信息</h4>
        <p style="color: #666; font-size: 14px; line-height: 1.6;">在「数据库」页面找到您的数据库，复制连接串。</p>

        <h4 style="margin: 20px 0 8px; font-size: 14px;">3. 配置 Hindsight</h4>
        <pre style="background: #f5f5f5; padding: 16px; border-radius: 4px; font-size: 13px; overflow-x: auto; white-space: pre-wrap;">from hindsight import Hindsight

hs = Hindsight(
    database_url="your_dbay_connection_string"
)
hs.remember("用户偏好 TypeScript")
results = hs.recall("编程语言")</pre>

        <h4 style="margin: 20px 0 8px; font-size: 14px;">4. 参考文档</h4>
        <p style="color: #666; font-size: 14px; line-height: 1.6;"><a href="https://github.com/anthropics/hindsight" target="_blank" style="color: #1890ff;">Hindsight GitHub</a></p>
      </div>

      <!-- CUSTOM type (fallback) -->
      <div v-else class="section-card" style="padding: 24px;">
        <h3 style="margin-bottom: 16px;">自定义记忆系统集成</h3>
        <p style="color: #666; font-size: 14px; line-height: 1.6;">您可以将任何支持 PostgreSQL 的记忆系统连接到 DBay 数据库。</p>

        <h4 style="margin: 20px 0 8px; font-size: 14px;">数据库连接信息</h4>
        <p style="color: #666; font-size: 14px; line-height: 1.6;">在「数据库」页面创建或选择一个数据库，获取连接信息：</p>
        <pre style="background: #f5f5f5; padding: 16px; border-radius: 4px; font-size: 13px; overflow-x: auto; white-space: pre-wrap;">Host:     proxy.dbay.cloud
Port:     5432
Database: your_db_name
User:     your_username
Password: your_password
SSL:      require</pre>

        <h4 style="margin: 20px 0 8px; font-size: 14px;">推荐扩展</h4>
        <p style="color: #666; font-size: 14px; line-height: 1.6;">DBay 数据库已预装以下扩展，适合记忆系统使用：</p>
        <ul style="color: #666; font-size: 14px; line-height: 1.8;">
          <li><strong>pgvector</strong> — 向量相似度搜索</li>
          <li><strong>pg_search</strong> — BM25 全文检索</li>
        </ul>

        <h4 style="margin: 20px 0 8px; font-size: 14px;">建议</h4>
        <p style="color: #666; font-size: 14px; line-height: 1.6;">确保您的记忆系统使用 <code>pgvector</code> 存储嵌入向量，并利用 <code>pg_search</code> 进行混合检索以获得最佳效果。</p>
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
