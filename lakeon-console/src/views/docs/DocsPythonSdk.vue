<template>
  <div class="sdk-docs">
    <h1>Python SDK</h1>
    <p class="subtitle">{{ t('异步 Python 客户端，支持 OpenAI / Anthropic 嵌入模型', 'Async Python client supporting OpenAI / Anthropic embedding models') }}</p>

    <section class="section">
      <h2>{{ t('安装', 'Installation') }}</h2>
      <pre class="code-block"><code>pip install dbay-memory</code></pre>
      <p>{{ t('支持 Python 3.9+，依赖 asyncio。', 'Requires Python 3.9+. Uses asyncio.') }}</p>
    </section>

    <section class="section">
      <h2>{{ t('快速开始', 'Quick Start') }}</h2>
      <pre class="code-block"><code>import asyncio
from dbay_memory import DBayMemory
from dbay_memory.providers import OpenAIEmbedding, OpenAILLM

async def main():
    mem = DBayMemory(
        api_key="dbay_sk_your_key_here",
        embedding=OpenAIEmbedding(api_key="sk-..."),
        llm=OpenAILLM(api_key="sk-..."),
    )
    await mem.init()

    # Store a memory
    await mem.ingest(
        user_id="user-123",
        role="user",
        content="I prefer TypeScript and use VS Code daily"
    )

    # Search memories
    results = await mem.recall(
        user_id="user-123",
        query="programming preferences"
    )
    for m in results["merged"]:
        print(f"{m['memory_type']}: {m['content']} (score: {m['score']:.2f})")

    # Discover behavioral patterns
    digest_result = await mem.digest(user_id="user-123")
    print(f"Traits generated: {digest_result['traits_generated']}")

    await mem.close()

asyncio.run(main())</code></pre>
    </section>

    <section class="section">
      <h2>{{ t('构造函数', 'Constructor') }}</h2>
      <div class="signature">DBayMemory(api_key, embedding, llm, **kwargs)</div>
      <div class="param-table">
        <div class="param-row header">
          <span>{{ t('参数', 'Param') }}</span><span>{{ t('类型', 'Type') }}</span>
          <span>{{ t('必填', 'Required') }}</span><span>{{ t('说明', 'Description') }}</span>
        </div>
        <div v-for="p in constructorParams" :key="p.name" class="param-row">
          <code>{{ p.name }}</code>
          <span class="type">{{ p.type }}</span>
          <span :class="p.required ? 'req-yes' : 'req-no'">{{ p.required ? t('是', 'Yes') : t('否', 'No') }}</span>
          <span>{{ p.desc }}</span>
        </div>
      </div>
    </section>

    <section class="section">
      <h2>{{ t('核心方法', 'Core Methods') }}</h2>

      <div v-for="m in methods" :key="m.name" class="method-card">
        <div class="method-sig">{{ m.sig }}</div>
        <p class="method-desc">{{ m.desc }}</p>
        <pre class="code-block"><code>{{ m.example }}</code></pre>
      </div>
    </section>

    <section class="section">
      <h2>{{ t('嵌入模型提供商', 'Embedding Providers') }}</h2>
      <div class="provider-grid">
        <div v-for="p in providers" :key="p.name" class="provider-card">
          <h3>{{ p.name }}</h3>
          <pre class="code-block small"><code>{{ p.code }}</code></pre>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useLocale } from '../../stores/locale'

const { t } = useLocale()

const constructorParams = computed(() => [
  { name: 'api_key', type: 'str', required: true, desc: t('DBay API Key', 'DBay API Key') },
  { name: 'embedding', type: 'EmbeddingProvider', required: true, desc: t('嵌入模型提供商', 'Embedding model provider') },
  { name: 'llm', type: 'LLMProvider', required: false, desc: t('LLM 提供商（ONE LLM 模式需要）', 'LLM provider (required for ONE LLM mode)') },
  { name: 'base_url', type: 'str', required: false, desc: t('自定义 API 地址，默认 https://api.dbay.cloud', 'Custom API base URL, default https://api.dbay.cloud') },
])

