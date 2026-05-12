<script setup>
/**
 * 个人中心：右侧玻璃抽屉 + 密码模态框（深色 AI 创作工具风格）
 */
import { ref, watch, computed } from 'vue'
import { RouterLink } from 'vue-router'
import { useAuthStore } from '../../stores/auth'
import { fetchMeStats } from '../../api/meProfile'
import { changePassword as apiChangePassword } from '../../api/auth'
import { getAxiosErrorMessage } from '../../utils/httpError'
import LegalGlassModal from '../legal/LegalGlassModal.vue'
import { useSiteMailStore } from '../../stores/siteMail'

const siteMailStore = useSiteMailStore()

/** 构建时注入：package.json 的 version，或环境变量 VITE_APP_VERSION（如 CI / Docker 传镜像 tag） */
const displayAppVersion = computed(() => {
  const raw = String(typeof __APP_VERSION__ !== 'undefined' ? __APP_VERSION__ : '0.0.0')
  return raw.startsWith('v') ? raw : `v${raw}`
})

const legalModal = ref(null)

const auth = useAuthStore()

const props = defineProps({
  open: { type: Boolean, default: false },
  isAuthenticated: { type: Boolean, default: false },
  username: { type: String, default: '' },
  isAdmin: { type: Boolean, default: false },
})

const emit = defineEmits(['update:open', 'logout', 'open-conversation'])

const stats = ref({ generatedImageCount: 0, conversationCount: 0, favoriteImageCount: 0 })
const loadingDash = ref(false)

const pwdModalOpen = ref(false)
const pwdOld = ref('')
const pwdNew = ref('')
const pwdConfirm = ref('')
const pwdError = ref('')
const pwdOk = ref('')
const pwdSubmitting = ref(false)

function close() {
  emit('update:open', false)
  pwdModalOpen.value = false
  legalModal.value = null
}

function openPwdModal() {
  pwdModalOpen.value = true
  pwdError.value = ''
  pwdOk.value = ''
}

function openLegal(kind) {
  legalModal.value = kind
}

function closeLegal() {
  legalModal.value = null
}

function openSiteMailCompose() {
  siteMailStore.openCompose()
  close()
}

function closePwdModal() {
  pwdModalOpen.value = false
  pwdOld.value = ''
  pwdNew.value = ''
  pwdConfirm.value = ''
  pwdError.value = ''
  pwdOk.value = ''
}

async function loadDashboard() {
  if (!props.isAuthenticated) return
  loadingDash.value = true
  try {
    const st = await fetchMeStats()
    stats.value = st.data
  } catch {
    stats.value = { generatedImageCount: 0, conversationCount: 0, favoriteImageCount: 0 }
  } finally {
    loadingDash.value = false
  }
}

watch(
  () => props.open,
  (o) => {
    if (o && props.isAuthenticated) {
      loadDashboard()
    }
  },
)

async function submitPasswordChange() {
  pwdError.value = ''
  pwdOk.value = ''
  if (pwdNew.value !== pwdConfirm.value) {
    pwdError.value = '两次输入的新密码不一致'
    return
  }
  if (pwdNew.value.length < 8) {
    pwdError.value = '新密码至少 8 位'
    return
  }
  pwdSubmitting.value = true
  try {
    await apiChangePassword({ oldPassword: pwdOld.value, newPassword: pwdNew.value })
    pwdOk.value = '密码已更新'
    pwdOld.value = ''
    pwdNew.value = ''
    pwdConfirm.value = ''
    window.setTimeout(() => {
      closePwdModal()
    }, 900)
  } catch (e) {
    pwdError.value = getAxiosErrorMessage(e)
  } finally {
    pwdSubmitting.value = false
  }
}

defineExpose({
  refreshDashboard: loadDashboard,
})
</script>

