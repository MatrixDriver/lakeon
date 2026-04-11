<template>
  <div class="ppage">
    <!-- 01 · Manifesto -->
    <section class="ppage-section ppage-section-white">
      <div class="ppage-inner">
        <router-link to="/" class="ppage-back">← {{ t('回到首页', 'Back to home') }}</router-link>
        <div class="ppage-eyebrow">{{ t('集成指引', 'Integration guide') }}</div>

        <h1 class="ppage-manifesto-title">
          <span class="in-title-a">{{ t('三步，', 'Three commands.') }}</span>
          <span class="in-title-b">{{ t('你的 Agent', 'Your agent') }}</span>
          <span class="in-title-c">{{ t('就接上了 DBay。', 'is plugged into DBay.') }}</span>
        </h1>

        <p class="ppage-manifesto-lede">
          {{ t(
            '装上 dbay-cli，用一行命令把 MCP 注册到你正在用的 Agent，创建一个记忆库 — 从此 Claude Code / Cursor / Cline / Gemini CLI / Claude Desktop 都可以读写同一套 DBay 记忆和知识。',
            'Install dbay-cli, register the MCP with the agent you already use, create a memory base — and from that point on, Claude Code, Cursor, Cline, Gemini CLI, and Claude Desktop all read and write the same DBay memory and knowledge.'
          ) }}
        </p>
      </div>
    </section>

    <!-- 02 · Three-step quick start -->
    <section class="ppage-section">
      <div class="ppage-inner">
        <h2 class="ppage-section-title">{{ t('三步接入 · 大约 3 分钟', 'Three steps · about three minutes') }}</h2>

        <ol class="in-steps">
          <li v-for="(step, i) in quickstartSteps" :key="i" class="in-step">
            <div class="in-step-idx">{{ i + 1 }}</div>
            <div class="in-step-body">
              <h3 class="in-step-title">{{ locale === 'zh' ? step.titleZh : step.title }}</h3>

              <div class="in-code-wrap" v-if="step.code">
                <pre class="in-code-block"><code>{{ step.code }}</code></pre>
                <button class="in-copy-btn" @click="copyText(step.code)">
                  {{ copiedKey === step.code ? t('已复制', 'Copied') : t('复制', 'Copy') }}
                </button>
              </div>

              <p class="in-step-note" v-if="step.noteZh">
                {{ locale === 'zh' ? step.noteZh : step.note }}
              </p>
            </div>
          </li>
        </ol>

        <div class="in-quickstart-result">
          <span class="in-result-label">{{ t('完成之后，在 Claude Code 里直接说：', 'Once done, just say this in Claude Code:') }}</span>
          <ul class="in-result-examples">
            <li>
              <em>{{ t('"记住我偏好 TypeScript 严格模式"', '"Remember I prefer strict TypeScript"') }}</em>
              <span class="in-result-arrow">→</span>
              <span class="in-result-side">{{ t('存进记忆层', 'into memory') }}</span>
            </li>
            <li>
              <em>{{ t('"我之前提过什么开发偏好？"', '"What preferences did I mention before?"') }}</em>
              <span class="in-result-arrow">→</span>
              <span class="in-result-side">{{ t('从记忆层召回', 'recalled from memory') }}</span>
            </li>
          </ul>
        </div>

        <p class="in-tip">
          {{ t(
            '如果提示 "MCP failed to connect"，先确认 Python 已装（python --version 需要 3.11+）。',
            'If you see "MCP failed to connect", check Python is installed (python --version needs 3.11+).'
          ) }}
        </p>
      </div>
    </section>

    <!-- 03 · Other tools -->
    <section class="ppage-section ppage-section-white">
      <div class="ppage-inner">
        <h2 class="ppage-section-title">{{ t('接入其他 Agent 工具', 'Plug into other agent tools') }}</h2>

        <p class="ppage-section-prose in-tools-intro">
          {{ t(
            '每一个主流 Agent 都有自己的 MCP 注册方式。第一步都是一样的：',
            'Each major agent has its own way to register an MCP. Step one is always the same:'
          ) }}
          <code class="ppage-code-inline">pip install dbay-cli &amp;&amp; dbay login</code>
        </p>

        <div class="in-tools">
          <nav class="in-tools-nav">
            <button
              v-for="tool in tools"
              :key="tool.id"
              class="in-tool-tab"
              :class="{ 'is-active': activeTool === tool.id }"
              @click="activeTool = tool.id"
            >
              <span class="in-tool-name">{{ tool.name }}</span>
              <span class="in-tool-desc">{{ locale === 'zh' ? tool.shortZh : tool.short }}</span>
            </button>
          </nav>
          <div class="in-tools-body">
            <div class="in-code-wrap">
              <pre class="in-code-block"><code>{{ activeSetup }}</code></pre>
              <button class="in-copy-btn" @click="copyText(activeSetup)">
                {{ copiedKey === activeSetup ? t('已复制', 'Copied') : t('复制', 'Copy') }}
              </button>
            </div>
          </div>
        </div>

        <p class="in-tools-more">
          {{ t(
            '更多 Agent 即将支持。有要加的工具？提交',
            'More agents coming. Want a specific tool supported? File an'
          ) }}
          <a href="https://github.com/MatrixDriver/lakeon/issues" target="_blank" rel="noopener">Issue</a>。
        </p>
      </div>
    </section>

    <!-- 04 · OpenClaw spotlight -->
    <section class="ppage-section">
      <div class="ppage-inner">
        <h2 class="ppage-section-title">{{ t('原生集成：OpenClaw', 'Native integration: OpenClaw') }}</h2>

        <router-link to="/integrations/openclaw" class="in-spotlight">
          <div class="in-spotlight-body">
            <div class="in-spotlight-name">OpenClaw</div>
            <p class="in-spotlight-desc">
              {{ t(
                '不需要装 MCP —— OpenClaw 把 DBay 记忆直接编进了内核。每次对话自动回忆、自动捕获、自动反思。适合"想省掉配置步骤"的人。',
                "No MCP to install — OpenClaw builds DBay memory into the core. Auto-recall, auto-capture, and auto-digest every conversation. For people who don't want to configure anything."
              ) }}
            </p>
          </div>
          <span class="in-spotlight-arrow">{{ t('查看详情', 'See details') }} →</span>
        </router-link>
      </div>
    </section>

    <!-- Footer -->
    <footer class="ppage-footer">
      <div class="ppage-footer-inner">
        <h3 class="ppage-footer-title">{{ t('继续了解', 'Keep reading') }}</h3>
        <div class="ppage-footer-grid">
          <router-link to="/product/memory" class="ppage-footer-card">
            <div class="ppage-footer-card-name">Memory</div>
            <div class="ppage-footer-card-tag">{{ t('长期记忆 · 反思 · 跨 Agent 共享', 'Long-term memory · reflection · cross-agent') }}</div>
          </router-link>
          <router-link to="/product/knowledge" class="ppage-footer-card">
            <div class="ppage-footer-card-name">Knowledge</div>
            <div class="ppage-footer-card-tag">{{ t('Agent 自己维护的活 wiki', 'A living wiki the agent maintains') }}</div>
          </router-link>
          <router-link to="/" class="ppage-footer-card">
            <div class="ppage-footer-card-name">{{ t('回到首页', 'Back to home') }}</div>
            <div class="ppage-footer-card-tag">{{ t('Agent 工作态和学习态的数据基础设施', 'Substrate for agent runtime and learning') }}</div>
          </router-link>
        </div>
      </div>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useLocale } from '../../stores/locale'

