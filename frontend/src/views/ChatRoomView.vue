<script setup>
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { listMessages, openChatSocket, sendMessage, request } from '../api/chat'
import { auth } from '../auth'
import { decryptMessage, encryptMessage, encryptVoice, maxVoiceBytes, maxVoiceDuration, voiceMimeTypes, exportVault, importVault, identityFingerprint,
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
// 安全环境仅控制页面入口，不改变密钥算法与持久化规则。
const secureContext = window.isSecureContext === true
const secureContextNotice = '请使用 HTTPS 或 localhost 解锁设备密钥。'
const keyDialog = ref(null)
const keyNotice = ref('请用登录密码解锁')
const keyPassword = ref('')
const myFingerprint = ref('')
const verifiedRoomFingerprint = ref('')
const roomFingerprint = ref('')
const owner = ref(false)
const candidates = ref([])

const voicePhase = ref('idle')
const voicePreview = ref('')
const voiceDuration = ref(0)
const voiceSupported = secureContext && typeof MediaRecorder !== 'undefined'
  && typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getUserMedia
let voiceBlob = null
let recorder = null
let microphone = null
let voiceTimer = null
let voiceVersion = 0

/** 释放麦克风和定时器，结束后不再占用录音设备。 */
function releaseMicrophone() {
  clearTimeout(voiceTimer)
  voiceTimer = null
  microphone?.getTracks().forEach(track => track.stop())
  microphone = null
}

/** 取消录制或草稿，版本号使迟到的授权和录音回调失效。 */
function cancelVoice() {
  voiceVersion++
  if (recorder && recorder.state !== 'inactive') recorder.stop()
  recorder = null
  releaseMicrophone()
  if (voicePreview.value) URL.revokeObjectURL(voicePreview.value)
  voicePreview.value = ''
  voiceBlob = null
  voiceDuration.value = 0
  voicePhase.value = 'idle'
}

/** 清理已解密语音URL，供刷新历史、锁定和离开房间复用。 */
function clearVoiceMessages() {
  for (const message of messages.value) {
    if (message.voiceUrl) URL.revokeObjectURL(message.voiceUrl)
  }
}

/** 锁定时清除明文音频、草稿和密钥，停止尚在等待的解密结果。 */
function lockRoom() {
  cancelVoice()
  clearVoiceMessages()
  historyVersion++
  refreshing.value = false
  roomKey = null
  keyReady.value = false
  messages.value = messages.value.map(({ voice, voiceUrl, duration, ...message }) => ({ ...message,
    displayContent: message.enc === 1 ? '已加密，当前设备无法解密' : message.displayContent }))
}

/** 仅显式点击后请求麦克风；录完留在本地预览，不自动发送。 */
async function startVoice() {
  if (!voiceSupported || voicePhase.value !== 'idle' || sending.value || keyBusy.value
      || !keyReady.value || !isUnlocked() || sessionExpired.value || props.chat.stealth || auth.user?.role !== 'USER') return
  const version = ++voiceVersion
  voicePhase.value = 'requesting'
  error.value = ''
  try {
    const mimeType = voiceMimeTypes.find(type => MediaRecorder.isTypeSupported(type))
    if (!mimeType) throw new Error('当前浏览器不支持录音格式')
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    if (disposed || version !== voiceVersion || !keyReady.value || !isUnlocked()) {
      stream.getTracks().forEach(track => track.stop())
      return
    }
    microphone = stream
    const current = new MediaRecorder(stream, { mimeType, audioBitsPerSecond: 32000 })
    recorder = current
    let chunks = []
    let size = 0
    const startedAt = Date.now()
    let duration = 0
    current.ondataavailable = event => {
      if (disposed || version !== voiceVersion || !event.data.size) return
      size += event.data.size
      if (size > maxVoiceBytes) {
        cancelVoice()
        error.value = '语音超过512 KiB，请缩短录制'
        return
      }
      chunks.push(event.data)
    }
    current.onerror = () => {
      if (version !== voiceVersion) return
      cancelVoice()
      error.value = '录音失败，请重试'
    }
    current.onstop = () => {
      if (disposed || version !== voiceVersion) return
      duration = duration || Math.min(maxVoiceDuration, Math.max(1, Date.now() - startedAt))
      releaseMicrophone()
      recorder = null
      if (!isUnlocked() || !keyReady.value) { cancelVoice(); return }
      const blob = new Blob(chunks, { type: current.mimeType })
      chunks = []
      if (!blob.size || blob.size > maxVoiceBytes || !voiceMimeTypes.includes(blob.type)) {
        cancelVoice()
        error.value = '录音为空或格式无效，请重试'
        return
      }
      voiceBlob = blob
      voiceDuration.value = duration
      voicePreview.value = URL.createObjectURL(blob)
      voicePhase.value = 'ready'
    }
    // 在stop调用时冻结录制时长，避免异步回调延迟计入语音时长。
    current.stopRecording = () => {
      if (current.state !== 'recording') return
      duration = Math.min(maxVoiceDuration, Math.max(1, Date.now() - startedAt))
      voicePhase.value = 'processing'
      current.stop()
      releaseMicrophone()
    }
    current.start(250)
    voicePhase.value = 'recording'
    voiceTimer = setTimeout(stopVoice, maxVoiceDuration)
  } catch (cause) {
    if (disposed || version !== voiceVersion) return
    cancelVoice()
    error.value = cause.name === 'NotAllowedError' ? '请允许使用麦克风' : '无法录音，请检查麦克风与浏览器支持'
  }
}

/** 停止录音并等待最后一块音频，麦克风立即释放。 */
function stopVoice() {
  recorder?.stopRecording()
}

/** 复用房间发送接口，只提交语音密文；失败保留本地草稿供重试。 */
async function sendVoice() {
  if (voicePhase.value !== 'ready' || !voiceBlob || sending.value || keyBusy.value
      || !keyReady.value || !isUnlocked() || sessionExpired.value || props.chat.stealth) return
  const version = voiceVersion
  const key = roomKey
  sending.value = true
  error.value = ''
  try {
    const envelope = await encryptVoice(key, props.chat.room.id, props.chat.member.id, voiceBlob, voiceDuration.value)
    if (disposed || version !== voiceVersion || key !== roomKey || !isUnlocked()) return
    const message = await sendMessage(props.chat.room.id, props.chat.member.token, envelope)
    if (disposed || version !== voiceVersion) return
    await mergeMessages([message], true)
    if (version === voiceVersion) cancelVoice()
  } catch (cause) {
    if (!disposed && version === voiceVersion) showError(cause)
  } finally {
    sending.value = false
  }
}

/** 其他标签页清理或替换登录凭证时同步清除解密音频。 */
function handleStorage(event) {
  if (event.key === 'mairy.auth.token' || event.key === null) lockRoom()
}
window.addEventListener?.('mairy:lock-keys', lockRoom)
window.addEventListener?.('storage', handleStorage)

/** 打开设备密钥对话框，由原生对话框管理焦点与 Esc 关闭。 */
function openKeyDialog() {
  keyDialog.value?.showModal()
}

/** 刷新本设备密钥与待授权成员，非安全环境仅加载可读历史。 */
async function refreshKeys() {
  if (keyBusy.value || disposed) return
  keyBusy.value = true
  lockRoom()
  candidates.value = []
  myFingerprint.value = ''
  roomFingerprint.value = ''
  owner.value = false
  try {
    if (auth.user?.role === 'ADMIN' || props.chat.stealth) {
      keyNotice.value = '当前为只读访问，无法解密消息'
      return
    }
    if (!secureContext) {
      keyNotice.value = secureContextNotice
      return
    }
    if (!isUnlocked()) throw new Error('请用登录密码解锁')
    myFingerprint.value = identityFingerprint()
    const result = await loadRoomKey(props.chat.room.id, props.chat.member.token, verifiedRoomFingerprint.value)
    if (disposed) return
    roomKey = result.key
    roomFingerprint.value = result.fingerprint
    verifiedRoomFingerprint.value = ''
    keyReady.value = true
    owner.value = result.ownerUserId === auth.user.id
    keyNotice.value = '加密已就绪'
    if (owner.value) candidates.value = await request('/e2ee/rooms/' + props.chat.room.id + '/candidates', { token: props.chat.member.token })
  } catch (cause) {
    if (!disposed) keyNotice.value = cause.message
  } finally {
    keyBusy.value = false
    if (!disposed) await refreshHistory()
  }
}

/** 仅在安全环境本地解锁，密码不发送到接口。 */
async function unlockKeys() {
  if (!secureContext) {
    keyPassword.value = ''
    keyNotice.value = secureContextNotice
    return
  }
  if (keyBusy.value) return
  keyBusy.value = true
  lockRoom()
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
    keyNotice.value = '已授权，请提醒对方刷新密钥'
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
    lockRoom()
    // 导入后同步清理页面身份，重新显示解锁入口与加密消息占位。
    keyPassword.value = ''
    myFingerprint.value = ''
    roomFingerprint.value = ''
    verifiedRoomFingerprint.value = ''
    candidates.value = []
    owner.value = false
    keyNotice.value = '已导入，请用备份原密码解锁'
    await refreshHistory()
  } catch {
    keyNotice.value = '无法导入：请检查备份账号、格式及本设备是否已有保险箱'
  } finally {
    event.target.value = ''
  }
}

