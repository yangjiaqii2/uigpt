<script setup>
/**
 * 高速自由对话模型：视觉与 {@link ChatAspectRatioSelect} 一致；列表高度约为旧版 2/3（更紧凑行高 + 更低 max-height）。
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
import {
  FAST_FREEFORM_MODEL_FAMILIES,
  labelForFastFreeformFamily,
} from '../../constants/fastFreeformModels'

const props = defineProps({
  modelValue: { type: String, required: true },
  disabled: { type: Boolean, default: false },
  ariaLabel: { type: String, default: '模型系列' },
  /** 参数区标签元素 id，与「模型系列」文案联动 */
  labelledBy: { type: String, default: '' },
  /** 仅显示角标字母的小按钮（用于输入栏左侧） */
  compact: { type: Boolean, default: false },
})

const emit = defineEmits(['update:modelValue'])

const dockGlassSelectKey = inject('dockGlassSelectKey', null)
const instanceKey = useId()

const BRAND = 'var(--chat-link-accent-fg)'

const flatList = computed(() => FAST_FREEFORM_MODEL_FAMILIES)

function flatIndexForId(id) {
  const i = flatList.value.findIndex((o) => o.id === id)
  return i >= 0 ? i : 0
}

/** @param {string} familyId */
function modelBadgeLetter(familyId) {
  switch (String(familyId || '').trim()) {
    case 'gpt':
      return 'G'
    case 'claude':
      return 'C'
    case 'gemini':
      return 'M'
    case 'grok':
      return 'K'
    case 'deepseek':
      return 'D'
    case 'zhipu':
      return 'Z'
    default:
      return '?'
  }
}

const displayLabel = computed(() => labelForFastFreeformFamily(props.modelValue))

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
  width: '320px',
  zIndex: '12050',
})

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

function selectValue(id) {
  emit('update:modelValue', id)
  close()
}

function schedulePosition() {
  requestAnimationFrame(() => {
    updatePanelPosition()
    requestAnimationFrame(() => updatePanelPosition())
  })
}

/** 选项行变高（说明折行）后略增可滚动区 */
const PANEL_SCROLL_MAX = 320

function updatePanelPosition() {
  const trigger = triggerRef.value
  if (!trigger || !open.value || isMobileUi.value) return
  const r = trigger.getBoundingClientRect()
  /* 面板需足够宽以完整展示各系列说明（中文一行较长） */
  const w = Math.min(Math.max(r.width, 300), 400)
  const vpW = window.innerWidth
  const vpH = window.innerHeight
  let left = r.left
  left = Math.max(10, Math.min(left, vpW - w - 10))
  const gap = 6
  let top = r.bottom + gap
  const panel = panelRef.value
  let ph = PANEL_SCROLL_MAX + 48
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
  const n = flatList.value.length
  if (n === 0) return
  if (e.key === 'Escape') {
    e.preventDefault()
    close()
    triggerRef.value?.focus()
    return
  }
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    activeIdx.value = Math.min(n - 1, activeIdx.value + 1)
    return
  }
  if (e.key === 'ArrowUp') {
    e.preventDefault()
    activeIdx.value = Math.max(0, activeIdx.value - 1)
    return
  }
  if (e.key === 'Enter') {
    e.preventDefault()
    const o = flatList.value[activeIdx.value]
    if (o) selectValue(o.id)
  }
}

