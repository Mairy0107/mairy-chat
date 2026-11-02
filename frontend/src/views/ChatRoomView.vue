<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { listMessages, openChatSocket, sendMessage } from '../api/chat'

const props = defineProps({ chat: { type: Object, required: true } })
defineEmits(['leave'])
const messages = ref([])
const draft = ref('')
const error = ref('')
const status = ref('连接中')
const sending = ref(false)
const loadingHistory = ref(false)
const refreshing = ref(false)
const hasMore = ref(false)
const nextBeforeId = ref(null)
const messageList = ref(null)
const sessionExpired = ref(false)
let socket = null
let disposed = false
let historyVersion = 0

/** 按消息主键去重并排序，合并 REST 回包与 WebSocket 推送。 */
function mergeMessages(records, scroll = false) {
  const byId = new Map(messages.value.map((message) => [message.id, message]))
  for (const message of records) byId.set(message.id, message)
  messages.value = [...byId.values()].sort((a, b) => BigInt(a.id) < BigInt(b.id) ? -1 : 1)
  if (scroll) nextTick(() => messageList.value?.scrollTo({ top: messageList.value.scrollHeight }))
}

/** 展示中文接口错误，过期会话停止继续发送。 */
function showError(cause) {
  error.value = cause.message
  if (cause.status === 401) {
    sessionExpired.value = true
    status.value = '会话已过期，请返回大厅'
    socket?.close()
  }
}

/** 首次进入或重连后刷新最近一页，避免断线消息缺口。 */
async function refreshHistory() {
  const version = ++historyVersion
  refreshing.value = true
  messages.value = []
  hasMore.value = false
  nextBeforeId.value = null
  try {
    const result = await listMessages(props.chat.room.id, props.chat.member.token)
    if (disposed || version !== historyVersion) return
    mergeMessages(result.records, true)
    nextBeforeId.value = result.nextBeforeId
    hasMore.value = result.hasMore
  } catch (cause) {
    if (!disposed && version === historyVersion) showError(cause)
  } finally {
    if (version === historyVersion) refreshing.value = false
  }
}

/** 加载更早的消息，并尽量保持当前阅读位置。 */
async function loadOlder() {
  if (loadingHistory.value || refreshing.value || !hasMore.value) return
  loadingHistory.value = true
  error.value = ''
  const version = historyVersion
  const oldHeight = messageList.value?.scrollHeight || 0
  const oldTop = messageList.value?.scrollTop || 0
  try {
    const result = await listMessages(props.chat.room.id, props.chat.member.token, nextBeforeId.value)
    if (disposed || version !== historyVersion) return
    mergeMessages(result.records)
    nextBeforeId.value = result.nextBeforeId
    hasMore.value = result.hasMore
    await nextTick()
    if (messageList.value) messageList.value.scrollTop = oldTop + messageList.value.scrollHeight - oldHeight
  } catch (cause) {
    if (!disposed && version === historyVersion) showError(cause)
  } finally {
    loadingHistory.value = false
  }
}

/** 连接实时推送，重连成功时重新同步历史。 */
function connect() {
  if (disposed || sessionExpired.value || socket?.readyState === WebSocket.OPEN || socket?.readyState === WebSocket.CONNECTING) return
  status.value = '连接中'
  error.value = ''
  const connection = openChatSocket(props.chat.member.token)
  socket = connection
  connection.onopen = () => {
    if (disposed || socket !== connection) return
    status.value = '实时连接已就绪'
    refreshHistory()
  }
  connection.onmessage = (event) => {
    if (disposed || socket !== connection) return
    try {
      const payload = JSON.parse(event.data)
      if (payload.type === 'message' && payload.data.roomId === props.chat.room.id) {
        const list = messageList.value
        const nearBottom = !list || list.scrollHeight - list.scrollTop - list.clientHeight < 100
        mergeMessages([payload.data], nearBottom)
      }
    } catch {
      error.value = '实时消息格式异常，请重新连接'
    }
  }
  connection.onclose = () => {
    if (!disposed && socket === connection && !sessionExpired.value) status.value = '实时连接已断开'
  }
  connection.onerror = () => {
    if (!disposed && socket === connection) error.value = '实时连接失败，可尝试重新连接'
  }
}

/** 保存消息后合并回包；断线时仍可通过 REST 发送。 */
async function handleSend() {
  const content = draft.value.trim()
  if (!content || sending.value || sessionExpired.value) return
  sending.value = true
  error.value = ''
  try {
    const message = await sendMessage(props.chat.room.id, props.chat.member.token, content)
    if (disposed) return
    mergeMessages([message], true)
    // 发送期间继续输入时，保留新增草稿。
    if (draft.value.trim() === content) draft.value = ''
  } catch (cause) {
    if (!disposed) showError(cause)
  } finally {
    sending.value = false
  }
}

/** 显示服务端返回的消息时间。 */
function formatTime(value) {
  return value.replace('T', ' ').slice(0, 19)
}

onMounted(() => {
  refreshHistory()
  connect()
})

onBeforeUnmount(() => {
  // 返回大厅后关闭连接，忽略尚未完成的请求回包。
  disposed = true
  socket?.close()
})
</script>

<template>
  <section class="panel chat-panel">
    <div class="section-heading">
      <div>
        <h2>{{ chat.room.name }}</h2>
        <p class="muted">{{ chat.member.nickname }} · <span role="status">{{ status }}</span></p>
      </div>
      <button class="secondary" @click="$emit('leave')">返回大厅</button>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <div class="chat-tools">
      <button class="secondary" :disabled="refreshing || loadingHistory || sessionExpired" @click="refreshHistory">刷新消息</button>
      <button v-if="status === '实时连接已断开'" class="secondary" :disabled="sessionExpired" @click="connect">重新连接</button>
    </div>
    <div ref="messageList" class="message-list" role="log" aria-label="聊天消息" aria-live="polite">
      <button v-if="hasMore" class="secondary load-more" :disabled="loadingHistory || refreshing" @click="loadOlder">
        {{ loadingHistory ? '加载中…' : '加载更早消息' }}
      </button>
      <p v-if="refreshing" class="empty">正在加载最近消息…</p>
      <p v-else-if="!messages.length" class="empty">还没有消息，打个招呼吧。</p>
      <article v-for="message in messages" :key="message.id" class="message"
        :class="{ own: message.memberId === chat.member.id }">
        <div class="message-meta"><strong>{{ message.nickname }}</strong><time>{{ formatTime(message.createdAt) }}</time></div>
        <!-- 使用文本插值展示用户输入，避免将消息当作 HTML。 -->
        <p class="message-bubble">{{ message.content }}</p>
      </article>
    </div>
    <form class="composer" @submit.prevent="handleSend">
      <label for="message-content">说点什么</label>
      <textarea id="message-content" v-model="draft" maxlength="1000" rows="3"
        :disabled="sessionExpired" placeholder="分享此刻的想法…" required></textarea>
      <div class="composer-footer">
        <span class="muted">{{ draft.length }} / 1000 · 点击发送</span>
        <button :disabled="sending || sessionExpired || !draft.trim()">{{ sending ? '发送中…' : '发送消息' }}</button>
      </div>
    </form>
  </section>
</template>
