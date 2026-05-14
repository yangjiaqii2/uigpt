<script setup>
/**
 * 图片工作台：会话列表 UI 对齐多模态侧栏「新建会话 + 对话记录」（ChatSidebar）。
 */
defineProps({
  /** @type {{ id: number, title: string, updatedAt: string, imageCount: number, thumbUrl: string | null, studioSkillId?: string }[]} */
  sessions: { type: Array, default: () => [] },
  currentId: { type: Number, default: null },
  loading: { type: Boolean, default: false },
  isAuthenticated: { type: Boolean, default: true },
})

const emit = defineEmits(['select', 'new', 'delete', 'session-contextmenu'])

/** 列表展示：空标题或后端默认「新对话」不显示该字样（与 ChatSidebar 一致） */
/** @param {string | undefined} title */
function displayConvTitle(title) {
  const t = (title ?? '').trim()
  if (!t || t === '新对话') return '未命名会话'
  return t
}

function displaySessionRecordTitle(title) {
  const t = displayConvTitle(title)
  if (t.length <= 12) return t
  return `${t.slice(0, 12)}…`
}

/** 侧栏技能行：中文相对时间 */
function formatRelativeZh(iso) {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return '—'
  const diff = Date.now() - d.getTime()
  const sec = Math.floor(diff / 1000)
  if (sec < 60) return '刚刚'
  const m = Math.floor(sec / 60)
  if (m < 60) return `${m}分钟前`
  const h = Math.floor(m / 60)
  if (h < 24) return `${h}小时前`
  const days = Math.floor(h / 24)
  if (days < 7) return `${days}天前`
  return d.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })
}

/** @param {{ studioSkillId?: string }} s */
function sessionSkillLabel(s) {
  const id = s?.studioSkillId != null && String(s.studioSkillId).trim() ? String(s.studioSkillId) : 'interior_designer'
  if (id === 'universal_master') return '全能大师'
  if (id === 'interior_designer') return '家装设计师'
  return '家装设计师'
}

/** @param {{ studioSkillId?: string }} s */
function sessionSkillDotClass(s) {
  const id = s?.studioSkillId != null && String(s.studioSkillId).trim() ? String(s.studioSkillId) : 'interior_designer'
  if (id === 'universal_master') return 'sb-conv-skill-dot sb-conv-skill-dot--universal'
  return 'sb-conv-skill-dot sb-conv-skill-dot--interior'
}
</script>

<template>
  <aside class="iss-root" aria-label="图片会话记录">
    <div class="sb-new-wrap">
      <button type="button" class="sb-new-chat" :disabled="loading || !isAuthenticated" @click="emit('new')">
        <span class="sb-new-plus" aria-hidden="true">+</span>
        <span>新建会话</span>
      </button>
    </div>
    <div class="sb-divider" />

    <section class="sb-history">
      <h2 class="sb-history-title">对话记录</h2>
      <div class="sb-history-scroll">
        <template v-if="loading">
          <div class="sb-empty">
            <p class="sb-empty-text">加载中…</p>
          </div>
        </template>
        <template v-else-if="!isAuthenticated">
          <p class="sb-guest">登录后可同步与查看历史会话</p>
        </template>
        <template v-else-if="sessions.length">
          <TransitionGroup name="sbconv" tag="div" class="sb-conv-tg">
            <div v-for="s in sessions" :key="s.id" class="iss-conv-row" @contextmenu.prevent="emit('session-contextmenu', s.id)">
              <button
                type="button"
                class="sb-conv iss-conv-btn"
                :class="{ 'sb-conv--on': currentId === s.id }"
                @click="emit('select', s.id)"
              >
                <span :class="sessionSkillDotClass(s)" aria-hidden="true" />
                <span class="sb-conv-mid">
                  <span class="sb-conv-title">{{ displaySessionRecordTitle(s.title) }}</span>
                  <span class="sb-conv-skillline">
                    <span class="sb-conv-skill-emoji" aria-hidden="true">🎨</span>
                    <span class="sb-conv-skill-name">{{ sessionSkillLabel(s) }}</span>
                    <span class="sb-conv-skill-sep">·</span>
                    <span class="sb-conv-skill-time">{{ formatRelativeZh(s.updatedAt) }}</span>
                  </span>
                </span>
                <span class="sb-conv-meta">
                  <span v-if="s.imageCount != null && s.imageCount > 0" class="sb-conv-count">{{ s.imageCount }}张</span>
                </span>
              </button>
              <button
                type="button"
                class="iss-del"
                title="删除会话"
                aria-label="删除会话"
                @click.stop="emit('delete', s.id)"
              >
                ×
              </button>
            </div>
          </TransitionGroup>
        </template>
        <div v-else class="sb-empty">
          <p class="sb-empty-text">暂无对话</p>
        </div>
      </div>
    </section>
  </aside>
