<script setup>
import { shallowRef } from 'vue'
import RoomListView from './views/RoomListView.vue'
import ChatRoomView from './views/ChatRoomView.vue'

// 会话只留在当前页面内存中，刷新后重新输入昵称。
const activeChat = shallowRef(null)

/** 切换到刚加入的聊天房间。 */
function enterRoom(chat) {
  activeChat.value = chat
}

/** 返回大厅并丢弃当前页面的匿名凭证。 */
function leaveRoom() {
  activeChat.value = null
}
</script>

<template>
  <main class="app-shell">
    <header class="app-header">
      <span class="brand-mark" aria-hidden="true">M</span>
      <div>
        <h1>Mairy Chat</h1>
        <p>一个昵称，一间房，一起聊聊。</p>
      </div>
    </header>
    <ChatRoomView v-if="activeChat" :chat="activeChat" @leave="leaveRoom" />
    <RoomListView v-else @enter="enterRoom" />
  </main>
</template>
