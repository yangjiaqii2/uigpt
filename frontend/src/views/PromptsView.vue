<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useAuthStore } from '../stores/auth'
import {
  fetchPromptTemplates,
  createPromptTemplate,
  updatePromptTemplate,
  deletePromptTemplate,
} from '../api/prompts'
import { getAxiosErrorMessage } from '../utils/httpError'

/** @typedef {{ id: number, title: string, body: string, updatedAt: string }} PromptItem */

const auth = useAuthStore()
const { isSuperAdmin } = storeToRefs(auth)

/** @type {import('vue').Ref<PromptItem[]>} */
const prompts = ref([])
const listLoading = ref(false)

const modalOpen = ref(false)
/** @type {import('vue').Ref<'create' | 'edit' | null>} */
const modalMode = ref(null)
const formTitle = ref('')
const formBody = ref('')
/** @type {import('vue').Ref<number|null>} */
const editingId = ref(null)
const deleteConfirmOpen = ref(false)
const deleteCancelBtnRef = ref(null)
const toast = ref('')
let toastTimer = 0

/** 复制结果：屏幕居中短提示（0.5s） */
const copyTip = ref('')
let copyTipTimer = 0

const sortedPrompts = computed(() => {
  const list = [...prompts.value]
  list.sort((a, b) => String(b.updatedAt).localeCompare(String(a.updatedAt)))
  return list
})

function showToast(msg) {
  toast.value = msg
  if (toastTimer) window.clearTimeout(toastTimer)
  toastTimer = window.setTimeout(() => {
    toast.value = ''
    toastTimer = 0
  }, 2200)
}

/** 复制专用：居中 tip，默认 500ms 消失 */
function showCenterCopyTip(msg, durationMs = 500) {
  copyTip.value = msg
  if (copyTipTimer) window.clearTimeout(copyTipTimer)
  copyTipTimer = window.setTimeout(() => {
    copyTip.value = ''
    copyTipTimer = 0
  }, durationMs)
}

/** @param {unknown} row */
function mapApiRow(row) {
  if (!row || typeof row !== 'object') return null
  const o = /** @type {Record<string, unknown>} */ (row)
  const id = Number(o.id)
  if (!Number.isFinite(id)) return null
  const title = typeof o.title === 'string' ? o.title : ''
  const body = typeof o.body === 'string' ? o.body : ''
  let updatedAt =
    typeof o.updatedAt === 'string'
      ? o.updatedAt
      : typeof o.createdAt === 'string'
        ? o.createdAt
        : new Date().toISOString()
  if (Array.isArray(o.updatedAt) && o.updatedAt.length >= 6) {
    const t = /** @type {number[]} */ (o.updatedAt)
    const [y, M, d, h, m, s] = t
    updatedAt = new Date(y, M - 1, d, h, m, Math.floor(Number(s) || 0)).toISOString()
  }
  return { id, title, body, updatedAt }
}

async function loadPrompts() {
  listLoading.value = true
  try {
    await auth.refreshMe()
    const { data } = await fetchPromptTemplates({ page: 0, size: 500 })
    if (!Array.isArray(data)) {
      prompts.value = []
      return
    }
    const next = []
    for (const row of data) {
      const m = mapApiRow(row)
      if (m) next.push(m)
    }
    prompts.value = next
  } catch (e) {
    prompts.value = []
    showToast(getAxiosErrorMessage(e, '加载失败'))
  } finally {
    listLoading.value = false
  }
}

function openDeleteConfirm() {
  if (!editingId.value) return
  deleteConfirmOpen.value = true
}

function cancelDeleteDialog() {
  deleteConfirmOpen.value = false
}

function onDeleteDialogKeydown(e) {
  if (!deleteConfirmOpen.value) return
  if (e.key === 'Escape') {
    e.preventDefault()
    cancelDeleteDialog()
  }
}

watch(deleteConfirmOpen, (open) => {
  if (!open) return
  nextTick(() => deleteCancelBtnRef.value?.focus?.())
})

