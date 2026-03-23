<template>
  <div class="landing-page">
    <!-- Top Navigation Bar -->
    <nav class="nav-bar">
      <div class="nav-inner">
        <div class="nav-logo">DBay <span class="nav-tagline">数据港湾</span></div>
        <div class="nav-links">
          <a href="#modules" @click.prevent="scrollTo('modules')">{{ t('产品', 'Products') }}</a>
          <a href="#features" @click.prevent="scrollTo('features')">{{ t('特性', 'Features') }}</a>
          <a href="#scenarios" @click.prevent="scrollTo('scenarios')">{{ t('场景', 'Scenarios') }}</a>
          <a href="#quickstart" @click.prevent="scrollTo('quickstart')">{{ t('快速开始', 'Quick Start') }}</a>
        </div>
        <div class="nav-right">
          <button class="lang-toggle" @click="toggleLocale">{{ locale === 'zh' ? 'EN' : '中' }}</button>
          <router-link to="/login" class="btn-signin">{{ t('登录', 'Sign In') }}</router-link>
        </div>
        <button class="mobile-menu-btn" @click="mobileMenuOpen = !mobileMenuOpen">&#9776;</button>
      </div>
      <div v-if="mobileMenuOpen" class="mobile-menu">
        <a href="#modules" @click.prevent="scrollTo('modules'); mobileMenuOpen = false">{{ t('产品', 'Products') }}</a>
        <a href="#features" @click.prevent="scrollTo('features'); mobileMenuOpen = false">{{ t('特性', 'Features') }}</a>
        <a href="#scenarios" @click.prevent="scrollTo('scenarios'); mobileMenuOpen = false">{{ t('场景', 'Scenarios') }}</a>
        <a href="#quickstart" @click.prevent="scrollTo('quickstart'); mobileMenuOpen = false">{{ t('快速开始', 'Quick Start') }}</a>
        <router-link to="/login" @click="mobileMenuOpen = false">{{ t('登录', 'Sign In') }}</router-link>
      </div>
    </nav>

    <!-- Hero Section -->
    <section class="hero" id="hero">
      <div class="container">
        <h1 class="hero-title">{{ t('数据港湾 — 为 AI 应用而生的 Serverless 数据平台', 'DBay — The Serverless Data Platform Built for AI') }}</h1>
        <p class="hero-subtitle">{{ t('让数据安全停泊，按需启航。秒级创建，自动休眠，弹性伸缩。关系型、向量、全文、图查询、RAG、时间旅行，一个平台全搞定。', 'Dock your data safely, set sail on demand. Create in seconds, auto-sleep, elastic scaling. Relational, vector, full-text, graph, RAG, time travel — all in one platform.') }}</p>
        <div class="hero-actions">
          <button class="btn-primary btn-lg" @click="startTrial" :disabled="trialLoading">
            {{ trialLoading ? t('创建中...', 'Creating...') : t('立即体验', 'Try Now') }}
          </button>
          <router-link to="/login" class="btn-outline btn-lg">{{ t('注册', 'Sign Up') }}</router-link>
        </div>
        <p class="hero-hint">{{ t('无需注册，30 秒创建临时数据库', 'No signup needed, get a database in 30 seconds') }}</p>
        <div v-if="trialError" class="trial-error">{{ trialError }}</div>
      </div>
    </section>

    <!-- Product Modules -->
    <section class="section" id="modules">
      <div class="container">
        <h2 class="section-title">{{ t('三大产品模块', 'Three Product Modules') }}</h2>
        <p class="section-desc">{{ t('以 Neon 为内核的 Lakebase，为知识库、记忆库、AI 多模数据湖提供统一的 Serverless 数据底座', 'Lakebase powered by Neon provides a unified Serverless data foundation for Knowledge Base, Memory Store, and AI Data Lake') }}</p>
        <div class="module-grid">
          <div class="module-card module-lakebase">
            <div class="module-badge">{{ t('核心引擎', 'Core Engine') }}</div>
            <div class="module-icon">&#x1F418;</div>
            <h3>Lakebase</h3>
            <p class="module-subtitle">Serverless PostgreSQL</p>
            <ul class="module-features">
              <li>{{ t('3ms 热启动，3s 冷启动', '3ms hot start, 3s cold start') }}</li>
              <li>{{ t('存算分离，自动扩缩容', 'Disaggregated storage, auto-scaling') }}</li>
              <li>{{ t('数据库分支与时间旅行', 'Database branching & time travel') }}</li>
              <li>{{ t('多版本管理与回滚', 'Version management & rollback') }}</li>
              <li>{{ t('多租户隔离', 'Multi-tenant isolation') }}</li>
              <li>{{ t('AI SQL 助手 (自然语言生成 SQL)', 'AI SQL Assistant (NL to SQL)') }}</li>
            </ul>
          </div>
          <div class="module-card module-kb">
            <div class="module-badge">{{ t('已上线', 'Live') }}</div>
            <div class="module-icon">&#x1F4DA;</div>
            <h3>{{ t('知识库', 'Knowledge Base') }}</h3>
            <p class="module-subtitle">{{ t('文档 + 表 + 向量检索', 'Documents + Tables + Vector Search') }}</p>
            <ul class="module-features">
              <li>{{ t('文档自动解析 (PDF/Word/Markdown)', 'Auto document parsing (PDF/Word/MD)') }}</li>
              <li>{{ t('向量检索 (pgvector)', 'Vector search (pgvector)') }}</li>
              <li>{{ t('全文搜索 (tsvector/RUM)', 'Full-text search (tsvector/RUM)') }}</li>
              <li>{{ t('表知识库 (结构化数据)', 'Table KB (structured data)') }}</li>
              <li>{{ t('向量 + 全文混合检索', 'Hybrid vector + full-text retrieval') }}</li>
              <li>{{ t('内置 Embedding 与 Reranker', 'Built-in embedding & reranker') }}</li>
            </ul>
          </div>
          <div class="module-card module-lake">
            <div class="module-badge">{{ t('已上线', 'Live') }}</div>
            <div class="module-icon">&#x1F30A;</div>
            <h3>{{ t('AI 数据湖', 'AI Data Lake') }}</h3>
            <p class="module-subtitle">{{ t('数据处理 + 训练 + 飞轮', 'Data Processing + Training + Flywheel') }}</p>
            <ul class="module-features">
              <li>{{ t('Python/Ray 任务调度', 'Python/Ray task scheduling') }}</li>
              <li>{{ t('Dataset 导出 (Parquet)', 'Dataset export (Parquet)') }}</li>
              <li>{{ t('模型微调支持', 'Model fine-tuning support') }}</li>
              <li>{{ t('Kata VM 安全隔离', 'Kata VM security isolation') }}</li>
              <li>{{ t('DB ↔ 数据湖 数据飞轮', 'DB ↔ Data Lake data flywheel') }}</li>
              <li>{{ t('增量 CDC 调度', 'Incremental CDC scheduling') }}</li>
            </ul>
          </div>
        </div>
        <div class="module-coming">
          <span class="module-coming-icon">&#x1F9E0;</span>
          <div>
            <strong>{{ t('记忆库 (即将推出)', 'Memory Store (Coming Soon)') }}</strong>
            <span class="module-coming-desc">{{ t(' — Neuromem 记忆引擎，为 AI Agent 提供长期记忆能力，计划合入 DBay', ' — Neuromem memory engine for AI Agent long-term memory, planned integration into DBay') }}</span>
          </div>
        </div>
      </div>
    </section>

    <!-- Core Features -->
    <section class="section bg-alt" id="features">
      <div class="container">
        <h2 class="section-title">{{ t('核心特性', 'Core Features') }}</h2>
        <div class="card-grid three">
          <div class="card" v-for="f in features" :key="f.icon">
            <div class="card-icon">{{ f.icon }}</div>
            <h3>{{ f.title }}</h3>
            <p>{{ f.desc }}</p>
          </div>
        </div>
      </div>
    </section>

    <!-- Use Cases -->
    <section class="section bg-alt" id="scenarios">
      <div class="container">
        <h2 class="section-title">{{ t('应用场景', 'Use Cases') }}</h2>
        <div class="card-grid">
          <div class="card" v-for="u in useCases" :key="u.title">
            <div class="card-icon">{{ u.icon }}</div>
            <h3>{{ u.title }}</h3>
            <p>{{ u.desc }}</p>
          </div>
        </div>
      </div>
    </section>

    <!-- Architecture Highlights -->
    <section class="section" id="architecture">
      <div class="container">
        <h2 class="section-title">{{ t('架构亮点', 'Architecture Highlights') }}</h2>
        <div class="arch-highlights">
          <div class="arch-point">
            <span class="arch-bullet">1</span>
            <span>{{ t('标准 PostgreSQL 协议，无需学习新接口', 'Standard PostgreSQL protocol, no new interfaces to learn') }}</span>
          </div>
          <div class="arch-point">
            <span class="arch-bullet">2</span>
            <span>{{ t('三大模块共享 Lakebase 底座，统一管理数据资产', 'Three modules share Lakebase foundation, unified data asset management') }}</span>
          </div>
          <div class="arch-point">
            <span class="arch-bullet">3</span>
            <span>{{ t('向量、全文、图查询、RAG 全部内置，一个连接串搞定所有', 'Vector, full-text, graph, RAG all built-in — one connection string for everything') }}</span>
          </div>
          <div class="arch-point">
            <span class="arch-bullet">4</span>
            <span>{{ t('DB ↔ 数据湖 数据飞轮，打通数据处理与模型训练闭环', 'DB ↔ Data Lake flywheel, connecting data processing and model training') }}</span>
          </div>
        </div>
      </div>
    </section>

    <!-- Quick Start -->
    <section class="section bg-alt" id="quickstart">
      <div class="container">
        <h2 class="section-title">{{ t('快速开始', 'Quick Start') }}</h2>
        <div class="steps">
          <div class="step">
            <div class="step-num">1</div>
            <h3>{{ t('注册账号', 'Sign Up') }}</h3>
            <p>{{ t('进入登录页面，输入租户名称，即可获取 API Key', 'Go to the login page, enter a tenant name, and get your API Key') }}</p>
            <router-link to="/login" class="btn-secondary">{{ t('前往注册', 'Go to Sign Up') }}</router-link>
          </div>
          <div class="step">
            <div class="step-num">2</div>
            <h3>{{ t('创建数据库', 'Create Database') }}</h3>
            <pre class="code-block"><code>curl -X POST https://api.dbay.cloud:8443/api/v1/databases \
  -H "X-API-Key: YOUR_API_KEY" \
  -d '{"name": "mydb"}'</code></pre>
          </div>
          <div class="step">
            <div class="step-num">3</div>
            <h3>{{ t('连接使用', 'Connect') }}</h3>
            <div class="code-label">Python</div>
            <pre class="code-block"><code>import psycopg2
