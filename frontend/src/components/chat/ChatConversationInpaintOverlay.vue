<script setup>
/**
 * 会话内局部重绘：交互顺序固定为「涂抹蒙版 → 填写提示词 → 提交 API」。
 * （若产品改为先提示词后蒙版，需同步改本组件步骤与 ChatView 调用约定。）
 */
import { ref, watch, nextTick } from 'vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** 当前卡片展示用 URL（含 data URL / 预签名 COS） */
  imageUrl: { type: String, default: '' },
  /** 提示词步骤初始文案 */
  initialPrompt: { type: String, default: '' },
  submitting: { type: Boolean, default: false },
})

const emit = defineEmits(['update:modelValue', 'close', 'submit'])

const step = ref('mask')
const loadError = ref('')
const imgLoaded = ref(false)
const imgNaturalW = ref(0)
const imgNaturalH = ref(0)
const brushPx = ref(28)
const promptText = ref('')

const stageRef = ref(null)
const imgRef = ref(null)
const maskCvRef = ref(null)
const painting = ref(false)

watch(
  () => props.modelValue,
  async (open) => {
    if (!open) return
    loadError.value = ''
    imgLoaded.value = false
    step.value = 'mask'
    promptText.value = props.initialPrompt || ''
    await nextTick()
    resetMaskCanvas()
  },
)

watch(
  () => props.initialPrompt,
  (v) => {
    if (props.modelValue && step.value === 'prompt' && (!promptText.value || !promptText.value.trim())) {
      promptText.value = v || ''
    }
  },
)

function close() {
  emit('update:modelValue', false)
  emit('close')
}

function onImgLoad() {
  const img = imgRef.value
  if (!img || !img.naturalWidth) return
  loadError.value = ''
  imgNaturalW.value = img.naturalWidth
  imgNaturalH.value = img.naturalHeight
  imgLoaded.value = true
  nextTick(() => resetMaskCanvas())
}

function onImgError() {
  loadError.value = '图片加载失败，无法局部重绘（可检查跨域或链接是否过期）'
  imgLoaded.value = false
}

function maskCtx() {
  const cv = maskCvRef.value
  return cv ? cv.getContext('2d') : null
}

function resetMaskCanvas() {
  const cv = maskCvRef.value
  const img = imgRef.value
  if (!cv || !img || !imgNaturalW.value) return
  cv.width = imgNaturalW.value
  cv.height = imgNaturalH.value
  const ctx = cv.getContext('2d')
  if (!ctx) return
  ctx.globalCompositeOperation = 'source-over'
  ctx.fillStyle = 'rgba(255,255,255,1)'
  ctx.fillRect(0, 0, cv.width, cv.height)
}

function clearMask() {
  resetMaskCanvas()
}

function goPromptStep() {
  if (!imgLoaded.value) return
  step.value = 'prompt'
}

function backToMask() {
  step.value = 'mask'
}

function evToLocal(e, el) {
  const r = el.getBoundingClientRect()
  const x = ((e.clientX - r.left) / Math.max(r.width, 1)) * el.width
  const y = ((e.clientY - r.top) / Math.max(r.height, 1)) * el.height
  return { x, y }
}

function paintDot(e) {
  const cv = maskCvRef.value
  const ctx = maskCtx()
  if (!cv || !ctx) return
  const { x, y } = evToLocal(e, cv)
  const r = Math.max(3, brushPx.value)
  ctx.globalCompositeOperation = 'destination-out'
  ctx.beginPath()
  ctx.arc(x, y, r, 0, Math.PI * 2)
  ctx.fill()
}

function onPointerDown(e) {
  if (step.value !== 'mask') return
  painting.value = true
  paintDot(e)
  try {
    e.target.setPointerCapture(e.pointerId)
  } catch {
    /* ignore */
  }
}

function onPointerMove(e) {
  if (!painting.value || step.value !== 'mask') return
  paintDot(e)
}

function onPointerUp(e) {
  painting.value = false
  try {
    e.target.releasePointerCapture(e.pointerId)
  } catch {
    /* ignore */
  }
}

