<template>
  <main class="product-overview">
    <!-- Hero -->
    <section class="hero">
      <h1 class="hero-title">{{ t('一个平台，三层架构', 'One Platform, Three Layers') }}</h1>
      <p class="hero-subtitle">{{ t(
        '以 Lakebase 为底座，覆盖知识库、记忆库、AI 数据湖全场景',
        'Lakebase-powered platform covering Knowledge Base, Memory Store, and AI Data Lake'
      ) }}</p>
    </section>

    <!-- Layer 01: Lakebase -->
    <section class="layer-section">
      <div class="layer-inner">
        <div class="layer-content">
          <div class="layer-number">01</div>
          <div class="layer-label">{{ t('底座层', 'FOUNDATION') }}</div>
          <h2 class="layer-title">Lakebase</h2>
          <p class="layer-subtitle">{{ t('Serverless PostgreSQL 数据底座', 'Serverless PostgreSQL Foundation') }}</p>
          <p class="layer-desc">{{ t(
            '存算分离架构，秒级启动，自动扩缩容。兼容所有 PostgreSQL 客户端，内置时间旅行与数据库分支。',
            'Disaggregated storage and compute with sub-second start. Compatible with all PostgreSQL clients, built-in time travel and database branching.'
          ) }}</p>
          <div class="layer-features">
            <span>{{ t('秒级启动', 'Fast Start') }}</span>
            <span class="feature-dot"></span>
            <span>{{ t('时间旅行', 'Time Travel') }}</span>
            <span class="feature-dot"></span>
            <span>pgvector</span>
            <span class="feature-dot"></span>
            <span>{{ t('数据库分支', 'Branching') }}</span>
          </div>
          <router-link to="/product/lakebase" class="layer-link">
            {{ t('了解更多', 'Learn more') }}
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M6 4l4 4-4 4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </router-link>
        </div>
        <div class="layer-visual">
          <LayerLakebase />
        </div>
      </div>
    </section>

    <!-- Layer 02: Services -->
    <section class="layer-section layer-alt">
      <div class="layer-inner layer-reverse">
        <div class="layer-content">
          <div class="layer-number">02</div>
          <div class="layer-label">{{ t('服务层', 'SERVICES') }}</div>
          <h2 class="layer-title">{{ t('知识库 + 记忆库', 'Knowledge + Memory') }}</h2>
          <p class="layer-subtitle">{{ t('AI 原生数据服务', 'AI-native Data Services') }}</p>
          <p class="layer-desc">{{ t(
            '知识库提供文档解析、向量检索与混合搜索。记忆库为 AI Agent 提供结构化长期记忆，自动提取实体与行为模式。',
            'Knowledge Base provides document parsing, vector search and hybrid retrieval. Memory Store offers structured long-term memory for AI Agents with automatic entity and pattern extraction.'
          ) }}</p>
          <div class="layer-features">
            <span>RAG</span>
            <span class="feature-dot"></span>
            <span>{{ t('混合检索', 'Hybrid Search') }}</span>
            <span class="feature-dot"></span>
            <span>{{ t('长期记忆', 'Long-term Memory') }}</span>
            <span class="feature-dot"></span>
            <span>MCP</span>
          </div>
          <div class="layer-links">
            <router-link to="/product/knowledge" class="layer-link">
              {{ t('知识库', 'Knowledge') }}
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M6 4l4 4-4 4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </router-link>
            <router-link to="/product/memory" class="layer-link">
              {{ t('记忆库', 'Memory') }}
              <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M6 4l4 4-4 4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </router-link>
          </div>
        </div>
        <div class="layer-visual">
          <LayerServices />
        </div>
      </div>
    </section>

    <!-- Layer 03: Datalake -->
    <section class="layer-section">
      <div class="layer-inner">
        <div class="layer-content">
          <div class="layer-number">03</div>
          <div class="layer-label">{{ t('计算层', 'COMPUTE') }}</div>
          <h2 class="layer-title">{{ t('AI 数据湖', 'AI Data Lake') }}</h2>
          <p class="layer-subtitle">{{ t('分布式计算与数据飞轮', 'Distributed Compute and Data Flywheel') }}</p>
          <p class="layer-desc">{{ t(
            '提交 Python 和 Ray 分布式作业，支持模型微调与增量 CDC。数据在数据库与数据湖之间双向流转，构建持续进化的数据飞轮。',
            'Submit Python and Ray distributed jobs with model fine-tuning and incremental CDC. Bidirectional data flow between database and data lake builds a continuously evolving data flywheel.'
          ) }}</p>
          <div class="layer-features">
            <span>Ray</span>
            <span class="feature-dot"></span>
            <span>Python</span>
            <span class="feature-dot"></span>
            <span>{{ t('数据飞轮', 'Flywheel') }}</span>
            <span class="feature-dot"></span>
            <span>{{ t('模型微调', 'Fine-tuning') }}</span>
          </div>
          <router-link to="/product/datalake" class="layer-link">
            {{ t('了解更多', 'Learn more') }}
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none"><path d="M6 4l4 4-4 4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>
          </router-link>
        </div>
        <div class="layer-visual">
          <LayerDatalake />
        </div>
      </div>
    </section>

    <!-- Bottom CTA -->
    <section class="bottom-cta">
      <h2 class="cta-title">{{ t('30 秒，免费获得一个 Serverless 数据库', 'Get a free Serverless database in 30 seconds') }}</h2>
      <p class="cta-subtitle">{{ t(
        '无需信用卡 · 兼容所有 PG 客户端 · 立即体验',
        'No credit card · Works with all PG clients · Try now'
      ) }}</p>
      <router-link to="/" class="cta-button">{{ t('立即试用', 'Try Now') }}</router-link>
    </section>
  </main>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'
