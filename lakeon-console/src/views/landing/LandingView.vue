<template>
  <div class="landing">
    <!-- Hero -->
    <section class="hero">
      <p class="hero-eyebrow">{{ t('AGENT 时代的数据基础设施', 'DATA INFRASTRUCTURE FOR THE AGENT ERA') }}</p>
      <h1 class="hero-title">
        {{ t('让你的 Agent', 'Give Your Agent') }}<br>
        {{ t('拥有完整的数据能力', 'Complete Data Power') }}
      </h1>
      <p class="hero-subtitle">
        {{ t(
          '一个平台，赋予你的 Agent 存储、检索、记忆和分析的全部能力。从 Serverless PostgreSQL 开始，按需扩展到知识库、记忆库与数据湖。',
          'One platform giving your agents the power to store, retrieve, remember, and analyze. Start with Serverless PostgreSQL, scale to knowledge, memory, and data lake.'
        ) }}
      </p>
      <div class="hero-actions">
        <button class="action-primary" @click="startTrial" :disabled="trialLoading">
          {{ trialLoading ? t('创建中...', 'Creating...') : t('立即试用', 'Try Now') }}
        </button>
        <router-link to="/docs" class="action-link">{{ t('阅读文档', 'Read the docs') }}</router-link>
      </div>
      <p class="hero-hint">{{ t('无需注册，30 秒获得一个数据库', 'No signup needed. Get a database in 30 seconds.') }}</p>
    </section>

    <!-- Divider -->
    <div class="section-rule"><div class="rule-inner"></div></div>

    <!-- Products -->
    <section class="products">
      <h2 class="products-heading">{{ t('四个模块，按需解锁', 'Four modules, unlock on demand') }}</h2>
      <div class="product-list">
        <router-link to="/product#lakebase" class="product-item">
          <div class="product-content">
            <h3 class="product-title">Lakebase</h3>
            <p class="product-desc">{{ t('Serverless PostgreSQL — 按需弹性伸缩，空闲自动休眠，内置 pgvector 与时间旅行', 'Serverless PostgreSQL with elastic scaling, auto-sleep, built-in pgvector and time travel') }}</p>
          </div>
          <span class="product-tags">PostgreSQL / pgvector / Serverless</span>
        </router-link>
        <router-link to="/product#knowledge" class="product-item">
          <div class="product-content">
            <h3 class="product-title">{{ t('知识库', 'Knowledge Base') }}</h3>
            <p class="product-desc">{{ t('文档上传、自动切片、向量化检索 — Agent 的外部知识引擎', 'Document ingestion, auto-chunking, vector retrieval — external knowledge engine for agents') }}</p>
          </div>
          <span class="product-tags">RAG / Embedding / {{ t('文档管理', 'Doc Management') }}</span>
        </router-link>
        <router-link to="/product#memory" class="product-item">
          <div class="product-content">
            <h3 class="product-title">{{ t('记忆库', 'Memory Store') }}</h3>
            <p class="product-desc">{{ t('自动提取、存储、召回 Agent 长期记忆，跨会话持久化', 'Auto-extract, store, and recall agent long-term memory across sessions') }}</p>
          </div>
          <span class="product-tags">Memory / MCP / {{ t('跨会话', 'Cross-session') }}</span>
        </router-link>
        <router-link to="/product#datalake" class="product-item">
          <div class="product-content">
            <h3 class="product-title">{{ t('数据湖', 'Data Lake') }}</h3>
            <p class="product-desc">{{ t('数据库、知识库、记忆库双向联动 — Ray/Python 作业实现数据飞轮', 'Bidirectional sync across all modules — Ray/Python jobs power the data flywheel') }}</p>
          </div>
          <span class="product-tags">Ray / Python / {{ t('数据飞轮', 'Data Flywheel') }}</span>
        </router-link>
      </div>
    </section>

    <!-- Divider -->
    <div class="section-rule"><div class="rule-inner"></div></div>

    <!-- Code -->
    <section class="code-section">
      <div class="code-left">
        <h2 class="code-heading">{{ t('多种方式接入', 'Multiple ways to connect') }}</h2>
        <p class="code-desc">
          {{ t(
            '用你熟悉的方式连接 DBay — 标准 PostgreSQL 协议、REST API、MCP 或 Python SDK。无需学习新工具。',
            'Connect to DBay the way you prefer — standard PostgreSQL protocol, REST API, MCP, or Python SDK. No new tools to learn.'
          ) }}
        </p>
        <div class="code-methods">
          <span class="method-pill">PostgreSQL</span>
          <span class="method-pill">REST API</span>
          <span class="method-pill">MCP</span>
          <span class="method-pill">Python SDK</span>
        </div>
      </div>
      <div class="code-right">
        <pre class="code-block"><code><span class="code-comment"># {{ t('用 psql 直接连接', 'Connect directly with psql') }}</span>
