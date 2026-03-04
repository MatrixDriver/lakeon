import axios from 'axios'

const client = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
})

client.interceptors.request.use((config) => {
  const apiKey = localStorage.getItem('lakeon_api_key')
  if (apiKey) {
    config.headers.Authorization = `Bearer ${apiKey}`
  }
  return config
})

client.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('lakeon_api_key')
      localStorage.removeItem('lakeon_tenant_id')
      localStorage.removeItem('lakeon_tenant_name')
      window.location.href = '/login'
    }
    return Promise.reject(error)
  }
)

export default client
