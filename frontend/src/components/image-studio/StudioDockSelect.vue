<script setup>
/**
 * 图片工作台底栏紧凑下拉：视觉对齐 ChatFastFreeformModelSelect compact（圆角、灰底、hover、选中高亮）。
 */
import { computed, inject, nextTick, onMounted, onUnmounted, ref, useId, watch } from 'vue'

const props = defineProps({
  modelValue: { type: String, required: true },
  /** @type {{ id: string, label: string, hint?: string }[]} */
  options: { type: Array, required: true },
  disabled: { type: Boolean, default: false },
  ariaLabel: { type: String, default: '选择' },
  /** 触发器上展示的缩写（不传则用当前项 label 截断） */
  triggerCap: { type: String, default: '' },
  /** 不传 triggerCap 时，触发器文案最多显示字符数（按 JS string.length，中文一字算 1） */
  triggerLabelMaxChars: { type: Number, default: 5 },
  /** 为 true 时触发器加宽，便于显示较长中文标签（如「家装设计师」） */
  expandTrigger: { type: Boolean, default: false },
})

const emit = defineEmits(['update:modelValue'])

const dockGlassSelectKey = inject('dockGlassSelectKey', null)
const instanceKey = useId()

const flatList = computed(() =>
  (props.options || []).map((o) => ({
    id: String(o.id),
    label: String(o.label || o.id),
    hint: o.hint ? String(o.hint) : '',
  })),
)

function flatIndexForId(id) {
  const i = flatList.value.findIndex((o) => o.id === id)
  return i >= 0 ? i : 0
}

const displayLabel = computed(() => {
  const cur = flatList.value.find((o) => o.id === props.modelValue)
  return cur?.label || props.modelValue
})

const capText = computed(() => {
  if (props.triggerCap) return props.triggerCap
  const t = displayLabel.value
  const n = Math.max(1, Number(props.triggerLabelMaxChars) || 5)
  return t.length > n ? t.slice(0, n) : t
})

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
  width: '280px',
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

const PANEL_SCROLL_MAX = 280

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
  const w = Math.min(Math.max(r.width, 220), 320)
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
  <div class="ids-root" :class="{ 'ids-root--wide': expandTrigger }">
    <button
      ref="triggerRef"
      type="button"
      class="ids-trigger"
      :class="{ 'ids-trigger--open': open, 'ids-trigger--wide': expandTrigger }"
      :disabled="disabled"
      :aria-expanded="open"
      :aria-haspopup="true"
      :aria-label="`${ariaLabel}：${displayLabel}`"
      @click.stop="toggle"
    >
      <span class="ids-cap" aria-hidden="true">{{ capText }}</span>
      <svg
        class="ids-chevron"
        :class="{ 'ids-chevron--open': open }"
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
      <Transition name="ids-desk">
        <div
          v-if="open && !isMobileUi"
          ref="panelRef"
          class="ids-panel"
          :style="panelStyle"
          role="listbox"
          :aria-label="ariaLabel"
          @click.stop
        >
          <div class="ids-panel-shine" aria-hidden="true" />
          <div class="ids-panel-body">
            <button
              v-for="(o, oi) in flatList"
              :key="o.id"
              type="button"
              role="option"
              class="ids-option"
              :class="{
                'ids-option--selected': o.id === modelValue,
                'ids-option--active': oi === activeIdx,
              }"
              :aria-selected="o.id === modelValue"
              @mouseenter="onOptionEnter(oi)"
              @click="selectValue(o.id)"
            >
              <span class="ids-opt-main">
                <span class="ids-opt-label">{{ o.label }}</span>
                <span v-if="o.hint" class="ids-opt-hint">{{ o.hint }}</span>
              </span>
              <svg
                v-if="o.id === modelValue"
                class="ids-check"
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
              <span v-else class="ids-check-spacer" aria-hidden="true" />
            </button>
          </div>
        </div>
      </Transition>
    </Teleport>

    <Teleport to="body">
      <Transition name="ids-backdrop">
        <div v-if="open && isMobileUi" class="ids-sheet-backdrop" aria-hidden="true" @click="close" />
      </Transition>
      <Transition name="ids-sheet">
        <div
          v-if="open && isMobileUi"
          ref="sheetRef"
          class="ids-sheet"
          role="listbox"
          :aria-label="ariaLabel"
          @click.stop
        >
          <div class="ids-sheet-handle-wrap" aria-hidden="true">
            <span class="ids-sheet-handle" />
          </div>
          <div class="ids-sheet-body">
            <button
              v-for="(o, oi) in flatList"
              :key="o.id"
              type="button"
              role="option"
              class="ids-option ids-option--sheet"
              :class="{
                'ids-option--selected': o.id === modelValue,
                'ids-option--active': oi === activeIdx,
              }"
              :aria-selected="o.id === modelValue"
              @mouseenter="onOptionEnter(oi)"
              @click="selectValue(o.id)"
            >
              <span class="ids-opt-main">
                <span class="ids-opt-label">{{ o.label }}</span>
                <span v-if="o.hint" class="ids-opt-hint">{{ o.hint }}</span>
              </span>
              <svg
                v-if="o.id === modelValue"
                class="ids-check"
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
              <span v-else class="ids-check-spacer" aria-hidden="true" />
            </button>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<style scoped>
