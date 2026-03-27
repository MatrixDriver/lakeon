<template>
  <main class="integrations-page">
    <div class="integrations-inner">
      <h1>{{ t('连接你的 AI 工具', 'Connect Your AI Tools') }}</h1>
      <p class="integ-subtitle">
        {{ t('通过 MCP 协议，让任何 AI 应用接入 DBay 的记忆、知识和数据能力', 'Connect any AI application to DBay memory, knowledge, and data capabilities via MCP protocol') }}
      </p>

      <!-- MCP 快速接入 -->
      <div class="mcp-quickstart">
        <h3>{{ t('5 分钟接入', '5-minute Setup') }}</h3>
        <div class="code-wrapper">
          <pre class="code-block"><code>{{ quickstartSnippet }}</code></pre>
          <button class="copy-btn" @click="copyText(quickstartSnippet)">{{ copyLabel }}</button>
        </div>
        <p class="tip">{{ t('API Key 存放在 ~/.dbay/config.json，不进入 Claude 配置文件或代码仓库。', 'API Key lives in ~/.dbay/config.json — never enters Claude config or your repo.') }}</p>
      </div>

      <!-- 集成卡片 -->
      <div class="integ-grid">
        <router-link
          v-for="tool in integrations"
          :key="tool.id"
          :to="tool.href"
          :id="tool.anchor"
          class="integ-card"
          :class="{ featured: tool.featured }"
        >
          <div class="integ-card-hd">
            <span class="integ-name">{{ tool.name }}</span>
            <span v-if="tool.featured" class="badge-featured">{{ t('精选', 'Featured') }}</span>
          </div>
          <p>{{ locale === 'zh' ? tool.descZh : tool.desc }}</p>
          <span class="integ-link">{{ t('查看文档', 'View docs') }} →</span>
        </router-link>

        <div class="integ-card coming">
          <span class="integ-name">+ {{ t('更多即将支持', 'More coming soon') }}</span>
          <p>{{ t('如有集成需求，欢迎提交 Issue', 'Submit an issue to request an integration') }}</p>
        </div>
      </div>
    </div>
  </main>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useLocale } from '../../stores/locale'

const { locale, t } = useLocale()

const copyLabel = ref('Copy')
function copyText(text: string) {
  navigator.clipboard.writeText(text)
  copyLabel.value = 'Copied!'
  setTimeout(() => { copyLabel.value = 'Copy' }, 1500)
}

const quickstartSnippet = `# 1. Install & login
pip install dbay-cli
dbay login

# 2. Register MCP server (Claude Code)
claude mcp add --scope user dbay -- uvx dbay-mcp

# 3. Install memory skill (Claude Code, recommended)
# In Claude Code, run:
#   /plugin marketplace add jackylk/dbay-plugins
#   /plugin install memory`

const integrations = [
  {
    id: 'openclaw', anchor: 'openclaw', name: 'OpenClaw', featured: true,
    href: '/integrations/openclaw',
    desc: 'OpenClaw AI assistant with native DBay memory integration. Auto-recall, auto-capture, and auto-digest on every conversation.',
    descZh: '龙虾 AI 助手，原生 DBay 记忆集成。每次对话自动回忆、自动捕获、自动反思。',
  },
  {
    id: 'claude-code', anchor: 'claude-code', name: 'Claude Code', featured: true,
    href: '/integrations#claude-code',
    desc: 'Connect DBay memory and knowledge base to Claude Code via MCP for persistent project context.',
    descZh: '通过 MCP 将 DBay 记忆库与知识库接入 Claude Code，实现持久化项目上下文。',
  },
  {
    id: 'claude-desktop', anchor: 'claude-desktop', name: 'Claude Desktop', featured: false,
    href: '/integrations#claude-desktop',
    desc: 'Persistent memory for Claude Desktop conversations across sessions.',
    descZh: '让 Claude Desktop 的对话记忆跨会话持久化。',
  },
  {
    id: 'cursor', anchor: 'cursor', name: 'Cursor', featured: false,
    href: '/integrations#cursor',
    desc: 'Codebase knowledge base retrieval and project memory in Cursor.',
    descZh: '在 Cursor 中使用代码库知识库检索和项目记忆。',
  },
  {
    id: 'gemini-cli', anchor: 'gemini-cli', name: 'Gemini CLI', featured: false,
    href: '/integrations#gemini-cli',
    desc: 'Long-term memory for Gemini CLI — your AI assistant remembers between sessions.',
    descZh: '为 Gemini CLI 提供长期记忆能力，跨会话记住用户偏好和上下文。',
  },
  {
    id: 'chatgpt', anchor: 'chatgpt', name: 'ChatGPT', featured: false,
    href: '/integrations#chatgpt',
    desc: 'Cross-session user memory sync for ChatGPT Plus and above.',
    descZh: '为 ChatGPT Plus 及以上用户提供跨会话记忆同步。',
  },
]
</script>

<style scoped>
.integrations-page { min-height: 100vh; background: var(--pub-bg); color: var(--pub-text); }
.integrations-inner { max-width: 900px; margin: 0 auto; padding: 48px 24px; }
h1 { font-size: 32px; font-weight: 700; margin-bottom: 8px; }
.integ-subtitle { color: var(--pub-text-2); font-size: 15px; margin-bottom: 32px; max-width: 600px; }
.mcp-quickstart {
  background: var(--pub-surface); border: 1px solid var(--pub-border);
  border-radius: 10px; padding: 20px; margin-bottom: 40px;
}
.mcp-quickstart h3 { font-size: 15px; font-weight: 600; margin: 0 0 12px; }
.code-wrapper { position: relative; }
.copy-btn {
  position: absolute; top: 8px; right: 8px;
  background: var(--pub-surface); border: 1px solid var(--pub-border); border-radius: 4px;
  padding: 2px 8px; font-size: 11px; color: var(--pub-text-4); cursor: pointer;
  transition: color 0.15s, border-color 0.15s;
}
.copy-btn:hover { color: var(--pub-code); border-color: var(--pub-code); }
.tip { font-size: 12px; color: var(--pub-text-4); margin-top: 8px; }
.code-block {
  background: var(--pub-code-bg); border: 1px solid var(--pub-border); border-radius: 6px;
  padding: 14px; font-size: 12px; color: var(--pub-code);
  overflow-x: auto; margin: 0; font-family: monospace; white-space: pre;
}
.integ-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.integ-card {
  background: var(--pub-surface); border: 1px solid var(--pub-border); border-radius: 10px;
  padding: 18px; text-decoration: none; display: flex;
  flex-direction: column; gap: 8px; transition: border-color 0.15s;
}
.integ-card:hover { border-color: var(--pub-code); }
.integ-card.featured { border-color: var(--pub-accent-border); background: var(--pub-accent-bg); }
.integ-card.coming { border-style: dashed; border-color: var(--pub-border); cursor: default; }
.integ-card.coming:hover { border-color: var(--pub-border); }
.integ-card-hd { display: flex; align-items: center; gap: 8px; }
.integ-name { font-size: 15px; font-weight: 600; color: var(--pub-text); }
.badge-featured {
  font-size: 10px; background: #7c3aed15; color: var(--pub-code);
  padding: 1px 6px; border-radius: 4px;
}
.integ-card p { font-size: 12px; color: var(--pub-text-2); margin: 0; line-height: 1.5; flex: 1; }
.integ-link { font-size: 12px; color: var(--pub-code); margin-top: auto; }
@media (max-width: 768px) {
  .integ-grid { grid-template-columns: 1fr; }
}
</style>
