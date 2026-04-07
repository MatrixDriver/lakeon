# KB Wiki Phase 2a: Web Clipper Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Chrome extension that lets users save the current page's URL to a DBay knowledge base with one click, using web-based login authentication.

**Architecture:** Manifest V3 Chrome extension with popup UI and background service worker. Authentication via `chrome.identity.launchWebAuthFlow()` that opens a dedicated `/ext-login` page on the console. All API calls go to the existing backend endpoints — no new backend APIs needed.

**Tech Stack:** Chrome Extension Manifest V3, vanilla JS (popup/background), Vue 3 + TypeScript (ext-login page in lakeon-console)

**Spec:** `docs/superpowers/specs/2026-04-07-kb-wiki-phase2-design.md`

---

## File Structure

### Chrome Extension (lakeon-clipper)

| File | Action | Responsibility |
|------|--------|----------------|
| `manifest.json` | Create | Extension manifest (permissions, popup, service worker, icons) |
| `popup.html` | Create | Popup shell (loads popup.js) |
| `popup.js` | Create | Popup logic: auth state, KB list, save URL, UI rendering |
| `background.js` | Create | Service worker: launchWebAuthFlow, API calls |
| `icons/icon-16.png` | Create | Toolbar icon 16x16 |
| `icons/icon-48.png` | Create | Extension list icon 48x48 |
| `icons/icon-128.png` | Create | Chrome Web Store icon 128x128 |

### Console (lakeon-console)

| File | Action | Responsibility |
|------|--------|----------------|
| `src/views/login/ExtLoginView.vue` | Create | Minimal login page for extension auth flow |
| `src/router/index.ts` | Modify | Add `/ext-login` route |

---

## Task 1: Console ext-login page — route and page skeleton

**Files:**
- Create: `lakeon-console/src/views/login/ExtLoginView.vue`
- Modify: `lakeon-console/src/router/index.ts`

- [ ] **Step 1: Add /ext-login route to router**

In `lakeon-console/src/router/index.ts`, add a new route before the public layout routes (before the `path: '/'` block at line ~10):

```typescript
{
  path: '/ext-login',
  name: 'ExtLogin',
  component: () => import('../views/login/ExtLoginView.vue'),
  meta: { noAuth: true },
},
```

- [ ] **Step 2: Create ExtLoginView.vue**

Create `lakeon-console/src/views/login/ExtLoginView.vue`:

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { tenantApi } from '@/api/tenant'

const username = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)