<template>
  <Teleport to="body">
    <Transition name="pp-drw">
      <div v-if="open" class="pp-shell" aria-hidden="false">
        <div class="pp-backdrop" @click="close" />
        <aside class="pp-drawer" @click.stop>
          <div class="pp-drawer-glass">
            <button type="button" class="pp-close" aria-label="关闭" @click="close">×</button>

            <div class="pp-scroll">
              <!-- 用户信息 -->
              <section class="pp-section pp-stagger">
                <div class="pp-user-block">
                  <div class="pp-avatar-wrap">
                    <div class="pp-avatar">
                      {{ isAuthenticated && username ? username.slice(0, 1).toUpperCase() : '?' }}
                    </div>
                    <div class="pp-avatar-hover" aria-hidden="true">
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M23 19a2 2 0 01-2 2H3a2 2 0 01-2-2V8a2 2 0 012-2h4l2-3h6l2 3h4a2 2 0 012 2z" />
                        <circle cx="12" cy="13" r="4" />
                      </svg>
                    </div>
                  </div>
                  <div class="pp-user-meta">
                    <div class="pp-user-name-row">
                      <span class="pp-user-name">{{ isAuthenticated ? username : '访客' }}</span>
                      <span v-if="isAuthenticated" class="pp-online-pill">
                        <span class="pp-online-dot" />
                        在线
                      </span>
                    </div>
                    <p v-if="!isAuthenticated" class="pp-guest-hint">登录后可同步作品</p>
                    <p v-else class="pp-points-hint" title="自然日按上海时区 Asia/Shanghai 重置；与角色日上限及管理员附加项有关">
                      积分余额 {{ auth.points }}
                    </p>
                  </div>
                </div>

                <div v-if="isAuthenticated" class="pp-stats-row">
                  <div class="pp-stat-card">
                    <span class="pp-stat-num">{{ loadingDash ? '…' : stats.generatedImageCount }}</span>
                    <span class="pp-stat-label">生成作品</span>
                  </div>
                  <div class="pp-stat-card">
                    <span class="pp-stat-num">{{ loadingDash ? '…' : stats.conversationCount }}</span>
                    <span class="pp-stat-label">对话次数</span>
                  </div>
                  <div class="pp-stat-card">
                    <span class="pp-stat-num">{{ loadingDash ? '…' : stats.favoriteImageCount }}</span>
                    <span class="pp-stat-label">收藏</span>
                  </div>
                </div>
              </section>

              <div class="pp-divider" />

              <!-- 菜单 -->
              <section class="pp-section pp-stagger pp-menu">
                <template v-if="isAuthenticated">
                  <button type="button" class="pp-menu-row" @click="openPwdModal">
                    <span class="pp-menu-ic" aria-hidden="true">
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                        <rect x="5" y="11" width="14" height="10" rx="2" />
                        <path d="M12 16v2M9 11V8a3 3 0 016 0v3" stroke-linecap="round" />
                      </svg>
                    </span>
                    <span class="pp-menu-txt">修改密码</span>
                    <span class="pp-menu-chev">›</span>
                  </button>

                  <button
                    v-if="!isAdmin"
                    type="button"
                    class="pp-menu-row"
                    @click="openSiteMailCompose"
                  >
                    <span class="pp-menu-ic" aria-hidden="true">
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                        <path d="M4 4h16v12H4V4z" stroke-linejoin="round" />
                        <path d="m22 16-4 4v-4h4z" stroke-linejoin="round" />
                        <path d="M8 9h8M8 12h5" stroke-linecap="round" />
                      </svg>
                    </span>
                    <span class="pp-menu-txt">联系管理员</span>
                    <span class="pp-menu-chev">›</span>
                  </button>

                  <button type="button" class="pp-menu-row pp-menu-row--danger" @click="emit('logout'); close()">
                    <span class="pp-menu-ic pp-menu-ic--danger" aria-hidden="true">
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
                        <path d="M9 21H5a2 2 0 01-2-2V5a2 2 0 012-2h4M16 17l5-5-5-5M21 12H9" stroke-linecap="round" stroke-linejoin="round" />
                      </svg>
                    </span>
                    <span class="pp-menu-txt">退出登录</span>
                    <span class="pp-menu-chev">›</span>
                  </button>
                </template>
                <template v-else>
                  <RouterLink
                    class="pp-menu-row pp-menu-row--link"
                    :to="{ path: '/login', query: { redirect: '/' } }"
                    @click="close"
                  >
                    <span class="pp-menu-ic" aria-hidden="true">→</span>
                    <span class="pp-menu-txt">登录</span>
                    <span class="pp-menu-chev">›</span>
                  </RouterLink>
                  <RouterLink class="pp-menu-row pp-menu-row--link" to="/register" @click="close">
                    <span class="pp-menu-ic" aria-hidden="true">◇</span>
                    <span class="pp-menu-txt">注册账号</span>
                    <span class="pp-menu-chev">›</span>
                  </RouterLink>
                </template>
              </section>

              <footer class="pp-footer">
                <span class="pp-ver pp-ver-tag" :title="`当前版本 ${displayAppVersion}`">{{ displayAppVersion }}</span>
                <span class="pp-footer-links">
                  <button type="button" class="pp-footer-a" @click="openLegal('privacy')">隐私政策</button>
                  <span class="pp-footer-dot">·</span>
                  <button type="button" class="pp-footer-a" @click="openLegal('terms')">用户协议</button>
                </span>
              </footer>
            </div>
          </div>
        </aside>
      </div>
    </Transition>
  </Teleport>

  <Teleport to="body">
    <Transition name="pp-modal">
      <div v-if="pwdModalOpen" class="pp-modal-shell">
        <div class="pp-modal-bg" @click="closePwdModal" />
        <div class="pp-modal" role="dialog" aria-modal="true" aria-labelledby="pp-pwd-title" @click.stop>
          <h2 id="pp-pwd-title" class="pp-modal-title">修改密码</h2>
          <p v-if="pwdOk" class="pp-modal-ok">{{ pwdOk }}</p>
          <p v-if="pwdError" class="pp-modal-err">{{ pwdError }}</p>
          <label class="pp-field">
            <span class="pp-field-lab">
              <svg class="pp-field-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M12 11c1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3 1.34 3 3 3z" />
                <path d="M4 21v-2a4 4 0 014-4h8a4 4 0 014 4v2" />
              </svg>
              当前密码
            </span>
            <input v-model="pwdOld" type="password" class="pp-input" autocomplete="current-password" :disabled="pwdSubmitting" />
          </label>
          <label class="pp-field">
            <span class="pp-field-lab">
              <svg class="pp-field-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <rect x="5" y="11" width="14" height="10" rx="2" />
                <path d="M12 16v2M9 11V8a3 3 0 016 0v3" stroke-linecap="round" />
              </svg>
              新密码
            </span>
            <input v-model="pwdNew" type="password" class="pp-input" autocomplete="new-password" :disabled="pwdSubmitting" />
          </label>
          <label class="pp-field">
            <span class="pp-field-lab">
              <svg class="pp-field-ic" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M9 12l2 2 4-4m5 2a9 9 0 11-18 0 9 9 0 0118 0z" stroke-linecap="round" />
              </svg>
              确认新密码
            </span>
            <input v-model="pwdConfirm" type="password" class="pp-input" autocomplete="new-password" :disabled="pwdSubmitting" />
          </label>
          <div class="pp-modal-actions">
            <button type="button" class="pp-btn-submit" :disabled="pwdSubmitting" @click="submitPasswordChange">
              {{ pwdSubmitting ? '保存中…' : '确认修改' }}
            </button>
            <button type="button" class="pp-btn-cancel" :disabled="pwdSubmitting" @click="closePwdModal">取消</button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>

  <LegalGlassModal
    :open="legalModal !== null"
    :kind="legalModal"
    @update:open="(v) => { if (!v) closeLegal() }"
  />
