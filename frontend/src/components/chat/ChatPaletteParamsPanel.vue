<script setup>
/**
 * 配色方案技能参数面板。
 */
import { computed, watch } from 'vue'
import GlassSelect from './GlassSelect.vue'
import {
  APPLICATION_FIELD_OPTIONS,
  MOOD_OPTIONS,
  LIGHTING_OPTIONS,
  MATERIAL_OPTIONS,
  CONTRAST_SEGMENTS,
  UPLOAD_PROMPT_HINTS,
  PALETTE_QUICK_EXAMPLES,
  paletteShowsContrast,
  paletteShowsInteriorExtras,
  createDefaultPaletteParams,
} from '../../constants/paletteSkillConfig'

const props = defineProps({
  disabled: { type: Boolean, default: false },
})

const emit = defineEmits(['quick-fill'])

const params = defineModel({ type: Object, required: true })

const applicationOptions = computed(() =>
  APPLICATION_FIELD_OPTIONS.map((o) => ({ value: o.value, label: o.label })),
)

const moodSelectOptions = computed(() => MOOD_OPTIONS.map((o) => ({ value: o.value, label: o.label })))

const lightingSelectOptions = computed(() =>
  LIGHTING_OPTIONS.map((o) => ({ value: o.value, label: o.label })),
)

const showContrast = computed(() => paletteShowsContrast(params.value.application_field))
const showInterior = computed(() => paletteShowsInteriorExtras(params.value.application_field))

function ensureParams() {
  if (!params.value || typeof params.value !== 'object') {
    params.value = createDefaultPaletteParams()
  }
}

watch(
  () => params.value?.application_field,
  () => {
    ensureParams()
    const af = params.value.application_field
    if (!paletteShowsContrast(af)) {
      params.value.contrast_level = params.value.contrast_level ?? 'AA级（标准）'
    }
  },
)

function toggleMaterial(m) {
  ensureParams()
  const raw = params.value.material_context
  const arr = Array.isArray(raw) ? [...raw] : []
  const i = arr.indexOf(m)
  if (i >= 0) arr.splice(i, 1)
  else arr.push(m)
  params.value.material_context = arr
}

function materialActive(m) {
  const raw = params.value?.material_context
  return Array.isArray(raw) && raw.includes(m)
}

function stepCount(delta) {
  ensureParams()
  let n = Number(params.value.color_count) || 5
  n += delta
  n = Math.min(8, Math.max(3, n))
  params.value.color_count = n
}

function clearBaseColor() {
  ensureParams()
  params.value.base_color = null
}

function fillExample(t) {
  emit('quick-fill', t)
}

/** @param {string} label */
function uploadLabelParts(label) {
  const s = String(label).trim()
  const space = s.indexOf(' ')
  if (space <= 0) return { icon: '', text: s }
  return { icon: s.slice(0, space), text: s.slice(space + 1).trim() }
}
</script>

