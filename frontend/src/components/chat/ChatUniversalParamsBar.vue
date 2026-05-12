<script setup>
/**
 * 生成参数工具条：{@code full} 为完整五项（遗留）；当前产品主要使用 {@code aspect-quality}（比例 + 画质）。
 */
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import {
  createDefaultUniversalParams,
  normalizeUniversalParams,
  UNIVERSAL_STYLE_OPTIONS,
  UNIVERSAL_QUALITY_KEYS,
  UNIVERSAL_QUALITY_LABELS,
  fidelityUiLabel,
  STORAGE_RECENT_COLORS,
} from '../../constants/universalGenParams'
import { createDefaultRetouchParams, normalizeRetouchParams } from '../../constants/retouchSkillConfig'
import ChatRetouchModeSelect from './ChatRetouchModeSelect.vue'
import ChatAspectRatioSelect from './ChatAspectRatioSelect.vue'

const props = defineProps({
  disabled: { type: Boolean, default: false },
  /** AI 修图强度已并入工具条时隐藏还原度滑块 */
  hideFidelity: { type: Boolean, default: false },
  /**
   * full：比例、风格、颜色、还原度、画质（遗留）
   * aspect-quality：比例 + 画质（效果图 / 原型 / 配色等）
   * aspect-quality-retouch：比例 + 画质 + 修图模式 + 修改强度（同一行）
   */
  paramMode: { type: String, default: 'full' },
})

const model = defineModel({
  type: Object,
  /** @returns {Record<string, unknown>} */
  default: () => createDefaultUniversalParams(),
})

/** aspect-quality-retouch 时由父组件绑定（修图技能） */
const retouchParams = defineModel('retouchParams', {
  type: Object,
  default: () => createDefaultRetouchParams(),
})

function sync(v) {
  model.value = normalizeUniversalParams(v)
}

const sliderLabel = computed(() => fidelityUiLabel(model.value.fidelity).text)

const popOpen = ref(false)
const popRef = ref(null)
const recentColors = ref(/** @type {string[]} */ ([]))

