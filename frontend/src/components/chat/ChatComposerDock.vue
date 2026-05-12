<script setup>
/**
 * 底部悬浮胶囊输入区：毛玻璃、附件上传、对话模型选择、圆形发送。
 */
import { ref, computed, watch, nextTick, onMounted, onUnmounted, provide } from 'vue'
import ChatFastFreeformModelSelect from './ChatFastFreeformModelSelect.vue'
import ChatDockSwitch from './ChatDockSwitch.vue'
import { INSUFFICIENT_POINTS_TOOLTIP_ZH } from '../../constants/pointCosts.js'

/** 同一 dock 内多个 GlassSelect 互斥展开 */
const dockGlassSelectKey = ref(null)
provide('dockGlassSelectKey', dockGlassSelectKey)

const props = defineProps({
  sending: { type: Boolean, default: false },
  inputPlaceholder: { type: String, default: '' },
  /** 多条时在输入框内轮播占位提示（单条则沿用原生 placeholder） */
  placeholderHints: { type: Array, default: () => [] },
  maxChars: { type: Number, default: 6000 },
  isStreaming: { type: Boolean, default: false },
  /** 已登录：展示 API易 对话模型选择 */
  showFastFreeformModelPicker: { type: Boolean, default: false },
  /** 已登录且当前可用积分不足以支付本轮对话时禁用发送（访客不受影响） */
  insufficientPointsForSend: { type: Boolean, default: false },
  insufficientPointsTitle: {
    type: String,
    default: INSUFFICIENT_POINTS_TOOLTIP_ZH,
  },
})

const emit = defineEmits(['send', 'keydown', 'stop'])

/** 参考图预览列表 { url: object URL, name }；发送后由父组件 revoke */
const attachments = defineModel('attachments', {
  type: Array,
  /** @returns {never[]} */
  default: () => [],
})

const input = defineModel({ type: String, required: true })

/** 自由对话所选大类 id（父组件持久化；勿设 default，避免欢迎态/对话态切换 dock 时把父 ref 写回 gpt） */
const fastFreeformModelId = defineModel('fastFreeformModelId', {
  type: String,
  required: true,
})

/** 深度推理：路由到 Pro / thinking / reasoning 等上游型号（父组件持久化） */
const freeformDeepReasoning = defineModel('freeformDeepReasoning', {
  type: Boolean,
  required: true,
})

const composerTaRef = ref(null)
const fileInputRef = ref(null)
/** 输入区整体聚焦光晕（textarea focus 时 true） */
const composerFocused = ref(false)
/** 拖拽悬停：涟漪 / 边框高亮 */
const dropHighlight = ref(false)
let dragDepth = 0

const charCountClass = computed(() => {
  const n = input.value.length
  if (n >= props.maxChars) return 'dock-meta-count dock-meta-count--warn'
  if (n >= props.maxChars * 0.9) return 'dock-meta-count dock-meta-count--soft-warn'
  return 'dock-meta-count'
})

const hintsList = computed(() => {
  const raw =
    Array.isArray(props.placeholderHints) && props.placeholderHints.length > 0
      ? props.placeholderHints
      : [props.inputPlaceholder || '']
  const seen = new Set()
  const out = []
  for (const item of raw) {
    const s = String(item ?? '').trim()
    if (!s || seen.has(s)) continue
    seen.add(s)
    out.push(s)
  }
  return out
})

const usePlaceholderCarousel = computed(() => hintsList.value.length > 1)

const textareaPlaceholder = computed(() =>
  usePlaceholderCarousel.value ? '' : hintsList.value[0] || props.inputPlaceholder,
)

const hintIndex = ref(0)
const carouselHint = computed(() => {
  const list = hintsList.value
  if (!list.length) return ''
  return list[hintIndex.value % list.length]
})

/** @type {ReturnType<typeof setInterval> | null} */
let hintRotateTimer = null

function clearHintRotateTimer() {
  if (hintRotateTimer != null) {
    clearInterval(hintRotateTimer)
    hintRotateTimer = null
  }
}

