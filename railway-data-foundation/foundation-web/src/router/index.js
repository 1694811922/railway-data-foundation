import { createRouter, createWebHistory } from 'vue-router'
import MainLayout from '../layout/MainLayout.vue'

const routes = [
  { path: '/login', component: () => import('../views/LoginView.vue') },
  { path: '/', component: MainLayout, children: [
    { path: '', redirect: '/dashboard' },
    { path: 'dashboard', component: () => import('../views/dashboard/DashboardView.vue'), meta: { title: '首页' } },
    { path: 'sources', component: () => import('../views/source/SourceView.vue'), meta: { title: '数据源管理' } },
    { path: 'scans', component: () => import('../views/metadata/MetadataView.vue'), meta: { title: '元数据扫描' } },
    { path: 'resources', component: () => import('../views/resource/ResourceListView.vue'), meta: { title: '资源目录' } },
    { path: 'resources/:id', component: () => import('../views/resource/ResourceDetailView.vue'), meta: { title: '资源详情' } },
    { path: 'capabilities', component: () => import('../views/capability/CapabilityView.vue'), meta: { title: '接口能力' } },
    { path: 'about', component: () => import('../views/AboutView.vue'), meta: { title: '系统说明' } },
  ] },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]
const router = createRouter({ history: createWebHistory(), routes })
router.beforeEach((to) => {
  if (to.path !== '/login' && sessionStorage.getItem('foundation_demo_login') !== '1') return '/login'
  if (to.path === '/login' && sessionStorage.getItem('foundation_demo_login') === '1') return '/dashboard'
})
router.afterEach((to) => { document.title = `${to.meta.title || '演示登录'} - 统一数据底座` })
export default router
