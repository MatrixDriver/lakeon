import { deleteTestTenant } from './api-helpers'
import * as fs from 'fs'
import * as path from 'path'
import { fileURLToPath } from 'url'

const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)

async function globalTeardown() {
  const tenantFile = path.join(__dirname, '..', '.auth', 'tenant.json')
  if (!fs.existsSync(tenantFile)) return

  const { tenantId, tenantName } = JSON.parse(fs.readFileSync(tenantFile, 'utf-8'))
  try {
    await deleteTestTenant(tenantId)
    console.log(`Test tenant deleted: ${tenantName} (${tenantId})`)
  } catch (e) {
    console.error(`Failed to delete test tenant: ${e}`)
  }
}

export default globalTeardown
