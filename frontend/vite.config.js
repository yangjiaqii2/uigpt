import { existsSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

const __dirname = dirname(fileURLToPath(import.meta.url))

/** 与 scripts/sync-version-from-env.mjs 一致：解析 KEY=VAL 行 */
function parseEnvFile(filePath) {
  if (!existsSync(filePath)) {
    return {}
  }
  const out = {}
  for (const line of readFileSync(filePath, 'utf8').split(/\r?\n/)) {
    const t = line.trim()
    if (!t || t.startsWith('#')) continue
    const eq = t.indexOf('=')
    if (eq <= 0) continue
    const key = t.slice(0, eq).trim()
    let val = t.slice(eq + 1).trim()
    if (
      (val.startsWith('"') && val.endsWith('"')) ||
      (val.startsWith("'") && val.endsWith("'"))
    ) {
      val = val.slice(1, -1)
    }
    out[key] = val
  }
  return out
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, __dirname, '')
  const pkg = JSON.parse(readFileSync(join(__dirname, 'package.json'), 'utf-8'))
  const backendEnv = parseEnvFile(join(__dirname, '..', 'backend', '.env'))
  // 个人中心版本：优先仓库 backend/.env（VITE_APP_VERSION 或 version），其次 frontend/.env，最后 package.json
  const appVersion =
    (backendEnv.VITE_APP_VERSION && String(backendEnv.VITE_APP_VERSION).trim()) ||
    (backendEnv.version && String(backendEnv.version).trim()) ||
    (env.VITE_APP_VERSION && String(env.VITE_APP_VERSION).trim()) ||
    (env.version && String(env.version).trim()) ||
    pkg.version

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