</template>

<style scoped>
.pp-shell {
  position: fixed;
  inset: 0;
  z-index: 12000;
  pointer-events: auto;
}

.pp-backdrop {
  position: absolute;
  inset: 0;
  background: var(--chat-backdrop);
}

.pp-drawer {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: min(
    400px,
    calc(100vw - env(safe-area-inset-left, 0px) - env(safe-area-inset-right, 0px) - 16px)
  );
  min-width: 0;
  max-width: 100%;
  display: flex;
  flex-direction: column;
  padding: 0;
}

.pp-drawer-glass {
  flex: 1;
  margin: max(12px, env(safe-area-inset-top, 0px)) max(12px, env(safe-area-inset-right, 0px))
    max(12px, env(safe-area-inset-bottom, 0px)) 0;
  border-radius: 20px 0 0 20px;
  background: color-mix(in srgb, var(--chat-panel) 90%, transparent);
  backdrop-filter: blur(24px);
  -webkit-backdrop-filter: blur(24px);
  border: 1px solid var(--chat-border);
  border-right: none;
  box-shadow: var(--chat-drawer-shadow), inset 0 1px 0 var(--chat-toolbar-divider);
  position: relative;
  overflow: hidden;
}

.pp-close {
  position: absolute;
  top: 14px;
  right: 14px;
  z-index: 3;
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 10px;
  background: var(--chat-profile-bg);
  color: var(--chat-muted);
  font-size: 1.25rem;
  cursor: pointer;
  transition:
    background 0.2s,
    color 0.2s,
    transform 0.15s;
}

