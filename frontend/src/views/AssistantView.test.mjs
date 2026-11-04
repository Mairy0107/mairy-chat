import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import vm from 'node:vm'
import { compileScript, parse } from '@vue/compiler-sfc'
import * as Vue from 'vue'

const { descriptor } = parse(await readFile(new URL('./AssistantView.vue', import.meta.url), 'utf8'))
const compiled = compileScript(descriptor, { id: 'assistant-test', inlineTemplate: true })
const row = { id: '7', title: '已有对话' }
const info = { enabled: true, quotaRemaining: 40, quotaLimit: 40, profileCompleted: false }

/** 内存渲染真实助手页面，复用项目测试方式，不启动浏览器。 */
async function page(overrides = {}) {
  const api = {
    getAiStatus: async () => ({ ...info }), listAiThreads: async () => [row],
    createAiThread: async () => ({ id: '8', title: null }),
    listAiMessages: async () => ({ records: [], hasMore: false }),
    streamAiMessage: async () => {}, ...overrides,
  }
  const context = vm.createContext({ AbortController, setTimeout, clearTimeout })
  const module = new vm.SourceTextModule(compiled.content, { context })
  await module.link(specifier => {
    const exports = specifier === 'vue' ? { ...Vue, vModelText: {} }
      : specifier === '../auth' ? { auth: { token: 'test-login' } } : api
    return new vm.SyntheticModule(Object.keys(exports), function () {
      for (const [name, value] of Object.entries(exports)) this.setExport(name, value)
    }, { context })
  })
  await module.evaluate()
  /** 节点提供滚动和焦点接口，事件全部来自实际模板。 */
  function node(tag, text = '') {
    return { tag, text, props: {}, children: [], parent: null, scrollTop: 0, scrollHeight: 100, clientHeight: 100, focus() {} }
  }
  const renderer = Vue.createRenderer({
    createElement: tag => node(tag), createText: text => node('#text', text), createComment: text => node('#comment', text),
    setText: (el, text) => { el.text = text }, setElementText: (el, text) => { el.text = text; el.children = [] },
    patchProp: (el, name, previous, value) => { el.props[name] = value },
    parentNode: el => el.parent, nextSibling: el => el.parent?.children[el.parent.children.indexOf(el) + 1] ?? null,
    insert: (el, parent, anchor = null) => {
      if (el.parent) el.parent.children.splice(el.parent.children.indexOf(el), 1)
      el.parent = parent
      const index = anchor ? parent.children.indexOf(anchor) : -1
      parent.children.splice(index < 0 ? parent.children.length : index, 0, el)
    },
    remove: el => { el.parent?.children.splice(el.parent.children.indexOf(el), 1); el.parent = null }, setScopeId() {},
  })
  const root = node('root')
  const app = renderer.createApp(module.namespace.default)
  app.component('RouterLink', { props: ['to'], setup: (props, { slots }) => () => Vue.h('a', { href: props.to }, slots.default?.()) })
  app.mount(root)
  await settle()
  /** 遍历全部节点以检查可见状态和实际操作。 */
  function find(predicate, parent = root) {
    return [parent, ...parent.children.flatMap(child => find(() => true, child))].filter(predicate)
  }
  return { app, root, find }
}

/** 等待 Vue 更新和短异步任务。 */
async function settle() {
  for (let i = 0; i < 3; i++) { await new Promise(resolve => setImmediate(resolve)); await Vue.nextTick() }
}

/** 只提取可见文本。 */
function text(node) { return (node.tag === '#comment' ? '' : node.text) + node.children.map(text).join('') }

/** 触发实际表单提交，返回进行中的异步发送。 */
function submit(view, content) {
  view.find(el => el.tag === 'textarea')[0].props['onUpdate:modelValue'](content)
  return view.find(el => el.tag === 'form')[0].props.onSubmit({ preventDefault() {} })
}

test('流增量立即显示，拒绝替换临时正文，完成后保留已保存消息', async t => {
  let emit
  let complete
  const view = await page({ streamAiMessage: async (id, content, signal, onEvent) => {
    emit = onEvent
    await new Promise(resolve => { complete = resolve })
  } })
  t.after(() => view.app.unmount())
  const pending = submit(view, '你好')
  await settle()
  emit('user', { id: '1', role: 'user', content: '你好' })
  emit('delta', { content: '这是临时输出' })
  await settle()
  assert.match(text(view.root), /这是临时输出/)
  assert.match(text(view.root), /停止生成/)
  emit('replace', { content: '安全拒绝' })
  emit('done', { message: { id: '2', role: 'assistant', content: '安全拒绝' }, quotaRemaining: 39 })
  complete()
  await pending
  await settle()
  assert.doesNotMatch(text(view.root), /这是临时输出|停止生成/)
  assert.equal(view.find(el => el.tag === 'article').length, 2)
  assert.match(text(view.root), /明文保存在服务器/)
})

test('停止中止读取并移除未确认输出', async t => {
  let signal
  const view = await page({ streamAiMessage: async (id, content, abort, emit) => {
    signal = abort
    emit('user', { id: '1', role: 'user', content })
    emit('delta', { content: '尚未保存' })
    await new Promise((resolve, reject) => abort.addEventListener('abort', () => reject(new Error('stopped'))))
  } })
  t.after(() => view.app.unmount())
  const pending = submit(view, '问题')
  await settle()
  view.find(el => el.tag === 'button' && text(el) === '停止生成')[0].props.onClick()
  await pending
  await settle()
  assert.equal(signal.aborted, true)
  assert.doesNotMatch(text(view.root), /尚未保存/)
  assert.match(text(view.root), /已停止/)
  assert.equal(view.find(el => el.tag === 'article').length, 1)
})

test('刷新同时更新配额状态，未配置时仍能看到已有会话', async t => {
  let enabled = false
  const view = await page({ getAiStatus: async () => ({ ...info, enabled }) })
  t.after(() => view.app.unmount())
  assert.match(text(view.root), /助手未配置|已有对话/)
  assert.equal(view.find(el => el.tag === 'button' && text(el) === '发送 →')[0].props.disabled, true)
  enabled = true
  await view.find(el => el.tag === 'button' && text(el) === '刷新')[0].props.onClick()
  await settle()
  assert.equal(view.find(el => el.tag === 'button' && text(el) === '发送 →')[0].props.disabled, false)
})
