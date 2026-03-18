<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">文档管理</h1>
      <div class="page-header-actions">
        <button class="btn btn-primary" @click="showUpload = true">上传文档</button>
      </div>
    </div>
    <p style="color: #999; margin-top: 16px;">上传 PDF、DOCX 或 Markdown 文档，自动解析并建立知识库索引。</p>

    <!-- Placeholder: document list will go here -->
    <div class="empty-state" style="margin-top: 48px; text-align: center;">
      <svg viewBox="0 0 24 24" width="48" height="48" fill="none" stroke="#ccc" stroke-width="1.5">
        <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/>
        <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/>
      </svg>
      <p style="color: #999; margin-top: 12px;">还没有文档，点击"上传文档"开始</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'

const showUpload = ref(false)
</script>
