# DBay Landing — 记忆库介绍 + 横向导航 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将记忆库合入 DBay 网站，升级为正式第四模块，并新增产品/集成/博客/文档多路由横向导航。

**Architecture:** 新建 `PublicLayout.vue` 作为所有公共页面（landing/product/integrations/blog/docs）的父路由组件，提供横向下拉导航。Vue Router 使用嵌套路由，`/docs/*` 再嵌套 `DocsLayout.vue`（左侧文档导航）。博客内容静态存储在 `src/data/blog-posts.ts`，使用 `marked` + `DOMPurify` 渲染 Markdown。

**Tech Stack:** Vue 3 + TypeScript, vue-router 4, vitest + @vue/test-utils, marked, dompurify

**Spec:** `docs/superpowers/specs/2026-03-24-dbay-landing-mem-intro-design.md`

**Content source:** `~/code/neuromem-cloud/web/` — 内容迁移时品牌名统一替换（neuromem → DBay 记忆库，api.neuromem.cloud → api.dbay.cloud，包名 neuromem → dbay，工具名 neuromem-mcp → dbay-mcp）

**关键说明：`useLocale` 已存在** — `src/stores/locale.ts` 中已有完整的 `useLocale()` 实现（locale ref + setLocale + t 函数 + localStorage 持久化）。所有新文件直接 import 这个路径，**不需要新建**。路径规则：与 `src/stores/locale.ts` 的相对路径。例如 `src/layouts/PublicLayout.vue` 中 import `'../stores/locale'`，`src/views/*/XxxView.vue` 中 import `'../../stores/locale'`。

**博客文章来源（共 9 篇）：** `~/code/neuromem-cloud/web/content/blog/`
- `memory-architecture.mdx`
- `trait-lifecycle.mdx`
- `user-profile.mdx`
- `conversation-import.mdx`
- `deployment-modes.mdx`
- `one-llm-mode.mdx`
- `openclaw-plugin.mdx`
- `platform-update-march-2026.mdx`
- `sdk-v011-features.mdx`

**文档来源：** `~/code/neuromem-cloud/web/src/app/docs/` — rest-api/page.tsx, python-sdk/page.tsx, deploy/page.tsx。若该目录不存在，在对应页面写 "内容即将推出" 占位即可继续。

---

## File Map

| 文件 | 操作 | 职责 |
|------|------|------|
| `src/layouts/PublicLayout.vue` | 新建 | 横向导航 + `<router-view>`，所有公共页父组件 |
| `src/components/public/NavDropdown.vue` | 新建 | hover 下拉菜单（桌面端） |
| `src/components/public/MobileNav.vue` | 新建 | hamburger + 覆盖层侧边菜单（< 768px） |
| `src/router/index.ts` | 修改 | 新增嵌套公共路由组 |
| `src/views/landing/LandingView.vue` | 修改 | 移除内联 nav-bar，添加记忆库第四模块，更新 SVG |
| `src/views/product/ProductView.vue` | 新建 | 四模块完整介绍 + 记忆库专题 |
| `src/views/integrations/IntegrationsView.vue` | 新建 | 7 个集成工具卡片 + MCP 示例 |
| `src/views/integrations/OpenClawView.vue` | 新建 | OpenClaw 详情页 |
| `src/data/blog-posts.ts` | 新建 | 9 篇博客文章静态数据（从 neuromem MDX 迁移） |
| `src/views/blog/BlogListView.vue` | 新建 | 博客列表页 |
| `src/views/blog/BlogPostView.vue` | 新建 | 博客文章页（marked + DOMPurify 渲染） |
| `src/views/docs/DocsLayout.vue` | 新建 | 左侧文档导航 + `<router-view>`，嵌套在 PublicLayout 内 |
| `src/views/docs/DocsHome.vue` | 新建 | 文档首页（快速开始） |
| `src/views/docs/DocsRestApi.vue` | 新建 | REST API 文档（从 neuromem 迁移） |
| `src/views/docs/DocsPythonSdk.vue` | 新建 | Python SDK 文档（从 neuromem 迁移） |
| `src/views/docs/DocsDeploy.vue` | 新建 | 部署指南（DBay 自托管） |
| `src/views/docs/DocsMcp.vue` | 新建 | MCP 接入指南（dbay-mcp） |
| `src/__tests__/PublicLayout.test.ts` | 新建 | 导航渲染 + 下拉菜单测试 |
| `src/__tests__/BlogPostView.test.ts` | 新建 | Markdown 渲染 + XSS 防护测试 |

---

## Task 1: 安装依赖 + PublicLayout 基础框架

**Files:**
- Modify: `package.json`
- Create: `src/layouts/PublicLayout.vue`
- Create: `src/__tests__/PublicLayout.test.ts`

- [ ] **Step 1: 安装 marked 和 dompurify**

```bash
cd lakeon-console
npm install marked dompurify
npm install -D @types/dompurify
```

Expected: `package.json` 更新，无报错

- [ ] **Step 2: 写 PublicLayout 渲染测试**

新建 `src/__tests__/PublicLayout.test.ts`:

```ts
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import PublicLayout from '../layouts/PublicLayout.vue'

const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/', component: { template: '<div>home</div>' } }],
})

describe('PublicLayout', () => {
  it('renders nav brand', async () => {
    const wrapper = mount(PublicLayout, {
      global: { plugins: [router] },
    })
    await router.isReady()
    expect(wrapper.text()).toContain('DBay')
  })

  it('renders desktop nav items', async () => {
    const wrapper = mount(PublicLayout, {
      global: { plugins: [router] },
    })
    await router.isReady()
    const text = wrapper.text()
    expect(text).toMatch(/产品|Product/)
    expect(text).toMatch(/集成|Integrations/)
    expect(text).toMatch(/博客|Blog/)
    expect(text).toMatch(/文档|Docs/)
  })
})
```

- [ ] **Step 3: 运行测试，确认失败**

```bash
cd lakeon-console
npx vitest run src/__tests__/PublicLayout.test.ts
```

Expected: FAIL — PublicLayout 文件不存在

- [ ] **Step 4: 创建 PublicLayout.vue**

新建 `src/layouts/PublicLayout.vue`:

```vue
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
  background: #0a0a0a;
  border-bottom: 1px solid #1a1a1a;
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
  color: #fff;
  text-decoration: none;
  white-space: nowrap;
  margin-right: 8px;
}
.pub-tagline {
  font-weight: 400;
  font-size: 12px;
  color: #555;
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
  color: #999;
  padding: 6px 12px;
  border-radius: 6px;
  text-decoration: none;
  transition: color 0.15s, background 0.15s;
}
.pub-nav-link:hover {
  color: #fff;
  background: #1a1a1a;
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
.lang-btn:hover { color: #ccc; background: #1a1a1a; }
.btn-signin {
  background: #fff;
  color: #000;
  font-size: 13px;
  font-weight: 500;
  padding: 6px 16px;
  border-radius: 6px;
  text-decoration: none;
  transition: background 0.15s;
}
.btn-signin:hover { background: #e5e5e5; }
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
  background: #ccc;
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
  background: #7c3aed22;
  color: #a78bfa;
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
.nav-item:hover { background: #1a1a1a; }
.nav-item-title {
  font-size: 13px;
  font-weight: 500;
  color: #e5e5e5;
}
.nav-item-desc {
  font-size: 11px;
  color: #666;
  margin-top: 1px;
}
@media (max-width: 768px) {
  .pub-nav-links { display: none; }
  .hamburger { display: flex; }
}
</style>
```

- [ ] **Step 5: 运行测试，确认通过**

```bash
cd lakeon-console
npx vitest run src/__tests__/PublicLayout.test.ts
```

Expected: 2 tests PASS

- [ ] **Step 6: Commit**

```bash
cd lakeon-console
git add src/layouts/PublicLayout.vue src/__tests__/PublicLayout.test.ts package.json package-lock.json
git commit -m "feat: add PublicLayout with horizontal dropdown navigation"
```

---

## Task 2: NavDropdown + MobileNav 组件

**Files:**
- Create: `src/components/public/NavDropdown.vue`
- Create: `src/components/public/MobileNav.vue`

- [ ] **Step 1: 创建 NavDropdown.vue**

新建 `src/components/public/NavDropdown.vue`:

```vue
<template>
  <div class="nav-dropdown" @mouseenter="open = true" @mouseleave="scheduleClose">
    <button class="nav-dropdown-trigger">
      {{ label }}
      <svg class="chevron" viewBox="0 0 10 6" fill="none">
        <path d="M1 1l4 4 4-4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/>
      </svg>
    </button>
    <div v-if="open" class="nav-dropdown-panel" @mouseenter="cancelClose" @mouseleave="scheduleClose">
      <slot />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'

defineProps<{ label: string }>()

const open = ref(false)
let closeTimer: ReturnType<typeof setTimeout> | null = null

function scheduleClose() {
  closeTimer = setTimeout(() => { open.value = false }, 150)
}
function cancelClose() {
  if (closeTimer) { clearTimeout(closeTimer); closeTimer = null }
}
</script>

<style scoped>
.nav-dropdown { position: relative; }
.nav-dropdown-trigger {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: #999;
  background: none;
  border: none;
  padding: 6px 12px;
  border-radius: 6px;
  cursor: pointer;
  transition: color 0.15s, background 0.15s;
}
.nav-dropdown-trigger:hover { color: #fff; background: #1a1a1a; }
.chevron { width: 10px; height: 6px; color: currentColor; }
.nav-dropdown-panel {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  min-width: 260px;
  background: #111;
  border: 1px solid #2a2a2a;
  border-radius: 8px;
  padding: 6px;
  box-shadow: 0 8px 32px rgba(0,0,0,0.6);
  z-index: 200;
}
</style>
```

