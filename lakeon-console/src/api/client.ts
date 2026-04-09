import axios from 'axios'

const client = axios.create({
  baseURL: 'https://api.dbay.cloud:8443/api/v1',
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
client.interceptors.response.use(
  (response) => response,
  (error) => {
    const publicPaths = ['/login', '/landing', '/ext-login', '/ext-callback', '/integrations', '/blog', '/docs', '/product']
    if (error.response?.status === 401 && !publicPaths.some(p => window.location.pathname.startsWith(p))) {
      localStorage.removeItem('lakeon_api_key')
      localStorage.removeItem('lakeon_tenant_id')
      localStorage.removeItem('lakeon_tenant_name')
      window.location.href = '/login'
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