conn = psycopg2.connect("host=api.dbay.cloud port=4432 user=cloud_admin dbname=postgres sslmode=disable options=endpoint%3Dmydb")</code></pre>
            <div class="code-label" style="margin-top: 12px;">Node.js</div>
            <pre class="code-block"><code>import pg from 'pg'
const client = new pg.Client({
  host: 'api.dbay.cloud', port: 4432,
  user: 'cloud_admin', database: 'postgres',
  ssl: false, connectionTimeoutMillis: 30000,
})
await client.connect()</code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- Footer -->
    <footer class="footer">
      <div class="container footer-inner">
        <span>&copy; 2026 DBay. All rights reserved.</span>
        <div class="footer-links">
          <router-link to="/login">{{ t('控制台', 'Console') }}</router-link>
        </div>
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

const { locale, setLocale, t } = useLocale()
const router = useRouter()
const authStore = useAuthStore()

const mobileMenuOpen = ref(false)
const trialLoading = ref(false)
const trialError = ref('')

async function startTrial() {
  trialLoading.value = true
  trialError.value = ''
  try {
    // Clear stale auth before calling trial (avoid 401 interceptor redirect)
    localStorage.removeItem('lakeon_api_key')
    authStore.apiKey = ''

    const res = await client.post('/trial')
    const data = res.data
    // Save trial auth to localStorage + store
    const key = data.api_key
    localStorage.setItem('lakeon_api_key', key)
    authStore.apiKey = key
    authStore.setTenant(data.tenant_id, data.username || 'trial')
    // Navigate to database manager if database was created
    const db = data.database
    if (db && db.id) {
      router.push(`/databases/${db.id}/manager`)
    } else {
      router.push('/dashboard')
    }
  } catch (e: any) {
    const msg = e?.response?.data?.error?.message || e?.message || t('创建失败，请稍后重试', 'Failed, please try again')
    trialError.value = msg
  } finally {
    trialLoading.value = false
  }
}

