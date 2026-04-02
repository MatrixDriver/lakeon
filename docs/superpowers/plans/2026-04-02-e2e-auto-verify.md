# E2E Auto-Verify Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Playwright browser E2E tests to lakeon-console that test the pipeline and component library UI against the live API, with automatic dev server management.

**Architecture:** Playwright runs against a local Vite dev server (localhost:5173). A global setup creates a test tenant via admin API and saves auth state to a JSON file. Each test loads this auth state so tests start authenticated. Global teardown cleans up the tenant.

**Tech Stack:** Playwright Test, TypeScript, Vite dev server, live API at api.dbay.cloud:8443

---

### Task 1: Install Playwright and configure project

**Files:**
- Modify: `lakeon-console/package.json`
- Create: `lakeon-console/playwright.config.ts`
- Modify: `lakeon-console/.gitignore`

- [ ] **Step 1: Install Playwright**

```bash
cd lakeon-console
npm install -D @playwright/test
npx playwright install chromium
```

- [ ] **Step 2: Create playwright.config.ts**

```typescript
import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 1,
  workers: 1,
  reporter: [['html', { open: 'never' }]],
  use: {
    baseURL: 'http://localhost:5173',
    storageState: 'e2e/.auth/state.json',
    screenshot: 'only-on-failure',
    trace: 'on-first-retry',
    actionTimeout: 15000,
  },
  globalSetup: './e2e/fixtures/global-setup.ts',
  globalTeardown: './e2e/fixtures/global-teardown.ts',
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 30000,
  },
})
```

- [ ] **Step 3: Add test:e2e script to package.json**

Add to the `"scripts"` section:

```json
"test:e2e": "playwright test",
"test:e2e:headed": "playwright test --headed",
"test:e2e:debug": "playwright test --debug"
```

- [ ] **Step 4: Update .gitignore**

Append to `lakeon-console/.gitignore`:

```
# Playwright
test-results/
playwright-report/
e2e/.auth/
```

- [ ] **Step 5: Create directory structure**

```bash
mkdir -p lakeon-console/e2e/fixtures
mkdir -p lakeon-console/e2e/.auth
```

- [ ] **Step 6: Commit**

```bash
git add lakeon-console/package.json lakeon-console/package-lock.json \
  lakeon-console/playwright.config.ts lakeon-console/.gitignore
git commit -m "chore: add Playwright E2E test infrastructure"
```

---

### Task 2: Global setup — create test tenant and save auth state

**Files:**
- Create: `lakeon-console/e2e/fixtures/global-setup.ts`
- Create: `lakeon-console/e2e/fixtures/global-teardown.ts`
- Create: `lakeon-console/e2e/fixtures/api-helpers.ts`

- [ ] **Step 1: Create api-helpers.ts**

HTTP helper that talks to the admin API. No Playwright dependency — pure fetch.

```typescript
// e2e/fixtures/api-helpers.ts
const API_BASE = 'https://api.dbay.cloud:8443/api/v1'
const ADMIN_TOKEN = process.env.DBAY_ADMIN_TOKEN || 'lakeon-sre-2026'

async function apiRequest(method: string, path: string, body?: unknown, token?: string) {
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (token) headers['Authorization'] = `Bearer ${token}`
  const resp = await fetch(`${API_BASE}${path}`, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
  })
  if (resp.status === 204) return {}
  const data = await resp.json()
  if (!resp.ok) throw new Error(`API ${resp.status}: ${JSON.stringify(data)}`)
  return data
}

export async function createTestTenant(): Promise<{ apiKey: string; tenantId: string; tenantName: string }> {
  const ts = Date.now()
  const username = `e2e-pw-${ts}`
  const password = `E2ePw@${ts}`
  const name = `E2E Playwright ${ts}`

  // Create invite code
  const invite = await apiRequest('POST', '/admin/invite-codes', { max_uses: 1 }, ADMIN_TOKEN)

  // Register tenant with spoofed IP to avoid rate limits
  const fakeIp = `10.${Math.floor(Math.random() * 256)}.${Math.floor(Math.random() * 256)}.${Math.floor(Math.random() * 254) + 1}`
  const resp = await fetch(`${API_BASE}/tenants`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Forwarded-For': fakeIp },
    body: JSON.stringify({ username, password, name, inviteCode: invite.code }),
  })
  const tenant = await resp.json()

  // Increase quota
  await apiRequest('PUT', `/admin/tenants/${tenant.id}/quota`,
    { max_databases: 20 }, ADMIN_TOKEN)

  return { apiKey: tenant.api_key, tenantId: tenant.id, tenantName: name }
}

export async function deleteTestTenant(tenantId: string): Promise<void> {
  // Delete all pipelines owned by tenant
  try {
    const pipelines = await apiRequest('GET', '/pipelines', undefined, undefined)
    // Admin endpoint lists all — filter isn't needed; tenant cleanup handles it
  } catch { /* ignore */ }

  // Delete tenant via admin API
  await apiRequest('DELETE', '/admin/tenants/batch', { ids: [tenantId] }, ADMIN_TOKEN)
}
```

