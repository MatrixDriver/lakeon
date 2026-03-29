<template>
  <main class="product-page">
    <div class="accent-line"></div>

    <div class="content">
      <!-- Hero -->
      <section class="hero">
        <h1 class="page-title">{{ t('记忆库', 'Memory Store') }}</h1>
        <p class="page-subtitle">{{ t('为 AI Agent 提供结构化长期记忆，越用越懂你', 'Structured long-term memory for AI Agents — gets smarter with every interaction') }}</p>
      </section>

      <!-- Section 1: Core Operations -->
      <section class="section">
        <h2 class="section-title">{{ t('三个核心操作', 'Three Core Operations') }}</h2>
        <div class="ops-list">
          <div v-for="op in coreOps" :key="op.name" class="op-item">
            <div class="op-header">
              <span class="op-name">{{ op.name }}</span>
              <span class="op-summary">{{ t(op.descZh, op.descEn) }}</span>
            </div>
            <p class="op-detail">{{ t(op.detailZh, op.detailEn) }}</p>
          </div>
        </div>
      </section>

      <hr class="divider">

      <!-- Section 2: Memory Types -->
      <section class="section">
        <h2 class="section-title">{{ t('四种记忆类型', 'Four Memory Types') }}</h2>
        <div class="types-list">
          <div v-for="mt in memoryTypes" :key="mt.label" class="type-item">
            <span class="type-badge">{{ mt.label }}</span>
            <div class="type-body">
              <div class="type-name">{{ t(mt.zh, mt.en) }}</div>
              <p class="type-desc">{{ t(mt.descZh, mt.descEn) }}</p>
            </div>
          </div>
        </div>
      </section>

      <hr class="divider">

      <!-- Section 3: Trait Lifecycle -->
      <section class="section">
        <h2 class="section-title">{{ t('特征生命周期', 'Trait Lifecycle') }}</h2>
        <div class="lifecycle">
          <template v-for="(step, i) in lifecycleSteps" :key="step.en">
            <div class="lifecycle-node">
              <div class="lifecycle-dot" :class="{ 'dot-hollow': i === lifecycleSteps.length - 1 }"></div>
              <div class="lifecycle-label">{{ t(step.zh, step.en) }}</div>
            </div>
            <div v-if="i < lifecycleSteps.length - 1" class="lifecycle-line"></div>
          </template>
        </div>
      </section>

      <hr class="divider">

      <!-- Section 4: Benchmark -->
      <section class="section">
        <h2 class="section-title">{{ t('LoCoMo 基准测试', 'LoCoMo Benchmark') }}</h2>

        <div class="benchmark-hero">
          <div class="benchmark-score">81.7%</div>
          <div class="benchmark-score-label">{{ t('综合得分', 'Overall Score') }}</div>
        </div>

        <div class="benchmark-subs">
          <div v-for="sub in subScores" :key="sub.labelEn" class="benchmark-sub">
            <span class="sub-value">{{ sub.value }}</span>
            <span class="sub-label">{{ t(sub.labelZh, sub.labelEn) }}</span>
          </div>
        </div>

        <div class="benchmark-bars">
          <div
            v-for="bar in comparisonBars"
            :key="bar.name"
            class="bar-row"
            :class="{ 'bar-highlighted': bar.highlighted }"
          >
            <span class="bar-name">{{ bar.name }}</span>
            <div class="bar-track">
              <div class="bar-fill" :style="{ width: bar.pct }"></div>
            </div>
            <span class="bar-value">{{ bar.value }}</span>
          </div>
        </div>

        <p class="benchmark-note">{{ t('基于公开基准测试，与主流 AI 记忆框架对比', 'Compared with mainstream AI memory frameworks on public benchmarks') }}</p>
      </section>

      <!-- CTA -->
      <section class="cta">
        <a href="/console" class="btn-primary">{{ t('立即试用', 'Get Started') }} &rarr;</a>
        <router-link to="/product" class="back-link">{{ t('返回产品总览', 'Back to Products') }}</router-link>
      </section>
    </div>
  </main>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'

const { t } = useLocale()

