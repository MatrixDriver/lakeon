<template>
  <div class="ppage">
    <!-- 01 · Manifesto -->
    <section class="ppage-section ppage-section-white">
      <div class="ppage-inner">
        <router-link to="/" class="ppage-back">← {{ t('回到首页', 'Back to home') }}</router-link>
        <div class="ppage-eyebrow">{{ t('产品介绍', 'Products') }}</div>

        <h1 class="ppage-manifesto-title">
          <span class="po-title-a">{{ t('Lakebase 和 FS，', 'Lakebase and FS,') }}</span>
          <span class="po-title-b">{{ t('同一套数据底座。', 'one data foundation.') }}</span>
        </h1>

        <p class="ppage-manifesto-lede">
          {{ t(
            'DBay 在当前阶段只提供 Lakebase 数据库和 LakebaseFS 文件系统：一个负责 PostgreSQL 事务与分支，一个负责面向应用的文件目录和对象管理。更高层的数据智能能力已经迁移到 dbay-agent。',
            'DBay now focuses on Lakebase database and LakebaseFS: PostgreSQL transactions and branching on one side, application file directories and object management on the other. Higher-level data intelligence has moved to dbay-agent.'
          ) }}
        </p>
      </div>
    </section>

    <!-- 02 · Capability cards -->
    <section class="ppage-section">
      <div class="ppage-inner">
        <h2 class="ppage-section-title">{{ t('两种核心能力', 'Two core capabilities') }}</h2>

        <div class="po-grid">
          <router-link to="/product/lakebase" class="po-card">
            <div class="po-card-eyebrow">01 · {{ t('数据库', 'Database') }}</div>
            <h3 class="po-card-name">Lakebase</h3>
            <p class="po-card-claim">
              {{ t('PostgreSQL，加 BM25 内核，运行在云原生存储上。', 'PostgreSQL, with a BM25 kernel, on cloud-native storage.') }}
            </p>
            <ul class="po-card-list">
              <li>{{ t('Serverless · 空闲自动休眠 · 秒级唤醒', 'Serverless · auto-sleeps · wakes in under 3s') }}</li>
              <li>{{ t('pgvector + BM25 + 图 + 时序一次检索融合', 'pgvector + BM25 + graph + temporal fusion') }}</li>
              <li>{{ t('Copy-on-write 分支 · 时间旅行', 'Copy-on-write branches · time travel') }}</li>
            </ul>
            <span class="po-card-more">{{ t('继续读 Lakebase', 'Read more') }} →</span>
          </router-link>

          <router-link to="/lbfs" class="po-card">
            <div class="po-card-eyebrow">02 · {{ t('文件系统', 'File system') }}</div>
            <h3 class="po-card-name">LakebaseFS</h3>
            <p class="po-card-claim">
              {{ t('数据库旁边的应用文件目录，支持目录类型、处理 profile 和对象生命周期。', 'Application file directories beside the database, with folder types, processing profiles, and object lifecycle.') }}
            </p>
            <ul class="po-card-list">
              <li>{{ t('按租户隔离的文件目录', 'Tenant-isolated file directories') }}</li>
              <li>{{ t('不同 folder type 对应不同后台处理', 'Folder types map to backend processing behavior') }}</li>
              <li>{{ t('元数据在 Lakebase，远端对象在 OBS', 'Metadata in Lakebase, remote objects in OBS') }}</li>
            </ul>
            <span class="po-card-more">{{ t('进入 FS', 'Open FS') }} →</span>
          </router-link>
        </div>
      </div>
    </section>

    <!-- 03 · How they connect -->
    <section class="ppage-section ppage-section-white">
      <div class="ppage-inner">
        <h2 class="ppage-section-title">{{ t('它们之间怎么协作', 'How they work together') }}</h2>

        <div class="po-prose">
          <p>
            {{ t('应用结构化数据放在', 'Structured application data lives in') }}
            <strong>Lakebase</strong>
            {{ t('：数据库、branch、备份、审计、查询历史都围绕 PostgreSQL 运行。', ': databases, branches, backups, audit, and query history all run around PostgreSQL.') }}
          </p>
          <p>
            <strong>LakebaseFS</strong>
            {{ t('管理文件和目录资产：小对象可以落在数据库，大对象进入 OBS，目录元数据和处理状态保持可查询。', 'manages file and directory assets: small objects can live in the database, large objects go to OBS, while directory metadata and processing status stay queryable.') }}
          </p>
          <p>
            {{ t('dbay-agent 会作为独立服务依赖这套底座；dbay.cloud 本身保持边界清晰，只负责数据库和 FS。', 'dbay-agent depends on this foundation as a separate service; dbay.cloud itself keeps a clear boundary and owns only the database and FS.') }}
          </p>
        </div>
      </div>
    </section>

    <!-- Footer -->
    <footer class="ppage-footer">
      <div class="ppage-footer-inner">
        <h3 class="ppage-footer-title">{{ t('下一步', 'Next steps') }}</h3>
        <div class="ppage-footer-grid">
          <router-link to="/product/lakebase" class="ppage-footer-card">
            <div class="ppage-footer-card-name">Lakebase</div>
            <div class="ppage-footer-card-tag">{{ t('Serverless PostgreSQL · 分支 · 时间旅行', 'Serverless PostgreSQL · branches · time travel') }}</div>
          </router-link>
          <router-link to="/docs" class="ppage-footer-card">
            <div class="ppage-footer-card-name">{{ t('文档', 'Docs') }}</div>
            <div class="ppage-footer-card-tag">{{ t('快速开始 · REST API · Python SDK', 'Quick start · REST API · Python SDK') }}</div>
          </router-link>
          <router-link to="/login?register=1" class="ppage-footer-card">
            <div class="ppage-footer-card-name">{{ t('开始使用', 'Get started') }}</div>
            <div class="ppage-footer-card-tag">{{ t('30 秒创建你的第一个数据库', 'Spin up your first database in 30 seconds') }}</div>
          </router-link>
        </div>
      </div>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'

