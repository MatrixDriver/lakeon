<template>
  <main class="product-detail">
    <div class="hero">
      <div class="hero-inner">
        <h1 class="hero-title">🐘 Lakebase</h1>
        <p class="hero-subtitle">{{ t('Serverless PostgreSQL，存算分离，自动扩缩容', 'Serverless PostgreSQL with disaggregated storage and auto-scaling') }}</p>
      </div>
    </div>

    <div class="content">
      <div class="features">
        <div v-for="feature in features" :key="feature.icon" class="feature-card">
          <div class="feature-icon">{{ feature.icon }}</div>
          <div class="feature-body">
            <h3 class="feature-title">{{ t(feature.titleZh, feature.titleEn) }}</h3>
            <p class="feature-desc">{{ t(feature.descZh, feature.descEn) }}</p>
          </div>
        </div>
      </div>

      <div class="cta-section">
        <router-link to="/app/databases" class="btn-cta">
          {{ t('立即试用', 'Get Started') }} →
        </router-link>
        <router-link to="/product" class="btn-back">
          ← {{ t('返回产品总览', 'Back to Products') }}
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
    icon: '⚡',
    titleZh: '秒级启动',
    titleEn: 'Fast Start',
    descZh: '3ms 热启动，3s 冷启动，计算资源按需唤醒',
    descEn: '3ms hot start, 3s cold start — compute wakes up on demand',
  },
  {
    icon: '📐',
    titleZh: '存算分离',
    titleEn: 'Disaggregated',
    descZh: '存储与计算独立扩展，按需自动伸缩',
    descEn: 'Independent storage and compute scaling',
  },
  {
    icon: '🔀',
    titleZh: '数据库分支',
    titleEn: 'Branching',
    descZh: '像 Git 一样管理数据版本，零成本创建分支',
    descEn: 'Git-like data version management with zero-cost branching',
  },
  {
    icon: '⏱',
    titleZh: '时间旅行',
    titleEn: 'Time Travel',
    descZh: '多版本管理与回滚，精确到任意时间点',
    descEn: 'Version management & rollback to any point in time',
  },
  {
    icon: '🔒',
    titleZh: '多租户隔离',
    titleEn: 'Multi-tenant',
    descZh: '租户级别的数据和计算隔离，安全可靠',
    descEn: 'Tenant-level data and compute isolation',
  },
  {
    icon: '🤖',
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
  background: var(--pub-surface, #fff);
}

.hero {
  border-top: 4px solid transparent;
  border-image: linear-gradient(90deg, #0073e6, #005bb5) 1;
  background: var(--pub-surface, #fff);
  padding: 56px 24px 48px;
}

.hero-inner {
  max-width: 800px;
  margin: 0 auto;
}

.hero-title {
  font-size: 2.4rem;
  font-weight: 700;
  color: var(--pub-text, #0f172a);
  margin: 0 0 12px;
  line-height: 1.2;
}

.hero-subtitle {
  font-size: 1.1rem;
  color: var(--pub-text-2, #475569);
  margin: 0;
  line-height: 1.6;
}

.content {
  max-width: 800px;
  margin: 0 auto;
  padding: 40px 24px 64px;
}

.features {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
  margin-bottom: 48px;
}

.feature-card {
  background: var(--pub-bg-alt, #f8fafc);
  border-radius: 12px;
  padding: 24px;
  display: flex;
  gap: 16px;
  align-items: flex-start;
  border: 1px solid var(--pub-border, #e2e8f0);
  transition: box-shadow 0.15s ease;
}

.feature-card:hover {
  box-shadow: 0 4px 16px rgba(0, 115, 230, 0.08);
}

.feature-icon {
  font-size: 1.6rem;
  line-height: 1;
  flex-shrink: 0;
  margin-top: 2px;
}

.feature-body {
  flex: 1;
  min-width: 0;
}

.feature-title {
  font-size: 1rem;
  font-weight: 600;
  color: var(--pub-text, #0f172a);
  margin: 0 0 6px;
}

.feature-desc {
  font-size: 0.875rem;
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
  padding: 12px 28px;
  background: linear-gradient(135deg, #0073e6, #005bb5);
  color: #fff;
  font-size: 1rem;
  font-weight: 600;
  text-decoration: none;
  border-radius: 8px;
  transition: opacity 0.15s ease, box-shadow 0.15s ease;
}

.btn-cta:hover {
  opacity: 0.9;
  box-shadow: 0 4px 16px rgba(0, 115, 230, 0.35);
}

.btn-back {
  font-size: 0.9rem;
  color: var(--pub-text-2, #475569);
  text-decoration: none;
  transition: color 0.15s ease;
}

.btn-back:hover {
  color: var(--pub-primary, #0073e6);
}

@media (max-width: 600px) {
  .hero-title {
    font-size: 1.8rem;
  }

  .features {
    grid-template-columns: 1fr;
  }

  .cta-section {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