function toggleLocale() {
  setLocale(locale.value === 'zh' ? 'en' : 'zh')
}

function scrollTo(id: string) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth' })
}

const features = computed(() => [
  { icon: '\u26A1', title: t('极速启动', 'Ultra-fast Start'), desc: t('热启动 3ms，冷启动 3 秒。弹性节点池按需扩缩，零等待', 'Hot start 3ms, cold start 3s. Elastic node pool scales on demand, zero wait') },
  { icon: '\u2601', title: t('Serverless 架构', 'Serverless Architecture'), desc: t('自动休眠唤醒，存算分离，存储弹性扩展，计算按需启停', 'Auto sleep/wake, disaggregated storage, elastic scaling, on-demand compute') },
  { icon: '\uD83C\uDF3F', title: t('数据库分支', 'Database Branching'), desc: t('像 Git 一样管理数据。创建版本、对比差异、安全回滚，copy-on-write 零开销', 'Manage data like Git. Create versions, compare diffs, safely rollback with copy-on-write') },
  { icon: '\uD83E\uDDE0', title: t('AI SQL 助手', 'AI SQL Assistant'), desc: t('自然语言描述需求，AI 自动生成 SQL。内置多种 LLM 模型可选', 'Describe what you need in natural language, AI generates SQL. Multiple LLM models available') },
  { icon: '\uD83D\uDCE5', title: t('数据迁移', 'Data Migration'), desc: t('一键从外部数据库导入数据。支持 PostgreSQL、MySQL 等主流数据库', 'One-click data import from external databases. Supports PostgreSQL, MySQL, and more') },
  { icon: '\uD83D\uDCCA', title: t('监控运维', 'Monitoring & Ops'), desc: t('内置监控面板、日志管理、备份管理，全方位运维能力', 'Built-in monitoring dashboard, log management, backup management — full ops capabilities') },
])


