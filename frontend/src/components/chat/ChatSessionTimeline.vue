<script setup>
import { ref, watch, onMounted, onUnmounted } from 'vue'
import DeleteConversationModal from './DeleteConversationModal.vue'
import {
  patchConversationTitle,
  patchConversationPinned,
  deleteConversation,
} from '../../api/conversations'
import { getAxiosErrorMessage } from '../../utils/httpError'

const STORAGE_LIST_OPEN = 'uigpt_chat_session_list_open_v1'

function readListOpen() {
  try {
    if (localStorage.getItem(STORAGE_LIST_OPEN) === '1') return true
  } catch {
    /* ignore */
  }
  return false
}

const props = defineProps({
  conversations: { type: Array, default: () => [] },
  /** @type {import('vue').PropType<number | null>} */
  activeConversationId: { type: [Number, String], default: null },
  isAuthenticated: { type: Boolean, default: false },
})

const emit = defineEmits(['new-chat', 'open-conversation', 'conversations-mutated', 'deleted-active'])

/** 右侧会话列表展开（默认收起，点击窄条进入） */
const listOpen = ref(readListOpen())

watch(listOpen, (open) => {
  try {
    localStorage.setItem(STORAGE_LIST_OPEN, open ? '1' : '0')
  } catch {
    /* ignore */
  }
})

/** @param {string} title */
function inferSkillId(title) {
  const s = title || ''
  if (/效果图|高保真|视觉稿|mockup/i.test(s)) return 'mockup'
  if (/原型|线框|wireframe|低保真/i.test(s)) return 'wireframe'
  if (/修图|修饰|retouch|降噪|调色/i.test(s)) return 'retouch'
  if (/配色|色彩|色板/i.test(s)) return 'palette'
  return 'freeform'
}

/** @param {string | undefined} title */
function displayConvTitle(title) {
  const t = (title ?? '').trim()
  if (!t || t === '新对话') return '未命名会话'
  return t
}

