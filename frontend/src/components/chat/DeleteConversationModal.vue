<script setup>
/**
 * 删除会话确认：深色毛玻璃、弹性动效、防误触、缩略图预览、移动端底部抽屉。
 */
import { ref, watch, computed, onMounted, onUnmounted } from 'vue'
import { fetchConversationImages } from '../../api/conversationImages'

const props = defineProps({
  open: { type: Boolean, default: false },
  /** @type {{ id: number, title?: string } | null} */
  conversation: { type: Object, default: null },
  /** @type {{ x: number, y: number } | null} */
  anchor: { type: Object, default: null },
  submitting: { type: Boolean, default: false },
  error: { type: String, default: '' },
})

const emit = defineEmits(['update:open', 'confirm'])

const previewUrls = ref([])
const previewLoading = ref(false)
const cooldownReady = ref(false)
/** @type {ReturnType<typeof setTimeout> | null} */
let cooldownTimer = null

/** @type {import('vue').Ref<MediaQueryList | null>} */
let mq = null
const isMobile = ref(false)

function onMq() {
  isMobile.value = mq?.matches ?? false
}

const displayTitle = computed(() => {
  const t = (props.conversation?.title ?? '').trim()
  if (!t || t === '新对话') return '未命名会话'
  return t
})

const cardOriginStyle = computed(() => {
  if (isMobile.value || !props.anchor) {
    return { transformOrigin: '50% 50%' }
  }
  return { transformOrigin: `${props.anchor.x}px ${props.anchor.y}px` }
})

async function loadPreview() {
  const id = props.conversation?.id
  if (id == null) {
    previewUrls.value = []
    return
  }
  previewLoading.value = true
  previewUrls.value = []
  try {
    const { data } = await fetchConversationImages(id, { offset: 0, limit: 3 })
    const list = Array.isArray(data) ? data : []
    previewUrls.value = list.slice(0, 3).map((x) => x.imageUrl).filter(Boolean)
  } catch {
    previewUrls.value = []
  } finally {
    previewLoading.value = false
  }
}

watch(
  () => props.open,
  (o) => {
    window.clearTimeout(cooldownTimer ?? undefined)
    cooldownTimer = null
    if (o) {
      cooldownReady.value = false
      cooldownTimer = window.setTimeout(() => {
        cooldownReady.value = true
        cooldownTimer = null
      }, 500)
      loadPreview()
    } else {
      cooldownReady.value = false
      previewUrls.value = []
    }
  },
)

function close() {
  if (props.submitting) return
  emit('update:open', false)
}

function confirm() {
  if (!cooldownReady.value || props.submitting) return
  emit('confirm')
}

function onKeydown(e) {
  if (!props.open) return
  if (e.key === 'Escape') {
    e.preventDefault()
    close()
    return
  }
  if (e.key === 'Enter' && !e.repeat) {
    if (!cooldownReady.value || props.submitting) return
    e.preventDefault()
    emit('confirm')
  }
}

onMounted(() => {
  mq = window.matchMedia('(max-width: 768px)')
  onMq()
  mq.addEventListener('change', onMq)
  window.addEventListener('keydown', onKeydown)
})

onUnmounted(() => {
  mq?.removeEventListener('change', onMq)
  window.removeEventListener('keydown', onKeydown)
  window.clearTimeout(cooldownTimer ?? undefined)
})

const confirmDisabled = computed(
  () => !cooldownReady.value || props.submitting,
)
</script>

