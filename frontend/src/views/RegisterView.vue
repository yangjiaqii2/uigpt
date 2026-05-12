<script setup>
/**
 * 注册页 — 品牌化布局
 * ----------------------------
 * 可调品牌色（与全局主题联动，亦可覆盖）：
 * --reg-accent / --reg-accent-glow / --reg-mesh-1 … 见下方 .reg-page 内注释
 */
import { reactive, ref, onMounted, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { fetchRegisterCaptcha, fetchRegisterOptions } from '../api/auth'
import { getAxiosErrorMessage } from '../utils/httpError'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const realName = ref('')
const phone = ref('')
const username = ref('')
const password = ref('')
const confirmPassword = ref('')
const captchaCode = ref('')

const CN_PHONE = /^1[3-9]\d{9}$/

/** 顶部全局错误（接口失败等） */
const globalError = ref('')
const loading = ref(false)

const recaptchaSiteKey = ref('')
const captchaId = ref('')
const captchaImageSrc = ref('')

/** 各字段校验文案；非空即显示红框 */
const fieldErrors = reactive({
  realName: '',
  phone: '',
  username: '',
  password: '',
  confirmPassword: '',
  captcha: '',
})

const focus = reactive({
  realName: false,
  phone: false,
  username: false,
  password: false,
  confirmPassword: false,
  captcha: false,
})

watch(realName, (v) => {
  if (fieldErrors.realName && v.trim().length > 0) fieldErrors.realName = ''
})
watch(phone, (v) => {
  if (fieldErrors.phone && CN_PHONE.test(v.trim())) fieldErrors.phone = ''
})
watch(username, (v) => {
  if (fieldErrors.username && v.trim().length >= 3) fieldErrors.username = ''
})
watch(password, () => {
  if (fieldErrors.password && password.value.length >= 8) fieldErrors.password = ''
  if (fieldErrors.confirmPassword && password.value === confirmPassword.value) fieldErrors.confirmPassword = ''
})
watch(confirmPassword, () => {
  if (fieldErrors.confirmPassword && password.value === confirmPassword.value) fieldErrors.confirmPassword = ''
})
watch(captchaCode, (v) => {
  if (fieldErrors.captcha && v.trim().length > 0) fieldErrors.captcha = ''
})

async function refreshCaptcha() {
  try {
    const { data } = await fetchRegisterCaptcha()
    captchaId.value = data.captchaId
    captchaImageSrc.value = `data:image/png;base64,${data.imageBase64}`
    captchaCode.value = ''
  } catch {
    captchaId.value = ''
    captchaImageSrc.value = ''
    captchaCode.value = ''
  }
}

function loadRecaptchaScript(siteKey) {
  return new Promise((resolve, reject) => {
    if (typeof window === 'undefined') {
      reject(new Error('no window'))
      return
    }
    if (window.grecaptcha?.execute) {
      resolve()
      return
    }
    const id = 'google-recaptcha-v3'
    if (document.getElementById(id)) {
      const check = () => {
        if (window.grecaptcha?.execute) resolve()
        else window.setTimeout(check, 50)
      }
      check()
      return
    }
    const s = document.createElement('script')
    s.id = id
    s.async = true
    s.src = `https://www.google.com/recaptcha/api.js?render=${encodeURIComponent(siteKey)}`
    s.onload = () => resolve()
    s.onerror = () => reject(new Error('人机验证脚本加载失败'))
    document.head.appendChild(s)
  })
}

async function obtainRecaptchaToken() {
  const key = recaptchaSiteKey.value
  if (!key) return ''
  await loadRecaptchaScript(key)
  return new Promise((resolve, reject) => {
    window.grecaptcha.ready(() => {
      window.grecaptcha.execute(key, { action: 'register' }).then(resolve).catch(reject)
    })
  })
}

function validateClient() {
  fieldErrors.realName = ''
  fieldErrors.phone = ''
  fieldErrors.username = ''
  fieldErrors.password = ''
  fieldErrors.confirmPassword = ''
  fieldErrors.captcha = ''
  let ok = true
  if (!realName.value.trim()) {
    fieldErrors.realName = '请填写姓名'
    ok = false
  }
  const ph = phone.value.trim()
  if (!CN_PHONE.test(ph)) {
    fieldErrors.phone = '请输入有效的 11 位中国大陆手机号'
    ok = false
  }
  const u = username.value.trim()
  if (u.length < 3) {
    fieldErrors.username = '用户名至少 3 个字符'
    ok = false
  }
  if (password.value.length < 8) {
    fieldErrors.password = '密码至少 8 位'
    ok = false
  }
  if (password.value !== confirmPassword.value) {
    fieldErrors.confirmPassword = '两次输入的密码不一致'
    ok = false
  }
  if (!captchaCode.value.trim()) {
    fieldErrors.captcha = '请输入图形验证码'
    ok = false
  }
  return ok
}

function mapServerError(msg) {
  globalError.value = msg
  const m = msg || ''
  if (/验证码|人机/i.test(m)) {
    fieldErrors.captcha = m.length < 80 ? m : '验证码校验失败，请重试'
  }
  if (/用户名/.test(m) && /存在|占用/.test(m)) {
    fieldErrors.username = m
  }
  if (/手机号/.test(m)) {
    fieldErrors.phone = m.length < 80 ? m : '该手机号不可用'
  }
}

onMounted(async () => {
  await refreshCaptcha()
  try {
    const { data } = await fetchRegisterOptions()
    if (data.recaptchaEnabled && data.recaptchaSiteKey) {
      recaptchaSiteKey.value = data.recaptchaSiteKey
      await loadRecaptchaScript(data.recaptchaSiteKey)
    }
  } catch {
    /* 提交时由后端兜底 */
  }
})

async function onSubmit() {
  globalError.value = ''
  if (!validateClient()) return

  loading.value = true
  try {
    let token = ''
    if (recaptchaSiteKey.value) {
      token = await obtainRecaptchaToken()
    }
    await auth.register({
      realName: realName.value.trim(),
      phone: phone.value.trim(),
      username: username.value.trim(),
      password: password.value,
      confirmPassword: confirmPassword.value,
      captchaId: captchaId.value,
      captchaCode: captchaCode.value.trim(),
      recaptchaToken: token || undefined,
    })
    const r = route.query.redirect
    const redirect = typeof r === 'string' && r ? r : '/chat'
    router.replace(redirect)
  } catch (e) {
    const msg = getAxiosErrorMessage(e)
    mapServerError(msg)
    await refreshCaptcha()
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="reg-page">
    <!-- 极淡噪点 + 暖灰底（浅色）；深色主题由变量覆盖 -->
    <div class="reg-noise" aria-hidden="true" />
    <div class="reg-bg-mesh" aria-hidden="true" />

    <div class="reg-shell">
      <!-- 左侧品牌视觉 ~45% -->
      <aside class="reg-hero" aria-hidden="true">
        <div class="reg-hero-inner">
          <div class="reg-hero-badge">AI · Design</div>
          <svg class="reg-hero-svg" viewBox="0 0 400 420" fill="none" xmlns="http://www.w3.org/2000/svg">
            <defs>
              <linearGradient id="rg1" x1="0%" y1="0%" x2="100%" y2="100%">
                <stop offset="0%" stop-color="var(--reg-mesh-1)" stop-opacity="0.55" />
                <stop offset="100%" stop-color="var(--reg-mesh-2)" stop-opacity="0.35" />
              </linearGradient>
              <linearGradient id="rg2" x1="100%" y1="0%" x2="0%" y2="100%">
                <stop offset="0%" stop-color="var(--reg-mesh-3)" stop-opacity="0.4" />
                <stop offset="100%" stop-color="var(--reg-mesh-1)" stop-opacity="0.15" />
              </linearGradient>
              <filter id="rgblur" x="-20%" y="-20%" width="140%" height="140%">
                <feGaussianBlur stdDeviation="24" />
              </filter>
            </defs>
            <ellipse cx="200" cy="120" rx="140" ry="100" fill="url(#rg1)" filter="url(#rgblur)" class="reg-blob reg-blob--a" />
            <ellipse cx="260" cy="260" rx="120" ry="130" fill="url(#rg2)" filter="url(#rgblur)" class="reg-blob reg-blob--b" />
            <path
              class="reg-flow"
              d="M60 280 Q140 200 220 240 T380 180"
              stroke="var(--reg-accent)"
              stroke-width="2"
              stroke-opacity="0.35"
              stroke-linecap="round"
              fill="none"
            />
            <circle cx="320" cy="100" r="6" fill="var(--reg-accent)" fill-opacity="0.6" class="reg-dot" />
            <circle cx="90" cy="160" r="4" fill="var(--reg-accent)" fill-opacity="0.45" class="reg-dot reg-dot--d2" />
            <rect x="160" y="300" width="88" height="56" rx="14" stroke="var(--reg-accent)" stroke-opacity="0.25" fill="var(--reg-card-glass)" />
          </svg>
          <p class="reg-hero-tagline">让创意与界面，在同一场对话里发生。</p>
        </div>
      </aside>

      <!-- 右侧表单 -->
      <main class="reg-panel">
        <div class="reg-card">
          <header class="reg-head reg-card-enter reg-card-enter--d0">
            <p class="reg-brand">UI GPT</p>
            <h1 class="reg-title">创建账号</h1>
            <p class="reg-sub">填写姓名与手机号，设置登录用户名及密码（至少 8 位，需两次一致）。</p>
          </header>

          <Transition name="reg-alert">
            <div v-if="globalError" class="reg-alert reg-card-enter reg-card-enter--d1" role="alert">
              <span class="reg-alert-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2">
                  <circle cx="12" cy="12" r="10" />
                  <path d="M12 8v5M12 16h.01" stroke-linecap="round" />
                </svg>
              </span>
              <span class="reg-alert-text">{{ globalError }}</span>
            </div>
          </Transition>

          <form class="reg-form" @submit.prevent="onSubmit">
            <!-- 姓名 -->
            <div
              class="float-field reg-card-enter reg-card-enter--d2"
              :class="{
                'float-field--active': focus.realName || realName.length > 0,
                'float-field--filled': realName.trim().length > 0,
                'float-field--error': Boolean(fieldErrors.realName),
              }"
            >
              <label class="float-label" for="reg-name">姓名</label>
              <span class="float-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.6">
                  <path d="M14 3h2l3 3v6h-4V9H9v3H5V6l3-3h2" stroke-linecap="round" stroke-linejoin="round" />
                  <path d="M7 21v-3a5 5 0 0 1 10 0v3" stroke-linecap="round" />
                </svg>
              </span>
              <input
                id="reg-name"
                v-model="realName"
                type="text"
                class="float-input"
                autocomplete="name"
                maxlength="64"
                @focus="focus.realName = true"
                @blur="focus.realName = false"
              />
              <p v-if="fieldErrors.realName" class="float-hint float-hint--err">{{ fieldErrors.realName }}</p>
            </div>

            <!-- 手机号 -->
            <div
              class="float-field reg-card-enter reg-card-enter--d3"
              :class="{
                'float-field--active': focus.phone || phone.length > 0,
                'float-field--filled': phone.trim().length > 0,
                'float-field--error': Boolean(fieldErrors.phone),
              }"
            >
              <label class="float-label" for="reg-phone">手机号</label>
              <span class="float-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.6">
                  <rect x="7" y="3" width="10" height="18" rx="2" />
                  <path d="M10 18h4" stroke-linecap="round" />
                </svg>
              </span>
              <input
                id="reg-phone"
                v-model="phone"
                type="tel"
                class="float-input"
                autocomplete="tel"
                maxlength="11"
                inputmode="numeric"
                @focus="focus.phone = true"
                @blur="focus.phone = false"
              />
              <p v-if="fieldErrors.phone" class="float-hint float-hint--err">{{ fieldErrors.phone }}</p>
            </div>

            <!-- 用户名 -->
            <div
              class="float-field reg-card-enter reg-card-enter--d4"
              :class="{
                'float-field--active': focus.username || username.length > 0,
                'float-field--filled': username.trim().length > 0,
                'float-field--error': Boolean(fieldErrors.username),
              }"
            >
              <label class="float-label" for="reg-user">用户名</label>
              <span class="float-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.6">
                  <path d="M12 12a4 4 0 1 0-4-4 4 4 0 0 0 4 4Z" />
                  <path d="M4 21v-1a7 7 0 0 1 7-7h2a7 7 0 0 1 7 7v1" stroke-linecap="round" />
                </svg>
              </span>
              <input
                id="reg-user"
                v-model="username"
                type="text"
                class="float-input"
                autocomplete="username"
                maxlength="64"
                @focus="focus.username = true"
                @blur="focus.username = false"
              />
              <p v-if="fieldErrors.username" class="float-hint float-hint--err">{{ fieldErrors.username }}</p>
            </div>

            <!-- 密码 -->
            <div
              class="float-field reg-card-enter reg-card-enter--d5"
              :class="{
                'float-field--active': focus.password || password.length > 0,
                'float-field--filled': password.length > 0,
                'float-field--error': Boolean(fieldErrors.password),
              }"
            >
              <label class="float-label" for="reg-pass">密码</label>
              <span class="float-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.6">
                  <rect x="5" y="11" width="14" height="10" rx="2" />
                  <path d="M8 11V8a4 4 0 0 1 8 0v3" stroke-linecap="round" />
                </svg>
              </span>
              <input
                id="reg-pass"
                v-model="password"
                type="password"
                class="float-input"
                autocomplete="new-password"
                maxlength="72"
                @focus="focus.password = true"
                @blur="focus.password = false"
              />
              <p v-if="fieldErrors.password" class="float-hint float-hint--err">{{ fieldErrors.password }}</p>
            </div>

            <!-- 确认密码 -->
            <div
              class="float-field reg-card-enter reg-card-enter--d6"
              :class="{
                'float-field--active': focus.confirmPassword || confirmPassword.length > 0,
                'float-field--filled': confirmPassword.length > 0,
                'float-field--error': Boolean(fieldErrors.confirmPassword),
              }"
            >
              <label class="float-label" for="reg-pass2">确认密码</label>
              <span class="float-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.6">
                  <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z" stroke-linejoin="round" />
                  <path d="m9 12 2 2 4-4" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </span>
              <input
                id="reg-pass2"
                v-model="confirmPassword"
                type="password"
                class="float-input"
                autocomplete="new-password"
                maxlength="72"
                @focus="focus.confirmPassword = true"
                @blur="focus.confirmPassword = false"
              />
              <p v-if="fieldErrors.confirmPassword" class="float-hint float-hint--err">{{ fieldErrors.confirmPassword }}</p>
            </div>

            <!-- 验证码：横向 -->
            <div class="reg-captcha reg-card-enter reg-card-enter--d7">
              <p class="reg-captcha-title">图形验证码</p>
              <div
                class="reg-captcha-row"
                :class="{ 'reg-captcha-row--error': Boolean(fieldErrors.captcha) }"
              >
                <button
                  type="button"
                  class="reg-cap-img-wrap"
                  title="点击刷新"
                  :disabled="!captchaImageSrc"
                  @click="refreshCaptcha"
                >
                  <img v-if="captchaImageSrc" :src="captchaImageSrc" alt="验证码" class="reg-cap-img" />
                  <span v-else class="reg-cap-placeholder">加载中…</span>
                </button>

                <div class="reg-cap-input-col">
                  <div
                    class="float-field"
                    :class="{
                      'float-field--active': focus.captcha || captchaCode.length > 0,
                      'float-field--filled': captchaCode.trim().length > 0,
                      'float-field--error': Boolean(fieldErrors.captcha),
                    }"
                  >
                    <label class="float-label" for="reg-cap">验证码</label>
                    <span class="float-icon" aria-hidden="true">
                      <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.6">
                        <path d="M12 3 4 7v6c0 5 4 8 8 9 4-1 8-4 8-9V7l-8-4Z" stroke-linejoin="round" />
                        <path d="m9 12 2 2 4-4" stroke-linecap="round" stroke-linejoin="round" />
                      </svg>
                    </span>
                    <input
                      id="reg-cap"
                      v-model="captchaCode"
                      type="text"
                      class="float-input"
                      maxlength="8"
                      autocomplete="off"
                      autocapitalize="off"
                      spellcheck="false"
                      @focus="focus.captcha = true"
                      @blur="focus.captcha = false"
                    />
                  </div>
                  <button type="button" class="reg-refresh" @click="refreshCaptcha">
                    <svg class="reg-refresh-svg" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
                      <path d="M21 12a9 9 0 0 1-9 9 9 9 0 0 1-6.36-2.64M3 12a9 9 0 0 1 9-9 9 9 0 0 1 6.36 2.64M3 3v6h6M21 21v-6h-6" stroke-linecap="round" stroke-linejoin="round" />
                    </svg>
                    换一张
                  </button>
                  <p v-if="fieldErrors.captcha" class="float-hint float-hint--err">{{ fieldErrors.captcha }}</p>
                </div>
              </div>
            </div>

            <div v-if="recaptchaSiteKey" class="reg-recaptcha-note reg-card-enter reg-card-enter--d8">
              <p class="reg-recaptcha-note-title">Google reCAPTCHA v3</p>
              <p class="reg-recaptcha-note-body">
                提交时将后台评分；加载成功后页面<strong>右下角</strong>可见徽标。
              </p>
            </div>

            <button type="submit" class="reg-submit reg-card-enter reg-card-enter--d9" :disabled="loading">
              <span class="reg-submit-shine" aria-hidden="true" />
              <span class="reg-submit-inner">
                <span class="reg-submit-text" :class="{ 'reg-submit-text--hide': loading }">
                  注册并登录
                  <svg class="reg-submit-arrow" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.2">
                    <path d="M5 12h14M13 6l6 6-6 6" stroke-linecap="round" stroke-linejoin="round" />
                  </svg>
                </span>
                <span v-if="loading" class="reg-spinner" aria-hidden="true" />
              </span>
            </button>
          </form>

          <p v-if="recaptchaSiteKey" class="reg-legal reg-card-enter reg-card-enter--d10">
            使用 reCAPTCHA v3，适用 Google
            <a href="https://policies.google.com/privacy" target="_blank" rel="noopener noreferrer">隐私政策</a>
            与
            <a href="https://policies.google.com/terms" target="_blank" rel="noopener noreferrer">服务条款</a>。
          </p>

          <p class="reg-login-wrap reg-card-enter reg-card-enter--d11">
            <RouterLink to="/login" class="reg-login-link">已有账号，去登录</RouterLink>
          </p>
        </div>
      </main>
    </div>
  </div>