- [ ] **Step 2: 创建 MobileNav.vue**

新建 `src/components/public/MobileNav.vue`:

```vue
<template>
  <div class="mobile-nav-overlay" @click.self="$emit('close')">
    <div class="mobile-nav-panel">
      <button class="mobile-close" @click="$emit('close')">✕</button>

      <!-- 产品 section -->
      <div class="mobile-section">
        <button class="mobile-section-hd" @click="toggle('product')">
          {{ t('产品', 'Products') }}
          <span>{{ expanded.product ? '▲' : '▼' }}</span>
        </button>
        <div v-if="expanded.product" class="mobile-section-items">
          <router-link to="/product#memory" @click="$emit('close')">
            {{ t('记忆库', 'Memory Store') }} <span class="badge-new">New</span>
          </router-link>
          <router-link to="/product#lakebase" @click="$emit('close')">Lakebase</router-link>
          <router-link to="/product#knowledge" @click="$emit('close')">{{ t('知识库', 'Knowledge Base') }}</router-link>
          <router-link to="/product#datalake" @click="$emit('close')">{{ t('AI 数据湖', 'AI Data Lake') }}</router-link>
        </div>
      </div>

      <!-- 集成 section -->
      <div class="mobile-section">
        <button class="mobile-section-hd" @click="toggle('integrations')">
          {{ t('集成', 'Integrations') }}
          <span>{{ expanded.integrations ? '▲' : '▼' }}</span>
        </button>
        <div v-if="expanded.integrations" class="mobile-section-items">
          <router-link to="/integrations/openclaw" @click="$emit('close')">OpenClaw</router-link>
          <router-link to="/integrations#claude-code" @click="$emit('close')">Claude Code</router-link>
          <router-link to="/integrations#claude-desktop" @click="$emit('close')">Claude Desktop</router-link>
          <router-link to="/integrations#cursor" @click="$emit('close')">Cursor</router-link>
          <router-link to="/integrations#gemini-cli" @click="$emit('close')">Gemini CLI</router-link>
          <router-link to="/integrations#chatgpt" @click="$emit('close')">ChatGPT</router-link>
        </div>
      </div>

      <router-link to="/blog" class="mobile-direct" @click="$emit('close')">{{ t('博客', 'Blog') }}</router-link>

      <!-- 文档 section -->
      <div class="mobile-section">
        <button class="mobile-section-hd" @click="toggle('docs')">
          {{ t('文档', 'Docs') }}
          <span>{{ expanded.docs ? '▲' : '▼' }}</span>
        </button>
        <div v-if="expanded.docs" class="mobile-section-items">
          <router-link to="/docs" @click="$emit('close')">{{ t('快速开始', 'Quick Start') }}</router-link>
          <router-link to="/docs/rest-api" @click="$emit('close')">REST API</router-link>
          <router-link to="/docs/python-sdk" @click="$emit('close')">Python SDK</router-link>
          <router-link to="/docs/deploy" @click="$emit('close')">{{ t('部署指南', 'Deploy') }}</router-link>
          <router-link to="/docs/mcp" @click="$emit('close')">MCP {{ t('接入', 'Integration') }}</router-link>
        </div>
      </div>

      <router-link to="/login" class="mobile-signin" @click="$emit('close')">{{ t('登录', 'Sign In') }}</router-link>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive } from 'vue'
import { useLocale } from '../../stores/locale'

defineProps<{ locale: string }>()
defineEmits<{ close: [] }>()

const { t } = useLocale()
const expanded = reactive({ product: false, integrations: false, docs: false })
function toggle(key: keyof typeof expanded) { expanded[key] = !expanded[key] }
</script>

<style scoped>
.mobile-nav-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.7);
  z-index: 300;
  display: flex;
  justify-content: flex-end;
}
.mobile-nav-panel {
  width: 280px;
  background: #111;
  height: 100%;
  padding: 20px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.mobile-close {
  align-self: flex-end;
  background: none;
  border: none;
  color: #888;
  font-size: 18px;
  cursor: pointer;
  margin-bottom: 12px;
}
.mobile-section-hd {
  width: 100%;
  display: flex;
  justify-content: space-between;
  background: none;
  border: none;
  color: #ccc;
  font-size: 14px;
  font-weight: 500;
  padding: 10px 8px;
  cursor: pointer;
  text-align: left;
}
.mobile-section-items {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding-left: 12px;
  margin-bottom: 8px;
}
.mobile-section-items a {
  color: #888;
  font-size: 13px;
  padding: 7px 8px;
  border-radius: 4px;
  text-decoration: none;
}
.mobile-section-items a:hover { background: #1a1a1a; color: #ccc; }
.mobile-direct {
  color: #ccc;
  font-size: 14px;
  font-weight: 500;
  padding: 10px 8px;
  text-decoration: none;
}
.mobile-signin {
  margin-top: auto;
  background: #fff;
  color: #000;
  font-size: 13px;
  font-weight: 500;
  padding: 10px 16px;
  border-radius: 6px;
  text-decoration: none;
  text-align: center;
}
.badge-new {
  font-size: 9px;
  background: #7c3aed;
  color: #fff;
  padding: 1px 4px;
  border-radius: 3px;
  margin-left: 4px;
  vertical-align: middle;
}
</style>
```

- [ ] **Step 3: Commit**

```bash
cd lakeon-console
git add src/components/public/NavDropdown.vue src/components/public/MobileNav.vue
git commit -m "feat: add NavDropdown and MobileNav components"
```

---

## Task 3: 更新路由 + Landing 页

**Files:**
- Modify: `src/router/index.ts`
- Modify: `src/views/landing/LandingView.vue`

- [ ] **Step 1: 解决 /docs 路径冲突 — 将控制台文档路由改为 /help**

现有 ConsoleLayout 中 `{ path: 'docs', name: 'Docs' }` 会与新的公共 `/docs` 路由冲突。

修改 `src/router/index.ts`，将 ConsoleLayout children 中：
```ts
{ path: 'docs', name: 'Docs', component: () => import('../views/docs/DocsView.vue') },
```
改为：
```ts
{ path: 'help', name: 'Docs', component: () => import('../views/docs/DocsView.vue') },
```

修改 `src/layouts/ConsoleLayout.vue`，将两处引用改为 `/help`：
- 第 158 行：`<router-link to="/docs"` → `<router-link to="/help"`
- 第 217 行：`'/docs'` → `'/help'`

- [ ] **Step 2: 更新 router/index.ts — 添加 PublicLayout 路由组**

**A.** 删除 `/landing` 路由整个对象（保留 `/login` 和 `/report`）

**B.** 在 `/login` 和 `/report` 路由之后、ConsoleLayout 路由之前，插入：

```ts
{
  path: '/',
  component: () => import('../layouts/PublicLayout.vue'),
  meta: { noAuth: true },
  children: [
    { path: '', name: 'Landing', component: () => import('../views/landing/LandingView.vue') },
    { path: 'landing', redirect: '/' },  // 书签兼容
    { path: 'product', name: 'Product', component: () => import('../views/product/ProductView.vue') },
    { path: 'integrations', name: 'Integrations', component: () => import('../views/integrations/IntegrationsView.vue') },
    { path: 'integrations/openclaw', name: 'OpenClaw', component: () => import('../views/integrations/OpenClawView.vue') },
    { path: 'blog', name: 'BlogList', component: () => import('../views/blog/BlogListView.vue') },
    { path: 'blog/:slug', name: 'BlogPost', component: () => import('../views/blog/BlogPostView.vue') },
    {
      path: 'docs',
      component: () => import('../views/docs/DocsLayout.vue'),
      children: [
        { path: '', name: 'DocsHome', component: () => import('../views/docs/DocsHome.vue') },
        { path: 'rest-api', name: 'DocsRestApi', component: () => import('../views/docs/DocsRestApi.vue') },
        { path: 'python-sdk', name: 'DocsPythonSdk', component: () => import('../views/docs/DocsPythonSdk.vue') },
        { path: 'deploy', name: 'DocsDeploy', component: () => import('../views/docs/DocsDeploy.vue') },
        { path: 'mcp', name: 'DocsMcp', component: () => import('../views/docs/DocsMcp.vue') },
      ],
    },
  ],
},
```

**Vue Router 匹配规则**：路由按列表顺序匹配。PublicLayout 列在前，其 children（`''`, `product`, `integrations`, `blog`, `docs`）匹配对应的公共 URL。`dashboard`、`databases` 等路径不在 PublicLayout children 中，继续匹配后面的 ConsoleLayout — 这是 Vue Router 4 的正常行为，两个 `path: '/'` 的路由可以共存。

- [ ] **Step 3: 更新 beforeEach 守卫**

在 `router.beforeEach` 中做两处修改：

