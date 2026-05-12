/**
 * 对话消息归一化：后端仅存 role + content，前端可扩展 attachments / genCard 等。
 * 接入真实生图 API 后，在拉取历史时把服务端字段映射到 genCard。
 */

/** @param {{ role: string, content: string }} raw */
export function normalizeMessage(raw) {
  if (!raw || typeof raw.role !== 'string') {
    return { role: 'user', content: '' }
  }
  return {
    role: raw.role,
    content: raw.content ?? '',
    attachments: Array.isArray(raw.attachments) ? raw.attachments : undefined,
    skillLabel: raw.skillLabel,
    skillId: typeof raw.skillId === 'string' ? raw.skillId : undefined,
    genCard: raw.genCard ? { ...raw.genCard } : undefined,
    sortOrder: raw.sortOrder != null ? Number(raw.sortOrder) : undefined,
  }
}

/** 发往 /api/chat/stream：role + content；可选 images（识图预分析，不落库） */
export function messagesToApiPayload(list) {
  return list.map((m) => {
    const o = {
      role: m.role,
      content: typeof m.content === 'string' ? m.content : '',
    }
    if (Array.isArray(m.images) && m.images.length) {
      o.images = m.images
        .map((x) => ({ url: typeof x?.url === 'string' ? x.url : '' }))
        .filter((x) => x.url.startsWith('data:image/') || x.url.startsWith('https://'))
    }
    return o
  })
}
