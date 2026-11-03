import { createRouter, createWebHistory } from 'vue-router'
import { auth, clearLogin } from './auth'
import { request } from './api/chat'
import AuthView from './views/AuthView.vue'
import RoomListView from './views/RoomListView.vue'
import RoomEntryView from './views/RoomEntryView.vue'
import AdminView from './views/AdminView.vue'
import DmChatView from './views/DmChatView.vue'

export const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', redirect: '/app' },
    { path: '/login', component: AuthView, meta: { guest: true } },
    { path: '/register', component: AuthView, meta: { guest: true } },
    { path: '/forgot-password', component: AuthView, meta: { guest: true } },
    { path: '/app', component: RoomListView },
    { path: '/admin', component: AdminView, meta: { admin: true } },
    { path: '/room/:id', component: RoomEntryView },
    // 私信与进房流程独立，仍复用全局登录守卫。
    { path: '/dm/:userId', component: DmChatView },
    { path: '/:pathMatch(.*)*', redirect: '/app' },
  ],
})

/** 刷新时向后端核验身份；普通用户不可进入后台路由。 */
router.beforeEach(async to => {
  if (auth.token && !auth.user) {
    try {
      auth.user = await request('/auth/me')
    } catch (cause) {
      if (cause.status === 401) clearLogin()
      else if (!to.meta.guest) return { path: '/login', query: { error: cause.message } }
    }
  }
  if (!auth.token && !to.meta.guest) return '/login'
  if (auth.user && to.meta.guest) return auth.user.role === 'ADMIN' ? '/admin' : '/app'
  if (to.meta.admin && auth.user?.role !== 'ADMIN') return '/app'
})

// 过期与跨标签退出时卸载聊天室，避免沿用旧进房凭证。
window.addEventListener('mairy:unauthorized', () => router.replace('/login'))
window.addEventListener('storage', event => {
  if (event.key === 'mairy.auth.token') {
    auth.token = event.newValue || ''
    auth.user = null
    router.replace('/login')
  }
})
