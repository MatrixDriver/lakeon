<template>
  <div class="ppage dp">
    <!-- Manifesto -->
    <section class="ppage-section ppage-section-white">
      <div class="ppage-inner dp-inner">
        <div class="ppage-eyebrow">{{ t('文档 · 概览', 'Docs · Overview') }}</div>

        <h1 class="dp-title">
          <span class="dp-title-a">{{ t('文档。', 'Documentation.') }}</span>
          <span class="dp-title-b">{{ t('在你这边。', 'On your side.') }}</span>
        </h1>

        <p class="dp-lede">
          {{ t(
            'DBay 给你两种使用方式：浏览器里的 Console 和终端里的 CLI。参考文档包含 REST API 和 Python SDK。选一个开始，或者随你的工作方式切换。',
            'DBay gives you two ways to use it: a Console in the browser and a CLI in your terminal. Reference docs cover the REST API and the Python SDK. Pick one to start, or switch between them freely.'
          ) }}
        </p>
      </div>
    </section>

    <!-- 使用方式 · three cards -->
    <section class="ppage-section">
      <div class="ppage-inner dp-inner">
        <h2 class="ppage-section-title">{{ t('两种使用方式', 'Two ways to use it') }}</h2>

        <div class="dp-grid dp-grid-2">
          <router-link to="/docs/console" class="dp-card">
            <div class="dp-card-num">01</div>
            <h3 class="dp-card-title">Console</h3>
            <p class="dp-card-claim">{{ t('浏览器里管理数据库、FS、备份、日志和 API Key。', 'Manage databases, FS, backups, logs, and API keys from the browser.') }}</p>
            <ul class="dp-card-list">
              <li>{{ t('数据库 Dashboard · 连接 · 存储 · 资源趋势', 'Database dashboard · connections · storage · resource trends') }}</li>
              <li>{{ t('LakebaseFS 文件目录和对象管理', 'LakebaseFS file directories and object management') }}</li>
              <li>{{ t('API Key 管理 · 用量 · 操作日志', 'API keys · usage · operation logs') }}</li>
            </ul>
            <span class="dp-card-more">{{ t('读 Console 文档', 'Open Console docs') }} →</span>
          </router-link>

          <router-link to="/docs/cli" class="dp-card">
            <div class="dp-card-num">02</div>
            <h3 class="dp-card-title">CLI</h3>
            <p class="dp-card-claim">{{ t('终端里的 DBay。一行命令搞定大多数事。', 'DBay in your terminal. Most things in a single command.') }}</p>
            <ul class="dp-card-list">
              <li>{{ t('登录 · 数据库 · branch · version · 用户', 'Login · databases · branches · versions · users') }}</li>
              <li>{{ t('导入数据 · 管理连接 · 查询状态', 'Import data · manage connections · inspect status') }}</li>
              <li>{{ t('面向 Lakebase core 的自动化入口', 'Automation entry point for Lakebase core') }}</li>
            </ul>
            <span class="dp-card-more">{{ t('读 CLI 文档', 'Open CLI docs') }} →</span>
          </router-link>
        </div>
      </div>
    </section>

    <!-- 参考文档 -->
    <section class="ppage-section ppage-section-white">
      <div class="ppage-inner dp-inner">
        <h2 class="ppage-section-title">{{ t('参考文档', 'Reference') }}</h2>

        <div class="dp-grid dp-grid-2">
          <router-link to="/docs/rest-api" class="dp-card dp-card-ref">
            <div class="dp-card-num">01</div>
            <h3 class="dp-card-title">REST API</h3>
            <p class="dp-card-claim">{{ t('完整 HTTP 参考：端点、参数、返回格式。', 'Full HTTP reference: endpoints, parameters, return shapes.') }}</p>
            <span class="dp-card-more">{{ t('打开', 'Open') }} →</span>
          </router-link>

          <router-link to="/docs/python-sdk" class="dp-card dp-card-ref">
            <div class="dp-card-num">02</div>
            <h3 class="dp-card-title">Python SDK</h3>
            <p class="dp-card-claim">{{ t('异步 Python 客户端，支持 OpenAI / Anthropic 嵌入模型。', 'Async Python client, supports OpenAI / Anthropic embedding models.') }}</p>
            <span class="dp-card-more">{{ t('打开', 'Open') }} →</span>
          </router-link>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'
const { t } = useLocale()
</script>

<style scoped>
.dp {
  background: var(--c-bg-alt);
}

.dp-inner {
  max-width: 1080px;
}

.dp-title {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: clamp(40px, 5vw, 72px);
  line-height: 1.05;
  letter-spacing: -0.02em;
  color: var(--c-primary);
  margin: 0 0 clamp(20px, 3vw, 32px);
}

.dp-title-a,
.dp-title-b {
  display: block;
}

.dp-title-b {
  color: var(--c-accent-text);
  font-style: italic;
  font-weight: 400;
}

.dp-lede {
  font-family: var(--font-display);
  font-weight: 400;
  font-size: clamp(18px, 2vw, 24px);
  line-height: 1.55;
  color: var(--c-text-2);
  max-width: 62ch;
  margin: 0;
}

.dp-grid {
  display: grid;
  gap: clamp(20px, 2.5vw, 32px);
}

.dp-grid-3 {
  grid-template-columns: repeat(3, 1fr);
}

.dp-grid-2 {
  grid-template-columns: repeat(2, 1fr);
}

.dp-card {
  display: flex;
  flex-direction: column;
  padding: clamp(24px, 3vw, 36px);
  background: #fff;
  border: 1px solid var(--c-border-light);
  border-radius: 8px;
  text-decoration: none;
  color: inherit;
  transition: border-color 160ms ease-out, transform 160ms ease-out;
}

.dp-card:hover {
  border-color: var(--c-text-3);
  transform: translateY(-2px);
}

.dp-card-num {
  font-family: var(--font-mono);
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 0.12em;
  color: var(--c-accent-text);
  margin-bottom: var(--space-md);
}

.dp-card-title {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: clamp(24px, 2.5vw, 32px);
  line-height: 1.15;
  color: var(--c-primary);
  letter-spacing: -0.01em;
  margin: 0 0 var(--space-md);
}

.dp-card-claim {
  font-family: var(--font-display);
  font-weight: 400;
  font-size: clamp(15px, 1.6vw, 17px);
  line-height: 1.5;
  color: var(--c-text);
  margin: 0 0 var(--space-lg);
}

.dp-card-list {
  list-style: none;
  padding: 0;
  margin: 0 0 var(--space-xl);
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  flex: 1;
}

.dp-card-list li {
  font-family: var(--font-sans);
  font-size: 13px;
  line-height: 1.55;
  color: var(--c-text-2);
  padding-left: 14px;
  position: relative;
}

.dp-card-list li::before {
  content: '—';
  position: absolute;
  left: 0;
  color: var(--c-text-3);
}

.dp-card-more {
  display: inline-flex;
  align-items: center;
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 500;
  color: var(--c-accent-text);
  transition: color 160ms ease-out;
}

.dp-card:hover .dp-card-more {
  color: var(--c-accent-hover);
}

.dp-card-ref .dp-card-claim {
  margin-bottom: var(--space-lg);
}

@media (max-width: 900px) {
  .dp-grid-3 { grid-template-columns: 1fr; }
  .dp-grid-2 { grid-template-columns: 1fr; }
}
</style>
