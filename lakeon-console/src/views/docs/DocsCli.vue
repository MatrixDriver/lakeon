<template>
  <div class="ppage dp">
    <!-- Manifesto -->
    <section class="ppage-section ppage-section-white">
      <div class="ppage-inner dp-inner">
        <router-link to="/docs" class="ppage-back">← {{ t('回到文档首页', 'Back to docs') }}</router-link>
        <div class="ppage-eyebrow">{{ t('开始使用 · CLI', 'Using DBay · CLI') }}</div>

        <h1 class="dp-title">
          <span class="dp-title-a">dbay-cli.</span>
          <span class="dp-title-b">{{ t('终端里的 DBay。', 'DBay in your terminal.') }}</span>
        </h1>

        <p class="dp-lede">
          {{ t(
            '一个命令装上，一个命令登录，之后数据库、branch、version 和用户管理都能从终端管。',
            'One command to install, one to log in, and from then on databases, branches, versions, and users all live at your prompt.'
          ) }}
        </p>
      </div>
    </section>

    <!-- 安装 -->
    <section class="ppage-section">
      <div class="ppage-inner dp-inner">
        <h2 class="ppage-section-title">{{ t('安装', 'Install') }}</h2>

        <div class="dcli-install">
          <pre class="dcli-code"><code>pip install dbay-cli
dbay login</code></pre>
          <p class="dcli-note">
            {{ t(
              'dbay-cli 默认把配置写进',
              'dbay-cli stores its config in'
            ) }}
            <code>~/.dbay/config.json</code>{{ t('，包括 endpoint 和 API key。', ' — endpoint and API key.') }}
          </p>
        </div>
      </div>
    </section>

    <!-- 命令组 -->
    <section class="ppage-section ppage-section-white">
      <div class="ppage-inner dp-inner">
        <h2 class="ppage-section-title">{{ t('命令组', 'Command groups') }}</h2>

        <div class="dcli-list">
          <div class="dcli-row">
            <div class="dcli-row-num">01</div>
            <div class="dcli-row-body">
              <h3 class="dcli-row-title">
                <code>dbay login</code>
                <span class="dcli-row-sub">{{ t('登录', 'Authentication') }}</span>
              </h3>
              <p>{{ t('把 API Key 写进本地配置。所有后续命令都从这里取凭据。',
                'Stores the API key in your local config. Every later command reads credentials from there.') }}</p>
            </div>
          </div>

          <div class="dcli-row">
            <div class="dcli-row-num">02</div>
            <div class="dcli-row-body">
              <h3 class="dcli-row-title">
                <code>dbay db</code>
                <span class="dcli-row-sub">{{ t('数据库', 'Databases') }}</span>
              </h3>
              <p>{{ t('创建、列出、唤醒、分支、删除数据库。典型用法：',
                'Create, list, wake, branch, and delete databases. Typical usage:') }}</p>
              <pre class="dcli-code"><code>dbay db create my-project
dbay db list
dbay db branch my-project --from main --name experiment</code></pre>
            </div>
          </div>

          <div class="dcli-row">
            <div class="dcli-row-num">03</div>
            <div class="dcli-row-body">
              <h3 class="dcli-row-title">
                <code>dbay branch / version / user</code>
                <span class="dcli-row-sub">{{ t('分支、版本和用户', 'Branches, versions, and users') }}</span>
              </h3>
              <p>{{ t('管理数据库分支、时间点版本和数据库用户。', 'Manage database branches, point-in-time versions, and database users.') }}</p>
              <pre class="dcli-code"><code>dbay branch list my-project
dbay version create my-project main v1
dbay user create my-project app_user</code></pre>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- Footer -->
    <section class="ppage-section">
      <div class="ppage-inner dp-inner">
        <div class="dp-footer-cards">
          <router-link to="/docs/python-sdk" class="dp-card">
            <div class="dp-card-num">{{ t('或者', 'Or') }}</div>
            <h3 class="dp-card-title">Python SDK</h3>
            <p class="dp-card-claim">{{ t('从代码里调 DBay。', 'Call DBay from your code.') }}</p>
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
.dp { background: var(--c-bg-alt); }
.dp-inner { max-width: 1040px; }

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

.dp-title-a {
  font-family: var(--font-mono);
  font-weight: 500;
  font-size: clamp(32px, 4vw, 56px);
  letter-spacing: 0.01em;
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

.dcli-install {
  max-width: 720px;
}

.dcli-code {
  font-family: var(--font-mono);
  font-size: 13px;
  line-height: 1.7;
  color: var(--c-text);
  background: color-mix(in oklch, var(--c-accent) 5%, #fff);
  border: 1px solid var(--c-border-light);
  border-radius: 6px;
  padding: clamp(16px, 2vw, 24px);
  margin: var(--space-md) 0;
  overflow-x: auto;
  white-space: pre;
}

.dcli-note {
  font-family: var(--font-sans);
  font-size: 13px;
  line-height: 1.6;
  color: var(--c-text-3);
  margin: var(--space-sm) 0 0;
}

.dcli-note code {
  font-family: var(--font-mono);
  font-size: 12px;
  color: var(--c-accent-text);
  background: color-mix(in oklch, var(--c-accent) 8%, #fff);
  border-radius: 3px;
  padding: 1px 6px;
}

.dcli-list {
  display: flex;
  flex-direction: column;
  gap: clamp(32px, 4vw, 56px);
}

.dcli-row {
  display: grid;
  grid-template-columns: 80px 1fr;
  gap: clamp(16px, 2vw, 32px);
  align-items: baseline;
}

.dcli-row-num {
  font-family: var(--font-mono);
  font-size: 13px;
  font-weight: 500;
  letter-spacing: 0.12em;
  color: var(--c-accent-text);
}

.dcli-row-body {
  border-top: 1px solid var(--c-border);
  padding-top: var(--space-md);
}

.dcli-row-title {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: clamp(20px, 2vw, 24px);
  line-height: 1.25;
  color: var(--c-primary);
  letter-spacing: -0.01em;
  margin: 0 0 var(--space-sm);
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: var(--space-sm);
}

.dcli-row-title code {
  font-family: var(--font-mono);
  font-size: inherit;
  font-weight: 500;
  color: var(--c-accent-text);
  background: none;
  padding: 0;
}

.dcli-row-sub {
  font-family: var(--font-sans);
  font-size: 11px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.1em;
  color: var(--c-text-3);
}

.dcli-row-body p {
  font-family: var(--font-sans);
  font-size: 15px;
  line-height: 1.7;
  color: var(--c-text-2);
  max-width: 72ch;
  margin: 0;
}

.dp-footer-cards {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: clamp(20px, 2.5vw, 32px);
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
  text-transform: uppercase;
}

.dp-card-title {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: clamp(22px, 2.2vw, 28px);
  line-height: 1.15;
  color: var(--c-primary);
  letter-spacing: -0.01em;
  margin: 0 0 var(--space-sm);
}

.dp-card-claim {
  font-family: var(--font-display);
  font-weight: 400;
  font-size: 15px;
  line-height: 1.5;
  color: var(--c-text);
  margin: 0 0 var(--space-lg);
}

.dp-card-more {
  display: inline-flex;
  align-items: center;
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 500;
  color: var(--c-accent-text);
}

@media (max-width: 900px) {
  .dcli-row {
    grid-template-columns: 1fr;
    gap: var(--space-sm);
  }
  .dp-footer-cards {
    grid-template-columns: 1fr;
  }
}
</style>
