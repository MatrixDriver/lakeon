<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">知识搜索</h1>
    </div>

    <div style="margin-top: 20px; max-width: 720px;">
      <!-- KB selector -->
      <div class="form-group" style="max-width: 360px;">
        <label class="form-label">选择知识库</label>
        <select v-model="selectedKbId" class="form-select">
          <option value="">全部知识库</option>
          <option v-for="kb in knowledgeBases" :key="kb.id" :value="kb.id">{{ kb.name }}</option>
        </select>
      </div>

      <!-- Search input -->
      <div style="display: flex; gap: 8px; margin-top: 16px;">
        <input
          v-model="query"
          type="text"
          class="form-input"
          placeholder="输入搜索内容..."
          style="flex: 1;"
          @keyup.enter="search"
        />
        <button class="btn btn-primary" @click="search" :disabled="!query.trim() || searching">{{ searching ? '搜索中...' : '搜索' }}</button>
      </div>
      <p style="color: #999; font-size: 12px; margin-top: 6px;">语义搜索 + 关键词搜索（pgvector + BM25 + RRF 融合）</p>
    </div>

    <!-- Results -->
    <div v-if="results.length > 0" style="margin-top: 24px; max-width: 720px;">
      <div style="font-size: 13px; color: #999; margin-bottom: 12px;">找到 {{ results.length }} 条结果</div>
      <div v-for="(r, i) in results" :key="i"
           style="border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 12px; background: #fafbfc;">
        <div style="font-size: 14px; line-height: 1.7; color: #333; white-space: pre-wrap;">{{ r.content }}</div>
        <div style="margin-top: 10px; font-size: 12px; color: #999; display: flex; gap: 16px; flex-wrap: wrap; align-items: center;">
          <span v-if="r.level === 1" style="background: #eff6ff; color: #2563eb; padding: 1px 8px; border-radius: 3px; font-weight: 500;">文档摘要</span>
          <span v-if="r.metadata?.kb_name" style="color: #1890ff;">{{ r.metadata.kb_name }}</span>
          <span>来源: {{ r.metadata?.filename }}</span>
          <span v-if="r.metadata?.section">章节: {{ r.metadata.section }}</span>
          <span>得分: {{ r.score?.toFixed(3) }}</span>
        </div>
      </div>
    </div>

    <div v-else-if="searched && !searching && results.length === 0" style="margin-top: 32px; text-align: center; color: #999;">
      未找到相关结果
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { listKnowledgeBases, searchKnowledge, type KnowledgeBase, type SearchResult } from '../../api/knowledge'

const knowledgeBases = ref<KnowledgeBase[]>([])
const selectedKbId = ref('')
const query = ref('')
const results = ref<SearchResult[]>([])
const searched = ref(false)
const searching = ref(false)

async function search() {
  if (!query.value.trim()) return
  searched.value = true
  searching.value = true
  try {
    const resp = await searchKnowledge(selectedKbId.value || '', query.value, 10)
    results.value = resp.data.results
  } finally {
    searching.value = false
  }
}

onMounted(async () => {
  const resp = await listKnowledgeBases()
  knowledgeBases.value = resp.data
})
</script>
