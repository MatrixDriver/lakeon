<template>
  <main class="product-page">
    <!-- 页面标题 -->
    <section class="prod-hero">
      <h1>{{ t('DBay 四大产品模块', 'DBay Four Product Modules') }}</h1>
      <p>{{ t('以 Lakebase 为底座，覆盖知识库、记忆库、AI 数据湖全场景', 'Lakebase-powered platform covering Knowledge Base, Memory Store, and AI Data Lake') }}</p>
    </section>

    <!-- 模块快速导航 -->
    <section class="prod-nav-cards">
      <a href="#memory" class="prod-nav-card prod-nav-memory">
        <span class="prod-badge-new">New</span>
        <div class="prod-card-icon">🧠</div>
        <div class="prod-card-name">{{ t('记忆库', 'Memory Store') }}</div>
      </a>
      <a href="#lakebase" class="prod-nav-card prod-nav-lakebase">
        <div class="prod-card-icon">🐘</div>
        <div class="prod-card-name">Lakebase</div>
      </a>
      <a href="#knowledge" class="prod-nav-card prod-nav-kb">
        <div class="prod-card-icon">📚</div>
        <div class="prod-card-name">{{ t('知识库', 'Knowledge Base') }}</div>
      </a>
      <a href="#datalake" class="prod-nav-card prod-nav-lake">
        <div class="prod-card-icon">🌊</div>
        <div class="prod-card-name">{{ t('AI 数据湖', 'AI Data Lake') }}</div>
      </a>
    </section>

    <!-- ===== 记忆库专题 ===== -->
    <section id="memory" class="prod-section prod-section-memory">
      <div class="prod-section-inner">
        <div class="prod-section-hd">
          <h2>🧠 {{ t('记忆库', 'Memory Store') }} <span class="badge-new">New</span></h2>
          <p>{{ t('为 AI Agent 提供结构化长期记忆，越用越懂你', 'Structured long-term memory for AI Agents — gets smarter with every interaction') }}</p>
        </div>

        <!-- 三个核心操作 -->
        <h3>{{ t('三个核心操作', 'Three Core Operations') }}</h3>
        <p class="sub">{{ t('三个 API 搞定一切——存储、搜索、理解', 'Three APIs for everything — store, search, understand') }}</p>
        <div class="prod-api-grid">
          <div class="prod-api-card" v-for="api in coreApis" :key="api.name">
            <code class="api-name">{{ api.name }}</code>
            <p>{{ api.desc }}</p>
            <p class="api-detail">{{ api.detail }}</p>
          </div>
        </div>

        <!-- 四种记忆类型 -->
        <h3>{{ t('四种记忆类型', 'Four Memory Types') }}</h3>
        <p class="sub">{{ t('四种记忆类型源自认知心理学，各有分工', 'Four memory types from cognitive psychology, each with a distinct role') }}</p>
        <div class="prod-types-grid">
          <div class="prod-type-card" v-for="mt in memoryTypes" :key="mt.icon">
            <span class="type-icon">{{ mt.icon }}</span>
            <div>
              <h4>{{ mt.title }}</h4>
              <p>{{ mt.desc }}</p>
            </div>
          </div>
        </div>

        <!-- 特征生命周期 -->
        <h3>{{ t('特征生命周期', 'Trait Lifecycle') }}</h3>
        <p class="sub">{{ t('特征随证据积累经历 6 个阶段，置信度在被反驳时衰减，被验证时增强', 'Traits evolve through 6 stages as evidence accumulates — confidence decays when contradicted, grows when confirmed') }}</p>
        <div class="trait-lifecycle">
          <div v-for="(stage, i) in traitStages" :key="stage.key" class="trait-stage">
            <div class="trait-dot" :class="{ first: i === 0, last: i === traitStages.length - 1 }"></div>
            <span>{{ stage.label }}</span>
            <div v-if="i < traitStages.length - 1" class="trait-line"></div>
          </div>
        </div>

        <!-- LoCoMo 基准测试 -->
        <h3>{{ t('LoCoMo 基准测试', 'LoCoMo Benchmark') }}</h3>
        <p class="sub">{{ t('基于 LoCoMo 公开长对话记忆基准测试，涵盖单跳、多跳、时间和开放域推理', 'Evaluated on LoCoMo public long-conversation memory benchmark, covering single-hop, multi-hop, temporal, and open-domain reasoning') }}</p>
        <div class="benchmark-score">
          <div class="score-big">81.7<span>%</span></div>
          <p>{{ t('LoCoMo 综合得分', 'LoCoMo Overall Score') }}</p>
        </div>
        <div class="sub-scores">
          <div v-for="s in subScores" :key="s.key" class="sub-score">
            <div class="sub-score-num">{{ s.score }}</div>
            <div class="sub-score-label">{{ s.label }}</div>
          </div>
        </div>
        <div class="benchmark-bars">
          <div v-for="b in benchmarkBars" :key="b.label" class="bar-row">
            <span class="bar-label" :class="{ highlight: b.highlight }">{{ b.label }}</span>
            <div class="bar-track">
              <div class="bar-fill" :class="{ highlight: b.highlight }" :style="{ width: b.score + '%' }"></div>
            </div>
            <span class="bar-score" :class="{ highlight: b.highlight }">{{ b.score }}%</span>
          </div>
        </div>
        <p class="benchmark-note">{{ t('基于公开基准测试，与主流 AI 记忆框架对比', 'Compared with mainstream AI memory frameworks on public benchmarks') }}</p>
      </div>
    </section>

    <!-- ===== Lakebase ===== -->
    <section id="lakebase" class="prod-section">
      <div class="prod-section-inner">
        <div class="prod-section-hd">
          <h2>🐘 Lakebase</h2>
          <p>{{ t('Serverless PostgreSQL，存算分离，自动扩缩容', 'Serverless PostgreSQL with disaggregated storage and auto-scaling') }}</p>
        </div>
        <ul class="prod-feature-list">
          <li>{{ t('3ms 热启动，3s 冷启动', '3ms hot start, 3s cold start') }}</li>
          <li>{{ t('存算分离，自动扩缩容', 'Disaggregated storage, auto-scaling') }}</li>
          <li>{{ t('数据库分支与时间旅行（像 Git 管理数据）', 'Database branching & time travel (Git for data)') }}</li>
          <li>{{ t('多版本管理与回滚', 'Version management & rollback') }}</li>
          <li>{{ t('多租户隔离', 'Multi-tenant isolation') }}</li>
          <li>{{ t('AI SQL 助手（自然语言生成 SQL）', 'AI SQL Assistant (NL to SQL)') }}</li>
        </ul>
      </div>
    </section>

    <!-- ===== 知识库 ===== -->
    <section id="knowledge" class="prod-section">
      <div class="prod-section-inner">
        <div class="prod-section-hd">
          <h2>📚 {{ t('知识库', 'Knowledge Base') }}</h2>
          <p>{{ t('文档 + 表 + 向量检索，内置 Embedding 与 Reranker', 'Documents + Tables + Vector Search, built-in Embedding & Reranker') }}</p>
        </div>
        <ul class="prod-feature-list">
          <li>{{ t('文档自动解析（PDF / Word / Markdown）', 'Auto document parsing (PDF / Word / Markdown)') }}</li>
          <li>{{ t('向量检索（pgvector）', 'Vector search (pgvector)') }}</li>
          <li>{{ t('全文搜索（tsvector / RUM）', 'Full-text search (tsvector / RUM)') }}</li>
          <li>{{ t('表知识库（结构化数据）', 'Table KB (structured data)') }}</li>
          <li>{{ t('向量 + 全文混合检索', 'Hybrid vector + full-text retrieval') }}</li>
          <li>{{ t('内置 Embedding 与 Reranker', 'Built-in embedding & reranker') }}</li>
        </ul>
      </div>
    </section>

    <!-- ===== AI 数据湖 ===== -->
    <section id="datalake" class="prod-section">
      <div class="prod-section-inner">
        <div class="prod-section-hd">
          <h2>🌊 {{ t('AI 数据湖', 'AI Data Lake') }}</h2>
          <p>{{ t('数据处理 + 训练 + 飞轮', 'Data Processing + Training + Flywheel') }}</p>
        </div>
        <ul class="prod-feature-list">
          <li>{{ t('Python / Ray 任务调度', 'Python / Ray task scheduling') }}</li>
          <li>{{ t('Dataset 导出（Parquet）', 'Dataset export (Parquet)') }}</li>
          <li>{{ t('模型微调支持', 'Model fine-tuning support') }}</li>
          <li>{{ t('Kata VM 安全隔离', 'Kata VM security isolation') }}</li>
          <li>{{ t('DB ↔ 数据湖 数据飞轮', 'DB ↔ Data Lake data flywheel') }}</li>
          <li>{{ t('增量 CDC 调度', 'Incremental CDC scheduling') }}</li>
        </ul>
      </div>
    </section>
  </main>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useLocale } from '../../stores/locale'

