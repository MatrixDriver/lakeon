# Landing Page 重构实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 重构 DBay Landing Page 为精简现代的首页，三层递进价值主张 + 导航菜单承载深度内容。

**Architecture:** 重写 `LandingView.vue` 为精简首页（Hero + 数字条 + 三层价值 + CTA），改造 `PublicLayout.vue` 导航栏（产品/集成下拉菜单重新组织），子页面路由占位。现有 trial 功能、i18n、theme 系统保持不变。

**Tech Stack:** Vue 3 Composition API, TypeScript, 自定义 CSS（CSS 变量主题），SVG 内联图表，vue-router

**Spec:** `docs/superpowers/specs/2026-03-27-landing-page-redesign.md`

---

## File Structure

| 文件 | 操作 | 职责 |
|------|------|------|
| `lakeon-console/src/views/landing/LandingView.vue` | 重写 | 首页：Hero + 数字条 + 三层价值 + 底部 CTA |
| `lakeon-console/src/views/landing/LayerLakebase.vue` | 新建 | 第一层 SVG 架构图组件 |
| `lakeon-console/src/views/landing/LayerServices.vue` | 新建 | 第二层 SVG 架构图组件 |
| `lakeon-console/src/views/landing/LayerDatalake.vue` | 新建 | 第三层 SVG 架构图组件 |
| `lakeon-console/src/layouts/PublicLayout.vue` | 修改 | 导航栏：按 spec 重新组织下拉菜单内容、CTA 改为"立即试用" |
| `lakeon-console/src/style.css` | 修改 | 新增蓝色主题 CSS 变量（--pub-primary 等） |

---

### Task 1: 新增 CSS 变量和基础样式

**Files:**
- Modify: `lakeon-console/src/style.css`

- [ ] **Step 1: 在 :root 中新增主色变量**

在现有 `--pub-hero-end` 之后追加：

```css
  --pub-primary: #0073e6;
  --pub-primary-dark: #005bb5;
  --pub-primary-light: #e3f2fd;
  --pub-bg-alt: #f8f9fb;
```

- [ ] **Step 2: 在 [data-theme="dark"] 中新增对应暗色变量**

在现有 `--pub-hero-end` 之后追加：

```css
  --pub-primary: #4da6ff;
  --pub-primary-dark: #0073e6;
  --pub-primary-light: #1a2a3a;
  --pub-bg-alt: #0f0f0f;
```

- [ ] **Step 3: 本地验证**

Run: `cd lakeon-console && npx vite build --mode development 2>&1 | tail -5`
Expected: 构建成功，无 CSS 解析错误

- [ ] **Step 4: Commit**

```bash
git add lakeon-console/src/style.css
git commit -m "feat(landing): add primary color CSS variables for redesign"
```

---

### Task 2: 改造导航栏

**Files:**
- Modify: `lakeon-console/src/layouts/PublicLayout.vue`

- [ ] **Step 1: 修改产品下拉菜单内容**

将现有的产品下拉菜单（约 Lines 13-33）替换为 spec 中的四宫格结构。找到 `<NavDropdown :label="t('产品', 'Products')">` 并替换其内容：

```html
<NavDropdown :label="t('产品', 'Products')">
  <div class="nav-product-grid">
    <router-link to="/product#lakebase" class="nav-item">
      <span class="nav-item-icon">🐘</span>
      <div>
        <div class="nav-item-title">Lakebase</div>
        <div class="nav-item-desc">Serverless PostgreSQL</div>
      </div>
    </router-link>
    <router-link to="/product#knowledge" class="nav-item">
      <span class="nav-item-icon">📚</span>
      <div>
        <div class="nav-item-title">{{ t('知识库', 'Knowledge Base') }}</div>
        <div class="nav-item-desc">{{ t('文档 + 向量搜索', 'Docs + Vector Search') }}</div>
      </div>
    </router-link>
    <router-link to="/product#memory" class="nav-item">
      <span class="nav-item-icon">🧠</span>
      <div>
        <div class="nav-item-title">{{ t('记忆库', 'Memory Store') }}</div>
        <div class="nav-item-desc">{{ t('Agent 长期记忆', 'Agent Long-term Memory') }}</div>
      </div>
    </router-link>
    <router-link to="/product#datalake" class="nav-item">
      <span class="nav-item-icon">🌊</span>
      <div>
        <div class="nav-item-title">{{ t('数据湖', 'Data Lake') }}</div>
        <div class="nav-item-desc">{{ t('数据处理 + 训练', 'Processing + Training') }}</div>
      </div>
    </router-link>
  </div>
</NavDropdown>
```

- [ ] **Step 2: 修改集成下拉菜单内容**

找到 `<NavDropdown :label="t('集成', 'Integrations')">` 替换其内容：

```html
<NavDropdown :label="t('集成', 'Integrations')">
  <router-link to="/integrations#mcp" class="nav-item">
    <span class="nav-item-icon">🤖</span>
    <div>
      <div class="nav-item-title">{{ t('MCP 集成', 'MCP Integration') }}</div>
    </div>
  </router-link>
  <router-link to="/integrations#skill" class="nav-item">
    <span class="nav-item-icon">🔧</span>
    <div>
      <div class="nav-item-title">{{ t('Skill 集成', 'Skill Integration') }}</div>
    </div>
  </router-link>
  <router-link to="/integrations#pg" class="nav-item">
    <span class="nav-item-icon">🐘</span>
    <div>
      <div class="nav-item-title">{{ t('PostgreSQL 协议', 'PostgreSQL Protocol') }}</div>
    </div>
  </router-link>
  <router-link to="/integrations#rest" class="nav-item">
    <span class="nav-item-icon">🔌</span>
    <div>
      <div class="nav-item-title">REST API</div>
    </div>
  </router-link>
  <div class="nav-divider"></div>
  <router-link to="/docs/rest-api" class="nav-item">
    <span class="nav-item-icon">📖</span>
    <div>
      <div class="nav-item-title" style="color: var(--pub-primary)">{{ t('API 文档', 'API Docs') }}</div>
    </div>
  </router-link>
</NavDropdown>
```

