<template>
  <main class="product-page">
    <div class="accent-line"></div>

    <div class="content">
      <!-- Hero -->
      <section class="hero">
        <h1 class="page-title">Lakebase</h1>
        <p class="page-subtitle">{{ t('Serverless PostgreSQL，存算分离，自动扩缩容', 'Serverless PostgreSQL with disaggregated storage and auto-scaling') }}</p>
      </section>

      <!-- Features -->
      <section class="features">
        <div v-for="feature in features" :key="feature.titleEn" class="feature">
          <div class="feature-accent"></div>
          <div class="feature-body">
            <h3 class="feature-title">{{ t(feature.titleZh, feature.titleEn) }}</h3>
            <p class="feature-desc">{{ t(feature.descZh, feature.descEn) }}</p>
          </div>
        </div>
      </section>

      <!-- CTA -->
      <section class="cta">
        <router-link to="/app/databases" class="btn-primary">{{ t('立即试用', 'Get Started') }} &rarr;</router-link>
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
    titleZh: '秒级启动',
    titleEn: 'Fast Start',
    descZh: '3ms 热启动，3s 冷启动，计算资源按需唤醒',
    descEn: '3ms hot start, 3s cold start — compute wakes up on demand',
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
    descEn: 'Version management & rollback to any point in time',
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
  background: var(--pub-bg, #fafaf7);
  color: var(--pub-text, #18181b);
}

.accent-line {
  height: 2px;
  background: #18181b;
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
  background: #18181b;
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
