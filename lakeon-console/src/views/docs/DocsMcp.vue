<template>
  <div class="mcp-docs">
    <h1>MCP {{ t('集成', 'Integration') }}</h1>
    <p class="subtitle">{{ t('将知识库和记忆库接入 Claude Code、Cursor 等 AI 工具', 'Connect knowledge base and memory to Claude Code, Cursor, and other AI tools') }}</p>

    <section class="section">
      <h2>Claude Code {{ t('（推荐）', '(Recommended)') }}</h2>
      <p>{{ t('复制以下命令到终端执行即可完成配置：', 'Copy and run the following command in your terminal:') }}</p>
      <pre class="code-block"><code>claude mcp add --scope user --transport http dbay \
  https://api.dbay.cloud:8443/mcp \
  --header "Authorization: Bearer {{ apiKey }}"</code></pre>
      <p class="tip">{{ t('使用 --scope user 全局生效，所有项目共享。API Key 不会进入代码仓库。', 'Using --scope user makes it global across all projects. API Key stays out of your repo.') }}</p>
    </section>

    <section class="section">
      <h2>Cursor / Windsurf / {{ t('其他 MCP 客户端', 'Other MCP Clients') }}</h2>
      <p>{{ t('在项目根目录创建', 'Create') }} <code>.mcp.json</code> {{ t('文件：', 'in your project root:') }}</p>
      <pre class="code-block"><code>{
  "mcpServers": {
    "dbay": {
      "transport": "http",
      "url": "https://api.dbay.cloud:8443/mcp",
      "headers": {
        "Authorization": "Bearer {{ apiKey }}"
      }
    }
  }
}</code></pre>
      <p class="tip">{{ t('注意：请将 .mcp.json 加入 .gitignore，避免 API Key 泄露。', 'Note: Add .mcp.json to .gitignore to keep your API Key safe.') }}</p>
    </section>

    <section class="section">
      <h2>{{ t('可用工具', 'Available Tools') }}</h2>

      <h3>{{ t('知识库', 'Knowledge Base') }}</h3>
      <div class="tools-table">
        <div class="tool-row header">
          <span>{{ t('工具名', 'Tool') }}</span>
          <span>{{ t('描述', 'Description') }}</span>
          <span>{{ t('主要参数', 'Parameters') }}</span>
        </div>
        <div v-for="tool in knowledgeTools" :key="tool.name" class="tool-row">
          <code>{{ tool.name }}</code>
          <span>{{ tool.desc }}</span>
          <span class="params">{{ tool.params }}</span>
        </div>
      </div>

      <h3>{{ t('记忆库', 'Memory') }}</h3>

      <div v-if="memoryBases.length" class="base-id-box">
        <span class="base-id-label">{{ t('你的记忆库', 'Your Memory Bases') }}</span>
        <div v-for="mb in memoryBases" :key="mb.id" class="base-id-item">
          <code>{{ mb.id }}</code>
          <span class="base-id-name">{{ mb.name }}</span>
        </div>
        <p class="base-id-hint">{{ t('在调用记忆工具时使用上面的 base_id', 'Use the base_id above when calling memory tools') }}</p>
      </div>
      <div v-else class="base-id-box empty">
        <span>{{ t('你还没有记忆库。', 'You don\'t have a memory base yet.') }} <router-link to="/memory">{{ t('去创建', 'Create one') }}</router-link></span>
      </div>

      <div class="tools-table">
        <div class="tool-row header">
          <span>{{ t('工具名', 'Tool') }}</span>
          <span>{{ t('描述', 'Description') }}</span>
          <span>{{ t('主要参数', 'Parameters') }}</span>
        </div>
        <div v-for="tool in memoryTools" :key="tool.name" class="tool-row">
          <code>{{ tool.name }}</code>
          <span>{{ tool.desc }}</span>
          <span class="params">{{ tool.params }}</span>
        </div>
      </div>

      <div class="memory-types">
        <h3>{{ t('记忆类型', 'Memory Types') }}</h3>
        <div class="type-chips">
          <span v-for="mt in memoryTypes" :key="mt.type" class="type-chip">
            <code>{{ mt.type }}</code> {{ mt.desc }}
          </span>
        </div>
      </div>
    </section>

    <section class="section">
      <h2>{{ t('使用示例', 'Usage Examples') }}</h2>
      <p>{{ t('配置完成后，在 AI 工具中用自然语言调用：', 'After setup, use natural language in your AI tool:') }}</p>
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
import { computed, onMounted, ref } from 'vue'
import { useLocale } from '../../stores/locale'
import { listMemoryBases, type MemoryBase } from '../../api/memory'

