import axios from 'axios'

const client = axios.create({
  baseURL: '/api/v1',
  timeout: 90000,
})

client.interceptors.request.use((config) => {
  const apiKey = localStorage.getItem('lakeon_api_key')
  if (apiKey) {
    config.headers.Authorization = `Bearer ${apiKey}`
  }
  return config
})

// Use localStorage directly instead of Pinia store to avoid circular dependency
// (auth store imports client, client cannot import auth store)
const PUBLIC_PATH_PREFIXES = ['/login', '/ext-login', '/ext-callback', '/oauth', '/integrations', '/blog', '/docs', '/product']

client.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      const path = window.location.pathname
      const isPublic = path === '/' || PUBLIC_PATH_PREFIXES.some(p => path.startsWith(p))
      // Clear stale auth regardless of where we are
      localStorage.removeItem('lakeon_api_key')
      localStorage.removeItem('lakeon_tenant_id')
      localStorage.removeItem('lakeon_tenant_name')
      // Bounce non-public pages back to landing — users with expired tokens
      // should land on the marketing page (with localStorage cleared so the
      // router guard no longer auto-redirects to /dashboard), not be forced
      // into the login form.
      if (!isPublic) {
        window.location.href = '/'
      }
    }
    // API offline (503 from nginx proxy or network error)
    if (error.response?.status === 503 || !error.response) {
      error.isApiOffline = true
      error.offlineMessage = error.response?.data?.error?.message
        || 'DBay API is currently offline. The service may be in maintenance mode.'
    }
    return Promise.reject(error)
  }
)

export default client
