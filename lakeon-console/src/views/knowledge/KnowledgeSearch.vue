<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">知识搜索</h1>
    </div>
    <div style="margin-top: 20px; max-width: 640px;">
      <div style="display: flex; gap: 8px;">
        <input
          v-model="query"
          type="text"
          class="form-input"
          placeholder="搜索知识库内容..."
          style="flex: 1;"
          @keyup.enter="search"
        />
        <button class="btn btn-primary" @click="search">搜索</button>
      </div>
      <p style="color: #999; font-size: 13px; margin-top: 8px;">支持语义搜索和关键词搜索（pgvector + BM25 + RRF 融合）</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'

const query = ref('')

function search() {
  // TODO: call /api/v1/knowledge/search
}
</script>