function confirmPrompt() {
  const t = promptText.value.trim()
  if (!t) return
  const cv = maskCvRef.value
  if (!cv) return
  cv.toBlob(
    (blob) => {
      if (!blob) return
      emit('submit', { prompt: t, maskPng: blob })
    },
    'image/png',
    1,
  )
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="modelValue"
      class="cvi-backdrop"
      role="dialog"
      aria-modal="true"
      :aria-labelledby="step === 'mask' ? 'cvi-mask-title' : 'cvi-prompt-title'"
      @click.self="close"
    >
      <div v-if="step === 'mask'" class="cvi-panel cvi-panel--wide" @click.stop>
        <h2 id="cvi-mask-title" class="cvi-title">局部重绘 · 涂抹区域</h2>
        <p class="cvi-hint">
          在需要修改的区域涂抹。蒙版规则：透明处为 AI 重绘区（橡皮擦式涂抹即可）。
        </p>
        <p v-if="loadError" class="cvi-err" role="alert">{{ loadError }}</p>
        <div ref="stageRef" class="cvi-stage">
          <div class="cvi-frame">
            <img
              ref="imgRef"
              :src="imageUrl"
              alt=""
              class="cvi-base-img"
              crossorigin="anonymous"
              draggable="false"
              @load="onImgLoad"
              @error="onImgError"
            />
            <canvas
              v-show="imgLoaded"
              ref="maskCvRef"
              class="cvi-mask-cv"
              @pointerdown.prevent="onPointerDown"
              @pointermove.prevent="onPointerMove"
              @pointerup.prevent="onPointerUp"
              @pointercancel.prevent="onPointerUp"
            />
          </div>
        </div>
        <div class="cvi-brush-row">
          <label class="cvi-mini-label">画笔半径（像素）</label>
          <input v-model.number="brushPx" type="range" min="4" max="120" class="cvi-range" />
          <span class="cvi-mini-val">{{ brushPx }}</span>
        </div>
        <div class="cvi-actions">
          <button type="button" class="cvi-btn cvi-btn--ghost" @click="clearMask">清除涂抹</button>
          <button type="button" class="cvi-btn cvi-btn--ghost" @click="close">取消</button>
          <button type="button" class="cvi-btn cvi-btn--primary" :disabled="!imgLoaded" @click="goPromptStep">
            继续，填写提示词
          </button>
        </div>
      </div>

      <div v-else class="cvi-panel" @click.stop>
        <h2 id="cvi-prompt-title" class="cvi-title">局部重绘 · 提示词</h2>
        <label class="cvi-label" for="cvi-prompt-ta">描述希望如何修改已选区域</label>
        <textarea
          id="cvi-prompt-ta"
          v-model="promptText"
          class="cvi-textarea"
          rows="5"
          placeholder="例如：将选中区域改为蓝色金属质感按钮，保持光影自然"
          :disabled="submitting"
        />
        <div class="cvi-actions">
          <button type="button" class="cvi-btn cvi-btn--ghost" :disabled="submitting" @click="backToMask">
            上一步
          </button>
          <button type="button" class="cvi-btn cvi-btn--ghost" :disabled="submitting" @click="close">取消</button>
          <button type="button" class="cvi-btn cvi-btn--primary" :disabled="submitting" @click="confirmPrompt">
            {{ submitting ? '提交中…' : '开始重绘' }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.cvi-backdrop {
  position: fixed;
  inset: 0;
  z-index: 24500;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
  background: rgba(0, 0, 0, 0.55);
  backdrop-filter: blur(8px);
}

.cvi-panel {
  width: min(100%, 440px);
  max-height: min(92vh, 900px);
  overflow: auto;
  padding: 18px 20px 16px;
  border-radius: 16px;
  border: 1px solid var(--chat-border-strong);
  background: var(--chat-shell-bg);
  color: var(--chat-fg);
  box-shadow: 0 24px 64px rgba(0, 0, 0, 0.35);
}

.cvi-panel--wide {
  width: min(100%, 720px);
}

.cvi-title {
  margin: 0 0 10px;
  font-size: 1.05rem;
  font-weight: 600;
}

.cvi-hint {
  margin: 0 0 12px;
  font-size: 0.8125rem;
  line-height: 1.45;
  color: var(--chat-muted-2);
}

.cvi-err {
  margin: 0 0 10px;
  font-size: 0.8125rem;
  color: var(--chat-danger-fg, #f87171);
}

.cvi-stage {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  max-height: min(52vh, 420px);
  border-radius: 12px;
  overflow: auto;
  border: 1px solid var(--chat-border);
  background: rgba(0, 0, 0, 0.25);
}

.cvi-frame {
  position: relative;
  display: inline-block;
  line-height: 0;
  max-width: 100%;
  max-height: min(52vh, 420px);
}

.cvi-base-img {
  display: block;
  max-width: 100%;
  max-height: min(52vh, 420px);
  width: auto;
  height: auto;
  object-fit: contain;
}

.cvi-mask-cv {
  position: absolute;
  left: 0;
  top: 0;
  width: 100%;
  height: 100%;
  touch-action: none;
  cursor: crosshair;
  pointer-events: auto;
}

.cvi-brush-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
  flex-wrap: wrap;
}

.cvi-mini-label {
  font-size: 0.75rem;
  color: var(--chat-muted-3);
}

.cvi-mini-val {
  font-size: 0.75rem;
  color: var(--chat-muted-2);
  min-width: 2rem;
}

.cvi-range {
  flex: 1;
  min-width: 120px;
}

.cvi-label {
  display: block;
  margin-bottom: 8px;
  font-size: 0.8125rem;
  color: var(--chat-muted-2);
}

.cvi-textarea {
  width: 100%;
  box-sizing: border-box;
  min-height: 120px;
  padding: 10px 12px;
  margin-bottom: 14px;
  border-radius: 12px;
  border: 1px solid var(--chat-border);
  background: rgba(255, 255, 255, 0.04);
  color: var(--chat-fg);
  font-size: 0.875rem;
  line-height: 1.45;
  resize: vertical;
  font-family: inherit;
}

html[data-theme='light'] .cvi-textarea {
  background: rgba(15, 23, 42, 0.04);
}

.cvi-textarea:focus {
  outline: none;
  border-color: var(--chat-link-accent-fg);
  box-shadow: 0 0 0 2px rgba(94, 225, 213, 0.2);
}

.cvi-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 4px;
}

.cvi-btn {
  padding: 8px 14px;
  border-radius: 10px;
  font-size: 0.875rem;
  cursor: pointer;
  border: 1px solid transparent;
}

.cvi-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.cvi-btn--ghost {
  border-color: var(--chat-border);
  background: transparent;
  color: var(--chat-muted-2);
}

.cvi-btn--ghost:hover:not(:disabled) {
  color: var(--chat-fg);
  border-color: var(--chat-link-accent-fg);
}

.cvi-btn--primary {
  border: none;
  background: linear-gradient(135deg, var(--chat-link-accent-fg), rgba(94, 225, 213, 0.75));
  color: #0f172a;
  font-weight: 600;
}

.cvi-btn--primary:hover:not(:disabled) {
  filter: brightness(1.05);
}
</style>