const { t } = useLocale()
</script>

<style scoped>
.po-title-a,
.po-title-b {
  display: block;
}

.po-title-b {
  color: var(--c-accent-text);
}

.po-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: clamp(24px, 3vw, 40px);
}

.po-card {
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

.po-card:hover {
  border-color: var(--c-text-3);
  transform: translateY(-2px);
}

.po-card:hover .po-card-more {
  color: var(--c-accent-hover);
  transform: translateX(2px);
}

.po-card-eyebrow {
  font-family: var(--font-sans);
  font-size: 11px;
  font-weight: 500;
  text-transform: uppercase;
  letter-spacing: 0.1em;
  color: var(--c-text-3);
  margin-bottom: var(--space-sm);
}

.po-card-name {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: clamp(28px, 3.5vw, 40px);
  line-height: 1;
  color: var(--c-primary);
  letter-spacing: -0.01em;
  margin: 0 0 var(--space-md);
}

.po-card-claim {
  font-family: var(--font-display);
  font-weight: 400;
  font-size: clamp(16px, 1.8vw, 20px);
  line-height: 1.4;
  color: var(--c-text);
  margin: 0 0 var(--space-lg);
}

.po-card-list {
  list-style: none;
  padding: 0;
  margin: 0 0 var(--space-xl);
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  flex: 1;
}

.po-card-list li {
  font-family: var(--font-sans);
  font-size: 13px;
  line-height: 1.55;
  color: var(--c-text-2);
  padding-left: 14px;
  position: relative;
}

.po-card-list li::before {
  content: '—';
  position: absolute;
  left: 0;
  color: var(--c-text-3);
}

.po-card-more {
  display: inline-flex;
  align-items: center;
  font-family: var(--font-sans);
  font-size: 13px;
  font-weight: 500;
  color: var(--c-accent-text);
  transition: color 160ms ease-out, transform 160ms ease-out;
}

.po-prose {
  font-family: var(--font-sans);
  font-size: 16px;
  line-height: 1.75;
  color: var(--c-text);
  max-width: 62ch;
}

.po-prose p {
  margin: 0 0 var(--space-lg);
}

.po-prose p:last-child {
  margin-bottom: 0;
}

.po-prose strong {
  color: var(--c-text);
  font-weight: 600;
}

@media (max-width: 900px) {
  .po-grid {
    grid-template-columns: 1fr;
  }
}
</style>
