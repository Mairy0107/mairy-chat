import { reactive } from 'vue'

// 登录凭证持久化便于刷新；XSS风险与HttpOnly改进见SECURITY.md。
export const auth = reactive({ token: localStorage.getItem('mairy.auth.token') || '', user: null })

/** 登录成功后保存凭证与经服务端筛选的用户资料。 */
export function saveLogin(result) {
  window.dispatchEvent(new Event('mairy:lock-keys'))
  auth.token = result.token
  auth.user = result.user
  localStorage.setItem('mairy.auth.token', result.token)
}

/** 退出或服务端返回401时清除本地登录态。 */
export function clearLogin() {
  window.dispatchEvent(new Event('mairy:lock-keys'))
  auth.token = ''
  auth.user = null
  localStorage.removeItem('mairy.auth.token')
}

/** 纯 JS SHA-256 仅用于浏览器配额指纹，不参与密码或房间加密。 */
function fingerprintSha256(bytes) {
  // 从前 64 个质数的立方根生成标准轮常量，平方根生成初始状态。
  const constants = [], state = []
  for (let candidate = 2; constants.length < 64; candidate++) {
    let prime = true
    for (let divisor = 2; divisor * divisor <= candidate; divisor++) {
      if (candidate % divisor === 0) { prime = false; break }
    }
    if (!prime) continue
    if (state.length < 8) state.push((Math.sqrt(candidate) % 1 * 0x100000000) >>> 0)
    constants.push((Math.cbrt(candidate) % 1 * 0x100000000) >>> 0)
  }
  const padded = new Uint8Array(Math.ceil((bytes.length + 9) / 64) * 64)
  padded.set(bytes)
  padded[bytes.length] = 0x80
  const view = new DataView(padded.buffer)
  view.setUint32(padded.length - 8, Math.floor(bytes.length / 0x20000000))
  view.setUint32(padded.length - 4, bytes.length * 8)
  const words = new Uint32Array(64)
  const rotate = (value, bits) => (value >>> bits) | (value << (32 - bits))
  for (let offset = 0; offset < padded.length; offset += 64) {
    for (let i = 0; i < 16; i++) words[i] = view.getUint32(offset + i * 4)
    for (let i = 16; i < 64; i++) {
      const x = words[i - 15], y = words[i - 2]
      words[i] = words[i - 16] + (rotate(x, 7) ^ rotate(x, 18) ^ (x >>> 3))
        + words[i - 7] + (rotate(y, 17) ^ rotate(y, 19) ^ (y >>> 10))
    }
    let [a, b, c, d, e, f, g, h] = state
    for (let i = 0; i < 64; i++) {
      const first = h + (rotate(e, 6) ^ rotate(e, 11) ^ rotate(e, 25)) + ((e & f) ^ (~e & g)) + constants[i] + words[i]
      const second = (rotate(a, 2) ^ rotate(a, 13) ^ rotate(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))
      h = g; g = f; f = e; e = (d + first) | 0
      d = c; c = b; b = a; a = (first + second) | 0
    }
    for (const [i, value] of [a, b, c, d, e, f, g, h].entries()) state[i] = (state[i] + value) >>> 0
  }
  return state.map(value => value.toString(16).padStart(8, '0')).join('')
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
  // HTTP IP 不提供 SubtleCrypto；回退结果与安全环境完全一致。
  const bytes = new TextEncoder().encode(source)
  const fingerprint = globalThis.crypto?.subtle
    ? Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)), byte => byte.toString(16).padStart(2, '0')).join('')
    : fingerprintSha256(bytes)
  localStorage.setItem('mairy.machineFingerprint', fingerprint)
  return fingerprint
}
