<template>
  <main class="product-detail">
    <div class="hero">
      <div class="hero-inner">
        <h1 class="hero-title">{{ t('知识库', 'Knowledge Base') }}</h1>
        <p class="hero-subtitle">
          {{ t('文档 + 表 + 向量检索，内置 Embedding 与 Reranker', 'Documents + Tables + Vector Search, built-in Embedding & Reranker') }}
        </p>
      </div>
    </div>

    <div class="content">
      <div class="feature-list">
        <div class="feature-item" v-for="feature in features" :key="feature.en">
          <div class="feature-body">
            <div class="feature-title">{{ t(feature.zh, feature.en) }}</div>
            <div class="feature-desc">{{ t(feature.descZh, feature.descEn) }}</div>
          </div>
        </div>
      </div>

      <div class="cta-section">
        <a href="/console" class="cta-button">{{ t('立即试用', 'Get Started') }}</a>
        <router-link to="/product" class="back-link">
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m15 18-6-6 6-6"/></svg>
          {{ t('返回产品总览', 'Back to Products') }}
        </router-link>
      </div>
    </div>
  </main>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'

const { t } = useLocale()

const features = [
  {
    zh: '文档自动解析',
    en: 'Doc Parsing',
    descZh: 'PDF / Word / Markdown 自动解析',
    descEn: 'Auto parsing for PDF, Word, Markdown',
  },
  {
    zh: '向量检索',
    en: 'Vector Search',
    descZh: '基于 pgvector 的语义搜索',
    descEn: 'Semantic search powered by pgvector',
  },
  {
    zh: '全文搜索',
    en: 'Full-text Search',
    descZh: 'tsvector / RUM 索引',
    descEn: 'tsvector / RUM index',
  },
  {
    zh: '表知识库',
    en: 'Table KB',
    descZh: '结构化数据知识库',
    descEn: 'Structured data knowledge base',
  },
  {
    zh: '混合检索',
    en: 'Hybrid Search',
    descZh: '向量 + 全文混合检索',
    descEn: 'Vector + full-text hybrid retrieval',
  },
  {
    zh: '内置模型',
    en: 'Built-in Models',
    descZh: '内置 Embedding 与 Reranker',
    descEn: 'Built-in embedding & reranker',
  },
]
</script>

<style scoped>
.product-detail {
  min-height: 100vh;
  background: var(--pub-bg);
}

.hero {
  background: var(--pub-surface);
  padding: 72px 24px 64px;
}

.hero-inner {
  max-width: 960px;
  margin: 0 auto;
}

.hero-title {
  font-size: 48px;
  font-weight: 700;
  color: var(--pub-text);
  margin: 0 0 12px;
  letter-spacing: -0.02em;
  line-height: 1.15;
}

.hero-subtitle {
  font-size: 15px;
  color: var(--pub-text-2);
  margin: 0;
  line-height: 1.6;
}

.content {
  max-width: 960px;
  margin: 0 auto;
  padding: 48px 24px 64px;
}

.feature-list {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
  margin: 0 0 48px;
}

.feature-item {
  padding: 24px 24px 24px 28px;
  background: var(--pub-surface);
  border: 1px solid var(--pub-border);
  border-left: 4px solid #4caf50;
  border-radius: 10px;
  transition: box-shadow 0.2s ease;
}

.feature-item:hover {
  box-shadow: 0 4px 16px rgba(76, 175, 80, 0.08);
}

.feature-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.feature-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--pub-text);
}

.feature-desc {
  font-size: 14px;
  color: var(--pub-text-2);
  line-height: 1.6;
}

.cta-section {
  display: flex;
  align-items: center;
  gap: 24px;
  flex-wrap: wrap;
}

.cta-button {
  display: inline-block;
  padding: 12px 32px;
  background: #4caf50;
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  border-radius: 100px;
  text-decoration: none;
  transition: opacity 0.2s;
}

.cta-button:hover {
  opacity: 0.85;
}

.back-link {
  font-size: 14px;
  color: var(--pub-text-2);
  text-decoration: none;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  transition: color 0.2s;
}

.back-link:hover {
  color: #4caf50;
}

@media (max-width: 600px) {
  .hero { padding: 48px 20px 40px; }
  .hero-title { font-size: 32px; }
  .feature-list { grid-template-columns: 1fr; }
  .cta-section { flex-direction: column; align-items: flex-start; }
}
</style>