</template>

<style scoped>
/* 与 ChatSidebar 侧栏会话区同源变量与排版 */
.iss-root {
  --sb-brand: #2dd4bf;
  --sb-brand2: #34d399;
  position: relative;
  flex-shrink: 0;
  width: 100%;
  max-width: none;
  min-height: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 14px 12px 10px;
  box-sizing: border-box;
  background: var(--chat-sidebar-bg);
  border-inline-end: none;
}

.sb-new-wrap {
  display: flex;
  flex-direction: column;
  gap: 6px;
  flex-shrink: 0;
}

.sb-new-chat {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  width: 100%;
  height: 46px;
  border: none;
  border-radius: 999px;
  cursor: pointer;
  font-size: 0.9375rem;
  font-weight: 700;
  color: var(--chat-send-fg);
  background: linear-gradient(120deg, var(--chat-send-bg-start) 0%, var(--chat-send-bg-end) 100%);
  box-shadow:
    0 10px 28px color-mix(in srgb, var(--chat-send-bg-end) 35%, transparent),
    0 0 0 1px color-mix(in srgb, var(--chat-fg-strong) 10%, transparent) inset;
  transition:
    transform 0.18s ease,
    box-shadow 0.22s ease,
    filter 0.22s ease;
}

.sb-new-chat:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow:
    0 14px 36px color-mix(in srgb, var(--chat-send-bg-end) 42%, transparent),
    0 0 0 1px color-mix(in srgb, var(--chat-fg-strong) 12%, transparent) inset;
  filter: brightness(1.03);
}

.sb-new-chat:active:not(:disabled) {
  transform: scale(0.97);
}

.sb-new-chat:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.sb-new-plus {
  font-size: 1.25rem;
  font-weight: 500;
  opacity: 0.95;
}

.sb-divider {
  height: 1px;
  margin: 14px 4px 10px;
  flex-shrink: 0;
  background: var(--chat-toolbar-divider);
}