psql "postgresql://user:pass@your.dbay.host/mydb"

<span class="code-comment"># {{ t('或通过 REST API', 'Or via REST API') }}</span>
curl -X POST https://api.dbay.host/v1/memory/ingest \
  -H "Authorization: Bearer $DBAY_KEY" \
  -d '{"content": "user prefers dark mode"}'

<span class="code-comment"># {{ t('或 Python SDK', 'Or Python SDK') }}</span>
from dbay import DBay
db = DBay(api_key="your-key")
db.memory.ingest("user prefers dark mode")</code></pre>
      </div>
    </section>

    <!-- Bottom CTA -->
    <section class="bottom-cta">
      <h2 class="bottom-cta-title">{{ t('从一个数据库开始', 'Start with a database') }}</h2>
      <p class="bottom-cta-subtitle">{{ t('无需信用卡，无需注册，30 秒体验 Serverless PostgreSQL', 'No credit card, no signup. Experience Serverless PostgreSQL in 30 seconds.') }}</p>
      <div class="bottom-cta-actions">
        <button class="action-primary-inv" @click="startTrial" :disabled="trialLoading">
          {{ trialLoading ? t('创建中...', 'Creating...') : t('立即试用', 'Try Now') }}
        </button>
        <router-link to="/dashboard" class="action-link-inv">{{ t('进入 Console', 'Open Console') }}</router-link>
      </div>
    </section>

    <!-- Footer -->
    <footer class="landing-footer">
      <div class="footer-inner">
        <div class="footer-col">
          <div class="footer-brand">DBay</div>
          <p class="footer-tagline">{{ t('Agent 时代的数据基础设施', 'Data infrastructure for the agent era') }}</p>
        </div>
        <div class="footer-col">
          <div class="footer-col-title">{{ t('产品', 'Product') }}</div>
          <router-link to="/product#lakebase">Lakebase</router-link>
          <router-link to="/product#knowledge">{{ t('知识库', 'Knowledge') }}</router-link>
          <router-link to="/product#memory">{{ t('记忆库', 'Memory') }}</router-link>
          <router-link to="/product#datalake">{{ t('数据湖', 'Data Lake') }}</router-link>
        </div>
        <div class="footer-col">
          <div class="footer-col-title">{{ t('资源', 'Resources') }}</div>
          <router-link to="/docs">{{ t('文档', 'Docs') }}</router-link>
          <router-link to="/docs/rest-api">REST API</router-link>
          <router-link to="/blog">{{ t('博客', 'Blog') }}</router-link>
        </div>
      </div>
      <div class="footer-bottom">
        <span>&copy; 2026 DBay</span>
      </div>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useLocale } from '../../stores/locale'
import { useAuthStore } from '../../stores/auth'
import client from '../../api/client'

const { t } = useLocale()
const router = useRouter()
const authStore = useAuthStore()
const trialLoading = ref(false)

async function startTrial() {
  trialLoading.value = true
  try {
    localStorage.removeItem('lakeon_api_key')
    authStore.apiKey = ''
    const { data } = await client.post('/trial', null, { timeout: 10000 })
    localStorage.setItem('lakeon_api_key', data.api_key)
    authStore.apiKey = data.api_key
    authStore.setTenant(data.tenant_id, data.username || 'trial')
    authStore.setTrialState(true, data.expires_at)
    router.push('/dashboard')
  } catch {
    router.push('/login')
  } finally {
    trialLoading.value = false
  }
}
</script>

<style scoped>
/* Layout */
.landing {
  max-width: 100%;
  overflow-x: hidden;
  background: var(--pub-bg);
}

