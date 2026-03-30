<template>
  <div class="landing">
    <!-- Hero -->
    <section class="hero">
      <div class="hero-inner">
        <p class="hero-eyebrow">{{ t('数据库 · 知识库 · 记忆库 · 数据湖', 'Database · Knowledge · Memory · Data Lake') }}</p>
        <h1 class="hero-title">
          {{ t('Agent 时代的', 'Data Infrastructure') }}<br>
          <span class="hero-accent">{{ t('数据基础设施', 'for the Agent Era') }}</span>
        </h1>
        <p class="hero-subtitle">
          {{ t(
            '一个平台，赋予你的 Agent 存储、检索、记忆和分析的全部能力',
            'One platform — give your agents the power to store, retrieve, remember, and analyze'
          ) }}
        </p>
        <div class="hero-ctas">
          <button class="cta-primary" @click="startTrial" :disabled="trialLoading">
            {{ trialLoading ? t('创建中...', 'Creating...') : t('立即试用 →', 'Try Now →') }}
          </button>
          <router-link to="/docs" class="cta-secondary">{{ t('查看文档', 'Read Docs') }}</router-link>
        </div>
        <p class="hero-hint">{{ t('无需注册，30 秒获得一个数据库', 'No signup needed, get a database in 30 seconds') }}</p>
      </div>
    </section>

    <!-- Stats bar -->
    <section class="stats">
      <div class="stats-inner">
        <div class="stat" v-for="s in stats" :key="s.value">
          <div class="stat-value">{{ s.value }}</div>
          <div class="stat-label">{{ s.label }}</div>
        </div>
      </div>
    </section>

    <!-- Three layers intro -->
    <section class="layers-intro fade-in">
      <h2 class="section-title">{{ t('从数据库到数据平台，按需解锁', 'From database to data platform, unlock on demand') }}</h2>
      <p class="section-subtitle">{{ t('不用一次全买，从第一层开始，按需向上扩展', 'Start with layer one, scale up as you need') }}</p>
    </section>

    <!-- Layer 1: Left text, Right SVG -->
    <section class="layer layer-alt fade-in">
      <div class="layer-inner">
        <div class="layer-text">
          <span class="layer-badge layer-badge-primary">{{ t('第一层 · 基础', 'Layer 1 · Foundation') }}</span>
          <h3 class="layer-title">Lakebase — Serverless PostgreSQL</h3>
          <p class="layer-desc">
            {{ t(
              '用你现有的 PG 客户端直接连接，按需弹性伸缩，空闲自动休眠。内置 pgvector 向量搜索、时间旅行数据版本管理。',
              'Connect with any PG client. Elastic scaling, auto-sleep on idle. Built-in pgvector and time travel versioning.'
            ) }}
          </p>
          <div class="layer-hint">
            <strong>{{ t('已在用 mem0、Hindsight？', 'Already using mem0 or Hindsight?') }}</strong><br>
            {{ t(
              '直接把数据库换成 Lakebase — 零改造，立刻获得 Serverless 弹性和高性价比。',
              'Swap your database to Lakebase — zero changes, instant Serverless elasticity.'
            ) }}
          </div>
          <router-link to="/product#lakebase" class="layer-link">{{ t('了解 Lakebase →', 'Learn about Lakebase →') }}</router-link>
        </div>
        <div class="layer-visual">
          <LayerLakebase />
        </div>
      </div>
    </section>

    <!-- Layer 2: Left SVG, Right text -->
    <section class="layer fade-in">
      <div class="layer-inner layer-inner-reverse">
        <div class="layer-text">
          <span class="layer-badge layer-badge-secondary">{{ t('第二层 · 进阶', 'Layer 2 · Advanced') }}</span>
          <h3 class="layer-title">{{ t('记忆 + 知识 + 状态服务', 'Memory + Knowledge + State Services') }}</h3>
          <p class="layer-desc">
            {{ t(
              'DBay 提供完全发挥 Lakebase 能力的原生服务 — 记忆持久化、知识 RAG、Agent 状态管理。Lakebase 的多分支能力让 Agent 可以并行探索不同方案，通过 MCP / Skill 直接对接。',
              'DBay provides native services that fully leverage Lakebase — memory persistence, knowledge RAG, and agent state management. Lakebase branching enables parallel exploration of different strategies, connected via MCP/Skill.'
            ) }}
          </p>
          <div class="layer-tags">
            <span class="tag">{{ t('MCP 一键接入', 'MCP one-click') }}</span>
            <span class="tag">{{ t('记忆自动提取', 'Auto memory extraction') }}</span>
            <span class="tag">{{ t('文档向量化', 'Doc vectorization') }}</span>
            <span class="tag">{{ t('分支并行探索', 'Branch parallel explore') }}</span>
            <span class="tag">{{ t('Console 管理', 'Console management') }}</span>
          </div>
          <router-link to="/product#memory" class="layer-link">{{ t('了解记忆、知识与状态服务 →', 'Learn about Memory, Knowledge & State →') }}</router-link>
        </div>
        <div class="layer-visual">
          <LayerServices />
        </div>
      </div>
    </section>

    <!-- Layer 3: Left text, Right SVG -->
    <section class="layer layer-alt fade-in">
      <div class="layer-inner">
        <div class="layer-text">
          <span class="layer-badge layer-badge-tertiary">{{ t('第三层 · 数据闭环', 'Layer 3 · Data Loop') }}</span>
          <h3 class="layer-title">{{ t('AI 多模态数据湖', 'AI Multimodal Data Lake') }}</h3>
          <p class="layer-desc">
            {{ t(
              '数据库、知识库、记忆库与数据湖双向联动 — 导入导出自如。在数据湖上运行 Ray、Python 作业，实现数据加工和分析，形成',
              'Database, knowledge, and memory bidirectionally sync with the data lake. Run Ray/Python jobs for processing and analysis, forming a'
            ) }}
            <strong class="flywheel-accent">{{ t('数据飞轮', 'data flywheel') }}</strong>{{ t(
              '：Agent 使用积累数据 → 数据湖分析产生洞察 → 优化 Agent 表现 → 积累更多高质量数据。',
              ': Agent usage generates data → Lake analyzes for insights → Optimize agent → More quality data.'
            ) }}
          </p>
          <div class="layer-tags">
            <span class="tag tag-purple">{{ t('分析 Agent 用户习惯', 'Analyze agent user behavior') }}</span>
            <span class="tag tag-blue">{{ t('记忆数据导出微调', 'Export memory for fine-tuning') }}</span>
            <span class="tag tag-orange">{{ t('知识库质量评估', 'Knowledge quality assessment') }}</span>
            <span class="tag tag-green">{{ t('过期记忆清洗归档', 'Clean & archive stale memory') }}</span>
          </div>
          <router-link to="/product#datalake" class="layer-link">{{ t('了解数据湖 →', 'Learn about Data Lake →') }}</router-link>
        </div>
        <div class="layer-visual">
          <LayerDatalake />
        </div>
      </div>
    </section>

    <!-- Bottom CTA -->
    <section class="bottom-cta fade-in">
      <div class="bottom-cta-inner">
        <h2 class="bottom-cta-title">{{ t('30 秒，免费获得一个 Serverless 数据库', 'Get a free Serverless database in 30 seconds') }}</h2>
        <p class="bottom-cta-subtitle">{{ t('无需信用卡 · 兼容所有 PG 客户端 · 立即体验', 'No credit card · Works with all PG clients · Try now') }}</p>
        <div class="bottom-cta-buttons">
          <button class="cta-white" @click="startTrial" :disabled="trialLoading">
            {{ trialLoading ? t('创建中...', 'Creating...') : t('立即试用 →', 'Try Now →') }}
          </button>
          <router-link to="/dashboard" class="cta-outline">{{ t('进入 Console', 'Open Console') }}</router-link>
        </div>
      </div>
    </section>

    <!-- Footer -->
    <footer class="landing-footer">
      <span>© 2026 DBay · {{ t('数据港湾', 'Data Harbor') }}</span>
      <div class="footer-links">
        <router-link to="/docs">{{ t('文档', 'Docs') }}</router-link>
        <router-link to="/docs/rest-api">API</router-link>
      </div>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useLocale } from '../../stores/locale'
