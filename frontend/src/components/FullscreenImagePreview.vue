<script setup>
/**
 * 生成图全屏预览：毛玻璃画框、右上角关闭、点遮罩关闭、Esc 关闭、打开时锁定 body 滚动。
 */
import { watch, onMounted, onUnmounted } from 'vue'

/** 多实例同时打开时避免错误恢复 overflow */
let bodyScrollLockCount = 0
let bodyScrollSavedOverflow = ''

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  /** 图片地址 */
  src: { type: String, default: '' },
})

const emit = defineEmits(['update:modelValue'])

function close() {
  emit('update:modelValue', false)
}

function onKeydown(e) {
  if (e.key === 'Escape' && props.modelValue) {
    e.preventDefault()
    close()
  }
}

watch(
  () => props.modelValue,
  (open) => {
    if (typeof document === 'undefined') return
    if (open) {
      if (bodyScrollLockCount === 0) bodyScrollSavedOverflow = document.body.style.overflow
      bodyScrollLockCount += 1
      document.body.style.overflow = 'hidden'
    } else {
      bodyScrollLockCount = Math.max(0, bodyScrollLockCount - 1)
      if (bodyScrollLockCount === 0) document.body.style.overflow = bodyScrollSavedOverflow
    }
  },
  { flush: 'sync', immediate: true },
)

onMounted(() => {
  window.addEventListener('keydown', onKeydown)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
  if (typeof document === 'undefined') return
  if (props.modelValue) {
    bodyScrollLockCount = Math.max(0, bodyScrollLockCount - 1)
    if (bodyScrollLockCount === 0) document.body.style.overflow = bodyScrollSavedOverflow
  }
})
</script>

<template>
  <Teleport to="body">
    <div
      v-if="modelValue && src"
      class="fip-root"
      role="dialog"
      aria-modal="true"
      aria-label="图片预览"
      @click="close"
    >
      <div class="fip-frame" @click.stop>
        <div class="fip-header">
          <button type="button" class="fip-close" title="关闭" aria-label="关闭" @click="close">×</button>
        </div>
        <div class="fip-img-shell">
          <img :src="src" alt="预览" class="fip-img" />
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.fip-root {
  position: fixed;
  inset: 0;
  z-index: 20000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: max(22px, env(safe-area-inset-top)) max(22px, env(safe-area-inset-right))
    max(22px, env(safe-area-inset-bottom)) max(22px, env(safe-area-inset-left));
  background: color-mix(in srgb, #000 52%, transparent);
  -webkit-backdrop-filter: blur(18px);
  backdrop-filter: blur(18px);
}

.fip-frame {
  position: relative;
  max-width: 100%;
  max-height: 100%;
  width: min(94vw, 1180px);
  display: flex;
  flex-direction: column;
  min-height: 0;
  border-radius: var(--radius-lg, 20px);
  border: 1px solid var(--ig-glass-border, rgba(255, 255, 255, 0.12));
  background: var(--ig-glass-bg, rgba(36, 36, 36, 0.55));
  -webkit-backdrop-filter: blur(20px);
  backdrop-filter: blur(20px);
  box-shadow:
    0 24px 64px rgba(0, 0, 0, 0.45),
    0 0 0 1px color-mix(in srgb, var(--chat-fg-strong, #fff) 6%, transparent) inset;
  padding: 14px 16px 18px;
  box-sizing: border-box;
}

.fip-header {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  min-height: 48px;
  padding: 2px 2px 6px;
  box-sizing: border-box;
  z-index: 3;
}

.fip-close {
  flex-shrink: 0;
  width: 44px;
  height: 44px;
  margin: 0;
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--chat-border-strong, rgba(255, 255, 255, 0.12));
  border-radius: var(--radius-md, 12px);
  background: color-mix(in srgb, var(--chat-panel, #242424) 55%, transparent);
  -webkit-backdrop-filter: blur(12px);
  backdrop-filter: blur(12px);
  color: var(--chat-fg-strong, #fff);
  font-size: 1.5rem;
  line-height: 1;
  cursor: pointer;
  transition:
    background 0.2s ease,
    border-color 0.2s ease,
    color 0.2s ease;
}

.fip-close:hover {
  background: color-mix(in srgb, var(--chat-link-accent-fg, #5ee1d5) 22%, transparent);
  border-color: color-mix(in srgb, var(--chat-link-accent-fg, #5ee1d5) 45%, transparent);
  color: var(--chat-fg-strong, #fff);
}

.fip-img-shell {
  flex: 1 1 auto;
  min-height: 0;
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 4px 10px 10px;
}

.fip-img {
  max-width: 100%;
  max-height: 100%;
  width: auto;
  height: auto;
  object-fit: contain;
  border-radius: var(--radius-md, 12px);
}

html[data-theme='light'] .fip-root {
  background: color-mix(in srgb, #0f172a 38%, transparent);
}

html[data-theme='light'] .fip-frame {
  background: color-mix(in srgb, #fff 72%, transparent);
  box-shadow:
    0 20px 50px rgba(15, 23, 42, 0.12),
    0 0 0 1px rgba(15, 23, 42, 0.06) inset;
}

html[data-theme='light'] .fip-close {
  color: var(--chat-fg, #1e293b);
  background: color-mix(in srgb, #fff 80%, transparent);
  border-color: rgba(15, 23, 42, 0.12);
}
</style>
