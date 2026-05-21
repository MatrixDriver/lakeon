import { test, expect } from '@playwright/test'
import * as fs from 'fs'
import * as path from 'path'
import { fileURLToPath } from 'url'

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)

const API = 'https://api.dbay.cloud:8443/api/v1'

function getAuth(): { apiKey: string; tenantId: string; tenantName: string } {
  return JSON.parse(
    fs.readFileSync(path.join(__dirname, '.auth', 'tenant.json'), 'utf-8'),
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
  if (!resp.ok) {
    throw new Error(`API ${method} ${urlPath} → ${resp.status}: ${JSON.stringify(data)}`)
  }
  return data
}

interface TestDb {
  id: string
  name: string
}

async function createDatabase(apiKey: string, name: string): Promise<TestDb> {
  const db = await apiRequest('POST', '/databases', apiKey, { name })
  // Allow the storage controller a moment to provision so PITR window has data
  await new Promise((r) => setTimeout(r, 3000))
  return { id: db.id, name: db.name }
}

async function deleteDatabase(apiKey: string, id: string) {
  await apiRequest('DELETE', `/databases/${id}`, apiKey).catch(() => {})
}

// ─── Tests ───

test.describe('PITR Restore Flow', () => {
  test('user can restore a database to a past time', async ({ page }) => {
    const { apiKey } = getAuth()
    const name = `pw-restore-${Date.now()}`
    const db = await createDatabase(apiKey, name)
    const newDbName = `${name}_restored_playwright`

    try {
      await page.goto(`/databases/${db.id}`)
      await expect(page.locator('.breadcrumb').getByText(db.name)).toBeVisible({
        timeout: 15000,
      })

      // Open the restore dialog
      await page.getByTestId('open-restore-dialog').click()
      await expect(page.getByTestId('restore-dialog')).toBeVisible()

      // The dialog auto-fills target-time to the latest available PITR point
      // once the window loads. Wait until the input has a value so confirm is enabled.
      const targetTime = page.getByTestId('target-time')
      await expect(targetTime).not.toHaveValue('', { timeout: 15000 })

      // Fill in the new database name
      await page.getByTestId('new-db-name').fill(newDbName)

      // Submit the restore
      await page.getByTestId('confirm-restore').click()

      // Should redirect to the newly created database detail page
      await expect(page).toHaveURL(/\/databases\/db_[a-zA-Z0-9]+/, { timeout: 30000 })
      await expect(page.locator('.breadcrumb').getByText(newDbName)).toBeVisible({
        timeout: 15000,
      })
    } finally {
      // Best-effort cleanup: delete the source DB. The restored DB will be
      // reaped when the e2e tenant is torn down in globalTeardown.
      await deleteDatabase(apiKey, db.id)
    }
  })

  test('shows error when target_time is outside window', async ({ page }) => {
    const { apiKey } = getAuth()
    const name = `pw-restore-err-${Date.now()}`
    const db = await createDatabase(apiKey, name)

    try {
      await page.goto(`/databases/${db.id}`)
      await expect(page.locator('.breadcrumb').getByText(db.name)).toBeVisible({
        timeout: 15000,
      })

      await page.getByTestId('open-restore-dialog').click()
      await expect(page.getByTestId('restore-dialog')).toBeVisible()

      // Override the auto-filled latest time with a clearly out-of-window value
      const targetTime = page.getByTestId('target-time')
      await expect(targetTime).toBeVisible()
      await targetTime.fill('2020-01-01T00:00')

      await page.getByTestId('new-db-name').fill('should_fail')

      // Trigger the submit attempt. Either the client-side range check blocks
      // submission and surfaces an error/warning, or the API rejects the
      // out-of-range request — both paths must surface [data-testid=error]
      // to the user.
      await page.getByTestId('confirm-restore').click({ force: true })

      await expect(page.getByTestId('error')).toBeVisible({ timeout: 15000 })
    } finally {
      await deleteDatabase(apiKey, db.id)
    }
  })
})
