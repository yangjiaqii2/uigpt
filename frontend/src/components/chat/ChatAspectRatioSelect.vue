<script setup>
/**
 * 出图比例：毛玻璃触发器 + 桌面下拉 / 移动端底部抽屉；键盘 ↑↓ Enter Esc。
 */
import {
  ref,
  computed,
  watch,
  inject,
  onMounted,
  onUnmounted,
  nextTick,
  useId,
} from 'vue'
import { UNIVERSAL_ASPECT_OPTIONS } from '../../constants/universalGenParams'

const props = defineProps({
  modelValue: { type: String, required: true },
  disabled: { type: Boolean, default: false },
  ariaLabel: { type: String, default: '出图比例' },
})

const emit = defineEmits(['update:modelValue'])

const dockGlassSelectKey = inject('dockGlassSelectKey', null)
const instanceKey = useId()

const BRAND = 'var(--chat-link-accent-fg, #5ee1d5)'

const open = ref(false)
const triggerRef = ref(null)
const panelRef = ref(null)
const sheetRef = ref(null)
const activeIdx = ref(0)

const isMobileUi = ref(false)
let mq

function syncMobile() {
  if (typeof window === 'undefined') return
  isMobileUi.value = window.matchMedia('(max-width: 768px)').matches
}

const panelStyle = ref({
  position: 'fixed',
  top: '0px',
  left: '0px',
  width: '168px',
  zIndex: '12050',
})

function aspectShape(aspectStr) {
  const parts = String(aspectStr).split(':').map(Number)
  if (parts.length !== 2 || !Number.isFinite(parts[0]) || !Number.isFinite(parts[1])) {
    return 'landscape'
  }
  const [w, h] = parts
  if (w <= 0 || h <= 0) return 'landscape'
  if (Math.abs(w - h) < 1e-6) return 'square'
  const r = w / h
  if (r >= 2.1) return 'ultrawide'
  if (w < h) return 'portrait'
  return 'landscape'
}

function close() {
  if (open.value && dockGlassSelectKey && dockGlassSelectKey.value === instanceKey) {
    dockGlassSelectKey.value = null
  }
  open.value = false
}

function toggle() {
  if (props.disabled) return
  if (open.value) {
    close()
    return
  }
  if (dockGlassSelectKey) dockGlassSelectKey.value = instanceKey
  open.value = true
}

function selectValue(val) {
  emit('update:modelValue', val)
  close()
}

function schedulePosition() {
  requestAnimationFrame(() => {
    updatePanelPosition()
    requestAnimationFrame(() => updatePanelPosition())
  })
}

function updatePanelPosition() {
  const trigger = triggerRef.value
  if (!trigger || !open.value || isMobileUi.value) return
  const r = trigger.getBoundingClientRect()
  const w = Math.min(Math.max(r.width, 168), 180)
  const vpW = window.innerWidth
  const vpH = window.innerHeight
  let left = r.left
  left = Math.max(10, Math.min(left, vpW - w - 10))
  const gap = 6
  let top = r.bottom + gap
  const panel = panelRef.value
  let ph = 280
  if (panel) {
    ph = panel.offsetHeight || ph
  }
  if (top + ph > vpH - 12) {
    top = Math.max(12, r.top - gap - ph)
  }
  panelStyle.value = {
    position: 'fixed',
    top: `${top}px`,
    left: `${left}px`,
    width: `${w}px`,
    zIndex: '12050',
  }
}

function onDocPointerDown(e) {
  if (!open.value) return
  const t = e.target
  if (triggerRef.value?.contains(t)) return
  if (panelRef.value?.contains(t)) return
  if (sheetRef.value?.contains(t)) return
  close()
}

