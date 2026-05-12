<script setup>
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import DeleteConversationModal from './DeleteConversationModal.vue'
import {
  patchConversationTitle,
  patchConversationPinned,
  deleteConversation,
} from '../../api/conversations'
import { getAxiosErrorMessage } from '../../utils/httpError'

const props = defineProps({
  conversations: { type: Array, default: () => [] },
  /** @type {import('vue').PropType<number | null>} */
  activeConversationId: { type: [Number, String], default: null },
  isAuthenticated: { type: Boolean, default: false },
  collapsed: { type: Boolean, default: false },
  mobileOpen: { type: Boolean, default: false },
})

const emit = defineEmits([
  'update:collapsed',
  'update:mobileOpen',
  'new-chat',
  'open-conversation',
  'conversations-mutated',
  'deleted-active',
])

const desktopMq = typeof window !== 'undefined' ? window.matchMedia('(min-width: 769px)') : null
const isDesktop = ref(!!desktopMq?.matches)

function onMq() {
  isDesktop.value = !!desktopMq?.matches
}

const collapsedEffective = computed(() => props.collapsed && isDesktop.value)

/** @param {string} title */
function inferSkillId(title) {
  const s = title || ''
  if (/效果图|高保真|视觉稿|mockup/i.test(s)) return 'mockup'
  if (/原型|线框|wireframe|低保真/i.test(s)) return 'wireframe'
  if (/修图|修饰|retouch|降噪|调色/i.test(s)) return 'retouch'
  if (/配色|色彩|色板/i.test(s)) return 'palette'
  return 'freeform'
}

/** 列表展示：空标题或后端默认「新对话」不显示该字样 */
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

/** 右键菜单 */
/** @type {import('vue').Ref<{ x: number, y: number, conv: Record<string, unknown> } | null>} */
const ctxMenu = ref(null)

/** @type {import('vue').Ref<{ id: number, title: string } | null>} */
const renameTarget = ref(null)

/** 删除会话确认 */
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

const renameInput = ref('')
const renameErr = ref('')
const renameSaving = ref(false)

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

function onDocClick(e) {
  if (!(e.target instanceof Node)) return
  if (e.target.closest?.('.sb-ctx-menu')) return
  closeCtxMenu()
}

