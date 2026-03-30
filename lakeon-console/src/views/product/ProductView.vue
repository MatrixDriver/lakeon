<template>
  <main class="product-overview">
    <section class="page-hero">
      <h1>{{ t('一个平台，四种数据能力', 'One Platform, Four Data Capabilities') }}</h1>
      <p>{{ t('以 Lakebase 为底座，覆盖知识库、记忆库、AI 数据湖全场景', 'Lakebase-powered platform covering Knowledge Base, Memory Store, and AI Data Lake') }}</p>
    </section>

    <section class="product-grid">
      <router-link v-for="p in products" :key="p.path" :to="p.path" class="product-card">
        <div class="card-body">
          <div class="card-header">
            <span class="card-dot" :style="{ background: p.color }"></span>
            <h3>{{ p.title }}</h3>
          </div>
          <p class="card-desc">{{ p.desc }}</p>
          <div class="card-tags">
            <span v-for="(tag, i) in p.tags" :key="tag" class="card-tag">
              <span v-if="i > 0" class="tag-dot">&middot;</span>{{ tag }}
            </span>
          </div>
          <span class="card-link">{{ t('了解更多', 'Learn more') }} <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M5 12h14"/><path d="m12 5 7 7-7 7"/></svg></span>
        </div>
      </router-link>
    </section>

    <section class="page-bottom-cta">
      <h2>{{ t('30 秒，免费获得一个 Serverless 数据库', 'Get a free Serverless database in 30 seconds') }}</h2>
      <p>{{ t('无需信用卡 · 兼容所有 PG 客户端 · 立即体验', 'No credit card · Works with all PG clients · Try now') }}</p>
      <router-link to="/" class="cta-primary">{{ t('立即试用', 'Try Now') }}</router-link>
    </section>
  </main>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useLocale } from '../../stores/locale'

const { t } = useLocale()

const products = computed(() => [
  {
    path: '/product/lakebase',
    icon: '',
    title: 'Lakebase',
    desc: t('Serverless PostgreSQL，存算分离，自动扩缩容', 'Serverless PostgreSQL with disaggregated storage and auto-scaling'),
    color: '#0073e6',
    tags: [t('秒级启动', 'Fast Start'), t('时间旅行', 'Time Travel'), 'pgvector'],
  },
  {
    path: '/product/knowledge',
    icon: '',
    title: t('知识库', 'Knowledge Base'),
    desc: t('文档 + 表 + 向量检索，内置 Embedding 与 Reranker', 'Documents + Tables + Vector Search, built-in Embedding & Reranker'),
    color: '#4caf50',
    tags: [t('文档解析', 'Doc Parsing'), t('混合检索', 'Hybrid Search'), 'RAG'],
  },
  {
    path: '/product/memory',
    icon: '',
    title: t('记忆库', 'Memory Store'),
    desc: t('为 AI Agent 提供结构化长期记忆，越用越懂你', 'Structured long-term memory for AI Agents — gets smarter with every interaction'),
    color: '#ff9800',
    tags: [t('自动提取', 'Auto Extract'), t('混合召回', 'Hybrid Recall'), 'MCP'],
  },
  {
    path: '/product/datalake',
    icon: '',
    title: t('AI 数据湖', 'AI Data Lake'),
    desc: t('数据处理 + 训练 + 飞轮', 'Data Processing + Training + Flywheel'),
    color: '#7b1fa2',
    tags: ['Ray', 'Python', t('数据飞轮', 'Flywheel')],
  },
])
</script>

<style scoped>
.product-overview {
  min-height: 100vh;
  background: var(--pub-bg);
}

.page-hero {
  padding: 72px 48px 64px;
  text-align: center;
  background: var(--pub-surface);
}
.page-hero h1 {
  font-size: 48px;
  font-weight: 700;
  color: var(--pub-text);
  margin: 0 0 12px;
  letter-spacing: -0.02em;
  line-height: 1.15;
}
.page-hero p {
  font-size: 15px;
  color: var(--pub-text-2);
  margin: 0;
  line-height: 1.6;
}

.product-grid {
  max-width: 960px;
  margin: 0 auto;
  padding: 48px 24px 64px;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}

.product-card {
  display: flex;
  background: var(--pub-surface);
  border: 1px solid var(--pub-border);
  border-radius: 12px;
  overflow: hidden;
  text-decoration: none;
  transition: box-shadow 0.2s, transform 0.2s;
}
.product-card:hover {
  box-shadow: 0 8px 24px var(--pub-shadow);
  transform: translateY(-2px);
}

.card-body {
  padding: 28px;
  flex: 1;
}

.card-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.card-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.card-body h3 {
  font-size: 18px;
  font-weight: 700;
  color: var(--pub-text);
  margin: 0;
}

.card-desc {
  font-size: 14px;
  color: var(--pub-text-2);
  line-height: 1.6;
  margin: 0 0 14px;
}

.card-tags {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  margin-bottom: 16px;
}

.card-tag {
  font-size: 13px;
  color: var(--pub-text-3);
  display: inline-flex;
  align-items: center;
}

.tag-dot {
  margin: 0 8px;
  color: var(--pub-text-4, #cbd5e1);
}

.card-link {
  font-size: 13px;
  color: var(--pub-primary);
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.page-bottom-cta {
  background: linear-gradient(135deg, #e8f2ff, #f0f5ff);
  padding: 64px 48px;
  text-align: center;
}
.page-bottom-cta h2 {
  font-size: 28px;
  font-weight: 700;
  color: var(--pub-text);
  margin: 0 0 8px;
  letter-spacing: -0.01em;
}
.page-bottom-cta p {
  font-size: 15px;
  color: var(--pub-text-2);
  margin: 0 0 24px;
}
.cta-primary {
  display: inline-block;
  background: var(--pub-primary, #0073e6);
  color: #fff;
  padding: 12px 36px;
  border-radius: 100px;
  font-size: 15px;
  font-weight: 600;
  text-decoration: none;
  transition: opacity 0.2s;
}
.cta-primary:hover { opacity: 0.85; }

@media (max-width: 768px) {
  .page-hero { padding: 48px 20px 40px; }
  .page-hero h1 { font-size: 32px; }
  .product-grid { grid-template-columns: 1fr; padding: 32px 20px 48px; }
  .page-bottom-cta { padding: 48px 20px; }
}
</style>
