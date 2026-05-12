<script setup>
/**
 * 对话页空状态：UI GPT 品牌字标 + 玻璃卡片引导。
 */
</script>

<template>
  <div class="empty-root" aria-hidden="false">
    <div class="empty-backdrop" aria-hidden="true" />
    <article class="empty-card">
      <p class="empty-kicker">开始创作</p>
      <h2 class="empty-brand" aria-label="UI GPT">
        <span class="empty-brand-ui">UI</span>
        <span class="empty-brand-gpt">GPT</span>
      </h2>
      <p class="empty-divider" aria-hidden="true" />
      <p class="empty-lead">写下第一条提示</p>
      <p class="empty-tagline">对话与图像创作，一步启程。</p>
    </article>
  </div>
</template>

<style scoped>
.empty-root {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: min(52vh, 420px);
  padding: 2rem 1.25rem;
  pointer-events: none;
}

.empty-backdrop {
  position: absolute;
  width: min(88vw, 380px);
  height: min(88vw, 380px);
  border-radius: 50%;
  background: radial-gradient(
    circle at 50% 42%,
    var(--chat-empty-orbit-glow) 0%,
    transparent 62%
  );
  filter: blur(0.5px);
  animation: empty-backdrop-pulse 6s ease-in-out infinite;
}

@keyframes empty-backdrop-pulse {
  0%,
  100% {
    transform: scale(1);
    opacity: 0.88;
  }
  50% {
    transform: scale(1.06);
    opacity: 1;
  }
}

.empty-card {
  position: relative;
  z-index: 1;
  text-align: center;
  max-width: 22.5rem;
  padding: 1.5rem 1.65rem 1.55rem;
  border-radius: calc(var(--radius-lg, 20px) + 6px);
  background: var(--chat-empty-card-bg);
  border: 1px solid var(--chat-empty-card-border);
  backdrop-filter: blur(var(--chat-empty-card-blur)) saturate(1.2);
  -webkit-backdrop-filter: blur(var(--chat-empty-card-blur)) saturate(1.2);
  box-shadow: var(--chat-empty-card-shadow-1), var(--chat-empty-card-shadow-2), var(--chat-empty-card-inner-glow);
}

.empty-kicker {
  margin: 0 0 0.5rem;
  font-size: 0.6875rem;
  font-weight: 600;
  letter-spacing: 0.2em;
  text-transform: uppercase;
  color: var(--chat-muted-4);
}

.empty-brand {
  margin: 0;
  font-size: clamp(2.125rem, 7vw, 2.875rem);
  font-weight: 800;
  letter-spacing: -0.03em;
  line-height: 1.05;
  display: flex;
  align-items: baseline;
  justify-content: center;
  gap: 0.14em;
}

.empty-brand-ui {
  background: linear-gradient(
    118deg,
    var(--chat-fg-strong) 0%,
    var(--chat-link-accent-fg) 52%,
    color-mix(in srgb, var(--chat-link-accent-fg) 85%, var(--chat-fg-strong)) 100%
  );
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.empty-brand-gpt {
  color: color-mix(in srgb, var(--chat-muted-2) 92%, var(--chat-link-accent-fg));
  font-weight: 700;
}

.empty-divider {
  width: 2.5rem;
  height: 3px;
  margin: 1rem auto 0.85rem;
  border-radius: 999px;
  background: linear-gradient(
    90deg,
    transparent,
    color-mix(in srgb, var(--chat-link-accent-fg) 55%, transparent),
    transparent
  );
}

.empty-lead {
  margin: 0 0 0.35rem;
  font-size: 1.0625rem;
  font-weight: 600;
  letter-spacing: -0.015em;
  color: var(--chat-fg-strong);
}

.empty-tagline {
  margin: 0;
  font-size: 0.8125rem;
  line-height: 1.5;
  color: var(--chat-muted-3);
}
</style>
