<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { createAiThread, getAiStatus, listAiMessages, listAiThreads, streamAiMessage } from '../api/ai'
import { auth } from '../auth'

const threads = ref([])
const thread = ref(null)
const messages = ref([])
const draft = ref('')
const error = ref('')
const notice = ref('')
const status = ref(null)
const loading = ref(false)
const loadingOlder = ref(false)
const sending = ref(false)
const hasMore = ref(false)
const beforeId = ref(null)
const messageList = ref(null)
const composer = ref(null)
const live = ref(null)
const waitingLong = ref(false)
let controller
let waitingTimer
let cancelTypewriter
let generation = 0
let disposed = false
let follow = true
const ready = computed(() => status.value?.enabled && status.value.quotaRemaining > 0)
const helper = computed(() => !status.value ? '正在读取助手状态' : !status.value.enabled ? '助手未配置'
  : status.value.quotaRemaining <= 0 ? '今日次数已用完，明天再来' : 'Enter 发送 · Shift+Enter 换行')

/** 页面卸载或退出后中止请求，旧回包不得再修改当前页面。 */
onBeforeUnmount(() => { disposed = true; ++generation; controller?.abort(); clearTimeout(waitingTimer); clearTypewriter() })

/** 清除逐字展示队列和定时器，并结束等待，防止旧内容继续写入。 */
function clearTypewriter() {
  cancelTypewriter?.()
  cancelTypewriter = undefined
}

/** 仅在用户接近底部时跟随流，阅读历史时保留位置。 */
function trackScroll() {
  const list = messageList.value
  follow = !list || list.scrollHeight - list.scrollTop - list.clientHeight < 80
}

/** 跟随新内容，不给逐字输出增加动画延迟。 */
async function scrollEnd(force = false) {
  await nextTick()
  if ((follow || force) && messageList.value) messageList.value.scrollTop = messageList.value.scrollHeight
}

/** 读取状态和会话，失败后保留明确的刷新入口。 */
async function load() {
  if (loading.value || sending.value) return
  const version = ++generation
  loading.value = true
  loadingOlder.value = false
  error.value = ''
  try {
    const [info, rows] = await Promise.all([getAiStatus(), listAiThreads()])
    if (disposed || version !== generation) return
    status.value = info
    threads.value = rows
    const selected = thread.value || rows[0]
    if (selected) await selectThread(selected, true)
  } catch (cause) { if (!disposed && version === generation) error.value = cause.message }
  finally { if (!disposed) loading.value = false }
}

/** 切换对话时清空旧消息并丢弃先前异步历史结果。 */
async function selectThread(row, initial = false) {
  if (sending.value || (loading.value && !initial)) return
  const version = ++generation
  thread.value = row
  messages.value = []
  live.value = null
  hasMore.value = false
  beforeId.value = null
  loadingOlder.value = false
  error.value = ''
  notice.value = ''
  loading.value = true
  try {
    const page = await listAiMessages(row.id)
    if (disposed || version !== generation) return
    messages.value = page.records
    hasMore.value = page.hasMore
    beforeId.value = page.nextBeforeId
    follow = true
    scrollEnd(true)
  } catch (cause) { if (!disposed && version === generation) error.value = cause.message }
  finally { if (!disposed && version === generation) loading.value = false }
}

/** 新对话在首次发送时才建库，避免留下无用空白便签。 */
function newThread() {
  if (sending.value || loading.value) return
  ++generation
  thread.value = null
  messages.value = []
  live.value = null
  hasMore.value = false
  beforeId.value = null
  loadingOlder.value = false
  error.value = ''
  notice.value = ''
  composer.value?.focus()
}

/** 游标加载更早消息，保持阅读位置并防止切换后的回包串入。 */
async function loadOlder() {
  if (!thread.value || !hasMore.value || loadingOlder.value || sending.value || loading.value) return
  const version = generation
  const oldHeight = messageList.value?.scrollHeight || 0
  const oldTop = messageList.value?.scrollTop || 0
  loadingOlder.value = true
  error.value = ''
  try {
    const page = await listAiMessages(thread.value.id, beforeId.value)
    if (disposed || version !== generation) return
    messages.value = [...page.records, ...messages.value]
    hasMore.value = page.hasMore
    beforeId.value = page.nextBeforeId
    await nextTick()
    if (messageList.value) messageList.value.scrollTop = oldTop + messageList.value.scrollHeight - oldHeight
  } catch (cause) { if (!disposed && version === generation) error.value = cause.message }
  finally { if (!disposed && version === generation) loadingOlder.value = false }
}

