<script setup lang="ts">
import { ref, nextTick, onMounted } from 'vue'
import { wikiAgentChatStream, getChatHistory, saveChatHistory } from '@/api/knowledge'
import MarkdownRenderer from '@/components/MarkdownRenderer.vue'

const props = defineProps<{ kbId: string }>()
const emit = defineEmits<{ (e: 'navigate', title: string): void }>()

interface AgentEvent {
  type: 'thinking' | 'tool_call' | 'tool_result' | 'content' | 'done' | 'error'
  content?: string
  tool?: string
  args?: Record<string, any>
  ok?: boolean
  summary?: string
  message?: string
  status?: string
  pages_created?: number
  pages_updated?: number
  tool_calls_count?: number
  duration_ms?: number
}

interface Message {
  role: 'user' | 'assistant'
  content: string
  events?: AgentEvent[]
  depth?: string      // legacy: from old direct-LLM fallback
  sources?: string[]  // legacy: from old direct-LLM fallback
  saved?: boolean
  saving?: boolean
}

async function loadMessagesFromServer(): Promise<Message[]> {
  try {
    const resp = await getChatHistory(props.kbId)
    const data = resp.data
    if (Array.isArray(data)) return data
    return []
  } catch { return [] }
}

function saveMessages() {
  if (!messages.value.length) {
    saveChatHistory(props.kbId, []).catch(() => {})
    return
  }
  const toSave = messages.value
    .filter(m => m.content)
    .map(({ role, content, events, saved }) => ({ role, content, events, saved }))
  saveChatHistory(props.kbId, toSave).catch(() => {})
}

const messages = ref<Message[]>([])
const input = ref('')
const loading = ref(false)
const messagesEl = ref<HTMLDivElement>()

// Review mode state
const reviewMode = ref(false)
const reviewDocId = ref<string | null>(null)

onMounted(async () => {
  messages.value = await loadMessagesFromServer()
  scrollToBottom()
})

function startReview(docId: string, filename: string) {
  reviewMode.value = true
  reviewDocId.value = docId
  // Auto-send a review prompt
  const name = filename.replace(/\.md$/, '')
  input.value = `请审核新文档「${name}」(document_id=${docId})，阅读后告诉我你打算如何更新 wiki，等我确认后再执行修改。`
  nextTick(() => send())
}