```ts
router.beforeEach((to) => {
  // 已登录用户访问 '/' 时跳转到控制台（新增）
  if (to.path === '/') {
    const apiKey = localStorage.getItem('lakeon_api_key')
    if (apiKey) return '/dashboard'
  }
  if (!to.meta.noAuth) {
    const apiKey = localStorage.getItem('lakeon_api_key')
    if (!apiKey) {
      return '/'   // 原为 '/landing'，改为 '/'
    }
  }
})
```


- [ ] **Step 2: 更新 LandingView.vue — 移除内联 nav-bar**

在 `LandingView.vue` 中删除 `<nav class="nav-bar">...</nav>` 整个块（约第 4-26 行），以及对应的 CSS（`.nav-bar`, `.nav-inner`, `.nav-logo`, `.nav-links`, `.nav-right`, `.mobile-menu-btn`, `.mobile-menu` 等 nav 相关样式）。

导航现在由 `PublicLayout.vue` 提供，无需 LandingView 自带。同时移除 script 里的 `mobileMenuOpen` ref。

- [ ] **Step 3: 更新 LandingView.vue — 记忆库升为第四模块**

在 `#modules` section 中：

1. 将标题改为"四大产品模块"（`t('四大产品模块', 'Four Product Modules')`）

2. 找到现有三个 `.module-card` 后新增记忆库卡片（在知识库卡片后、数据湖卡片前插入）：

```html
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
```

3. 删除底部 `.module-coming` 提示条（整个 div）

4. 添加 CSS（在现有 module 样式旁）：

```css
.module-memory {
  border-color: #7c3aed;
  background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
}
.module-memory .module-badge {
  background: #7c3aed22;
  color: #a78bfa;
  border-color: #7c3aed44;
}
```

5. 更新架构图 SVG：找到 `<!-- Memory Store - coming soon -->` 注释的虚线矩形，替换为实线样式并更新文字：

```html
<!-- Memory Store - 已上线 -->
<rect x="40" y="400" width="200" height="60" rx="10" fill="#1a1a2e" stroke="#7c3aed" stroke-width="1.5"/>
<text x="140" y="428" text-anchor="middle" font-size="14" font-weight="600" fill="#a78bfa">{{ locale === 'zh' ? '记忆库' : 'Memory Store' }}</text>
<text x="140" y="448" text-anchor="middle" font-size="11" fill="#7c3aed">DBay Memory</text>
```

- [ ] **Step 4: 本地验证**

```bash
cd lakeon-console
npm run dev
```

访问 `http://localhost:5173`，确认：
- 顶部显示新导航（产品/集成/博客/文档 + 下拉）
- 记忆库作为第四模块卡片显示
- "即将推出"提示条不见了
- 架构图记忆库节点有颜色

- [ ] **Step 5: Commit**

```bash
cd lakeon-console
git add src/router/index.ts src/views/landing/LandingView.vue
git commit -m "feat: update router to PublicLayout nested routes, add memory module to landing"
```

---

## Task 4: /product 产品页

**Files:**
- Create: `src/views/product/ProductView.vue`

- [ ] **Step 1: 创建 ProductView.vue**

新建 `src/views/product/ProductView.vue`。该页面包含四个模块的详细介绍，记忆库专题区域内容最丰富。

**页面结构：**

