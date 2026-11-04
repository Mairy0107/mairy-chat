<script setup>
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { listDmMessages, openDm, openDmSocket, sendDmMessage } from '../api/chat'
import { auth } from '../auth'

const route = useRoute()
const thread = ref(null)
const messages = ref([])
const draft = ref('')
const error = ref('')
const status = ref('连接中')
const opening = ref(false)
const sending = ref(false)
const refreshing = ref(false)
const loadingOlder = ref(false)
const blocked = ref(false)
const hasMore = ref(false)
const nextBeforeId = ref(null)
const messageList = ref(null)
let socket = null
let generation = 0
let historyVersion = 0
let disposed = false

/** 忽略切换好友或离开页面后返回的异步结果。 */
function current(version) {
  return !disposed && version === generation
}

/** 通过主键去重合并 REST 与推送，并按主键升序显示。 */
async function mergeMessages(records, scroll = false) {
  const byId = new Map(messages.value.map(message => [message.id, message]))
  for (const message of records) byId.set(message.id, message)
  messages.value = [...byId.values()].sort((a, b) => BigInt(a.id) < BigInt(b.id) ? -1 : 1)
  if (scroll) {
    await nextTick()
    messageList.value?.scrollTo({ top: messageList.value.scrollHeight })
  }
}

/** 权限失效时停止连接并清理已展示消息，其他错误允许重试。 */
function showError(cause) {
  error.value = cause.message
  if (cause.status === 401 || cause.status === 403) {
    blocked.value = true
    // 废弃进行中的历史请求，权限失效后不允许旧回包重新展示内容。
    ++historyVersion
    refreshing.value = false
    loadingOlder.value = false
    messages.value = []
    status.value = '私信不可用'
    socket?.close()
  }
}

/** 首次进入或重新连接时重置最近一页，避免长时间断线后的历史缺口。 */
async function refreshHistory() {
  if (!thread.value || blocked.value) return
  const version = generation
  const history = ++historyVersion
  refreshing.value = true
  error.value = ''
  messages.value = []
  hasMore.value = false
  nextBeforeId.value = null
  try {
    const result = await listDmMessages(thread.value.id)
    if (!current(version) || history !== historyVersion) return
    hasMore.value = result.hasMore
    nextBeforeId.value = result.nextBeforeId
    await mergeMessages(result.records, true)
  } catch (cause) {
    if (current(version) && history === historyVersion) showError(cause)
  } finally {
    if (current(version) && history === historyVersion) refreshing.value = false
  }
}

/** 加载更早的一页并保持当前阅读位置。 */
async function loadOlder() {
  if (!thread.value || !hasMore.value || loadingOlder.value || refreshing.value || blocked.value) return
  const version = generation
  const history = historyVersion
  loadingOlder.value = true
  error.value = ''
  const oldHeight = messageList.value?.scrollHeight || 0
  const oldTop = messageList.value?.scrollTop || 0
  try {
    const result = await listDmMessages(thread.value.id, nextBeforeId.value)
    if (!current(version) || history !== historyVersion) return
    hasMore.value = result.hasMore
    nextBeforeId.value = result.nextBeforeId
    await mergeMessages(result.records)
    await nextTick()
    if (current(version) && history === historyVersion && messageList.value) {
      messageList.value.scrollTop = oldTop + messageList.value.scrollHeight - oldHeight
    }
  } catch (cause) {
    if (current(version) && history === historyVersion) showError(cause)
  } finally {
    if (current(version)) loadingOlder.value = false
  }
}

/** 建立登录级推送，只接收当前私信；成功重连后重新读取历史。 */
function connect() {
  if (disposed || blocked.value || !thread.value || socket?.readyState === WebSocket.OPEN || socket?.readyState === WebSocket.CONNECTING) return
  const version = generation
  status.value = '连接中'
  let connection
  try {
    connection = openDmSocket()
  } catch {
    status.value = '实时连接已断开'
    error.value = '实时连接失败，请重新连接'
    return
  }
  socket = connection
  connection.onopen = () => {
    if (!current(version) || socket !== connection) return
    status.value = '实时连接已就绪'
    refreshHistory()
  }
  connection.onmessage = event => {
    if (!current(version) || socket !== connection || blocked.value) return
    try {
      const payload = JSON.parse(event.data)
      if (payload.type === 'dm-message' && payload.data.threadId === thread.value.id) {
        const list = messageList.value
        const nearBottom = !list || list.scrollHeight - list.scrollTop - list.clientHeight < 100
        mergeMessages([payload.data], nearBottom)
      }
    } catch {
      error.value = '实时消息格式异常，请刷新消息'
    }
  }
  connection.onclose = () => {
    if (!current(version) || socket !== connection || blocked.value) return
    status.value = '实时连接已断开'
    // 主动核验登录与好友状态；网络故障仍可手动重连或走 REST。
    refreshHistory()
  }
  connection.onerror = () => {
    if (current(version) && socket === connection) error.value = '实时连接失败，请重新连接'
  }
}