import LayerLakebase from '../landing/LayerLakebase.vue'
import LayerServices from '../landing/LayerServices.vue'
import LayerDatalake from '../landing/LayerDatalake.vue'

const { t } = useLocale()
</script>

<style scoped>
.product-overview {
  min-height: 100vh;
  background: var(--pub-bg);
}

/* Hero */
.hero {
  padding: 72px 24px 64px;
  text-align: center;
}

.hero-title {
  font-family: var(--pub-sans);
  font-size: 48px;
  font-weight: 700;
  color: var(--pub-text);
  margin: 0 0 12px;
  letter-spacing: -0.02em;
}

.hero-subtitle {
  font-family: var(--pub-sans);
  font-size: 18px;
  font-weight: 400;
  color: var(--pub-text-2);
  margin: 0;
  line-height: 1.6;
}

/* Layer sections */
.layer-section {
  padding: 64px 24px;
}

.layer-alt {
  background: var(--pub-bg-alt);
}

.layer-inner {
  max-width: 960px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  gap: 64px;
}

.layer-reverse {
  flex-direction: row-reverse;
}

.layer-content {
  flex: 1;
  min-width: 0;
}

.layer-visual {
  flex: 0 0 320px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.layer-number {
  font-family: var(--pub-sans);
  font-size: 48px;
  font-weight: 300;
  color: #2a7a5f;
  line-height: 1;
  margin-bottom: 4px;
}

.layer-label {
  font-family: var(--pub-sans);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--pub-text-3);
  margin-bottom: 16px;
}

.layer-title {
  font-family: var(--pub-sans);
  font-size: 28px;
  font-weight: 600;
  color: var(--pub-text);
  margin: 0 0 6px;
  letter-spacing: -0.01em;
}

.layer-subtitle {
  font-family: var(--pub-sans);
  font-size: 15px;
  font-weight: 400;
  color: var(--pub-text-3);
  margin: 0 0 16px;
}

.layer-desc {
  font-family: var(--pub-sans);
  font-size: 15px;
  color: var(--pub-text-2);
  line-height: 1.7;
  margin: 0 0 20px;
}

.layer-features {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 20px;
  font-family: var(--pub-sans);
  font-size: 13px;
  color: var(--pub-text-2);
}

.feature-dot {
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: var(--pub-text-3);
  flex-shrink: 0;
}

.layer-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-family: var(--pub-sans);
  font-size: 14px;
  font-weight: 500;
  color: #2a7a5f;
  text-decoration: none;
  transition: opacity 0.15s;
}

.layer-link:hover {
  opacity: 0.75;
}

.layer-links {
  display: flex;
  gap: 24px;
}

/* Bottom CTA */
.bottom-cta {
  background: linear-gradient(135deg, #eef6f2, #dff0e8);
  padding: 64px 24px;
  text-align: center;
}

.cta-title {
  font-family: var(--pub-sans);
  font-size: 24px;
  font-weight: 600;
  color: var(--pub-text);
  margin: 0 0 8px;
}

.cta-subtitle {
  font-family: var(--pub-sans);
  font-size: 14px;
  color: var(--pub-text-2);
  margin: 0 0 24px;
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
  background: #1a2e25;
}

/* Responsive */
@media (max-width: 768px) {
  .hero {
    padding: 48px 20px 40px;
  }

  .hero-title {
    font-size: 32px;
  }

  .layer-section {
    padding: 48px 20px;
  }

  .layer-inner,
  .layer-reverse {
    flex-direction: column;
    gap: 32px;
  }

  .layer-visual {
    flex: none;
    width: 100%;
    max-width: 280px;
  }

  .bottom-cta {
    padding: 48px 20px;
  }
}
</style>