<template>
  <Teleport to="body">
    <Transition name="delconv-shell">
      <div
        v-if="open && conversation"
        class="delconv-shell"
        role="dialog"
        aria-labelledby="delconv-title"
        aria-modal="true"
      >
        <div class="delconv-backdrop" @click.self="close" />
        <div class="delconv-center">
          <div class="delconv-card" :style="cardOriginStyle">
            <div class="delconv-card-inner">
              <div class="delconv-icon-wrap" aria-hidden="true">
                <span class="delconv-icon-glow" />
                <svg class="delconv-icon" viewBox="0 0 24 24" fill="none">
                  <path
                    d="M9 3h6l1 2h4v2H4V5h4l1-2zm1 5h4v12l-1 1H11l-1-1V8zm-5 3h2v8H5v-8zm12 0h2v8h-2v-8z"
                    fill="currentColor"
                  />
                </svg>
              </div>

              <h2 id="delconv-title" class="delconv-title">删除对话</h2>
              <p class="delconv-desc">
                确定要删除「{{ displayTitle }}」吗？此操作不可恢复，相关图片作品也将被移除。
              </p>

              <div v-if="previewLoading || previewUrls.length > 0" class="delconv-preview-block">
                <p class="delconv-preview-label">以下作品将被删除</p>
                <div class="delconv-preview-row">
                  <template v-if="previewLoading">
                    <span v-for="n in 3" :key="'sk-' + n" class="delconv-thumb delconv-thumb--skel" />
                  </template>
                  <template v-else>
                    <img
                      v-for="(url, i) in previewUrls"
                      :key="i"
                      :src="url"
                      alt=""
                      class="delconv-thumb"
                    />
                  </template>
                </div>
              </div>

              <p v-if="error" class="delconv-err">{{ error }}</p>

              <div class="delconv-actions" :class="{ 'delconv-actions--mobile': isMobile }">
                <template v-if="isMobile">
                  <button
                    type="button"
                    class="delconv-btn delconv-btn--danger"
                    :disabled="confirmDisabled"
                    @click="confirm"
                  >
                    <span v-if="submitting" class="delconv-spin" aria-hidden="true" />
                    <svg
                      v-else
                      class="delconv-btn-ic"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="2"
                    >
                      <path
                        d="M9 3h6l1 2h4v2H4V5h4l1-2zm0 5h12l-1 14H10L9 8z"
                        stroke-linejoin="round"
                      />
                    </svg>
                    {{ submitting ? '删除中…' : '确认删除' }}
                  </button>
                  <button type="button" class="delconv-btn delconv-btn--ghost" :disabled="submitting" @click="close">
                    取消
                  </button>
                </template>
                <template v-else>
                  <button type="button" class="delconv-btn delconv-btn--ghost" :disabled="submitting" @click="close">
                    取消
                  </button>
                  <button
                    type="button"
                    class="delconv-btn delconv-btn--danger"
                    :disabled="confirmDisabled"
                    @click="confirm"
                  >
                    <span v-if="submitting" class="delconv-spin" aria-hidden="true" />
                    <svg
                      v-else
                      class="delconv-btn-ic"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="2"
                    >
                      <path
                        d="M9 3h6l1 2h4v2H4V5h4l1-2zm0 5h12l-1 14H10L9 8z"
                        stroke-linejoin="round"
                      />
                    </svg>
                    {{ submitting ? '删除中…' : '确认删除' }}
                  </button>
                </template>
              </div>
            </div>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.delconv-shell {
  position: fixed;
  inset: 0;
  z-index: 10080;
  display: flex;
  align-items: stretch;
  justify-content: center;
  pointer-events: auto;
}

.delconv-backdrop {
  position: absolute;
  inset: 0;
  background: var(--chat-backdrop);
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
}

.delconv-center {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  min-height: 100%;
  padding: max(24px, env(safe-area-inset-top, 0px)) max(24px, env(safe-area-inset-right, 0px))
    max(24px, env(safe-area-inset-bottom, 0px)) max(24px, env(safe-area-inset-left, 0px));
  box-sizing: border-box;
}

.delconv-card {
  width: 100%;
  max-width: 392px;
  border-radius: 22px;
  background: color-mix(in srgb, var(--chat-panel) 94%, transparent);
  backdrop-filter: blur(24px);
  -webkit-backdrop-filter: blur(24px);
  border: 1px solid var(--chat-border-strong);
  box-shadow: var(--chat-panel-shadow), inset 0 1px 0 var(--chat-toolbar-divider);
}

.delconv-card-inner {
  padding: 26px 24px 22px;
}

.delconv-icon-wrap {
  position: relative;
  width: 56px;
  height: 56px;
  margin: 0 auto 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: hsl(0, 52%, 58%);
}

.delconv-icon-glow {
  position: absolute;
  inset: -8px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(239, 68, 68, 0.22) 0%, transparent 68%);
  animation: delconv-pulse 2s ease-in-out infinite;
}

@keyframes delconv-pulse {
  0%,
  100% {
    opacity: 0.55;
    transform: scale(1);
  }
  50% {
    opacity: 1;
    transform: scale(1.06);
  }
}

.delconv-icon {
  position: relative;
  width: 30px;
  height: 30px;
  filter: drop-shadow(0 2px 8px rgba(239, 68, 68, 0.25));
}

.delconv-title {
  margin: 0 0 12px;
  font-size: 1.0625rem;
  font-weight: 700;
  color: var(--chat-fg-strong);
  text-align: center;
  letter-spacing: -0.02em;
}