const { t } = useLocale()

const memoryBases = ref<MemoryBase[]>([])

onMounted(async () => {
  try {
    const { data } = await listMemoryBases()
    memoryBases.value = data.filter(b => b.status === 'READY')
  } catch { /* ignore */ }
})

const firstBaseId = computed(() => memoryBases.value[0]?.id ?? 'your_base_id')

const apiKey = computed(() => localStorage.getItem('lakeon_api_key') || 'lk_your_api_key')

const knowledgeTools = computed(() => [
  {
    name: 'knowledge_list_bases',
    desc: t('列出所有知识库', 'List all knowledge bases'),
    params: '-',
  },
  {
    name: 'knowledge_search',
    desc: t('语义搜索知识库，返回相关文档片段', 'Semantic search, returns relevant chunks'),
    params: 'kb_id, query, top_k?',
  },
  {
    name: 'knowledge_list_documents',
    desc: t('列出知识库中的文档', 'List documents in a knowledge base'),
    params: 'kb_id',
  },
  {
    name: 'knowledge_get_chunk',
    desc: t('获取指定切片的完整内容', 'Get full content of a specific chunk'),
    params: 'kb_id, document_id, chunk_index',
  },
])

const memoryTools = computed(() => [
  {
    name: 'memory_recall',
    desc: t('语义检索相关记忆', 'Recall related memories by semantic search'),
    params: 'base_id, query, memory_types?, top_k?',
  },
  {
    name: 'memory_ingest',
    desc: t('存储一条记忆，服务端自动分类', 'Store a memory, server auto-categorizes'),
    params: 'base_id, content, memory_type?',
  },
  {
    name: 'memory_ingest_extracted',
    desc: t('批量写入已提取的结构化记忆', 'Batch write pre-extracted structured memories'),
    params: 'base_id, memories[]',
  },
  {
    name: 'memory_list',
    desc: t('浏览记忆列表，可按类型过滤', 'Browse memories, filter by type'),
    params: 'base_id, memory_type?, limit?',
  },
  {
    name: 'memory_delete',
    desc: t('删除指定记忆', 'Delete a specific memory'),
    params: 'base_id, memory_id',
  },
])

const memoryTypes = computed(() => [
  { type: 'fact', desc: t('事实', 'Facts') },
  { type: 'episode', desc: t('情景', 'Episodes') },
  { type: 'procedural', desc: t('流程', 'Procedures') },
  { type: 'decision', desc: t('决策', 'Decisions') },
  { type: 'rejection', desc: t('拒绝', 'Rejections') },
  { type: 'convention', desc: t('惯例', 'Conventions') },
])

const examples = computed(() => [
  {
    prompt: t('搜一下知识库里关于部署流程的内容', 'Search the knowledge base for deployment procedures'),
    tool: 'knowledge_search(kb_id, query="deployment")',
  },
  {
    prompt: t('记住我的华为云 AK 是 xxx', 'Remember my Huawei Cloud AK is xxx'),
    tool: `memory_ingest(base_id="${firstBaseId.value}", content="...", memory_type="fact")`,
  },
  {
    prompt: t('我之前怎么解决 OOM 问题的？', 'How did I solve the OOM issue before?'),
    tool: `memory_recall(base_id="${firstBaseId.value}", query="OOM solution")`,
  },
  {
    prompt: t('删掉那条过时的记忆', 'Delete that outdated memory'),
    tool: `memory_delete(base_id="${firstBaseId.value}", memory_id)`,
  },
])
</script>

