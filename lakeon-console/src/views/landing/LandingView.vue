<template>
  <div class="landing">
    <!-- Hero -->
    <section class="hero">
      <span class="hero-badge">Agent {{ t('时代的数据基础设施', 'Era Data Infrastructure') }}</span>
      <h1 class="hero-title">
        {{ t('让你的 Agent', 'Give Your Agent') }}<br>
        {{ t('拥有完整的数据能力', 'Complete Data Power') }}
      </h1>
      <p class="hero-subtitle">
        {{ t(
          '一个平台，赋予你的 Agent 存储、检索、记忆和分析的全部能力',
          'One platform — give your agents the power to store, retrieve, remember, and analyze'
        ) }}
      </p>
      <div class="hero-ctas">
        <button class="cta-primary" @click="startTrial" :disabled="trialLoading">
          {{ trialLoading ? t('创建中...', 'Creating...') : t('立即试用', 'Try Now') }}
        </button>
        <router-link to="/docs" class="cta-secondary">{{ t('查看文档', 'Read Docs') }}</router-link>
      </div>
      <p class="hero-hint">{{ t('无需注册，30 秒获得一个数据库', 'No signup needed, get a database in 30 seconds') }}</p>
    </section>

    <!-- Products -->
    <section class="products">
      <h2 class="section-heading">{{ t('从数据库到数据平台，按需解锁', 'From Database to Data Platform') }}</h2>
      <div class="product-grid">
        <router-link to="/product#lakebase" class="product-card">
          <div class="product-header">
            <span class="product-dot" style="background: #1a2e25"></span>
            <h3 class="product-name">Lakebase</h3>
          </div>
          <p class="product-desc">{{ t('Serverless PostgreSQL，按需弹性伸缩，空闲自动休眠。内置 pgvector 向量搜索、时间旅行。', 'Serverless PostgreSQL with elastic scaling, auto-sleep, built-in pgvector and time travel.') }}</p>
          <div class="product-tags">
            <span class="pill">PostgreSQL</span>
            <span class="pill">Serverless</span>
            <span class="pill">pgvector</span>
          </div>
        </router-link>

        <router-link to="/product#knowledge" class="product-card">
          <div class="product-header">
            <span class="product-dot" style="background: #2a7a5f"></span>
            <h3 class="product-name">{{ t('知识库', 'Knowledge Base') }}</h3>
          </div>
          <p class="product-desc">{{ t('上传文档自动分块向量化，支持语义搜索和全文检索，Agent 直接通过 MCP 对接。', 'Auto-chunk and vectorize documents. Semantic + full-text search. Direct MCP integration for agents.') }}</p>
          <div class="product-tags">
            <span class="pill">RAG</span>
            <span class="pill">{{ t('向量搜索', 'Vector Search') }}</span>
            <span class="pill">MCP</span>
          </div>
        </router-link>

        <router-link to="/product#memory" class="product-card">
          <div class="product-header">
            <span class="product-dot" style="background: #b45309"></span>
            <h3 class="product-name">{{ t('记忆库', 'Memory Store') }}</h3>
          </div>
          <p class="product-desc">{{ t('Agent 长期记忆自动提取与检索，跨会话持久化，支持记忆合并与遗忘策略。', 'Auto-extract and recall agent long-term memory. Cross-session persistence with merge and decay policies.') }}</p>
          <div class="product-tags">
            <span class="pill">{{ t('长期记忆', 'Long-term') }}</span>
            <span class="pill">{{ t('自动提取', 'Auto Extract') }}</span>
            <span class="pill">MCP</span>
          </div>
        </router-link>

        <router-link to="/product#datalake" class="product-card">
          <div class="product-header">
            <span class="product-dot" style="background: #6d28d9"></span>
            <h3 class="product-name">{{ t('数据湖', 'Data Lake') }}</h3>
          </div>
          <p class="product-desc">{{ t('数据库、知识库、记忆库与数据湖双向联动，运行 Ray / Python 作业实现数据飞轮。', 'Bidirectional sync with all stores. Run Ray/Python jobs for processing, forming a data flywheel.') }}</p>
          <div class="product-tags">
            <span class="pill">Ray</span>
            <span class="pill">Python</span>
            <span class="pill">{{ t('数据飞轮', 'Flywheel') }}</span>
          </div>
        </router-link>
      </div>
    </section>

    <!-- Connect -->
    <section class="connect">
      <div class="connect-text">
        <h2 class="connect-title">{{ t('多种方式接入', 'Connect Your Way') }}</h2>
        <p class="connect-desc">{{ t('通过你熟悉的协议和工具直接接入 DBay，无需学习新技术栈。', 'Connect through familiar protocols and tools. No new tech stack to learn.') }}</p>
        <div class="connect-methods">
          <span class="method-pill">MCP</span>
          <span class="method-pill">PostgreSQL</span>
          <span class="method-pill">REST API</span>
          <span class="method-pill">Python SDK</span>
          <span class="method-pill">Skill</span>
        </div>
      </div>
      <div class="connect-code">
        <div class="code-block">
          <div class="code-header">
            <span class="code-dot" style="background: #ff5f57"></span>
            <span class="code-dot" style="background: #febc2e"></span>
            <span class="code-dot" style="background: #28c840"></span>
          </div>
          <pre class="code-content"><code><span class="code-comment"># {{ t('连接你的 Lakebase', 'Connect to your Lakebase') }}</span>