const useCases = computed(() => [
  { icon: '\uD83E\uDDE0', title: t('AI Agent 长期记忆', 'AI Agent Long-term Memory'), desc: t('持久化对话历史、用户画像和行为偏好，让 Agent 真正"记住"用户。利用数据库分支实现时间旅行，随时回溯 Agent 的任意历史状态', 'Persist conversation history, user profiles and preferences. Use database branching for time travel — roll back to any historical state of your Agent') },
  { icon: '\uD83D\uDCDA', title: t('知识库 / RAG', 'Knowledge Base / RAG'), desc: t('内置 embedding 生成与 reranker，向量 + 全文混合检索，无需外部服务即可构建高质量 RAG 管线', 'Built-in embedding generation and reranker. Hybrid vector + full-text retrieval for high-quality RAG pipelines — no external services needed') },
  { icon: '\uD83C\uDF10', title: t('多模态数据处理', 'Multimodal Data'), desc: t('通过 pgvector 存储文本、图像、音频等任意模态的 embedding 向量，实现跨模态统一检索', 'Store embeddings from text, images, audio and any modality via pgvector for unified cross-modal retrieval') },
  { icon: '\uD83D\uDD70', title: t('AI 数据版本管理', 'AI Data Versioning'), desc: t('基于 Neon 分支能力，像 Git 一样管理训练数据和模型配置。对比不同版本、安全回滚，加速 AI 迭代', 'Git-like data management via Neon branching. Compare versions, safely rollback, and accelerate AI iteration cycles') },
])
</script>

