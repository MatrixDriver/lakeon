<template>
  <div class="ppage dp">
    <!-- Manifesto -->
    <section class="ppage-section ppage-section-white">
      <div class="ppage-inner dp-inner">
        <router-link to="/docs" class="ppage-back">← {{ t('回到文档首页', 'Back to docs') }}</router-link>
        <div class="ppage-eyebrow">{{ t('开始使用 · Console', 'Using DBay · Console') }}</div>

        <h1 class="dp-title">
          <span class="dp-title-a">{{ t('在浏览器里', 'Your DBay') }}</span>
          <span class="dp-title-b">{{ t('管理你的 DBay。', 'in the browser.') }}</span>
        </h1>

        <p class="dp-lede">
          {{ t(
            'Console 是 DBay 的图形界面。创建数据库、管理 LakebaseFS 文件目录、查看备份和日志、管理 API Key——全部在一个地方。',
            'The Console is the graphical front door to DBay. Create databases, manage LakebaseFS directories, inspect backups and logs, and manage API keys — all in one place.'
          ) }}
        </p>
      </div>
    </section>

    <!-- 你能做什么 -->
    <section class="ppage-section">
      <div class="ppage-inner dp-inner">
        <h2 class="ppage-section-title">{{ t('你能做什么', 'What you can do') }}</h2>

        <div class="dc-list">
          <div class="dc-row">
            <div class="dc-row-num">01</div>
            <div class="dc-row-body">
              <h3 class="dc-row-title">{{ t('数据库 · Dashboard', 'Databases · Dashboard') }}</h3>
              <p>{{ t(
                '每个数据库有独立 Dashboard：连接数、存储、CPU / 内存趋势、休眠状态。Serverless 数据库闲时自动睡眠，秒级唤醒。你可以看到它什么时候醒、醒的时候占了多少资源。',
                'Every database has its own dashboard: connections, storage, CPU / memory trends, sleep state. Serverless databases sleep when idle and wake in under three seconds. You can see exactly when and for how long they were active.'
              ) }}</p>
            </div>
          </div>

          <div class="dc-row">
            <div class="dc-row-num">02</div>
            <div class="dc-row-body">
              <h3 class="dc-row-title">LakebaseFS</h3>
              <p>{{ t(
                '按 folder type 管理应用文件目录。小对象可以直接进入 Lakebase，大对象进入 OBS，处理状态在 Console 中可见。',
                'Manage application file directories by folder type. Small objects can live directly in Lakebase, large objects go to OBS, and processing status is visible in the Console.'
              ) }}</p>
            </div>
          </div>

          <div class="dc-row">
            <div class="dc-row-num">03</div>
            <div class="dc-row-body">
              <h3 class="dc-row-title">{{ t('备份 · 审计 · 日志', 'Backups · audit · logs') }}</h3>
              <p>{{ t(
                '创建和恢复备份，查看审计日志、操作日志和组件健康状态。SRE 控制台负责更底层的集群与云资源视图。',
                'Create and restore backups, inspect audit logs, operation logs, and component health. The SRE console owns lower-level cluster and cloud resource views.'
              ) }}</p>
            </div>
          </div>

          <div class="dc-row">
            <div class="dc-row-num">04</div>
            <div class="dc-row-body">
              <h3 class="dc-row-title">API Key</h3>
              <p>{{ t(
                '创建和回收 API Key、查看用量，并用同一套凭据调用 REST API 或 CLI。',
                'Create and revoke API keys, inspect usage, and use the same credentials from the REST API or CLI.'
              ) }}</p>
            </div>
          </div>

          <div class="dc-row">
            <div class="dc-row-num">05</div>
            <div class="dc-row-body">
              <h3 class="dc-row-title">{{ t('数据导入', 'Data import') }}</h3>
              <p>{{ t(
                '从外部 PostgreSQL 临时连接导入数据，支持整库、选表和满足条件时的持续同步。',
                'Import data from an external PostgreSQL temporary connection, with full import, selected tables, and continuous sync when prerequisites are met.'
              ) }}</p>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- Footer -->
    <section class="ppage-section ppage-section-white">
      <div class="ppage-inner dp-inner">
        <div class="dp-footer-cards">
          <router-link to="/docs/cli" class="dp-card dp-card-ref">
            <div class="dp-card-num">{{ t('接下来', 'Next') }}</div>
            <h3 class="dp-card-title">{{ t('CLI 简介', 'The CLI') }}</h3>
            <p class="dp-card-claim">{{ t('终端里做同样的事。', 'The same things from your terminal.') }}</p>
            <span class="dp-card-more">{{ t('打开', 'Open') }} →</span>
          </router-link>
          <router-link to="/docs/rest-api" class="dp-card dp-card-ref">
            <div class="dp-card-num">{{ t('或者', 'Or') }}</div>
            <h3 class="dp-card-title">REST API</h3>
            <p class="dp-card-claim">{{ t('从服务端直接调用 Lakebase 能力。', 'Call Lakebase capabilities directly from your service.') }}</p>
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
  max-width: 1040px;
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

.dc-list {
  display: flex;
  flex-direction: column;
  gap: clamp(32px, 4vw, 56px);
}

.dc-row {
  display: grid;
  grid-template-columns: 80px 1fr;
  gap: clamp(16px, 2vw, 32px);
  align-items: baseline;
}

.dc-row-num {
  font-family: var(--font-mono);
  font-size: 13px;
  font-weight: 500;
  letter-spacing: 0.12em;
  color: var(--c-accent-text);
}

.dc-row-body {
  border-top: 1px solid var(--c-border);
  padding-top: var(--space-md);
}

.dc-row-title {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: clamp(22px, 2.2vw, 28px);
  line-height: 1.2;
  color: var(--c-primary);
  letter-spacing: -0.01em;
  margin: 0 0 var(--space-sm);
}

.dc-row-body p {
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

.dp-card:hover .dp-card-more {
  color: var(--c-accent-hover);
}

@media (max-width: 900px) {
  .dc-row {
    grid-template-columns: 1fr;
    gap: var(--space-sm);
  }
  .dp-footer-cards {
    grid-template-columns: 1fr;
  }
}
</style>
