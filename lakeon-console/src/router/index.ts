import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/login/LoginView.vue'),
    meta: { noAuth: true },
  },
  {
    path: '/landing',
    name: 'Landing',
    component: () => import('../views/landing/LandingView.vue'),
    meta: { noAuth: true },
  },
  {
    path: '/',
    component: () => import('../layouts/ConsoleLayout.vue'),
    children: [
      { path: '', redirect: '/dashboard' },
      { path: 'dashboard', name: 'Dashboard', component: () => import('../views/dashboard/DashboardView.vue') },
      { path: 'databases', name: 'DatabaseList', component: () => import('../views/database/DatabaseList.vue') },
      { path: 'databases/:id', name: 'DatabaseDetail', component: () => import('../views/database/DatabaseDetail.vue') },
      { path: 'databases/:id/manager', name: 'DatabaseManager', component: () => import('../views/database/DatabaseManager.vue') },
      { path: 'monitor', name: 'Monitor', component: () => import('../views/monitor/MonitorView.vue') },
      { path: 'audit', name: 'Audit', component: () => import('../views/audit/AuditView.vue') },
      { path: 'apikey', name: 'ApiKey', component: () => import('../views/apikey/ApiKeyView.vue') },
      { path: 'docs', name: 'Docs', component: () => import('../views/docs/DocsView.vue') },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior(to) {
    if (to.hash) {
      return new Promise((resolve) => {
        setTimeout(() => {
          resolve({ el: to.hash, behavior: 'instant' })
        }, 300)
      })
    }
    return { top: 0 }
  },
})

router.beforeEach((to) => {
  if (!to.meta.noAuth) {
    const apiKey = localStorage.getItem('lakeon_api_key')
    if (!apiKey) {
      return '/landing'
    }
  }
})

// When lazy-loaded chunks fail (e.g. after a new deploy changes chunk hashes),
// reload the page to fetch the latest assets
router.onError((error, to) => {
  if (
    error.message.includes('Failed to fetch dynamically imported module') ||
    error.message.includes('Importing a module script failed') ||
    error.message.includes('Loading chunk') ||
    error.message.includes('Loading CSS chunk')
  ) {
    // Prevent infinite reload loop
    const key = 'last_chunk_reload'
    const last = sessionStorage.getItem(key)
    const now = Date.now()
    if (!last || now - Number(last) > 10000) {
      sessionStorage.setItem(key, String(now))
      window.location.assign(to.fullPath)
    }
  }
})

export default router
