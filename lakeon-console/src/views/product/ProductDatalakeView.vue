<template>
  <main class="product-detail">
    <div class="hero">
      <div class="hero-inner">
        <h1 class="hero-title">🌊 {{ t('AI 数据湖', 'AI Data Lake') }}</h1>
        <p class="hero-subtitle">{{ t('数据处理 + 训练 + 飞轮', 'Data Processing + Training + Flywheel') }}</p>
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
        <router-link to="/app/datalake" class="btn-cta">
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
    icon: '⚙️',
    titleZh: 'Python / Ray 任务调度',
    titleEn: 'Task Scheduling',
    descZh: '提交 Python 和 Ray 分布式作业',
    descEn: 'Submit Python and Ray distributed jobs',
  },
  {
    icon: '📦',
    titleZh: 'Dataset 导出',
    titleEn: 'Dataset Export',
    descZh: '导出为 Parquet 格式',
    descEn: 'Export to Parquet format',
  },
  {
    icon: '🧬',
    titleZh: '模型微调',
    titleEn: 'Fine-tuning',
    descZh: '支持模型微调工作流',
    descEn: 'Model fine-tuning workflow support',
  },
  {
    icon: '🔒',
    titleZh: 'Kata VM 隔离',
    titleEn: 'Kata Isolation',
    descZh: '作业级安全隔离',
    descEn: 'Job-level security isolation',
  },
  {
    icon: '🔄',
    titleZh: '数据飞轮',
    titleEn: 'Data Flywheel',
    descZh: 'DB ↔ 数据湖双向数据流转',
    descEn: 'Bidirectional data flow between DB and Data Lake',
  },
  {
    icon: '📡',
    titleZh: '增量 CDC',
    titleEn: 'Incremental CDC',
    descZh: '增量变更捕获与调度',
    descEn: 'Incremental change data capture and scheduling',
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
  border-image: linear-gradient(90deg, #7b1fa2, #4a148c) 1;
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
  box-shadow: 0 4px 16px rgba(123, 31, 162, 0.08);
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
  background: linear-gradient(135deg, #7b1fa2, #4a148c);
  color: #fff;
  font-size: 1rem;
  font-weight: 600;
  text-decoration: none;
  border-radius: 8px;
  transition: opacity 0.15s ease, box-shadow 0.15s ease;
}

.btn-cta:hover {
  opacity: 0.9;
  box-shadow: 0 4px 16px rgba(123, 31, 162, 0.35);
}

.btn-back {
  font-size: 0.9rem;
  color: var(--pub-text-2, #475569);
  text-decoration: none;
  transition: color 0.15s ease;
}

.btn-back:hover {
  color: #7b1fa2;
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
