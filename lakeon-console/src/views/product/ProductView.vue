<template>
  <main class="product-overview">

    <!-- Hero -->
    <section class="hero">
      <h1 class="hero-title">{{ t('从数据库到数据平台', 'From Database to Data Platform') }}</h1>
      <p class="hero-subtitle">{{ t('以 Lakebase 为底座，按需解锁知识库、记忆库、AI 数据湖', 'Lakebase-powered platform — unlock Knowledge, Memory, and Data Lake on demand') }}</p>
    </section>

    <!-- Layer 1 -->
    <section class="layer">
      <div class="layer-content">
        <div class="layer-text">
          <div class="layer-header">
            <span class="layer-number">01</span>
            <span class="layer-label">{{ t('基础层', 'Foundation') }}</span>
          </div>
          <h2 class="layer-title">Lakebase</h2>
          <p class="layer-subtitle">Serverless PostgreSQL</p>
          <p class="layer-desc">
            {{ t(
              '用你现有的 PG 客户端直接连接，按需弹性伸缩，空闲自动休眠。内置 pgvector 向量搜索、时间旅行数据版本管理。',
              'Connect with any PG client. Elastic scaling, auto-sleep on idle. Built-in pgvector and time travel versioning.'
            ) }}
          </p>
          <div class="layer-features-grid">
            <div class="layer-feature">
              <div class="layer-feature-name">{{ t('秒级启动', 'Fast Start') }}</div>
              <div class="layer-feature-desc">{{ t('3ms 热启动，3s 冷启动', '3ms hot, 3s cold start') }}</div>
            </div>
            <div class="layer-feature">
              <div class="layer-feature-name">{{ t('存算分离', 'Disaggregated') }}</div>
              <div class="layer-feature-desc">{{ t('存储与计算独立扩展', 'Independent storage and compute') }}</div>
            </div>
            <div class="layer-feature">
              <div class="layer-feature-name">{{ t('数据库分支', 'Branching') }}</div>
              <div class="layer-feature-desc">{{ t('像 Git 一样管理数据', 'Git-like data versioning') }}</div>
            </div>
            <div class="layer-feature">
              <div class="layer-feature-name">{{ t('时间旅行', 'Time Travel') }}</div>
              <div class="layer-feature-desc">{{ t('回滚到任意时间点', 'Rollback to any point') }}</div>
            </div>
          </div>
          <p class="layer-tags">{{ t('秒级启动', 'Fast Start') }} · {{ t('时间旅行', 'Time Travel') }} · pgvector</p>
          <router-link to="/product/lakebase" class="layer-link">{{ t('了解 Lakebase', 'Learn about Lakebase') }} &rarr;</router-link>
        </div>
        <div class="layer-visual">
          <LayerLakebase />
        </div>
      </div>
    </section>

    <hr class="divider">

    <!-- Layer 2 -->
    <section class="layer">
      <div class="layer-content layer-content--reverse">
        <div class="layer-visual">
          <LayerServices />
        </div>
        <div class="layer-text">
          <div class="layer-header">
            <span class="layer-number">02</span>
            <span class="layer-label">{{ t('服务层', 'Services') }}</span>
          </div>
          <h2 class="layer-title">{{ t('知识库 + 记忆库', 'Knowledge + Memory') }}</h2>
          <p class="layer-subtitle">{{ t('原生 AI 数据服务', 'Native AI Data Services') }}</p>
          <p class="layer-desc">
            {{ t(
              'DBay 提供完全发挥 Lakebase 能力的原生服务 — Agent 通过 MCP / Skill 直接对接，多库合一，减少第三方依赖。',
              'Native services that fully leverage Lakebase — connect via MCP/Skill, all-in-one, fewer dependencies.'
            ) }}
          </p>
          <p class="layer-tags">{{ t('MCP 一键接入', 'MCP one-click') }} · {{ t('记忆自动提取', 'Auto extraction') }} · {{ t('文档向量化', 'Doc vectorization') }} · {{ t('Console 管理', 'Console management') }}</p>
          <router-link to="/product/memory" class="layer-link">{{ t('了解记忆与知识服务', 'Learn about Memory & Knowledge') }} &rarr;</router-link>
        </div>
      </div>
    </section>

    <hr class="divider">

    <!-- Layer 3 -->
    <section class="layer">
      <div class="layer-content">
        <div class="layer-text">
          <div class="layer-header">
            <span class="layer-number">03</span>
            <span class="layer-label">{{ t('数据闭环', 'Data Loop') }}</span>
          </div>
          <h2 class="layer-title">{{ t('AI 数据湖', 'AI Data Lake') }}</h2>
          <p class="layer-subtitle">{{ t('多模态数据处理与飞轮', 'Multimodal Processing & Flywheel') }}</p>
          <p class="layer-desc">
            {{ t(
              '数据库、知识库、记忆库与数据湖双向联动 — 导入导出自如。在数据湖上运行 Ray、Python 作业，实现数据加工和分析，形成数据飞轮。',
              'Bidirectional sync between database, knowledge, memory, and data lake. Run Ray/Python jobs for processing and analysis, forming a data flywheel.'
            ) }}
          </p>
          <p class="layer-tags">{{ t('分析用户习惯', 'Analyze behavior') }} · {{ t('导出微调', 'Export for fine-tuning') }} · {{ t('质量评估', 'Quality assessment') }} · {{ t('清洗归档', 'Clean & archive') }}</p>
          <router-link to="/product/datalake" class="layer-link">{{ t('了解数据湖', 'Learn about Data Lake') }} &rarr;</router-link>
        </div>
        <div class="layer-visual">
          <LayerDatalake />
        </div>
      </div>
    </section>

    <!-- Bottom CTA -->
    <section class="bottom-cta">
      <p class="bottom-cta-text">{{ t('无需信用卡 · 兼容所有 PG 客户端 · 立即体验', 'No credit card · Works with all PG clients · Try now') }}</p>
      <router-link to="/" class="btn-primary">{{ t('立即试用', 'Try Now') }} &rarr;</router-link>
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
  background: var(--pub-bg, #fafaf7);
  color: var(--pub-text, #18181b);
}

/* Hero */
.hero {
  max-width: 720px;
  margin: 0 auto;
  padding: 96px 32px 80px;
}

.hero-title {
  font-family: var(--pub-serif, 'Cormorant Garamond', Georgia, serif);
  font-size: 48px;
  font-weight: 600;
  line-height: 1.15;
  margin: 0 0 16px;
  color: var(--pub-text, #18181b);
}

.hero-subtitle {
  font-family: var(--pub-sans, 'Plus Jakarta Sans', sans-serif);
  font-size: 17px;
  line-height: 1.7;
  color: var(--pub-text-2, #52525b);
  margin: 0;
}

/* Layers */
.layer {
  max-width: 960px;
  margin: 0 auto;
  padding: 80px 32px;
}

.layer-content {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 64px;
  align-items: start;
}

.layer-content--reverse {
  direction: rtl;
}

.layer-content--reverse > * {
  direction: ltr;
}

.layer-header {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 16px;
}

.layer-number {
  font-family: var(--pub-serif, 'Cormorant Garamond', Georgia, serif);
  font-size: 42px;
  font-weight: 300;
  line-height: 1;
  color: var(--pub-text-3, #a1a1aa);
}

.layer-label {
  font-family: var(--pub-sans, 'Plus Jakarta Sans', sans-serif);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--pub-text-3, #a1a1aa);
}

.layer-title {
  font-family: var(--pub-sans, 'Plus Jakarta Sans', sans-serif);
  font-size: 24px;
  font-weight: 700;
  margin: 0 0 6px;
  color: var(--pub-text, #18181b);
}

.layer-subtitle {
  font-family: var(--pub-sans, 'Plus Jakarta Sans', sans-serif);
  font-size: 14px;
  color: var(--pub-text-3, #a1a1aa);
  margin: 0 0 20px;
}

.layer-desc {
  font-family: var(--pub-sans, 'Plus Jakarta Sans', sans-serif);
  font-size: 15px;
  line-height: 1.75;
  color: var(--pub-text-2, #52525b);
  margin: 0 0 24px;
}

.layer-features-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px 32px;
  margin-bottom: 24px;
}

.layer-feature-name {
  font-family: var(--pub-sans, 'Plus Jakarta Sans', sans-serif);
  font-size: 14px;
  font-weight: 600;
  color: var(--pub-text, #18181b);
  margin-bottom: 4px;
}

.layer-feature-desc {
  font-family: var(--pub-sans, 'Plus Jakarta Sans', sans-serif);
  font-size: 13px;
  color: var(--pub-text-3, #a1a1aa);
  line-height: 1.5;
}

.layer-tags {
  font-family: var(--pub-sans, 'Plus Jakarta Sans', sans-serif);
  font-size: 13px;
  color: var(--pub-text-3, #a1a1aa);
  margin: 0 0 20px;
  line-height: 1.6;
}

.layer-link {
  font-family: var(--pub-sans, 'Plus Jakarta Sans', sans-serif);
  font-size: 14px;
  font-weight: 600;
  color: #92400e;
  text-decoration: none;
  transition: opacity 0.15s;
}

.layer-link:hover {
  opacity: 0.7;
}

.layer-visual {
  display: flex;
  align-items: center;
  justify-content: center;
}

/* Divider */
.divider {
  max-width: 960px;
  margin: 0 auto;
  border: none;
  border-top: 1px solid var(--pub-border, #e4e4e7);
}

/* Bottom CTA */
.bottom-cta {
  max-width: 720px;
  margin: 0 auto;
  padding: 80px 32px 96px;
  text-align: left;
}

.bottom-cta-text {
  font-family: var(--pub-sans, 'Plus Jakarta Sans', sans-serif);
  font-size: 14px;
  color: var(--pub-text-3, #a1a1aa);
  margin: 0 0 24px;
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

/* Responsive */
@media (max-width: 768px) {
  .hero {
    padding: 64px 24px 56px;
  }

  .hero-title {
    font-size: 36px;
  }

  .layer {
    padding: 56px 24px;
  }

  .layer-content,
  .layer-content--reverse {
    grid-template-columns: 1fr;
    direction: ltr;
    gap: 40px;
  }

  .layer-features-grid {
    grid-template-columns: 1fr;
  }

  .layer-visual {
    order: -1;
  }

  .bottom-cta {
    padding: 56px 24px 72px;
  }
}
</style>
