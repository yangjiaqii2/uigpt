import http from './http'

export function login(username, password) {
  return http.post('/login', { username, password })
}

/** @param {{ realName: string, phone: string, registeredDate: string, newPassword: string, confirmNewPassword: string }} body */
export function forgotPasswordReset(body) {
  return http.post('/forgot-password/reset', body)
}

export function fetchRegisterOptions() {
  return http.get('/register/options')
}

export function fetchRegisterCaptcha() {
  return http.get('/register/captcha')
}

/**
 * @param {{
 *   realName: string,
 *   phone: string,
 *   username: string,
 *   password: string,
 *   confirmPassword: string,
 *   captchaId: string,
 *   captchaCode: string,
 *   recaptchaToken?: string,
 * }} body
 */
export function register(body) {
  const payload = {
    realName: body.realName,
    phone: body.phone,
    username: body.username,
    password: body.password,
    confirmPassword: body.confirmPassword,
    captchaId: body.captchaId,
    captchaCode: body.captchaCode,
  }
  if (body.recaptchaToken) payload.recaptchaToken = body.recaptchaToken
  return http.post('/register', payload)
}

export function fetchMe() {
  return http.get('/me')
}

/** 服务端将当前 Token 写入 Redis 黑名单；登出后应再清空本地存储。 */
export function logout() {
  return http.post('/logout')
}

/** @param {{ oldPassword: string, newPassword: string }} body */
export function changePassword(body) {
  return http.put('/me/password', body)
}