async function confirmDeleteDialog() {
  if (!editingId.value || !deleteConfirmOpen.value) return
  const id = editingId.value
  try {
    await deletePromptTemplate(id)
    prompts.value = prompts.value.filter((p) => p.id !== id)
    showToast('已删除')
    deleteConfirmOpen.value = false
    closeModal()
  } catch (e) {
    showToast(getAxiosErrorMessage(e, '删除失败'))
  }
}

onMounted(() => {
  window.addEventListener('keydown', onDeleteDialogKeydown)
  void loadPrompts()
})

onUnmounted(() => {
  window.removeEventListener('keydown', onDeleteDialogKeydown)
  if (toastTimer) window.clearTimeout(toastTimer)
  if (copyTipTimer) window.clearTimeout(copyTipTimer)
})

function openCreate() {
  modalMode.value = 'create'
  editingId.value = null
  formTitle.value = ''
  formBody.value = ''
  modalOpen.value = true
}

/** @param {PromptItem} p */
function openEdit(p) {
  modalMode.value = 'edit'
  editingId.value = p.id
  formTitle.value = p.title
  formBody.value = p.body
  modalOpen.value = true
}

function closeModal() {
  deleteConfirmOpen.value = false
  modalOpen.value = false
  modalMode.value = null
  editingId.value = null
}

/** @param {PromptItem} p */
function onTileActivate(p) {
  if (isSuperAdmin.value) {
    openEdit(p)
  } else {
    void copyPromptBody(p.body)
  }
}

async function saveForm() {
  const title = String(formTitle.value ?? '').trim()
  const body = String(formBody.value ?? '')
  if (!title) {
    showToast('请填写标题')
    return
  }
  try {
    if (modalMode.value === 'create') {
      const { data } = await createPromptTemplate({ title, body })
      const m = mapApiRow(data)
      if (m) prompts.value = [m, ...prompts.value]
      showToast('已保存')
      closeModal()
      return
    }
    if (modalMode.value === 'edit' && editingId.value != null) {
      const id = editingId.value
      const { data } = await updatePromptTemplate(id, { title, body })
      const m = mapApiRow(data)
      if (m) {
        prompts.value = prompts.value.map((p) => (p.id === id ? m : p))
      }
      showToast('已保存')
      closeModal()
    }
  } catch (e) {
    showToast(getAxiosErrorMessage(e, '保存失败'))
  }
}

/**
 * Clipboard API 在 HTTP（非 localhost）等环境下常不可用，需 textarea + execCommand 降级。
 * @param {string} text
 * @returns {Promise<boolean>}
 */