function onDocKey(e) {
  if (e.key === 'Escape') {
    if (deleteModalOpen.value) return
    closeCtxMenu()
    renameTarget.value = null
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

onMounted(() => {
  desktopMq?.addEventListener('change', onMq)
  document.addEventListener('click', onDocClick)
  document.addEventListener('keydown', onDocKey)
})

onUnmounted(() => {
  desktopMq?.removeEventListener('change', onMq)
  document.removeEventListener('click', onDocClick)
  document.removeEventListener('keydown', onDocKey)
  if (deleteModalCleanupTimer) window.clearTimeout(deleteModalCleanupTimer)
})

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
</script>

<template>
  <aside
    class="sb"
    :class="{
      'sb--collapsed': collapsedEffective,
      'sb--open': mobileOpen,
      'sb--enter': true,
    }"
  >
    <div class="sb-edge" aria-hidden="true" />
    <div class="sb-noise" aria-hidden="true" />

    <!-- 展开布局 -->
    <div v-show="!collapsedEffective" class="sb-body sb-body--expanded">
      <header class="sb-brand sb-stagger" style="--si: 0">
        <span class="sb-brand-name">UI GPT</span>
        <div class="sb-brand-actions">
          <button
            v-if="isDesktop"
            type="button"
            class="sb-icon-btn sb-collapse-btn"
            aria-label="收起侧栏"
            title="收起"
            @click="emit('update:collapsed', true)"
          >
            <svg class="sb-chevron" width="18" height="18" viewBox="0 0 24 24" aria-hidden="true">
              <path
                fill="currentColor"
                d="M14 18l-6-6 6-6 1.4 1.4L10.8 12l4.6 4.6L14 18z"
              />
            </svg>
          </button>
          <button
            type="button"
            class="sb-icon-btn sb-close-mobile"
            aria-label="关闭侧栏"
            @click="emit('update:mobileOpen', false)"
          >
            <span aria-hidden="true">×</span>
          </button>
        </div>
      </header>

      <div class="sb-new-wrap sb-stagger" style="--si: 1">
        <button type="button" class="sb-new-chat" @click="emit('new-chat')">
          <span class="sb-new-plus" aria-hidden="true">+</span>
          <span>新建会话</span>
        </button>
      </div>

      <div class="sb-divider sb-stagger" style="--si: 2" />

      <section class="sb-history sb-stagger" style="--si: 3">
        <h2 class="sb-history-title">对话记录</h2>
        <div class="sb-history-scroll">
          <template v-if="isAuthenticated">
            <TransitionGroup name="sbconv" tag="div" class="sb-conv-tg">
              <button
                v-for="c in conversations"
                :key="c.id"
                type="button"
                class="sb-conv"
                :class="{
                  'sb-conv--on': activeConversationId != null && Number(activeConversationId) === Number(c.id),
                }"
                @click="emit('open-conversation', c.id)"
                @contextmenu="onCtxConv($event, c)"
              >
                <span class="sb-conv-skill" :data-skill="inferSkillId(c.title)" aria-hidden="true" />
                <span class="sb-conv-mid">
                  <span class="sb-conv-title">{{ displayConvTitle(c.title) }}</span>
                </span>
                <span class="sb-conv-meta">
                  <span class="sb-conv-time">{{ formatConvTime(c.updatedAt) }}</span>
                  <span v-if="c.messageCount != null && c.messageCount > 0" class="sb-conv-count"
                    >{{ c.messageCount }}条</span
                  >
                </span>
              </button>
            </TransitionGroup>
            <div v-if="conversations.length === 0" class="sb-empty">
              <p class="sb-empty-text">暂无对话</p>
            </div>
          </template>
          <p v-else class="sb-guest">登录后可同步与查看历史会话</p>
        </div>
      </section>
    </div>

    <!-- 收起：窄轨 -->
    <div v-show="collapsedEffective" class="sb-body sb-body--rail">
      <button type="button" class="sb-rail-brand" aria-label="UI GPT" @click="emit('update:collapsed', false)">
        U
      </button>
      <button type="button" class="sb-rail-new" aria-label="新建会话" title="新建会话" @click="emit('new-chat')">
        +
      </button>
      <div class="sb-rail-scroll">
        <TransitionGroup name="sbconv" tag="div" class="sb-rail-tg">
          <button
            v-for="c in conversations"
            :key="c.id"
            type="button"
            class="sb-rail-dot"
            :data-skill="inferSkillId(c.title)"
            :class="{ 'sb-rail-dot--on': activeConversationId != null && Number(activeConversationId) === Number(c.id) }"
            :title="displayConvTitle(c.title)"
            @click="emit('open-conversation', c.id)"
          />
        </TransitionGroup>
      </div>
    </div>

    <!-- 右键菜单 -->
    <Teleport to="body">
      <div
        v-if="ctxMenu"
        class="sb-ctx-menu sb-glass"
        :style="menuStyle()"
        role="menu"
        @click.stop
      >
        <button type="button" role="menuitem" class="sb-ctx-item" @click="ctxRename">重命名</button>
        <button type="button" role="menuitem" class="sb-ctx-item" @click="ctxPin">
          {{ ctxMenu.conv.pinned ? '取消置顶' : '置顶' }}
        </button>
        <button type="button" role="menuitem" class="sb-ctx-item sb-ctx-item--danger" @click="ctxDelete">
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
      <Transition name="sb-del-toast">
        <div v-if="deleteToast" class="sb-del-toast" role="status">已删除</div>
      </Transition>
    </Teleport>

    <!-- 重命名 -->
    <Teleport to="body">
      <div v-if="renameTarget" class="sb-modal-backdrop" @click.self="cancelRename">
        <div class="sb-modal sb-glass" role="dialog" aria-labelledby="sb-rename-title" @click.stop>
          <h3 id="sb-rename-title" class="sb-modal-title">重命名对话</h3>
          <input v-model="renameInput" type="text" class="sb-city-input" maxlength="255" @keydown.enter="submitRename" />
          <p v-if="renameErr" class="sb-modal-err">{{ renameErr }}</p>
          <div class="sb-modal-actions">
            <button type="button" class="sb-modal-primary" :disabled="renameSaving" @click="submitRename">
              保存
            </button>
            <button type="button" class="sb-modal-cancel" @click="cancelRename">取消</button>
          </div>
        </div>
      </div>
    </Teleport>
  </aside>