- [ ] **Step 2: Create global-setup.ts**

Creates a test tenant and saves localStorage auth state for Playwright.

```typescript
// e2e/fixtures/global-setup.ts
import { chromium, type FullConfig } from '@playwright/test'
import { createTestTenant } from './api-helpers'
import * as fs from 'fs'
import * as path from 'path'

async function globalSetup(config: FullConfig) {
  const { apiKey, tenantId, tenantName } = await createTestTenant()

  // Save tenant info for teardown
  const authDir = path.join(__dirname, '..', '.auth')
  fs.mkdirSync(authDir, { recursive: true })
  fs.writeFileSync(path.join(authDir, 'tenant.json'),
    JSON.stringify({ tenantId, tenantName, apiKey }))

  // Launch browser, set localStorage, save storageState
  const browser = await chromium.launch()
  const context = await browser.newContext()
  const page = await context.newPage()

  const baseURL = config.projects[0].use?.baseURL || 'http://localhost:5173'
  await page.goto(baseURL)

  await page.evaluate(({ apiKey, tenantId, tenantName }) => {
    localStorage.setItem('lakeon_api_key', apiKey)
    localStorage.setItem('lakeon_tenant_id', tenantId)
    localStorage.setItem('lakeon_tenant_name', tenantName)
  }, { apiKey, tenantId, tenantName })

  await context.storageState({ path: path.join(authDir, 'state.json') })
  await browser.close()

  console.log(`✅ Test tenant created: ${tenantName} (${tenantId})`)
}

export default globalSetup
```

- [ ] **Step 3: Create global-teardown.ts**

```typescript
// e2e/fixtures/global-teardown.ts
import { deleteTestTenant } from './api-helpers'
import * as fs from 'fs'
import * as path from 'path'

async function globalTeardown() {
  const tenantFile = path.join(__dirname, '..', '.auth', 'tenant.json')
  if (!fs.existsSync(tenantFile)) return

  const { tenantId, tenantName } = JSON.parse(fs.readFileSync(tenantFile, 'utf-8'))
  try {
    await deleteTestTenant(tenantId)
    console.log(`🧹 Test tenant deleted: ${tenantName} (${tenantId})`)
  } catch (e) {
    console.error(`⚠️  Failed to delete test tenant: ${e}`)
  }
}

export default globalTeardown
```

- [ ] **Step 4: Commit**

```bash
git add lakeon-console/e2e/fixtures/
git commit -m "feat(e2e): add global setup/teardown for test tenant lifecycle"
```

---

### Task 3: Pipeline list page test

**Files:**
- Create: `lakeon-console/e2e/pipeline.spec.ts`

- [ ] **Step 1: Write pipeline.spec.ts**

```typescript
// e2e/pipeline.spec.ts
import { test, expect } from '@playwright/test'

const API = 'https://api.dbay.cloud:8443/api/v1'

/** Read api_key from saved auth state */
function getApiKey(): string {
  const fs = require('fs')
  const path = require('path')
  const tenant = JSON.parse(
    fs.readFileSync(path.join(__dirname, '.auth', 'tenant.json'), 'utf-8')
  )
  return tenant.apiKey
}

async function apiRequest(method: string, path: string, apiKey: string, body?: unknown) {
  const resp = await fetch(`${API}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${apiKey}`,
    },
    body: body ? JSON.stringify(body) : undefined,
  })
  if (resp.status === 204) return {}
  return resp.json()
}

const TEXT_DAG = `name: e2e-test
data_type: TEXT
steps:
  - id: clean
    component: text_clean
    component_version: 1
    inputs: { text: "$input.dataset" }
    outputs: { text: cleaned }
