import { auth } from '../auth'
import { request } from '../api/chat'

const encoder = new TextEncoder()
const decoder = new TextDecoder('utf-8', { fatal: true })
const rsa = { name: 'RSA-OAEP', hash: 'SHA-256' }
const rounds = 600000
let identity = null
let generation = 0

/** 二进制编码不经过日志或第三方加密服务。 */
export function toBase64(bytes) {
  return btoa(String.fromCharCode(...new Uint8Array(bytes)))
}

/** 严格读取规范 Base64，拒绝编码歧义。 */
export function fromBase64(text) {
  const bytes = Uint8Array.from(atob(text), char => char.charCodeAt(0))
  if (toBase64(bytes) !== text) throw new Error('加密数据格式无效')
  return bytes
}

/** 生成可以通过其他可信渠道核对的完整公钥指纹。 */
export async function fingerprint(publicKey) {
  const hash = await crypto.subtle.digest('SHA-256', fromBase64(publicKey))
  return Array.from(new Uint8Array(hash), byte => byte.toString(16).padStart(2, '0')).join('')
}

/** 以账号隔离本地加密保险箱，绝不上传该条存储。 */
function vaultName(userId) {
  return `mairy.e2ee.v1.vault.${userId}`
}

/** 密码派生的本地保护密钥不导出、不发送到服务器。 */
async function passwordKey(password, salt) {
  const material = await crypto.subtle.importKey('raw', encoder.encode(password), 'PBKDF2', false, ['deriveKey'])
  return crypto.subtle.deriveKey({ name: 'PBKDF2', hash: 'SHA-256', salt, iterations: rounds }, material,
    { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'])
}

/** 将身份、版本与公钥绑定到保险箱认证数据。 */
function vaultAad(userId, publicKey) {
  return encoder.encode(`mairy:e2ee:v1:vault:${userId}:${publicKey}`)
}

/** 检查内存身份仍属于当前登录，防止账号切换时沿用旧密钥。 */
function currentIdentity() {
  if (!identity || identity.userId !== auth.user?.id || identity.token !== auth.token) {
    throw new Error('设备密钥未解锁，请在本页输入本地密钥密码')
  }
  return identity
}

/** 读取解锁状态供页面控制发送按钮。 */
export function isUnlocked() {
  try { currentIdentity(); return true } catch { return false }
}

/** 退出时释放内存私钥；刷新后仍需密码解锁本地保险箱。 */
export function lockIdentity() {
  generation++
  identity = null
}

window.addEventListener('mairy:lock-keys', lockIdentity)
// 其他标签页切换或退出登录时，也释放本标签页的内存身份。
window.addEventListener('storage', event => {
  if (event.key === 'mairy.auth.token') lockIdentity()
})

/** 登录时创建或解锁身份；私钥持久化前始终先用密码加密。 */
export async function unlockIdentity(password) {
  if (!crypto.subtle) throw new Error('端到端加密需要 HTTPS 或 localhost 安全环境')
  if (!auth.user || auth.user.role !== 'USER') throw new Error('管理员不能取得加密成员密钥')
  if (!password) throw new Error('请输入本地密钥密码')
  const userId = auth.user.id
  const token = auth.token
  // 每次重新解锁都撤销旧内存身份，失败后保持锁定。
  identity = null
  const version = ++generation
  /** 异步解锁期间切换账号时立即停止，防止向新账号发布旧公钥。 */
  function requireLogin() {
    if (version !== generation || auth.token !== token || auth.user?.id !== userId || auth.user.role !== 'USER') {
      throw new Error('登录状态已变化，请重新解锁')
    }
  }
  /** 串行处理同源标签页的首次登记，避免覆盖本地身份。 */
  async function openVault() {
    requireLogin()
    const remote = await request('/e2ee/identity')
    requireLogin()
    const stored = localStorage.getItem(vaultName(userId))
    let vault
    let privateBytes
    try {
      if (stored) {
        vault = JSON.parse(stored)
        if (vault.version !== 1 || vault.userId !== userId || vault.iterations !== rounds) throw new Error('保险箱格式无效')
        const iv = fromBase64(vault.iv)
        const salt = fromBase64(vault.salt)
        if (iv.length !== 12 || salt.length !== 16) throw new Error('保险箱格式无效')
        privateBytes = new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv,
          additionalData: vaultAad(userId, vault.publicKey), tagLength: 128 },
        await passwordKey(password, salt), fromBase64(vault.ciphertext)))
      } else {
        if (remote) throw new Error('此设备没有原私钥，请导入原设备的加密备份；不能通过重置密码恢复')
        if (password.length < 8) throw new Error('首次创建密钥需至少8位密码')
        const pair = await crypto.subtle.generateKey({ ...rsa, modulusLength: 3072,
          publicExponent: new Uint8Array([1, 0, 1]) }, true, ['encrypt', 'decrypt'])
        const publicKey = toBase64(await crypto.subtle.exportKey('spki', pair.publicKey))
        privateBytes = new Uint8Array(await crypto.subtle.exportKey('pkcs8', pair.privateKey))
        const salt = crypto.getRandomValues(new Uint8Array(16))
        const iv = crypto.getRandomValues(new Uint8Array(12))
        const ciphertext = await crypto.subtle.encrypt({ name: 'AES-GCM', iv,
          additionalData: vaultAad(userId, publicKey), tagLength: 128 }, await passwordKey(password, salt), privateBytes)
        vault = { version: 1, userId, iterations: rounds, publicKey,
          salt: toBase64(salt), iv: toBase64(iv), ciphertext: toBase64(ciphertext) }
        requireLogin()
        // 先保存再发布，网络失败时仍保留唯一原私钥；后续重试使用相同公钥。
        if (localStorage.getItem(vaultName(userId))) throw new Error('其他标签页已创建密钥，请重新解锁')
        localStorage.setItem(vaultName(userId), JSON.stringify(vault))
      }
      if (remote && (remote.publicKey !== vault.publicKey || remote.fingerprint !== await fingerprint(vault.publicKey))) {
        throw new Error('服务端公钥与本设备不符，已停止加密操作')
      }
      const privateKey = await crypto.subtle.importKey('pkcs8', privateBytes, rsa, false, ['decrypt'])
      const publicKey = await crypto.subtle.importKey('spki', fromBase64(vault.publicKey), rsa, false, ['encrypt'])
      // 验证备份中的公私钥确实成对，防止错误备份破坏解密身份。
      const challenge = crypto.getRandomValues(new Uint8Array(32))
      const sealed = await crypto.subtle.encrypt(rsa, publicKey, challenge)
      const opened = new Uint8Array(await crypto.subtle.decrypt(rsa, privateKey, sealed))
      if (!opened.every((byte, index) => byte === challenge[index])) throw new Error('设备身份不匹配')
      requireLogin()
      const published = await request('/e2ee/identity', { method: 'PUT', body: { publicKey: vault.publicKey } })
      requireLogin()
      if (published.publicKey !== vault.publicKey || published.fingerprint !== await fingerprint(vault.publicKey)) {
        throw new Error('公钥登记响应不匹配，已停止加密操作')
      }
      identity = { userId, token, privateKey, publicKey: vault.publicKey, fingerprint: published.fingerprint }
      return identity.fingerprint
    } catch (cause) {
      if (cause.name === 'OperationError') throw new Error('本地密钥密码不正确或备份已损坏；密码重置后请使用原密码解锁')
      throw cause
    } finally {
      privateBytes?.fill(0)
    }
  }
  return navigator.locks ? navigator.locks.request(`mairy-e2ee-${userId}`, openVault) : openVault()
}