- [ ] **Step 3: 修改右侧 CTA 按钮**

找到 Sign In 按钮区域（`.pub-nav-right`），将 Sign In 按钮后面的内容改为"立即试用"：

```html
<div class="pub-nav-right">
  <button class="theme-btn" @click="toggleTheme" :title="theme === 'dark' ? 'Light mode' : 'Dark mode'">
    {{ theme === 'dark' ? '☀️' : '🌙' }}
  </button>
  <button class="lang-btn" @click="toggleLocale">{{ locale === 'zh' ? 'EN' : '中' }}</button>
  <router-link to="/login" class="btn-signin">{{ t('登录', 'Sign In') }}</router-link>
  <router-link to="/" class="btn-trial" @click.prevent="handleNavTrial">{{ t('立即试用', 'Try Now') }}</router-link>
</div>
```

- [ ] **Step 4: 添加 nav-product-grid 和 btn-trial CSS**

在 `PublicLayout.vue` 的 `<style scoped>` 中追加：

```css
.nav-product-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
}
.nav-item-icon {
  font-size: 18px;
  flex-shrink: 0;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 8px;
}
.nav-divider {
  height: 1px;
  background: var(--pub-border);
  margin: 4px 0;
}
.btn-trial {
  background: var(--pub-primary, #0073e6);
  color: #fff !important;
  padding: 8px 20px;
  border-radius: 8px;
  font-size: 15px;
  font-weight: 600;
  text-decoration: none;
  transition: opacity 0.15s;
}
.btn-trial:hover {
  opacity: 0.9;
}
```

- [ ] **Step 5: 添加 handleNavTrial 函数**

在 `<script setup>` 中导入 auth 和 client，添加 trial 处理：

```typescript
import { useAuthStore } from '../stores/auth'
import client from '../api/client'

const authStore = useAuthStore()

async function handleNavTrial() {
  try {
    localStorage.removeItem('lakeon_api_key')
    const { data } = await client.post('/trial')
    authStore.setTenant(data.tenant_id, data.username || 'trial')
    authStore.setTrialState(true, data.expires_at)
    router.push('/dashboard')
  } catch (e) {
    router.push('/login')
  }
}
```

（注意：如果 `router` 还没导入，需要添加 `const router = useRouter()` 和 `import { useRouter } from 'vue-router'`）

- [ ] **Step 6: 本地验证**

Run: `cd lakeon-console && npm run dev`

打开浏览器验证：
- 产品下拉菜单显示四宫格
- 集成下拉菜单显示列表 + API 文档链接
- 右上角"立即试用"按钮蓝色实心

- [ ] **Step 7: Commit**

```bash
git add lakeon-console/src/layouts/PublicLayout.vue
git commit -m "feat(landing): reorganize nav dropdowns with product grid and integration list"
```

---

### Task 3: 第一层 SVG 架构图组件

**Files:**
- Create: `lakeon-console/src/views/landing/LayerLakebase.vue`

- [ ] **Step 1: 创建组件**

