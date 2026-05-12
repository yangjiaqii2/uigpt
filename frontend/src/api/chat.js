import http from './http'
import { NETWORK_TRY_LATER_ZH } from '../utils/httpError'

/**
 * @param {number | null | undefined} [conversationId] 登录用户续写会话
 * @param {{ skillContext?: string, skillId?: string, deepReasoning?: boolean, useRag?: boolean, ragCollection?: string }} [extras] 可选技能上下文、技能 id、深度推理、知识库等
 */
export function sendChat(messages, conversationId, extras = {}) {
  const body = { messages }
  if (conversationId != null) body.conversationId = conversationId
  const sc = extras.skillContext
  if (sc != null && String(sc).trim() !== '') body.skillContext = String(sc).trim()
  const sid = extras.skillId
  if (sid != null && String(sid).trim() !== '') body.skillId = String(sid).trim()
  const tm = extras.tierMode
  if (tm != null && String(tm).trim() !== '') body.tierMode = String(tm).trim()
  const ffm = extras.fastFreeformModel
  if (ffm != null && String(ffm).trim() !== '') body.fastFreeformModel = String(ffm).trim()
  if (extras.deepReasoning === true) body.deepReasoning = true
  if (extras.useRag === true) body.useRag = true
  const rc = extras.ragCollection
  if (rc != null && String(rc).trim() !== '') body.ragCollection = String(rc).trim()
  return http.post('/chat', body)
}

/**
 * 流式对话（SSE）。事件 JSON：{ delta } | { done, conversationId } | { error }
 * @returns {Promise<{ conversationId: number | null }>} `conversationId` 来自流末尾 done 事件（登录用户新建会话时便于立刻文生图）
 * @param {Array<{role:string,content:string}>} messages
 * @param {number | null | undefined} conversationId
 * @param {{ onDelta: (t: string) => void, onDone?: (conversationId: number | null) => void | Promise<void> }} handlers
 * @param {{ skillContext?: string, skillId?: string, signal?: AbortSignal, deepReasoning?: boolean, useRag?: boolean, ragCollection?: string }} [extras] 可选：skillContext、skillId、deepReasoning、useRag（透传模式下 true 时启用知识库）、ragCollection
 */
export async function streamChat(messages, conversationId, handlers, extras = {}) {
  const { onDelta, onDone } = handlers
  const token = localStorage.getItem('uigpt_token')
  const body = { messages }
  if (conversationId != null) body.conversationId = conversationId
  const sc = extras.skillContext
  if (sc != null && String(sc).trim() !== '') body.skillContext = String(sc).trim()
  const sid = extras.skillId
  if (sid != null && String(sid).trim() !== '') body.skillId = String(sid).trim()
  const tm = extras.tierMode
  if (tm != null && String(tm).trim() !== '') body.tierMode = String(tm).trim()
  const ffm = extras.fastFreeformModel
  if (ffm != null && String(ffm).trim() !== '') body.fastFreeformModel = String(ffm).trim()
  if (extras.deepReasoning === true) body.deepReasoning = true
  if (extras.useRag === true) body.useRag = true
  const rc = extras.ragCollection
  if (rc != null && String(rc).trim() !== '') body.ragCollection = String(rc).trim()

  const prefix = (import.meta.env.BASE_URL || '/').replace(/\/$/, '')
  const url = `${prefix}/api/chat/stream`
  const res = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      // 兼容服务端在校验失败等场景返回 JSON；仍以 SSE 为主
      Accept: 'application/json, text/event-stream;q=0.95',
    },
    body: JSON.stringify(body),
    signal: extras.signal,
  })

  if (res.status === 401) {
    const { useAuthStore } = await import('../stores/auth')
    useAuthStore().logout()
    const { default: router } = await import('../router')
    if (router.currentRoute.value.path !== '/login') {
      router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
    }
    throw new Error('登录已失效，请重新登录')
  }

  if (res.status === 402) {
    let msg = '积分不足'
    try {
      const t = (await res.text()).trim()
      if (t.startsWith('{')) {
        const j = JSON.parse(t)
        const m = j?.message
        if (typeof m === 'string' && m.trim()) msg = m.trim()
      }
    } catch {
      /* ignore */
    }
    throw new Error(msg)
  }

  if (!res.ok || !res.body) {
    let detail = ''
    try {
      detail = (await res.text()).trim()
    } catch {
      /* ignore */
    }
    if (detail.startsWith('{')) {
      try {
        const j = JSON.parse(detail)
        const m = j?.message
        if (typeof m === 'string' && m.trim()) {
          throw new Error(m.trim())
        }
      } catch (e) {
        if (e instanceof SyntaxError) {
          /* ignore */
        } else {
          throw e
        }
      }
    }
    throw new Error(NETWORK_TRY_LATER_ZH)
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  /** @type {number | null} */
  let resolvedConversationId = null

  /**
   * 与 AbortSignal 联动：`fetch` 中止后 `read()` 仍可能挂起，需在 abort 时 `cancel` reader 并立刻结束循环。
   * @param {ReadableStreamDefaultReader<Uint8Array>} r
   * @param {AbortSignal | undefined} signal
   */
  function readWhenActive(r, signal) {
    const abortErr = new DOMException('Aborted', 'AbortError')
    return new Promise((resolve, reject) => {
      let settled = false
      const cleanup = () => {
        if (signal) signal.removeEventListener('abort', onAbort)
      }
      const onAbort = () => {
        if (settled) return
        settled = true
        cleanup()
        r.cancel().catch(() => {})
        reject(abortErr)
      }
      if (signal) {
        if (signal.aborted) {
          onAbort()
          return
        }
        signal.addEventListener('abort', onAbort, { once: true })
      }
      r.read().then(
        (chunk) => {
          if (settled) return
          settled = true
          cleanup()
          resolve(chunk)
        },
        (e) => {
          if (settled) return
          settled = true
          cleanup()
          if (signal?.aborted) reject(abortErr)
          else reject(e)
        },
      )
    })
  }

  while (true) {
    const { done, value } = await readWhenActive(reader, extras.signal)
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const blocks = buffer.split('\n\n')
    buffer = blocks.pop() ?? ''
    for (const block of blocks) {
      const lines = block.trim().split('\n')
      for (const raw of lines) {
        const line = raw.trim()
        if (!line.startsWith('data:')) continue
        const payload = line.slice(5).trim()
        if (!payload || payload === '[DONE]') continue
        let data
        try {
          data = JSON.parse(payload)
        } catch {
          continue
        }
        if (typeof data.delta === 'string' && data.delta.length > 0) {
          onDelta(data.delta)
        }
        if (data.done === true) {
          const cid = data.conversationId
          resolvedConversationId = cid != null && cid !== '' ? Number(cid) : null
          await Promise.resolve(onDone?.(resolvedConversationId))
        }
        if (typeof data.error === 'string' && data.error.length > 0) {
          throw new Error(data.error)
        }
      }
    }
  }

  return { conversationId: resolvedConversationId }
}
