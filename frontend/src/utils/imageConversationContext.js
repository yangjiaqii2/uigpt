/**
 * 从会话消息列表组装「出图用对话上下文」（不含当前助手气泡及其后）。
 * @param {Array<{ role?: string, content?: string }>} messages
 * @param {number} assistantAiIdx 当前要出图的助手消息下标
 * @param {{ maxChars?: number }} [opts]
 * @returns {string}
 */
export function buildImageConversationContext(messages, assistantAiIdx, opts = {}) {
  const maxChars = opts.maxChars ?? 12000
  if (!Array.isArray(messages) || assistantAiIdx == null || assistantAiIdx <= 0) {
    return ''
  }
  const lines = []
  for (let i = 0; i < assistantAiIdx; i++) {
    const m = messages[i]
    if (!m || !m.role) continue
    const r = String(m.role).toLowerCase()
    if (r !== 'user' && r !== 'assistant') continue
    const text = typeof m.content === 'string' ? m.content.trim() : ''
    if (!text) continue
    const label = r === 'user' ? '用户' : '助手'
    lines.push(`[${label}] ${text}`)
  }
  let joined = lines.join('\n')
  if (joined.length > maxChars) {
    joined = '…' + joined.slice(joined.length - maxChars)
  }
  return joined
}
