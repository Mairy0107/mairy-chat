import { auth, clearLogin } from '../auth'
import { request } from './chat'
import { consumeAiStream } from './ai-stream'

const API_BASE = import.meta.env.VITE_API_BASE || '/api'

/** 读取模型可用性和上海当日配额。 */
export function getAiStatus() { return request('/ai/status') }

/** 列出本人最近的助手会话。 */
export function listAiThreads() { return request('/ai/threads') }

/** 创建独立助手会话，不涉及进房或设备密钥。 */
export function createAiThread() { return request('/ai/threads', { method: 'POST' }) }

/** 复用现有主键游标分页格式。 */
export function listAiMessages(threadId, beforeId) {
  const query = new URLSearchParams({ size: '30' })
  if (beforeId) query.set('beforeId', beforeId)
  return request('/ai/threads/' + encodeURIComponent(threadId) + '/messages?' + query)
}

/** fetch 支持 Bearer 与主动中止，流请求独立于普通 REST 的十秒超时。 */
export async function streamAiMessage(threadId, content, signal, onEvent) {
  const loginToken = auth.token
  let response
  try {
    response = await fetch(`${API_BASE}/ai/threads/${encodeURIComponent(threadId)}/messages`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${loginToken}`, 'Content-Type': 'application/json', Accept: 'text/event-stream, application/json' },
      body: JSON.stringify({ content }),
      signal: AbortSignal.any([signal, AbortSignal.timeout(180000)]),
    })
  } catch (cause) {
    if (signal.aborted) throw cause
    throw new Error(cause.name === 'TimeoutError' ? '回复超时，请稍后重试' : '无法连接助手，请稍后重试')
  }
  if (!response.ok) {
    const data = await response.json().catch(() => null)
    if (response.status === 401 && loginToken && auth.token === loginToken) {
      clearLogin()
      window.dispatchEvent(new Event('mairy:unauthorized'))
    }
    const error = new Error(data?.message || '助手暂不可用，请稍后重试')
    error.status = response.status
    throw error
  }
  if (!response.body || !response.headers.get('content-type')?.includes('text/event-stream')) {
    throw new Error('回复格式异常，请刷新对话')
  }
  await consumeAiStream(response.body, (type, data) => {
    if (!signal.aborted && auth.token === loginToken) onEvent(type, data)
  })
}
