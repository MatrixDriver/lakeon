const API_BASE = 'https://api.dbay.cloud:8443/api/v1'
const ADMIN_TOKEN = process.env.DBAY_ADMIN_TOKEN || 'lakeon-sre-2026'

process.env.NODE_TLS_REJECT_UNAUTHORIZED = process.env.NODE_TLS_REJECT_UNAUTHORIZED || '0'

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
  await apiRequest('DELETE', '/admin/tenants/batch', { ids: [tenantId] }, ADMIN_TOKEN)
}

export { apiRequest, API_BASE }