/** 合并REST与推送，只有当前密钥校验通过的语音才创建本地播放URL。 */
async function mergeMessages(records, scroll = false, version = historyVersion) {
  const decoded = await Promise.all(records.map(message => decryptMessage(roomKey, props.chat.room.id, message)))
  if (disposed || version !== historyVersion) return
  const byId = new Map(messages.value.map((message) => [message.id, message]))
  for (const message of decoded) {
    const old = byId.get(message.id)
    if (old?.voiceUrl) URL.revokeObjectURL(old.voiceUrl)
    if (message.voice) message.voiceUrl = URL.createObjectURL(message.voice)
    byId.set(message.id, message)
  }
  messages.value = [...byId.values()].sort((a, b) => BigInt(a.id) < BigInt(b.id) ? -1 : 1)
  if (scroll) nextTick(() => messageList.value?.scrollTo({ top: messageList.value.scrollHeight }))
}

/** 展示中文接口错误，过期会话停止继续发送。 */
function showError(cause) {
  error.value = cause.message
  if (cause.status === 401) {
    sessionExpired.value = true
    lockRoom()
    status.value = '会话已过期，请返回大厅'
    socket?.close()
  }
}

/** 刷新最近历史前释放语音URL，避免残留解密音频和断线缺口。 */
async function refreshHistory() {
  const version = ++historyVersion
  refreshing.value = true
  clearVoiceMessages()
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

/** 保存文字后合并回包；录音期间禁发文字，避免重复操作。 */
async function handleSend() {
  const content = draft.value.trim()
  if (!content || voicePhase.value !== 'idle' || sending.value || keyBusy.value || sessionExpired.value || props.chat.stealth || !keyReady.value) return
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
  cancelVoice()
  clearVoiceMessages()
  window.removeEventListener?.('mairy:lock-keys', lockRoom)
  window.removeEventListener?.('storage', handleStorage)
  roomKey = null
  keyPassword.value = ''
  socket?.close()
})
</script>