</template>

<style scoped>
/* ========== 品牌可调变量（深色主题下由 .reg-page 覆盖） ========== */
.reg-page {
  --reg-accent: var(--accent);
  --reg-accent-glow: var(--accent-dim);
  --reg-mesh-1: #5ee1d5;
  --reg-mesh-2: #818cf8;
  --reg-mesh-3: #38bdf8;
  --reg-card-glass: rgba(255, 255, 255, 0.06);
  --reg-page-bg: #f4f3f0;
  --reg-page-bg2: #e8eef6;
  --reg-alert-bg: #fef2f2;
  --reg-alert-fg: #991b1b;
  --reg-alert-border: rgba(220, 38, 38, 0.18);
  --reg-input-border: #e5e7eb;
  --reg-error-border: #f87171;
  --reg-shadow-soft: 0 18px 48px rgba(15, 23, 42, 0.08);
  --reg-btn-shadow: 0 12px 28px rgba(13, 148, 136, 0.22);

  min-height: 100vh;
  min-height: 100dvh;
  position: relative;
  overflow-x: clip;
  max-width: 100%;
  background: linear-gradient(145deg, var(--reg-page-bg) 0%, #faf8f6 42%, var(--reg-page-bg2) 100%);
  color: var(--text);
}

html[data-theme='dark'] .reg-page,
html:not([data-theme]) .reg-page {
  --reg-page-bg: #0c0e14;
  --reg-page-bg2: #121826;
  --reg-card-glass: rgba(255, 255, 255, 0.04);
  --reg-input-border: rgba(255, 255, 255, 0.12);
  --reg-alert-bg: rgba(127, 29, 29, 0.22);
  --reg-alert-fg: #fecaca;
  --reg-alert-border: rgba(248, 113, 113, 0.25);
  --reg-shadow-soft: 0 24px 64px rgba(0, 0, 0, 0.45);
  --reg-btn-shadow: 0 14px 36px rgba(94, 225, 213, 0.18);

  background: linear-gradient(155deg, var(--reg-page-bg) 0%, #0a0c12 45%, var(--reg-page-bg2) 100%);
}

.reg-noise {
  pointer-events: none;
  position: absolute;
  inset: 0;
  opacity: 0.045;
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 256 256' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.85' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E");
}

.reg-bg-mesh {
  pointer-events: none;
  position: absolute;
  inset: -20% -10%;
  background:
    radial-gradient(ellipse 60% 50% at 80% 20%, rgba(94, 225, 213, 0.14), transparent),
    radial-gradient(ellipse 50% 40% at 10% 70%, rgba(129, 140, 248, 0.12), transparent);
  opacity: 0.9;
}

html[data-theme='dark'] .reg-bg-mesh,
html:not([data-theme]) .reg-bg-mesh {
  background:
    radial-gradient(ellipse 55% 45% at 75% 15%, rgba(94, 225, 213, 0.12), transparent),
    radial-gradient(ellipse 45% 38% at 12% 65%, rgba(129, 140, 248, 0.1), transparent);
}

.reg-shell {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(min(100%, 320px), min(520px, 100%));
  min-height: 100vh;
  min-height: 100dvh;
  max-width: 1200px;
  margin: 0 auto;
  align-items: stretch;
  padding: max(clamp(1rem, 3vw, 2.5rem), env(safe-area-inset-top, 0px))
    max(clamp(1rem, 3vw, 2.5rem), env(safe-area-inset-right, 0px))
    max(clamp(1rem, 3vw, 2.5rem), env(safe-area-inset-bottom, 0px))
    max(clamp(1rem, 3vw, 2.5rem), env(safe-area-inset-left, 0px));
  gap: clamp(1rem, 4vw, 3rem);
  box-sizing: border-box;
}

@media (max-width: 960px) {
  .reg-shell {
    grid-template-columns: 1fr;
    padding: max(1rem, env(safe-area-inset-top, 0px)) max(1rem, env(safe-area-inset-right, 0px))
      max(1rem, env(safe-area-inset-bottom, 0px)) max(1rem, env(safe-area-inset-left, 0px));
  }
}

/* ----- 左侧插画 ----- */
.reg-hero {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 1rem;
}

@media (max-width: 960px) {
  .reg-hero {
    min-height: auto;
    padding: 0.5rem 0 0;
  }
}

.reg-hero-inner {
  width: 100%;
  max-width: 420px;
}

.reg-hero-badge {
  display: inline-block;
  font-size: 0.6875rem;
  font-weight: 700;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--text-muted);
  margin-bottom: 1rem;
  padding: 0.35rem 0.75rem;
  border-radius: 999px;
  border: 1px solid var(--stroke);
  background: var(--bg-panel);
}

.reg-hero-svg {
  width: 100%;
  height: auto;
  max-height: 340px;
}

.reg-blob--a {
  animation: reg-float-a 14s ease-in-out infinite;
}

.reg-blob--b {
  animation: reg-float-b 18s ease-in-out infinite;
}

.reg-flow {
  animation: reg-dash 10s ease-in-out infinite;
}

.reg-dot {
  animation: reg-pulse 3s ease-in-out infinite;
}

.reg-dot--d2 {
  animation-delay: 1.2s;
}

@keyframes reg-float-a {
  0%,
  100% {
    transform: translate(0, 0);
  }
  50% {
    transform: translate(12px, -10px);
  }
}

@keyframes reg-float-b {
  0%,
  100% {
    transform: translate(0, 0);
  }
  50% {
    transform: translate(-14px, 12px);
  }
}

@keyframes reg-dash {
  0%,
  100% {
    opacity: 0.35;
  }
  50% {
    opacity: 0.65;
  }
}

@keyframes reg-pulse {
  0%,
  100% {
    opacity: 0.45;
    transform: scale(1);
  }
  50% {
    opacity: 0.85;
    transform: scale(1.15);
  }
}

.reg-hero-tagline {
  margin: 1rem 0 0;
  font-size: 0.9375rem;
  line-height: 1.55;
  color: var(--text-muted);
  max-width: 28ch;
}

/* ----- 右侧卡片 ----- */
.reg-panel {
  display: flex;
  align-items: center;
  justify-content: center;
}

.reg-card {
  width: 100%;
  max-width: 440px;
  padding: clamp(1.75rem, 4vw, 2.5rem);
  border-radius: 28px;
  background: var(--bg-panel);
  border: 1px solid var(--stroke);
  box-shadow: var(--shadow-glow), var(--reg-shadow-soft);
  backdrop-filter: blur(18px);
}

.reg-head {
  margin-bottom: 1.5rem;
}

.reg-brand {
  margin: 0 0 0.65rem;
  font-size: 0.8125rem;
  font-weight: 800;
  letter-spacing: 0.22em;
  text-transform: uppercase;
  background: linear-gradient(120deg, var(--reg-accent), #6366f1, var(--reg-accent));
  background-size: 200% auto;
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
  animation: reg-shimmer 8s ease-in-out infinite;
  filter: drop-shadow(0 0 20px rgba(94, 225, 213, 0.15));
}

@keyframes reg-shimmer {
  0%,
  100% {
    background-position: 0% 50%;
  }
  50% {
    background-position: 100% 50%;
  }
}

.reg-title {
  margin: 0 0 0.5rem;
  font-size: clamp(1.65rem, 4vw, 2rem);
  font-weight: 800;
  letter-spacing: -0.03em;
  color: var(--text);
  line-height: 1.15;
}

.reg-sub {
  margin: 0;
  font-size: 0.9375rem;
  color: var(--text-muted);
  opacity: 0.85;
  line-height: 1.55;
}

/* ----- 全局错误条 ----- */
.reg-alert {
  display: flex;
  align-items: flex-start;
  gap: 0.65rem;
  padding: 0.75rem 1rem;
  margin-bottom: 1.25rem;
  border-radius: 14px;
  background: var(--reg-alert-bg);
  border: 1px solid var(--reg-alert-border);
  color: var(--reg-alert-fg);
  font-size: 0.875rem;
  line-height: 1.45;
}

.reg-alert-icon {
  flex-shrink: 0;
  margin-top: 0.1rem;
  opacity: 0.85;
}

.reg-alert-enter-active,
.reg-alert-leave-active {
  transition:
    opacity 0.28s ease-out,
    transform 0.28s ease-out;
}

.reg-alert-enter-from,
.reg-alert-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

/* ----- 表单 ----- */
.reg-form {
  display: flex;
  flex-direction: column;
  gap: 1.35rem;
}

.float-field {
  position: relative;
  --float-pad-x: 48px;
  --float-h: 50px;
}

/**
 * 已输入内容时隐藏浮动标签（不再缩在框内上方），仅保留用户输入可见；
 * 未输入时仍显示占位式浮动文案（聚焦时可上移高亮）。
 */
.float-field--filled .float-label {
  opacity: 0 !important;
  visibility: hidden !important;
}

.float-field--filled .float-input {
  padding: 14px 14px 14px var(--float-pad-x);
}

.float-label {
  position: absolute;
  left: var(--float-pad-x);
  top: 50%;
  transform: translateY(-50%);
  font-size: 0.9375rem;
  color: var(--text-muted);
  opacity: 0.65;
  pointer-events: none;
  transition:
    transform 0.25s ease-out,
    font-size 0.25s ease-out,
    top 0.25s ease-out,
    opacity 0.25s ease-out;
  transform-origin: left center;
  z-index: 1;
}

.float-field--active .float-label {
  top: 11px;
  transform: translateY(0) scale(0.72);
  opacity: 0.95;
  color: var(--reg-accent);
}

.float-icon {
  position: absolute;
  left: 16px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--text-muted);
  opacity: 0.38;
  pointer-events: none;
  z-index: 1;
  transition: color 0.25s ease-out;
}

.float-field--active .float-icon {
  opacity: 0.55;
  color: var(--reg-accent);
}

.float-input {
  width: 100%;
  box-sizing: border-box;
  height: var(--float-h);
  padding: 22px 14px 10px var(--float-pad-x);
  border-radius: 14px;
  border: 1px solid var(--reg-input-border);
  background: var(--auth-input-bg);
  color: var(--text);
  font-size: 0.9375rem;
  line-height: 1.35;
  outline: none;
  transition:
    border-color 0.25s ease-out,
    box-shadow 0.25s ease-out;
}

.float-input:focus {
  border-color: var(--reg-accent);
  box-shadow: 0 0 0 4px color-mix(in srgb, var(--reg-accent) 12%, transparent);
}

.float-field--error .float-input {
  border-color: var(--reg-error-border);
  box-shadow: 0 0 0 4px rgba(248, 113, 113, 0.12);
}

.float-field--error.float-field--active .float-label {
  color: #dc2626;
}

.float-hint {
  margin: 0.35rem 0 0;
  font-size: 0.75rem;
  min-height: 1em;
}

.float-hint--err {
  color: #b91c1c;
}

html[data-theme='dark'] .float-hint--err,
html:not([data-theme]) .float-hint--err {
  color: #fca5a5;
}

/* 验证码区 */
.reg-captcha-title {
  margin: 0 0 0.5rem;
  font-size: 0.8125rem;
  font-weight: 600;
  color: var(--text-muted);
  letter-spacing: 0.02em;
}

.reg-captcha-row {
  display: flex;
  flex-direction: row;
  align-items: flex-start;
  gap: 14px;
}

.reg-cap-img-wrap {
  flex-shrink: 0;
  padding: 0;
  border: none;
  border-radius: 14px;
  overflow: hidden;
  cursor: pointer;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.08);
  transition:
    box-shadow 0.25s ease-out,
    transform 0.25s ease-out;
  background: var(--auth-input-bg-muted);
}

