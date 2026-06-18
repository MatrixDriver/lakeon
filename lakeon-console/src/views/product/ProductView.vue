<template>
  <div class="ppage">
    <!-- 01 · Manifesto -->
    <section class="ppage-section ppage-section-white">
      <div class="ppage-inner">
        <router-link to="/" class="ppage-back">← {{ t('回到首页', 'Back to home') }}</router-link>
        <div class="ppage-eyebrow">{{ t('产品介绍', 'Products') }}</div>

        <h1 class="ppage-manifesto-title">
          <span class="po-title-a">{{ t('Lakebase 与 FS。', 'Lakebase and FS.') }}</span>
          <span class="po-title-b">{{ t('一个干净的数据底座。', 'A clean data foundation.') }}</span>
        </h1>

        <p class="ppage-manifesto-lede">
          {{ t(
            'dbay.cloud 第一阶段只提供 Lakebase 数据库和 LakebaseFS：兼容 PostgreSQL 的 serverless 数据库、分支/时间旅行，以及面向应用和 Agent 的文件系统。',
            'dbay.cloud now focuses on Lakebase and LakebaseFS: serverless PostgreSQL-compatible databases, branching, time travel, and an application/agent-facing file system.'
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
              {{ t('PostgreSQL，加 BM25 内核，长在数据湖上。', 'PostgreSQL, with a BM25 kernel, on top of a data lake.') }}
            </p>
            <ul class="po-card-list">
              <li>{{ t('Serverless · 空闲自动休眠 · 秒级唤醒', 'Serverless · auto-sleeps · wakes in under 3s') }}</li>
              <li>{{ t('pgvector + BM25 + 图 + 时序一次检索融合', 'pgvector + BM25 + graph + temporal fusion') }}</li>
              <li>{{ t('Copy-on-write 分支 · 时间旅行', 'Copy-on-write branches · time travel') }}</li>
            </ul>
            <span class="po-card-more">{{ t('继续读 Lakebase', 'Read more') }} →</span>
          </router-link>

          <router-link to="/lbfs" class="po-card">
            <div class="po-card-eyebrow">02 · {{ t('文件系统', 'File System') }}</div>
            <h3 class="po-card-name">LakebaseFS</h3>
            <p class="po-card-claim">
              {{ t('面向数据库、应用和 Agent 的统一文件目录。', 'A unified file directory for databases, applications, and agents.') }}
            </p>
            <ul class="po-card-list">
              <li>{{ t('目录和文件 API · 与租户数据库绑定', 'Directory and file APIs bound to each tenant database') }}</li>
              <li>{{ t('小文件入库 · 大对象入 OBS', 'Small files in database, large objects in OBS') }}</li>
              <li>{{ t('同一套权限和审计边界', 'Same permission and audit boundary') }}</li>
            </ul>
            <span class="po-card-more">{{ t('打开 FS', 'Open FS') }} →</span>
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
            {{ t('Lakebase 承载结构化数据和 PostgreSQL 生态访问，LakebaseFS 承载文件、目录和对象存储映射。', 'Lakebase carries structured data and PostgreSQL ecosystem access; LakebaseFS carries files, directories, and object storage mappings.') }}
          </p>
          <p>
            {{ t('知识、记忆、数据湖、Pipeline、Ray 和 Notebook 已迁移到 dbay-agent，它们通过 API 使用 dbay.cloud 提供的 Lakebase 数据底座。', 'Knowledge, memory, datalake, pipeline, Ray, and notebook now live in dbay-agent. They use dbay.cloud as the Lakebase data foundation through APIs.') }}
          </p>
          <p>
            {{ t('这种边界让 dbay.cloud 更稳定、更清晰：它只负责数据库和 FS，智能体与数据分析工作负载在独立系统中伸缩。', 'This boundary keeps dbay.cloud stable and clear: it owns database and FS, while agent and analytics workloads scale in a separate system.') }}
          </p>
        </div>
      </div>
    </section>

    <!-- Footer -->
    <footer class="ppage-footer">
      <div class="ppage-footer-inner">
        <h3 class="ppage-footer-title">{{ t('下一步', 'Next steps') }}</h3>
        <div class="ppage-footer-grid">
          <router-link to="/integrations" class="ppage-footer-card">
            <div class="ppage-footer-card-name">{{ t('集成', 'Integrations') }}</div>
            <div class="ppage-footer-card-tag">{{ t('三步接入 Claude Code · Cursor · Cline', 'Three steps to Claude Code · Cursor · Cline') }}</div>
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
