<template>
  <div class="landing">
    <!-- Hero — asymmetric, left-aligned -->
    <section class="hero">
      <div class="hero-inner">
        <div class="hero-content">
          <p class="hero-eyebrow">{{ t('Agent 时代的数据基础设施', 'Data Infrastructure for the Agent Era') }}</p>
          <h1 class="hero-title">
            {{ t('让你的 Agent', 'Give your Agent') }}<br>
            <span class="hero-title-accent">{{ t('拥有完整的数据能力', 'complete data capabilities') }}</span>
          </h1>
          <p class="hero-desc">
            {{ t(
              '存储、检索、记忆、分析 — 一个平台，从数据库到数据湖，按需解锁。',
              'Store, search, remember, analyze — one platform, from database to data lake, unlock as you grow.'
            ) }}
          </p>
          <div class="hero-actions">
            <button class="btn-primary" @click="startTrial" :disabled="trialLoading">
              {{ trialLoading ? t('创建中...', 'Creating...') : t('免费开始', 'Get Started Free') }}
            </button>
            <router-link to="/docs" class="btn-ghost">{{ t('阅读文档', 'Read Docs') }}</router-link>
          </div>
          <p class="hero-note">{{ t('无需注册，30 秒获得一个 Serverless PostgreSQL', 'No signup, get a Serverless PostgreSQL in 30 seconds') }}</p>
        </div>
        <div class="hero-visual">
          <div class="hero-diagram">
            <div class="diagram-ring ring-outer">
              <span class="ring-label ring-label-top">{{ t('数据湖', 'Data Lake') }}</span>
              <div class="diagram-ring ring-middle">
                <span class="ring-label ring-label-left">{{ t('知识库', 'Knowledge') }}</span>
                <span class="ring-label ring-label-right">{{ t('记忆库', 'Memory') }}</span>
                <div class="diagram-core">
                  <span class="core-icon">&#9875;</span>
                  <span class="core-text">Lakebase</span>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>

    <!-- Products — staggered, not grid -->
    <section class="products">
      <div class="products-inner">
        <h2 class="section-heading">{{ t('四种能力，一个平台', 'Four capabilities, one platform') }}</h2>
        <div class="product-list">
          <router-link
            v-for="(p, i) in products"
            :key="p.path"
            :to="p.path"
            class="product-item"
            :class="'product-item-' + i"
          >
            <div class="product-marker" :style="{ background: p.color }"></div>
            <div class="product-body">
              <h3 class="product-name">{{ p.title }}</h3>
              <p class="product-desc">{{ p.desc }}</p>
              <div class="product-tags">
                <span v-for="tag in p.tags" :key="tag">{{ tag }}</span>
              </div>
            </div>
            <span class="product-arrow">&rarr;</span>
          </router-link>
        </div>
      </div>
    </section>

    <!-- Code snippet — show don't tell -->
    <section class="connect">
      <div class="connect-inner">
        <div class="connect-text">
          <h2 class="section-heading">{{ t('用你熟悉的方式连接', 'Connect the way you know') }}</h2>
          <p class="connect-desc">
            {{ t(
              '标准 PostgreSQL 协议，任何 PG 客户端直接连接。也支持 REST API、Python SDK、MCP 集成。',
              'Standard PostgreSQL protocol. Any PG client connects directly. Also REST API, Python SDK, MCP integration.'
            ) }}
          </p>
          <div class="connect-methods">
            <span class="method-tag">PostgreSQL</span>
            <span class="method-tag">REST API</span>
            <span class="method-tag">Python SDK</span>
            <span class="method-tag">MCP</span>
          </div>
        </div>
        <div class="connect-code">
          <div class="code-header">
            <span class="code-dot"></span>
            <span class="code-dot"></span>
            <span class="code-dot"></span>
            <span class="code-lang">psql</span>
          </div>
          <pre class="code-body"><code>psql "postgres://user@your-db.dbay.io/main"

<span class="code-comment"># {{ t('或者用 Python', 'Or use Python') }}</span>
<span class="code-keyword">from</span> dbay <span class="code-keyword">import</span> Client

