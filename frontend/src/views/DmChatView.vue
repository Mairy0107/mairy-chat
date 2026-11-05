<script setup>
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { listDmMessages, openDm, openDmSocket, sendDmMessage } from '../api/chat'
import { auth } from '../auth'
import { encodeDmVoice, readDmVoice, maxVoiceBytes, maxVoiceDuration, voiceMimeTypes } from '../media/dm-voice'

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

// 复用房间录音交互，私信不需要设备解锁或房间密钥。
const voicePhase = ref('idle')
const voicePreview = ref('')
const voiceDuration = ref(0)
const voiceSupported = window.isSecureContext === true && typeof MediaRecorder !== 'undefined'
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

/** 刷新、权限失效和离开页面时撤销所有语音播放 URL。 */
function clearVoiceMessages() {
  for (const message of messages.value) {
    if (message.voiceUrl) URL.revokeObjectURL(message.voiceUrl)
  }
}

/** 仅显式点击后请求麦克风；录完留在本地预览，不自动发送。 */
async function startVoice() {
  if (!voiceSupported || !thread.value || blocked.value || voicePhase.value !== 'idle' || sending.value) return
  const version = ++voiceVersion
  voicePhase.value = 'requesting'
  error.value = ''
  try {
    const mimeType = voiceMimeTypes.find(type => MediaRecorder.isTypeSupported(type))
    if (!mimeType) throw new Error('当前浏览器不支持录音格式')
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    if (disposed || version !== voiceVersion || blocked.value) {
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
      if (blocked.value) { cancelVoice(); return }
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

/** 仅提交明文音频载荷；发送失败保留草稿，切换好友后丢弃旧结果。 */
async function sendVoice() {
  if (voicePhase.value !== 'ready' || !voiceBlob || sending.value || blocked.value || !thread.value) return
  const version = generation
  const voice = voiceVersion
  const threadId = thread.value.id
  sending.value = true
  error.value = ''
  try {
    const payload = await encodeDmVoice(voiceBlob, voiceDuration.value)
    if (!current(version) || voice !== voiceVersion || blocked.value) return
    const message = await sendDmMessage(threadId, payload)
    if (!current(version) || voice !== voiceVersion || blocked.value) return
    await mergeMessages([message], true)
    if (current(version) && voice === voiceVersion) cancelVoice()
  } catch (cause) {
    if (current(version) && voice === voiceVersion) showError(cause)
  } finally {
    if (current(version)) sending.value = false
  }
}

/** 登录变化时立即清理录音与消息，防止旧账号异步请求继续进入页面。 */
function clearLoginMedia() {
  ++generation
  ++historyVersion
  blocked.value = true
  cancelVoice()
  clearVoiceMessages()
  messages.value = []
  socket?.close()
}

/** 其他标签页退出或换账号时，同步释放麦克风与本地音频。 */
function handleStorage(event) {
  if (event.key === 'mairy.auth.token' || event.key === null) clearLoginMedia()
}
window.addEventListener('mairy:lock-keys', clearLoginMedia)
window.addEventListener('storage', handleStorage)

/** 忽略切换好友或离开页面后返回的异步结果。 */
function current(version) {
  return !disposed && version === generation
}

/** 校验明文语音后生成本地 URL，按主键去重合并 REST 与推送。 */
async function mergeMessages(records, scroll = false) {
  const byId = new Map(messages.value.map(message => [message.id, message]))
  for (const record of records) {
    const previous = byId.get(record.id)
    if (previous?.voiceUrl) URL.revokeObjectURL(previous.voiceUrl)
    // 绝不直接采用网络传入的播放 URL 或 Blob。
    const { voiceUrl, voice, ...message } = record
    if (message.kind === 'voice') {
      try { message.voiceUrl = URL.createObjectURL(readDmVoice(message)) }
      catch { message.voiceError = '语音数据无效，无法播放' }
    }
    byId.set(message.id, message)
  }
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
    cancelVoice()
    clearVoiceMessages()
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
  clearVoiceMessages()
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
  if (!thread.value || !content || sending.value || blocked.value || voicePhase.value !== 'idle') return
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
  cancelVoice()
  clearVoiceMessages()
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
  cancelVoice()
  clearVoiceMessages()
  window.removeEventListener('mairy:lock-keys', clearLoginMedia)
  window.removeEventListener('storage', handleStorage)
  socket?.close()
})
</script>

<template>
  <section class="panel chat-panel dm-panel">
    <!-- 顶部信息在短屏内独立滚动，为消息和发送栏保留空间。 -->
    <div class="chat-context">
      <div class="section-heading">
        <!-- 私信沿用房间的纸卡标题，仅以短标签区分会话。 -->
        <div class="chat-identity"><span class="paper-mark" aria-hidden="true"></span><div><span class="room-label">私信</span><h2>{{ thread?.peer.nickname || '私信' }}</h2><p v-if="thread" class="muted">{{ thread.peer.publicNo }} · <span role="status">{{ status }}</span></p></div></div>
        <RouterLink class="button-link secondary" to="/app">返回大厅</RouterLink>
      </div>
      <p class="notice dm-privacy">私信未加密，服务器可读取内容。</p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <p v-if="opening" class="empty">正在打开私信…</p>
      <button v-if="!opening && !thread && !blocked" class="secondary" @click="start(route.params.userId)">重试</button>
      <div v-if="thread" class="chat-tools">
        <button class="secondary" :disabled="refreshing || loadingOlder || blocked" @click="refreshHistory">刷新消息</button>
        <button v-if="status === '实时连接已断开'" class="secondary" :disabled="blocked" @click="connect">重新连接</button>
      </div>
    </div>
    <template v-if="thread">
      <div ref="messageList" class="message-list" role="log" aria-label="私信消息" aria-live="polite">
        <button v-if="hasMore && !blocked" class="secondary load-more" :disabled="loadingOlder || refreshing" @click="loadOlder">{{ loadingOlder ? '加载中…' : '加载更早消息' }}</button>
        <p v-if="refreshing" class="empty">正在加载消息…</p>
        <p v-else-if="!messages.length && !blocked" class="empty">暂无消息</p>
        <article v-for="message in messages" :key="message.id" class="message" :class="{ own: message.senderId === auth.user?.id }">
          <div class="message-meta"><strong>{{ message.senderId === auth.user?.id ? '我' : thread.peer.nickname }}</strong><time>{{ formatTime(message.createdAt) }}</time></div>
          <!-- 文本插值避免将好友输入渲染为 HTML。 -->
          <div v-if="message.kind === 'voice'" class="message-bubble voice-message">
            <template v-if="message.voiceUrl">
              <span>语音 · {{ Math.ceil(message.duration / 1000) }}秒</span>
              <audio :src="message.voiceUrl" controls preload="none" aria-label="私信语音消息"
                @error="error = '当前浏览器无法播放此语音'"></audio>
            </template>
            <span v-else>{{ message.voiceError }}</span>
          </div>
          <p v-else class="message-bubble">{{ message.content }}</p>
        </article>
      </div>
      <form class="composer" @submit.prevent="handleSend">
        <!-- 两行输入与紧凑语音栏按内容占高，超高时内部滚动，发送栏固定在底部。 -->
        <div class="composer-body">
          <label for="dm-content">发给 {{ thread.peer.nickname }}</label>
          <textarea id="dm-content" v-model="draft" maxlength="1000" rows="2" :disabled="blocked" placeholder="说点什么…" required @keydown="handleEnter"></textarea>
          <!-- 语音只保存在本地，点击发送时明文上传。 -->
          <div class="voice-tools">
            <button v-if="voicePhase === 'idle'" type="button" class="secondary"
              :disabled="!voiceSupported || sending || blocked" @click="startVoice">录语音</button>
            <button v-if="voicePhase === 'recording'" type="button" @click="stopVoice">结束录音</button>
            <span class="muted" role="status">{{ voicePhase === 'requesting' ? '请允许使用麦克风' : voicePhase === 'recording' ? '录音中，最多60秒' : voicePhase === 'processing' ? '正在处理录音…' : !voiceSupported ? '录音需HTTPS或localhost及浏览器支持' : '最多60秒 · 512 KiB' }}</span>
            <button v-if="voicePhase !== 'idle'" type="button" class="secondary" :disabled="sending" @click="cancelVoice">取消语音</button>
          </div>
          <div v-if="voicePreview" class="voice-tools">
            <audio :src="voicePreview" controls preload="metadata" aria-label="待发送语音预览" @error="error = '当前浏览器无法播放此语音'"></audio>
            <span class="muted">{{ Math.ceil(voiceDuration / 1000) }}秒 · 仅本地预览</span>
            <button type="button" :disabled="sending || blocked" @click="sendVoice">{{ sending ? '发送中…' : '发送语音' }}</button>
          </div>
        </div>
        <div class="composer-footer"><span class="muted">{{ draft.length }} / 1000 · Enter发送 · Shift+Enter换行</span><button :disabled="voicePhase !== 'idle' || sending || blocked || !draft.trim()">{{ sending ? '发送中…' : '发送' }}</button></div>
      </form>
    </template>
  </section>
</template>

<style scoped>
/* 复用项目现有聊天面板，仅补充私信页按钮的窄屏排列。 */
.dm-panel .button-link { flex-shrink: 0; }
.dm-panel .chat-tools { flex-wrap: wrap; }
/* 原生音频与录制按钮沿用纸卡样式，小屏自动换行。 */
.voice-tools { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-1) var(--space-2); margin-top: var(--space-1); }
.voice-tools .muted { flex: 1; min-width: 0; font-size: 12px; line-height: 1.4; }
.voice-tools audio, .voice-message audio { width: min(280px, 100%); max-width: 100%; }
.voice-message { display: grid; gap: 8px; min-width: 0; }
</style>