function onGlobalKeydown(e) {
  if (!open.value || props.disabled) return
  if (e.key === 'Escape') {
    e.preventDefault()
    close()
    triggerRef.value?.focus()
    return
  }
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    activeIdx.value = Math.min(UNIVERSAL_ASPECT_OPTIONS.length - 1, activeIdx.value + 1)
    return
  }
  if (e.key === 'ArrowUp') {
    e.preventDefault()
    activeIdx.value = Math.max(0, activeIdx.value - 1)
    return
  }
  if (e.key === 'Enter') {
    e.preventDefault()
    const v = UNIVERSAL_ASPECT_OPTIONS[activeIdx.value]
    if (v) selectValue(v)
    return
  }
}

watch(open, (v) => {
  if (v) {
    const i = UNIVERSAL_ASPECT_OPTIONS.indexOf(props.modelValue)
    activeIdx.value = i >= 0 ? i : 0
    nextTick(() => {
      if (!isMobileUi.value) schedulePosition()
    })
    document.addEventListener('keydown', onGlobalKeydown, true)
    window.addEventListener('scroll', schedulePosition, true)
    window.addEventListener('resize', schedulePosition)
  } else {
    document.removeEventListener('keydown', onGlobalKeydown, true)
    window.removeEventListener('scroll', schedulePosition, true)
    window.removeEventListener('resize', schedulePosition)
  }
})

if (dockGlassSelectKey) {
  watch(dockGlassSelectKey, (k) => {
    if (k !== instanceKey) open.value = false
  })
}

watch(
  () => props.modelValue,
  () => {
    if (open.value) {
      const i = UNIVERSAL_ASPECT_OPTIONS.indexOf(props.modelValue)
      if (i >= 0) activeIdx.value = i
    }
  },
)

watch(
  [open, isMobileUi],
  ([o, mobile]) => {
    if (typeof document === 'undefined') return
    document.body.style.overflow = o && mobile ? 'hidden' : ''
  },
  { flush: 'post' },
)

onMounted(() => {
  syncMobile()
  mq = window.matchMedia('(max-width: 768px)')
  mq.addEventListener('change', syncMobile)
  document.addEventListener('pointerdown', onDocPointerDown, true)
})

onUnmounted(() => {
  if (typeof document !== 'undefined') document.body.style.overflow = ''
  mq?.removeEventListener('change', syncMobile)
  document.removeEventListener('pointerdown', onDocPointerDown, true)
  document.removeEventListener('keydown', onGlobalKeydown, true)
  window.removeEventListener('scroll', schedulePosition, true)
  window.removeEventListener('resize', schedulePosition)
  if (dockGlassSelectKey && dockGlassSelectKey.value === instanceKey) {
    dockGlassSelectKey.value = null
  }
})

function onOptionEnter(idx) {
  activeIdx.value = idx
}

const triggerShape = computed(() => aspectShape(props.modelValue))
</script>

