<template>
  <div class="rest-api-docs">
    <h1>REST API</h1>
    <p class="subtitle">{{ t('完整 HTTP API 参考文档', 'Complete HTTP API reference') }}</p>
    <p class="base-url">
      Base URL: <code>https://api.dbay.cloud</code>
    </p>

    <section class="section">
      <h2>{{ t('认证', 'Authentication') }}</h2>
      <p>{{ t('所有请求需在 Authorization 请求头中携带 API Key，可在控制台获取，格式为', 'All requests require an API Key in the Authorization header. Get yours from the dashboard. Keys start with') }} <code>dbay_sk_</code>.</p>
      <pre class="code-block"><code>Authorization: Bearer dbay_sk_your_api_key_here</code></pre>
    </section>

    <div class="endpoint-toc">
      <p class="toc-label">{{ t('端点列表', 'Endpoints') }}</p>
      <div v-for="ep in endpoints" :key="ep.path" class="toc-item">
        <span class="method-badge" :class="ep.method.toLowerCase()">{{ ep.method }}</span>
        <a :href="`#${ep.anchor}`" class="toc-path">{{ ep.path }}</a>
      </div>
    </div>

    <template v-for="ep in endpoints" :key="ep.path">
      <section :id="ep.anchor" class="endpoint-section">
        <div class="endpoint-hd">
          <span class="method-badge" :class="ep.method.toLowerCase()">{{ ep.method }}</span>
          <h2 class="endpoint-path">{{ ep.path }}</h2>
        </div>
        <p class="endpoint-desc">{{ ep.desc }}</p>

        <template v-if="ep.params && ep.params.length">
          <p class="param-title">{{ t('请求体', 'Request Body') }}</p>
          <div class="param-table">
            <div class="param-row header">
              <span>{{ t('字段', 'Field') }}</span>
              <span>{{ t('类型', 'Type') }}</span>
              <span>{{ t('必填', 'Required') }}</span>
              <span>{{ t('说明', 'Description') }}</span>
            </div>
            <div v-for="p in ep.params" :key="p.name" class="param-row">
              <code>{{ p.name }}</code>
              <span class="type">{{ p.type }}</span>
              <span :class="p.required ? 'req-yes' : 'req-no'">{{ p.required ? t('是', 'Yes') : t('否', 'No') }}</span>
              <span>{{ p.desc }}</span>
            </div>
          </div>
        </template>

        <template v-if="ep.request">
          <p class="param-title">{{ t('请求示例', 'Request Example') }}</p>
          <pre class="code-block"><code>{{ ep.request }}</code></pre>
        </template>
        <template v-if="ep.response">
          <p class="param-title">{{ t('响应示例', 'Response Example') }}</p>
          <pre class="code-block response"><code>{{ ep.response }}</code></pre>
        </template>
        <div v-if="ep.note" class="note">{{ ep.note }}</div>
      </section>
      <hr class="divider" />
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useLocale } from '../../stores/locale'

const { t } = useLocale()

