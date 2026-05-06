import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  { path: '/',           name: 'overview',  component: () => import('./pages/OverviewPage.vue') },
  { path: '/memory',     name: 'memory',    component: () => import('./pages/MemoryPage.vue') },
  {
    path: '/cognition',
    component: () => import('./pages/CognitionPage.vue'),
    children: [
      { path: '',          redirect: '/cognition/timeline' },
      { path: 'timeline',  name: 'cog-timeline', component: () => import('./pages/cognition/TimelineView.vue') },
      { path: 'summary',   name: 'cog-summary',  component: () => import('./pages/cognition/SummaryView.vue') },
      { path: 'graph',     name: 'cog-graph',    component: () => import('./pages/cognition/GraphView.vue') },
      { path: 'skill',     name: 'cog-skill',    component: () => import('./pages/cognition/SkillView.vue') },
    ],
  },
  { path: '/status',     name: 'status',    component: () => import('./pages/StatusPage.vue') },
]

export const router = createRouter({
  history: createWebHashHistory(),
  routes,
})
