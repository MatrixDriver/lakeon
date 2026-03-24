<template>
  <div class="mcp-docs">
    <h1>MCP {{ t('工具', 'Tools') }}</h1>
    <p class="subtitle">{{ t('通过 MCP 协议将记忆库接入任何支持 MCP 的 AI 工具', 'Connect memory store to any MCP-compatible AI tool') }}</p>

    <section class="section">
      <h2>{{ t('安装', 'Installation') }}</h2>
      <pre class="code-block"><code>pip install dbay-mcp
# 或使用 uvx（推荐，无需手动安装）
uvx dbay-mcp</code></pre>
    </section>

    <section class="section">
      <h2>{{ t('配置', 'Configuration') }}</h2>
      <h3>Claude Code / Claude Desktop</h3>
      <pre class="code-block"><code>{
  "mcpServers": {
    "dbay": {
      "command": "uvx",
      "args": ["dbay-mcp"],
      "env": {
        "DBAY_API_KEY": "dbay_sk_your_key_here"
      }
    }
  }
}</code></pre>
      <p class="tip">{{ t('Claude Desktop 配置文件路径：', 'Claude Desktop config file location:') }} <code>~/Library/Application Support/Claude/claude_desktop_config.json</code></p>

      <h3>Cursor</h3>
      <pre class="code-block"><code># .cursor/mcp.json
{
  "mcpServers": {
    "dbay": {
      "command": "uvx",
      "args": ["dbay-mcp"],
      "env": { "DBAY_API_KEY": "dbay_sk_your_key_here" }
    }
  }
}</code></pre>
    </section>

    <section class="section">
      <h2>{{ t('环境变量', 'Environment Variables') }}</h2>
      <div class="param-table">
        <div class="param-row header">
          <span>{{ t('变量', 'Variable') }}</span>
          <span>{{ t('必填', 'Required') }}</span>
          <span>{{ t('说明', 'Description') }}</span>
        </div>
        <div v-for="env in envVars" :key="env.name" class="param-row">
          <code>{{ env.name }}</code>
          <span :class="env.required ? 'req-yes' : 'req-no'">{{ env.required ? t('是', 'Yes') : t('否', 'No') }}</span>
          <span>{{ env.desc }}</span>
        </div>
      </div>
    </section>

    <section class="section">
      <h2>{{ t('可用工具', 'Available Tools') }}</h2>
      <div class="tools-table">
        <div class="tool-row header">
          <span>{{ t('工具名', 'Tool Name') }}</span>
          <span>{{ t('描述', 'Description') }}</span>
          <span>{{ t('主要参数', 'Key Parameters') }}</span>
        </div>
        <div v-for="tool in tools" :key="tool.name" class="tool-row">
          <code>{{ tool.name }}</code>
          <span>{{ tool.desc }}</span>
          <span class="params">{{ tool.params }}</span>
        </div>
      </div>
    </section>

    <section class="section">
      <h2>{{ t('使用示例', 'Usage Examples') }}</h2>
      <p>{{ t('配置完成后，在 Claude Code 中直接使用自然语言调用：', 'After configuration, use natural language in Claude Code:') }}</p>
      <div class="example-list">
        <div v-for="ex in examples" :key="ex.prompt" class="example-item">
          <span class="prompt-label">{{ t('指令', 'Prompt') }}</span>
          <p class="prompt-text">"{{ ex.prompt }}"</p>
          <span class="action-label">{{ t('调用', 'Calls') }}</span>
          <code class="tool-call">{{ ex.tool }}</code>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useLocale } from '../../stores/locale'

const { t } = useLocale()

const envVars = computed(() => [
  { name: 'DBAY_API_KEY', required: true, desc: t('DBay API Key，在控制台创建', 'DBay API Key, created in the console') },
  { name: 'DBAY_BASE_URL', required: false, desc: t('自定义 API 地址，默认 https://api.dbay.cloud', 'Custom API base URL, default https://api.dbay.cloud') },
  { name: 'DBAY_USER_ID', required: false, desc: t('默认用户 ID，默认 "default"', 'Default user ID, default "default"') },
])