```vue
<template>
  <main class="product-page">
    <!-- 页面标题 -->
    <section class="prod-hero">
      <h1>{{ t('DBay 四大产品模块', 'DBay Four Product Modules') }}</h1>
      <p>{{ t('以 Lakebase 为底座，覆盖知识库、记忆库、AI 数据湖全场景', 'Lakebase-powered platform covering Knowledge Base, Memory Store, and AI Data Lake') }}</p>
    </section>

    <!-- 模块快速导航 -->
    <section class="prod-nav-cards">
      <a href="#memory" class="prod-nav-card prod-nav-memory">
        <span class="prod-badge-new">New</span>
        <div class="prod-card-icon">🧠</div>
        <div class="prod-card-name">{{ t('记忆库', 'Memory Store') }}</div>
      </a>
      <a href="#lakebase" class="prod-nav-card prod-nav-lakebase">
        <div class="prod-card-icon">🐘</div>
        <div class="prod-card-name">Lakebase</div>
      </a>
      <a href="#knowledge" class="prod-nav-card prod-nav-kb">
        <div class="prod-card-icon">📚</div>
        <div class="prod-card-name">{{ t('知识库', 'Knowledge Base') }}</div>
      </a>
      <a href="#datalake" class="prod-nav-card prod-nav-lake">
        <div class="prod-card-icon">🌊</div>
        <div class="prod-card-name">{{ t('AI 数据湖', 'AI Data Lake') }}</div>
      </a>
    </section>

    <!-- ===== 记忆库专题 ===== -->
    <section id="memory" class="prod-section prod-section-memory">
      <div class="prod-section-inner">
        <div class="prod-section-hd">
          <h2>🧠 {{ t('记忆库', 'Memory Store') }} <span class="badge-new">New</span></h2>
          <p>{{ t('为 AI Agent 提供结构化长期记忆，越用越懂你', 'Structured long-term memory for AI Agents — gets smarter with every interaction') }}</p>
        </div>

        <!-- 三个核心操作 -->
        <h3>{{ t('三个核心操作', 'Three Core Operations') }}</h3>
        <p class="sub">{{ t('三个 API 搞定一切——存储、搜索、理解', 'Three APIs for everything — store, search, understand') }}</p>
        <div class="prod-api-grid">
          <div class="prod-api-card" v-for="api in coreApis" :key="api.name">
            <code class="api-name">{{ api.name }}</code>
            <p>{{ api.desc }}</p>
            <p class="api-detail">{{ api.detail }}</p>
          </div>
        </div>

        <!-- 四种记忆类型 -->
        <h3>{{ t('四种记忆类型', 'Four Memory Types') }}</h3>
        <p class="sub">{{ t('四种记忆类型源自认知心理学，各有分工', 'Four memory types from cognitive psychology, each with a distinct role') }}</p>
        <div class="prod-types-grid">
          <div class="prod-type-card" v-for="mt in memoryTypes" :key="mt.icon">
            <span class="type-icon">{{ mt.icon }}</span>
            <div>
              <h4>{{ mt.title }}</h4>
              <p>{{ mt.desc }}</p>
            </div>
          </div>
        </div>

        <!-- 特征生命周期 -->
        <h3>{{ t('特征生命周期', 'Trait Lifecycle') }}</h3>
        <p class="sub">{{ t('特征随证据积累经历 6 个阶段，置信度在被反驳时衰减，被验证时增强', 'Traits evolve through 6 stages as evidence accumulates — confidence decays when contradicted, grows when confirmed') }}</p>
        <div class="trait-lifecycle">
          <div v-for="(stage, i) in traitStages" :key="stage.key" class="trait-stage">
            <div class="trait-dot" :class="{ first: i === 0, last: i === traitStages.length - 1 }"></div>
            <span>{{ stage.label }}</span>
            <div v-if="i < traitStages.length - 1" class="trait-line"></div>
          </div>
        </div>

        <!-- LoCoMo 基准测试 -->
        <h3>{{ t('LoCoMo 基准测试', 'LoCoMo Benchmark') }}</h3>
        <p class="sub">{{ t('基于 LoCoMo 公开长对话记忆基准测试，涵盖单跳、多跳、时间和开放域推理', 'Evaluated on LoCoMo public long-conversation memory benchmark, covering single-hop, multi-hop, temporal, and open-domain reasoning') }}</p>
        <div class="benchmark-score">
          <div class="score-big">81.7<span>%</span></div>
          <p>{{ t('LoCoMo 综合得分', 'LoCoMo Overall Score') }}</p>
        </div>
        <div class="sub-scores">
          <div v-for="s in subScores" :key="s.key" class="sub-score">
            <div class="sub-score-num">{{ s.score }}</div>
            <div class="sub-score-label">{{ s.label }}</div>
          </div>
        </div>
        <div class="benchmark-bars">
          <div v-for="b in benchmarkBars" :key="b.label" class="bar-row">
            <span class="bar-label" :class="{ highlight: b.highlight }">{{ b.label }}</span>
            <div class="bar-track">
              <div class="bar-fill" :class="{ highlight: b.highlight }" :style="{ width: b.score + '%' }"></div>
            </div>
            <span class="bar-score" :class="{ highlight: b.highlight }">{{ b.score }}%</span>
          </div>
        </div>
        <p class="benchmark-note">{{ t('基于公开基准测试，与主流 AI 记忆框架对比', 'Compared with mainstream AI memory frameworks on public benchmarks') }}</p>
      </div>
    </section>

    <!-- ===== Lakebase ===== -->
    <section id="lakebase" class="prod-section">
      <div class="prod-section-inner">
        <div class="prod-section-hd">
          <h2>🐘 Lakebase</h2>
          <p>{{ t('Serverless PostgreSQL，存算分离，自动扩缩容', 'Serverless PostgreSQL with disaggregated storage and auto-scaling') }}</p>
        </div>
        <!-- 复用 Landing 页特性列表内容，简化版 -->
        <ul class="prod-feature-list">
          <li>{{ t('3ms 热启动，3s 冷启动', '3ms hot start, 3s cold start') }}</li>
          <li>{{ t('存算分离，自动扩缩容', 'Disaggregated storage, auto-scaling') }}</li>
          <li>{{ t('数据库分支与时间旅行（像 Git 管理数据）', 'Database branching & time travel (Git for data)') }}</li>
          <li>{{ t('多版本管理与回滚', 'Version management & rollback') }}</li>
          <li>{{ t('多租户隔离', 'Multi-tenant isolation') }}</li>
          <li>{{ t('AI SQL 助手（自然语言生成 SQL）', 'AI SQL Assistant (NL to SQL)') }}</li>
        </ul>
      </div>
    </section>

    <!-- ===== 知识库 ===== -->
    <section id="knowledge" class="prod-section">
      <div class="prod-section-inner">
        <div class="prod-section-hd">
          <h2>📚 {{ t('知识库', 'Knowledge Base') }}</h2>
          <p>{{ t('文档 + 表 + 向量检索，内置 Embedding 与 Reranker', 'Documents + Tables + Vector Search, built-in Embedding & Reranker') }}</p>
        </div>
        <ul class="prod-feature-list">
          <li>{{ t('文档自动解析（PDF / Word / Markdown）', 'Auto document parsing (PDF / Word / Markdown)') }}</li>
          <li>{{ t('向量检索（pgvector）', 'Vector search (pgvector)') }}</li>
          <li>{{ t('全文搜索（tsvector / RUM）', 'Full-text search (tsvector / RUM)') }}</li>
          <li>{{ t('表知识库（结构化数据）', 'Table KB (structured data)') }}</li>
          <li>{{ t('向量 + 全文混合检索', 'Hybrid vector + full-text retrieval') }}</li>
          <li>{{ t('内置 Embedding 与 Reranker', 'Built-in embedding & reranker') }}</li>
        </ul>
      </div>
    </section>

    <!-- ===== AI 数据湖 ===== -->
    <section id="datalake" class="prod-section">
      <div class="prod-section-inner">
        <div class="prod-section-hd">
          <h2>🌊 {{ t('AI 数据湖', 'AI Data Lake') }}</h2>
          <p>{{ t('数据处理 + 训练 + 飞轮', 'Data Processing + Training + Flywheel') }}</p>
        </div>
        <ul class="prod-feature-list">
          <li>{{ t('Python / Ray 任务调度', 'Python / Ray task scheduling') }}</li>
          <li>{{ t('Dataset 导出（Parquet）', 'Dataset export (Parquet)') }}</li>
          <li>{{ t('模型微调支持', 'Model fine-tuning support') }}</li>
          <li>{{ t('Kata VM 安全隔离', 'Kata VM security isolation') }}</li>
          <li>{{ t('DB ↔ 数据湖 数据飞轮', 'DB ↔ Data Lake data flywheel') }}</li>
          <li>{{ t('增量 CDC 调度', 'Incremental CDC scheduling') }}</li>
        </ul>
      </div>
    </section>
  </main>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useLocale } from '../../stores/locale'

const { locale, t } = useLocale()

const coreApis = computed(() => [
  {
    name: 'ingest',
    desc: t('将对话和事实存入长期记忆，自动提取实体、偏好和事件', 'Store conversations and facts into long-term memory. Auto-extracts entities, preferences, and events.'),
    detail: t('接收原始对话、文档或结构化事实。通过 LLM 驱动的解析自动提取实体和事件片段，以向量嵌入存储用于语义搜索，同时建立 BM25 关键词索引。', 'Receives raw conversations, documents, or structured facts. LLM-driven parsing extracts entities and events, stored as vector embeddings for semantic search alongside BM25 keyword index.'),
  },
  {
    name: 'recall',
    desc: t('混合检索记忆，结合向量、BM25 和知识图谱', 'Hybrid memory retrieval combining vector search, BM25, and knowledge graph traversal.'),
    detail: t('结合稠密向量搜索、稀疏 BM25 评分和知识图谱遍历。结果经过融合与重排序以获得最佳相关性，支持时间过滤和可配置的 top-k。', 'Combines dense vector search, sparse BM25 scoring, and knowledge graph traversal. Results are fusion-ranked for optimal relevance, with time filtering and configurable top-k.'),
  },
  {
    name: 'digest',
    desc: t('将记忆合成洞察，生成用户画像和行为模式', 'Synthesize memories into insights — user profiles and behavioral patterns.'),
    detail: t('分析累积的记忆生成结构化洞察：行为模式、偏好摘要、情感画像和关系图谱。异步运行并缓存结果以便快速获取。', 'Analyzes accumulated memories to generate structured insights: behavioral patterns, preference summaries, emotional profiles, and relationship maps. Runs asynchronously with cached results.'),
  },
])

const memoryTypes = computed(() => [
  { icon: '📋', title: t('事实', 'Fact'), desc: t('从对话中提取的离散、客观、可验证的信息。以向量嵌入存储并关联知识图谱。', 'Discrete, objective, verifiable information extracted from conversations. Stored as vector embeddings linked to the knowledge graph.') },
  { icon: '🕐', title: t('事件', 'Episode'), desc: t('带有情绪元数据（效价、唤醒度、情绪标签）和时间表达的时间绑定事件和体验。', 'Time-bound events and experiences with emotional metadata (valence, arousal, emotion tags) and temporal expressions.') },
  { icon: '🧠', title: t('特征', 'Trait'), desc: t('通过多会话反思发现的行为模式。经历从趋势到核心的 6 阶段生命周期演化。', 'Behavioral patterns discovered through multi-session reflection. Evolves through a 6-stage lifecycle from trend to core.') },
  { icon: '📄', title: t('文档', 'Document'), desc: t('上传的静态参考资料，用于 RAG 风格检索。支持 PDF 和文件的专项搜索。', 'Uploaded static reference materials for RAG-style retrieval. Supports targeted search over PDFs and files.') },
])

const traitStages = computed(() => [
  { key: 'trend', label: t('趋势', 'Trend') },
  { key: 'candidate', label: t('候选', 'Candidate') },
  { key: 'emerging', label: t('萌发', 'Emerging') },
  { key: 'established', label: t('确立', 'Established') },
  { key: 'core', label: t('核心', 'Core') },
  { key: 'dissolved', label: t('消解', 'Dissolved') },
])

const subScores = computed(() => [
  { key: 'singleHop', score: '82.9%', label: t('单跳', 'Single-hop') },
  { key: 'multiHop', score: '84.3%', label: t('多跳', 'Multi-hop') },
  { key: 'openDomain', score: '81.1%', label: t('开放域', 'Open-domain') },
  { key: 'temporal', score: '76.6%', label: t('时间推理', 'Temporal') },
])

const benchmarkBars = [
  { label: 'DBay 记忆库', score: 81.7, highlight: true },
  { label: 'Framework A', score: 75.8, highlight: false },
  { label: 'Framework B', score: 75.1, highlight: false },
  { label: 'Framework C', score: 68.4, highlight: false },
  { label: 'Framework D', score: 66.9, highlight: false },
]
</script>

<style scoped>
.product-page { min-height: 100vh; background: #0a0a0a; color: #e5e5e5; }
.prod-hero { max-width: 800px; margin: 0 auto; padding: 48px 24px 32px; text-align: center; }
.prod-hero h1 { font-size: 32px; font-weight: 700; margin-bottom: 12px; }
.prod-hero p { color: #888; font-size: 16px; }

.prod-nav-cards {
  max-width: 900px; margin: 0 auto 0; padding: 0 24px 32px;
  display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px;
}
.prod-nav-card {
  border-radius: 10px; padding: 16px; text-decoration: none;
  text-align: center; position: relative; transition: transform 0.15s;
  border: 1px solid #2a2a2a; background: #111;
}
.prod-nav-card:hover { transform: translateY(-2px); }
.prod-nav-memory { border-color: #7c3aed; background: #1a1a2e; }
.prod-nav-lakebase { border-color: #0052a3; }
.prod-nav-kb { border-color: #5b21b6; }
.prod-nav-lake { border-color: #0e7490; }
.prod-card-icon { font-size: 24px; margin-bottom: 6px; }
.prod-card-name { font-size: 13px; font-weight: 600; color: #e5e5e5; }
.prod-badge-new {
  position: absolute; top: 8px; right: 8px;
  font-size: 9px; background: #7c3aed; color: #fff;
  padding: 2px 5px; border-radius: 3px;
}

.prod-section { padding: 64px 24px; border-top: 1px solid #1a1a1a; }
.prod-section-memory { background: #0d0d18; }
.prod-section-inner { max-width: 800px; margin: 0 auto; }
.prod-section-hd { margin-bottom: 40px; }
.prod-section-hd h2 { font-size: 26px; font-weight: 700; margin-bottom: 8px; }
.prod-section-hd p { color: #888; font-size: 15px; }
.badge-new {
  font-size: 11px; background: #7c3aed; color: #fff;
  padding: 2px 7px; border-radius: 4px; margin-left: 8px; vertical-align: middle;
}

h3 { font-size: 18px; font-weight: 600; margin: 32px 0 6px; }
.sub { color: #888; font-size: 14px; margin-bottom: 16px; }

.prod-api-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-bottom: 8px; }
.prod-api-card {
  background: #0a0a14; border: 1px solid #2a2a3a;
  border-radius: 8px; padding: 16px;
}
.api-name { font-family: monospace; font-size: 15px; color: #60a5fa; display: block; margin-bottom: 8px; }
.prod-api-card p { font-size: 13px; color: #888; margin: 0 0 8px; }
.api-detail { font-size: 11px; color: #555; }

.prod-types-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; }
.prod-type-card {
  display: flex; gap: 12px; align-items: flex-start;
  background: #0a0a14; border: 1px solid #2a2a3a;
  border-radius: 8px; padding: 14px;
}
.type-icon { font-size: 22px; flex-shrink: 0; }
.prod-type-card h4 { font-size: 14px; font-weight: 600; margin: 0 0 4px; }
.prod-type-card p { font-size: 12px; color: #888; margin: 0; }

.trait-lifecycle {
  display: flex; align-items: center; justify-content: space-between;
  max-width: 600px; margin: 16px 0;
}
.trait-stage { display: flex; align-items: center; flex-direction: column; position: relative; }
.trait-dot { width: 12px; height: 12px; border-radius: 50%; background: #7c3aed; }
.trait-dot.first { background: #a78bfa; }
.trait-dot.last { background: #444; border: 1px dashed #666; }
.trait-stage span { font-size: 10px; color: #666; margin-top: 6px; white-space: nowrap; }
.trait-line { width: 40px; height: 1px; background: #2a2a3a; margin-bottom: 18px; }

.benchmark-score { text-align: center; margin: 16px 0 8px; }
.score-big { font-size: 64px; font-weight: 700; color: #fff; line-height: 1; }
.score-big span { font-size: 36px; color: #888; }
.benchmark-score p { color: #888; font-size: 14px; margin: 4px 0 0; }

.sub-scores { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin: 16px 0; }
.sub-score {
  text-align: center; background: #0a0a14;
  border: 1px solid #2a2a3a; border-radius: 8px; padding: 12px;
}
.sub-score-num { font-size: 20px; font-weight: 600; }
.sub-score-label { font-size: 11px; color: #888; margin-top: 2px; }

.benchmark-bars { display: flex; flex-direction: column; gap: 8px; max-width: 500px; margin: 16px 0; }
.bar-row { display: flex; align-items: center; gap: 10px; }
.bar-label { font-size: 12px; width: 90px; text-align: right; color: #666; }
.bar-label.highlight { color: #e5e5e5; font-weight: 600; }
.bar-track { flex: 1; background: #1a1a1a; border-radius: 4px; height: 16px; overflow: hidden; }
.bar-fill { height: 100%; background: #444; border-radius: 4px; }
.bar-fill.highlight { background: #a78bfa; }
.bar-score { font-size: 12px; width: 36px; color: #666; }
.bar-score.highlight { color: #e5e5e5; font-weight: 600; }
.benchmark-note { font-size: 11px; color: #555; margin-top: 8px; }

.prod-feature-list { list-style: none; padding: 0; margin: 16px 0 0; display: flex; flex-direction: column; gap: 10px; }
.prod-feature-list li {
  padding: 10px 14px; background: #111; border: 1px solid #222;
  border-radius: 6px; font-size: 14px; color: #ccc;
}
.prod-feature-list li::before { content: '✓'; color: #7c3aed; margin-right: 8px; }

@media (max-width: 768px) {
  .prod-nav-cards { grid-template-columns: repeat(2, 1fr); }
  .prod-api-grid { grid-template-columns: 1fr; }
  .sub-scores { grid-template-columns: repeat(2, 1fr); }
}
</style>
```