html[data-theme='dark'] .reg-cap-img-wrap,
html:not([data-theme]) .reg-cap-img-wrap {
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.35);
}

.reg-cap-img-wrap:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 12px 32px rgba(15, 23, 42, 0.12);
}

.reg-cap-img-wrap:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.reg-cap-img {
  display: block;
  width: 130px;
  height: 50px;
  object-fit: cover;
}

.reg-cap-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 130px;
  height: 50px;
  font-size: 0.75rem;
  color: var(--text-muted);
}

.reg-cap-input-col {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 0.35rem;
}

.reg-refresh {
  align-self: flex-start;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 2px;
  padding: 0;
  border: none;
  background: none;
  font-size: 0.8125rem;
  font-weight: 600;
  color: var(--text-muted);
  cursor: pointer;
  transition: color 0.25s ease-out;
}

.reg-refresh:hover {
  color: var(--reg-accent);
}

.reg-refresh-svg {
  transition: transform 0.35s ease-out;
}

.reg-refresh:hover .reg-refresh-svg {
  transform: rotate(180deg);
}

.reg-captcha-row--error .reg-cap-img-wrap {
  box-shadow: 0 0 0 2px var(--reg-error-border);
}

.reg-recaptcha-note {
  padding: 0.75rem 1rem;
  border-radius: 14px;
  border: 1px dashed color-mix(in srgb, var(--reg-accent) 35%, var(--stroke));
  background: color-mix(in srgb, var(--reg-accent) 6%, transparent);
}