function readRecent() {
  try {
    const raw = localStorage.getItem(STORAGE_RECENT_COLORS)
    if (!raw) return []
    const a = JSON.parse(raw)
    if (!Array.isArray(a)) return []
    return a
      .filter((x) => typeof x === 'string' && /^#[0-9A-Fa-f]{6}$/.test(x))
      .map((x) => String(x).toUpperCase())
      .slice(0, 8)
  } catch {
    return []
  }
}

function writeRecent(arr) {
  try {
    localStorage.setItem(STORAGE_RECENT_COLORS, JSON.stringify(arr.slice(0, 8)))
  } catch {
    /* ignore */
  }
}

function pushRecent(hex) {
  const h = String(hex).toUpperCase()
  const next = [h, ...recentColors.value.filter((x) => x !== h)]
  recentColors.value = next.slice(0, 8)
  writeRecent(recentColors.value)
}

const hexDraft = ref('')
watch(
  () => model.value.color_hex,
  (c) => {
    hexDraft.value = typeof c === 'string' ? c : ''
  },
  { immediate: true },
)

watch(
  () => props.paramMode,
  () => closePop(),
)

function togglePop() {
  if (props.disabled) return
  popOpen.value = !popOpen.value
}

function closePop() {
  popOpen.value = false
}

function onDocClick(e) {
  if (!(e.target instanceof Node)) return
  const root = popRef.value
  if (root && !root.contains(e.target)) closePop()
}

onMounted(() => {
  recentColors.value = readRecent()
  document.addEventListener('click', onDocClick)
})
onUnmounted(() => document.removeEventListener('click', onDocClick))

function pick(hex) {
  const h = String(hex).toUpperCase()
  sync({ ...model.value, color_hex: h })
  pushRecent(h)
  hexDraft.value = h
}

function clearColor() {
  sync({ ...model.value, color_hex: '' })
  hexDraft.value = ''
}

function onHexCommit() {
  const s = hexDraft.value.trim()
  if (!s) {
    clearColor()
    return
  }
  const withHash = s.startsWith('#') ? s : `#${s}`
  if (/^#[0-9A-Fa-f]{6}$/.test(withHash)) {
    pick(withHash)
  } else {
    hexDraft.value = model.value.color_hex || ''
  }
}

function onNativePicker(e) {
  const el = e.target
  if (el instanceof HTMLInputElement && el.value) pick(el.value)
}

function onSwatchMainClick() {
  if (props.disabled) return
  if (model.value.color_hex) clearColor()
  else togglePop()
}

function resetAll() {
  sync(createDefaultUniversalParams())
  hexDraft.value = ''
  closePop()
}

/** 紧凑模式：只把比例、画质恢复默认，不影响已隐藏字段 */
function resetAspectQuality() {
  const d = createDefaultUniversalParams()
  sync({
    ...model.value,
    aspect_ratio: d.aspect_ratio,
    quality: d.quality,
  })
}

function setQuality(q) {
  sync({ ...model.value, quality: q })
}

const isRetouchBar = computed(() => props.paramMode === 'aspect-quality-retouch')
const isAspectQualityOnly = computed(
  () => props.paramMode === 'aspect-quality' || props.paramMode === 'aspect-quality-retouch',
)

const rtNorm = computed(() => normalizeRetouchParams(retouchParams.value))

const strengthTierLabel = computed(() => {
  const n = Number(rtNorm.value.strength)
  const v = Number.isFinite(n) ? n : 50
  if (v <= 33) return '保留原图'
  if (v <= 66) return '适度修改'
  return '大幅重构'
})

const strengthReadout = computed(() => `${rtNorm.value.strength} · ${strengthTierLabel.value}`)

function patchRetouch(/** @type {Record<string, unknown>} */ partial) {
  retouchParams.value = normalizeRetouchParams({ ...rtNorm.value, ...partial })
}

function resetRetouchBar() {
  resetAspectQuality()
  retouchParams.value = createDefaultRetouchParams()
}
</script>

<template>
  <div
    class="upb"
    :aria-label="isRetouchBar ? '修图比例、画质、模式与强度' : isAspectQualityOnly ? '出图比例与画质' : '通用生成参数'"
  >
    <div class="upb-row">
      <template v-if="isRetouchBar">
        <label class="upb-item upb-item--kv upb-aspect-slot" title="出图比例">
          <span class="upb-k">比例</span>
          <ChatAspectRatioSelect
            :model-value="model.aspect_ratio"
            :disabled="disabled"
            aria-label="出图比例"
            @update:model-value="sync({ ...model, aspect_ratio: $event })"
          />
        </label>

        <div class="upb-item upb-item--kv upb-seg-wrap" title="画质">
          <span class="upb-k">画质</span>
          <div class="upb-seg" role="group" aria-label="画质">
            <button
              v-for="q in UNIVERSAL_QUALITY_KEYS"
              :key="q"
              type="button"
              class="upb-seg-btn"
              :class="{ 'upb-seg-btn--on': model.quality === q }"
              :disabled="disabled"
              @click="setQuality(q)"
            >
              {{ UNIVERSAL_QUALITY_LABELS[q] }}
            </button>
          </div>
        </div>

        <label class="upb-item upb-item--kv upb-retouch-mode-slot" title="修图模式">
          <span class="upb-k">模式</span>
          <ChatRetouchModeSelect
            :model-value="rtNorm.edit_mode"
            :disabled="disabled"
            aria-label="修图模式"
            @update:model-value="patchRetouch({ edit_mode: $event })"
          />
        </label>

        <div class="upb-item upb-item--kv upb-retouch-strength" title="修改强度">
          <span class="upb-k">强度</span>
          <input
            class="upb-range upb-range--violet"
            type="range"
            min="0"
            max="100"
            step="1"
            :disabled="disabled"
            :value="rtNorm.strength"
            aria-valuemin="0"
            aria-valuemax="100"
            :aria-valuenow="rtNorm.strength"
            aria-label="修改强度"
            @input="patchRetouch({ strength: Number(($event.target).value) })"
          >
          <span class="upb-fidelity-readout upb-fidelity-readout--short" aria-live="polite">{{
            strengthReadout
          }}</span>
        </div>

        <button
          type="button"
          class="upb-reset"
          :disabled="disabled"
          title="比例、画质、模式与强度恢复默认"
          @click="resetRetouchBar"
        >
          恢复默认
        </button>
      </template>

      <template v-else>
      <!-- 出图比例 -->
      <div class="upb-item upb-aspect-row" title="出图比例">
        <span class="upb-ico" aria-hidden="true">◇</span>
        <ChatAspectRatioSelect
          :model-value="model.aspect_ratio"
          :disabled="disabled"
          aria-label="出图比例"
          @update:model-value="sync({ ...model, aspect_ratio: $event })"
        />
      </div>

      <!-- 出图风格 🎨 -->
      <label v-if="!isAspectQualityOnly" class="upb-item" title="出图风格">
        <span class="upb-ico" aria-hidden="true">🎨</span>
        <select
          class="upb-select"
          :disabled="disabled"
          :value="model.render_style"
          aria-label="出图风格"
          @change="sync({ ...model, render_style: ($event.target).value })"
        >
          <option v-for="s in UNIVERSAL_STYLE_OPTIONS" :key="s" :value="s">{{ s }}</option>
        </select>
      </label>

      <!-- 颜色 ● -->
      <div v-if="!isAspectQualityOnly" ref="popRef" class="upb-item upb-color-wrap">
        <span class="upb-ico" aria-hidden="true" title="颜色">●</span>
        <button
          type="button"
          class="upb-swatch-wrap"
          :disabled="disabled"
          :title="model.color_hex ? `颜色 ${model.color_hex}，点击清除` : '选择颜色'"
          aria-label="颜色"
          @click.stop="onSwatchMainClick"
        >
          <span
            class="upb-swatch"
            :class="{ 'upb-swatch--empty': !model.color_hex }"
            :style="model.color_hex ? { background: model.color_hex } : {}"
          />
        </button>
        <button
          type="button"
          class="upb-mini"
          :disabled="disabled"
          title="打开颜色选择"
          aria-label="展开颜色选择器"
          @click.stop="togglePop"
        >
          ⌄
        </button>

        <div v-if="popOpen" class="upb-pop" role="dialog" aria-label="颜色选择器" @click.stop>
          <input
            type="color"
            class="upb-native-color"
            aria-label="色盘"
            :disabled="disabled"
            :value="model.color_hex || '#888888'"
            @input="onNativePicker"
          />
          <div class="upb-hex-row">
            <span class="upb-hex-label">HEX</span>
            <input
              v-model="hexDraft"
              type="text"
              class="upb-hex-input"
              maxlength="7"
              placeholder="#RRGGBB"
              :disabled="disabled"
              @keydown.enter.prevent="onHexCommit"
              @blur="onHexCommit"
            />
          </div>
          <div v-if="recentColors.length" class="upb-recent">
            <span class="upb-recent-label">最近</span>
            <button
              v-for="c in recentColors"
              :key="c"
              type="button"
              class="upb-recent-dot"
              :title="c"
              :style="{ background: c }"
              :disabled="disabled"
              @click="pick(c)"
            />
          </div>
          <button type="button" class="upb-clear-btn" :disabled="disabled" @click="clearColor">
            清除颜色
          </button>
        </div>
      </div>

      <!-- 还原度 ↔（修图模式由专用面板控制强度，此处隐藏） -->
      <div v-if="!isAspectQualityOnly && !hideFidelity" class="upb-item upb-fidelity" title="还原度">
        <span class="upb-ico" aria-hidden="true">↔</span>
        <input
          class="upb-range"
          type="range"
          min="1"
          max="10"
          step="1"
          :disabled="disabled"
          :value="model.fidelity"
          aria-valuemin="1"
          aria-valuemax="10"
          :aria-valuenow="model.fidelity"
          aria-label="还原度"
          @input="sync({ ...model, fidelity: Number(($event.target).value) })"
        />
        <span class="upb-fidelity-readout" aria-live="polite">{{ sliderLabel }}</span>
      </div>

      <!-- 画质 ✦ -->
      <div class="upb-item upb-seg-wrap" title="画质">
        <span class="upb-ico upb-ico--seg" aria-hidden="true">✦</span>
        <div class="upb-seg" role="group" aria-label="画质">
          <button
            v-for="q in UNIVERSAL_QUALITY_KEYS"
            :key="q"
            type="button"
            class="upb-seg-btn"
            :class="{ 'upb-seg-btn--on': model.quality === q }"
            :disabled="disabled"
            @click="setQuality(q)"
          >
            {{ UNIVERSAL_QUALITY_LABELS[q] }}
          </button>
        </div>
      </div>

      <button
        v-if="paramMode === 'aspect-quality'"
        type="button"
        class="upb-reset"
        :disabled="disabled"
        title="比例与画质恢复默认"
        @click="resetAspectQuality"
      >
        恢复默认
      </button>
      <button
        v-else-if="paramMode !== 'aspect-quality-retouch'"
        type="button"
        class="upb-reset"
        :disabled="disabled"
        title="恢复默认"
        @click="resetAll"
      >
        恢复默认
      </button>
      </template>
    </div>

    <p v-if="!isAspectQualityOnly && !hideFidelity" class="upb-ticks" aria-hidden="true">
      还原度：1 创意发散 · 5 平衡 · 10 严格还原
    </p>
  </div>
</template>

<style scoped>
.upb {
  width: 100%;
  min-width: 0;
}

.upb-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 10px;
}

