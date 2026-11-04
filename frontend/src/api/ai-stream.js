/** 增量解析 SSE，兼容 UTF-8 拆包、CRLF 和多行 data；未收到 done 视为中断。 */
export async function consumeAiStream(stream, onEvent) {
  const reader = stream.getReader()
  const decoder = new TextDecoder()
  let pending = ''
  let event = 'message'
  let data = []

  /** 处理一行 SSE，JSON 正文不会作为 HTML 执行。 */
  function line(value) {
    if (value.endsWith('\r')) value = value.slice(0, -1)
    if (!value) {
      if (!data.length) { event = 'message'; return false }
      const payload = JSON.parse(data.join('\n'))
      const type = event
      data = []
      event = 'message'
      if (type === 'error') throw new Error(payload.message || '回复确认失败，请刷新对话')
      onEvent(type, payload)
      return type === 'done'
    }
    const colon = value.indexOf(':')
    const field = colon < 0 ? value : value.slice(0, colon)
    let content = colon < 0 ? '' : value.slice(colon + 1)
    if (content.startsWith(' ')) content = content.slice(1)
    if (field === 'event') event = content
    if (field === 'data') data.push(content)
    if (data.join('').length > 131072) throw new Error('回复格式异常，请刷新对话')
    return false
  }

  try {
    while (true) {
      const { value, done } = await reader.read()
      pending += done ? decoder.decode() : decoder.decode(value, { stream: true })
      if (pending.length > 262144) throw new Error('回复格式异常，请刷新对话')
      let end
      while ((end = pending.indexOf('\n')) >= 0) {
        const complete = line(pending.slice(0, end))
        pending = pending.slice(end + 1)
        if (complete) return
      }
      if (done) throw new Error('回复已中断，请刷新对话后重试')
    }
  } finally {
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}
