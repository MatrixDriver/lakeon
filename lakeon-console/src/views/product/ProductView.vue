<template>
  <main class="product-page">
    <!-- Hero -->
    <section class="prod-hero">
      <h1>{{ t('从数据库到数据平台', 'From Database to Data Platform') }}</h1>
      <p class="prod-hero-sub">{{ t('三层架构，按需解锁。从 Lakebase 开始，逐步扩展到知识库、记忆库和数据湖。', 'Three-layer architecture, unlock as you grow. Start with Lakebase, expand to Knowledge, Memory, and Data Lake.') }}</p>
    </section>

    <!-- Layer 1 -->
    <section class="layer">
      <div class="layer-inner">
        <div class="layer-head">
          <span class="layer-num" style="color: #1e2d3d">01</span>
          <span class="layer-label">{{ t('基础层', 'Foundation') }}</span>
        </div>
        <div class="layer-content">
          <div class="layer-text">
            <h2>Lakebase</h2>
            <p class="layer-subtitle">Serverless PostgreSQL</p>
            <p class="layer-desc">
              {{ t(
                '用你现有的 PG 客户端直接连接，按需弹性伸缩，空闲自动休眠。内置 pgvector 向量搜索、时间旅行数据版本管理。',
                'Connect with any PG client. Elastic scaling, auto-sleep on idle. Built-in pgvector and time travel versioning.'
              ) }}
            </p>
            <div class="layer-features">
              <div class="feature" v-for="f in layer1Features" :key="f.title">
                <strong>{{ f.title }}</strong>
                <span>{{ f.desc }}</span>
              </div>
            </div>
            <router-link to="/product/lakebase" class="layer-link">{{ t('了解 Lakebase', 'Learn about Lakebase') }} &rarr;</router-link>
          </div>
          <div class="layer-visual">
            <LayerLakebase />
          </div>
        </div>
      </div>
    </section>

    <!-- Layer 2 -->
    <section class="layer layer-alt">
      <div class="layer-inner">
        <div class="layer-head">
          <span class="layer-num" style="color: #4a8b8c">02</span>
          <span class="layer-label">{{ t('服务层', 'Services') }}</span>
        </div>
        <div class="layer-content layer-content-reverse">
          <div class="layer-visual">
            <LayerServices />
          </div>
          <div class="layer-text">
            <h2>{{ t('知识库 + 记忆库', 'Knowledge + Memory') }}</h2>
            <p class="layer-subtitle">{{ t('原生 AI 数据服务', 'Native AI Data Services') }}</p>
            <p class="layer-desc">
              {{ t(
                '不想自己搭记忆和知识组件？DBay 提供完全发挥 Lakebase 能力的原生服务 — Agent 通过 MCP / Skill 直接对接，多库合一。',
                'Don\'t want to build your own stack? DBay provides native services — connect via MCP/Skill, all-in-one.'
              ) }}
            </p>
            <div class="layer-tags">
              <span>{{ t('MCP 一键接入', 'MCP one-click') }}</span>
              <span>{{ t('记忆自动提取', 'Auto memory extraction') }}</span>
              <span>{{ t('文档向量化', 'Doc vectorization') }}</span>
              <span>{{ t('混合检索', 'Hybrid search') }}</span>
            </div>
            <div class="layer-link-group">
              <router-link to="/product/knowledge" class="layer-link">{{ t('知识库', 'Knowledge') }} &rarr;</router-link>
              <router-link to="/product/memory" class="layer-link">{{ t('记忆库', 'Memory') }} &rarr;</router-link>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- Layer 3 -->
    <section class="layer">
      <div class="layer-inner">
        <div class="layer-head">
          <span class="layer-num" style="color: #7a5195">03</span>
          <span class="layer-label">{{ t('数据闭环', 'Data Loop') }}</span>
        </div>
        <div class="layer-content">
          <div class="layer-text">
            <h2>{{ t('AI 数据湖', 'AI Data Lake') }}</h2>
            <p class="layer-subtitle">{{ t('数据飞轮', 'Data Flywheel') }}</p>
            <p class="layer-desc">
              {{ t(
                '数据库、知识库、记忆库与数据湖双向联动。在数据湖上运行 Ray、Python 作业，形成数据飞轮：Agent 使用积累数据 → 数据湖分析洞察 → 优化 Agent → 积累更多高质量数据。',
                'Bidirectional sync between all data stores and the lake. Run Ray/Python jobs to form a data flywheel.'
              ) }}
            </p>
            <div class="layer-tags">
              <span>{{ t('用户行为分析', 'User behavior analysis') }}</span>
              <span>{{ t('记忆导出微调', 'Memory export for fine-tuning') }}</span>
              <span>{{ t('知识质量评估', 'Knowledge quality assessment') }}</span>
              <span>{{ t('过期数据归档', 'Stale data archiving') }}</span>
            </div>
            <router-link to="/product/datalake" class="layer-link">{{ t('了解数据湖', 'Learn about Data Lake') }} &rarr;</router-link>
          </div>
          <div class="layer-visual">
            <LayerDatalake />
          </div>
        </div>
      </div>
    </section>

    <!-- Bottom CTA -->
    <section class="prod-bottom">
      <h2>{{ t('从第一层开始', 'Start with Layer One') }}</h2>
      <p>{{ t('免费的 Serverless PostgreSQL，随时向上解锁。', 'Free Serverless PostgreSQL. Unlock more anytime.') }}</p>
      <router-link to="/" class="prod-cta">{{ t('免费试用', 'Try Free') }} &rarr;</router-link>
    </section>
  </main>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useLocale } from '../../stores/locale'
