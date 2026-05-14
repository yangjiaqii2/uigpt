import http from './http'

/**
 * @param {{ page?: number, size?: number }} params
 */
export function fetchImageStudioSessions(params = {}) {
  return http.get('/image-studio/sessions', { params })
}

export function createImageStudioSession() {
  return http.post('/image-studio/sessions')
}

/**
 * @param {number|string} id
 */
export function fetchImageStudioSessionDetail(id) {
  return http.get(`/image-studio/sessions/${id}`)
}

/**
 * @param {number|string} id
 * @param {{ contextText?: string, title?: string, studioSkillId?: string }} body
 */
export function patchImageStudioSession(id, body) {
  return http.patch(`/image-studio/sessions/${id}`, body)
}

/**
 * @param {number|string} id
 */
export function deleteImageStudioSession(id) {
  return http.delete(`/image-studio/sessions/${id}`)
}

/**
 * 同域读取会话内图片为 Base64，供编辑接口使用（避免跨域 fetch COS）。
 * @param {number|string} sessionId
 * @param {number|string} imageId
 */
export function fetchImageStudioSessionImageInline(sessionId, imageId) {
  return http.get(`/image-studio/sessions/${sessionId}/images/${imageId}/inline-data`)
}
