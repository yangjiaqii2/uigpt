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
  // 实例默认带 application/json；FormData 须由浏览器设置 multipart 边界，否则会 415
  if (typeof FormData !== 'undefined' && config.data instanceof FormData && config.headers) {
    const h = config.headers
    if (typeof h.delete === 'function') {
      h.delete('Content-Type')
    } else {
      delete h['Content-Type']
    }
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
