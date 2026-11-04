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
  <!-- 左侧纸艺仅作装饰，右侧纸卡保留原有账号操作。 -->
  <section class="auth-layout" :class="{ 'is-entering': entering }">
    <div class="auth-visual" aria-hidden="true">
      <svg viewBox="0 0 540 480" fill="none" focusable="false">
        <!-- 底纸与撕边纸错位叠放，留白承接页面米纸底色。 -->
        <g transform="rotate(-11 240 262)">
          <rect class="visual-underpaper" x="76" y="110" width="290" height="304" rx="4" />
          <path class="visual-stitch" d="M96 136H346M96 386H280" />
        </g>
        <g transform="rotate(6 284 240)">
          <path class="visual-underpaper" d="M138 76H405L432 103V399L420 395L408 401L396 397L384 403L372 398L360 402L348 397L336 401L324 396L312 402L300 398L288 403L276 397L264 401L252 396L240 402L228 398L216 402L204 397L192 401L180 396L168 402L156 398L144 402L138 398Z" />
          <path class="visual-paper" d="M132 68H399L426 95V391L414 387L402 393L390 389L378 395L366 390L354 394L342 389L330 393L318 388L306 394L294 390L282 395L270 389L258 393L246 388L234 394L222 390L210 394L198 389L186 393L174 388L162 394L150 390L138 394L132 390Z" />
          <path class="visual-fold" d="M399 68V95H426" />
          <path class="visual-stitch" d="M154 328H404" />
        </g>
        <!-- 复用品牌门标的邮票虚线与拱门比例，避免引入另一套图案。 -->
        <g transform="translate(211 125) rotate(6 72 90) scale(3)">
          <path class="visual-stamp" d="M4 3H44V57H4Z" stroke-dasharray="3 3" />
          <path class="visual-stamp" d="M12 49V25A12 12 0 0 1 36 25V49Z" />
          <circle class="visual-leaf" cx="31" cy="35" r="1" />
        </g>
        <path class="visual-citrus" d="M96 302L160 292L164 310L100 321Z" />
        <path class="visual-thread" d="M61 236C17 304 83 373 142 346M405 172C472 147 502 190 472 235" />
      </svg>
    </div>
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
/* 桌面左纸艺、右表单；列宽可收缩，纸卡和焦点环不裁切。 */
.auth-layout { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 480px); align-items: center; gap: clamp(32px, 6vw, 80px); padding: clamp(32px, 8vh, 88px) 0 48px; }
.auth-card { width: 100%; max-width: 480px; min-width: 0; padding: 32px 40px 40px; }
/* 纯 SVG 纸艺沿用现有令牌，静态呈现以保持表单为视觉焦点。 */
.auth-visual { min-width: 0; pointer-events: none; }
.auth-visual svg { display: block; width: 100%; max-width: 540px; height: auto; margin: 0 auto; }
.visual-underpaper { fill: var(--underpaper); }
.visual-paper { fill: var(--paper); }
.visual-fold { fill: var(--rice); stroke: var(--line); stroke-width: 1; }
.visual-stitch { stroke: var(--line); stroke-width: 1.5; stroke-dasharray: 2 6; stroke-linecap: round; }
.visual-stamp { stroke: var(--leaf); stroke-width: 1.4; }
.visual-leaf { fill: var(--leaf); }
.visual-citrus { fill: var(--citrus); }
.visual-thread { stroke: var(--ink); stroke-width: 1; stroke-dasharray: 2 7; stroke-linecap: round; opacity: 0.3; }
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
/* 窄屏隐藏装饰，登录、注册和找回密码均保留完整可用宽度。 */
@media (max-width: 899px) {
  .auth-layout { grid-template-columns: minmax(0, 1fr); gap: 0; }
  .auth-visual { display: none; }
  .auth-card { justify-self: center; }
}
@media (max-width: 600px) {
  .auth-layout { padding-top: 28px; }
  .auth-card { padding: 26px 24px 32px; }
}
@media (prefers-reduced-motion: reduce) { .is-entering .auth-card { animation: none; } }
</style>