.pp-close:hover {
  background: var(--chat-btn-bg-hover);
  color: var(--chat-fg-strong);
}

.pp-close:active {
  transform: scale(0.96);
}

.pp-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 52px 20px 20px;
  scrollbar-width: thin;
  scrollbar-color: color-mix(in srgb, var(--chat-muted) 35%, transparent) transparent;
}

.pp-scroll::-webkit-scrollbar {
  width: 4px;
}
.pp-scroll::-webkit-scrollbar-thumb {
  background: color-mix(in srgb, var(--chat-muted) 38%, transparent);
  border-radius: 99px;
}

.pp-section {
  margin-bottom: 4px;
}

.pp-stagger > * {
  animation: pp-in 0.45s ease-out both;
}
.pp-stagger > *:nth-child(1) {
  animation-delay: 0.05s;
}
.pp-stagger > *:nth-child(2) {
  animation-delay: 0.1s;
}
.pp-stagger > *:nth-child(3) {
  animation-delay: 0.15s;
}

@keyframes pp-in {
  from {
    opacity: 0;
    transform: translateY(8px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.pp-user-block {
  display: flex;
  gap: 14px;
  align-items: flex-start;
}

.pp-avatar-wrap {
  position: relative;
  flex-shrink: 0;
}

.pp-avatar {
  width: 60px;
  height: 60px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.35rem;
  font-weight: 800;
  color: var(--chat-send-fg);
  background: linear-gradient(
    145deg,
    var(--chat-avatar-gradient-start),
    var(--chat-avatar-gradient-end)
  );
  box-shadow: 0 8px 28px color-mix(in srgb, var(--chat-avatar-gradient-end) 35%, transparent);
  transition: transform 0.25s cubic-bezier(0.34, 1.45, 0.64, 1);
}

.pp-avatar-hover {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0;
  transition: opacity 0.22s ease;
  pointer-events: none;
  color: var(--chat-fg-strong);
}

.pp-avatar-hover svg {
  width: 22px;
  height: 22px;
}

.pp-avatar-wrap:hover .pp-avatar {
  transform: scale(1.04);
}

.pp-avatar-wrap:hover .pp-avatar-hover {
  opacity: 1;
}

.pp-user-meta {
  flex: 1;
  min-width: 0;
  padding-top: 4px;
}

.pp-user-name-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.pp-user-name {
  font-size: 1.0625rem;
  font-weight: 700;
  color: var(--chat-fg-strong);
  letter-spacing: -0.02em;
}

.pp-online-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 0.6875rem;
  font-weight: 600;
  color: rgba(167, 243, 208, 0.95);
  background: rgba(52, 211, 153, 0.12);
  border: 1px solid rgba(52, 211, 153, 0.22);
}

.pp-online-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #34d399;
  box-shadow: 0 0 8px rgba(52, 211, 153, 0.8);
}

.pp-guest-hint {
  margin: 8px 0 0;
  font-size: 0.75rem;
  color: var(--chat-muted-3);
}

.pp-points-hint {
  margin: 8px 0 0;
  font-size: 0.75rem;
  color: var(--chat-muted-2);
}

.pp-stats-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin-top: 18px;
}

