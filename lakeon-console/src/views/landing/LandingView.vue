<template>
  <main class="landing">
    <section class="hero">
      <div class="hero-inner">
        <p class="eyebrow">{{ t('DBay Cloud', 'DBay Cloud') }}</p>
        <h1>{{ t('Serverless PostgreSQL 与应用文件系统。', 'Serverless PostgreSQL and an application file system.') }}</h1>
        <p class="lede">
          {{ t(
            '当前 dbay.cloud 聚焦 Lakebase 和 LakebaseFS：数据库负责事务、分支、备份和查询；FS 负责文件目录、对象存储和处理状态。更高层的数据智能能力已经迁移到 dbay-agent。',
            'dbay.cloud now focuses on Lakebase and LakebaseFS: the database owns transactions, branches, backups, and queries; FS owns file directories, object storage, and processing state. Higher-level data intelligence has moved to dbay-agent.'
          ) }}
        </p>
        <div class="hero-actions">
          <router-link to="/login?register=1" class="primary">{{ t('开始使用', 'Get started') }}</router-link>
          <router-link to="/product" class="secondary">{{ t('了解产品', 'Explore product') }}</router-link>
        </div>
      </div>
    </section>

    <section class="capabilities">
      <div class="section-inner">
        <article class="capability">
          <span class="num">01</span>
          <h2>Lakebase</h2>
          <p>{{ t('兼容 PostgreSQL 生态，支持 serverless compute、branch、time travel、backup/restore 和审计。', 'Compatible with the PostgreSQL ecosystem, with serverless compute, branches, time travel, backup/restore, and audit.') }}</p>
          <router-link to="/product/lakebase">{{ t('继续了解 Lakebase', 'Read about Lakebase') }} →</router-link>
        </article>
        <article class="capability">
          <span class="num">02</span>
          <h2>LakebaseFS</h2>
          <p>{{ t('面向应用的文件目录和对象管理；元数据进入 Lakebase，大对象进入 OBS，目录类型决定后台处理方式。', 'Application file directories and object management; metadata goes into Lakebase, large objects go into OBS, and folder types drive backend processing.') }}</p>
          <router-link to="/lbfs">{{ t('进入 FS 控制台', 'Open FS console') }} →</router-link>
        </article>
      </div>
    </section>

    <section class="architecture">
      <div class="section-inner">
        <h2>{{ t('边界清晰的底座', 'A foundation with clear boundaries') }}</h2>
        <p>
          {{ t(
            'dbay.cloud 只承载 Lakebase / LakebaseFS 的控制面和数据面。高层数据智能能力进入独立的 dbay-agent 仓库与部署，并通过 API 依赖这套底座。',
            'dbay.cloud carries only the Lakebase / LakebaseFS control plane and data plane. Higher-level data intelligence capabilities live in the separate dbay-agent repository and deployment, depending on this foundation through APIs.'
          ) }}
        </p>
      </div>
    </section>
  </main>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'

const { t } = useLocale()
</script>

<style scoped>
.landing {
  min-height: 100vh;
  background: var(--c-bg-alt);
  color: var(--c-text);
}

.hero {
  min-height: 72vh;
  display: flex;
  align-items: center;
  background: var(--c-bg);
  border-bottom: 1px solid var(--c-border);
}

.hero-inner,
.section-inner {
  width: min(1120px, calc(100% - 48px));
  margin: 0 auto;
}

.eyebrow {
  margin: 0 0 18px;
  color: var(--c-accent-text);
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0;
}

h1,
h2 {
  margin: 0;
  font-family: var(--font-serif);
  color: var(--c-text);
  letter-spacing: 0;
}

h1 {
  max-width: 860px;
  font-size: 64px;
  line-height: 1.04;
  font-weight: 600;
}

.lede {
  max-width: 760px;
  margin: 28px 0 0;
  font-size: 19px;
  line-height: 1.75;
  color: var(--c-text-muted);
}

.hero-actions {
  display: flex;
  gap: 14px;
  margin-top: 36px;
  flex-wrap: wrap;
}

.primary,
.secondary,
.capability a {
  display: inline-flex;
  align-items: center;
  min-height: 42px;
  padding: 0 18px;
  border-radius: 6px;
  font-weight: 700;
  text-decoration: none;
}

.primary {
  background: var(--c-primary);
  color: var(--c-bg);
}

.secondary {
  color: var(--c-primary);
  border: 1px solid var(--c-border);
}

.capabilities,
.architecture {
  padding: 88px 0;
}

.capabilities .section-inner {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 24px;
}

.capability {
  padding: 28px;
  border: 1px solid var(--c-border);
  border-radius: 8px;
  background: var(--c-bg);
}

.num {
  display: block;
  margin-bottom: 16px;
  color: var(--c-accent-text);
  font-weight: 800;
  font-size: 13px;
}

.capability h2,
.architecture h2 {
  font-size: 34px;
  line-height: 1.15;
}

.capability p,
.architecture p {
  margin: 18px 0 0;
  font-size: 16px;
  line-height: 1.75;
  color: var(--c-text-muted);
}

.capability a {
  margin-top: 22px;
  padding: 0;
  color: var(--c-primary);
}

.architecture {
  background: var(--c-bg);
  border-top: 1px solid var(--c-border);
}

.architecture p {
  max-width: 820px;
}

@media (max-width: 760px) {
  h1 {
    font-size: 42px;
  }

  .capabilities .section-inner {
    grid-template-columns: 1fr;
  }
}
</style>
