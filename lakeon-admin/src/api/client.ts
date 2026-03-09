import axios from 'axios'

// Use nginx reverse proxy by default (relative URL).
// If proxy returns 502/503/504, the interceptor will retry via direct API.
const PROXY_BASE = '/api/v1/admin'
const DIRECT_BASE = 'https://api.dbay.cloud:8443/api/v1/admin'

const client = axios.create({
  baseURL: PROXY_BASE,
  timeout: 30000,
})

client.interceptors.request.use((config) => {
  const token = localStorage.getItem('lakeon_admin_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

client.interceptors.response.use(
  (response) => response,
  async (error) => {
    const config = error.config
    // If proxy failed (502/503/504), retry once via direct API URL
    if (!config._retried && config.baseURL === PROXY_BASE
        && (error.response?.status === 502 || error.response?.status === 503
            || error.response?.status === 504 || !error.response)) {
      config._retried = true
      config.baseURL = DIRECT_BASE
      return client.request(config)
    }
    if (error.response?.status === 403) {
      localStorage.removeItem('lakeon_admin_token')
      window.location.href = '/login'
    }
    if (error.response?.status === 503 || !error.response) {
      error.isApiOffline = true
      error.offlineMessage = error.response?.data?.error?.message
        || 'DBay API is currently offline. The service may be in maintenance mode.'
    }
    return Promise.reject(error)
  }
)

export default client
