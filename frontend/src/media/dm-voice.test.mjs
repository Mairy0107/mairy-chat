import assert from 'node:assert/strict'
import { test } from 'node:test'
import { encodeDmVoice, readDmVoice, maxVoiceBytes } from './dm-voice.js'

/** 最大音频无 Crypto 往返，Base64 与原字节一致，元数据不加密。 */
test('plaintext DM voice round trip at the size and duration limits', async () => {
  const bytes = new Uint8Array(maxVoiceBytes).fill(42)
  const payload = await encodeDmVoice(new Blob([bytes], { type: 'audio/webm' }), 60000)
  assert.equal(payload.content, Buffer.from(bytes).toString('base64'))
  const decoded = readDmVoice({ ...payload, enc: 0, iv: null })
  assert.deepEqual(new Uint8Array(await decoded.arrayBuffer()), bytes)
  assert.equal(decoded.type, 'audio/webm')
  assert.equal(payload.enc, undefined)
  assert.equal(payload.iv, undefined)
})

/** 拒绝加密标记、外部地址、歧义 Base64、危险格式及超限时长和字节。 */
test('DM voice validates payload and never trusts a supplied URL', async () => {
  const valid = { kind: 'voice', enc: 0, iv: null, content: 'Kg==', mime: 'audio/webm', duration: 1 }
  for (const changes of [{ enc: 1 }, { iv: 'room-iv' }, { kind: 'text' }, { mime: 'text/html' },
    { duration: 0 }, { duration: 60001 }, { duration: 1.5 }, { content: '' }, { content: 'Kh==' },
    { content: 'Kg' }, { content: 'Kg==\n' }, { content: 'https://invalid.example/audio' },
    { content: Buffer.alloc(maxVoiceBytes + 1).toString('base64') }]) {
    assert.throws(() => readDmVoice({ ...valid, ...changes }))
  }
  assert.equal(readDmVoice({ ...valid, voiceUrl: 'https://invalid.example/audio' }).size, 1)
  for (const blob of [new Blob([]), new Blob(['bad'], { type: 'text/html' }),
    new Blob([new Uint8Array(maxVoiceBytes + 1)], { type: 'audio/webm' })]) {
    await assert.rejects(encodeDmVoice(blob, 1))
  }
})
