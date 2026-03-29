<template>
  <main class="product-page">
    <!-- Hero -->
    <div class="hero">
      <div class="hero-inner">
        <div class="hero-marker"></div>
        <h1 class="hero-title">{{ t('记忆库', 'Memory Store') }}</h1>
        <p class="hero-subtitle">
          {{ t('为 AI Agent 提供结构化长期记忆，越用越懂你', 'Structured long-term memory for AI Agents — gets smarter with every interaction') }}
        </p>
      </div>
    </div>

    <div class="content">

      <!-- Section 1: Three Core Operations -->
      <section class="section">
        <h2 class="section-title">{{ t('三个核心操作', 'Three Core Operations') }}</h2>
        <div class="ops-list">
          <div class="ops-item" v-for="op in coreOps" :key="op.name">
            <div class="ops-header">
              <span class="ops-name">{{ op.name }}</span>
              <span class="ops-summary">{{ t(op.descZh, op.descEn) }}</span>
            </div>
            <div class="ops-detail">{{ t(op.detailZh, op.detailEn) }}</div>
          </div>
        </div>
      </section>

      <!-- Section 2: Four Memory Types -->
      <section class="section">
        <h2 class="section-title">{{ t('四种记忆类型', 'Four Memory Types') }}</h2>
        <div class="types-list">
          <div class="type-item" v-for="mt in memoryTypes" :key="mt.badge">
            <div class="type-badge">{{ mt.badge }}</div>
            <div class="type-body">
              <div class="type-title">{{ t(mt.zh, mt.en) }}</div>
              <div class="type-desc">{{ t(mt.descZh, mt.descEn) }}</div>
            </div>
          </div>
        </div>
      </section>

      <!-- Section 3: Trait Lifecycle -->
      <section class="section">
        <h2 class="section-title">{{ t('特征生命周期', 'Trait Lifecycle') }}</h2>
        <div class="lifecycle">
          <div
            v-for="(step, i) in lifecycleSteps"
            :key="step.zh"
            class="lifecycle-step"
          >
            <div class="step-dot" :class="{ 'dot-hollow': i === lifecycleSteps.length - 1 }"></div>
            <div class="step-label">{{ t(step.zh, step.en) }}</div>
            <div v-if="i < lifecycleSteps.length - 1" class="step-line"></div>
          </div>
        </div>
      </section>

      <!-- Section 4: LoCoMo Benchmark -->
      <section class="section">
        <h2 class="section-title">{{ t('LoCoMo 基准测试', 'LoCoMo Benchmark') }}</h2>
        <div class="benchmark">
          <div class="benchmark-score-block">
            <div class="benchmark-main-score">81.7%</div>
            <div class="benchmark-score-label">{{ t('综合得分', 'Overall Score') }}</div>
          </div>
          <div class="benchmark-sub-scores">
            <div class="sub-score" v-for="sub in subScores" :key="sub.labelEn">
              <div class="sub-score-value">{{ sub.value }}</div>
              <div class="sub-score-label">{{ t(sub.labelZh, sub.labelEn) }}</div>
            </div>
          </div>
          <div class="benchmark-bars">
            <div
              class="bar-row"
              v-for="bar in comparisonBars"
              :key="bar.name"
              :class="{ 'bar-highlighted': bar.highlighted }"
            >
              <div class="bar-label">{{ bar.name }}</div>
              <div class="bar-track">
                <div class="bar-fill" :style="{ width: bar.pct }"></div>
              </div>
              <div class="bar-value">{{ bar.value }}</div>
            </div>
          </div>
          <div class="benchmark-note">
            {{ t('基于公开基准测试，与主流 AI 记忆框架对比', 'Compared with mainstream AI memory frameworks on public benchmarks') }}
          </div>
        </div>
      </section>

      <!-- CTA -->
      <div class="cta-section">
        <a href="/console" class="btn-cta">{{ t('立即试用', 'Get Started') }}</a>
        <router-link to="/product" class="btn-back">{{ t('返回产品总览', 'Back to Products') }}</router-link>
      </div>

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
    badge: 'FACT',
    zh: '事实 / Fact',
    en: 'Fact',
    descZh: '从对话中提取的离散、客观、可验证的信息。以向量嵌入存储并关联知识图谱。',
    descEn: 'Discrete, objective, verifiable information extracted from conversations. Stored as vector embeddings linked to the knowledge graph.',
  },
  {
    badge: 'EVENT',
    zh: '事件 / Episode',
    en: 'Episode',
    descZh: '带有情绪元数据（效价、唤醒度、情绪标签）和时间表达的时间绑定事件和体验。',
    descEn: 'Time-bound events and experiences with emotional metadata (valence, arousal, emotion tags) and temporal expressions.',
  },
  {
    badge: 'TRAIT',
    zh: '特征 / Trait',
    en: 'Trait',
    descZh: '通过多会话反思发现的行为模式。经历从趋势到核心的 6 阶段生命周期演化。',
    descEn: 'Behavioral patterns discovered through multi-session reflection. Evolves through a 6-stage lifecycle from trend to core.',
  },
  {
    badge: 'DOC',
    zh: '文档 / Document',
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
  background: var(--pub-bg);
  font-family: var(--pub-sans, 'DM Sans', sans-serif);
}