```vue
<template>
  <svg viewBox="0 0 440 300" class="layer-svg">
    <defs>
      <linearGradient id="gl1-bg" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="var(--pub-primary-light, #e3f2fd)" />
        <stop offset="100%" stop-color="#bbdefb" />
      </linearGradient>
      <linearGradient id="gl1-main" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="var(--pub-primary, #0073e6)" />
        <stop offset="100%" stop-color="var(--pub-primary-dark, #005bb5)" />
      </linearGradient>
      <linearGradient id="gl1-green" x1="0%" y1="0%" x2="0%" y2="100%">
        <stop offset="0%" stop-color="#e8f5e9" />
        <stop offset="100%" stop-color="#c8e6c9" />
      </linearGradient>
      <filter id="sh1">
        <feDropShadow dx="0" dy="4" stdDeviation="8" flood-color="rgba(0,115,230,0.15)" />
      </filter>
      <filter id="sh2">
        <feDropShadow dx="0" dy="2" stdDeviation="4" flood-color="rgba(0,0,0,0.08)" />
      </filter>
    </defs>

    <!-- Background glow -->
    <ellipse cx="220" cy="150" rx="200" ry="130" fill="url(#gl1-bg)" opacity="0.4" />

    <!-- Top: clients -->
    <rect x="40" y="20" width="100" height="40" rx="10" fill="url(#gl1-green)" filter="url(#sh2)" />
    <text x="90" y="44" text-anchor="middle" font-size="11" font-weight="600" fill="#2e7d32">🧠 mem0</text>
    <rect x="170" y="20" width="100" height="40" rx="10" fill="url(#gl1-green)" filter="url(#sh2)" />
    <text x="220" y="44" text-anchor="middle" font-size="11" font-weight="600" fill="#2e7d32">👁 Hindsight</text>
    <rect x="300" y="20" width="100" height="40" rx="10" fill="url(#gl1-green)" filter="url(#sh2)" />
    <text x="350" y="44" text-anchor="middle" font-size="11" font-weight="600" fill="#2e7d32">🤖 Your Agent</text>

    <!-- Lines -->
    <line x1="90" y1="60" x2="220" y2="110" stroke="var(--pub-primary, #0073e6)" stroke-width="2" opacity="0.5" />
    <line x1="220" y1="60" x2="220" y2="110" stroke="var(--pub-primary, #0073e6)" stroke-width="2" opacity="0.5" />
    <line x1="350" y1="60" x2="220" y2="110" stroke="var(--pub-primary, #0073e6)" stroke-width="2" opacity="0.5" />

    <!-- Protocol badge -->
    <rect x="180" y="78" width="80" height="22" rx="11" fill="var(--pub-surface, #fff)" stroke="var(--pub-primary, #0073e6)" stroke-width="1.5" filter="url(#sh2)" />
    <text x="220" y="93" text-anchor="middle" font-size="9" font-weight="700" fill="var(--pub-primary, #0073e6)">PG {{ t('协议', 'Protocol') }}</text>

    <!-- Lakebase -->
    <rect x="80" y="110" width="280" height="60" rx="16" fill="url(#gl1-main)" filter="url(#sh1)" />
    <text x="220" y="136" text-anchor="middle" font-size="18" font-weight="800" fill="#fff">🐘 Lakebase</text>
    <text x="220" y="156" text-anchor="middle" font-size="10" fill="rgba(255,255,255,0.8)">Serverless PostgreSQL</text>

    <!-- Feature badges row 1 -->
    <rect x="60" y="190" width="80" height="30" rx="8" fill="var(--pub-surface, #fff)" filter="url(#sh2)" />
    <text x="100" y="205" text-anchor="middle" font-size="8" fill="var(--pub-primary, #0073e6)" font-weight="600">⚡ {{ t('按需弹性', 'Auto Scale') }}</text>
    <text x="100" y="215" text-anchor="middle" font-size="7" fill="var(--pub-text-3, #888)">{{ t('自动伸缩', 'Elastic') }}</text>

    <rect x="155" y="190" width="80" height="30" rx="8" fill="var(--pub-surface, #fff)" filter="url(#sh2)" />
    <text x="195" y="205" text-anchor="middle" font-size="8" fill="var(--pub-primary, #0073e6)" font-weight="600">⏱ {{ t('时间旅行', 'Time Travel') }}</text>
    <text x="195" y="215" text-anchor="middle" font-size="7" fill="var(--pub-text-3, #888)">{{ t('版本管理', 'Versioning') }}</text>

    <rect x="250" y="190" width="80" height="30" rx="8" fill="var(--pub-surface, #fff)" filter="url(#sh2)" />
    <text x="290" y="205" text-anchor="middle" font-size="8" fill="var(--pub-primary, #0073e6)" font-weight="600">🔍 pgvector</text>
    <text x="290" y="215" text-anchor="middle" font-size="7" fill="var(--pub-text-3, #888)">{{ t('向量搜索', 'Vector') }}</text>

    <!-- Feature badges row 2 -->
    <rect x="60" y="235" width="80" height="30" rx="8" fill="var(--pub-surface, #fff)" filter="url(#sh2)" />
    <text x="100" y="250" text-anchor="middle" font-size="8" fill="var(--pub-primary, #0073e6)" font-weight="600">🔀 {{ t('分支', 'Branch') }}</text>
    <text x="100" y="260" text-anchor="middle" font-size="7" fill="var(--pub-text-3, #888)">{{ t('类 Git 管理', 'Git-like') }}</text>

    <rect x="155" y="235" width="80" height="30" rx="8" fill="var(--pub-surface, #fff)" filter="url(#sh2)" />
    <text x="195" y="250" text-anchor="middle" font-size="8" fill="var(--pub-primary, #0073e6)" font-weight="600">💤 {{ t('自动休眠', 'Auto Sleep') }}</text>
    <text x="195" y="260" text-anchor="middle" font-size="7" fill="var(--pub-text-3, #888)">{{ t('秒级唤醒', 'Fast Wake') }}</text>

    <rect x="250" y="235" width="80" height="30" rx="8" fill="var(--pub-surface, #fff)" filter="url(#sh2)" />
    <text x="290" y="250" text-anchor="middle" font-size="8" fill="var(--pub-primary, #0073e6)" font-weight="600">🔒 {{ t('零运维', 'Zero Ops') }}</text>
    <text x="290" y="260" text-anchor="middle" font-size="7" fill="var(--pub-text-3, #888)">{{ t('全托管', 'Managed') }}</text>
  </svg>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'
const { t } = useLocale()
</script>

<style scoped>
.layer-svg {
  width: 100%;
  height: auto;
}
</style>
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/views/landing/LayerLakebase.vue
git commit -m "feat(landing): add Layer 1 Lakebase SVG architecture diagram"
```

---

### Task 4: 第二层 SVG 架构图组件

**Files:**
- Create: `lakeon-console/src/views/landing/LayerServices.vue`

- [ ] **Step 1: 创建组件**

