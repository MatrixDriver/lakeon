<template>
  <div class="page-container">
    <div class="page-header">
      <h1 class="page-title">消息日志</h1>
    </div>

    <MemoryBaseSelector @change="onBaseChange" />

    <div v-if="baseId" style="margin-top: 20px;">
      <div style="display: flex; gap: 12px; align-items: center; margin-bottom: 12px;">
        <label style="font-size: 13px; color: #666;">类型</label>
        <select v-model="opFilter" @change="currentPage = 1; load()"
                style="padding: 4px 10px; border: 1px solid #e0e0e0; border-radius: 4px; font-size: 13px;">
          <option value="">全部</option>
          <option value="ingest_memory">记忆写入</option>
          <option value="ingest_conversation">会话写入</option>
        </select>
        <span style="color: #999; font-size: 13px;">共 {{ total }} 条（保留 7 天）</span>
      </div>
      <p v-if="loading" style="text-align: center; color: #999; padding: 40px 0;">加载中...</p>
      <p v-else-if="messages.length === 0" style="text-align: center; color: #999; padding: 40px 0;">暂无消息</p>

      <div v-else class="table-wrapper">
        <table class="data-table">
          <thead>
            <tr>
              <th style="width: 160px;">时间</th>
              <th style="width: 100px;">类型</th>
              <th style="width: 80px;">角色</th>
              <th style="width: 80px;">来源</th>
              <th>内容</th>
              <th style="width: 60px;">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="msg in messages" :key="msg.id" style="cursor: pointer;" @click="openDetail(msg.id)">
              <td style="color: #999; font-size: 13px;">{{ new Date(msg.created_at).toLocaleString('zh-CN') }}</td>
              <td>
                <span v-if="msg.op" style="display: inline-block; padding: 1px 8px; border-radius: 10px; font-size: 11px; background: color-mix(in oklch, var(--c-accent) 10%, #fff); color: var(--c-accent-text);">
                  {{ opLabel(msg.op) }}
                </span>
                <span v-else style="color: #ccc;">-</span>
              </td>
              <td>
                <span style="display: inline-block; padding: 1px 8px; border-radius: 10px; font-size: 11px;"
                      :style="msg.role === 'user' ? 'background: color-mix(in oklch, var(--c-primary) 10%, #fff); color: var(--c-primary);' : 'background: color-mix(in oklch, var(--c-accent) 12%, #fff); color: var(--c-accent-text);'">
                  {{ msg.role }}
                </span>
              </td>
              <td>
                <span v-if="msg.source" style="display: inline-block; padding: 1px 8px; border-radius: 10px; font-size: 11px; background: color-mix(in oklch, var(--c-primary) 10%, #fff); color: var(--c-primary);">
                  {{ msg.source }}
                </span>
                <span v-else style="color: #ccc;">-</span>
              </td>
              <td style="font-size: 13px; color: #333; max-width: 400px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;">
                {{ msg.content_preview || msg.content }}
              </td>
              <td>
                <button class="btn btn-text btn-small" style="color: #9a5b25;" @click.stop="openDetail(msg.id)">详情</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Pagination -->
      <div v-if="total > PAGE_SIZE" style="display: flex; justify-content: center; gap: 8px; margin-top: 16px;">
        <button class="btn btn-sm" :disabled="currentPage <= 1" @click="currentPage--; load()">上一页</button>
        <span style="line-height: 32px; font-size: 13px; color: #666;">
          {{ (currentPage - 1) * PAGE_SIZE + 1 }}-{{ Math.min(currentPage * PAGE_SIZE, total) }} / {{ total }}
        </span>
        <button class="btn btn-sm" :disabled="currentPage * PAGE_SIZE >= total" @click="currentPage++; load()">下一页</button>
      </div>
    </div>

    <!-- Detail side panel -->
    <div v-if="detailVisible" class="panel-overlay" @click="detailVisible = false"></div>
    <transition name="slide">
      <div v-if="detailVisible" class="detail-panel">
        <div class="detail-header">
          <span style="font-size: 16px; font-weight: 600;">消息详情</span>
          <button class="detail-close" @click="detailVisible = false">&times;</button>
        </div>

        <div v-if="detailLoading" style="padding: 40px; text-align: center; color: #999;">加载中...</div>
        <div v-else-if="detail" class="detail-body">
          <!-- Message info -->
          <div class="detail-section">
            <div class="detail-row">
              <span class="detail-label">时间</span>
              <span>{{ new Date(detail.message.created_at).toLocaleString('zh-CN') }}</span>
            </div>
            <div class="detail-row">
              <span class="detail-label">角色</span>
              <span>{{ detail.message.role }}</span>
            </div>
            <div class="detail-row">
              <span class="detail-label">来源</span>
              <span>{{ detail.message.source || '-' }}</span>
            </div>
            <div class="detail-row">
              <span class="detail-label">ID</span>
              <span style="font-family: monospace; font-size: 12px;">{{ detail.message.id }}</span>
            </div>
          </div>

          <!-- Content -->
          <div class="detail-section">
            <div style="font-size: 13px; font-weight: 600; margin-bottom: 8px;">消息内容</div>
            <pre class="detail-code">{{ detail.message.content }}</pre>
          </div>

          <!-- Extracted memories -->
          <div class="detail-section">
            <div style="font-size: 13px; font-weight: 600; margin-bottom: 8px;">
              提取的记忆 ({{ detail.extracted_memories.length }})
            </div>
            <div v-if="detail.extracted_memories.length === 0" style="color: #999; font-size: 13px;">
              暂无（可能正在提取中）
            </div>
            <div v-for="mem in detail.extracted_memories" :key="mem.id"
                 style="padding: 10px 12px; background: #fafafa; border-radius: 6px; margin-bottom: 8px; font-size: 13px;">
              <div style="display: flex; align-items: center; gap: 6px; margin-bottom: 4px;">
                <span style="padding: 1px 6px; border-radius: 3px; font-size: 11px; background: #e8f5e9; color: #2e7d32;">
                  {{ mem.memory_type }}
                </span>
                <span style="color: #999; font-size: 11px;">重要性 {{ Math.round(mem.importance * 100) }}%</span>
              </div>
              <div style="color: #333; line-height: 1.5;">{{ mem.content }}</div>
            </div>
          </div>
        </div>
      </div>
    </transition>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import MemoryBaseSelector from '@/components/MemoryBaseSelector.vue'