<span class="code-kw">import</span> psycopg2

conn = psycopg2.connect(
    <span class="code-str">"postgresql://user:pass@your.dbay.host/db"</span>
)
cur = conn.cursor()
cur.execute(<span class="code-str">"SELECT * FROM memories"</span>)</code></pre>
        </div>
      </div>
    </section>

    <!-- Bottom CTA -->
    <section class="bottom-cta">
      <h2 class="bottom-cta-title">{{ t('30 秒，免费获得一个 Serverless 数据库', 'Get a free Serverless database in 30 seconds') }}</h2>
      <p class="bottom-cta-subtitle">{{ t('无需信用卡 · 兼容所有 PG 客户端 · 立即体验', 'No credit card · Works with all PG clients · Try now') }}</p>
      <div class="bottom-cta-buttons">
        <button class="cta-primary" @click="startTrial" :disabled="trialLoading">
          {{ trialLoading ? t('创建中...', 'Creating...') : t('立即试用', 'Try Now') }}
        </button>
        <router-link to="/dashboard" class="cta-ghost">{{ t('进入 Console', 'Open Console') }}</router-link>
      </div>
    </section>

    <!-- Footer -->
    <footer class="landing-footer">
      <div class="footer-inner">
        <div class="footer-group">
          <div class="footer-label">{{ t('产品', 'Products') }}</div>
          <router-link to="/product#lakebase">Lakebase</router-link>
          <router-link to="/product#knowledge">{{ t('知识库', 'Knowledge') }}</router-link>
          <router-link to="/product#memory">{{ t('记忆库', 'Memory') }}</router-link>
          <router-link to="/product#datalake">{{ t('数据湖', 'Data Lake') }}</router-link>
        </div>
        <div class="footer-group">
          <div class="footer-label">{{ t('开发者', 'Developers') }}</div>
          <router-link to="/docs">{{ t('文档', 'Docs') }}</router-link>
          <router-link to="/docs/rest-api">REST API</router-link>
          <router-link to="/docs/python-sdk">Python SDK</router-link>
          <router-link to="/docs/mcp">MCP</router-link>
        </div>
        <div class="footer-group">
          <div class="footer-label">{{ t('更多', 'More') }}</div>
          <router-link to="/blog">{{ t('博客', 'Blog') }}</router-link>
          <router-link to="/login">{{ t('登录', 'Sign In') }}</router-link>
        </div>
      </div>
      <div class="footer-bottom">
        <span>&copy; 2026 DBay &middot; {{ t('数据港湾', 'Data Harbor') }}</span>
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
  padding: 80px 24px 60px;
  text-align: center;
  background: var(--pub-surface);
}
.hero-badge {
  display: inline-block;
  padding: 6px 18px;
  border-radius: 100px;
  background: var(--pub-accent-bg);
  color: var(--pub-accent);
  font-size: 13px;
  font-weight: 500;
  margin-bottom: 24px;
  border: 1px solid var(--pub-accent-border);
}
.hero-title {
  font-size: 48px;
  font-weight: 700;
  color: var(--pub-text);
  line-height: 1.15;
  letter-spacing: -0.5px;
  margin: 0;
}
.hero-subtitle {
  font-size: 17px;
  font-weight: 400;
  color: var(--pub-text-2);
  margin: 16px auto 0;
  line-height: 1.6;
  max-width: 520px;
}
.hero-ctas {
  display: flex;
  gap: 12px;
  justify-content: center;
  margin-top: 32px;
}
.cta-primary {
  background: var(--pub-primary);
  color: #fff;
  padding: 12px 32px;
  border-radius: 100px;
  font-size: 15px;
  font-weight: 600;
  border: none;
  cursor: pointer;
  transition: background 0.25s ease;
  font-family: var(--pub-sans);
}
.cta-primary:hover { background: var(--pub-primary-dark); }
.cta-primary:disabled { opacity: 0.5; cursor: not-allowed; }
.cta-secondary {
  background: transparent;
  color: var(--pub-text);
  padding: 12px 32px;
  border-radius: 100px;
  font-size: 15px;
  font-weight: 500;
  text-decoration: none;
  border: 1px solid var(--pub-border);
  transition: all 0.25s ease;
}
.cta-secondary:hover {
  border-color: var(--pub-text-3);
  background: var(--pub-hover);
}
.hero-hint {
  font-size: 13px;
  color: var(--pub-text-3);
  margin-top: 16px;
}

