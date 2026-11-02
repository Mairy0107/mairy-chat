import { reactive } from 'vue'

// 登录凭证持久化便于刷新；XSS风险与HttpOnly改进见SECURITY.md。
export const auth = reactive({ token: localStorage.getItem('mairy.auth.token') || '', user: null })

/** 登录成功后保存凭证与经服务端筛选的用户资料。 */
export function saveLogin(result) {
  auth.token = result.token
  auth.user = result.user
  localStorage.setItem('mairy.auth.token', result.token)
}

/** 退出或服务端返回401时清除本地登录态。 */
export function clearLogin() {
  auth.token = ''
  auth.user = null
  localStorage.removeItem('mairy.auth.token')
}

/** 持久化浏览器指纹，清理存储或伪造特征可以绕过，非硬件序列号。 */
export async function machineFingerprint() {
  const saved = localStorage.getItem('mairy.machineFingerprint')
  if (/^[a-f0-9]{64}$/.test(saved || '')) return saved
  const canvas = document.createElement('canvas')
  const context = canvas.getContext('2d')
  if (context) {
    context.font = '16px sans-serif'
    context.fillText('Mairy 你好 🌿', 4, 20)
  }
  const source = [navigator.userAgent, navigator.language, screen.width, screen.height,
    screen.colorDepth, Intl.DateTimeFormat().resolvedOptions().timeZone, canvas.toDataURL()].join('|')
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(source))
  const fingerprint = Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('')
  localStorage.setItem('mairy.machineFingerprint', fingerprint)
  return fingerprint
}