const { locale, t } = useLocale()

const copiedKey = ref('')
function copyText(text: string) {
  navigator.clipboard.writeText(text)
  copiedKey.value = text
  setTimeout(() => { copiedKey.value = '' }, 1500)
}

const quickstartSteps = [
  {
    titleZh: '安装 dbay-cli 并登录',
    title: 'Install dbay-cli and sign in',
    code: 'pip install dbay-cli\ndbay login',
    noteZh: 'API Key 存放在 ~/.dbay/config.json — 不进入 Claude 配置文件，也不进入你的 repo。',
    note: 'API Key lives in ~/.dbay/config.json — never in your Claude config, never in your repo.',
  },
  {
    titleZh: '把 MCP 注册到 Claude Code',
    title: 'Register the MCP with Claude Code',
    code: 'claude mcp add --scope user dbay -- python -m dbay_mcp',
  },
  {
    titleZh: '创建一个记忆库',
    title: 'Create a memory base',
    code: 'dbay memory create                # 普通记忆库\ndbay memory create --encrypted    # 本地加密（e2e，会要你设密码）',
    noteZh: 'Embedding 可选：1) DBay 默认  2) 外部 API  3) 本地模型（即将支持）。',
    note: 'Embedding options: 1) DBay default  2) External API  3) Local model (coming soon).',
  },
]

