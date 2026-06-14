import { defineStore } from 'pinia'
import { ref } from 'vue'
import client from '../api/client'
import { tenantApi } from '../api/tenant'

export const useAuthStore = defineStore('auth', () => {
  const apiKey = ref(localStorage.getItem('lakeon_api_key') || '')
  const tenantId = ref(localStorage.getItem('lakeon_tenant_id') || '')
  const tenantName = ref(localStorage.getItem('lakeon_tenant_name') || '')
  const username = ref(localStorage.getItem('lakeon_username') || '')

  async function login(loginUsername: string, password: string): Promise<{ ok: boolean; error?: string }> {
    try {
      const res = await client.post('/auth/login', { username: loginUsername, password })
      const tenant = res.data
      const key = tenant?.api_key
      if (!key) return { ok: false, error: '登录失败' }

      apiKey.value = key
      localStorage.setItem('lakeon_api_key', key)
      if (tenant?.id) {
        setTenant(tenant.id, tenant.name || '')
      }
      if (tenant?.username) {
        username.value = tenant.username
        localStorage.setItem('lakeon_username', tenant.username)
      }
      return { ok: true }
    } catch (e: any) {
      if (e.response?.status === 401) {
        return { ok: false, error: '用户名或密码错误' }
      }
      return { ok: false, error: '网络错误，请稍后重试' }
    }
  }

  async function loginWithOAuthCode(code: string): Promise<{ ok: boolean; error?: string }> {
    try {
      const res = await tenantApi.oauthExchangeToken(code)
      const tenant = res.data
      const key = tenant?.api_key
      if (!key) return { ok: false, error: 'OAuth 登录失败' }

      apiKey.value = key
      localStorage.setItem('lakeon_api_key', key)
      if (tenant?.id) {
        setTenant(tenant.id, tenant.name || '')
      }
      if (tenant?.username) {
        username.value = tenant.username
        localStorage.setItem('lakeon_username', tenant.username)
      }
      return { ok: true }
    } catch (e: any) {
      if (e.response?.status === 401) {
        return { ok: false, error: '授权码无效或已过期' }
      }
      return { ok: false, error: '网络错误，请稍后重试' }
    }
  }

  function setTenant(id: string, name: string) {
    tenantId.value = id
    tenantName.value = name
    localStorage.setItem('lakeon_tenant_id', id)
    localStorage.setItem('lakeon_tenant_name', name)
  }

  function logout() {
    apiKey.value = ''
    tenantId.value = ''
    tenantName.value = ''
    username.value = ''
    localStorage.removeItem('lakeon_api_key')
    localStorage.removeItem('lakeon_tenant_id')
    localStorage.removeItem('lakeon_tenant_name')
    localStorage.removeItem('lakeon_username')
  }

  return { apiKey, tenantId, tenantName, username, login, loginWithOAuthCode, setTenant, logout }
})
