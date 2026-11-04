import assert from 'node:assert/strict'
import { webcrypto, createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import vm from 'node:vm'

/** 真实加载房间加密模块，仅替换身份登记与本地存储。 */
async function voiceDevice() {
  const source = await readFile(new URL('./e2ee.js', import.meta.url), 'utf8')
  const storage = new Map()
  const auth = { user: { id: '1', role: 'USER' }, token: 'test-voice-session' }
  let registered = null
  const context = vm.createContext({ crypto: webcrypto, TextEncoder, TextDecoder, btoa, atob, Blob,
    window: { addEventListener() {} }, navigator: {},
    localStorage: { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value) } })
  const module = new vm.SourceTextModule(source, { context })
  await module.link(specifier => {
    const name = specifier === '../auth' ? 'auth' : 'request'
    return new vm.SyntheticModule([name], function () {
      this.setExport(name, name === 'auth' ? auth : async (path, options = {}) => {
        assert.equal(path, '/e2ee/identity')
        if (options.method === 'PUT') registered = { ...options.body,
          fingerprint: createHash('sha256').update(Buffer.from(options.body.publicKey, 'base64')).digest('hex') }
        return registered
      })
    }, { context })
  })
  await module.evaluate()
  await module.namespace.unlockIdentity(webcrypto.randomUUID())
  return module.namespace
}

/** 覆盖大音频往返、AAD类型隔离、密文篡改和非明文降级。 */
test('room voice encrypts large audio and authenticates type, room, member and metadata', async () => {
  const api = await voiceDevice()
  const key = await webcrypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt'])
  const bytes = new Uint8Array(api.maxVoiceBytes).fill(42)
  const blob = new Blob([bytes], { type: 'audio/webm' })
  const envelope = await api.encryptVoice(key, '7', '11', blob, 60000)
  const message = { id: '1', roomId: '7', memberId: '11', ...envelope }
  const opened = await api.decryptMessage(key, '7', message)
  assert.equal(opened.duration, 60000)
  assert.equal(opened.voice.type, 'audio/webm')
  assert.deepEqual(new Uint8Array(await opened.voice.arrayBuffer()), bytes)
  assert.equal(envelope.kind, 'voice')
  assert.equal(envelope.enc, 1)
  assert.equal(Buffer.from(envelope.iv, 'base64').length, 12)
  const next = await api.encryptVoice(key, '7', '11', blob, 60000)
  assert.notEqual(next.iv, envelope.iv)
  const flipped = Buffer.from(envelope.content, 'base64'); flipped[0] ^= 1
  for (const altered of [{ ...message, kind: 'text' }, { ...message, roomId: '8' },
    { ...message, memberId: '12' }, { ...message, iv: next.iv }, { ...message, content: flipped.toString('base64') },
    { ...message, enc: 0 }, { ...message, kind: 'external' }]) {
    assert.equal((await api.decryptMessage(key, altered.roomId, altered)).voice, undefined)
  }
  assert.equal((await api.decryptMessage(null, '7', message)).voice, undefined)
  const injected = await api.decryptMessage(null, '7', { ...message, voice: blob, voiceUrl: 'https://invalid.example/audio', duration: 1 })
  assert.equal(injected.voice, undefined)
  assert.equal(injected.voiceUrl, undefined)
  const text = await api.encryptMessage(key, '7', '11', '旧文字仍兼容')
  assert.equal((await api.decryptMessage(key, '7', { roomId: '7', memberId: '11', ...text })).displayContent, '旧文字仍兼容')
  assert.equal((await api.decryptMessage(key, '7', { roomId: '7', memberId: '11', ...text, kind: 'voice' })).voice, undefined)
  for (const [invalidBlob, duration] of [[new Blob([]), 1], [new Blob([new Uint8Array(api.maxVoiceBytes + 1)], { type: blob.type }), 1],
    [new Blob(['bad'], { type: 'text/html' }), 1], [blob, 0], [blob, 60001], [blob, 1.5]]) {
    await assert.rejects(api.encryptVoice(key, '7', '11', invalidBlob, duration))
  }
  // 即使能生成合法GCM密文，解密端仍拒绝危险格式和超限元数据。
  for (const changes of [{ mime: 'text/html' }, { duration: 60001 }, { data: '' }, { version: 2 }]) {
    const payload = { version: 1, mime: 'audio/webm', duration: 1000, data: 'Kg==', ...changes }
    const iv = webcrypto.getRandomValues(new Uint8Array(12))
    const content = await webcrypto.subtle.encrypt({ name: 'AES-GCM', iv, tagLength: 128,
      additionalData: new TextEncoder().encode('mairy:e2ee:v1:message:7:member:11:kind:voice') }, key,
      new TextEncoder().encode(JSON.stringify(payload)))
    const result = await api.decryptMessage(key, '7', { ...message, iv: api.toBase64(iv), content: api.toBase64(content) })
    assert.equal(result.voice, undefined)
    assert.match(result.displayContent, /校验失败/)
  }
  api.lockIdentity()
  await assert.rejects(api.encryptVoice(key, '7', '11', blob, 1000), /未解锁/)
  assert.equal((await api.decryptMessage(key, '7', message)).voice, undefined)
})
