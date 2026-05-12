import http from './http'

export function fetchMeStats() {
  return http.get('/me/stats')
}

/** @param {{ limit?: number }} [params] */
export function fetchMeRecentImages(params) {
  return http.get('/me/recent-images', { params })
}

/** @param {{ page?: number, size?: number, skill?: string }} [params] — skill 如 studio 仅工作台作品 */
export function fetchMeImagesPage(params) {
  return http.get('/me/images', { params })
}

/** 取消收藏 / 收藏（含原会话已删除的图片） */
export function patchMyImageFavorite(imageId, favorite) {
  return http.patch(`/me/images/${imageId}/favorite`, { favorite })
}

/** 删除当前用户名下的一张生成图 */
export function deleteMyImage(imageId) {
  return http.delete(`/me/images/${imageId}`)
}
