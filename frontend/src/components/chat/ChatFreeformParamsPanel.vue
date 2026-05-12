<script setup>
/**
 * 自由对话：无专属参数时的说明。
 */
import { FREEFORM_PARAMS_PANEL_HINT } from '../../constants/freeformSkillConfig'
</script>

<template>
  <div class="sp-param-panel">
    <section class="sp-param-group sp-param-group--ghost" role="note">
      <p class="sp-param-free-hint">{{ FREEFORM_PARAMS_PANEL_HINT }}</p>
    </section>
  </div>
</template>

<style scoped>
.sp-param-free-hint {
  margin: 0;
  padding: 6px 4px 4px;
  font-size: 14px;
  line-height: 1.55;
  font-weight: 500;
  color: rgba(255, 255, 255, 0.48);
  text-align: center;
}

html[data-theme='light'] .sp-param-free-hint {
  color: rgba(15, 23, 42, 0.48);
}
</style>
