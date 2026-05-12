<script setup>
/**
 * 原型图设计参数面板 — 与全局 skill-params 对齐。
 */
import { computed } from 'vue'
import GlassSelect from './GlassSelect.vue'
import {
  WIREFRAME_ASPECT_OPTIONS,
  WIREFRAME_DEVICE_TYPES,
  WIREFRAME_FIDELITY_LABELS,
  WIREFRAME_PAGE_TYPES,
  WIREFRAME_STYLE_OPTIONS,
  wireframeFidelityLabel,
} from '../../constants/wireframeSkillConfig'

defineProps({
  disabled: { type: Boolean, default: false },
})

const params = defineModel({ type: Object, required: true })

const pageOpts = computed(() => WIREFRAME_PAGE_TYPES.map((p) => ({ value: p, label: p })))
</script>

<template>
  <div class="sp-param-panel">
    <section class="sp-param-group" aria-labelledby="wf-gr-device">
      <h3 id="wf-gr-device" class="sp-param-group-title">设备与页面</h3>
      <div class="sp-param-field">
        <span class="sp-param-label">设备类型</span>
        <div class="sp-param-seg" role="tablist" aria-label="设备类型">
          <button
            v-for="seg in WIREFRAME_DEVICE_TYPES"
            :key="seg"
            type="button"
            role="tab"
            class="sp-param-seg-btn"
            :class="{ 'sp-param-seg-btn--on-blue': params.device_type === seg }"
            :disabled="disabled"
            @click="params.device_type = seg"
          >
            {{ seg }}
          </button>
        </div>
      </div>
      <div class="sp-param-field">
        <label class="sp-param-label">页面类型</label>
        <GlassSelect
          v-model="params.page_type"
          :options="pageOpts"
          aria-label="页面类型"
          :disabled="disabled"
          :min-panel-width="200"
          full-width
        />
      </div>
    </section>

    <section class="sp-param-group" aria-labelledby="wf-gr-fidelity">
      <h3 id="wf-gr-fidelity" class="sp-param-group-title">线框保真度</h3>
      <div class="sp-param-field sp-param-range-wrap" style="--sp-range-accent: #3b82f6">
        <div class="sp-param-label-row">
          <span class="sp-param-label">保真度</span>
          <span class="sp-param-label-meta">{{ params.fidelity }} · {{ wireframeFidelityLabel(params.fidelity) }}</span>
        </div>
        <input
          v-model.number="params.fidelity"
          type="range"
          min="1"
          max="3"
          step="1"
          class="sp-param-range"
          :disabled="disabled"
          aria-valuemin="1"
          aria-valuemax="3"
          :aria-valuenow="params.fidelity"
        >
        <div class="sp-param-range-ticks" aria-hidden="true">
          <span v-for="t in WIREFRAME_FIDELITY_LABELS" :key="t">{{ t }}</span>
        </div>
      </div>
    </section>

    <section class="sp-param-group" aria-labelledby="wf-gr-visual">
      <h3 id="wf-gr-visual" class="sp-param-group-title">出图示意</h3>
      <div class="sp-param-grid-2">
        <div class="sp-param-field">
          <label class="sp-param-label">示意比例</label>
          <GlassSelect
            v-model="params.aspect_ratio"
            :options="WIREFRAME_ASPECT_OPTIONS"
            aria-label="示意出图比例"
            :disabled="disabled"
            :min-panel-width="160"
            full-width
          />
        </div>
        <div class="sp-param-field">
          <label class="sp-param-label">示意风格</label>
          <GlassSelect
            v-model="params.render_style"
            :options="WIREFRAME_STYLE_OPTIONS"
            aria-label="示意渲染风格"
            :disabled="disabled"
            :min-panel-width="160"
            full-width
          />
        </div>
      </div>
    </section>
  </div>
</template>
