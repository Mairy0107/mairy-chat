import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import vm from 'node:vm'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as Vue from 'vue'

const { descriptor } = parse(await readFile(new URL('./AuthView.vue', import.meta.url), 'utf8'))
const compiled = compileScript(descriptor, { id: 'dm-test', inlineTemplate: true })

/** 内存渲染实际 Vue 页面，不启动浏览器，也不提供 Web Crypto。 */
async function page(mode, overrides = {}) {
  const route = Vue.reactive({ path: '/' + mode, query: {} })
  const calls = { requests: [], saved: [], unlocked: [], navigation: [] }
  const authApi = {
    request: async (...args) => { calls.requests.push(args); return mode === 'login'
      ? { token: 'http-login', user: { role: 'USER' } } : { message: '申请已提交' } },
    ...overrides,
  }
  const context = vm.createContext({
    window: { isSecureContext: false, matchMedia: () => ({ matches: true }) }, clearTimeout,
  })
  const module = new vm.SourceTextModule(compiled.content, { context })
  await module.link(specifier => {
    const exports = specifier === 'vue' ? { ...Vue, vModelText: {} }
      : specifier === 'vue-router' ? { useRoute: () => route, useRouter: () => ({
        push: async path => calls.navigation.push(path), replace: async path => calls.navigation.push(path),
      }) }
      : specifier === '../auth' ? { machineFingerprint: async () => 'a'.repeat(64), saveLogin: value => calls.saved.push(value) }
      : specifier === '../crypto/e2ee' ? { unlockIdentity: async () => calls.unlocked.push(true) } : authApi
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

/** HTTP 三种账号入口均提交成功，登录跳过设备密钥解锁。 */
test('HTTP register, login and forgot-password submit without Web Crypto', async t => {
  for (const mode of ['register', 'login', 'forgot-password']) {
    const view = await page(mode)
    await view.find(el => el.tag === 'form')[0].props.onSubmit({ preventDefault() {} })
    await settle()
    assert.equal(view.calls.requests[0][0], '/auth/' + mode)
    assert.equal(view.calls.requests[0][1].publicRequest, true)
    if (mode === 'register') assert.equal(view.calls.requests[0][1].body.machineFingerprint, 'a'.repeat(64))
    if (mode === 'login') {
      assert.equal(view.calls.saved.length, 1)
      assert.equal(view.calls.unlocked.length, 0)
      assert.deepEqual(view.calls.navigation, ['/app'])
    }
    if (mode === 'forgot-password') assert.match(text(view.root), /申请已提交/)
    view.app.unmount()
  }
})

/** 浏览器原始异常替换为友好中文，服务端配额提示仍保留。 */
test('auth errors hide raw TypeError and preserve Chinese quota errors', async () => {
  for (const message of ["Cannot read properties of undefined (reading 'digest')", '每浏览器最多注册3个账号']) {
    const view = await page('register', { request: async () => { throw new TypeError(message) } })
    await view.find(el => el.tag === 'form')[0].props.onSubmit({ preventDefault() {} })
    await settle()
    assert.doesNotMatch(text(view.root), /Cannot read|TypeError|digest/)
    assert.match(text(view.root), message.startsWith('每') ? /每浏览器最多注册3个账号/ : /操作未完成/)
    view.app.unmount()
  }
})