.ids-root {
  position: relative;
  flex: 0 0 auto;
  min-width: 0;
}

.ids-root--wide .ids-trigger {
  min-width: 100px;
  max-width: 148px;
  padding: 0 8px 0 10px;
}

.ids-root--wide .ids-cap {
  max-width: 118px;
  font-size: 11px;
  font-weight: 650;
  letter-spacing: 0.02em;
}

.ids-trigger {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
  position: relative;
  min-width: 52px;
  height: 38px;
  padding: 0 6px 0 8px;
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(26, 26, 30, 0.9);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  color: #fff;
  font-size: 11px;
  font-weight: 700;
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

.ids-trigger:hover:not(:disabled):not(.ids-trigger--open) {
  background: rgba(34, 34, 40, 0.92);
  border-color: rgba(255, 255, 255, 0.12);
  box-shadow:
    0 1px 0 rgba(255, 255, 255, 0.06) inset,
    0 0 28px rgba(94, 225, 213, 0.12),
    0 8px 24px rgba(0, 0, 0, 0.22);
  transform: translateY(-1px);
}

.ids-trigger--open:not(:disabled) {
  border-color: rgba(94, 225, 213, 0.28);
  box-shadow:
    0 0 0 1px rgba(94, 225, 213, 0.15),
    0 0 24px rgba(94, 225, 213, 0.1);
}

.ids-trigger:disabled {
  opacity: 0.45;
  cursor: not-allowed;
  transform: none;
}

.ids-cap {
  max-width: 52px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ids-chevron {
  flex-shrink: 0;
  opacity: 0.45;
  margin-left: 1px;
}

.ids-chevron--open {
  opacity: 0.85;
}

.ids-panel {
  border-radius: 14px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(22, 22, 26, 0.96);
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.45);
  overflow: hidden;
}

.ids-panel-shine {
  height: 1px;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.12), transparent);
}

.ids-panel-body {
  max-height: 280px;
  overflow-y: auto;
  padding: 6px;
}

.ids-option {
  width: 100%;
  display: flex;
  align-items: flex-start;
  gap: 10px;
  text-align: left;
  padding: 10px 10px;
  margin: 2px 0;
  border-radius: 10px;
  border: 1px solid transparent;
  background: transparent;
  color: rgba(255, 255, 255, 0.92);
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease;
}

.ids-option:hover,
.ids-option--active {
  background: rgba(255, 255, 255, 0.06);
}

.ids-option--selected {
  border-color: rgba(94, 225, 213, 0.35);
  background: rgba(94, 225, 213, 0.08);
}

.ids-opt-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.ids-opt-label {
  font-size: 13px;
  font-weight: 650;
}

.ids-opt-hint {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.45);
  line-height: 1.35;
}

.ids-check,
.ids-check-spacer {
  flex-shrink: 0;
  margin-top: 2px;
}

.ids-check-spacer {
  width: 14px;
  height: 14px;
}

.ids-desk-enter-active,
.ids-desk-leave-active {
  transition:
    opacity 0.16s ease,
    transform 0.16s ease;
}
.ids-desk-enter-from,
.ids-desk-leave-to {
  opacity: 0;
  transform: translateY(4px);
}

.ids-sheet-backdrop {
  position: fixed;
  inset: 0;
  z-index: 12040;
  background: rgba(0, 0, 0, 0.45);
}

.ids-sheet {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 12045;
  max-height: 72vh;
  border-radius: 16px 16px 0 0;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(22, 22, 26, 0.98);
  overflow: hidden;
}

.ids-sheet-handle-wrap {
  display: flex;
  justify-content: center;
  padding: 10px 0 4px;
}

.ids-sheet-handle {
  width: 40px;
  height: 4px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.18);
}

.ids-sheet-body {
  max-height: calc(72vh - 28px);
  overflow-y: auto;
  padding: 8px 12px 16px;
}

.ids-backdrop-enter-active,
.ids-backdrop-leave-active {
  transition: opacity 0.18s ease;
}
.ids-backdrop-enter-from,
.ids-backdrop-leave-to {
  opacity: 0;
}

.ids-sheet-enter-active,
.ids-sheet-leave-active {
  transition: transform 0.22s ease;
}
.ids-sheet-enter-from,
.ids-sheet-leave-to {
  transform: translateY(100%);
}
</style>
