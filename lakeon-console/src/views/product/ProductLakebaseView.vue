<template>
  <div class="ppage">
    <!-- ══════════════════════════════════════════
         01 · Manifesto
         ══════════════════════════════════════════ -->
    <section class="ppage-section ppage-section-white">
      <div class="ppage-inner">
        <router-link to="/" class="ppage-back">← {{ t('回到首页', 'Back to home') }}</router-link>
        <div class="ppage-eyebrow">{{ t('能力 01 · 数据库', 'Capability 01 · Database') }}</div>

        <h1 class="ppage-manifesto-title">
          <span class="lb-title-line-1">PostgreSQL，</span>
          <span class="lb-title-line-2">{{ t('加 BM25 内核，', 'with a BM25 kernel,') }}</span>
          <span class="lb-title-line-3">{{ t('运行在云原生存储上。', 'on cloud-native storage.') }}</span>
        </h1>

        <p class="ppage-manifesto-lede">
          {{ t(
            '不是一个 Neon 的 fork，也不是一个 wrapper。我们在 Neon 的 PageServer 里加了真正的 BM25 索引，让 Lakebase 同时承担事务、全文检索、向量召回、时序查询 —— 同一组 tuple 版本，一个 copy-on-write 分支里全部可得。',
            "Not a Neon fork or a wrapper. We added a real BM25 index inside the Neon PageServer, so Lakebase handles transactions, full-text search, vector recall, and temporal queries on a single set of tuple versions inside one copy-on-write branch."
          ) }}
        </p>
      </div>
    </section>

    <!-- ══════════════════════════════════════════
         02 · Scene (editorial)
         ══════════════════════════════════════════ -->
    <section class="ppage-section">
      <div class="ppage-inner">
        <h2 class="ppage-section-title">{{ t('一个场景', 'The situation') }}</h2>

        <div class="ppage-scene">
          <p>
            {{ t(
              '你要给产品上一个"像样的搜索"。不是 SQL 的 LIKE，也不是只看向量相似度。用户输入的是自然语言 —— 可能是精确短语，可能是意图描述。最理想的召回是全文 + 向量 + 时间窗口三路融合。',
              "You're adding \"real search\" to a product. Not SQL LIKE. Not vector cosine alone. Users type natural language — precise quotes sometimes, fuzzy intent other times. The ideal recall fuses full-text + vector + a time window."
            ) }}
          </p>
          <p>
            {{ t(
              '你翻了一圈，目前的业内做法是：Postgres 存事务、Elasticsearch 存 BM25、向量库存 embedding，再写一套双写 + 数据一致性补偿 + 合并排序。',
              "You look around. The industry playbook: Postgres for transactions, Elasticsearch for BM25, a vector store for embeddings, and one more layer that double-writes, reconciles drift, and fuses rankings."
            ) }}
          </p>
          <p>
            <em>{{ t(
              '这是一个本来不应该存在的工程。',
              "That's an engineering project that should never have existed."
            ) }}</em>
          </p>
        </div>
      </div>
    </section>

    <!-- ══════════════════════════════════════════
         03 · Why the standard playbook falls short
         ══════════════════════════════════════════ -->
    <section class="ppage-section ppage-section-white">
      <div class="ppage-inner">
        <h2 class="ppage-section-title">{{ t('拼装方案的代价', 'What the glue actually costs') }}</h2>

        <div class="ppage-contrast">
          <div class="ppage-contrast-col is-faded">
            <h3>{{ t('拼 Postgres + Elasticsearch + 向量库', 'Postgres + Elasticsearch + vector store') }}</h3>
            <ul>
              <li>{{ t('三套存储，三套运维，三次扩容计算', 'Three storage systems, three ops teams, three capacity plans') }}</li>
              <li>{{ t('双写 / 三写，数据一致性靠定时对账', 'Double- or triple-writes; consistency hinges on scheduled recon jobs') }}</li>
              <li>{{ t('查询要先拉出排名再手工 rerank', 'Queries fetch rankings from each, then rerank by hand') }}</li>
              <li>{{ t('CoW 分支？只有 Postgres 有。另外两边只能 dump/restore', 'CoW branches? Only on Postgres. The other two dump-and-restore') }}</li>
              <li>{{ t('帐单：三份 compute + 三份 storage', 'Bills: three compute fleets + three storage bills') }}</li>
            </ul>
          </div>
          <div class="ppage-contrast-col is-accent">
            <h3>Lakebase</h3>
            <ul>
              <li>{{ t('一个数据库，psql 直连，标准 PostgreSQL 协议', 'One database, psql drops right in, standard PostgreSQL protocol') }}</li>
              <li>{{ t('BM25 索引在 PageServer 里 — 和表共用 tuple 版本', 'BM25 indexes live in the PageServer — same tuple versions as the table') }}</li>
              <li>{{ t('pgvector + BM25 + 图 + 时序，一次 SQL 融合', 'pgvector + BM25 + graph + temporal, fused in a single SQL') }}</li>
              <li>{{ t('Copy-on-write 分支继承所有索引，不用重建', 'CoW branches inherit every index — no rebuild') }}</li>
              <li>{{ t('Serverless：空闲时零 compute 账单', 'Serverless: zero compute charges when idle') }}</li>
            </ul>
          </div>
        </div>
      </div>
    </section>

    <!-- ══════════════════════════════════════════
         04 · Our approach (内核改动)
         ══════════════════════════════════════════ -->
    <section class="ppage-section">
      <div class="ppage-inner">
        <h2 class="ppage-section-title">{{ t('我们在 Neon 内核里做了什么', 'What we changed inside the Neon kernel') }}</h2>

        <div class="ppage-approach">
          <div class="ppage-stack">
            <div class="ppage-stack-layer">
              <span class="ppage-stack-name">{{ t('查询层', 'Query layer') }}</span>
              <span class="ppage-stack-note">{{ t('SQL · pgvector · BM25 路由', 'SQL · pgvector · BM25 routing') }}</span>
            </div>
            <div class="ppage-stack-line">↓</div>
            <div class="ppage-stack-layer is-bold">
              <span class="ppage-stack-name">PageServer + BM25</span>
              <span class="ppage-stack-note">{{ t('我们改动的层', 'The layer we changed') }}</span>
            </div>
            <div class="ppage-stack-line">↓</div>
            <div class="ppage-stack-layer">
              <span class="ppage-stack-name">Safekeepers</span>
              <span class="ppage-stack-note">{{ t('WAL 写入 · 三副本', 'WAL · triple replication') }}</span>
            </div>
            <div class="ppage-stack-line">↓</div>
            <div class="ppage-stack-layer">
              <span class="ppage-stack-name">OBS / S3</span>
              <span class="ppage-stack-note">{{ t('冷数据持久化', 'Cold-tier storage') }}</span>
            </div>
          </div>

          <div class="ppage-section-prose">
            <p>
              {{ t(
                'Neon 原生支持 PG 事务 · CoW 分支 · 时间旅行，但全文检索只能靠 pg_trgm 和 tsvector — 不是真 BM25，召回质量对搜索产品不够用。',
                'Stock Neon gives you PG transactions, CoW branches, and time travel, but full-text is limited to pg_trgm and tsvector — not real BM25. Not good enough for a product search.'
              ) }}
            </p>
            <p>
              {{ t('我们的改动不是在 app 层加一个 extension，而是', "Our change isn't an app-layer extension. We went into") }}
              <strong>{{ t('直接进 PageServer', 'the PageServer itself') }}</strong>
              {{ t(
                '— 让 BM25 索引与表数据共用一组 tuple 版本、共用 WAL 写入路径、共用 CoW 分支。索引不再是外挂，而是存储层的一等公民。',
                "— the BM25 index now shares tuple versions, the same WAL write path, and the same CoW branching as table data. Indexing is no longer a side-car; it's a first-class citizen of the storage layer."
              ) }}
            </p>
            <p>
              {{ t(
                '副作用：你在一个 copy-on-write 分支上做的任何全文索引改动，瞬间对该分支可见，对其他分支零影响。这是 Elasticsearch 外挂方案永远做不到的事情。',
                'A side benefit: any BM25 change you make inside a CoW branch is visible there immediately and invisible to other branches. External Elasticsearch can never give you that.'
              ) }}
            </p>

            <code class="ppage-code">-- {{ t('在一张表上同时建 vector + BM25 索引', 'Create vector + BM25 indexes on one table') }}
