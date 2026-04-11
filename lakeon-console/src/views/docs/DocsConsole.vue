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
            'Console 是 DBay 的图形界面。创建数据库、浏览知识库、看记忆里的反思洞察、跑数据湖流水线、管理 API Key——全部在一个地方。',
            'The Console is the graphical front door to DBay. Create databases, browse knowledge, inspect the reflections in your memory, run datalake pipelines, manage API keys — all in one place.'
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
              <h3 class="dc-row-title">{{ t('知识 · Wiki', 'Knowledge · Wiki') }}</h3>
              <p>{{ t(
                '上传文档，DBay 在后台生成结构化 wiki 条目和条目之间的知识链接。Console 里可以逐条读、搜索、分享给团队——也可以导出你所有的文档和 wiki。',
                'Upload documents and DBay distills them into structured wiki entries with cross-links. The Console lets you read entry by entry, search, share with teammates — and export everything you put in.'
              ) }}</p>
            </div>
          </div>

          <div class="dc-row">
            <div class="dc-row-num">03</div>
            <div class="dc-row-body">
              <h3 class="dc-row-title">{{ t('记忆 · 反思洞察', 'Memory · Reflections') }}</h3>
              <p>{{ t(
                '每晚 DBay 会反刍你的对话，提炼出你的思维方式。在 Memory 面板里可以看到这些反思洞察，按类型筛选（fact · decision · convention · procedural…），也可以手动写入或删除。API Key 和其他秘钥通过加密通道直接写入，服务器只看到密文。',
                "Every night DBay digests your conversations and distills how you think. The Memory panel shows these reflections, filterable by type (fact · decision · convention · procedural…), and lets you write or delete entries directly. API keys and other secrets enter through an encrypted channel — the server only sees ciphertext."
              ) }}</p>
            </div>
          </div>

          <div class="dc-row">
            <div class="dc-row-num">04</div>
            <div class="dc-row-body">
              <h3 class="dc-row-title">{{ t('数据湖 · 流水线', 'Datalake · Pipelines') }}</h3>
              <p>{{ t(
                '多模态批处理在 Console 里是一张 DAG 图：文本 / 图像 / 音频 / 视频 / 文档算子拖拽拼接，一次 run 生成分布式 Python 程序跑起来。Ray 热池启动，大约 13 秒冷启。',
                "Multi-modal batch processing is a DAG in the Console: drag and drop text / image / audio / video / document operators. One run compiles the DAG to a distributed Python program. Ray warm pool keeps cold start under about 13 seconds."
              ) }}</p>
            </div>
          </div>

          <div class="dc-row">
            <div class="dc-row-num">05</div>
            <div class="dc-row-body">
              <h3 class="dc-row-title">{{ t('API Key · 租户 · 用量', 'API keys · tenants · usage') }}</h3>
              <p>{{ t(
                '创建和回收 API Key、管理团队成员、看调用和资源用量。分享一份知识库给团队需要的是一次点击，不是一次集成。',
                'Create and revoke API keys, manage teammates, inspect usage. Sharing a knowledge base with your team is a click, not an integration.'
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
          <router-link to="/docs/mcp" class="dp-card dp-card-ref">
            <div class="dp-card-num">{{ t('或者', 'Or') }}</div>
            <h3 class="dp-card-title">{{ t('MCP 工具', 'MCP tools') }}</h3>
            <p class="dp-card-claim">{{ t('让你的 Agent 直接调用。', 'Let your agent call these directly.') }}</p>
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