const { t } = useLocale()

const coreApis = computed(() => [
  {
    name: 'ingest',
    desc: t('将对话和事实存入长期记忆，自动提取实体、偏好和事件', 'Store conversations and facts into long-term memory. Auto-extracts entities, preferences, and events.'),
    detail: t('接收原始对话、文档或结构化事实。通过 LLM 驱动的解析自动提取实体和事件片段，以向量嵌入存储用于语义搜索，同时建立 BM25 关键词索引。', 'Receives raw conversations, documents, or structured facts. LLM-driven parsing extracts entities and events, stored as vector embeddings for semantic search alongside BM25 keyword index.'),
  },
  {
    name: 'recall',
    desc: t('混合检索记忆，结合向量、BM25 和知识图谱', 'Hybrid memory retrieval combining vector search, BM25, and knowledge graph traversal.'),
    detail: t('结合稠密向量搜索、稀疏 BM25 评分和知识图谱遍历。结果经过融合与重排序以获得最佳相关性，支持时间过滤和可配置的 top-k。', 'Combines dense vector search, sparse BM25 scoring, and knowledge graph traversal. Results are fusion-ranked for optimal relevance, with time filtering and configurable top-k.'),
  },
  {
    name: 'digest',
    desc: t('将记忆合成洞察，生成用户画像和行为模式', 'Synthesize memories into insights — user profiles and behavioral patterns.'),
    detail: t('分析累积的记忆生成结构化洞察：行为模式、偏好摘要、情感画像和关系图谱。异步运行并缓存结果以便快速获取。', 'Analyzes accumulated memories to generate structured insights: behavioral patterns, preference summaries, emotional profiles, and relationship maps. Runs asynchronously with cached results.'),
  },
])