CREATE INDEX ON docs USING hnsw (embedding vector_cosine_ops);
CREATE INDEX ON docs USING bm25 (content);

-- {{ t('单条 SQL 四路融合检索', 'One SQL, four-way fusion retrieval') }}
SELECT id, fusion_score(
  bm25(content, 'refresh token'),
  vector(embedding, :query_vec),
  graph(related_to, :doc_id),
  recency(created_at, '7d')
) AS score
FROM docs
ORDER BY score DESC
LIMIT 10;</code>
          </div>
        </div>
      </div>
    </section>

    <!-- ══════════════════════════════════════════
         05 · Today you can do
         ══════════════════════════════════════════ -->
    <section class="ppage-section ppage-section-white">
      <div class="ppage-inner">
        <div class="ppage-today">
          <span class="ppage-today-eyebrow">{{ t('今天你就能做', 'You can do this today') }}</span>
          <h2>{{ t('30 秒：创建一个带 BM25 的 Lakebase', 'Thirty seconds: spin up a Lakebase with BM25') }}</h2>

          <div class="ppage-today-prose">
            <p>{{ t('装上 CLI，登录你的 DBay 账号：', 'Install the CLI and sign in:') }}</p>
            <code class="ppage-code">$ pip install dbay-cli
$ dbay login</code>

            <p>{{ t('创建一个数据库，连接字符串立刻可用：', 'Create a database — the connection string is ready immediately:') }}</p>
            <code class="ppage-code">$ dbay db create my-app-db
