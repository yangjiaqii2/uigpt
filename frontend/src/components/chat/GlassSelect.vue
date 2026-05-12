<script setup>
/**
 * 深色毛玻璃自定义下拉（出图比例 / 风格等）。
 * 浅色主题在文末 media / html[data-theme='light'] 中适配。
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

const props = defineProps({
  /** 当前值 */
  modelValue: { type: String, required: true },
  /** { value, label? }[] */
  options: { type: Array, required: true },
  /** 无障碍名称 */
  ariaLabel: { type: String, default: '' },
  disabled: { type: Boolean, default: false },
  /** 是否显示底部「设为默认」 */
  showDefaultFooter: { type: Boolean, default: true },
  /** 下拉最小宽度（px），便于与触发按钮对齐 */
  minPanelWidth: { type: Number, default: 200 },
  /** 占满父级宽度；圆角矩形、44px 高，用于技能参数面板 */
  fullWidth: { type: Boolean, default: false },
})

const emit = defineEmits(['update:modelValue', 'set-default'])

/** 同一块输入区内互斥展开 */
const dockGlassSelectKey = inject('dockGlassSelectKey', null)
const instanceKey = useId()

const GAP_PX = 6
const VIEW_MARGIN_PX = 10
const PANEL_MIN_SCROLL_H = 120

const open = ref(false)
const triggerRef = ref(null)
const panelRef = ref(null)
/** 面板在触发器上方展开（动画方向与翻转一致） */
const placementAbove = ref(false)

const panelStyle = ref({
  position: 'fixed',
  top: '0px',
  left: '0px',
  width: '0px',
  minWidth: `${props.minPanelWidth}px`,
  zIndex: '12050',
})