const coreOps = [
  {
    name: 'ingest',
    descZh: '将对话和事实存入长期记忆，自动提取实体、偏好和事件',
    descEn: 'Store conversations and facts into long-term memory. Auto-extracts entities, preferences, and events.',
    detailZh: '接收原始对话、文档或结构化事实。通过 LLM 驱动的解析自动提取实体和事件片段，以向量嵌入存储用于语义搜索，同时建立 BM25 关键词索引。',
    detailEn: 'Receives raw conversations, documents, or structured facts. LLM-driven parsing extracts entities and events, stored as vector embeddings for semantic search alongside BM25 keyword index.',
  },
  {
    name: 'recall',
    descZh: '混合检索记忆，结合向量、BM25 和知识图谱',
    descEn: 'Hybrid memory retrieval combining vector search, BM25, and knowledge graph traversal.',
    detailZh: '结合稠密向量搜索、稀疏 BM25 评分和知识图谱遍历。结果经过融合与重排序以获得最佳相关性，支持时间过滤和可配置的 top-k。',
    detailEn: 'Combines dense vector search, sparse BM25 scoring, and knowledge graph traversal. Results are fusion-ranked for optimal relevance, with time filtering and configurable top-k.',
  },
  {
    name: 'digest',
    descZh: '将记忆合成洞察，生成用户画像和行为模式',
    descEn: 'Synthesize memories into insights — user profiles and behavioral patterns.',
    detailZh: '分析累积的记忆生成结构化洞察：行为模式、偏好摘要、情感画像和关系图谱。异步运行并缓存结果以便快速获取。',
    detailEn: 'Analyzes accumulated memories to generate structured insights: behavioral patterns, preference summaries, emotional profiles, and relationship maps. Runs asynchronously with cached results.',
  },
]

const memoryTypes = [
  {
    label: 'FACT',
    zh: '事实',
    en: 'Fact',
    descZh: '从对话中提取的离散、客观、可验证的信息。以向量嵌入存储并关联知识图谱。',
    descEn: 'Discrete, objective, verifiable information extracted from conversations. Stored as vector embeddings linked to the knowledge graph.',
  },
  {
    label: 'EVENT',
    zh: '事件',
    en: 'Episode',
    descZh: '带有情绪元数据（效价、唤醒度、情绪标签）和时间表达的时间绑定事件和体验。',
    descEn: 'Time-bound events and experiences with emotional metadata (valence, arousal, emotion tags) and temporal expressions.',
  },
  {
    label: 'TRAIT',
    zh: '特征',
    en: 'Trait',
    descZh: '通过多会话反思发现的行为模式。经历从趋势到核心的 6 阶段生命周期演化。',
    descEn: 'Behavioral patterns discovered through multi-session reflection. Evolves through a 6-stage lifecycle from trend to core.',
  },
  {
    label: 'DOC',
    zh: '文档',
    en: 'Document',
    descZh: '上传的静态参考资料，用于 RAG 风格检索。支持 PDF 和文件的专项搜索。',
    descEn: 'Uploaded static reference materials for RAG-style retrieval. Supports targeted search over PDFs and files.',
  },
]

const lifecycleSteps = [
  { zh: '趋势', en: 'Trend' },
  { zh: '候选', en: 'Candidate' },
  { zh: '萌发', en: 'Emerging' },
  { zh: '确立', en: 'Established' },
  { zh: '核心', en: 'Core' },
  { zh: '消解', en: 'Dissolved' },
]

const subScores = [
  { value: '82.9%', labelZh: 'Single-hop', labelEn: 'Single-hop' },
  { value: '84.3%', labelZh: 'Multi-hop', labelEn: 'Multi-hop' },
  { value: '81.1%', labelZh: 'Open-domain', labelEn: 'Open-domain' },
  { value: '76.6%', labelZh: 'Temporal', labelEn: 'Temporal' },
]

