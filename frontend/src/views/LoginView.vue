<script setup>
import { ref, computed } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import AnimatedCharacters from '../components/login/AnimatedCharacters.vue'
import { getAxiosErrorMessage } from '../utils/httpError'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const username = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)
const showPassword = ref(false)
const isUsernameFocused = ref(false)

const passwordLength = computed(() => password.value.length)

async function onSubmit() {
  error.value = ''
  loading.value = true
  try {
    await auth.login(username.value.trim(), password.value)
    const r = route.query.redirect
    const redirect = typeof r === 'string' && r ? r : '/chat'
    router.replace(redirect)
  } catch (e) {
    error.value = getAxiosErrorMessage(e)
  } finally {
    loading.value = false
  }
}

function enterWithoutAccount() {
  router.replace('/chat')
}
</script>

<template>
  <div class="shell">
    <!-- 与右侧同一套深色底 + 网格光斑（全宽一致） -->
    <div class="shell-bg" aria-hidden="true">
      <div class="shell-grid" />
      <span class="orb o1" />
      <span class="orb o2" />
      <span class="orb o3" />
    </div>

    <RouterLink to="/chat" class="brand-corner">
      <span class="brand-dot" />
      <span class="brand-text">UI GPT</span>
    </RouterLink>

    <!-- 左侧：与右侧登录块同一垂直中线对齐（大屏居中） -->
    <aside class="left-panel" aria-hidden="false">
      <div class="left-align">
        <div class="stage-wrap">
          <div class="stage-rail" aria-hidden="true" />
          <AnimatedCharacters
            :is-typing="isUsernameFocused"
            :show-password="showPassword"
            :password-length="passwordLength"
          />
        </div>
      </div>
    </aside>

    <!-- 右侧：表单 -->
    <div class="right-panel">
      <div class="right-align">
        <main class="card">
          <p class="eyebrow">UI GPT</p>
          <h1>欢迎回来</h1>
          <p class="sub">请登录以使用对话服务。</p>

          <form class="form" @submit.prevent="onSubmit">
            <label class="field">
              <span>用户名</span>
              <input
                v-model="username"
                type="text"
                autocomplete="username"
                required
                placeholder="请输入用户名"
                @focus="isUsernameFocused = true"
                @blur="isUsernameFocused = false"
              />
            </label>
            <label class="field">
              <span>密码</span>
              <div class="pass-wrap">
                <input
                  v-model="password"
                  :type="showPassword ? 'text' : 'password'"
                  autocomplete="current-password"
                  required
                  placeholder="••••••••"
                />
                <button
                  type="button"
                  class="toggle-pw"
                  :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                  @click="showPassword = !showPassword"
                >
                  <svg v-if="!showPassword" class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
                    <circle cx="12" cy="12" r="3" />
                  </svg>
                  <svg v-else class="ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                    <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24" />
                    <line x1="1" y1="1" x2="23" y2="23" />
                  </svg>
                </button>
              </div>
            </label>
            <p v-if="error" class="error">{{ error }}</p>
            <button type="submit" class="btn-primary" :disabled="loading">
              {{ loading ? '登录中…' : '登录' }}
            </button>
            <button type="button" class="btn-guest" :disabled="loading" @click="enterWithoutAccount">
              直接进入
            </button>
            <p class="guest-hint" lang="zh-CN">
              无需账号亦可试用对话（模型由服务端默认）
            </p>
          </form>

          <p class="foot foot-links">
            <RouterLink to="/forgot-password" class="link">忘记密码</RouterLink>
            <span class="foot-sep" aria-hidden="true">·</span>
            <RouterLink to="/register" class="link">注册账号</RouterLink>
          </p>
        </main>
      </div>
    </div>
  </div>
</template>

<style scoped>
.shell {
  min-height: 100vh;
  min-height: 100dvh;
  display: grid;
  grid-template-columns: 1fr;
  overflow-x: clip;
  overflow-y: auto;
  position: relative;
  background: var(--bg-deep);
  color: var(--text);
  max-width: 100%;
}

@media (min-width: 1024px) {
  .shell {
    grid-template-columns: 1fr 1fr;
    align-items: stretch;
  }
}

.brand-corner {
  position: absolute;
  top: max(1.25rem, env(safe-area-inset-top, 0px));
  left: max(1.25rem, env(safe-area-inset-left, 0px));
  z-index: 10;
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.45rem 1rem 0.45rem 0.65rem;
  border-radius: 9999px;
  font-size: 0.9375rem;
  font-weight: 600;
  text-decoration: none;
  color: var(--text);
  background: rgba(18, 22, 32, 0.72);
  border: 1px solid var(--stroke);
  backdrop-filter: blur(12px);
  box-shadow: 0 4px 24px rgba(0, 0, 0, 0.35);
  transition:
    border-color 0.2s,
    box-shadow 0.2s;
}

