<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ChatRoomView from './ChatRoomView.vue'
import { request, joinRoom } from '../api/chat'

const route = useRoute()
const router = useRouter()
const chat = ref(null)
const error = ref('')
let disposed = false

/** 直接打开房间路由时先读取房间，再绑定当前登录身份。 */
async function enter() {
  try {
    const room = await request(`/rooms/${encodeURIComponent(route.params.id)}`)
    const stealth = route.query.stealth === 'true'
    const member = await joinRoom(room.id, stealth)
    if (!disposed) chat.value = { room, member, stealth }
  } catch (cause) {
    if (!disposed) error.value = cause.message
  }
}

onMounted(enter)
onBeforeUnmount(() => { disposed = true })
</script>

<template>
  <ChatRoomView v-if="chat" :chat="chat" @leave="router.push('/app')" />
  <!-- 进入状态复用纸卡，不改变自动进房流程。 -->
  <section v-else class="panel">
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-else class="empty">正在进入房间…</p>
    <RouterLink to="/app">返回大厅</RouterLink>
  </section>
</template>
