<template>
  <div class="docs-home">
    <h1>{{ t('DBay 记忆库文档', 'DBay Memory Store Docs') }}</h1>
    <p class="subtitle">{{ t('快速接入 API、SDK 和 MCP，为你的 AI 应用赋予持久记忆', 'Get started with the API, SDK, and MCP to add persistent memory to your AI app') }}</p>

    <section class="qs-section">
      <h2>{{ t('5 步快速开始', '5-Step Quick Start') }}</h2>

      <div v-for="(step, i) in steps" :key="i" class="step">
        <div class="step-num">{{ i + 1 }}</div>
        <div>
          <strong>{{ step.title }}</strong>
          <pre class="code-block"><code>{{ step.code }}</code></pre>
        </div>
      </div>
    </section>

    <section class="link-cards">
      <router-link to="/docs/rest-api" class="link-card">
        <h3>REST API</h3>
        <p>{{ t('完整 HTTP API 参考，含所有端点、参数和示例', 'Full HTTP API reference with all endpoints, parameters and examples') }}</p>
        <span class="more">{{ t('查看文档', 'View docs') }} →</span>
      </router-link>
      <router-link to="/docs/python-sdk" class="link-card">
        <h3>Python SDK</h3>
        <p>{{ t('异步 Python 客户端，支持 OpenAI / Anthropic 嵌入模型', 'Async Python client supporting OpenAI / Anthropic embedding models') }}</p>
        <span class="more">{{ t('查看文档', 'View docs') }} →</span>
      </router-link>
      <router-link to="/docs/mcp" class="link-card">
        <h3>MCP {{ t('工具', 'Tools') }}</h3>
        <p>{{ t('通过 MCP 将记忆库接入 Claude Code、Cursor 等 AI 工具', 'Connect memory store to Claude Code, Cursor and other AI tools via MCP') }}</p>
        <span class="more">{{ t('查看文档', 'View docs') }} →</span>
      </router-link>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useLocale } from '../../stores/locale'

const { t } = useLocale()

const steps = computed(() => [
  {
    title: t('安装并登录', 'Install & Login'),
    code: 'pip install dbay-cli\ndbay login',
  },
  {
    title: t('注册 MCP 服务（Claude Code）', 'Register MCP Server (Claude Code)'),
    code: 'claude mcp add --scope user dbay -- uvx dbay-mcp',
  },
  {
    title: t('安装记忆 Skill（推荐）', 'Install Memory Skill (Recommended)'),
    code: `# Claude Code 用户：安装 dbay skill 插件
# /plugin marketplace add jackylk/dbay-plugins
# /plugin install memory
# 安装后，说"记住"时 CC 自动调用 DBay 记忆库

# 其他 AI 工具：注入记忆提示
dbay setup gemini    # Gemini CLI
dbay setup cursor    # Cursor
dbay setup windsurf  # Windsurf`,
  },
  {
    title: t('存入记忆', 'Store a Memory'),
    code: t(
      '# 在 AI 工具中直接说：\n# "记住我的 PyPI token 是 pypi-xxx"\n# AI 会自动调用 DBay 记忆库存储',
      '# Just tell your AI tool:\n# "Remember my PyPI token is pypi-xxx"\n# It will automatically call DBay memory to store it'
    ),
  },
  {
    title: t('检索记忆', 'Recall Memories'),
    code: t(
      '# 在 AI 工具中直接问：\n# "我之前的 PyPI token 是什么？"\n# AI 会自动从 DBay 记忆库检索',
      '# Just ask your AI tool:\n# "What was my PyPI token?"\n# It will automatically recall from DBay memory'
    ),
  },
])
</script>

<style scoped>
.docs-home h1 { font-size: 28px; font-weight: 700; margin: 0 0 8px; }
.subtitle { color: var(--pub-text-2); font-size: 15px; margin-bottom: 40px; line-height: 1.6; }
.qs-section { margin-bottom: 48px; }
.qs-section h2 { font-size: 18px; font-weight: 600; margin-bottom: 24px; }
.step { display: flex; gap: 16px; align-items: flex-start; margin-bottom: 20px; }
.step-num {
  width: 24px; height: 24px; border-radius: 50%;
  background: #7c3aed; color: #fff; font-size: 12px; font-weight: 600;
  display: flex; align-items: center; justify-content: center; flex-shrink: 0; margin-top: 2px;
}
.step strong { font-size: 14px; font-weight: 600; display: block; margin-bottom: 8px; }
.code-block {
  background: var(--pub-code-bg); border: 1px solid var(--pub-border); border-radius: 6px;
  padding: 12px 14px; font-size: 12px; color: var(--pub-code);
  overflow-x: auto; margin: 0; font-family: monospace; white-space: pre;
}
.link-cards { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.link-card {
  background: var(--pub-surface); border: 1px solid var(--pub-border); border-radius: 8px;
  padding: 16px; text-decoration: none; display: flex;
  flex-direction: column; gap: 6px; transition: border-color 0.15s;
}
.link-card:hover { border-color: var(--pub-code); }
.link-card h3 { font-size: 14px; font-weight: 600; color: var(--pub-text); margin: 0; }
.link-card p { font-size: 12px; color: var(--pub-text-2); margin: 0; flex: 1; line-height: 1.5; }
.more { font-size: 12px; color: var(--pub-code); }
@media (max-width: 768px) {
  .link-cards { grid-template-columns: 1fr; }
}
</style>
