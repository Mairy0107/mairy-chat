<script setup>
import { onMounted, ref } from 'vue'
import { request, listRooms } from '../api/chat'

const users = ref([])
const page = ref(1)
const total = ref(0)
const resets = ref([])
const blocks = ref([])
const rooms = ref([])
const roomPage = ref(0)
const roomTotal = ref(0)
const selected = ref(null)
const devices = ref([])
const password = ref('')
const error = ref('')
const notice = ref('')
const busy = ref(false)

/** 一次加载后台各个列表，接口权限由后端验证。 */
async function load(nextPage = page.value) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const result = await request(`/admin/users?page=${nextPage}`)
    users.value = result.records
    total.value = result.total
    page.value = nextPage
    resets.value = await request('/admin/reset-requests')
    blocks.value = await request('/admin/ip-blocks')
    await loadRooms(true)
  } catch (cause) { error.value = cause.message }
  finally { busy.value = false }
}

/** 管理员分页浏览全部房间，支持隐身进入。 */
async function loadRooms(reset = false) {
  const next = reset ? 1 : roomPage.value + 1
  try {
    const result = await listRooms(next)
    rooms.value = reset ? result.records : [...rooms.value, ...result.records]
    roomPage.value = next
    roomTotal.value = result.total
  } catch (cause) { error.value = cause.message }
}

/** 选择账号后展示粗略注册设备，临时密码不预填。 */
async function selectUser(user) {
  selected.value = user
  password.value = ''
  devices.value = []
  try {
    const result = await request(`/admin/users/${user.id}/devices`)
    if (selected.value?.id === user.id) devices.value = result
  } catch (cause) { error.value = cause.message }
}

/** 人工核实后重置密码，完成相关申请并撤销该账号旧会话。 */
async function resetPassword() {
  if (!selected.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    const result = await request(`/admin/users/${selected.value.id}/reset-password`, {
      method: 'POST', body: { password: password.value },
    })
    password.value = ''
    selected.value = null
    notice.value = result.message
    resets.value = await request('/admin/reset-requests')
  } catch (cause) { error.value = cause.message }
  finally { busy.value = false }
}

/** 解封后重新读取列表。 */
async function unblock(ip) {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    notice.value = (await request(`/admin/ip-blocks/unblock?ip=${encodeURIComponent(ip)}`, { method: 'POST' })).message
    blocks.value = await request('/admin/ip-blocks')
  } catch (cause) { error.value = cause.message }
  finally { busy.value = false }
}

onMounted(() => load())
</script>

<template>
  <!-- 后台复用同一纸面令牌，管理与权限操作保持原样。 -->
  <section class="panel">
    <div class="section-heading"><div><h2>管理后台</h2></div><button class="secondary" :disabled="busy" @click="load()">刷新后台</button></div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="notice" class="notice" role="status">{{ notice }}</p>
    <h3>用户列表 · {{ total }}</h3>
    <div class="table-scroll"><table><thead><tr><th>编号</th><th>用户名 / 昵称</th><th>角色 / 状态</th><th>操作</th></tr></thead><tbody><tr v-for="user in users" :key="user.id"><td>{{ user.publicNo }}</td><td>{{ user.username }} / {{ user.nickname }}</td><td>{{ user.role === 'ADMIN' ? '管理员' : '用户' }} / {{ user.disabled ? '停用' : '正常' }}</td><td><button class="secondary" :disabled="busy" @click="selectUser(user)">查看设备 / 重置</button></td></tr></tbody></table></div>
    <div class="actions pager"><button class="secondary" :disabled="busy || page <= 1" @click="load(page - 1)">上一页</button><span>第 {{ page }} 页</span><button class="secondary" :disabled="busy || page * 30 >= total" @click="load(page + 1)">下一页</button></div>
    <form v-if="selected" class="my-room paper-stack" @submit.prevent="resetPassword">
      <h3>重置 {{ selected.nickname }} {{ selected.publicNo }}</h3>
      <p class="muted">{{ devices.map(device => device.label).join('、') || '无浏览器注册设备记录（本地种子账号）' }}</p>
      <p class="muted">请先线下核实身份；提交后立即撤销该用户所有登录。重置自己的账号也会退出。</p>
      <label>管理员指定临时密码<input v-model="password" type="password" autocomplete="new-password" required minlength="8" maxlength="64" placeholder="8至64字，最多72字节" /></label>
      <div class="actions"><button :disabled="busy">确认重置并处理待办</button><button type="button" class="secondary" @click="selected = null; password = ''">取消</button></div>
    </form>
    <h3 class="subheading">密码找回申请（最近200条）</h3>
    <p v-if="!resets.length" class="muted">暂无申请</p>
    <div v-for="row in resets" :key="row.id" class="people-row"><div><strong>{{ row.user.nickname }} {{ row.user.publicNo }}</strong><p class="muted">{{ row.note || '无备注' }} · {{ row.status === 'PENDING' ? '待处理' : '已处理' }}</p></div><button v-if="row.status === 'PENDING'" class="secondary" :disabled="busy" @click="selectUser(row.user)">核实并重置</button></div>
    <h3 class="subheading">临时IP封禁</h3>
    <p v-if="!blocks.length" class="muted">没有有效封禁</p>
    <div v-for="block in blocks" :key="block.ip" class="people-row"><div><strong>{{ block.ip }}</strong><p class="muted">{{ block.reason }} · 至 {{ block.untilAt.replace('T', ' ') }}</p></div><button class="secondary" :disabled="busy" @click="unblock(block.ip)">解封</button></div>
    <h3 class="subheading">房间巡查</h3>
    <p class="muted">隐身模式只读，不公开进房记录或成员信息。</p>
    <div class="room-grid"><div v-for="room in rooms" :key="room.id" class="room-card"><strong>{{ room.name }}</strong><div class="actions"><RouterLink :to="`/room/${room.id}`">普通进入</RouterLink><RouterLink :to="`/room/${room.id}?stealth=true`">隐身进入 →</RouterLink></div></div></div>
    <button v-if="rooms.length < roomTotal" class="secondary load-more" :disabled="busy" @click="loadRooms()">更多房间</button>
  </section>
</template>
