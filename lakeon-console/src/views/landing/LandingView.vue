<template>
  <main class="landing">
    <section class="hero">
      <div class="hero-copy">
        <p class="eyebrow">DBay Cloud</p>
        <h1>Lakebase 数据库与 LakebaseFS</h1>
        <p class="lede">
          面向应用和数据智能工作流的 Serverless PostgreSQL 底座。数据库按需启动，
          LakebaseFS 提供同租户的文件空间，状态、分支和文件都由你管理。
        </p>
        <div class="actions">
          <router-link to="/login?register=1" class="primary-action">开始使用</router-link>
          <router-link to="/product" class="secondary-action">查看产品</router-link>
        </div>
      </div>

      <div class="hero-panel" aria-label="DBay service surface">
        <div class="panel-row">
          <span class="panel-label">Database</span>
          <span class="panel-value">PostgreSQL 17</span>
        </div>
        <div class="panel-row">
          <span class="panel-label">Branch</span>
          <span class="panel-value">copy-on-write</span>
        </div>
        <div class="panel-row">
          <span class="panel-label">Compute</span>
          <span class="panel-value">按 DB / branch 启停</span>
        </div>
        <div class="panel-row">
          <span class="panel-label">LakebaseFS</span>
          <span class="panel-value">租户隔离文件空间</span>
        </div>
      </div>
    </section>

    <section class="foundation">
      <article>
        <h2>数据库</h2>
        <p>
          创建数据库、管理 branch、连接 PG 客户端、备份恢复和 PITR 都在同一套控制台完成。
          应用仍然使用标准 PostgreSQL 协议接入。
        </p>
      </article>
      <article>
        <h2>LakebaseFS</h2>
        <p>
          为每个租户提供可 API 访问的文件层，适合保存配置、导入文件、任务产物和与数据库绑定的工作目录。
        </p>
      </article>
      <article>
        <h2>API 底座</h2>
        <p>
          DBay Cloud 暴露数据库和 FS 相关 API。Knowledge、Memory、Datalake、Ray 和 Notebook
          已迁移到 dbay-agent，由独立运行时调用 DBay。
        </p>
      </article>
    </section>
  </main>
</template>

<style scoped>
.landing {
  min-height: 100vh;
  background: var(--c-bg);
  color: var(--c-text);
}

.hero {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 420px;
  gap: 72px;
  align-items: center;
  min-height: calc(100vh - 72px);
  padding: 72px clamp(32px, 7vw, 112px) 56px;
}

.hero-copy {
  max-width: 760px;
}

.eyebrow {
  margin: 0 0 22px;
  font-size: 13px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--c-accent-text);
  font-weight: 600;
}

h1 {
  margin: 0;
  font-family: var(--font-display);
  font-size: clamp(48px, 7vw, 88px);
  line-height: 0.98;
  font-weight: 500;
  letter-spacing: 0;
  color: var(--c-primary);
}

.lede {
  margin: 30px 0 0;
  max-width: 660px;
  font-size: 18px;
  line-height: 1.8;
  color: var(--c-text-2);
}

.actions {
  display: flex;
  gap: 14px;
  margin-top: 38px;
  flex-wrap: wrap;
}

.primary-action,
.secondary-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 44px;
  padding: 0 22px;
  border-radius: 6px;
  font-weight: 600;
  text-decoration: none;
  transition: transform 160ms ease-out, border-color 160ms ease-out, background 160ms ease-out;
}

.primary-action {
  background: var(--c-accent);
  color: #fffaf5;
}

.secondary-action {
  border: 1px solid var(--c-border);
  color: var(--c-primary);
  background: var(--c-bg);
}

.primary-action:hover,
.secondary-action:hover {
  transform: translateY(-1px);
}

.hero-panel {
  border: 1px solid var(--c-border);
  border-radius: 8px;
  background: var(--c-bg-alt);
  padding: 10px;
  box-shadow: 0 24px 60px rgba(42, 77, 106, 0.08);
}

.panel-row {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  padding: 18px 16px;
  border-bottom: 1px solid var(--c-border);
}

.panel-row:last-child {
  border-bottom: 0;
}

.panel-label {
  color: var(--c-text-3);
  font-size: 13px;
  font-weight: 600;
}

.panel-value {
  color: var(--c-text);
  font-family: var(--font-mono);
  font-size: 13px;
  text-align: right;
}

.foundation {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 24px;
  padding: 0 clamp(32px, 7vw, 112px) 88px;
}

.foundation article {
  padding: 28px;
  border: 1px solid var(--c-border);
  border-radius: 8px;
  background: var(--c-bg);
}

.foundation h2 {
  margin: 0 0 14px;
  font-family: var(--font-display);
  font-size: 26px;
  font-weight: 500;
  color: var(--c-primary);
}

.foundation p {
  margin: 0;
  line-height: 1.75;
  color: var(--c-text-2);
}

@media (max-width: 960px) {
  .hero {
    grid-template-columns: 1fr;
    gap: 40px;
  }

  .foundation {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 560px) {
  .hero {
    padding: 48px 22px 40px;
  }

  .foundation {
    padding: 0 22px 56px;
  }

  .primary-action,
  .secondary-action {
    width: 100%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .primary-action,
  .secondary-action {
    transition: none;
  }
}
</style>