.brand-corner:hover {
  border-color: rgba(94, 225, 213, 0.35);
  box-shadow: 0 6px 28px rgba(94, 225, 213, 0.1);
}

.shell-bg {
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 0;
  overflow: hidden;
}

.shell-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(94, 225, 213, 0.04) 1px, transparent 1px),
    linear-gradient(90deg, rgba(94, 225, 213, 0.04) 1px, transparent 1px);
  background-size: 48px 48px;
  mask-image: radial-gradient(ellipse 85% 65% at 50% 42%, black 18%, transparent 72%);
}

.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  opacity: 0.45;
  animation: float-orb 18s ease-in-out infinite;
}

.o1 {
  width: 420px;
  height: 420px;
  background: #2d6cdf;
  top: 8%;
  left: 5%;
  animation-delay: 0s;
}

.o2 {
  width: 360px;
  height: 360px;
  background: #5ee1d5;
  bottom: 0%;
  right: 8%;
  animation-delay: -6s;
}

.o3 {
  width: 280px;
  height: 280px;
  background: #a78bfa;
  top: 38%;
  right: 18%;
  opacity: 0.32;
  animation-delay: -12s;
}

@keyframes float-orb {
  0%,
  100% {
    transform: translate(0, 0) scale(1);
  }
  33% {
    transform: translate(20px, -16px) scale(1.04);
  }
  66% {
    transform: translate(-14px, 10px) scale(0.98);
  }
}

.left-panel {
  display: none;
  position: relative;
  z-index: 1;
  background: transparent;
  overflow: hidden;
}

@media (min-width: 1024px) {
  .left-panel {
    display: flex;
    align-items: center;
    justify-content: center;
    min-height: 100vh;
    padding: 2rem 1.5rem 2rem 2rem;
    border-right: 1px solid var(--stroke);
    box-shadow: inset -1px 0 0 rgba(0, 0, 0, 0.2);
  }
}

.left-align {
  position: relative;
  z-index: 2;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 1.25rem;
  width: 100%;
  max-width: 560px;
}

.brand-dot {
  width: 0.65rem;
  height: 0.65rem;
  border-radius: 50%;
  background: var(--accent);
  box-shadow: 0 0 14px rgba(94, 225, 213, 0.55);
  flex-shrink: 0;
}

.brand-text {
  color: var(--text);
  letter-spacing: -0.02em;
}

.stage-wrap {
  position: relative;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  width: 100%;
  min-height: min(400px, 52vh);
  padding: 0.5rem 0 1.25rem;
}

/* 与登录卡片视觉同一「地平线」：底部弱光带 + 中线提示 */
.stage-rail {
  position: absolute;
  left: 4%;
  right: 4%;
  bottom: 0.35rem;
  height: 2px;
  border-radius: 2px;
  background: linear-gradient(
    90deg,
    transparent 0%,
    rgba(94, 225, 213, 0.12) 20%,
    rgba(94, 225, 213, 0.22) 50%,
    rgba(94, 225, 213, 0.12) 80%,
    transparent 100%
  );
  pointer-events: none;
}

@media (min-width: 1024px) {
  .stage-rail {
    left: 0;
    right: -12px;
    bottom: 0.5rem;
    mask-image: linear-gradient(90deg, black 0%, black 88%, transparent 100%);
  }
}

.left-foot {
  margin: 0;
  font-size: 0.8125rem;
  letter-spacing: 0.04em;
  color: var(--text-muted);
  text-align: center;
}

.right-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  min-height: 100dvh;
  padding: max(2rem, env(safe-area-inset-top, 0px)) max(1.5rem, env(safe-area-inset-right, 0px))
    max(2rem, env(safe-area-inset-bottom, 0px)) max(1.5rem, env(safe-area-inset-left, 0px));
  position: relative;
  z-index: 1;
  background: transparent;
  overflow-x: clip;
  overflow-y: auto;
}

@media (min-width: 1024px) {
  .right-panel {
    padding: max(2rem, env(safe-area-inset-top, 0px)) max(2.25rem, env(safe-area-inset-right, 0px))
      max(2rem, env(safe-area-inset-bottom, 0px)) max(1.75rem, env(safe-area-inset-left, 0px));
  }
}

.right-align {
  width: 100%;
  max-width: 440px;
  display: flex;
  justify-content: center;
}

@media (min-width: 1024px) {
  .right-align {
    align-items: center;
    min-height: 0;
  }
}