<style scoped>
* {
  box-sizing: border-box;
}

.landing-page {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
  color: #1a1a1a;
  scroll-behavior: smooth;
}

/* Nav Bar */
.nav-bar {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  height: 56px;
  background: #fff;
  border-bottom: 1px solid #e8e8e8;
  z-index: 100;
}

.nav-inner {
  max-width: 1200px;
  margin: 0 auto;
  height: 56px;
  display: flex;
  align-items: center;
  padding: 0 24px;
}

.nav-logo {
  font-size: 22px;
  font-weight: 700;
  color: #0073e6;
  margin-right: 40px;
  white-space: nowrap;
}

.nav-tagline {
  font-size: 13px;
  font-weight: 400;
  color: #888;
  margin-left: 6px;
}

.nav-links {
  display: flex;
  gap: 28px;
  flex: 1;
  justify-content: center;
}

.nav-links a {
  color: #333;
  text-decoration: none;
  font-size: 14px;
  font-weight: 500;
  transition: color 0.2s;
}

.nav-links a:hover {
  color: #0073e6;
}

.nav-right {
  display: flex;
  align-items: center;
  gap: 12px;
}

.lang-toggle {
  background: #f5f5f5;
  border: 1px solid #ddd;
  border-radius: 4px;
  padding: 4px 12px;
  cursor: pointer;
  font-size: 13px;
  font-weight: 500;
}

.btn-signin {
  background: #0073e6;
  color: #fff;
  border: none;
  border-radius: 4px;
  padding: 6px 18px;
  font-size: 14px;
  text-decoration: none;
  font-weight: 500;
  transition: background 0.2s;
}

.btn-signin:hover {
  background: #005bb5;
}

.mobile-menu-btn {
  display: none;
  background: none;
  border: none;
  font-size: 22px;
  cursor: pointer;
  margin-left: auto;
}

.mobile-menu {
  display: none;
  flex-direction: column;
  background: #fff;
  padding: 12px 24px;
  border-bottom: 1px solid #e8e8e8;
}

.mobile-menu a {
  padding: 8px 0;
  color: #333;
  text-decoration: none;
  font-size: 14px;
}

/* Container */
.container {
  max-width: 1200px;
  margin: 0 auto;
  padding: 0 24px;
}

/* Sections */
.section {
  padding: 80px 0;
}

.bg-alt {
  background: #f7f9fc;
}

.section-title {
  text-align: center;
  font-size: 28px;
  font-weight: 700;
  margin-bottom: 48px;
  color: #1a1a1a;
}

.section-desc {
  text-align: center;
  font-size: 16px;
  color: #555;
  max-width: 720px;
  margin: -28px auto 48px;
  line-height: 1.7;
}