const memoryTypes = computed(() => [
  { icon: '📋', title: t('事实', 'Fact'), desc: t('从对话中提取的离散、客观、可验证的信息。以向量嵌入存储并关联知识图谱。', 'Discrete, objective, verifiable information extracted from conversations. Stored as vector embeddings linked to the knowledge graph.') },
  { icon: '🕐', title: t('事件', 'Episode'), desc: t('带有情绪元数据（效价、唤醒度、情绪标签）和时间表达的时间绑定事件和体验。', 'Time-bound events and experiences with emotional metadata (valence, arousal, emotion tags) and temporal expressions.') },
  { icon: '🧠', title: t('特征', 'Trait'), desc: t('通过多会话反思发现的行为模式。经历从趋势到核心的 6 阶段生命周期演化。', 'Behavioral patterns discovered through multi-session reflection. Evolves through a 6-stage lifecycle from trend to core.') },
  { icon: '📄', title: t('文档', 'Document'), desc: t('上传的静态参考资料，用于 RAG 风格检索。支持 PDF 和文件的专项搜索。', 'Uploaded static reference materials for RAG-style retrieval. Supports targeted search over PDFs and files.') },
])

const traitStages = computed(() => [
  { key: 'trend', label: t('趋势', 'Trend') },
  { key: 'candidate', label: t('候选', 'Candidate') },
  { key: 'emerging', label: t('萌发', 'Emerging') },
  { key: 'established', label: t('确立', 'Established') },
  { key: 'core', label: t('核心', 'Core') },
  { key: 'dissolved', label: t('消解', 'Dissolved') },
])

const subScores = computed(() => [
  { key: 'singleHop', score: '82.9%', label: t('单跳', 'Single-hop') },
  { key: 'multiHop', score: '84.3%', label: t('多跳', 'Multi-hop') },
  { key: 'openDomain', score: '81.1%', label: t('开放域', 'Open-domain') },
  { key: 'temporal', score: '76.6%', label: t('时间推理', 'Temporal') },
])

const benchmarkBars = [
  { label: 'DBay 记忆库', score: 81.7, highlight: true },
  { label: 'Framework A', score: 75.8, highlight: false },
  { label: 'Framework B', score: 75.1, highlight: false },
  { label: 'Framework C', score: 68.4, highlight: false },
  { label: 'Framework D', score: 66.9, highlight: false },
]
</script>