function syncHintCarousel() {
  clearHintRotateTimer()
  if (!usePlaceholderCarousel.value || props.sending || input.value.trim()) return
  hintRotateTimer = setInterval(() => {
    const n = hintsList.value.length
    if (n < 2) return
    hintIndex.value = (hintIndex.value + 1) % n
  }, 4200)
}

watch(hintsList, () => {
  hintIndex.value = 0
  syncHintCarousel()
})

watch(
  () => [input.value, props.sending],
  () => syncHintCarousel(),
)

watch(
  () => usePlaceholderCarousel.value,
  () => {
    hintIndex.value = 0
    nextTick(syncHintCarousel)
  },
)

/** 文本域高度：1～5 行，平滑变化 */
function adjustTextareaHeight() {
  const el = composerTaRef.value
  if (!el) return
  const cs = window.getComputedStyle(el)
  const lh = parseFloat(cs.lineHeight) || 22.4
  const pt = parseFloat(cs.paddingTop) || 0
  const pb = parseFloat(cs.paddingBottom) || 0
  const maxH = lh * 5 + pt + pb
  el.style.height = 'auto'
  const h = Math.min(el.scrollHeight, maxH)
  el.style.height = `${h}px`
}

function onInput() {
  adjustTextareaHeight()
}

function onComposerKeydown(e) {
  emit('keydown', e)
}

function openAttach() {
  fileInputRef.value?.click()
}

function addImageFiles(fileList) {
  if (!fileList?.length) return
  const next = [...attachments.value]
  for (const f of fileList) {
    if (!f.type.startsWith('image/')) continue
    next.push({ url: URL.createObjectURL(f), name: f.name })
  }
  attachments.value = next
}

function onAttachChange(e) {
  const inputEl = e.target
  if (!(inputEl instanceof HTMLInputElement)) return
  const files = inputEl.files
  if (files?.length) addImageFiles(files)
  inputEl.value = ''
  nextTick(adjustTextareaHeight)
}

function removeAttachment(i) {
  const item = attachments.value[i]
  if (item?.url?.startsWith('blob:')) URL.revokeObjectURL(item.url)
  attachments.value = attachments.value.filter((_, j) => j !== i)
}

function onDragEnter(e) {
  e.preventDefault()
  dragDepth++
  dropHighlight.value = true
}

function onDragOver(e) {
  e.preventDefault()
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy'
}

function onDragLeave(e) {
  e.preventDefault()
  dragDepth = Math.max(0, dragDepth - 1)
  if (dragDepth === 0) dropHighlight.value = false
}

function onDrop(e) {
  e.preventDefault()
  dragDepth = 0
  dropHighlight.value = false
  const files = e.dataTransfer?.files
  if (files?.length) addImageFiles(files)
  nextTick(adjustTextareaHeight)
}

const primarySendDisabled = computed(() => {
  if (props.isStreaming) return false
  return !input.value.trim() || props.insufficientPointsForSend
})

const primarySendButtonTitle = computed(() => {
  if (props.isStreaming) return undefined
  if (props.insufficientPointsForSend && input.value.trim()) return props.insufficientPointsTitle
  return undefined
})

function onPrimaryAction() {
  if (props.isStreaming) emit('stop')
  else emit('send')
}

watch(
  () => input.value,
  () => nextTick(adjustTextareaHeight),
)

watch(
  () => props.inputPlaceholder,
  () => nextTick(adjustTextareaHeight),
)

onMounted(() => {
  nextTick(() => {
    adjustTextareaHeight()
    syncHintCarousel()
  })
})

onUnmounted(() => {
  clearHintRotateTimer()
})

defineExpose({ adjustTextareaHeight })
</script>

