<template>
  <div class="ppage dp">
    <section class="ppage-section ppage-section-white">
      <div class="ppage-inner dp-inner">
        <router-link to="/docs" class="ppage-back">← {{ t('回到文档首页', 'Back to docs') }}</router-link>
        <div class="ppage-eyebrow">{{ t('参考文档 · REST API', 'Reference · REST API') }}</div>
        <h1 class="dp-title">REST API</h1>
        <p class="dp-lede">
          {{ t('Lakebase core HTTP API。Base URL:', 'Lakebase core HTTP API. Base URL:') }}
          <code class="dp-inline-code">https://api.dbay.cloud/api/v1</code>
        </p>
      </div>
    </section>

    <section class="ppage-section">
      <div class="ppage-inner dp-inner">
        <h2 class="ppage-section-title">{{ t('认证', 'Authentication') }}</h2>
        <p class="dp-body">{{ t('所有请求需在 Authorization 请求头中携带 API Key。', 'All requests require an API key in the Authorization header.') }}</p>
        <pre class="code-block"><code>Authorization: Bearer dbay_sk_your_api_key_here</code></pre>

        <div class="endpoint-grid">
          <article v-for="ep in endpoints" :key="ep.path" class="endpoint-card">
            <div class="endpoint-head">
              <span class="method">{{ ep.method }}</span>
              <code>{{ ep.path }}</code>
            </div>
            <p>{{ ep.desc }}</p>
            <pre class="code-block"><code>{{ ep.example }}</code></pre>
          </article>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useLocale } from '../../stores/locale'

const { t } = useLocale()

const endpoints = computed(() => [
  {
    method: 'GET',
    path: '/databases',
    desc: t('列出当前租户的数据库。', 'List databases for the current tenant.'),
    example: 'curl -H "Authorization: Bearer dbay_sk_..." https://api.dbay.cloud/api/v1/databases',
  },
  {
    method: 'POST',
    path: '/databases',
    desc: t('创建一个 Lakebase 数据库。', 'Create a Lakebase database.'),
    example: `curl -X POST https://api.dbay.cloud/api/v1/databases \\
  -H "Authorization: Bearer dbay_sk_..." \\
  -H "Content-Type: application/json" \\
  -d '{"name":"app","compute_size":"1cu"}'`,
  },
  {
    method: 'POST',
    path: '/databases/{id}/branches',
    desc: t('从现有分支创建新 branch。', 'Create a new branch from an existing branch.'),
    example: `curl -X POST https://api.dbay.cloud/api/v1/databases/db_x/branches \\
  -H "Authorization: Bearer dbay_sk_..." \\
  -H "Content-Type: application/json" \\
  -d '{"name":"experiment","parent_branch_id":"br_main"}'`,
  },
  {
    method: 'GET',
    path: '/lbfs/folders',
    desc: t('列出 LakebaseFS 文件夹。', 'List LakebaseFS folders.'),
    example: 'curl -H "Authorization: Bearer dbay_sk_..." https://api.dbay.cloud/api/v1/lbfs/folders',
  },
])
</script>

<style scoped>
.dp { background: var(--c-bg-alt); }
.dp-inner { max-width: 1040px; }
.dp-title {
  font-family: var(--font-display);
  font-weight: 500;
  font-size: clamp(40px, 5vw, 72px);
  line-height: 1.05;
  color: var(--c-primary);
  margin: 0 0 var(--space-lg);
}
.dp-lede,
.dp-body {
  font-size: 18px;
  line-height: 1.8;
  color: var(--c-text-muted);
}
.dp-inline-code,
.code-block {
  font-family: var(--font-mono);
}
.code-block {
  margin: var(--space-md) 0 0;
  padding: var(--space-md);
  border: 1px solid var(--c-border);
  border-radius: 8px;
  overflow-x: auto;
  background: #fff;
}
.endpoint-grid {
  display: grid;
  gap: var(--space-lg);
  margin-top: var(--space-xl);
}
.endpoint-card {
  padding: var(--space-lg);
  border: 1px solid var(--c-border);
  border-radius: 8px;
  background: #fff;
}
.endpoint-head {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}
.method {
  color: var(--c-accent-text);
  font-weight: 800;
}
</style>