const endpoints = computed(() => [
  {
    method: 'POST', path: '/api/v1/ingest', anchor: 'ingest',
    desc: t('将对话内容写入记忆库，系统自动提取事实、事件和关系', 'Write conversation content to memory store. System automatically extracts facts, episodes and relationships.'),
    params: [
      { name: 'content', type: 'string', required: true, desc: t('对话内容文本', 'Conversation content text') },
      { name: 'role', type: 'string', required: false, desc: t('发言角色，默认 "user"', 'Speaker role, default "user"') },
      { name: 'user_id', type: 'string', required: false, desc: t('用户标识，默认 "default"', 'User identifier, default "default"') },
    ],
    request: `curl -X POST https://api.dbay.cloud/api/v1/ingest \\
  -H "Authorization: Bearer dbay_sk_..." \\
  -H "Content-Type: application/json" \\
  -d '{
    "content": "User prefers dark mode and uses TypeScript daily",
    "user_id": "user-123",
    "role": "user"
  }'`,
    response: `{
  "id": "mem_abc123",
  "user_id": "user-123",
  "role": "user",
  "extraction_required": false
}`,
    note: t('ONE LLM 模式下 extraction_required 始终为 false，系统在服务端完成提取。', 'In ONE LLM mode, extraction_required is always false — extraction happens server-side.'),
  },
  {
    method: 'POST', path: '/api/v1/recall', anchor: 'recall',
    desc: t('根据查询语句从记忆库检索相关记忆，返回事实、事件、关系和特征', 'Retrieve relevant memories by query. Returns facts, episodes, triples and traits.'),
    params: [
      { name: 'query', type: 'string', required: true, desc: t('查询语句', 'Search query') },
      { name: 'user_id', type: 'string', required: false, desc: t('用户标识', 'User identifier') },
      { name: 'top_k', type: 'integer', required: false, desc: t('每类返回条数，默认 10', 'Results per type, default 10') },
      { name: 'include_traits', type: 'boolean', required: false, desc: t('是否包含用户特征，默认 true', 'Include user traits, default true') },
    ],
    request: `curl -X POST https://api.dbay.cloud/api/v1/recall \\
  -H "Authorization: Bearer dbay_sk_..." \\
  -H "Content-Type: application/json" \\
  -d '{
    "query": "programming preferences",
    "user_id": "user-123",
    "top_k": 5
  }'`,
    response: `{
  "merged": [
    { "memory_type": "fact", "content": "User prefers dark mode", "score": 0.92 },
    { "memory_type": "fact", "content": "User uses TypeScript daily", "score": 0.88 }
  ],
  "traits": [
    { "content": "Developer who prefers dark mode tools" }
  ]
}`,
    note: null,
  },
  {
    method: 'POST', path: '/api/v1/digest', anchor: 'digest',
    desc: t('对积累的记忆进行反思，提取用户特征和行为模式', 'Digest accumulated memories to extract user traits and behavioral patterns.'),
    params: [
      { name: 'user_id', type: 'string', required: false, desc: t('用户标识', 'User identifier') },
    ],
    request: `curl -X POST https://api.dbay.cloud/api/v1/digest \\
  -H "Authorization: Bearer dbay_sk_..." \\
  -H "Content-Type: application/json" \\
  -d '{ "user_id": "user-123" }'`,
    response: `{
  "user_id": "user-123",
  "traits_generated": 3,
  "memories_processed": 24
}`,
    note: null,
  },
  {
    method: 'GET', path: '/api/v1/memories', anchor: 'memories-list',
    desc: t('列出用户的所有记忆，支持按类型过滤', 'List all memories for a user, filterable by type.'),
    params: [
      { name: 'user_id', type: 'string', required: false, desc: t('用户标识（query param）', 'User identifier (query param)') },
      { name: 'memory_type', type: 'string', required: false, desc: t('过滤类型：fact / episode / triple / trait', 'Filter by type: fact / episode / triple / trait') },
      { name: 'limit', type: 'integer', required: false, desc: t('最大返回数，默认 50', 'Max results, default 50') },
    ],
    request: `curl "https://api.dbay.cloud/api/v1/memories?user_id=user-123&memory_type=fact" \\
  -H "Authorization: Bearer dbay_sk_..."`,
    response: `{
  "memories": [
    { "id": "mem_abc123", "memory_type": "fact", "content": "User prefers dark mode", "created_at": "2026-03-20T10:00:00Z" }
  ],
  "total": 1
}`,
    note: null,
  },
  {
    method: 'DELETE', path: '/api/v1/memories/{memory_id}', anchor: 'memories-delete',
    desc: t('删除单条记忆', 'Delete a single memory by ID.'),
    params: [],
    request: `curl -X DELETE https://api.dbay.cloud/api/v1/memories/mem_abc123 \\
  -H "Authorization: Bearer dbay_sk_..."`,
    response: `{ "deleted": true, "id": "mem_abc123" }`,
    note: null,
  },
  {
    method: 'POST', path: '/api/v1/memories/batch-delete', anchor: 'memories-batch-delete',
    desc: t('批量删除记忆', 'Batch delete memories by IDs.'),
    params: [
      { name: 'ids', type: 'array', required: true, desc: t('要删除的记忆 ID 数组', 'Array of memory IDs to delete') },
    ],
    request: `curl -X POST https://api.dbay.cloud/api/v1/memories/batch-delete \\
  -H "Authorization: Bearer dbay_sk_..." \\
  -H "Content-Type: application/json" \\
  -d '{ "ids": ["mem_abc123", "mem_def456"] }'`,
    response: `{ "deleted": 2 }`,
    note: null,
  },
])
</script>