<template>
  <div class="ar-root">
    <button
      ref="triggerRef"
      type="button"
      class="ar-trigger"
      :class="{ 'ar-trigger--open': open, 'ar-trigger--mobile': isMobileUi }"
      :disabled="disabled"
      :aria-expanded="open"
      :aria-haspopup="true"
      :aria-label="ariaLabel"
      @click.stop="toggle"
    >
      <span class="ar-trigger-icon" aria-hidden="true">
        <svg
          class="ar-shape-svg"
          width="14"
          height="14"
          viewBox="0 0 16 16"
          fill="none"
          xmlns="http://www.w3.org/2000/svg"
        >
          <rect
            v-if="triggerShape === 'square'"
            x="2.25"
            y="2.25"
            width="11.5"
            height="11.5"
            rx="1"
            stroke="currentColor"
            stroke-width="1.5"
          />
          <rect
            v-else-if="triggerShape === 'portrait'"
            x="4.5"
            y="1.5"
            width="7"
            height="13"
            rx="1"
            stroke="currentColor"
            stroke-width="1.5"
          />
          <rect
            v-else-if="triggerShape === 'ultrawide'"
            x="1"
            y="5"
            width="14"
            height="6"
            rx="1"
            stroke="currentColor"
            stroke-width="1.5"
          />
          <rect
            v-else
            x="1.5"
            y="4.5"
            width="13"
            height="7"
            rx="1"
            stroke="currentColor"
            stroke-width="1.5"
          />
        </svg>
      </span>
      <span class="ar-trigger-label">{{ modelValue }}</span>
      <svg
        class="ar-chevron"
        :class="{ 'ar-chevron--open': open }"
        width="10"
        height="10"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        <path d="M6 9l6 6 6-6" />
      </svg>
    </button>

    <!-- 桌面下拉 -->
    <Teleport to="body">
      <Transition name="ar-desk">
        <div
          v-if="open && !isMobileUi"
          ref="panelRef"
          class="ar-panel"
          :style="panelStyle"
          role="listbox"
          :aria-label="ariaLabel"
          @click.stop
        >
          <div class="ar-panel-shine" aria-hidden="true" />
          <div class="ar-panel-body">
            <button
              v-for="(opt, idx) in UNIVERSAL_ASPECT_OPTIONS"
              :key="opt"
              type="button"
              role="option"
              class="ar-option"
              :class="{
                'ar-option--selected': opt === modelValue,
                'ar-option--active': idx === activeIdx,
              }"
              :aria-selected="opt === modelValue"
              @mouseenter="onOptionEnter(idx)"
              @click="selectValue(opt)"
            >
              <span class="ar-option-icon" aria-hidden="true">
                <svg
                  class="ar-shape-svg ar-shape-svg--opt"
                  width="16"
                  height="16"
                  viewBox="0 0 16 16"
                  fill="none"
                  xmlns="http://www.w3.org/2000/svg"
                >
                  <rect
                    v-if="aspectShape(opt) === 'square'"
                    x="2"
                    y="2"
                    width="12"
                    height="12"
                    rx="1.25"
                    stroke="currentColor"
                    stroke-width="1.5"
                  />
                  <rect
                    v-else-if="aspectShape(opt) === 'portrait'"
                    x="4.25"
                    y="1.25"
                    width="7.5"
                    height="13.5"
                    rx="1.25"
                    stroke="currentColor"
                    stroke-width="1.5"
                  />
                  <rect
                    v-else-if="aspectShape(opt) === 'ultrawide'"
                    x="0.75"
                    y="5"
                    width="14.5"
                    height="6"
                    rx="1.25"
                    stroke="currentColor"
                    stroke-width="1.5"
                  />
                  <rect
                    v-else
                    x="1.25"
                    y="4.25"
                    width="13.5"
                    height="7.5"
                    rx="1.25"
                    stroke="currentColor"
                    stroke-width="1.5"
                  />
                </svg>
              </span>
              <span class="ar-option-text">{{ opt }}</span>
              <svg
                v-if="opt === modelValue"
                class="ar-check"
                width="14"
                height="14"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2.2"
                aria-hidden="true"
              >
                <path d="M20 6L9 17l-5-5" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
              <span v-else class="ar-check-spacer" aria-hidden="true" />
            </button>
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- 移动端底部抽屉 -->
    <Teleport to="body">
      <Transition name="ar-backdrop">
        <div
          v-if="open && isMobileUi"
          class="ar-sheet-backdrop"
          aria-hidden="true"
          @click="close"
        />
      </Transition>
      <Transition name="ar-sheet">
        <div
          v-if="open && isMobileUi"
          ref="sheetRef"
          class="ar-sheet"
          role="listbox"
          :aria-label="ariaLabel"
          @click.stop
        >
          <div class="ar-sheet-handle-wrap" aria-hidden="true">
            <span class="ar-sheet-handle" />
          </div>
          <div class="ar-sheet-shine" aria-hidden="true" />
          <div class="ar-sheet-body">
            <button
              v-for="(opt, idx) in UNIVERSAL_ASPECT_OPTIONS"
              :key="opt"
              type="button"
              role="option"
              class="ar-option ar-option--sheet"
              :class="{
                'ar-option--selected': opt === modelValue,
                'ar-option--active': idx === activeIdx,
              }"
              :aria-selected="opt === modelValue"
              @mouseenter="onOptionEnter(idx)"
              @click="selectValue(opt)"
            >
              <span class="ar-option-icon" aria-hidden="true">
                <svg
                  class="ar-shape-svg ar-shape-svg--opt"
                  width="16"
                  height="16"
                  viewBox="0 0 16 16"
                  fill="none"
                  xmlns="http://www.w3.org/2000/svg"
                >
                  <rect
                    v-if="aspectShape(opt) === 'square'"
                    x="2"
                    y="2"
                    width="12"
                    height="12"
                    rx="1.25"
                    stroke="currentColor"
                    stroke-width="1.5"
                  />
                  <rect
                    v-else-if="aspectShape(opt) === 'portrait'"
                    x="4.25"
                    y="1.25"
                    width="7.5"
                    height="13.5"
                    rx="1.25"
                    stroke="currentColor"
                    stroke-width="1.5"
                  />
                  <rect
                    v-else-if="aspectShape(opt) === 'ultrawide'"
                    x="0.75"
                    y="5"
                    width="14.5"
                    height="6"
                    rx="1.25"
                    stroke="currentColor"
                    stroke-width="1.5"
                  />
                  <rect
                    v-else
                    x="1.25"
                    y="4.25"
                    width="13.5"
                    height="7.5"
                    rx="1.25"
                    stroke="currentColor"
                    stroke-width="1.5"
                  />
                </svg>
              </span>
              <span class="ar-option-text">{{ opt }}</span>
              <svg
                v-if="opt === modelValue"
                class="ar-check"
                width="14"
                height="14"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2.2"
                aria-hidden="true"
              >
                <path d="M20 6L9 17l-5-5" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
              <span v-else class="ar-check-spacer" aria-hidden="true" />
            </button>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<style scoped>