const tools = computed(() => [
  {
    name: 'memory_ingest',
    desc: t('将文本内容写入记忆库，自动提取结构化记忆', 'Write text to memory store, auto-extracts structured memories'),
    params: 'content, user_id?, role?',
  },
  {
    name: 'memory_recall',
    desc: t('根据查询检索相关记忆，返回合并后的结果和特征', 'Retrieve relevant memories by query, returns merged results and traits'),
    params: 'query, user_id?, top_k?',
  },
  {
    name: 'memory_digest',
    desc: t('对积累的记忆进行反思，发现行为模式和特征', 'Digest accumulated memories, discover behavioral patterns and traits'),
    params: 'user_id?',
  },
  {
    name: 'kb_search',
    desc: t('在知识库中检索相关文档片段', 'Search relevant document chunks in the knowledge base'),
    params: 'query, kb_id, top_k?',
  },
])

const examples = computed(() => [
  {
    prompt: t('记住我喜欢用 TypeScript 写后端', 'Remember that I prefer TypeScript for backend'),
    tool: 'memory_ingest(content="User prefers TypeScript for backend")',
  },
  {
    prompt: t('我之前说过什么关于代码风格的偏好？', 'What have I said about code style preferences?'),
    tool: 'memory_recall(query="code style preferences")',
  },
  {
    prompt: t('总结一下我的技术偏好', 'Summarize my technical preferences'),
    tool: 'memory_digest() → memory_recall(query="tech preferences")',
  },
])
</script>

<style scoped>
.mcp-docs h1 { font-size: 28px; font-weight: 700; margin: 0 0 8px; }
.subtitle { color: #666; font-size: 15px; margin-bottom: 40px; }
.section { margin-bottom: 40px; }
.section h2 { font-size: 18px; font-weight: 600; margin-bottom: 16px; padding-bottom: 8px; border-bottom: 1px solid #e5e5e5; }
.section h3 { font-size: 14px; font-weight: 600; color: #444; margin: 16px 0 8px; }
.section p { font-size: 14px; color: #666; line-height: 1.6; margin-bottom: 8px; }
.section code { background: #f4f4f6; padding: 1px 6px; border-radius: 3px; color: #7c3aed; font-family: monospace; font-size: 12px; }
.tip { font-size: 12px; color: #999; margin-top: 8px; }
.code-block {
  background: #f4f4f6; border: 1px solid #e5e5e5; border-radius: 6px;
  padding: 12px 14px; font-size: 12px; color: #7c3aed;
  overflow-x: auto; margin-bottom: 12px; font-family: monospace; white-space: pre;
}
.param-table { border: 1px solid #e5e5e5; border-radius: 6px; overflow: hidden; font-size: 13px; margin-bottom: 0; }
.param-row { display: grid; grid-template-columns: 180px 60px 1fr; gap: 1px; background: #e5e5e5; }
.param-row.header > * { background: #f9f9f9; color: #999; font-size: 11px; font-weight: 600; text-transform: uppercase; }
.param-row > * { background: #fff; padding: 8px 10px; }
.param-row code { font-family: monospace; color: #7c3aed; background: transparent; padding: 8px 10px; }
.req-yes { color: #ea580c; font-size: 11px; font-weight: 600; }
.req-no { color: #bbb; font-size: 11px; }
.tools-table { border: 1px solid #e5e5e5; border-radius: 6px; overflow: hidden; font-size: 13px; }
.tool-row { display: grid; grid-template-columns: 160px 1fr 180px; gap: 1px; background: #e5e5e5; }
.tool-row.header > * { background: #f9f9f9; color: #999; font-size: 11px; font-weight: 600; text-transform: uppercase; }
.tool-row > * { background: #fff; padding: 8px 10px; color: #444; }
.tool-row code { font-family: monospace; color: #7c3aed; background: transparent; padding: 8px 10px; }
.params { font-family: monospace; font-size: 11px; color: #999; }
.example-list { display: flex; flex-direction: column; gap: 12px; }
.example-item { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 14px; }
.prompt-label, .action-label { font-size: 10px; font-weight: 600; color: #999; text-transform: uppercase; letter-spacing: 0.06em; }
.prompt-text { font-size: 13px; color: #444; margin: 4px 0 10px; font-style: italic; }
.tool-call { font-family: monospace; font-size: 12px; color: #7c3aed; background: #f4f4f6; padding: 4px 8px; border-radius: 4px; display: inline-block; margin-top: 4px; }
@media (max-width: 600px) {
  .tool-row { grid-template-columns: 140px 1fr; }
  .tool-row .params { display: none; }
}
</style>