/** 展示自己设备的指纹，不暴露私钥。 */
export function identityFingerprint() {
  return currentIdentity().fingerprint
}

/** OAEP 标签绑定房间、协议和收件人，禁止跨房间复用密钥信封。 */
function wrapLabel(roomId, userId, hash) {
  return encoder.encode(`mairy:e2ee:v1:room:${roomId}:user:${userId}:key:${hash}`)
}

/** 只为经过完整指纹核验的目标公钥封装密钥。 */
export async function wrapRoomKey(roomId, key, recipient, verifiedFingerprint) {
  const me = currentIdentity()
  const hash = await fingerprint(recipient.publicKey)
  if (hash !== recipient.fingerprint || hash !== verifiedFingerprint.trim().toLowerCase()) {
    throw new Error('指纹不一致，请通过可信渠道向成员本人核验完整指纹')
  }
  const publicKey = await crypto.subtle.importKey('spki', fromBase64(recipient.publicKey), rsa, false, ['wrapKey'])
  const wrappedKey = await crypto.subtle.wrapKey('raw', key, publicKey,
    { name: 'RSA-OAEP', label: wrapLabel(roomId, recipient.userId, hash) })
  if (currentIdentity() !== me) throw new Error('设备身份已变化，请重试')
  return { fingerprint: hash, wrappedKey: toBase64(wrappedKey) }
}