```vue
<template>
  <svg viewBox="0 0 440 280" class="layer-svg">
    <defs>
      <linearGradient id="gl2-bg" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="var(--pub-primary-light, #e3f2fd)" />
        <stop offset="100%" stop-color="#90caf9" />
      </linearGradient>
      <linearGradient id="gl2-mem" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#ff9800" />
        <stop offset="100%" stop-color="#e65100" />
      </linearGradient>
      <linearGradient id="gl2-know" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#4caf50" />
        <stop offset="100%" stop-color="#2e7d32" />
      </linearGradient>
      <linearGradient id="gl2-base" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="var(--pub-primary, #0073e6)" />
        <stop offset="100%" stop-color="var(--pub-primary-dark, #005bb5)" />
      </linearGradient>
      <filter id="gs1"><feDropShadow dx="0" dy="4" stdDeviation="8" flood-color="rgba(0,115,230,0.15)" /></filter>
      <filter id="gs2"><feDropShadow dx="0" dy="2" stdDeviation="4" flood-color="rgba(0,0,0,0.08)" /></filter>
    </defs>

    <ellipse cx="220" cy="140" rx="200" ry="120" fill="url(#gl2-bg)" opacity="0.25" />

    <!-- Agent -->
    <rect x="145" y="10" width="150" height="42" rx="12" fill="var(--pub-surface, #fff)" stroke="#1565c0" stroke-width="1.5" filter="url(#gs2)" />
    <text x="220" y="36" text-anchor="middle" font-size="13" font-weight="700" fill="#1565c0">🤖 AI Agent</text>

    <!-- Lines to services -->
    <line x1="185" y1="52" x2="140" y2="95" stroke="#ff9800" stroke-width="2" opacity="0.6" />
    <line x1="255" y1="52" x2="300" y2="95" stroke="#4caf50" stroke-width="2" opacity="0.6" />

    <!-- Protocol badge -->
    <rect x="170" y="62" width="100" height="22" rx="11" fill="var(--pub-surface, #fff)" stroke="var(--pub-primary, #0073e6)" stroke-width="1.5" filter="url(#gs2)" />
    <text x="220" y="77" text-anchor="middle" font-size="9" font-weight="700" fill="var(--pub-primary, #0073e6)">MCP / Skill</text>

    <!-- Memory service -->
    <rect x="50" y="95" width="170" height="50" rx="14" fill="url(#gl2-mem)" filter="url(#gs1)" />
    <text x="135" y="118" text-anchor="middle" font-size="13" font-weight="700" fill="#fff">🧠 {{ t('记忆服务', 'Memory') }}</text>
    <text x="135" y="134" text-anchor="middle" font-size="9" fill="rgba(255,255,255,0.85)">{{ t('自动提取 · 状态持久化', 'Extract · Persist') }}</text>

    <!-- Knowledge service -->
    <rect x="240" y="95" width="170" height="50" rx="14" fill="url(#gl2-know)" filter="url(#gs1)" />
    <text x="325" y="118" text-anchor="middle" font-size="13" font-weight="700" fill="#fff">📚 {{ t('知识服务', 'Knowledge') }}</text>
    <text x="325" y="134" text-anchor="middle" font-size="9" fill="rgba(255,255,255,0.85)">{{ t('文档向量化 · RAG', 'Vectorize · RAG') }}</text>

    <!-- Lines to Lakebase -->
    <line x1="135" y1="145" x2="220" y2="180" stroke="var(--pub-primary, #0073e6)" stroke-width="2" opacity="0.5" />
    <line x1="325" y1="145" x2="220" y2="180" stroke="var(--pub-primary, #0073e6)" stroke-width="2" opacity="0.5" />

    <!-- Lakebase -->
    <rect x="100" y="180" width="240" height="48" rx="14" fill="url(#gl2-base)" filter="url(#gs1)" />
    <text x="220" y="203" text-anchor="middle" font-size="14" font-weight="800" fill="#fff">🐘 Lakebase</text>
    <text x="220" y="219" text-anchor="middle" font-size="9" fill="rgba(255,255,255,0.8)">{{ t('统一存储引擎', 'Unified Storage') }}</text>

    <!-- Bottom badge -->
    <rect x="130" y="245" width="180" height="26" rx="13" fill="var(--pub-surface, #fff)" stroke="var(--pub-primary, #0073e6)" stroke-width="1" filter="url(#gs2)" />
    <text x="220" y="262" text-anchor="middle" font-size="10" font-weight="600" fill="var(--pub-primary, #0073e6)">{{ t('多库合一 · 减少依赖', 'All-in-one · Less Deps') }}</text>
  </svg>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'
const { t } = useLocale()
</script>

<style scoped>
.layer-svg { width: 100%; height: auto; }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/views/landing/LayerServices.vue
git commit -m "feat(landing): add Layer 2 Memory+Knowledge services SVG diagram"
```

---

### Task 5: 第三层 SVG 架构图组件

**Files:**
- Create: `lakeon-console/src/views/landing/LayerDatalake.vue`

- [ ] **Step 1: 创建组件**

