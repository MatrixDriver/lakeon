<template>
  <div class="chunk-content">
    <!-- Tabs -->
    <div class="tab-bar">
      <div v-for="tab in tabs" :key="tab.key"
           class="tab-item"
           :class="{ active: activeTab === tab.key }"
           @click="activeTab = tab.key">
        {{ tab.label }}
      </div>
    </div>

    <!-- Tab 1: Chunk content -->
    <div v-if="activeTab === 'content'" class="tab-panel">
      <div v-if="!editing" class="content-view">
        <div class="content-meta">
          <span><b>#{{ chunk.chunk_index }}</b></span>
          <span>{{ chunk.char_count }} 字</span>
          <span v-if="chunk.metadata?.section">章节: {{ chunk.metadata.section }}</span>
          <span v-if="chunk.page_start != null">页码: {{ chunk.page_start }}<template v-if="chunk.page_end != null && chunk.page_end !== chunk.page_start">-{{ chunk.page_end }}</template></span>
          <span v-if="chunk.edited" class="tag-blue" style="font-size: 11px; padding: 0 6px; border-radius: 3px;">已编辑</span>
        </div>
        <div class="content-text">{{ chunk.content }}</div>
        <div class="content-actions">
          <button class="btn btn-default btn-small" @click="startEdit">编辑</button>
          <button class="btn btn-small" style="color: #e6393d; background: none; border: 1px solid #e6393d;" @click="handleDelete">删除</button>
        </div>
      </div>
      <div v-else class="content-edit">
        <textarea v-model="editContent" class="edit-textarea" rows="12"></textarea>
        <div class="content-actions">
          <button class="btn btn-primary btn-small" @click="handleSave" :disabled="saving">保存</button>
          <button class="btn btn-default btn-small" @click="cancelEdit">取消</button>
        </div>
      </div>
    </div>

    <!-- Tab 2: Fulltext location -->
    <div v-if="activeTab === 'fulltext'" class="tab-panel tab-panel-fulltext">
      <div v-if="fulltextLoading" class="placeholder-text">加载中...</div>
      <div v-else-if="fulltextError" class="placeholder-text error-text">加载原文失败: {{ fulltextError }}</div>
      <FulltextHighlight
        v-else-if="cachedFulltext != null"
        :fulltext="cachedFulltext"
        :chunkContent="chunk.content"
        :chunkOffsetStart="chunk.char_offset_start"
        :chunkOffsetEnd="chunk.char_offset_end"
      />
      <div v-else class="placeholder-text">暂无原文数据</div>
    </div>

    <!-- Tab 3: Adjacent chunks -->
    <div v-if="activeTab === 'context'" class="tab-panel">
      <div v-if="context">
        <div v-if="context.prev" class="context-block">
          <div class="context-label">上一个切片 #{{ context.prev.chunk_index }}</div>
          <div class="context-text">{{ context.prev.content }}</div>
        </div>
        <div v-else class="context-empty">没有上一个切片</div>

        <div class="context-divider"></div>

        <div class="context-current">
          <div class="context-label" style="color: #0073e6;">当前切片 #{{ chunk.chunk_index }}</div>
          <div class="context-text" style="background: #e6f7ff;">{{ chunk.content }}</div>
        </div>

        <div class="context-divider"></div>

        <div v-if="context.next" class="context-block">
          <div class="context-label">下一个切片 #{{ context.next.chunk_index }}</div>
          <div class="context-text">{{ context.next.content }}</div>
        </div>
        <div v-else class="context-empty">没有下一个切片</div>
      </div>
      <div v-else class="placeholder-text">加载中...</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import type { Chunk, ChunkContext } from '../../api/knowledge'
import { editChunk, deleteChunk, getFulltext } from '../../api/knowledge'
import FulltextHighlight from './FulltextHighlight.vue'

const props = defineProps<{
  chunk: Chunk
  context: ChunkContext | null
  kbId: string
  docId: string
}>()

const emit = defineEmits<{
  updated: []
  deleted: [chunkIndex: number]
}>()

const tabs = [
  { key: 'content', label: '切片内容' },
  { key: 'fulltext', label: '原文定位' },
  { key: 'context', label: '相邻切片' },
]

const activeTab = ref('content')
const editing = ref(false)
const editContent = ref('')
const saving = ref(false)

// Fulltext state — cached per docId so we don't refetch on chunk change
const cachedFulltext = ref<string | null>(null)
const fulltextLoading = ref(false)
const fulltextError = ref<string | null>(null)
let cachedFulltextDocId: string | null = null

