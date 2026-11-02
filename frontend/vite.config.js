import { fileURLToPath } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

// 仅在开发服务器中使用后端地址，不把数据库配置注入浏览器。
export default defineConfig(({ mode }) => {
  const envDir = fileURLToPath(new URL('../', import.meta.url))
  const env = loadEnv(mode, envDir, '')
  const target = env.DEV_BACKEND_URL || 'http://127.0.0.1:8080'
  const isProd = mode === 'production'
  const proxy = {
    '/api': { target },
    '/ws': { target, ws: true },
    '/chat/api': { target, rewrite: (path) => path.replace(/^\/chat\/api/, '/api') },
    '/chat/ws': { target, ws: true, rewrite: (path) => path.replace(/^\/chat\/ws/, '/ws') },
  }

  return {
    plugins: [vue()],
    envDir,
    base: isProd ? '/chat/' : '/',
    server: { host: 'localhost', port: 5173, strictPort: true, proxy },
    preview: { host: 'localhost', port: 5173, strictPort: true, proxy },
  }
})