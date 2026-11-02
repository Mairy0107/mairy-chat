<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { listMessages, openChatSocket, sendMessage, request } from '../api/chat'
import { auth } from '../auth'
import { decryptMessage, encryptMessage, exportVault, importVault, identityFingerprint,
  isUnlocked, loadRoomKey, unlockIdentity, wrapRoomKey } from '../crypto/e2ee'

const props = defineProps({ chat: { type: Object, required: true } })
defineEmits(['leave'])
const messages = ref([])
const draft = ref('')
const composerInput = ref(null)
const emojis = ['😀', '😊', '😂', '❤️', '👍', '🎉', '🌹', '☕']
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
// 房间内容密钥只驻留当前页面内存，退出时释放引用。
let roomKey = null
const keyReady = ref(false)
const keyBusy = ref(false)
const keyNotice = ref('设备密钥未解锁')
const keyPassword = ref('')
const myFingerprint = ref('')
const verifiedRoomFingerprint = ref('')
const roomFingerprint = ref('')
const owner = ref(false)
const candidates = ref([])

/** 刷新本设备密钥与待授权成员，缺失密钥绝不降级发送明文。 */
async function refreshKeys() {
  if (keyBusy.value || disposed) return
  keyBusy.value = true
  keyReady.value = false
  roomKey = null
  candidates.value = []
  myFingerprint.value = ''
  roomFingerprint.value = ''
  owner.value = false
  try {
    if (auth.user?.role === 'ADMIN' || props.chat.stealth) {
      keyNotice.value = '平台管理员无法解密加密消息；旧明文仍按原规则显示'
      return
    }
    if (!isUnlocked()) throw new Error('请输入本地密钥密码解锁（首次为创建密钥时的登录密码）')
    myFingerprint.value = identityFingerprint()
    const result = await loadRoomKey(props.chat.room.id, props.chat.member.token, verifiedRoomFingerprint.value)
    if (disposed) return
    roomKey = result.key
    roomFingerprint.value = result.fingerprint
    verifiedRoomFingerprint.value = ''
    keyReady.value = true
    owner.value = result.ownerUserId === auth.user.id
    keyNotice.value = '端到端加密已就绪 · 服务器和平台管理员无法解密'
    if (owner.value) candidates.value = await request('/e2ee/rooms/' + props.chat.room.id + '/candidates', { token: props.chat.member.token })
  } catch (cause) {
    if (!disposed) keyNotice.value = cause.message
  } finally {
    keyBusy.value = false
    if (!disposed) await refreshHistory()
  }
}

/** 只在当前设备解锁保险箱，不把本地密钥密码发送到接口。 */
async function unlockKeys() {
  if (keyBusy.value) return
  keyBusy.value = true
  keyReady.value = false
  roomKey = null
  myFingerprint.value = ''
  candidates.value = []
  try {
    await unlockIdentity(keyPassword.value)
  } catch (cause) {
    keyNotice.value = cause.message
    await refreshHistory()
    return
  } finally {
    keyPassword.value = ''
    keyBusy.value = false
  }
  await refreshKeys()
}

/** 房主核验成员本人提供的完整指纹后提交专属信封。 */
async function grantMember(candidate) {
  if (keyBusy.value || !keyReady.value) return
  keyBusy.value = true
  try {
    const body = await wrapRoomKey(props.chat.room.id, roomKey, candidate, candidate.verifiedFingerprint || '')
    await request('/e2ee/rooms/' + props.chat.room.id + '/members/' + candidate.userId,
      { method: 'PUT', token: props.chat.member.token, body })
    candidates.value = candidates.value.filter(row => row.userId !== candidate.userId)
    keyNotice.value = '已授权该成员；对方点击刷新密钥即可读取本房间全部历史密文'
  } catch (cause) {
    keyNotice.value = cause.message
  } finally {
    keyBusy.value = false
  }
}

