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
    path: '/report',
    name: 'Report',
    component: () => import('../views/report/ReportView.vue'),
    meta: { noAuth: true },
  },
  {
    path: '/',
    component: () => import('../layouts/ConsoleLayout.vue'),
    children: [
      { path: '', redirect: '/dashboard' },
      { path: 'dashboard', name: 'Dashboard', component: () => import('../views/dashboard/DashboardView.vue') },
      { path: 'databases', redirect: '/dashboard' },
      { path: 'databases/:id', name: 'DatabaseDetail', component: () => import('../views/database/DatabaseDetail.vue') },
      { path: 'databases/:id/manager', name: 'DatabaseManager', component: () => import('../views/database/DatabaseManager.vue') },
      { path: 'timetravel', name: 'TimeTravel', component: () => import('../views/timetravel/TimeTravelView.vue') },
      { path: 'sql', name: 'SqlEditor', component: () => import('../views/sql/SqlEditorEntry.vue') },
      { path: 'import', name: 'Import', component: () => import('../views/import/ImportEntry.vue') },
      { path: 'monitor', name: 'Monitor', component: () => import('../views/monitor/MonitorView.vue') },
      { path: 'logs', name: 'LogManagement', component: () => import('../views/log-management/LogManagement.vue') },
      { path: 'backups', name: 'BackupManagement', component: () => import('../views/backup/BackupManagement.vue') },
      { path: 'audit', redirect: '/logs' },
      { path: 'apikey', name: 'ApiKey', component: () => import('../views/apikey/ApiKeyView.vue') },
      { path: 'usage', name: 'Usage', component: () => import('../views/usage/UsageBillingView.vue') },
      { path: 'account', name: 'Account', component: () => import('../views/account/AccountSettingsView.vue') },
      { path: 'docs', name: 'Docs', component: () => import('../views/docs/DocsView.vue') },
      // Datalake
      { path: 'datalake', name: 'DatalakeJobs', component: () => import('../views/datalake/DatalakeJobs.vue') },
      { path: 'datalake/jobs/:jobId', name: 'DatalakeJobDetail', component: () => import('../views/datalake/DatalakeJobDetail.vue') },
      { path: 'datalake/datasets', name: 'DatalakeDatasets', component: () => import('../views/datalake/DatalakeDatasets.vue') },
      { path: 'datalake/datasets/new', name: 'DatalakeDatasetNew', component: () => import('../views/datalake/DatalakeDatasetNew.vue') },
      { path: 'datalake/datasets/:id', name: 'DatalakeDatasetDetail', component: () => import('../views/datalake/DatalakeDatasetDetail.vue') },
      // Knowledge
      { path: 'knowledge', name: 'KnowledgeBases', component: () => import('../views/knowledge/KnowledgeBases.vue') },
      { path: 'knowledge/:kbId', name: 'KnowledgeBaseDetail', component: () => import('../views/knowledge/KnowledgeBaseDetail.vue') },
      { path: 'knowledge/:kbId/documents/:docId', name: 'DocumentDetail', component: () => import('../views/knowledge/DocumentDetail.vue') },
      { path: 'knowledge/datasources', name: 'KnowledgeDataSources', component: () => import('../views/knowledge/KnowledgeDataSources.vue') },
      { path: 'knowledge/search', name: 'KnowledgeSearch', component: () => import('../views/knowledge/KnowledgeSearch.vue') },
      // Memory
      { path: 'memory', name: 'MemoryBases', component: () => import('../views/memory/MemoryBases.vue') },
      { path: 'memory/:memId', name: 'MemoryBaseDetail', component: () => import('../views/memory/MemoryBaseDetail.vue') },
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
