<script setup>
/**
 * 横向技能药丸列表。样式与动效集中在本文件，便于与底部胶囊统一微调。
 */
import { CHAT_SKILLS } from '../../constants/chatSkills'

defineProps({
  disabled: { type: Boolean, default: false },
})

const selectedId = defineModel({ type: String, required: true })

/** @param {{ id: string }} skill */
function select(skill) {
  if (selectedId.value === skill.id) return
  selectedId.value = skill.id
}
</script>

<template>
  <div class="skillstrip-wrap">
    <div
      class="skillstrip-scroller"
      role="listbox"
      aria-label="选择技能"
      aria-orientation="horizontal"
    >
      <button
        v-for="s in CHAT_SKILLS"
        :key="s.id"
        type="button"
        class="skillstrip-chip"
        :class="{ 'skillstrip-chip--active': selectedId === s.id }"
        :disabled="disabled"
        role="option"
        :aria-selected="selectedId === s.id"
        @click="select(s)"
      >
        <span
          v-if="s.iconEmoji"
          class="skillstrip-emoji"
          aria-hidden="true"
          :style="s.accentColor ? { filter: `drop-shadow(0 0 8px ${s.accentColor}55)` } : undefined"
        >
          {{ s.iconEmoji }}
        </span>
        <span v-else class="skillstrip-ic" aria-hidden="true">
          <svg v-if="s.icon === 'freeform'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <path d="M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 4v-4z" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          <svg v-else-if="s.icon === 'wireframe'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <rect x="3" y="4" width="18" height="16" rx="2" />
            <path d="M3 9h18M9 9v11" stroke-linecap="round" />
            <path d="M13 13h6M13 17h4" stroke-linecap="round" />
          </svg>
          <svg v-else-if="s.icon === 'mockup'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <path d="M3 5h18v11H3z" stroke-linejoin="round" />
            <path d="M8 21h8M12 17v4" stroke-linecap="round" />
            <path d="M7 8l3 3 4-5 3 4" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          <!-- 画框 + 星芒：示意图像增强 / AI 修图 -->
          <svg v-else-if="s.icon === 'retouch'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <rect x="3" y="6" width="14" height="12" rx="2" />
            <circle cx="8" cy="11" r="1.25" />
            <path d="M11 13l2 3 3.5-5" stroke-linecap="round" stroke-linejoin="round" />
            <path d="M17 4l.75 2 2 .75-2 .75L17 10l-.75-2-2-.75 2-.75z" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
          <svg v-else-if="s.icon === 'palette'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <path d="M12 3a7 7 0 107 7c0 1.5-1 2-2 2h-2a2 2 0 00-2 2v1" stroke-linecap="round" />
            <circle cx="6.5" cy="9.5" r="1" fill="currentColor" />
            <circle cx="9.5" cy="6.5" r="1" fill="currentColor" />
            <circle cx="14.5" cy="6.5" r="1" fill="currentColor" />
            <circle cx="17.5" cy="10.5" r="1" fill="currentColor" />
          </svg>
        </span>
        <span class="skillstrip-label-text">{{ s.label }}</span>
      </button>
    </div>
  </div>
</template>

<style scoped>
.skillstrip-wrap {
  padding: 2px calc(2px * var(--ds-chat-scale, 1)) 10px;
  margin-bottom: 8px;
  overflow: visible;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

html[data-theme='light'] .skillstrip-wrap {
  border-bottom-color: rgba(15, 23, 42, 0.06);
}

.skillstrip-scroller {
  display: flex;
  flex-wrap: nowrap;
  gap: 10px;
  overflow-x: auto;
  overflow-y: hidden;
  padding: 2px 2px 6px;
  scroll-behavior: smooth;
  -webkit-overflow-scrolling: touch;
  overscroll-behavior-x: contain;
  scrollbar-width: thin;
  scroll-snap-type: x proximity;
  mask-image: linear-gradient(
    to right,
    transparent,
    #000 12px,
    #000 calc(100% - 12px),
    transparent
  );
}

@media (min-width: 900px) {
  .skillstrip-scroller {
    justify-content: center;
    overflow-x: visible;
    flex-wrap: wrap;
    mask-image: none;
    padding-bottom: 4px;
  }
}

.skillstrip-chip {
  position: relative;
  flex: 0 0 auto;
  scroll-snap-align: start;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 34px;
  padding: 6px 14px 6px 10px;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(255, 255, 255, 0.06);
  color: var(--chat-muted-2);
  font-size: 0.72rem;
  font-weight: 600;
  letter-spacing: 0.02em;
  cursor: pointer;
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  box-shadow: 0 1px 0 rgba(255, 255, 255, 0.04) inset;
  transition:
    transform 0.25s ease-out,
    box-shadow 0.28s ease-out,
    border-color 0.25s ease-out,
    background 0.28s ease-out,
    color 0.25s ease-out;
}

html[data-theme='light'] .skillstrip-chip {
  border-color: rgba(15, 23, 42, 0.08);
  background: rgba(15, 23, 42, 0.05);
  color: var(--chat-muted-2);
}

.skillstrip-chip:hover:not(:disabled) {
  transform: scale(1.04);
  border-color: rgba(94, 225, 213, 0.22);
  color: var(--chat-fg);
  box-shadow:
    0 14px 36px rgba(0, 0, 0, 0.14),
    0 0 0 1px rgba(94, 225, 213, 0.06);
}

html[data-theme='light'] .skillstrip-chip:hover:not(:disabled) {
  border-color: rgba(13, 148, 136, 0.2);
  box-shadow: 0 12px 32px rgba(15, 23, 42, 0.06);
}

.skillstrip-chip:active:not(:disabled) {
  transform: scale(0.99);
  transition-duration: 0.1s;
}

.skillstrip-chip:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  transform: none;
}