<template>
  <section class="panel chat-panel">
    <!-- 顶部信息在短屏内独立滚动，为消息和发送栏保留空间。 -->
    <div class="chat-context">
      <div class="section-heading">
        <!-- 门标接续大厅票卡，消息区域保持静态阅读。 -->
        <div class="chat-identity">
          <span class="paper-mark" aria-hidden="true"></span>
          <div><h2>{{ chat.room.name }}</h2>
          <p class="muted">{{ chat.member.nickname }} {{ chat.stealth ? "· 隐身只读" : "" }} · <span role="status">{{ status }}</span></p></div>
        </div>
        <button class="secondary" @click="$emit('leave')">返回大厅</button>
      </div>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <!-- 聊天页仅保留一个入口，密钥表单按需在对话框中展开。 -->
      <dialog ref="keyDialog" class="key-dialog" aria-labelledby="device-key-title" @close="keyPassword = ''">
        <div class="key-dialog-heading">
          <div>
            <h3 id="device-key-title">设备密钥</h3>
            <p class="muted">端到端加密</p>
          </div>
          <button type="button" class="secondary" aria-label="关闭设备密钥" @click="keyDialog.close()">关闭</button>
        </div>
        <!-- HTTP 下只展示一条环境提示，禁用按钮旁始终可见原因。 -->
        <p v-if="!secureContext" id="key-context-warning" class="key-warning" role="alert">{{ secureContextNotice }}</p>
        <p v-else class="key-status" role="status">{{ keyBusy ? '正在处理…' : keyNotice }}</p>
        <template v-if="auth.user?.role === 'USER' && !chat.stealth">
          <form v-if="!myFingerprint" class="key-form" @submit.prevent="unlockKeys">
            <label>登录密码<input v-model="keyPassword" type="password" autocomplete="current-password" required maxlength="128"
              :disabled="keyBusy || !secureContext" :aria-describedby="!secureContext ? 'key-context-warning' : 'key-password-hint'" autofocus /></label>
            <p id="key-password-hint" class="muted">密码重置后，请用原密码解锁。</p>
            <button :disabled="keyBusy || !secureContext" :aria-describedby="!secureContext ? 'key-context-warning' : undefined">{{ keyBusy ? '解锁中…' : '解锁' }}</button>
          </form>
          <button v-else type="button" class="secondary key-refresh" :disabled="keyBusy || !secureContext" @click="refreshKeys">刷新密钥</button>
          <details class="key-section">
            <summary>密钥备份</summary>
            <div class="key-section-content">
              <p class="muted">备份需用原密码解锁；丢失后无法恢复。</p>
              <button type="button" class="secondary" :disabled="!myFingerprint || keyBusy" @click="downloadBackup">导出备份</button>
              <label>导入备份<input type="file" accept="application/json,.json" :disabled="keyBusy" @change="restoreBackup" /></label>
            </div>
          </details>
          <details v-if="myFingerprint" class="key-section" :open="!keyReady">
            <summary>房间指纹</summary>
            <div class="key-section-content">
              <form v-if="!keyReady" class="key-form" @submit.prevent="refreshKeys">
                <label>房主提供的完整指纹<input v-model="verifiedRoomFingerprint" autocomplete="off" maxlength="64" required
                  :disabled="keyBusy || !secureContext" placeholder="通过其他可信渠道向房主获取" /></label>
                <button :disabled="keyBusy || !secureContext">核验指纹</button>
              </form>
              <template v-else>
                <p class="muted">已核验。房主可通过可信渠道分享此指纹。</p>
                <p class="key-fingerprint">{{ roomFingerprint }}</p>
              </template>
            </div>
          </details>
          <details v-if="myFingerprint" class="key-section">
            <summary>设备指纹</summary>
            <div class="key-section-content">
              <p class="muted">通过可信渠道发给房主，用于成员授权。</p>
              <p class="key-fingerprint">{{ myFingerprint }}</p>
            </div>
          </details>
          <details v-if="owner && keyReady && candidates.length" class="key-section">
            <summary>成员授权（{{ candidates.length }}）</summary>
            <div class="key-section-content">
              <p class="muted">授权后可读全部历史消息。请向成员本人核对指纹。</p>
              <details v-for="candidate in candidates" :key="candidate.userId" class="key-candidate">
                <summary>{{ candidate.nickname }}（账号 {{ candidate.userId }}）</summary>
                <form class="key-form" @submit.prevent="grantMember(candidate)">
                  <label>成员本人提供的完整指纹<input v-model="candidate.verifiedFingerprint" autocomplete="off" maxlength="64" required :disabled="keyBusy" /></label>
                  <button :disabled="keyBusy || !candidate.verifiedFingerprint">核验并授权</button>
                </form>
              </details>
            </div>
          </details>
        </template>
      </dialog>
      <div class="chat-tools">
        <button type="button" class="secondary key-entry" :class="{ 'key-ready': keyReady }" aria-haspopup="dialog" @click="openKeyDialog">设备密钥</button>
        <button class="secondary" :disabled="refreshing || loadingHistory || sessionExpired" @click="refreshHistory">刷新消息</button>
        <button v-if="status === '实时连接已断开'" class="secondary" :disabled="sessionExpired" @click="connect">重新连接</button>
        <!-- 禁用发送时保留简短的密钥操作指引。 -->
        <span v-if="auth.user?.role === 'USER' && !chat.stealth" class="muted key-hint" role="status">{{ keyReady ? '加密已就绪' : '发送前请打开设备密钥' }}</span>
      </div>
    </div>
    <div ref="messageList" class="message-list" role="log" aria-label="聊天消息" aria-live="polite">
      <button v-if="hasMore" class="secondary load-more" :disabled="loadingHistory || refreshing" @click="loadOlder">
        {{ loadingHistory ? '加载中…' : '加载更早消息' }}
      </button>
      <p v-if="refreshing" class="empty">加载中…</p>
      <p v-else-if="!messages.length" class="empty">暂无消息</p>
      <article v-for="message in messages" :key="message.id" class="message"
        :class="{ own: message.memberId === chat.member.id }">
        <div class="message-meta"><span>{{ message.legacy ? "旧明文" : "加密消息" }}</span><strong>{{ message.nickname }}</strong><time>{{ formatTime(message.createdAt) }}</time></div>
        <!-- 使用文本插值展示用户输入，避免将消息当作 HTML。 -->
        <div v-if="message.voiceUrl" class="message-bubble voice-message">
          <span>语音 · {{ Math.ceil(message.duration / 1000) }}秒</span>
          <audio :src="message.voiceUrl" controls preload="none" :aria-label="message.nickname + '的语音'"
            @error="error = '当前浏览器无法播放此语音'"></audio>
        </div>
        <p v-else class="message-bubble">{{ message.displayContent }}</p>
      </article>
    </div>
    <p v-if="chat.stealth" class="notice">隐身只读：不会向房间公开成员或进房事件。</p>
    <form v-else class="composer" @submit.prevent="handleSend">
      <!-- 输入及语音内容可内部滚动，发送栏始终留在底部。 -->
      <div class="composer-body">
        <label for="message-content">说点什么</label>
        <textarea ref="composerInput" id="message-content" @keydown="handleEnter" v-model="draft" maxlength="1000" rows="3"
          :disabled="sessionExpired || !keyReady" placeholder="说点什么…" required></textarea>
        <div class="emoji-picker" aria-label="表情选择器">
          <button v-for="emoji in emojis" :key="emoji" type="button" class="secondary" :disabled="sessionExpired" :aria-label="`插入表情 ${emoji}`" @click="insertEmoji(emoji)">{{ emoji }}</button>
        </div>
        <!-- 语音只保存在本地，点击发送时才使用房间密钥加密。 -->
        <div class="voice-tools">
          <button v-if="voicePhase === 'idle'" type="button" class="secondary"
            :disabled="!voiceSupported || !keyReady || keyBusy || sending || sessionExpired" @click="startVoice">录语音</button>
          <button v-if="voicePhase === 'recording'" type="button" @click="stopVoice">结束录音</button>
          <span class="muted" role="status">{{ voicePhase === 'requesting' ? '请允许使用麦克风' : voicePhase === 'recording' ? '录音中，最多60秒' : voicePhase === 'processing' ? '正在处理录音…' : !voiceSupported ? '录音需HTTPS及浏览器支持' : '最多60秒 · 512 KiB' }}</span>
          <button v-if="voicePhase !== 'idle'" type="button" class="secondary" :disabled="sending" @click="cancelVoice">取消语音</button>
        </div>
        <div v-if="voicePreview" class="voice-tools">
          <audio :src="voicePreview" controls preload="metadata" aria-label="待发送语音预览" @error="error = '当前浏览器无法播放此语音'"></audio>
          <span class="muted">{{ Math.ceil(voiceDuration / 1000) }}秒 · 仅本地预览</span>
          <button type="button" :disabled="sending || sessionExpired || !keyReady || keyBusy" @click="sendVoice">{{ sending ? '发送中…' : '发送语音' }}</button>
        </div>
      </div>
      <div class="composer-footer">
        <span class="muted">{{ draft.length }} / 1000 · Enter发送 · Shift+Enter换行</span>
        <button :disabled="voicePhase !== 'idle' || sending || sessionExpired || !keyReady || keyBusy || !draft.trim()">{{ sending ? '发送中…' : '发送' }}</button>
      </div>
    </form>
  </section>
