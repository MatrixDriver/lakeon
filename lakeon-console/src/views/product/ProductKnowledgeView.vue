<template>
  <main class="product-page">
    <section class="hero">
      <div class="hero-accent"></div>
      <div class="hero-inner">
        <h1 class="hero-title">{{ t('知识库', 'Knowledge Base') }}</h1>
        <p class="hero-subtitle">{{ t(
          '文档 + 表 + 向量检索，内置 Embedding 与 Reranker',
          'Documents + Tables + Vector Search, built-in Embedding & Reranker'
        ) }}</p>
      </div>
    </section>

    <section class="content">
      <div class="features-grid">
        <div v-for="feature in features" :key="feature.titleEn" class="feature-card">
          <div class="feature-header">
            <span class="feature-dot"></span>
            <h3 class="feature-title">{{ t(feature.titleZh, feature.titleEn) }}</h3>
          </div>
          <p class="feature-desc">{{ t(feature.descZh, feature.descEn) }}</p>
        </div>
      </div>

      <div class="cta-section">
        <a href="/console" class="cta-button">{{ t('立即试用', 'Get Started') }}</a>
        <router-link to="/product" class="back-link">{{ t('返回产品总览', 'Back to Products') }}</router-link>
      </div>
    </section>
  </main>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'

const { t } = useLocale()

const features = [
  {
    titleZh: '文档自动解析',
    titleEn: 'Document Parsing',
    descZh: 'PDF / Word / Markdown 自动解析',
    descEn: 'Auto parsing for PDF, Word, Markdown',
  },
  {
    titleZh: '向量检索',
    titleEn: 'Vector Search',
    descZh: '基于 pgvector 的语义搜索',
    descEn: 'Semantic search powered by pgvector',
  },
  {
    titleZh: '全文搜索',
    titleEn: 'Full-text Search',
    descZh: 'tsvector / RUM 索引',
    descEn: 'tsvector / RUM index',
  },
  {
    titleZh: '表知识库',
    titleEn: 'Table Knowledge Base',
    descZh: '结构化数据知识库',
    descEn: 'Structured data knowledge base',
  },
  {
    titleZh: '混合检索',
    titleEn: 'Hybrid Search',
    descZh: '向量 + 全文混合检索',
    descEn: 'Vector + full-text hybrid retrieval',
  },
  {
    titleZh: '内置模型',
    titleEn: 'Built-in Models',
    descZh: '内置 Embedding 与 Reranker',
    descEn: 'Built-in embedding & reranker',
  },
]
</script>

<style scoped>
.product-page {
  min-height: 100vh;
  background: var(--pub-bg);
}

/* Hero */
.hero {
  background: var(--pub-bg-alt);
  padding: 56px 24px 48px;
  position: relative;
}

.hero-accent {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 4px;
  background: #2a7a5f;
  border-radius: 0 0 4px 4px;
}

.hero-inner {
  max-width: 800px;
  margin: 0 auto;
}

.hero-title {
  font-family: var(--pub-sans);
  font-size: 36px;
  font-weight: 700;
  color: var(--pub-text);
  margin: 0 0 10px;
  letter-spacing: -0.02em;
}

.hero-subtitle {
  font-family: var(--pub-sans);
  font-size: 16px;
  font-weight: 400;
  color: var(--pub-text-2);
  margin: 0;
  line-height: 1.6;
}

/* Content */
.content {
  max-width: 800px;
  margin: 0 auto;
  padding: 48px 24px 72px;
}

/* Features grid */
.features-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
  margin-bottom: 48px;
}

.feature-card {
  background: var(--pub-surface);
  border: 1px solid var(--pub-border);
  border-radius: 12px;
  padding: 24px;
  transition: border-color 0.2s;
}

.feature-card:hover {
  border-color: #2a7a5f;
}

.feature-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.feature-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #2a7a5f;
  flex-shrink: 0;
}

.feature-title {
  font-family: var(--pub-sans);
  font-size: 15px;
  font-weight: 600;
  color: var(--pub-text);
  margin: 0;
}

.feature-desc {
  font-family: var(--pub-sans);
  font-size: 14px;
  color: var(--pub-text-2);
  line-height: 1.6;
  margin: 0;
  padding-left: 16px;
}

/* CTA */
.cta-section {
  display: flex;
  align-items: center;
  gap: 24px;
  flex-wrap: wrap;
}

.cta-button {
  display: inline-block;
  padding: 12px 32px;
  background: #2a7a5f;
  color: #fff;
  font-family: var(--pub-sans);
  font-size: 15px;
  font-weight: 600;
  text-decoration: none;
  border-radius: 100px;
  transition: background 0.15s;
}

.cta-button:hover {
  background: #1a5a42;
}

.back-link {
  font-family: var(--pub-sans);
  font-size: 14px;
  color: var(--pub-text-3);
  text-decoration: none;
  transition: color 0.15s;
}

.back-link:hover {
  color: var(--pub-text);
}

/* Responsive */
@media (max-width: 600px) {
  .hero-title {
    font-size: 28px;
  }

  .features-grid {
    grid-template-columns: 1fr;
  }

  .cta-section {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
