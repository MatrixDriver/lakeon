<template>
  <div class="deploy-docs">
    <h1>{{ t('部署指南', 'Deployment Guide') }}</h1>
    <p class="subtitle">{{ t('三种部署模式，按需选择', 'Three deployment modes — choose what fits your needs') }}</p>

    <section class="section">
      <h2>{{ t('部署模式', 'Deployment Modes') }}</h2>
      <div class="mode-grid">
        <div v-for="mode in modes" :key="mode.key" class="mode-card" :class="{ recommended: mode.recommended }">
          <div class="mode-hd">
            <span class="mode-name">{{ mode.name }}</span>
            <span v-if="mode.recommended" class="badge">{{ t('推荐', 'Recommended') }}</span>
          </div>
          <p>{{ mode.desc }}</p>
          <ul>
            <li v-for="item in mode.points" :key="item">{{ item }}</li>
          </ul>
        </div>
      </div>
    </section>

    <section class="section">
      <h2>{{ t('云端托管（快速开始）', 'Cloud Hosted (Quick Start)') }}</h2>
      <ol class="guide-steps">
        <li>
          <strong>{{ t('注册账户', 'Sign up') }}</strong>
          <p>{{ t('在 DBay 控制台注册账户，即可获得免费额度。', 'Sign up at the DBay console to get free credits.') }}</p>
        </li>
        <li>
          <strong>{{ t('获取 API Key', 'Get API Key') }}</strong>
          <p>{{ t('在控制台 → API Keys 页面创建密钥，格式为', 'In the console → API Keys page, create a key. Keys start with') }} <code>dbay_sk_</code>.</p>
        </li>
        <li>
          <strong>{{ t('开始调用', 'Start calling') }}</strong>
          <pre class="code-block"><code>curl -X POST https://api.dbay.cloud/api/v1/ingest \
  -H "Authorization: Bearer dbay_sk_your_key" \
  -H "Content-Type: application/json" \
  -d '{"content": "Hello world", "user_id": "me"}'</code></pre>
        </li>
      </ol>
    </section>

    <section class="section">
      <h2>{{ t('本地部署（Docker）', 'Local Deployment (Docker)') }}</h2>
      <pre class="code-block"><code># docker-compose.yml
version: "3.9"
services:
  dbay-memory:
    image: ghcr.io/lakeon/dbay-memory:latest
    ports:
      - "8080:8080"
    environment:
      DATABASE_URL: postgresql://postgres:password@postgres:5432/dbay
      EMBEDDING_PROVIDER: openai
      OPENAI_API_KEY: sk-...
  postgres:
    image: pgvector/pgvector:pg17
    environment:
      POSTGRES_PASSWORD: password
      POSTGRES_DB: dbay</code></pre>
      <pre class="code-block"><code>docker compose up -d
# API available at http://localhost:8080</code></pre>
    </section>

    <section class="section">
      <h2>{{ t('环境变量', 'Environment Variables') }}</h2>
      <div class="param-table">
        <div class="param-row header">
          <span>{{ t('变量', 'Variable') }}</span>
          <span>{{ t('必填', 'Required') }}</span>
          <span>{{ t('说明', 'Description') }}</span>
        </div>
        <div v-for="env in envVars" :key="env.name" class="param-row">
          <code>{{ env.name }}</code>
          <span :class="env.required ? 'req-yes' : 'req-no'">{{ env.required ? t('是', 'Yes') : t('否', 'No') }}</span>
          <span>{{ env.desc }}</span>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useLocale } from '../../stores/locale'

const { t } = useLocale()

const modes = computed(() => [
  {
    key: 'cloud',
    name: t('云端托管', 'Cloud Hosted'),
    recommended: true,
    desc: t('由 DBay 管理基础设施，零运维成本。', 'DBay manages all infrastructure. Zero ops overhead.'),
    points: [
      t('即开即用，无需配置', 'Zero setup — start in minutes'),
      t('按量计费', 'Pay as you go'),
      t('自动扩缩容', 'Auto-scaling'),
    ],
  },
  {
    key: 'local',
    name: t('本地部署', 'Self-hosted'),
    recommended: false,
    desc: t('在你自己的基础设施上运行，数据完全不离境。', 'Run on your own infrastructure. Data never leaves your environment.'),
    points: [
      t('数据主权', 'Full data sovereignty'),
      t('支持 Docker / Kubernetes', 'Docker / Kubernetes ready'),
      t('离线可用', 'Works offline'),
    ],
  },
  {
    key: 'hybrid',
    name: t('混合部署', 'Hybrid'),
    recommended: false,
    desc: t('敏感数据本地存储，API 调用路由到云端。', 'Sensitive data stored locally, API routing to cloud.'),
    points: [
      t('合规场景适用', 'For compliance requirements'),
      t('灵活路由', 'Flexible routing'),
      t('统一 SDK', 'Unified SDK'),
    ],
  },
])

