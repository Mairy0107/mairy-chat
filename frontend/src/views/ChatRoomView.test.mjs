import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import vm from 'node:vm'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as Vue from 'vue'

const source = await readFile(new URL('./ChatRoomView.vue', import.meta.url), 'utf8')
const { descriptor } = parse(source)
const compiled = compileScript(descriptor, { id: 'chat-room-test', inlineTemplate: true })
const fingerprint = 'a'.repeat(64)

/** 使用 Vue 内存渲染器检查页面状态与事件，不启动浏览器或打包前端。 */
async function room({ secure = true, unlocked = false, verify = false, role = 'USER', candidates = [], media = null, sendFails = false } = {}) {
  const calls = { voice: [], revoked: [], unlock: [], load: [], sent: [], grants: [], imported: [], decrypted: [] }
  let needsVerification = verify
  const key = {}
  const socket = { readyState: 1, close() {} }
  const auth = { user: { id: '1', role } }
  const history = { id: '1', roomId: '7', memberId: '2', nickname: '成员', enc: 1, createdAt: '2026-11-03T12:00:00' }
  const api = {
    listMessages: async () => ({ records: [history], hasMore: false, nextBeforeId: null }),
    openChatSocket: () => socket,
    sendMessage: async (...args) => { calls.sent.push(args); if (sendFails) throw new Error('发送失败'); return { ...history, id: '2', memberId: '11', kind: args[2].kind } },
    request: async (path, options) => {
      if (path.endsWith('/candidates')) return candidates.map(row => ({ ...row }))
      calls.grants.push([path, options])
    },
  }
  const crypto = {
    isUnlocked: () => unlocked,
    identityFingerprint: () => fingerprint,
    unlockIdentity: async password => {
      calls.unlock.push(password)
      if (!password) throw new Error('请输入登录密码')
      unlocked = true
    },
    loadRoomKey: async (...args) => {
      calls.load.push(args)
      if (needsVerification && args[2] !== fingerprint) throw new Error('首次使用或恢复设备后，请输入房主通过可信渠道提供的完整房间密钥指纹')
      needsVerification = false
      return { key, fingerprint, ownerUserId: '1' }
    },
    encryptMessage: async (...args) => { assert.equal(args[0], key); return { enc: 1, content: 'ciphertext', iv: 'iv' } },
    decryptMessage: async (roomKey, roomId, message) => {
      calls.decrypted.push(roomKey)
      if (roomKey && message.kind === 'voice') return { ...message, displayContent: '加密语音',
        voice: new Blob(['test-audio'], { type: 'audio/webm' }), duration: 1000 }
      return { ...message, displayContent: roomKey ? '已解密内容' : '已加密，当前设备无法解密' }
    },
    maxVoiceBytes: 512 * 1024, maxVoiceDuration: 60000, voiceMimeTypes: ['audio/webm'],
    encryptVoice: async (...args) => { calls.voice.push(args); assert.equal(args[0], key); return { kind: 'voice', enc: 1, content: 'voice-ciphertext', iv: 'voice-iv' } },
    exportVault: () => '{}',
    importVault: text => { calls.imported.push(text); unlocked = false },
    wrapRoomKey: async (roomId, roomKey, candidate, verified) => {
      assert.equal(roomKey, key)
      assert.equal(verified, candidate.fingerprint)
      return { fingerprint: verified, wrappedKey: 'wrapped' }
    },
  }
  const events = new Map()
  const context = vm.createContext({
    window: { isSecureContext: secure, addEventListener: (name, fn) => events.set(name, fn), removeEventListener: name => events.delete(name) },
    WebSocket: { OPEN: 1, CONNECTING: 0 }, Blob, setTimeout, clearTimeout,
    URL: { createObjectURL: () => 'blob:local-' + Math.random(), revokeObjectURL: url => calls.revoked.push(url) },
    ...(media || {}),
  })
  const module = new vm.SourceTextModule(compiled.content, { context })
  await module.link(specifier => {
    // 双向绑定由测试直接触发更新事件，避免依赖浏览器 DOM 指令实现。
    const exports = specifier === 'vue' ? { ...Vue, vModelText: {} }
      : specifier === '../auth' ? { auth } : specifier === '../api/chat' ? api : crypto
    return new vm.SyntheticModule(Object.keys(exports), function () {
      for (const [name, value] of Object.entries(exports)) this.setExport(name, value)
    }, { context })
  })
  await module.evaluate()
  /** 提供原生对话框的最小接口，保留 Vue 实际渲染的事件和属性。 */
  function node(tag, text = '') {
    return { tag, text, props: {}, children: [], parent: null, open: false,
      showModal() { this.open = true },
      close() { this.open = false; this.props.onClose?.() },
      scrollTo() {},
    }
  }
  const renderer = Vue.createRenderer({
    createElement: tag => node(tag), createText: text => node('#text', text), createComment: text => node('#comment', text),
    setText: (el, text) => { el.text = text },
    setElementText: (el, text) => { el.text = text; el.children = [] },
    patchProp: (el, name, previous, value) => { el.props[name] = value },
    parentNode: el => el.parent,
    nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1] ?? null,
    insert: (el, parent, anchor = null) => {
      if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1)
      el.parent = parent
      const index = anchor ? parent.children.indexOf(anchor) : -1
      parent.children.splice(index < 0 ? parent.children.length : index, 0, el)
    },
    remove: el => { el.parent?.children.splice(el.parent.children.indexOf(el), 1); el.parent = null },
    setScopeId() {},
  })
  const root = node('root')
  const app = renderer.createApp(module.namespace.default, { chat: { room: { id: '7', name: '房间' }, member: { id: '11', token: 'room-token' } } })
  app.mount(root)
  await settle()
  /** 遍历实际渲染节点，便于检查条件显示和禁用原因。 */
  function find(predicate, parent = root) {
    return [parent, ...parent.children.flatMap(child => find(() => true, child))].filter(predicate)
  }
  return { root, find, calls, socket, app, events }
}