watch(open, (v) => {
  if (v) {
    activeIdx.value = flatIndexForId(props.modelValue)
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
    if (open.value) activeIdx.value = flatIndexForId(props.modelValue)
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

function onOptionEnter(optIndex) {
  activeIdx.value = optIndex
}
</script>

<template>
  <div class="ffm-root" :class="{ 'ffm-root--compact': compact }">
    <button
      ref="triggerRef"
      type="button"
      class="ffm-trigger"
      :class="{
        'ffm-trigger--open': open,
        'ffm-trigger--mobile': isMobileUi && !compact,
        'ffm-trigger--compact': compact,
      }"
      :disabled="disabled"
      :aria-expanded="open"
      :aria-haspopup="true"
      :aria-labelledby="compact ? undefined : labelledBy || undefined"
      :aria-label="compact || !labelledBy ? `${ariaLabel}：${displayLabel}` : undefined"
      @click.stop="toggle"
    >
      <span class="ffm-trigger-letter" aria-hidden="true">{{ modelBadgeLetter(modelValue) }}</span>
      <span v-if="!compact" class="ffm-trigger-label">{{ displayLabel }}</span>
      <svg
        v-if="!compact"
        class="ffm-chevron"
        :class="{ 'ffm-chevron--open': open }"
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
      <svg
        v-else
        class="ffm-chevron ffm-chevron--compact"
        :class="{ 'ffm-chevron--open': open }"
        width="8"
        height="8"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2.2"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        <path d="M6 9l6 6 6-6" />
      </svg>
    </button>

    <Teleport to="body">
      <Transition name="ffm-desk">
        <div
          v-if="open && !isMobileUi"
          ref="panelRef"
          class="ffm-panel"
          :style="panelStyle"
          role="listbox"
          :aria-label="ariaLabel"
          @click.stop
        >
          <div class="ffm-panel-shine" aria-hidden="true" />
          <div class="ffm-panel-body">
            <div class="ffm-group-title" role="presentation">模型系列</div>
            <button
              v-for="(o, oi) in flatList"
              :key="o.id"
              type="button"
              role="option"
              class="ffm-option"
              :class="{
                'ffm-option--selected': o.id === modelValue,
                'ffm-option--active': oi === activeIdx,
              }"
              :aria-selected="o.id === modelValue"
              @mouseenter="onOptionEnter(oi)"
              @click="selectValue(o.id)"
            >
              <span class="ffm-opt-letter" aria-hidden="true">{{ modelBadgeLetter(o.id) }}</span>
              <span class="ffm-opt-main">
                <span class="ffm-opt-label">{{ o.label }}</span>
                <span v-if="o.hint" class="ffm-opt-hint">{{ o.hint }}</span>
              </span>
              <svg
                v-if="o.id === modelValue"
                class="ffm-check"
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
              <span v-else class="ffm-check-spacer" aria-hidden="true" />
            </button>
          </div>
        </div>
      </Transition>
    </Teleport>

    <Teleport to="body">
      <Transition name="ffm-backdrop">
        <div
          v-if="open && isMobileUi"
          class="ffm-sheet-backdrop"
          aria-hidden="true"
          @click="close"
        />
      </Transition>
      <Transition name="ffm-sheet">
        <div
          v-if="open && isMobileUi"
          ref="sheetRef"
          class="ffm-sheet"
          role="listbox"
          :aria-label="ariaLabel"
          @click.stop
        >
          <div class="ffm-sheet-handle-wrap" aria-hidden="true">
            <span class="ffm-sheet-handle" />
          </div>
          <div class="ffm-sheet-shine" aria-hidden="true" />
          <div class="ffm-sheet-body">
            <div class="ffm-group-title ffm-group-title--sheet" role="presentation">模型系列</div>
            <button
              v-for="(o, oi) in flatList"
              :key="o.id"
              type="button"
              role="option"
              class="ffm-option ffm-option--sheet"
              :class="{
                'ffm-option--selected': o.id === modelValue,
                'ffm-option--active': oi === activeIdx,
              }"
              :aria-selected="o.id === modelValue"
              @mouseenter="onOptionEnter(oi)"
              @click="selectValue(o.id)"
            >
              <span class="ffm-opt-letter" aria-hidden="true">{{ modelBadgeLetter(o.id) }}</span>
              <span class="ffm-opt-main">
                <span class="ffm-opt-label">{{ o.label }}</span>
                <span v-if="o.hint" class="ffm-opt-hint">{{ o.hint }}</span>
              </span>
              <svg
                v-if="o.id === modelValue"
                class="ffm-check"
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
              <span v-else class="ffm-check-spacer" aria-hidden="true" />
            </button>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<style scoped>
.ffm-root {
  position: relative;
  display: flex;
  flex: 1 1 200px;
  min-width: 168px;
}

.ffm-root--compact {
  flex: 0 0 auto;
  min-width: 0;
  width: auto;
}

/* 触发器：对齐 ChatAspectRatioSelect */
.ffm-trigger {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  height: 40px;
  min-height: 40px;
  padding: 0 12px;
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

.ffm-trigger--mobile {
  height: 32px;
  min-height: 32px;
  padding: 0 10px;
  font-size: 12px;
  border-radius: 10px;
}

.ffm-trigger:hover:not(:disabled):not(.ffm-trigger--open) {
  background: rgba(34, 34, 40, 0.92);
  border-color: rgba(255, 255, 255, 0.12);
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.06) inset,
    0 0 28px rgba(94, 225, 213, 0.12),
    0 8px 24px rgba(0, 0, 0, 0.22);
  transform: translateY(-1px);
}