/** 下载受原密码保护的备份，备份本身从不经过服务器。 */
function downloadBackup() {
  try {
    const url = URL.createObjectURL(new Blob([exportVault()], { type: 'application/json' }))
    const link = document.createElement('a')
    link.href = url
    link.download = 'mairy-device-key-backup.json'
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (cause) {
    keyNotice.value = cause.message
  }
}

/** 新设备读取用户选择的加密备份，随后必须输入备份原密码解锁。 */
async function restoreBackup(event) {
  try {
    const file = event.target.files?.[0]
    if (!file) return
    if (file.size > 20000) throw new Error('备份文件过大')
    importVault(await file.text())
    roomKey = null
    keyReady.value = false
    keyNotice.value = '备份已导入，请使用备份原密码解锁'
  } catch {
    keyNotice.value = '无法导入：请检查备份账号、格式及本设备是否已有保险箱'
  } finally {
    event.target.value = ''
  }
}

/** 按消息主键去重并排序，合并 REST 回包与 WebSocket 推送。 */
async function mergeMessages(records, scroll = false, version = historyVersion) {
  const decoded = await Promise.all(records.map(message => decryptMessage(roomKey, props.chat.room.id, message)))
  if (disposed || version !== historyVersion) return
  const byId = new Map(messages.value.map((message) => [message.id, message]))
  for (const message of decoded) byId.set(message.id, message)
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
    await mergeMessages(result.records, true, version)
    if (disposed || version !== historyVersion) return
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
    await mergeMessages(result.records, false, version)
    if (disposed || version !== historyVersion) return
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
    // 首次及重连均在连接建立后同步，避免重复请求与历史缺口。
    refreshHistory()
  }
  connection.onmessage = async (event) => {
    if (disposed || socket !== connection) return
    try {
      const payload = JSON.parse(event.data)
      if (payload.type === 'message' && payload.data.roomId === props.chat.room.id) {
        const list = messageList.value
        const nearBottom = !list || list.scrollHeight - list.scrollTop - list.clientHeight < 100
        await mergeMessages([payload.data], nearBottom)
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
  if (!content || sending.value || keyBusy.value || sessionExpired.value || props.chat.stealth || !keyReady.value) return
  sending.value = true
  error.value = ''
  try {
    const envelope = await encryptMessage(roomKey, props.chat.room.id, props.chat.member.id, content)
    const message = await sendMessage(props.chat.room.id, props.chat.member.token, envelope)
    if (disposed) return
    await mergeMessages([message], true)
    // 发送期间继续输入时，保留新增草稿。
    if (draft.value.trim() === content) draft.value = ''
  } catch (cause) {
    if (!disposed) showError(cause)
  } finally {
    sending.value = false
  }
}

/** 中文输入法确认不发送；Enter发送，Shift+Enter换行。 */
function handleEnter(event) {
  if (event.key === 'Enter' && !event.shiftKey && !event.isComposing && event.keyCode !== 229) {
    event.preventDefault()
    handleSend()
  }
}

/** 在光标位置插入表情，保持输入焦点且不超过消息上限。 */
async function insertEmoji(emoji) {
  const input = composerInput.value
  const start = input?.selectionStart ?? draft.value.length
  const end = input?.selectionEnd ?? start
  const text = draft.value.slice(0, start) + emoji + draft.value.slice(end)
  if (text.length > 1000) return
  draft.value = text
  await nextTick()
  input?.focus()
  input?.setSelectionRange(start + emoji.length, start + emoji.length)
}

/** 显示服务端返回的消息时间。 */
function formatTime(value) {
  return value.replace('T', ' ').slice(0, 19)
}

onMounted(async () => {
  await refreshKeys()
  if (!disposed) connect()
})

onBeforeUnmount(() => {
  // 返回大厅后关闭连接，忽略尚未完成的请求回包。
  disposed = true
  roomKey = null
  keyPassword.value = ''
  socket?.close()
})
</script>

<template>
  <section class="panel chat-panel">
    <div class="section-heading">
      <div>
        <h2>{{ chat.room.name }}</h2>
        <p class="muted">{{ chat.member.nickname }} {{ chat.stealth ? "· 隐身只读" : "" }} · <span role="status">{{ status }}</span></p>
      </div>
      <button class="secondary" @click="$emit('leave')">返回大厅</button>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <!-- 密钥操作限定在房间页面，不修改前端公共组件。 -->
    <details class="panel" open>
      <summary>端到端加密与设备密钥</summary>
      <p role="status">{{ keyNotice }}</p>
      <template v-if="auth.user?.role === 'USER' && !chat.stealth">
        <p class="muted">私钥仅在此设备。请保留加密备份和原密码；丢失后管理员也无法恢复。</p>
        <form @submit.prevent="unlockKeys">
          <label>本地密钥密码<input v-model="keyPassword" type="password" autocomplete="off" required maxlength="128" /></label>
          <button :disabled="keyBusy">解锁设备密钥</button>
        </form>
        <label>首次使用：房主通过可信渠道提供的完整房间密钥指纹<input v-model="verifiedRoomFingerprint" autocomplete="off" maxlength="64" /></label>
        <button class="secondary" :disabled="keyBusy" @click="refreshKeys">核验 / 刷新密钥 / 待授权成员</button>
        <p v-if="roomFingerprint" style="overflow-wrap: anywhere">已核验的房间密钥指纹：{{ roomFingerprint }}。房主请通过可信渠道把此指纹提供给成员；新设备也需重新核验。</p>
        <button class="secondary" :disabled="!myFingerprint || keyBusy" @click="downloadBackup">导出加密备份</button>
        <label>导入原设备加密备份<input type="file" accept="application/json,.json" :disabled="keyBusy" @change="restoreBackup" /></label>
        <p v-if="myFingerprint" style="overflow-wrap: anywhere">本设备完整指纹：{{ myFingerprint }}</p>
        <template v-if="owner && keyReady">
          <p class="muted">授权会共享房间全部历史与后续密文。请通过其他可信渠道索取对方完整指纹，不要直接复制此页面候选人的指纹。</p>
          <div v-for="candidate in candidates" :key="candidate.userId">
            <p style="overflow-wrap: anywhere">{{ candidate.nickname }}（账号 {{ candidate.userId }}）· 公钥指纹 {{ candidate.fingerprint }}</p>
            <label>成员本人提供的完整指纹<input v-model="candidate.verifiedFingerprint" autocomplete="off" maxlength="64" /></label>
            <button :disabled="keyBusy || !candidate.verifiedFingerprint" @click="grantMember(candidate)">核验并授权该成员</button>
          </div>
        </template>
      </template>
    </details>
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
        <div class="message-meta"><span>{{ message.legacy ? "旧明文" : "加密消息" }}</span><strong>{{ message.nickname }}</strong><time>{{ formatTime(message.createdAt) }}</time></div>
        <!-- 使用文本插值展示用户输入，避免将消息当作 HTML。 -->
        <p class="message-bubble">{{ message.displayContent }}</p>
      </article>
    </div>
    <p v-if="chat.stealth" class="notice">隐身只读：不会向房间公开成员或进房事件。</p>
    <form v-else class="composer" @submit.prevent="handleSend">
      <label for="message-content">说点什么</label>
      <textarea ref="composerInput" id="message-content" @keydown="handleEnter" v-model="draft" maxlength="1000" rows="3"
        :disabled="sessionExpired || !keyReady" placeholder="分享此刻的想法…" required></textarea>
      <div class="emoji-picker" aria-label="表情选择器">
        <button v-for="emoji in emojis" :key="emoji" type="button" class="secondary" :disabled="sessionExpired" :aria-label="`插入表情 ${emoji}`" @click="insertEmoji(emoji)">{{ emoji }}</button>
      </div>
      <div class="composer-footer">
        <span class="muted">{{ draft.length }} / 1000 · Enter发送 · Shift+Enter换行</span>
        <button :disabled="sending || sessionExpired || !keyReady || keyBusy || !draft.trim()">{{ sending ? '发送中…' : '发送消息' }}</button>
      </div>
    </form>
  </section>
</template>