- [ ] **Step 2: 本地验证**

访问 `http://localhost:5173/product`，确认：
- 四个模块导航卡片显示
- 记忆库专题区完整（三 API + 四类型 + 生命周期 + 基准测试）
- 页内锚点跳转正常（#memory, #lakebase 等）

- [ ] **Step 3: Commit**

```bash
cd lakeon-console
git add src/views/product/ProductView.vue
git commit -m "feat: add /product page with full memory store detail section"
```

---

## Task 5: 博客数据 + 博客页面

**Files:**
- Create: `src/data/blog-posts.ts`
- Create: `src/views/blog/BlogListView.vue`
- Create: `src/views/blog/BlogPostView.vue`
- Create: `src/__tests__/BlogPostView.test.ts`

- [ ] **Step 1: 写 BlogPostView 测试**

新建 `src/__tests__/BlogPostView.test.ts`:

```ts
import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import BlogPostView from '../views/blog/BlogPostView.vue'

const router = createRouter({
  history: createMemoryHistory(),
  routes: [{ path: '/blog/:slug', component: BlogPostView }],
})

describe('BlogPostView', () => {
  it('shows 404 message for unknown slug', async () => {
    router.push('/blog/nonexistent-slug')
    await router.isReady()
    const wrapper = mount(BlogPostView, { global: { plugins: [router] } })
    expect(wrapper.text()).toMatch(/404|找不到|not found/i)
  })

  it('does not render script tags (XSS prevention)', async () => {
    router.push('/blog/memory-architecture')
    await router.isReady()
    const wrapper = mount(BlogPostView, { global: { plugins: [router] } })
    // DOMPurify removes <script> tags — rendered HTML should not contain them
    expect(wrapper.html()).not.toContain('<script')
  })
})
```

- [ ] **Step 2: 运行测试，确认失败**

```bash
cd lakeon-console
npx vitest run src/__tests__/BlogPostView.test.ts
```

Expected: FAIL — 文件不存在

- [ ] **Step 3: 创建 blog-posts.ts**

新建 `src/data/blog-posts.ts`。从 `~/code/neuromem-cloud/web/content/blog/` 的 9 篇 MDX 文件迁移内容，移除 MDX 组件标签（`<BlogStatRow .../>`, `<BlogPieChart .../>` 等自定义标签），保留纯 Markdown 正文。品牌名替换规则：`neuromem` → `DBay 记忆库`。

文件结构：

```ts
export interface BlogPost {
  slug: string
  title: string
  titleZh: string
  date: string
  category: string      // '产品' | '技术' | '公告'
  summary: string
  summaryZh: string
  content: string       // 英文 Markdown 正文
  contentZh: string     // 中文 Markdown 正文
}

export const blogPosts: BlogPost[] = [
  {
    slug: 'memory-architecture',
    title: 'How DBay Memory Store Models Memory: A Cognitive Psychology Approach',
    titleZh: 'DBay 记忆库如何建模记忆：认知心理学视角下的 AI 记忆架构',
    date: '2026-03-10',
    category: '技术',
    summary: "A deep dive into DBay Memory Store's 4-type memory classification, trait lifecycle, and self-maintaining memory quality — inspired by Tulving's framework.",
    summaryZh: '深入解析 DBay 记忆库的四类记忆分类、特质生命周期与自维护记忆质量机制——灵感源自 Tulving 框架，专为 AI Agent 打造。',
    content: `<!-- 从 memory-architecture.mdx 迁移，移除 BlogStatRow/BlogPieChart 等自定义组件标签，保留纯 Markdown -->`,
    contentZh: `<!-- 同上，中文部分 -->`,
  },
  // ... 其余 8 篇文章，slug 对应文件名（去掉 .mdx）
  // conversation-import, deployment-modes, one-llm-mode,
  // openclaw-plugin, platform-update-march-2026, sdk-v011-features,
  // trait-lifecycle, user-profile
]

export function getBlogPost(slug: string): BlogPost | undefined {
  return blogPosts.find(p => p.slug === slug)
}
```

**迁移注意：** MDX 中的自定义组件（`<BlogStatRow>`, `<BlogPieChart>`, `<BlogCodeBlock>`）直接删除该行，保留周围的 Markdown 文字。frontmatter 的 `title`/`titleZh`/`date`/`summary`/`summaryZh` 直接使用。

- [ ] **Step 4: 创建 BlogListView.vue**

新建 `src/views/blog/BlogListView.vue`:

```vue
<template>
  <main class="blog-list-page">
    <div class="blog-list-inner">
      <h1>{{ t('博客', 'Blog') }}</h1>
      <p class="blog-subtitle">{{ t('产品更新、技术解析、使用指南', 'Product updates, technical deep-dives, and guides') }}</p>
      <div class="post-list">
        <router-link
          v-for="post in sortedPosts"
          :key="post.slug"
          :to="`/blog/${post.slug}`"
          class="post-card"
        >
          <div class="post-meta">
            <span class="post-date">{{ post.date }}</span>
            <span class="post-category">{{ post.category }}</span>
          </div>
          <h2>{{ locale === 'zh' ? post.titleZh : post.title }}</h2>
          <p>{{ locale === 'zh' ? post.summaryZh : post.summary }}</p>
          <span class="post-read-more">{{ t('阅读全文', 'Read more') }} →</span>
        </router-link>
      </div>
    </div>
  </main>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useLocale } from '../../stores/locale'
import { blogPosts } from '../../data/blog-posts'

const { locale, t } = useLocale()
const sortedPosts = computed(() =>
  [...blogPosts].sort((a, b) => b.date.localeCompare(a.date))
)
</script>

<style scoped>
.blog-list-page { min-height: 100vh; background: #0a0a0a; color: #e5e5e5; }
.blog-list-inner { max-width: 720px; margin: 0 auto; padding: 48px 24px; }
h1 { font-size: 32px; font-weight: 700; margin-bottom: 8px; }
.blog-subtitle { color: #888; margin-bottom: 32px; }
.post-list { display: flex; flex-direction: column; gap: 16px; }
.post-card {
  background: #111; border: 1px solid #222; border-radius: 10px;
  padding: 20px; text-decoration: none; display: block;
  transition: border-color 0.15s;
}
.post-card:hover { border-color: #7c3aed; }
.post-meta { display: flex; gap: 8px; margin-bottom: 8px; }
.post-date { font-size: 12px; color: #555; }
.post-category {
  font-size: 11px; color: #a78bfa;
  background: #7c3aed22; padding: 1px 6px; border-radius: 4px;
}
.post-card h2 { font-size: 16px; font-weight: 600; color: #e5e5e5; margin: 0 0 8px; }
.post-card p { font-size: 13px; color: #888; margin: 0 0 12px; line-height: 1.6; }
.post-read-more { font-size: 12px; color: #7c3aed; }
</style>
```