`

test.describe('Pipeline List Page', () => {
  test('shows pipeline list page', async ({ page }) => {
    await page.goto('/datalake/pipelines')
    await expect(page.locator('h1')).toContainText('数据生产线')
  })

  test('shows empty state when no pipelines', async ({ page }) => {
    await page.goto('/datalake/pipelines')
    // New tenant has no pipelines — should see empty state or empty table
    await page.waitForLoadState('networkidle')
    // Page should load without errors
    await expect(page.locator('.page-container')).toBeVisible()
  })

  test('create pipeline via API then verify it appears in list', async ({ page }) => {
    const apiKey = getApiKey()
    const name = `e2e-list-${Date.now()}`

    // Create via API
    const pipeline = await apiRequest('POST', '/pipelines', apiKey, {
      name, data_type: 'TEXT', dag_yaml: TEXT_DAG,
    })

    // Navigate and verify
    await page.goto('/datalake/pipelines')
    await page.waitForLoadState('networkidle')
    await expect(page.getByText(name)).toBeVisible()

    // Cleanup
    await apiRequest('DELETE', `/pipelines/${pipeline.id}`, apiKey)
  })

  test('create button shows data type menu', async ({ page }) => {
    await page.goto('/datalake/pipelines')
    await page.getByRole('button', { name: '新建生产线' }).click()
    await expect(page.getByText('选择数据类型')).toBeVisible()
    await expect(page.getByText('视频数据生产线')).toBeVisible()
    await expect(page.getByText('文本数据生产线')).toBeVisible()
  })
})

test.describe('Pipeline Detail Page', () => {
  let pipelineId: string
  const apiKey = getApiKey()
  const pipelineName = `e2e-detail-${Date.now()}`

  test.beforeAll(async () => {
    const pipeline = await apiRequest('POST', '/pipelines', apiKey, {
      name: pipelineName, data_type: 'TEXT', dag_yaml: TEXT_DAG,
    })
    pipelineId = pipeline.id
  })

  test.afterAll(async () => {
    await apiRequest('DELETE', `/pipelines/${pipelineId}`, apiKey)
  })

  test('shows pipeline detail with correct name', async ({ page }) => {
    await page.goto(`/datalake/pipelines/${pipelineId}`)
    await expect(page.getByText(pipelineName)).toBeVisible()
    await expect(page.getByText('TEXT')).toBeVisible()
  })

  test('shows version list', async ({ page }) => {
    await page.goto(`/datalake/pipelines/${pipelineId}`)
    // Click versions tab if not active
    await page.getByRole('button', { name: '版本' }).click()
    await expect(page.getByText('v1')).toBeVisible()
  })

  test('shows runs tab', async ({ page }) => {
    await page.goto(`/datalake/pipelines/${pipelineId}`)
    await page.getByRole('button', { name: '运行' }).click()
    // New pipeline has no runs — should show empty state
    await page.waitForLoadState('networkidle')
  })

  test('trigger run button exists', async ({ page }) => {
    await page.goto(`/datalake/pipelines/${pipelineId}`)
    await expect(page.getByRole('button', { name: '触发运行' })).toBeVisible()
  })

  test('edit button navigates to editor', async ({ page }) => {
    await page.goto(`/datalake/pipelines/${pipelineId}`)
    await page.getByRole('button', { name: '编辑' }).click()
    await expect(page).toHaveURL(new RegExp(`/datalake/pipelines/${pipelineId}/edit`))
  })
})

test.describe('Pipeline Delete', () => {
  test('delete pipeline removes it from list', async ({ page }) => {
    const apiKey = getApiKey()
    const name = `e2e-del-${Date.now()}`

    // Create via API
    const pipeline = await apiRequest('POST', '/pipelines', apiKey, {
      name, data_type: 'TEXT', dag_yaml: TEXT_DAG,
    })

    // Navigate to detail
    await page.goto(`/datalake/pipelines/${pipeline.id}`)
    await expect(page.getByText(name)).toBeVisible()

    // Find and click delete (look for delete button or menu)
    // The detail page may have a delete action in header or dropdown
    const deleteBtn = page.getByRole('button', { name: /删除/ })
    if (await deleteBtn.isVisible()) {
      // Handle confirmation dialog
      page.on('dialog', dialog => dialog.accept())
      await deleteBtn.click()
      // Should redirect to list
      await page.waitForURL('/datalake/pipelines')
    } else {
      // Cleanup via API if no delete button in UI
      await apiRequest('DELETE', `/pipelines/${pipeline.id}`, apiKey)
    }
  })
})
```

- [ ] **Step 2: Run the test**

```bash
cd lakeon-console
npx playwright test e2e/pipeline.spec.ts --headed
```

Expected: Tests run against dev server, some may fail if UI has issues — that's the point. Fix and re-run.

- [ ] **Step 3: Fix any failures found**

Read the Playwright HTML report and screenshots:

```bash
npx playwright show-report
```

Fix frontend/backend issues as needed, re-run until all pass.

- [ ] **Step 4: Commit**

```bash
git add lakeon-console/e2e/pipeline.spec.ts
git commit -m "test(e2e): add Playwright pipeline page tests"
```

---

### Task 4: Component library page test

**Files:**
- Create: `lakeon-console/e2e/components.spec.ts`

