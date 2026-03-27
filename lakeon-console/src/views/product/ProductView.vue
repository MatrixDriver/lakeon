<template>
  <main class="product-overview">
    <section class="page-hero">
      <h1>{{ t('一个平台，四种数据能力', 'One Platform, Four Data Capabilities') }}</h1>
      <p>{{ t('以 Lakebase 为底座，覆盖知识库、记忆库、AI 数据湖全场景', 'Lakebase-powered platform covering Knowledge Base, Memory Store, and AI Data Lake') }}</p>
    </section>

    <section class="product-grid">
      <router-link v-for="p in products" :key="p.path" :to="p.path" class="product-card">
        <div class="card-accent" :style="{ background: p.gradient }"></div>
        <div class="card-body">
          <div class="card-icon">{{ p.icon }}</div>
          <h3>{{ p.title }}</h3>
          <p class="card-desc">{{ p.desc }}</p>
          <div class="card-tags">
            <span v-for="tag in p.tags" :key="tag" class="card-tag">{{ tag }}</span>
          </div>
          <span class="card-link">{{ t('了解更多 →', 'Learn more →') }}</span>
        </div>
      </router-link>
    </section>

    <section class="page-bottom-cta">
      <h2>{{ t('30 秒，免费获得一个 Serverless 数据库', 'Get a free Serverless database in 30 seconds') }}</h2>
      <p>{{ t('无需信用卡 · 兼容所有 PG 客户端 · 立即体验', 'No credit card · Works with all PG clients · Try now') }}</p>
      <router-link to="/" class="cta-primary">{{ t('立即试用 →', 'Try Now →') }}</router-link>
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
    icon: '🐘',
    title: 'Lakebase',
    desc: t('Serverless PostgreSQL，存算分离，自动扩缩容', 'Serverless PostgreSQL with disaggregated storage and auto-scaling'),
    gradient: 'linear-gradient(180deg, #0073e6, #005bb5)',
    tags: [t('秒级启动', 'Fast Start'), t('时间旅行', 'Time Travel'), 'pgvector'],
  },
  {
    path: '/product/knowledge',
    icon: '📚',
    title: t('知识库', 'Knowledge Base'),
    desc: t('文档 + 表 + 向量检索，内置 Embedding 与 Reranker', 'Documents + Tables + Vector Search, built-in Embedding & Reranker'),
    gradient: 'linear-gradient(180deg, #4caf50, #2e7d32)',
    tags: [t('文档解析', 'Doc Parsing'), t('混合检索', 'Hybrid Search'), 'RAG'],
  },
  {
    path: '/product/memory',
    icon: '🧠',
    title: t('记忆库', 'Memory Store'),
    desc: t('为 AI Agent 提供结构化长期记忆，越用越懂你', 'Structured long-term memory for AI Agents — gets smarter with every interaction'),
    gradient: 'linear-gradient(180deg, #ff9800, #e65100)',
    tags: [t('自动提取', 'Auto Extract'), t('混合召回', 'Hybrid Recall'), 'MCP'],
  },
  {
    path: '/product/datalake',
    icon: '🌊',
    title: t('AI 数据湖', 'AI Data Lake'),
    desc: t('数据处理 + 训练 + 飞轮', 'Data Processing + Training + Flywheel'),
    gradient: 'linear-gradient(180deg, #7b1fa2, #4a148c)',
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
  padding: 48px 48px 32px;
  text-align: center;
  background: var(--pub-surface);
}
.page-hero h1 {
  font-size: 36px;
  font-weight: 700;
  color: var(--pub-text);
  margin: 0 0 8px;
}
.page-hero p {
  font-size: 16px;
  color: var(--pub-text-2);
  margin: 0;
}

.product-grid {
  max-width: 900px;
  margin: 0 auto;
  padding: 0 24px 48px;
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

.card-accent {
  width: 6px;
  flex-shrink: 0;
}

.card-body {
  padding: 24px;
  flex: 1;
}

.card-icon {
  font-size: 28px;
  margin-bottom: 8px;
}

.card-body h3 {
  font-size: 18px;
  font-weight: 700;
  color: var(--pub-text);
  margin: 0 0 6px;
}

.card-desc {
  font-size: 13px;
  color: var(--pub-text-2);
  line-height: 1.5;
  margin: 0 0 12px;
}

.card-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.card-tag {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 4px;
  background: var(--pub-bg-alt, #f8f9fb);
  color: var(--pub-text-3);
}

.card-link {
  font-size: 13px;
  color: var(--pub-primary);
  font-weight: 600;
}

.page-bottom-cta {
  background: linear-gradient(135deg, #0062cc, var(--pub-primary, #0073e6));
  padding: 40px 48px;
  text-align: center;
}
.page-bottom-cta h2 {
  font-size: 24px;
  font-weight: 700;
  color: #fff;
  margin: 0 0 6px;
}
.page-bottom-cta p {
  font-size: 14px;
  color: rgba(255,255,255,0.7);
  margin: 0 0 20px;
}
.cta-primary {
  display: inline-block;
  background: #fff;
  color: var(--pub-primary, #0073e6);
  padding: 12px 32px;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 600;
  text-decoration: none;
}
.cta-primary:hover { opacity: 0.9; }

@media (max-width: 768px) {
  .page-hero { padding: 32px 20px 24px; }
  .page-hero h1 { font-size: 28px; }
  .product-grid { grid-template-columns: 1fr; padding: 0 20px 32px; }
  .page-bottom-cta { padding: 32px 20px; }
}
</style>