const envVars = computed(() => [
  { name: 'DATABASE_URL', required: true, desc: t('PostgreSQL 连接串（含 pgvector 扩展）', 'PostgreSQL connection string (requires pgvector extension)') },
  { name: 'EMBEDDING_PROVIDER', required: true, desc: t('嵌入模型提供商：openai / anthropic / local', 'Embedding provider: openai / anthropic / local') },
  { name: 'OPENAI_API_KEY', required: false, desc: t('OpenAI API Key（使用 OpenAI 嵌入时必填）', 'OpenAI API Key (required when using OpenAI embeddings)') },
  { name: 'ANTHROPIC_API_KEY', required: false, desc: t('Anthropic API Key（使用 Anthropic 嵌入时必填）', 'Anthropic API Key (required when using Anthropic embeddings)') },
  { name: 'PORT', required: false, desc: t('监听端口，默认 8080', 'Listen port, default 8080') },
  { name: 'LOG_LEVEL', required: false, desc: t('日志级别：debug / info / warn / error', 'Log level: debug / info / warn / error') },
])
</script>

<style scoped>
.deploy-docs h1 { font-size: 28px; font-weight: 700; margin: 0 0 8px; }
.subtitle { color: #888; font-size: 15px; margin-bottom: 40px; }
.section { margin-bottom: 40px; }
.section h2 { font-size: 18px; font-weight: 600; margin-bottom: 16px; padding-bottom: 8px; border-bottom: 1px solid #1a1a1a; }
.section p { font-size: 14px; color: #888; line-height: 1.6; }
.section code { background: #1a1a1a; padding: 1px 6px; border-radius: 3px; color: #a78bfa; font-family: monospace; font-size: 13px; }
.mode-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-bottom: 0; }
.mode-card { background: #111; border: 1px solid #1a1a1a; border-radius: 8px; padding: 16px; }
.mode-card.recommended { border-color: #7c3aed44; background: #1a1a2e; }
.mode-hd { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.mode-name { font-size: 14px; font-weight: 600; color: #e5e5e5; }
.badge { font-size: 10px; background: #7c3aed22; color: #a78bfa; padding: 1px 6px; border-radius: 4px; }
.mode-card p { font-size: 13px; color: #888; margin-bottom: 10px; }
.mode-card ul { padding-left: 16px; margin: 0; }
.mode-card li { font-size: 12px; color: #666; line-height: 1.8; }
.guide-steps { padding-left: 20px; display: flex; flex-direction: column; gap: 16px; }
.guide-steps li { font-size: 14px; color: #ccc; }
.guide-steps strong { display: block; margin-bottom: 4px; }
.guide-steps p { margin: 4px 0 8px; }
.code-block {
  background: #0d0d0d; border: 1px solid #1a1a1a; border-radius: 6px;
  padding: 12px 14px; font-size: 12px; color: #a78bfa;
  overflow-x: auto; margin-bottom: 12px; font-family: monospace; white-space: pre;
}
.param-table { border: 1px solid #1a1a1a; border-radius: 6px; overflow: hidden; font-size: 13px; }
.param-row { display: grid; grid-template-columns: 200px 60px 1fr; gap: 1px; background: #1a1a1a; }
.param-row.header { background: #111; }
.param-row > * { background: #0d0d0d; padding: 8px 10px; }
.param-row.header > * { background: #111; color: #555; font-size: 11px; font-weight: 600; text-transform: uppercase; }
.param-row code { font-family: monospace; color: #a78bfa; background: transparent; padding: 8px 10px; }
.req-yes { color: #fb923c; font-size: 11px; font-weight: 600; }
.req-no { color: #444; font-size: 11px; }
@media (max-width: 768px) {
  .mode-grid { grid-template-columns: 1fr; }
}
</style>
