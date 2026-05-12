/**
 * 会话标题：取用户首条消息的首行，按 Unicode 码位截断（与后端 ConversationService 自动标题一致）。
 * @param {string | null | undefined} raw
 * @param {number} [maxCodePoints=10]
 * @returns {string}
 */
export function sessionTitleFromUserContent(raw, maxCodePoints = 10) {
  if (raw == null || typeof raw !== 'string') return ''
  const n = raw.indexOf('\n')
  const r = raw.indexOf('\r')
  let end = raw.length
  if (n >= 0) end = Math.min(end, n)
  if (r >= 0) end = Math.min(end, r)
  const line = raw.slice(0, end).trim()
  if (!line) return ''
  const chars = [...line]
  return chars.slice(0, maxCodePoints).join('')
}

/**
 * 首行全文（用于 title 提示），不截断。
 * @param {string | null | undefined} raw
 * @returns {string}
 */
export function firstUserLinePlain(raw) {
  if (raw == null || typeof raw !== 'string') return ''
  const n = raw.indexOf('\n')
  const r = raw.indexOf('\r')
  let end = raw.length
  if (n >= 0) end = Math.min(end, n)
  if (r >= 0) end = Math.min(end, r)
  return raw.slice(0, end).trim()
}
