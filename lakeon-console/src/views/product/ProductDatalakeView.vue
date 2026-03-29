<template>
  <main class="product-page">
    <section class="hero">
      <div class="hero-accent"></div>
      <div class="hero-inner">
        <h1 class="hero-title">{{ t('AI 数据湖', 'AI Data Lake') }}</h1>
        <p class="hero-subtitle">{{ t(
          '数据处理 + 训练 + 飞轮',
          'Data Processing + Training + Flywheel'
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
        <router-link to="/app/datalake" class="cta-button">
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
    titleZh: 'Python / Ray 任务调度',
    titleEn: 'Task Scheduling',
    descZh: '提交 Python 和 Ray 分布式作业',
    descEn: 'Submit Python and Ray distributed jobs',
  },
  {
    titleZh: 'Dataset 导出',
    titleEn: 'Dataset Export',
    descZh: '导出为 Parquet 格式',
    descEn: 'Export to Parquet format',
  },
  {
    titleZh: '模型微调',
    titleEn: 'Fine-tuning',
    descZh: '支持模型微调工作流',
    descEn: 'Model fine-tuning workflow support',
  },
  {
    titleZh: 'Kata VM 隔离',
    titleEn: 'Kata Isolation',
    descZh: '作业级安全隔离',
    descEn: 'Job-level security isolation',
  },
  {
    titleZh: '数据飞轮',
    titleEn: 'Data Flywheel',
    descZh: 'DB 与数据湖双向数据流转',
    descEn: 'Bidirectional data flow between DB and Data Lake',
  },
  {
    titleZh: '增量 CDC',
    titleEn: 'Incremental CDC',
    descZh: '增量变更捕获与调度',
    descEn: 'Incremental change data capture and scheduling',
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
  background: #6d28d9;
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
  border-color: #6d28d9;
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
  background: #6d28d9;
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
  background: #6d28d9;
  color: #fff;
  font-family: var(--pub-sans);
  font-size: 15px;
  font-weight: 600;
  text-decoration: none;
  border-radius: 100px;
  transition: background 0.15s;
}

.cta-button:hover {
  background: #5b21b6;
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
