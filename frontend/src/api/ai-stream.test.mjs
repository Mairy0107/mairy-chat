import assert from 'node:assert/strict'
import { test } from 'node:test'
import { consumeAiStream } from './ai-stream.js'

/** 按任意字节切分真实 UTF-8 流，覆盖中文拆包。 */
function stream(text, step = 1) {
  const bytes = new TextEncoder().encode(text)
  return new ReadableStream({ start(controller) {
    for (let i = 0; i < bytes.length; i += step) controller.enqueue(bytes.slice(i, i + step))
    controller.close()
  } })
}

test('中文拆包、CRLF、替换拒绝及最终确认按顺序分发', async () => {
  const frames = ': keepalive\r\n\r\nevent: delta\r\ndata: {"content":"你好🌿"}\r\n\r\n'
    + 'event: replace\ndata: {"content":"安全拒绝"}\n\nevent: done\ndata: {"message":{"content":"安全拒绝"},"quotaRemaining":39}\n\n'
  const events = []
  await consumeAiStream(stream(frames), (type, data) => events.push([type, data]))
  assert.deepEqual(events.map(([type]) => type), ['delta', 'replace', 'done'])
  assert.equal(events[0][1].content, '你好🌿')
  assert.equal(events[2][1].quotaRemaining, 39)
})

test('未完成、错误帧和非法 JSON 都不能被误判为成功', async () => {
  await assert.rejects(consumeAiStream(stream('event: delta\ndata: {"content":"半截"}\n\n'), () => {}), /中断/)
  await assert.rejects(consumeAiStream(stream('event: error\ndata: {"message":"确认失败"}\n\n'), () => {}), /确认失败/)
  await assert.rejects(consumeAiStream(stream('event: done\ndata: invalid\n\n'), () => {}))
})

test('解析失败时取消上游读取，避免连接继续占用', async () => {
  let cancelled = false
  const response = new ReadableStream({
    start(controller) { controller.enqueue(new TextEncoder().encode('event: error\ndata: {}\n\n')) },
    cancel() { cancelled = true },
  })
  await assert.rejects(consumeAiStream(response, () => {}))
  assert.equal(cancelled, true)
})
