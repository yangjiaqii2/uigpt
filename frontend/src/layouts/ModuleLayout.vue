<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import ChatProfileDrawer from '../components/chat/ChatProfileDrawer.vue'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const ROUTE_TITLE_FALLBACK = {
  history: '历史会话',
  works: '我的作品',
  'studio-works': '作品库',
  knowledge: '知识库',
  prompts: '提示词',
}

const profileOpen = ref(false)
const profileWrapRef = ref(null)

/** 已登录时轮询刷新 /api/me（积分等） */
const ME_POLL_MS = 25_000
let mePollTimer = null

const pointsFormatted = computed(() => {
  const n = Math.trunc(Number(auth.points) || 0)
  return new Intl.NumberFormat('zh-CN').format(Number.isFinite(n) ? n : 0)
})

function clearMePoll() {
  if (mePollTimer != null) {
    clearInterval(mePollTimer)
    mePollTimer = null
  }
}

function scheduleMePoll() {
  clearMePoll()
  if (!auth.isAuthenticated) return
  mePollTimer = window.setInterval(() => {
    void auth.refreshMe()
  }, ME_POLL_MS)
}

function refreshMeIfAuthed() {
  if (auth.isAuthenticated) void auth.refreshMe()
}

const showModuleTopbar = computed(
  () => route.name !== 'chat' && route.name !== 'image-gen',
)

const moduleTopTitle = computed(() => {
  const hit = items.value.find((it) => isActive(it.to))
  if (hit) return hit.label
  const name = route.name != null ? String(route.name) : ''
  if (name && ROUTE_TITLE_FALLBACK[name]) return ROUTE_TITLE_FALLBACK[name]
  return '功能模块'
})

onMounted(() => {
  refreshMeIfAuthed()
  scheduleMePoll()
  document.addEventListener('click', onDocClick)
  document.addEventListener('keydown', onDocKeydown)
  window.addEventListener('focus', refreshMeIfAuthed)
  document.addEventListener('visibilitychange', onVisibilityRefresh)
})

onUnmounted(() => {
  clearMePoll()
  document.removeEventListener('click', onDocClick)
  document.removeEventListener('keydown', onDocKeydown)
  window.removeEventListener('focus', refreshMeIfAuthed)
  document.removeEventListener('visibilitychange', onVisibilityRefresh)
})

function onVisibilityRefresh() {
  if (document.visibilityState === 'visible') refreshMeIfAuthed()
}

const items = computed(() => {
  const chatEntry = { to: '/chat', label: '多模态推理', sub: '对话', icon: '◇', public: true }
  if (!auth.isAuthenticated) return [chatEntry]

  const base = [
    chatEntry,
    { to: '/image-gen', label: '图片生成', sub: '创作台', icon: '▢', public: true },
    { to: '/studio-works', label: '作品库', sub: '图片台', icon: '▣', requiresAuth: true },
    ...(auth.isSuperAdmin
      ? [{ to: '/knowledge', label: '知识库', sub: '接入', icon: '📚', requiresAuth: true }]
      : []),
    { to: '/prompts', label: '提示词', sub: '收藏', icon: '✦', requiresAuth: true },
  ]
  if (auth.isAdmin) {
    base.push({ to: '/admin/users', label: '用户管理', sub: '账号', icon: '👤', requiresAuth: true, requiresAdmin: true })
  }
  return base
})

function isActive(to) {
  return route.path === to || route.path.startsWith(`${to}/`)
}

function toggleProfile(e) {
  e.stopPropagation()
  profileOpen.value = !profileOpen.value
}

function openConversation(id) {
  router.push({ path: '/chat', query: { conversation: String(id) } })
}

function logout() {
  profileOpen.value = false
  auth.logout()
  void router.push('/login')
}

function onDocClick(e) {
  if (!(e.target instanceof Node)) return
  if (
    e.target.closest?.('.pp-shell') ||
    e.target.closest?.('.pp-modal-shell') ||
    e.target.closest?.('.sb-modal-backdrop') ||
    e.target.closest?.('.srl-modal-backdrop') ||
    e.target.closest?.('.sb-ctx-menu') ||
    e.target.closest?.('.srl-ctx') ||
    e.target.closest?.('.delconv-shell') ||
    e.target.closest?.('.prm-modal-backdrop')
  ) {
    return
  }
  const pw = profileWrapRef.value
  if (pw && !pw.contains(e.target)) {
    profileOpen.value = false
  }
}