```vue
<template>
  <svg viewBox="0 0 440 300" class="layer-svg">
    <defs>
      <linearGradient id="gl3-lake" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#7b1fa2" />
        <stop offset="100%" stop-color="#4a148c" />
      </linearGradient>
      <linearGradient id="gl3-bg" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#f3e5f5" />
        <stop offset="100%" stop-color="#e1bee7" />
      </linearGradient>
      <linearGradient id="gl3-ray" x1="0%" y1="0%" x2="100%" y2="100%">
        <stop offset="0%" stop-color="#ff6f00" />
        <stop offset="100%" stop-color="#e65100" />
      </linearGradient>
      <filter id="gd1"><feDropShadow dx="0" dy="4" stdDeviation="8" flood-color="rgba(123,31,162,0.15)" /></filter>
      <filter id="gd2"><feDropShadow dx="0" dy="2" stdDeviation="4" flood-color="rgba(0,0,0,0.08)" /></filter>
    </defs>

    <ellipse cx="220" cy="150" rx="210" ry="140" fill="url(#gl3-bg)" opacity="0.3" />

    <!-- Three sources -->
    <rect x="20" y="15" width="110" height="38" rx="10" fill="var(--pub-surface, #fff)" stroke="var(--pub-primary, #0073e6)" stroke-width="1.5" filter="url(#gd2)" />
    <text x="75" y="38" text-anchor="middle" font-size="10" font-weight="600" fill="var(--pub-primary, #0073e6)">🐘 {{ t('数据库', 'Database') }}</text>

    <rect x="165" y="15" width="110" height="38" rx="10" fill="var(--pub-surface, #fff)" stroke="#2e7d32" stroke-width="1.5" filter="url(#gd2)" />
    <text x="220" y="38" text-anchor="middle" font-size="10" font-weight="600" fill="#2e7d32">📚 {{ t('知识库', 'Knowledge') }}</text>

    <rect x="310" y="15" width="110" height="38" rx="10" fill="var(--pub-surface, #fff)" stroke="#e65100" stroke-width="1.5" filter="url(#gd2)" />
    <text x="365" y="38" text-anchor="middle" font-size="10" font-weight="600" fill="#e65100">🧠 {{ t('记忆库', 'Memory') }}</text>

    <!-- Bidirectional arrows -->
    <line x1="75" y1="53" x2="75" y2="80" stroke="#7b1fa2" stroke-width="1.5" />
    <polygon points="75,80 71,72 79,72" fill="#7b1fa2" />
    <polygon points="75,53 71,61 79,61" fill="#7b1fa2" />
    <line x1="220" y1="53" x2="220" y2="80" stroke="#7b1fa2" stroke-width="1.5" />
    <polygon points="220,80 216,72 224,72" fill="#7b1fa2" />
    <polygon points="220,53 216,61 224,61" fill="#7b1fa2" />
    <line x1="365" y1="53" x2="365" y2="80" stroke="#7b1fa2" stroke-width="1.5" />
    <polygon points="365,80 361,72 369,72" fill="#7b1fa2" />
    <polygon points="365,53 361,61 369,61" fill="#7b1fa2" />

    <text x="90" y="70" font-size="7" fill="#7b1fa2" font-weight="600">{{ t('双向', 'Sync') }}</text>
    <text x="235" y="70" font-size="7" fill="#7b1fa2" font-weight="600">{{ t('双向', 'Sync') }}</text>
    <text x="380" y="70" font-size="7" fill="#7b1fa2" font-weight="600">{{ t('双向', 'Sync') }}</text>

    <!-- Data Lake -->
    <rect x="30" y="85" width="380" height="60" rx="18" fill="url(#gl3-lake)" filter="url(#gd1)" />
    <text x="220" y="112" text-anchor="middle" font-size="18" font-weight="800" fill="#fff">🌊 {{ t('AI 多模态数据湖', 'AI Data Lake') }}</text>
    <text x="220" y="132" text-anchor="middle" font-size="10" fill="rgba(255,255,255,0.8)">Parquet · Lance · {{ t('多模态存储', 'Multimodal') }}</text>

    <!-- Processing -->
    <line x1="220" y1="145" x2="220" y2="170" stroke="#7b1fa2" stroke-width="2" />

    <rect x="80" y="170" width="280" height="44" rx="12" fill="url(#gl3-ray)" filter="url(#gd1)" />
    <text x="220" y="192" text-anchor="middle" font-size="13" font-weight="700" fill="#fff">⚙️ Ray / Python {{ t('作业引擎', 'Job Engine') }}</text>
    <text x="220" y="206" text-anchor="middle" font-size="9" fill="rgba(255,255,255,0.85)">{{ t('数据加工 · 分析 · 训练', 'Process · Analyze · Train') }}</text>

    <!-- Scenarios -->
    <line x1="140" y1="214" x2="100" y2="240" stroke="#e65100" stroke-width="1.2" opacity="0.5" />
    <line x1="220" y1="214" x2="220" y2="240" stroke="#e65100" stroke-width="1.2" opacity="0.5" />
    <line x1="300" y1="214" x2="340" y2="240" stroke="#e65100" stroke-width="1.2" opacity="0.5" />

    <rect x="40" y="240" width="110" height="36" rx="8" fill="var(--pub-surface, #fff)" filter="url(#gd2)" />
    <text x="95" y="256" text-anchor="middle" font-size="8" font-weight="600" fill="#7b1fa2">📊 {{ t('用户行为分析', 'User Analytics') }}</text>
    <text x="95" y="268" text-anchor="middle" font-size="7" fill="var(--pub-text-3, #888)">{{ t('Agent 使用习惯', 'Agent Patterns') }}</text>

    <rect x="165" y="240" width="110" height="36" rx="8" fill="var(--pub-surface, #fff)" filter="url(#gd2)" />
    <text x="220" y="256" text-anchor="middle" font-size="8" font-weight="600" fill="#7b1fa2">🔄 {{ t('数据导出训练', 'Export Training') }}</text>
    <text x="220" y="268" text-anchor="middle" font-size="7" fill="var(--pub-text-3, #888)">{{ t('记忆 → 微调数据', 'Memory → Finetune') }}</text>

    <rect x="290" y="240" width="110" height="36" rx="8" fill="var(--pub-surface, #fff)" filter="url(#gd2)" />
    <text x="345" y="256" text-anchor="middle" font-size="8" font-weight="600" fill="#7b1fa2">🧹 {{ t('数据治理', 'Governance') }}</text>
    <text x="345" y="268" text-anchor="middle" font-size="7" fill="var(--pub-text-3, #888)">{{ t('清洗 · 归档', 'Clean · Archive') }}</text>
  </svg>
</template>

<script setup lang="ts">
import { useLocale } from '../../stores/locale'
const { t } = useLocale()
</script>

<style scoped>
.layer-svg { width: 100%; height: auto; }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-console/src/views/landing/LayerDatalake.vue
git commit -m "feat(landing): add Layer 3 Data Lake SVG architecture diagram"
```