/* Hero */
.hero {
  border-top: 3px solid #c67d3a;
  background: var(--pub-surface);
  border-bottom: 1px solid var(--pub-border);
  padding: clamp(40px, 6vw, 64px) 24px clamp(32px, 5vw, 48px);
}

.hero-inner {
  max-width: 800px;
  margin: 0 auto;
}

.hero-marker {
  width: 32px;
  height: 3px;
  background: #c67d3a;
  margin-bottom: 20px;
}

.hero-title {
  font-family: var(--pub-serif, 'Noto Serif SC', Georgia, serif);
  font-size: clamp(1.75rem, 4vw, 2.5rem);
  font-weight: 700;
  color: var(--pub-text);
  margin: 0 0 12px;
  line-height: 1.2;
}

.hero-subtitle {
  font-family: var(--pub-sans, 'DM Sans', sans-serif);
  font-size: clamp(0.95rem, 1.5vw, 1.1rem);
  color: var(--pub-text-2);
  margin: 0;
  line-height: 1.6;
}

/* Content wrapper */
.content {
  max-width: 800px;
  margin: 0 auto;
  padding: 40px 24px 64px;
  display: flex;
  flex-direction: column;
  gap: 40px;
}

/* Section */
.section {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.section-title {
  font-family: var(--pub-serif, 'Noto Serif SC', Georgia, serif);
  font-size: clamp(1.1rem, 2vw, 1.3rem);
  font-weight: 700;
  color: var(--pub-text);
  margin: 0;
}

/* Section 1: Core Operations */
.ops-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ops-item {
  padding: 20px 24px;
  border-left: 3px solid #c67d3a;
  background: var(--pub-surface);
  display: flex;
  flex-direction: column;
  gap: 10px;
  transition: background 0.15s ease;
}

.ops-item:hover {
  background: var(--pub-hover);
}

.ops-header {
  display: flex;
  align-items: baseline;
  gap: 12px;
  flex-wrap: wrap;
}

.ops-name {
  font-family: var(--pub-code, 'JetBrains Mono', 'Fira Code', monospace);
  font-size: clamp(0.9rem, 1.2vw, 1rem);
  font-weight: 700;
  color: #c67d3a;
}

.ops-summary {
  font-family: var(--pub-sans, 'DM Sans', sans-serif);
  font-size: clamp(0.8rem, 1.1vw, 0.9rem);
  font-weight: 600;
  color: var(--pub-text);
  line-height: 1.5;
}

.ops-detail {
  font-family: var(--pub-sans, 'DM Sans', sans-serif);
  font-size: clamp(0.78rem, 1vw, 0.85rem);
  color: var(--pub-text-2);
  line-height: 1.7;
}

/* Section 2: Memory types */
.types-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.type-item {
  padding: 20px 24px;
  border-left: 3px solid #c67d3a;
  background: var(--pub-surface);
  display: flex;
  gap: 16px;
  align-items: flex-start;
  transition: background 0.15s ease;
}

.type-item:hover {
  background: var(--pub-hover);
}

.type-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 3px 10px;
  background: #c67d3a;
  color: #fff;
  font-family: var(--pub-sans, 'DM Sans', sans-serif);
  font-size: clamp(0.65rem, 0.9vw, 0.72rem);
  font-weight: 700;
  letter-spacing: 0.06em;
  border-radius: 3px;
  flex-shrink: 0;
  margin-top: 2px;
}

.type-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.type-title {
  font-family: var(--pub-sans, 'DM Sans', sans-serif);
  font-size: clamp(0.9rem, 1.2vw, 1rem);
  font-weight: 600;
  color: var(--pub-text);
}

.type-desc {
  font-family: var(--pub-sans, 'DM Sans', sans-serif);
  font-size: clamp(0.8rem, 1.1vw, 0.88rem);
  color: var(--pub-text-2);
  line-height: 1.6;
}

/* Section 3: Trait lifecycle */
.lifecycle {
  display: flex;
  align-items: flex-start;
  flex-wrap: nowrap;
  overflow-x: auto;
  padding-bottom: 8px;
  gap: 0;
}

.lifecycle-step {
  display: flex;
  flex-direction: column;
  align-items: center;
  position: relative;
  flex: 1;
  min-width: 80px;
}

.step-dot {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #c67d3a;
  border: 2px solid #c67d3a;
  flex-shrink: 0;
  position: relative;
  z-index: 1;
}

.dot-hollow {
  background: transparent;
  border-style: dashed;
}

