<template>
  <!-- Floating button -->
  <button v-if="!open" class="ai-fab" @click="open = true" title="AI 助手">
    <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2">
      <path d="M12 2a7 7 0 0 1 7 7c0 2.38-1.19 4.47-3 5.74V17a2 2 0 0 1-2 2h-4a2 2 0 0 1-2-2v-2.26C6.19 13.47 5 11.38 5 9a7 7 0 0 1 7-7z"/>
      <line x1="10" y1="21" x2="14" y2="21"/>
    </svg>
  </button>

  <!-- Chat panel -->
  <div v-if="open" class="ai-panel">
    <div class="ai-panel-header">
      <span class="ai-panel-title">AI 诊断助手</span>
      <div style="display: flex; gap: 8px;">
        <button class="ai-header-btn" @click="clearChat" title="清空对话">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 6h18M8 6V4a2 2 0 012-2h4a2 2 0 012 2v2m-1 0v12a2 2 0 01-2 2H9a2 2 0 01-2-2V6"/></svg>
        </button>
        <button class="ai-header-btn" @click="open = false" title="关闭">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 6L6 18M6 6l12 12"/></svg>
        </button>
      </div>
    </div>

    <div class="ai-messages" ref="messagesEl">
      <div v-if="chatMessages.length === 0" class="ai-empty">
        <p>我是 DBay SRE 智能助手，可以帮你：</p>
        <ul>
          <li>诊断数据库启动失败原因</li>
          <li>分析组件异常日志</li>
          <li>排查 K8s Pod 调度问题</li>
          <li>查看系统健康和告警状态</li>
        </ul>
        <p style="color: #999; font-size: 12px; margin-top: 12px;">输入问题开始诊断</p>
      </div>
      <div v-for="(msg, i) in chatMessages" :key="i" :class="'ai-msg ai-msg-' + msg.role">
        <div v-if="msg.role === 'user'" class="ai-msg-content">{{ msg.content }}</div>
        <div v-else class="ai-msg-content">
          <!-- Thinking / tool calls -->
          <div v-if="msg.steps && msg.steps.length" class="ai-steps">
            <div v-for="(step, j) in msg.steps" :key="j" class="ai-step">
              <span v-if="step.type === 'thinking'" class="ai-step-thinking">{{ step.content }}</span>
              <span v-else-if="step.type === 'tool_call'" class="ai-step-tool">
                调用 {{ step.content }}
              </span>
              <span v-else-if="step.type === 'tool_result'" class="ai-step-result">{{ step.content }}</span>
            </div>
          </div>
          <!-- Final content -->
          <div v-if="msg.content" class="ai-answer" v-html="renderMarkdown(msg.content)"></div>
          <!-- Streaming indicator -->
          <span v-if="msg.streaming" class="ai-typing">●</span>
        </div>
      </div>
    </div>

    <div class="ai-input-area">
      <textarea
        ref="inputEl"
        v-model="inputText"
        placeholder="描述你遇到的问题..."
        rows="2"
        @keydown.enter.exact.prevent="sendMessage"
        :disabled="streaming"
      ></textarea>
      <button class="ai-send-btn" @click="sendMessage" :disabled="streaming || !inputText.trim()">
        <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><path d="M2 21l21-9L2 3v7l15 2-15 2z"/></svg>
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, watch } from 'vue'
import { adminApi } from '../api/admin'

interface ChatStep {
  type: 'thinking' | 'tool_call' | 'tool_result' | 'error'
  content: string
}

interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
  steps?: ChatStep[]
  streaming?: boolean
}

const open = ref(false)
const inputText = ref('')
const streaming = ref(false)
const chatMessages = ref<ChatMessage[]>([])
const messagesEl = ref<HTMLElement>()
const inputEl = ref<HTMLTextAreaElement>()

const pendingContext = ref<{resource_type: string; resource_id: string} | undefined>()

function openWithContext(resourceType: string, resourceId: string, question: string) {
  open.value = true
  pendingContext.value = { resource_type: resourceType, resource_id: resourceId }
  inputText.value = question
  nextTick(() => sendMessage())
}

function clearChat() {
  chatMessages.value = []
  pendingContext.value = undefined
}

async function sendMessage() {
  const text = inputText.value.trim()
  if (!text || streaming.value) return

  chatMessages.value.push({ role: 'user', content: text })
  inputText.value = ''
  streaming.value = true

  const assistantMsg: ChatMessage = { role: 'assistant', content: '', steps: [], streaming: true }
  chatMessages.value.push(assistantMsg)
  scrollToBottom()

  try {
    const apiMessages = chatMessages.value
      .filter(m => m.role === 'user' || (m.role === 'assistant' && m.content && !m.streaming))
      .map(m => ({ role: m.role, content: m.content }))

    const response = await adminApi.aiChat(apiMessages, pendingContext.value)
    pendingContext.value = undefined

    if (!response.ok) {
      assistantMsg.content = `请求失败: ${response.status}`
      assistantMsg.streaming = false
      streaming.value = false
      return
    }

    const reader = response.body?.getReader()
    if (!reader) {
      assistantMsg.content = '无法读取响应流'
      assistantMsg.streaming = false
      streaming.value = false
      return
    }

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
        const jsonStr = line.slice(5).trim()
        if (!jsonStr) continue

        try {
          const event = JSON.parse(jsonStr)
          if (event.type === 'content') {
            assistantMsg.content = event.content
          } else if (event.type === 'thinking' || event.type === 'tool_call' || event.type === 'tool_result') {
            assistantMsg.steps!.push({ type: event.type, content: event.content })
          } else if (event.type === 'error') {
            assistantMsg.content = event.content
          }
          scrollToBottom()
        } catch { /* skip malformed events */ }
      }
    }
  } catch (e: any) {
    assistantMsg.content = '连接失败: ' + (e.message || '网络错误')
  }

  assistantMsg.streaming = false
  streaming.value = false
  scrollToBottom()
}

