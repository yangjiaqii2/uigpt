/**
 * 前端展示用积分门槛，须与后端计费一致。
 *
 * 对话单轮：{@code application.yml} {@code uigpt.points.chat-turn-cost} /
 * {@code uigpt.points.chat-turn-deep-cost}（默认 5 / 10），
 * {@code ChatController#chatTurnCost} 按 {@code ChatRequest#deepReasoning} 切换。
 *
 * 工作台文生图档位：{@code ImageGenerationPointCosts.forNanoBananaImageSize}（1K/2K/4K → 18 / 32 / 52）。
 */

export const CHAT_TURN_POINTS_COST = 5
export const CHAT_TURN_DEEP_POINTS_COST = 10

/** 积分不足时禁用主操作按钮的 title/tooltip */
export const INSUFFICIENT_POINTS_TOOLTIP_ZH = '积分不足，无法发送'

/**
 * @param {boolean} deepReasoning 与 ChatView 发往 API 的 deepReasoning 一致
 * @returns {number}
 */
export function chatTurnPointsCost(deepReasoning) {
  return deepReasoning ? CHAT_TURN_DEEP_POINTS_COST : CHAT_TURN_POINTS_COST
}

/**
 * 与 {@code ImageGenerationPointCosts.forNanoBananaImageSize} 一致（大小写不敏感，空/未知按 2K）。
 * @param {string | null | undefined} imageSize API 的 1K / 2K / 4K
 * @returns {number}
 */
export function nanoBananaPointsForImageSize(imageSize) {
  const s = imageSize == null ? '' : String(imageSize).trim().toUpperCase()
  if (s === '1K') return 18
  if (s === '4K') return 52
  return 32
}

/**
 * ImageGenView 画质 pill：std / hd / uhd → 与 {@code qualityToImageSize} 相同档位后再计费。
 * @param {string} studioQuality
 * @returns {number}
 */
export function nanoBananaPointsForStudioQuality(studioQuality) {
  if (studioQuality === 'uhd') return nanoBananaPointsForImageSize('4K')
  if (studioQuality === 'hd') return nanoBananaPointsForImageSize('2K')
  return nanoBananaPointsForImageSize('1K')
}
