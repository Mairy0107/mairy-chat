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

/** 内存渲染真实助手页面，可注入假时钟验证逐字展示，不启动浏览器。 */
async function page(overrides = {}, timers = { setTimeout, clearTimeout }) {
  const api = {
    getAiStatus: async () => ({ ...info }), listAiThreads: async () => [row],
    createAiThread: async () => ({ id: '8', title: null }),
    listAiMessages: async () => ({ records: [], hasMore: false }),
    streamAiMessage: async () => {}, ...overrides,
  }
  const context = vm.createContext({ AbortController, ...timers })
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

/** 每个页面独立推进假时钟，精确检查间隔和残留定时器。 */
function fakeClock() {
  let now = 0
  let nextId = 0
  const tasks = new Map()
  return {
    setTimeout: (callback, delay) => {
      const id = ++nextId
      tasks.set(id, { callback, at: now + delay })
      return id
    },
    clearTimeout: id => tasks.delete(id),
    get size() { return tasks.size },
    /** 按到期顺序执行回调，并等待 Vue 完成对应渲染。 */
    async advance(milliseconds) {
      const end = now + milliseconds
      while (true) {
        const next = [...tasks].sort((a, b) => a[1].at - b[1].at)[0]
        if (!next || next[1].at > end) break
        const [id, task] = next
        tasks.delete(id)
        now = task.at
        task.callback()
        await settle()
      }
      now = end
      await settle()
    },
  }
}

/** 读取当前逐字展示的正文，排除光标文案。 */
function liveText(view) {
  const article = view.find(el => el.props['aria-label'] === '助手正在回复')[0]
  return article ? text(article.children.find(el => el.tag === 'p')).replace(/▍$/, '') : null
}

/** 构造服务端最终确认事件。 */
function done(emit, content) {
  emit('done', { message: { id: '2', role: 'assistant', content }, quotaRemaining: 39 })
}

test('增量固定30ms逐字显示，replace清空旧队列，done仍等待逐字结束', async t => {
  const clock = fakeClock()
  let emit
  let complete
  const view = await page({ streamAiMessage: async (id, content, signal, onEvent) => {
    emit = onEvent
    await new Promise(resolve => { complete = resolve })
  } }, clock)
  t.after(() => view.app.unmount())
  const pending = submit(view, '你好')
  await settle()
  emit('user', { id: '1', role: 'user', content: '你好' })
  emit('delta', { content: '这是临时输出' })
  await clock.advance(29)
  assert.equal(liveText(view), '')
  await clock.advance(1)
  assert.equal(liveText(view), '这')
  emit('delta', { content: '更多积压' })
  await clock.advance(30)
  assert.equal(liveText(view), '这是')
  emit('replace', { content: '安全拒绝' })
  done(emit, '安全拒绝')
  complete()
  await settle()
  assert.equal(liveText(view), '')
  assert.equal(view.find(el => el.tag === 'article').length, 2)
  await clock.advance(29)
  assert.equal(liveText(view), '')
  await clock.advance(1)
  assert.equal(liveText(view), '安')
  assert.match(text(view.root), /停止生成/)
  await clock.advance(60)
  assert.equal(liveText(view), '安全拒')
  await clock.advance(30)
  await pending
  await settle()
  assert.equal(liveText(view), null)
  assert.doesNotMatch(text(view.root), /这是临时输出|更多积压|停止生成|DeepSeek/i)
  assert.equal(view.find(el => el.tag === 'article').length, 2)
  assert.match(text(view.root), /安全拒绝/)
  assert.match(text(view.root), /对话以明文保存在服务器，并发送至云端模型。请勿输入密码、密钥或隐私。/)
  assert.equal(clock.size, 0)
})

test('done保留已显示前缀，积压内容和emoji继续以30ms每字展示', async t => {
  const clock = fakeClock()
  let emit
  let complete
  const view = await page({ streamAiMessage: async (id, content, signal, onEvent) => {
    emit = onEvent
    await new Promise(resolve => { complete = resolve })
  } }, clock)
  t.after(() => view.app.unmount())
  const pending = submit(view, '问题')
  await settle()
  emit('delta', { content: '你😀好世界' })
  await clock.advance(30)
  assert.equal(liveText(view), '你')
  done(emit, '你😀好世界')
  complete()
  await clock.advance(29)
  assert.equal(liveText(view), '你')
  await clock.advance(1)
  assert.equal(liveText(view), '你😀')
  await clock.advance(60)
  assert.equal(liveText(view), '你😀好世')
  await clock.advance(30)
  await pending
  await settle()
  assert.match(text(view.root), /你😀好世界/)
  assert.equal(liveText(view), null)
  assert.equal(clock.size, 0)
})

/** 由测试主体推进假时钟，避免嵌套settle尚未结束便检查done结果。 */
test('done正文修正时清除旧队列并重新逐字展示', async t => {
  const clock = fakeClock()
  let emit
  const view = await page({ streamAiMessage: async (id, content, signal, callback) => {
    emit = callback
    emit('delta', { content: '旧文本' })
  } }, clock)
  t.after(() => view.app.unmount())
  const pending = submit(view, '问题')
  await settle()
  await clock.advance(30)
  assert.equal(liveText(view), '旧')
  done(emit, '新正文')
  await settle()
  assert.equal(liveText(view), '')
  await clock.advance(30)
  assert.equal(liveText(view), '新')
  await clock.advance(60)
  await pending
  assert.doesNotMatch(text(view.root), /旧文本/)
  assert.match(text(view.root), /新正文/)
  assert.equal(clock.size, 0)
})

for (const finalReceived of [false, true]) {
  test('停止清理队列和timer，忽略迟到事件：' + (finalReceived ? 'done后' : '流期间'), async t => {
    const clock = fakeClock()
    let signal
    let emit
    const view = await page({ streamAiMessage: async (id, content, abort, onEvent) => {
      signal = abort
      emit = onEvent
      emit('user', { id: '1', role: 'user', content })
      emit('delta', { content: '尚未保存' })
      if (finalReceived) done(emit, '尚未保存')
      else await new Promise((resolve, reject) => abort.addEventListener('abort', () => reject(new Error('stopped'))))
    } }, clock)
    t.after(() => view.app.unmount())
    const pending = submit(view, '问题')
    await clock.advance(30)
    assert.equal(liveText(view), '尚')
    view.find(el => el.tag === 'button' && text(el) === '停止生成')[0].props.onClick()
    emit('delta', { content: '迟到内容' })
    done(emit, '迟到确认')
    await pending
    await clock.advance(30000)
    assert.equal(signal.aborted, true)
    assert.doesNotMatch(text(view.root), /尚未保存|迟到内容|迟到确认|停止生成/)
    assert.match(text(view.root), /已停止/)
    assert.equal(view.find(el => el.tag === 'article').length, 1)
    assert.equal(clock.size, 0)
  })

  test('卸载中止请求并清理全部timer：' + (finalReceived ? 'done后' : '流期间'), async () => {
    const clock = fakeClock()
    let signal
    const view = await page({ streamAiMessage: async (id, content, abort, emit) => {
      signal = abort
      emit('delta', { content: '剩余内容' })
      if (finalReceived) done(emit, '剩余内容')
      else await new Promise((resolve, reject) => abort.addEventListener('abort', () => reject(new Error('stopped'))))
    } }, clock)
    const pending = submit(view, '问题')
    await clock.advance(30)
    assert.equal(liveText(view), '剩')
    view.app.unmount()
    await pending
    assert.equal(signal.aborted, true)
    assert.equal(clock.size, 0)
    await clock.advance(30000)
    assert.equal(clock.size, 0)
  })
}

test('流中断丢弃未确认文本并清理队列和timer', async t => {
  const clock = fakeClock()
  let interrupt
  const view = await page({ streamAiMessage: async (id, content, signal, emit) => {
    emit('user', { id: '1', role: 'user', content })
    emit('delta', { content: '未确认回复' })
    await new Promise((resolve, reject) => { interrupt = reject })
  } }, clock)
  t.after(() => view.app.unmount())
  const pending = submit(view, '问题')
  await clock.advance(30)
  assert.equal(liveText(view), '未')
  interrupt(new Error('回复已中断，请刷新对话后重试'))
  await pending
  await clock.advance(30000)
  assert.equal(liveText(view), null)
  assert.doesNotMatch(text(view.root), /未确认回复/)
  assert.match(text(view.root), /回复已中断/)
  assert.equal(view.find(el => el.tag === 'article').length, 1)
  assert.equal(clock.size, 0)
})

test('只有done也逐字展示，阅读历史时保持滚动位置', async t => {
  const clock = fakeClock()
  const view = await page({ streamAiMessage: async (id, content, signal, emit) => done(emit, '最终正文') }, clock)
  t.after(() => view.app.unmount())
  const pending = submit(view, '问题')
  await settle()
  const list = view.find(el => el.props.class === 'assistant-messages')[0]
  list.scrollHeight = 1000
  list.scrollTop = 100
  list.props.onScroll()
  await clock.advance(30)
  assert.equal(liveText(view), '最')
  assert.equal(list.scrollTop, 100)
  await clock.advance(90)
  await pending
  assert.equal(list.scrollTop, 100)
  assert.equal(clock.size, 0)
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