- [ ] **Step 5: 创建 BlogPostView.vue**

新建 `src/views/blog/BlogPostView.vue`:

```vue
<template>
  <main class="blog-post-page">
    <div v-if="!post" class="post-not-found">
      <h1>404</h1>
      <p>{{ t('找不到这篇文章', 'Post not found') }}</p>
      <router-link to="/blog">← {{ t('返回博客', 'Back to Blog') }}</router-link>
    </div>
    <article v-else class="post-article">
      <header class="post-header">
        <div class="post-meta">
          <span class="post-date">{{ post.date }}</span>
          <span class="post-category">{{ post.category }}</span>
        </div>
        <h1>{{ locale === 'zh' ? post.titleZh : post.title }}</h1>
        <p class="post-summary">{{ locale === 'zh' ? post.summaryZh : post.summary }}</p>
      </header>
      <div
        class="post-content"
        v-html="renderedContent"
      />
      <footer class="post-footer">
        <router-link to="/blog">← {{ t('返回博客列表', 'Back to Blog') }}</router-link>
      </footer>
    </article>
  </main>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { useLocale } from '../../stores/locale'
import { getBlogPost } from '../../data/blog-posts'

const route = useRoute()
const { locale, t } = useLocale()

const post = computed(() => getBlogPost(route.params.slug as string))

const renderedContent = computed(() => {
  if (!post.value) return ''
  const md = locale.value === 'zh' ? post.value.contentZh : post.value.content
  const html = marked(md) as string
  return DOMPurify.sanitize(html)
})
</script>

<style scoped>
.blog-post-page { min-height: 100vh; background: #0a0a0a; color: #e5e5e5; }
.post-not-found { max-width: 400px; margin: 120px auto; text-align: center; }
.post-not-found h1 { font-size: 64px; color: #333; }
.post-not-found p { color: #888; margin: 8px 0 24px; }
.post-not-found a { color: #7c3aed; text-decoration: none; }
.post-article { max-width: 720px; margin: 0 auto; padding: 48px 24px 80px; }
.post-header { margin-bottom: 40px; padding-bottom: 24px; border-bottom: 1px solid #1a1a1a; }
.post-meta { display: flex; gap: 8px; margin-bottom: 12px; }
.post-date { font-size: 12px; color: #555; }
.post-category { font-size: 11px; color: #a78bfa; background: #7c3aed22; padding: 1px 6px; border-radius: 4px; }
.post-header h1 { font-size: 28px; font-weight: 700; margin: 0 0 12px; line-height: 1.3; }
.post-summary { color: #888; font-size: 15px; line-height: 1.6; margin: 0; }
.post-content :deep(h2) { font-size: 20px; font-weight: 600; margin: 32px 0 12px; }
.post-content :deep(h3) { font-size: 16px; font-weight: 600; margin: 24px 0 8px; }
.post-content :deep(p) { font-size: 14px; color: #ccc; line-height: 1.8; margin: 0 0 16px; }
.post-content :deep(ul), .post-content :deep(ol) { padding-left: 20px; margin: 0 0 16px; }
.post-content :deep(li) { font-size: 14px; color: #ccc; line-height: 1.8; }
.post-content :deep(code) { font-family: monospace; background: #1a1a1a; padding: 1px 5px; border-radius: 3px; font-size: 13px; color: #60a5fa; }
.post-content :deep(pre) { background: #111; border: 1px solid #222; border-radius: 8px; padding: 16px; overflow-x: auto; margin: 0 0 16px; }
.post-content :deep(pre code) { background: none; padding: 0; color: #ccc; }
.post-content :deep(blockquote) { border-left: 3px solid #7c3aed; padding-left: 16px; color: #888; margin: 0 0 16px; }
.post-footer { margin-top: 48px; padding-top: 24px; border-top: 1px solid #1a1a1a; }
.post-footer a { color: #7c3aed; text-decoration: none; font-size: 14px; }
</style>
```

- [ ] **Step 6: 运行测试**

```bash
cd lakeon-console
npx vitest run src/__tests__/BlogPostView.test.ts
```

Expected: 2 tests PASS

- [ ] **Step 7: 本地验证**

访问 `http://localhost:5173/blog`，确认文章列表显示。点击一篇文章，确认 Markdown 正常渲染。

- [ ] **Step 8: Commit**

```bash
cd lakeon-console
git add src/data/blog-posts.ts src/views/blog/ src/__tests__/BlogPostView.test.ts
git commit -m "feat: add blog data, BlogListView and BlogPostView with marked+DOMPurify"
```

---

## Task 6: /integrations 集成页

**Files:**
- Create: `src/views/integrations/IntegrationsView.vue`
- Create: `src/views/integrations/OpenClawView.vue`

- [ ] **Step 1: 创建 IntegrationsView.vue**

新建 `src/views/integrations/IntegrationsView.vue`:

```vue
<template>
  <main class="integrations-page">
    <div class="integrations-inner">
      <h1>{{ t('连接你的 AI 工具', 'Connect Your AI Tools') }}</h1>
      <p class="integ-subtitle">
        {{ t('通过 MCP 协议，让任何 AI 应用接入 DBay 的记忆、知识和数据能力', 'Connect any AI application to DBay memory, knowledge, and data capabilities via MCP protocol') }}
      </p>

      <!-- MCP 快速接入 -->
      <div class="mcp-quickstart">
        <h3>{{ t('5 分钟接入', '5-minute Setup') }}</h3>
        <pre class="code-block"><code>pip install dbay-mcp

# Claude Desktop / Claude Code
# 在 claude_desktop_config.json 中添加：
{
  "mcpServers": {
    "dbay": {
      "command": "uvx",
      "args": ["dbay-mcp"],
      "env": { "DBAY_API_KEY": "your-api-key" }
    }
  }
}</code></pre>
      </div>

      <!-- 集成卡片 -->
      <div class="integ-grid">
        <router-link
          v-for="tool in integrations"
          :key="tool.id"
          :to="tool.href"
          :id="tool.anchor"
          class="integ-card"
          :class="{ featured: tool.featured }"
        >
          <div class="integ-card-hd">
            <span class="integ-name">{{ tool.name }}</span>
            <span v-if="tool.featured" class="badge-featured">{{ t('精选', 'Featured') }}</span>
          </div>
          <p>{{ locale === 'zh' ? tool.descZh : tool.desc }}</p>
          <span class="integ-link">{{ t('查看文档', 'View docs') }} →</span>
        </router-link>

        <div class="integ-card coming">
          <span class="integ-name">+ {{ t('更多即将支持', 'More coming soon') }}</span>
          <p>{{ t('如有集成需求，欢迎提交 Issue', 'Submit an issue to request an integration') }}</p>
        </div>
      </div>
    </div>
  </main>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'

const { locale, t } = useLocale()

const integrations = [
  {
    id: 'openclaw', anchor: 'openclaw', name: 'OpenClaw', featured: true,
    href: '/integrations/openclaw',
    desc: 'OpenClaw AI assistant with native DBay memory integration. Auto-recall, auto-capture, and auto-digest on every conversation.',
    descZh: '龙虾 AI 助手，原生 DBay 记忆集成。每次对话自动回忆、自动捕获、自动反思。',
  },
  {
    id: 'claude-code', anchor: 'claude-code', name: 'Claude Code', featured: true,
    href: '/integrations#claude-code',
    desc: 'Connect DBay memory and knowledge base to Claude Code via MCP for persistent project context.',
    descZh: '通过 MCP 将 DBay 记忆库与知识库接入 Claude Code，实现持久化项目上下文。',
  },
  {
    id: 'claude-desktop', anchor: 'claude-desktop', name: 'Claude Desktop',
    href: '/integrations#claude-desktop',
    desc: 'Persistent memory for Claude Desktop conversations across sessions.',
    descZh: '让 Claude Desktop 的对话记忆跨会话持久化。',
  },
  {
    id: 'cursor', anchor: 'cursor', name: 'Cursor',
    href: '/integrations#cursor',
    desc: 'Codebase knowledge base retrieval and project memory in Cursor.',
    descZh: '在 Cursor 中使用代码库知识库检索和项目记忆。',
  },
  {
    id: 'gemini-cli', anchor: 'gemini-cli', name: 'Gemini CLI',
    href: '/integrations#gemini-cli',
    desc: 'Long-term memory for Gemini CLI — your AI assistant remembers between sessions.',
    descZh: '为 Gemini CLI 提供长期记忆能力，跨会话记住用户偏好和上下文。',
  },
  {
    id: 'chatgpt', anchor: 'chatgpt', name: 'ChatGPT',
    href: '/integrations#chatgpt',
    desc: 'Cross-session user memory sync for ChatGPT Plus and above.',
    descZh: '为 ChatGPT Plus 及以上用户提供跨会话记忆同步。',
  },
]
</script>

<style scoped>
.integrations-page { min-height: 100vh; background: #0a0a0a; color: #e5e5e5; }
.integrations-inner { max-width: 900px; margin: 0 auto; padding: 48px 24px; }
h1 { font-size: 32px; font-weight: 700; margin-bottom: 8px; }
.integ-subtitle { color: #888; font-size: 15px; margin-bottom: 32px; max-width: 600px; }
.mcp-quickstart {
  background: #0a0a14; border: 1px solid #2a2a3a;
  border-radius: 10px; padding: 20px; margin-bottom: 40px;
}
.mcp-quickstart h3 { font-size: 15px; font-weight: 600; margin: 0 0 12px; }
.code-block {
  background: #0d0d0d; border: 1px solid #222; border-radius: 6px;
  padding: 14px; font-size: 12px; color: #a78bfa;
  overflow-x: auto; margin: 0; font-family: monospace;
}
.integ-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.integ-card {
  background: #111; border: 1px solid #222; border-radius: 10px;
  padding: 18px; text-decoration: none; display: flex;
  flex-direction: column; gap: 8px; transition: border-color 0.15s;
}
.integ-card:hover { border-color: #7c3aed; }
.integ-card.featured { border-color: #7c3aed44; background: #1a1a2e; }
.integ-card.coming { border-style: dashed; border-color: #2a2a2a; cursor: default; }
.integ-card.coming:hover { border-color: #2a2a2a; }
.integ-card-hd { display: flex; align-items: center; gap: 8px; }
.integ-name { font-size: 15px; font-weight: 600; color: #e5e5e5; }
.badge-featured {
  font-size: 10px; background: #7c3aed22; color: #a78bfa;
  padding: 1px 6px; border-radius: 4px;
}
.integ-card p { font-size: 12px; color: #888; margin: 0; line-height: 1.5; flex: 1; }
.integ-link { font-size: 12px; color: #7c3aed; margin-top: auto; }
@media (max-width: 768px) {
  .integ-grid { grid-template-columns: 1fr; }
}
</style>
```