<style scoped>
.mcp-docs h1 { font-size: 28px; font-weight: 700; margin: 0 0 8px; }
.subtitle { color: var(--pub-text-2); font-size: 15px; margin-bottom: 40px; }
.section { margin-bottom: 40px; }
.section h2 { font-size: 18px; font-weight: 600; margin-bottom: 16px; padding-bottom: 8px; border-bottom: 1px solid var(--pub-border); }
.section h3 { font-size: 14px; font-weight: 600; color: var(--pub-text-2); margin: 20px 0 8px; }
.section p { font-size: 14px; color: var(--pub-text-2); line-height: 1.6; margin-bottom: 8px; }
.section code { background: var(--pub-code-bg); padding: 1px 6px; border-radius: 3px; color: var(--pub-code); font-family: monospace; font-size: 12px; }
.tip { font-size: 12px; color: var(--pub-text-4); margin-top: 8px; }
.code-block {
  background: var(--pub-code-bg); border: 1px solid var(--pub-border); border-radius: 6px;
  padding: 12px 14px; font-size: 12px; color: var(--pub-code);
  overflow-x: auto; margin-bottom: 12px; font-family: monospace; white-space: pre;
}
.tools-table { border: 1px solid var(--pub-border); border-radius: 6px; overflow: hidden; font-size: 13px; margin-bottom: 12px; }
.tool-row { display: grid; grid-template-columns: 200px 1fr 220px; gap: 1px; background: var(--pub-border); }
.tool-row.header > * { background: var(--pub-surface); color: var(--pub-text-4); font-size: 11px; font-weight: 600; text-transform: uppercase; }
.tool-row > * { background: var(--pub-surface); padding: 8px 10px; color: var(--pub-text-2); }
.tool-row code { font-family: monospace; color: var(--pub-code); background: transparent; padding: 8px 10px; }
.params { font-family: monospace; font-size: 11px; color: var(--pub-text-4); }
.base-id-box { background: var(--pub-surface); border: 1px solid var(--pub-border); border-radius: 6px; padding: 12px 14px; margin-bottom: 12px; }
.base-id-box.empty { color: var(--pub-text-4); font-size: 13px; }
.base-id-label { font-size: 11px; font-weight: 600; color: var(--pub-text-4); text-transform: uppercase; letter-spacing: 0.06em; }
.base-id-item { margin-top: 6px; display: flex; align-items: center; gap: 10px; }
.base-id-item code { font-family: monospace; font-size: 13px; color: var(--pub-code); background: var(--pub-code-bg); padding: 2px 8px; border-radius: 3px; }
.base-id-name { font-size: 13px; color: var(--pub-text-2); }
.base-id-hint { font-size: 11px; color: var(--pub-text-4); margin-top: 8px; margin-bottom: 0; }
.memory-types { margin-top: 16px; }
.type-chips { display: flex; flex-wrap: wrap; gap: 8px; }
.type-chip { font-size: 13px; color: var(--pub-text-2); background: var(--pub-surface); border: 1px solid var(--pub-border); border-radius: 6px; padding: 4px 10px; }
.type-chip code { background: transparent; color: var(--pub-code); font-size: 12px; margin-right: 4px; }
.example-list { display: flex; flex-direction: column; gap: 12px; }
.example-item { background: var(--pub-surface); border: 1px solid var(--pub-border); border-radius: 8px; padding: 14px; }
.prompt-label, .action-label { font-size: 10px; font-weight: 600; color: var(--pub-text-4); text-transform: uppercase; letter-spacing: 0.06em; }
.prompt-text { font-size: 13px; color: var(--pub-text-2); margin: 4px 0 10px; font-style: italic; }
.tool-call { font-family: monospace; font-size: 12px; color: var(--pub-code); background: var(--pub-code-bg); padding: 4px 8px; border-radius: 4px; display: inline-block; margin-top: 4px; }
@media (max-width: 600px) {
  .tool-row { grid-template-columns: 160px 1fr; }
  .tool-row .params { display: none; }
}
</style>
