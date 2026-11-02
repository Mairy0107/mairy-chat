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
const title = computed(() => ({ login: '欢迎回来', register: '认识新的朋友', 'forgot-password': '找回你的账号' })[mode.value])

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
  entranceTimer = setTimeout(finishEntrance, 1700)
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
      // 本地加密失败不阻断登录，房间页可用原密码或加密备份重新解锁。
      if (result.user.role === 'USER') {
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
  <section class="auth-layout" :class="{ 'is-entering': entering }">
    <div class="auth-story">
      <!-- 轻量几何与聊天气泡只作装饰，保持表单阅读和操作不受影响。 -->
      <svg class="auth-scenery" viewBox="0 0 520 660" fill="none" preserveAspectRatio="xMidYMid slice" aria-hidden="true" focusable="false">
        <circle cx="470" cy="475" r="210" />
        <circle cx="470" cy="475" r="166" />
        <path class="auth-bubble" d="M325 315h78a18 18 0 0 1 18 18v40a18 18 0 0 1-18 18h-41l-23 18v-18h-14a18 18 0 0 1-18-18v-40a18 18 0 0 1 18-18Z" />
        <path class="auth-bubble auth-bubble-secondary" d="M65 455h48a14 14 0 0 1 14 14v27a14 14 0 0 1-14 14H96l-17 13v-13H65a14 14 0 0 1-14-14v-27a14 14 0 0 1 14-14Z" />
        <path d="M333 353h8m13 0h8m13 0h8M179 553h36m-18-18v36" />
      </svg>
      <span class="auth-kicker">MAIRY · 让交流自然发生</span>
      <h2>留一间房，<br />给聊得来的人。</h2>
      <p>你的专属编号，你的聊天空间。<br />从一句你好，开始新的连接。</p>
      <!-- 同一枚徽章完成入场与待机，小屏也保留紧凑版本。 -->
      <div class="auth-emblem" aria-hidden="true">
        <div class="auth-orbit"><span>聊</span><div class="auth-spark"><i>✦</i></div></div>
      </div>
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
.auth-layout { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); min-height: 610px; border-radius: 24px; overflow: hidden; border: 1px solid #dbe3ef; box-shadow: 0 24px 70px #24365a12; background: #fff; }
.auth-story { position: relative; isolation: isolate; overflow: hidden; background: radial-gradient(ellipse at 75% 70%, #2f5368, transparent 65%), #132b39; color: #f2f6f5; padding: 48px 38px; }
/* 光斑与线稿分层漂浮，避免动画触发布局重排。 */
.auth-story::before { content: ''; position: absolute; z-index: -1; inset: 18% -35% -10% -15%; pointer-events: none; background: radial-gradient(ellipse, #76b6ba1c, transparent 66%); animation: auth-drift 18s ease-in-out infinite alternate; }
.auth-scenery { position: absolute; z-index: -1; inset: 0; width: 100%; height: 100%; stroke: #aac9cc; stroke-width: 1; opacity: 0.13; pointer-events: none; animation: auth-drift 22s ease-in-out infinite alternate-reverse; }
.auth-bubble { fill: #bdded408; }
.auth-bubble-secondary { opacity: 0.65; }
.auth-kicker { font-size: 11px; letter-spacing: 2px; color: #94b3b7; }
.auth-story h2 { margin: 38px 0 20px; font-size: 38px; line-height: 1.5; font-weight: 600; letter-spacing: 2px; }
.auth-story p { color: #b9cbd2; line-height: 2; }
.auth-story small { position: absolute; bottom: 35px; color: #9cb4bf; }
/* 外层负责一次入场，内层负责呼吸光，结束时直接保持原位。 */
.auth-emblem { width: 170px; height: 170px; margin: 40px auto 52px; }
.auth-orbit { position: relative; width: 100%; height: 100%; border: 1px solid #91b4ba40; border-radius: 50%; display: grid; place-items: center; }
.auth-orbit::before { content: ''; position: absolute; inset: -12px; border-radius: 50%; background: radial-gradient(circle, #a9d8cd1c, transparent 70%); opacity: 0.55; animation: auth-breathe 6s ease-in-out infinite; }
.auth-orbit span { position: relative; display: grid; place-items: center; height: 110px; width: 110px; border-radius: 30px; background: linear-gradient(145deg, #b9e3d81c, #b9e3d80c); border: 1px solid #baded331; box-shadow: inset 0 1px 0 #e0f1e50d, 0 12px 30px #0c222518; font-size: 42px; }
.auth-spark { position: absolute; inset: 0; pointer-events: none; }
.auth-spark i { position: absolute; top: -8px; left: 32px; color: #d2bf8f; font-size: 28px; font-style: normal; line-height: 1; text-shadow: 0 0 14px #d2bf8f33; }
.auth-card { min-width: 0; background: #ffffffeb; padding: 48px 38px; display: flex; flex-direction: column; justify-content: center; }
.auth-card h2 { font-size: 28px; margin: 12px 0 8px; }
.auth-form { display: grid; gap: 18px; margin-top: 30px; }
.auth-form input, .auth-form textarea { transition: border-color 180ms ease, box-shadow 180ms ease; }
.auth-form input:hover, .auth-form textarea:hover { border-color: #9bb5bc; }
.auth-form input:focus-visible, .auth-form textarea:focus-visible { outline-color: #7ca6ac; border-color: #719ba3; }
.auth-form button { margin-top: 5px; background: #234c53; padding: 13px; transition: background-color 180ms ease, box-shadow 180ms ease, transform 180ms ease; }
.auth-form button:hover:not(:disabled) { background: #2c5b62; filter: none; box-shadow: 0 6px 18px #234c5324; transform: translateY(-1px); }
.auth-form button:active:not(:disabled) { transform: translateY(0); box-shadow: none; }
.auth-form button:focus-visible { outline-color: #7ca6ac; }
.auth-links { display: flex; flex-wrap: wrap; gap: 22px; margin-top: 22px; font-size: 14px; }
.auth-links a { transition: color 180ms ease; }
.auth-links a:hover { color: #234c53; }
.auth-footnote { margin-top: 32px; font-size: 11px; color: #8995a5; }
/* 1.7 秒内完成徽章缩放、星点轻轨与内容显现，不遮挡表单。 */
.is-entering .auth-emblem { animation: auth-emblem-in 1600ms cubic-bezier(0.22, 1, 0.36, 1) both; }
.is-entering .auth-orbit::after { content: ''; position: absolute; inset: -1px; border: 1px solid #c7dfd455; border-radius: 50%; pointer-events: none; animation: auth-halo-in 1600ms ease-out both; }
.is-entering .auth-spark { animation: auth-spark-in 1500ms cubic-bezier(0.22, 1, 0.36, 1) both; }
.is-entering .auth-spark i::after { content: ''; position: absolute; top: 50%; left: 65%; width: 36px; height: 1px; transform: rotate(28deg); transform-origin: left; background: linear-gradient(90deg, #d2bf8f88, transparent); animation: auth-trail-in 1500ms ease-out both; }
.is-entering .auth-story > .auth-kicker, .is-entering .auth-story h2, .is-entering .auth-story p, .is-entering .auth-story small { animation: auth-content-in 650ms 250ms ease-out both; }
.is-entering .auth-card > * { animation: auth-content-in 650ms 500ms ease-out both; }
@keyframes auth-emblem-in {
  0% { opacity: 0; transform: translateY(18px) scale(0.72); }
  35% { opacity: 1; }
  70% { transform: translateY(-3px) scale(1.035); }
  100% { opacity: 1; transform: translateY(0) scale(1); }
}
@keyframes auth-halo-in {
  0% { opacity: 0; transform: scale(0.85); }
  35% { opacity: 0.8; }
  100% { opacity: 0; transform: scale(1.38); }
}
@keyframes auth-spark-in {
  0% { opacity: 0; transform: rotate(-85deg); }
  25% { opacity: 1; }
  100% { opacity: 1; transform: rotate(0); }
}
@keyframes auth-trail-in {
  0%, 100% { opacity: 0; }
  35% { opacity: 0.65; }
}
@keyframes auth-content-in {
  from { opacity: 0; transform: translateY(7px); }
  to { opacity: 1; transform: translateY(0); }
}
@keyframes auth-drift {
  from { transform: translate3d(-5px, 0, 0); }
  to { transform: translate3d(7px, -12px, 0); }
}
@keyframes auth-breathe {
  0%, 100% { opacity: 0.4; transform: scale(0.96); }
  50% { opacity: 0.85; transform: scale(1.08); }
}
/* 小屏保留徽章，以更短的入场和更紧凑的上下布局控制高度。 */
@media (max-width: 720px) {
  .auth-layout { grid-template-columns: minmax(0, 1fr); min-height: auto; }
  .auth-story { padding: 26px; }
  .auth-story h2 { font-size: 28px; margin: 18px 0 10px; }
  .auth-story p { font-size: 14px; }
  .auth-story small { position: static; display: block; font-size: 11px; text-align: center; }
  .auth-emblem { width: 108px; height: 108px; margin: 22px auto 18px; }
  .auth-orbit span { width: 72px; height: 72px; border-radius: 21px; font-size: 30px; }
  .auth-spark i { top: -6px; left: 17px; font-size: 22px; }
  .auth-scenery { opacity: 0.1; }
  .auth-card { padding: 30px 24px; }
  .is-entering .auth-emblem, .is-entering .auth-orbit::after, .is-entering .auth-spark, .is-entering .auth-spark i::after { animation-duration: 1200ms; }
  .is-entering .auth-card > * { animation-delay: 300ms; }
}
/* 系统要求减少动态时，入场、待机和交互位移均直接使用最终态。 */
@media (prefers-reduced-motion: reduce) {
  .auth-story::before, .auth-scenery, .auth-orbit::before,
  .is-entering .auth-emblem, .is-entering .auth-orbit::after, .is-entering .auth-spark, .is-entering .auth-spark i::after,
  .is-entering .auth-story > .auth-kicker, .is-entering .auth-story h2, .is-entering .auth-story p, .is-entering .auth-story small, .is-entering .auth-card > * { animation: none; }
  .is-entering .auth-orbit::after, .is-entering .auth-spark i::after { display: none; }
  .auth-form input, .auth-form textarea, .auth-form button, .auth-links a { transition: none; }
  .auth-form button:hover:not(:disabled) { transform: none; }
}
</style>
