import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import vm from 'node:vm'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as Vue from 'vue'

const { descriptor } = parse(await readFile(new URL('./DmChatView.vue', import.meta.url), 'utf8'))
const compiled = compileScript(descriptor, { id: 'dm-test', inlineTemplate: true })
const first = { id: '1', threadId: '7', senderId: '2', content: '你好', enc: 0, iv: null, createdAt: '2026-11-03T12:00:00' }

/** 内存渲染实际 Vue 页面，不启动浏览器，也不提供 Web Crypto。 */
async function page(overrides = {}) {
  const route = Vue.reactive({ params: { userId: '2' } })
  const calls = { sent: [], sockets: [], opened: [] }
  const api = {
    openDm: async userId => { calls.opened.push(userId); return { id: userId === '2' ? '7' : '8', peer: { id: userId, nickname: '好友' + userId, publicNo: '#000' + userId } } },
    listDmMessages: async () => ({ records: [first], hasMore: false, nextBeforeId: '1' }),
    sendDmMessage: async (...args) => { calls.sent.push(args); return { ...first, id: '2', senderId: '1', content: args[1] } },
    openDmSocket: () => { const socket = { readyState: 0, close() { this.closed = true } }; calls.sockets.push(socket); return socket },
    ...overrides,
  }
  const context = vm.createContext({ WebSocket: { OPEN: 1, CONNECTING: 0 } })
  const module = new vm.SourceTextModule(compiled.content, { context })
  await module.link(specifier => {
    const exports = specifier === 'vue' ? { ...Vue, vModelText: {} }
      : specifier === 'vue-router' ? { useRoute: () => route }
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
  return { root, app, route, calls, find }
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