/* ── Products ── */
.products {
  padding: 72px 24px;
  max-width: 1080px;
  margin: 0 auto;
}
.section-heading {
  font-size: 28px;
  font-weight: 600;
  color: var(--pub-text);
  text-align: center;
  margin: 0 0 40px;
}
.product-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20px;
}
.product-card {
  background: var(--pub-surface);
  border: 1px solid var(--pub-border);
  border-radius: 16px;
  padding: 28px;
  text-decoration: none;
  transition: transform 0.25s ease, box-shadow 0.25s ease;
}
.product-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 8px 24px var(--pub-shadow);
}
.product-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.product-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.product-name {
  font-size: 17px;
  font-weight: 600;
  color: var(--pub-text);
  margin: 0;
}
.product-desc {
  font-size: 14px;
  color: var(--pub-text-2);
  line-height: 1.65;
  margin: 0 0 16px;
}
.product-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.pill {
  display: inline-block;
  padding: 3px 10px;
  border-radius: 100px;
  font-size: 12px;
  font-weight: 500;
  color: var(--pub-text-3);
  background: var(--pub-hover);
  border: 1px solid var(--pub-border);
}

/* ── Connect ── */
.connect {
  padding: 72px 24px;
  max-width: 1080px;
  margin: 0 auto;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 48px;
  align-items: center;
}
.connect-title {
  font-size: 28px;
  font-weight: 600;
  color: var(--pub-text);
  margin: 0 0 12px;
}
.connect-desc {
  font-size: 15px;
  color: var(--pub-text-2);
  line-height: 1.6;
  margin: 0 0 24px;
}
.connect-methods {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.method-pill {
  padding: 6px 14px;
  border-radius: 100px;
  font-size: 13px;
  font-weight: 500;
  color: var(--pub-text-2);
  border: 1px solid var(--pub-border);
  background: transparent;
}
.code-block {
  background: var(--pub-code-bg);
  border: 1px solid var(--pub-border);
  border-radius: 12px;
  overflow: hidden;
}
.code-header {
  display: flex;
  gap: 6px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--pub-border);
}
.code-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
}
.code-content {
  padding: 16px 20px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--pub-text);
  font-family: 'SF Mono', 'Fira Code', monospace;
  overflow-x: auto;
  margin: 0;
}
.code-comment { color: var(--pub-text-3); }
.code-kw { color: var(--pub-accent); font-weight: 600; }
.code-str { color: var(--pub-code); }

/* ── Bottom CTA ── */
.bottom-cta {
  background: var(--pub-primary-light);
  padding: 72px 24px;
  text-align: center;
}
.bottom-cta-title {
  font-size: 28px;
  font-weight: 600;
  color: var(--pub-text);
  margin: 0 0 8px;
}
.bottom-cta-subtitle {
  font-size: 15px;
  color: var(--pub-text-2);
  margin: 0 0 28px;
}
.bottom-cta-buttons {
  display: flex;
  gap: 12px;
  justify-content: center;
  align-items: center;
}
.cta-ghost {
  color: var(--pub-text-2);
  font-size: 15px;
  font-weight: 500;
  text-decoration: none;
  padding: 12px 24px;
  transition: color 0.25s ease;
}
.cta-ghost:hover {
  color: var(--pub-text);
}

/* ── Footer ── */
.landing-footer {
  background: var(--pub-surface);
  border-top: 1px solid var(--pub-border);
  padding: 48px 24px 24px;
}
.footer-inner {
  max-width: 1080px;
  margin: 0 auto;
  display: flex;
  justify-content: center;
  gap: 80px;
  margin-bottom: 40px;
}
.footer-group {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.footer-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--pub-text-3);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  margin-bottom: 4px;
}
.footer-group a {
  font-size: 13px;
  color: var(--pub-text-2);
  text-decoration: none;
  transition: color 0.25s ease;
}
.footer-group a:hover {
  color: var(--pub-text);
}
.footer-bottom {
  text-align: center;
  font-size: 12px;
  color: var(--pub-text-3);
  border-top: 1px solid var(--pub-border);
  padding-top: 20px;
  max-width: 1080px;
  margin: 0 auto;
}

/* ── Responsive ── */
@media (max-width: 768px) {
  .hero { padding: 48px 20px 40px; }
  .hero-title { font-size: 32px; }
  .hero-subtitle { font-size: 15px; }
  .products { padding: 48px 20px; }
  .product-grid { grid-template-columns: 1fr; }
  .connect { grid-template-columns: 1fr; padding: 48px 20px; gap: 32px; }
  .bottom-cta { padding: 48px 20px; }
  .footer-inner { flex-direction: column; gap: 32px; align-items: center; text-align: center; }
  .hero-ctas { flex-direction: column; align-items: center; }
}
</style>