<template>
  <div class="sp-param-panel">
    <section class="sp-param-group" aria-labelledby="pl-gr-base">
      <h3 id="pl-gr-base" class="sp-param-group-title">基础参数</h3>
      <div class="sp-param-grid-2">
        <div class="sp-param-field">
          <label class="sp-param-label">应用领域</label>
          <GlassSelect
            v-model="params.application_field"
            :options="applicationOptions"
            aria-label="应用领域"
            :disabled="props.disabled"
            :min-panel-width="220"
            full-width
          />
        </div>
        <div class="sp-param-field">
          <label class="sp-param-label">情绪氛围</label>
          <GlassSelect
            v-model="params.mood"
            :options="moodSelectOptions"
            aria-label="情绪氛围"
            :disabled="props.disabled"
            :min-panel-width="200"
            full-width
          />
        </div>
      </div>
      <div class="sp-param-grid-2">
        <div class="sp-param-field">
          <label class="sp-param-label">色板数量</label>
          <div class="sp-param-stepper" role="group" aria-label="色板数量">
            <button
              type="button"
              class="sp-param-step-btn"
              :disabled="props.disabled || params.color_count <= 3"
              aria-label="减少"
              @click="stepCount(-1)"
            >
              −
            </button>
            <span class="sp-param-step-val">{{ params.color_count }}</span>
            <button
              type="button"
              class="sp-param-step-btn"
              :disabled="props.disabled || params.color_count >= 8"
              aria-label="增加"
              @click="stepCount(1)"
            >
              +
            </button>
          </div>
        </div>
        <div class="sp-param-field">
          <label class="sp-param-label">基准色（可选）</label>
          <div class="sp-param-color-row">
            <input
              type="color"
              class="sp-param-color-native"
              :value="params.base_color || '#ffffff'"
              :disabled="props.disabled"
              aria-label="选择基准色"
              @input="params.base_color = $event.target.value"
            >
            <button
              type="button"
              class="sp-param-clear-btn"
              :disabled="props.disabled"
              @click="clearBaseColor"
            >
              清除
            </button>
          </div>
          <p class="sp-param-hint">若有指定品牌色或偏好色，可从此指定起点</p>
        </div>
      </div>
    </section>

    <section v-if="showContrast" class="sp-param-group" aria-labelledby="pl-gr-contrast">
      <h3 id="pl-gr-contrast" class="sp-param-group-title">数字 / UI</h3>
      <div class="sp-param-field">
        <span class="sp-param-label">对比度标准</span>
        <div class="sp-param-seg-grid sp-param-seg-grid--3" role="tablist" aria-label="对比度标准">
          <button
            v-for="seg in CONTRAST_SEGMENTS"
            :key="seg"
            type="button"
            role="tab"
            class="sp-param-seg-btn"
            :class="{ 'sp-param-seg-btn--on-amber': params.contrast_level === seg }"
            :disabled="props.disabled"
            @click="params.contrast_level = seg"
          >
            {{ seg }}
          </button>
        </div>
      </div>
    </section>

    <section v-if="showInterior" class="sp-param-group" aria-labelledby="pl-gr-interior">
      <h3 id="pl-gr-interior" class="sp-param-group-title">装修场景扩展</h3>
      <div class="sp-param-field">
        <span class="sp-param-label">关联材质</span>
        <div class="sp-param-chip-grid">
          <button
            v-for="m in MATERIAL_OPTIONS"
            :key="m"
            type="button"
            class="sp-param-chip"
            :class="{ 'sp-param-chip--on-amber': materialActive(m) }"
            :disabled="props.disabled"
            @click="toggleMaterial(m)"
          >
            {{ m }}
          </button>
        </div>
      </div>
      <div class="sp-param-field">
        <label class="sp-param-label">空间光照</label>
        <GlassSelect
          v-model="params.lighting_condition"
          :options="lightingSelectOptions"
          aria-label="空间光照"
          :disabled="props.disabled"
          :min-panel-width="220"
          full-width
        />
      </div>
    </section>

    <section class="sp-param-group" aria-labelledby="pl-gr-upload">
      <h3 id="pl-gr-upload" class="sp-param-group-title">参考上传</h3>
      <div class="sp-param-upload-grid">
        <div
          v-for="u in UPLOAD_PROMPT_HINTS"
          :key="u.type"
          class="sp-param-upload-btn"
          role="note"
          :title="u.desc"
        >
          <span class="sp-param-upload-ic">{{ uploadLabelParts(u.label).icon }}</span>
          <span>{{ uploadLabelParts(u.label).text }}</span>
        </div>
      </div>
    </section>

    <section class="sp-param-group sp-param-group--ghost" aria-labelledby="pl-gr-quick">
      <h3 id="pl-gr-quick" class="sp-param-quick-head">快捷示例</h3>
      <div class="sp-param-quick-grid">
        <button
          v-for="(ex, i) in PALETTE_QUICK_EXAMPLES"
          :key="i"
          type="button"
          class="sp-param-quick-card sp-param-quick-card--amber"
          :disabled="props.disabled"
          @click="fillExample(ex)"
        >
          {{ ex }}
        </button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.sp-param-quick-card--amber:hover:not(:disabled) {
  border-color: rgba(245, 158, 11, 0.32);
  background: rgba(245, 158, 11, 0.07);
}
</style>