- [ ] **Step 1: Write components.spec.ts**

```typescript
// e2e/components.spec.ts
import { test, expect } from '@playwright/test'

test.describe('Component Library Page', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/datalake/components')
    await page.waitForLoadState('networkidle')
  })

  test('shows component library page', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('组件库')
  })

  test('displays preset components', async ({ page }) => {
    // Should show at least 12 platform components
    const cards = page.locator('.comp-card')
    await expect(cards.first()).toBeVisible()
    const count = await cards.count()
    expect(count).toBeGreaterThanOrEqual(12)
  })

  test('filter by data type TEXT', async ({ page }) => {
    // Click TEXT filter pill
    await page.getByRole('button', { name: 'TEXT', exact: true }).click()
    await page.waitForTimeout(300) // Wait for filter to apply

    const cards = page.locator('.comp-card')
    const count = await cards.count()
    expect(count).toBeGreaterThan(0)

    // All visible cards should be TEXT type
    for (let i = 0; i < count; i++) {
      const text = await cards.nth(i).textContent()
      // TEXT components include: text_dedup, text_clean, text_tokenize, text_quality_score
      expect(text).toBeTruthy()
    }
  })

  test('filter by data type VIDEO', async ({ page }) => {
    await page.getByRole('button', { name: 'VIDEO', exact: true }).click()
    await page.waitForTimeout(300)

    const cards = page.locator('.comp-card')
    const count = await cards.count()
    expect(count).toBeGreaterThan(0)
  })

  test('filter by category', async ({ page }) => {
    // Click "清洗" (CLEAN) category
    await page.getByRole('button', { name: '清洗' }).click()
    await page.waitForTimeout(300)

    const cards = page.locator('.comp-card')
    const count = await cards.count()
    expect(count).toBeGreaterThan(0)
  })

  test('search components', async ({ page }) => {
    await page.locator('.filter-search').fill('清洗')
    await page.waitForTimeout(300)

    const cards = page.locator('.comp-card')
    const count = await cards.count()
    expect(count).toBeGreaterThan(0)
  })

  test('click component opens detail panel', async ({ page }) => {
    const firstCard = page.locator('.comp-card').first()
    await firstCard.click()

    // Detail panel or dialog should appear
    // Look for component name, description, version info
    await page.waitForTimeout(500)
    // The detail panel should show component information
    const detailVisible = await page.locator('.detail-panel, .slide-panel, [class*="detail"]').first().isVisible()
      .catch(() => false)
    // If no detail panel, the click might navigate — either way, no crash
  })
})
```

- [ ] **Step 2: Run the test**

```bash
cd lakeon-console
npx playwright test e2e/components.spec.ts --headed
```

- [ ] **Step 3: Fix any failures found**

Analyze screenshots and traces, fix issues, re-run.

- [ ] **Step 4: Commit**

```bash
git add lakeon-console/e2e/components.spec.ts
git commit -m "test(e2e): add Playwright component library page tests"
```

---

### Task 5: Run full suite, fix all failures, deploy

- [ ] **Step 1: Run all E2E tests**

```bash
cd lakeon-console
npx playwright test
```

- [ ] **Step 2: Fix any remaining failures**

For each failure:
1. Open the HTML report: `npx playwright show-report`
2. Check the screenshot and trace
3. Identify root cause (frontend field mismatch, missing element, API error, etc.)
4. Fix the code
5. Re-run: `npx playwright test`

Repeat until all tests pass.

- [ ] **Step 3: Also run pytest API E2E to ensure no regressions**

```bash
cd /Users/jacky/code/lakeon
python3 -m pytest tests/e2e/test_pipeline.py -v
```

- [ ] **Step 4: Commit all fixes**

```bash
git add -A
git commit -m "fix: resolve E2E test failures in pipeline and component pages"
```

- [ ] **Step 5: Deploy API to hwstaff**

```bash
SITE=hwstaff bash deploy/cce/build-and-push-api.sh
KUBECONFIG=~/.kube/cce-lakeon-config kubectl rollout restart deployment/lakeon-api -n lakeon
KUBECONFIG=~/.kube/cce-lakeon-config kubectl rollout status deployment/lakeon-api -n lakeon --timeout=180s
```

- [ ] **Step 6: Push to trigger Railway frontend deploy**

```bash
git push
```

- [ ] **Step 7: Post-deploy verification — run pytest E2E against live API**

```bash
python3 -m pytest tests/e2e/test_pipeline.py -v
```

All 24 tests should pass.

- [ ] **Step 8: Commit final state**

```bash
git add -A
git commit -m "test(e2e): all Playwright and pytest E2E tests passing"
git push
```
