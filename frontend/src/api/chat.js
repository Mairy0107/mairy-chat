import { auth, clearLogin } from '../auth'

/** 生产构建走 /chat 前缀，本地开发仍用 Vite 根路径代理。 */
const API_BASE = import.meta.env.VITE_API_BASE || '/api'
const WS_PATH = import.meta.env.VITE_WS_PATH || '/ws/chat'

/** 发起业务请求，统一携带登录和进房凭证并转换错误信息。 */
export async function request(path, { method = 'GET', body, token, publicRequest = false } = {}) {
  const headers = {}
  const loginToken = publicRequest ? null : auth.token
  if (loginToken) headers.Authorization = `Bearer ${loginToken}`
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
  const data = await response.json().catch(() => null)
  if (!response.ok) {
    if (response.status === 401 && loginToken && auth.token === loginToken) {
      clearLogin()
      window.dispatchEvent(new Event('mairy:unauthorized'))
    }
    const error = new Error(data?.message || '请求失败，请稍后重试')
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

/** 以登录身份获取绑定当前房间的会话，隐身由后端校验。 */
export function joinRoom(roomId, stealth = false) {
  return request(`/rooms/${roomId}/members`, { method: 'POST', body: { stealth } })
}

/** 按游标加载一段历史，返回的消息按时间正序排列。 */
export function listMessages(roomId, token, beforeId) {
  const query = new URLSearchParams({ size: '30' })
  if (beforeId) query.set('beforeId', beforeId)
  return request(`/rooms/${roomId}/messages?${query}`, { token })
}

/** 仅传输客户端生成的密文信封，WebSocket 只负责接收推送。 */
export function sendMessage(roomId, token, envelope) {
  return request(`/rooms/${roomId}/messages`, { method: 'POST', token, body: envelope })
}

/** 使用同源路径建立房间实时连接，不打印带凭证的 URL。 */
export function openChatSocket(token) {
  const url = new URL(WS_PATH, window.location.href)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  url.searchParams.set('token', token)
  return new WebSocket(url)
}