- [ ] **Step 2: 创建 OpenClawView.vue**

新建 `src/views/integrations/OpenClawView.vue`，内容从 `~/code/neuromem-cloud/web/src/app/integrations/openclaw/page.tsx` 迁移：

```vue
<template>
  <main class="openclaw-page">
    <div class="openclaw-inner">
      <router-link to="/integrations" class="back-link">← {{ t('返回集成', 'Back to Integrations') }}</router-link>
      <div class="openclaw-hd">
        <h1>OpenClaw × DBay 记忆库</h1>
        <p>{{ t('龙虾 AI 助手与 DBay 记忆库的深度集成，实现自动回忆、自动捕获和自动反思', 'Deep integration of OpenClaw AI assistant with DBay Memory Store for auto-recall, auto-capture, and auto-digest') }}</p>
      </div>

      <section class="oc-section">
        <h2>{{ t('快速接入', 'Quick Setup') }}</h2>
        <ol class="oc-steps">
          <li>{{ t('安装 dbay-mcp', 'Install dbay-mcp') }}: <code>pip install dbay-mcp</code></li>
          <li>{{ t('在 OpenClaw 插件设置中配置 MCP 服务器地址', 'Configure MCP server address in OpenClaw plugin settings') }}</li>
          <li>{{ t('输入 DBay API Key，启用记忆自动捕获', 'Enter DBay API Key and enable auto-capture') }}</li>
        </ol>
      </section>

      <section class="oc-section">
        <h2>{{ t('核心功能', 'Core Features') }}</h2>
        <div class="oc-features">
          <div class="oc-feature">
            <h3>{{ t('自动回忆', 'Auto-Recall') }}</h3>
            <p>{{ t('每次对话开始前，自动从记忆库检索与当前话题相关的记忆，注入系统提示词', 'Before each conversation, automatically retrieves relevant memories and injects them into the system prompt') }}</p>
          </div>
          <div class="oc-feature">
            <h3>{{ t('自动捕获', 'Auto-Capture') }}</h3>
            <p>{{ t('对话结束后自动调用 ingest API，提取并存储重要事实、事件和偏好', 'After each conversation, automatically calls the ingest API to extract and store important facts, events, and preferences') }}</p>
          </div>
          <div class="oc-feature">
            <h3>{{ t('自动反思', 'Auto-Digest') }}</h3>
            <p>{{ t('定期调用 digest API，将积累的记忆合成为用户画像和行为模式', 'Periodically calls the digest API to synthesize accumulated memories into user profiles and behavioral patterns') }}</p>
          </div>
        </div>
      </section>

      <section class="oc-section">
        <h2>{{ t('配置示例', 'Configuration Example') }}</h2>
        <pre class="code-block"><code># OpenClaw MCP 配置 (openclawrc.json)
{
  "mcp_servers": {
    "dbay": {
      "url": "http://localhost:7860",
      "api_key": "your-dbay-api-key"
    }
  },
  "memory": {
    "auto_recall": true,
    "auto_capture": true,
    "auto_digest": true,
    "recall_top_k": 5
  }
}</code></pre>
      </section>
    </div>
  </main>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'
const { t } = useLocale()
</script>

<style scoped>
.openclaw-page { min-height: 100vh; background: #0a0a0a; color: #e5e5e5; }
.openclaw-inner { max-width: 800px; margin: 0 auto; padding: 48px 24px; }
.back-link { color: #7c3aed; text-decoration: none; font-size: 13px; display: block; margin-bottom: 24px; }
.openclaw-hd { margin-bottom: 40px; }
.openclaw-hd h1 { font-size: 28px; font-weight: 700; margin: 0 0 10px; }
.openclaw-hd p { color: #888; font-size: 15px; }
.oc-section { margin-bottom: 40px; }
.oc-section h2 { font-size: 20px; font-weight: 600; margin: 0 0 16px; }
.oc-steps { padding-left: 20px; display: flex; flex-direction: column; gap: 10px; }
.oc-steps li { color: #ccc; font-size: 14px; }
.oc-steps code { background: #1a1a1a; padding: 1px 5px; border-radius: 3px; color: #60a5fa; font-family: monospace; }
.oc-features { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.oc-feature { background: #111; border: 1px solid #222; border-radius: 8px; padding: 16px; }
.oc-feature h3 { font-size: 14px; font-weight: 600; margin: 0 0 8px; }
.oc-feature p { font-size: 13px; color: #888; margin: 0; }
.code-block {
  background: #0d0d0d; border: 1px solid #222; border-radius: 8px;
  padding: 16px; font-family: monospace; font-size: 12px; color: #a78bfa; overflow-x: auto;
}
@media (max-width: 768px) { .oc-features { grid-template-columns: 1fr; } }
</style>
```

- [ ] **Step 3: Commit**

```bash
cd lakeon-console
git add src/views/integrations/
git commit -m "feat: add /integrations page and OpenClaw detail page"
```

---

## Task 7: /docs 文档页

**Files:**
- Create: `src/views/docs/DocsLayout.vue`
- Create: `src/views/docs/DocsHome.vue`
- Create: `src/views/docs/DocsRestApi.vue`
- Create: `src/views/docs/DocsPythonSdk.vue`
- Create: `src/views/docs/DocsDeploy.vue`
- Create: `src/views/docs/DocsMcp.vue`

- [ ] **Step 1: 创建 DocsLayout.vue**

新建 `src/views/docs/DocsLayout.vue`（左侧固定导航 + router-view）：

```vue
<template>
  <div class="docs-layout">
    <aside class="docs-sidebar">
      <nav class="docs-nav">
        <div class="docs-nav-section">
          <p class="docs-nav-label">{{ t('文档', 'Documentation') }}</p>
          <router-link to="/docs" exact-active-class="active" class="docs-nav-link">
            {{ t('快速开始', 'Quick Start') }}
          </router-link>
          <router-link to="/docs/rest-api" active-class="active" class="docs-nav-link">
            REST API
          </router-link>
          <router-link to="/docs/python-sdk" active-class="active" class="docs-nav-link">
            Python SDK
          </router-link>
          <router-link to="/docs/deploy" active-class="active" class="docs-nav-link">
            {{ t('部署指南', 'Deploy Guide') }}
          </router-link>
          <router-link to="/docs/mcp" active-class="active" class="docs-nav-link">
            MCP {{ t('接入', 'Integration') }}
          </router-link>
        </div>
      </nav>
    </aside>
    <main class="docs-content">
      <router-view />
    </main>
  </div>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'
const { t } = useLocale()
</script>

<style scoped>
.docs-layout { display: flex; min-height: calc(100vh - 52px); background: #0a0a0a; }
.docs-sidebar {
  width: 220px; flex-shrink: 0; border-right: 1px solid #1a1a1a;
  padding: 24px 0; position: sticky; top: 52px; height: calc(100vh - 52px);
  overflow-y: auto;
}
.docs-nav { padding: 0 12px; }
.docs-nav-label { font-size: 10px; color: #555; text-transform: uppercase; letter-spacing: 1px; margin: 0 0 8px 8px; }
.docs-nav-link {
  display: block; padding: 7px 10px; border-radius: 6px;
  font-size: 13px; color: #888; text-decoration: none; transition: all 0.15s;
}
.docs-nav-link:hover { color: #ccc; background: #1a1a1a; }
.docs-nav-link.active { color: #a78bfa; background: #1a1a2e; }
.docs-content { flex: 1; padding: 40px 48px; max-width: 800px; color: #e5e5e5; }
@media (max-width: 768px) {
  .docs-layout { flex-direction: column; }
  .docs-sidebar { width: 100%; height: auto; position: static; padding: 12px; border-right: none; border-bottom: 1px solid #1a1a1a; }
  .docs-nav { display: flex; flex-wrap: wrap; gap: 4px; }
  .docs-content { padding: 24px 16px; }
}
</style>
```