/* Hero */
.hero {
  padding: 140px 0 80px;
  background: linear-gradient(180deg, #f0f5ff 0%, #fff 100%);
  text-align: center;
}

.hero-title {
  font-size: 40px;
  font-weight: 800;
  line-height: 1.3;
  margin-bottom: 20px;
  color: #1a1a1a;
}

.hero-subtitle {
  font-size: 18px;
  color: #555;
  max-width: 680px;
  margin: 0 auto 28px;
  line-height: 1.6;
}

.hero-hint {
  font-size: 13px;
  color: #999;
  margin-top: 12px;
  margin-bottom: 32px;
}

.btn-lg {
  padding: 12px 32px;
  font-size: 16px;
  border-radius: 8px;
}

.trial-error {
  margin-top: 12px;
  color: #e6393d;
  font-size: 14px;
}

.hero-actions {
  display: flex;
  gap: 16px;
  justify-content: center;
  margin-top: 8px;
}

.btn-outline {
  background: transparent;
  color: #0073e6;
  border: 1.5px solid #0073e6;
  border-radius: 6px;
  padding: 10px 24px;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  text-decoration: none;
  transition: background 0.2s;
}

.btn-outline:hover {
  background: rgba(0, 115, 230, 0.06);
}

.btn-primary {
  background: #0073e6;
  color: #fff;
  border: none;
  border-radius: 6px;
  padding: 10px 24px;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.2s;
  white-space: nowrap;
}

.btn-primary:hover {
  background: #005bb5;
}

.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.btn-secondary {
  background: #fff;
  color: #0073e6;
  border: 1px solid #0073e6;
  border-radius: 6px;
  padding: 8px 20px;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.2s;
}

.btn-secondary:hover {
  background: #f0f5ff;
}


.api-key-result {
  margin-top: 24px;
}

.api-key-box {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  background: #f5f5f5;
  border: 1px solid #ddd;
  border-radius: 6px;
  padding: 10px 16px;
  max-width: 100%;
  overflow-x: auto;
}

.api-key-label {
  font-weight: 600;
  font-size: 14px;
  white-space: nowrap;
}

.api-key-value {
  font-family: 'SF Mono', 'Fira Code', monospace;
  font-size: 13px;
  word-break: break-all;
}

.btn-copy {
  background: #0073e6;
  color: #fff;
  border: none;
  border-radius: 4px;
  padding: 4px 12px;
  font-size: 12px;
  cursor: pointer;
  white-space: nowrap;
}

/* Product Modules */
.module-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 24px;
  margin-bottom: 24px;
}

.module-card {
  background: #fff;
  border-radius: 12px;
  padding: 32px 28px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
  position: relative;
  border-top: 4px solid #ddd;
  transition: transform 0.2s, box-shadow 0.2s;
}

.module-card:hover {
  transform: translateY(-4px);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.12);
}