.upb-item--kv {
  gap: 7px;
}

.upb-retouch-mode-slot {
  flex: 1 1 156px;
  min-width: 0;
  align-items: center;
}

.upb-k {
  flex-shrink: 0;
  font-size: 0.62rem;
  font-weight: 700;
  letter-spacing: 0.03em;
  color: var(--chat-muted-4, #64748b);
}

.upb-k::after {
  content: '：';
}

.upb-aspect-slot {
  flex: 1 1 168px;
  min-width: 0;
  align-items: center;
}

.upb-aspect-row {
  flex: 0 1 auto;
  min-width: 0;
}

.upb-retouch-strength {
  flex: 1 1 140px;
  min-width: min(100%, 176px);
}

.upb-range--violet {
  accent-color: rgba(139, 92, 246, 0.88);
}

html[data-theme='light'] .upb-range--violet {
  accent-color: rgba(124, 58, 237, 0.85);
}

.upb-fidelity-readout--short {
  min-width: 0;
  max-width: 122px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.upb-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.upb-ico {
  flex-shrink: 0;
  width: 1.25rem;
  text-align: center;
  font-size: 0.95rem;
  line-height: 1;
  opacity: 0.85;
  cursor: default;
}

.upb-ico--seg {
  align-self: center;
}

.upb-select {
  height: 40px;
  min-width: 0;
  max-width: 118px;
  padding: 0 10px;
  border-radius: 10px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(0, 0, 0, 0.2);
  color: var(--chat-input-fg, #e5e7eb);
  font-size: 0.72rem;
  font-weight: 600;
  cursor: pointer;
  outline: none;
}

html[data-theme='light'] .upb-select {
  border-color: rgba(15, 23, 42, 0.12);
  background: rgba(255, 255, 255, 0.85);
  color: var(--chat-input-fg, #0f172a);
}

.upb-select:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.upb-color-wrap {
  position: relative;
}

.upb-swatch-wrap {
  width: 40px;
  height: 40px;
  padding: 0;
  border-radius: 10px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(0, 0, 0, 0.18);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

html[data-theme='light'] .upb-swatch-wrap {
  border-color: rgba(15, 23, 42, 0.12);
  background: rgba(255, 255, 255, 0.75);
}

.upb-swatch-wrap:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.upb-swatch {
  width: 22px;
  height: 22px;
  border-radius: 6px;
  border: 1px solid rgba(255, 255, 255, 0.2);
}

.upb-swatch--empty {
  background: repeating-conic-gradient(#444 0% 25%, #222 0% 50%) 50% / 8px 8px !important;
  opacity: 0.65;
}

html[data-theme='light'] .upb-swatch--empty {
  background: repeating-conic-gradient(#e2e8f0 0% 25%, #fff 0% 50%) 50% / 8px 8px !important;
}

.upb-mini {
  width: 28px;
  height: 40px;
  margin-left: 2px;
  padding: 0;
  border: none;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.06);
  color: var(--chat-muted-3, #94a3b8);
  font-size: 0.75rem;
  cursor: pointer;
  line-height: 1;
}

.upb-mini:hover:not(:disabled) {
  color: var(--chat-fg, #f8fafc);
  background: rgba(255, 255, 255, 0.1);
}

.upb-mini:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.upb-pop {
  position: absolute;
  z-index: 40;
  left: 0;
  bottom: calc(100% + 8px);
  min-width: 200px;
  padding: 12px;
  border-radius: 12px;
  background: rgba(22, 22, 26, 0.96);
  border: 1px solid rgba(255, 255, 255, 0.1);
  box-shadow: 0 16px 40px rgba(0, 0, 0, 0.35);
}

html[data-theme='light'] .upb-pop {
  background: rgba(255, 255, 255, 0.98);
  border-color: rgba(15, 23, 42, 0.1);
}

.upb-native-color {
  width: 100%;
  height: 36px;
  padding: 0;
  border: none;
  border-radius: 8px;
  cursor: pointer;
}

.upb-hex-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}

.upb-hex-label {
  font-size: 0.65rem;
  font-weight: 700;
  color: var(--chat-muted-4, #64748b);
}

.upb-hex-input {
  flex: 1;
  height: 34px;
  padding: 0 10px;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(0, 0, 0, 0.25);
  color: var(--chat-input-fg);
  font-size: 0.75rem;
  font-family: ui-monospace, monospace;
}

html[data-theme='light'] .upb-hex-input {
  border-color: rgba(15, 23, 42, 0.12);
  background: rgba(248, 250, 252, 0.95);
}

.upb-recent {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
}

.upb-recent-label {
  font-size: 0.62rem;
  color: var(--chat-muted-4);
  width: 100%;
}

.upb-recent-dot {
  width: 24px;
  height: 24px;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.15);
  padding: 0;
  cursor: pointer;
}

.upb-clear-btn {
  margin-top: 10px;
  width: 100%;
  height: 32px;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: transparent;
  color: var(--chat-muted-2);
  font-size: 0.72rem;
  cursor: pointer;
}

.upb-clear-btn:hover:not(:disabled) {
  color: var(--chat-fg);
  border-color: rgba(94, 225, 213, 0.35);
}

.upb-fidelity {
  flex: 1 1 200px;
  min-width: min(100%, 220px);
}

.upb-range {
  flex: 1;
  min-width: 72px;
  height: 6px;
  accent-color: rgba(94, 225, 213, 0.85);
  cursor: pointer;
}

.upb-range:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.upb-fidelity-readout {
  flex-shrink: 0;
  min-width: 118px;
  font-size: 0.68rem;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  color: var(--chat-muted-2, #cbd5e1);
}

.upb-seg-wrap {
  flex: 1 1 160px;
}

.upb-seg {
  display: inline-flex;
  height: 40px;
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.1);
}

html[data-theme='light'] .upb-seg {
  border-color: rgba(15, 23, 42, 0.1);
}

.upb-seg-btn {
  padding: 0 12px;
  border: none;
  background: rgba(0, 0, 0, 0.18);
  color: var(--chat-muted-3);
  font-size: 0.68rem;
  font-weight: 600;
  cursor: pointer;
  transition:
    background 0.15s ease-out,
    color 0.15s ease-out;
}

html[data-theme='light'] .upb-seg-btn {
  background: rgba(248, 250, 252, 0.9);
}

.upb-seg-btn--on {
  background: rgba(94, 225, 213, 0.18);
  color: var(--chat-fg-strong, #f8fafc);
}

html[data-theme='light'] .upb-seg-btn--on {
  background: rgba(13, 148, 136, 0.15);
  color: var(--chat-fg-strong, #0f172a);
}

.upb-seg-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.upb-reset {
  height: 40px;
  padding: 0 12px;
  border-radius: 10px;
  border: 1px dashed rgba(255, 255, 255, 0.14);
  background: transparent;
  color: var(--chat-muted-3);
  font-size: 0.68rem;
  font-weight: 600;
  cursor: pointer;
  flex-shrink: 0;
}

.upb-reset:hover:not(:disabled) {
  color: var(--chat-fg);
  border-color: rgba(94, 225, 213, 0.35);
}

.upb-reset:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.upb-ticks {
  margin: 6px 0 0;
  padding: 0 2px;
  font-size: 0.58rem;
  letter-spacing: 0.02em;
  color: var(--chat-muted-4, #64748b);
  opacity: 0.88;
}
</style>
