/**
 * 将 axios 错误转为面向用户的中文说明，避免展示 “Request failed with status code 502” 等英文。
 */
/** 与后端 UserFacingMessages.NETWORK_TRY_LATER 一致 */
export const NETWORK_TRY_LATER_ZH = '网络异常，请稍后再试'

const STATUS_ZH = {
  400: '请求参数有误，请检查后重试',
  401: '登录已失效，请重新登录',
  402: '积分不足，请稍后再试或联系管理员充值',
  403: '没有权限执行此操作',
  404: '请求的资源不存在',
  408: '请求超时',
  409: '操作冲突，请刷新后重试',
  413: '提交的内容过大',
  422: '数据格式不符合要求',
  429: '注册过于频繁或请求过多，请稍后再试',
  500: NETWORK_TRY_LATER_ZH,
  502: NETWORK_TRY_LATER_ZH,
  503: NETWORK_TRY_LATER_ZH,
  504: NETWORK_TRY_LATER_ZH,
}

/** 后端 GlobalExceptionHandler 返回的 { message } */
function hasApiErrorMessage(data) {
  return (
    data != null &&
    typeof data === 'object' &&
    !Array.isArray(data) &&
    typeof data.message === 'string' &&
    data.message.trim().length > 0
  )
}

/** 开发环境后端未启动时亦统一口径，避免暴露端口与路径 */
const BACKEND_UNREACHABLE_ZH = NETWORK_TRY_LATER_ZH

function isAsciiOnly(s) {
  return /^[\x00-\x7F]*$/.test(s)
}

/**
 * @param {unknown} err axios 错误对象
 * @param {string} [fallback] 无更具体信息时的中文兜底
 */
export function getAxiosErrorMessage(err, fallback = NETWORK_TRY_LATER_ZH) {
  if (err == null) return fallback

  const res = err.response
  const status = res?.status
  const data = res?.data
  const code = err.code
  const rawMsg = typeof err.message === 'string' ? err.message : ''

  // 开发代理（如 Vite）在后端未启动时常返回 502，易被误判为「网关异常」
  if (code === 'ECONNREFUSED') {
    return BACKEND_UNREACHABLE_ZH
  }

  // 后端 ApiError { message }：任意状态码（含 503 COS/流水线不可用）一律优先展示具体说明，
  // 避免纯 ASCII 英文说明被下方 STATUS_ZH[503] 覆盖成「网络异常」。
  if (hasApiErrorMessage(data)) {
    return data.message.trim()
  }

  if (status === 502) {
    return BACKEND_UNREACHABLE_ZH
  }
  if (status === 503 || status === 504) {
    return NETWORK_TRY_LATER_ZH
  }

  if (typeof data === 'string' && data.length < 200 && !data.trim().startsWith('<')) {
    const t = data.trim()
    if (t && !isAsciiOnly(t)) return t
  }

  if (status && STATUS_ZH[status]) {
    return STATUS_ZH[status]
  }

  if (code === 'ECONNABORTED' || /timeout/i.test(rawMsg)) {
    return NETWORK_TRY_LATER_ZH
  }
  if (code === 'ERR_NETWORK' || rawMsg === 'Network Error') {
    return NETWORK_TRY_LATER_ZH
  }

  if (/Request failed with status code\s*\d+/i.test(rawMsg)) {
    if (status && STATUS_ZH[status]) return STATUS_ZH[status]
    return fallback
  }

  if (rawMsg && !isAsciiOnly(rawMsg)) {
    return rawMsg
  }

  return fallback
}
