<script setup>
import { ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import AnimatedCharacters from '../components/login/AnimatedCharacters.vue'
import { forgotPasswordReset } from '../api/auth'
import { getAxiosErrorMessage } from '../utils/httpError'

const router = useRouter()

const realName = ref('')
const phone = ref('')
const registeredDate = ref('')
const newPassword = ref('')
const confirmNewPassword = ref('')
const error = ref('')
const loading = ref(false)
const success = ref(false)

const CN_PHONE = /^1[3-9]\d{9}$/

async function onSubmit() {
  error.value = ''
  if (!realName.value.trim()) {
    error.value = '请填写姓名'
    return
  }
  if (!CN_PHONE.test(phone.value.trim())) {
    error.value = '请输入有效的 11 位手机号'
    return
  }
  if (!registeredDate.value) {
    error.value = '请选择注册日期'
    return
  }
  if (newPassword.value.length < 8) {
    error.value = '新密码至少 8 位'
    return
  }
  if (newPassword.value !== confirmNewPassword.value) {
    error.value = '两次输入的新密码不一致'
    return
  }

  loading.value = true
  try {
    await forgotPasswordReset({
      realName: realName.value.trim(),
      phone: phone.value.trim(),
      registeredDate: registeredDate.value,
      newPassword: newPassword.value,
      confirmNewPassword: confirmNewPassword.value,
    })
    success.value = true
    window.setTimeout(() => router.replace('/login'), 1600)
  } catch (e) {
    error.value = getAxiosErrorMessage(e)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="shell">
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

    <aside class="left-panel" aria-hidden="false">
      <div class="left-align">
        <div class="stage-wrap">
          <div class="stage-rail" aria-hidden="true" />
          <AnimatedCharacters :is-typing="false" :show-password="false" :password-length="0" />
        </div>
      </div>
    </aside>

    <div class="right-panel">
      <div class="right-align">
        <main class="card">
          <p class="eyebrow">UI GPT</p>
          <h1>忘记密码</h1>
          <p class="sub">
            请填写注册时的<strong>姓名</strong>、<strong>手机号</strong>与<strong>注册日期</strong>（年月日，须与系统记录一致），验证通过后可设置新密码。
          </p>

          <div v-if="success" class="ok-banner" role="status">密码已重置，即将跳转登录…</div>

          <form v-else class="form" @submit.prevent="onSubmit">
            <label class="field">
              <span>姓名</span>
              <input v-model="realName" type="text" autocomplete="name" maxlength="64" required placeholder="与注册时一致" />
            </label>
            <label class="field">
              <span>手机号</span>
              <input
                v-model="phone"
                type="tel"
                autocomplete="tel"
                maxlength="11"
                inputmode="numeric"
                required
                placeholder="11 位手机号"
              />
            </label>
            <label class="field">
              <span>注册日期</span>
              <input v-model="registeredDate" type="date" required class="input-date" />
              <span class="field-hint">精确到年月日，须与账号创建日期一致</span>
            </label>
            <label class="field">
              <span>新密码</span>
              <input
                v-model="newPassword"
                type="password"
                autocomplete="new-password"
                required
                minlength="8"
                maxlength="72"
                placeholder="至少 8 位"
              />
            </label>
            <label class="field">
              <span>确认新密码</span>
              <input
                v-model="confirmNewPassword"
                type="password"
                autocomplete="new-password"
                required
                minlength="8"
                maxlength="72"
                placeholder="再次输入新密码"
              />
            </label>

            <p v-if="error" class="error">{{ error }}</p>
            <button type="submit" class="btn-primary" :disabled="loading">
              {{ loading ? '提交中…' : '重置密码' }}
            </button>
          </form>

          <p class="foot">
            <RouterLink to="/login" class="link">返回登录</RouterLink>
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
  overflow-x: clip;
  overflow-y: auto;
}

.right-align {
  width: 100%;
  max-width: 440px;
  display: flex;
  justify-content: center;
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
  box-shadow: var(--shadow-glow), 0 24px 64px rgba(0, 0, 0, 0.45), inset 0 1px 0 rgba(255, 255, 255, 0.04);
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
  margin: 0 0 1.5rem;
  font-size: 0.9rem;
  color: var(--text-muted);
  line-height: 1.55;
}

.sub strong {
  color: var(--text);
  font-weight: 600;
}

.ok-banner {
  margin: 0 0 1rem;
  padding: 0.85rem 1rem;
  border-radius: var(--radius-md);
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--accent);
  background: var(--accent-dim);
  border: 1px solid color-mix(in srgb, var(--accent) 35%, transparent);
}

.form {
  display: flex;
  flex-direction: column;
  gap: 0.95rem;
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

.field input:focus {
  border-color: var(--accent);
  box-shadow: 0 0 0 3px var(--accent-dim);
}

.input-date {
  min-height: 2.75rem;
}

.field-hint {
  font-size: 0.72rem;
  line-height: 1.35;
  color: var(--text-muted);
  opacity: 0.9;
}

.btn-primary {
  margin-top: 0.35rem;
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

.link {
  color: var(--accent);
  text-decoration: none;
  font-weight: 500;
}

.link:hover {
  text-decoration: underline;
}
</style>
