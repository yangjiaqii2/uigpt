<script setup>
/**
 * 输入栏工具条：深度推理等二元开关（胶囊轨道 + 滑块）。
 */
const props = defineProps({
  modelValue: { type: Boolean, required: true },
  disabled: { type: Boolean, default: false },
  label: { type: String, default: '' },
})

const emit = defineEmits(['update:modelValue'])

function handleToggle() {
  if (props.disabled) return
  emit('update:modelValue', !props.modelValue)
}
</script>

<template>
  <button
    type="button"
    class="dock-switch"
    role="switch"
    :aria-checked="modelValue"
    :aria-label="label || undefined"
    :disabled="disabled"
    @click="handleToggle"
  >
    <span class="dock-switch-track" aria-hidden="true">
      <span class="dock-switch-thumb" />
    </span>
    <span v-if="label" class="dock-switch-label">{{ label }}</span>
  </button>
</template>

<style scoped>
.dock-switch {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  margin-bottom: 6px;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--chat-muted-3);
  cursor: pointer;
  outline: none;
  transition:
    color 0.2s ease,
    opacity 0.2s ease;
}

.dock-switch:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.dock-switch:not(:disabled):hover {
  color: var(--chat-fg);
}

.dock-switch-track {
  position: relative;
  width: 40px;
  height: 22px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--chat-border-strong) 80%, transparent);
  border: 1px solid color-mix(in srgb, var(--chat-border-strong) 100%, transparent);
  box-shadow: 0 1px 0 color-mix(in srgb, var(--chat-fg-strong) 6%, transparent) inset;
  transition:
    background 0.22s ease,
    border-color 0.22s ease,
    box-shadow 0.22s ease;
}

.dock-switch[aria-checked='true'] .dock-switch-track {
  background: color-mix(in srgb, var(--chat-link-accent-fg) 22%, transparent);
  border-color: color-mix(in srgb, var(--chat-link-accent-fg) 42%, transparent);
  box-shadow:
    0 0 20px color-mix(in srgb, var(--chat-link-accent-fg) 18%, transparent),
    0 1px 0 color-mix(in srgb, var(--chat-fg-strong) 8%, transparent) inset;
}

.dock-switch-thumb {
  position: absolute;
  top: 50%;
  left: 3px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: color-mix(in srgb, var(--chat-panel) 55%, var(--chat-fg-strong));
  box-shadow: 0 1px 4px color-mix(in srgb, var(--chat-fg-strong) 18%, transparent);
  transform: translateY(-50%);
  transition:
    transform 0.22s cubic-bezier(0.34, 1.4, 0.64, 1),
    background 0.22s ease;
}

.dock-switch[aria-checked='true'] .dock-switch-thumb {
  transform: translate(18px, -50%);
  background: color-mix(in srgb, var(--chat-link-accent-fg) 35%, var(--chat-panel));
}

.dock-switch-label {
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.02em;
  color: var(--chat-muted-3);
  transition: color 0.2s ease;
  user-select: none;
}

.dock-switch:not(:disabled):hover .dock-switch-label {
  color: var(--chat-fg);
}

.dock-switch[aria-checked='true'] .dock-switch-label {
  color: color-mix(in srgb, var(--chat-link-accent-fg) 72%, var(--chat-fg));
}
</style>