const tools = [
  {
    id: 'claude-code',
    name: 'Claude Code',
    shortZh: 'MCP 命令行注册',
    short: 'CLI registration',
    setup: `# 1. Install & sign in
pip install dbay-cli
dbay login

# 2. Register the MCP with Claude Code
claude mcp add --scope user dbay -- python -m dbay_mcp

# 3. Create a memory base
dbay memory create                # plain memory
dbay memory create --encrypted    # encrypted (e2e, prompts for password)`,
  },
  {
    id: 'claude-desktop',
    name: 'Claude Desktop',
    shortZh: 'JSON 配置文件',
    short: 'JSON config',
    setup: `# 1. Install & sign in
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
dbay memory create
dbay memory create --encrypted`,
  },
  {
    id: 'cursor',
    name: 'Cursor',
    shortZh: '项目级 MCP 配置',
    short: 'Project MCP config',
    setup: `# 1. Install & sign in
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
dbay memory create
dbay memory create --encrypted`,
  },
  {
    id: 'cline',
    name: 'Cline',
    shortZh: 'VS Code MCP 设置',
    short: 'VS Code MCP settings',
    setup: `# 1. Install & sign in
pip install dbay-cli
dbay login

# 2. In VS Code, open Cline settings and add the MCP:
{
  "mcpServers": {
    "dbay": {
      "command": "python",
      "args": ["-m", "dbay_mcp"]
    }
  }
}

# 3. Create a memory base
dbay memory create
dbay memory create --encrypted`,
  },
  {
    id: 'gemini-cli',
    name: 'Gemini CLI',
    shortZh: '全局配置文件',
    short: 'Global settings',
    setup: `# 1. Install & sign in
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
dbay memory create
dbay memory create --encrypted`,
  },
  {
    id: 'chatgpt',
    name: 'ChatGPT',
    shortZh: 'REST API 集成',
    short: 'REST API',
    setup: `# ChatGPT integrates via REST API (requires a middleware layer).
# See the API docs: https://dbay.cloud/docs/rest-api`,
  },
]

const activeTool = ref('claude-code')
const activeSetup = computed(() => tools.find(t => t.id === activeTool.value)?.setup ?? '')
</script>

<style scoped>
/* Title stagger */
.in-title-a,
.in-title-b,
.in-title-c {
  display: block;
}
.in-title-a { color: var(--c-text-3); }
.in-title-b { color: var(--c-primary); }
.in-title-c { color: var(--c-accent-text); }

/* Three-step quick start */
.in-steps {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: clamp(32px, 4vw, 56px);
}

.in-step {
  display: grid;
  grid-template-columns: 56px 1fr;
  gap: var(--space-xl);
  align-items: start;
}

.in-step-idx {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: 42px;
  line-height: 1;
  color: var(--c-accent);
  font-variant-numeric: tabular-nums;
}