---

### Task 6: 重写 LandingView.vue 主页

**Files:**
- Rewrite: `lakeon-console/src/views/landing/LandingView.vue`

这是最大的 task。完全重写 LandingView.vue，结构为：Hero → 数字条 → 三层递进 → 底部 CTA → Footer。

- [ ] **Step 1: 重写 template 部分**

用以下内容完全替换现有 `<template>` 到 `</template>` 之间的内容：

```html
<template>
  <div class="landing">
    <!-- Hero -->
    <section class="hero">
      <h1 class="hero-title">
        Serverless PostgreSQL<br>
        <span class="hero-accent">{{ t('为 AI Agent 而生', 'Built for AI Agents') }}</span>
      </h1>
      <p class="hero-subtitle">
        {{ t(
          '按需弹性 · 时间旅行 · 零运维 — 用你熟悉的 PG 协议，获得云原生的全部能力',
          'Elastic scaling · Time travel · Zero ops — Use the PG protocol you know, get cloud-native superpowers'
        ) }}
      </p>
      <div class="hero-ctas">
        <button class="cta-primary" @click="startTrial" :disabled="trialLoading">
          {{ trialLoading ? t('创建中...', 'Creating...') : t('立即试用 →', 'Try Now →') }}
        </button>
        <router-link to="/docs" class="cta-secondary">{{ t('查看文档', 'Read Docs') }}</router-link>
      </div>
      <p class="hero-hint">{{ t('无需注册，30 秒获得一个数据库', 'No signup needed, get a database in 30 seconds') }}</p>
    </section>

    <!-- Stats bar -->
    <section class="stats">
      <div class="stat" v-for="s in stats" :key="s.value">
        <div class="stat-value">{{ s.value }}</div>
        <div class="stat-label">{{ s.label }}</div>
      </div>
    </section>

    <!-- Three layers intro -->
    <section class="layers-intro">
      <h2 class="section-title">{{ t('从数据库到数据平台，按需解锁', 'From database to data platform, unlock on demand') }}</h2>
      <p class="section-subtitle">{{ t('不用一次全买，从第一层开始，按需向上扩展', 'Start with layer one, scale up as you need') }}</p>
    </section>

    <!-- Layer 1: Left text, Right SVG -->
    <section class="layer layer-alt">
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
          💡 <strong>{{ t('已在用 mem0、Hindsight？', 'Already using mem0 or Hindsight?') }}</strong><br>
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
    </section>

    <!-- Layer 2: Left SVG, Right text -->
    <section class="layer">
      <div class="layer-visual">
        <LayerServices />
      </div>
      <div class="layer-text">
        <span class="layer-badge layer-badge-secondary">{{ t('第二层 · 进阶', 'Layer 2 · Advanced') }}</span>
        <h3 class="layer-title">{{ t('原生记忆服务 + 知识服务', 'Native Memory + Knowledge Services') }}</h3>
        <p class="layer-desc">
          {{ t(
            '不想自己搭记忆和知识组件？DBay 提供完全发挥 Lakebase 能力的原生服务 — Agent 通过 MCP / Skill 直接对接，多库合一，减少第三方依赖。',
            'Don\'t want to build your own memory stack? DBay provides native services that fully leverage Lakebase — connect via MCP/Skill, all-in-one, fewer dependencies.'
          ) }}
        </p>
        <div class="layer-tags">
          <span class="tag">✓ {{ t('MCP 一键接入', 'MCP one-click') }}</span>
          <span class="tag">✓ {{ t('记忆自动提取', 'Auto memory extraction') }}</span>
          <span class="tag">✓ {{ t('文档向量化', 'Doc vectorization') }}</span>
          <span class="tag">✓ {{ t('Console 管理', 'Console management') }}</span>
        </div>
        <router-link to="/product#memory" class="layer-link">{{ t('了解记忆与知识服务 →', 'Learn about Memory & Knowledge →') }}</router-link>
      </div>
    </section>

    <!-- Layer 3: Left text, Right SVG -->
    <section class="layer layer-alt">
      <div class="layer-text">
        <span class="layer-badge layer-badge-tertiary">{{ t('第三层 · 数据闭环', 'Layer 3 · Data Loop') }}</span>
        <h3 class="layer-title">{{ t('AI 多模态数据湖', 'AI Multimodal Data Lake') }}</h3>
        <p class="layer-desc">
          {{ t(
            '数据库、知识库、记忆库与数据湖双向联动 — 导入导出自如。在数据湖上运行 Ray、Python 作业，实现数据加工和分析，形成',
            'Database, knowledge, and memory bidirectionally sync with the data lake. Run Ray/Python jobs for processing and analysis, forming a'
          ) }}
          <strong style="color: #7b1fa2">{{ t('数据飞轮', 'data flywheel') }}</strong>{{ t(
            '：Agent 使用积累数据 → 数据湖分析产生洞察 → 优化 Agent 表现 → 积累更多高质量数据。',
            ': Agent usage generates data → Lake analyzes for insights → Optimize agent → More quality data.'
          ) }}
        </p>
        <div class="layer-tags">
          <span class="tag tag-purple">📊 {{ t('分析 Agent 用户习惯', 'Analyze agent user behavior') }}</span>
          <span class="tag tag-blue">🔄 {{ t('记忆数据导出微调', 'Export memory for fine-tuning') }}</span>
          <span class="tag tag-orange">📈 {{ t('知识库质量评估', 'Knowledge quality assessment') }}</span>
          <span class="tag tag-green">🧹 {{ t('过期记忆清洗归档', 'Clean & archive stale memory') }}</span>
        </div>
        <router-link to="/product#datalake" class="layer-link">{{ t('了解数据湖 →', 'Learn about Data Lake →') }}</router-link>
      </div>
      <div class="layer-visual">
        <LayerDatalake />
      </div>
    </section>

    <!-- Bottom CTA -->
    <section class="bottom-cta">
      <h2 class="bottom-cta-title">{{ t('30 秒，免费获得一个 Serverless 数据库', 'Get a free Serverless database in 30 seconds') }}</h2>
      <p class="bottom-cta-subtitle">{{ t('无需信用卡 · 兼容所有 PG 客户端 · 立即体验', 'No credit card · Works with all PG clients · Try now') }}</p>
      <div class="bottom-cta-buttons">
        <button class="cta-white" @click="startTrial" :disabled="trialLoading">
          {{ trialLoading ? t('创建中...', 'Creating...') : t('立即试用 →', 'Try Now →') }}
        </button>
        <router-link to="/dashboard" class="cta-outline">{{ t('进入 Console', 'Open Console') }}</router-link>
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
```