.delconv-desc {
  margin: 0;
  font-size: 0.8125rem;
  line-height: 1.6;
  color: var(--chat-muted);
  text-align: center;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.delconv-preview-block {
  margin-top: 16px;
}

.delconv-preview-label {
  margin: 0 0 8px;
  font-size: 0.6875rem;
  color: var(--chat-muted-3);
  text-align: center;
  letter-spacing: 0.06em;
}

.delconv-preview-row {
  display: flex;
  gap: 8px;
  justify-content: center;
  flex-wrap: wrap;
}

.delconv-thumb {
  width: 52px;
  height: 40px;
  object-fit: cover;
  border-radius: 6px;
  border: 1px solid var(--chat-border);
}

.delconv-thumb--skel {
  background: linear-gradient(
    110deg,
    var(--chat-btn-bg) 0%,
    color-mix(in srgb, var(--chat-border-strong) 75%, var(--chat-btn-bg)) 45%,
    var(--chat-btn-bg) 90%
  );
  background-size: 200% 100%;
  animation: delconv-sh 1s ease-in-out infinite;
}

@keyframes delconv-sh {
  to {
    background-position: -100% 0;
  }
}

.delconv-err {
  margin: 12px 0 0;
  font-size: 0.75rem;
  color: var(--chat-danger-fg);
  text-align: center;
}

.delconv-actions {
  display: flex;
  flex-direction: row;
  justify-content: flex-end;
  align-items: center;
  gap: 12px;
  margin-top: 22px;
}

.delconv-actions--mobile {
  flex-direction: column;
  justify-content: stretch;
}

.delconv-btn {
  height: 42px;
  padding: 0 18px;
  border-radius: 11px;
  font-size: 0.875rem;
  font-weight: 600;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  transition:
    transform 0.18s ease,
    background 0.2s ease,
    border-color 0.2s ease,
    box-shadow 0.22s ease,
    opacity 0.2s ease;
}

.delconv-btn:disabled {
  cursor: not-allowed;
}

.delconv-btn--ghost {
  border: 1px solid var(--chat-border-strong);
  background: transparent;
  color: var(--chat-muted-2);
}

.delconv-btn--ghost:hover:not(:disabled) {
  background: var(--chat-btn-bg-hover);
  color: var(--chat-fg-strong);
}

.delconv-btn--ghost:active:not(:disabled) {
  transform: scale(0.97);
}

.delconv-btn--danger {
  border: none;
  color: #fff;
  background: linear-gradient(145deg, hsl(0, 56%, 52%), hsl(0, 58%, 46%));
  box-shadow: 0 8px 28px rgba(220, 38, 38, 0.15);
  min-width: 132px;
}

.delconv-btn--danger:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 12px 36px rgba(220, 38, 38, 0.22);
  filter: brightness(1.05);
}

.delconv-btn--danger:active:not(:disabled) {
  transform: scale(0.96);
}

.delconv-btn--danger:disabled {
  opacity: 0.45;
}

.delconv-actions--mobile .delconv-btn {
  width: 100%;
}

.delconv-actions--mobile .delconv-btn--danger:active:not(:disabled) {
  transform: scale(0.98);
}

.delconv-btn-ic {
  width: 17px;
  height: 17px;
  flex-shrink: 0;
}

.delconv-spin {
  width: 17px;
  height: 17px;
  border: 2px solid rgba(255, 255, 255, 0.35);
  border-top-color: #fff;
  border-radius: 50%;
  animation: delconv-spin 0.7s linear infinite;
}

@keyframes delconv-spin {
  to {
    transform: rotate(360deg);
  }
}

/* 入场 / 离场（桌面 scale，移动端滑入） */
.delconv-shell-enter-active,
.delconv-shell-leave-active {
  transition: opacity 0.25s cubic-bezier(0.34, 1.45, 0.64, 1);
}

.delconv-shell-enter-active .delconv-card,
.delconv-shell-leave-active .delconv-card {
  transition:
    transform 0.26s cubic-bezier(0.34, 1.45, 0.64, 1),
    opacity 0.24s cubic-bezier(0.34, 1.45, 0.64, 1);
}

.delconv-shell-enter-from,
.delconv-shell-leave-to {
  opacity: 0;
}

.delconv-shell-enter-from .delconv-card,
.delconv-shell-leave-to .delconv-card {
  opacity: 0;
  transform: scale(0.92);
}

.delconv-shell-enter-to .delconv-card,
.delconv-shell-leave-from .delconv-card {
  opacity: 1;
  transform: scale(1);
}

@media (max-width: 768px) {
  .delconv-center {
    align-items: flex-end;
    padding: 0;
    padding-bottom: env(safe-area-inset-bottom, 0px);
  }

  .delconv-card {
    max-width: none;
    width: min(
      92vw,
      calc(100vw - env(safe-area-inset-left, 0px) - env(safe-area-inset-right, 0px) - 8px)
    );
    margin: 0 auto;
    border-radius: 20px 20px 0 0;
    border-bottom: none;
  }

  .delconv-shell-enter-from .delconv-card,
  .delconv-shell-leave-to .delconv-card {
    transform: translateY(100%);
    opacity: 1;
  }

  .delconv-shell-enter-to .delconv-card,
  .delconv-shell-leave-from .delconv-card {
    transform: translateY(0);
    opacity: 1;
  }
}
</style>