<template>
  <div class="dock-float">
    <div
      class="dock-capsule"
      :class="{
        'dock-capsule--focused': composerFocused,
        'dock-capsule--drop': dropHighlight,
      }"
      @dragenter="onDragEnter"
      @dragover="onDragOver"
      @dragleave="onDragLeave"
      @drop="onDrop"
    >
      <div class="dock-input-shell">
        <input
          ref="fileInputRef"
          type="file"
          class="dock-file-input"
          multiple
          tabindex="-1"
          aria-hidden="true"
          @change="onAttachChange"
        />

        <div class="dock-row">
          <div class="dock-side dock-side--left dock-side--stack" aria-label="上传与模型">
            <div class="dock-left-stack">
              <button
                type="button"
                class="dock-icon-btn"
                title="上传文件"
                aria-label="上传文件"
                :disabled="sending"
                @click="openAttach"
              >
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" aria-hidden="true">
                  <path d="M21.44 11.05l-9.19 9.19a6 6 0 01-8.49-8.49l9.19-9.19a4 4 0 015.66 5.66l-9.2 9.19a2 2 0 01-2.83-2.83l8.49-8.48" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </button>
              <ChatFastFreeformModelSelect
                v-if="showFastFreeformModelPicker"
                v-model="fastFreeformModelId"
                compact
                :disabled="sending"
                aria-label="模型系列"
              />
            </div>
          </div>

          <div class="dock-ta-wrap">
            <div class="dock-ta-main">
              <div v-if="attachments.length" class="dock-att-chips">
                <div v-for="(a, ai) in attachments" :key="a.url + ai" class="dock-att-chip">
                  <img :src="a.url" alt="" class="dock-att-thumb" />
                  <button
                    type="button"
                    class="dock-att-remove"
                    aria-label="移除图片"
                    :disabled="sending"
                    @click="removeAttachment(ai)"
                  >
                    ×
                  </button>
                </div>
              </div>
              <div class="dock-ta-stack">
                <div
                  v-if="usePlaceholderCarousel && !input.trim() && !sending"
                  class="dock-ph-carousel"
                  aria-hidden="true"
                >
                  <Transition name="dock-ph-fade" mode="out-in">
                    <span :key="carouselHint" class="dock-ph-carousel-text">{{ carouselHint }}</span>
                  </Transition>
                </div>
                <textarea
                  ref="composerTaRef"
                  v-model="input"
                  rows="1"
                  class="dock-ta"
                  :class="{ 'dock-ta--carousel': usePlaceholderCarousel }"
                  :placeholder="textareaPlaceholder"
                  :disabled="sending"
                  :maxlength="maxChars"
                  spellcheck="false"
                  @keydown="onComposerKeydown"
                  @input="onInput"
                  @focus="composerFocused = true"
                  @blur="composerFocused = false"
                />
              </div>
            </div>
          </div>

          <div class="dock-side dock-side--right dock-side--trail">
            <ChatDockSwitch
              v-if="showFastFreeformModelPicker"
              v-model="freeformDeepReasoning"
              label="深度推理"
              :disabled="sending"
            />
            <button
              type="button"
              class="dock-send"
              :class="{ 'dock-send--stop': isStreaming }"
              :disabled="primarySendDisabled"
              :title="primarySendButtonTitle"
              :aria-label="isStreaming ? '停止生成' : '发送'"
              @click="onPrimaryAction"
            >
              <template v-if="isStreaming">
                <span class="dock-stop-rect" aria-hidden="true" />
              </template>
              <svg v-else viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path
                  d="M22 2L11 13M22 2l-7 20-4-9-9-4 20-7z"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
              </svg>
            </button>
          </div>
        </div>

        <div class="dock-meta" aria-live="polite">
          <span class="dock-meta-skill">自由对话</span>
          <span :class="charCountClass">{{ input.length }} / {{ maxChars }}</span>
        </div>
      </div>

    </div>
  </div>
</template>

<style scoped>
/* ========= 胶囊：阴影与圆角（颜色来自全局 theme 变量）========= */
.dock-float {
  --dock-shadow-ambient: var(--chat-panel-shadow);
  --dock-radius: 26px;
  padding: 10px max(calc(6px * var(--ds-chat-scale, 1)), env(safe-area-inset-right, 0px))
    max(14px, env(safe-area-inset-bottom, 0px))
    max(calc(6px * var(--ds-chat-scale, 1)), env(safe-area-inset-left, 0px));
}

