<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { request } from '../api/chat'
import { machineFingerprint, saveLogin } from '../auth'

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
const title = computed(() => ({ login: '欢迎回来', register: '认识新的朋友', 'forgot-password': '找回你的账号' })[mode.value])

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
  <section class="auth-layout">
    <div class="auth-story">
      <span class="auth-kicker">MAIRY · 让交流自然发生</span>
      <h2>留一间房，<br />给聊得来的人。</h2>
      <p>你的专属编号，你的聊天空间。<br />从一句你好，开始新的连接。</p>
      <div class="auth-orbit" aria-hidden="true"><span>聊</span><i>✦</i></div>
      <small>简单连接 · 实时相伴 · 保持真诚</small>
    </div>
    <div class="auth-card">
      <span class="auth-kicker">YOUR SPACE, YOUR PEOPLE</span>
      <h2>{{ title }}</h2>
      <p class="muted">{{ mode === 'forgot-password' ? '提交申请后，由管理员核实并人工重置密码。' : '用一个账号，开启属于你的交流空间。' }}</p>
      <form class="auth-form" @submit.prevent="submit">
        <label>用户名<input v-model="username" required minlength="3" maxlength="32" autocomplete="username"
          :pattern="mode === 'register' ? '[a-zA-Z0-9_]{3,32}' : undefined" placeholder="字母、数字或下划线" /></label>
        <label v-if="mode === 'register'">展示昵称<input v-model="nickname" required maxlength="20" autocomplete="nickname" placeholder="朋友们怎么称呼你？" /></label>
        <label v-if="mode !== 'forgot-password'">密码<input v-model="password" type="password" required
          :minlength="mode === 'register' ? 8 : 1" maxlength="64"
          :autocomplete="mode === 'register' ? 'new-password' : 'current-password'" placeholder="请输入密码" /></label>
        <label v-else>核实备注（选填）<textarea v-model="note" maxlength="200" placeholder="请勿填写密码或敏感证件信息" /></label>
        <p v-if="mode === 'register'" class="muted">密码8至64字、最多72字节。每个浏览器指纹最多注册3个账号。</p>
        <p v-if="error || route.query.error" class="error" role="alert">{{ error || route.query.error }}</p>
        <p v-if="notice || route.query.registered" class="notice" role="status">{{ notice || "注册成功，请登录。" }}</p>
        <button :disabled="busy">{{ busy ? '正在处理…' : mode === 'login' ? '登录，进入空间 →' : mode === 'register' ? '创建账号 →' : '提交重置申请' }}</button>
      </form>
      <nav class="auth-links" aria-label="账号入口">
        <RouterLink v-if="mode !== 'login'" to="/login">返回登录</RouterLink>
        <RouterLink v-if="mode !== 'register'" to="/register">注册账号</RouterLink>
        <RouterLink v-if="mode === 'login'" to="/forgot-password">忘记密码</RouterLink>
      </nav>
      <p class="auth-footnote">浏览器指纹不等于硬件序列号，可被清理或伪造。</p>
    </div>
  </section>
</template>

<style scoped>
.auth-layout { display: grid; grid-template-columns: 1fr 1fr; min-height: 610px; border-radius: 24px; overflow: hidden; border: 1px solid #dbe3ef; box-shadow: 0 24px 70px #24365a12; }
.auth-story { position: relative; overflow: hidden; background: radial-gradient(ellipse at 75% 70%, #345d78, transparent 65%), #132b39; color: #f2f6f5; padding: 48px 38px; }
.auth-kicker { font-size: 11px; letter-spacing: 2px; color: #94b3b7; }
.auth-story h2 { margin: 38px 0 20px; font-size: 38px; line-height: 1.5; font-weight: 600; letter-spacing: 2px; }
.auth-story p { color: #b9cbd2; line-height: 2; }
.auth-story small { position: absolute; bottom: 35px; color: #9cb4bf; }
.auth-orbit { width: 170px; height: 170px; margin: 40px auto; border: 1px solid #91b4ba40; border-radius: 50%; display: grid; place-items: center; transform: rotate(-15deg); }
.auth-orbit span { display: grid; place-items: center; height: 110px; width: 110px; border-radius: 30px; background: #b9e3d817; border: 1px solid #baded331; font-size: 42px; transform: rotate(15deg); }
.auth-orbit i { position: absolute; top: -14px; color: #d2bf8f; font-size: 30px; }
.auth-card { background: #ffffffeb; padding: 48px 38px; display: flex; flex-direction: column; justify-content: center; }
.auth-card h2 { font-size: 28px; margin: 12px 0 8px; }
.auth-form { display: grid; gap: 18px; margin-top: 30px; }
.auth-form button { margin-top: 5px; background: #234c53; padding: 13px; }
.auth-links { display: flex; gap: 22px; margin-top: 22px; font-size: 14px; }
.auth-footnote { margin-top: 32px; font-size: 11px; color: #8995a5; }
@media (max-width: 720px) { .auth-layout { grid-template-columns: 1fr; min-height: auto; } .auth-story { padding: 26px; } .auth-story h2 { font-size: 28px; margin: 18px 0 10px; } .auth-story small, .auth-orbit { display: none; } .auth-card { padding: 30px 24px; } }
</style>
