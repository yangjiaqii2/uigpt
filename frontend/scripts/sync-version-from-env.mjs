/**
 * 从 .env 写入 package.json 的 version，避免手改版本号。
 * 读取顺序（后者覆盖前者）：frontend/.env → frontend/.env.local → 仓库根 .env → backend/.env（版本号建议只维护在 backend/.env）
 * 以及进程环境变量 VITE_APP_VERSION。
 * 键名优先：VITE_APP_VERSION，其次 version=、FRONTEND_VERSION、APP_VERSION。
 */
import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = dirname(fileURLToPath(import.meta.url))
const frontendRoot = join(__dirname, '..')
const pkgPath = join(frontendRoot, 'package.json')

function parseEnvFile(filePath) {
  if (!existsSync(filePath)) {
    return {}
  }
  const out = {}
  for (const line of readFileSync(filePath, 'utf8').split(/\r?\n/)) {
    const t = line.trim()
    if (!t || t.startsWith('#')) {
      continue
    }
    const eq = t.indexOf('=')
    if (eq <= 0) {
      continue
    }
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

const envPaths = [
  join(frontendRoot, '.env'),
  join(frontendRoot, '.env.local'),
  join(frontendRoot, '..', '.env'),
  join(frontendRoot, '..', 'backend', '.env'),
]

let merged = {}
for (const p of envPaths) {
  merged = { ...merged, ...parseEnvFile(p) }
}

const rawVersion =
  (process.env.VITE_APP_VERSION && String(process.env.VITE_APP_VERSION).trim()) ||
  (merged.VITE_APP_VERSION && String(merged.VITE_APP_VERSION).trim()) ||
  (merged.version && String(merged.version).trim()) ||
  (merged.FRONTEND_VERSION && String(merged.FRONTEND_VERSION).trim()) ||
  (merged.APP_VERSION && String(merged.APP_VERSION).trim()) ||
  ''
// package.json 的 version 字段使用无 v 前缀的 semver；展示 v 前缀由 ChatProfileDrawer 负责
const version = rawVersion.replace(/^v+/i, '') || rawVersion

if (!version) {
  console.warn(
    '[sync-version-from-env] 未配置 VITE_APP_VERSION / version / FRONTEND_VERSION / APP_VERSION，跳过写入 package.json',
  )
  process.exit(0)
}

if (!/^[\d.a-zA-Z+-]+$/.test(version)) {
  console.warn(`[sync-version-from-env] 版本号含异常字符：${version}，仍写入 package.json`)
}

const pkg = JSON.parse(readFileSync(pkgPath, 'utf8'))
if (pkg.version === version) {
  process.exit(0)
}
pkg.version = version
writeFileSync(pkgPath, `${JSON.stringify(pkg, null, 2)}\n`, 'utf8')
console.log(`[sync-version-from-env] package.json version → ${version}`)
