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
 * @param {FormData} formData 已含 files 字段（可为 Blob + 文件名）
 */
export function adminRagImportFormData(formData) {
  return http.post('/admin/rag/documents/import', formData, {
    timeout: 900_000,
  })
}

/**
 * @param {File[]} files
 */
export function adminRagImportDocuments(files) {
  const fd = new FormData()
  for (const f of files) {
    fd.append('files', f)
  }
  return adminRagImportFormData(fd)
}
