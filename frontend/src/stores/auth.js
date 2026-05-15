import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { useSiteMailStore } from './siteMail'

/** 与后端 {@code UserPrivilege.SUPER_ADMIN} / GET /api/me 的 privilege 一致 */
const SUPER_ADMIN_PRIVILEGE = 2

const TOKEN_KEY = 'uigpt_token'
const USER_KEY = 'uigpt_user'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) || '')
  const username = ref(localStorage.getItem(USER_KEY) || '')
  /** 是否具备管理员权限（来自 GET /api/me） */
  const isAdmin = ref(false)
  /** 0 普通 1 付费 2 超级管理（来自 GET /api/me） */
  const privilege = ref(0)
  /** 当前可用积分（GET /api/me 的 points 或 credits） */
  const points = ref(0)
  const meLoaded = ref(false)

  const isAuthenticated = computed(() => Boolean(token.value))

  /** DB 超级管理员（privilege=2）；与 isAdmin（含环境变量管理员名单）不同 */
  const isSuperAdmin = computed(
    () => Math.trunc(Number(privilege.value) || 0) === SUPER_ADMIN_PRIVILEGE,
  )

  function persist() {
    if (token.value) localStorage.setItem(TOKEN_KEY, token.value)
    else localStorage.removeItem(TOKEN_KEY)
    if (username.value) localStorage.setItem(USER_KEY, username.value)
    else localStorage.removeItem(USER_KEY)
  }

  async function refreshMe() {
    if (!token.value) {
      isAdmin.value = false
      privilege.value = 0
      points.value = 0
      meLoaded.value = true
      return
    }
    try {
      const { fetchMe } = await import('../api/auth')
      const { data } = await fetchMe()
      username.value = data.username ?? username.value
      isAdmin.value = Boolean(data.admin)
      privilege.value = Number(data.privilege ?? 0)
      const p = data.points ?? data.credits
      points.value = Number.isFinite(Number(p)) ? Math.trunc(Number(p)) : 0
    } catch {
      isAdmin.value = false
      privilege.value = 0
      points.value = 0
    } finally {
      meLoaded.value = true
    }
  }

  async function login(user, pass) {
    const { login: loginApi } = await import('../api/auth')
    const { data } = await loginApi(user, pass)
    token.value = data.token
    username.value = data.username
    meLoaded.value = false
    persist()
    await refreshMe()
  }

  /** @param {Record<string, unknown>} payload 注册请求 JSON，字段与后端 RegisterRequest 一致 */
  async function register(payload) {
    const { register: reg } = await import('../api/auth')
    const { data } = await reg(payload)
    token.value = data.token
    username.value = data.username
    meLoaded.value = false
    persist()
    await refreshMe()
  }

  async function logout() {
    try {
      const { logout: logoutApi } = await import('../api/auth')
      await logoutApi()
    } catch {
      /* 未登录或网络失败时仍清空本地态 */
    }
    token.value = ''
    username.value = ''
    isAdmin.value = false
    privilege.value = 0
    points.value = 0
    meLoaded.value = false
    persist()
    try {
      useSiteMailStore().reset()
    } catch {
      /* ignore */
    }
  }

  return {
    token,
    username,
    isAdmin,
    privilege,
    points,
    meLoaded,
    isAuthenticated,
    isSuperAdmin,
    login,
    register,
    logout,
    refreshMe,
  }
})
