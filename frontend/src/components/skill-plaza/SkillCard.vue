<script setup>
defineProps({
  /** @type {{ id: string, name: string, tag?: string, ragCollection?: string, description?: string }} */
  skill: { type: Object, required: true },
})

defineEmits(['edit', 'delete'])
</script>

<template>
  <article class="spl-card">
    <div class="spl-card-head">
      <span v-if="skill.tag" class="spl-card-tag">{{ skill.tag }}</span>
      <div class="spl-card-actions">
        <button type="button" class="spl-card-act" @click.stop="$emit('edit')">编辑</button>
        <button type="button" class="spl-card-act spl-card-act--danger" @click.stop="$emit('delete')">删除</button>
      </div>
    </div>
    <h2 class="spl-card-title">{{ skill.name }}</h2>
    <p class="spl-card-id"><code>{{ skill.id }}</code></p>
    <p class="spl-card-desc">{{ skill.description || '（无描述）' }}</p>
    <p class="spl-card-rag">
      <span class="spl-card-rag-lab">RAG</span>
      <span class="spl-card-rag-val">{{ skill.ragCollection || '未绑定 · 通用扩写' }}</span>
    </p>
  </article>
</template>

<style scoped>
/* 参照提示词卡片约 120×80 量级时，本卡片为约 3× 面积：360×240，等比响应式 */
.spl-card {
  --spl-w: min(100%, 360px);
  --spl-h: 240px;
  width: var(--spl-w);
  max-width: 100%;
  height: var(--spl-h);
  box-sizing: border-box;
  padding: 18px 18px 14px;
  border-radius: 16px;
  border: 1px solid var(--chat-border-strong, #ffffff1a);
  background: var(--chat-panel, #242424);
  box-shadow: var(--chat-panel-shadow, 0 16px 48px #00000073);
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 8px;
  transition:
    border-color 0.15s ease,
    background 0.15s ease,
    transform 0.12s ease;
}

.spl-card:hover {
  border-color: color-mix(in srgb, var(--accent, #5ee1d5) 45%, var(--chat-border-strong, #fff));
  background: color-mix(in srgb, var(--chat-panel, #242424) 92%, var(--accent, #5ee1d5));
}

.spl-card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  flex-shrink: 0;
}

.spl-card-tag {
  display: inline-flex;
  align-items: center;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: 0.72rem;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: color-mix(in srgb, var(--accent, #5ee1d5) 92%, var(--chat-fg-strong, #fff));
  background: color-mix(in srgb, var(--accent, #5ee1d5) 16%, transparent);
  border: 1px solid color-mix(in srgb, var(--accent, #5ee1d5) 38%, transparent);
}

.spl-card-actions {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}

.spl-card-act {
  padding: 4px 10px;
  border-radius: 8px;
  border: 1px solid var(--chat-border-strong, #ffffff1a);
  background: color-mix(in srgb, var(--chat-fg, #fff) 6%, transparent);
  color: var(--chat-muted, #9aa3b2);
  font-size: 0.72rem;
  font-weight: 600;
  cursor: pointer;
}

.spl-card-act:hover {
  color: var(--chat-fg-strong, #e8ecf5);
  border-color: color-mix(in srgb, var(--accent, #5ee1d5) 35%, transparent);
}

.spl-card-act--danger:hover {
  color: var(--chat-danger-fg, #f87171);
  border-color: color-mix(in srgb, var(--chat-danger-fg, #f87171) 45%, transparent);
}

.spl-card-title {
  margin: 0;
  font-size: 1.125rem;
  font-weight: 750;
  letter-spacing: -0.02em;
  color: var(--chat-fg-strong, #e8ecf5);
  line-height: 1.25;
  flex-shrink: 0;
}

.spl-card-id {
  margin: 0;
  font-size: 0.75rem;
  color: var(--chat-muted-2, #8b95a8);
}

.spl-card-id code {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 0.72rem;
}

.spl-card-desc {
  margin: 0;
  flex: 1;
  min-height: 0;
  font-size: 0.8125rem;
  line-height: 1.5;
  color: var(--chat-muted, #9aa3b2);
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 3;
  overflow: hidden;
  word-break: break-word;
}

.spl-card-rag {
  margin: 0;
  margin-top: auto;
  padding-top: 8px;
  border-top: 1px solid color-mix(in srgb, var(--chat-border-strong, #fff) 55%, transparent);
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 0.75rem;
  flex-shrink: 0;
}

.spl-card-rag-lab {
  font-weight: 700;
  color: var(--chat-muted-2, #8b95a8);
  letter-spacing: 0.06em;
}

.spl-card-rag-val {
  color: var(--chat-fg-strong, #e8ecf5);
  word-break: break-all;
}

@media (max-width: 420px) {
  .spl-card {
    --spl-h: auto;
    min-height: 200px;
    height: auto;
  }
}
</style>