function scrollToBottom() {
  nextTick(() => {
    if (messagesEl.value) {
      messagesEl.value.scrollTop = messagesEl.value.scrollHeight
    }
  })
}

function renderMarkdown(text: string): string {
  // Escape HTML
  let html = text
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  // Code blocks (must be before inline replacements)
  html = html.replace(/```(\w*)\n([\s\S]*?)```/g, '<pre><code>$2</code></pre>')
  // Inline code
  html = html.replace(/`([^`]+)`/g, '<code>$1</code>')
  // Bold
  html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
  // Headings (### before ## before #)
  html = html.replace(/^### (.+)$/gm, '<h4 style="margin:8px 0 4px;font-size:14px;">$1</h4>')
  html = html.replace(/^## (.+)$/gm, '<h3 style="margin:10px 0 4px;font-size:15px;">$1</h3>')
  html = html.replace(/^# (.+)$/gm, '<h3 style="margin:10px 0 4px;font-size:16px;">$1</h3>')
  // Unordered lists (- item)
  html = html.replace(/^- (.+)$/gm, '<li style="margin-left:16px;list-style:disc;">$1</li>')
  // Ordered lists (1. item)
  html = html.replace(/^\d+\. (.+)$/gm, '<li style="margin-left:16px;list-style:decimal;">$1</li>')
  // Line breaks (for remaining newlines not consumed by headings/lists)
  html = html.replace(/\n/g, '<br>')
  // Clean up <br> after block elements
  html = html.replace(/<\/h[34]><br>/g, '</h3>').replace(/<\/h4><br>/g, '</h4>')
  html = html.replace(/<\/li><br>/g, '</li>')
  html = html.replace(/<\/pre><br>/g, '</pre>')
  return html
}

watch(open, (val) => {
  if (val) nextTick(() => inputEl.value?.focus())
})

defineExpose({ openWithContext })
</script>

<style scoped>
.ai-fab {
  position: fixed;
  bottom: 24px;
  right: 24px;
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: #0073e6;
  color: #fff;
  border: none;
  cursor: pointer;
  box-shadow: 0 4px 12px rgba(0,0,0,0.2);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  transition: transform 0.15s;
}
.ai-fab:hover { transform: scale(1.08); }

.ai-panel {
  position: fixed;
  top: 0;
  right: 0;
  width: 420px;
  height: 100vh;
  background: #fff;
  border-left: 1px solid #e2e8f0;
  box-shadow: -4px 0 16px rgba(0,0,0,0.08);
  display: flex;
  flex-direction: column;
  z-index: 1001;
}

.ai-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border-bottom: 1px solid #e2e8f0;
  background: #f8fafc;
}
.ai-panel-title { font-weight: 600; font-size: 14px; }
.ai-header-btn {
  background: none; border: none; cursor: pointer; padding: 4px;
  color: #64748b; border-radius: 4px;
}
.ai-header-btn:hover { background: #e2e8f0; }

.ai-messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ai-empty {
  color: #64748b;
  font-size: 13px;
  padding: 24px 8px;
}
.ai-empty ul { margin: 8px 0; padding-left: 20px; }
.ai-empty li { margin: 4px 0; }

.ai-msg-user .ai-msg-content {
  background: #0073e6;
  color: #fff;
  padding: 8px 12px;
  border-radius: 12px 12px 2px 12px;
  max-width: 85%;
  align-self: flex-end;
  font-size: 13px;
  margin-left: auto;
}

.ai-msg-assistant .ai-msg-content {
  background: #f1f5f9;
  padding: 10px 14px;
  border-radius: 12px 12px 12px 2px;
  max-width: 95%;
  font-size: 13px;
}

.ai-steps {
  margin-bottom: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.ai-step { font-size: 12px; color: #64748b; }
.ai-step-thinking { font-style: italic; }
.ai-step-tool { color: #0073e6; }
.ai-step-result { color: #059669; font-size: 11px; }

.ai-answer { line-height: 1.6; }
.ai-answer :deep(pre) {
  background: #1e293b;
  color: #e2e8f0;
  padding: 8px 12px;
  border-radius: 6px;
  overflow-x: auto;
  font-size: 12px;
  margin: 8px 0;
}
.ai-answer :deep(code) {
  background: #e2e8f0;
  padding: 1px 4px;
  border-radius: 3px;
  font-size: 12px;
}
.ai-answer :deep(pre code) {
  background: none;
  padding: 0;
}

.ai-typing {
  display: inline-block;
  animation: blink 1s infinite;
  color: #0073e6;
}
@keyframes blink { 0%, 100% { opacity: 1; } 50% { opacity: 0.3; } }

.ai-input-area {
  border-top: 1px solid #e2e8f0;
  padding: 12px;
  display: flex;
  gap: 8px;
  background: #f8fafc;
}
.ai-input-area textarea {
  flex: 1;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  padding: 8px 12px;
  font-size: 13px;
  resize: none;
  outline: none;
  font-family: inherit;
}
.ai-input-area textarea:focus { border-color: #0073e6; }

.ai-send-btn {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  background: #0073e6;
  color: #fff;
  border: none;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  align-self: flex-end;
}
.ai-send-btn:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
