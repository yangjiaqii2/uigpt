import http from './http'

const NB_TIMEOUT_MS = 300_000

/**
 * Nano Banana Pro 文生图（服务端代理 API 易 generateContent）。
 * @param {{ prompt: string, aspectRatio?: string, imageSize?: string }} payload
 */
export function nanoBananaTextToImage(payload, axiosConfig = {}) {
  return http.post('/image-studio/nano-banana/text-to-image', payload, {
    timeout: NB_TIMEOUT_MS,
    ...axiosConfig,
  })
}

/**
 * Nano Banana Pro 图片编辑（text + 多图 inlineData）。
 * @param {{ prompt: string, aspectRatio?: string, imageSize?: string, images: { mimeType?: string, dataBase64: string }[] }} payload
 */
export function nanoBananaEdit(payload, axiosConfig = {}) {
  return http.post('/image-studio/nano-banana/edit', payload, {
    timeout: NB_TIMEOUT_MS,
    ...axiosConfig,
  })
}

const PROMPT_OPT_TIMEOUT_MS = 120_000

/**
 * 提示词优化（服务端 LLM 扩写）。
 * @param {{ prompt: string, tool?: string, styleLabel?: string, aspectLabel?: string, qualityLabel?: string, medium?: 'image'|'video' }} payload
 */
export function optimizeImageStudioPrompt(payload, axiosConfig = {}) {
  return http.post('/image-studio/prompt/optimize', payload, {
    timeout: PROMPT_OPT_TIMEOUT_MS,
    ...axiosConfig,
  })
}