function onDocKeydown(e) {
  if (e.key !== 'Escape') return
  if (!profileOpen.value) return
  profileOpen.value = false
}

watch(
  () => route.name,
  (n) => {
    if (n === 'chat') profileOpen.value = false
  },
)

watch(
  () => route.fullPath,
  () => {
    refreshMeIfAuthed()
  },
)

watch(
  () => auth.isAuthenticated,
  (ok) => {
    if (ok) {
      void auth.refreshMe()
      scheduleMePoll()
    } else {
      clearMePoll()
    }
  },
)
</script>

<template>
  <div class="mod-shell">
    <aside class="mod-rail" aria-label="功能模块">
      <div class="mod-brand">
        <RouterLink to="/chat" class="mod-brand-link" title="UI GPT">UIG</RouterLink>
      </div>
      <nav class="mod-nav">
        <RouterLink
          v-for="it in items"
          :key="it.to"
          :to="it.to"
          class="mod-nav-item"
          :class="{ 'mod-nav-item--active': isActive(it.to) }"
        >
          <span class="mod-nav-ic" aria-hidden="true">{{ it.icon }}</span>
          <span class="mod-nav-txt">
            <span class="mod-nav-label">{{ it.label }}</span>
            <span class="mod-nav-sub">{{ it.sub }}</span>
          </span>
        </RouterLink>
      </nav>
      <div class="mod-rail-foot">
        <div
          v-if="auth.isAuthenticated"
          class="mod-points-row"
          role="status"
          :aria-label="`积分 ${pointsFormatted}`"
        >
          <span class="mod-points-lab" aria-hidden="true">积分</span>
          <span class="mod-points-num">{{ pointsFormatted }}</span>
        </div>
        <RouterLink v-else to="/login" class="mod-foot-link">登录</RouterLink>
      </div>
    </aside>
    <main class="mod-main">
      <header v-if="showModuleTopbar" class="mod-topbar">
        <span class="mod-topbar-title">{{ moduleTopTitle }}</span>
        <div class="mod-topbar-trailing">
          <div ref="profileWrapRef" class="mod-profile-wrap">
            <button
              type="button"
              class="mod-profile-trigger"
              aria-haspopup="menu"
              :aria-expanded="profileOpen"
              aria-label="个人中心"
              @click="toggleProfile"
            >
              <span class="mod-profile-avatar">{{ auth.isAuthenticated ? auth.username.slice(0, 1).toUpperCase() : '?' }}</span>
              <span class="mod-profile-text">个人中心</span>
            </button>
            <ChatProfileDrawer
              :open="profileOpen"
              :is-authenticated="auth.isAuthenticated"
              :username="auth.username"
              @update:open="profileOpen = $event"
              @logout="logout"
              @open-conversation="openConversation"
            />
          </div>
        </div>
      </header>
      <div class="mod-main-body">
        <RouterView />
      </div>
    </main>
  </div>
</template>

