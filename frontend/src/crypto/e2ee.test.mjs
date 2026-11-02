import assert from 'node:assert/strict'
import { webcrypto, createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import vm from 'node:vm'

const source = await readFile(new URL('./e2ee.js', import.meta.url), 'utf8')

/** 隔离两台设备的真实生产模块，仅替换浏览器存储与网络边界。 */
async function device(userId, server, storage = new Map(), role = 'USER') {
  const auth = { user: { id: userId, role }, token: `test-session-${userId}` }
  const context = vm.createContext({ crypto: webcrypto, TextEncoder, TextDecoder, btoa, atob,
    window: { addEventListener() {} }, navigator: {},
    localStorage: { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value) } })
  const module = new vm.SourceTextModule(source, { context })
  await module.link(specifier => {
    const isAuth = specifier === '../auth'
    return new vm.SyntheticModule([isAuth ? 'auth' : 'request'], function () {
      this.setExport(isAuth ? 'auth' : 'request', isAuth ? auth : (path, options) => server(userId, path, options))
    }, { context })
  })
  await module.evaluate()
  return { api: module.namespace, storage, auth }
}

/** 内存中转服务只接收公开身份或密钥信封，不持有任何客户端私钥。 */
function relay() {
  const identities = new Map()
  const wraps = new Map()
  const received = []
  /** 模拟必要的密文中转契约，权限校验由后端集成测试覆盖。 */
  async function request(userId, path, options = {}) {
    const { method = 'GET', body } = options
    if (body) received.push(JSON.stringify(body))
    if (path === '/e2ee/identity') {
      if (method === 'GET') return identities.get(userId) || null
      assert.deepEqual(Object.keys(body), ['publicKey'])
      const record = { userId, publicKey: body.publicKey,
        fingerprint: createHash('sha256').update(Buffer.from(body.publicKey, 'base64')).digest('hex') }
      const previous = identities.get(userId)
      if (previous && previous.publicKey !== record.publicKey) throw Object.assign(new Error('immutable'), { status: 409 })
      identities.set(userId, record)
      return record
    }
    const grant = path.match(/^\/e2ee\/rooms\/(\d+)\/members\/(\d+)$/)
    if (grant) {
      assert.deepEqual(Object.keys(body).sort(), ['fingerprint', 'wrappedKey'])
      const name = `${grant[1]}:${grant[2]}`
      if (wraps.has(name)) throw Object.assign(new Error('immutable'), { status: 409 })
      wraps.set(name, body)
      return null
    }
    const room = path.match(/^\/e2ee\/rooms\/(\d+)$/)
    if (room) return { ownerUserId: '1', initialized: wraps.has(`${room[1]}:1`),
      ...wraps.get(`${room[1]}:${userId}`) }
    throw new Error('unexpected test request')
  }
  return { request, identities, wraps, received }
}

