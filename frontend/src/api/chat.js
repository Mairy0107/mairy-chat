/** 生产构建走 /chat 前缀，本地开发仍用 Vite 根路径代理。 */
const API_BASE = import.meta.env.VITE_API_BASE || '/api'
const WS_PATH = import.meta.env.VITE_WS_PATH || '/ws/chat'

/** 发起业务请求，统一携带匿名凭证并转换错误信息。 */
async function request(path, { method = 'GET', body, token } = {}) {
  const headers = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (token) headers['X-Session-Token'] = token

  let response
  try {
    response = await fetch(`${API_BASE}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(10000),
    })
  } catch (cause) {
    throw new Error(cause.name === 'TimeoutError' ? '请求超时，请稍后重试' : '无法连接后端，请检查本地服务')
  }
  const data = await response.json().catch(() => ({}))
  if (!response.ok) {
    const error = new Error(data.message || '请求失败，请稍后重试')
    error.status = response.status
    throw error
  }
  return data
}

/** 分页读取房间列表。 */
export function listRooms(page = 1) {
  return request(`/rooms?page=${page}&size=20`)
}

/** 创建房间。 */
export function createRoom(name) {
  return request('/rooms', { method: 'POST', body: { name } })
}

/** 用昵称换取当前房间的匿名会话。 */
export function joinRoom(roomId, nickname) {
  return request(`/rooms/${roomId}/members`, { method: 'POST', body: { nickname } })
}

/** 按游标加载一段历史，返回的消息按时间正序排列。 */
export function listMessages(roomId, token, beforeId) {
  const query = new URLSearchParams({ size: '30' })
  if (beforeId) query.set('beforeId', beforeId)
  return request(`/rooms/${roomId}/messages?${query}`, { token })
}

/** 消息通过 REST 入库，WebSocket 只负责接收推送。 */
export function sendMessage(roomId, token, content) {
  return request(`/rooms/${roomId}/messages`, { method: 'POST', token, body: { content } })
}

/** 使用同源路径建立房间实时连接，不打印带凭证的 URL。 */
export function openChatSocket(token) {
  const url = new URL(WS_PATH, window.location.href)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  url.searchParams.set('token', token)
  return new WebSocket(url)
}