/** 中止浏览器读取并清理逐字展示；服务端发现断连后收尾。 */
function stop() {
  controller?.abort()
  clearTypewriter()
  live.value = null
  notice.value = '已停止。服务端收尾可能稍有延迟，可刷新对话确认。'
}

/** 输入法确认不误发送，保留 Shift+Enter 换行。 */
function onKeydown(event) {
  if (event.key !== 'Enter' || event.shiftKey || event.isComposing || event.keyCode === 229) return
  event.preventDefault()
  send()
}

/** 服务器接受后追加用户消息，回复固定逐字展示，失败时保留未发送草稿。 */
async function send() {
  const content = draft.value.trim()
  if (sending.value || loading.value || loadingOlder.value) return
  if (!content) { error.value = '请输入内容'; composer.value?.focus(); return }
  if (!ready.value) { error.value = helper.value; return }
  const version = generation
  const loginToken = auth.token
  // 同时核对页面版本和登录凭证，换账号后丢弃旧流。
  const active = () => !disposed && version === generation && loginToken === auth.token
  sending.value = true
  error.value = ''
  notice.value = ''
  controller = new AbortController()
  waitingLong.value = false
  waitingTimer = setTimeout(() => { if (active()) waitingLong.value = true }, 15000)
  let accepted = false
  follow = true
  live.value = { role: 'assistant', content: '' }
  let targetContent = ''
  let savedMessage
  let typingTimer
  let resolveTyping
  const typingFinished = new Promise(resolve => { resolveTyping = resolve })
  cancelTypewriter = () => {
    clearTimeout(typingTimer)
    typingTimer = undefined
    targetContent = ''
    resolveTyping()
  }

  /** 每次只展示一个 Unicode 码点，固定间隔，不因积压或完成事件加速。 */
  function scheduleTyping() {
    if (typingTimer !== undefined || !live.value) return
    if (live.value.content.length >= targetContent.length) {
      if (savedMessage) {
        messages.value.push(savedMessage)
        live.value = null
        resolveTyping()
      }
      return
    }
    typingTimer = setTimeout(() => {
      typingTimer = undefined
      if (!active() || controller.signal.aborted) { clearTypewriter(); return }
      live.value.content += String.fromCodePoint(targetContent.codePointAt(live.value.content.length))
      scrollEnd()
      scheduleTyping()
    }, 30)
  }

  /** 安全替换立即清除旧队列，最终确认保留已显示的相同前缀。 */
  function replaceTyping(content, reset = false) {
    targetContent = content
    if (reset || !content.startsWith(live.value.content)) {
      clearTimeout(typingTimer)
      typingTimer = undefined
      live.value.content = ''
    }
    scheduleTyping()
  }

  try {
    if (!thread.value) {
      const row = await createAiThread()
      if (!active() || controller.signal.aborted) return
      thread.value = row
      threads.value = [row, ...threads.value]
    }
    await streamAiMessage(thread.value.id, content, controller.signal, (type, data) => {
      if (!active() || controller.signal.aborted) return
      if (type === 'user') {
        accepted = true
        messages.value.push(data)
        if (draft.value.trim() === content) draft.value = ''
        status.value.quotaRemaining = Math.max(0, status.value.quotaRemaining - 1)
        if (!thread.value.title) thread.value.title = content.slice(0, 20)
      } else if (type === 'delta') {
        targetContent += data.content
        scheduleTyping()
      } else if (type === 'replace') replaceTyping(data.content, true)
      else if (type === 'done') {
        savedMessage = data.message
        status.value.quotaRemaining = data.quotaRemaining
        clearTimeout(waitingTimer)
        replaceTyping(data.message.content)
      }
      scrollEnd()
    })
    // 网络结束后仍等待队列逐字播完，期间保留停止入口。
    await typingFinished
  } catch (cause) {
    if (!active()) return
    if (!controller.signal.aborted) error.value = cause.message
    // 未确认完成的临时文本不作为已保存回复展示。
    live.value = null
    if (accepted && !controller.signal.aborted) notice.value = '可刷新对话确认已保存的消息。'
  } finally {
    clearTimeout(waitingTimer)
    clearTypewriter()
    if (active()) {
      sending.value = false
      live.value = null
      try {
        const info = await getAiStatus()
        if (active()) status.value = info
      } catch { /* 保留已知配额和原错误。 */ }
      if (active()) composer.value?.focus()
    }
  }
}

