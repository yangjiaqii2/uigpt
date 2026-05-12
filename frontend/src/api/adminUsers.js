import http from './http'

/**
 * @param {{
 *   page?: number,
 *   size?: number,
 *   phone?: string,
 *   username?: string,
 *   createdFrom?: string,
 *   createdTo?: string,
 *   privilege?: number,
 * }} [params]
 */
export function adminListUsers(params) {
  return http.get('/admin/users', { params })
}

/** @param {number} id */
export function adminGetUser(id) {
  return http.get(`/admin/users/${id}`)
}

/** @param {Record<string, unknown>} body */
export function adminCreateUser(body) {
  return http.post('/admin/users', body)
}

/** @param {number} id @param {Record<string, unknown>} body */
export function adminUpdateUser(id, body) {
  return http.put(`/admin/users/${id}`, body)
}

/** @param {number} id */
export function adminDeleteUser(id) {
  return http.delete(`/admin/users/${id}`)
}
