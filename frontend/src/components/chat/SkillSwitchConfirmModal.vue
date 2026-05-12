<script setup>
/**
 * 切换技能确认：与 DeleteConversationModal 一致的毛玻璃模态 / 移动端底部抽屉 / 防误触 / 可选「不再询问」。
 */
import { ref, watch, computed, onMounted, onUnmounted } from 'vue'

const props = defineProps({
  open: { type: Boolean, default: false },
  /** 当前技能展示名 */
  fromLabel: { type: String, default: '' },
  /** 目标技能展示名 */
  toLabel: { type: String, default: '' },
  submitting: { type: Boolean, default: false },
})

const emit = defineEmits(['update:open', 'confirm'])

const dontAskAgain = ref(false)
const cooldownReady = ref(false)
/** @type {ReturnType<typeof setTimeout> | null} */
let cooldownTimer = null

/** @type {import('vue').Ref<MediaQueryList | null>} */
let mq = null
const isMobile = ref(false)

function onMq() {
  isMobile.value = mq?.matches ?? false
}

watch(
  () => props.open,
  (o) => {
    window.clearTimeout(cooldownTimer ?? undefined)
    cooldownTimer = null
    if (o) {
      dontAskAgain.value = false
      cooldownReady.value = false
      cooldownTimer = window.setTimeout(() => {
        cooldownReady.value = true
        cooldownTimer = null
      }, 300)
    } else {
      cooldownReady.value = false
    }
  },
)

function close() {
  if (props.submitting) return
  emit('update:open', false)
}

