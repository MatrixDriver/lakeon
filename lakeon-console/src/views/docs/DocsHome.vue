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
    title: t('安装 SDK', 'Install SDK'),
    code: 'pip install dbay-memory',
  },
  {
    title: t('初始化客户端', 'Initialize Client'),
    code: `from dbay_memory import DBayMemory

mem = DBayMemory(api_key="dbay_sk_your_key_here")`,
  },
  {
    title: t('写入记忆', 'Ingest Memory'),
    code: `await mem.ingest(
    user_id="alice",
    content="User prefers dark mode and uses TypeScript daily"
)`,
  },
  {
    title: t('检索记忆', 'Recall Memories'),
    code: `results = await mem.recall(
    user_id="alice",
    query="programming preferences"
)
for m in results["merged"]:
    print(m["content"])`,
  },
  {
    title: t('接入 Claude Code（MCP）', 'Connect to Claude Code (MCP)'),
    code: `# claude_desktop_config.json
{
  "mcpServers": {
    "dbay": {
      "command": "uvx",
      "args": ["dbay-mcp"],
      "env": { "DBAY_API_KEY": "dbay_sk_your_key_here" }
    }
  }
}`,
  },
])
</script>

<style scoped>
.docs-home h1 { font-size: 28px; font-weight: 700; margin: 0 0 8px; }
.subtitle { color: #666; font-size: 15px; margin-bottom: 40px; line-height: 1.6; }
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
  background: #f4f4f6; border: 1px solid #e5e5e5; border-radius: 6px;
  padding: 12px 14px; font-size: 12px; color: #7c3aed;
  overflow-x: auto; margin: 0; font-family: monospace; white-space: pre;
}
.link-cards { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.link-card {
  background: #fff; border: 1px solid #e5e5e5; border-radius: 8px;
  padding: 16px; text-decoration: none; display: flex;
  flex-direction: column; gap: 6px; transition: border-color 0.15s;
}
.link-card:hover { border-color: #7c3aed; }
.link-card h3 { font-size: 14px; font-weight: 600; color: #1a1a1a; margin: 0; }
.link-card p { font-size: 12px; color: #666; margin: 0; flex: 1; line-height: 1.5; }
.more { font-size: 12px; color: #7c3aed; }
@media (max-width: 768px) {
  .link-cards { grid-template-columns: 1fr; }
}
</style>
