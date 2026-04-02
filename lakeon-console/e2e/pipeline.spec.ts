import { test, expect } from '@playwright/test'
import * as fs from 'fs'
import * as path from 'path'
import { fileURLToPath } from 'url'

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)

const API = 'https://api.dbay.cloud:8443/api/v1'

function getAuth(): { apiKey: string; tenantId: string; tenantName: string } {
  return JSON.parse(
    fs.readFileSync(path.join(__dirname, '.auth', 'tenant.json'), 'utf-8')
  )
}

async function apiRequest(method: string, urlPath: string, apiKey: string, body?: unknown) {
  const resp = await fetch(`${API}${urlPath}`, {
    method,
    headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${apiKey}` },
    body: body ? JSON.stringify(body) : undefined,
  })
  if (resp.status === 204) return {}
  const data = await resp.json()
  if (!resp.ok) throw new Error(`API ${method} ${urlPath} → ${resp.status}: ${JSON.stringify(data)}`)
  return data
}

const MINIMAL_DAG = `name: e2e-test
data_type: TEXT
steps:
  - id: clean
    component: text_clean
    component_version: 1
    inputs: { text: "$input.dataset" }
    outputs: { text: cleaned }
`

async function createTestPipeline(apiKey: string, name: string, dataType = 'TEXT') {
  return apiRequest('POST', '/pipelines', apiKey, {
    name,
    data_type: dataType,
    dag_yaml: MINIMAL_DAG.replace('data_type: TEXT', `data_type: ${dataType}`),
  })
}

async function deleteTestPipeline(apiKey: string, id: string) {
  return apiRequest('DELETE', `/pipelines/${id}`, apiKey)
}

// ─── Tests ───

test.describe('Pipeline list page', () => {
  test('renders with correct title', async ({ page }) => {
    await page.goto('/datalake/pipelines')
    await expect(page.locator('h1.page-title')).toHaveText('数据生产线')
  })

  test('empty state renders without crash for new tenant', async ({ page }) => {
    await page.goto('/datalake/pipelines')
    await page.waitForLoadState('networkidle')
    await expect(page.locator('.page-container')).toBeVisible()
  })

  test('create pipeline via API shows in list', async ({ page }) => {
    const { apiKey } = getAuth()
    const pipelineName = `e2e-list-${Date.now()}`
    const created = await createTestPipeline(apiKey, pipelineName)

    try {
      await page.goto('/datalake/pipelines')
      await expect(page.getByText(pipelineName)).toBeVisible({ timeout: 10000 })
    } finally {
      await deleteTestPipeline(apiKey, created.id)
    }
  })

  test('"新建生产线" button shows data type menu', async ({ page }) => {
    await page.goto('/datalake/pipelines')
    await page.waitForLoadState('networkidle')

    await page.getByRole('button', { name: '新建生产线' }).click()

    await expect(page.locator('.create-menu')).toBeVisible()
    await expect(page.getByText('选择数据类型')).toBeVisible()
    await expect(page.getByText('视频数据生产线')).toBeVisible()
    await expect(page.getByText('文本数据生产线')).toBeVisible()
  })

  test('delete pipeline removes it from list', async ({ page }) => {
    const { apiKey } = getAuth()
    const pipelineName = `e2e-delete-${Date.now()}`
    const created = await createTestPipeline(apiKey, pipelineName)

    await page.goto('/datalake/pipelines')
    await expect(page.getByText(pipelineName)).toBeVisible({ timeout: 10000 })

    // Switch to table view
    await page.locator('button[title="表格视图"]').click()

    // Accept the confirm dialog
    page.on('dialog', dialog => dialog.accept())

    // Click delete button in the row
    const row = page.locator('tr', { has: page.getByText(pipelineName) })
    await row.getByText('删除').click()

    // Pipeline should disappear
    await expect(page.getByText(pipelineName)).toBeHidden({ timeout: 10000 })
  })
})

test.describe('Pipeline detail page', () => {
  // Each test creates its own pipeline to avoid shared state issues with retries
  async function withPipeline(fn: (pipelineId: string, pipelineName: string) => Promise<void>) {
    const { apiKey } = getAuth()
    const name = `e2e-detail-${Date.now()}`
    const created = await createTestPipeline(apiKey, name)
    try {
      await fn(created.id, name)
    } finally {
      await deleteTestPipeline(apiKey, created.id).catch(() => {})
    }
  }

  test('shows correct name and data type', async ({ page }) => {
    await withPipeline(async (id, name) => {
      await page.goto(`/datalake/pipelines/${id}`)
      // Wait for the pipeline name to appear in the breadcrumb
      await expect(page.locator('.breadcrumb').getByText(name)).toBeVisible({ timeout: 15000 })
      await expect(page.locator('.meta-tag')).toHaveText('TEXT')
    })
  })

  test('has breadcrumb linking back to list', async ({ page }) => {
    await withPipeline(async (id) => {
      await page.goto(`/datalake/pipelines/${id}`)
      await expect(page.locator('.breadcrumb-link')).toHaveText('数据生产线')
    })
  })

  test('versions tab shows v1', async ({ page }) => {
    await withPipeline(async (id) => {
      await page.goto(`/datalake/pipelines/${id}`)
      // Wait for page to fully load
      await page.waitForLoadState('networkidle')

      // Click the versions tab
      await page.getByRole('button', { name: '版本列表' }).click()

      // Should show v1 in the table
      await expect(page.locator('.data-table').getByText('v1')).toBeVisible({ timeout: 10000 })
    })
  })

  test('runs tab works (may be empty)', async ({ page }) => {
    await withPipeline(async (id) => {
      await page.goto(`/datalake/pipelines/${id}`)
      await page.waitForLoadState('networkidle')

      // Default active tab is "运行历史"
      const runsTab = page.getByRole('button', { name: '运行历史' })
      await runsTab.click()

      // Should see either runs or the empty hint
      await expect(
        page.getByText('暂无运行记录').or(page.locator('.data-table tbody tr'))
      ).toBeVisible({ timeout: 10000 })
    })
  })

  test('"触发运行" button is visible', async ({ page }) => {
    await withPipeline(async (id) => {
      await page.goto(`/datalake/pipelines/${id}`)
      await expect(page.getByRole('button', { name: '触发运行' })).toBeVisible({ timeout: 10000 })
    })
  })

  test('"编辑" button navigates to edit page', async ({ page }) => {
    await withPipeline(async (id) => {
      await page.goto(`/datalake/pipelines/${id}`)
      await page.waitForLoadState('networkidle')
      await page.getByRole('button', { name: '编辑' }).click()
      await expect(page).toHaveURL(new RegExp(`/datalake/pipelines/${id}/edit`))
    })
  })
})
