<template>
  <main class="product-page">
    <div class="hero">
      <div class="hero-inner">
        <div class="hero-marker"></div>
        <h1 class="hero-title">Lakebase</h1>
        <p class="hero-subtitle">{{ t('Serverless PostgreSQL，存算分离，自动扩缩容', 'Serverless PostgreSQL with disaggregated storage and auto-scaling') }}</p>
      </div>
    </div>

    <div class="content">
      <ul class="feature-list">
        <li v-for="feature in features" :key="feature.titleEn" class="feature-item">
          <div class="feature-body">
            <h3 class="feature-title">{{ t(feature.titleZh, feature.titleEn) }}</h3>
            <p class="feature-desc">{{ t(feature.descZh, feature.descEn) }}</p>
          </div>
        </li>
      </ul>

      <div class="cta-section">
        <router-link to="/app/databases" class="btn-cta">
          {{ t('立即试用', 'Get Started') }}
        </router-link>
        <router-link to="/product" class="btn-back">
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
  background: var(--pub-bg);
  font-family: var(--pub-sans, 'DM Sans', sans-serif);
}

.hero {
  border-top: 3px solid #1e2d3d;
  background: var(--pub-surface);
  border-bottom: 1px solid var(--pub-border);
  padding: clamp(40px, 6vw, 64px) 24px clamp(32px, 5vw, 48px);
}

.hero-inner {
  max-width: 800px;
  margin: 0 auto;
}

.hero-marker {
  width: 32px;
  height: 3px;
  background: #1e2d3d;
  margin-bottom: 20px;
}

.hero-title {
  font-family: var(--pub-serif, 'Noto Serif SC', Georgia, serif);
  font-size: clamp(1.75rem, 4vw, 2.5rem);
  font-weight: 700;
  color: var(--pub-text);
  margin: 0 0 12px;
  line-height: 1.2;
}

.hero-subtitle {
  font-family: var(--pub-sans, 'DM Sans', sans-serif);
  font-size: clamp(0.95rem, 1.5vw, 1.1rem);
  color: var(--pub-text-2);
  margin: 0;
  line-height: 1.6;
}

.content {
  max-width: 800px;
  margin: 0 auto;
  padding: 40px 24px 64px;
}

.feature-list {
  list-style: none;
  margin: 0 0 48px;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.feature-item {
  padding: 20px 24px;
  border-left: 3px solid #1e2d3d;
  background: var(--pub-surface);
  transition: background 0.15s ease;
}

.feature-item:hover {
  background: var(--pub-hover);
}

.feature-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.feature-title {
  font-family: var(--pub-sans, 'DM Sans', sans-serif);
  font-size: clamp(0.9rem, 1.2vw, 1rem);
  font-weight: 600;
  color: var(--pub-text);
  margin: 0;
}

.feature-desc {
  font-family: var(--pub-sans, 'DM Sans', sans-serif);
  font-size: clamp(0.8rem, 1.1vw, 0.9rem);
  color: var(--pub-text-2);
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
  padding: 10px 24px;
  background: var(--pub-btn-bg, #1e2d3d);
  color: #fff;
  font-family: var(--pub-sans, 'DM Sans', sans-serif);
  font-size: clamp(0.85rem, 1.1vw, 0.95rem);
  font-weight: 600;
  text-decoration: none;
  border-radius: 6px;
  transition: opacity 0.15s ease;
}

.btn-cta:hover {
  opacity: 0.85;
}

.btn-back {
  font-family: var(--pub-sans, 'DM Sans', sans-serif);
  font-size: clamp(0.8rem, 1vw, 0.9rem);
  color: var(--pub-text-3);
  text-decoration: none;
  transition: color 0.15s ease;
}

.btn-back:hover {
  color: var(--pub-accent, #c67d3a);
}

@media (max-width: 600px) {
  .feature-item {
    padding: 16px;
  }

  .cta-section {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
