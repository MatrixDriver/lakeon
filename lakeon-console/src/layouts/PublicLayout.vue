<template>
  <div class="public-layout">
    <nav class="pub-nav">
      <div class="pub-nav-inner">
        <!-- Brand -->
        <router-link to="/" class="pub-brand">
          DBay <span class="pub-brand-dot"></span><span class="pub-tagline">{{ t('数据港湾', 'Data Harbor') }}</span>
        </router-link>

        <!-- Desktop nav -->
        <div class="pub-nav-links">
          <!-- 产品 dropdown -->
          <NavDropdown :label="t('产品', 'Products')">
            <div class="nav-product-grid">
              <router-link to="/product#lakebase" class="nav-item nav-item-grid">
                <span class="nav-dot nav-dot-lakebase"></span>
                <div>
                  <div class="nav-item-title">Lakebase</div>
                  <div class="nav-item-desc">Serverless PostgreSQL</div>
                </div>
              </router-link>
              <router-link to="/product#knowledge" class="nav-item nav-item-grid">
                <span class="nav-dot nav-dot-knowledge"></span>
                <div>
                  <div class="nav-item-title">{{ t('知识库', 'Knowledge Base') }}</div>
                  <div class="nav-item-desc">{{ t('文档 + 向量搜索', 'Docs + Vector Search') }}</div>
                </div>
              </router-link>
              <router-link to="/product#memory" class="nav-item nav-item-grid">
                <span class="nav-dot nav-dot-memory"></span>
                <div>
                  <div class="nav-item-title">{{ t('记忆库', 'Memory Store') }}</div>
                  <div class="nav-item-desc">{{ t('Agent 长期记忆', 'Agent Long-term Memory') }}</div>
                </div>
              </router-link>
              <router-link to="/product#datalake" class="nav-item nav-item-grid">
                <span class="nav-dot nav-dot-datalake"></span>
                <div>
                  <div class="nav-item-title">{{ t('数据湖', 'Data Lake') }}</div>
                  <div class="nav-item-desc">{{ t('数据处理 + 训练', 'Processing + Training') }}</div>
                </div>
              </router-link>
            </div>
          </NavDropdown>

          <!-- 集成 dropdown -->
          <NavDropdown :label="t('集成', 'Integrations')">
            <router-link to="/integrations/openclaw" class="nav-item nav-item-row">
              <span class="nav-dot nav-dot-knowledge"></span>
              <span class="nav-item-title">OpenClaw</span>
            </router-link>
            <router-link to="/integrations#claude-code" class="nav-item nav-item-row">
              <span class="nav-dot nav-dot-lakebase"></span>
              <span class="nav-item-title">Claude Code</span>
            </router-link>
            <router-link to="/integrations#cursor" class="nav-item nav-item-row">
              <span class="nav-dot nav-dot-memory"></span>
              <span class="nav-item-title">Cursor</span>
            </router-link>
            <router-link to="/integrations#gemini-cli" class="nav-item nav-item-row">
              <span class="nav-dot nav-dot-datalake"></span>
              <span class="nav-item-title">Gemini CLI</span>
            </router-link>
            <div class="nav-divider"></div>
            <router-link to="/integrations" class="nav-item">
              <span class="nav-item-title" style="color: var(--pub-text-2)">{{ t('查看全部', 'View all') }} →</span>
            </router-link>
            <router-link to="/docs/rest-api" class="nav-item">
              <span class="nav-item-title" style="color: var(--pub-primary)">{{ t('API 文档', 'API Docs') }}</span>
            </router-link>
          </NavDropdown>

          <!-- 博客 direct link -->
          <router-link to="/blog" class="pub-nav-link">{{ t('博客', 'Blog') }}</router-link>

          <!-- 文档 dropdown -->
          <NavDropdown :label="t('文档', 'Docs')">
            <router-link to="/docs" class="nav-item">
              <span class="nav-item-title">{{ t('快速开始', 'Quick Start') }}</span>
              <span class="nav-item-desc">{{ t('5 分钟接入 DBay', '5 min integration guide') }}</span>
            </router-link>
            <router-link to="/docs/rest-api" class="nav-item">
              <span class="nav-item-title">REST API</span>
              <span class="nav-item-desc">{{ t('完整 API 参考', 'Full API reference') }}</span>
            </router-link>
            <router-link to="/docs/python-sdk" class="nav-item">
              <span class="nav-item-title">Python SDK</span>
              <span class="nav-item-desc">{{ t('dbay Python 客户端', 'dbay Python client') }}</span>
            </router-link>
            <router-link to="/docs/deploy" class="nav-item">
              <span class="nav-item-title">{{ t('部署指南', 'Deploy Guide') }}</span>
              <span class="nav-item-desc">{{ t('自托管部署', 'Self-hosted deployment') }}</span>
            </router-link>
            <router-link to="/docs/mcp" class="nav-item">
              <span class="nav-item-title">MCP {{ t('接入', 'Integration') }}</span>
              <span class="nav-item-desc">{{ t('dbay-mcp 配置指南', 'dbay-mcp setup guide') }}</span>
            </router-link>
          </NavDropdown>
        </div>

        <!-- Right side -->
        <div class="pub-nav-right">
          <button class="lang-btn" @click="toggleLocale">{{ locale === 'zh' ? 'EN' : '中' }}</button>
          <router-link to="/login" class="btn-signin">{{ t('登录', 'Sign In') }}</router-link>
          <a href="#" class="btn-trial" @click.prevent="handleNavTrial">{{ t('立即试用', 'Try Now') }}</a>
          <!-- Mobile hamburger -->
          <button class="hamburger" @click="mobileOpen = !mobileOpen" aria-label="Menu">
            <span></span><span></span><span></span>
          </button>
        </div>
      </div>

      <!-- Mobile overlay menu -->
      <MobileNav v-if="mobileOpen" @close="mobileOpen = false" />
    </nav>

    <router-view />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useLocale } from '../stores/locale'
