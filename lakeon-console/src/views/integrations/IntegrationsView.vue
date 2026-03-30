<template>
  <main class="integrations-page">

    <!-- Hero -->
    <section class="hero">
      <h1>{{ t('连接你的 AI 工具', 'Connect Your AI Tools') }}</h1>
      <p class="hero-sub">
        {{ t('通过 MCP 协议，让任何 AI 应用接入 DBay 的记忆、知识和数据能力', 'Connect any AI application to DBay memory, knowledge, and data capabilities via MCP protocol') }}
      </p>
    </section>

    <!-- Quickstart -->
    <section class="quickstart-section">
      <div class="quickstart-inner">
        <h2 class="section-title">{{ t('5 分钟快速接入', '5-Minute Quickstart') }}</h2>
        <div class="code-wrapper">
          <pre class="code-block"><code>{{ quickstartSnippet }}</code></pre>
          <button class="copy-btn" @click="copyText(quickstartSnippet)">{{ copyLabel }}</button>
        </div>
        <p class="quickstart-note">
          {{ t('API Key 存放在 ~/.dbay/config.json，不进入 Claude 配置文件或代码仓库。', 'API Key lives in ~/.dbay/config.json — never enters Claude config or your repo.') }}
        </p>
      </div>
    </section>

    <!-- Tool cards -->
    <section class="tools-section">
      <div class="tools-inner">
        <div class="tools-grid">
          <component
            :is="tool.href.startsWith('/integrations/') ? 'router-link' : 'a'"
            v-for="tool in integrations"
            :key="tool.id"
            :to="tool.href.startsWith('/integrations/') ? tool.href : undefined"
            :href="tool.href.startsWith('/integrations/') ? undefined : tool.href"
            class="tool-card"
            :class="`tool-card--${tool.type}`"
          >
            <div class="tool-card-hd">
              <h3 class="tool-name">{{ tool.name }}</h3>
              <span v-if="tool.featured" class="badge-featured">{{ t('Featured', 'Featured') }}</span>
            </div>
            <p class="tool-desc">{{ locale === 'zh' ? tool.descZh : tool.desc }}</p>
            <span class="tool-link">{{ t('查看指南 →', 'View guide →') }}</span>
          </component>
        </div>
      </div>
    </section>

    <!-- Protocol reference -->
    <section class="protocols-section">
      <div class="protocols-inner">
        <h2 class="section-title">{{ t('四种集成协议', 'Four Integration Protocols') }}</h2>
        <div class="protocols-grid">
          <div v-for="proto in protocols" :key="proto.id" class="proto-card">
            <div class="proto-name">{{ proto.name }}</div>
            <code class="proto-code">{{ proto.snippet }}</code>
          </div>
        </div>
      </div>
    </section>

    <!-- Bottom CTA -->
    <section class="page-bottom-cta">
      <h2>{{ t('立即接入，免费开始', 'Connect Now, Start Free') }}</h2>
      <p>{{ t('无需信用卡 · 兼容所有 MCP 客户端 · 5 分钟完成接入', 'No credit card · Works with all MCP clients · Ready in 5 minutes') }}</p>
      <router-link to="/" class="cta-primary">{{ t('立即试用 →', 'Try Now →') }}</router-link>
    </section>

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
claude mcp add --scope user dbay -- uvx dbay-mcp`

const integrations = [
  {
    id: 'openclaw',
    name: 'OpenClaw',
    featured: true,
    type: 'native',
    href: '/integrations/openclaw',
    desc: 'OpenClaw AI assistant with native DBay memory integration.',
    descZh: '龙虾 AI 助手，原生 DBay 记忆集成。每次对话自动回忆、自动捕获、自动反思。',
  },
  {
    id: 'claude-code',
    name: 'Claude Code',
    featured: true,
    type: 'mcp',
    href: '/integrations#claude-code',
    desc: 'Connect DBay memory and knowledge base to Claude Code via MCP.',
    descZh: '通过 MCP 将 DBay 记忆库与知识库接入 Claude Code，实现持久化项目上下文。',
  },
  {
    id: 'claude-desktop',
    name: 'Claude Desktop',
    featured: false,
    type: 'mcp',
    href: '/integrations#claude-desktop',
    desc: 'Persistent memory for Claude Desktop conversations across sessions.',
    descZh: '让 Claude Desktop 的对话记忆跨会话持久化。',
  },
  {
    id: 'cursor',
    name: 'Cursor',
    featured: false,
    type: 'mcp',
    href: '/integrations#cursor',
    desc: 'Codebase knowledge base retrieval and project memory in Cursor.',
    descZh: '在 Cursor 中使用代码库知识库检索和项目记忆。',
  },
  {
    id: 'gemini-cli',
    name: 'Gemini CLI',
    featured: false,
    type: 'mcp',
    href: '/integrations#gemini-cli',
    desc: 'Long-term memory for Gemini CLI.',
    descZh: '为 Gemini CLI 提供长期记忆能力，跨会话记住用户偏好和上下文。',
  },
  {
    id: 'chatgpt',
    name: 'ChatGPT',
    featured: false,
    type: 'api',
    href: '/integrations#chatgpt',
    desc: 'Cross-session user memory sync for ChatGPT Plus.',
    descZh: '为 ChatGPT Plus 及以上用户提供跨会话记忆同步。',
  },
]

const protocols = [
  {
    id: 'mcp',
    name: 'MCP',
    snippet: 'claude mcp add dbay -- uvx dbay-mcp',
  },
  {
    id: 'skill',
    name: 'Skill',
    snippet: '.claude/skills/dbay-memory',
  },
  {
    id: 'postgres',
    name: 'PostgreSQL',
    snippet: 'psql postgres://...@dbay.cloud/mydb',
  },
  {
    id: 'rest',
    name: 'REST',
    snippet: 'curl https://api.dbay.cloud/api/v1/recall',
  },
]
</script>

<style scoped>
.integrations-page {
  min-height: 100vh;
  background: var(--pub-bg);
  color: var(--pub-text);
}

/* Hero */
.hero {
  background: var(--pub-surface);
  text-align: center;
  padding: 72px 24px 64px;
  border-bottom: 1px solid var(--pub-border);
}
.hero h1 {
  font-size: 40px;
  font-weight: 700;
  letter-spacing: -0.02em;
  margin: 0 0 16px;
  color: var(--pub-text);
}
.hero-sub {
  font-size: 15px;
  color: var(--pub-text-2);
  max-width: 560px;
  margin: 0 auto;
  line-height: 1.7;
}

/* Section title */
.section-title {
  font-size: 20px;
  font-weight: 700;
  margin: 0 0 28px;
  color: var(--pub-text);
  text-align: center;
  letter-spacing: -0.01em;
}

/* Quickstart */
.quickstart-section {
  background: var(--pub-bg-alt);
  padding: 64px 24px;
  border-bottom: 1px solid var(--pub-border);
}
.quickstart-inner {
  max-width: 640px;
  margin: 0 auto;
}
.code-wrapper {
  position: relative;
}
.code-block {
  background: #1e293b;
  border-radius: 12px;
  padding: 22px 24px;
  font-size: 13px;
  color: #a5f3fc;
  overflow-x: auto;
  margin: 0;
  font-family: monospace;
  white-space: pre;
  line-height: 1.7;
}
.copy-btn {
  position: absolute;
  top: 12px;
  right: 12px;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.15);
  border-radius: 20px;
  padding: 4px 14px;
  font-size: 11px;
  color: #94a3b8;
  cursor: pointer;
  transition: background 0.25s ease, color 0.25s ease;
}
.copy-btn:hover {
  background: rgba(255, 255, 255, 0.15);
  color: #a5f3fc;
}
.quickstart-note {
  font-size: 12px;
  color: var(--pub-text-3);
  margin: 14px 0 0;
  line-height: 1.5;
}

/* Tool cards */
.tools-section {
  padding: 64px 24px;
}
.tools-inner {
  max-width: 900px;
  margin: 0 auto;
}
.tools-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 18px;
}
.tool-card {
  background: var(--pub-surface);
  border: 1px solid var(--pub-border);
  border-radius: 14px;
  padding: 24px;
  text-decoration: none;
  display: flex;
  flex-direction: column;
  gap: 12px;
  transition: transform 0.25s ease, box-shadow 0.25s ease, border-color 0.25s ease;
  cursor: pointer;
  border-left: 3px solid transparent;
}
.tool-card--native {
  border-left-color: var(--pub-primary, #0073e6);
}
.tool-card--mcp {
  border-left-color: #10b981;
}
.tool-card--api {
  border-left-color: #f59e0b;
}
.tool-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.08);
}
.tool-card-hd {
  display: flex;
  align-items: center;
  gap: 10px;
}
.tool-name {
  font-size: 16px;
  font-weight: 600;
  color: var(--pub-text);
  margin: 0;
}
.badge-featured {
  font-size: 10px;
  background: color-mix(in srgb, var(--pub-primary, #0073e6) 12%, transparent);
  color: var(--pub-primary, #0073e6);
  padding: 2px 8px;
  border-radius: 10px;
  font-weight: 600;
  letter-spacing: 0.02em;
}
.tool-desc {
  font-size: 13px;
  color: var(--pub-text-2);
  margin: 0;
  line-height: 1.6;
  flex: 1;
}
.tool-link {
  font-size: 13px;
  color: var(--pub-primary, #0073e6);
  font-weight: 500;
  margin-top: auto;
  transition: opacity 0.25s ease;
}
.tool-card:hover .tool-link {
  opacity: 0.8;
}

/* Protocol reference */
.protocols-section {
  background: var(--pub-bg-alt);
  padding: 64px 24px;
  border-top: 1px solid var(--pub-border);
}
.protocols-inner {
  max-width: 900px;
  margin: 0 auto;
}
.protocols-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 18px;
}
.proto-card {
  background: var(--pub-surface);
  border: 1px solid var(--pub-border);
  border-radius: 12px;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  transition: transform 0.25s ease, box-shadow 0.25s ease;
}
.proto-card:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.06);
}
.proto-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--pub-text);
}
.proto-code {
  font-size: 12px;
  color: var(--pub-text-2);
  font-family: monospace;
  word-break: break-all;
  line-height: 1.6;
  background: var(--pub-bg-alt);
  padding: 8px 10px;
  border-radius: 8px;
}

/* Bottom CTA */
.page-bottom-cta {
  background: linear-gradient(135deg, #e8f2ff, #f0f5ff);
  padding: 64px 24px;
  text-align: center;
}
.page-bottom-cta h2 {
  font-size: 24px;
  font-weight: 700;
  color: var(--pub-text);
  margin: 0 0 10px;
  letter-spacing: -0.01em;
}
.page-bottom-cta p {
  font-size: 14px;
  color: var(--pub-text-2);
  margin: 0 0 28px;
  line-height: 1.6;
}
.cta-primary {
  display: inline-block;
  background: var(--pub-primary, #0073e6);
  color: #fff;
  padding: 12px 40px;
  border-radius: 100px;
  font-size: 16px;
  font-weight: 600;
  text-decoration: none;
  transition: opacity 0.25s ease, transform 0.25s ease;
}
.cta-primary:hover {
  opacity: 0.92;
  transform: translateY(-1px);
}

/* Responsive */
@media (max-width: 768px) {
  .hero { padding: 48px 20px 40px; }
  .hero h1 { font-size: 30px; }
  .quickstart-section { padding: 40px 20px; }
  .tools-section { padding: 40px 20px; }
  .tools-grid { grid-template-columns: 1fr; }
  .protocols-section { padding: 40px 20px; }
  .protocols-grid { grid-template-columns: 1fr 1fr; }
  .page-bottom-cta { padding: 40px 20px; }
}

@media (max-width: 480px) {
  .protocols-grid { grid-template-columns: 1fr; }
}
</style>
