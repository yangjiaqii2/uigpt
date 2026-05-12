/**
 * 登录用户发消息时：将 composer 中的 blob 预览转为 data URL，供后端 API易「识图」预分析。
 * @param {{ url: string, name?: string }[]} attachments
 * @param {number} [maxCount]
 * @returns {Promise<{ url: string }[]>}
 */
export async function blobAttachmentsToImageParts(attachments, maxCount = 4) {
  if (!Array.isArray(attachments) || attachments.length === 0) return []
  const parts = []
  const slice = attachments.slice(0, Math.max(1, Math.min(4, maxCount)))
  for (const a of slice) {
    const u = a?.url
    if (!u || typeof u !== 'string') continue
    if (u.startsWith('data:image/')) {
      parts.push({ url: u })
      continue
    }
    if (u.startsWith('blob:')) {
      try {
        const res = await fetch(u)
        const blob = await res.blob()
        if (!blob.type.startsWith('image/')) continue
        const dataUrl = await new Promise((resolve, reject) => {
          const fr = new FileReader()
          fr.onload = () => resolve(fr.result)
          fr.onerror = reject
          fr.readAsDataURL(blob)
        })
        if (typeof dataUrl === 'string' && dataUrl.startsWith('data:image/')) {
          parts.push({ url: dataUrl })
        }
      } catch {
        /* skip broken blob */
      }
    }
  }
  return parts
}

/** 允许附件且走后端识图预分析（当前仅自由对话） */
export const VISION_PAYLOAD_SKILL_IDS = ['freeform']

/**
 * @param {Array<{ role: string, content?: string, images?: { url: string }[] }>} payload
 * @param {{ url: string }[]} imageParts
 */
export function attachVisionImagesToLastUser(payload, imageParts) {
  if (!imageParts?.length || !payload?.length) return payload
  const next = payload.map((m) => ({ ...m }))
  for (let i = next.length - 1; i >= 0; i--) {
    if (next[i].role === 'user') {
      next[i] = { ...next[i], images: imageParts }
      break
    }
  }
  return next
}