import LayerLakebase from '../landing/LayerLakebase.vue'
import LayerServices from '../landing/LayerServices.vue'
import LayerDatalake from '../landing/LayerDatalake.vue'

const { t } = useLocale()

const layer1Features = computed(() => [
  { title: t('秒级启动', 'Fast Start'), desc: t('3ms 热启动，3s 冷启动', '3ms hot, 3s cold start') },
  { title: t('存算分离', 'Disaggregated'), desc: t('独立扩展存储与计算', 'Scale storage & compute independently') },
  { title: t('时间旅行', 'Time Travel'), desc: t('精确到任意时间点回滚', 'Point-in-time recovery') },
  { title: 'pgvector', desc: t('内置向量搜索', 'Built-in vector search') },
])
</script>

<style scoped>
.product-page {
  min-height: 100vh;
  background: var(--pub-bg);
}

/* Hero */
.prod-hero {
  padding: clamp(48px, 7vw, 80px) clamp(24px, 4vw, 48px) clamp(32px, 4vw, 48px);
  max-width: 1100px;
  margin: 0 auto;
}
.prod-hero h1 {
  font-family: var(--pub-serif, serif);
  font-size: clamp(28px, 4vw, 42px);
  font-weight: 700;
  color: var(--pub-text);
  margin: 0 0 12px;
}
.prod-hero-sub {
  font-size: clamp(15px, 1.5vw, 17px);
  color: var(--pub-text-2);
  line-height: 1.7;
  max-width: 640px;
  margin: 0;
}

/* Layers */
.layer {
  padding: clamp(40px, 5vw, 64px) 0;
  border-top: 1px solid var(--pub-border);
}
.layer-alt {
  background: var(--pub-surface);
}
.layer-inner {
  max-width: 1100px;
  margin: 0 auto;
  padding: 0 clamp(24px, 4vw, 48px);
}
.layer-head {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 28px;
}
.layer-num {
  font-family: var(--pub-serif, serif);
  font-size: 36px;
  font-weight: 900;
  line-height: 1;
}
.layer-label {
  font-family: var(--pub-sans, sans-serif);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 1.5px;
  text-transform: uppercase;
  color: var(--pub-text-3);
}
.layer-content {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: clamp(28px, 4vw, 56px);
  align-items: start;
}
.layer-content-reverse {
  direction: rtl;
}
.layer-content-reverse > * {
  direction: ltr;
}
.layer-text h2 {
  font-family: var(--pub-sans, sans-serif);
  font-size: 24px;
  font-weight: 700;
  color: var(--pub-text);
  margin: 0;
}
.layer-subtitle {
  font-size: 14px;
  color: var(--pub-text-3);
  margin: 4px 0 16px;
}
.layer-desc {
  font-size: 14px;
  color: var(--pub-text-2);
  line-height: 1.7;
  margin: 0 0 20px;
}
.layer-features {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  margin-bottom: 20px;
}
.feature {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.feature strong {
  font-size: 13px;
  font-weight: 600;
  color: var(--pub-text);
}
.feature span {
  font-size: 12px;
  color: var(--pub-text-3);
}
.layer-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  margin-bottom: 20px;
}
.layer-tags span {
  font-size: 12px;
  font-weight: 500;
  padding: 4px 12px;
  border-radius: 4px;
  background: var(--pub-bg-alt);
  color: var(--pub-text-2);
}
.layer-link {
  font-family: var(--pub-sans, sans-serif);
  font-size: 14px;
  font-weight: 600;
  color: var(--pub-accent, #c67d3a);
  text-decoration: none;
  transition: opacity 0.15s;
}
.layer-link:hover { opacity: 0.7; }
.layer-link-group {
  display: flex;
  gap: 20px;
}
.layer-visual {
  display: flex;
  align-items: center;
  justify-content: center;
}
.layer-visual :deep(.layer-svg) {
  width: 100%;
  max-width: 440px;
}

/* Bottom */
.prod-bottom {
  padding: clamp(48px, 6vw, 80px) clamp(24px, 4vw, 48px);
  background: var(--pub-primary);
  text-align: center;
}
.prod-bottom h2 {
  font-family: var(--pub-serif, serif);
  font-size: clamp(22px, 3vw, 32px);
  font-weight: 700;
  color: var(--pub-btn-text);
  margin: 0 0 8px;
}
.prod-bottom p {
  font-size: 15px;
  color: var(--pub-btn-text);
  opacity: 0.7;
  margin: 0 0 24px;
}
.prod-cta {
  display: inline-block;
  font-family: var(--pub-sans, sans-serif);
  background: var(--pub-btn-text);
  color: var(--pub-primary);
  padding: 13px 28px;
  border-radius: 6px;
  font-size: 15px;
  font-weight: 600;
  text-decoration: none;
  transition: opacity 0.15s;
}
.prod-cta:hover { opacity: 0.9; }

@media (max-width: 768px) {
  .layer-content, .layer-content-reverse {
    grid-template-columns: 1fr;
    direction: ltr;
  }
  .layer-features { grid-template-columns: 1fr; }
  .layer-visual { display: none; }
}
</style>