.card {
  position: relative;
  width: 100%;
  max-width: 420px;
  min-width: 0;
  padding: clamp(1.25rem, 4vw, 2.35rem) clamp(1rem, 4vw, 2.1rem) clamp(1.25rem, 4vw, 2.15rem);
  border-radius: var(--radius-lg);
  background: var(--bg-panel);
  border: 1px solid var(--stroke);
  box-shadow:
    var(--shadow-glow),
    0 24px 64px rgba(0, 0, 0, 0.45),
    inset 0 1px 0 rgba(255, 255, 255, 0.04);
  backdrop-filter: blur(20px);
}

.card::before {
  content: '';
  position: absolute;
  top: 0;
  left: 1.25rem;
  right: 1.25rem;
  height: 3px;
  border-radius: 0 0 6px 6px;
  background: linear-gradient(90deg, transparent, rgba(94, 225, 213, 0.45), transparent);
  opacity: 0.85;
  pointer-events: none;
}

.eyebrow {
  margin: 0 0 0.5rem;
  font-size: 0.75rem;
  font-weight: 600;
  letter-spacing: 0.2em;
  text-transform: uppercase;
  color: var(--accent);
}

h1 {
  margin: 0 0 0.35rem;
  font-size: 1.75rem;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--text);
}

.sub {
  margin: 0 0 1.75rem;
  font-size: 0.95rem;
  color: var(--text-muted);
  line-height: 1.5;
}

.form {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 0.4rem;
  font-size: 0.85rem;
  color: var(--text-muted);
}

.field input {
  padding: 0.75rem 0.9rem;
  border-radius: var(--radius-md);
  border: 1px solid var(--stroke);
  background: var(--auth-input-bg);
  color: var(--text);
  outline: none;
  transition:
    border-color 0.2s,
    box-shadow 0.2s;
}

.pass-wrap {
  position: relative;
}

.pass-wrap input {
  width: 100%;
  padding-right: 2.75rem;
  box-sizing: border-box;
}

.toggle-pw {
  position: absolute;
  right: 0.65rem;
  top: 50%;
  transform: translateY(-50%);
  border: none;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  padding: 0.25rem;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
}

.toggle-pw:hover {
  color: var(--text);
}

.toggle-pw .ic {
  width: 1.25rem;
  height: 1.25rem;
}

.field input:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-dim);
}

.btn-primary {
  margin-top: 0.25rem;
  padding: 0.85rem 1rem;
  border: none;
  border-radius: var(--radius-md);
  font-weight: 600;
  cursor: pointer;
  color: #061018;
  background: linear-gradient(135deg, #5ee1d5, #4ad4c8);
  transition:
    transform 0.15s,
    filter 0.15s;
}

.btn-primary:hover:not(:disabled) {
  filter: brightness(1.06);
  transform: translateY(-1px);
}

.btn-primary:disabled {
  opacity: 0.65;
  cursor: not-allowed;
}

.btn-guest {
  margin-top: 0.65rem;
  width: 100%;
  padding: 0.75rem 1rem;
  border-radius: var(--radius-md);
  border: 1px solid var(--stroke);
  background: transparent;
  color: var(--text-muted);
  font-size: 0.9rem;
  font-weight: 600;
  cursor: pointer;
  transition:
    color 0.2s ease-out,
    border-color 0.2s ease-out,
    background 0.2s ease-out,
    transform 0.15s;
}

.btn-guest:hover:not(:disabled) {
  color: var(--accent);
  border-color: color-mix(in srgb, var(--accent) 45%, var(--stroke));
  background: var(--accent-dim);
}

.btn-guest:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.guest-hint {
  margin: 0.5rem 0 0;
  padding: 0 0.25rem;
  text-align: center;
  font-size: 0.8125rem;
  line-height: 1.65;
  letter-spacing: 0;
  word-break: normal;
  overflow-wrap: break-word;
  /* 拉丁优先的字体会拉紧中文笔画间距；说明文案强制走中文栈 */
  font-family:
    system-ui,
    'PingFang SC',
    'Hiragino Sans GB',
    'Microsoft YaHei',
    sans-serif;
  color: var(--text-muted);
  opacity: 0.9;
}

.guest-hint-em {
  font-weight: 400;
  color: var(--accent);
  letter-spacing: 0;
}

.error {
  margin: 0;
  font-size: 0.85rem;
  color: var(--danger);
}

.foot {
  margin: 1.25rem 0 0;
  text-align: center;
  font-size: 0.9rem;
}

.foot-links {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 0.35rem 0.75rem;
}

.foot-sep {
  color: var(--text-muted);
  opacity: 0.55;
  user-select: none;
}

.link {
  color: var(--accent);
  text-decoration: none;
  font-weight: 500;
}

.link:hover {
  text-decoration: underline;
}
</style>