.ar-root {
  position: relative;
  display: inline-flex;
  min-width: 168px;
  vertical-align: middle;
}

/* —— 触发器 —— */
.ar-trigger {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  height: 40px;
  min-height: 40px;
  padding: 0 12px 0 12px;
  border-radius: 11px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(26, 26, 30, 0.9);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.02em;
  cursor: pointer;
  outline: none;
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.04) inset,
    0 0 0 1px rgba(0, 0, 0, 0.2) inset;
  transition:
    background 0.2s cubic-bezier(0.22, 1, 0.36, 1),
    border-color 0.2s ease,
    box-shadow 0.2s ease,
    transform 0.2s cubic-bezier(0.22, 1, 0.36, 1);
}

.ar-trigger--mobile {
  height: 32px;
  min-height: 32px;
  padding: 0 10px;
  font-size: 12px;
  border-radius: 10px;
}

.ar-trigger:hover:not(:disabled):not(.ar-trigger--open) {
  background: rgba(34, 34, 40, 0.92);
  border-color: rgba(255, 255, 255, 0.12);
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.06) inset,
    0 0 28px rgba(94, 225, 213, 0.12),
    0 8px 24px rgba(0, 0, 0, 0.22);
  transform: translateY(-1px);
}

.ar-trigger--open:not(:disabled) {
  border-color: rgba(94, 225, 213, 0.28);
  box-shadow:
    0 0 0 1px rgba(94, 225, 213, 0.15),
    0 0 24px rgba(94, 225, 213, 0.1);
}

.ar-trigger:disabled {
  opacity: 0.45;
  cursor: not-allowed;
  transform: none;
}

.ar-trigger-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: rgba(255, 255, 255, 0.72);
}

.ar-shape-svg {
  display: block;
  color: rgba(255, 255, 255, 0.55);
}

