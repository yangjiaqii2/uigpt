<script setup>
/**
 * 全屋定制「效果图」参数面板 — 网格对齐、分组卡片、统一控件规格。
 */
import { computed } from 'vue'
import GlassSelect from './GlassSelect.vue'
import {
  HOUSE_TYPE_OPTIONS,
  RENDER_SCOPE_OPTIONS,
  DESIGN_STYLE_OPTIONS,
  ASPECT_RATIO_OPTIONS,
  CAMERA_ANGLE_SEGMENTS,
  LIGHTING_OPTIONS,
  PROJECT_STAGE_SEGMENTS,
  CUSTOM_FOCUS_OPTIONS,
  BUDGET_LEVEL_OPTIONS,
  MOCKUP_UPLOAD_HINTS,
  MOCKUP_QUICK_EXAMPLES,
  descForAspectRatio,
  descForDesignStyle,
} from '../../constants/mockupSkillConfig'

const props = defineProps({
  disabled: { type: Boolean, default: false },
})

const emit = defineEmits(['quick-fill'])

const params = defineModel({ type: Object, required: true })

const houseOpts = computed(() => HOUSE_TYPE_OPTIONS.map((o) => ({ value: o.value, label: o.label })))
const renderScopeOpts = computed(() =>
  RENDER_SCOPE_OPTIONS.map((o) => ({ value: o.value, label: o.label })),
)
const styleOpts = computed(() => DESIGN_STYLE_OPTIONS.map((o) => ({ value: o.value, label: o.label })))
const aspectOpts = computed(() => ASPECT_RATIO_OPTIONS.map((o) => ({ value: o.value, label: o.label })))
const lightingOpts = computed(() => LIGHTING_OPTIONS.map((o) => ({ value: o.value, label: o.label })))
const budgetOpts = computed(() => BUDGET_LEVEL_OPTIONS.map((o) => ({ value: o.value, label: o.label })))

const selectedStyleDesc = computed(() => descForDesignStyle(params.value.design_style))
const selectedAspectDesc = computed(() => descForAspectRatio(params.value.aspect_ratio))

function ensureCustomFocus() {
  if (!Array.isArray(params.value.custom_focus)) {
    params.value.custom_focus = []
  }
}

function toggleCustomFocus(label) {
  ensureCustomFocus()
  const arr = [...params.value.custom_focus]
  const i = arr.indexOf(label)
  if (i >= 0) arr.splice(i, 1)
  else arr.push(label)
  params.value.custom_focus = arr
}

