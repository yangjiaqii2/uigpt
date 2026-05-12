<script setup>
/**
 * 普通用户：右上角站内信图标 + 下拉列表（与管理员会话）
 */
import { ref, watch, onMounted, onUnmounted } from 'vue'
import { storeToRefs } from 'pinia'
import { useAuthStore } from '../../stores/auth'
import { useSiteMailStore } from '../../stores/siteMail'
import { fetchSiteMailThread } from '../../api/siteMail'

const auth = useAuthStore()
const siteMail = useSiteMailStore()
const { unreadCount } = storeToRefs(siteMail)

const rootRef = ref(null)
const open = ref(false)
const loading = ref(false)
/** @type {import('vue').Ref<{ threadId: number | null, messages: Array<Record<string, unknown>> } | null>} */
const thread = ref(null)

let pollTimer = 0

async function loadThread() {
  if (!auth.isAuthenticated) return
  loading.value = true
  try {
    const { data } = await fetchSiteMailThread()
    thread.value = data
    await siteMail.refreshSummary()
  } catch {
    thread.value = { threadId: null, messages: [] }
  } finally {
    loading.value = false
  }
}

function toggle(e) {
  e.stopPropagation()
  open.value = !open.value
}

function close() {
  open.value = false
}

watch(open, (o) => {
  if (o) void loadThread()
})

function onDocClick(e) {
  if (!(e.target instanceof Node)) return
  const root = rootRef.value
  if (root && root.contains(e.target)) return
  close()
}

onMounted(() => {
  document.addEventListener('click', onDocClick)
  if (auth.isAuthenticated && !auth.isAdmin) {
    void siteMail.refreshSummary()
    pollTimer = window.setInterval(() => {
      if (auth.isAuthenticated && !auth.isAdmin) void siteMail.refreshSummary()
    }, 30_000)
  }
})

onUnmounted(() => {
  document.removeEventListener('click', onDocClick)
  if (pollTimer) window.clearInterval(pollTimer)
})

watch(
  () => auth.isAuthenticated,
  (ok) => {
    if (ok && !auth.isAdmin) void siteMail.refreshSummary()
    else if (!ok) {
      siteMail.reset()
      close()
    }
  },
)

function fmtTime(v) {
  if (v == null || v === '') return ''
  return String(v).replace('T', ' ').slice(0, 16)
}
</script>

<template>
  <div v-if="auth.isAuthenticated && !auth.isAdmin" ref="rootRef" class="site-mail-wrap">
    <button
      type="button"
      class="site-mail-bell"
      :class="{ 'site-mail-bell--open': open }"
      aria-label="站内信"
      :aria-expanded="open"
      @click="toggle"
    >
      <svg class="site-mail-bell-svg" viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2">
        <path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9" stroke-linecap="round" stroke-linejoin="round" />
        <path d="M13.73 21a2 2 0 0 1-3.46 0" stroke-linecap="round" />
      </svg>
      <span v-if="unreadCount > 0" class="site-mail-badge" aria-hidden="true">{{ unreadCount > 99 ? '99+' : unreadCount }}</span>
    </button>

    <Transition name="site-mail-pop">
      <div v-if="open" class="site-mail-pop" role="region" aria-label="站内信列表">
        <header class="site-mail-pop-head">
          <span class="site-mail-pop-title">与管理员</span>
          <button type="button" class="site-mail-pop-close" @click="close">收起</button>
        </header>
        <div class="site-mail-pop-body">
          <p v-if="loading" class="site-mail-pop-loading">加载中…</p>
          <template v-else>
            <p v-if="!thread?.messages?.length" class="site-mail-pop-empty">暂无消息。可在个人中心「联系管理员」发送站内信。</p>
            <div v-else class="site-mail-thread">
              <div
                v-for="m in thread.messages"
                :key="m.id"
                class="site-mail-msg"
                :class="{ 'site-mail-msg--self': m.fromSelf, 'site-mail-msg--admin': !m.fromSelf }"
              >
                <div class="site-mail-msg-meta">
                  <span class="site-mail-msg-who">{{ m.fromSelf ? '我' : '管理员' }}</span>
                  <span class="site-mail-msg-time">{{ fmtTime(m.createdAt) }}</span>
                </div>
                <div v-if="m.imageUrls?.length" class="site-mail-msg-imgs">
                  <a v-for="(url, i) in m.imageUrls" :key="i" :href="url" target="_blank" rel="noopener noreferrer">
                    <img :src="url" alt="" class="site-mail-msg-img" />
                  </a>
                </div>
                <p class="site-mail-msg-body">{{ (m.body || '').trim() }}</p>
              </div>
            </div>
          </template>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.site-mail-wrap {
  position: relative;
  flex-shrink: 0;
}