.pp-stat-card {
  padding: 12px 8px;
  border-radius: 14px;
  background: var(--chat-profile-bg);
  border: 1px solid var(--chat-border);
  text-align: center;
  transition:
    background 0.2s,
    border-color 0.2s,
    box-shadow 0.2s;
}

.pp-stat-card:hover {
  background: var(--chat-btn-bg-hover);
  border-color: var(--chat-border-strong);
  box-shadow: var(--chat-panel-shadow);
}

.pp-stat-num {
  display: block;
  font-size: 1.125rem;
  font-weight: 800;
  color: var(--chat-fg-strong);
  letter-spacing: -0.02em;
}

.pp-stat-label {
  display: block;
  margin-top: 4px;
  font-size: 0.625rem;
  color: var(--chat-muted-3);
  letter-spacing: 0.04em;
}

.pp-divider {
  height: 1px;
  margin: 18px 0;
  background: var(--chat-toolbar-divider);
}

.pp-menu-row {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 12px;
  height: 50px;
  padding: 0 14px;
  margin-bottom: 6px;
  border: none;
  border-radius: 12px;
  background: transparent;
  color: var(--chat-fg);
  font-size: 0.875rem;
  text-align: left;
  cursor: pointer;
  transition:
    background 0.2s ease,
    color 0.2s ease,
    transform 0.12s ease;
  text-decoration: none;
  box-sizing: border-box;
}

.pp-menu-row--link {
  display: flex;
}

.pp-menu-row:hover {
  background: var(--chat-btn-bg-hover);
  color: var(--chat-fg-strong);
}

.pp-menu-row:active {
  transform: scale(0.98);
}

.pp-menu-row:hover .pp-menu-chev {
  transform: translateX(4px);
}

.pp-menu-row--danger {
  color: var(--chat-danger-fg);
}

.pp-menu-row--danger:hover {
  background: var(--chat-danger-bg);
  color: var(--chat-danger-fg);
}

.pp-menu-ic {
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--chat-muted-3);
  flex-shrink: 0;
}

.pp-menu-ic svg {
  width: 20px;
  height: 20px;
}

.pp-menu-row:hover .pp-menu-ic {
  color: var(--chat-muted);
}

.pp-menu-ic--danger {
  color: color-mix(in srgb, var(--chat-danger-fg) 75%, transparent);
}

.pp-menu-txt {
  flex: 1;
}

.pp-menu-chev {
  font-size: 1.1rem;
  color: var(--chat-muted-4);
  transition: transform 0.2s ease;
}

.pp-footer {
  margin-top: 28px;
  padding-top: 16px;
  border-top: 1px solid var(--chat-toolbar-divider);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.pp-ver {
  font-size: 0.6875rem;
  color: var(--chat-muted-4);
}

.pp-ver-tag {
  display: inline-flex;
  align-items: center;
  padding: 0.2rem 0.55rem;
  border-radius: 999px;
  font-size: 0.625rem;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--chat-muted-2);
  background: color-mix(in srgb, var(--chat-profile-bg) 88%, transparent);
  border: 1px solid var(--chat-toolbar-divider);
  box-shadow: 0 1px 0 rgba(255, 255, 255, 0.04) inset;
}

.pp-footer-links {
  font-size: 0.6875rem;
  color: var(--chat-muted-4);
}

.pp-footer-a {
  padding: 0;
  border: none;
  background: none;
  font: inherit;
  cursor: pointer;
  color: inherit;
  text-decoration: none;
  transition: color 0.15s;
}

.pp-footer-a:hover {
  color: var(--chat-muted-2);
  text-decoration: underline;
  text-underline-offset: 2px;
}

.pp-footer-dot {
  margin: 0 6px;
  opacity: 0.5;
}