.dock-capsule--drop {
  border-color: color-mix(in srgb, var(--chat-link-accent-fg) 48%, transparent) !important;
  box-shadow:
    var(--dock-shadow-ambient),
    0 0 0 2px color-mix(in srgb, var(--chat-link-accent-fg) 22%, transparent),
    0 0 48px color-mix(in srgb, var(--chat-link-accent-fg) 18%, transparent) !important;
  animation: dock-drop-ripple 0.85s ease-out;
}

@keyframes dock-drop-ripple {
  0% {
    box-shadow:
      var(--dock-shadow-ambient),
      0 0 0 0 color-mix(in srgb, var(--chat-link-accent-fg) 38%, transparent);
  }
  100% {
    box-shadow:
      var(--dock-shadow-ambient),
      0 0 0 12px transparent;
  }
}

.dock-params-region {
  margin-top: 6px;
  padding: 10px 12px 12px;
  border-radius: 18px;
  background: color-mix(in srgb, var(--chat-panel) 82%, var(--chat-shell-bg));
  border: 1px solid color-mix(in srgb, var(--chat-border-strong) 45%, transparent);
  box-shadow:
    inset 0 1px 0 color-mix(in srgb, var(--chat-fg-strong) 5%, transparent),
    0 8px 28px color-mix(in srgb, var(--chat-fg-strong) 8%, transparent);
}

html[data-theme='light'] .dock-params-region {
  background: color-mix(in srgb, var(--chat-panel) 94%, var(--chat-shell-bg));
  border-color: color-mix(in srgb, var(--chat-border-strong) 70%, transparent);
  box-shadow:
    inset 0 1px 0 color-mix(in srgb, var(--chat-fg-strong) 88%, transparent),
    0 8px 26px color-mix(in srgb, var(--chat-fg-strong) 6%, transparent);
}

.dock-params-divider {
  height: 1px;
  margin: 16px 10px 14px;
  border-radius: 999px;
  background: linear-gradient(
    90deg,
    transparent,
    color-mix(in srgb, var(--chat-fg-strong) 14%, transparent),
    transparent
  );
}

html[data-theme='light'] .dock-params-divider {
  background: linear-gradient(
    90deg,
    transparent,
    color-mix(in srgb, var(--chat-fg-strong) 11%, transparent),
    transparent
  );
}

.dock-params-enter-active,
.dock-params-leave-active {
  transition:
    opacity 0.2s ease-out,
    transform 0.2s ease-out;
}

.dock-params-enter-from {
  opacity: 0;
  transform: translateY(8px);
}

.dock-params-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

.dock-params-enter-to,
.dock-params-leave-from {
  opacity: 1;
  transform: translateY(0);
}

.dock-upb-slot {
  width: 100%;
  min-width: 0;
}

/* 技能切换：参数条淡入 + 轻微下移 */
.dock-paramswap-enter-active,
.dock-paramswap-leave-active {
  transition:
    opacity 0.22s ease-out,
    transform 0.24s cubic-bezier(0.34, 1.35, 0.64, 1);
}

.dock-paramswap-enter-from,
.dock-paramswap-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

.dock-paramswap-enter-to,
.dock-paramswap-leave-from {
  opacity: 1;
  transform: translateY(0);
}

/* 底部「正在使用」文案切换 */
.dock-skillfade-enter-active,
.dock-skillfade-leave-active {
  transition: opacity 0.2s ease-out;
}

.dock-skillfade-enter-from,
.dock-skillfade-leave-to {
  opacity: 0;
}

.dock-params-surface {
  width: 100%;
}

.dock-mock-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 6px 4px 8px;
}

.dock-mock-label {
  font-size: 0.65rem;
  font-weight: 600;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  color: var(--chat-muted-4);
}

.dock-att-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 8px 0;
}

.dock-att-chip {
  position: relative;
  width: 52px;
  height: 52px;
  border-radius: 12px;
  overflow: hidden;
  border: 1px solid var(--chat-border-strong);
}

