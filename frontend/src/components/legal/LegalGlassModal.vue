<script setup>
/**
 * 通用法律文档：毛玻璃拟态 + 关闭按钮（Teleport 到 body）
 */
import { computed, watch, onBeforeUnmount } from 'vue'
import { LEGAL_DOCUMENTS } from '../../content/legalDocuments'

const props = defineProps({
  open: { type: Boolean, default: false },
  /** 'privacy' | 'terms' */
  kind: { type: String, default: null },
})

const emit = defineEmits(['update:open'])

const doc = computed(() => {
  const k = props.kind === 'privacy' || props.kind === 'terms' ? props.kind : 'terms'
  return LEGAL_DOCUMENTS[k] || LEGAL_DOCUMENTS.terms
})

function close() {
  emit('update:open', false)
}

watch(
  () => props.open,
  (o) => {
    if (typeof document === 'undefined') return
    if (o) document.body.classList.add('legal-modal-open')
    else document.body.classList.remove('legal-modal-open')
  },
)

onBeforeUnmount(() => {
  if (typeof document !== 'undefined') document.body.classList.remove('legal-modal-open')
})
</script>

<template>
  <Teleport to="body">
    <Transition name="legal-fade">
      <div v-if="open" class="legal-shell" role="dialog" aria-modal="true" aria-labelledby="legal-modal-title">
        <div class="legal-backdrop" @click="close" />
        <div class="legal-panel" @click.stop>
          <header class="legal-head">
            <h2 id="legal-modal-title" class="legal-title">{{ doc.title }}</h2>
            <button type="button" class="legal-close" aria-label="关闭" @click="close">
              <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2.2">
                <path d="M18 6 6 18M6 6l12 12" stroke-linecap="round" />
              </svg>
            </button>
          </header>
          <div class="legal-scroll">
            <p v-for="(p, i) in doc.sections" :key="i" class="legal-p">{{ p }}</p>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.legal-shell {
  position: fixed;
  inset: 0;
  z-index: 14000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: max(20px, env(safe-area-inset-top, 0px)) max(20px, env(safe-area-inset-right, 0px))
    max(20px, env(safe-area-inset-bottom, 0px)) max(20px, env(safe-area-inset-left, 0px));
  box-sizing: border-box;
}

.legal-backdrop {
  position: absolute;
  inset: 0;
  background: rgba(8, 10, 18, 0.42);
  backdrop-filter: blur(14px) saturate(1.15);
  -webkit-backdrop-filter: blur(14px) saturate(1.15);
}

.legal-panel {
  position: relative;
  width: min(520px, 100%);
  max-height: min(78vh, 640px);
  display: flex;
  flex-direction: column;
  border-radius: 22px;
  background: linear-gradient(
    155deg,
    rgba(255, 255, 255, 0.14) 0%,
    rgba(255, 255, 255, 0.06) 48%,
    rgba(255, 255, 255, 0.03) 100%
  );
  background-color: rgba(22, 26, 36, 0.52);
  border: 1px solid rgba(255, 255, 255, 0.14);
  box-shadow:
    0 0 0 1px rgba(255, 255, 255, 0.04) inset,
    0 28px 80px rgba(0, 0, 0, 0.45),
    0 12px 32px rgba(0, 0, 0, 0.25);
  backdrop-filter: blur(28px) saturate(1.25);
  -webkit-backdrop-filter: blur(28px) saturate(1.25);
  overflow: hidden;
}

.legal-head {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 18px 18px 14px 22px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.legal-title {
  margin: 0;
  font-size: 1.125rem;
  font-weight: 800;
  letter-spacing: 0.02em;
  color: rgba(248, 250, 252, 0.96);
  text-shadow: 0 1px 18px rgba(0, 0, 0, 0.35);
}

.legal-close {
  flex-shrink: 0;
  width: 42px;
  height: 42px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  cursor: pointer;
  color: rgba(226, 232, 240, 0.85);
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.1);
  transition:
    background 0.2s,
    color 0.2s,
    transform 0.15s;
}

.legal-close:hover {
  background: rgba(255, 255, 255, 0.12);
  color: #fff;
}

.legal-close:active {
  transform: scale(0.96);
}

.legal-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 16px 22px 22px;
  scrollbar-width: thin;
  scrollbar-color: rgba(255, 255, 255, 0.2) transparent;
}

.legal-scroll::-webkit-scrollbar {
  width: 6px;
}
.legal-scroll::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.18);
  border-radius: 99px;
}

.legal-p {
  margin: 0 0 1rem;
  font-size: 0.875rem;
  line-height: 1.65;
  color: rgba(226, 232, 240, 0.88);
}

.legal-p:last-child {
  margin-bottom: 0;
}

.legal-fade-enter-active,
.legal-fade-leave-active {
  transition: opacity 0.28s ease;
}
.legal-fade-enter-active .legal-panel,
.legal-fade-leave-active .legal-panel {
  transition:
    opacity 0.28s ease,
    transform 0.32s cubic-bezier(0.22, 1, 0.36, 1);
}
.legal-fade-enter-from,
.legal-fade-leave-to {
  opacity: 0;
}
.legal-fade-enter-from .legal-panel,
.legal-fade-leave-to .legal-panel {
  opacity: 0;
  transform: translateY(12px) scale(0.98);
}
</style>

<style>
body.legal-modal-open {
  overflow: hidden;
}
</style>