async function loadFulltext() {
  if (cachedFulltextDocId === props.docId && cachedFulltext.value != null) return
  fulltextLoading.value = true
  fulltextError.value = null
  try {
    const res = await getFulltext(props.kbId, props.docId)
    const data = res.data
    cachedFulltext.value = typeof data === 'string' ? data : (data as any).fulltext ?? (data as any).content ?? String(data)
    cachedFulltextDocId = props.docId
  } catch (e: any) {
    fulltextError.value = e.response?.data?.error || e.message || '未知错误'
  } finally {
    fulltextLoading.value = false
  }
}

watch(activeTab, (tab) => {
  if (tab === 'fulltext') loadFulltext()
})

watch(() => props.chunk.id, () => {
  editing.value = false
  activeTab.value = 'content'
})

watch(() => props.docId, () => {
  // Reset fulltext cache when document changes
  cachedFulltext.value = null
  cachedFulltextDocId = null
  fulltextError.value = null
})

function startEdit() {
  editContent.value = props.chunk.content
  editing.value = true
}

function cancelEdit() {
  editing.value = false
}

async function handleSave() {
  if (saving.value) return
  saving.value = true
  try {
    await editChunk(props.kbId, props.docId, props.chunk.chunk_index, editContent.value)
    editing.value = false
    emit('updated')
  } catch (e: any) {
    alert('保存失败: ' + (e.response?.data?.error || e.message))
  } finally {
    saving.value = false
  }
}

async function handleDelete() {
  if (!confirm(`确认删除切片 #${props.chunk.chunk_index}？`)) return
  try {
    await deleteChunk(props.kbId, props.docId, props.chunk.chunk_index)
    emit('deleted', props.chunk.chunk_index)
  } catch (e: any) {
    alert('删除失败: ' + (e.response?.data?.error || e.message))
  }
}
</script>

<style scoped>
.chunk-content {
  height: 100%;
  display: flex;
  flex-direction: column;
}

.tab-bar {
  display: flex;
  gap: 0;
  border-bottom: 1px solid #e5e5e5;
  flex-shrink: 0;
}

.tab-item {
  padding: 10px 20px;
  font-size: 14px;
  color: #666;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.15s;
}

.tab-item:hover {
  color: #333;
}

.tab-item.active {
  color: #0073e6;
  font-weight: 600;
  border-bottom-color: #0073e6;
}

.tab-panel {
  flex: 1;
  overflow-y: auto;
  padding: 16px 20px;
}

.content-meta {
  display: flex;
  gap: 12px;
  font-size: 13px;
  color: #575d6c;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.content-text {
  font-size: 14px;
  line-height: 1.8;
  color: #333;
  white-space: pre-wrap;
  word-break: break-word;
  background: #fafafa;
  border: 1px solid #ebebeb;
  border-radius: 4px;
  padding: 16px;
}

.content-actions {
  display: flex;
  gap: 8px;
  margin-top: 16px;
}

.content-edit {
  display: flex;
  flex-direction: column;
}

.edit-textarea {
  width: 100%;
  border: 1px solid #c2c6cc;
  border-radius: 4px;
  padding: 12px;
  font-size: 14px;
  line-height: 1.8;
  color: #333;
  resize: vertical;
  outline: none;
  font-family: inherit;
}

.edit-textarea:focus {
  border-color: #0073e6;
  box-shadow: 0 0 0 2px rgba(0, 115, 230, 0.1);
}

.placeholder-text {
  color: #adb0b8;
  font-size: 14px;
  text-align: center;
  padding: 48px 0;
}

.error-text {
  color: #e6393d;
}

.tab-panel-fulltext {
  padding: 16px 20px;
  overflow-y: auto;
}

.context-block,
.context-current {
  margin-bottom: 4px;
}

.context-label {
  font-size: 12px;
  font-weight: 600;
  color: #575d6c;
  margin-bottom: 8px;
}

.context-text {
  font-size: 13px;
  line-height: 1.7;
  color: #333;
  white-space: pre-wrap;
  word-break: break-word;
  background: #fafafa;
  border: 1px solid #ebebeb;
  border-radius: 4px;
  padding: 12px;
}

.context-empty {
  font-size: 13px;
  color: #adb0b8;
  padding: 12px 0;
}

.context-divider {
  height: 1px;
  background: #ebebeb;
  margin: 16px 0;
}
</style>
