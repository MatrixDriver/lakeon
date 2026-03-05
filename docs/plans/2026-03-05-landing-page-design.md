# Landing Page Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a bilingual (zh/en) landing page to lakeon-console that introduces Lakeon as an AI-era serverless data platform, with inline registration.

**Architecture:** Single-page scroll layout added as `/landing` route in existing lakeon-console Vue app. Router redirects unauthenticated `/` visits to landing page. i18n via a simple reactive locale store (no vue-i18n dependency). Reuses existing Huawei Cloud style tokens from `style.css`.

**Tech Stack:** Vue 3, TypeScript, existing project styles, no new dependencies.

---

### Task 1: i18n locale store

**Files:**
- Create: `lakeon-console/src/stores/locale.ts`

**Step 1: Create locale store**

```typescript
import { ref } from 'vue'

const locale = ref<'zh' | 'en'>((localStorage.getItem('lakeon_locale') as 'zh' | 'en') || 'zh')

export function useLocale() {
  function setLocale(l: 'zh' | 'en') {
    locale.value = l
    localStorage.setItem('lakeon_locale', l)
  }

  function t(zh: string, en: string): string {
    return locale.value === 'zh' ? zh : en
  }

  return { locale, setLocale, t }
}
```

**Step 2: Commit**

```bash
git add lakeon-console/src/stores/locale.ts
git commit -m "feat: add simple i18n locale store for landing page"
```

---

### Task 2: Landing page view - Hero section

**Files:**
- Create: `lakeon-console/src/views/landing/LandingView.vue`

**Step 1: Create LandingView with Hero section**

Create the full landing page component. The Hero section includes:
- Top nav bar with logo, nav links (anchors to sections), language toggle, "登录/Login" button
- Headline: "为 AI 应用而生的 Serverless 数据平台" / "The Serverless Data Platform Built for AI"
- Subtitle describing core value
- Inline registration form (tenant name input + register button), reusing `tenantApi.register()`
- Registration success flow showing API key with copy button

Style: light background, blue primary (`#0073e6`), red accent (`#e6393d`), consistent with existing `style.css`.

**Step 2: Commit**

```bash
git add lakeon-console/src/views/landing/LandingView.vue
git commit -m "feat: add landing page with hero section and registration"
```

---

### Task 3: Landing page - Feature sections

**Files:**
- Modify: `lakeon-console/src/views/landing/LandingView.vue`

**Step 1: Add remaining sections to LandingView**

Add these sections below Hero, all within the same component:

1. **Core Features** — 4 cards in a grid:
   - Serverless (auto sleep/wake, zero idle cost)
   - Instant Ready (create DB in seconds, zero ops)
   - Database Branching (Git-like data management)
   - Disaggregated Storage/Compute (elastic storage, on-demand compute)

2. **Unified Capabilities** — icon row showing:
   - Relational (PostgreSQL) — available
   - Vector Search (pgvector) — available
   - Full-text Search (pg_search) — available
   - Graph Queries — "coming soon" badge
   - KV Store — "coming soon" badge

3. **Use Cases** — 3 cards:
   - AI Agent Long-term Memory
   - Knowledge Base / RAG
   - Multimodal Data Processing

4. **Architecture Highlights** — simplified diagram (CSS/SVG):
   - App -> Lakeon -> multiple data capabilities
   - Highlight serverless elasticity, auto sleep, unified interface

5. **Quick Start** — 3 steps with code snippets:
   - Register -> Create Database -> Connect (Python/Node.js examples)

6. **Pricing** — simple "免费试用" + "详细定价即将公布"

7. **Footer** — contact, copyright

All text uses `t(zh, en)` for bilingual support.

**Step 2: Commit**

```bash
git add lakeon-console/src/views/landing/LandingView.vue
git commit -m "feat: add feature sections, use cases, quick start to landing page"
```

---

### Task 4: Router integration

**Files:**
- Modify: `lakeon-console/src/router/index.ts`

**Step 1: Add landing route and update redirect logic**

```typescript
// Add landing route (noAuth)
{
  path: '/landing',
  name: 'Landing',
  component: () => import('../views/landing/LandingView.vue'),
  meta: { noAuth: true },
},
```

Update `beforeEach` guard:
- If unauthenticated and visiting `/`, redirect to `/landing` instead of `/login`
- `/login` remains accessible for existing users
- Authenticated users visiting `/landing` still see landing page (no forced redirect)

**Step 2: Verify**

- Visit `/` unauthenticated -> shows landing page
- Click "登录" on landing page -> goes to `/login`
- Register on landing page -> shows API key -> can login
- Visit `/` authenticated -> goes to `/dashboard`

**Step 3: Commit**

```bash
git add lakeon-console/src/router/index.ts
git commit -m "feat: add landing route, redirect unauthenticated root to landing page"
```

---

### Task 5: Build and verify

**Step 1: Build**

```bash
cd lakeon-console && npm run build
```

Fix any TypeScript or build errors.

**Step 2: Local dev test**

```bash
npm run dev
```

Verify:
- Landing page renders at `/landing`
- All sections scroll correctly
- Language toggle switches all text
- Registration form works
- Nav links scroll to correct sections
- Responsive on mobile widths

**Step 3: Commit any fixes**

```bash
git add -A
git commit -m "fix: landing page build and polish"
```