.dock-att-thumb {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.dock-att-remove {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 20px;
  height: 20px;
  padding: 0;
  border: none;
  border-radius: 6px;
  background: color-mix(in srgb, var(--chat-shell-bg) 58%, transparent);
  color: var(--chat-fg-strong);
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
}

.dock-capsule {
  border-radius: var(--dock-radius);
  padding: 10px 12px 8px;
  background: linear-gradient(
    160deg,
    color-mix(in srgb, var(--chat-fg-strong) 9%, transparent) 0%,
    color-mix(in srgb, var(--chat-fg-strong) 3%, transparent) 42%,
    color-mix(in srgb, var(--chat-shell-bg) 35%, transparent) 100%
  );
  backdrop-filter: blur(22px) saturate(1.25);
  -webkit-backdrop-filter: blur(22px) saturate(1.25);
  border: 1px solid color-mix(in srgb, var(--chat-border-strong) 85%, transparent);
  box-shadow:
    var(--dock-shadow-ambient),
    0 1px 0 color-mix(in srgb, var(--chat-fg-strong) 8%, transparent) inset,
    0 -1px 0 color-mix(in srgb, var(--chat-shell-bg) 45%, transparent) inset;
  transition:
    box-shadow 0.28s ease-out,
    border-color 0.28s ease-out,
    transform 0.28s ease-out;
}

html[data-theme='light'] .dock-capsule {
  background: linear-gradient(
    165deg,
    color-mix(in srgb, var(--chat-panel) 74%, transparent) 0%,
    color-mix(in srgb, var(--chat-panel) 54%, transparent) 45%,
    color-mix(in srgb, var(--chat-shell-bg) 92%, transparent) 100%
  );
  border-color: color-mix(in srgb, var(--chat-border-strong) 70%, transparent);
  box-shadow:
    var(--dock-shadow-ambient),
    0 1px 0 color-mix(in srgb, var(--chat-fg-strong) 92%, transparent) inset;
}

.dock-capsule--focused {
  border-color: color-mix(in srgb, var(--chat-link-accent-fg) 32%, transparent);
  box-shadow:
    var(--dock-shadow-ambient),
    var(--accent-glow),
    0 1px 0 color-mix(in srgb, var(--chat-fg-strong) 8%, transparent) inset;
}

html[data-theme='light'] .dock-capsule--focused {
  border-color: color-mix(in srgb, var(--chat-link-accent-fg) 30%, transparent);
  box-shadow:
    var(--dock-shadow-ambient),
    var(--accent-glow),
    0 1px 0 color-mix(in srgb, var(--chat-fg-strong) 94%, transparent) inset;
}

.dock-tier {
  margin: -2px 0 6px;
  padding-bottom: 8px;
  border-bottom: 1px solid color-mix(in srgb, var(--chat-toolbar-divider) 100%, transparent);
}

html[data-theme='light'] .dock-tier {
  border-bottom-color: color-mix(in srgb, var(--chat-toolbar-divider) 100%, transparent);
}

.dock-tier-inner {
  display: inline-flex;
  gap: 6px;
}

.dock-tier-btn {
  padding: 5px 12px;
  border-radius: 999px;
  border: none;
  background: color-mix(in srgb, var(--chat-btn-bg) 100%, transparent);
  color: var(--chat-muted-2);
  font-size: 0.72rem;
  font-weight: 500;
  cursor: pointer;
  transition:
    color 0.22s ease-out,
    background 0.22s ease-out,
    transform 0.22s ease-out;
}

.dock-tier-btn:hover {
  color: var(--chat-fg);
  background: color-mix(in srgb, var(--chat-btn-bg-hover) 100%, transparent);
  transform: scale(1.02);
}

.dock-tier-btn--on {
  background: color-mix(in srgb, var(--chat-link-accent-fg) 14%, transparent);
  color: var(--chat-fg-strong);
}

html[data-theme='light'] .dock-tier-btn--on {
  background: color-mix(in srgb, var(--chat-link-accent-fg) 14%, transparent);
}

.dock-input-shell {
  position: relative;
  margin-top: 4px;
}

.dock-file-input {
  position: absolute;
  width: 0;
  height: 0;
  opacity: 0;
  pointer-events: none;
}

.dock-row {
  display: flex;
  align-items: flex-end;
  gap: 8px;
}

.dock-side {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  padding-bottom: 5px;
}

.dock-side--left {
  gap: 2px;
}

.dock-side--stack {
  align-items: stretch;
  align-self: stretch;
}

.dock-left-stack {
  display: flex;
  flex-direction: row;
  align-items: flex-end;
  flex-wrap: nowrap;
  gap: 6px;
}

.dock-side--trail {
  display: flex;
  flex-direction: row;
  align-items: flex-end;
  gap: 8px;
  flex-shrink: 0;
}

.dock-icon-btn {
  width: 38px;
  height: 38px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 12px;
  background: transparent;
  color: var(--chat-muted-3);
  cursor: pointer;
  transition:
    color 0.25s ease-out,
    background 0.25s ease-out,
    transform 0.22s ease-out;
}

.dock-icon-btn svg {
  width: 20px;
  height: 20px;
}

.dock-icon-btn:hover:not(:disabled) {
  color: var(--chat-link-accent-fg);
  background: linear-gradient(
    145deg,
    color-mix(in srgb, var(--chat-link-accent-fg) 14%, transparent),
    transparent
  );
  transform: scale(1.06);
}

html[data-theme='light'] .dock-icon-btn:hover:not(:disabled) {
  background: linear-gradient(
    145deg,
    color-mix(in srgb, var(--chat-link-accent-fg) 12%, transparent),
    transparent
  );
}

.dock-icon-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.dock-ta-wrap {
  flex: 1;
  min-width: 0;
  position: relative;
  border-radius: 18px;
  background: color-mix(in srgb, var(--chat-shell-bg) 28%, transparent);
  border: 1px solid transparent;
  transition:
    border-color 0.26s ease-out,
    background 0.26s ease-out,
    box-shadow 0.26s ease-out;
}

.dock-ta-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.dock-ta-stack {
  position: relative;
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.dock-ph-carousel {
  position: absolute;
  inset: 0;
  padding: 10px 12px;
  pointer-events: none;
  z-index: 1;
  display: flex;
  align-items: flex-start;
}

.dock-ph-carousel-text {
  font-size: calc(0.76rem * var(--ds-chat-scale, 1));
  line-height: 1.42;
  color: var(--chat-muted-4);
  opacity: 0.92;
}

.dock-ph-fade-enter-active,
.dock-ph-fade-leave-active {
  transition: opacity 0.38s ease;
}

.dock-ph-fade-enter-from,
.dock-ph-fade-leave-to {
  opacity: 0;
}

.dock-ffm-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 10px;
  width: 100%;
  min-width: 0;
  padding: 4px 2px 2px;
}

.dock-ffm-k {
  flex-shrink: 0;
  font-size: 0.62rem;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--chat-muted-4);
}