.module-lakebase { border-top-color: #0073e6; }
.module-kb { border-top-color: #e6a700; }
.module-lake { border-top-color: #2ecc71; }

.module-badge {
  position: absolute;
  top: 12px;
  right: 16px;
  font-size: 11px;
  font-weight: 600;
  padding: 2px 10px;
  border-radius: 10px;
  background: #e8f4fd;
  color: #0073e6;
}

.module-kb .module-badge { background: #fff8e0; color: #b38600; }
.module-lake .module-badge { background: #e8faf0; color: #1a9c4a; }

.module-icon {
  font-size: 40px;
  margin-bottom: 12px;
}

.module-card h3 {
  font-size: 20px;
  font-weight: 700;
  color: #1a1a1a;
  margin-bottom: 4px;
}

.module-subtitle {
  font-size: 13px;
  color: #888;
  margin-bottom: 16px;
}

.module-features {
  list-style: none;
  padding: 0;
  margin: 0;
}

.module-features li {
  font-size: 14px;
  color: #555;
  padding: 6px 0;
  border-bottom: 1px solid #f0f0f0;
  line-height: 1.5;
}

.module-features li:last-child {
  border-bottom: none;
}

.module-features li::before {
  content: '✓ ';
  color: #0073e6;
  font-weight: 600;
}

.module-kb .module-features li::before { color: #e6a700; }
.module-lake .module-features li::before { color: #2ecc71; }

.module-coming {
  display: flex;
  align-items: center;
  gap: 12px;
  background: #f7f9fc;
  border: 1px dashed #ccc;
  border-radius: 8px;
  padding: 16px 24px;
  font-size: 14px;
  color: #666;
}

.module-coming-icon {
  font-size: 28px;
  flex-shrink: 0;
}

.module-coming-desc {
  color: #888;
}

/* Cards */
.card-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 24px;
}

.card-grid.three {
  grid-template-columns: repeat(3, 1fr);
}

.card {
  background: #fff;
  border-radius: 8px;
  padding: 32px 28px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
  transition: box-shadow 0.2s;
}

.card:hover {
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.1);
}

.card-icon {
  font-size: 32px;
  margin-bottom: 16px;
}

.card h3 {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 10px;
  color: #1a1a1a;
}

.card p {
  font-size: 14px;
  color: #666;
  line-height: 1.6;
  margin: 0;
}


/* Architecture */
.arch-diagram {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-bottom: 48px;
  flex-wrap: wrap;
}

.arch-box {
  padding: 18px 28px;
  border-radius: 8px;
  font-weight: 600;
  font-size: 16px;
  text-align: center;
}

.app-box {
  background: #f0f5ff;
  border: 2px solid #b3d4fc;
  color: #333;
}

.lakeon-box {
  background: #0073e6;
  color: #fff;
  border: 2px solid #005bb5;
  font-size: 20px;
  padding: 18px 36px;
}

.arch-arrow {
  font-size: 28px;
  color: #999;
}

.arch-capabilities {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.arch-cap-box {
  background: #f7f9fc;
  border: 1px solid #d0d0d0;
  border-radius: 6px;
  padding: 10px 18px;
  font-size: 14px;
  font-weight: 500;
}

.arch-highlights {
  max-width: 600px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.arch-point {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  font-size: 15px;
  color: #333;
  line-height: 1.5;
}

.arch-bullet {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  min-width: 26px;
  background: #0073e6;
  color: #fff;
  border-radius: 50%;
  font-size: 13px;
  font-weight: 700;
}

/* Steps */
.steps {
  display: flex;
  flex-direction: column;
  gap: 40px;
  max-width: 720px;
  margin: 0 auto;
}

.step {
  position: relative;
}

.step-num {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  background: #0073e6;
  color: #fff;
  border-radius: 50%;
  font-size: 15px;
  font-weight: 700;
  margin-bottom: 12px;
}

.step h3 {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 12px;
  color: #1a1a1a;
}

.step p {
  font-size: 14px;
  color: #666;
  margin-bottom: 12px;
}

.code-label {
  font-size: 13px;
  font-weight: 600;
  color: #888;
  margin-bottom: 4px;
}

.code-block {
  background: #1e1e1e;
  color: #d4d4d4;
  border-radius: 6px;
  padding: 16px 20px;
  overflow-x: auto;
  font-size: 13px;
  line-height: 1.6;
  margin: 0;
}

.code-block code {
  font-family: 'SF Mono', 'Fira Code', 'Consolas', monospace;
  white-space: pre;
}

/* Pricing */
.center-text {
  text-align: center;
}

.pricing-headline {
  font-size: 22px;
  font-weight: 600;
  color: #1a1a1a;
  margin-bottom: 8px;
}

.pricing-sub {
  font-size: 15px;
  color: #888;
  margin-bottom: 28px;
}

/* Footer */
.footer {
  background: #1a1a1a;
  color: #aaa;
  padding: 24px 0;
  font-size: 13px;
}

.footer-inner {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}

.footer-links {
  display: flex;
  gap: 8px;
  align-items: center;
}

.footer-links a {
  color: #aaa;
  text-decoration: none;
  transition: color 0.2s;
}

.footer-links a:hover {
  color: #fff;
}

.footer-sep {
  color: #555;
}

/* Responsive */
@media (max-width: 768px) {
  .nav-links,
  .nav-right {
    display: none;
  }

  .mobile-menu-btn {
    display: block;
  }

  .mobile-menu {
    display: flex;
  }

  .hero-title {
    font-size: 28px;
  }

  .hero-subtitle {
    font-size: 15px;
  }

  .register-form {
    flex-direction: column;
  }

  .card-grid,
  .card-grid.three,
  .module-grid {
    grid-template-columns: 1fr;
  }

  .arch-diagram {
    flex-direction: column;
  }

  .arch-arrow {
    transform: rotate(90deg);
  }

  .arch-capabilities {
    justify-content: center;
  }

  .footer-inner {
    flex-direction: column;
    text-align: center;
  }
}
</style>
