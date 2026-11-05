// 与房间录音使用相同格式和限额；DM 只做明文编码，不依赖 Web Crypto。
export const maxVoiceBytes = 512 * 1024
export const maxVoiceDuration = 60000
export const voiceMimeTypes = ['audio/webm;codecs=opus', 'audio/webm', 'audio/ogg;codecs=opus', 'audio/ogg', 'audio/mp4']

/** 分块编码避免大音频展开参数超过调用栈上限。 */
function toBase64(bytes) {
  let binary = ''
  for (let offset = 0; offset < bytes.length; offset += 8192) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + 8192))
  }
  return btoa(binary)
}

/** 校验明文语音的类型、格式、整数时长和规范 Base64，不加载外部 URL。 */
export function readDmVoice(message) {
  if (message.kind !== 'voice' || message.enc !== 0 || message.iv != null
      || !voiceMimeTypes.includes(message.mime) || !Number.isInteger(message.duration)
      || message.duration < 1 || message.duration > maxVoiceDuration
      || typeof message.content !== 'string' || message.content.length > 699052) throw new Error('语音格式无效')
  let bytes
  try { bytes = Uint8Array.from(atob(message.content), char => char.charCodeAt(0)) }
  catch { throw new Error('语音数据无效') }
  if (!bytes.length || bytes.length > maxVoiceBytes || toBase64(bytes) !== message.content) throw new Error('语音数据无效')
  return new Blob([bytes], { type: message.mime })
}

/** 将本地录音转为明文请求；服务端独立确定 enc=0 和发送人。 */
export async function encodeDmVoice(blob, duration) {
  if (!blob.size || blob.size > maxVoiceBytes) throw new Error('语音不能超过512 KiB')
  const payload = { kind: 'voice', mime: blob.type, duration, content: toBase64(new Uint8Array(await blob.arrayBuffer())) }
  readDmVoice({ ...payload, enc: 0, iv: null })
  return payload
}