const displayLabel = computed(() => {
  const o = props.options.find((x) => x.value === props.modelValue)
  return o ? (o.label ?? o.value) : props.modelValue
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
  nextTick(() => {
    schedulePanelPosition()
  })
}

function getViewportRect() {
  const vv = window.visualViewport
  if (vv) {
    return {
      top: vv.offsetTop,
      left: vv.offsetLeft,
      width: vv.width,
      height: vv.height,
    }
  }
  return {
    top: 0,
    left: 0,
    width: window.innerWidth,
    height: window.innerHeight,
  }
}

/** 布局后再测高度，避免缩放 / 底部输入区导致超出可视区域 */
function schedulePanelPosition() {
  requestAnimationFrame(() => {
    updatePanelPosition()
    requestAnimationFrame(() => updatePanelPosition())
  })
}

function updatePanelPosition() {
  const trigger = triggerRef.value
  const panel = panelRef.value
  if (!trigger || !open.value) return

  const r = trigger.getBoundingClientRect()
  const w = Math.max(r.width, props.minPanelWidth)
  const vp = getViewportRect()

  let left = r.left
  const maxLeft = vp.left + vp.width - VIEW_MARGIN_PX - w
  const minLeft = vp.left + VIEW_MARGIN_PX
  if (left > maxLeft) left = maxLeft
  if (left < minLeft) left = minLeft

  placementAbove.value = false
  let top = r.bottom + GAP_PX
  let maxHeight = ''

  if (panel) {
    const prevMax = panel.style.maxHeight
    panel.style.maxHeight = 'none'
    const naturalH = panel.offsetHeight
    panel.style.maxHeight = prevMax

    const bottomEdge = vp.top + vp.height - VIEW_MARGIN_PX
    const spaceBelow = bottomEdge - r.bottom - GAP_PX
    const spaceAbove = r.top - vp.top - GAP_PX - VIEW_MARGIN_PX

    if (naturalH <= spaceBelow) {
      top = r.bottom + GAP_PX
      maxHeight = ''
      placementAbove.value = false
    } else if (naturalH <= spaceAbove) {
      top = r.top - GAP_PX - naturalH
      maxHeight = ''
      placementAbove.value = true
    } else {
      const useBelow = spaceBelow >= spaceAbove
      placementAbove.value = !useBelow
      const avail = Math.max(PANEL_MIN_SCROLL_H, useBelow ? spaceBelow : spaceAbove)
      maxHeight = `${avail}px`
      if (useBelow) {
        top = r.bottom + GAP_PX
      } else {
        top = r.top - GAP_PX - avail
      }
      if (top < vp.top + VIEW_MARGIN_PX) {
        top = vp.top + VIEW_MARGIN_PX
      }
      if (top + avail > bottomEdge) {
        top = Math.max(vp.top + VIEW_MARGIN_PX, bottomEdge - avail)
      }
    }
  }

  const nextStyle = {
    position: 'fixed',
    top: `${top}px`,
    left: `${left}px`,
    width: `${w}px`,
    minWidth: `${props.minPanelWidth}px`,
    zIndex: '12050',
  }
  if (maxHeight) nextStyle.maxHeight = maxHeight
  panelStyle.value = nextStyle
}

function selectOption(val) {
  emit('update:modelValue', val)
  close()
}

function onSetDefault() {
  emit('set-default')
}

function onDocPointerDown(e) {
  if (!open.value) return
  const t = e.target
  if (triggerRef.value?.contains(t)) return
  if (panelRef.value?.contains(t)) return
  close()
}

function onDocKeydown(e) {
  if (e.key === 'Escape') close()
}

if (dockGlassSelectKey) {
  watch(dockGlassSelectKey, (k) => {
    if (k !== instanceKey) open.value = false
  })
}

function onVisualViewportChange() {
  if (open.value) schedulePanelPosition()
}

watch(open, (v) => {
  if (v) {
    window.addEventListener('scroll', schedulePanelPosition, true)
    window.addEventListener('resize', schedulePanelPosition)
    window.visualViewport?.addEventListener('resize', onVisualViewportChange)
    window.visualViewport?.addEventListener('scroll', onVisualViewportChange)
  } else {
    window.removeEventListener('scroll', schedulePanelPosition, true)
    window.removeEventListener('resize', schedulePanelPosition)
    window.visualViewport?.removeEventListener('resize', onVisualViewportChange)
    window.visualViewport?.removeEventListener('scroll', onVisualViewportChange)
  }
})

onMounted(() => {
  document.addEventListener('pointerdown', onDocPointerDown, true)
  document.addEventListener('keydown', onDocKeydown)
})

onUnmounted(() => {
  document.removeEventListener('pointerdown', onDocPointerDown, true)
  document.removeEventListener('keydown', onDocKeydown)
  window.removeEventListener('scroll', schedulePanelPosition, true)
  window.removeEventListener('resize', schedulePanelPosition)
  window.visualViewport?.removeEventListener('resize', onVisualViewportChange)
  window.visualViewport?.removeEventListener('scroll', onVisualViewportChange)
  if (dockGlassSelectKey && dockGlassSelectKey.value === instanceKey) {
    dockGlassSelectKey.value = null
  }
})
</script>

<template>
  <div class="glass-dd" :class="{ 'glass-dd--block': fullWidth }">
    <button
      ref="triggerRef"
      type="button"
      class="glass-dd-trigger"
      :class="{
        'glass-dd-trigger--open': open,
        'glass-dd-trigger--disabled': disabled,
        'glass-dd-trigger--block': fullWidth,
      }"
      :disabled="disabled"
      :aria-expanded="open"
      :aria-haspopup="true"
      :aria-label="ariaLabel || displayLabel"
      @click.stop="toggle"
    >
      <span class="glass-dd-trigger-text">{{ displayLabel }}</span>
      <svg
        class="glass-dd-chevron"
        :class="{ 'glass-dd-chevron--open': open }"
        width="12"
        height="12"
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

    <Teleport to="body">
      <Transition name="glass-dd-panel">
        <div
          v-if="open"
          ref="panelRef"
          class="glass-dd-panel"
          :class="{ 'glass-dd-panel--above': placementAbove }"
          :style="panelStyle"
          role="listbox"
          :aria-label="ariaLabel"
          @click.stop
        >
          <div class="glass-dd-panel-inner">
            <div class="glass-dd-glow" aria-hidden="true" />
            <button
              v-for="(opt, idx) in options"
              :key="opt.value"
              type="button"
              role="option"
              class="glass-dd-option"
              :class="{ 'glass-dd-option--selected': opt.value === modelValue }"
              :aria-selected="opt.value === modelValue"
              @click="selectOption(opt.value)"
            >
              <span class="glass-dd-option-bar" aria-hidden="true" />
              <span class="glass-dd-option-label">{{ opt.label ?? opt.value }}</span>
              <svg
                v-if="opt.value === modelValue"
                class="glass-dd-check"
                width="16"
                height="16"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2.2"
                aria-hidden="true"
              >
                <path d="M20 6L9 17l-5-5" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
              <span v-else class="glass-dd-check-placeholder" aria-hidden="true" />
            </button>
          </div>

          <div v-if="showDefaultFooter" class="glass-dd-footer">
            <button type="button" class="glass-dd-footer-btn" @click="onSetDefault">
              设为默认
            </button>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<style scoped>
.glass-dd {
  position: relative;
  display: inline-flex;
}

