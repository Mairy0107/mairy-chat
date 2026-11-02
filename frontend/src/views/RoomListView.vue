<script setup>
import { onMounted, ref } from 'vue'
import { createRoom, joinRoom, listRooms } from '../api/chat'

const emit = defineEmits(['enter'])
const rooms = ref([])
const page = ref(0)
const total = ref(0)
const loading = ref(false)
const creating = ref(false)
const joining = ref(false)
const roomName = ref('')
const nickname = ref('')
const selectedRoom = ref(null)
const error = ref('')

/** 分页加载大厅，失败时保留当前已加载的房间。 */
async function loadRooms(reset = false) {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    const nextPage = reset ? 1 : page.value + 1
    const result = await listRooms(nextPage)
    rooms.value = reset ? result.records : [...rooms.value, ...result.records]
    page.value = nextPage
    total.value = result.total
  } catch (cause) {
    error.value = cause.message
  } finally {
    loading.value = false
  }
}

/** 创建后刷新第一页，并展开该房间的昵称输入。 */
async function handleCreate() {
  const name = roomName.value.trim()
  if (!name || creating.value || loading.value) return
  creating.value = true
  error.value = ''
  try {
    const room = await createRoom(name)
    roomName.value = ''
    selectedRoom.value = room
    await loadRooms(true)
  } catch (cause) {
    error.value = cause.message
  } finally {
    creating.value = false
  }
}

/** 获取会话成功后进入聊天页，凭证不写入持久化存储。 */
async function handleJoin() {
  const name = nickname.value.trim()
  if (!name || !selectedRoom.value || joining.value) return
  joining.value = true
  error.value = ''
  const room = selectedRoom.value
  try {
    const member = await joinRoom(room.id, name)
    emit('enter', { room, member })
  } catch (cause) {
    error.value = cause.message
  } finally {
    joining.value = false
  }
}

onMounted(() => loadRooms(true))
</script>

<template>
  <section class="panel">
    <div class="section-heading">
      <div>
        <h2>房间大厅</h2>
        <p class="muted">找个感兴趣的话题，加入聊天。</p>
      </div>
      <button class="secondary" :disabled="loading || creating" @click="loadRooms(true)">刷新</button>
    </div>

    <form class="inline-form" @submit.prevent="handleCreate">
      <label class="grow">新房间名称
        <input v-model="roomName" maxlength="50" required placeholder="例如：今天也要好好生活" />
      </label>
      <button :disabled="creating || loading || !roomName.trim()">{{ creating ? '创建中…' : '创建房间' }}</button>
    </form>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="loading && !rooms.length" class="empty" role="status">正在寻找房间…</p>
    <p v-else-if="!rooms.length" class="empty">还没有房间，创建第一个吧。</p>

    <div class="room-grid">
      <button v-for="room in rooms" :key="room.id" class="room-card"
        :class="{ selected: selectedRoom?.id === room.id }" :disabled="joining"
        :aria-pressed="selectedRoom?.id === room.id" @click="selectedRoom = room">
        <span class="room-label">聊天房间</span>
        <strong>{{ room.name }}</strong>
        <span class="muted">输入昵称加入 →</span>
      </button>
    </div>
    <button v-if="rooms.length < total" class="secondary load-more" :disabled="loading || creating"
      @click="loadRooms()">{{ loading ? '加载中…' : '更多房间' }}</button>

    <form v-if="selectedRoom" class="join-form" @submit.prevent="handleJoin">
      <h3>加入「{{ selectedRoom.name }}」</h3>
      <p class="muted">昵称只用于本次会话，刷新页面后需重新加入。</p>
      <div class="inline-form">
        <label class="grow">你的昵称
          <input v-model="nickname" maxlength="20" required autocomplete="nickname" placeholder="大家怎么称呼你？" />
        </label>
        <button :disabled="joining || !nickname.trim()">{{ joining ? '加入中…' : '进入聊天' }}</button>
      </div>
    </form>
  </section>
</template>