/* ── Hero ── */
.hero {
  max-width: 1000px;
  margin: 0 auto;
  padding: 120px 32px 80px;
}
.hero-eyebrow {
  font-family: var(--pub-sans);
  font-size: 12px;
  font-weight: 500;
  color: var(--pub-accent);
  letter-spacing: 3px;
  text-transform: uppercase;
  margin: 0 0 20px;
}
.hero-title {
  font-family: var(--pub-serif);
  font-size: 64px;
  font-weight: 600;
  color: var(--pub-text);
  line-height: 1.15;
  letter-spacing: -0.5px;
  margin: 0;
}
.hero-subtitle {
  font-family: var(--pub-sans);
  font-size: 17px;
  font-weight: 300;
  color: var(--pub-text-2);
  margin: 24px 0 0;
  line-height: 1.7;
  max-width: 500px;
}
.hero-actions {
  display: flex;
  align-items: center;
  gap: 24px;
  margin-top: 36px;
}
.action-primary {
  font-family: var(--pub-sans);
  background: var(--pub-btn-bg);
  color: var(--pub-btn-text);
  padding: 12px 32px;
  border-radius: 2px;
  font-size: 15px;
  font-weight: 500;
  border: none;
  cursor: pointer;
  letter-spacing: 0.2px;
  transition: background 0.15s;
}
.action-primary:hover { background: var(--pub-btn-hover); }
.action-primary:disabled { opacity: 0.5; cursor: not-allowed; }
.action-link {
  font-family: var(--pub-sans);
  color: var(--pub-text-2);
  font-size: 15px;
  font-weight: 400;
  text-decoration: none;
  border-bottom: 1px solid var(--pub-text-3);
  padding-bottom: 1px;
  transition: color 0.15s, border-color 0.15s;
}
.action-link:hover {
  color: var(--pub-text);
  border-color: var(--pub-text);
}
.hero-hint {
  font-family: var(--pub-sans);
  font-size: 13px;
  color: var(--pub-text-3);
  margin-top: 16px;
}

/* ── Section divider ── */
.section-rule {
  max-width: 1000px;
  margin: 0 auto;
  padding: 0 32px;
}
.rule-inner {
  height: 1px;
  background: var(--pub-border);
}

/* ── Products ── */
.products {
  max-width: 1000px;
  margin: 0 auto;
  padding: 80px 32px;
}
.products-heading {
  font-family: var(--pub-serif);
  font-size: 36px;
  font-weight: 600;
  color: var(--pub-text);
  margin: 0 0 48px;
  letter-spacing: -0.3px;
}
.product-list {
  display: flex;
  flex-direction: column;
}
.product-item {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 32px;
  padding: 24px 0;
  border-top: 1px solid var(--pub-border);
  text-decoration: none;
  transition: padding-left 0.2s;
}
.product-item:last-child {
  border-bottom: 1px solid var(--pub-border);
}
.product-item:hover {
  padding-left: 8px;
}
.product-item:hover .product-title {
  color: var(--pub-accent);
}
.product-content {
  flex: 1;
}
.product-title {
  font-family: var(--pub-sans);
  font-size: 20px;
  font-weight: 600;
  color: var(--pub-text);
  margin: 0 0 4px;
  transition: color 0.2s;
}
.product-desc {
  font-family: var(--pub-sans);
  font-size: 15px;
  font-weight: 300;
  color: var(--pub-text-2);
  margin: 0;
  line-height: 1.6;
}
.product-tags {
  font-family: var(--pub-sans);
  font-size: 12px;
  color: var(--pub-text-3);
  white-space: nowrap;
  flex-shrink: 0;
}