.reg-recaptcha-note-title {
  margin: 0 0 0.35rem;
  font-size: 0.72rem;
  font-weight: 800;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--reg-accent);
}

.reg-recaptcha-note-body {
  margin: 0;
  font-size: 0.8125rem;
  color: var(--text-muted);
  line-height: 1.5;
}

.reg-recaptcha-note-body strong {
  color: var(--text);
}

/* 提交按钮 */
.reg-submit {
  position: relative;
  margin-top: 0.25rem;
  width: 100%;
  height: 54px;
  border: none;
  border-radius: 999px;
  cursor: pointer;
  font-size: 1rem;
  font-weight: 700;
  color: #042f2e;
  background: linear-gradient(135deg, #5ee1d5 0%, #7ee8cb 45%, #a7f3d0 100%);
  box-shadow: var(--reg-btn-shadow);
  overflow: hidden;
  transition:
    transform 0.22s ease-out,
    box-shadow 0.22s ease-out;
}

html[data-theme='dark'] .reg-submit,
html:not([data-theme]) .reg-submit {
  color: #061018;
}

.reg-submit:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 16px 40px rgba(94, 225, 213, 0.28);
}

.reg-submit:active:not(:disabled) {
  transform: translateY(0) scale(0.98);
}

.reg-submit:disabled {
  opacity: 0.85;
  cursor: not-allowed;
  transform: none;
}

