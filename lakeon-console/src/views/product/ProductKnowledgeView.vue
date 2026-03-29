<template>
  <main class="product-page">
    <div class="hero">
      <div class="hero-inner">
        <div class="hero-marker"></div>
        <h1 class="hero-title">{{ t('知识库', 'Knowledge Base') }}</h1>
        <p class="hero-subtitle">
          {{ t('文档 + 表 + 向量检索，内置 Embedding 与 Reranker', 'Documents + Tables + Vector Search, built-in Embedding & Reranker') }}
        </p>
      </div>
    </div>

    <div class="content">
      <ul class="feature-list">
        <li v-for="feature in features" :key="feature.en" class="feature-item">
          <div class="feature-body">
            <div class="feature-title">{{ t(feature.zh, feature.en) }}</div>
            <div class="feature-desc">{{ t(feature.descZh, feature.descEn) }}</div>
          </div>
        </li>
      </ul>

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
.product-page {
  min-height: 100vh;
  background: var(--pub-bg);
  font-family: var(--pub-sans, 'DM Sans', sans-serif);
}

.hero {
  border-top: 3px solid #4a8b8c;
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
  background: #4a8b8c;
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

.content {
  max-width: 800px;
  margin: 0 auto;
  padding: 40px 24px 64px;
}

.feature-list {
  list-style: none;
  margin: 0 0 48px;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.feature-item {
  padding: 20px 24px;
  border-left: 3px solid #4a8b8c;
  background: var(--pub-surface);
  transition: background 0.15s ease;
}

.feature-item:hover {
  background: var(--pub-hover);
}

.feature-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.feature-title {
  font-family: var(--pub-sans, 'DM Sans', sans-serif);
  font-size: clamp(0.9rem, 1.2vw, 1rem);
  font-weight: 600;
  color: var(--pub-text);
}

.feature-desc {
  font-family: var(--pub-sans, 'DM Sans', sans-serif);
  font-size: clamp(0.8rem, 1.1vw, 0.9rem);
  color: var(--pub-text-2);
  line-height: 1.6;
}

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

@media (max-width: 600px) {
  .feature-item {
    padding: 16px;
  }

  .cta-section {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