/** 等待页面挂载、密钥刷新和历史刷新依次完成。 */
async function settle() {
  for (let index = 0; index < 4; index++) {
    await new Promise(resolve => setImmediate(resolve))
    await Vue.nextTick()
  }
}

/** 提取渲染文本，忽略 Vue 条件占位注释。 */
function text(node) {
  return (node.tag === '#comment' ? '' : node.text) + node.children.map(text).join('')
}

/** 模拟表单提交，保留模板绑定的阻止默认提交行为。 */
async function submit(form) {
  await form.props.onSubmit({ preventDefault() {} })
  await settle()
}

/** HTTP 下显示唯一安全环境提示，按钮和事件入口均禁止解锁。 */
test('HTTP disables unlock with a visible reason and keeps history available', async t => {
  const view = await room({ secure: false })
  t.after(() => view.app.unmount())
  const dialog = view.find(el => el.tag === 'dialog')[0]
  assert.equal(dialog.open, false)
  const entry = view.find(el => el.tag === 'button' && text(el) === '设备密钥')
  assert.equal(entry.length, 1)
  entry[0].props.onClick()
  assert.equal(dialog.open, true)
  const warnings = view.find(el => el.props.role === 'alert')
  assert.equal(warnings.length, 1)
  assert.match(text(warnings[0]), /HTTPS.*localhost/)
  const password = view.find(el => el.props.type === 'password')[0]
  assert.equal(password.props.disabled, true)
  assert.equal(password.props['aria-describedby'], warnings[0].props.id)
  assert.equal(view.find(el => el.tag === 'button' && text(el) === '解锁')[0].props.disabled, true)
  await submit(view.find(el => el.tag === 'form', dialog)[0])
  assert.deepEqual(view.calls.unlock, [])
  assert.deepEqual(view.calls.load, [])
  assert.match(text(view.root), /已加密，当前设备无法解密/)
})

