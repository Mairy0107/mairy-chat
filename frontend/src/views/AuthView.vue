<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { request } from '../api/chat'
import { machineFingerprint, saveLogin } from '../auth'
import { unlockIdentity } from '../crypto/e2ee'

const route = useRoute()
const router = useRouter()
const username = ref('')
const nickname = ref('')
const password = ref('')
const note = ref('')
const busy = ref(false)
const error = ref('')
const notice = ref('')
const mode = computed(() => route.path === '/register' ? 'register' : route.path === '/forgot-password' ? 'forgot-password' : 'login')
const title = computed(() => ({ login: '欢迎回来', register: '注册账号', 'forgot-password': '找回密码' })[mode.value])

// 入场状态仅影响装饰层，不参与认证请求与表单状态。
const entering = ref(false)
const entranceKey = 'mairy.auth.entrance.seen'
let entranceTimer

/** 结束入场并清理计时，确保切换路由时不遗留回调。 */
function finishEntrance() {
  clearTimeout(entranceTimer)
  entering.value = false
}

/** 每个标签页会话首次进入登录页时播放；减少动态或存储不可用时直接落位。 */
function startEntrance() {
  finishEntrance()
  if (mode.value !== 'login' || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
  try {
    if (sessionStorage.getItem(entranceKey)) return
    sessionStorage.setItem(entranceKey, '1')
  } catch {
    // 隐私设置禁用存储时保持静态首页，避免反复播放。
    return
  }
  entering.value = true
  entranceTimer = setTimeout(finishEntrance, 320)
}

onMounted(startEntrance)
onBeforeUnmount(finishEntrance)
watch(mode, startEntrance)

/** 路由复用页面时清空密码和上一页的反馈。 */
watch(mode, () => { password.value = ''; error.value = ''; notice.value = '' })

/** 按页面模式提交表单，角色跳转依据登录响应。 */
async function submit() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  notice.value = ''
  try {
    if (mode.value === 'login') {
      const result = await request('/auth/login', { method: 'POST', publicRequest: true,
        body: { username: username.value.trim(), password: password.value } })
      saveLogin(result)
      // 仅在安全环境自动解锁；HTTP 登录仍进入房间并显示环境提示。
      if (result.user.role === 'USER' && window.isSecureContext === true) {
        try { await unlockIdentity(password.value) } catch { /* 房间页显示设备解锁入口。 */ }
      }
      password.value = ''
      await router.replace(result.user.role === 'ADMIN' ? '/admin' : '/app')
    } else if (mode.value === 'register') {
      await request('/auth/register', { method: 'POST', publicRequest: true, body: {
        username: username.value.trim(), nickname: nickname.value.trim(), password: password.value,
        machineFingerprint: await machineFingerprint(),
      } })
      await router.push('/login?registered=1')
    } else {
      const result = await request('/auth/forgot-password', { method: 'POST', publicRequest: true,
        body: { username: username.value.trim(), note: note.value.trim() } })
      notice.value = result.message
    }
  } catch (cause) {
    error.value = cause.message
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <!-- 纸卡只承载账号操作，省去宣传区与重复提示。 -->
  <section class="auth-layout" :class="{ 'is-entering': entering }">
    <div class="auth-card paper-stack">
      <nav class="auth-tabs" aria-label="账号入口">
        <RouterLink to="/login" :aria-current="mode === 'login' ? 'page' : undefined">登录</RouterLink>
        <RouterLink to="/register" :aria-current="mode === 'register' ? 'page' : undefined">注册</RouterLink>
      </nav>
      <h2>{{ title }}</h2>
      <p v-if="mode === 'forgot-password'" class="muted auth-hint">管理员核实后重置。</p>
      <form class="auth-form" @submit.prevent="submit">
        <label>用户名<input v-model="username" required minlength="3" maxlength="32" autocomplete="username"
          :pattern="mode === 'register' ? '[a-zA-Z0-9_]{3,32}' : undefined" :placeholder="mode === 'register' ? '字母、数字或下划线' : '输入用户名'" /></label>
        <label v-if="mode === 'register'">昵称<input v-model="nickname" required maxlength="20" autocomplete="nickname" placeholder="输入昵称" /></label>
        <div v-if="mode !== 'forgot-password'" class="password-field">
          <label for="auth-password">密码</label>
          <RouterLink v-if="mode === 'login'" to="/forgot-password">忘记密码</RouterLink>
          <input id="auth-password" v-model="password" type="password" required :minlength="mode === 'register' ? 8 : 1" maxlength="64"
            :autocomplete="mode === 'register' ? 'new-password' : 'current-password'" placeholder="输入密码" />
        </div>
        <label v-else>备注（选填）<textarea v-model="note" maxlength="200" placeholder="勿填密码或证件信息" /></label>
        <p v-if="mode === 'register'" class="muted">密码 8–64 字，最多 72 字节；每浏览器限 3 个账号。</p>
        <p v-if="error || route.query.error" class="error" role="alert">{{ error || route.query.error }}</p>
        <p v-if="notice || route.query.registered" class="notice" role="status">{{ notice || '注册成功，请登录。' }}</p>
        <button :disabled="busy">{{ busy ? '处理中…' : mode === 'login' ? '登录' : mode === 'register' ? '注册' : '提交申请' }}</button>
      </form>
      <RouterLink v-if="mode === 'forgot-password'" class="auth-back" to="/login">返回登录</RouterLink>
    </div>
  </section>
</template>

<style scoped>
/* 登录、注册和找回密码共用一张裁角纸卡。 */
.auth-layout { display: grid; place-items: center; padding: clamp(32px, 8vh, 88px) 0 48px; }
.auth-card { width: min(100%, 480px); padding: 32px 40px 40px; }
.auth-tabs { display: flex; gap: 32px; margin-bottom: 32px; border-bottom: 1px dotted var(--line); }
.auth-tabs a { position: relative; padding: 10px 2px 14px; color: var(--muted); }
.auth-tabs a[aria-current='page'] { color: var(--ink); font-weight: 700; }
.auth-tabs a[aria-current='page']::after { content: ''; position: absolute; inset: auto 0 -1px; height: 3px; background: var(--action); border-radius: 3px; }
.auth-card h2 { font-size: 32px; }
.auth-hint { margin-top: 8px; }
.auth-form { display: grid; gap: 20px; margin-top: 28px; }
.auth-form .error, .auth-form .notice { margin: 0; }
.auth-form button { width: 100%; margin-top: 8px; }
.password-field { display: grid; grid-template-columns: 1fr auto; gap: 6px 12px; align-items: center; }
.password-field a { font-size: 13px; }
.password-field input { grid-column: 1 / -1; }
.auth-back { display: inline-block; padding: 12px 0 0; margin-top: 12px; font-size: 14px; }
/* 仅首次登录归位，不对输入和错误重复播放。 */
.is-entering .auth-card { animation: paper-settle var(--motion-enter) var(--ease-paper) both; }
@media (max-width: 600px) {
  .auth-layout { padding-top: 28px; }
  .auth-card { padding: 26px 24px 32px; }
}
@media (prefers-reduced-motion: reduce) { .is-entering .auth-card { animation: none; } }
</style>