import { useAuthStore } from '../../stores/auth'
import client from '../../api/client'
import LayerLakebase from './LayerLakebase.vue'
import LayerServices from './LayerServices.vue'
import LayerDatalake from './LayerDatalake.vue'

const { t } = useLocale()
const router = useRouter()
const authStore = useAuthStore()
const trialLoading = ref(false)

const stats = computed(() => [
  { value: '<1s', label: t('冷启动', 'Cold Start') },
  { value: t('自动', 'Auto'), label: t('弹性伸缩', 'Elastic Scale') },
  { value: t('时间旅行', 'Time Travel'), label: t('数据版本管理', 'Data Versioning') },
  { value: '0', label: t('运维负担', 'Ops Burden') },
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

// Scroll-triggered fade-in
let observer: IntersectionObserver | null = null
onMounted(() => {
  observer = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add('visible')
          observer?.unobserve(entry.target)
        }
      })
    },
    { threshold: 0.15 }
  )
  document.querySelectorAll('.fade-in').forEach((el) => observer?.observe(el))
})
onUnmounted(() => observer?.disconnect())
</script>

<style scoped>
/* Layout */
.landing { max-width: 100%; overflow-x: hidden; }

/* Hero */
.hero {
  padding: 80px 24px 48px;
  text-align: center;
  background: linear-gradient(180deg, var(--pub-hero-start, #f0f5ff) 0%, var(--pub-surface) 100%);
}
.hero-inner {
  max-width: 720px;
  margin: 0 auto;
}
.hero-eyebrow {
  font-size: 13px;
  font-weight: 600;
  color: var(--pub-primary);
  letter-spacing: 2.5px;
  text-transform: uppercase;
  margin: 0 0 16px;
}
.hero-title {
  font-size: 52px;
  font-weight: 800;
  color: var(--pub-text);
  line-height: 1.15;
  letter-spacing: -1px;
  margin: 0;
}
.hero-accent { color: var(--pub-primary); }
.hero-subtitle {
  font-size: 17px;
  color: var(--pub-text-2);
  margin: 20px auto 0;
  line-height: 1.7;
  max-width: 540px;
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
  padding: 13px 36px;
  border-radius: 10px;
  font-size: 16px;
  font-weight: 600;
  border: none;
  cursor: pointer;
  transition: transform 0.15s, box-shadow 0.15s;
  box-shadow: 0 2px 8px rgba(0, 115, 230, 0.2);
}
.cta-primary:hover { transform: translateY(-1px); box-shadow: 0 4px 16px rgba(0, 115, 230, 0.3); }
.cta-primary:disabled { opacity: 0.6; cursor: not-allowed; transform: none; box-shadow: none; }
.cta-secondary {
  background: var(--pub-hover);
  color: var(--pub-text);
  padding: 13px 36px;
  border-radius: 10px;
  font-size: 16px;
  text-decoration: none;
  transition: background 0.15s;
}
.cta-secondary:hover { background: var(--pub-border); }
.hero-hint {
  font-size: 13px;
  color: var(--pub-text-3);
  margin-top: 14px;
}

/* Stats */
.stats {
  background: var(--pub-surface);
  border-top: 1px solid var(--pub-border);
  border-bottom: 1px solid var(--pub-border);
  padding: 28px 24px;
}
.stats-inner {
  max-width: 800px;
  margin: 0 auto;
  display: flex;
  justify-content: space-between;
}
.stat { text-align: center; flex: 1; }
.stat-value {
  font-size: 28px;
  font-weight: 800;
  color: var(--pub-primary);
}
.stat-label {
  font-size: 12px;
  color: var(--pub-text-3);
  margin-top: 2px;
}

/* Layers intro */
.layers-intro {
  padding: 56px 24px 0;
  text-align: center;
}
.section-title {
  font-size: 32px;
  font-weight: 700;
  color: var(--pub-text);
  margin: 0;
  letter-spacing: -0.3px;
}
.section-subtitle {
  font-size: 15px;
  color: var(--pub-text-3);
  margin-top: 8px;
}

/* Layer sections */
.layer {
  padding: 48px 24px;
}
.layer-alt { background: var(--pub-bg-alt, #f8f9fb); }
.layer-inner {
  max-width: 1040px;
  margin: 0 auto;
  display: flex;
  gap: 56px;
  align-items: center;
}
.layer-inner-reverse { flex-direction: row-reverse; }
.layer-text { flex: 1; min-width: 0; }
.layer-visual { flex: 0 0 420px; max-width: 420px; }

.layer-badge {
  padding: 4px 14px;
  border-radius: 20px;
  font-size: 11px;
  font-weight: 700;
  display: inline-block;
  margin-bottom: 14px;
  color: #fff;
}
.layer-badge-primary { background: var(--pub-primary); }
.layer-badge-secondary { background: #e67e22; }
.layer-badge-tertiary { background: #7b1fa2; }

.layer-title {
  font-size: 26px;
  font-weight: 700;
  color: var(--pub-text);
  margin: 0 0 12px;
  letter-spacing: -0.2px;
}
.layer-desc {
  font-size: 15px;
  color: var(--pub-text-2);
  line-height: 1.75;
  margin: 0 0 18px;
}
.flywheel-accent { color: #7b1fa2; }
[data-theme="dark"] .flywheel-accent { color: #ce93d8; }

.layer-hint {
  padding: 14px 16px;
  background: var(--pub-hint-bg, #f0fdf4);
  border-radius: 10px;
  font-size: 13px;
  color: var(--pub-hint-text, #2e7d32);
  line-height: 1.6;
  border: 1px solid var(--pub-hint-border, #c8e6c9);
  margin-bottom: 16px;
}
.layer-tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}
.tag {
  background: var(--pub-bg-alt, #f8f9fb);
  border: 1px solid var(--pub-border);
  border-radius: 6px;
  padding: 6px 12px;
  font-size: 12px;
  color: var(--pub-text-2);
}
.tag-purple { background: var(--tag-purple-bg, #f3e5f5); color: var(--tag-purple-text, #7b1fa2); border-color: transparent; }
.tag-blue { background: var(--tag-blue-bg, #e3f2fd); color: var(--tag-blue-text, #1565c0); border-color: transparent; }
.tag-orange { background: var(--tag-orange-bg, #fff3e0); color: var(--tag-orange-text, #e65100); border-color: transparent; }
.tag-green { background: var(--tag-green-bg, #e8f5e9); color: var(--tag-green-text, #2e7d32); border-color: transparent; }

.layer-link {
  font-size: 14px;
  color: var(--pub-primary);
  font-weight: 600;
  text-decoration: none;
  transition: opacity 0.15s;
}
.layer-link:hover { opacity: 0.8; }

/* Bottom CTA */
.bottom-cta {
  background: linear-gradient(135deg, #0062cc, var(--pub-primary));
  padding: 56px 24px;
  text-align: center;
}
.bottom-cta-inner {
  max-width: 600px;
  margin: 0 auto;
}
.bottom-cta-title {
  font-size: 28px;
  font-weight: 700;
  color: #fff;
  margin: 0 0 8px;
}
.bottom-cta-subtitle {
  font-size: 14px;
  color: rgba(255,255,255,0.7);
  margin: 0 0 24px;
}
.bottom-cta-buttons {
  display: flex;
  gap: 12px;
  justify-content: center;
}
.cta-white {
  background: #fff;
  color: var(--pub-primary);
  padding: 13px 36px;
  border-radius: 10px;
  font-size: 16px;
  font-weight: 600;
  border: none;
  cursor: pointer;
  transition: transform 0.15s, box-shadow 0.15s;
}
.cta-white:hover { transform: translateY(-1px); box-shadow: 0 4px 16px rgba(0,0,0,0.15); }
.cta-white:disabled { opacity: 0.6; cursor: not-allowed; transform: none; }
.cta-outline {
  border: 1.5px solid rgba(255,255,255,0.5);
  color: #fff;
  padding: 13px 36px;
  border-radius: 10px;
  font-size: 16px;
  text-decoration: none;
  transition: border-color 0.15s;
}
.cta-outline:hover { border-color: #fff; }

/* Footer */
.landing-footer {
  background: var(--pub-footer-bg, #111);
  padding: 16px 48px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 11px;
  color: var(--pub-footer-text, #666);
}
.footer-links {
  display: flex;
  gap: 16px;
}
.footer-links a {
  color: var(--pub-footer-text, #666);
  text-decoration: none;
}
.footer-links a:hover { color: var(--pub-footer-link-hover, #999); }

/* Fade-in animation */
.fade-in {
  opacity: 0;
  transform: translateY(24px);
  transition: opacity 0.6s ease-out, transform 0.6s ease-out;
}
.fade-in.visible {
  opacity: 1;
  transform: translateY(0);
}

/* Responsive */
@media (max-width: 768px) {
  .hero { padding: 48px 20px 32px; }
  .hero-title { font-size: 34px; }
  .hero-subtitle { font-size: 15px; }
  .stats-inner { gap: 12px; flex-wrap: wrap; }
  .stat-value { font-size: 22px; }
  .layer { padding: 32px 20px; }
  .layer-inner { flex-direction: column; gap: 24px; }
  .layer-inner-reverse { flex-direction: column; }
  .layer-visual { flex: none; max-width: 100%; width: 100%; }
  .layers-intro { padding: 32px 20px 0; }
  .section-title { font-size: 24px; }
  .bottom-cta { padding: 40px 20px; }
  .landing-footer { padding: 14px 20px; }
}
</style>