Created. Connection:
  postgresql://u:k@db.dbay.cloud:5432/my-app-db</code>

            <p>{{ t('用任何 PostgreSQL 客户端连接：', 'Connect with any PostgreSQL client:') }}</p>
            <code class="ppage-code">$ psql $(dbay db uri my-app-db)
my-app-db=> CREATE EXTENSION vector;
my-app-db=> CREATE INDEX ON docs USING bm25 (content);</code>

            <p>
              {{ t('你现在有一个 serverless PG，空闲自动休眠，唤醒 ', 'You now have a serverless Postgres that auto-sleeps when idle, wakes in ') }}
              <strong>{{ t('小于 3 秒', 'under 3 seconds') }}</strong>
              {{ t('，BM25 和 pgvector 索引与你的表共用 tuple 版本，CoW 分支瞬时克隆。', ', and runs BM25 + pgvector on the same tuple versions as your table. CoW branches clone instantly.') }}
            </p>
          </div>

          <p class="ppage-today-aside">
            {{ t(
              '分支 · 时间旅行 · 版本 — 你不需要手动备份。每一次 commit 都进入了 WAL，你可以在任何时间点回到过去。',
              'Branches, time travel, versions — no backups to run. Every commit is in the WAL; you can return to any moment.'
            ) }}
          </p>
        </div>
      </div>
    </section>

    <!-- ══════════════════════════════════════════
         06 · Hard numbers
         ══════════════════════════════════════════ -->
    <section class="ppage-section">
      <div class="ppage-inner">
        <h2 class="ppage-section-title">{{ t('四个可以被验证的事实', 'Four facts you can verify') }}</h2>

        <div class="ppage-numbers">
          <div class="ppage-num">
            <div class="ppage-num-val">&lt;3s</div>
            <div class="ppage-num-label">{{ t('挂起 → 可查询', 'Suspend → queryable') }}</div>
            <p class="ppage-num-note">
              {{ t('任意 compute pod 从挂起状态唤醒到 SQL 可执行。', 'Any suspended compute pod wakes back up to executing SQL.') }}
            </p>
          </div>
          <div class="ppage-num">
            <div class="ppage-num-val">4</div>
            <div class="ppage-num-label">{{ t('路融合检索', 'fusion dimensions') }}</div>
            <p class="ppage-num-note">
              {{ t('vector · BM25 · graph · temporal，同一条 SQL 打分。', 'Vector · BM25 · graph · temporal, scored in one SQL.') }}
            </p>
          </div>
          <div class="ppage-num">
            <div class="ppage-num-val">0</div>
            <div class="ppage-num-label">{{ t('外部组件', 'external systems') }}</div>
            <p class="ppage-num-note">
              {{ t('不需要 Elasticsearch · Pinecone · Redis · Kafka。', 'No Elasticsearch, Pinecone, Redis, or Kafka to glue in.') }}
            </p>
          </div>
        </div>
      </div>
    </section>

    <!-- Cross-links -->
    <footer class="ppage-footer">
      <div class="ppage-footer-inner">
        <h3 class="ppage-footer-title">{{ t('继续了解', 'Keep reading') }}</h3>
        <div class="ppage-footer-grid">
          <router-link to="/lbfs" class="ppage-footer-card">
            <div class="ppage-footer-card-name">LakebaseFS</div>
            <div class="ppage-footer-card-tag">{{ t('数据库旁边的应用文件目录', 'Application file directories beside the database') }}</div>
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
.lb-title-line-1,
.lb-title-line-2,
.lb-title-line-3 {
  display: block;
}

.lb-title-line-2 {
  color: var(--c-text);
}

.lb-title-line-3 {
  color: var(--c-accent-text);
}
</style>
