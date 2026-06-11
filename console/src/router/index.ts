import { createRouter, createWebHashHistory } from 'vue-router'
import { getToken } from '../api/client'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/LoginView.vue') },
  { path: '/', redirect: '/monitor' },
  { path: '/monitor', component: () => import('../views/MonitorView.vue') },
  { path: '/audit', component: () => import('../views/AuditView.vue') },
  { path: '/operator', component: () => import('../views/OperatorView.vue') },
  { path: '/resources', component: () => import('../views/ResourceView.vue') },
  { path: '/approvals', component: () => import('../views/ApprovalView.vue') },
]

const router = createRouter({ history: createWebHashHistory(), routes })
router.beforeEach((to) => {
  if (to.path !== '/login' && !getToken()) return '/login'
})
export default router
