import http from './http'

const NB_TIMEOUT_MS = 100_000

/**
 * Nano Banana Pro 文生图（服务端代理 API 易 generateContent）。
 * @param {{ prompt: string, aspectRatio?: string, imageSize?: string }} payload
 */
export function nanoBananaTextToImage(payload, axiosConfig = {}) {
  return http.post('/image-studio/nano-banana/text-to-image', { useRag: true, ...payload }, {
    timeout: NB_TIMEOUT_MS,
    ...axiosConfig,
  })
}

const NB_PAIR_TIMEOUT_MS = Math.max(NB_TIMEOUT_MS, 180_000)

/**
 * Nano Banana 文生图多路并行（默认 2 路，可选 candidateCount）。
 * 响应含 first、second、可选 extraSlots、recommendedSlot（服务端 Judge 开启时）。
 */
export function nanoBananaTextToImagePair(payload, axiosConfig = {}) {
  return http.post('/image-studio/nano-banana/text-to-image-pair', { useRag: true, ...payload }, {
    timeout: NB_PAIR_TIMEOUT_MS,
    ...axiosConfig,
  })
}

/**
 * Nano Banana Pro 图片编辑（text + 多图 inlineData）。
 * @param {{ prompt: string, aspectRatio?: string, imageSize?: string, images: { mimeType?: string, dataBase64: string }[] }} payload
 */
export function nanoBananaEdit(payload, axiosConfig = {}) {
  return http.post('/image-studio/nano-banana/edit', { useRag: true, ...payload }, {
    timeout: NB_TIMEOUT_MS,
    ...axiosConfig,
  })
}

/** Nano Banana 图片编辑多路并行。 */
export function nanoBananaEditPair(payload, axiosConfig = {}) {
  return http.post('/image-studio/nano-banana/edit-pair', { useRag: true, ...payload }, {
    timeout: NB_PAIR_TIMEOUT_MS,
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
