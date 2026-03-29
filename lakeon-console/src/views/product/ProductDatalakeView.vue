<template>
  <main class="product-page">
    <div class="hero">
      <div class="hero-inner">
        <div class="hero-marker"></div>
        <h1 class="hero-title">{{ t('AI 数据湖', 'AI Data Lake') }}</h1>
        <p class="hero-subtitle">{{ t('数据处理 + 训练 + 飞轮', 'Data Processing + Training + Flywheel') }}</p>
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
        <router-link to="/app/datalake" class="btn-cta">
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
    descZh: 'DB ↔ 数据湖双向数据流转',
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
  font-family: var(--pub-sans, 'DM Sans', sans-serif);
}

.hero {
  border-top: 3px solid #7a5195;
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
  background: #7a5195;
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
  border-left: 3px solid #7a5195;
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