defineExpose({ startReview })

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
    events: [],
    saved: false,
    saving: false,
  }
  messages.value.push(assistantMsg)

  try {
    const history = messages.value.slice(0, -2).map(m => ({
      role: m.role, content: m.content
    }))

    const response = await wikiAgentChatStream(props.kbId, question, history,
      reviewMode.value ? { mode: 'review', documentId: reviewDocId.value || undefined } : undefined)
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
        if (!line.startsWith('data:')) continue
        const data = line.slice(5).trim()
        if (data === '[DONE]' || !data) continue

        try {
          const event = JSON.parse(data) as AgentEvent

          // Legacy fallback: old API sends {type: "chunk"} and {type: "meta"}
          if ((event as any).type === 'chunk') {
            assistantMsg.content += (event as any).content || ''
            if (assistantMsg.content.length % 50 < 5) await scrollToBottom()
            continue
          }
          if ((event as any).type === 'meta') continue

          // New agent events
          assistantMsg.events!.push(event)

          if (event.type === 'content') {
            assistantMsg.content += event.content || ''
          } else if (event.type === 'error') {
            assistantMsg.content = '抱歉，出错了: ' + (event.message || '')
          }

          await scrollToBottom()
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


function clearChat() {
  if (!confirm('确认清空对话历史？')) return
  messages.value = []
  reviewMode.value = false
  reviewDocId.value = null
  saveMessages()
}

function copyChat() {
  const text = messages.value
    .filter(m => m.content)
    .map(m => `### ${m.role === 'user' ? '用户' : 'Wiki Agent'}\n\n${m.content}`)
    .join('\n\n---\n\n')
  navigator.clipboard.writeText(text).then(() => {
    copiedToast.value = true
    setTimeout(() => { copiedToast.value = false }, 2000)
  })
}

const copiedToast = ref(false)

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
      <div v-else style="display: flex; justify-content: flex-end; gap: 6px; margin-bottom: 8px;">
        <span v-if="reviewMode" style="font-size: 11px; color: #c19a6b; border: 1px solid #c19a6b; border-radius: 4px; padding: 2px 8px; margin-right: auto;">审核模式</span>
        <button @click="copyChat" style="font-size: 11px; color: #b0a090; background: none; border: 1px solid #e0d8ce; border-radius: 4px; padding: 2px 8px; cursor: pointer;">复制对话</button>
        <button @click="clearChat" style="font-size: 11px; color: #b0a090; background: none; border: 1px solid #e0d8ce; border-radius: 4px; padding: 2px 8px; cursor: pointer;">清空</button>
      </div>
      <div v-if="copiedToast" style="position: fixed; top: 20px; left: 50%; transform: translateX(-50%); background: #386b47; color: #fff; padding: 6px 16px; border-radius: 6px; font-size: 13px; z-index: 999;">已复制</div>
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
          <!-- Agent events -->
          <div v-if="msg.events?.length" style="margin-bottom: 8px;">
            <template v-for="(ev, ei) in msg.events" :key="ei">
              <!-- Thinking -->
              <details v-if="ev.type === 'thinking'" class="agent-event">
                <summary class="agent-event-summary" style="color: #b0a090;">
                  <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2" style="vertical-align: -1px; margin-right: 4px;"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg>
                  思考中...
                </summary>
                <div class="agent-event-body">{{ ev.content }}</div>
              </details>

              <!-- Tool call -->
              <div v-else-if="ev.type === 'tool_call'" class="agent-tool-call">
                <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="#c19a6b" stroke-width="2" style="flex-shrink: 0;"><path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/></svg>
                <span>{{ ev.tool }}</span>
                <span v-if="ev.args?.title" style="color: #bbb;">&middot; {{ ev.args.title }}</span>
                <span v-else-if="ev.args?.query" style="color: #bbb;">&middot; "{{ ev.args.query }}"</span>
              </div>

              <!-- Tool result -->
              <details v-else-if="ev.type === 'tool_result'" class="agent-event">
                <summary class="agent-event-summary" :style="{ color: ev.ok !== false ? '#7a9e5a' : '#c25a3c' }">
                  <span v-if="ev.ok !== false">&#10003;</span><span v-else>&#10007;</span>
                  {{ ev.tool }} &middot; {{ ev.summary?.substring(0, 60) }}{{ (ev.summary?.length || 0) > 60 ? '...' : '' }}
                </summary>
                <div class="agent-event-body" style="white-space: pre-wrap; max-height: 200px; overflow-y: auto;">{{ ev.summary }}</div>
              </details>

              <!-- Done -->
              <div v-else-if="ev.type === 'done' && ev.status === 'success' && (ev.pages_created || ev.pages_updated)" style="margin-bottom: 4px; font-size: 12px; color: #7a9e5a;">
                &#10003; 完成
                <span v-if="ev.pages_created">&middot; 创建 {{ ev.pages_created }} 页</span>
                <span v-if="ev.pages_updated">&middot; 更新 {{ ev.pages_updated }} 页</span>
              </div>
            </template>
          </div>

          <!-- Content bubble -->
          <div v-if="msg.content" style="background: #fff; border: 1px solid #e8e0d8; padding: 12px 16px; border-radius: 8px;">
            <MarkdownRenderer :content="msg.content" :kb-id="kbId" @navigate="(t) => emit('navigate', t)" />
          </div>
          <div v-else-if="loading && i === messages.length - 1 && !msg.events?.length" style="color: #b0a090; padding: 8px;">
            思考中...
          </div>
          <!-- Done stats (pages created/updated) shown inline above -->
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

<style scoped>
.agent-event { margin-bottom: 4px; }
.agent-event summary {
  font-size: 12px;
  list-style: none;
  display: flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
}
.agent-event summary::-webkit-details-marker { display: none; }
.agent-event-summary::before {
  content: '\25b6';
  font-size: 8px;
  color: #ccc;
  transition: transform 0.15s;
  margin-right: 4px;
}
.agent-event[open] .agent-event-summary::before {
  transform: rotate(90deg);
}
.agent-event-body {
  padding: 6px 12px;
  margin: 4px 0;
  color: #999;
  font-size: 12px;
  border-left: 2px solid #e8e0d8;
}
.agent-tool-call {
  margin-bottom: 4px;
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #8c7a68;
}
</style>