</template>

<style scoped>
.sb {
  --sb-w: 276px;
  --sb-brand: #2dd4bf;
  --sb-brand2: #34d399;
  position: relative;
  width: var(--sb-w);
  flex-shrink: 0;
  min-height: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  background: var(--chat-sidebar-bg);
  border-right: 1px solid var(--chat-border);
  box-shadow: inset -1px 0 0 var(--chat-toolbar-divider);
  transition:
    width 0.3s ease-out,
    transform 0.32s ease-out,
    opacity 0.28s ease-out;
}

/* 仅桌面：入场动画会写 transform/opacity，若在窄屏启用会覆盖抽屉用的 translateX(-100%)，导致侧栏关不掉 */
@media (min-width: 769px) {
  .sb {
    animation: sb-slide-in 0.45s ease-out both;
  }
}

@keyframes sb-slide-in {
  from {
    transform: translateX(-12px);
    opacity: 0;
  }
  to {
    transform: translateX(0);
    opacity: 1;
  }
}

.sb--collapsed {
  --sb-w: 60px;
}

.sb-edge {
  position: absolute;
  top: 0;
  right: 0;
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

.sb-noise {
  position: absolute;
  inset: 0;
  opacity: 0.035;
  pointer-events: none;
  z-index: 0;
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 256 256' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.9' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E");
}

.sb-body {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  padding: 14px 12px 10px;
}

.sb-body--rail {
  align-items: center;
  padding: 12px 6px;
  gap: 10px;
}

.sb-stagger {
  animation: sb-fade-up 0.5s ease-out both;
  animation-delay: calc(var(--si, 0) * 0.05s);
}

@keyframes sb-fade-up {
  from {
    opacity: 0;
    transform: translateY(6px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.sb-brand {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 4px 12px;
}

.sb-brand-name {
  font-size: 1.0625rem;
  font-weight: 800;
  letter-spacing: -0.02em;
  color: var(--chat-fg-strong);
  text-shadow: 0 0 22px color-mix(in srgb, var(--chat-link-accent-fg) 28%, transparent);
}

.sb-brand-actions {
  display: flex;
  align-items: center;
  gap: 2px;
}

.sb-icon-btn {
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: var(--chat-sidebar-close);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition:
    color 0.2s ease,
    background 0.2s ease,
    transform 0.2s ease;
}

.sb-icon-btn:hover {
  color: var(--chat-fg-strong);
  background: var(--chat-btn-bg-hover);
}

.sb-collapse-btn:hover .sb-chevron {
  transform: translateX(-2px) rotate(-12deg);
}

.sb-chevron {
  transition: transform 0.22s ease;
}

.sb-close-mobile {
  display: none;
}

.sb-new-wrap {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.sb-new-chat {
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
  background: linear-gradient(
    120deg,
    var(--chat-send-bg-start) 0%,
    var(--chat-send-bg-end) 100%
  );
  box-shadow:
    0 10px 28px color-mix(in srgb, var(--chat-send-bg-end) 35%, transparent),
    0 0 0 1px color-mix(in srgb, var(--chat-fg-strong) 10%, transparent) inset;
  transition:
    transform 0.18s ease,
    box-shadow 0.22s ease,
    filter 0.22s ease;
}

.sb-new-chat:hover {
  transform: translateY(-2px);
  box-shadow:
    0 14px 36px color-mix(in srgb, var(--chat-send-bg-end) 42%, transparent),
    0 0 0 1px color-mix(in srgb, var(--chat-fg-strong) 12%, transparent) inset;
  filter: brightness(1.03);
}

.sb-new-chat:active {
  transform: scale(0.97);
}

.sb-new-plus {
  font-size: 1.25rem;
  font-weight: 500;
  opacity: 0.95;
}

.sb-divider {
  height: 1px;
  margin: 14px 4px 10px;
  background: var(--chat-toolbar-divider);
}

.sb-history {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.sb-history-title {
  margin: 0 4px 16px;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.28em;
  text-transform: uppercase;
  color: var(--chat-muted);
}

.sb-history-scroll {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding-right: 4px;
  scrollbar-width: thin;
  scrollbar-color: color-mix(in srgb, var(--chat-muted) 35%, transparent) transparent;
}

.sb-history-scroll:hover {
  scrollbar-color: color-mix(in srgb, var(--chat-muted) 55%, transparent) transparent;
}

.sb-history-scroll::-webkit-scrollbar {
  width: 4px;
}
.sb-history-scroll::-webkit-scrollbar-thumb {
  background: color-mix(in srgb, var(--chat-muted) 38%, transparent);
  border-radius: 99px;
}
.sb-history-scroll:hover::-webkit-scrollbar-thumb {
  width: 6px;
  background: color-mix(in srgb, var(--chat-muted) 58%, transparent);
}

.sb-conv-tg {
  display: flex;
  flex-direction: column;
  gap: 5px;
  width: 100%;
}

/* 删除会话后列表项向左滑出 */
.sbconv-move {
  transition: transform 0.34s cubic-bezier(0.22, 1, 0.36, 1);
}
.sbconv-leave-active {
  transition:
    transform 0.38s cubic-bezier(0.22, 1, 0.36, 1),
    opacity 0.34s ease;
}
.sbconv-leave-to {
  opacity: 0;
  transform: translateX(-22px);
}

.sb-conv {
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

.sb-conv::before {
  content: '';
  position: absolute;
  left: 0;
  top: 10px;
  bottom: 10px;
  width: 3px;
  border-radius: 99px;
  background: var(--sb-brand);
  opacity: 0;
  transform: scaleY(0.6);
  transition:
    opacity 0.2s ease,
    transform 0.2s ease,
    box-shadow 0.2s ease;
}

.sb-conv:hover {
  background: var(--chat-btn-bg-hover);
  color: var(--chat-fg-strong);
}

.sb-conv:hover::before {
  opacity: 0.85;
  transform: scaleY(1);
}

.sb-conv--on {
  background: var(--chat-mode-on-bg);
  color: var(--chat-fg-strong);
}

.sb-conv--on::before {
  opacity: 1;
  box-shadow: 0 0 12px rgba(45, 212, 191, 0.55);
  transform: scaleY(1);
}

.sb-conv--on .sb-conv-title {
  font-weight: 700;
}

.sb-conv-skill {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  background: #64748b;
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--chat-shell-bg) 65%, transparent);
}

.sb-conv-skill[data-skill='mockup'] {
  background: #a78bfa;
}
.sb-conv-skill[data-skill='wireframe'] {
  background: #38bdf8;
}
.sb-conv-skill[data-skill='retouch'] {
  background: #f472b6;
}
.sb-conv-skill[data-skill='palette'] {
  background: #fbbf24;
}

.sb-conv-mid {
  flex: 1;
  min-width: 0;
}

.sb-conv-title {
  display: block;
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.sb-conv-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  flex-shrink: 0;
}

.sb-conv-time {
  font-size: 10px;
  color: var(--chat-muted-4);
}

.sb-conv-count {
  font-size: 9px;
  color: var(--chat-muted-3);
}

.sb-empty {
  padding: 20px 8px;
  text-align: center;
}

.sb-empty-text {
  margin: 0;
  font-size: 13px;
  color: var(--chat-muted-3);
}

.sb-guest {
  margin: 8px 4px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--chat-muted-2);
}

/* 窄轨 */
.sb-rail-brand {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  border: 1px solid var(--chat-border);
  background: var(--chat-profile-bg);
  color: var(--chat-fg-strong);
  font-weight: 900;
  font-size: 1rem;
  cursor: pointer;
  transition:
    background 0.2s ease,
    transform 0.15s ease;
}

.sb-rail-brand:hover {
  background: var(--chat-link-accent-bg);
}

.sb-rail-new {
  width: 40px;
  height: 40px;
  border-radius: 999px;
  border: none;
  background: linear-gradient(
    135deg,
    var(--chat-send-bg-start) 0%,
    var(--chat-send-bg-end) 100%
  );
  color: var(--chat-send-fg);
  font-size: 1.35rem;
  font-weight: 500;
  line-height: 1;
  cursor: pointer;
  box-shadow: 0 8px 20px color-mix(in srgb, var(--chat-send-bg-end) 30%, transparent);
  transition: transform 0.15s ease;
}

.sb-rail-new:active {
  transform: scale(0.97);
}

.sb-rail-scroll {
  flex: 1;
  width: 100%;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 4px 0;
  scrollbar-width: thin;
}

.sb-rail-tg {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  width: 100%;
}

.sb-rail-dot {
  width: 10px;
  height: 10px;
  border: none;
  border-radius: 50%;
  padding: 0;
  cursor: pointer;
  background: #64748b;
  transition:
    transform 0.2s ease,
    box-shadow 0.2s ease;
}

.sb-rail-dot[data-skill='mockup'] {
  background: #a78bfa;
}
.sb-rail-dot[data-skill='wireframe'] {
  background: #38bdf8;
}
.sb-rail-dot[data-skill='retouch'] {
  background: #f472b6;
}
.sb-rail-dot[data-skill='palette'] {
  background: #fbbf24;
}

.sb-rail-dot--on {
  box-shadow: 0 0 0 3px rgba(45, 212, 191, 0.45);
  transform: scale(1.12);
}

.sb-rail-dot:hover {
  transform: scale(1.15);
}

/* 毛玻璃浮层 */
.sb-glass {
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);
  background: color-mix(in srgb, var(--chat-panel) 88%, transparent);
  border: 1px solid var(--chat-border-strong);
}

.sb-ctx-menu {
  position: fixed;
  z-index: 10060;
  min-width: 148px;
  padding: 6px;
  border-radius: 12px;
  box-shadow: var(--chat-panel-shadow);
}

.sb-ctx-item {
  display: block;
  width: 100%;
  text-align: left;
  padding: 10px 12px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--chat-fg);
  font-size: 13px;
  cursor: pointer;
  transition:
    background 0.18s ease,
    color 0.18s ease;
}

.sb-ctx-item:hover {
  background: var(--chat-btn-bg-hover);
  color: var(--chat-fg-strong);
}

.sb-ctx-item--danger {
  color: var(--chat-danger-fg);
}

.sb-ctx-item--danger:hover {
  background: var(--chat-danger-bg);
}

.sb-del-toast {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 28px;
  z-index: 12020;
  margin: 0 auto;
  width: max-content;
  max-width: calc(100vw - 32px);
  padding: 10px 18px;
  border-radius: 999px;
  font-size: 13px;
  font-weight: 500;
  letter-spacing: 0.02em;
  color: var(--chat-fg);
  background: color-mix(in srgb, var(--chat-panel) 82%, transparent);
  border: 1px solid var(--chat-border);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  box-shadow: var(--chat-panel-shadow);
  pointer-events: none;
}

.sb-del-toast-enter-active,
.sb-del-toast-leave-active {
  transition:
    opacity 0.38s ease,
    transform 0.38s cubic-bezier(0.22, 1, 0.36, 1);
}

.sb-del-toast-enter-from,
.sb-del-toast-leave-to {
  opacity: 0;
  transform: translateY(10px);
}

.sb-modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 10050;
  background: var(--chat-backdrop);
  backdrop-filter: blur(6px);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: max(20px, env(safe-area-inset-top, 0px)) max(20px, env(safe-area-inset-right, 0px))
    max(20px, env(safe-area-inset-bottom, 0px)) max(20px, env(safe-area-inset-left, 0px));
  box-sizing: border-box;
  animation: sb-modal-in 0.25s ease both;
}

@keyframes sb-modal-in {
  from {
    opacity: 0;
  }
  to {
    opacity: 1;
  }
}

.sb-modal {
  width: min(360px, 100%);
  padding: 20px;
  border-radius: 18px;
  animation: sb-modal-pop 0.28s ease both;
}

@keyframes sb-modal-pop {
  from {
    opacity: 0;
    transform: scale(0.96);
  }
  to {
    opacity: 1;
    transform: scale(1);
  }
}

.sb-modal-title {
  margin: 0 0 14px;
  font-size: 16px;
  font-weight: 700;
  color: var(--chat-fg-strong);
}

.sb-city-input {
  width: 100%;
  box-sizing: border-box;
  padding: 12px 14px;
  border-radius: 12px;
  border: 1px solid var(--chat-border-strong);
  background: var(--auth-input-bg);
  color: var(--chat-input-fg);
  font-size: 14px;
  outline: none;
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease;
}

.sb-city-input:focus {
  border-color: color-mix(in srgb, var(--chat-link-accent-fg) 55%, transparent);
  box-shadow: 0 0 0 3px var(--chat-link-accent-bg);
}

.sb-city-list {
  list-style: none;
  margin: 12px 0 0;
  padding: 0;
  max-height: 220px;
  overflow-y: auto;
}

.sb-city-li {
  padding: 10px 12px;
  border-radius: 10px;
  cursor: pointer;
  color: var(--chat-fg);
  font-size: 13px;
  transition: background 0.15s ease;
}

.sb-city-li:hover {
  background: var(--chat-btn-bg-hover);
}

.sb-city-li-muted {
  padding: 10px 12px;
  color: var(--chat-muted-3);
  font-size: 13px;
}

.sb-modal-cancel {
  margin-top: 14px;
  width: 100%;
  padding: 10px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: var(--chat-muted);
  cursor: pointer;
  font-size: 13px;
}

.sb-modal-cancel:hover {
  color: var(--chat-fg-strong);
}

.sb-modal-err {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--chat-danger-fg);
}

