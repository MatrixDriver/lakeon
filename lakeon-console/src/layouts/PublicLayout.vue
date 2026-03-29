<template>
  <div class="public-layout">
    <nav class="pub-nav">
      <div class="pub-nav-inner">
        <!-- Brand -->
        <router-link to="/" class="pub-brand">
          <span class="pub-brand-mark">&#9875;</span>DBay
        </router-link>

        <!-- Desktop nav -->
        <div class="pub-nav-links">
          <!-- 产品 dropdown -->
          <NavDropdown :label="t('产品', 'Products')">
            <div class="nav-product-grid">
              <router-link to="/product/lakebase" class="nav-item nav-item-grid">
                <span class="nav-item-dot" style="background: #1e2d3d"></span>
                <div>
                  <div class="nav-item-title">Lakebase</div>
                  <div class="nav-item-desc">Serverless PostgreSQL</div>
                </div>
              </router-link>
              <router-link to="/product/knowledge" class="nav-item nav-item-grid">
                <span class="nav-item-dot" style="background: #4a8b8c"></span>
                <div>
                  <div class="nav-item-title">{{ t('知识库', 'Knowledge Base') }}</div>
                  <div class="nav-item-desc">{{ t('文档 + 向量搜索', 'Docs + Vector Search') }}</div>
                </div>
              </router-link>
              <router-link to="/product/memory" class="nav-item nav-item-grid">
                <span class="nav-item-dot" style="background: #c67d3a"></span>
                <div>
                  <div class="nav-item-title">{{ t('记忆库', 'Memory Store') }}</div>
                  <div class="nav-item-desc">{{ t('Agent 长期记忆', 'Agent Long-term Memory') }}</div>
                </div>
              </router-link>
              <router-link to="/product/datalake" class="nav-item nav-item-grid">
                <span class="nav-item-dot" style="background: #7a5195"></span>
                <div>
                  <div class="nav-item-title">{{ t('数据湖', 'Data Lake') }}</div>
                  <div class="nav-item-desc">{{ t('数据处理 + 训练', 'Processing + Training') }}</div>
                </div>
              </router-link>
            </div>
            <div class="nav-divider"></div>
            <router-link to="/product" class="nav-item">
              <span class="nav-item-title" style="color: var(--pub-accent, #c67d3a)">{{ t('产品架构总览', 'Architecture Overview') }}</span>
            </router-link>
          </NavDropdown>

          <!-- 集成 dropdown -->
          <NavDropdown :label="t('集成', 'Integrations')">
            <router-link to="/integrations#mcp" class="nav-item">
              <span class="nav-item-title">{{ t('MCP 集成', 'MCP Integration') }}</span>
            </router-link>
            <router-link to="/integrations#skill" class="nav-item">
              <span class="nav-item-title">{{ t('Skill 集成', 'Skill Integration') }}</span>
            </router-link>
            <router-link to="/integrations#pg" class="nav-item">
              <span class="nav-item-title">{{ t('PostgreSQL 协议', 'PostgreSQL Protocol') }}</span>
            </router-link>
            <router-link to="/integrations#rest" class="nav-item">
              <span class="nav-item-title">REST API</span>
            </router-link>
            <div class="nav-divider"></div>
            <router-link to="/docs/rest-api" class="nav-item">
              <span class="nav-item-title" style="color: var(--pub-accent, var(--pub-primary))">{{ t('API 文档', 'API Docs') }}</span>
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
          <!-- Theme toggle -->
          <button class="theme-btn" @click="toggleTheme" :aria-label="theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'">
            <span v-if="theme === 'dark'" style="font-size: 14px">&#9788;</span>
            <span v-else style="font-size: 14px">&#9790;</span>
          </button>
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
import { useTheme } from '../stores/theme'
import { useAuthStore } from '../stores/auth'
import client from '../api/client'
import NavDropdown from '../components/public/NavDropdown.vue'
import MobileNav from '../components/public/MobileNav.vue'

const { locale, setLocale, t } = useLocale()
const { theme, toggle: toggleTheme } = useTheme()
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
  background: var(--pub-surface);
  border-bottom: 1px solid var(--pub-border);
}
.pub-nav-inner {
  max-width: 1200px;
  margin: 0 auto;
  padding: 0 24px;
  height: 52px;
  display: flex;
  align-items: center;
  gap: 24px;
}
.pub-brand {
  font-family: var(--pub-sans, inherit);
  font-weight: 700;
  font-size: 17px;
  color: var(--pub-text);
  text-decoration: none;
  white-space: nowrap;
  margin-right: 8px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.pub-brand-mark {
  font-size: 20px;
  color: var(--pub-accent, #c67d3a);
}
.pub-nav-links {
  display: flex;
  align-items: center;
  gap: 2px;
  flex: 1;
}
.pub-nav-link {
  font-size: 13px;
  color: var(--pub-text-2);
  padding: 6px 12px;
  border-radius: 6px;
  text-decoration: none;
  transition: color 0.15s, background 0.15s;
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
.theme-btn {
  background: none;
  border: none;
  cursor: pointer;
  padding: 4px 6px;
  border-radius: 6px;
  font-size: 16px;
  line-height: 1;
  transition: background 0.15s;
}
.theme-btn:hover { background: var(--pub-hover); }
.lang-btn {
  background: none;
  border: none;
  color: var(--pub-text-2);
  font-size: 13px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
  transition: color 0.15s, background 0.15s;
}
.lang-btn:hover { color: var(--pub-text); background: var(--pub-hover); }
.btn-signin {
  background: var(--pub-btn-bg);
  color: var(--pub-btn-text);
  font-size: 13px;
  font-weight: 500;
  padding: 6px 16px;
  border-radius: 6px;
  text-decoration: none;
  transition: background 0.15s;
}
.btn-signin:hover { background: var(--pub-btn-hover); }
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
  transition: background 0.15s;
}
.nav-item:hover { background: var(--pub-hover); }
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
.nav-item-icon {
  font-size: 18px;
  flex-shrink: 0;
}
.nav-item-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.nav-divider {
  height: 1px;
  background: var(--pub-border);
  margin: 4px 0;
}
.btn-trial {
  background: var(--pub-btn-bg, #1e2d3d);
  color: var(--pub-btn-text, #fff) !important;
  padding: 6px 16px;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 600;
  text-decoration: none;
  transition: opacity 0.15s;
}
.btn-trial:hover {
  opacity: 0.9;
}
@media (max-width: 768px) {
  .pub-nav-links { display: none; }
  .hamburger { display: flex; }
}
</style>