.site-mail-bell {
  position: relative;
  width: 40px;
  height: 40px;
  border-radius: 12px;
  border: 1px solid var(--chat-profile-border, rgba(255, 255, 255, 0.1));
  background: var(--chat-profile-bg, rgba(255, 255, 255, 0.05));
  color: var(--chat-muted, #9aa3b2);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition:
    background 0.2s,
    color 0.2s,
    border-color 0.2s;
}

.site-mail-bell:hover,
.site-mail-bell--open {
  color: var(--chat-fg-strong, #e8eaed);
  border-color: color-mix(in srgb, var(--chat-link-accent-fg, #6366f1) 35%, transparent);
}

.site-mail-badge {
  position: absolute;
  top: -4px;
  right: -4px;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: 999px;
  font-size: 0.65rem;
  font-weight: 800;
  line-height: 18px;
  text-align: center;
  background: #ef4444;
  color: #fff;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.35);
}

.site-mail-pop {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  width: min(360px, calc(100vw - 24px));
  max-height: min(70vh, 480px);
  display: flex;
  flex-direction: column;
  border-radius: 16px;
  border: 1px solid var(--chat-border, rgba(255, 255, 255, 0.1));
  background: color-mix(in srgb, var(--chat-panel, #1a1d26) 92%, transparent);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  box-shadow: 0 20px 50px rgba(0, 0, 0, 0.45);
  z-index: 50;
  overflow: hidden;
}

.site-mail-pop-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-bottom: 1px solid var(--chat-toolbar-divider, rgba(255, 255, 255, 0.08));
}

.site-mail-pop-title {
  font-size: 0.8125rem;
  font-weight: 800;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--chat-muted, #9aa3b2);
}

.site-mail-pop-close {
  border: none;
  background: none;
  font-size: 0.75rem;
  font-weight: 700;
  color: var(--chat-link-accent-fg, #6366f1);
  cursor: pointer;
}

.site-mail-pop-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 10px 12px 14px;
}

.site-mail-pop-loading,
.site-mail-pop-empty {
  margin: 0;
  font-size: 0.8125rem;
  color: var(--chat-muted, #9aa3b2);
  line-height: 1.5;
}

.site-mail-thread {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.site-mail-msg {
  border-radius: 12px;
  padding: 8px 10px;
  font-size: 0.8125rem;
  line-height: 1.45;
}

.site-mail-msg--self {
  align-self: flex-end;
  max-width: 92%;
  background: color-mix(in srgb, var(--chat-link-accent-fg, #6366f1) 18%, transparent);
  border: 1px solid color-mix(in srgb, var(--chat-link-accent-fg, #6366f1) 28%, transparent);
}

.site-mail-msg--admin {
  align-self: flex-start;
  max-width: 92%;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid var(--chat-toolbar-divider, rgba(255, 255, 255, 0.08));
}

.site-mail-msg-meta {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 4px;
  font-size: 0.6875rem;
  color: var(--chat-muted-3, #7a8496);
}

.site-mail-msg-who {
  font-weight: 700;
}

.site-mail-msg-imgs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 6px;
}

.site-mail-msg-img {
  width: 64px;
  height: 64px;
  object-fit: cover;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.site-mail-msg-body {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--chat-fg, #e8eaed);
}

.site-mail-pop-enter-active,
.site-mail-pop-leave-active {
  transition:
    opacity 0.2s ease,
    transform 0.2s ease;
}
.site-mail-pop-enter-from,
.site-mail-pop-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}
</style>
