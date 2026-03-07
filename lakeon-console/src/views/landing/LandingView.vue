<template>
  <div class="landing-page">
    <!-- Top Navigation Bar -->
    <nav class="nav-bar">
      <div class="nav-inner">
        <div class="nav-logo">Lakeon</div>
        <div class="nav-links">
          <a href="#features" @click.prevent="scrollTo('features')">{{ t('特性', 'Features') }}</a>
          <a href="#capabilities" @click.prevent="scrollTo('capabilities')">{{ t('能力', 'Capabilities') }}</a>
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
        <a href="#features" @click.prevent="scrollTo('features'); mobileMenuOpen = false">{{ t('特性', 'Features') }}</a>
        <a href="#capabilities" @click.prevent="scrollTo('capabilities'); mobileMenuOpen = false">{{ t('能力', 'Capabilities') }}</a>
        <a href="#scenarios" @click.prevent="scrollTo('scenarios'); mobileMenuOpen = false">{{ t('场景', 'Scenarios') }}</a>
        <a href="#quickstart" @click.prevent="scrollTo('quickstart'); mobileMenuOpen = false">{{ t('快速开始', 'Quick Start') }}</a>
        <router-link to="/login" @click="mobileMenuOpen = false">{{ t('登录', 'Sign In') }}</router-link>
      </div>
    </nav>

    <!-- Hero Section -->
    <section class="hero" id="hero">
      <div class="container">
        <h1 class="hero-title">{{ t('为 AI 应用而生的 Serverless 数据平台', 'The Serverless Data Platform Built for AI') }}</h1>
        <p class="hero-subtitle">{{ t('秒级创建，自动休眠，按需付费。关系型、向量、全文、图查询、RAG、时间旅行，一个平台全搞定。', 'Create in seconds, auto-sleep, pay-per-use. Relational, vector, full-text, graph, RAG, time travel — all in one platform.') }}</p>
        <div class="cap-row" id="capabilities">
          <div class="cap-item" v-for="c in capabilities" :key="c.label">
            <div class="cap-icon">{{ c.icon }}</div>
            <div class="cap-label">{{ c.name }}</div>
            <div class="cap-desc">{{ c.label }}</div>
            <span v-if="c.soon" class="badge-soon">{{ t('即将上线', 'Coming Soon') }}</span>
          </div>
        </div>
      </div>
    </section>

    <!-- Core Features -->
    <section class="section bg-alt" id="features">
      <div class="container">
        <h2 class="section-title">{{ t('核心特性', 'Core Features') }}</h2>
        <div class="card-grid">
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
        <div class="arch-diagram">
          <div class="arch-box app-box">{{ t('你的应用', 'Your App') }}</div>
          <div class="arch-arrow">&rarr;</div>
          <div class="arch-box lakeon-box">Lakeon</div>
          <div class="arch-arrow">&rarr;</div>
          <div class="arch-capabilities">
            <div class="arch-cap-box">SQL</div>
            <div class="arch-cap-box">Vector</div>
            <div class="arch-cap-box">Search</div>
            <div class="arch-cap-box">Graph</div>
            <div class="arch-cap-box">RAG</div>
            <div class="arch-cap-box">Time Travel</div>
          </div>
        </div>
        <div class="arch-highlights">
          <div class="arch-point">
            <span class="arch-bullet">1</span>
            <span>{{ t('标准 PostgreSQL 协议，无需学习新接口', 'Standard PostgreSQL protocol, no new interfaces to learn') }}</span>
          </div>
          <div class="arch-point">
            <span class="arch-bullet">2</span>
            <span>{{ t('自动弹性伸缩，从零到海量无感切换', 'Auto-scaling from zero to massive, seamlessly') }}</span>
          </div>
          <div class="arch-point">
            <span class="arch-bullet">3</span>
            <span>{{ t('向量、全文、图查询、RAG 全部内置，一个连接串搞定所有', 'Vector, full-text, graph, RAG all built-in — one connection string for everything') }}</span>
          </div>
          <div class="arch-point">
            <span class="arch-bullet">4</span>
            <span>{{ t('数据库分支实现时间旅行，像 Git 一样管理 AI 数据版本', 'Database branching enables time travel — manage AI data versions like Git') }}</span>
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
            <p>{{ t('在上方输入租户名称，点击免费试用获取 API Key', 'Enter a tenant name above and click Free Trial to get your API Key') }}</p>
            <button class="btn-secondary" @click="scrollTo('hero')">{{ t('前往注册', 'Go to Sign Up') }}</button>
          </div>
          <div class="step">
            <div class="step-num">2</div>
            <h3>{{ t('创建数据库', 'Create Database') }}</h3>
            <pre class="code-block"><code>curl -X POST https://api.lakeon.cn/v1/databases \
  -H "Authorization: Bearer YOUR_API_KEY" \
  -d '{"name": "mydb"}'</code></pre>
          </div>
          <div class="step">
            <div class="step-num">3</div>
            <h3>{{ t('连接使用', 'Connect') }}</h3>
            <div class="code-label">Python</div>
            <pre class="code-block"><code>import psycopg2
