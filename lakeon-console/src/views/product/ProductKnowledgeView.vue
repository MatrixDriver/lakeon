<template>
  <main class="product-page">
    <div class="accent-line"></div>

    <div class="content">
      <!-- Hero -->
      <section class="hero">
        <h1 class="page-title">{{ t('知识库', 'Knowledge Base') }}</h1>
        <p class="page-subtitle">{{ t('文档 + 表 + 向量检索，内置 Embedding 与 Reranker', 'Documents + Tables + Vector Search, built-in Embedding & Reranker') }}</p>
      </section>

      <!-- Features -->
      <section class="features">
        <div v-for="feature in features" :key="feature.en" class="feature">
          <div class="feature-accent"></div>
          <div class="feature-body">
            <h3 class="feature-title">{{ t(feature.zh, feature.en) }}</h3>
            <p class="feature-desc">{{ t(feature.descZh, feature.descEn) }}</p>
          </div>
        </div>
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

const features = [
  {
    zh: '文档自动解析',
    en: 'Document Parsing',
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
    en: 'Table Knowledge Base',
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
  background: var(--pub-bg, #fafaf7);
  color: var(--pub-text, #18181b);
}

.accent-line {
  height: 2px;
  background: #065f46;
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

/* Features */
.features {
  display: flex;
  flex-direction: column;
  gap: 28px;
  padding-bottom: 64px;
}

.feature {
  display: flex;
  gap: 20px;
  align-items: flex-start;
}

.feature-accent {
  width: 2px;
  min-height: 100%;
  background: #065f46;
  flex-shrink: 0;
  margin-top: 4px;
}

.feature-body {
  flex: 1;
}

.feature-title {
  font-family: var(--pub-sans, 'Plus Jakarta Sans', sans-serif);
  font-size: 16px;
  font-weight: 700;
  margin: 0 0 6px;
  color: var(--pub-text, #18181b);
}

.feature-desc {
  font-family: var(--pub-sans, 'Plus Jakarta Sans', sans-serif);
  font-size: 14px;
  line-height: 1.7;
  color: var(--pub-text-2, #52525b);
  margin: 0;
}

/* CTA */
.cta {
  display: flex;
  align-items: center;
  gap: 28px;
  padding-bottom: 96px;
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

  .cta {
    flex-direction: column;
    align-items: flex-start;
    gap: 16px;
  }
}
</style>