client = Client(<span class="code-string">"your-api-key"</span>)
client.memory.ingest(<span class="code-string">"用户偏好深色模式"</span>)
client.knowledge.search(<span class="code-string">"部署文档"</span>)</code></pre>
        </div>
      </div>
    </section>

    <!-- Bottom CTA -->
    <section class="bottom-cta">
      <div class="bottom-cta-inner">
        <h2>{{ t('从第一层开始', 'Start with Layer One') }}</h2>
        <p>{{ t('免费的 Serverless PostgreSQL，随时向上解锁知识库、记忆库、数据湖。', 'Free Serverless PostgreSQL. Unlock Knowledge, Memory, and Data Lake anytime.') }}</p>
        <div class="bottom-actions">
          <button class="btn-primary btn-primary-light" @click="startTrial" :disabled="trialLoading">
            {{ trialLoading ? t('创建中...', 'Creating...') : t('免费试用', 'Try Free') }}
          </button>
          <router-link to="/product" class="btn-ghost-light">{{ t('了解产品架构', 'Explore Architecture') }}</router-link>
        </div>
      </div>
    </section>

    <!-- Footer -->
    <footer class="landing-footer">
      <div class="footer-inner">
        <div class="footer-brand">
          <strong>DBay</strong> <span>{{ t('数据港湾', 'Data Harbor') }}</span>
        </div>
        <div class="footer-links">
          <router-link to="/product">{{ t('产品', 'Products') }}</router-link>
          <router-link to="/docs">{{ t('文档', 'Docs') }}</router-link>
          <router-link to="/docs/rest-api">API</router-link>
          <router-link to="/blog">{{ t('博客', 'Blog') }}</router-link>
        </div>
        <div class="footer-copy">&copy; 2026 DBay</div>
      </div>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useLocale } from '../../stores/locale'
import { useAuthStore } from '../../stores/auth'
import client from '../../api/client'

const { t } = useLocale()
const router = useRouter()
const authStore = useAuthStore()
const trialLoading = ref(false)

const products = computed(() => [
  {
    path: '/product/lakebase',
    title: 'Lakebase',
    desc: t('Serverless PostgreSQL，存算分离，按需弹性伸缩', 'Serverless PostgreSQL with disaggregated storage and elastic scaling'),
    color: '#1e2d3d',
    tags: [t('秒级启动', 'Fast Start'), t('时间旅行', 'Time Travel'), 'pgvector'],
  },
  {
    path: '/product/knowledge',
    title: t('知识库', 'Knowledge Base'),
    desc: t('文档解析 + 向量检索 + 全文搜索，内置 Embedding', 'Doc parsing + vector search + full-text, built-in Embedding'),
    color: '#4a8b8c',
    tags: [t('混合检索', 'Hybrid Search'), 'RAG', t('文档解析', 'Doc Parsing')],
  },
  {
    path: '/product/memory',
    title: t('记忆库', 'Memory Store'),
    desc: t('AI Agent 结构化长期记忆，自动提取，越用越懂你', 'Structured long-term memory for AI Agents'),
    color: '#c67d3a',
    tags: [t('自动提取', 'Auto Extract'), 'MCP', t('混合召回', 'Hybrid Recall')],
  },
  {
    path: '/product/datalake',
    title: t('数据湖', 'Data Lake'),
    desc: t('Ray / Python 作业引擎，数据飞轮闭环', 'Ray/Python job engine, data flywheel loop'),
    color: '#7a5195',
    tags: ['Ray', 'Python', t('数据飞轮', 'Flywheel')],
  },
])

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
/* ── Landing page ── */
.landing {
  max-width: 100%;
  overflow-x: hidden;
  background: var(--pub-bg);
}

/* ── Hero ── */
.hero {
  padding: clamp(48px, 8vw, 96px) 0 clamp(40px, 6vw, 80px);
  background: var(--pub-surface);
  border-bottom: 1px solid var(--pub-border);
}
.hero-inner {
  max-width: 1100px;
  margin: 0 auto;
  padding: 0 clamp(24px, 4vw, 48px);
  display: grid;
  grid-template-columns: 1.1fr 0.9fr;
  gap: clamp(32px, 5vw, 80px);
  align-items: center;
}
.hero-eyebrow {
  font-family: var(--pub-sans);
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 1.5px;
  text-transform: uppercase;
  color: var(--pub-accent);
  margin: 0 0 16px;
}
.hero-title {
  font-family: var(--pub-serif);
  font-size: clamp(32px, 4.5vw, 52px);
  font-weight: 900;
  line-height: 1.2;
  color: var(--pub-text);
  margin: 0 0 20px;
}
.hero-title-accent {
  color: var(--pub-accent);
}
.hero-desc {
  font-size: clamp(15px, 1.5vw, 17px);
  color: var(--pub-text-2);
  line-height: 1.7;
  margin: 0 0 28px;
  max-width: 480px;
}
.hero-actions {
  display: flex;
  gap: 12px;
  align-items: center;
}
.hero-note {
  font-size: 12px;
  color: var(--pub-text-4);
  margin-top: 12px;
}

