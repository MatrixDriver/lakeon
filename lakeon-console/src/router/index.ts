import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/login/LoginView.vue'),
    meta: { noAuth: true },
  },
  {
    path: '/report',
    name: 'Report',
    component: () => import('../views/report/ReportView.vue'),
    meta: { noAuth: true },
  },
  {
    path: '/ext-login',
    name: 'ExtLogin',
    component: () => import('../views/login/ExtLoginView.vue'),
    meta: { noAuth: true },
  },
  {
    path: '/ext-callback',
    name: 'ExtCallback',
    component: () => import('../views/login/ExtCallbackView.vue'),
    meta: { noAuth: true },
  },
  {
    path: '/oauth/callback',
    name: 'OAuthCallback',
    component: () => import('../views/login/OAuthCallbackView.vue'),
    meta: { noAuth: true },
  },
  {
    path: '/',
    component: () => import('../layouts/PublicLayout.vue'),
    meta: { noAuth: true },
    children: [
      { path: '', name: 'Landing', component: () => import('../views/landing/LandingView.vue') },
      { path: 'landing', redirect: '/' },
      { path: 'product', name: 'Product', component: () => import('../views/product/ProductView.vue') },
      { path: 'product/lakebase', name: 'ProductLakebase', component: () => import('../views/product/ProductLakebaseView.vue') },
      { path: 'product/knowledge', name: 'ProductKnowledge', component: () => import('../views/product/ProductKnowledgeView.vue') },
      { path: 'product/memory', name: 'ProductMemory', component: () => import('../views/product/ProductMemoryView.vue') },
      { path: 'product/datalake', name: 'ProductDatalake', component: () => import('../views/product/ProductDatalakeView.vue') },
      { path: 'integrations', name: 'Integrations', component: () => import('../views/integrations/IntegrationsView.vue') },
      { path: 'integrations/openclaw', name: 'OpenClaw', component: () => import('../views/integrations/OpenClawView.vue') },
      { path: 'blog', name: 'BlogList', component: () => import('../views/blog/BlogListView.vue') },
      { path: 'blog/:slug', name: 'BlogPost', component: () => import('../views/blog/BlogPostView.vue') },
      {
        path: 'docs',
        component: () => import('../views/docs/DocsLayout.vue'),
        children: [
          { path: '', name: 'DocsHome', component: () => import('../views/docs/DocsHome.vue') },
          { path: 'console', name: 'DocsConsole', component: () => import('../views/docs/DocsConsole.vue') },
          { path: 'cli', name: 'DocsCli', component: () => import('../views/docs/DocsCli.vue') },
          { path: 'mcp', name: 'DocsMcp', component: () => import('../views/docs/DocsMcp.vue') },
          { path: 'rest-api', name: 'DocsRestApi', component: () => import('../views/docs/DocsRestApi.vue') },
          { path: 'python-sdk', name: 'DocsPythonSdk', component: () => import('../views/docs/DocsPythonSdk.vue') },
        ],
      },
    ],
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
      { path: 'connectors', name: 'Connectors', component: () => import('../views/connectors/ConnectorsView.vue') },
      { path: 'monitor', name: 'Monitor', component: () => import('../views/monitor/MonitorView.vue') },
      { path: 'logs', name: 'LogManagement', component: () => import('../views/log-management/LogManagement.vue') },
      { path: 'backups', name: 'BackupManagement', component: () => import('../views/backup/BackupManagement.vue') },
      { path: 'audit', redirect: '/logs' },
      { path: 'apikey', name: 'ApiKey', component: () => import('../views/apikey/ApiKeyView.vue') },
      { path: 'usage', name: 'Usage', component: () => import('../views/usage/UsageBillingView.vue') },
      { path: 'account', name: 'Account', component: () => import('../views/account/AccountSettingsView.vue') },
      { path: 'account/activity', name: 'DataActivity', component: () => import('../views/account/DataActivityView.vue') },
      { path: 'recycle-bin', name: 'RecycleBin', component: () => import('../views/account/RecycleBin.vue') },
      { path: 'help', name: 'Docs', component: () => import('../views/docs/DocsView.vue') },
      { path: 'agent-state', name: 'AgentStateWorkbench', component: () => import('../views/agent-state/AgentStateWorkbench.vue') },
      { path: 'agent-state/runs/:taskRunId', name: 'AgentTaskRunDetail', component: () => import('../views/agent-state/AgentTaskRunDetailView.vue') },
      // Datalake
      { path: 'datalake', redirect: '/datalake/datasets' },
      { path: 'datalake/jobs', name: 'DatalakeJobs', component: () => import('../views/datalake/DatalakeJobs.vue') },
      { path: 'datalake/jobs/new', name: 'DatalakeJobNew', component: () => import('../views/datalake/DatalakeJobNew.vue') },
      { path: 'datalake/jobs/:jobId', name: 'DatalakeJobDetail', component: () => import('../views/datalake/DatalakeJobDetail.vue') },
      { path: 'datalake/datasets', name: 'DatalakeDatasets', component: () => import('../views/datalake/DatalakeDatasets.vue') },
      { path: 'datalake/datasets/new', name: 'DatalakeDatasetNew', component: () => import('../views/datalake/DatalakeDatasetNew.vue') },
      { path: 'datalake/datasets/:id', name: 'DatalakeDatasetDetail', component: () => import('../views/datalake/DatalakeDatasetDetail.vue') },
      { path: 'datalake/notebook', name: 'DatalakeNotebookList', component: () => import('../views/datalake/DatalakeNotebookList.vue') },
      { path: 'datalake/notebook/:id', name: 'DatalakeNotebookEditor', component: () => import('../views/datalake/DatalakeNotebook.vue') },
      { path: 'datalake/monitor', name: 'DatalakeMonitor', component: () => import('../views/datalake/DatalakeMonitor.vue') },
      // Datalake — Pipeline
      { path: 'datalake/pipelines', name: 'DatalakePipelines', component: () => import('../views/datalake/DatalakePipelines.vue') },
      { path: 'datalake/pipelines/new', name: 'DatalakePipelineNew', component: () => import('../views/datalake/DatalakePipelineEditor.vue') },
      { path: 'datalake/pipelines/:id', name: 'DatalakePipelineDetail', component: () => import('../views/datalake/DatalakePipelineDetail.vue') },
      { path: 'datalake/pipelines/:id/edit', name: 'DatalakePipelineEdit', component: () => import('../views/datalake/DatalakePipelineEditor.vue') },
      { path: 'datalake/pipelines/:id/runs/:runId', name: 'DatalakePipelineRun', component: () => import('../views/datalake/DatalakePipelineRun.vue') },
      // Datalake — Connections
      { path: 'datalake/connections', redirect: '/connectors' },
      // Datalake — Components
      { path: 'datalake/components', name: 'DatalakeComponents', component: () => import('../views/datalake/DatalakeComponents.vue') },
      { path: 'datalake/components/register', name: 'DatalakeComponentRegister', component: () => import('../views/datalake/DatalakeComponentRegister.vue') },
      // Knowledge — static routes MUST come before :kbId
      { path: 'knowledge', name: 'KnowledgeBases', component: () => import('../views/knowledge/KnowledgeBases.vue') },
      { path: 'knowledge/datasources', redirect: '/knowledge' },
      { path: 'knowledge/search', name: 'KnowledgeSearch', component: () => import('../views/knowledge/KnowledgeSearch.vue') },
      { path: 'knowledge/chat', name: 'KnowledgeChat', component: () => import('../views/knowledge/KnowledgeChatPage.vue') },
      { path: 'knowledge/:kbId', name: 'KnowledgeBaseDetail', component: () => import('../views/knowledge/KnowledgeBaseDetail.vue') },
      { path: 'knowledge/:kbId/documents/:docId', name: 'DocumentDetail', component: () => import('../views/knowledge/DocumentDetail.vue') },
      // LakebaseFS — generic file system
      { path: 'lbfs', name: 'LakebaseFSBrowse', component: () => import('../views/lbfs/LakebaseFSBrowse.vue') },
      // Memory — static routes MUST come before :memId
      { path: 'memory', name: 'MemoryBases', component: () => import('../views/memory/MemoryBases.vue') },
      { path: 'memory/browse', name: 'MemoryBrowse', component: () => import('../views/memory/MemoryBrowse.vue') },
      { path: 'memory/traits', redirect: '/memory/browse' },
      { path: 'memory/messages', redirect: '/memory' },
      { path: 'memory/stats', redirect: '/memory' },
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
  // Authenticated users visiting '/' go to dashboard
  if (to.path === '/') {
    const apiKey = localStorage.getItem('lakeon_api_key')
    if (apiKey) return '/dashboard'
  }
  if (!to.meta.noAuth) {
    const apiKey = localStorage.getItem('lakeon_api_key')
    if (!apiKey) {
      return '/'
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
