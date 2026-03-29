<template>
  <main class="product-page">
    <section class="hero">
      <div class="hero-accent"></div>
      <div class="hero-inner">
        <h1 class="hero-title">Lakebase</h1>
        <p class="hero-subtitle">{{ t(
          'Serverless PostgreSQL，存算分离，自动扩缩容',
          'Serverless PostgreSQL with disaggregated storage and auto-scaling'
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
        <router-link to="/app/databases" class="cta-button">
          {{ t('立即试用', 'Get Started') }}
        </router-link>
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
    titleZh: '秒级启动',
    titleEn: 'Fast Start',
    descZh: '3ms 热启动，3s 冷启动，计算资源按需唤醒',
    descEn: '3ms hot start, 3s cold start -- compute wakes up on demand',
  },
  {
    titleZh: '存算分离',
    titleEn: 'Disaggregated Storage',
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
    descEn: 'Version management and rollback to any point in time',
  },
  {
    titleZh: '多租户隔离',
    titleEn: 'Multi-tenant Isolation',
    descZh: '租户级别的数据和计算隔离，安全可靠',
    descEn: 'Tenant-level data and compute isolation',
  },
  {
    titleZh: 'AI SQL 助手',
    titleEn: 'AI SQL Assistant',
    descZh: '自然语言生成 SQL，降低数据库使用门槛',
    descEn: 'Natural language to SQL',
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
  background: #1a2e25;
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
  border-color: #1a2e25;
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
  background: #1a2e25;
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
  background: #1a2e25;
  color: #fff;
  font-family: var(--pub-sans);
  font-size: 15px;
  font-weight: 600;
  text-decoration: none;
  border-radius: 100px;
  transition: background 0.15s;
}

.cta-button:hover {
  background: #0f1c17;
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
