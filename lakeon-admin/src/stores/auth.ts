import { defineStore } from 'pinia'
import { ref } from 'vue'
import client from '../api/client'

export const useAdminAuthStore = defineStore('adminAuth', () => {
  const token = ref(localStorage.getItem('lakeon_admin_token') || '')

  async function login(adminToken: string): Promise<boolean> {
    try {
      await client.get('/dashboard', {
        headers: { Authorization: `Bearer ${adminToken}` },
      })
      token.value = adminToken
      localStorage.setItem('lakeon_admin_token', adminToken)
      return true
    } catch {
      return false
    }
  }

  function logout() {
    token.value = ''
    localStorage.removeItem('lakeon_admin_token')
  }

  return { token, login, logout }
})