async function copyTextToClipboard(text) {
  const t = String(text ?? '')
  try {
    if (typeof navigator !== 'undefined' && navigator.clipboard?.writeText && window.isSecureContext) {
      await navigator.clipboard.writeText(t)
      return true
    }
  } catch {
    /* 走降级 */
  }
  try {
    const ta = document.createElement('textarea')
    ta.value = t
    ta.setAttribute('readonly', '')
    ta.setAttribute('aria-hidden', 'true')
    ta.style.cssText =
      'position:fixed;left:0;top:0;width:1px;height:1px;padding:0;margin:0;border:none;outline:none;opacity:0;font-size:16px;'
    document.body.appendChild(ta)
    ta.focus()
    ta.select()
    const len = ta.value.length
    ta.setSelectionRange(0, len)
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch {
    return false
  }
}

async function copyBody() {
  const text = String(formBody.value ?? '')
  const ok = await copyTextToClipboard(text)
  showCenterCopyTip(ok ? '复制成功' : '复制失败')
}

/** @param {string} [text] */
async function copyPromptBody(text) {
  const ok = await copyTextToClipboard(String(text ?? ''))
  showCenterCopyTip(ok ? '复制成功' : '复制失败')
}

function formatUpdated(iso) {
  try {
    const d = new Date(iso)
    if (Number.isNaN(d.getTime())) return ''
    return d.toLocaleString('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
  } catch {
    return ''
  }
}
</script>

<template>
  <div class="prm">
    <header class="prm-head">
      <div class="prm-head-row">
        <div>
          <h1 class="prm-title">提示词</h1>
        </div>
        <button
          v-if="isSuperAdmin"
          type="button"
          class="prm-btn prm-btn--primary"
          @click="openCreate"
        >
          新增
        </button>
      </div>
    </header>

    <p v-if="toast" class="prm-toast" role="status">{{ toast }}</p>

    <div v-if="listLoading" class="prm-loading">加载中…</div>

    <div v-else-if="sortedPrompts.length === 0" class="prm-empty">
      暂无提示词；超级管理员可通过「新增」添加模板。
    </div>

    <div v-else class="prm-grid" role="list">
      <button
        v-for="p in sortedPrompts"
        :key="p.id"
        type="button"
        class="prm-tile"
        role="listitem"
        @click="onTileActivate(p)"
      >
        <span class="prm-tile-title">{{ p.title || '未命名' }}</span>
        <span class="prm-tile-body">{{ p.body || '（无正文）' }}</span>
        <span v-if="formatUpdated(p.updatedAt)" class="prm-tile-meta">{{ formatUpdated(p.updatedAt) }}</span>
      </button>
    </div>

    <div
      v-if="modalOpen"
      class="prm-modal-backdrop"
      aria-modal="true"
      role="dialog"
      @click.self="closeModal"
    >
      <div class="prm-modal" @keydown.escape.stop="closeModal">
        <div class="prm-modal-head">
          <h2 class="prm-modal-title">{{ modalMode === 'create' ? '新建提示词' : '编辑提示词' }}</h2>
          <button type="button" class="prm-icon-btn" aria-label="关闭" @click="closeModal">×</button>
        </div>
        <div class="prm-modal-body">
          <label class="prm-field">
            <span class="prm-label">标题</span>
            <input v-model="formTitle" class="prm-input" type="text" maxlength="120" placeholder="简短名称" />
          </label>
          <label class="prm-field">
            <span class="prm-label">正文</span>
            <textarea v-model="formBody" class="prm-textarea" rows="10" spellcheck="false" placeholder="提示词内容…" />
          </label>
        </div>
        <div class="prm-modal-foot">
          <div class="prm-modal-foot-left">
            <button v-if="modalMode === 'edit'" type="button" class="prm-btn prm-btn--ghost" @click="copyBody">
              复制正文
            </button>
            <button v-if="modalMode === 'edit'" type="button" class="prm-btn prm-btn--danger" @click="openDeleteConfirm">
              删除
            </button>
          </div>
          <div class="prm-modal-foot-right">
            <button type="button" class="prm-btn prm-btn--ghost" @click="closeModal">取消</button>
            <button type="button" class="prm-btn prm-btn--primary" @click="saveForm">保存并关闭</button>
          </div>
        </div>
      </div>
    </div>

    <Teleport to="body">
      <Transition name="prm-del">
        <div
          v-if="deleteConfirmOpen"
          class="prm-del-shell"
          role="dialog"
          aria-modal="true"
          aria-labelledby="prm-del-title"
          aria-describedby="prm-del-desc"
        >
          <div class="prm-del-backdrop" @click="cancelDeleteDialog" />
          <div class="prm-del-center">
            <div class="prm-del-panel" @click.stop>
              <h2 id="prm-del-title" class="prm-del-title">删除提示词</h2>
              <p id="prm-del-desc" class="prm-del-desc">
                该提示词将从服务器永久移除，所有用户将不再看到。请确认是否继续。
              </p>
              <div class="prm-del-actions">
                <button
                  ref="deleteCancelBtnRef"
                  type="button"
                  class="prm-del-btn prm-del-btn--ghost"
                  @click="cancelDeleteDialog"
                >
                  取消
                </button>
                <button type="button" class="prm-del-btn prm-del-btn--danger" @click="confirmDeleteDialog">
                  确认删除
                </button>
              </div>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
    <Teleport to="body">
      <Transition name="prm-copy-flash">
        <div v-if="copyTip" class="prm-copy-flash" role="status">{{ copyTip }}</div>
      </Transition>
    </Teleport>
  </div>
</template>

<style scoped>
.prm {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 28px 24px 32px;
  overflow: auto;
  box-sizing: border-box;
}

.prm-head {
  margin-bottom: 20px;
}

.prm-head-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.prm-title {
  margin: 0;
  font-size: 1.35rem;
  font-weight: 700;
  color: var(--chat-fg-strong, #e8ecf5);
}

.prm-toast {
  margin: 0 0 14px;
  font-size: 0.875rem;
  color: var(--accent, #5ee1d5);
}

/* 复制成功：视口居中短提示（Teleport 到 body，z-index 高于编辑弹窗） */
.prm-copy-flash {
  position: fixed;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  z-index: 10120;
  padding: 10px 20px;
  border-radius: 12px;
  border: 1px solid color-mix(in srgb, var(--accent, #5ee1d5) 42%, transparent);
  background: color-mix(in srgb, var(--chat-panel, #242424) 88%, var(--accent, #5ee1d5));
  color: var(--chat-fg-strong, #e8ecf5);
  font-size: 0.875rem;
  font-weight: 650;
  letter-spacing: 0.02em;
  box-shadow:
    0 12px 40px rgba(0, 0, 0, 0.45),
    inset 0 1px 0 color-mix(in srgb, #fff 8%, transparent);
  pointer-events: none;
}

.prm-copy-flash-enter-active,
.prm-copy-flash-leave-active {
  transition:
    opacity 0.12s ease,
    transform 0.12s ease;
}

.prm-copy-flash-enter-from,
.prm-copy-flash-leave-to {
  opacity: 0;
  transform: translate(-50%, -50%) scale(0.94);
}

.prm-loading {
  padding: 20px;
  font-size: 0.9rem;
  color: var(--chat-muted, #9aa3b2);
}

.prm-empty {
  padding: 28px 20px;
  border-radius: 14px;
  border: 1px dashed var(--chat-border-strong, #ffffff1a);
  background: color-mix(in srgb, var(--chat-panel, #242424) 70%, transparent);
  color: var(--chat-muted-2, #8b95a8);
  font-size: 0.9rem;
  text-align: center;
}

.prm-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 14px;
  align-content: start;
}

.prm-tile {
  aspect-ratio: 1;
  min-height: 140px;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  text-align: left;
  padding: 14px 14px 12px;
  border-radius: 14px;
  border: 1px solid var(--chat-border-strong, #ffffff1a);
  background: var(--chat-panel, #242424);
  box-shadow: var(--chat-panel-shadow, 0 16px 48px #00000073);
  color: inherit;
  cursor: pointer;
  transition:
    border-color 0.15s ease,
    background 0.15s ease,
    transform 0.12s ease;
}

.prm-tile:hover {
  border-color: color-mix(in srgb, var(--accent, #5ee1d5) 45%, var(--chat-border-strong, #fff));
  background: color-mix(in srgb, var(--chat-panel, #242424) 92%, var(--accent, #5ee1d5));
}

.prm-tile:active {
  transform: scale(0.99);
}

.prm-tile-title {
  font-size: 0.9375rem;
  font-weight: 650;
  color: var(--chat-fg-strong, #e8ecf5);
  line-height: 1.3;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
}

.prm-tile-body {
  margin-top: 8px;
  flex: 1;
  min-height: 0;
  font-size: 0.78rem;
  line-height: 1.45;
  color: var(--chat-muted, #9aa3b2);
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 4;
  overflow: hidden;
  word-break: break-word;
}

.prm-tile-meta {
  margin-top: 10px;
  font-size: 0.68rem;
  color: var(--chat-muted-2, #8b95a8);
}

.prm-btn {
  padding: 8px 16px;
  border-radius: 999px;
  border: 1px solid var(--chat-border-strong, #ffffff1a);
  background: var(--chat-bubble-bg, #ffffff0a);
  color: var(--chat-fg-strong, #e8ecf5);
  font-size: 0.875rem;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease, opacity 0.15s ease;
}

.prm-btn--primary {
  border-color: color-mix(in srgb, var(--accent, #5ee1d5) 45%, transparent);
  background: color-mix(in srgb, var(--accent, #5ee1d5) 18%, transparent);
}

.prm-btn--primary:hover:not(:disabled) {
  background: color-mix(in srgb, var(--accent, #5ee1d5) 28%, transparent);
}

.prm-btn--ghost:hover:not(:disabled) {
  background: color-mix(in srgb, var(--chat-fg, #fff) 8%, transparent);
}

.prm-btn--danger {
  border-color: color-mix(in srgb, var(--danger, #ff6b8a) 45%, transparent);
  color: var(--danger, #ff6b8a);
  background: color-mix(in srgb, var(--danger, #ff6b8a) 12%, transparent);
}

.prm-btn--danger:hover:not(:disabled) {
  background: color-mix(in srgb, var(--danger, #ff6b8a) 22%, transparent);
}

.prm-modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 80;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px 16px;
  background: rgba(0, 0, 0, 0.55);
  backdrop-filter: blur(6px);
}

.prm-modal {
  width: min(560px, 100%);
  max-height: min(88vh, 720px);
  display: flex;
  flex-direction: column;
  border-radius: 16px;
  border: 1px solid var(--chat-border-strong, #ffffff1a);
  background: var(--chat-topbar-bg, #161922);
  box-shadow: var(--chat-panel-shadow, 0 24px 64px #000000a6);
  color: var(--chat-fg, #e8eaed);
}

.prm-modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 18px 10px;
  border-bottom: 1px solid var(--chat-border, #ffffff0f);
}

.prm-modal-title {
  margin: 0;
  font-size: 1.05rem;
  font-weight: 650;
  color: var(--chat-fg-strong, #e8ecf5);
}

.prm-icon-btn {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  border: 1px solid transparent;
  background: transparent;
  color: var(--chat-muted, #9aa3b2);
  font-size: 1.35rem;
  line-height: 1;
  cursor: pointer;
}

.prm-icon-btn:hover {
  background: color-mix(in srgb, var(--chat-fg, #fff) 8%, transparent);
  color: var(--chat-fg-strong, #e8ecf5);
}

.prm-modal-body {
  padding: 14px 18px 8px;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.prm-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.prm-label {
  font-size: 0.78rem;
  font-weight: 600;
  color: var(--chat-muted-2, #8b95a8);
}

.prm-input,
.prm-textarea {
  width: 100%;
  box-sizing: border-box;
  border-radius: 10px;
  border: 1px solid var(--chat-border-strong, #ffffff1a);
  background: var(--chat-shell-bg, #1a1a1a);
  color: var(--chat-fg, #e8e8e8);
  font-size: 0.875rem;
  padding: 10px 12px;
}

.prm-textarea {
  resize: vertical;
  min-height: 160px;
  line-height: 1.5;
  font-family: ui-sans-serif, system-ui, sans-serif;
}

.prm-input:focus,
.prm-textarea:focus {
  outline: none;
  border-color: color-mix(in srgb, var(--accent, #5ee1d5) 55%, var(--chat-border-strong, #fff));
}

.prm-modal-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  padding: 12px 18px 16px;
  border-top: 1px solid var(--chat-border, #ffffff0f);
}

.prm-modal-foot-left,
.prm-modal-foot-right {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

/* —— 删除确认：毛玻璃浮层（与作品库 StudioWorksLibraryView 一致） —— */
.prm-del-shell {
  position: fixed;
  inset: 0;
  z-index: 10080;
  pointer-events: auto;
}

.prm-del-backdrop {
  position: absolute;
  inset: 0;
  background: color-mix(in srgb, var(--chat-backdrop, rgba(0, 0, 0, 0.45)) 88%, #000);
  -webkit-backdrop-filter: blur(12px);
  backdrop-filter: blur(12px);
}

.prm-del-center {
  position: absolute;
  inset: 0;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: max(20px, env(safe-area-inset-top, 0px)) max(20px, env(safe-area-inset-right, 0px))
    max(20px, env(safe-area-inset-bottom, 0px)) max(20px, env(safe-area-inset-left, 0px));
  box-sizing: border-box;
  pointer-events: none;
}

.prm-del-panel {
  width: min(100%, 380px);
  pointer-events: auto;
  border-radius: 20px;
  border: 1px solid color-mix(in srgb, var(--chat-border-strong) 92%, transparent);
  background: color-mix(in srgb, var(--chat-panel) 76%, rgba(255, 255, 255, 0.06));
  -webkit-backdrop-filter: blur(22px);
  backdrop-filter: blur(22px);
  box-shadow:
    var(--chat-panel-shadow, 0 16px 48px rgba(0, 0, 0, 0.45)),
    inset 0 1px 0 color-mix(in srgb, var(--chat-fg-strong, #fff) 8%, transparent);
  padding: 22px 22px 18px;
  box-sizing: border-box;
}

.prm-del-title {
  margin: 0 0 10px;
  font-size: 1.0625rem;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--chat-fg-strong);
  text-align: center;
}

.prm-del-desc {
  margin: 0;
  font-size: 0.8125rem;
  line-height: 1.65;
  color: var(--chat-muted);
  text-align: center;
}

.prm-del-actions {
  display: flex;
  flex-direction: row;
  justify-content: flex-end;
  align-items: center;
  gap: 12px;
  margin-top: 22px;
}

.prm-del-btn {
  height: 42px;
  padding: 0 18px;
  border-radius: 11px;
  font-size: 0.875rem;
  font-weight: 600;
  cursor: pointer;
  transition:
    transform 0.18s ease,
    background 0.2s ease,
    border-color 0.2s ease,
    box-shadow 0.22s ease,
    opacity 0.2s ease;
}

.prm-del-btn:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.prm-del-btn--ghost {
  border: 1px solid var(--chat-border-strong);
  background: transparent;
  color: var(--chat-muted-2);
}

.prm-del-btn--ghost:hover:not(:disabled) {
  background: var(--chat-btn-bg-hover);
  color: var(--chat-fg-strong);
}

.prm-del-btn--ghost:active:not(:disabled) {
  transform: scale(0.97);
}

.prm-del-btn--danger {
  border: 1px solid color-mix(in srgb, var(--chat-danger-fg, #f87171) 55%, transparent);
  color: #fff;
  background: linear-gradient(
    145deg,
    color-mix(in srgb, var(--chat-danger-fg, #f87171) 72%, #7f1d1d),
    color-mix(in srgb, var(--chat-link-accent-fg, #5ee1d5) 12%, #991b1b)
  );
  box-shadow: 0 8px 28px color-mix(in srgb, var(--chat-danger-fg, #f87171) 22%, transparent);
  min-width: 120px;
}

.prm-del-btn--danger:hover:not(:disabled) {
  transform: translateY(-1px);
  filter: brightness(1.06);
  box-shadow: 0 12px 32px color-mix(in srgb, var(--chat-danger-fg, #f87171) 28%, transparent);
}

.prm-del-btn--danger:active:not(:disabled) {
  transform: scale(0.97);
}

.prm-del-enter-active,
.prm-del-leave-active {
  transition: opacity 0.22s ease;
}

.prm-del-enter-active .prm-del-panel,
.prm-del-leave-active .prm-del-panel {
  transition:
    transform 0.24s cubic-bezier(0.34, 1.45, 0.64, 1),
    opacity 0.22s ease;
}

.prm-del-enter-from,
.prm-del-leave-to {
  opacity: 0;
}

.prm-del-enter-from .prm-del-panel,
.prm-del-leave-to .prm-del-panel {
  opacity: 0;
  transform: scale(0.94);
}

.prm-del-enter-to .prm-del-panel,
.prm-del-leave-from .prm-del-panel {
  opacity: 1;
  transform: scale(1);
}

@media (max-width: 480px) {
  .prm-del-actions {
    flex-direction: column-reverse;
  }

  .prm-del-btn {
    width: 100%;
  }
}
</style>