onMounted(load)
</script>

<template>
  <div class="assistant-layout">
    <aside class="panel assistant-index" aria-label="助手对话">
      <div class="section-heading"><h2>助手</h2><RouterLink to="/app">回大厅</RouterLink></div>
      <button class="new-thread" :disabled="sending || loading" @click="newThread">＋ 新对话</button>
      <p class="muted quota" role="status">{{ status ? `今日还可提问 ${status.quotaRemaining} / ${status.quotaLimit} 次` : '读取状态中…' }}</p>
      <nav class="thread-index" aria-label="最近对话">
        <button v-for="row in threads" :key="row.id" class="secondary thread-tab" :class="{ selected: row.id === thread?.id }" :aria-current="row.id === thread?.id ? 'page' : undefined" :disabled="sending || loading" @click="selectThread(row)">{{ row.title || '新对话' }}</button>
        <p v-if="!threads.length && !loading" class="muted">还没有对话</p>
      </nav>
    </aside>

    <section class="panel assistant-page" aria-labelledby="assistant-title">
      <header class="assistant-heading">
        <div><span class="room-label">Mairy Chat · 文字助手</span><h2 id="assistant-title">{{ thread?.title || '有什么想聊的？' }}</h2></div>
        <button class="secondary" :disabled="loading || sending || loadingOlder" @click="load">刷新</button>
      </header>
      <!-- 隐私提示使用统一的云端模型称谓，保留明文及外发说明。 -->
      <p class="privacy-note">对话以明文保存在服务器，并发送至云端模型。请勿输入密码、密钥或隐私。</p>
      <p v-if="error" id="assistant-error" class="error" role="alert">{{ error }} <button v-if="!status" class="secondary" :disabled="loading" @click="load">重试</button></p>
      <p v-if="notice" class="notice" role="status">{{ notice }}</p>
      <div ref="messageList" class="assistant-messages" :aria-busy="loading" @scroll="trackScroll">
        <p v-if="loading" class="empty" role="status">正在翻开对话…</p>
        <button v-if="hasMore" class="secondary older" :disabled="loadingOlder || sending || loading" @click="loadOlder">{{ loadingOlder ? '加载中…' : '更早消息' }}</button>
        <div v-if="!loading && !messages.length && !sending" class="assistant-empty">
          <span class="blank-note" aria-hidden="true">?</span><h3>从一个问题开始</h3><p class="muted">写点文字，理清思路，或问个小问题。</p><p v-if="status && !status.enabled" class="notice">助手未配置，请联系管理员</p>
        </div>
        <!-- 始终使用文本插值，模型内容不能执行 HTML 或脚本。 -->
        <article v-for="message in messages" :key="message.id" class="assistant-message" :class="{ mine: message.role === 'user' }">
          <span class="message-role">{{ message.role === 'user' ? '我' : '助手' }}</span><p>{{ message.content }}</p>
        </article>
        <article v-if="live" class="assistant-message" aria-label="助手正在回复"><span class="message-role">助手 · {{ live.content ? '正在回复' : waitingLong ? '仍在准备，可停止后重试' : '正在准备回复…' }}</span><p>{{ live.content }}<span class="stream-caret" aria-hidden="true">▍</span></p></article>
      </div>
      <form class="assistant-composer" @submit.prevent="send">
        <label for="assistant-draft">写给助手</label>
        <textarea id="assistant-draft" ref="composer" v-model="draft" name="content" maxlength="2000" rows="3" :disabled="sending" :aria-invalid="!!error" aria-describedby="assistant-hint assistant-error" placeholder="输入你的问题…" @keydown="onKeydown" />
        <div class="composer-footer"><span id="assistant-hint" class="muted">{{ helper }}<br />回复可能有误，请核实重要信息。</span><div class="actions"><span class="muted draft-count">{{ draft.length }}/2000</span><button v-if="sending" type="button" class="secondary" @click="stop">停止生成</button><button v-else :disabled="loading || loadingOlder || !ready" type="submit">发送 →</button></div></div>
      </form>
      <span class="assistant-announcement" role="status" aria-live="polite">{{ sending ? (live?.content ? '助手正在回复' : '正在准备回复') : '可以继续提问' }}</span>
    </section>
  </div>