const methods = computed(() => [
  {
    name: 'ingest',
    sig: 'await mem.ingest(user_id, content, role="user")',
    desc: t('将对话内容写入记忆库，自动提取事实、事件和关系。', 'Write conversation content to memory store, auto-extracting facts, episodes and triples.'),
    example: `result = await mem.ingest(
    user_id="alice",
    content="I switched from Vim to VS Code last month",
    role="user"
)
print(result["id"])  # mem_abc123`,
  },
  {
    name: 'recall',
    sig: 'await mem.recall(user_id, query, top_k=10, include_traits=True)',
    desc: t('检索与查询相关的记忆，返回 merged 列表（按相关度排序）和 traits 列表。', 'Retrieve memories relevant to the query. Returns merged list (sorted by relevance) and traits list.'),
    example: `results = await mem.recall(
    user_id="alice",
    query="code editor preferences"
)
for m in results["merged"]:
    print(f"{m['memory_type']}: {m['content']}")
for trait in results.get("traits", []):
    print(f"trait: {trait['content']}")`,
  },
  {
    name: 'digest',
    sig: 'await mem.digest(user_id)',
    desc: t('分析积累的记忆，提取用户特征和行为模式。建议在会话结束时调用。', 'Analyze accumulated memories and extract user traits and behavioral patterns. Call at session end.'),
    example: `result = await mem.digest(user_id="alice")
print(f"Traits generated: {result['traits_generated']}")
print(f"Memories processed: {result['memories_processed']}")`,
  },
  {
    name: 'delete',
    sig: 'await mem.delete(memory_id)',
    desc: t('删除单条记忆。', 'Delete a single memory by ID.'),
    example: `await mem.delete("mem_abc123")`,
  },
])

const providers = computed(() => [
  {
    name: 'OpenAI',
    code: `from dbay_memory.providers import OpenAIEmbedding, OpenAILLM

embedding = OpenAIEmbedding(
    api_key="sk-...",
    model="text-embedding-3-small"
)
llm = OpenAILLM(api_key="sk-...", model="gpt-4o-mini")`,
  },
  {
    name: 'Anthropic',
    code: `from dbay_memory.providers import AnthropicEmbedding, AnthropicLLM

embedding = AnthropicEmbedding(api_key="sk-ant-...")
llm = AnthropicLLM(
    api_key="sk-ant-...",
    model="claude-haiku-4-5-20251001"
)`,
  },
])
</script>

<style scoped>
.sdk-docs h1 { font-size: 28px; font-weight: 700; margin: 0 0 8px; }
.subtitle { color: #666; font-size: 15px; margin-bottom: 40px; }
.section { margin-bottom: 40px; }
.section h2 { font-size: 18px; font-weight: 600; margin-bottom: 16px; padding-bottom: 8px; border-bottom: 1px solid #e5e5e5; }
.section p { font-size: 14px; color: #666; margin-bottom: 12px; line-height: 1.6; }
.signature { background: #f4f4f6; border: 1px solid #e5e5e5; border-radius: 6px; padding: 10px 14px; font-family: monospace; font-size: 13px; color: #444; margin-bottom: 16px; }
.param-table { border: 1px solid #e5e5e5; border-radius: 6px; overflow: hidden; margin-bottom: 16px; font-size: 13px; }
.param-row { display: grid; grid-template-columns: 140px 120px 60px 1fr; gap: 1px; background: #e5e5e5; }
.param-row.header { background: #f5f5f5; }
.param-row > * { background: #fff; padding: 8px 10px; }
.param-row.header > * { background: #f9f9f9; color: #999; font-size: 11px; font-weight: 600; text-transform: uppercase; }
.param-row code { font-family: monospace; color: #7c3aed; background: transparent; padding: 8px 10px; }
.type { color: #999; font-family: monospace; }
.req-yes { color: #ea580c; font-size: 11px; font-weight: 600; }
.req-no { color: #bbb; font-size: 11px; }
.method-card { border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; background: #fff; }
.method-sig { font-family: monospace; font-size: 13px; color: #444; font-weight: 600; margin-bottom: 8px; }
.method-desc { font-size: 13px; color: #666; margin-bottom: 12px; line-height: 1.6; }
.code-block {
  background: #f4f4f6; border: 1px solid #e5e5e5; border-radius: 6px;
  padding: 12px 14px; font-size: 12px; color: #7c3aed;
  overflow-x: auto; margin: 0; font-family: monospace; white-space: pre;
}
.code-block.small { font-size: 11px; }
.provider-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.provider-card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; }
.provider-card h3 { font-size: 14px; font-weight: 600; margin: 0 0 12px; }
@media (max-width: 600px) {
  .provider-grid { grid-template-columns: 1fr; }
  .param-row { grid-template-columns: 120px 90px 50px 1fr; }
}
</style>