.step-line {
  position: absolute;
  top: 6px;
  left: calc(50% + 7px);
  right: calc(-50% + 7px);
  height: 2px;
  background: #c67d3a;
  opacity: 0.35;
  z-index: 0;
}

.step-label {
  margin-top: 10px;
  font-family: var(--pub-sans, 'DM Sans', sans-serif);
  font-size: clamp(0.7rem, 0.9vw, 0.8rem);
  font-weight: 600;
  color: var(--pub-text-2);
  text-align: center;
  white-space: nowrap;
}

/* Section 4: Benchmark */
.benchmark {
  background: var(--pub-surface);
  border: 1px solid var(--pub-border);
  border-radius: 8px;
  padding: 28px 24px;
  display: flex;
  flex-direction: column;
  gap: 24px;
}

.benchmark-score-block {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.benchmark-main-score {
  font-family: var(--pub-serif, 'Noto Serif SC', Georgia, serif);
  font-size: clamp(2.5rem, 6vw, 3.5rem);
  font-weight: 800;
  color: #c67d3a;
  line-height: 1;
}

.benchmark-score-label {
  font-family: var(--pub-sans, 'DM Sans', sans-serif);
  font-size: clamp(0.75rem, 1vw, 0.85rem);
  color: var(--pub-text-3);
  letter-spacing: 0.04em;
  text-transform: uppercase;
}

.benchmark-sub-scores {
  display: flex;
  gap: 12px;
  justify-content: center;
  flex-wrap: wrap;
}

.sub-score {
  background: var(--pub-bg-alt);
  border: 1px solid var(--pub-border);
  border-radius: 6px;
  padding: 10px 16px;
  text-align: center;
  min-width: 100px;
}

.sub-score-value {
  font-family: var(--pub-sans, 'DM Sans', sans-serif);
  font-size: clamp(1rem, 1.5vw, 1.2rem);
  font-weight: 700;
  color: var(--pub-text);
}

.sub-score-label {
  font-family: var(--pub-sans, 'DM Sans', sans-serif);
  font-size: clamp(0.7rem, 0.9vw, 0.78rem);
  color: var(--pub-text-3);
  margin-top: 2px;
}

.benchmark-bars {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.bar-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.bar-label {
  font-family: var(--pub-sans, 'DM Sans', sans-serif);
  font-size: clamp(0.78rem, 1vw, 0.85rem);
  color: var(--pub-text-2);
  width: 110px;
  flex-shrink: 0;
}

.bar-highlighted .bar-label {
  font-weight: 700;
  color: var(--pub-text);
}

.bar-track {
  flex: 1;
  height: 10px;
  background: var(--pub-bg-alt);
  border-radius: 5px;
  overflow: hidden;
}

.bar-fill {
  height: 100%;
  background: var(--pub-text-3);
  border-radius: 5px;
  transition: width 0.4s ease;
}

.bar-highlighted .bar-fill {
  background: #c67d3a;
}

.bar-value {
  font-family: var(--pub-sans, 'DM Sans', sans-serif);
  font-size: clamp(0.78rem, 1vw, 0.85rem);
  font-weight: 600;
  color: var(--pub-text-2);
  width: 48px;
  text-align: right;
  flex-shrink: 0;
}

.bar-highlighted .bar-value {
  color: #c67d3a;
  font-weight: 700;
}

.benchmark-note {
  font-family: var(--pub-sans, 'DM Sans', sans-serif);
  font-size: clamp(0.72rem, 0.9vw, 0.8rem);
  color: var(--pub-text-4);
  text-align: center;
  line-height: 1.5;
}

/* CTA */
.cta-section {
  display: flex;
  align-items: center;
  gap: 24px;
  flex-wrap: wrap;
}

.btn-cta {
  display: inline-block;
  padding: 10px 24px;
  background: var(--pub-btn-bg, #1e2d3d);
  color: #fff;
  font-family: var(--pub-sans, 'DM Sans', sans-serif);
  font-size: clamp(0.85rem, 1.1vw, 0.95rem);
  font-weight: 600;
  text-decoration: none;
  border-radius: 6px;
  transition: opacity 0.15s ease;
}

.btn-cta:hover {
  opacity: 0.85;
}

.btn-back {
  font-family: var(--pub-sans, 'DM Sans', sans-serif);
  font-size: clamp(0.8rem, 1vw, 0.9rem);
  color: var(--pub-text-3);
  text-decoration: none;
  transition: color 0.15s ease;
}

.btn-back:hover {
  color: var(--pub-accent, #c67d3a);
}

/* Responsive */
@media (max-width: 700px) {
  .ops-item {
    padding: 16px;
  }

  .type-item {
    padding: 16px;
  }

  .lifecycle-step {
    min-width: 60px;
  }

  .step-label {
    font-size: 0.7rem;
  }

  .cta-section {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