function customFocusOn(label) {
  return Array.isArray(params.value.custom_focus) && params.value.custom_focus.includes(label)
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

ensureCustomFocus()
</script>

<template>
  <div class="sp-param-panel">
    <!-- 基础设置 -->
    <section class="sp-param-group" aria-labelledby="mockup-gr-basic">
      <h3 id="mockup-gr-basic" class="sp-param-group-title">基础设置</h3>
      <div class="sp-param-grid-3">
        <div class="sp-param-field">
          <label class="sp-param-label">户型类型</label>
          <GlassSelect
            v-model="params.house_type"
            :options="houseOpts"
            aria-label="户型类型"
            :disabled="props.disabled"
            :min-panel-width="200"
            full-width
          />
        </div>
        <div class="sp-param-field">
          <label class="sp-param-label" for="mockup-area">建筑面积（㎡）</label>
          <input
            id="mockup-area"
            v-model="params.area"
            type="number"
            min="20"
            max="500"
            class="sp-param-input"
            placeholder="例如：105"
            aria-label="建筑面积"
            :disabled="props.disabled"
          >
        </div>
        <div class="sp-param-field">
          <label class="sp-param-label">整体风格</label>
          <GlassSelect
            v-model="params.design_style"
            :options="styleOpts"
            aria-label="整体风格"
            :disabled="props.disabled"
            :min-panel-width="200"
            full-width
          />
        </div>
      </div>
    </section>

    <!-- 空间与画幅 -->
    <section class="sp-param-group" aria-labelledby="mockup-gr-space">
      <h3 id="mockup-gr-space" class="sp-param-group-title">空间与画幅</h3>
      <div class="sp-param-grid-2">
        <div class="sp-param-field">
          <label class="sp-param-label">出图范围</label>
          <GlassSelect
            v-model="params.render_scope"
            :options="renderScopeOpts"
            aria-label="出图范围"
            :disabled="props.disabled"
            :min-panel-width="220"
            full-width
          />
        </div>
        <div class="sp-param-field">
          <label class="sp-param-label">出图比例</label>
          <GlassSelect
            v-model="params.aspect_ratio"
            :options="aspectOpts"
            aria-label="出图比例"
            :disabled="props.disabled"
            :min-panel-width="180"
            full-width
          />
        </div>
      </div>
      <p v-if="selectedStyleDesc" class="sp-param-detail">
        <strong>风格说明</strong>
        {{ selectedStyleDesc }}
      </p>
      <p v-if="selectedAspectDesc" class="sp-param-detail">
        <strong>比例与构图</strong>
        {{ selectedAspectDesc }}
      </p>
    </section>

    <!-- 渲染选项 -->
    <section class="sp-param-group" aria-labelledby="mockup-gr-render">
      <h3 id="mockup-gr-render" class="sp-param-group-title">渲染选项</h3>
      <div class="sp-param-field">
        <span class="sp-param-label">渲染视角</span>
        <div
          class="sp-param-seg-grid sp-param-seg-grid--4"
          role="tablist"
          aria-label="渲染视角"
        >
          <button
            v-for="seg in CAMERA_ANGLE_SEGMENTS"
            :key="seg"
            type="button"
            role="tab"
            class="sp-param-seg-btn"
            :class="{ 'sp-param-seg-btn--on': params.camera_angle === seg }"
            :disabled="props.disabled"
            @click="params.camera_angle = seg"
          >
            {{ seg }}
          </button>
        </div>
      </div>
      <div class="sp-param-grid-2">
        <div class="sp-param-field">
          <label class="sp-param-label">光照氛围</label>
          <GlassSelect
            v-model="params.lighting"
            :options="lightingOpts"
            aria-label="光照氛围"
            :disabled="props.disabled"
            :min-panel-width="200"
            full-width
          />
        </div>
        <div class="sp-param-field">
          <span class="sp-param-label">项目阶段</span>
          <div
            class="sp-param-seg-grid sp-param-seg-grid--3"
            role="tablist"
            aria-label="项目阶段"
          >
            <button
              v-for="seg in PROJECT_STAGE_SEGMENTS"
              :key="seg"
              type="button"
              role="tab"
              class="sp-param-seg-btn"
              :class="{ 'sp-param-seg-btn--on': params.project_stage === seg }"
              :disabled="props.disabled"
              @click="params.project_stage = seg"
            >
              {{ seg }}
            </button>
          </div>
        </div>
      </div>
    </section>

    <!-- 定制与预算 -->
    <section class="sp-param-group" aria-labelledby="mockup-gr-custom">
      <h3 id="mockup-gr-custom" class="sp-param-group-title">定制与预算</h3>
      <div class="sp-param-field">
        <span class="sp-param-label">定制重点（可多选）</span>
        <div class="sp-param-chip-grid" role="group" aria-label="定制重点">
          <button
            v-for="f in CUSTOM_FOCUS_OPTIONS"
            :key="f"
            type="button"
            class="sp-param-chip"
            :class="{ 'sp-param-chip--on': customFocusOn(f) }"
            :disabled="props.disabled"
            @click="toggleCustomFocus(f)"
          >
            {{ f }}
          </button>
        </div>
      </div>
      <div class="sp-param-field">
        <label class="sp-param-label">预算档位</label>
        <GlassSelect
          v-model="params.budget_level"
          :options="budgetOpts"
          aria-label="预算档位"
          :disabled="props.disabled"
          :min-panel-width="200"
          full-width
        />
      </div>
    </section>

    <!-- 参考上传 -->
    <section class="sp-param-group" aria-labelledby="mockup-gr-upload">
      <h3 id="mockup-gr-upload" class="sp-param-group-title">参考上传</h3>
      <div class="sp-param-upload-grid">
        <div
          v-for="u in MOCKUP_UPLOAD_HINTS"
          :key="u.type"
          class="sp-param-upload-btn"
          role="note"
          :title="u.desc"
        >
          <span class="sp-param-upload-ic">{{ uploadLabelParts(u.label).icon }}</span>
          <span>{{ uploadLabelParts(u.label).text }}</span>
        </div>
      </div>
      <p class="sp-param-hint">上传户型图可显著提升还原度；亦可附加风格参考或现场照片。</p>
    </section>

    <!-- 快捷示例 -->
    <section class="sp-param-group sp-param-group--ghost" aria-labelledby="mockup-gr-quick">
      <h3 id="mockup-gr-quick" class="sp-param-quick-head">快捷示例</h3>
      <div class="sp-param-quick-grid">
        <button
          v-for="(ex, i) in MOCKUP_QUICK_EXAMPLES"
          :key="i"
          type="button"
          class="sp-param-quick-card"
          :disabled="props.disabled"
          @click="fillExample(ex)"
        >
          {{ ex }}
        </button>
      </div>
    </section>
  </div>
</template>

