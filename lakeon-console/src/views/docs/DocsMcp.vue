<template>
  <div class="mcp-docs">
    <h1>MCP {{ t('集成', 'Integration') }}</h1>
    <p class="subtitle">{{ t('将知识库和记忆库接入 Claude Code、Cursor 等 AI 工具', 'Connect knowledge base and memory to Claude Code, Cursor, and other AI tools') }}</p>

    <section class="section">
      <h2>{{ t('第 1 步：配置凭据', 'Step 1: Configure Credentials') }}</h2>
      <p>{{ t('安装后执行登录命令，自动写入', 'Install and login to auto-configure') }} <code>~/.dbay/config.json</code>{{ t('：', ':') }}</p>
      <div class="code-wrapper">
        <pre class="code-block"><code>{{ loginSnippet }}</code></pre>
        <button class="copy-btn" @click="copy(loginSnippet)">{{ copyLabel }}</button>
      </div>
      <p class="tip">{{ t('也可以手动创建配置文件：', 'Or create the config file manually:') }}</p>
      <div class="code-wrapper">
        <pre class="code-block"><code>{{ configSnippet }}</code></pre>
        <button class="copy-btn" @click="copy(configSnippet)">{{ copyLabel }}</button>
      </div>
      <p class="tip">{{ t('API Key 和资源 ID 只存放在本地 ~/.dbay/ 目录，不会进入 AI 工具的配置文件或代码仓库。', 'API Key and resource IDs live in ~/.dbay/ only — never enter AI tool configs or your repo.') }}</p>
    </section>

    <section class="section">
      <h2>{{ t('第 2 步：注册 MCP 服务', 'Step 2: Register MCP Server') }}</h2>

      <h3>Claude Code {{ t('（推荐）', '(Recommended)') }}</h3>
      <p>{{ t('终端执行：', 'Run in terminal:') }}</p>
      <div class="code-wrapper">
        <pre class="code-block"><code>{{ claudeCodeSnippet }}</code></pre>
        <button class="copy-btn" @click="copy(claudeCodeSnippet)">{{ copyLabel }}</button>
      </div>
      <p class="tip">{{ t('--scope user 全局生效。配置中只有启动命令，不含任何密钥。', '--scope user makes it global. Config only contains the launch command, no secrets.') }}</p>

      <h3>Cursor / Windsurf / {{ t('其他 MCP 客户端', 'Other MCP Clients') }}</h3>
      <p>{{ t('在项目根目录创建', 'Create') }} <code>.mcp.json</code>{{ t('：', ':') }}</p>
      <div class="code-wrapper">
        <pre class="code-block"><code>{{ cursorSnippet }}</code></pre>
        <button class="copy-btn" @click="copy(cursorSnippet)">{{ copyLabel }}</button>
      </div>
      <p class="tip">{{ t('MCP 配置中不含 API Key，可安全提交到 git 仓库。', 'No API Key in MCP config — safe to commit to git.') }}</p>
    </section>

    <section class="section">
      <h2>{{ t('第 3 步：启用记忆提示（推荐）', 'Step 3: Enable Memory Hints (Recommended)') }}</h2>
      <p>{{ t('让 AI 工具在你说"记住"时自动调用 DBay 记忆库，而不是只存在本地。', 'Tell your AI tool to use DBay memory when you say "remember", instead of local-only storage.') }}</p>

      <div v-for="agent in agentSetups" :key="agent.name" class="agent-setup">
        <h3>{{ agent.name }}</h3>
        <div class="code-wrapper">
          <pre class="code-block"><code>{{ agent.command }}</code></pre>
          <button class="copy-btn" @click="copy(agent.command)">{{ copyLabel }}</button>
        </div>
      </div>

      <p class="tip">{{ t('已安装 dbay-mcp 的用户也可以用：', 'If you have dbay-mcp installed:') }} <code>dbay setup claude-code</code></p>
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
import { listKnowledgeBases, type KnowledgeBase } from '../../api/knowledge'
import { listMemoryBases, type MemoryBase } from '../../api/memory'

const { t } = useLocale()

const knowledgeBases = ref<KnowledgeBase[]>([])
const memoryBases = ref<MemoryBase[]>([])

onMounted(async () => {
  try {
    const [kbRes, memRes] = await Promise.all([listKnowledgeBases(), listMemoryBases()])
    knowledgeBases.value = kbRes.data
    memoryBases.value = memRes.data.filter(b => b.status === 'READY')
  } catch { /* ignore */ }
})

