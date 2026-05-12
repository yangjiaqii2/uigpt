import http from './http'

/**
 * @param {string[]} texts
 */
export function adminRagUpsert(texts) {
  return http.post('/admin/rag/upsert', { texts })
}

/**
 * @param {{ page?: number, size?: number }} [params]
 */
export function adminRagListDocuments(params = {}) {
  return http.get('/admin/rag/documents', { params })
}

/**
 * @param {string} pointId
 */
export function adminRagGetDocument(pointId) {
  return http.get(`/admin/rag/documents/${encodeURIComponent(pointId)}`)
}

/**
 * @param {{ title?: string, text: string }} body
 */
export function adminRagCreateDocument(body) {
  return http.post('/admin/rag/documents', body)
}

/**
 * @param {string} pointId
 */
export function adminRagDeleteDocument(pointId) {
  return http.delete(`/admin/rag/documents/${encodeURIComponent(pointId)}`)
}

/**
 * @param {File[]} files
 */
export function adminRagImportDocuments(files) {
  const fd = new FormData()
  for (const f of files) {
    fd.append('files', f)
  }
  return http.post('/admin/rag/documents/import', fd, {
    timeout: 300_000,
  })
}