/* Buttons */
.btn-primary {
  font-family: var(--pub-sans);
  background: var(--pub-btn-bg);
  color: var(--pub-btn-text);
  padding: 13px 28px;
  border-radius: 6px;
  font-size: 15px;
  font-weight: 600;
  border: none;
  cursor: pointer;
  transition: background 0.2s ease;
}
.btn-primary:hover { background: var(--pub-btn-hover); }
.btn-primary:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-ghost {
  font-family: var(--pub-sans);
  color: var(--pub-text-2);
  padding: 13px 20px;
  font-size: 15px;
  font-weight: 500;
  text-decoration: none;
  border-bottom: 1.5px solid var(--pub-border);
  transition: color 0.2s, border-color 0.2s;
}
.btn-ghost:hover {
  color: var(--pub-text);
  border-color: var(--pub-text);
}

/* Hero diagram */
.hero-visual {
  display: flex;
  justify-content: center;
  align-items: center;
}
.hero-diagram {
  position: relative;
  width: 320px;
  height: 320px;
  display: flex;
  justify-content: center;
  align-items: center;
}
.diagram-ring {
  border-radius: 50%;
  display: flex;
  justify-content: center;
  align-items: center;
  position: relative;
}
.ring-outer {
  width: 300px;
  height: 300px;
  border: 1.5px solid var(--pub-border);
  background: var(--pub-bg);
}
.ring-middle {
  width: 200px;
  height: 200px;
  border: 1.5px solid var(--pub-border);
  background: var(--pub-surface);
}
.diagram-core {
  width: 90px;
  height: 90px;
  border-radius: 50%;
  background: var(--pub-primary);
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  box-shadow: 0 8px 32px rgba(30, 45, 61, 0.2);
}
.core-icon {
  font-size: 22px;
  color: var(--pub-btn-text);
  line-height: 1;
}
.core-text {
  font-family: var(--pub-sans);
  font-size: 10px;
  font-weight: 700;
  color: var(--pub-btn-text);
  letter-spacing: 0.5px;
  margin-top: 2px;
}
.ring-label {
  position: absolute;
  font-family: var(--pub-sans);
  font-size: 12px;
  font-weight: 600;
  color: var(--pub-text-3);
  background: var(--pub-surface);
  padding: 4px 12px;
  border-radius: 20px;
  border: 1px solid var(--pub-border);
  white-space: nowrap;
}
.ring-label-top { top: -12px; }
.ring-label-left { left: -36px; top: 50%; transform: translateY(-50%); }
.ring-label-right { right: -36px; top: 50%; transform: translateY(-50%); }

/* ── Products ── */
.products {
  padding: clamp(48px, 6vw, 80px) 0;
}
.products-inner {
  max-width: 1100px;
  margin: 0 auto;
  padding: 0 clamp(24px, 4vw, 48px);
}
.section-heading {
  font-family: var(--pub-serif);
  font-size: clamp(22px, 3vw, 30px);
  font-weight: 700;
  color: var(--pub-text);
  margin: 0 0 clamp(28px, 4vw, 48px);
}
.product-list {
  display: flex;
  flex-direction: column;
  gap: 1px;
  background: var(--pub-border);
  border: 1px solid var(--pub-border);
  border-radius: 10px;
  overflow: hidden;
}
.product-item {
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 24px 28px;
  background: var(--pub-surface);
  text-decoration: none;
  transition: background 0.2s ease;
  cursor: pointer;
}
.product-item:hover {
  background: var(--pub-hover);
}
.product-marker {
  width: 4px;
  height: 40px;
  border-radius: 2px;
  flex-shrink: 0;
}
.product-body { flex: 1; }
.product-name {
  font-family: var(--pub-sans);
  font-size: 17px;
  font-weight: 700;
  color: var(--pub-text);
  margin: 0 0 4px;
}
.product-desc {
  font-size: 14px;
  color: var(--pub-text-2);
  line-height: 1.5;
  margin: 0 0 8px;
}
.product-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.product-tags span {
  font-size: 11px;
  font-weight: 500;
  padding: 2px 10px;
  border-radius: 4px;
  background: var(--pub-bg-alt);
  color: var(--pub-text-3);
}
.product-arrow {
  font-size: 20px;
  color: var(--pub-text-4);
  flex-shrink: 0;
  transition: transform 0.2s, color 0.2s;
}
.product-item:hover .product-arrow {
  transform: translateX(4px);
  color: var(--pub-accent);
}