.dock-ffm-k::after {
  content: '：';
}

html[data-theme='light'] .dock-ta-wrap {
  background: color-mix(in srgb, var(--chat-fg-strong) 5%, transparent);
}

.dock-capsule--focused .dock-ta-wrap {
  border-color: color-mix(in srgb, var(--chat-link-accent-fg) 28%, transparent);
  box-shadow: var(--accent-glow);
}

html[data-theme='light'] .dock-capsule--focused .dock-ta-wrap {
  border-color: color-mix(in srgb, var(--chat-link-accent-fg) 26%, transparent);
}

.dock-ta {
  position: relative;
  z-index: 2;
  display: block;
  width: 100%;
  min-height: 42px;
  max-height: 200px;
  overflow-y: auto;
  resize: none;
  margin: 0;
  padding: 10px 12px;
  border: none;
  border-radius: 18px;
  background: transparent;
  color: var(--chat-input-fg);
  /* 略小于消息气泡，避免输入区视觉过重 */
  font-size: calc(0.8125rem * var(--ds-chat-scale, 1));
  line-height: 1.5;
  outline: none;
  transition: height 0.2s ease-out;
}

.dock-ta.dock-ta--carousel::placeholder {
  color: transparent;
  opacity: 0;
}

.dock-ta::placeholder {
  color: var(--chat-muted-4);
  opacity: 0.92;
  white-space: pre-line;
  font-size: calc(0.76rem * var(--ds-chat-scale, 1));
  line-height: 1.42;
  animation: dock-ph-shimmer 2.8s ease-in-out infinite;
}

