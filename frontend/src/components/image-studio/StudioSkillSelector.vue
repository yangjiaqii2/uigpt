<script setup>
import StudioDockSelect from './StudioDockSelect.vue'

defineProps({
  modelValue: { type: String, required: true },
  /** @type {{ id: string, label: string, hint?: string }[]} */
  options: { type: Array, required: true },
  disabled: { type: Boolean, default: false },
})

defineEmits(['update:modelValue'])
</script>

<template>
  <StudioDockSelect
    :model-value="modelValue"
    :options="options"
    :disabled="disabled"
    :trigger-label-max-chars="8"
    expand-trigger
    aria-label="作图技能"
    @update:model-value="$emit('update:modelValue', $event)"
  />
</template>
