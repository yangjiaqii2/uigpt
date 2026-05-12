import axios from 'axios'

const http = axios.create({
  baseURL: '/api',
  timeout: 120_000,
  headers: { 'Content-Type': 'application/json' },
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('uigpt_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (r) => r,
  async (err) => {
    if (err.response?.status === 401) {
      const { default: router } = await import('../router')
      const path = router.currentRoute.value.path
      const hadToken = Boolean(localStorage.getItem('uigpt_token'))
      const { useAuthStore } = await import('../stores/auth')
      useAuthStore().logout()
      // 访客模式未持有令牌时不强制跳转登录（例如误调了需登录接口）
      if (hadToken && path !== '/login') {
        router.push({
          path: '/login',
          query: { redirect: router.currentRoute.value.fullPath },
        })
      }
    }
    return Promise.reject(err)
  },
)

export default http
