import axios from 'axios'

const client = axios.create({
  baseURL: 'https://api.dbay.cloud:8443/api/v1/admin',
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
  (error) => {
    if (error.response?.status === 403) {
      localStorage.removeItem('lakeon_admin_token')
      window.location.href = '/login'
    }
    if (error.response?.status === 503 || !error.response) {
      error.isApiOffline = true
      error.offlineMessage = error.response?.data?.error?.message
        || 'Lakeon API is currently offline. The service may be in maintenance mode.'
    }
    return Promise.reject(error)
  }
)

export default client
