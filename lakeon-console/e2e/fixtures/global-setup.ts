import type { FullConfig } from '@playwright/test'
import { createTestTenant } from './api-helpers'
import * as fs from 'fs'
import * as path from 'path'
import { fileURLToPath } from 'url'

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)

async function globalSetup(config: FullConfig) {
  const { apiKey, tenantId, tenantName } = await createTestTenant()

  // Save tenant info for teardown
  const authDir = path.join(__dirname, '..', '.auth')
  fs.mkdirSync(authDir, { recursive: true })
  fs.writeFileSync(path.join(authDir, 'tenant.json'),
    JSON.stringify({ tenantId, tenantName, apiKey }))

  const baseURL = config.projects[0]?.use?.baseURL || 'http://localhost:5173'
  const origin = new URL(baseURL).origin
  fs.writeFileSync(
    path.join(authDir, 'state.json'),
    JSON.stringify({
      cookies: [],
      origins: [
        {
          origin,
          localStorage: [
            { name: 'lakeon_api_key', value: apiKey },
            { name: 'lakeon_tenant_id', value: tenantId },
            { name: 'lakeon_tenant_name', value: tenantName },
          ],
        },
      ],
    }, null, 2),
  )

  console.log(`Test tenant created: ${tenantName} (${tenantId})`)
}

export default globalSetup
