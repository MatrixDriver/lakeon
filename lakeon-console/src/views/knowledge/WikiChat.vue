<script setup lang="ts">
import { ref, nextTick, onMounted } from 'vue'
import { wikiChatStream, saveWikiResponse } from '@/api/knowledge'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'

const props = defineProps<{ kbId: string }>()
const emit = defineEmits<{ (e: 'navigate', title: string): void }>()

interface Message {
  role: 'user' | 'assistant'
  content: string
  depth?: string
  sources?: string[]
  saved?: boolean
  saving?: boolean
}

const STORAGE_KEY_PREFIX = 'wiki_chat_'
function storageKey() { return STORAGE_KEY_PREFIX + props.kbId }

function loadMessages(): Message[] {
  try {
    const raw = localStorage.getItem(storageKey())
    if (!raw) return []
    return JSON.parse(raw)
  } catch { return [] }
}

function saveMessages() {
  if (!messages.value.length) {
    localStorage.removeItem(storageKey())
    return
  }
  // Only persist completed messages (skip if assistant is still streaming)
  const toSave = messages.value
    .filter(m => m.content)
    .map(({ role, content, depth, sources, saved }) => ({ role, content, depth, sources, saved }))
  try {
    localStorage.setItem(storageKey(), JSON.stringify(toSave))
  } catch { /* quota exceeded — silently ignore */ }
}

const messages = ref<Message[]>([])
const input = ref('')
const loading = ref(false)
const messagesEl = ref<HTMLDivElement>()

onMounted(() => {
  messages.value = loadMessages()
  scrollToBottom()
})

async function send() {
  const question = input.value.trim()
  if (!question || loading.value) return

  messages.value.push({ role: 'user', content: question })
  input.value = ''
  loading.value = true
  await scrollToBottom()

  // Add empty assistant message that will be filled incrementally
  const assistantMsg: Message = {
    role: 'assistant',
    content: '',
    saved: false,
    saving: false,
  }
  messages.value.push(assistantMsg)

  try {
    const history = messages.value.slice(0, -2).map(m => ({
      role: m.role, content: m.content
    }))

    const response = await wikiChatStream(props.kbId, question, history)
    if (!response.ok) throw new Error(`HTTP ${response.status}`)

    const reader = response.body!.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        // SSE format: "data:" prefix
        if (!line.startsWith('data:')) continue
        const data = line.slice(5).trim()
        if (data === '[DONE]' || !data) continue

        try {
          const parsed = JSON.parse(data)
          if (parsed.type === 'meta') {
            assistantMsg.depth = parsed.depth
            assistantMsg.sources = parsed.sources
          } else if (parsed.type === 'chunk') {
            assistantMsg.content += parsed.content
            if (assistantMsg.content.length % 50 < 5) {
              await scrollToBottom()
            }
          } else if (parsed.type === 'error') {
            assistantMsg.content = '抱歉，出错了: ' + parsed.message
          }
        } catch { /* skip malformed */ }
      }
    }
  } catch (e: any) {
    if (!assistantMsg.content) {
      assistantMsg.content = '抱歉，出错了: ' + (e.message || '未知错误')
    }
  } finally {
    loading.value = false
    saveMessages()
    await scrollToBottom()
  }
}

async function saveToWiki(msg: Message) {
  if (msg.saving || msg.saved) return
  msg.saving = true
  try {
    const title = (msg.sources?.length ? msg.sources[0] : undefined) ?? '对话沉淀'
    await saveWikiResponse(props.kbId, title, msg.content)
    msg.saved = true
    saveMessages()
  } catch (e: any) {
    alert('保存失败: ' + (e.message || e))
  } finally {
    msg.saving = false
  }
}

function clearChat() {
  messages.value = []
  saveMessages()
}

function handleKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    send()
  }
}

async function scrollToBottom() {
  await nextTick()
  if (messagesEl.value) {
    messagesEl.value.scrollTop = messagesEl.value.scrollHeight
  }
}
</script>

<template>
  <div style="display: flex; flex-direction: column; height: 100%;">
    <!-- Messages -->
    <div ref="messagesEl" style="flex: 1; overflow-y: auto; padding: 16px;">
      <div v-if="messages.length === 0" style="color: #b0a090; text-align: center; padding: 40px 0;">
        基于知识库 Wiki 提问，获得智能回答
      </div>
      <div v-else style="display: flex; justify-content: flex-end; margin-bottom: 8px;">
        <button @click="clearChat" style="font-size: 11px; color: #b0a090; background: none; border: 1px solid #e0d8ce; border-radius: 4px; padding: 2px 8px; cursor: pointer;">清空对话</button>
      </div>
      <div v-for="(msg, i) in messages" :key="i" style="margin-bottom: 16px;">
        <div style="font-size: 12px; color: #b0a090; margin-bottom: 4px;">
          {{ msg.role === 'user' ? '你' : 'Wiki Agent' }}
          <span v-if="msg.depth" style="margin-left: 8px; font-size: 11px; padding: 1px 6px; border-radius: 3px;"
                :style="msg.depth === 'deep'
                  ? { background: '#fef3e5', color: '#c25a3c' }
                  : { background: '#f0ebe4', color: '#8c7a68' }">
            {{ msg.depth === 'deep' ? '深度分析' : '快速回答' }}
          </span>
        </div>
        <div v-if="msg.role === 'user'"
             style="background: #faf5f0; padding: 10px 14px; border-radius: 8px; color: #3d3d3d;">
          {{ msg.content }}
        </div>
        <div v-else>
          <!-- Show content bubble only when there's content, otherwise show thinking indicator -->
          <div v-if="msg.content" style="background: #fff; border: 1px solid #e8e0d8; padding: 12px 16px; border-radius: 8px;">
            <MarkdownRenderer :content="msg.content" :kb-id="kbId" @navigate="(t) => emit('navigate', t)" />
          </div>
          <div v-else-if="loading && i === messages.length - 1" style="color: #b0a090; padding: 8px;">
            思考中...
          </div>
          <!-- Show save button only when content is complete (not loading) -->
          <div v-if="msg.content && !loading && !msg.saved" style="margin-top: 6px;">
            <button :disabled="msg.saving"
                    style="font-size: 12px; color: #8b6914; background: none; border: 1px solid #8b6914; border-radius: 4px; padding: 3px 10px; cursor: pointer;"
                    @click="saveToWiki(msg)">
              {{ msg.saving ? '保存中...' : '沉淀到知识库' }}
            </button>
          </div>
          <span v-if="msg.saved" style="margin-top: 6px; font-size: 12px; color: #7a9e5a; display: inline-block;">
            已沉淀
          </span>
        </div>
      </div>
    </div>

    <!-- Input -->
    <div style="border-top: 1px solid #e8e0d8; padding: 12px 16px; display: flex; gap: 8px; background: #fdfbf8;">
      <textarea v-model="input"
                @keydown="handleKeydown"
                placeholder="基于知识库提问..."
                rows="1"
                style="flex: 1; resize: none; border: 1px solid #d4c4b0; border-radius: 6px; padding: 8px 12px; font-size: 14px; font-family: inherit; background: #fff; color: #3d3d3d; outline: none;" />
      <button @click="send"
              :disabled="loading || !input.trim()"
              :style="{
                padding: '8px 20px',
                background: loading || !input.trim() ? '#d4c4b0' : '#c25a3c',
                color: '#fff',
                border: 'none',
                borderRadius: '6px',
                cursor: loading || !input.trim() ? 'not-allowed' : 'pointer',
                whiteSpace: 'nowrap',
                fontFamily: 'inherit'
              }">
        发送
      </button>
    </div>
  </div>
</template>
