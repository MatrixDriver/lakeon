<template>
  <main class="product-detail">
    <div class="hero">
      <div class="hero-inner">
        <h1 class="hero-title">Lakebase</h1>
        <p class="hero-subtitle">{{ t('Serverless PostgreSQL，存算分离，自动扩缩容', 'Serverless PostgreSQL with disaggregated storage and auto-scaling') }}</p>
      </div>
    </div>

    <div class="content">
      <div class="features">
        <div v-for="feature in features" :key="feature.titleEn" class="feature-card">
          <div class="feature-body">
            <h3 class="feature-title">{{ t(feature.titleZh, feature.titleEn) }}</h3>
            <p class="feature-desc">{{ t(feature.descZh, feature.descEn) }}</p>
          </div>
        </div>
      </div>

      <div class="cta-section">
        <router-link to="/app/databases" class="btn-cta">
          {{ t('立即试用', 'Get Started') }}
        </router-link>
        <router-link to="/product" class="btn-back">
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
    titleZh: '秒级启动',
    titleEn: 'Fast Start',
    descZh: '3ms 热启动，3s 冷启动，计算资源按需唤醒',
    descEn: '3ms hot start, 3s cold start — compute wakes up on demand',
  },
  {
    titleZh: '存算分离',
    titleEn: 'Disaggregated',
    descZh: '存储与计算独立扩展，按需自动伸缩',
    descEn: 'Independent storage and compute scaling',
  },
  {
    titleZh: '数据库分支',
    titleEn: 'Branching',
    descZh: '像 Git 一样管理数据版本，零成本创建分支',
    descEn: 'Git-like data version management with zero-cost branching',
  },
  {
    titleZh: '时间旅行',
    titleEn: 'Time Travel',
    descZh: '多版本管理与回滚，精确到任意时间点',
    descEn: 'Version management & rollback to any point in time',
  },
  {
    titleZh: '多租户隔离',
    titleEn: 'Multi-tenant',
    descZh: '租户级别的数据和计算隔离，安全可靠',
    descEn: 'Tenant-level data and compute isolation',
  },
  {
    titleZh: 'AI SQL 助手',
    titleEn: 'AI SQL',
    descZh: '自然语言生成 SQL，降低数据库使用门槛',
    descEn: 'Natural language to SQL',
  },
]
</script>

<style scoped>
.product-detail {
  min-height: 100vh;
  background: var(--pub-bg, #fff);
}

.hero {
  background: var(--pub-surface, #fff);
  padding: 72px 24px 64px;
}

.hero-inner {
  max-width: 960px;
  margin: 0 auto;
}

.hero-title {
  font-size: 48px;
  font-weight: 700;
  color: var(--pub-text, #0f172a);
  margin: 0 0 12px;
  letter-spacing: -0.02em;
  line-height: 1.15;
}

.hero-subtitle {
  font-size: 15px;
  color: var(--pub-text-2, #475569);
  margin: 0;
  line-height: 1.6;
}

.content {
  max-width: 960px;
  margin: 0 auto;
  padding: 48px 24px 64px;
}

.features {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
  margin-bottom: 48px;
}

.feature-card {
  background: var(--pub-surface, #fff);
  border-radius: 10px;
  padding: 24px 24px 24px 28px;
  border: 1px solid var(--pub-border, #e2e8f0);
  border-left: 4px solid var(--pub-primary, #0073e6);
  transition: box-shadow 0.2s ease;
}

.feature-card:hover {
  box-shadow: 0 4px 16px rgba(0, 115, 230, 0.08);
}

.feature-body {
  flex: 1;
  min-width: 0;
}

.feature-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--pub-text, #0f172a);
  margin: 0 0 6px;
}

.feature-desc {
  font-size: 14px;
  color: var(--pub-text-2, #475569);
  margin: 0;
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
  padding: 12px 32px;
  background: var(--pub-primary, #0073e6);
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  text-decoration: none;
  border-radius: 100px;
  transition: opacity 0.2s ease;
}

.btn-cta:hover {
  opacity: 0.85;
}

.btn-back {
  font-size: 14px;
  color: var(--pub-text-2, #475569);
  text-decoration: none;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  transition: color 0.2s ease;
}

.btn-back:hover {
  color: var(--pub-primary, #0073e6);
}

@media (max-width: 600px) {
  .hero { padding: 48px 20px 40px; }
  .hero-title { font-size: 32px; }
  .features { grid-template-columns: 1fr; }
  .cta-section { flex-direction: column; align-items: flex-start; }
}
</style>
