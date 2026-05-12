import http from './http'

/** @param {{ page?: number, size?: number }} [params] */
export function fetchPromptTemplates(params) {
  return http.get('/prompts', { params })
}

/** @param {{ title: string, body: string }} body */
export function createPromptTemplate(body) {
  return http.post('/admin/prompts', body)
}

/** @param {number|string} id @param {{ title: string, body: string }} body */
export function updatePromptTemplate(id, body) {
  return http.put(`/admin/prompts/${id}`, body)
}

/** @param {number|string} id */
export function deletePromptTemplate(id) {
  return http.delete(`/admin/prompts/${id}`)
}
