<script setup>
defineProps({
  title: { type: String, default: '模块' },
  subtitle: { type: String, default: '内容建设中' },
})
</script>

<template>
  <div class="ph">
    <header class="ph-head">
      <h1 class="ph-title">{{ title }}</h1>
      <p class="ph-sub">{{ subtitle }}</p>
    </header>
    <div class="ph-card" aria-hidden="true">
      <div class="ph-placeholder" />
      <p class="ph-note">该模块页面已预留，后续可接入文生图 / 视频生成管线等能力。</p>
    </div>
  </div>
</template>

<style scoped>
.ph {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 28px 24px 32px;
  overflow: auto;
  box-sizing: border-box;
}

.ph-head {
  margin-bottom: 20px;
}

.ph-title {
  margin: 0 0 8px;
  font-size: 1.35rem;
  font-weight: 700;
}

.ph-sub {
  margin: 0;
  font-size: 0.9rem;
  color: var(--chat-muted, #9aa3b2);
}

.ph-card {
  flex: 1;
  min-height: 200px;
  border-radius: 14px;
  border: 1px dashed var(--chat-border-strong, #3d4553);
  background: color-mix(in srgb, var(--chat-panel, #1a1f2e) 88%, transparent);
  padding: 24px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
}

.ph-placeholder {
  width: min(100%, 420px);
  height: 160px;
  border-radius: 10px;
  background: linear-gradient(
    120deg,
    color-mix(in srgb, var(--chat-muted-3, #555) 25%, transparent) 0%,
    color-mix(in srgb, var(--chat-link-accent-fg, #6366f1) 12%, transparent) 50%,
    color-mix(in srgb, var(--chat-muted-3, #555) 25%, transparent) 100%
  );
  opacity: 0.55;
}

.ph-note {
  margin: 0;
  font-size: 0.8125rem;
  color: var(--chat-muted-2, #8b95a8);
  text-align: center;
  max-width: 420px;
}
</style>