const comparisonBars = [
  { name: 'DBay', value: '81.7%', pct: '81.7%', highlighted: true },
  { name: 'Framework A', value: '75.8%', pct: '75.8%', highlighted: false },
  { name: 'Framework B', value: '75.1%', pct: '75.1%', highlighted: false },
  { name: 'Framework C', value: '68.4%', pct: '68.4%', highlighted: false },
  { name: 'Framework D', value: '66.9%', pct: '66.9%', highlighted: false },
]
</script>

<style scoped>
.product-page {
  min-height: 100vh;
  background: var(--pub-bg, #fafaf7);
  color: var(--pub-text, #18181b);
}

.accent-line {
  height: 2px;
  background: #92400e;
}

.content {
  max-width: 680px;
  margin: 0 auto;
  padding: 0 32px;
}

/* Hero */
.hero {
  padding: 80px 0 64px;
}

.page-title {
  font-family: var(--pub-serif, 'Cormorant Garamond', Georgia, serif);
  font-size: 44px;
  font-weight: 600;
  line-height: 1.1;
  margin: 0 0 14px;
  color: var(--pub-text, #18181b);
}

.page-subtitle {
  font-family: var(--pub-sans, 'Plus Jakarta Sans', sans-serif);
  font-size: 16px;
  line-height: 1.7;
  color: var(--pub-text-2, #52525b);
  margin: 0;
}

/* Sections */
.section {
  padding: 48px 0;
}

.section-title {
  font-family: var(--pub-serif, 'Cormorant Garamond', Georgia, serif);
  font-size: 28px;
  font-weight: 600;
  margin: 0 0 32px;
  color: var(--pub-text, #18181b);
}

.divider {
  border: none;
  border-top: 1px solid var(--pub-border, #e4e4e7);
  margin: 0;
}

/* Core Operations */
.ops-list {
  display: flex;
  flex-direction: column;
  gap: 28px;
}

.op-item {
  padding-left: 20px;
  border-left: 2px solid #92400e;
}

.op-header {
  display: flex;
  align-items: baseline;
  gap: 14px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.op-name {
  font-family: var(--pub-code, 'JetBrains Mono', 'Fira Code', monospace);
  font-size: 16px;
  font-weight: 700;
  color: #92400e;
}

.op-summary {
  font-family: var(--pub-sans, 'Plus Jakarta Sans', sans-serif);
  font-size: 14px;
  font-weight: 600;
  color: var(--pub-text, #18181b);
}

.op-detail {
  font-family: var(--pub-sans, 'Plus Jakarta Sans', sans-serif);
  font-size: 14px;
  line-height: 1.75;
  color: var(--pub-text-2, #52525b);
  margin: 0;
}

/* Memory Types */
.types-list {
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.type-item {
  display: flex;
  gap: 16px;
  align-items: flex-start;
}

.type-badge {
  display: inline-block;
  padding: 3px 10px;
  background: #92400e;
  color: #fff;
  font-family: var(--pub-sans, 'Plus Jakarta Sans', sans-serif);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.06em;
  border-radius: 3px;
  flex-shrink: 0;
  margin-top: 3px;
}

.type-body {
  flex: 1;
}

.type-name {
  font-family: var(--pub-sans, 'Plus Jakarta Sans', sans-serif);
  font-size: 15px;
  font-weight: 700;
  color: var(--pub-text, #18181b);
  margin-bottom: 4px;
}

.type-desc {
  font-family: var(--pub-sans, 'Plus Jakarta Sans', sans-serif);
  font-size: 14px;
  line-height: 1.7;
  color: var(--pub-text-2, #52525b);
  margin: 0;
}

/* Lifecycle */
.lifecycle {
  display: flex;
  align-items: flex-start;
  gap: 0;
  overflow-x: auto;
  padding-bottom: 8px;
}

.lifecycle-node {
  display: flex;
  flex-direction: column;
  align-items: center;
  flex-shrink: 0;
}

.lifecycle-dot {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #92400e;
  border: 2px solid #92400e;
}

.dot-hollow {
  background: transparent;
  border-style: dashed;
}

.lifecycle-label {
  margin-top: 10px;
  font-family: var(--pub-sans, 'Plus Jakarta Sans', sans-serif);
  font-size: 13px;
  font-weight: 600;
  color: var(--pub-text-2, #52525b);
  white-space: nowrap;
}

.lifecycle-line {
  flex: 1;
  min-width: 32px;
  height: 2px;
  background: #92400e;
  opacity: 0.35;
  margin-top: 6px;
  align-self: flex-start;
}

/* Benchmark */
.benchmark-hero {
  text-align: left;
  margin-bottom: 24px;
}

.benchmark-score {
  font-family: var(--pub-serif, 'Cormorant Garamond', Georgia, serif);
  font-size: 64px;
  font-weight: 700;
  line-height: 1;
  color: #92400e;
}

.benchmark-score-label {
  font-family: var(--pub-sans, 'Plus Jakarta Sans', sans-serif);
  font-size: 13px;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--pub-text-3, #a1a1aa);
  margin-top: 4px;
}

.benchmark-subs {
  display: flex;
  gap: 24px;
  flex-wrap: wrap;
  margin-bottom: 32px;
}

.benchmark-sub {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.sub-value {
  font-family: var(--pub-sans, 'Plus Jakarta Sans', sans-serif);
  font-size: 18px;
  font-weight: 700;
  color: var(--pub-text, #18181b);
}

.sub-label {
  font-family: var(--pub-sans, 'Plus Jakarta Sans', sans-serif);
  font-size: 12px;
  color: var(--pub-text-3, #a1a1aa);
}

.benchmark-bars {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 20px;
}

.bar-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.bar-name {
  font-family: var(--pub-sans, 'Plus Jakarta Sans', sans-serif);
  font-size: 14px;
  color: var(--pub-text-2, #52525b);
  width: 110px;
  flex-shrink: 0;
}

.bar-highlighted .bar-name {
  font-weight: 700;
  color: var(--pub-text, #18181b);
}

.bar-track {
  flex: 1;
  height: 8px;
  background: var(--pub-border, #e4e4e7);
  border-radius: 4px;
  overflow: hidden;
}

.bar-fill {
  height: 100%;
  background: var(--pub-text-3, #a1a1aa);
  border-radius: 4px;
  transition: width 0.4s ease;
}

.bar-highlighted .bar-fill {
  background: #92400e;
}

.bar-value {
  font-family: var(--pub-sans, 'Plus Jakarta Sans', sans-serif);
  font-size: 14px;
  font-weight: 600;
  color: var(--pub-text-2, #52525b);
  width: 48px;
  text-align: right;
  flex-shrink: 0;
}

.bar-highlighted .bar-value {
  color: #92400e;
  font-weight: 700;
}

.benchmark-note {
  font-family: var(--pub-sans, 'Plus Jakarta Sans', sans-serif);
  font-size: 13px;
  color: var(--pub-text-3, #a1a1aa);
  line-height: 1.6;
}

/* CTA */
.cta {
  display: flex;
  align-items: center;
  gap: 28px;
  padding: 16px 0 96px;
}

.btn-primary {
  display: inline-block;
  padding: 14px 36px;
  background: var(--pub-btn-bg, #18181b);
  color: #fff;
  font-family: var(--pub-sans, 'Plus Jakarta Sans', sans-serif);
  font-size: 15px;
  font-weight: 600;
  text-decoration: none;
  border-radius: 6px;
  transition: opacity 0.15s;
}

.btn-primary:hover {
  opacity: 0.85;
}

.back-link {
  font-family: var(--pub-sans, 'Plus Jakarta Sans', sans-serif);
  font-size: 14px;
  color: var(--pub-text-3, #a1a1aa);
  text-decoration: none;
  transition: color 0.15s;
}

.back-link:hover {
  color: var(--pub-text, #18181b);
}

/* Responsive */
@media (max-width: 600px) {
  .content {
    padding: 0 24px;
  }

  .hero {
    padding: 56px 0 48px;
  }

  .page-title {
    font-size: 34px;
  }

  .benchmark-score {
    font-size: 48px;
  }

  .benchmark-subs {
    gap: 16px;
  }

  .lifecycle-line {
    min-width: 20px;
  }

  .cta {
    flex-direction: column;
    align-items: flex-start;
    gap: 16px;
  }
}
</style>