.reg-submit-shine {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    105deg,
    transparent 40%,
    rgba(255, 255, 255, 0.35) 50%,
    transparent 60%
  );
  transform: translateX(-100%);
  animation: reg-shine 3.5s ease-in-out infinite;
}

@keyframes reg-shine {
  0% {
    transform: translateX(-100%);
  }
  35%,
  100% {
    transform: translateX(100%);
  }
}

.reg-submit-inner {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
}

.reg-submit-text {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  transition: opacity 0.25s ease-out;
}

.reg-submit-text--hide {
  opacity: 0;
}

.reg-submit-arrow {
  transition: transform 0.25s ease-out;
}

.reg-submit:hover:not(:disabled) .reg-submit-arrow {
  transform: translateX(3px);
}

.reg-spinner {
  position: absolute;
  width: 22px;
  height: 22px;
  border: 2.5px solid rgba(6, 24, 24, 0.2);
  border-top-color: #061018;
  border-radius: 50%;
  animation: reg-spin 0.75s linear infinite;
}

@keyframes reg-spin {
  to {
    transform: rotate(360deg);
  }
}

.reg-legal {
  margin: 1rem 0 0;
  font-size: 0.6875rem;
  line-height: 1.45;
  color: var(--text-muted);
}

.reg-legal a {
  color: var(--reg-accent);
}