.in-step-body {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.in-step-title {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: clamp(20px, 2.2vw, 26px);
  color: var(--c-text);
  margin: 0;
  letter-spacing: -0.005em;
}

.in-step-note {
  font-family: var(--font-sans);
  font-size: 13px;
  line-height: 1.6;
  color: var(--c-text-3);
  margin: 0;
  max-width: 64ch;
  font-style: italic;
}

.in-code-wrap {
  position: relative;
  max-width: 720px;
}

.in-code-block {
  background: var(--c-bg-alt);
  border: 1px solid var(--c-border);
  border-radius: 6px;
  padding: 14px 18px;
  margin: 0;
  font-family: var(--font-mono);
  font-size: 13px;
  line-height: 1.6;
  color: var(--c-text);
  overflow-x: auto;
  white-space: pre;
}

.in-code-block code {
  font-family: inherit;
  background: none;
  padding: 0;
}

.in-copy-btn {
  position: absolute;
  top: 10px;
  right: 10px;
  background: #fff;
  border: 1px solid var(--c-border);
  border-radius: 3px;
  padding: 3px 10px;
  font-family: var(--font-sans);
  font-size: 11px;
  color: var(--c-text-2);
  cursor: pointer;
  transition: border-color 160ms ease-out, color 160ms ease-out;
}

.in-copy-btn:hover {
  border-color: var(--c-accent);
  color: var(--c-accent-text);
}

/* Quickstart result block */
.in-quickstart-result {
  margin-top: clamp(40px, 5vw, 64px);
  padding: var(--space-xl) var(--space-2xl);
  background: color-mix(in oklch, var(--c-accent) 5%, #fff);
  border: 1px solid color-mix(in oklch, var(--c-accent) 20%, var(--c-border-light));
  border-radius: 6px;
  max-width: 720px;
}

.in-result-label {
  display: block;
  font-family: var(--font-sans);
  font-size: 12px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--c-accent-text);
  margin-bottom: var(--space-md);
}

.in-result-examples {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.in-result-examples li {
  display: flex;
  align-items: baseline;
  gap: var(--space-sm);
  font-family: var(--font-sans);
  font-size: 14px;
  line-height: 1.6;
  color: var(--c-text);
  flex-wrap: wrap;
}

.in-result-examples em {
  font-family: var(--font-display);
  font-style: italic;
  color: var(--c-text);
}

.in-result-arrow {
  color: var(--c-accent);
  font-weight: 500;
}

.in-result-side {
  color: var(--c-text-3);
  font-size: 13px;
}

.in-tip {
  margin-top: var(--space-xl);
  font-family: var(--font-sans);
  font-size: 12px;
  color: var(--c-text-3);
  font-style: italic;
  max-width: 64ch;
}

/* Tools tabs */
.in-tools-intro {
  margin-bottom: var(--space-2xl);
}

.in-tools {
  display: grid;
  grid-template-columns: minmax(200px, 260px) 1fr;
  gap: var(--space-2xl);
  align-items: start;
}

.in-tools-nav {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.in-tool-tab {
  text-align: left;
  background: none;
  border: 1px solid var(--c-border-light);
  border-radius: 4px;
  padding: var(--space-md);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 2px;
  transition: border-color 160ms ease-out, background 160ms ease-out;
}

.in-tool-tab:hover {
  border-color: var(--c-border);
  background: var(--c-bg-alt);
}

.in-tool-tab.is-active {
  background: color-mix(in oklch, var(--c-accent) 8%, #fff);
  border-color: color-mix(in oklch, var(--c-accent) 35%, var(--c-border));
}

.in-tool-name {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: 15px;
  color: var(--c-text);
}

.in-tool-desc {
  font-family: var(--font-sans);
  font-size: 11px;
  color: var(--c-text-3);
}

.in-tools-body {
  min-width: 0;
}

.in-tools-body .in-code-block {
  max-height: 480px;
  overflow-y: auto;
}

.in-tools-more {
  margin-top: var(--space-xl);
  font-family: var(--font-sans);
  font-size: 13px;
  color: var(--c-text-3);
}

.in-tools-more a {
  color: var(--c-accent-text);
  text-decoration: none;
  border-bottom: 1px solid currentColor;
}

.in-tools-more a:hover {
  color: var(--c-accent-hover);
}

/* OpenClaw spotlight */
.in-spotlight {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-xl);
  padding: var(--space-2xl);
  background: #fff;
  border: 1px solid var(--c-border);
  border-radius: 8px;
  text-decoration: none;
  transition: border-color 160ms ease-out, transform 160ms ease-out;
}

.in-spotlight:hover {
  border-color: var(--c-accent);
}

.in-spotlight-body {
  flex: 1;
}

.in-spotlight-name {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: clamp(22px, 2.6vw, 32px);
  color: var(--c-primary);
  letter-spacing: -0.005em;
  margin-bottom: var(--space-sm);
}

.in-spotlight-desc {
  font-family: var(--font-sans);
  font-size: 14px;
  line-height: 1.65;
  color: var(--c-text-2);
  margin: 0;
  max-width: 68ch;
}

.in-spotlight-arrow {
  flex-shrink: 0;
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 500;
  color: var(--c-accent-text);
  white-space: nowrap;
  transition: transform 160ms ease-out;
}

.in-spotlight:hover .in-spotlight-arrow {
  transform: translateX(4px);
}

@media (max-width: 900px) {
  .in-step {
    grid-template-columns: 1fr;
    gap: var(--space-md);
  }
  .in-step-idx {
    font-size: 32px;
  }
  .in-tools {
    grid-template-columns: 1fr;
  }
  .in-spotlight {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