.sb-modal-actions {
  display: flex;
  gap: 10px;
  margin-top: 14px;
}

.sb-modal-primary {
  flex: 1;
  padding: 12px;
  border: none;
  border-radius: 12px;
  font-weight: 700;
  font-size: 14px;
  color: var(--chat-send-fg);
  cursor: pointer;
  background: linear-gradient(
    120deg,
    var(--chat-send-bg-start) 0%,
    var(--chat-send-bg-end) 100%
  );
}

.sb-modal-primary:disabled {
  opacity: 0.6;
  cursor: default;
}

@media (max-width: 768px) {
  .sb-close-mobile {
    display: inline-flex;
  }

  .sb-collapse-btn {
    display: none;
  }

  .sb {
    position: fixed;
    left: 0;
    top: 0;
    bottom: 0;
    z-index: 40;
    box-sizing: border-box;
    width: min(var(--sb-w), calc(100vw - env(safe-area-inset-left, 0px) - env(safe-area-inset-right, 0px)));
    padding-left: env(safe-area-inset-left, 0px);
    transform: translateX(-100%);
    box-shadow: var(--chat-drawer-shadow);
  }

  .sb--open {
    transform: translateX(0);
  }

  .sb--collapsed {
    --sb-w: min(276px, 92vw);
  }
}
</style>

<style>
html[data-theme='light'] .sb-noise {
  opacity: 0.022;
}
</style>