.ffm-trigger--open:not(:disabled) {
  border-color: rgba(94, 225, 213, 0.28);
  box-shadow:
    0 0 0 1px rgba(94, 225, 213, 0.15),
    0 0 24px rgba(94, 225, 213, 0.1);
}

.ffm-trigger:disabled {
  opacity: 0.45;
  cursor: not-allowed;
  transform: none;
}

.ffm-trigger--compact {
  position: relative;
  width: 38px;
  min-width: 38px;
  height: 38px;
  min-height: 38px;
  padding: 0;
  gap: 0;
  border-radius: 12px;
  flex-direction: column;
  justify-content: center;
}

.ffm-trigger--compact .ffm-trigger-letter {
  width: 20px;
  height: 20px;
  font-size: 10px;
  border-radius: 5px;
}

.ffm-chevron--compact {
  position: absolute;
  right: 3px;
  bottom: 4px;
  opacity: 0.45;
}

.ffm-trigger--compact.ffm-trigger--open .ffm-chevron--compact {
  opacity: 0.85;
}

.ffm-trigger-letter {
  flex-shrink: 0;
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  background: rgba(0, 0, 0, 0.28);
  color: rgba(255, 255, 255, 0.72);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: -0.03em;
}

.ffm-trigger-label {
  flex: 1;
  min-width: 0;
  text-align: left;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ffm-chevron {
  flex-shrink: 0;
  opacity: 0.55;
  transition:
    transform 0.22s cubic-bezier(0.34, 1.2, 0.64, 1),
    opacity 0.2s ease,
    color 0.2s ease;
}

.ffm-trigger:hover:not(:disabled) .ffm-chevron {
  opacity: 0.95;
  color: v-bind(BRAND);
}

.ffm-chevron--open {
  transform: rotate(180deg);
  opacity: 1;
  color: v-bind(BRAND);
}

/* 桌面面板 */
.ffm-panel {
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

.ffm-panel-shine {
  pointer-events: none;
  position: absolute;
  left: 0;
  right: 0;
  top: 0;
  height: 36px;
  border-radius: 15px 15px 0 0;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.07) 0%, transparent 75%);
}

.ffm-panel-body {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 1px;
  max-height: min(320px, 56vh);
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.ffm-group-title {
  padding: 5px 8px 3px;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: rgba(255, 255, 255, 0.38);
}

.ffm-group-title--sheet {
  padding-top: 8px;
}

.ffm-group-title:first-child {
  padding-top: 2px;
}

.ffm-option {
  display: flex;
  align-items: flex-start;
  width: 100%;
  min-height: 0;
  padding: 8px 8px;
  gap: 8px;
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
    color 0.15s ease;
}

.ffm-opt-letter {
  flex-shrink: 0;
  width: 22px;
  height: 22px;
  margin-top: 1px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  font-size: 10px;
  font-weight: 800;
  color: rgba(255, 255, 255, 0.45);
  transition:
    color 0.15s ease,
    transform 0.15s ease,
    border-color 0.15s ease;
}

.ffm-opt-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 4px;
}