/** 真实 Web Crypto 往返验证及篡改、备份、密码重置和管理员隔离回归。 */
test('two devices exchange only ciphertext and reject tampering or missing identities', async () => {
  const server = relay()
  const owner = await device('1', server.request)
  const member = await device('2', server.request)
  // 只在测试内存生成临时密码，输出中不显示密码或密钥材料。
  const password = Buffer.from(webcrypto.getRandomValues(new Uint8Array(24))).toString('base64')
  const wrongPassword = Buffer.from(webcrypto.getRandomValues(new Uint8Array(24))).toString('base64')
  await owner.api.unlockIdentity(password)
  await member.api.unlockIdentity(password)
  const first = await owner.api.loadRoomKey('7', 'test-room')
  await assert.rejects(member.api.loadRoomKey('7', 'test-room'), /尚无房间密钥/)
  const recipient = server.identities.get('2')
  await assert.rejects(owner.api.wrapRoomKey('7', first.key, recipient, '0'.repeat(64)), /指纹不一致/)
  const wrapped = await owner.api.wrapRoomKey('7', first.key, recipient, member.api.identityFingerprint())
  await server.request('1', '/e2ee/rooms/7/members/2', { method: 'PUT', body: wrapped })
  await assert.rejects(member.api.loadRoomKey('7', 'test-room'), /完整房间密钥指纹/)
  const second = await member.api.loadRoomKey('7', 'test-room', first.fingerprint)
  const text = '中文与表情🔒'.repeat(70)
  const envelope = await owner.api.encryptMessage(first.key, '7', '11', text)
  const message = { id: '1', roomId: '7', memberId: '11', ...envelope }
  const opened = await member.api.decryptMessage(second.key, '7', message)
  assert.equal(opened.displayContent, text)
  assert.equal(envelope.enc, 1)
  assert.notEqual(envelope.content, text)
  assert.equal(Buffer.from(envelope.iv, 'base64').length, 12)
  const another = await owner.api.encryptMessage(first.key, '7', '11', text)
  assert.notEqual(envelope.iv, another.iv)
  assert.equal((await member.api.decryptMessage(null, '7', message)).displayContent, '已加密，当前设备无法解密')
  const flipped = Buffer.from(envelope.content, 'base64')
  flipped[0] ^= 1
  for (const altered of [{ ...message, content: flipped.toString('base64') },
    { ...message, memberId: '12' }, { ...message, iv: another.iv }]) {
    assert.match((await member.api.decryptMessage(second.key, '7', altered)).displayContent, /校验失败/)
  }
  assert.equal((await member.api.decryptMessage(second.key, '8', message)).displayContent, '消息房间不匹配')
  assert.equal((await member.api.decryptMessage(null, '7', { roomId: '7', enc: 0, content: '旧明文' })).displayContent, '旧明文')
  assert.equal((await member.api.decryptMessage(null, '7', { roomId: '7', enc: 2, content: text })).displayContent, '不支持的消息加密版本')
  assert.ok(server.received.every(payload => !payload.includes(text) && !payload.includes(password)))
  const backup = owner.api.exportVault()
  assert.deepEqual(Object.keys(JSON.parse(backup)).sort(),
    ['ciphertext', 'iterations', 'iv', 'publicKey', 'salt', 'userId', 'version'])
  owner.api.lockIdentity()
  await assert.rejects(owner.api.encryptMessage(first.key, '7', '11', text), /未解锁/)
  await assert.rejects(owner.api.unlockIdentity(wrongPassword), /本地密钥密码/)
  const refreshed = await device('1', server.request, owner.storage)
  await refreshed.api.unlockIdentity(password)
  const reloaded = await refreshed.api.loadRoomKey('7', 'test-room')
  assert.equal((await refreshed.api.decryptMessage(reloaded.key, '7', message)).displayContent, text)
  const missing = await device('1', server.request)
  await assert.rejects(missing.api.unlockIdentity(password), /没有原私钥/)
  missing.api.importVault(backup)
  await assert.rejects(missing.api.unlockIdentity(wrongPassword), /本地密钥密码/)
  await missing.api.unlockIdentity(password)
  await assert.rejects(missing.api.loadRoomKey('7', 'test-room'), /完整房间密钥指纹/)
  const restored = await missing.api.loadRoomKey('7', 'test-room', first.fingerprint)
  assert.equal((await missing.api.decryptMessage(restored.key, '7', message)).displayContent, text)
  assert.throws(() => missing.api.importVault(backup), /不能覆盖/)
  const admin = await device('3', server.request, new Map(), 'ADMIN')
  await assert.rejects(admin.api.unlockIdentity(password), /管理员不能/)
  // 同一身份的两个客户端并发初始化，最终必须解封同一已提交内容密钥。
  const anotherTab = await device('1', server.request, owner.storage)
  await anotherTab.api.unlockIdentity(password)
  const concurrent = await Promise.all([refreshed.api.loadRoomKey('9', 'test-room'), anotherTab.api.loadRoomKey('9', 'test-room')])
  const concurrentEnvelope = await refreshed.api.encryptMessage(concurrent[0].key, '9', '21', text)
  assert.equal((await anotherTab.api.decryptMessage(concurrent[1].key, '9',
    { roomId: '9', memberId: '21', ...concurrentEnvelope })).displayContent, text)
  // 恶意中转服务器用公开公钥封装自己已知的AES密钥，客户端必须拒绝替代信封。
  const attackerKey = await webcrypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, true, ['encrypt', 'decrypt'])
  /** 攻击者只需公开材料就能构造合法OAEP信封，因此必须核验独立信任的房间指纹。 */
  async function attackerEnvelope(recipient, roomId) {
    const publicKey = await webcrypto.subtle.importKey('spki', Buffer.from(recipient.publicKey, 'base64'),
      { name: 'RSA-OAEP', hash: 'SHA-256' }, false, ['wrapKey'])
    const label = new TextEncoder().encode('mairy:e2ee:v1:room:' + roomId + ':user:' + recipient.userId + ':key:' + recipient.fingerprint)
    const ciphertext = await webcrypto.subtle.wrapKey('raw', attackerKey, publicKey, { name: 'RSA-OAEP', label })
    return { fingerprint: recipient.fingerprint, wrappedKey: Buffer.from(ciphertext).toString('base64') }
  }
  server.wraps.set('7:2', await attackerEnvelope(recipient, '7'))
  await assert.rejects(member.api.loadRoomKey('7', 'test-room'), /房间密钥指纹不匹配/)
  // 首次初始化的房主也必须固定自己生成的密钥，不能信任服务器回传的新信封。
  const poisoned = await device('1', async (userId, path, options) => {
    const result = await server.request(userId, path, options)
    if (path === '/e2ee/rooms/10' && result.wrappedKey) {
      return { ...result, ...await attackerEnvelope(server.identities.get('1'), '10') }
    }
    return result
  }, owner.storage)
  await poisoned.api.unlockIdentity(password)
  await assert.rejects(poisoned.api.loadRoomKey('10', 'test-room'), /房间密钥指纹不匹配/)
  // 服务端替换已登记公钥时，本地解锁必须拒绝。
  const originalIdentity = server.identities.get('1')
  server.identities.set('1', recipient)
  await assert.rejects(refreshed.api.unlockIdentity(password), /公钥与本设备不符/)
  server.identities.set('1', originalIdentity)
  // 解锁等待期间切换登录，不应为新账号提交旧公钥。
  let switched
  switched = await device('4', async (userId, path, options) => {
    const result = await server.request(userId, path, options)
    switched.auth.user = { id: '5', role: 'USER' }
    switched.auth.token = 'changed-test-session'
    return result
  })
  await assert.rejects(switched.api.unlockIdentity(password), /登录状态已变化/)
  assert.equal(server.identities.has('4'), false)
  assert.equal(server.identities.has('5'), false)
  // 信封标签不同，原房间的信封搬到其他房间后不能解密。
  server.wraps.set('8:2', wrapped)
  await assert.rejects(member.api.loadRoomKey('8', 'test-room'))
})
