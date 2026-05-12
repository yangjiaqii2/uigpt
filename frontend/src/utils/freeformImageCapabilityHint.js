/**
 * 自由对话下：用户是否在要「真实出图」，或助手是否在假装调用生图工具 / 承诺正在出图。
 * 用于展示「本模式不自动生图」提示，避免误以为会出图。
 */

/** @param {string} s */
function norm(s) {
  return (s == null ? '' : String(s)).trim()
}

/**
 * @param {string} assistant
 * @returns {boolean} 助手已说明本模式不出图等，则不再叠加大横幅
 */
function assistantAlreadyClarifiedNoImage(assistant) {
  const a = norm(assistant)
  if (a.length < 8) return false
  const noSupport =
    /不支持|无法生成|不能生成|不会生成|不提供|没有.{0,6}功能|无法自动|不会自动|纯文本|仅文字|仅支持文字/.test(a)
  const imageCtx = /图|出图|配图|生图|文生图|效果图|作图|画一张|生成图片/.test(a)
  return noSupport && imageCtx
}

/**
 * @param {{ userText?: string, assistantText?: string }} param
 * @returns {boolean}
 */
export function shouldShowFreeformNoImageHint({ userText = '', assistantText = '' } = {}) {
  const user = norm(userText)
  const asst = norm(assistantText)
  if (!user && !asst) return false
  if (assistantAlreadyClarifiedNoImage(asst)) return false

  const userRes = [
    /生成.{0,12}(图|图片|效果图|配图|示意图|渲染)/,
    /画.{0,8}(一张|一幅|个)?(图|画)/,
    /\b出图\b|文生图|作图|配图\b|效果图\b/,
    /(帮我|请|能否).{0,10}(画|生成|做|出).{0,10}(张|幅)?(图|效果图)/,
    /cad.{0,24}(效果|渲染|可视化)/i,
    /\b(dall[- ]?e|midjourney|mj)\b/i,
    /\btext2im\b/i,
    /图生图|ai绘图|ai画图/,
  ]
  for (const re of userRes) {
    if (user && re.test(user)) return true
  }

  const asstRes = [
    /正在[^。]{0,24}(生成|绘制|渲染).{0,12}(图|图片|效果图)/,
    /"action"\s*:\s*"[^"]*(?:dalle|text2im|image)/i,
    /\bdalle\.text2im\b/i,
    /已为您生成.{0,10}(图|图片)/,
    /请稍等[^。]{0,20}(生成|出图)/,
  ]
  for (const re of asstRes) {
    if (asst && re.test(asst)) return true
  }
  return false
}