.ffm-opt-label {
  flex-shrink: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ffm-opt-hint {
  font-size: 11px;
  font-weight: 500;
  line-height: 1.45;
  color: rgba(255, 255, 255, 0.42);
  white-space: normal;
  word-break: break-word;
}

.ffm-check {
  flex-shrink: 0;
  margin-top: 3px;
  color: v-bind(BRAND);
}

.ffm-check-spacer {
  width: 14px;
  flex-shrink: 0;
  margin-top: 3px;
}

.ffm-option:hover:not(.ffm-option--selected) {
  background: rgba(255, 255, 255, 0.05);
  color: rgba(255, 255, 255, 0.96);
}

.ffm-option:hover:not(.ffm-option--selected) .ffm-opt-letter {
  color: rgba(255, 255, 255, 0.82);
  border-color: rgba(255, 255, 255, 0.18);
  transform: scale(1.05);
}

.ffm-option--selected {
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
  font-weight: 700;
}

.ffm-option--selected .ffm-opt-letter {
  color: v-bind(BRAND);
  border-color: rgba(94, 225, 213, 0.35);
}

.ffm-option--active:not(.ffm-option--selected) {
  background: rgba(255, 255, 255, 0.04);
}

/* 动画 */
.ffm-desk-enter-active {
  transition:
    opacity 0.2s cubic-bezier(0.22, 1, 0.36, 1),
    transform 0.2s cubic-bezier(0.34, 1.25, 0.64, 1);
}

.ffm-desk-leave-active {
  transition:
    opacity 0.15s ease-in,
    transform 0.15s ease-in;
}

.ffm-desk-enter-from,
.ffm-desk-leave-to {
  opacity: 0;
  transform: translateY(-6px) scale(0.97);
}

.ffm-desk-enter-to,
.ffm-desk-leave-from {
  opacity: 1;
  transform: translateY(0) scale(1);
}

/* 移动端 */
.ffm-sheet-backdrop {
  position: fixed;
  inset: 0;
  z-index: 12055;
  background: rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(4px);
}

.ffm-sheet {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 12056;
  width: 100%;
  max-height: min(58vh, 380px);
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

.ffm-sheet-handle-wrap {
  display: flex;
  justify-content: center;
  padding: 10px 0 6px;
  flex-shrink: 0;
}

.ffm-sheet-handle {
  width: 36px;
  height: 4px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.18);
}

.ffm-sheet-shine {
  pointer-events: none;
  position: absolute;
  left: 0;
  right: 0;
  top: 0;
  height: 40px;
  border-radius: 16px 16px 0 0;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.08) 0%, transparent 80%);
}

.ffm-sheet-body {
  position: relative;
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.ffm-option--sheet {
  min-height: 44px;
  padding: 8px 10px;
}

.ffm-backdrop-enter-active,
.ffm-backdrop-leave-active {
  transition: opacity 0.2s ease;
}

.ffm-backdrop-enter-from,
.ffm-backdrop-leave-to {
  opacity: 0;
}

.ffm-sheet-enter-active {
  transition: transform 0.28s cubic-bezier(0.22, 1, 0.36, 1), opacity 0.22s ease;
}

.ffm-sheet-leave-active {
  transition: transform 0.2s ease-in, opacity 0.18s ease-in;
}

.ffm-sheet-enter-from,
.ffm-sheet-leave-to {
  opacity: 0.85;
  transform: translateY(100%);
}

.ffm-sheet-enter-to,
.ffm-sheet-leave-from {
  opacity: 1;
  transform: translateY(0);
}

/* 浅色 */
html[data-theme='light'] .ffm-trigger {
  border-color: rgba(15, 23, 42, 0.12);
  background: rgba(255, 255, 255, 0.72);
  color: #0f172a;
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.9) inset,
    0 1px 3px rgba(15, 23, 42, 0.06);
}

html[data-theme='light'] .ffm-trigger:hover:not(:disabled):not(.ffm-trigger--open) {
  background: rgba(255, 255, 255, 0.88);
  border-color: rgba(13, 148, 136, 0.22);
}

html[data-theme='light'] .ffm-trigger-letter {
  background: rgba(15, 23, 42, 0.06);
  color: rgba(15, 23, 42, 0.65);
}

html[data-theme='light'] .ffm-panel,
html[data-theme='light'] .ffm-sheet {
  background: rgba(252, 253, 255, 0.94);
  border-color: rgba(15, 23, 42, 0.1);
  box-shadow:
    0 12px 32px rgba(15, 23, 42, 0.12),
    0 1px 0 rgba(255, 255, 255, 0.9) inset;
}

html[data-theme='light'] .ffm-group-title {
  color: rgba(15, 23, 42, 0.45);
}

html[data-theme='light'] .ffm-option {
  color: rgba(51, 65, 85, 0.72);
}

html[data-theme='light'] .ffm-opt-letter {
  color: rgba(51, 65, 85, 0.45);
  border-color: rgba(15, 23, 42, 0.12);
}

html[data-theme='light'] .ffm-opt-hint {
  color: rgba(51, 65, 85, 0.45);
}

html[data-theme='light'] .ffm-option:hover:not(.ffm-option--selected) {
  background: rgba(15, 23, 42, 0.06);
  color: #0f172a;
}

html[data-theme='light'] .ffm-option--selected {
  background: rgba(13, 148, 136, 0.12);
  color: #0f172a;
}

html[data-theme='light'] .ffm-sheet-backdrop {
  background: rgba(15, 23, 42, 0.35);
}

@media (max-width: 768px) {
  .ffm-root {
    flex: 1 1 140px;
    min-width: 0;
  }

  .ffm-root--compact {
    flex: 0 0 auto;
  }
}
</style>