conn = psycopg2.connect("postgres://user@api.lakeon.cn:4432/mydb?options=endpoint%3Dmydb")</code></pre>
            <div class="code-label" style="margin-top: 12px;">Node.js</div>
            <pre class="code-block"><code>import pg from 'pg'
const client = new pg.Client("postgres://user@api.lakeon.cn:4432/mydb?options=endpoint%3Dmydb")
await client.connect()</code></pre>
          </div>
        </div>
      </div>
    </section>

    <!-- Pricing -->
    <section class="section" id="pricing">
      <div class="container center-text">
        <h2 class="section-title">{{ t('定价', 'Pricing') }}</h2>
        <p class="pricing-headline">{{ t('免费试用，开箱即用', 'Free to try, ready out of the box') }}</p>
        <p class="pricing-sub">{{ t('详细定价即将公布', 'Detailed pricing coming soon') }}</p>
        <button class="btn-primary" @click="scrollTo('hero')">{{ t('免费试用', 'Free Trial') }}</button>
      </div>
    </section>

    <!-- Footer -->
    <footer class="footer">
      <div class="container footer-inner">
        <span>&copy; 2026 Lakeon. All rights reserved.</span>
        <div class="footer-links">
          <a href="#">{{ t('文档', 'Docs') }}</a>
          <span class="footer-sep">|</span>
          <router-link to="/login">{{ t('控制台', 'Console') }}</router-link>
          <span class="footer-sep">|</span>
          <a href="https://github.com" target="_blank" rel="noopener">GitHub</a>
        </div>
      </div>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useLocale } from '../../stores/locale'

const { locale, setLocale, t } = useLocale()

const mobileMenuOpen = ref(false)

function toggleLocale() {
  setLocale(locale.value === 'zh' ? 'en' : 'zh')
}

function scrollTo(id: string) {
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth' })
}

const features = computed(() => [
  { icon: '\u2601', title: t('Serverless 架构', 'Serverless Architecture'), desc: t('自动休眠唤醒，不使用时零成本', 'Auto sleep/wake, zero cost when idle') },
  { icon: '\u26A1', title: t('秒级就绪', 'Instant Ready'), desc: t('几秒内创建数据库，零运维负担', 'Create a database in seconds, zero ops burden') },
  { icon: '\uD83C\uDF3F', title: t('数据库分支', 'Database Branching'), desc: t('像 Git 一样管理数据，安全地开发和测试', 'Manage data like Git, develop and test safely') },
  { icon: '\uD83D\uDD17', title: t('存算分离', 'Disaggregated Storage'), desc: t('存储弹性扩展，计算按需启停', 'Elastic storage scaling, on-demand compute') },
])

const capabilities = computed(() => [
  { icon: '\uD83D\uDC18', name: 'PostgreSQL', label: t('关系型查询', 'Relational Queries'), soon: false },
  { icon: '\uD83D\uDD0D', name: 'pgvector', label: t('向量 & 多模态检索', 'Vector & Multimodal Search'), soon: false },
  { icon: '\uD83D\uDCC4', name: 'RUM', label: t('全文检索', 'Full-text Search'), soon: false },
  { icon: '\uD83D\uDD78', name: 'SQL Graph', label: t('图查询 (CTE)', 'Graph Queries (CTE)'), soon: false },
  { icon: '\uD83E\uDDE0', name: 'pgrag', label: t('内置 RAG', 'Built-in RAG'), soon: false },
  { icon: '\u23F3', name: 'Time Travel', label: t('时间旅行', 'Time Travel'), soon: false },
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
  margin: 0 auto 36px;
  line-height: 1.6;
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

/* Capabilities */
.cap-row {
  display: flex;
  justify-content: center;
  gap: 40px;
  flex-wrap: wrap;
}

.cap-item {
  text-align: center;
  position: relative;
  min-width: 120px;
}

.cap-icon {
  font-size: 36px;
  margin-bottom: 10px;
}

.cap-label {
  font-size: 16px;
  font-weight: 600;
  color: #1a1a1a;
  margin-bottom: 4px;
}

.cap-desc {
  font-size: 13px;
  color: #888;
}

.badge-soon {
  display: inline-block;
  margin-top: 8px;
  background: #fff3e0;
  color: #e67700;
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 10px;
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
  .card-grid.three {
    grid-template-columns: 1fr;
  }

  .cap-row {
    gap: 24px;
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