.glass-dd--block {
  width: 100%;
  display: flex;
}

.glass-dd--block .glass-dd-trigger {
  width: 100%;
}

.glass-dd-trigger {
  display: inline-flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-height: 38px;
  padding: 8px 14px 8px 16px;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(16, 18, 24, 0.42);
  backdrop-filter: blur(16px) saturate(1.2);
  -webkit-backdrop-filter: blur(16px) saturate(1.2);
  color: rgba(235, 238, 245, 0.82);
  font-size: 0.74rem;
  font-weight: 500;
  letter-spacing: 0.02em;
  cursor: pointer;
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.04) inset,
    0 0 0 1px rgba(0, 0, 0, 0.12) inset;
  transition:
    background 0.22s ease-out,
    border-color 0.22s ease-out,
    color 0.22s ease-out,
    box-shadow 0.22s ease-out,
    transform 0.22s ease-out;
}

.glass-dd-trigger:hover:not(:disabled):not(.glass-dd-trigger--disabled) {
  background: rgba(24, 28, 38, 0.55);
  border-color: rgba(255, 255, 255, 0.12);
  color: rgba(248, 250, 252, 0.94);
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.06) inset,
    0 0 24px rgba(94, 225, 213, 0.08),
    0 8px 22px rgba(0, 0, 0, 0.18);
  transform: translateY(-1px);
}

.glass-dd-trigger--open:not(:disabled) {
  border-color: rgba(94, 225, 213, 0.28);
  box-shadow:
    0 0 0 1px rgba(94, 225, 213, 0.12),
    0 6px 22px rgba(0, 0, 0, 0.2);
}

.glass-dd-trigger--block {
  min-height: 44px;
  border-radius: 12px;
  font-size: 14px;
  padding-left: 14px;
  padding-right: 14px;
}

.glass-dd-trigger--disabled,
.glass-dd-trigger:disabled {
  opacity: 0.45;
  cursor: not-allowed;
  transform: none;
}

