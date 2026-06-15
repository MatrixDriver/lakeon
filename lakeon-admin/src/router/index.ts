import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/login/LoginView.vue'),
    meta: { noAuth: true },
  },
  {
    path: '/',
    component: () => import('../layouts/AdminLayout.vue'),
    children: [
      { path: '', redirect: '/databases' },
      { path: 'dashboard', redirect: '/databases' },
      { path: 'tenants', name: 'TenantList', component: () => import('../views/tenants/TenantList.vue') },
      { path: 'invite-codes', name: 'InviteCodes', component: () => import('../views/tenants/InviteCodes.vue') },
      { path: 'databases', name: 'DatabaseList', component: () => import('../views/databases/DatabaseList.vue') },
      { path: 'databases/:id', name: 'DatabaseDetail', component: () => import('../views/databases/DatabaseDetail.vue') },
      { path: 'operations', name: 'OperationList', component: () => import('../views/operations/OperationList.vue') },
      { path: 'system', name: 'SystemHealth', component: () => import('../views/system/SystemHealth.vue') },
      { path: 'metrics', name: 'MetricsView', component: () => import('../views/system/MetricsView.vue') },
      { path: 'logs', name: 'LogDiagnostics', component: () => import('../views/logs/LogDiagnostics.vue') },
      { path: 'logs/trace/:requestId', name: 'LogTraceDetail', component: () => import('../views/logs/LogDiagnostics.vue'), props: true },
      { path: 'alerts', name: 'AlertsView', component: () => import('../views/system/AlertsView.vue') },
      { path: 'infra', name: 'InfraMonitor', component: () => import('../views/system/InfraMonitor.vue') },
      { path: 'cost', name: 'CostView', component: () => import('../views/cost/CostView.vue') },
      { path: 'cloud', name: 'CloudView', component: () => import('../views/cloud/CloudView.vue') },
      { path: 'audit', name: 'AuditLogs', component: () => import('../views/AuditLogs.vue') },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  if (!to.meta.noAuth) {
    const token = localStorage.getItem('lakeon_admin_token')
    if (!token) {
      return '/login'
    }
  }
})

export default router