/* 选中：品牌色渐变底 + 发光描边（与底部胶囊 accent 对齐） */
.skillstrip-chip--active {
  color: var(--chat-fg-strong);
  border-color: rgba(94, 225, 213, 0.55);
  background: linear-gradient(
    135deg,
    rgba(110, 228, 215, 0.38) 0%,
    rgba(94, 225, 213, 0.22) 38%,
    rgba(72, 198, 185, 0.14) 72%,
    rgba(58, 168, 159, 0.1) 100%
  );
  box-shadow:
    0 0 0 1px rgba(94, 225, 213, 0.35),
    0 0 16px rgba(94, 225, 213, 0.35),
    0 0 28px rgba(58, 168, 159, 0.22),
    0 10px 32px rgba(58, 168, 159, 0.14);
  animation: skillstrip-pulse 2.6s ease-in-out infinite;
}

html[data-theme='light'] .skillstrip-chip--active {
  border-color: rgba(13, 148, 136, 0.48);
  background: linear-gradient(
    135deg,
    rgba(130, 230, 210, 0.42) 0%,
    rgba(45, 190, 170, 0.22) 45%,
    rgba(100, 200, 185, 0.14) 100%
  );
  box-shadow:
    0 0 0 1px rgba(13, 148, 136, 0.28),
    0 0 14px rgba(13, 148, 136, 0.28),
    0 0 26px rgba(45, 170, 155, 0.18),
    0 8px 26px rgba(13, 148, 136, 0.12);
  animation: skillstrip-pulse-light 2.6s ease-in-out infinite;
}

.skillstrip-chip--active .skillstrip-ic {
  color: var(--chat-link-accent-fg);
  opacity: 1;
  filter: drop-shadow(0 0 12px rgba(94, 225, 213, 0.45));
}

.skillstrip-chip--active .skillstrip-label-text {
  color: var(--chat-fg-strong);
}

/* 与下方参数区视觉衔接 */
.skillstrip-chip--active::after {
  content: '';
  position: absolute;
  left: 14%;
  right: 14%;
  bottom: -9px;
  height: 2px;
  border-radius: 999px;
  background: linear-gradient(
    90deg,
    transparent,
    rgba(94, 225, 213, 0.75),
    transparent
  );
  pointer-events: none;
}

html[data-theme='light'] .skillstrip-chip--active::after {
  background: linear-gradient(
    90deg,
    transparent,
    rgba(13, 148, 136, 0.65),
    transparent
  );
}

@keyframes skillstrip-pulse {
  0%,
  100% {
    box-shadow:
      0 0 0 1px rgba(94, 225, 213, 0.32),
      0 0 12px rgba(94, 225, 213, 0.28),
      0 8px 26px rgba(58, 168, 159, 0.12);
  }
  50% {
    box-shadow:
      0 0 0 1px rgba(94, 225, 213, 0.55),
      0 0 22px rgba(94, 225, 213, 0.42),
      0 12px 36px rgba(58, 168, 159, 0.18);
  }
}

@keyframes skillstrip-pulse-light {
  0%,
  100% {
    box-shadow:
      0 0 0 1px rgba(13, 148, 136, 0.22),
      0 0 10px rgba(13, 148, 136, 0.2),
      0 6px 20px rgba(13, 148, 136, 0.08);
  }
  50% {
    box-shadow:
      0 0 0 1px rgba(13, 148, 136, 0.42),
      0 0 20px rgba(45, 170, 155, 0.32),
      0 10px 28px rgba(13, 148, 136, 0.14);
  }
}

.skillstrip-emoji {
  display: flex;
  width: 20px;
  height: 20px;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  font-size: 1rem;
  line-height: 1;
}

.skillstrip-chip--active .skillstrip-emoji {
  transform: scale(1.08);
}

.skillstrip-ic {
  display: flex;
  width: 18px;
  height: 18px;
  flex-shrink: 0;
  color: var(--chat-muted-3);
  opacity: 0.88;
  transition:
    color 0.25s ease-out,
    opacity 0.25s ease-out,
    filter 0.28s ease-out;
}

.skillstrip-chip:hover:not(:disabled) .skillstrip-ic {
  color: var(--chat-link-accent-fg);
  opacity: 1;
}

.skillstrip-ic svg {
  width: 100%;
  height: 100%;
}

.skillstrip-label-text {
  white-space: nowrap;
  padding-right: 1px;
  transition: color 0.25s ease-out;
}
</style>