const firstBaseId = computed(() => memoryBases.value[0]?.id ?? 'mem_your_base_id')
const firstKbId = computed(() => knowledgeBases.value[0]?.id ?? 'kb_your_kb_id')

const apiKey = computed(() => localStorage.getItem('lakeon_api_key') || 'lk_your_api_key')

const copyLabel = ref('Copy')
function copy(text: string) {
  navigator.clipboard.writeText(text)
  copyLabel.value = 'Copied!'
  setTimeout(() => { copyLabel.value = 'Copy' }, 1500)
}

const loginSnippet = 'pip install dbay-cli\ndbay login'
// dbay-cli auto-installs dbay-mcp as dependency

const memoryHint = 'When the user says "记住/remember", call dbay MCP `memory_ingest` to store it in the user\'s long-term memory (persists across projects and sessions). When the user asks about past decisions or context, call `memory_recall` first.'

const agentSetups = [
  {
    name: 'Claude Code',
    command: `mkdir -p ~/.claude\ncat >> ~/.claude/CLAUDE.md << 'EOF'\n\n<!-- dbay-memory -->\n## DBay Memory\n${memoryHint}\nEOF`,
  },
  {
    name: 'Gemini CLI',
    command: `mkdir -p ~/.gemini\ncat >> ~/.gemini/GEMINI.md << 'EOF'\n\n<!-- dbay-memory -->\n## DBay Memory\n${memoryHint}\nEOF`,
  },
  {
    name: 'Cursor',
    command: `mkdir -p ~/.cursor/rules\ncat > ~/.cursor/rules/dbay.mdc << 'EOF'\n---\ndescription: DBay memory integration\nglobs:\nalwaysApply: true\n---\n${memoryHint}\nEOF`,
  },
  {
    name: 'Windsurf',
    command: `mkdir -p ~/.windsurf/rules\ncat > ~/.windsurf/rules/dbay.md << 'EOF'\n\n<!-- dbay-memory -->\n## DBay Memory\n${memoryHint}\nEOF`,
  },
]

const configSnippet = computed(() =>
`mkdir -p ~/.dbay
cat > ~/.dbay/config.json << 'EOF'
{
  "endpoint": "https://api.dbay.cloud:8443",
  "api_key": "${apiKey.value}",
  "knowledge_base": "${firstKbId.value}",
  "memory_base": "${firstBaseId.value}"
}
EOF`)

const claudeCodeSnippet = 'claude mcp add --scope user dbay -- uvx dbay-mcp'

const cursorSnippet = `{
  "mcpServers": {
    "dbay": {
      "command": "uvx",
      "args": ["dbay-mcp"]
    }
  }
}`

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
.agent-setup { margin-bottom: 16px; }
.agent-setup h3 { margin-bottom: 6px; }
.mcp-docs h1 { font-size: 28px; font-weight: 700; margin: 0 0 8px; }
.subtitle { color: var(--pub-text-2); font-size: 15px; margin-bottom: 40px; }
.section { margin-bottom: 40px; }
.section h2 { font-size: 18px; font-weight: 600; margin-bottom: 16px; padding-bottom: 8px; border-bottom: 1px solid var(--pub-border); }
.section h3 { font-size: 14px; font-weight: 600; color: var(--pub-text-2); margin: 20px 0 8px; }
.section p { font-size: 14px; color: var(--pub-text-2); line-height: 1.6; margin-bottom: 8px; }
.section code { background: var(--pub-code-bg); padding: 1px 6px; border-radius: 3px; color: var(--pub-code); font-family: monospace; font-size: 12px; }
.tip { font-size: 12px; color: var(--pub-text-4); margin-top: 8px; }
.code-wrapper { position: relative; margin-bottom: 12px; }
.code-wrapper .code-block { margin-bottom: 0; }
.copy-btn {
  position: absolute; top: 8px; right: 8px;
  background: var(--pub-surface); border: 1px solid var(--pub-border); border-radius: 4px;
  padding: 2px 8px; font-size: 11px; color: var(--pub-text-4); cursor: pointer;
  transition: color 0.15s, border-color 0.15s;
}
.copy-btn:hover { color: var(--pub-code); border-color: var(--pub-code); }
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