/** 保存明文私信并保留发送期间新增的草稿。 */
async function handleSend() {
  const content = draft.value.trim()
  if (!thread.value || !content || sending.value || blocked.value) return
  const version = generation
  sending.value = true
  error.value = ''
  try {
    const message = await sendDmMessage(thread.value.id, content)
    if (!current(version) || blocked.value) return
    await mergeMessages([message], true)
    if (current(version) && draft.value.trim() === content) draft.value = ''
  } catch (cause) {
    if (current(version)) showError(cause)
  } finally {
    if (current(version)) sending.value = false
  }
}

/** 中文输入法确认不发送，Enter 发送、Shift+Enter 换行。 */
function handleEnter(event) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
    event.preventDefault()
    handleSend()
  }
}

/** 原样展示后端 Asia/Shanghai 时间，不做本地时区二次转换。 */
function formatTime(value) {
  return value.replace('T', ' ').slice(0, 19)
}

/** 切换好友时释放旧连接与状态，接口成功后再加载私信。 */
async function start(userId) {
  const version = ++generation
  socket?.close()
  socket = null
  ++historyVersion
  thread.value = null
  messages.value = []
  draft.value = ''
  error.value = ''
  blocked.value = false
  sending.value = false
  refreshing.value = false
  loadingOlder.value = false
  hasMore.value = false
  nextBeforeId.value = null
  opening.value = true
  status.value = '连接中'
  try {
    if (!/^[1-9][0-9]*$/.test(userId)) throw new Error('用户编号无效')
    const result = await openDm(userId)
    if (!current(version)) return
    thread.value = result
    // 即使 WebSocket 不可用，也能读取历史并通过 REST 收发。
    await refreshHistory()
    if (current(version)) connect()
  } catch (cause) {
    if (current(version)) showError(cause)
  } finally {
    if (current(version)) opening.value = false
  }
}

// 同一组件切换好友时重新初始化，不沿用上一位好友的消息。
watch(() => route.params.userId, start, { immediate: true })
onBeforeUnmount(() => {
  disposed = true
  ++generation
  socket?.close()
})
</script>

<template>
  <section class="panel chat-panel dm-panel">
    <div class="section-heading">
      <!-- 私信沿用房间的纸卡标题，仅以短标签区分会话。 -->
      <div class="chat-identity"><span class="paper-mark" aria-hidden="true"></span><div><span class="room-label">私信</span><h2>{{ thread?.peer.nickname || '私信' }}</h2><p v-if="thread" class="muted">{{ thread.peer.publicNo }} · <span role="status">{{ status }}</span></p></div></div>
      <RouterLink class="button-link secondary" to="/app">返回大厅</RouterLink>
    </div>
    <p class="notice dm-privacy">私信未加密，服务器可读取内容。</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="opening" class="empty">正在打开私信…</p>
    <button v-if="!opening && !thread && !blocked" class="secondary" @click="start(route.params.userId)">重试</button>
    <template v-if="thread">
      <div class="chat-tools">
        <button class="secondary" :disabled="refreshing || loadingOlder || blocked" @click="refreshHistory">刷新消息</button>
        <button v-if="status === '实时连接已断开'" class="secondary" :disabled="blocked" @click="connect">重新连接</button>
      </div>
      <div ref="messageList" class="message-list" role="log" aria-label="私信消息" aria-live="polite">
        <button v-if="hasMore && !blocked" class="secondary load-more" :disabled="loadingOlder || refreshing" @click="loadOlder">{{ loadingOlder ? '加载中…' : '加载更早消息' }}</button>
        <p v-if="refreshing" class="empty">正在加载消息…</p>
        <p v-else-if="!messages.length && !blocked" class="empty">暂无消息</p>
        <article v-for="message in messages" :key="message.id" class="message" :class="{ own: message.senderId === auth.user?.id }">
          <div class="message-meta"><strong>{{ message.senderId === auth.user?.id ? '我' : thread.peer.nickname }}</strong><time>{{ formatTime(message.createdAt) }}</time></div>
          <!-- 文本插值避免将好友输入渲染为 HTML。 -->
          <p class="message-bubble">{{ message.content }}</p>
        </article>
      </div>
      <form class="composer" @submit.prevent="handleSend">
        <label for="dm-content">发给 {{ thread.peer.nickname }}</label>
        <textarea id="dm-content" v-model="draft" maxlength="1000" rows="3" :disabled="blocked" placeholder="说点什么…" required @keydown="handleEnter"></textarea>
        <div class="composer-footer"><span class="muted">{{ draft.length }} / 1000 · Enter发送 · Shift+Enter换行</span><button :disabled="sending || blocked || !draft.trim()">{{ sending ? '发送中…' : '发送' }}</button></div>
      </form>
    </template>
  </section>
</template>

<style scoped>
/* 复用项目现有聊天面板，仅补充私信页按钮的窄屏排列。 */
.dm-panel .button-link { flex-shrink: 0; }
.dm-panel .chat-tools { flex-wrap: wrap; }
</style>