@keyframes dock-ph-shimmer {
  0%,
  100% {
    opacity: 0.55;
  }
  50% {
    opacity: 0.88;
  }
}

.dock-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-top: 6px;
  padding: 0 4px 2px;
  font-size: 0.625rem;
  letter-spacing: 0.02em;
  color: var(--chat-muted-4);
}

.dock-meta-skill {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  opacity: 0.92;
}

.dock-meta-count {
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
  opacity: 0.85;
  transition: color 0.2s ease-out;
}

.dock-meta-count--soft-warn {
  color: var(--chat-muted-2);
}

.dock-meta-count--warn {
  color: var(--chat-danger-fg);
}

.dock-send {
  width: 46px;
  height: 46px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 3px;
  border: none;
  border-radius: 50%;
  cursor: pointer;
  color: var(--chat-send-fg);
  background: linear-gradient(145deg, var(--chat-send-bg-start), var(--chat-send-bg-end));
  box-shadow:
    0 6px 20px color-mix(in srgb, var(--chat-send-bg-end) 30%, transparent),
    0 1px 0 color-mix(in srgb, var(--chat-fg-strong) 28%, transparent) inset;
  transition:
    transform 0.22s ease-out,
    box-shadow 0.26s ease-out,
    filter 0.22s ease-out,
    opacity 0.2s ease-out;
}

html[data-theme='light'] .dock-send {
  box-shadow:
    0 8px 22px color-mix(in srgb, var(--chat-send-bg-end) 22%, transparent),
    0 1px 0 color-mix(in srgb, var(--chat-fg-strong) 38%, transparent) inset;
}

.dock-send:hover:not(:disabled) {
  transform: scale(1.06);
  filter: brightness(1.04);
  box-shadow:
    0 10px 28px color-mix(in srgb, var(--chat-send-bg-end) 38%, transparent),
    0 0 24px color-mix(in srgb, var(--chat-link-accent-fg) 38%, transparent),
    0 1px 0 color-mix(in srgb, var(--chat-fg-strong) 30%, transparent) inset;
}

.dock-send:active:not(:disabled) {
  transform: scale(0.94);
  transition-duration: 0.08s;
}

.dock-send--stop {
  background: linear-gradient(
    145deg,
    color-mix(in srgb, var(--chat-danger-fg) 82%, var(--chat-panel)),
    color-mix(in srgb, var(--chat-danger-fg) 62%, var(--chat-shell-bg))
  ) !important;
  animation: dock-stop-pulse 1.6s ease-in-out infinite;
}

@keyframes dock-stop-pulse {
  0%,
  100% {
    box-shadow:
      var(--chat-dock-stop-shadow),
      0 1px 0 color-mix(in srgb, var(--chat-fg-strong) 22%, transparent) inset;
  }
  50% {
    box-shadow:
      var(--chat-dock-stop-shadow),
      var(--chat-dock-stop-glow),
      0 1px 0 color-mix(in srgb, var(--chat-fg-strong) 24%, transparent) inset;
  }
}

.dock-stop-rect {
  display: block;
  width: 14px;
  height: 14px;
  border-radius: 3px;
  background: currentColor;
}

.dock-send:disabled {
  opacity: 0.38;
  cursor: not-allowed;
  transform: none;
  box-shadow: none;
}

.dock-send--stop:disabled {
  opacity: 0.55;
}

.dock-send svg {
  width: 22px;
  height: 22px;
}

.dock-send-arrow {
  transform: rotate(-12deg) translate(1px, 1px);
}

.dock-send-dots {
  font-size: 1.1rem;
  letter-spacing: 2px;
  font-weight: 700;
  line-height: 1;
  opacity: 0.85;
}
</style>
