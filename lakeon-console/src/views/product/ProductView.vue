<template>
  <div class="ppage">
    <!-- 01 · Manifesto -->
    <section class="ppage-section ppage-section-white">
      <div class="ppage-inner">
        <router-link to="/" class="ppage-back">← {{ t('回到首页', 'Back to home') }}</router-link>
        <div class="ppage-eyebrow">{{ t('产品介绍', 'Products') }}</div>

        <h1 class="ppage-manifesto-title">
          <span class="po-title-a">{{ t('四件事，', 'Four things,') }}</span>
          <span class="po-title-b">{{ t('一套基础设施。', 'one foundation.') }}</span>
        </h1>

        <p class="ppage-manifesto-lede">
          {{ t(
            'DBay 是 Agent 工作态和学习态的数据基础设施。Agent 运行时读写事务、召回记忆、搜索知识；Agent 学习时反刍对话、跑批处理、沉淀新知。这四件事，DBay 让它们发生在同一套底座上。',
            "DBay is the data foundation for the agent runtime and the learning phase. At runtime your agent reads transactions, recalls memory, searches knowledge. Between runs it digests conversations, runs batch jobs, and lays down new knowledge. All four on a single foundation."
          ) }}
        </p>
      </div>
    </section>

    <!-- 02 · Four capability cards -->
    <section class="ppage-section">
      <div class="ppage-inner">
        <h2 class="ppage-section-title">{{ t('四种能力，同一底座', 'Four capabilities, one foundation') }}</h2>

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

          <router-link to="/product/knowledge" class="po-card">
            <div class="po-card-eyebrow">02 · {{ t('知识库', 'Knowledge') }}</div>
            <h3 class="po-card-name">Knowledge</h3>
            <p class="po-card-claim">
              {{ t('不是 RAG。是 Agent 自己在维护的一本 wiki。', "Not RAG. A wiki the agent maintains itself.") }}
            </p>
            <ul class="po-card-list">
              <li>{{ t('LLM 从源文档自动生成结构化 wiki 条目', 'LLM distills source docs into structured wiki entries') }}</li>
              <li>{{ t('Agent 读条目不读 chunk · 幻觉降', 'Agents read entries, not chunks — hallucinations drop') }}</li>
              <li>{{ t('新问题反哺 wiki · 知识持续沉淀', 'New questions feed the wiki — knowledge compounds') }}</li>
            </ul>
            <span class="po-card-more">{{ t('继续读 Knowledge', 'Read more') }} →</span>
          </router-link>

          <router-link to="/product/memory" class="po-card">
            <div class="po-card-eyebrow">03 · {{ t('记忆库', 'Memory') }}</div>
            <h3 class="po-card-name">Memory</h3>
            <p class="po-card-claim">
              {{ t('Agent 的长期记忆，会自己反思你是谁。', "Long-term memory — it thinks about who you are.") }}
            </p>
            <ul class="po-card-list">
              <li>{{ t('每晚 digest 抽 traits · 三天后出反思洞察', 'Nightly digest · insights surface within days') }}</li>
              <li>{{ t('跨 Agent · 跨 session · 跨项目', 'Shared across agents, sessions, projects') }}</li>
              <li>{{ t('本地加密 · PBKDF2 三因素密钥', 'Locally encrypted · PBKDF2 three-factor keys') }}</li>
            </ul>
            <span class="po-card-more">{{ t('继续读 Memory', 'Read more') }} →</span>
          </router-link>

          <router-link to="/product/datalake" class="po-card">
            <div class="po-card-eyebrow">04 · {{ t('数据湖', 'Datalake') }}</div>
            <h3 class="po-card-name">Datalake</h3>
            <p class="po-card-claim">
              {{ t('多模态算子，DAG 编排，算子间 zero-copy。', 'Multi-modal operators, DAG orchestration, zero-copy in between.') }}
            </p>
            <ul class="po-card-list">
              <li>{{ t('内置文本/图像/音频/视频/文档多模态算子', 'Built-in text/image/audio/video/doc operators') }}</li>
              <li>{{ t('DAG 编译成分布式 Python · shared memory', 'DAG compiles to distributed Python · shared memory') }}</li>
              <li>{{ t('Ray 热池 · ~13s 冷启动 · CCI Kata 隔离', 'Ray warm pool · ~13s cold start · CCI Kata isolation') }}</li>
            </ul>
            <span class="po-card-more">{{ t('继续读 Datalake', 'Read more') }} →</span>
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
            {{ t('Agent 执行任务时，走的是', 'When the agent runs, it goes through the') }}
            <strong>{{ t('工作态', 'working state') }}</strong>
            {{ t('：从 Lakebase 读写事务、从 Memory 召回相关记忆、从 Knowledge 搜索私有知识。这三件事共享同一套融合检索内核，结果在一次查询里打分。', ': reads and writes transactions on Lakebase, recalls matching memories from Memory, searches private knowledge in Knowledge. All three share the same fusion retrieval kernel — a single query, a single ranking.') }}
          </p>
          <p>
            {{ t('任务完成后，走的是', 'Between runs it moves into the') }}
            <strong>{{ t('学习态', 'learning state') }}</strong>
            {{ t('：Memory 每晚 digest 反刍当天对话抽出 traits；Datalake 跑多模态批处理沉淀数据集；Knowledge 自己维护 wiki 并把新问答变成新条目。这些处理完后再注入回工作态，下一次 Agent 运行时就能用。', ': Memory\'s nightly digest walks the day\'s conversations for new traits. Datalake processes multi-modal batches into datasets. Knowledge curates its wiki and turns new Q&As into new entries. All of that flows back into the working state for the next run.') }}
          </p>
          <p>
            {{ t('同一套底座让工作态和学习态可以', 'Sitting on one foundation lets working and learning state hand off to each other ') }}
            <strong>{{ t('无缝切换', 'seamlessly') }}</strong>
            {{ t('。不需要自己做 ETL，不需要跨系统对账，不需要维护胶水代码。', '. No ETL, no cross-system reconciliation, no glue code to maintain.') }}
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
