<template>
  <div class="public-layout">
    <nav class="pub-nav">
      <div class="pub-nav-inner">
        <!-- Brand -->
        <router-link to="/" class="pub-brand">
          DBay <span class="pub-tagline">{{ t('数据港湾', 'Data Harbor') }}</span>
        </router-link>

        <!-- Desktop nav -->
        <div class="pub-nav-links">
          <!-- 产品 dropdown -->
          <NavDropdown :label="t('产品', 'Products')">
            <router-link to="/product#lakebase" class="nav-item">
              <span class="nav-item-title">Lakebase</span>
              <span class="nav-item-desc">{{ t('Serverless PostgreSQL · 存算分离', 'Serverless PostgreSQL · Disaggregated') }}</span>
            </router-link>
            <router-link to="/product#knowledge" class="nav-item">
              <span class="nav-item-title">{{ t('知识库', 'Knowledge Base') }}</span>
              <span class="nav-item-desc">{{ t('文档 + 向量 + 全文混合检索', 'Docs + Vector + Hybrid FTS') }}</span>
            </router-link>
            <router-link to="/product#memory" class="nav-item">
              <span class="nav-item-title">
                {{ t('记忆库', 'Memory Store') }}
                <span class="badge-new">New</span>
              </span>
              <span class="nav-item-desc">{{ t('AI Agent 长期记忆引擎', 'Long-term memory for AI Agents') }}</span>
            </router-link>
            <router-link to="/product#datalake" class="nav-item">
              <span class="nav-item-title">{{ t('AI 数据湖', 'AI Data Lake') }}</span>
              <span class="nav-item-desc">{{ t('Python · Ray · 微调 · 数据飞轮', 'Python · Ray · Fine-tuning · Flywheel') }}</span>
            </router-link>
          </NavDropdown>

          <!-- 集成 dropdown -->
          <NavDropdown :label="t('集成', 'Integrations')">
            <router-link to="/integrations/openclaw" class="nav-item">
              <span class="nav-item-title">OpenClaw <span class="badge-featured">{{ t('精选', 'Featured') }}</span></span>
              <span class="nav-item-desc">{{ t('龙虾 AI 助手，原生记忆集成', 'OpenClaw AI with native memory') }}</span>
            </router-link>
            <router-link to="/integrations#claude-code" class="nav-item">
              <span class="nav-item-title">Claude Code</span>
              <span class="nav-item-desc">{{ t('通过 MCP 接入记忆库与知识库', 'Memory + KB via MCP') }}</span>
            </router-link>
            <router-link to="/integrations#claude-desktop" class="nav-item">
              <span class="nav-item-title">Claude Desktop</span>
              <span class="nav-item-desc">{{ t('桌面客户端记忆持久化', 'Persistent memory for desktop') }}</span>
            </router-link>
            <router-link to="/integrations#cursor" class="nav-item">
              <span class="nav-item-title">Cursor</span>
              <span class="nav-item-desc">{{ t('代码库知识库检索', 'Codebase knowledge retrieval') }}</span>
            </router-link>
            <router-link to="/integrations#gemini-cli" class="nav-item">
              <span class="nav-item-title">Gemini CLI</span>
              <span class="nav-item-desc">{{ t('命令行 AI 长期记忆', 'Long-term memory for CLI AI') }}</span>
            </router-link>
            <router-link to="/integrations#chatgpt" class="nav-item">
              <span class="nav-item-title">ChatGPT</span>
              <span class="nav-item-desc">{{ t('跨会话用户记忆同步', 'Cross-session memory sync') }}</span>
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
          <!-- Mobile hamburger -->
          <button class="hamburger" @click="mobileOpen = !mobileOpen" aria-label="Menu">
            <span></span><span></span><span></span>
          </button>
        </div>
      </div>

      <!-- Mobile overlay menu -->
      <MobileNav v-if="mobileOpen" :locale="locale" @close="mobileOpen = false" />
    </nav>

    <router-view />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useLocale } from '../stores/locale'
import NavDropdown from '../components/public/NavDropdown.vue'
import MobileNav from '../components/public/MobileNav.vue'

const { locale, setLocale, t } = useLocale()
const mobileOpen = ref(false)

function toggleLocale() {
  setLocale(locale.value === 'zh' ? 'en' : 'zh')
}
</script>

<style scoped>
.pub-nav {
  position: sticky;
  top: 0;
  z-index: 100;
  background: #fff;
  border-bottom: 1px solid #e5e5e5;
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
  font-weight: 700;
  font-size: 17px;
  color: #1a1a1a;
  text-decoration: none;
  white-space: nowrap;
  margin-right: 8px;
}
.pub-tagline {
  font-weight: 400;
  font-size: 12px;
  color: #999;
  margin-left: 4px;
}
.pub-nav-links {
  display: flex;
  align-items: center;
  gap: 2px;
  flex: 1;
}
.pub-nav-link {
  font-size: 13px;
  color: #555;
  padding: 6px 12px;
  border-radius: 6px;
  text-decoration: none;
  transition: color 0.15s, background 0.15s;
}
.pub-nav-link:hover {
  color: #1a1a1a;
  background: #f5f5f5;
}
.pub-nav-right {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-left: auto;
}
.lang-btn {
  background: none;
  border: none;
  color: #666;
  font-size: 13px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
}
.lang-btn:hover { color: #333; background: #f5f5f5; }
.btn-signin {
  background: #1a1a1a;
  color: #fff;
  font-size: 13px;
  font-weight: 500;
  padding: 6px 16px;
  border-radius: 6px;
  text-decoration: none;
  transition: background 0.15s;
}
.btn-signin:hover { background: #333; }
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
  background: #555;
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
.nav-item:hover { background: #f5f5f5; }
.nav-item-title {
  font-size: 13px;
  font-weight: 500;
  color: #1a1a1a;
}
.nav-item-desc {
  font-size: 11px;
  color: #888;
  margin-top: 1px;
}
@media (max-width: 768px) {
  .pub-nav-links { display: none; }
  .hamburger { display: flex; }
}
</style>