/** 安全环境解锁后沿用原密钥收发，关闭窗口及时清空密码。 */
test('secure unlock keeps encrypted REST and socket chat working', async t => {
  const view = await room()
  t.after(() => view.app.unmount())
  const dialog = view.find(el => el.tag === 'dialog')[0]
  const password = view.find(el => el.props.type === 'password')[0]
  password.props['onUpdate:modelValue']('temporary-password')
  dialog.close()
  await submit(view.find(el => el.tag === 'form', dialog)[0])
  assert.equal(view.calls.unlock[0], '')
  assert.equal(view.calls.load.length, 0)
  view.find(el => el.props.type === 'password')[0].props['onUpdate:modelValue']('temporary-password')
  await submit(view.find(el => el.tag === 'form', dialog)[0])
  assert.equal(view.calls.unlock[1], 'temporary-password')
  assert.equal(view.calls.load.length, 1)
  assert.match(text(view.root), /已解密内容/)
  assert.equal(view.find(el => el.props.type === 'password').length, 0)
  const sections = view.find(el => el.tag === 'details', dialog)
  assert.ok(sections.every(el => !el.props.open))
  view.find(el => el.tag === 'textarea')[0].props['onUpdate:modelValue']('你好')
  await submit(view.find(el => el.tag === 'form' && el.props.class === 'composer')[0])
  assert.equal(view.calls.sent.length, 1)
  assert.deepEqual(JSON.parse(JSON.stringify(view.calls.sent[0])), ['7', 'room-token', { enc: 1, content: 'ciphertext', iv: 'iv' }])
  await view.socket.onmessage({ data: JSON.stringify({ type: 'message', data: { id: '3', roomId: '7', memberId: '2', nickname: '成员', createdAt: '2026-11-03T12:00:00' } }) })
  await settle()
  assert.equal(view.find(el => el.tag === 'article').length, 3)
  assert.ok(view.calls.decrypted.some(key => key !== null))
})

/** 首次验证继续传递完整指纹，导入备份后回到未解锁状态。 */
test('room verification and backup import preserve the existing flow', async t => {
  const view = await room({ unlocked: true, verify: true })
  t.after(() => view.app.unmount())
  const dialog = view.find(el => el.tag === 'dialog')[0]
  const verifyInput = view.find(el => el.props.placeholder === '通过其他可信渠道向房主获取')[0]
  assert.ok(verifyInput)
  verifyInput.props['onUpdate:modelValue'](fingerprint)
  await submit(view.find(el => el.tag === 'form', dialog)[0])
  assert.equal(view.calls.load.at(-1)[2], fingerprint)
  assert.equal(view.find(el => el.props.placeholder === '通过其他可信渠道向房主获取').length, 0)
  const file = view.find(el => el.props.type === 'file')[0]
  const target = { files: [{ size: 2, text: async () => '{}' }], value: 'backup.json' }
  await file.props.onChange({ target })
  await settle()
  assert.deepEqual(view.calls.imported, ['{}'])
  assert.equal(target.value, '')
  assert.equal(view.find(el => el.props.type === 'password').length, 1)
  assert.match(text(view.root), /已加密，当前设备无法解密/)
})

/** 房主仍需提交成员本人核验的指纹，管理员没有解锁入口。 */
test('member grants retain fingerprint verification and admin stays read only', async t => {
  const view = await room({ unlocked: true, candidates: [{ userId: '2', nickname: '成员', fingerprint }] })
  t.after(() => view.app.unmount())
  const dialog = view.find(el => el.tag === 'dialog')[0]
  const input = view.find(el => el.tag === 'input' && el.props.maxlength === '64', dialog)[0]
  input.props['onUpdate:modelValue'](fingerprint)
  await submit(view.find(el => el.tag === 'form', dialog)[0])
  assert.equal(view.calls.grants[0][0], '/e2ee/rooms/7/members/2')
  assert.equal(view.calls.grants[0][1].body.fingerprint, fingerprint)
  assert.equal(view.find(el => el.tag === 'input' && el.props.maxlength === '64', dialog).length, 0)
  const admin = await room({ role: 'ADMIN' })
  t.after(() => admin.app.unmount())
  assert.equal(admin.find(el => el.props.type === 'password').length, 0)
  assert.deepEqual(admin.calls.load, [])
})

/** 模拟录制事件和媒体设备，检查页面真实按钮、草稿及清理行为。 */
function voiceMedia({ permission, empty = false, oversized = false } = {}) {
  const tracks = { stopped: 0, stop() { this.stopped++ } }
  const stream = { getTracks: () => [tracks] }
  let active
  class Recorder {
    static isTypeSupported(type) { return type === 'audio/webm' }
    constructor(stream, options) { this.mimeType = options.mimeType; this.state = 'inactive'; active = this }
    start() { this.state = 'recording' }
    stop() {
      this.state = 'inactive'
      queueMicrotask(() => {
        this.ondataavailable?.({ data: new Blob([new Uint8Array(empty ? 0 : oversized ? 512 * 1024 + 1 : 32)], { type: this.mimeType }) })
        this.onstop?.()
      })
    }
  }
  return { tracks, stream, get active() { return active }, globals: { MediaRecorder: Recorder,
    navigator: { mediaDevices: { getUserMedia: () => permission ? permission() : Promise.resolve(stream) } } } }
}