- [ ] **Step 2: 重写 script 部分**

替换整个 `<script setup>` 块：

```typescript
<script setup lang="ts">
import { ref, computed } from 'vue'
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
  { value: t('秒级', 'Sub-sec'), label: t('冷启动', 'Cold Start') },
  { value: t('自动', 'Auto'), label: t('弹性伸缩', 'Elastic Scale') },
  { value: t('时间旅行', 'Time Travel'), label: t('数据版本管理', 'Data Versioning') },
  { value: '0', label: t('运维负担', 'Ops Burden') },
])

async function startTrial() {
  trialLoading.value = true
  try {
    localStorage.removeItem('lakeon_api_key')
    const { data } = await client.post('/trial')
    authStore.setTenant(data.tenant_id, data.username || 'trial')
    authStore.setTrialState(true, data.expires_at)
    router.push('/dashboard')
  } catch {
    trialLoading.value = false
  }
}
</script>
```

- [ ] **Step 3: 重写 style 部分**

替换整个 `<style scoped>` 块：

```css
<style scoped>
/* Layout */
.landing { max-width: 100%; overflow-x: hidden; }

/* Hero */
.hero {
  padding: 52px 48px 0;
  text-align: center;
  background: var(--pub-surface);
}
.hero-title {
  font-size: 44px;
  font-weight: 800;
  color: var(--pub-text);
  line-height: 1.15;
  letter-spacing: -0.5px;
  margin: 0;
}
.hero-accent { color: var(--pub-primary); }
.hero-subtitle {
  font-size: 17px;
  color: var(--pub-text-2);
  margin: 14px 0 0;
  line-height: 1.6;
}
.hero-ctas {
  display: flex;
  gap: 12px;
  justify-content: center;
  margin-top: 24px;
}
.cta-primary {
  background: var(--pub-primary);
  color: #fff;
  padding: 12px 32px;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 600;
  border: none;
  cursor: pointer;
  transition: opacity 0.15s;
}
.cta-primary:hover { opacity: 0.9; }
.cta-primary:disabled { opacity: 0.6; cursor: not-allowed; }
.cta-secondary {
  background: var(--pub-hover);
  color: var(--pub-text);
  padding: 12px 32px;
  border-radius: 8px;
  font-size: 16px;
  text-decoration: none;
  transition: background 0.15s;
}
.cta-secondary:hover { background: var(--pub-border); }
.hero-hint {
  font-size: 13px;
  color: var(--pub-text-3);
  margin-top: 12px;
}

/* Stats */
.stats {
  background: var(--pub-surface);
  padding: 28px 48px;
  display: flex;
  justify-content: center;
  gap: 64px;
}
.stat { text-align: center; }
.stat-value {
  font-size: 32px;
  font-weight: 800;
  color: var(--pub-primary);
}
.stat-label {
  font-size: 13px;
  color: var(--pub-text-3);
}

/* Layers intro */
.layers-intro {
  background: var(--pub-bg-alt, #f8f9fb);
  padding: 36px 48px 0;
  border-top: 1px solid var(--pub-border);
  text-align: center;
}
.section-title {
  font-size: 26px;
  font-weight: 700;
  color: var(--pub-text);
  margin: 0;
}
.section-subtitle {
  font-size: 14px;
  color: var(--pub-text-3);
  margin-top: 4px;
}

/* Layer sections */
.layer {
  padding: 36px 48px;
  display: flex;
  gap: 40px;
  align-items: center;
  border-top: 1px solid var(--pub-border);
  background: var(--pub-surface);
}
.layer-alt { background: var(--pub-bg-alt, #f8f9fb); }
.layer-text { flex: 1; }
.layer-visual { flex: 1; }

.layer-badge {
  padding: 3px 12px;
  border-radius: 20px;
  font-size: 11px;
  font-weight: 700;
  display: inline-block;
  margin-bottom: 12px;
  color: #fff;
}
.layer-badge-primary { background: var(--pub-primary); }
.layer-badge-secondary { background: #555; }
.layer-badge-tertiary { background: #888; }

.layer-title {
  font-size: 24px;
  font-weight: 700;
  color: var(--pub-text);
  margin: 0 0 10px;
}
.layer-desc {
  font-size: 14px;
  color: var(--pub-text-2);
  line-height: 1.7;
  margin: 0 0 16px;
}
.layer-hint {
  padding: 14px;
  background: #f0fdf4;
  border-radius: 10px;
  font-size: 13px;
  color: #2e7d32;
  line-height: 1.6;
  border: 1px solid #c8e6c9;
  margin-bottom: 14px;
}
.layer-tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}
.tag {
  background: var(--pub-bg-alt, #f8f9fb);
  border-radius: 6px;
  padding: 8px 12px;
  font-size: 12px;
  color: var(--pub-text-2);
}
.tag-purple { background: #f3e5f5; color: #7b1fa2; }
.tag-blue { background: #e3f2fd; color: #1565c0; }
.tag-orange { background: #fff3e0; color: #e65100; }
.tag-green { background: #e8f5e9; color: #2e7d32; }

.layer-link {
  font-size: 14px;
  color: var(--pub-primary);
  font-weight: 600;
  text-decoration: none;
}
.layer-link:hover { text-decoration: underline; }

/* Bottom CTA */
.bottom-cta {
  background: linear-gradient(135deg, #0062cc, var(--pub-primary));
  padding: 40px 48px;
  text-align: center;
}
.bottom-cta-title {
  font-size: 26px;
  font-weight: 700;
  color: #fff;
  margin: 0 0 6px;
}
.bottom-cta-subtitle {
  font-size: 14px;
  color: rgba(255,255,255,0.7);
  margin: 0 0 20px;
}
.bottom-cta-buttons {
  display: flex;
  gap: 12px;
  justify-content: center;
}
.cta-white {
  background: #fff;
  color: var(--pub-primary);
  padding: 12px 32px;
  border-radius: 8px;
  font-size: 16px;
  font-weight: 600;
  border: none;
  cursor: pointer;
}
.cta-white:hover { opacity: 0.9; }
.cta-white:disabled { opacity: 0.6; cursor: not-allowed; }
.cta-outline {
  border: 1.5px solid rgba(255,255,255,0.5);
  color: #fff;
  padding: 12px 32px;
  border-radius: 8px;
  font-size: 16px;
  text-decoration: none;
}
.cta-outline:hover { border-color: #fff; }

/* Footer */
.landing-footer {
  background: #111;
  padding: 14px 48px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 11px;
  color: #666;
}
.footer-links {
  display: flex;
  gap: 16px;
}
.footer-links a {
  color: #666;
  text-decoration: none;
}
.footer-links a:hover { color: #999; }

/* Responsive */
@media (max-width: 768px) {
  .hero { padding: 32px 20px 0; }
  .hero-title { font-size: 28px; }
  .hero-subtitle { font-size: 15px; }
  .stats { gap: 24px; padding: 20px; flex-wrap: wrap; }
  .stat-value { font-size: 24px; }
  .layer { flex-direction: column; padding: 24px 20px; gap: 24px; }
  .layers-intro { padding: 24px 20px 0; }
  .bottom-cta { padding: 32px 20px; }
  .landing-footer { padding: 14px 20px; }
}
</style>
```