function confirm() {
  if (!cooldownReady.value || props.submitting) return
  emit('confirm', { dontAskAgain: dontAskAgain.value })
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
    emit('confirm', { dontAskAgain: dontAskAgain.value })
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

const confirmDisabled = computed(() => !cooldownReady.value || props.submitting)

const displayFrom = computed(() => (props.fromLabel || '').trim() || '当前技能')
const displayTo = computed(() => (props.toLabel || '').trim() || '目标技能')
</script>

<template>
  <Teleport to="body">
    <Transition name="ssw-shell">
      <div
        v-if="open"
        class="skill-switch-shell"
        role="dialog"
        aria-labelledby="ssw-title"
        aria-modal="true"
      >
        <div class="ssw-backdrop" @click.self="close" />
        <div class="ssw-center">
          <div class="ssw-card">
            <div class="ssw-card-inner">
              <div class="ssw-icon-wrap" aria-hidden="true">
                <span class="ssw-icon-glow" />
                <svg class="ssw-icon" viewBox="0 0 24 24" fill="none">
                  <path
                    d="M8 7h9l-3.5-3.5M16 17H7l3.5 3.5"
                    stroke="currentColor"
                    stroke-width="1.85"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                  />
                  <path
                    d="M16 12H8"
                    stroke="currentColor"
                    stroke-width="1.85"
                    stroke-linecap="round"
                  />
                </svg>
              </div>

              <h2 id="ssw-title" class="ssw-title">切换技能</h2>
              <p class="ssw-desc">
                切换技能将开启新对话，当前对话的历史记录不会丢失，但新消息将使用新的技能模式。是否继续？
              </p>

              <div class="ssw-pills" aria-hidden="false">
                <span class="ssw-pill">{{ displayFrom }}</span>
                <span class="ssw-arrow" aria-hidden="true">→</span>
                <span class="ssw-pill ssw-pill--target">{{ displayTo }}</span>
              </div>

              <label class="ssw-check">
                <input v-model="dontAskAgain" type="checkbox" class="ssw-check-input" :disabled="submitting" />
                <span class="ssw-check-text">下次切换技能不再询问</span>
              </label>

              <div class="ssw-actions" :class="{ 'ssw-actions--mobile': isMobile }">
                <template v-if="isMobile">
                  <button
                    type="button"
                    class="ssw-btn ssw-btn--primary"
                    :class="{ 'ssw-btn--cooldown': confirmDisabled && !submitting }"
                    :disabled="confirmDisabled"
                    @click="confirm"
                  >
                    <span v-if="submitting" class="ssw-spin" aria-hidden="true" />
                    <svg
                      v-else
                      class="ssw-btn-ic"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="2"
                    >
                      <path
                        d="M8 7h9l-3.5-3.5M16 17H7l3.5 3.5"
                        stroke-linecap="round"
                        stroke-linejoin="round"
                      />
                      <path d="M14 12H8" stroke-linecap="round" />
                    </svg>
                    {{ submitting ? '切换中…' : '确认切换' }}
                  </button>
                  <button type="button" class="ssw-btn ssw-btn--ghost" :disabled="submitting" @click="close">
                    取消
                  </button>
                </template>
                <template v-else>
                  <button type="button" class="ssw-btn ssw-btn--ghost" :disabled="submitting" @click="close">
                    取消
                  </button>
                  <button
                    type="button"
                    class="ssw-btn ssw-btn--primary"
                    :class="{ 'ssw-btn--cooldown': confirmDisabled && !submitting }"
                    :disabled="confirmDisabled"
                    @click="confirm"
                  >
                    <span v-if="submitting" class="ssw-spin" aria-hidden="true" />
                    <svg
                      v-else
                      class="ssw-btn-ic"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="2"
                    >
                      <path
                        d="M8 7h9l-3.5-3.5M16 17H7l3.5 3.5"
                        stroke-linecap="round"
                        stroke-linejoin="round"
                      />
                      <path d="M14 12H8" stroke-linecap="round" />
                    </svg>
                    {{ submitting ? '切换中…' : '确认切换' }}
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
.skill-switch-shell {
  position: fixed;
  inset: 0;
  z-index: 10085;
  display: flex;
  align-items: stretch;
  justify-content: center;
  pointer-events: auto;
}

.ssw-backdrop {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
}

.ssw-center {
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

.ssw-card {
  width: 100%;
  max-width: 400px;
  border-radius: 22px;
  background: rgba(26, 26, 30, 0.95);
  backdrop-filter: blur(24px);
  -webkit-backdrop-filter: blur(24px);
  border: 1px solid rgba(255, 255, 255, 0.08);
  box-shadow:
    0 24px 64px rgba(0, 0, 0, 0.45),
    inset 0 1px 0 rgba(255, 255, 255, 0.06);
}

html[data-theme='light'] .ssw-card {
  background: color-mix(in srgb, var(--chat-panel) 94%, transparent);
  border-color: rgba(15, 23, 42, 0.1);
  box-shadow: var(--chat-panel-shadow), inset 0 1px 0 rgba(255, 255, 255, 0.85);
}

.ssw-card-inner {
  padding: 26px 24px 22px;
}

.ssw-icon-wrap {
  position: relative;
  width: 56px;
  height: 56px;
  margin: 0 auto 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #10b981;
}

.ssw-icon-glow {
  position: absolute;
  inset: -10px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(16, 185, 129, 0.35) 0%, transparent 65%);
  animation: ssw-pulse 2.2s ease-in-out infinite;
}

@keyframes ssw-pulse {
  0%,
  100% {
    opacity: 0.45;
    transform: scale(1);
  }
  50% {
    opacity: 1;
    transform: scale(1.07);
  }
}

.ssw-icon {
  position: relative;
  width: 30px;
  height: 30px;
  filter: drop-shadow(0 2px 10px rgba(16, 185, 129, 0.35));
}

.ssw-title {
  margin: 0 0 12px;
  font-size: 1.0625rem;
  font-weight: 700;
  color: #fff;
  text-align: center;
  letter-spacing: -0.02em;
}

html[data-theme='light'] .ssw-title {
  color: var(--chat-fg-strong);
}

.ssw-desc {
  margin: 0;
  font-size: 0.8125rem;
  line-height: 1.6;
  color: rgba(255, 255, 255, 0.55);
  text-align: center;
}

html[data-theme='light'] .ssw-desc {
  color: color-mix(in srgb, var(--chat-muted) 85%, transparent);
}

.ssw-pills {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 18px;
}

.ssw-pill {
  max-width: 42%;
  padding: 8px 14px;
  border-radius: 999px;
  font-size: 0.72rem;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.88);
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.1);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

html[data-theme='light'] .ssw-pill {
  color: var(--chat-fg-strong);
  background: rgba(15, 23, 42, 0.05);
  border-color: rgba(15, 23, 42, 0.1);
}

.ssw-pill--target {
  border-color: rgba(16, 185, 129, 0.35);
  background: rgba(16, 185, 129, 0.12);
}

.ssw-arrow {
  font-size: 0.85rem;
  font-weight: 700;
  color: rgba(16, 185, 129, 0.85);
  flex-shrink: 0;
}

.ssw-check {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin-top: 16px;
  cursor: pointer;
  user-select: none;
}

.ssw-check-input {
  width: 14px;
  height: 14px;
  accent-color: #10b981;
  cursor: pointer;
}

.ssw-check-input:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.ssw-check-text {
  font-size: 0.6875rem;
  color: rgba(255, 255, 255, 0.45);
}