/** 计算房间密钥指纹以识别恶意服务器自行生成的替代密钥。 */
async function contentKeyFingerprint(raw) {
  const hash = await crypto.subtle.digest('SHA-256', raw)
  return Array.from(new Uint8Array(hash), byte => byte.toString(16).padStart(2, '0')).join('')
}

/** 按当前账号和房间隔离本地信任指纹，不能从服务端信封自动信任。 */
function roomPinName(userId, roomId) {
  return 'mairy.e2ee.v1.room-pin.' + userId + '.' + roomId
}

/** 房主固定自己生成的密钥；成员首次必须核验房主本人提供的密钥指纹。 */
export async function loadRoomKey(roomId, token, verifiedRoomFingerprint = '') {
  const me = currentIdentity()
  let state = await request(`/e2ee/rooms/${roomId}`, { token })
  if (!state.wrappedKey && !state.initialized && state.ownerUserId === me.userId) {
    if (localStorage.getItem(roomPinName(me.userId, roomId))) {
      throw new Error('本设备已固定房间密钥，但服务器未返回信封；不能自动重新初始化')
    }
    const key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, ['encrypt', 'decrypt'])
    const ownRaw = new Uint8Array(await crypto.subtle.exportKey('raw', key))
    let ownPin
    try { ownPin = await contentKeyFingerprint(ownRaw) } finally { ownRaw.fill(0) }
    const body = await wrapRoomKey(roomId, key, { userId: me.userId, publicKey: me.publicKey,
      fingerprint: me.fingerprint }, me.fingerprint)
    try {
      await request(`/e2ee/rooms/${roomId}/members/${me.userId}`, { method: 'PUT', token, body })
      if (currentIdentity() !== me) throw new Error('设备身份已变化，请重试')
      // 只信任本机刚生成并成功提交的密钥，绝不自动信任服务器提供的密钥。
      const existingPin = localStorage.getItem(roomPinName(me.userId, roomId))
      if (existingPin && existingPin !== ownPin) throw new Error('房间已固定其他密钥，请停止操作')
      localStorage.setItem(roomPinName(me.userId, roomId), ownPin)
    } catch (cause) {
      // 并发初始化由服务端行锁裁决，始终重新读取已提交信封。
      if (cause.status !== 409) throw cause
    }
    state = await request(`/e2ee/rooms/${roomId}`, { token })
  }
  if (!state.wrappedKey) throw new Error(state.ownerUserId
    ? '尚无房间密钥，请联系房主核验指纹并授权后点击刷新密钥' : '旧房间没有账号房主，请创建新房间；旧明文仍可阅读')
  if (state.fingerprint !== me.fingerprint) throw new Error('房间信封与本设备身份不匹配')
  const raw = new Uint8Array(await crypto.subtle.decrypt({ name: 'RSA-OAEP',
    label: wrapLabel(roomId, me.userId, me.fingerprint) }, me.privateKey, fromBase64(state.wrappedKey)))
  try {
    if (raw.length !== 32) throw new Error('房间密钥格式无效')
    const hash = await contentKeyFingerprint(raw)
    const pinName = roomPinName(me.userId, roomId)
    const pinned = localStorage.getItem(pinName)
    const verified = verifiedRoomFingerprint.trim().toLowerCase()
    if (!pinned && !/^[a-f0-9]{64}$/.test(verified)) {
      throw new Error('首次使用或恢复设备后，请输入房主通过可信渠道提供的完整房间密钥指纹')
    }
    if ((pinned && pinned !== hash) || (verified && verified !== hash)) {
      throw new Error('房间密钥指纹不匹配，可能是服务器替换信封；已停止解密和发送')
    }
    const key = await crypto.subtle.importKey('raw', raw, 'AES-GCM', true, ['encrypt', 'decrypt'])
    if (currentIdentity() !== me) throw new Error('设备身份已变化，请重试')
    if (!pinned) localStorage.setItem(pinName, hash)
    return { key, ownerUserId: state.ownerUserId, fingerprint: hash }
  } finally {
    raw.fill(0)
  }
}

