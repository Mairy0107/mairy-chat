import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import vm from 'node:vm'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as Vue from 'vue'
import * as voice from '../media/dm-voice.js'

const { descriptor } = parse(await readFile(new URL('./DmChatView.vue', import.meta.url), 'utf8'))
const compiled = compileScript(descriptor, { id: 'dm-test', inlineTemplate: true })
const first = { id: '1', threadId: '7', senderId: '2', content: '你好', enc: 0, iv: null, createdAt: '2026-11-03T12:00:00' }

/** 内存渲染实际 Vue 页面，不启动浏览器，也不提供 Web Crypto。 */
async function page(overrides = {}, { secure = false, media = {} } = {}) {
  const route = Vue.reactive({ params: { userId: '2' } })
  const calls = { sent: [], sockets: [], opened: [], revoked: [] }
  const events = new Map()
  const api = {
    openDm: async userId => { calls.opened.push(userId); return { id: userId === '2' ? '7' : '8', peer: { id: userId, nickname: '好友' + userId, publicNo: '#000' + userId } } },
    listDmMessages: async () => ({ records: [first], hasMore: false, nextBeforeId: '1' }),
    sendDmMessage: async (...args) => { calls.sent.push(args); return { ...first, id: '2', senderId: '1', content: args[1], ...(typeof args[1] === 'object' ? args[1] : {}) } },
    openDmSocket: () => { const socket = { readyState: 0, close() { this.closed = true } }; calls.sockets.push(socket); return socket },
    ...overrides,
  }
  const context = vm.createContext({ WebSocket: { OPEN: 1, CONNECTING: 0 }, Blob, setTimeout, clearTimeout,
    window: { isSecureContext: secure, addEventListener: (name, fn) => events.set(name, fn), removeEventListener: name => events.delete(name) },
    URL: { createObjectURL: () => 'blob:dm-' + Math.random(), revokeObjectURL: url => calls.revoked.push(url) },
    ...media,
  })
  const module = new vm.SourceTextModule(compiled.content, { context })
  await module.link(specifier => {
    const exports = specifier === 'vue' ? { ...Vue, vModelText: {} }
      : specifier === 'vue-router' ? { useRoute: () => route }
      : specifier === '../media/dm-voice' ? voice
      : specifier === '../auth' ? { auth: { user: { id: '1' } } } : api
    return new vm.SyntheticModule(Object.keys(exports), function () {
      for (const [name, value] of Object.entries(exports)) this.setExport(name, value)
    }, { context })
  })
  await module.evaluate()
  /** 提供页面需要的最小节点、滚动接口与实际绑定事件。 */
  function node(tag, text = '') {
    return { tag, text, props: {}, children: [], parent: null, scrollHeight: 100, scrollTop: 0, clientHeight: 100, scrollTo() {} }
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
  const app = renderer.createApp(module.namespace.default)
  app.component('RouterLink', { props: ['to'], setup: (props, { slots }) => () => Vue.h('a', { href: props.to }, slots.default?.()) })
  app.mount(root)
  await settle()
  /** 遍历渲染节点以验证页面真实状态。 */
  function find(predicate, parent = root) {
    return [parent, ...parent.children.flatMap(child => find(() => true, child))].filter(predicate)
  }
  return { root, app, route, calls, find, events }
}

/** 等待请求与 Vue 更新完成。 */
async function settle() {
  for (let index = 0; index < 3; index++) {
    await new Promise(resolve => setImmediate(resolve))
    await Vue.nextTick()
  }
}

/** 只提取可见文本，不包括 Vue 注释。 */
function text(node) {
  return (node.tag === '#comment' ? '' : node.text) + node.children.map(text).join('')
}

/** 提交真实模板绑定的输入和表单。 */
async function send(view, value) {
  view.find(el => el.tag === 'textarea')[0].props['onUpdate:modelValue'](value)
  await view.find(el => el.tag === 'form')[0].props.onSubmit({ preventDefault() {} })
  await settle()
}

/** 无 Web Crypto 也可收发，REST 与推送去重，其他会话不进入当前页面。 */
test('plaintext DM works on HTTP and deduplicates realtime messages', async t => {
  const view = await page()
  t.after(() => view.app.unmount())
  assert.match(text(view.root), /服务器可读取内容/)
  assert.match(text(view.root), /你好/)
  const socket = view.calls.sockets[0]
  socket.readyState = 1
  socket.onopen()
  await settle()
  await send(view, '小白，你好')
  assert.deepEqual(Array.from(view.calls.sent[0]), ['7', '小白，你好'])
  socket.onmessage({ data: JSON.stringify({ type: 'dm-message', data: { ...first, id: '2', senderId: '1' } }) })
  socket.onmessage({ data: JSON.stringify({ type: 'dm-message', data: { ...first, id: '3', threadId: '999', content: '其他私信' } }) })
  await settle()
  assert.equal(view.find(el => el.tag === 'article').length, 2)
  assert.doesNotMatch(text(view.root), /其他私信/)
})

/** 非好友不能展示输入，服务端中文提示直接可见。 */
test('friend gate prevents opening a composer', async t => {
  const view = await page({ openDm: async () => { throw Object.assign(new Error('仅好友可私信'), { status: 403 }) } })
  t.after(() => view.app.unmount())
  assert.match(text(view.root), /仅好友可私信/)
  assert.equal(view.find(el => el.tag === 'textarea').length, 0)
  assert.equal(view.calls.sockets.length, 0)
})

/** 权限失效后，先前发起的历史请求返回也不能重新展示消息。 */
test('late history cannot restore messages after access is denied', async t => {
  let resolveHistory
  let delay = false
  const view = await page({
    listDmMessages: async () => delay ? new Promise(resolve => { resolveHistory = resolve }) : { records: [first], hasMore: false },
    sendDmMessage: async () => { throw Object.assign(new Error('仅好友可私信'), { status: 403 }) },
  })
  t.after(() => view.app.unmount())
  delay = true
  view.calls.sockets[0].onopen()
  await settle()
  await send(view, '测试')
  resolveHistory({ records: [first], hasMore: false })
  await settle()
  assert.equal(view.find(el => el.tag === 'article').length, 0)
  assert.equal(view.find(el => el.tag === 'textarea')[0].props.disabled, true)
  assert.equal(view.calls.sockets[0].closed, true)
})

/** 同组件切换好友时清理旧连接，忽略旧页面的发送及推送回包。 */
test('switching friends ignores stale send and socket results', async t => {
  let resolveSend
  const view = await page({
    listDmMessages: async id => ({ records: id === '7' ? [first] : [], hasMore: false }),
    sendDmMessage: async () => new Promise(resolve => { resolveSend = resolve }),
  })
  t.after(() => view.app.unmount())
  const oldSocket = view.calls.sockets[0]
  const input = view.find(el => el.tag === 'textarea')[0]
  input.props['onUpdate:modelValue']('上一位好友的草稿')
  const pending = view.find(el => el.tag === 'form')[0].props.onSubmit({ preventDefault() {} })
  await settle()
  view.route.params.userId = '3'
  await settle()
  assert.equal(oldSocket.closed, true)
  resolveSend({ ...first, id: '2', content: '旧请求内容' })
  await pending
  oldSocket.onmessage({ data: JSON.stringify({ type: 'dm-message', data: first }) })
  await settle()
  assert.match(text(view.root), /好友3/)
  assert.equal(view.find(el => el.tag === 'article').length, 0)
  assert.match(text(view.root), /0 \/ 1000/)
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

/** 按钮使用真实事件，避免通过脚本内部状态伪造录音流程。 */
async function click(view, label) {
  await view.find(el => el.tag === 'button' && text(el) === label)[0].props.onClick()
  await settle()
}

/** 本地预览后提交明文，REST与推送去重，播放仅采用本地Blob URL。 */
test('DM records, previews and sends plaintext voice without Crypto', async t => {
  const media = voiceMedia()
  const view = await page({}, { secure: true, media: media.globals })
  t.after(() => view.app.unmount())
  await click(view, '录语音')
  await send(view, '保留文字草稿')
  assert.equal(view.calls.sent.length, 0)
  await click(view, '结束录音')
  assert.ok(media.tracks.stopped)
  assert.equal(view.calls.sent.length, 0)
  assert.equal(view.find(el => el.tag === 'audio').length, 1)
  await click(view, '发送语音')
  const payload = view.calls.sent[0][1]
  assert.equal(payload.kind, 'voice')
  assert.equal(payload.mime, 'audio/webm')
  assert.equal(Buffer.from(payload.content, 'base64').length, 32)
  assert.equal(payload.enc, undefined)
  assert.equal(payload.iv, undefined)
  const playback = view.find(el => el.tag === 'audio')[0]
  assert.match(playback.props.src, /^blob:dm-/)
  assert.equal(playback.props.preload, 'none')
  assert.ok(text(view.root).includes('6 / 1000'))
  view.calls.sockets[0].onmessage({ data: JSON.stringify({ type: 'dm-message', data: {
    ...first, id: '2', ...payload, voiceUrl: 'https://invalid.example/audio',
  } }) })
  await settle()
  assert.equal(view.find(el => el.tag === 'article').length, 2)
  assert.equal(view.find(el => el.tag === 'audio').length, 1)
  assert.match(view.find(el => el.tag === 'audio')[0].props.src, /^blob:dm-/)
  await click(view, '刷新消息')
  assert.equal(view.find(el => el.tag === 'audio').length, 0)
  assert.equal(view.calls.revoked.length, 3)
})

/** HTTP 明确提示录音环境限制，文字仍可发送；收到的语音仍可播放。 */
test('HTTP DM keeps text and voice playback, but disables microphone', async t => {
  const payload = { ...first, kind: 'voice', mime: 'audio/webm', duration: 1000, content: 'Kg==' }
  const view = await page({ listDmMessages: async () => ({ records: [payload], hasMore: false }) })
  t.after(() => view.app.unmount())
  assert.match(text(view.root), /录音需HTTPS或localhost/)
  assert.equal(view.find(el => el.tag === 'button' && text(el) === '录语音')[0].props.disabled, true)
  await click(view, '录语音')
  assert.equal(view.find(el => el.tag === 'audio').length, 1)
  await send(view, 'HTTP文字')
  assert.equal(view.calls.sent.length, 1)
})

/** 拒绝权限、空音频、超限和迟到授权均不上传；切换好友清理旧麦克风。 */
test('DM voice rejects invalid recordings and stale permission', async t => {
  for (const options of [{ empty: true }, { oversized: true },
    { permission: () => Promise.reject(Object.assign(new Error(), { name: 'NotAllowedError' })) }]) {
    const media = voiceMedia(options)
    const view = await page({}, { secure: true, media: media.globals })
    t.after(() => view.app.unmount())
    await click(view, '录语音')
    view.find(el => el.tag === 'button' && text(el) === '结束录音')[0]?.props.onClick()
    await settle()
    assert.equal(view.find(el => el.tag === 'audio').length, 0)
    assert.equal(view.calls.sent.length, 0)
    assert.ok(view.find(el => el.props.role === 'alert').length)
  }
  let allow
  const media = voiceMedia({ permission: () => new Promise(resolve => { allow = resolve }) })
  const view = await page({}, { secure: true, media: media.globals })
  t.after(() => view.app.unmount())
  const pending = click(view, '录语音')
  await settle()
  view.route.params.userId = '3'
  await settle()
  allow(media.stream)
  await pending
  assert.equal(media.active, undefined)
  assert.equal(media.tracks.stopped, 1)
  assert.equal(view.calls.sent.length, 0)
})

/** 失败保留预览可重试，权限失效或退出登录清理预览及播放URL。 */
test('DM voice retry and login cleanup', async t => {
  const media = voiceMedia()
  let fails = true
  const view = await page({ sendDmMessage: async (id, payload) => {
    if (fails) throw new Error('发送失败')
    return { ...first, id: '2', ...payload }
  } }, { secure: true, media: media.globals })
  t.after(() => view.app.unmount())
  await click(view, '录语音')
  await click(view, '结束录音')
  await click(view, '发送语音')
  assert.match(text(view.root), /发送失败/)
  assert.equal(view.find(el => el.tag === 'audio').length, 1)
  fails = false
  await click(view, '发送语音')
  assert.equal(view.find(el => el.tag === 'audio').length, 1)
  view.events.get('mairy:lock-keys')()
  await settle()
  assert.equal(view.find(el => el.tag === 'audio').length, 0)
  assert.equal(view.calls.revoked.length, 2)
})

/** 切换好友与卸载后，旧发送结果及录音停止回调不能污染新页面。 */
test('DM voice ignores stale send and releases microphone on unmount', async () => {
  const media = voiceMedia()
  let resolveSend
  const view = await page({ sendDmMessage: async () => new Promise(resolve => { resolveSend = resolve }) },
    { secure: true, media: media.globals })
  await click(view, '录语音')
  await click(view, '结束录音')
  const pending = click(view, '发送语音')
  await settle()
  view.route.params.userId = '3'
  await settle()
  resolveSend({ ...first, id: '9', kind: 'voice', mime: 'audio/webm', duration: 1, content: 'Kg==' })
  await pending
  assert.equal(view.find(el => el.tag === 'audio').length, 0)
  await click(view, '录语音')
  view.app.unmount()
  await settle()
  assert.equal(media.active.state, 'inactive')
  assert.ok(media.tracks.stopped)
  assert.equal(view.events.size, 0)
})

/** 60 秒定时停止只生成本地预览；取消后撤销 URL 并恢复文字发送。 */
test('DM voice stops at the duration limit and cancels local preview', async t => {
  const media = voiceMedia()
  let stopRecording
  const cleared = []
  media.globals.setTimeout = (callback, delay) => {
    assert.equal(delay, voice.maxVoiceDuration)
    stopRecording = callback
    return 1
  }
  media.globals.clearTimeout = timer => cleared.push(timer)
  const view = await page({}, { secure: true, media: media.globals })
  t.after(() => view.app.unmount())
  await click(view, '录语音')
  stopRecording()
  await settle()
  assert.equal(media.active.state, 'inactive')
  assert.ok(media.tracks.stopped)
  assert.ok(cleared.includes(1))
  assert.equal(view.calls.sent.length, 0)
  assert.equal(view.find(el => el.tag === 'audio').length, 1)
  await click(view, '取消语音')
  assert.equal(view.find(el => el.tag === 'audio').length, 0)
  assert.equal(view.calls.revoked.length, 1)
  await send(view, '取消后文字')
  assert.equal(view.calls.sent[0][1], '取消后文字')
})

/** 语音发送收到 403 后清理草稿与历史音频，禁止后续录制和迟到推送。 */
test('DM voice clears all media when access is denied', async t => {
  const media = voiceMedia()
  const received = { ...first, kind: 'voice', mime: 'audio/webm', duration: 1, content: 'Kg==' }
  const view = await page({
    listDmMessages: async () => ({ records: [received], hasMore: false }),
    sendDmMessage: async () => { throw Object.assign(new Error('仅好友可私信'), { status: 403 }) },
  }, { secure: true, media: media.globals })
  t.after(() => view.app.unmount())
  await click(view, '录语音')
  await click(view, '结束录音')
  assert.equal(view.find(el => el.tag === 'audio').length, 2)
  await click(view, '发送语音')
  assert.equal(view.find(el => el.tag === 'audio').length, 0)
  assert.equal(view.calls.revoked.length, 2)
  assert.equal(view.calls.sockets[0].closed, true)
  assert.equal(view.find(el => el.tag === 'textarea')[0].props.disabled, true)
  view.calls.sockets[0].onmessage({ data: JSON.stringify({ type: 'dm-message', data: received }) })
  await click(view, '录语音')
  assert.equal(view.find(el => el.tag === 'audio').length, 0)
  assert.equal(media.active.state, 'inactive')
})

/** 其他标签页清空登录时释放正在使用的麦克风，旧停止事件不能恢复预览。 */
test('DM voice stops recording on cross-tab logout', async t => {
  const media = voiceMedia()
  const view = await page({}, { secure: true, media: media.globals })
  t.after(() => view.app.unmount())
  await click(view, '录语音')
  view.events.get('storage')({ key: null })
  await settle()
  assert.equal(media.active.state, 'inactive')
  assert.ok(media.tracks.stopped)
  assert.equal(view.find(el => el.tag === 'audio').length, 0)
  assert.equal(view.calls.sent.length, 0)
  assert.equal(view.calls.sockets[0].closed, true)
})
