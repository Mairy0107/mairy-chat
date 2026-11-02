<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { auth, clearLogin } from './auth'
import { request } from './api/chat'

const router = useRouter()
const error = ref('')
const busy = ref(false)

/** 服务端撤销成功后退出，网络失败时允许重试。 */
async function logout() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    await request('/auth/logout', { method: 'POST' })
    clearLogin()
    await router.replace('/login')
  } catch (cause) { error.value = cause.message }
  finally { busy.value = false }
}
</script>

<template>
  <main class="app-shell">
    <header class="app-header">
      <span class="brand-mark" aria-hidden="true">M</span>
      <div class="grow"><h1>Mairy Chat</h1><p>留一间房，给聊得来的人。</p></div>
      <nav v-if="auth.user" class="header-links">
        <RouterLink to="/app">主页</RouterLink>
        <RouterLink v-if="auth.user.role === 'ADMIN'" to="/admin">后台</RouterLink>
        <button class="secondary" :disabled="busy" @click="logout">退出</button>
      </nav>
    </header>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <!-- 路由变化时重建页面，关闭旧房间连接并清除该页草稿。 -->
    <RouterView v-if="$route.meta.guest || auth.user" :key="$route.fullPath" />
  </main>
</template>
