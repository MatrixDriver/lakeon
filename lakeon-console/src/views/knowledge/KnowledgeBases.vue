<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">知识库</h1>
      <div class="page-header-actions">
        <button class="btn btn-primary" @click="showCreate = true">创建知识库</button>
      </div>
    </div>

    <!-- Create dialog -->
    <div v-if="showCreate" class="dialog-overlay" @click.self="showCreate = false">
      <div class="dialog-box" style="max-width: 480px;">
        <div class="dialog-header">
          <h3>创建知识库</h3>
          <button class="dialog-close" @click="showCreate = false">&times;</button>
        </div>
        <div class="dialog-body">
          <div class="form-group">
            <label class="form-label">名称 <span style="color:#e6393d">*</span></label>
            <input v-model="createForm.name" class="form-input" placeholder="例如：产品文档库" />
          </div>
          <div class="form-group">
            <label class="form-label">描述</label>
            <input v-model="createForm.description" class="form-input" placeholder="可选，描述知识库用途" />
          </div>
          <p style="font-size: 12px; color: #999; margin-top: 12px;">
            系统将自动创建专用数据库，使用 BGE-M3 向量模型（1024维）和结构化切片策略。
          </p>
        </div>
        <div class="dialog-footer">
          <button class="btn btn-default" @click="showCreate = false">取消</button>
          <button class="btn btn-primary" @click="handleCreate" :disabled="!createForm.name.trim()">创建</button>
        </div>
      </div>
    </div>

    <!-- Knowledge base list -->
    <div v-if="knowledgeBases.length > 0" class="table-wrapper" style="margin-top: 20px;">
      <table class="data-table">
        <thead>
          <tr>
            <th>名称</th>
            <th>描述</th>
            <th>文档数</th>
            <th>状态</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="kb in knowledgeBases" :key="kb.id">
            <td>
              <router-link :to="`/knowledge/${kb.id}`" style="color: #0073e6; text-decoration: none; font-weight: 500;">
                {{ kb.name }}
              </router-link>
            </td>
            <td style="color: #666;">{{ kb.description || '-' }}</td>
            <td>{{ kb.documentCount ?? 0 }}</td>
            <td>
              <span class="status-tag" :class="'tag-' + statusColor(kb.status)">{{ statusText(kb.status) }}</span>
            </td>
            <td style="color: #999;">{{ formatTime(kb.createdAt) }}</td>
            <td>
              <button class="btn btn-text btn-small" style="color: #e6393d;" @click="handleDelete(kb)">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- Empty state -->
    <div v-else class="empty-state" style="margin-top: 64px; text-align: center;">
      <svg viewBox="0 0 24 24" width="48" height="48" fill="none" stroke="#ccc" stroke-width="1.5">
        <path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/>
        <path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/>
      </svg>
      <p style="color: #666; margin-top: 12px;">还没有知识库</p>
      <p style="color: #999; font-size: 13px;">创建知识库后，上传文档即可自动建立检索索引</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'

interface KnowledgeBase {
  id: string
  name: string
  description: string
  documentCount: number
  status: string
  createdAt: string
}

const knowledgeBases = ref<KnowledgeBase[]>([])
const showCreate = ref(false)
const createForm = ref({ name: '', description: '' })

function statusColor(status: string) {
  if (status === 'READY') return 'green'
  if (status === 'CREATING') return 'blue'
  if (status === 'FAILED') return 'red'
  return 'blue'
}

function statusText(status: string) {
  const map: Record<string, string> = { READY: '就绪', CREATING: '创建中', FAILED: '失败' }
  return map[status] || status
}

function formatTime(t: string) {
  if (!t) return '-'
  return new Date(t).toLocaleString('zh-CN')
}

async function handleCreate() {
  // TODO: POST /api/v1/knowledge/bases
  showCreate.value = false
  createForm.value = { name: '', description: '' }
}

async function handleDelete(kb: KnowledgeBase) {
  if (!confirm(`确认删除知识库"${kb.name}"？所有文档和索引数据将被永久删除。`)) return
  // TODO: DELETE /api/v1/knowledge/bases/{id}
}

onMounted(async () => {
  // TODO: GET /api/v1/knowledge/bases
})
</script>