- [ ] **Step 4: 本地验证**

Run: `cd lakeon-console && npm run dev`

打开浏览器验证：
- Hero 居中，标题 + 副标题 + CTA + 提示文字
- 数字条 4 项横排
- 三层递进：左文右图 → 左图右文 → 左文右图
- SVG 图表渲染正确（渐变、投影、连线）
- "立即试用"按钮可点击，调用 trial API
- 底部 CTA + Footer 显示正常
- 768px 以下响应式布局正常

- [ ] **Step 5: Commit**

```bash
git add lakeon-console/src/views/landing/LandingView.vue
git commit -m "feat(landing): complete landing page redesign with three-layer value proposition"
```

---

### Task 7: TypeScript 类型检查 + 最终验证

**Files:** 无新文件，全局验证

- [ ] **Step 1: 运行 vue-tsc 类型检查**

Run: `cd lakeon-console && npx vue-tsc -b --noEmit 2>&1 | tail -20`
Expected: 无错误

- [ ] **Step 2: 修复类型错误（如有）**

根据 vue-tsc 输出修复任何类型问题。

- [ ] **Step 3: 运行构建验证**

Run: `cd lakeon-console && npm run build 2>&1 | tail -10`
Expected: 构建成功

- [ ] **Step 4: 本地完整验证**

Run: `cd lakeon-console && npm run dev`

完整验证清单：
- [ ] 导航栏：产品下拉四宫格、集成下拉列表、"立即试用"蓝色按钮
- [ ] Hero：居中大标题、副标题、两个 CTA、提示文字
- [ ] 数字条：秒级 / 自动 / 时间旅行 / 0
- [ ] 第一层：左文右 SVG（mem0/Hindsight 连 Lakebase）
- [ ] 第二层：左 SVG 右文（Agent → 记忆+知识 → Lakebase）
- [ ] 第三层：左文右 SVG（三库 ↔ 数据湖 → Ray → 场景）
- [ ] 底部 CTA：蓝色渐变 + 两个按钮
- [ ] "立即试用"按钮调用 /trial API 后跳转 dashboard
- [ ] 中英切换正常
- [ ] 移动端响应式正常

- [ ] **Step 5: Final commit**

```bash
git add -A
git commit -m "feat(landing): landing page redesign complete - verify and polish"
```
