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
      { path: 'apikey', name: 'ApiKey', component: () => import('../views/apikey/ApiKeyView.vue') },
      { path: 'docs', name: 'Docs', component: () => import('../views/docs/DocsView.vue') },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  if (!to.meta.noAuth) {
    const apiKey = localStorage.getItem('lakeon_api_key')
    if (!apiKey) {
      return '/landing'
    }
  }
})

export default router