/* ── Code Section ── */
.code-section {
  max-width: 1000px;
  margin: 0 auto;
  padding: 80px 32px;
  display: flex;
  gap: 64px;
  align-items: flex-start;
}
.code-left {
  flex: 1;
}
.code-heading {
  font-family: var(--pub-serif);
  font-size: 32px;
  font-weight: 600;
  color: var(--pub-text);
  margin: 0 0 16px;
  letter-spacing: -0.3px;
}
.code-desc {
  font-family: var(--pub-sans);
  font-size: 15px;
  font-weight: 300;
  color: var(--pub-text-2);
  line-height: 1.7;
  margin: 0 0 24px;
}
.code-methods {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.method-pill {
  font-family: var(--pub-sans);
  font-size: 12px;
  font-weight: 500;
  color: var(--pub-text-2);
  padding: 5px 14px;
  border: 1px solid var(--pub-border);
  border-radius: 2px;
}
.code-right {
  flex: 1;
}
.code-block {
  font-family: 'SF Mono', 'Fira Code', 'Fira Mono', Menlo, monospace;
  font-size: 13px;
  line-height: 1.7;
  color: var(--pub-text);
  background: var(--pub-code-bg);
  padding: 28px 24px;
  border-radius: 2px;
  overflow-x: auto;
  margin: 0;
  border: 1px solid var(--pub-border);
}
.code-comment {
  color: var(--pub-text-3);
}

/* ── Bottom CTA ── */
.bottom-cta {
  background: var(--pub-btn-bg);
  padding: 100px 32px;
  text-align: center;
}
.bottom-cta-title {
  font-family: var(--pub-serif);
  font-size: 40px;
  font-weight: 600;
  color: var(--pub-btn-text);
  margin: 0 0 12px;
}
.bottom-cta-subtitle {
  font-family: var(--pub-sans);
  font-size: 16px;
  font-weight: 300;
  color: var(--pub-text-3);
  margin: 0 0 36px;
}
.bottom-cta-actions {
  display: flex;
  gap: 24px;
  justify-content: center;
  align-items: center;
}
.action-primary-inv {
  font-family: var(--pub-sans);
  background: var(--pub-btn-text);
  color: var(--pub-btn-bg);
  padding: 12px 32px;
  border-radius: 2px;
  font-size: 15px;
  font-weight: 500;
  border: none;
  cursor: pointer;
  letter-spacing: 0.2px;
  transition: opacity 0.15s;
}
.action-primary-inv:hover { opacity: 0.85; }
.action-primary-inv:disabled { opacity: 0.5; cursor: not-allowed; }
.action-link-inv {
  font-family: var(--pub-sans);
  color: var(--pub-text-3);
  font-size: 15px;
  font-weight: 400;
  text-decoration: none;
  border-bottom: 1px solid var(--pub-text-4);
  padding-bottom: 1px;
  transition: color 0.15s;
}
.action-link-inv:hover {
  color: var(--pub-btn-text);
}

/* ── Footer ── */
.landing-footer {
  border-top: 1px solid var(--pub-border);
  background: var(--pub-bg);
}
.footer-inner {
  max-width: 1000px;
  margin: 0 auto;
  padding: 64px 32px 48px;
  display: flex;
  gap: 80px;
}
.footer-col {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.footer-col:first-child {
  flex: 1;
}
.footer-brand {
  font-family: var(--pub-serif);
  font-size: 20px;
  font-weight: 600;
  color: var(--pub-text);
}
.footer-tagline {
  font-family: var(--pub-sans);
  font-size: 13px;
  color: var(--pub-text-3);
  margin: 0;
}
.footer-col-title {
  font-family: var(--pub-sans);
  font-size: 12px;
  font-weight: 600;
  color: var(--pub-text);
  text-transform: uppercase;
  letter-spacing: 1px;
  margin-bottom: 4px;
}
.footer-col a {
  font-family: var(--pub-sans);
  font-size: 13px;
  color: var(--pub-text-3);
  text-decoration: none;
  transition: color 0.15s;
}
.footer-col a:hover {
  color: var(--pub-text);
}
.footer-bottom {
  max-width: 1000px;
  margin: 0 auto;
  padding: 16px 32px 24px;
  border-top: 1px solid var(--pub-border);
  font-family: var(--pub-sans);
  font-size: 12px;
  color: var(--pub-text-3);
}

/* ── Responsive ── */
@media (max-width: 768px) {
  .hero {
    padding: 72px 20px 48px;
  }
  .hero-title {
    font-size: 40px;
  }
  .hero-subtitle {
    font-size: 15px;
    max-width: 100%;
  }
  .section-rule {
    padding: 0 20px;
  }
  .products {
    padding: 48px 20px;
  }
  .products-heading {
    font-size: 28px;
    margin-bottom: 32px;
  }
  .product-item {
    flex-direction: column;
    gap: 8px;
  }
  .code-section {
    flex-direction: column;
    padding: 48px 20px;
    gap: 32px;
  }
  .bottom-cta {
    padding: 64px 20px;
  }
  .bottom-cta-title {
    font-size: 28px;
  }
  .footer-inner {
    flex-direction: column;
    gap: 32px;
    padding: 40px 20px 32px;
  }
  .footer-bottom {
    padding: 16px 20px 24px;
  }
}
</style>