/** 录完不自动上传，成功发送只含密文，离开时释放预览。 */
test('room records, previews and sends only encrypted voice', async t => {
  const media = voiceMedia()
  const view = await room({ unlocked: true, media: media.globals })
  t.after(() => view.app.unmount())
  const button = label => view.find(el => el.tag === 'button' && text(el) === label)[0]
  await button('录语音').props.onClick()
  assert.equal(media.active.state, 'recording')
  button('结束录音').props.onClick()
  await settle()
  assert.equal(media.tracks.stopped > 0, true)
  assert.equal(view.calls.sent.length, 0)
  assert.equal(view.find(el => el.tag === 'audio').length, 1)
  await button('发送语音').props.onClick()
  await settle()
  assert.equal(view.calls.voice.length, 1)
  assert.equal(view.calls.sent.length, 1)
  assert.deepEqual(JSON.parse(JSON.stringify(view.calls.sent[0][2])), { kind: 'voice', enc: 1, content: 'voice-ciphertext', iv: 'voice-iv' })
  const playback = view.find(el => el.tag === 'audio')[0]
  assert.equal(playback.props['aria-label'], '成员的语音')
  assert.equal(playback.props.preload, 'none')
  await button('刷新消息').props.onClick()
  await settle()
  assert.equal(view.find(el => el.tag === 'audio').length, 0)
  assert.equal(view.calls.revoked.length, 2)
})

/** 取消授权等待后不启录；拒绝权限、空录音和超限音频不发送。 */
test('room voice handles permission, cancel, empty and oversize without uploads', async t => {
  let allow
  const media = voiceMedia({ permission: () => new Promise(resolve => { allow = resolve }) })
  const view = await room({ unlocked: true, media: media.globals })
  t.after(() => view.app.unmount())
  const start = view.find(el => el.tag === 'button' && text(el) === '录语音')[0].props.onClick()
  await settle()
  view.find(el => el.tag === 'button' && text(el) === '取消语音')[0].props.onClick()
  allow(media.stream)
  await start
  assert.equal(media.active, undefined)
  assert.equal(media.tracks.stopped, 1)
  assert.equal(view.calls.sent.length, 0)
  for (const options of [{ empty: true }, { oversized: true },
    { permission: () => Promise.reject(Object.assign(new Error(), { name: 'NotAllowedError' })) }]) {
    const otherMedia = voiceMedia(options)
    const other = await room({ unlocked: true, media: otherMedia.globals })
    t.after(() => other.app.unmount())
    await other.find(el => el.tag === 'button' && text(el) === '录语音')[0].props.onClick()
    other.find(el => el.tag === 'button' && text(el) === '结束录音')[0]?.props.onClick()
    await settle()
    assert.equal(other.find(el => el.tag === 'audio').length, 0)
    assert.equal(other.calls.sent.length, 0)
    assert.ok(other.find(el => el.props.role === 'alert').length)
  }
})

/** 发送失败保留草稿；锁定清除预览，卸载也结束正在录制的设备。 */
test('room voice retains failed draft and clears audio on lock and unmount', async t => {
  const media = voiceMedia()
  const view = await room({ unlocked: true, media: media.globals, sendFails: true })
  t.after(() => view.app.unmount())
  await view.find(el => el.tag === 'button' && text(el) === '录语音')[0].props.onClick()
  view.find(el => el.tag === 'button' && text(el) === '结束录音')[0].props.onClick()
  await settle()
  await view.find(el => el.tag === 'button' && text(el) === '发送语音')[0].props.onClick()
  await settle()
  assert.equal(view.find(el => el.tag === 'audio').length, 1)
  view.events.get('mairy:lock-keys')()
  await settle()
  assert.equal(view.find(el => el.tag === 'audio').length, 0)
  assert.equal(view.calls.revoked.length, 1)
  assert.equal(view.find(el => el.tag === 'button' && text(el) === '录语音')[0].props.disabled, true)
  const otherMedia = voiceMedia()
  const other = await room({ unlocked: true, media: otherMedia.globals })
  await other.find(el => el.tag === 'button' && text(el) === '录语音')[0].props.onClick()
  other.app.unmount()
  await settle()
  assert.ok(otherMedia.tracks.stopped)
  assert.equal(other.calls.sent.length, 0)
})
