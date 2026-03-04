import { defineStore } from 'pinia'
import { ref } from 'vue'
import client from '../api/client'

export const useAuthStore = defineStore('auth', () => {
  const apiKey = ref(localStorage.getItem('lakeon_api_key') || '')
  const tenantId = ref(localStorage.getItem('lakeon_tenant_id') || '')
  const tenantName = ref(localStorage.getItem('lakeon_tenant_name') || '')

  async function login(key: string): Promise<boolean> {
    try {
      await client.get('/databases', {
        headers: { Authorization: `Bearer ${key}` },
      })
      apiKey.value = key
      localStorage.setItem('lakeon_api_key', key)
      return true
    } catch {
      return false
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