html[data-theme='light'] .ssw-check-text {
  color: var(--chat-muted-3);
}

.ssw-actions {
  display: flex;
  flex-direction: row;
  justify-content: stretch;
  align-items: center;
  gap: 12px;
  margin-top: 22px;
}

.ssw-actions--mobile {
  flex-direction: column;
}

.ssw-btn {
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
    transform 0.18s cubic-bezier(0.34, 1.45, 0.64, 1),
    background 0.2s ease,
    border-color 0.2s ease,
    box-shadow 0.22s ease,
    opacity 0.2s ease,
    filter 0.2s ease;
  flex: 1;
  min-width: 0;
}

.ssw-btn:disabled {
  cursor: not-allowed;
}

.ssw-btn--ghost {
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(255, 255, 255, 0.05);
  color: rgba(255, 255, 255, 0.72);
}

.ssw-btn--ghost:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
}

.ssw-btn--ghost:active:not(:disabled) {
  transform: scale(0.97);
}

html[data-theme='light'] .ssw-btn--ghost {
  border-color: rgba(15, 23, 42, 0.12);
  background: rgba(15, 23, 42, 0.04);
  color: var(--chat-muted-2);
}

html[data-theme='light'] .ssw-btn--ghost:hover:not(:disabled) {
  background: rgba(15, 23, 42, 0.08);
  color: var(--chat-fg-strong);
}

.ssw-btn--primary {
  flex: 1.15;
  border: none;
  color: #fff;
  font-weight: 700;
  background: linear-gradient(145deg, #10b981 0%, #34d399 100%);
  box-shadow: 0 8px 28px rgba(16, 185, 129, 0.28);
}

.ssw-btn--primary:hover:not(:disabled):not(.ssw-btn--cooldown) {
  transform: translateY(-2px);
  box-shadow: 0 14px 40px rgba(16, 185, 129, 0.38);
  filter: brightness(1.04);
}

.ssw-btn--primary:active:not(:disabled) {
  transform: scale(0.96);
}

.ssw-btn--primary.ssw-btn--cooldown {
  opacity: 0.42;
}

.ssw-btn--primary:disabled {
  opacity: 0.55;
  transform: none;
  filter: none;
}

.ssw-actions--mobile .ssw-btn {
  width: 100%;
  flex: none;
}

.ssw-btn-ic {
  width: 17px;
  height: 17px;
  flex-shrink: 0;
}

.ssw-spin {
  width: 17px;
  height: 17px;
  border: 2px solid rgba(255, 255, 255, 0.35);
  border-top-color: #fff;
  border-radius: 50%;
  animation: ssw-spin 0.65s linear infinite;
}

@keyframes ssw-spin {
  to {
    transform: rotate(360deg);
  }
}

/* 入场 / 离场 */
.ssw-shell-enter-active,
.ssw-shell-leave-active {
  transition: opacity 0.25s cubic-bezier(0.34, 1.45, 0.64, 1);
}

.ssw-shell-enter-active .ssw-card,
.ssw-shell-leave-active .ssw-card {
  transition:
    transform 0.26s cubic-bezier(0.34, 1.45, 0.64, 1),
    opacity 0.24s cubic-bezier(0.34, 1.45, 0.64, 1);
}

.ssw-shell-enter-from,
.ssw-shell-leave-to {
  opacity: 0;
}

.ssw-shell-enter-from .ssw-card,
.ssw-shell-leave-to .ssw-card {
  opacity: 0;
  transform: scale(0.92);
}

.ssw-shell-enter-to .ssw-card,
.ssw-shell-leave-from .ssw-card {
  opacity: 1;
  transform: scale(1);
}

@media (max-width: 768px) {
  .ssw-center {
    align-items: flex-end;
    padding: 0;
    padding-bottom: env(safe-area-inset-bottom, 0px);
  }

  .ssw-card {
    max-width: none;
    width: min(
      92vw,
      calc(100vw - env(safe-area-inset-left, 0px) - env(safe-area-inset-right, 0px) - 8px)
    );
    margin: 0 auto;
    border-radius: 20px 20px 0 0;
    border-bottom: none;
  }

  .ssw-shell-enter-from .ssw-card,
  .ssw-shell-leave-to .ssw-card {
    transform: translateY(100%);
    opacity: 1;
  }

  .ssw-shell-enter-to .ssw-card,
  .ssw-shell-leave-from .ssw-card {
    transform: translateY(0);
    opacity: 1;
  }
}
</style>
