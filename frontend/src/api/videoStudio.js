import http from './http'

const SUBMIT_MS = 60_000
/** multipart 上传参考图可能较慢，与文档建议一致放宽 */
const SUBMIT_MULTIPART_MS = 120_000
const POLL_MS = 45_000
const FINALIZE_MS = 600_000

/**
 * Sora 2 官转：提交文生视频任务（后端代理 POST /v1/videos）。
 * @param {{ prompt: string, model: string, seconds: string, size: string }} payload
 */
export function submitSora2Video(payload, axiosConfig = {}) {
  return http.post('/video-studio/sora2/submit', payload, {
    timeout: SUBMIT_MS,
    ...axiosConfig,
  })
}

/**
 * 图生视频：multipart，`input_reference` 为 PNG/JPEG/WEBP 文件（像素须与 `size` 一致；前端可先缩放）。
 * @param {FormData} formData — fields: prompt, model, seconds, size, input_reference
 */
export function submitSora2VideoMultipart(formData, axiosConfig = {}) {
  return http.post('/video-studio/sora2/submit-multipart', formData, {
    timeout: SUBMIT_MULTIPART_MS,
    ...axiosConfig,
  })
}

/** @param {string} videoId */
export function getSora2Task(videoId, axiosConfig = {}) {
  return http.get(`/video-studio/sora2/tasks/${encodeURIComponent(videoId)}`, {
    timeout: POLL_MS,
    ...axiosConfig,
  })
}

/**
 * 下载上游 MP4 并写入 COS，返回可播放 URL。
 * @param {string} videoId
 * @param {{ prompt?: string }} [body] — 与提交任务一致的提示词，写入视频工作台对话记录
 * @param {import('axios').AxiosRequestConfig} [axiosConfig]
 */
export function finalizeSora2Task(videoId, body = {}, axiosConfig = {}) {
  return http.post(
    `/video-studio/sora2/tasks/${encodeURIComponent(videoId)}/finalize`,
    body,
    {
      timeout: FINALIZE_MS,
      ...axiosConfig,
    },
  )
}
