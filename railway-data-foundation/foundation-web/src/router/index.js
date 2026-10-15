import { createRouter, createWebHistory } from 'vue-router'
import MainLayout from '../layout/MainLayout.vue'
import { restoreSession } from '../api/auth'
import { session } from '../utils/sessionState'
import { allowedRoute } from '../utils/workspace'
import { publicAuthRoute } from '../utils/registration'

const routes = [
  { path: '/login', component: () => import('../views/LoginView.vue') },
  { path: '/register', component: () => import('../views/RegisterView.vue'), meta: { title: '账号注册' } },
  { path: '/', component: MainLayout, children: [
    { path: '', redirect: '/dashboard' },
    { path: 'dashboard', component: () => import('../views/dashboard/WorkspaceHome.vue'), meta: { title: '首页' } },
    { path: 'sources', component: () => import('../views/source/SourceView.vue'), meta: { title: '数据源管理' } },
    { path: 'scans', component: () => import('../views/metadata/MetadataView.vue'), meta: { title: '元数据扫描' } },
    { path: 'resources', component: () => import('../views/resource/ResourceListView.vue'), meta: { title: '资源目录' } },
    { path: 'resources/:id', component: () => import('../views/resource/WorkspaceResourceDetail.vue'), meta: { title: '资源详情' } },
    { path: 'capabilities', component: () => import('../views/capability/CapabilityView.vue'), meta: { title: '接口能力' } },
    { path: 'accounts', component: () => import('../views/subject/AccountView.vue'), meta: { title: '用户账号' } },
    { path: 'subjects', component: () => import('../views/subject/SubjectView.vue'), meta: { title: '共享主体管理' } },
    { path: 'relationships', component: () => import('../views/sharing/RelationshipView.vue'), meta: { title: '共享关系管理' } },
    { path: 'kafka-subscriptions', component: () => import('../views/sharing/SubscriptionView.vue'), meta: { title: 'Kafka 订阅授权' } },
    { path: 'policies', component: () => import('../views/sharing/PolicyView.vue'), meta: { title: '访问策略' } },
    { path: 'credentials', component: () => import('../views/sharing/CredentialView.vue'), meta: { title: '调用凭据' } },
    { path: 'receiving', component: () => import('../views/receiving/ReceivingView.vue'), meta: { title: '数据接收' } },
    { path: 'production', component: () => import('../views/production/ProductionView.vue'), meta: { title: '数据发送' } },
    { path: 'access-check', component: () => import('../views/sharing/AccessView.vue'), meta: { title: '共享调用验证' } },
    { path: 'runtime-events', component: () => import('../views/runtime/AuditView.vue'), meta: { title: '调用审计' } },
    { path: 'runtime-traces/:id?', component: () => import('../views/runtime/TraceView.vue'), meta: { title: '调用追踪' } },
    { path: 'metrics', component: () => import('../views/runtime/MetricView.vue'), meta: { title: '共享计量' } },
    { path: 'about', component: () => import('../views/AboutView.vue'), meta: { title: '系统说明' } },
  ] },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]
const router = createRouter({ history: createWebHistory(), routes })
router.beforeEach(async(to)=>{
  try { await restoreSession() } catch { return publicAuthRoute(to.path)?true:'/login' }
  if(!session.user && !publicAuthRoute(to.path))return '/login'
  if(session.user && (publicAuthRoute(to.path)||!allowedRoute(session.user.role,to.path)))return '/dashboard'
})
window.addEventListener('foundation-session-expired',()=>router.replace('/login'))
router.afterEach((to) => { document.title = `${to.meta.title || '登录'} - 统一数据底座` })
export default router
