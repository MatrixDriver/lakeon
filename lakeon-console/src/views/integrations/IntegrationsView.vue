<template>
  <main class="integrations-page">
    <div class="integrations-inner">
      <h1>{{ t('连接你的 AI 工具', 'Connect Your AI Tools') }}</h1>
      <p class="integ-subtitle">
        {{ t('通过 MCP 协议，让任何 AI 应用接入 DBay 的记忆、知识和数据能力', 'Connect any AI application to DBay memory, knowledge, and data capabilities via MCP protocol') }}
      </p>

      <!-- Quick start -->
      <section class="quickstart">
        <div class="quickstart-header">
          <h2>{{ t('快速开始', 'Quick Start') }}</h2>
          <span class="quickstart-badge">~3 min</span>
        </div>
        <div class="quickstart-steps">
          <div class="step" v-for="(step, i) in quickstartSteps" :key="i">
            <div class="step-num">{{ i + 1 }}</div>
            <div class="step-body">
              <div class="step-title">{{ locale === 'zh' ? step.titleZh : step.title }}</div>
              <div class="code-wrapper" v-if="step.code">
                <pre class="code-block"><code>{{ step.code }}</code></pre>
                <button class="copy-btn" @click="copyText(step.code)">{{ copiedKey === step.code ? t('已复制', 'Copied') : t('复制', 'Copy') }}</button>
              </div>
              <p class="step-note" v-if="step.note">{{ locale === 'zh' ? step.noteZh : step.note }}</p>
            </div>
          </div>
        </div>
        <div class="quickstart-result">
          <div class="result-label">{{ t('完成后，在 Claude Code 中直接说：', 'Then in Claude Code, just say:') }}</div>
          <div class="result-examples">
            <span class="result-example">"{{ t('记住我喜欢用 TypeScript', 'Remember I prefer TypeScript') }}"<span class="result-arrow">&rarr;</span>{{ t('保存到记忆库', 'saves to memory') }}</span>
            <span class="result-example">"{{ t('我之前说过什么偏好？', 'What preferences did I mention?') }}"<span class="result-arrow">&rarr;</span>{{ t('从记忆库召回', 'recalls from memory') }}</span>
          </div>
        </div>
        <p class="quickstart-tip">{{ t('如果提示 MCP failed to connect，请检查 Python 是否已安装：python --version（需要 3.11+）', 'If you see "MCP failed to connect", check Python is installed: python --version (requires 3.11+)') }}</p>
      </section>

      <!-- OpenClaw spotlight -->
      <router-link to="/integrations/openclaw" class="spotlight">
        <div class="spotlight-text">
          <h3>OpenClaw</h3>
          <p>{{ t('龙虾 AI 助手，原生 DBay 记忆集成。每次对话自动回忆、自动捕获、自动反思。', 'AI assistant with native DBay memory. Auto-recall, auto-capture, and auto-digest on every conversation.') }}</p>
        </div>
        <span class="spotlight-action">{{ t('查看详情', 'View details') }} &rarr;</span>
      </router-link>

      <!-- Tool setup panel -->
      <section class="tools-section">
        <h2>{{ t('接入其他工具', 'Setup for Other Tools') }}</h2>
        <div class="tools-panel">
          <nav class="tools-nav">
            <button
              v-for="tool in tools"
              :key="tool.id"
              :id="tool.id"
              class="tool-tab"
              :class="{ active: activeTool === tool.id }"
              @click="activeTool = tool.id"
            >
              <span class="tool-tab-name">{{ tool.name }}</span>
              <span class="tool-tab-desc">{{ locale === 'zh' ? tool.shortZh : tool.short }}</span>
            </button>
          </nav>
          <div class="tools-content">
            <div class="code-wrapper">
              <pre class="code-block"><code>{{ activeSetup }}</code></pre>
              <button class="copy-btn" @click="copyText(activeSetup)">{{ copiedKey === activeSetup ? t('已复制', 'Copied') : t('复制', 'Copy') }}</button>
            </div>
          </div>
        </div>
        <p class="tools-more">{{ t('更多工具即将支持。如有集成需求，欢迎提交', 'More tools coming soon. To request an integration, submit an') }} <a href="https://github.com/MatrixDriver/lakeon/issues" target="_blank" rel="noopener">Issue</a></p>
      </section>
    </div>
  </main>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useLocale } from '../../stores/locale'
import { listKnowledgeBases, type KnowledgeBase } from '../../api/knowledge'
import { listMemoryBases, type MemoryBase } from '../../api/memory'

const { locale, t } = useLocale()

const knowledgeBases = ref<KnowledgeBase[]>([])
const memoryBases = ref<MemoryBase[]>([])

onMounted(async () => {
  const apiKey = localStorage.getItem('lakeon_api_key')
  if (!apiKey) return
  try {
    const [kbRes, memRes] = await Promise.all([listKnowledgeBases(), listMemoryBases()])
    knowledgeBases.value = kbRes.data
    memoryBases.value = memRes.data.filter(b => b.status === 'READY')
  } catch { /* ignore */ }
})

