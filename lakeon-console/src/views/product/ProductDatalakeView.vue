<template>
  <main class="product-page">
    <div class="accent-line"></div>

    <div class="content">
      <!-- Hero -->
      <section class="hero">
        <h1 class="page-title">{{ t('AI 数据湖', 'AI Data Lake') }}</h1>
        <p class="page-subtitle">{{ t('数据处理 + 训练 + 飞轮', 'Data Processing + Training + Flywheel') }}</p>
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
        <router-link to="/app/datalake" class="btn-primary">{{ t('立即试用', 'Get Started') }} &rarr;</router-link>
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
  background: var(--pub-bg, #fafaf7);
  color: var(--pub-text, #18181b);
}

.accent-line {
  height: 2px;
  background: #581c87;
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
  background: #581c87;
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