<style scoped>
.rest-api-docs h1 { font-size: 28px; font-weight: 700; margin: 0 0 8px; }
.subtitle { color: var(--pub-text-2); font-size: 15px; margin-bottom: 4px; }
.base-url { font-size: 13px; color: var(--pub-text-3); margin-bottom: 32px; }
.base-url code { background: var(--pub-code-bg); padding: 2px 6px; border-radius: 3px; color: var(--pub-code); font-family: monospace; }
.section { margin-bottom: 40px; }
.section h2 { font-size: 18px; font-weight: 600; margin-bottom: 12px; }
.section p { font-size: 14px; color: var(--pub-text-2); line-height: 1.6; margin-bottom: 10px; }
.section code { background: var(--pub-code-bg); padding: 1px 6px; border-radius: 3px; color: #2563eb; font-family: monospace; font-size: 13px; }
.endpoint-toc { background: var(--pub-surface); border: 1px solid var(--pub-border); border-radius: 8px; padding: 16px; margin-bottom: 40px; }
.toc-label { font-size: 10px; font-weight: 600; color: var(--pub-text-4); text-transform: uppercase; letter-spacing: 0.08em; margin: 0 0 10px; }
.toc-item { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
.toc-path { font-size: 13px; font-family: monospace; color: var(--pub-text-2); text-decoration: none; }
.toc-path:hover { color: var(--pub-text); }
.method-badge {
  font-size: 10px; font-weight: 700; font-family: monospace;
  padding: 1px 6px; border-radius: 3px; border: 1px solid;
}
.method-badge.post { background: #eff6ff; color: #2563eb; border-color: #bfdbfe; }
.method-badge.get { background: #f0fdf4; color: #16a34a; border-color: #bbf7d0; }
.method-badge.delete { background: #fef2f2; color: #dc2626; border-color: #fecaca; }
.method-badge.patch { background: #fff7ed; color: #ea580c; border-color: #fed7aa; }
.endpoint-section { margin-bottom: 0; padding: 32px 0; }
.endpoint-hd { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.endpoint-path { font-size: 18px; font-weight: 600; font-family: monospace; margin: 0; }
.endpoint-desc { font-size: 14px; color: var(--pub-text-2); margin-bottom: 20px; line-height: 1.6; }
.param-title { font-size: 12px; font-weight: 600; color: var(--pub-text-4); text-transform: uppercase; letter-spacing: 0.06em; margin: 0 0 8px; }
.param-table { border: 1px solid var(--pub-border); border-radius: 6px; overflow: hidden; margin-bottom: 16px; font-size: 13px; }
.param-row { display: grid; grid-template-columns: 160px 80px 60px 1fr; gap: 1px; background: var(--pub-border); }
.param-row.header { background: var(--pub-hover); }
.param-row > * { background: var(--pub-surface); padding: 8px 10px; }
.param-row.header > * { background: var(--pub-surface); color: var(--pub-text-4); font-size: 11px; font-weight: 600; text-transform: uppercase; }
.param-row code { font-family: monospace; color: var(--pub-code); background: transparent; padding: 8px 10px; }
.type { color: var(--pub-text-4); font-family: monospace; }
.req-yes { color: #ea580c; font-size: 11px; font-weight: 600; }
.req-no { color: #bbb; font-size: 11px; }
.code-block {
  background: var(--pub-code-bg); border: 1px solid var(--pub-border); border-radius: 6px;
  padding: 12px 14px; font-size: 12px; color: var(--pub-code);
  overflow-x: auto; margin-bottom: 16px; font-family: monospace; white-space: pre;
}
.code-block.response { color: var(--pub-text-2); }
.note { border-left: 2px solid #7c3aed; background: var(--pub-accent-bg); border-radius: 0 6px 6px 0; padding: 10px 14px; font-size: 13px; color: var(--pub-text-2); margin-bottom: 0; }
.divider { border: 1px solid var(--pub-border); margin: 0; }
</style>