/* 抽屉进场 */
.pp-drw-enter-active,
.pp-drw-leave-active {
  transition: opacity 0.28s ease;
}
.pp-drw-enter-active .pp-drawer,
.pp-drw-leave-active .pp-drawer {
  transition: transform 0.32s ease-out;
}
.pp-drw-enter-from,
.pp-drw-leave-to {
  opacity: 0;
}
.pp-drw-enter-from .pp-drawer,
.pp-drw-leave-to .pp-drawer {
  transform: translateX(100%);
}

/* 密码模态 */
.pp-modal-shell {
  position: fixed;
  inset: 0;
  z-index: 13000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: max(24px, env(safe-area-inset-top, 0px)) max(24px, env(safe-area-inset-right, 0px))
    max(24px, env(safe-area-inset-bottom, 0px)) max(24px, env(safe-area-inset-left, 0px));
  box-sizing: border-box;
}

.pp-modal-bg {
  position: absolute;
  inset: 0;
  background: var(--chat-backdrop);
  backdrop-filter: blur(8px);
}

.pp-modal {
  position: relative;
  width: 100%;
  max-width: 400px;
  min-width: 0;
  padding: 26px 22px 22px;
  border-radius: 20px;
  background: color-mix(in srgb, var(--chat-panel) 94%, transparent);
  backdrop-filter: blur(20px);
  border: 1px solid var(--chat-border-strong);
  box-shadow: var(--chat-panel-shadow);
}

.pp-modal-enter-active,
.pp-modal-leave-active {
  transition: opacity 0.22s ease;
}
.pp-modal-enter-active .pp-modal,
.pp-modal-leave-active .pp-modal {
  transition:
    transform 0.28s cubic-bezier(0.34, 1.45, 0.64, 1),
    opacity 0.28s ease;
}
.pp-modal-enter-from,
.pp-modal-leave-to {
  opacity: 0;
}
.pp-modal-enter-from .pp-modal,
.pp-modal-leave-to .pp-modal {
  opacity: 0;
  transform: scale(0.95);
}

.pp-modal-title {
  margin: 0 0 16px;
  font-size: 1.125rem;
  font-weight: 700;
  color: var(--chat-fg-strong);
}

.pp-modal-ok {
  margin: 0 0 10px;
  font-size: 0.78rem;
  color: var(--chat-link-accent-fg);
}

.pp-modal-err {
  margin: 0 0 10px;
  font-size: 0.78rem;
  color: var(--chat-danger-fg);
}

.pp-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 14px;
}

.pp-field-lab {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 0.6875rem;
  color: var(--chat-muted-2);
}

.pp-field-ic {
  width: 16px;
  height: 16px;
  opacity: 0.55;
}

.pp-input {
  padding: 12px 14px;
  border-radius: 14px;
  border: 1px solid var(--chat-border-strong);
  background: var(--auth-input-bg);
  color: var(--chat-input-fg);
  font-size: 0.875rem;
  outline: none;
  transition:
    border-color 0.2s,
    box-shadow 0.2s;
}

.pp-input:focus {
  border-color: color-mix(in srgb, var(--chat-link-accent-fg) 55%, transparent);
  box-shadow: 0 0 0 3px var(--chat-link-accent-bg);
}

.pp-modal-actions {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 8px;
}

.pp-btn-submit {
  padding: 12px 16px;
  border: none;
  border-radius: 14px;
  font-weight: 700;
  font-size: 0.875rem;
  color: var(--chat-send-fg);
  background: linear-gradient(
    135deg,
    var(--chat-send-bg-start),
    var(--chat-send-bg-end)
  );
  cursor: pointer;
  transition:
    filter 0.15s,
    transform 0.12s;
}

.pp-btn-submit:hover:not(:disabled) {
  filter: brightness(1.05);
}

.pp-btn-submit:active:not(:disabled) {
  transform: scale(0.98);
}

.pp-btn-submit:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.pp-btn-cancel {
  padding: 8px;
  border: none;
  background: none;
  color: var(--chat-muted);
  font-size: 0.8125rem;
  cursor: pointer;
}
.pp-btn-cancel:hover {
  color: var(--chat-fg-strong);
}
</style>
