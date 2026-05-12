<script setup>
/**
 * AI 修图「模式」定制下拉：毛玻璃触发器 + 浮层，与底部胶囊参数条风格一致。
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
  RETOUCH_EDIT_MODES,
  retouchModePlainLabel,
  retouchModeMenuIcon,
} from '../../constants/retouchSkillConfig'

const props = defineProps({
  disabled: { type: Boolean, default: false },
  ariaLabel: { type: String, default: '修图模式' },
})

const modelValue = defineModel({ type: String, required: true })

const rows = computed(() =>
  RETOUCH_EDIT_MODES.map((m) => ({
    value: m,
    icon: retouchModeMenuIcon(m),
    label: retouchModePlainLabel(m),
  })),
)

const dockGlassSelectKey = inject('dockGlassSelectKey', null)
const instanceKey = useId()

const open = ref(false)
const triggerRef = ref(null)
const panelRef = ref(null)
const panelStyle = ref({})

const currentIcon = computed(() => retouchModeMenuIcon(modelValue.value))
const currentLabel = computed(() => retouchModePlainLabel(modelValue.value))

function close() {
  if (open.value && dockGlassSelectKey && dockGlassSelectKey.value === instanceKey) {
    dockGlassSelectKey.value = null
  }
  open.value = false
}

function schedulePosition() {
  nextTick(() => {
    const el = triggerRef.value
    if (!el) return
    const r = el.getBoundingClientRect()
    const w = Math.min(200, Math.max(180, Math.round(r.width)))
    let left = r.left
    const pad = 10
    left = Math.min(Math.max(pad, left), window.innerWidth - w - pad)
    panelStyle.value = {
      position: 'fixed',
      top: `${Math.round(r.bottom + 6)}px`,
      left: `${Math.round(left)}px`,
      width: `${w}px`,
      zIndex: '12060',
    }
  })
}

function toggle() {
  if (props.disabled) return
  if (open.value) {
    close()
    return
  }
  if (dockGlassSelectKey) dockGlassSelectKey.value = instanceKey
  open.value = true
  schedulePosition()
}

function pick(val) {
  modelValue.value = val
  close()
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

function onVisualViewportChange() {
  if (open.value) schedulePosition()
}

if (dockGlassSelectKey) {
  watch(dockGlassSelectKey, (k) => {
    if (k !== instanceKey) open.value = false
  })
}

watch(open, (v) => {
  if (v) {
    window.addEventListener('scroll', schedulePosition, true)
    window.addEventListener('resize', schedulePosition)
    window.visualViewport?.addEventListener('resize', onVisualViewportChange)
    window.visualViewport?.addEventListener('scroll', onVisualViewportChange)
  } else {
    window.removeEventListener('scroll', schedulePosition, true)
    window.removeEventListener('resize', schedulePosition)
    window.visualViewport?.removeEventListener('resize', onVisualViewportChange)
    window.visualViewport?.removeEventListener('scroll', onVisualViewportChange)
  }
})

watch(
  () => props.disabled,
  (d) => {
    if (d) close()
  },
)

onMounted(() => {
  document.addEventListener('pointerdown', onDocPointerDown, true)
  document.addEventListener('keydown', onDocKeydown)
})

onUnmounted(() => {
  document.removeEventListener('pointerdown', onDocPointerDown, true)
  document.removeEventListener('keydown', onDocKeydown)
  window.removeEventListener('scroll', schedulePosition, true)
  window.removeEventListener('resize', schedulePosition)
  window.visualViewport?.removeEventListener('resize', onVisualViewportChange)
  window.visualViewport?.removeEventListener('scroll', onVisualViewportChange)
  if (dockGlassSelectKey && dockGlassSelectKey.value === instanceKey) {
    dockGlassSelectKey.value = null
  }
})
</script>

<template>
  <div class="rt-mode-dd">
    <button
      ref="triggerRef"
      type="button"
      class="rt-mode-dd-trigger"
      :class="{ 'rt-mode-dd-trigger--open': open, 'rt-mode-dd-trigger--disabled': disabled }"
      :disabled="disabled"
      :aria-expanded="open"
      aria-haspopup="listbox"
      :aria-label="ariaLabel"
      @click.stop="toggle"
    >
      <span class="rt-mode-dd-trigger-ico" aria-hidden="true">{{ currentIcon }}</span>
      <span class="rt-mode-dd-trigger-text">{{ currentLabel }}</span>
      <svg
        class="rt-mode-dd-chevron"
        :class="{ 'rt-mode-dd-chevron--open': open }"
        width="10"
        height="10"
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
      <Transition name="rt-mode-panel">
        <div
          v-if="open"
          ref="panelRef"
          class="rt-mode-dd-panel"
          role="listbox"
          :aria-label="ariaLabel"
          :style="panelStyle"
          @click.stop
        >
          <div class="rt-mode-dd-panel-inner">
            <button
              v-for="row in rows"
              :key="row.value"
              type="button"
              role="option"
              class="rt-mode-dd-opt"
              :class="{ 'rt-mode-dd-opt--sel': row.value === modelValue }"
              :aria-selected="row.value === modelValue"
              @click="pick(row.value)"
            >
              <span class="rt-mode-dd-opt-ico" aria-hidden="true">{{ row.icon }}</span>
              <span class="rt-mode-dd-opt-label">{{ row.label }}</span>
              <span class="rt-mode-dd-opt-check" aria-hidden="true">
                <svg
                  v-if="row.value === modelValue"
                  width="14"
                  height="14"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2.5"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                >
                  <path d="M20 6L9 17l-5-5" />
                </svg>
              </span>
            </button>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<style scoped>
.rt-mode-dd {
  flex: 1;
  min-width: 0;
  max-width: 220px;
}

.rt-mode-dd-trigger {
  width: 100%;
  height: 40px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 0 11px 0 10px;
  margin: 0;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 11px;
  background: rgba(26, 26, 30, 0.9);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  color: rgba(255, 255, 255, 0.96);
  font-size: 0.8125rem;
  font-weight: 600;
  letter-spacing: 0.01em;
  cursor: pointer;
  outline: none;
  box-shadow: 0 1px 0 rgba(255, 255, 255, 0.04) inset;
  transition:
    background 0.18s ease-out,
    border-color 0.18s ease-out,
    box-shadow 0.18s ease-out,
    transform 0.18s ease-out,
    color 0.18s ease-out;
}

html[data-theme='light'] .rt-mode-dd-trigger {
  border-color: rgba(15, 23, 42, 0.1);
  background: rgba(255, 255, 255, 0.72);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  color: rgba(15, 23, 42, 0.92);
  box-shadow: 0 1px 0 rgba(255, 255, 255, 0.85) inset;
}

.rt-mode-dd-trigger:hover:not(:disabled):not(.rt-mode-dd-trigger--disabled) {
  background: rgba(34, 34, 40, 0.94);
  border-color: rgba(255, 255, 255, 0.12);
  box-shadow:
    0 0 0 1px rgba(94, 225, 213, 0.06),
    0 10px 28px rgba(0, 0, 0, 0.18);
  transform: translateY(-1px);
}

html[data-theme='light'] .rt-mode-dd-trigger:hover:not(:disabled):not(.rt-mode-dd-trigger--disabled) {
  background: rgba(255, 255, 255, 0.88);
  border-color: rgba(13, 148, 136, 0.18);
  box-shadow:
    0 0 0 1px rgba(13, 148, 136, 0.06),
    0 8px 24px rgba(15, 23, 42, 0.08);
}

.rt-mode-dd-trigger:hover:not(:disabled):not(.rt-mode-dd-trigger--disabled) .rt-mode-dd-chevron {
  opacity: 1;
  color: rgba(94, 225, 213, 0.95);
}

.rt-mode-dd-trigger:hover:not(:disabled):not(.rt-mode-dd-trigger--disabled) .rt-mode-dd-chevron:not(.rt-mode-dd-chevron--open) {
  transform: scale(1.08);
}

html[data-theme='light']
  .rt-mode-dd-trigger:hover:not(:disabled):not(.rt-mode-dd-trigger--disabled)
  .rt-mode-dd-chevron {
  color: rgba(13, 148, 136, 0.9);
}

.rt-mode-dd-trigger--open {
  border-color: rgba(94, 225, 213, 0.35);
  box-shadow:
    0 0 0 1px rgba(94, 225, 213, 0.1),
    0 8px 26px rgba(94, 225, 213, 0.08);
}

html[data-theme='light'] .rt-mode-dd-trigger--open {
  border-color: rgba(13, 148, 136, 0.32);
  box-shadow:
    0 0 0 1px rgba(13, 148, 136, 0.08),
    0 8px 22px rgba(13, 148, 136, 0.07);
}

.rt-mode-dd-trigger:focus-visible {
  border-color: rgba(94, 225, 213, 0.45);
}

.rt-mode-dd-trigger--disabled,
.rt-mode-dd-trigger:disabled {
  opacity: 0.45;
  cursor: not-allowed;
  transform: none;
}

.rt-mode-dd-trigger-ico {
  flex-shrink: 0;
  width: 22px;
  text-align: center;
  font-size: 16px;
  line-height: 1;
}

.rt-mode-dd-trigger-text {
  flex: 1;
  min-width: 0;
  text-align: left;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rt-mode-dd-chevron {
  flex-shrink: 0;
  opacity: 0.62;
  transition:
    transform 0.22s cubic-bezier(0.34, 1.15, 0.64, 1),
    opacity 0.18s ease-out,
    color 0.18s ease-out;
}

.rt-mode-dd-chevron--open {
  transform: rotate(180deg);
  opacity: 0.95;
}

.rt-mode-dd-trigger:hover:not(:disabled):not(.rt-mode-dd-trigger--disabled) .rt-mode-dd-chevron--open {
  transform: rotate(180deg) scale(1.08);
}

.rt-mode-dd-panel {
  padding: 0;
  border-radius: 15px;
  border: 1px solid rgba(255, 255, 255, 0.07);
  background: rgba(22, 22, 24, 0.95);
  backdrop-filter: blur(20px) saturate(1.15);
  -webkit-backdrop-filter: blur(20px) saturate(1.15);
  box-shadow:
    0 0 0 1px rgba(255, 255, 255, 0.03) inset,
    0 12px 32px rgba(0, 0, 0, 0.2);
}

html[data-theme='light'] .rt-mode-dd-panel {
  border-color: rgba(15, 23, 42, 0.08);
  background: rgba(252, 252, 253, 0.96);
  box-shadow:
    0 0 0 1px rgba(255, 255, 255, 0.75) inset,
    0 12px 32px rgba(15, 23, 42, 0.1);
}

.rt-mode-dd-panel-inner {
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.rt-mode-dd-opt {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 42px;
  padding: 0 10px;
  margin: 0;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: rgba(255, 255, 255, 0.7);
  font-size: 0.8125rem;
  font-weight: 500;
  text-align: left;
  cursor: pointer;
  transition:
    background 0.15s ease-out,
    color 0.15s ease-out,
    transform 0.15s ease-out;
}

html[data-theme='light'] .rt-mode-dd-opt {
  color: rgba(15, 23, 42, 0.58);
}

.rt-mode-dd-opt:hover {
  background: rgba(255, 255, 255, 0.05);
  color: rgba(255, 255, 255, 0.98);
}

html[data-theme='light'] .rt-mode-dd-opt:hover {
  background: rgba(15, 23, 42, 0.05);
  color: rgba(15, 23, 42, 0.92);
}

.rt-mode-dd-opt:hover .rt-mode-dd-opt-ico {
  transform: scale(1.1);
}

.rt-mode-dd-opt--sel {
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
  font-weight: 700;
}

html[data-theme='light'] .rt-mode-dd-opt--sel {
  background: rgba(13, 148, 136, 0.08);
  color: rgba(15, 23, 42, 0.95);
}

.rt-mode-dd-opt--sel .rt-mode-dd-opt-ico {
  filter: saturate(1.15);
}

.rt-mode-dd-opt-ico {
  flex-shrink: 0;
  width: 22px;
  text-align: center;
  font-size: 16px;
  line-height: 1;
  transition: transform 0.15s ease-out;
}

.rt-mode-dd-opt-label {
  flex: 1;
  min-width: 0;
}

.rt-mode-dd-opt-check {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: rgba(94, 225, 213, 0.95);
}

html[data-theme='light'] .rt-mode-dd-opt-check {
  color: rgba(13, 148, 136, 0.92);
}

/* 入场：略带回弹；退场：稍快 */
.rt-mode-panel-enter-active {
  transition:
    opacity 0.2s cubic-bezier(0.34, 1.15, 0.64, 1),
    transform 0.2s cubic-bezier(0.34, 1.15, 0.64, 1);
}

.rt-mode-panel-leave-active {
  transition:
    opacity 0.15s ease-in,
    transform 0.15s ease-in;
}

.rt-mode-panel-enter-from,
.rt-mode-panel-leave-to {
  opacity: 0;
  transform: translateY(-6px) scale(0.97);
}

.rt-mode-panel-enter-to,
.rt-mode-panel-leave-from {
  opacity: 1;
  transform: translateY(0) scale(1);
}
</style>
