<template>
  <main class="product-detail">
    <div class="hero">
      <div class="hero-inner">
        <h1 class="hero-title">{{ t('📚 知识库', '📚 Knowledge Base') }}</h1>
        <p class="hero-subtitle">
          {{ t('文档 + 表 + 向量检索，内置 Embedding 与 Reranker', 'Documents + Tables + Vector Search, built-in Embedding & Reranker') }}
        </p>
      </div>
    </div>

    <div class="content">
      <ul class="feature-list">
        <li class="feature-item" v-for="feature in features" :key="feature.icon">
          <span class="feature-icon">{{ feature.icon }}</span>
          <div class="feature-body">
            <div class="feature-title">{{ t(feature.zh, feature.en) }}</div>
            <div class="feature-desc">{{ t(feature.descZh, feature.descEn) }}</div>
          </div>
        </li>
      </ul>

      <div class="cta-section">
        <a href="/console" class="cta-button">{{ t('立即试用 →', 'Get Started →') }}</a>
        <router-link to="/product" class="back-link">{{ t('← 返回产品总览', '← Back to Products') }}</router-link>
      </div>
    </div>
  </main>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'

const { t } = useLocale()

const features = [
  {
    icon: '📄',
    zh: '文档自动解析',
    en: 'Doc Parsing',
    descZh: 'PDF / Word / Markdown 自动解析',
    descEn: 'Auto parsing for PDF, Word, Markdown',
  },
  {
    icon: '🔍',
    zh: '向量检索',
    en: 'Vector Search',
    descZh: '基于 pgvector 的语义搜索',
    descEn: 'Semantic search powered by pgvector',
  },
  {
    icon: '📝',
    zh: '全文搜索',
    en: 'Full-text Search',
    descZh: 'tsvector / RUM 索引',
    descEn: 'tsvector / RUM index',
  },
  {
    icon: '📊',
    zh: '表知识库',
    en: 'Table KB',
    descZh: '结构化数据知识库',
    descEn: 'Structured data knowledge base',
  },
  {
    icon: '🔗',
    zh: '混合检索',
    en: 'Hybrid Search',
    descZh: '向量 + 全文混合检索',
    descEn: 'Vector + full-text hybrid retrieval',
  },
  {
    icon: '🧩',
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
  border-top: 4px solid transparent;
  border-image: linear-gradient(90deg, #4caf50, #2e7d32) 1;
  background: var(--pub-surface);
  border-bottom: 1px solid var(--pub-border);
  padding: 56px 24px 48px;
}

.hero-inner {
  max-width: 800px;
  margin: 0 auto;
}

.hero-title {
  font-size: 2rem;
  font-weight: 700;
  color: var(--pub-text);
  margin: 0 0 12px;
}

.hero-subtitle {
  font-size: 1.05rem;
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
  gap: 16px;
}

.feature-item {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  padding: 20px 24px;
  background: var(--pub-surface);
  border: 1px solid var(--pub-border);
  border-radius: 10px;
  transition: border-color 0.2s, background 0.2s;
}

.feature-item:hover {
  border-color: #4caf50;
  background: var(--pub-hover);
}

.feature-icon {
  font-size: 1.5rem;
  line-height: 1;
  flex-shrink: 0;
  margin-top: 2px;
}

.feature-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.feature-title {
  font-size: 1rem;
  font-weight: 600;
  color: var(--pub-text);
}

.feature-desc {
  font-size: 0.9rem;
  color: var(--pub-text-2);
  line-height: 1.5;
}

.cta-section {
  display: flex;
  align-items: center;
  gap: 24px;
  flex-wrap: wrap;
}

.cta-button {
  display: inline-block;
  padding: 12px 28px;
  background: #4caf50;
  color: #fff;
  font-size: 0.95rem;
  font-weight: 600;
  border-radius: 8px;
  text-decoration: none;
  transition: background 0.2s;
}

.cta-button:hover {
  background: #2e7d32;
}

.back-link {
  font-size: 0.9rem;
  color: var(--pub-text-3);
  text-decoration: none;
  transition: color 0.2s;
}

.back-link:hover {
  color: var(--pub-text);
}

@media (max-width: 600px) {
  .hero-title {
    font-size: 1.5rem;
  }

  .feature-item {
    padding: 16px;
  }

  .cta-section {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