.glass-dd-trigger-text {
  flex: 1;
  min-width: 0;
  text-align: left;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.glass-dd-chevron {
  flex-shrink: 0;
  opacity: 0.55;
  transition:
    transform 0.22s ease-out,
    opacity 0.22s ease-out,
    color 0.22s ease-out;
}

.glass-dd-trigger:hover:not(:disabled) .glass-dd-chevron {
  opacity: 0.95;
  color: var(--chat-link-accent-fg, #5ee1d5);
}

.glass-dd-chevron--open {
  transform: rotate(180deg);
  opacity: 0.95;
  color: var(--chat-link-accent-fg, #5ee1d5);
}

/* 面板：纵向 flex，选项区可滚动，底部「设为默认」常驻 */
.glass-dd-panel {
  display: flex;
  flex-direction: column;
  padding: 0;
  border-radius: 18px;
  border: 1px solid rgba(255, 255, 255, 0.09);
  background: rgba(14, 16, 22, 0.82);
  backdrop-filter: blur(22px) saturate(1.35);
  -webkit-backdrop-filter: blur(22px) saturate(1.35);
  box-shadow:
    0 0 0 1px rgba(0, 0, 0, 0.35) inset,
    0 2px 0 rgba(255, 255, 255, 0.05) inset,
    0 24px 48px rgba(0, 0, 0, 0.45);
  overflow: hidden;
  min-height: 0;
}

.glass-dd-panel-inner {
  position: relative;
  flex: 1 1 auto;
  min-height: 0;
  max-height: 100%;
  padding: 8px;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  -webkit-overflow-scrolling: touch;
}

.glass-dd-glow {
  pointer-events: none;
  position: absolute;
  left: 10%;
  right: 10%;
  top: 0;
  height: 1px;
  background: linear-gradient(
    90deg,
    transparent,
    rgba(94, 225, 213, 0.35),
    transparent
  );
  opacity: 0.65;
}

.glass-dd-option {
  position: relative;
  display: flex;
  align-items: center;
  width: 100%;
  min-height: 42px;
  padding: 0 14px 0 12px;
  gap: 10px;
  border: none;
  border-radius: 14px;
  background: transparent;
  color: rgba(220, 224, 232, 0.72);
  font-size: 0.8125rem;
  font-weight: 500;
  text-align: left;
  cursor: pointer;
  transition:
    background 0.18s ease-out,
    color 0.18s ease-out,
    transform 0.18s ease-out;
}

.glass-dd-option-bar {
  position: absolute;
  left: 4px;
  top: 50%;
  transform: translateY(-50%) scaleY(0);
  width: 3px;
  height: 0;
  border-radius: 2px;
  background: linear-gradient(180deg, var(--chat-link-accent-fg, #5ee1d5), rgba(94, 225, 213, 0.35));
  opacity: 0;
  transition:
    opacity 0.2s ease-out,
    transform 0.2s ease-out,
    height 0.2s ease-out;
}

.glass-dd-option:hover:not(.glass-dd-option--selected) {
  background: rgba(255, 255, 255, 0.06);
  color: rgba(252, 252, 252, 0.96);
  transform: translateX(4px);
}

.glass-dd-option:hover:not(.glass-dd-option--selected) .glass-dd-option-bar {
  opacity: 1;
  transform: translateY(-50%) scaleY(1);
  height: 56%;
}

.glass-dd-option--selected {
  background: rgba(94, 225, 213, 0.1);
  color: var(--chat-link-accent-fg, #5ee1d5);
  font-weight: 600;
}

.glass-dd-option--selected .glass-dd-option-bar {
  opacity: 1;
  transform: translateY(-50%) scaleY(1);
  height: 62%;
}

.glass-dd-option-label {
  flex: 1;
  min-width: 0;
}

.glass-dd-check {
  flex-shrink: 0;
  color: var(--chat-link-accent-fg, #5ee1d5);
  opacity: 0.95;
}

.glass-dd-check-placeholder {
  width: 16px;
  flex-shrink: 0;
}

.glass-dd-footer {
  flex-shrink: 0;
  padding: 6px 12px 10px;
  border-top: 1px solid rgba(255, 255, 255, 0.05);
  background: linear-gradient(180deg, rgba(0, 0, 0, 0.08), transparent);
}

.glass-dd-footer-btn {
  width: 100%;
  padding: 6px 8px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: rgba(180, 186, 198, 0.42);
  font-size: 0.6875rem;
  font-weight: 500;
  letter-spacing: 0.04em;
  cursor: pointer;
  transition:
    color 0.18s ease-out,
    background 0.18s ease-out;
}

.glass-dd-footer-btn:hover {
  color: rgba(200, 206, 218, 0.62);
  background: rgba(255, 255, 255, 0.04);
}

/* 入场 / 离场 */
.glass-dd-panel-enter-active,
.glass-dd-panel-leave-active {
  transition:
    opacity 0.2s ease-out,
    transform 0.2s ease-out;
}

.glass-dd-panel-enter-from,
.glass-dd-panel-leave-to {
  opacity: 0;
  transform: translateY(-8px) scale(0.95);
}

.glass-dd-panel-enter-to,
.glass-dd-panel-leave-from {
  opacity: 1;
  transform: translateY(0) scale(1);
}

/* 向上展开时入场从下方轻微上浮，避免与向下展开同向突兀 */
.glass-dd-panel--above.glass-dd-panel-enter-from,
.glass-dd-panel--above.glass-dd-panel-leave-to {
  transform: translateY(8px) scale(0.95);
}

/* 浅色主题 */
html[data-theme='light'] .glass-dd-trigger {
  border-color: rgba(15, 23, 42, 0.1);
  background: rgba(255, 255, 255, 0.55);
  color: rgba(30, 41, 59, 0.78);
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.85) inset,
    0 1px 3px rgba(15, 23, 42, 0.06);
}

html[data-theme='light'] .glass-dd-trigger:hover:not(:disabled):not(.glass-dd-trigger--disabled) {
  background: rgba(255, 255, 255, 0.72);
  border-color: rgba(13, 148, 136, 0.22);
  color: rgba(15, 23, 42, 0.92);
  box-shadow:
    0 0 20px rgba(13, 148, 136, 0.1),
    0 8px 24px rgba(15, 23, 42, 0.06);
}

html[data-theme='light'] .glass-dd-panel {
  border-color: rgba(15, 23, 42, 0.1);
  background: rgba(252, 253, 255, 0.88);
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.9) inset,
    0 18px 40px rgba(15, 23, 42, 0.12);
}

html[data-theme='light'] .glass-dd-option {
  color: rgba(51, 65, 85, 0.78);
}

html[data-theme='light'] .glass-dd-option:hover:not(.glass-dd-option--selected) {
  background: rgba(15, 23, 42, 0.05);
  color: rgba(15, 23, 42, 0.92);
}

html[data-theme='light'] .glass-dd-option--selected {
  background: rgba(13, 148, 136, 0.12);
  color: var(--chat-link-accent-fg, #0d9488);
}

html[data-theme='light'] .glass-dd-footer {
  border-top-color: rgba(15, 23, 42, 0.06);
}
</style>