<style scoped>
.product-page { min-height: 100vh; background: #0a0a0a; color: #e5e5e5; }
.prod-hero { max-width: 800px; margin: 0 auto; padding: 48px 24px 32px; text-align: center; }
.prod-hero h1 { font-size: 32px; font-weight: 700; margin-bottom: 12px; }
.prod-hero p { color: #888; font-size: 16px; }

.prod-nav-cards {
  max-width: 900px; margin: 0 auto 0; padding: 0 24px 32px;
  display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px;
}
.prod-nav-card {
  border-radius: 10px; padding: 16px; text-decoration: none;
  text-align: center; position: relative; transition: transform 0.15s;
  border: 1px solid #2a2a2a; background: #111;
}
.prod-nav-card:hover { transform: translateY(-2px); }
.prod-nav-memory { border-color: #7c3aed; background: #1a1a2e; }
.prod-nav-lakebase { border-color: #0052a3; }
.prod-nav-kb { border-color: #5b21b6; }
.prod-nav-lake { border-color: #0e7490; }
.prod-card-icon { font-size: 24px; margin-bottom: 6px; }
.prod-card-name { font-size: 13px; font-weight: 600; color: #e5e5e5; }
.prod-badge-new {
  position: absolute; top: 8px; right: 8px;
  font-size: 9px; background: #7c3aed; color: #fff;
  padding: 2px 5px; border-radius: 3px;
}

.prod-section { padding: 64px 24px; border-top: 1px solid #1a1a1a; }
.prod-section-memory { background: #0d0d18; }
.prod-section-inner { max-width: 800px; margin: 0 auto; }
.prod-section-hd { margin-bottom: 40px; }
.prod-section-hd h2 { font-size: 26px; font-weight: 700; margin-bottom: 8px; }
.prod-section-hd p { color: #888; font-size: 15px; }
.badge-new {
  font-size: 11px; background: #7c3aed; color: #fff;
  padding: 2px 7px; border-radius: 4px; margin-left: 8px; vertical-align: middle;
}

h3 { font-size: 18px; font-weight: 600; margin: 32px 0 6px; }
.sub { color: #888; font-size: 14px; margin-bottom: 16px; }

.prod-api-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-bottom: 8px; }
.prod-api-card {
  background: #0a0a14; border: 1px solid #2a2a3a;
  border-radius: 8px; padding: 16px;
}
.api-name { font-family: monospace; font-size: 15px; color: #60a5fa; display: block; margin-bottom: 8px; }
.prod-api-card p { font-size: 13px; color: #888; margin: 0 0 8px; }
.api-detail { font-size: 11px; color: #555; }

.prod-types-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; }
.prod-type-card {
  display: flex; gap: 12px; align-items: flex-start;
  background: #0a0a14; border: 1px solid #2a2a3a;
  border-radius: 8px; padding: 14px;
}
.type-icon { font-size: 22px; flex-shrink: 0; }
.prod-type-card h4 { font-size: 14px; font-weight: 600; margin: 0 0 4px; }
.prod-type-card p { font-size: 12px; color: #888; margin: 0; }

.trait-lifecycle {
  display: flex; align-items: center; justify-content: space-between;
  max-width: 600px; margin: 16px 0;
}
.trait-stage { display: flex; align-items: center; flex-direction: column; position: relative; }
.trait-dot { width: 12px; height: 12px; border-radius: 50%; background: #7c3aed; }
.trait-dot.first { background: #a78bfa; }
.trait-dot.last { background: #444; border: 1px dashed #666; }
.trait-stage span { font-size: 10px; color: #666; margin-top: 6px; white-space: nowrap; }
.trait-line { width: 40px; height: 1px; background: #2a2a3a; margin-bottom: 18px; }

.benchmark-score { text-align: center; margin: 16px 0 8px; }
.score-big { font-size: 64px; font-weight: 700; color: #fff; line-height: 1; }
.score-big span { font-size: 36px; color: #888; }
.benchmark-score p { color: #888; font-size: 14px; margin: 4px 0 0; }

.sub-scores { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin: 16px 0; }
.sub-score {
  text-align: center; background: #0a0a14;
  border: 1px solid #2a2a3a; border-radius: 8px; padding: 12px;
}
.sub-score-num { font-size: 20px; font-weight: 600; }
.sub-score-label { font-size: 11px; color: #888; margin-top: 2px; }

.benchmark-bars { display: flex; flex-direction: column; gap: 8px; max-width: 500px; margin: 16px 0; }
.bar-row { display: flex; align-items: center; gap: 10px; }
.bar-label { font-size: 12px; width: 90px; text-align: right; color: #666; }
.bar-label.highlight { color: #e5e5e5; font-weight: 600; }
.bar-track { flex: 1; background: #1a1a1a; border-radius: 4px; height: 16px; overflow: hidden; }
.bar-fill { height: 100%; background: #444; border-radius: 4px; }
.bar-fill.highlight { background: #a78bfa; }
.bar-score { font-size: 12px; width: 36px; color: #666; }
.bar-score.highlight { color: #e5e5e5; font-weight: 600; }
.benchmark-note { font-size: 11px; color: #555; margin-top: 8px; }

.prod-feature-list { list-style: none; padding: 0; margin: 16px 0 0; display: flex; flex-direction: column; gap: 10px; }
.prod-feature-list li {
  padding: 10px 14px; background: #111; border: 1px solid #222;
  border-radius: 6px; font-size: 14px; color: #ccc;
}
.prod-feature-list li::before { content: '✓'; color: #7c3aed; margin-right: 8px; }

@media (max-width: 768px) {
  .prod-nav-cards { grid-template-columns: repeat(2, 1fr); }
  .prod-api-grid { grid-template-columns: 1fr; }
  .sub-scores { grid-template-columns: repeat(2, 1fr); }
}
</style>
