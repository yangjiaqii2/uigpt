import http from './http'

/** @param {number} conversationId */
/** @param {{ offset?: number, limit?: number }} [params] */
export function fetchConversationImages(conversationId, params) {
  return http.get(`/conversations/${conversationId}/images`, { params })
}

/** @param {number} conversationId @param {FormData} formData */
export function uploadConversationImage(conversationId, formData) {
  return http.post(`/conversations/${conversationId}/images`, formData)
}

/**
 * 文生图固定走 APIYi；响应含 `b64_json` 与 `b64_mime_type`（OpenAI 风格原始 Base64）；列表/上传接口不含。
 * @param {number} conversationId
 * @param {{ messageSortOrder: number, skillId: string, userMessage: string, assistantReply?: string, imageConversationContext?: string, aspectKey?: string, styleLabel?: string, tierMode?: 'free'|'fast', qualityTier?: 'standard'|'hd'|'ultra' }} body
 */
export function generateErnieConversationImage(conversationId, body) {
  return http.post(`/conversations/${conversationId}/images/ernie-generate`, body, {
    timeout: 100_000,
  })
}

/**
 * 会话内局部重绘：multipart 字段见后端 {@code POST /conversations/{id}/images/ernie-inpaint}。
 * @param {number} conversationId
 * @param {FormData} formData
 */
export function generateErnieConversationImageInpaint(conversationId, formData) {
  return http.post(`/conversations/${conversationId}/images/ernie-inpaint`, formData, {
    timeout: 100_000,
  })
}

/** @param {number} conversationId @param {number} imageId */
export function deleteConversationImage(conversationId, imageId) {
  return http.delete(`/conversations/${conversationId}/images/${imageId}`)
}

/** @param {number} conversationId @param {number} imageId @param {boolean} favorite */
export function patchConversationImageFavorite(conversationId, imageId, favorite) {
  return http.patch(`/conversations/${conversationId}/images/${imageId}/favorite`, { favorite })
}