const copiedKey = ref('')
function copyText(text: string) {
  navigator.clipboard.writeText(text)
  copiedKey.value = text
  setTimeout(() => { copiedKey.value = '' }, 1500)
}

const quickstartSteps = [
  {
    titleZh: '安装并登录', title: 'Install & login',
    code: 'pip install dbay-cli\ndbay login',
    noteZh: 'API Key 存放在 ~/.dbay/config.json，不进入 Claude 配置文件或代码仓库。',
    note: 'API Key lives in ~/.dbay/config.json — never enters Claude config or your repo.',
  },
  {
    titleZh: '连接 Claude Code', title: 'Connect Claude Code',
    code: 'claude mcp add --scope user dbay -- python -m dbay_mcp',
  },
  {
    titleZh: '创建记忆库', title: 'Create a memory base',
    code: 'dbay memory create                # plain memory\ndbay memory create --encrypted    # encrypted (e2e, you\'ll be prompted to set a password)',
    noteZh: 'Embedding 可选：1) DBay（默认）  2) 自有 API  3) 本地模型（即将支持）',
    note: 'Embedding options: 1) DBay (default)  2) External API  3) Local model (coming soon)',
  },
]

const tools = [
  {
    id: 'claude-code', name: 'Claude Code',
    shortZh: 'MCP 命令行注册', short: 'CLI registration',
    setup: `# 1. Install & login
pip install dbay-cli
dbay login

# 2. Connect to Claude Code
claude mcp add --scope user dbay -- python -m dbay_mcp

# 3. Create a memory base
dbay memory create                # plain memory
dbay memory create --encrypted    # encrypted (e2e, you'll be prompted to set a password)`,
  },
  {
    id: 'claude-desktop', name: 'Claude Desktop',
    shortZh: 'JSON 配置文件', short: 'JSON config',
    setup: `# 1. Install & login
pip install dbay-cli
dbay login

# 2. Add to Claude Desktop config (~/.claude/claude_desktop_config.json):
{
  "mcpServers": {
    "dbay": {
      "command": "python",
      "args": ["-m", "dbay_mcp"]
    }
  }
}

# 3. Create a memory base
dbay memory create                # plain memory
dbay memory create --encrypted    # encrypted (e2e, you'll be prompted to set a password)`,
  },
  {
    id: 'cursor', name: 'Cursor',
    shortZh: '项目级 MCP 配置', short: 'Project MCP config',
    setup: `# 1. Install & login
pip install dbay-cli
dbay login

# 2. Add to .cursor/mcp.json in your project:
{
  "mcpServers": {
    "dbay": {
      "command": "python",
      "args": ["-m", "dbay_mcp"]
    }
  }
}

# 3. Create a memory base
dbay memory create                # plain memory
dbay memory create --encrypted    # encrypted (e2e, you'll be prompted to set a password)`,
  },
  {
    id: 'gemini-cli', name: 'Gemini CLI',
    shortZh: '全局配置文件', short: 'Global settings',
    setup: `# 1. Install & login
pip install dbay-cli
dbay login

# 2. Add to ~/.gemini/settings.json:
{
  "mcpServers": {
    "dbay": {
      "command": "python",
      "args": ["-m", "dbay_mcp"]
    }
  }
}

# 3. Create a memory base
dbay memory create                # plain memory
dbay memory create --encrypted    # encrypted (e2e, you'll be prompted to set a password)`,
  },
  {
    id: 'chatgpt', name: 'ChatGPT',
    shortZh: 'REST API 集成', short: 'REST API',
    setup: `# ChatGPT integrates via REST API (requires a middleware layer)
# See API docs: https://dbay.cloud/docs/rest-api`,
  },
]

const activeTool = ref('claude-code')
const activeSetup = computed(() => tools.find(t => t.id === activeTool.value)?.setup ?? '')
</script>

<style scoped>
.integrations-page {
  min-height: 100vh;
  background: var(--pub-bg);
  color: var(--pub-text);
}
.integrations-inner {
  max-width: 860px;
  margin: 0 auto;
  padding: 56px 24px 80px;
}

h1 {
  font-size: 28px;
  font-weight: 700;
  letter-spacing: -0.02em;
  margin: 0 0 6px;
}
.integ-subtitle {
  color: var(--pub-text-3);
  font-size: 14px;
  line-height: 1.5;
  margin: 0 0 40px;
  max-width: 520px;
}

/* ---- Quick Start ---- */
.quickstart {
  margin-bottom: 48px;
}
.quickstart-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 20px;
}
.quickstart-header h2 {
  font-size: 17px;
  font-weight: 600;
  margin: 0;
}
.quickstart-badge {
  font-size: 11px;
  color: var(--pub-hint-text);
  background: var(--pub-hint-bg);
  border: 1px solid var(--pub-hint-border);
  padding: 1px 8px;
  border-radius: 10px;
  font-weight: 500;
}

.quickstart-steps {
  display: flex;
  flex-direction: column;
  gap: 0;
}
.step {
  display: flex;
  gap: 14px;
  padding: 16px 0;
  border-bottom: 1px solid var(--pub-border);
}
.step:first-child { padding-top: 0; }
.step:last-child { border-bottom: none; }

