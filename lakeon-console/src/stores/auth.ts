import { defineStore } from 'pinia'
import { ref } from 'vue'
import client from '../api/client'

export const useAuthStore = defineStore('auth', () => {
  const apiKey = ref(localStorage.getItem('lakeon_api_key') || '')
  const tenantId = ref(localStorage.getItem('lakeon_tenant_id') || '')
  const tenantName = ref(localStorage.getItem('lakeon_tenant_name') || '')

  async function login(username: string, password: string): Promise<{ ok: boolean; error?: string }> {
    try {
      const res = await client.post('/auth/login', { username, password })
      const tenant = res.data
      const key = tenant?.api_key
      if (!key) return { ok: false, error: '登录失败' }

      apiKey.value = key
      localStorage.setItem('lakeon_api_key', key)
      if (tenant?.id) {
        setTenant(tenant.id, tenant.name || '')
      }
      return { ok: true }
    } catch (e: any) {
      if (e.response?.status === 401) {
        return { ok: false, error: '用户名或密码错误' }
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
    localStorage.removeItem('lakeon_api_key')
    localStorage.removeItem('lakeon_tenant_id')
    localStorage.removeItem('lakeon_tenant_name')
  }

  return { apiKey, tenantId, tenantName, login, setTenant, logout }
})