/* ── Connect ── */
.connect {
  padding: clamp(48px, 6vw, 80px) 0;
  background: var(--pub-surface);
  border-top: 1px solid var(--pub-border);
  border-bottom: 1px solid var(--pub-border);
}
.connect-inner {
  max-width: 1100px;
  margin: 0 auto;
  padding: 0 clamp(24px, 4vw, 48px);
  display: grid;
  grid-template-columns: 1fr 1.2fr;
  gap: clamp(32px, 5vw, 64px);
  align-items: start;
}
.connect-desc {
  font-size: 15px;
  color: var(--pub-text-2);
  line-height: 1.7;
  margin: 0 0 20px;
}
.connect-methods {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}
.method-tag {
  font-family: var(--pub-sans);
  font-size: 12px;
  font-weight: 600;
  padding: 5px 14px;
  border-radius: 20px;
  border: 1px solid var(--pub-border);
  color: var(--pub-text-2);
}
.connect-code {
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid var(--pub-border);
  background: var(--pub-bg-alt);
}
.code-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 10px 16px;
  background: var(--pub-border);
}
.code-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--pub-text-4);
  opacity: 0.4;
}
.code-lang {
  margin-left: auto;
  font-family: var(--pub-sans);
  font-size: 11px;
  color: var(--pub-text-4);
}
.code-body {
  padding: 20px 20px;
  font-family: 'SF Mono', 'Fira Code', 'Cascadia Code', monospace;
  font-size: 13px;
  line-height: 1.7;
  color: var(--pub-text);
  margin: 0;
  overflow-x: auto;
}
.code-comment { color: var(--pub-text-4); }
.code-keyword { color: var(--pub-teal); font-weight: 600; }
.code-string { color: var(--pub-accent); }

/* ── Bottom CTA ── */
.bottom-cta {
  padding: clamp(48px, 6vw, 80px) 0;
  background: var(--pub-primary);
  color: var(--pub-btn-text);
}
.bottom-cta-inner {
  max-width: 1100px;
  margin: 0 auto;
  padding: 0 clamp(24px, 4vw, 48px);
  text-align: center;
}
.bottom-cta h2 {
  font-family: var(--pub-serif);
  font-size: clamp(24px, 3vw, 34px);
  font-weight: 700;
  margin: 0 0 10px;
}
.bottom-cta p {
  font-size: 15px;
  opacity: 0.7;
  margin: 0 0 28px;
  line-height: 1.6;
}
.bottom-actions {
  display: flex;
  gap: 12px;
  justify-content: center;
  align-items: center;
}
.btn-primary-light {
  background: var(--pub-btn-text);
  color: var(--pub-primary);
}
.btn-primary-light:hover {
  background: var(--pub-bg);
}
.btn-ghost-light {
  font-family: var(--pub-sans);
  color: rgba(255,255,255,0.7);
  padding: 13px 20px;
  font-size: 15px;
  text-decoration: none;
  border-bottom: 1.5px solid rgba(255,255,255,0.3);
  transition: color 0.2s, border-color 0.2s;
}
.btn-ghost-light:hover {
  color: #fff;
  border-color: #fff;
}

/* ── Footer ── */
.landing-footer {
  padding: 28px 0;
  border-top: 1px solid var(--pub-border);
  background: var(--pub-surface);
}
.footer-inner {
  max-width: 1100px;
  margin: 0 auto;
  padding: 0 clamp(24px, 4vw, 48px);
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.footer-brand {
  font-size: 14px;
  color: var(--pub-text);
}
.footer-brand span {
  color: var(--pub-text-3);
  font-size: 12px;
  margin-left: 6px;
}
.footer-links {
  display: flex;
  gap: 20px;
}
.footer-links a {
  font-size: 13px;
  color: var(--pub-text-3);
  text-decoration: none;
  transition: color 0.15s;
}
.footer-links a:hover { color: var(--pub-text); }
.footer-copy {
  font-size: 11px;
  color: var(--pub-text-4);
}

/* ── Responsive ── */
@media (max-width: 768px) {
  .hero-inner {
    grid-template-columns: 1fr;
    text-align: center;
  }
  .hero-desc { max-width: none; }
  .hero-actions { justify-content: center; }
  .hero-visual { display: none; }
  .connect-inner { grid-template-columns: 1fr; }
  .footer-inner { flex-direction: column; gap: 12px; text-align: center; }
  .footer-copy { order: 3; }
}
</style>
