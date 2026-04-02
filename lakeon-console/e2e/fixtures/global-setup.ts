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

  const baseURL = config.projects[0]?.use?.baseURL || 'http://localhost:5173'
  await page.goto(baseURL)

  await page.evaluate(({ apiKey, tenantId, tenantName }) => {
    localStorage.setItem('lakeon_api_key', apiKey)
    localStorage.setItem('lakeon_tenant_id', tenantId)
    localStorage.setItem('lakeon_tenant_name', tenantName)
  }, { apiKey, tenantId, tenantName })

  await context.storageState({ path: path.join(authDir, 'state.json') })
  await browser.close()

  console.log(`Test tenant created: ${tenantName} (${tenantId})`)
}

export default globalSetup