/** AES-GCM 认证数据绑定房间和发送会话，防止跨房间搬移密文。 */
function messageAad(roomId, memberId) {
  return encoder.encode(`mairy:e2ee:v1:message:${roomId}:member:${memberId}`)
}

/** 发送前仅在客户端加密正文，每次生成独立96位随机 IV。 */
export async function encryptMessage(key, roomId, memberId, text) {
  const me = currentIdentity()
  if (!key) throw new Error('缺少房间密钥，不能发送')
  if (!text.trim() || text.length > 1000) throw new Error('消息需为1至1000字')
  const iv = crypto.getRandomValues(new Uint8Array(12))
  const ciphertext = await crypto.subtle.encrypt({ name: 'AES-GCM', iv, tagLength: 128,
    additionalData: messageAad(roomId, memberId) }, key, encoder.encode(text))
  if (currentIdentity() !== me) throw new Error('设备身份已变化，请重试')
  return { enc: 1, content: toBase64(ciphertext), iv: toBase64(iv) }
}

/** 双读旧明文和新密文；异常密文、缺失密钥均显示友好占位。 */
export async function decryptMessage(key, roomId, message) {
  if (message.roomId !== roomId) return { ...message, displayContent: '消息房间不匹配' }
  if (message.enc === 0) return { ...message, displayContent: message.content, legacy: true }
  if (message.enc !== 1) return { ...message, displayContent: '不支持的消息加密版本' }
  if (!key || !isUnlocked()) return { ...message, displayContent: '已加密，当前设备无法解密' }
  try {
    const me = currentIdentity()
    const iv = fromBase64(message.iv)
    if (iv.length !== 12) throw new Error('IV无效')
    const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv, tagLength: 128,
      additionalData: messageAad(roomId, message.memberId) }, key, fromBase64(message.content))
    if (currentIdentity() !== me) throw new Error('设备身份已变化')
    return { ...message, displayContent: decoder.decode(plain) }
  } catch {
    return { ...message, displayContent: '已加密，密钥不匹配或消息校验失败' }
  }
}

/** 导出仍受原密码保护的设备备份，绝不导出明文私钥。 */
export function exportVault() {
  const me = currentIdentity()
  return localStorage.getItem(vaultName(me.userId))
}

/** 新设备导入加密备份，覆盖现有保险箱一律拒绝。 */
export function importVault(text) {
  if (!auth.user || auth.user.role !== 'USER') throw new Error('请用普通账号登录')
  if (text.length > 20000) throw new Error('备份文件过大')
  const vault = JSON.parse(text)
  if (vault.version !== 1 || vault.userId !== auth.user.id || vault.iterations !== rounds
      || typeof vault.publicKey !== 'string' || typeof vault.ciphertext !== 'string') throw new Error('备份账号或格式不匹配')
  const name = vaultName(auth.user.id)
  if (localStorage.getItem(name)) throw new Error('本设备已有保险箱，不能覆盖；请先保留原备份')
  localStorage.setItem(name, JSON.stringify(vault))
  lockIdentity()
}
