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
 * @param {string[]} pointIds
 */
export function adminRagBatchDeleteDocuments(pointIds) {
  return http.post('/admin/rag/documents/batch-delete', { pointIds })
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms))
}

const IMPORT_POLL_MS = 1500
const IMPORT_MAX_WAIT_MS = 900_000

/**
 * 提交 multipart 后服务端返回 202 + taskId，在此轮询直至 SUCCEEDED / FAILED / 超时。
 * @param {FormData} formData 已含 files 字段（可为 Blob + 文件名）
 * @returns {Promise<{ data: { imported: number } }>}
 */
export async function adminRagImportFormData(formData) {
  const submit = await http.post('/admin/rag/documents/import', formData, {
    timeout: 300_000,
  })
  if (submit.status === 202) {
    const taskId = submit.data?.taskId
    if (!taskId || typeof taskId !== 'string') {
      throw new Error('服务器未返回任务编号')
    }
    const deadline = Date.now() + IMPORT_MAX_WAIT_MS
    while (Date.now() < deadline) {
      await sleep(IMPORT_POLL_MS)
      const { data } = await http.get(
        `/admin/rag/documents/import-tasks/${encodeURIComponent(taskId)}`,
        { timeout: 60_000 },
      )
      if (data.status === 'SUCCEEDED') {
        return { data: { imported: data.imported ?? 0 } }
      }
      if (data.status === 'FAILED') {
        throw new Error(data.error || '导入失败')
      }
    }
    throw new Error('导入等待超时，请稍后在知识库列表中确认是否已写入')
  }
  if (submit.status === 200 && submit.data != null && typeof submit.data.imported === 'number') {
    return submit
  }
  throw new Error('意外的导入响应')
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
