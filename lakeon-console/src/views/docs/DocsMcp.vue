<template>
  <div class="ppage dp">
    <!-- Manifesto -->
    <section class="ppage-section ppage-section-white">
      <div class="ppage-inner dp-inner">
        <router-link to="/docs" class="ppage-back">← {{ t('回到文档首页', 'Back to docs') }}</router-link>
        <div class="ppage-eyebrow">{{ t('开始使用 · MCP 工具', 'Using DBay · MCP tools') }}</div>

        <h1 class="dp-title">
          <span class="dp-title-a">{{ t('Agent 能调用的', 'The tools your agent') }}</span>
          <span class="dp-title-b">{{ t('一组工具。', 'can call directly.') }}</span>
        </h1>

        <p class="dp-lede">
          {{ t(
            'DBay 的 MCP 服务把知识库和记忆库的能力包装成一组 Agent 可以直接调用的工具。每个工具都是一次 DBay API 调用，数据始终在你这边。需要知道怎么把这些工具接进 Claude Code · Cursor · Cline · OpenClaw，看',
            "DBay's MCP server wraps your knowledge and memory bases in a set of tools that any MCP-compatible agent can call directly. Every tool call is a DBay API call — your data stays on your side. To wire these up in Claude Code, Cursor, Cline, or OpenClaw, read the"
          ) }}
          <router-link to="/integrations" class="dp-inline-link">{{ t('集成指引', 'integration guide') }}</router-link>{{ t('。', '.') }}
        </p>
      </div>
    </section>

    <!-- Knowledge tools -->
    <section class="ppage-section">
      <div class="ppage-inner dp-inner">
        <h2 class="ppage-section-title">{{ t('知识库工具', 'Knowledge tools') }}</h2>

        <div class="dmcp-table">
          <div class="dmcp-row dmcp-row-head">
            <span>{{ t('工具', 'Tool') }}</span>
            <span>{{ t('描述', 'Description') }}</span>
            <span>{{ t('主要参数', 'Parameters') }}</span>
          </div>
          <div v-for="tool in knowledgeTools" :key="tool.name" class="dmcp-row">
            <code class="dmcp-name">{{ tool.name }}</code>
            <span class="dmcp-desc">{{ tool.desc }}</span>
            <code class="dmcp-params">{{ tool.params }}</code>
          </div>
        </div>
      </div>
    </section>

    <!-- Memory tools -->
    <section class="ppage-section ppage-section-white">
      <div class="ppage-inner dp-inner">
        <h2 class="ppage-section-title">{{ t('记忆库工具', 'Memory tools') }}</h2>

        <div class="dmcp-table">
          <div class="dmcp-row dmcp-row-head">
            <span>{{ t('工具', 'Tool') }}</span>
            <span>{{ t('描述', 'Description') }}</span>
            <span>{{ t('主要参数', 'Parameters') }}</span>
          </div>
          <div v-for="tool in memoryTools" :key="tool.name" class="dmcp-row">
            <code class="dmcp-name">{{ tool.name }}</code>
            <span class="dmcp-desc">{{ tool.desc }}</span>
            <code class="dmcp-params">{{ tool.params }}</code>
          </div>
        </div>

        <div class="dmcp-types">
          <h3 class="dmcp-types-title">{{ t('记忆类型', 'Memory types') }}</h3>
          <p class="dmcp-types-note">
            {{ t(
              'DBay 把记忆分成 6 种类型。Agent 调用 memory_ingest 时可以指定类型，服务端也会自动分类。',
              'DBay sorts memories into six types. Agents can specify one when calling memory_ingest, and the server will auto-classify if none is given.'
            ) }}
          </p>
          <div class="dmcp-chips">
            <span v-for="mt in memoryTypes" :key="mt.type" class="dmcp-chip">
              <code>{{ mt.type }}</code>
              <span>{{ mt.desc }}</span>
            </span>
          </div>
        </div>
      </div>
    </section>

    <!-- Footer -->
    <section class="ppage-section">
      <div class="ppage-inner dp-inner">
        <div class="dp-footer-cards">
          <router-link to="/integrations" class="dp-card">
            <div class="dp-card-num">{{ t('怎么接', 'Wire it up') }}</div>
            <h3 class="dp-card-title">{{ t('集成指引', 'Integrations') }}</h3>
            <p class="dp-card-claim">{{ t('Claude Code · Cursor · Cline · OpenClaw 三步接入。', 'Claude Code · Cursor · Cline · OpenClaw in three steps.') }}</p>
            <span class="dp-card-more">{{ t('打开', 'Open') }} →</span>
          </router-link>
          <router-link to="/docs/rest-api" class="dp-card">
            <div class="dp-card-num">{{ t('或者', 'Or') }}</div>
            <h3 class="dp-card-title">REST API</h3>
            <p class="dp-card-claim">{{ t('直接调 HTTP，跳过 MCP。', 'Call the HTTP API directly, skip MCP.') }}</p>
            <span class="dp-card-more">{{ t('打开', 'Open') }} →</span>
          </router-link>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useLocale } from '../../stores/locale'

const { t } = useLocale()

const knowledgeTools = computed(() => [
  { name: 'knowledge_list', desc: t('列出可用的知识库', 'List the knowledge bases you can access'), params: '—' },
  { name: 'knowledge_search', desc: t('语义检索知识库中的 wiki 条目和文档片段', 'Semantic search over wiki entries and document chunks'), params: 'kb_id, query, top_k?' },
  { name: 'knowledge_upload', desc: t('上传单个文档到知识库', 'Upload a single document to a knowledge base'), params: 'kb_id, path' },
  { name: 'knowledge_upload_directory', desc: t('按目录批量上传文档', 'Upload every document in a directory'), params: 'kb_id, directory' },
  { name: 'knowledge_wiki_ingest', desc: t('触发 wiki 条目生成与知识链接整理', 'Trigger wiki entry generation and knowledge linking'), params: 'kb_id' },
])

