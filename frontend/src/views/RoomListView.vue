<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { createRoom, listRooms, request } from '../api/chat'
import { auth } from '../auth'

const rooms = ref([])
const mine = ref(null)
const page = ref(0)
const total = ref(0)
const loading = ref(false)
const creating = ref(false)
const roomName = ref('')
const keyword = ref('')
const results = ref([])
const relations = ref([])
const error = ref('')
const notice = ref('')
const friendBusy = ref(false)
// 入场只改变纸卡展示，不延迟大厅请求和路由跳转。
const entering = ref(false)
let entranceTimer
let disposed = false

/** 每个标签页首次加载大厅时播放一次纸卡归位。 */
function startEntrance() {
  if (disposed || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return
  try {
    if (sessionStorage.getItem('mairy.lobby.entrance.seen')) return
    sessionStorage.setItem('mairy.lobby.entrance.seen', '1')
  } catch { return }
  entering.value = true
  entranceTimer = setTimeout(() => { entering.value = false }, 360)
}

onBeforeUnmount(() => { disposed = true; clearTimeout(entranceTimer) })
const friends = computed(() => relations.value.filter(row => row.status === 'ACCEPTED'))
const incoming = computed(() => relations.value.filter(row => row.status === 'PENDING' && row.receiver.id === auth.user?.id))
const outgoing = computed(() => relations.value.filter(row => row.status === 'PENDING' && row.sender.id === auth.user?.id))

/** 分页读取现有大厅，失败时保留已加载数据。 */
async function loadRooms(reset = false) {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const next = reset ? 1 : page.value + 1
    const result = await listRooms(next)
    rooms.value = reset ? result.records : [...rooms.value, ...result.records]
    page.value = next
    total.value = result.total
  } catch (cause) { error.value = cause.message }
  finally { loading.value = false }
}

/** 创建唯一自建房后刷新大厅，服务端唯一约束处理并发。 */
async function handleCreate() {
  if (!roomName.value.trim() || creating.value) return
  creating.value = true
  error.value = ''
  try {
    mine.value = await createRoom(roomName.value.trim())
    roomName.value = ''
    await loadRooms(true)
  } catch (cause) { error.value = cause.message }
  finally { creating.value = false }
}

/** 搜索其他用户，统一使用编号或用户名、昵称。 */
async function search() {
  if (!keyword.value.trim() || friendBusy.value) return
  friendBusy.value = true
  error.value = ''
  try {
    results.value = await request(`/friends/search?q=${encodeURIComponent(keyword.value.trim())}`)
    notice.value = results.value.length ? '' : '没有匹配的用户'
  } catch (cause) { error.value = cause.message }
  finally { friendBusy.value = false }
}

/** 刷新好友及申请状态，便于接收新请求。 */
async function refreshFriends() {
  relations.value = await request('/friends')
}

/** 发起或处理申请后立即刷新关系列表。 */
async function friendAction(path) {
  if (friendBusy.value) return
  friendBusy.value = true
  error.value = ''
  try {
    notice.value = (await request(path, { method: 'POST' })).message
    await refreshFriends()
  } catch (cause) { error.value = cause.message }
  finally { friendBusy.value = false }
}

/** 手动刷新好友并显示网络错误。 */
async function reloadFriends() {
  try { await refreshFriends() } catch (cause) { error.value = cause.message }
}

/** 从一条双向关系中取得对方的资料。 */
function other(row) {
  return row.sender.id === auth.user?.id ? row.receiver : row.sender
}

onMounted(async () => {
  await loadRooms(true)
  startEntrance()
  try {
    mine.value = await request('/rooms/mine')
    await refreshFriends()
  } catch (cause) { error.value = cause.message }
})
</script>

<template>
  <!-- 房间票卡仅支持进入，不呈现多选或默认选中态。 -->
  <div class="home-grid" :class="{ 'is-entering': entering }">
    <section class="panel home-main">
      <div class="section-heading"><div><span class="room-label">{{ auth.user.publicNo }}</span><h2>{{ auth.user.nickname }}，你好</h2></div><button class="secondary" :disabled="loading" @click="loadRooms(true)">刷新大厅</button></div>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <p v-if="notice" class="notice" role="status">{{ notice }}</p>
      <div class="my-room paper-stack">
        <h3>我的房间</h3>
        <div v-if="mine" class="section-heading"><span>{{ mine.name }}</span><RouterLink class="button-link" :to="`/room/${mine.id}`">进入 →</RouterLink></div>
        <form v-else class="inline-form" @submit.prevent="handleCreate"><label class="grow">房间名<input v-model="roomName" required maxlength="50" placeholder="输入房间名" /></label><button :disabled="creating || !roomName.trim()">创建</button></form>
      </div>
      <h3>发现房间</h3>
      <p v-if="loading && !rooms.length" class="empty">加载中…</p>
      <p v-else-if="!rooms.length" class="empty">暂无房间</p>
      <div class="room-grid"><RouterLink v-for="room in rooms" :key="room.id" class="room-card" :to="`/room/${room.id}`"><span class="paper-mark room-stamp" aria-hidden="true"></span><strong>{{ room.name }}</strong><span class="ticket-entry">进入 →</span></RouterLink></div>
      <button v-if="rooms.length < total" class="secondary load-more" :disabled="loading" @click="loadRooms()">更多</button>
    </section>
    <aside class="panel">
      <div class="section-heading"><h2>朋友</h2><button class="secondary" @click="reloadFriends">刷新</button></div>
      <form class="friend-search" @submit.prevent="search"><label>找朋友<input v-model="keyword" required maxlength="32" placeholder="#0001 / 用户名 / 昵称" /></label><button :disabled="friendBusy">搜索</button></form>
      <div v-for="user in results" :key="user.id" class="people-row"><div><strong>{{ user.nickname }}</strong><p class="muted">{{ user.publicNo }} · {{ user.username }}</p></div><button class="secondary" :disabled="friendBusy" @click="friendAction(`/friends/${user.id}`)">加好友</button></div>
      <h3 class="subheading">好友 · {{ friends.length }}</h3>
      <p v-if="!friends.length" class="muted">暂无好友</p>
      <!-- 私信入口使用对方账号，好友权限由后端逐次验证。 -->
      <div v-for="row in friends" :key="row.id" class="people-row"><span>{{ other(row).nickname }} <small class="muted">{{ other(row).publicNo }}</small></span><RouterLink class="button-link" :to="'/dm/' + other(row).id">发私信</RouterLink></div>
      <h3 class="subheading">收到 · {{ incoming.length }}</h3>
      <div v-for="row in incoming" :key="row.id" class="people-row"><span>{{ row.sender.nickname }} {{ row.sender.publicNo }}</span><div class="actions"><button :disabled="friendBusy" @click="friendAction(`/friends/requests/${row.id}?accept=true`)">同意</button><button class="secondary" :disabled="friendBusy" @click="friendAction(`/friends/requests/${row.id}?accept=false`)">拒绝</button></div></div>
      <h3 class="subheading">已发出 · {{ outgoing.length }}</h3>
      <p v-for="row in outgoing" :key="row.id" class="muted">{{ row.receiver.nickname }} {{ row.receiver.publicNo }} · 等待回应</p>
    </aside>
  </div>
</template>