.step-num {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--pub-text);
  color: var(--pub-surface);
  font-size: 12px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin-top: 1px;
}
.step-body {
  flex: 1;
  min-width: 0;
}
.step-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
  color: var(--pub-text);
}
.step-note {
  font-size: 12px;
  color: var(--pub-text-4);
  margin: 8px 0 0;
  line-height: 1.5;
}

/* ---- Quick Start result ---- */
.quickstart-result {
  margin-top: 20px;
  padding: 14px 16px;
  background: var(--pub-accent-bg);
  border-radius: 8px;
}
.result-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--pub-text-2);
  margin-bottom: 8px;
}
.result-examples {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.result-example {
  font-size: 13px;
  color: var(--pub-text);
  font-family: monospace;
}
.result-arrow {
  display: inline-block;
  margin: 0 8px;
  color: var(--pub-text-4);
  font-family: -apple-system, BlinkMacSystemFont, sans-serif;
}

.quickstart-tip {
  font-size: 11px;
  color: var(--pub-text-4);
  margin: 12px 0 0;
  line-height: 1.5;
}

/* ---- OpenClaw spotlight ---- */
.spotlight {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 20px 24px;
  margin-bottom: 48px;
  background: var(--pub-surface);
  border: 1px solid var(--pub-border);
  border-radius: 10px;
  text-decoration: none;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.spotlight:hover {
  border-color: var(--pub-code);
  box-shadow: 0 2px 12px var(--pub-shadow);
}
.spotlight-text {
  flex: 1;
}
.spotlight-text h3 {
  font-size: 15px;
  font-weight: 600;
  color: var(--pub-text);
  margin: 0 0 4px;
}
.spotlight-text p {
  font-size: 13px;
  color: var(--pub-text-2);
  margin: 0;
  line-height: 1.5;
}
.spotlight-action {
  font-size: 13px;
  color: var(--pub-code);
  white-space: nowrap;
  flex-shrink: 0;
}

/* ---- Tool setup panel ---- */
.tools-section {
  margin-bottom: 40px;
}
.tools-section h2 {
  font-size: 17px;
  font-weight: 600;
  margin: 0 0 16px;
}
.tools-panel {
  display: grid;
  grid-template-columns: 180px 1fr;
  border: 1px solid var(--pub-border);
  border-radius: 10px;
  overflow: hidden;
  background: var(--pub-surface);
}

.tools-nav {
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--pub-border);
  background: var(--pub-bg);
}
.tool-tab {
  all: unset;
  display: flex;
  flex-direction: column;
  padding: 12px 16px;
  cursor: pointer;
  border-bottom: 1px solid var(--pub-border);
  transition: background 0.15s;
}
.tool-tab:last-child { border-bottom: none; }
.tool-tab:hover { background: var(--pub-hover); }
.tool-tab.active {
  background: var(--pub-surface);
  box-shadow: inset 3px 0 0 var(--pub-code);
}
.tool-tab-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--pub-text);
}
.tool-tab-desc {
  font-size: 11px;
  color: var(--pub-text-4);
  margin-top: 2px;
}

.tools-content {
  padding: 16px;
  min-height: 240px;
}

.tools-more {
  font-size: 12px;
  color: var(--pub-text-4);
  margin: 12px 0 0;
}
.tools-more a {
  color: var(--pub-code);
  text-decoration: none;
}
.tools-more a:hover { text-decoration: underline; }

/* ---- Shared: code blocks ---- */
.code-wrapper { position: relative; }
.code-block {
  background: var(--pub-code-bg);
  border: 1px solid var(--pub-border);
  border-radius: 6px;
  padding: 14px;
  font-size: 12px;
  color: var(--pub-code);
  overflow-x: auto;
  margin: 0;
  font-family: 'SF Mono', 'Cascadia Code', 'Fira Code', monospace;
  white-space: pre;
  line-height: 1.6;
}
.copy-btn {
  position: absolute;
  top: 8px;
  right: 8px;
  background: var(--pub-surface);
  border: 1px solid var(--pub-border);
  border-radius: 4px;
  padding: 2px 8px;
  font-size: 11px;
  color: var(--pub-text-4);
  cursor: pointer;
  transition: color 0.15s, border-color 0.15s;
}
.copy-btn:hover { color: var(--pub-code); border-color: var(--pub-code); }

/* ---- Responsive ---- */
@media (max-width: 640px) {
  .tools-panel {
    grid-template-columns: 1fr;
  }
  .tools-nav {
    flex-direction: row;
    border-right: none;
    border-bottom: 1px solid var(--pub-border);
    overflow-x: auto;
  }
  .tool-tab {
    border-bottom: none;
    border-right: 1px solid var(--pub-border);
    white-space: nowrap;
    padding: 10px 14px;
  }
  .tool-tab:last-child { border-right: none; }
  .tool-tab.active {
    box-shadow: inset 0 -2px 0 var(--pub-code);
  }
  .tool-tab-desc { display: none; }
  .spotlight { flex-direction: column; align-items: flex-start; }
}
</style>
