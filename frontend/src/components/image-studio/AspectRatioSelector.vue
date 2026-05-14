<script setup>
import StudioDockSelect from './StudioDockSelect.vue'

defineProps({
  modelValue: { type: String, required: true },
  /** @type {{ id: string, label: string }[]} */
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
    aria-label="画幅比例"
    @update:model-value="$emit('update:modelValue', $event)"
  />
</template>
