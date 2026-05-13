import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

const __dirname = dirname(fileURLToPath(import.meta.url))

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, __dirname, '')
  const pkg = JSON.parse(readFileSync(join(__dirname, 'package.json'), 'utf-8'))
  const appVersion =
    (env.VITE_APP_VERSION && String(env.VITE_APP_VERSION).trim()) || pkg.version

  return {
    plugins: [vue()],
    define: {
      __APP_VERSION__: JSON.stringify(appVersion),
    },
    server: {
      port: 5173,
      proxy: {
        '/api': {
          target: 'http://localhost:8088',
          changeOrigin: true,
          configure(proxy) {
            proxy.on('proxyRes', (proxyRes, req) => {
              if (req.url?.includes('/chat/stream')) {
                proxyRes.headers['x-accel-buffering'] = 'no'
              }
            })
          },
        },
      },
    },
  }
})