</template>

<style scoped>
/* 语音控件复用页面令牌与原生播放器，小屏自动换行。 */
.voice-tools { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
.voice-tools button { min-height: 44px; }
.voice-tools audio, .voice-message audio { width: min(280px, 100%); max-width: 100%; }
.voice-message { display: grid; gap: 8px; min-width: 0; }
/* 密钥弹窗复用纸面令牌，安全提示与指纹核验保持可见。 */
.key-entry::before { content: ''; display: inline-block; width: 7px; height: 7px; margin-right: 7px; border-radius: 50%; background: var(--muted); }
.key-entry.key-ready::before { background: var(--action); }
.key-dialog { width: min(460px, calc(100% - 32px)); max-height: calc(100dvh - 48px); padding: 24px; border: 1px solid var(--line); border-radius: var(--radius-dialog); color: var(--ink); background: var(--paper); box-shadow: var(--shadow-paper); overflow-y: auto; }
.key-dialog[open] { animation: paper-settle 240ms var(--ease-paper); }
.key-dialog::backdrop { background: #263e3466; }
.key-dialog-heading { display: flex; justify-content: space-between; align-items: center; gap: 16px; margin-bottom: 18px; }
.key-dialog-heading button { padding: 6px 12px; }
.key-status, .key-warning { padding: 10px 12px; margin-bottom: 18px; border-radius: 8px; font-size: 14px; overflow-wrap: anywhere; }
.key-status { background: var(--tint); }
.key-warning { background: var(--warning); color: var(--ink); }
.key-form, .key-section-content { display: grid; gap: 12px; }
.key-form button, .key-section-content > button { justify-self: start; }
.key-refresh { margin-bottom: 4px; }
.key-section { margin-top: 18px; padding-top: 14px; border-top: 1px dotted var(--line); }
.key-section summary { cursor: pointer; font-size: 14px; font-weight: 600; overflow-wrap: anywhere; }
.key-section-content, .key-candidate .key-form { margin-top: 12px; }
.key-fingerprint { padding: 10px 12px; border-radius: 8px; background: var(--rice); overflow-wrap: anywhere; font-family: monospace; font-size: 13px; user-select: all; }
/* 小屏让入口自动换行，对话框保持可滚动。 */
.chat-tools { flex-wrap: wrap; }
@media (max-width: 600px) {
  .key-dialog { padding: 20px; max-height: calc(100dvh - 24px); }
}
</style>
