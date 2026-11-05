import assert from 'node:assert/strict'
import { createHash, webcrypto } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { test } from 'node:test'
import vm from 'node:vm'

/** 加载真实指纹方法，模拟 HTTP 缺少 SubtleCrypto 的浏览器。 */
async function fingerprintDevice(crypto, storage = new Map()) {
  const context = vm.createContext({ crypto, TextEncoder, Uint8Array, Uint32Array,
    localStorage: { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value) },
    navigator: { userAgent: 'test-browser', language: 'zh-CN' },
    screen: { width: 1280, height: 720, colorDepth: 24 },
    document: { createElement: () => ({ getContext: () => null, toDataURL: () => '你好🌿' }) },
    Intl: { DateTimeFormat: () => ({ resolvedOptions: () => ({ timeZone: 'Asia/Shanghai' }) }) },
  })
  const module = new vm.SourceTextModule(await readFile(new URL('./auth.js', import.meta.url), 'utf8'), { context })
  await module.link(() => new vm.SyntheticModule(['reactive'], function () { this.setExport('reactive', value => value) }, { context }))
  await module.evaluate()
  return module.namespace.machineFingerprint
}

/** 两种环境必须生成相同 SHA-256，且升级到 HTTPS 后继续复用已存指纹配额。 */
test('HTTP fingerprint matches SHA-256 and reuses the saved browser quota identity', async () => {
  const expected = createHash('sha256').update('test-browser|zh-CN|1280|720|24|Asia/Shanghai|你好🌿').digest('hex')
  for (const crypto of [undefined, {}, webcrypto]) {
    const storage = new Map()
    const fingerprint = await fingerprintDevice(crypto, storage)
    assert.equal(await fingerprint(), expected)
    assert.equal(storage.get('mairy.machineFingerprint'), expected)
    storage.set('mairy.machineFingerprint', 'a'.repeat(64))
    assert.equal(await fingerprint(), 'a'.repeat(64))
    assert.equal(await (await fingerprintDevice(webcrypto, storage))(), 'a'.repeat(64))
  }
})