<style scoped>
.mod-shell {
  display: flex;
  height: 100vh;
  width: 100%;
  max-width: 100vw;
  overflow: hidden;
  background: var(--chat-shell-bg, var(--bg-app, #0f1115));
  color: var(--chat-fg, var(--text, #e8eaed));
}

.mod-rail {
  width: 220px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--chat-border, var(--stroke, #2a2f3a));
  background: var(--chat-topbar-bg, var(--bg-panel, #161922));
}

.mod-brand {
  padding: 14px 14px 10px;
}

.mod-brand-link {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border-radius: 12px;
  font-weight: 800;
  font-size: 0.85rem;
  letter-spacing: -0.02em;
  text-decoration: none;
  color: var(--chat-send-fg, #fff);
  background: linear-gradient(135deg, var(--chat-link-accent-fg, #6366f1), #8b5cf6);
}

.mod-nav {
  flex: 1;
  overflow-y: auto;
  padding: 8px 10px 16px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.mod-nav-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 10px;
  text-decoration: none;
  color: inherit;
  border: 1px solid transparent;
  transition:
    background 0.15s,
    border-color 0.15s;
}

.mod-nav-item:hover {
  background: color-mix(in srgb, var(--chat-fg, #fff) 6%, transparent);
}

.mod-nav-item--active {
  background: color-mix(in srgb, var(--chat-link-accent-fg, #6366f1) 18%, transparent);
  border-color: color-mix(in srgb, var(--chat-link-accent-fg, #6366f1) 35%, transparent);
}

.mod-nav-ic {
  font-size: 1rem;
  line-height: 1.2;
  opacity: 0.9;
  width: 1.25rem;
  text-align: center;
}

.mod-nav-txt {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.mod-nav-label {
  font-size: 0.875rem;
  font-weight: 600;
}

.mod-nav-sub {
  font-size: 0.7rem;
  color: var(--chat-muted, #9aa3b2);
}

.mod-rail-foot {
  margin-top: auto;
  padding: 12px;
  border-top: 1px solid var(--chat-border, var(--stroke, #2a2f3a));
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.mod-points-row {
  display: flex;
  align-items: baseline;
  gap: 6px;
  min-width: 0;
  font-size: 0.75rem;
  line-height: 1.2;
  color: var(--chat-muted, #9aa3b2);
}

.mod-points-lab {
  flex-shrink: 0;
  opacity: 0.85;
}

.mod-points-num {
  font-variant-numeric: tabular-nums;
  color: color-mix(in srgb, var(--chat-fg, #e8eaed) 78%, var(--chat-muted, #9aa3b2));
  font-weight: 600;
  letter-spacing: 0.01em;
}

.mod-foot-link {
  font-size: 0.75rem;
  color: var(--chat-muted, #9aa3b2);
  text-decoration: none;
}

.mod-foot-link:hover {
  color: var(--chat-link-accent-fg, #6366f1);
}

.mod-main {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.mod-main > .mod-topbar {
  position: relative;
  z-index: 20;
  flex-shrink: 0;
}

.mod-main-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  display: flex;
  flex-direction: column;
}

.mod-topbar {
  height: 52px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0 max(16px, env(safe-area-inset-right, 0px)) 0 max(16px, env(safe-area-inset-left, 0px));
  border-bottom: 1px solid var(--chat-border, var(--stroke, #2a2f3a));
  background: var(--chat-topbar-bg, var(--bg-panel, #161922));
  backdrop-filter: blur(12px);
}

.mod-topbar-title {
  display: block;
  max-width: min(52vw, 320px);
  margin: 0 auto;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 0.9375rem;
  font-weight: 650;
  color: var(--chat-fg-strong, var(--text, #e8eaed));
  letter-spacing: 0.02em;
}

.mod-topbar-trailing {
  position: absolute;
  right: 12px;
  top: 50%;
  transform: translateY(-50%);
  display: flex;
  align-items: center;
  gap: 8px;
}

.mod-profile-wrap {
  position: relative;
}

.mod-profile-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px 6px 6px;
  border-radius: 999px;
  border: 1px solid var(--chat-profile-border, var(--stroke, #2a2f3a));
  background: var(--chat-profile-bg, var(--bg-panel, #1a1d26));
  color: var(--chat-fg, var(--text, #e8eaed));
  font-size: 0.8125rem;
  cursor: pointer;
  transition:
    background 0.15s,
    border-color 0.15s;
}

.mod-profile-trigger:hover {
  background: var(--chat-btn-bg-hover, color-mix(in srgb, var(--chat-fg, #fff) 10%, transparent));
  border-color: var(--chat-border-strong, var(--stroke, #3d4454));
}

.mod-profile-avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: linear-gradient(
    135deg,
    var(--chat-avatar-gradient-start, #6366f1),
    var(--chat-avatar-gradient-end, #8b5cf6)
  );
  color: var(--chat-send-fg, #fff);
  font-weight: 700;
  font-size: 0.75rem;
  display: flex;
  align-items: center;
  justify-content: center;
}

.mod-profile-text {
  padding-right: 4px;
}

@media (max-width: 768px) {
  .mod-profile-text {
    display: none;
  }

  .mod-topbar {
    height: auto;
    min-height: calc(52px + env(safe-area-inset-top, 0px));
    padding-top: env(safe-area-inset-top, 0px);
  }
}

@media (max-width: 720px) {
  .mod-rail {
    width: 72px;
  }
  .mod-nav-txt,
  .mod-brand-link {
    display: none;
  }
  .mod-brand {
    display: flex;
    justify-content: center;
  }
  .mod-nav-item {
    justify-content: center;
    padding: 10px 8px;
  }

  .mod-rail-foot {
    flex-direction: column;
    align-items: center;
    padding: 10px 6px;
    gap: 6px;
  }

  .mod-points-row {
    flex-direction: column;
    align-items: center;
    gap: 2px;
    font-size: 0.6875rem;
    text-align: center;
  }

  .mod-points-lab {
    font-size: 0.625rem;
    opacity: 0.75;
  }
}
</style>
