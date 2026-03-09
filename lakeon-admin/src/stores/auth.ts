import { defineStore } from 'pinia'
import { ref } from 'vue'
import client from '../api/client'

export const useAdminAuthStore = defineStore('adminAuth', () => {
  const token = ref(localStorage.getItem('lakeon_admin_token') || '')

  async function login(adminToken: string): Promise<{ ok: boolean; error?: string }> {
    try {
      await client.get('/dashboard', {
        headers: { Authorization: `Bearer ${adminToken}` },
      })
      token.value = adminToken
      localStorage.setItem('lakeon_admin_token', adminToken)
      return { ok: true }
    } catch (e: any) {
      if (e.response?.status === 403) {
        return { ok: false, error: 'Admin Token 无效，请检查后重试' }
      }
      if (e.isApiOffline) {
        return { ok: false, error: e.offlineMessage || 'API 服务离线，请稍后重试' }
      }
      return { ok: false, error: '网络连接失败，请检查网络后重试' }
    }
  }

  function logout() {
    token.value = ''
    localStorage.removeItem('lakeon_admin_token')
  }

  return { token, login, logout }
})