- [ ] **Step 2: 创建 DocsHome.vue（快速开始）**

新建 `src/views/docs/DocsHome.vue`:

```vue
<template>
  <div class="docs-page">
    <h1>{{ t('快速开始', 'Quick Start') }}</h1>
    <p class="docs-lead">{{ t('5 分钟接入 DBay 记忆库与知识库', 'Integrate DBay Memory Store and Knowledge Base in 5 minutes') }}</p>

    <h2>{{ t('1. 安装 SDK', '1. Install SDK') }}</h2>
    <pre class="code-block"><code>pip install dbay</code></pre>

    <h2>{{ t('2. 初始化客户端', '2. Initialize Client') }}</h2>
    <pre class="code-block"><code>from dbay import DBayClient

client = DBayClient(api_key="your-api-key")
# 或从环境变量读取
# client = DBayClient()  # 读取 DBAY_API_KEY</code></pre>

    <h2>{{ t('3. 存入记忆', '3. Ingest Memory') }}</h2>
    <pre class="code-block"><code>client.memory.ingest(
    user_id="user_123",
    messages=[
        {"role": "user", "content": "我是前端工程师，偏好 Vue 3"},
        {"role": "assistant", "content": "好的，我记住了"},
    ]
)</code></pre>

    <h2>{{ t('4. 召回记忆', '4. Recall Memory') }}</h2>
    <pre class="code-block"><code>results = client.memory.recall(
    user_id="user_123",
    query="用户的技术偏好",
    top_k=5
)
for r in results:
    print(r.content, r.score)</code></pre>

    <h2>{{ t('5. 通过 MCP 接入', '5. Connect via MCP') }}</h2>
    <pre class="code-block"><code>pip install dbay-mcp

# Claude Desktop 配置（claude_desktop_config.json）
{
  "mcpServers": {
    "dbay": {
      "command": "uvx",
      "args": ["dbay-mcp"],
      "env": { "DBAY_API_KEY": "your-api-key" }
    }
  }
}</code></pre>

    <div class="docs-next">
      <h3>{{ t('下一步', 'Next Steps') }}</h3>
      <div class="docs-next-links">
        <router-link to="/docs/rest-api">REST API →</router-link>
        <router-link to="/docs/python-sdk">Python SDK →</router-link>
        <router-link to="/docs/mcp">MCP {{ t('接入', 'Integration') }} →</router-link>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'
const { t } = useLocale()
</script>

<style scoped>
.docs-page h1 { font-size: 28px; font-weight: 700; margin: 0 0 8px; }
.docs-lead { color: #888; font-size: 15px; margin: 0 0 32px; }
.docs-page h2 { font-size: 18px; font-weight: 600; margin: 28px 0 10px; }
.code-block {
  background: #111; border: 1px solid #222; border-radius: 8px;
  padding: 14px; font-family: monospace; font-size: 13px; color: #a78bfa;
  overflow-x: auto; margin: 0 0 8px;
}
.docs-next { margin-top: 48px; padding-top: 24px; border-top: 1px solid #1a1a1a; }
.docs-next h3 { font-size: 15px; font-weight: 600; margin: 0 0 12px; }
.docs-next-links { display: flex; gap: 16px; }
.docs-next-links a { color: #7c3aed; text-decoration: none; font-size: 14px; }
</style>
```

- [ ] **Step 3: 创建其余文档子页**

内容从 `~/code/neuromem-cloud/web/src/app/docs/` 迁移，品牌名替换。

**DocsRestApi.vue** — 从 `docs/rest-api/page.tsx` 迁移（~779 行，含完整 API 端点描述）：
- 将 Next.js JSX 转为 Vue template
- 将所有 `t("key", locale)` 替换为 `t('中文', 'English')` 内联模式
- API 端点 URL 替换为 `https://api.dbay.cloud:8443/api/v1`
- 包名 `neuromem` → `dbay`

**DocsPythonSdk.vue** — 从 `docs/python-sdk/page.tsx` 迁移（~542 行）：
- 同样格式转换
- 包名替换

**DocsDeploy.vue** — 从 `docs/deploy/page.tsx` 迁移：
- 部署模式（云托管 / 自托管 / 混合）适配为 DBay 的情况

**DocsMcp.vue** — 新写，内容如下：

```vue
<template>
  <div class="docs-page">
    <h1>MCP {{ t('接入指南', 'Integration Guide') }}</h1>
    <p class="docs-lead">{{ t('通过 MCP 协议将 DBay 记忆库接入任何 AI 应用', 'Connect DBay Memory Store to any AI application via MCP protocol') }}</p>

    <h2>{{ t('安装', 'Installation') }}</h2>
    <pre class="code-block"><code>pip install dbay-mcp</code></pre>

    <h2>{{ t('配置', 'Configuration') }}</h2>
    <p>{{ t('在 claude_desktop_config.json 中添加：', 'Add to claude_desktop_config.json:') }}</p>
    <pre class="code-block"><code>{
  "mcpServers": {
    "dbay": {
      "command": "uvx",
      "args": ["dbay-mcp"],
      "env": { "DBAY_API_KEY": "your-api-key" }
    }
  }
}</code></pre>

    <h2>{{ t('支持的 MCP 工具', 'Supported MCP Tools') }}</h2>
    <div class="tool-list">
      <div class="tool-item" v-for="tool in mcpTools" :key="tool.name">
        <code class="tool-name">{{ tool.name }}</code>
        <p>{{ locale === 'zh' ? tool.descZh : tool.desc }}</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'
const { locale, t } = useLocale()
const mcpTools = [
  { name: 'memory_ingest', desc: 'Store messages and facts into long-term memory', descZh: '将消息和事实存入长期记忆' },
  { name: 'memory_recall', desc: 'Search memories by natural language query', descZh: '通过自然语言查询搜索记忆' },
  { name: 'memory_digest', desc: 'Generate user profile and behavioral insights', descZh: '生成用户画像和行为洞察' },
  { name: 'kb_search', desc: 'Search the knowledge base for relevant documents', descZh: '在知识库中搜索相关文档' },
]
</script>

<style scoped>
.docs-page h1 { font-size: 28px; font-weight: 700; margin: 0 0 8px; }
.docs-lead { color: #888; font-size: 15px; margin: 0 0 32px; }
.docs-page h2 { font-size: 18px; font-weight: 600; margin: 28px 0 10px; }
.code-block { background: #111; border: 1px solid #222; border-radius: 8px; padding: 14px; font-family: monospace; font-size: 13px; color: #a78bfa; overflow-x: auto; margin: 0 0 8px; }
.tool-list { display: flex; flex-direction: column; gap: 10px; }
.tool-item { background: #111; border: 1px solid #222; border-radius: 8px; padding: 14px; }
.tool-name { font-family: monospace; font-size: 14px; color: #60a5fa; display: block; margin-bottom: 6px; }
.tool-item p { font-size: 13px; color: #888; margin: 0; }
</style>
```

- [ ] **Step 4: 本地验证**

访问 `http://localhost:5173/docs`，确认：
- 左侧导航显示，点击各链接路由正确
- DocsHome 快速开始内容完整
- REST API 页和 Python SDK 页内容正确显示

- [ ] **Step 5: Commit**

```bash
cd lakeon-console
git add src/views/docs/
git commit -m "feat: add /docs pages with DocsLayout, quick start, REST API, SDK, deploy, MCP"
```

---

## Task 8: 验收测试 + 最终检查

- [ ] **Step 1: 运行全部测试**

```bash
cd lakeon-console
npx vitest run
```

Expected: 所有测试 PASS，无新增失败

- [ ] **Step 2: 全路由验证**

```bash
npm run dev
```

逐一访问并确认：
- `http://localhost:5173/` — Landing 页，记忆库第四模块，新导航
- `http://localhost:5173/product` — 产品页，记忆库专题完整
- `http://localhost:5173/product#memory` — 锚点跳转正常
- `http://localhost:5173/integrations` — 7 个集成卡片
- `http://localhost:5173/integrations/openclaw` — OpenClaw 详情页
- `http://localhost:5173/blog` — 9 篇文章列表
- `http://localhost:5173/blog/memory-architecture` — 文章内容渲染
- `http://localhost:5173/docs` — 文档快速开始
- `http://localhost:5173/docs/rest-api` — REST API 文档
- `http://localhost:5173/docs/mcp` — MCP 接入指南
- 导航下拉菜单 hover 正常
- 移动端（< 768px）hamburger 菜单正常
- 语言切换（中/EN）在各页面生效

- [ ] **Step 3: 验证旧路径兼容**

访问 `http://localhost:5173/landing`，确认重定向到 `/`（书签不断）

- [ ] **Step 4: 最终 Commit**

```bash
cd lakeon-console
npx vitest run   # 确认全部通过
git add src/ docs/
git commit -m "feat: DBay landing memory module integration complete — nav, product, integrations, blog, docs"
```