const memoryTools = computed(() => [
  { name: 'memory_list', desc: t('浏览记忆列表，可按类型筛选', 'Browse memories, filterable by type'), params: 'base_id, type?, limit?' },
  { name: 'memory_recall', desc: t('语义检索相关的记忆条目', 'Recall memory entries by semantic search'), params: 'base_id, query, top_k?' },
  { name: 'memory_ingest', desc: t('写入一条记忆，服务端自动分类并反思', 'Write a memory — the server auto-classifies and reflects'), params: 'base_id, content, type?' },
  { name: 'memory_delete', desc: t('删除指定记忆条目', 'Delete a specific memory entry'), params: 'base_id, memory_id' },
])

const memoryTypes = computed(() => [
  { type: 'fact', desc: t('事实', 'Facts') },
  { type: 'episode', desc: t('情景', 'Episodes') },
  { type: 'procedural', desc: t('流程', 'Procedures') },
  { type: 'decision', desc: t('决策', 'Decisions') },
  { type: 'convention', desc: t('惯例', 'Conventions') },
  { type: 'rejection', desc: t('拒绝', 'Rejections') },
])
</script>

<style scoped>
.dp { background: var(--c-bg-alt); }
.dp-inner { max-width: 1040px; }

.dp-title {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: clamp(40px, 5vw, 72px);
  line-height: 1.05;
  letter-spacing: -0.02em;
  color: var(--c-primary);
  margin: 0 0 clamp(20px, 3vw, 32px);
}

.dp-title-a,
.dp-title-b {
  display: block;
}

.dp-title-b {
  color: var(--c-accent-text);
  font-style: italic;
  font-weight: 400;
}

.dp-lede {
  font-family: var(--font-display);
  font-weight: 400;
  font-size: clamp(18px, 2vw, 24px);
  line-height: 1.55;
  color: var(--c-text-2);
  max-width: 62ch;
  margin: 0;
}

.dp-inline-link {
  color: var(--c-accent-text);
  text-decoration: none;
  border-bottom: 1px solid currentColor;
}

.dp-inline-link:hover {
  color: var(--c-accent-hover);
}

.dmcp-table {
  border: 1px solid var(--c-border-light);
  border-radius: 6px;
  overflow: hidden;
  background: #fff;
}

.dmcp-row {
  display: grid;
  grid-template-columns: 220px 1fr 260px;
  gap: var(--space-lg);
  padding: var(--space-md) var(--space-lg);
  border-bottom: 1px solid var(--c-border-light);
  align-items: center;
}

.dmcp-row:last-child {
  border-bottom: none;
}

.dmcp-row-head {
  font-family: var(--font-sans);
  font-size: 11px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.1em;
  color: var(--c-text-3);
  background: var(--c-bg-alt);
}

.dmcp-name {
  font-family: var(--font-mono);
  font-size: 13px;
  font-weight: 500;
  color: var(--c-accent-text);
}

.dmcp-desc {
  font-family: var(--font-sans);
  font-size: 14px;
  line-height: 1.55;
  color: var(--c-text-2);
}

.dmcp-params {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--c-text-3);
  line-height: 1.5;
}

.dmcp-types {
  margin-top: clamp(32px, 4vw, 56px);
}

.dmcp-types-title {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: 18px;
  letter-spacing: -0.01em;
  color: var(--c-primary);
  margin: 0 0 var(--space-sm);
}

.dmcp-types-note {
  font-family: var(--font-sans);
  font-size: 14px;
  line-height: 1.6;
  color: var(--c-text-2);
  margin: 0 0 var(--space-lg);
  max-width: 62ch;
}

.dmcp-chips {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-sm);
}

.dmcp-chip {
  display: inline-flex;
  align-items: center;
  gap: var(--space-sm);
  padding: 6px 12px;
  background: #fff;
  border: 1px solid var(--c-border);
  border-radius: 999px;
  font-family: var(--font-sans);
  font-size: 13px;
  color: var(--c-text-2);
}

.dmcp-chip code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--c-accent-text);
}

.dp-footer-cards {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: clamp(20px, 2.5vw, 32px);
}

.dp-card {
  display: flex;
  flex-direction: column;
  padding: clamp(24px, 3vw, 36px);
  background: #fff;
  border: 1px solid var(--c-border-light);
  border-radius: 8px;
  text-decoration: none;
  color: inherit;
  transition: border-color 160ms ease-out, transform 160ms ease-out;
}

.dp-card:hover {
  border-color: var(--c-text-3);
  transform: translateY(-2px);
}

.dp-card-num {
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.12em;
  color: var(--c-accent-text);
  margin-bottom: var(--space-md);
  text-transform: uppercase;
}

.dp-card-title {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: clamp(22px, 2.2vw, 28px);
  line-height: 1.15;
  color: var(--c-primary);
  letter-spacing: -0.01em;
  margin: 0 0 var(--space-sm);
}

.dp-card-claim {
  font-family: var(--font-display);
  font-weight: 400;
  font-size: 15px;
  line-height: 1.5;
  color: var(--c-text);
  margin: 0 0 var(--space-lg);
}

.dp-card-more {
  display: inline-flex;
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 500;
  color: var(--c-accent-text);
}

@media (max-width: 900px) {
  .dmcp-row {
    grid-template-columns: 1fr;
    gap: 6px;
  }
  .dmcp-row-head {
    display: none;
  }
  .dp-footer-cards {
    grid-template-columns: 1fr;
  }
}
</style>