/** @param {string | undefined} iso */
function formatConvTime(iso) {
  if (!iso) return '—'
  const d = new Date(iso)
  const diff = Date.now() - d.getTime()
  const m = Math.floor(diff / 60000)
  if (m < 1) return '刚刚'
  if (m < 60) return `${m}m`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h}h`
  const days = Math.floor(h / 24)
  if (days < 7) return `${days}d`
  return d.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })
}

function openConversation(id) {
  emit('open-conversation', id)
  try {
    if (typeof window !== 'undefined' && window.matchMedia('(max-width: 768px)').matches) {
      listOpen.value = false
    }
  } catch {
    /* ignore */
  }
}

/** @type {import('vue').Ref<{ x: number, y: number, conv: Record<string, unknown> } | null>} */
const ctxMenu = ref(null)
/** @type {import('vue').Ref<{ id: number, title: string } | null>} */
const renameTarget = ref(null)
const renameInput = ref('')
const renameErr = ref('')
const renameSaving = ref(false)

const deleteModalOpen = ref(false)
/** @type {import('vue').Ref<{ id: number, title: string } | null>} */
const deleteModalConv = ref(null)
/** @type {import('vue').Ref<{ x: number, y: number } | null>} */
const deleteModalAnchor = ref(null)
const deleteSubmitting = ref(false)
const deleteModalErr = ref('')
const deleteToast = ref(false)

/** @type {ReturnType<typeof setTimeout> | null} */
let deleteModalCleanupTimer = null
watch(deleteModalOpen, (open) => {
  if (deleteModalCleanupTimer) {
    window.clearTimeout(deleteModalCleanupTimer)
    deleteModalCleanupTimer = null
  }
  if (!open) {
    deleteModalCleanupTimer = window.setTimeout(() => {
      deleteModalCleanupTimer = null
      deleteModalConv.value = null
      deleteModalAnchor.value = null
      deleteModalErr.value = ''
    }, 280)
  }
})

function closeCtxMenu() {
  ctxMenu.value = null
}

function onCtxConv(e, conv) {
  e.preventDefault()
  ctxMenu.value = { x: e.clientX, y: e.clientY, conv }
}

function ctxRename() {
  const c = ctxMenu.value?.conv
  closeCtxMenu()
  if (!c?.id) return
  renameTarget.value = { id: c.id, title: String(c.title || '') }
  renameInput.value = renameTarget.value.title
  renameErr.value = ''
}

async function ctxPin() {
  const c = ctxMenu.value?.conv
  closeCtxMenu()
  if (!c?.id || !props.isAuthenticated) return
  try {
    await patchConversationPinned(Number(c.id), !c.pinned)
    emit('conversations-mutated')
  } catch (e) {
    window.alert(getAxiosErrorMessage(e))
  }
}

function ctxDelete() {
  const m = ctxMenu.value
  const c = m?.conv
  const anchor = m ? { x: m.x, y: m.y } : null
  closeCtxMenu()
  if (!c?.id || !props.isAuthenticated) return
  deleteModalErr.value = ''
  deleteModalConv.value = { id: Number(c.id), title: String(c.title || '') }
  deleteModalAnchor.value = anchor
  deleteModalOpen.value = true
}

async function executeDeleteConversation() {
  const id = deleteModalConv.value?.id
  if (id == null) return
  deleteModalErr.value = ''
  deleteSubmitting.value = true
  const t0 = Date.now()
  try {
    await deleteConversation(id)
    const elapsed = Date.now() - t0
    if (elapsed < 800) await new Promise((r) => window.setTimeout(r, 800 - elapsed))
    deleteModalOpen.value = false
    if (Number(props.activeConversationId) === Number(id)) {
      emit('deleted-active')
    }
    emit('conversations-mutated')
    deleteToast.value = true
    window.setTimeout(() => {
      deleteToast.value = false
    }, 2000)
  } catch (e) {
    deleteModalErr.value = getAxiosErrorMessage(e)
  } finally {
    deleteSubmitting.value = false
  }
}

async function submitRename() {
  if (!renameTarget.value) return
  const t = renameInput.value.trim()
  if (!t) {
    renameErr.value = '标题不能为空'
    return
  }
  renameSaving.value = true
  renameErr.value = ''
  try {
    await patchConversationTitle(renameTarget.value.id, t)
    renameTarget.value = null
    emit('conversations-mutated')
  } catch (e) {
    renameErr.value = getAxiosErrorMessage(e)
  } finally {
    renameSaving.value = false
  }
}

function cancelRename() {
  renameTarget.value = null
  renameErr.value = ''
}

function onDocClick(e) {
  if (!(e.target instanceof Node)) return
  if (e.target.closest?.('.srl-ctx')) return
  closeCtxMenu()
}

function onDocKey(e) {
  if (e.key !== 'Escape') return
  if (deleteModalOpen.value) return
  if (renameTarget.value) {
    renameTarget.value = null
    return
  }
  if (listOpen.value) {
    listOpen.value = false
  }
  closeCtxMenu()
}

function menuStyle() {
  const m = ctxMenu.value
  if (!m) return {}
  const maxW = 200
  const pad = 8
  let left = m.x
  let top = m.y
  if (typeof window !== 'undefined') {
    left = Math.min(left, window.innerWidth - maxW - pad)
    top = Math.min(top, window.innerHeight - 160 - pad)
  }
  return { left: `${left}px`, top: `${top}px` }
}

onMounted(() => {
  document.addEventListener('click', onDocClick)
  document.addEventListener('keydown', onDocKey)
})

onUnmounted(() => {
  document.removeEventListener('click', onDocClick)
  document.removeEventListener('keydown', onDocKey)
  if (deleteModalCleanupTimer) window.clearTimeout(deleteModalCleanupTimer)
})
</script>

<template>
  <aside
    v-if="isAuthenticated"
    class="srl"
    :class="{ 'srl--open': listOpen }"
    aria-label="会话与对话记录"
  >
    <!-- 收起：贴右侧边条，与旧版左侧「窄轨」入口类似 -->
    <button
      v-if="!listOpen"
      type="button"
      class="srl-tab"
      aria-label="打开对话记录侧栏"
      title="对话记录"
      @click="listOpen = true"
    >
      <span class="srl-tab-icon" aria-hidden="true">☰</span>
      <span class="srl-tab-text">会话</span>
    </button>

    <!-- 展开：覆盖在聊天区上，侧栏从右侧滑入（布局对齐原左侧侧栏） -->
    <Transition name="srl-shell">
      <div v-if="listOpen" class="srl-shell" key="srl-shell">
        <div class="srl-scrim" aria-hidden="true" @click="listOpen = false" />
        <div class="srl-drawer" @click.stop>
          <div class="srl-edge" aria-hidden="true" />
          <div class="srl-noise" aria-hidden="true" />
          <div class="srl-body">
            <header class="srl-brand">
              <span class="srl-brand-name">UI GPT</span>
              <button
                type="button"
                class="srl-icon-btn"
                aria-label="收起侧栏"
                title="收起"
                @click="listOpen = false"
              >
                <svg class="srl-chevron" width="18" height="18" viewBox="0 0 24 24" aria-hidden="true">
                  <path
                    fill="currentColor"
                    d="M10 6l6 6-6 6-1.4-1.4L13.2 12 8.6 7.4 10 6z"
                  />
                </svg>
              </button>
            </header>

            <div class="srl-new-wrap">
              <button type="button" class="srl-new-chat" aria-label="新建会话" title="新建会话" @click="emit('new-chat')">
                <span class="srl-new-plus" aria-hidden="true">+</span>
                <span>新建会话</span>
              </button>
            </div>

            <div class="srl-divider" />

            <section class="srl-history">
              <h3 class="srl-history-title">对话记录</h3>
              <div class="srl-history-scroll">
                <TransitionGroup v-if="conversations.length" name="srlconv" tag="div" class="srl-conv-tg">
                  <button
                    v-for="c in conversations"
                    :key="c.id"
                    type="button"
                    class="srl-conv"
                    :class="{
                      'srl-conv--on': activeConversationId != null && Number(activeConversationId) === Number(c.id),
                    }"
                    @click="openConversation(c.id)"
                    @contextmenu="onCtxConv($event, c)"
                  >
                    <span class="srl-conv-skill" :data-skill="inferSkillId(c.title)" aria-hidden="true" />
                    <span class="srl-conv-mid">
                      <span class="srl-conv-title">{{ displayConvTitle(c.title) }}</span>
                    </span>
                    <span class="srl-conv-meta">
                      <span class="srl-conv-time">{{ formatConvTime(c.updatedAt) }}</span>
                      <span v-if="c.messageCount != null && c.messageCount > 0" class="srl-conv-count"
                        >{{ c.messageCount }}条</span
                      >
                    </span>
                  </button>
                </TransitionGroup>
                <div v-else class="srl-empty">
                  <p class="srl-empty-text">暂无对话</p>
                </div>
              </div>
            </section>
          </div>
        </div>
      </div>
    </Transition>

    <Teleport to="body">
      <div v-if="ctxMenu" class="srl-ctx srl-glass" :style="menuStyle()" role="menu" @click.stop>
        <button type="button" role="menuitem" class="srl-ctx-item" @click="ctxRename">重命名</button>
        <button type="button" role="menuitem" class="srl-ctx-item" @click="ctxPin">
          {{ ctxMenu.conv.pinned ? '取消置顶' : '置顶' }}
        </button>
        <button type="button" role="menuitem" class="srl-ctx-item srl-ctx-item--danger" @click="ctxDelete">
          删除
        </button>
      </div>
    </Teleport>

    <DeleteConversationModal
      v-model:open="deleteModalOpen"
      :conversation="deleteModalConv"
      :anchor="deleteModalAnchor"
      :submitting="deleteSubmitting"
      :error="deleteModalErr"
      @confirm="executeDeleteConversation"
    />

    <Teleport to="body">
      <Transition name="srl-toast">
        <div v-if="deleteToast" class="srl-toast" role="status">已删除</div>
      </Transition>
    </Teleport>

    <Teleport to="body">
      <div v-if="renameTarget" class="srl-modal-backdrop" @click.self="cancelRename">
        <div class="srl-modal srl-glass" role="dialog" aria-labelledby="srl-rename-title" @click.stop>
          <h3 id="srl-rename-title" class="srl-modal-title">重命名对话</h3>
          <input v-model="renameInput" type="text" class="srl-modal-input" maxlength="255" @keydown.enter="submitRename" />
          <p v-if="renameErr" class="srl-modal-err">{{ renameErr }}</p>
          <div class="srl-modal-actions">
            <button type="button" class="srl-modal-primary" :disabled="renameSaving" @click="submitRename">保存</button>
            <button type="button" class="srl-modal-cancel" @click="cancelRename">取消</button>
          </div>
        </div>
      </div>
    </Teleport>
  </aside>
</template>

<style scoped>
.srl {
  --srl-brand: #2dd4bf;
  --srl-drawer-w: min(276px, 88vw);
  position: absolute;
  inset: 0;
  z-index: 24;
  pointer-events: none;
}

.srl--open {
  pointer-events: auto;
}

/* 右侧边条（不占主列宽度，与主区同一栅格内绝对定位） */
.srl-tab {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: 48px;
  z-index: 2;
  margin: 0;
  padding: 16px 0;
  border: none;
  border-radius: 0;
  border-left: 1px solid var(--chat-border);
  background: color-mix(in srgb, var(--chat-topbar-bg) 94%, transparent);
  color: var(--chat-fg-strong);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-start;
  gap: 10px;
  pointer-events: auto;
  transition: background 0.2s ease;
  box-sizing: border-box;
}

.srl-tab:hover {
  background: color-mix(in srgb, var(--chat-link-accent-fg) 12%, transparent);
}

.srl-tab-icon {
  font-size: 1.1rem;
  line-height: 1;
  opacity: 0.85;
}

.srl-tab-text {
  writing-mode: vertical-rl;
  text-orientation: mixed;
  font-size: 0.8125rem;
  font-weight: 700;
  letter-spacing: 0.18em;
  color: var(--chat-muted);
}

/* 覆盖层：左侧半透明遮罩 + 右侧侧栏（与原左侧 ChatSidebar 同一套背景与分隔） */
.srl-shell {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: row;
  align-items: stretch;
  z-index: 1;
}

.srl-scrim {
  flex: 1;
  min-width: 0;
  cursor: pointer;
  /* 仅用半透明压暗，不对背后主聊天区做 backdrop 模糊 */
  background: var(--chat-backdrop);
}

.srl-drawer {
  position: relative;
  flex: 0 0 var(--srl-drawer-w);
  width: var(--srl-drawer-w);
  max-width: 88vw;
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: var(--chat-sidebar-bg);
  border-left: 1px solid var(--chat-border);
  box-shadow:
    inset 1px 0 0 var(--chat-toolbar-divider),
    -12px 0 40px rgba(0, 0, 0, 0.22);
  overflow: hidden;
}

.srl-edge {
  position: absolute;
  top: 0;
  left: 0;
  width: 1px;
  height: 40%;
  background: linear-gradient(
    180deg,
    color-mix(in srgb, var(--chat-border-strong) 90%, transparent) 0%,
    transparent 100%
  );
  pointer-events: none;
  z-index: 2;
}

.srl-noise {
  position: absolute;
  inset: 0;
  opacity: 0.035;
  pointer-events: none;
  z-index: 0;
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 256 256' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E");
}

.srl-body {
  position: relative;
  z-index: 1;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 14px 12px 10px;
  overflow: hidden;
}

.srl-brand {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 4px 12px;
  flex-shrink: 0;
}

.srl-brand-name {
  font-size: 1.0625rem;
  font-weight: 800;
  letter-spacing: -0.02em;
  color: var(--chat-fg-strong);
  text-shadow: 0 0 22px color-mix(in srgb, var(--chat-link-accent-fg) 28%, transparent);
}

.srl-icon-btn {
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: var(--chat-sidebar-close, var(--chat-muted));
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition:
    color 0.2s ease,
    background 0.2s ease,
    transform 0.2s ease;
}

.srl-icon-btn:hover {
  color: var(--chat-fg-strong);
  background: var(--chat-btn-bg-hover);
}

.srl-icon-btn:hover .srl-chevron {
  transform: translateX(2px);
}

.srl-chevron {
  transition: transform 0.22s ease;
}

/* 侧栏整体入场：遮罩渐显 + 抽屉自右滑入 */
.srl-shell-enter-active,
.srl-shell-leave-active {
  transition: opacity 0.22s ease;
}

.srl-shell-enter-active .srl-drawer,
.srl-shell-leave-active .srl-drawer {
  transition: transform 0.34s cubic-bezier(0.22, 1, 0.36, 1);
}

.srl-shell-enter-from,
.srl-shell-leave-to {
  opacity: 0;
}

.srl-shell-enter-from .srl-drawer,
.srl-shell-leave-to .srl-drawer {
  transform: translateX(100%);
}

.srl-shell-enter-to,
.srl-shell-leave-from {
  opacity: 1;
}

.srl-shell-enter-to .srl-drawer,
.srl-shell-leave-from .srl-drawer {
  transform: translateX(0);
}

@media (prefers-reduced-motion: reduce) {
  .srl-shell-enter-active,
  .srl-shell-leave-active,
  .srl-shell-enter-active .srl-drawer,
  .srl-shell-leave-active .srl-drawer {
    transition-duration: 0.01ms !important;
  }
}

.srl-new-wrap {
  flex-shrink: 0;
}

.srl-new-chat {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  width: 100%;
  height: 46px;
  border: none;
  border-radius: 999px;
  cursor: pointer;
  font-size: 0.9375rem;
  font-weight: 700;
  color: var(--chat-send-fg);
  background: linear-gradient(120deg, var(--chat-send-bg-start) 0%, var(--chat-send-bg-end) 100%);
  box-shadow:
    0 10px 28px color-mix(in srgb, var(--chat-send-bg-end) 35%, transparent),
    0 0 0 1px color-mix(in srgb, var(--chat-fg-strong) 10%, transparent) inset;
  transition:
    transform 0.18s ease,
    box-shadow 0.22s ease,
    filter 0.22s ease;
}

.srl-new-chat:hover {
  transform: translateY(-2px);
  box-shadow:
    0 14px 36px color-mix(in srgb, var(--chat-send-bg-end) 42%, transparent),
    0 0 0 1px color-mix(in srgb, var(--chat-fg-strong) 12%, transparent) inset;
  filter: brightness(1.03);
}

.srl-new-chat:active {
  transform: scale(0.97);
}

.srl-new-plus {
  font-size: 1.25rem;
  font-weight: 500;
  opacity: 0.95;
}

.srl-divider {
  height: 1px;
  margin: 14px 2px 10px;
  background: var(--chat-toolbar-divider);
  flex-shrink: 0;
}

.srl-history {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.srl-history-title {
  margin: 0 2px 10px;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.28em;
  text-transform: uppercase;
  color: var(--chat-muted);
  flex-shrink: 0;
}

.srl-history-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding-right: 4px;
  scrollbar-width: thin;
  scrollbar-color: color-mix(in srgb, var(--chat-muted) 35%, transparent) transparent;
}

.srl-history-scroll:hover {
  scrollbar-color: color-mix(in srgb, var(--chat-muted) 55%, transparent) transparent;
}

.srl-history-scroll::-webkit-scrollbar {
  width: 4px;
}

.srl-history-scroll::-webkit-scrollbar-thumb {
  background: color-mix(in srgb, var(--chat-muted) 38%, transparent);
  border-radius: 99px;
}

.srl-history-scroll:hover::-webkit-scrollbar-thumb {
  background: color-mix(in srgb, var(--chat-muted) 58%, transparent);
}

.srl-conv-tg {
  display: flex;
  flex-direction: column;
  gap: 5px;
  width: 100%;
}

.srlconv-move {
  transition: transform 0.34s cubic-bezier(0.22, 1, 0.36, 1);
}

.srlconv-leave-active {
  transition:
    transform 0.38s cubic-bezier(0.22, 1, 0.36, 1),
    opacity 0.34s ease;
}

.srlconv-leave-to {
  opacity: 0;
  transform: translateX(12px);
}

.srl-conv {
  position: relative;
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-height: 42px;
  padding: 8px 10px 8px 12px;
  border: none;
  border-radius: 11px;
  background: transparent;
  color: var(--chat-fg);
  cursor: pointer;
  text-align: left;
  transition:
    background 0.2s ease-out,
    color 0.2s ease-out,
    box-shadow 0.2s ease-out;
}

.srl-conv::before {
  content: '';
  position: absolute;
  left: 0;
  top: 10px;
  bottom: 10px;
  width: 3px;
  border-radius: 99px;
  background: var(--srl-brand);
  opacity: 0;
  transform: scaleY(0.6);
  transition:
    opacity 0.2s ease,
    transform 0.2s ease,
    box-shadow 0.2s ease;
}

.srl-conv:hover {
  background: var(--chat-btn-bg-hover);
  color: var(--chat-fg-strong);
}

.srl-conv:hover::before {
  opacity: 0.85;
  transform: scaleY(1);
}

.srl-conv--on {
  background: var(--chat-mode-on-bg);
  color: var(--chat-fg-strong);
}

.srl-conv--on::before {
  opacity: 1;
  box-shadow: 0 0 12px rgba(45, 212, 191, 0.55);
  transform: scaleY(1);
}

.srl-conv--on .srl-conv-title {
  font-weight: 700;
}

.srl-conv-skill {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  background: #64748b;
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--chat-sidebar-bg) 65%, transparent);
}

.srl-conv-skill[data-skill='mockup'] {
  background: #a78bfa;
}
.srl-conv-skill[data-skill='wireframe'] {
  background: #38bdf8;
}
.srl-conv-skill[data-skill='retouch'] {
  background: #f472b6;
}
.srl-conv-skill[data-skill='palette'] {
  background: #fbbf24;
}

.srl-conv-mid {
  flex: 1;
  min-width: 0;
}

.srl-conv-title {
  display: block;
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.srl-conv-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  flex-shrink: 0;
}

.srl-conv-time {
  font-size: 10px;
  color: var(--chat-muted-4);
}

.srl-conv-count {
  font-size: 9px;
  color: var(--chat-muted-3);
}

.srl-empty {
  padding: 20px 8px;
  text-align: center;
}

.srl-empty-text {
  margin: 0;
  font-size: 13px;
  color: var(--chat-muted-3);
}

.srl-ctx {
  position: fixed;
  z-index: 12000;
  min-width: 140px;
  padding: 6px;
  border-radius: 10px;
  border: 1px solid var(--chat-border-strong);
  background: var(--chat-panel);
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.35);
}

.srl-ctx-item {
  display: block;
  width: 100%;
  text-align: left;
  padding: 8px 10px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--chat-fg);
  font-size: 0.8125rem;
  cursor: pointer;
}

.srl-ctx-item:hover {
  background: var(--chat-btn-bg);
}

.srl-ctx-item--danger {
  color: #f87171;
}

.srl-glass {
  backdrop-filter: blur(10px);
}

.srl-modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 13000;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
}

.srl-modal {
  width: min(100%, 380px);
  padding: 18px;
  border-radius: 12px;
  border: 1px solid var(--chat-border-strong);
  background: var(--chat-panel);
}

.srl-modal-title {
  margin: 0 0 12px;
  font-size: 1rem;
}

.srl-modal-input {
  width: 100%;
  box-sizing: border-box;
  padding: 10px 12px;
  border-radius: 8px;
  border: 1px solid var(--chat-border);
  background: var(--chat-shell-bg);
  color: inherit;
  font-size: 0.9rem;
}

.srl-modal-err {
  color: #f87171;
  font-size: 0.8rem;
  margin: 8px 0 0;
}

.srl-modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 14px;
}

.srl-modal-primary,
.srl-modal-cancel {
  padding: 8px 14px;
  border-radius: 8px;
  font-size: 0.8125rem;
  cursor: pointer;
  border: 1px solid var(--chat-border);
  background: var(--chat-btn-bg);
  color: var(--chat-fg-strong);
}

.srl-modal-primary {
  border-color: color-mix(in srgb, var(--chat-link-accent-fg) 50%, transparent);
  background: color-mix(in srgb, var(--chat-link-accent-fg) 28%, var(--chat-panel));
}

.srl-toast {
  position: fixed;
  bottom: 24px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 12500;
  padding: 8px 16px;
  border-radius: 999px;
  background: var(--chat-panel);
  border: 1px solid var(--chat-border-strong);
  font-size: 0.8125rem;
}

.srl-toast-enter-active,
.srl-toast-leave-active {
  transition: opacity 0.2s ease;
}
.srl-toast-enter-from,
.srl-toast-leave-to {
  opacity: 0;
}
</style>