async function handleLogin() {
  error.value = ''
  loading.value = true
  try {
    const res = await tenantApi.login(username.value, password.value)
    const tenant = res.data
    const apiKey = tenant?.api_key
    if (!apiKey) {
      error.value = '登录失败：未获取到 API Key'
      return
    }
    // Extract redirect_uri from query string
    const params = new URLSearchParams(window.location.search)
    const redirectUri = params.get('redirect_uri')
    if (redirectUri && redirectUri.endsWith('.chromiumapp.org/')) {
      // Redirect back to extension with key in hash
      window.location.href = redirectUri + '#key=' + encodeURIComponent(apiKey)
    } else {
      error.value = '无效的 redirect_uri'
    }
  } catch (e: any) {
    error.value = e?.response?.data?.error || e?.message || '登录失败'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="ext-login-container">
    <div class="ext-login-card">
      <div class="ext-login-header">
        <h2>登录 DBay</h2>
        <p class="subtitle">授权 Web Clipper 扩展访问你的知识库</p>
      </div>
      <form @submit.prevent="handleLogin" class="ext-login-form">
        <div class="field">
          <label for="username">用户名</label>
          <input
            id="username"
            v-model="username"
            type="text"
            autocomplete="username"
            required
            :disabled="loading"
          />
        </div>
        <div class="field">
          <label for="password">密码</label>
          <input
            id="password"
            v-model="password"
            type="password"
            autocomplete="current-password"
            required
            :disabled="loading"
          />
        </div>
        <p v-if="error" class="error">{{ error }}</p>
        <button type="submit" :disabled="loading || !username || !password">
          {{ loading ? '登录中...' : '授权登录' }}
        </button>
      </form>
    </div>
  </div>
</template>

<style scoped>
.ext-login-container {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #faf8f5;
}
.ext-login-card {
  width: 360px;
  background: #fff;
  border-radius: 12px;
  padding: 40px 32px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.08);
}
.ext-login-header {
  text-align: center;
  margin-bottom: 28px;
}
.ext-login-header h2 {
  font-size: 22px;
  font-weight: 600;
  color: #2c2c2c;
  margin: 0 0 6px;
}
.subtitle {
  font-size: 13px;
  color: #888;
  margin: 0;
}
.ext-login-form .field {
  margin-bottom: 18px;
}
.ext-login-form label {
  display: block;
  font-size: 13px;
  font-weight: 500;
  color: #555;
  margin-bottom: 6px;
}
.ext-login-form input {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid #ddd;
  border-radius: 8px;
  font-size: 14px;
  outline: none;
  transition: border-color 0.2s;
  box-sizing: border-box;
}
.ext-login-form input:focus {
  border-color: #c19a6b;
}
.error {
  color: #d94f4f;
  font-size: 13px;
  margin: 0 0 12px;
}
.ext-login-form button {
  width: 100%;
  padding: 11px;
  background: #c19a6b;
  color: #fff;
  border: none;
  border-radius: 8px;
  font-size: 15px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.2s;
}
.ext-login-form button:hover:not(:disabled) {
  background: #a8834f;
}
.ext-login-form button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
</style>
```

- [ ] **Step 3: Verify the page renders**

Run: `cd lakeon-console && npm run dev`

Open: `http://localhost:5173/ext-login?redirect_uri=https://test.chromiumapp.org/`

Expected: Login form renders with username/password fields and "授权登录" button.

- [ ] **Step 4: Commit**

```bash
git add lakeon-console/src/views/login/ExtLoginView.vue lakeon-console/src/router/index.ts
git commit -m "feat(console): add ext-login page for Chrome extension auth flow"
```

---

## Task 2: Chrome extension — manifest and popup HTML

**Files:**
- Create: `lakeon-clipper/manifest.json`
- Create: `lakeon-clipper/popup.html`

- [ ] **Step 1: Create lakeon-clipper directory**

```bash
mkdir -p lakeon-clipper/icons
```

- [ ] **Step 2: Create manifest.json**

Create `lakeon-clipper/manifest.json`:

```json
{
  "manifest_version": 3,
  "name": "DBay Web Clipper",
  "version": "1.0.0",
  "description": "Save web pages to your DBay knowledge base with one click",
  "permissions": [
    "activeTab",
    "storage",
    "identity"
  ],
  "host_permissions": [
    "https://api.dbay.cloud:8443/*"
  ],
  "action": {
    "default_popup": "popup.html",
    "default_icon": {
      "16": "icons/icon-16.png",
      "48": "icons/icon-48.png",
      "128": "icons/icon-128.png"
    }
  },
  "background": {
    "service_worker": "background.js"
  },
  "icons": {
    "16": "icons/icon-16.png",
    "48": "icons/icon-48.png",
    "128": "icons/icon-128.png"
  }
}
```

- [ ] **Step 3: Create popup.html**

Create `lakeon-clipper/popup.html`:

```html
<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8">
  <style>
    * { margin: 0; padding: 0; box-sizing: border-box; }
    body {
      width: 320px;
      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
      background: #faf8f5;
      color: #2c2c2c;
    }
    .header {
      padding: 16px 18px 12px;
      border-bottom: 1px solid #eee;
    }
    .header h1 {
      font-size: 16px;
      font-weight: 600;
      color: #2c2c2c;
    }
    .content { padding: 16px 18px; }

    /* Login state */
    .login-btn {
      width: 100%;
      padding: 11px;
      background: #c19a6b;
      color: #fff;
      border: none;
      border-radius: 8px;
      font-size: 14px;
      font-weight: 500;
      cursor: pointer;
    }
    .login-btn:hover { background: #a8834f; }
    .login-hint {
      font-size: 12px;
      color: #999;
      text-align: center;
      margin-top: 10px;
    }

    /* Logged-in state */
    .url-display {
      font-size: 12px;
      color: #666;
      word-break: break-all;
      background: #f5f2ed;
      padding: 8px 10px;
      border-radius: 6px;
      margin-bottom: 14px;
      max-height: 48px;
      overflow: hidden;
    }
    .field-label {
      font-size: 12px;
      font-weight: 500;
      color: #888;
      margin-bottom: 6px;
    }
    select {
      width: 100%;
      padding: 9px 10px;
      border: 1px solid #ddd;
      border-radius: 8px;
      font-size: 13px;
      background: #fff;
      margin-bottom: 14px;
      outline: none;
    }
    select:focus { border-color: #c19a6b; }
    .save-btn {
      width: 100%;
      padding: 10px;
      background: #c19a6b;
      color: #fff;
      border: none;
      border-radius: 8px;
      font-size: 14px;
      font-weight: 500;
      cursor: pointer;
    }
    .save-btn:hover { background: #a8834f; }
    .save-btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .status {
      font-size: 12px;
      text-align: center;
      margin-top: 10px;
      padding: 6px;
      border-radius: 6px;
    }
    .status.success { color: #4a8c5c; background: #edf7ef; }
    .status.error { color: #d94f4f; background: #fdf0f0; }
    .status.loading { color: #c19a6b; background: #fdf8f0; }

    /* Footer */
    .footer {
      padding: 10px 18px;
      border-top: 1px solid #eee;
      display: flex;
      justify-content: flex-end;
    }
    .logout-btn {
      font-size: 11px;
      color: #999;
      background: none;
      border: none;
      cursor: pointer;
    }
    .logout-btn:hover { color: #d94f4f; }

    .hidden { display: none; }
  </style>
</head>
<body>
  <div class="header">
    <h1>DBay Web Clipper</h1>
  </div>

  <!-- Not logged in -->
  <div id="login-view" class="content hidden">
    <button class="login-btn" id="login-btn">登录 DBay</button>
    <p class="login-hint">登录后即可保存网页到知识库</p>
  </div>

  <!-- Logged in -->
  <div id="main-view" class="content hidden">
    <div class="url-display" id="current-url"></div>
    <div class="field-label">选择知识库</div>
    <select id="kb-select">
      <option value="">加载中...</option>
    </select>
    <button class="save-btn" id="save-btn" disabled>保存到知识库</button>
    <div id="status" class="status hidden"></div>
  </div>

  <div id="footer" class="footer hidden">
    <button class="logout-btn" id="logout-btn">退出登录</button>
  </div>

  <script src="popup.js"></script>
</body>
</html>
```

- [ ] **Step 4: Create placeholder icons**

Generate simple placeholder PNG icons (will be replaced with real icons later). For now, create minimal valid PNGs:

```bash
# Use ImageMagick if available, otherwise create manually later
cd lakeon-clipper
convert -size 16x16 xc:#c19a6b icons/icon-16.png 2>/dev/null || echo "Create icons manually"
convert -size 48x48 xc:#c19a6b icons/icon-48.png 2>/dev/null || echo "Create icons manually"
convert -size 128x128 xc:#c19a6b icons/icon-128.png 2>/dev/null || echo "Create icons manually"
```

If ImageMagick not available, create any valid PNG files at those sizes manually.

- [ ] **Step 5: Commit**

```bash
git add lakeon-clipper/manifest.json lakeon-clipper/popup.html lakeon-clipper/icons/
git commit -m "feat(clipper): add Chrome extension manifest and popup HTML"
```

---

## Task 3: Chrome extension — background service worker

**Files:**
- Create: `lakeon-clipper/background.js`

- [ ] **Step 1: Create background.js**

Create `lakeon-clipper/background.js`:

```javascript
const API_BASE = 'https://api.dbay.cloud:8443/api/v1'
const AUTH_URL = 'https://console.dbay.cloud/ext-login'

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  if (msg.type === 'login') {
    handleLogin().then(sendResponse)
    return true // async response
  }
  if (msg.type === 'logout') {
    chrome.storage.local.remove(['apiKey', 'lastKbId'], () => {
      sendResponse({ ok: true })
    })
    return true
  }
  if (msg.type === 'getApiKey') {
    chrome.storage.local.get('apiKey', (data) => {
      sendResponse({ apiKey: data.apiKey || null })
    })
    return true
  }
  if (msg.type === 'fetchKbList') {
    fetchKbList(msg.apiKey).then(sendResponse)
    return true
  }
  if (msg.type === 'saveUrl') {
    saveUrl(msg.apiKey, msg.kbId, msg.url).then(sendResponse)
    return true
  }
})

async function handleLogin() {
  try {
    const redirectUri = chrome.identity.getRedirectURL()
    const authUrl = `${AUTH_URL}?redirect_uri=${encodeURIComponent(redirectUri)}`
    const responseUrl = await new Promise((resolve, reject) => {
      chrome.identity.launchWebAuthFlow(
        { url: authUrl, interactive: true },
        (responseUrl) => {
          if (chrome.runtime.lastError) {
            reject(new Error(chrome.runtime.lastError.message))
          } else {
            resolve(responseUrl)
          }
        }
      )
    })
    // Extract key from hash: ...#key=lk_xxx
    const hash = new URL(responseUrl).hash
    const params = new URLSearchParams(hash.substring(1))
    const apiKey = params.get('key')
    if (!apiKey) {
      return { ok: false, error: '未获取到 API Key' }
    }
    await chrome.storage.local.set({ apiKey })
    return { ok: true }
  } catch (e) {
    return { ok: false, error: e.message || '登录失败' }
  }
}

async function fetchKbList(apiKey) {
  try {
    const res = await fetch(`${API_BASE}/knowledge/bases`, {
      headers: { 'Authorization': `Bearer ${apiKey}` }
    })
    if (!res.ok) {
      if (res.status === 401) return { ok: false, error: 'unauthorized' }
      return { ok: false, error: `HTTP ${res.status}` }
    }
    const data = await res.json()
    // Filter to DOCUMENT type KBs only (wiki ingest only works on document KBs)
    const kbs = data
      .filter(kb => kb.type === 'DOCUMENT' && kb.status === 'READY')
      .map(kb => ({ id: kb.id, name: kb.name }))
    return { ok: true, kbs }
  } catch (e) {
    return { ok: false, error: e.message || '获取知识库列表失败' }
  }
}

async function saveUrl(apiKey, kbId, url) {
  try {
    const res = await fetch(`${API_BASE}/knowledge/wiki/ingest-url`, {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${apiKey}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ kb_id: kbId, url })
    })
    if (!res.ok) {
      const body = await res.json().catch(() => ({}))
      return { ok: false, error: body.error || `HTTP ${res.status}` }
    }
    const data = await res.json()
    return { ok: true, data }
  } catch (e) {
    return { ok: false, error: e.message || '保存失败' }
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add lakeon-clipper/background.js
git commit -m "feat(clipper): add background service worker with auth and API calls"
```

---

## Task 4: Chrome extension — popup logic

**Files:**
- Create: `lakeon-clipper/popup.js`

- [ ] **Step 1: Create popup.js**

Create `lakeon-clipper/popup.js`:

```javascript
const loginView = document.getElementById('login-view')
const mainView = document.getElementById('main-view')
const footer = document.getElementById('footer')
const loginBtn = document.getElementById('login-btn')
const logoutBtn = document.getElementById('logout-btn')
const currentUrlEl = document.getElementById('current-url')
const kbSelect = document.getElementById('kb-select')
const saveBtn = document.getElementById('save-btn')
const statusEl = document.getElementById('status')

let currentApiKey = null
let currentTabUrl = null

// Initialize
document.addEventListener('DOMContentLoaded', init)

async function init() {
  // Get current tab URL
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true })
  currentTabUrl = tab?.url || ''
  currentUrlEl.textContent = currentTabUrl

  // Check auth state
  const res = await sendMessage({ type: 'getApiKey' })
  if (res.apiKey) {
    currentApiKey = res.apiKey
    showMainView()
    loadKbList()
  } else {
    showLoginView()
  }
}

// --- Views ---

function showLoginView() {
  loginView.classList.remove('hidden')
  mainView.classList.add('hidden')
  footer.classList.add('hidden')
}

function showMainView() {
  loginView.classList.add('hidden')
  mainView.classList.remove('hidden')
  footer.classList.remove('hidden')
}

// --- Login ---

loginBtn.addEventListener('click', async () => {
  loginBtn.disabled = true
  loginBtn.textContent = '登录中...'
  const res = await sendMessage({ type: 'login' })
  if (res.ok) {
    const keyRes = await sendMessage({ type: 'getApiKey' })
    currentApiKey = keyRes.apiKey
    showMainView()
    loadKbList()
  } else {
    showStatus('error', res.error || '登录失败')
  }
  loginBtn.disabled = false
  loginBtn.textContent = '登录 DBay'
})

// --- Logout ---

logoutBtn.addEventListener('click', async () => {
  await sendMessage({ type: 'logout' })
  currentApiKey = null
  showLoginView()
  hideStatus()
})

// --- KB List ---

async function loadKbList() {
  kbSelect.innerHTML = '<option value="">加载中...</option>'
  saveBtn.disabled = true

  const res = await sendMessage({ type: 'fetchKbList', apiKey: currentApiKey })
  if (!res.ok) {
    if (res.error === 'unauthorized') {
      // Key expired, force re-login
      await sendMessage({ type: 'logout' })
      currentApiKey = null
      showLoginView()
      showStatus('error', 'API Key 已过期，请重新登录')
      return
    }
    kbSelect.innerHTML = '<option value="">加载失败</option>'
    showStatus('error', res.error)
    return
  }

  if (res.kbs.length === 0) {
    kbSelect.innerHTML = '<option value="">没有可用的知识库</option>'
    return
  }

  // Restore last selected KB
  const stored = await chrome.storage.local.get('lastKbId')
  const lastKbId = stored.lastKbId

  kbSelect.innerHTML = res.kbs.map(kb =>
    `<option value="${kb.id}" ${kb.id === lastKbId ? 'selected' : ''}>${kb.name}</option>`
  ).join('')

  saveBtn.disabled = false
}

// Remember KB selection
kbSelect.addEventListener('change', () => {
  chrome.storage.local.set({ lastKbId: kbSelect.value })
})

// --- Save URL ---

saveBtn.addEventListener('click', async () => {
  const kbId = kbSelect.value
  if (!kbId || !currentTabUrl) return

  saveBtn.disabled = true
  saveBtn.textContent = '保存中...'
  showStatus('loading', '正在保存到知识库...')

  // Remember selection
  chrome.storage.local.set({ lastKbId: kbId })

  const res = await sendMessage({
    type: 'saveUrl',
    apiKey: currentApiKey,
    kbId,
    url: currentTabUrl
  })

  if (res.ok) {
    showStatus('success', '已保存到知识库')
  } else {
    showStatus('error', res.error || '保存失败')
  }

  saveBtn.disabled = false
  saveBtn.textContent = '保存到知识库'
})

// --- Helpers ---

function showStatus(type, text) {
  statusEl.className = `status ${type}`
  statusEl.textContent = text
  statusEl.classList.remove('hidden')
}

function hideStatus() {
  statusEl.classList.add('hidden')
}

function sendMessage(msg) {
  return new Promise((resolve) => {
    chrome.runtime.sendMessage(msg, resolve)
  })
}
```

- [ ] **Step 2: Load extension in Chrome and test**

1. Open `chrome://extensions/`
2. Enable "Developer mode"
3. Click "Load unpacked" → select `lakeon-clipper/` directory
4. Click the extension icon → verify popup shows login view
5. Click "登录 DBay" → verify it opens the ext-login page
6. Login → verify redirect back and popup shows KB list
7. Select a KB → click "保存到知识库" → verify success

- [ ] **Step 3: Commit**

```bash
git add lakeon-clipper/popup.js
git commit -m "feat(clipper): add popup logic with auth, KB list, and URL save"
```

---

## Task 5: Console ext-login — type check and build verification

**Files:**
- Modify: `lakeon-console/src/views/login/ExtLoginView.vue` (if needed)

- [ ] **Step 1: Run type check**

```bash
cd lakeon-console && npx vue-tsc -b --noEmit
```

Expected: No errors. If the `tenantApi.login` call has type issues, check `lakeon-console/src/api/tenant.ts` for the correct function signature and adjust ExtLoginView.vue accordingly.

- [ ] **Step 2: Run build**

```bash
cd lakeon-console && npm run build
```

Expected: Build succeeds with no errors.

- [ ] **Step 3: Fix any issues and commit**

If type check or build fails, fix the issues and commit:

```bash
git add lakeon-console/
git commit -m "fix(console): resolve ext-login type check issues"
```

---

## Task 6: E2E test — ext-login page

**Files:**
- Create: `tests/e2e/test_ext_login.py`

- [ ] **Step 1: Write E2E test for ext-login page**

Create `tests/e2e/test_ext_login.py`:

```python
"""E2E tests for the ext-login page used by Chrome extension auth flow."""
import pytest
import httpx
import uuid

API_BASE = "https://api.dbay.cloud:8443/api/v1"
CONSOLE_URL = "https://console.dbay.cloud"


@pytest.fixture(scope="module")
def temp_tenant():
    """Create a temporary tenant for testing, clean up after."""
    client = httpx.Client(base_url=API_BASE, verify=False, timeout=30)
    name = f"clipper-test-{uuid.uuid4().hex[:8]}"
    username = f"clipper_{uuid.uuid4().hex[:6]}"
    password = "testpass123"
    res = client.post("/tenants", json={
        "name": name,
        "username": username,
        "password": password
    })
    assert res.status_code == 200, f"Failed to create tenant: {res.text}"
    tenant = res.json()
    yield {
        "id": tenant["id"],
        "username": username,
        "password": password,
        "api_key": tenant["api_key"],
        "name": name
    }
    # Cleanup: delete tenant via admin API
    admin_headers = {"Authorization": "Bearer lakeon-sre-2026"}
    client.delete(f"/admin/tenants/{tenant['id']}", headers=admin_headers)
    client.close()


class TestExtLoginPage:
    """Tests for the ext-login authentication page."""

    def test_login_api_returns_api_key(self, temp_tenant):
        """Verify the login API returns a valid API key (same API ext-login page uses)."""
        client = httpx.Client(base_url=API_BASE, verify=False, timeout=30)
        res = client.post("/auth/login", json={
            "username": temp_tenant["username"],
            "password": temp_tenant["password"]
        })
        assert res.status_code == 200
        data = res.json()
        assert "api_key" in data, f"Response missing api_key: {data}"
        assert data["api_key"].startswith("lk_"), f"Invalid API key format: {data['api_key']}"
        # Verify the key works for KB list
        kb_res = client.get("/knowledge/bases", headers={
            "Authorization": f"Bearer {data['api_key']}"
        })
        assert kb_res.status_code == 200
        client.close()

    def test_login_wrong_password_returns_error(self, temp_tenant):
        """Verify wrong password returns 401."""
        client = httpx.Client(base_url=API_BASE, verify=False, timeout=30)
        res = client.post("/auth/login", json={
            "username": temp_tenant["username"],
            "password": "wrong_password"
        })
        assert res.status_code == 401
        client.close()

    def test_ext_login_page_accessible(self):
        """Verify the ext-login page is accessible (returns 200 HTML)."""
        client = httpx.Client(base_url=CONSOLE_URL, verify=False, timeout=30)
        res = client.get("/ext-login?redirect_uri=https://test.chromiumapp.org/")
        assert res.status_code == 200
        assert "text/html" in res.headers.get("content-type", "")
        client.close()


class TestClipperApiWorkflow:
    """Test the full API workflow that the Chrome extension performs."""

    def test_full_clipper_flow(self, temp_tenant):
        """Simulate the complete clipper workflow: login → list KBs → save URL."""
        api_key = temp_tenant["api_key"]
        headers = {"Authorization": f"Bearer {api_key}"}
        client = httpx.Client(base_url=API_BASE, verify=False, timeout=60)

        # Step 1: List KBs (should be empty for new tenant, but API works)
        res = client.get("/knowledge/bases", headers=headers)
        assert res.status_code == 200
        kbs = res.json()
        assert isinstance(kbs, list)

        # Step 2: Create a test KB to save URL into
        res = client.post("/knowledge/bases", headers=headers, json={
            "name": f"clipper-kb-{uuid.uuid4().hex[:6]}",
            "type": "DOCUMENT"
        })
        assert res.status_code == 200
        kb = res.json()
        kb_id = kb["id"]

        # Wait for KB to be ready
        import time
        for _ in range(30):
            res = client.get(f"/knowledge/bases/{kb_id}", headers=headers)
            if res.status_code == 200 and res.json().get("status") == "READY":
                break
            time.sleep(2)
        else:
            pytest.fail("KB did not become READY within 60s")

        # Step 3: List KBs again — should include our new KB
        res = client.get("/knowledge/bases", headers=headers)
        assert res.status_code == 200
        kbs = res.json()
        kb_ids = [k["id"] for k in kbs]
        assert kb_id in kb_ids, f"Created KB {kb_id} not in list: {kb_ids}"

        # Step 4: Save a URL (use a small, reliable test page)
        res = client.post("/knowledge/wiki/ingest-url", headers=headers, json={
            "kb_id": kb_id,
            "url": "https://en.wikipedia.org/wiki/Knowledge_base"
        })
        assert res.status_code == 200, f"URL ingest failed: {res.text}"
        data = res.json()
        assert "document_id" in data or "status" in data, f"Unexpected response: {data}"

        # Cleanup: delete the KB
        client.delete(f"/knowledge/bases/{kb_id}", headers=headers)
        client.close()
```

- [ ] **Step 2: Run the E2E tests**

```bash
python3 -m pytest tests/e2e/test_ext_login.py -v
```

Expected: All tests pass. If ext-login page is not yet deployed, skip `test_ext_login_page_accessible` temporarily and note it needs to be tested after deploy.

- [ ] **Step 3: Fix any failures and commit**

```bash
git add tests/e2e/test_ext_login.py
git commit -m "test: add E2E tests for Web Clipper auth flow and API workflow"
```

---

## Task 7: Deploy and manual verification

- [ ] **Step 1: Push console changes to trigger Railway deploy**

```bash
git push origin main
```

Console (with ext-login page) will auto-deploy to Railway.

- [ ] **Step 2: Verify ext-login page on production**

Open: `https://console.dbay.cloud/ext-login?redirect_uri=https://test.chromiumapp.org/`

Expected: Login form renders correctly.

- [ ] **Step 3: Load extension in Chrome and test end-to-end**

1. Open `chrome://extensions/` → Load unpacked `lakeon-clipper/`
2. Navigate to any article page
3. Click extension icon → "登录 DBay"
4. Login on ext-login page → verify popup shows KB list
5. Select KB → "保存到知识库" → verify "已保存到知识库"
6. Open the KB in DBay console → verify the URL document appears
7. Wait for ingest to complete → verify wiki is updated

- [ ] **Step 4: Re-run E2E tests against production**

```bash
python3 -m pytest tests/e2e/test_ext_login.py -v
```

Expected: All tests PASSED.