.sb-history {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.sb-history-title {
  margin: 0 4px 16px;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.28em;
  text-transform: uppercase;
  color: var(--chat-muted);
}

.sb-history-scroll {
  flex: 1;
  overflow-y: auto;
  overflow-x: hidden;
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding-right: 4px;
  scrollbar-width: thin;
  scrollbar-color: color-mix(in srgb, var(--chat-muted) 35%, transparent) transparent;
}

.sb-history-scroll:hover {
  scrollbar-color: color-mix(in srgb, var(--chat-muted) 55%, transparent) transparent;
}

.sb-history-scroll::-webkit-scrollbar {
  width: 4px;
}
.sb-history-scroll::-webkit-scrollbar-thumb {
  background: color-mix(in srgb, var(--chat-muted) 38%, transparent);
  border-radius: 99px;
}
.sb-history-scroll:hover::-webkit-scrollbar-thumb {
  width: 6px;
  background: color-mix(in srgb, var(--chat-muted) 58%, transparent);
}

.sb-conv-tg {
  display: flex;
  flex-direction: column;
  gap: 5px;
  width: 100%;
}

.sbconv-move {
  transition: transform 0.34s cubic-bezier(0.22, 1, 0.36, 1);
}
.sbconv-leave-active {
  transition:
    transform 0.38s cubic-bezier(0.22, 1, 0.36, 1),
    opacity 0.34s ease;
}
.sbconv-leave-to {
  opacity: 0;
  transform: translateX(-22px);
}

.iss-conv-row {
  display: flex;
  align-items: stretch;
  gap: 2px;
  width: 100%;
  min-height: 52px;
}

.iss-conv-btn.sb-conv {
  flex: 1;
  min-width: 0;
  width: auto;
}

.sb-conv {
  position: relative;
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 42px;
  padding: 8px 10px 8px 12px;
  border: none;
  border-radius: 11px;
  background: transparent;
  color: var(--chat-fg);
  cursor: pointer;
  text-align: left;
  transition:
    background 0.2s ease-out,
    color 0.2s ease-out,
    box-shadow 0.2s ease-out;
}

.sb-conv::before {
  content: '';
  position: absolute;
  left: 0;
  top: 10px;
  bottom: 10px;
  width: 3px;
  border-radius: 99px;
  background: var(--sb-brand);
  opacity: 0;
  transform: scaleY(0.6);
  transition:
    opacity 0.2s ease,
    transform 0.2s ease,
    box-shadow 0.2s ease;
}

.sb-conv:hover {
  background: var(--chat-btn-bg-hover);
  color: var(--chat-fg-strong);
}

.sb-conv:hover::before {
  opacity: 0.85;
  transform: scaleY(1);
}

.sb-conv--on {
  background: var(--chat-mode-on-bg);
  color: var(--chat-fg-strong);
}

.sb-conv--on::before {
  opacity: 1;
  box-shadow: 0 0 12px rgba(45, 212, 191, 0.55);
  transform: scaleY(1);
}

.sb-conv--on .sb-conv-title {
  font-weight: 700;
}

.sb-conv-skill-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  align-self: center;
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--chat-shell-bg) 65%, transparent);
}

/* 家装设计师：暖色 */
.sb-conv-skill-dot--interior {
  background: linear-gradient(135deg, #fb923c, #f97316, #ea580c);
}

/* 全能大师：中性偏冷的彩虹高光 */
.sb-conv-skill-dot--universal {
  background: conic-gradient(
    from 200deg,
    #94a3b8,
    #38bdf8,
    #a78bfa,
    #f472b6,
    #fbbf24,
    #94a3b8
  );
}

.sb-conv-skillline {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 3px 4px;
  margin-top: 3px;
  font-size: 11px;
  line-height: 1.35;
  color: var(--chat-muted-3);
  font-weight: 500;
}

.sb-conv-skill-emoji {
  flex-shrink: 0;
  font-size: 11px;
  line-height: 1;
  opacity: 0.92;
}

.sb-conv-skill-name {
  color: color-mix(in srgb, var(--chat-fg) 72%, var(--chat-muted) 28%);
}

.sb-conv-skill-sep {
  opacity: 0.55;
  padding: 0 1px;
}

.sb-conv-skill-time {
  color: var(--chat-muted-4);
  font-size: 10px;
}

.sb-conv-mid {
  flex: 1;
  min-width: 0;
}

.sb-conv-title {
  display: block;
  font-size: 14px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.sb-conv-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 2px;
  flex-shrink: 0;
}

.sb-conv-count {
  font-size: 9px;
  color: var(--chat-muted-3);
}

.sb-empty {
  padding: 20px 8px;
  text-align: center;
}

.sb-empty-text {
  margin: 0;
  font-size: 13px;
  color: var(--chat-muted-3);
}

.sb-guest {
  margin: 8px 4px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--chat-muted-2);
}

.iss-del {
  flex-shrink: 0;
  width: 28px;
  min-width: 28px;
  align-self: center;
  height: 32px;
  margin-right: 2px;
  border: none;
  border-radius: 9px;
  background: transparent;
  color: var(--chat-muted);
  font-size: 1.05rem;
  line-height: 1;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  opacity: 0.4;
  transition:
    opacity 0.15s ease,
    color 0.15s ease,
    background 0.15s ease;
}

.iss-conv-row:hover .iss-del {
  opacity: 0.95;
}

.iss-del:hover {
  color: #f87171;
  background: rgba(248, 113, 113, 0.12);
}
</style>