.ar-trigger:hover:not(:disabled) .ar-shape-svg {
  color: rgba(255, 255, 255, 0.85);
}

.ar-trigger-label {
  flex: 1;
  min-width: 0;
  text-align: left;
  white-space: nowrap;
}

.ar-chevron {
  flex-shrink: 0;
  opacity: 0.55;
  transition:
    transform 0.22s cubic-bezier(0.34, 1.2, 0.64, 1),
    opacity 0.2s ease,
    color 0.2s ease,
    stroke 0.2s ease;
}

.ar-trigger:hover:not(:disabled) .ar-chevron {
  opacity: 0.95;
  color: v-bind(BRAND);
}

.ar-chevron--open {
  transform: rotate(180deg);
  opacity: 1;
  color: v-bind(BRAND);
}

/* —— 桌面面板 —— */
.ar-panel {
  position: relative;
  padding: 8px;
  border-radius: 15px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(22, 22, 24, 0.95);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  box-shadow:
    0 0 0 1px rgba(255, 255, 255, 0.04) inset,
    0 12px 32px rgba(0, 0, 0, 0.2);
  overflow: hidden;
}

.ar-panel-shine {
  pointer-events: none;
  position: absolute;
  left: 0;
  right: 0;
  top: 0;
  height: 36px;
  border-radius: 15px 15px 0 0;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.07) 0%, transparent 75%);
}

.ar-panel-body {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: min(52vh, 360px);
  overflow-y: auto;
  overscroll-behavior: contain;
}

/* —— 选项 —— */
.ar-option {
  display: flex;
  align-items: center;
  width: 100%;
  min-height: 42px;
  height: 42px;
  padding: 0 10px;
  gap: 10px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: rgba(255, 255, 255, 0.7);
  font-size: 13px;
  font-weight: 500;
  text-align: left;
  cursor: pointer;
  outline: none;
  transition:
    background 0.15s ease,
    color 0.15s ease,
    transform 0.15s ease;
}

.ar-option-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: rgba(255, 255, 255, 0.45);
  transition:
    color 0.15s ease,
    transform 0.15s ease;
}

.ar-shape-svg--opt {
  width: 16px;
  height: 16px;
}

.ar-option-text {
  flex: 1;
  min-width: 0;
}

.ar-check {
  flex-shrink: 0;
  color: v-bind(BRAND);
}

.ar-check-spacer {
  width: 14px;
  flex-shrink: 0;
}

.ar-option:hover:not(.ar-option--selected) {
  background: rgba(255, 255, 255, 0.05);
  color: rgba(255, 255, 255, 0.96);
}

.ar-option:hover:not(.ar-option--selected) .ar-option-icon {
  color: rgba(255, 255, 255, 0.85);
  transform: scale(1.06);
}

.ar-option--selected {
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
  font-weight: 700;
}

.ar-option--selected .ar-option-icon {
  color: v-bind(BRAND);
}

.ar-option--active:not(.ar-option--selected) {
  background: rgba(255, 255, 255, 0.04);
}

/* —— 动画：桌面 —— */
.ar-desk-enter-active {
  transition:
    opacity 0.2s cubic-bezier(0.22, 1, 0.36, 1),
    transform 0.2s cubic-bezier(0.34, 1.25, 0.64, 1);
}

.ar-desk-leave-active {
  transition:
    opacity 0.15s ease-in,
    transform 0.15s ease-in;
}

.ar-desk-enter-from,
.ar-desk-leave-to {
  opacity: 0;
  transform: translateY(-6px) scale(0.97);
}

.ar-desk-enter-to,
.ar-desk-leave-from {
  opacity: 1;
  transform: translateY(0) scale(1);
}

/* —— 移动端遮罩 + 抽屉 —— */
.ar-sheet-backdrop {
  position: fixed;
  inset: 0;
  z-index: 12055;
  background: rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(4px);
}