</template>

<style scoped>
/* 复用全局纸笺令牌，新增样式仅作用于助手页面。 */
.assistant-layout { display: grid; grid-template-columns: 240px minmax(0, 1fr); gap: var(--space-8); align-items: start; }
.assistant-index { padding: var(--space-6); }
.assistant-index h2 { font-size: 26px; }
.assistant-index .section-heading { gap: var(--space-2); }
.assistant-index a { min-height: 44px; display: inline-flex; align-items: center; white-space: nowrap; }
.new-thread { width: 100%; }
.quota { margin: var(--space-4) 0; font-variant-numeric: tabular-nums; }
.thread-index { display: grid; gap: var(--space-2); max-height: 58vh; overflow-y: auto; padding: 5px; margin: -5px; }
.thread-tab { justify-content: start; text-align: left; overflow-wrap: anywhere; }
.thread-tab.selected { background: var(--warning); border-color: var(--action); }
.assistant-page { min-width: 0; padding: var(--space-6); }
.assistant-heading { display: flex; justify-content: space-between; align-items: start; gap: var(--space-4); }
.assistant-heading h2 { font-size: clamp(23px, 2.4vw, 30px); margin: var(--space-1) 0 var(--space-3); }
.assistant-heading button { flex-shrink: 0; }
.privacy-note { font-size: 13px; color: var(--muted); border-bottom: 1px dashed var(--line); padding-bottom: var(--space-4); }
.assistant-messages { height: clamp(280px, 46vh, 560px); overflow-y: auto; overscroll-behavior: contain; padding: var(--space-4) 6px; }
.older { display: flex; margin: 0 auto var(--space-4); }
.assistant-empty { display: grid; justify-items: center; gap: var(--space-3); padding: 30px 8px; text-align: center; }
.blank-note { display: grid; place-items: center; width: 64px; height: 70px; background: var(--warning); color: var(--leaf); font: 36px var(--font-title); border-bottom: 3px solid var(--citrus); transform: rotate(-5deg); }
.assistant-message { max-width: 94%; margin: 0 auto var(--space-6) 0; }
.assistant-message.mine { margin-left: auto; margin-right: 0; padding: var(--space-3) var(--space-4); background: var(--tint); border-radius: var(--radius-input); }
.message-role { display: block; font-size: 12px; color: var(--muted); margin-bottom: 6px; }
.assistant-message p { white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.85; }
.stream-caret { color: var(--action); }
.assistant-composer { padding-top: var(--space-4); border-top: 1px dashed var(--line); }
.assistant-composer textarea { margin-top: var(--space-2); font-size: 16px; }
.composer-footer { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: var(--space-3); margin-top: var(--space-3); }
.draft-count { font-variant-numeric: tabular-nums; }
.assistant-announcement { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
@media (max-width: 760px) {
  .assistant-layout { grid-template-columns: minmax(0, 1fr); gap: var(--space-6); }
  .assistant-index, .assistant-page { padding: var(--space-4); }
  .assistant-index .section-heading { margin-bottom: var(--space-3); }
  .thread-index { display: flex; max-height: 120px; overflow: auto; }
  .thread-tab { flex: 0 0 150px; }
  .assistant-messages { height: 45dvh; min-height: 260px; }
}
@media (prefers-reduced-motion: reduce) {
  .assistant-layout button, .assistant-layout a { transition: none; transform: none; }
}
</style>