import { useAuthStore } from '../stores/auth'
import client from '../api/client'
import NavDropdown from '../components/public/NavDropdown.vue'
import MobileNav from '../components/public/MobileNav.vue'

const { locale, setLocale, t } = useLocale()
const router = useRouter()
const authStore = useAuthStore()
const mobileOpen = ref(false)

function toggleLocale() {
  setLocale(locale.value === 'zh' ? 'en' : 'zh')
}

async function handleNavTrial() {
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
  }
}
</script>

<style scoped>
.pub-nav {
  position: sticky;
  top: 0;
  z-index: 100;
  background: rgba(255, 255, 255, 0.85);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  border-bottom: 1px solid var(--pub-border);
}
.pub-nav-inner {
  max-width: 1200px;
  margin: 0 auto;
  padding: 0 24px;
  height: 56px;
  display: flex;
  align-items: center;
  gap: 24px;
}
.pub-brand {
  font-weight: 700;
  font-size: 17px;
  color: var(--pub-text);
  text-decoration: none;
  white-space: nowrap;
  margin-right: 8px;
}
.pub-brand-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--pub-primary);
  margin: 0 6px;
  vertical-align: middle;
}
.pub-tagline {
  font-weight: 400;
  font-size: 12px;
  color: var(--pub-text-4);
}
.pub-nav-links {
  display: flex;
  align-items: center;
  gap: 2px;
  flex: 1;
}
.pub-nav-link {
  font-size: 13px;
  font-weight: 500;
  color: var(--pub-text-2);
  padding: 6px 12px;
  border-radius: 6px;
  text-decoration: none;
  transition: color 0.25s ease, background 0.25s ease;
}
.pub-nav-link:hover {
  color: var(--pub-text);
  background: var(--pub-hover);
}
.pub-nav-right {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}
.lang-btn {
  background: none;
  border: none;
  color: var(--pub-text-2);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
  transition: color 0.25s ease, background 0.25s ease;
}
.lang-btn:hover { color: var(--pub-text); background: var(--pub-hover); }
.btn-signin {
  background: transparent;
  color: var(--pub-text);
  font-size: 13px;
  font-weight: 500;
  padding: 6px 16px;
  border-radius: 24px;
  border: 1px solid var(--pub-border);
  text-decoration: none;
  transition: background 0.25s ease, border-color 0.25s ease;
}
.btn-signin:hover { background: var(--pub-hover); border-color: var(--pub-text-3); }
.hamburger {
  display: none;
  flex-direction: column;
  gap: 4px;
  background: none;
  border: none;
  cursor: pointer;
  padding: 4px;
}
.hamburger span {
  display: block;
  width: 20px;
  height: 2px;
  background: var(--pub-text-2);
  border-radius: 1px;
}
.badge-new {
  font-size: 10px;
  background: #7c3aed;
  color: #fff;
  padding: 1px 5px;
  border-radius: 3px;
  margin-left: 5px;
  vertical-align: middle;
}
.badge-featured {
  font-size: 10px;
  background: #7c3aed15;
  color: #7c3aed;
  padding: 1px 5px;
  border-radius: 3px;
  margin-left: 5px;
  vertical-align: middle;
}
.nav-item {
  display: flex;
  flex-direction: column;
  padding: 8px 10px;
  border-radius: 6px;
  text-decoration: none;
  transition: background 0.25s ease;
}
.nav-item:hover { background: var(--pub-hover); }
.nav-item-row {
  flex-direction: row;
  align-items: center;
  gap: 8px;
}
.nav-item-title {
  font-size: 13px;
  font-weight: 500;
  color: var(--pub-text);
}
.nav-item-desc {
  font-size: 11px;
  color: var(--pub-text-3);
  margin-top: 1px;
}
.nav-product-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
}
.nav-item-grid {
  flex-direction: row;
  align-items: center;
  gap: 8px;
}
.nav-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.nav-dot-lakebase { background: #0073e6; }
.nav-dot-knowledge { background: #4caf50; }
.nav-dot-memory { background: #ff9800; }
.nav-dot-datalake { background: #7b1fa2; }
.nav-divider {
  height: 1px;
  background: var(--pub-border);
  margin: 4px 0;
}
.btn-trial {
  background: var(--pub-primary, #0073e6);
  color: #fff !important;
  padding: 6px 16px;
  border-radius: 24px;
  font-size: 13px;
  font-weight: 600;
  text-decoration: none;
  transition: opacity 0.25s ease;
}
.btn-trial:hover {
  opacity: 0.9;
}
@media (max-width: 768px) {
  .pub-nav-links { display: none; }
  .hamburger { display: flex; }
}
</style>