/* 登录链接 */
.reg-login-wrap {
  margin: 1.25rem 0 0;
  text-align: center;
}

.reg-login-link {
  position: relative;
  font-size: 0.9375rem;
  font-weight: 600;
  color: var(--text-muted);
  text-decoration: none;
  transition: color 0.25s ease-out;
}

.reg-login-link::after {
  content: '';
  position: absolute;
  left: 0;
  bottom: -3px;
  width: 100%;
  height: 2px;
  background: var(--reg-accent);
  transform: scaleX(0);
  transform-origin: left;
  transition: transform 0.28s ease-out;
}

.reg-login-link:hover {
  color: var(--reg-accent);
}

.reg-login-link:hover::after {
  transform: scaleX(1);
}

/* 入场 stagger（时长 ease-out 0.25–0.28s 量级由 reg-in 控制） */
.reg-card-enter {
  animation: reg-in 0.55s ease-out both;
}

.reg-card-enter--d0 {
  animation-delay: 0s;
}
.reg-card-enter--d1 {
  animation-delay: 0.06s;
}
.reg-card-enter--d2 {
  animation-delay: 0.11s;
}
.reg-card-enter--d3 {
  animation-delay: 0.16s;
}
.reg-card-enter--d4 {
  animation-delay: 0.21s;
}
.reg-card-enter--d5 {
  animation-delay: 0.26s;
}
.reg-card-enter--d6 {
  animation-delay: 0.31s;
}
.reg-card-enter--d7 {
  animation-delay: 0.36s;
}
.reg-card-enter--d8 {
  animation-delay: 0.41s;
}
.reg-card-enter--d9 {
  animation-delay: 0.46s;
}
.reg-card-enter--d10 {
  animation-delay: 0.51s;
}
.reg-card-enter--d11 {
  animation-delay: 0.56s;
}

@keyframes reg-in {
  from {
    opacity: 0;
    transform: translateY(14px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* 移动端验证码纵向时仍可并排：窄屏叠放 */
@media (max-width: 420px) {
  .reg-captcha-row {
    flex-direction: column;
    align-items: stretch;
  }

  .reg-cap-img-wrap {
    align-self: flex-start;
  }
}
</style>

<style>
/* reCAPTCHA v3 徽标可见 */
.grecaptcha-badge {
  visibility: visible !important;
  opacity: 1 !important;
}
</style>