import { listRawMessages, getRawMessage, type RawMessage, type RawMessageDetail } from '@/api/memory'

const PAGE_SIZE = 20

const baseId = ref('')
const messages = ref<RawMessage[]>([])
const total = ref(0)
const currentPage = ref(1)
const loading = ref(false)
const opFilter = ref('')

function opLabel(op: string): string {
  return op === 'ingest_memory' ? '记忆写入'
    : op === 'ingest_conversation' ? '会话写入'
    : op
}

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<RawMessageDetail | null>(null)

function onBaseChange(id: string) {
  baseId.value = id
  currentPage.value = 1
  load()
}

async function load() {
  if (!baseId.value) return
  loading.value = true
  try {
    const { data } = await listRawMessages(baseId.value, {
      offset: (currentPage.value - 1) * PAGE_SIZE,
      limit: PAGE_SIZE,
      ...(opFilter.value ? { op: opFilter.value } : {}),
    })
    messages.value = data.messages
    total.value = data.total
  } catch (e) {
    console.error('Failed to load messages', e)
    messages.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function openDetail(messageId: string) {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    const { data } = await getRawMessage(baseId.value, messageId)
    detail.value = data
  } catch (e) {
    console.error('Failed to load message detail', e)
  } finally {
    detailLoading.value = false
  }
}
</script>

<style scoped>
.panel-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.15);
  z-index: 999;
}
.detail-panel {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  width: 480px;
  background: #fff;
  box-shadow: -4px 0 16px rgba(0, 0, 0, 0.08);
  z-index: 1000;
  display: flex;
  flex-direction: column;
}
.detail-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 24px 16px;
  border-bottom: 1px solid #f0f0f0;
}
.detail-close {
  background: none;
  border: none;
  font-size: 22px;
  color: #999;
  cursor: pointer;
  padding: 0 4px;
  line-height: 1;
}
.detail-close:hover {
  color: #333;
}
.detail-body {
  flex: 1;
  overflow-y: auto;
  padding: 16px 24px;
}
.detail-section {
  margin-bottom: 20px;
}
.detail-row {
  display: flex;
  gap: 16px;
  padding: 8px 0;
  border-bottom: 1px solid #f5f5f5;
  font-size: 13px;
}
.detail-label {
  color: #999;
  min-width: 60px;
  flex-shrink: 0;
}
.detail-code {
  background: #f7f8fa;
  border: 1px solid #e8e8e8;
  border-radius: 4px;
  padding: 12px 16px;
  font-size: 13px;
  font-family: monospace;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 300px;
  overflow-y: auto;
  margin: 0;
  color: #333;
  line-height: 1.6;
}
.slide-enter-active,
.slide-leave-active {
  transition: transform 0.2s ease;
}
.slide-enter-from,
.slide-leave-to {
  transform: translateX(100%);
}
@media (max-width: 768px) {
  .detail-panel {
    width: 100%;
  }
}
</style>