.ar-sheet {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 12056;
  width: 100%;
  max-height: min(70vh, 420px);
  padding: 0 12px calc(12px + env(safe-area-inset-bottom, 0px));
  border-radius: 16px 16px 0 0;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-bottom: none;
  background: rgba(22, 22, 24, 0.97);
  backdrop-filter: blur(24px);
  -webkit-backdrop-filter: blur(24px);
  box-shadow:
    0 -8px 40px rgba(0, 0, 0, 0.35),
    0 0 0 1px rgba(255, 255, 255, 0.05) inset;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.ar-sheet-handle-wrap {
  display: flex;
  justify-content: center;
  padding: 10px 0 6px;
  flex-shrink: 0;
}

.ar-sheet-handle {
  width: 36px;
  height: 4px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.18);
}

.ar-sheet-shine {
  pointer-events: none;
  position: absolute;
  left: 0;
  right: 0;
  top: 0;
  height: 40px;
  border-radius: 16px 16px 0 0;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.08) 0%, transparent 80%);
}

.ar-sheet-body {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding-bottom: 4px;
}

.ar-option--sheet {
  min-height: 48px;
  height: 48px;
}

.ar-backdrop-enter-active,
.ar-backdrop-leave-active {
  transition: opacity 0.2s ease;
}

.ar-backdrop-enter-from,
.ar-backdrop-leave-to {
  opacity: 0;
}

.ar-sheet-enter-active {
  transition: transform 0.28s cubic-bezier(0.22, 1, 0.36, 1), opacity 0.22s ease;
}

.ar-sheet-leave-active {
  transition: transform 0.2s ease ease-in, opacity 0.18s ease-in;
}

.ar-sheet-enter-from,
.ar-sheet-leave-to {
  opacity: 0.85;
  transform: translateY(100%);
}

.ar-sheet-enter-to,
.ar-sheet-leave-from {
  opacity: 1;
  transform: translateY(0);
}

/* —— 浅色 —— */
html[data-theme='light'] .ar-trigger {
  border-color: rgba(15, 23, 42, 0.12);
  background: rgba(255, 255, 255, 0.72);
  color: #0f172a;
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.9) inset,
    0 1px 3px rgba(15, 23, 42, 0.06);
}

html[data-theme='light'] .ar-trigger:hover:not(:disabled):not(.ar-trigger--open) {
  background: rgba(255, 255, 255, 0.88);
  border-color: rgba(13, 148, 136, 0.22);
  box-shadow: 0 6px 22px rgba(15, 23, 42, 0.08);
}

html[data-theme='light'] .ar-trigger-icon .ar-shape-svg,
html[data-theme='light'] .ar-trigger:hover .ar-shape-svg {
  color: rgba(15, 23, 42, 0.55);
}

html[data-theme='light'] .ar-panel,
html[data-theme='light'] .ar-sheet {
  background: rgba(252, 253, 255, 0.94);
  border-color: rgba(15, 23, 42, 0.1);
  box-shadow:
    0 12px 32px rgba(15, 23, 42, 0.12),
    0 1px 0 rgba(255, 255, 255, 0.9) inset;
}

html[data-theme='light'] .ar-option {
  color: rgba(51, 65, 85, 0.72);
}

html[data-theme='light'] .ar-option-icon {
  color: rgba(51, 65, 85, 0.45);
}

html[data-theme='light'] .ar-option:hover:not(.ar-option--selected) {
  background: rgba(15, 23, 42, 0.06);
  color: #0f172a;
}

html[data-theme='light'] .ar-option--selected {
  background: rgba(13, 148, 136, 0.12);
  color: #0f172a;
}

html[data-theme='light'] .ar-sheet-backdrop {
  background: rgba(15, 23, 42, 0.35);
}

@media (max-width: 768px) {
  .ar-root {
    min-width: 0;
  }
}
</style>
