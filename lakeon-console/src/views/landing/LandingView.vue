<template>
  <div class="landing-page">
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
        <h2 class="section-title">{{ t('四大产品模块', 'Four Product Modules') }}</h2>
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
          <div class="module-card module-memory">
            <div class="module-badge">{{ t('已上线', 'Live') }}</div>
            <div class="module-icon">&#x1F9E0;</div>
            <h3>{{ t('记忆库', 'Memory Store') }}</h3>
            <p class="module-subtitle">{{ t('AI Agent 长期记忆引擎', 'Long-term Memory Engine') }}</p>
            <ul class="module-features">
              <li>{{ t('事实 / 事件 / 特征 / 文档四类记忆', 'Fact / Episode / Trait / Document memory types') }}</li>
              <li>{{ t('ingest · recall · digest 三个核心 API', 'Three core APIs: ingest · recall · digest') }}</li>
              <li>{{ t('向量 + BM25 + 知识图谱混合检索', 'Hybrid: vector + BM25 + knowledge graph') }}</li>
              <li>{{ t('特征生命周期 6 阶段自动演化', '6-stage trait lifecycle evolution') }}</li>
              <li>{{ t('LoCoMo 基准测试 81.7% 综合得分', 'LoCoMo benchmark: 81.7% overall score') }}</li>
              <li>{{ t('MCP 协议接入，5 分钟集成', 'MCP protocol, 5-minute integration') }}</li>
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
        <p class="section-desc">{{ t('一个连接串，统一关系型、向量、全文、图查询、RAG — 数据在库与湖之间自动流转', 'One connection string for relational, vector, full-text, graph, RAG — data flows automatically between DB and Lake') }}</p>
        <div class="arch-svg-wrap">
          <svg viewBox="0 0 960 520" xmlns="http://www.w3.org/2000/svg" class="arch-svg">
            <defs>
              <linearGradient id="gBase" x1="0" y1="0" x2="1" y2="1"><stop offset="0%" stop-color="#0073e6"/><stop offset="100%" stop-color="#005bb5"/></linearGradient>
              <linearGradient id="gKB" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stop-color="#7c3aed"/><stop offset="100%" stop-color="#5b21b6"/></linearGradient>
              <linearGradient id="gLake" x1="0" y1="0" x2="0" y2="1"><stop offset="0%" stop-color="#0891b2"/><stop offset="100%" stop-color="#0e7490"/></linearGradient>
              <linearGradient id="gTime" x1="0" y1="0" x2="1" y2="0"><stop offset="0%" stop-color="#d97706"/><stop offset="100%" stop-color="#b45309"/></linearGradient>
              <marker id="arrowR" markerWidth="8" markerHeight="6" refX="8" refY="3" orient="auto"><path d="M0,0 L8,3 L0,6" fill="#0073e6"/></marker>
              <marker id="arrowL" markerWidth="8" markerHeight="6" refX="0" refY="3" orient="auto"><path d="M8,0 L0,3 L8,6" fill="#0891b2"/></marker>
              <marker id="arrowD" markerWidth="6" markerHeight="8" refX="3" refY="8" orient="auto"><path d="M0,0 L3,8 L6,0" fill="#d97706"/></marker>
            </defs>

            <!-- AI Agent / App - top -->
            <rect x="340" y="16" width="280" height="52" rx="26" fill="#f0f5ff" stroke="#b3d4fc" stroke-width="2"/>
            <text x="480" y="48" text-anchor="middle" font-size="15" font-weight="600" fill="#333">AI Agent / {{ locale === 'zh' ? '应用' : 'App' }}</text>

            <!-- Arrow down from agent to Lakebase -->
            <line x1="480" y1="68" x2="480" y2="104" stroke="#0073e6" stroke-width="2" marker-end="url(#arrowD)"/>

            <!-- Lakebase - center foundation -->
            <rect x="180" y="110" width="600" height="80" rx="12" fill="url(#gBase)"/>
            <text x="480" y="145" text-anchor="middle" font-size="22" font-weight="700" fill="#fff">Lakebase</text>
            <text x="480" y="172" text-anchor="middle" font-size="13" fill="rgba(255,255,255,0.8)">Serverless PostgreSQL · {{ locale === 'zh' ? '存算分离 · 自动扩缩容' : 'Disaggregated Storage · Auto-scaling' }}</text>

            <!-- Capability badges inside Lakebase area -->
            <g transform="translate(200, 200)">
              <rect x="0" y="0" width="110" height="34" rx="17" fill="#e0edff" stroke="#b3d4fc" stroke-width="1"/>
              <text x="55" y="22" text-anchor="middle" font-size="12" font-weight="600" fill="#0052a3">{{ locale === 'zh' ? '关系型' : 'Relational' }}</text>

              <rect x="124" y="0" width="100" height="34" rx="17" fill="#e0edff" stroke="#b3d4fc" stroke-width="1"/>
              <text x="174" y="22" text-anchor="middle" font-size="12" font-weight="600" fill="#0052a3">pgvector</text>

              <rect x="238" y="0" width="100" height="34" rx="17" fill="#e0edff" stroke="#b3d4fc" stroke-width="1"/>
              <text x="288" y="22" text-anchor="middle" font-size="12" font-weight="600" fill="#0052a3">{{ locale === 'zh' ? '全文检索' : 'Full-text' }}</text>

              <rect x="352" y="0" width="80" height="34" rx="17" fill="#e0edff" stroke="#b3d4fc" stroke-width="1"/>
              <text x="392" y="22" text-anchor="middle" font-size="12" font-weight="600" fill="#0052a3">{{ locale === 'zh' ? '图查询' : 'Graph' }}</text>

              <rect x="446" y="0" width="110" height="34" rx="17" fill="#e0edff" stroke="#b3d4fc" stroke-width="1"/>
              <text x="501" y="22" text-anchor="middle" font-size="12" font-weight="600" fill="#0052a3">{{ locale === 'zh' ? '内置 RAG' : 'Built-in RAG' }}</text>
            </g>

            <!-- Knowledge Base - left -->
            <rect x="40" y="270" width="260" height="100" rx="12" fill="url(#gKB)"/>
            <text x="170" y="308" text-anchor="middle" font-size="17" font-weight="700" fill="#fff">{{ locale === 'zh' ? '知识库' : 'Knowledge Base' }}</text>
            <text x="170" y="332" text-anchor="middle" font-size="12" fill="rgba(255,255,255,0.8)">{{ locale === 'zh' ? '文档 · 表 · 向量 · 全文混合检索' : 'Docs · Tables · Hybrid Vector+FTS' }}</text>
            <text x="170" y="352" text-anchor="middle" font-size="12" fill="rgba(255,255,255,0.8)">{{ locale === 'zh' ? '内置 Embedding & Reranker' : 'Built-in Embedding & Reranker' }}</text>

            <!-- Line from Lakebase down to KB -->
            <line x1="300" y1="234" x2="220" y2="270" stroke="#7c3aed" stroke-width="1.5" stroke-dasharray="4,3" opacity="0.6"/>

            <!-- AI Data Lake - right -->
            <rect x="660" y="270" width="260" height="100" rx="12" fill="url(#gLake)"/>
            <text x="790" y="308" text-anchor="middle" font-size="17" font-weight="700" fill="#fff">{{ locale === 'zh' ? 'AI 数据湖' : 'AI Data Lake' }}</text>
            <text x="790" y="332" text-anchor="middle" font-size="12" fill="rgba(255,255,255,0.8)">{{ locale === 'zh' ? 'Python · Ray · 微调 · Parquet' : 'Python · Ray · Fine-tune · Parquet' }}</text>
            <text x="790" y="352" text-anchor="middle" font-size="12" fill="rgba(255,255,255,0.8)">{{ locale === 'zh' ? 'Kata VM 安全隔离' : 'Kata VM Isolation' }}</text>

            <!-- Line from Lakebase down to Data Lake -->
            <line x1="660" y1="234" x2="740" y2="270" stroke="#0891b2" stroke-width="1.5" stroke-dasharray="4,3" opacity="0.6"/>

            <!-- Data Flywheel - bidirectional arrows between Lakebase and Data Lake -->
            <line x1="780" y1="155" x2="870" y2="270" stroke="#0073e6" stroke-width="2" marker-end="url(#arrowR)"/>
            <line x1="870" y1="270" x2="780" y2="190" stroke="#0891b2" stroke-width="2" marker-start="url(#arrowL)"/>
            <rect x="850" y="200" width="110" height="28" rx="14" fill="#fef3c7" stroke="#d97706" stroke-width="1.5"/>
            <text x="905" y="219" text-anchor="middle" font-size="11" font-weight="700" fill="#92400e">{{ locale === 'zh' ? '数据飞轮' : 'Data Flywheel' }}</text>

            <!-- Time Travel - bottom center -->
            <rect x="320" y="280" width="320" height="80" rx="12" fill="url(#gTime)" opacity="0.9"/>
            <text x="480" y="312" text-anchor="middle" font-size="17" font-weight="700" fill="#fff">{{ locale === 'zh' ? '时间旅行' : 'Time Travel' }}</text>
            <text x="480" y="336" text-anchor="middle" font-size="12" fill="rgba(255,255,255,0.85)">{{ locale === 'zh' ? '数据库分支 · 版本管理 · 即时回滚' : 'DB Branching · Versioning · Instant Rollback' }}</text>
            <text x="480" y="352" text-anchor="middle" font-size="12" fill="rgba(255,255,255,0.85)">{{ locale === 'zh' ? '像 Git 一样管理数据' : 'Manage data like Git' }}</text>

            <!-- Line from Lakebase down to Time Travel -->
            <line x1="480" y1="234" x2="480" y2="280" stroke="#d97706" stroke-width="1.5" stroke-dasharray="4,3" opacity="0.6"/>

            <!-- Memory Store - live -->
            <rect x="40" y="400" width="200" height="60" rx="10" fill="#1a1a2e" stroke="#7c3aed" stroke-width="1.5"/>
            <text x="140" y="428" text-anchor="middle" font-size="14" font-weight="600" fill="#a78bfa">{{ locale === 'zh' ? '记忆库' : 'Memory Store' }}</text>
            <text x="140" y="448" text-anchor="middle" font-size="11" fill="#a78bfa">{{ locale === 'zh' ? '已上线' : 'Live' }}</text>

            <!-- Infrastructure bar - bottom -->
            <rect x="40" y="484" width="880" height="32" rx="6" fill="#f1f5f9" stroke="#cbd5e1" stroke-width="1"/>
            <text x="480" y="505" text-anchor="middle" font-size="12" font-weight="500" fill="#64748b">Kubernetes · OBS · RDS · {{ locale === 'zh' ? '弹性节点池 · 自动扩缩' : 'Elastic Node Pool · Auto-scaling' }}</text>
          </svg>
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

const { locale, t } = useLocale()
const router = useRouter()
const authStore = useAuthStore()

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
.module-memory {
  border-color: #7c3aed;
  background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
}
.module-memory .module-badge {
  background: #7c3aed22;
  color: #a78bfa;
  border-color: #7c3aed44;
}

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
.module-memory .module-features li::before { color: #a78bfa; }
.module-memory .module-features li { color: #c4b5fd; border-bottom-color: #2d2d4e; }
.module-memory h3 { color: #e9d5ff; }
.module-memory .module-subtitle { color: #a78bfa; }

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


/* Architecture SVG */
.arch-svg-wrap {
  max-width: 800px;
  margin: 32px auto 0;
}

.arch-svg {
  width: 100%;
  height: auto;
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

/* Responsive */
@media (max-width: 768px) {
  .hero-title {
    font-size: 28px;
  }

  .hero-subtitle {
    font-size: 15px;
  }

  .card-grid,
  .card-grid.three,
  .module-grid {
    grid-template-columns: 1fr;
  }

  .footer-inner {
    flex-direction: column;
    text-align: center;
  }
}
</style>
