<script setup>
/** 效果图 / 生图结果卡片（phase：thinking | done | failed；配图 URL 来自服务端 MinIO） */
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { patchConversationImageFavorite } from '../../api/conversationImages'
import FullscreenImagePreview from '../FullscreenImagePreview.vue'

const props = defineProps({
  /** @type {{ phase: string, statusText?: string, progress?: number, images?: any[], collapsed?: boolean, lastUserPrompt?: string }} */
  modelValue: { type: Object, required: true },
  /** 当前会话 id，登录且已落库后有值，用于收藏接口 */
  conversationId: { type: Number, default: null },
  enableFavorite: { type: Boolean, default: false },
})

const emit = defineEmits([
  'update:modelValue',
  'regenerate',
  'download',
  'preview',
  'inpaint',
  'toggle-collapse',
  'favorite-change',
])

const zoomUrl = ref('')
const zoomOpen = ref(false)

const card = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

function openZoom(url) {
  zoomUrl.value = url
  zoomOpen.value = true
}

function onDownload(url) {
  emit('download', url)
  // 演示：新开标签打开图片；接入后可改为 blob 下载
  window.open(url, '_blank', 'noopener,noreferrer')
}

function onRegenerate() {
  emit('regenerate')
}

function onInpaint() {
  emit('inpaint')
}

const isMobile = ref(false)
/** @type {MediaQueryList | null} */
let mqListen = null
function onMq() {
  isMobile.value = mqListen?.matches ?? false
}

onMounted(() => {
  mqListen = window.matchMedia('(max-width: 768px)')
  isMobile.value = mqListen.matches
  mqListen.addEventListener('change', onMq)
})

onUnmounted(() => {
  mqListen?.removeEventListener('change', onMq)
})

const phaseLabel = computed(() => card.value.statusText || '')
const showToolbar = ref(false)

const toolbarVisible = computed(() => {
  if (card.value.phase !== 'done' || !card.value.images?.length) return false
  return showToolbar.value || isMobile.value
})

watch(
  () => card.value.phase,
  (p) => {
    if (p === 'done') showToolbar.value = false
  },
)

const favBusy = ref(false)

async function toggleFavorite(img, idx) {
  if (!props.enableFavorite || props.conversationId == null || !img?.serverImageId) return
  if (favBusy.value) return
  const next = !img.favorite
  favBusy.value = true
  try {
    await patchConversationImageFavorite(props.conversationId, img.serverImageId, next)
    const imgs = [...(card.value.images || [])]
    imgs[idx] = { ...imgs[idx], favorite: next }
    emit('update:modelValue', { ...card.value, images: imgs })
    emit('favorite-change')
  } catch {
    /* 静默失败，避免打断对话 */
  } finally {
    favBusy.value = false
  }
}
</script>

<template>
  <div class="gen-card" :class="{ 'gen-card--collapsed': card.collapsed }">
    <!-- 折叠：横向缩略条 -->
    <button
      v-if="card.collapsed && card.images?.length"
      type="button"
      class="gen-collapsed-strip"
      @click="emit('toggle-collapse')"
    >
      <img
        v-for="(img, i) in card.images.slice(0, 5)"
        :key="img.id || i"
        :src="img.url"
        alt=""
        class="gen-strip-thumb"
      />
      <span class="gen-strip-hint">展开查看</span>
    </button>

    <div v-else class="gen-card-inner">
      <!-- 失败 -->
      <div v-if="card.phase === 'failed'" class="gen-status gen-status--failed" role="alert">
        <span class="gen-status-text">{{ phaseLabel }}</span>
      </div>

      <!-- 生成状态 -->
      <div v-else-if="card.phase !== 'done'" class="gen-status">
        <div class="gen-status-row">
          <span class="gen-status-dots" aria-hidden="true">
            <span class="gen-dot" />
            <span class="gen-dot" />
            <span class="gen-dot" />
          </span>
          <span class="gen-status-text">{{ phaseLabel }}</span>
        </div>
        <div class="gen-progress-track">
          <div class="gen-progress-fill" :style="{ width: `${Math.min(100, card.progress ?? 0)}%` }" />
        </div>
      </div>

      <!-- 主图 -->
      <div
        v-for="(img, idx) in card.images || []"
        :key="img.id || idx"
        class="gen-figure-wrap"
        @mouseenter="showToolbar = true"
        @mouseleave="showToolbar = false"
      >
        <figure class="gen-figure">
          <button
            v-if="enableFavorite && conversationId && img.serverImageId && card.phase === 'done'"
            type="button"
            class="gen-fav"
            :class="{ 'gen-fav--on': img.favorite }"
            :disabled="favBusy"
            title="收藏"
            aria-label="收藏"
            @click.stop="toggleFavorite(img, idx)"
          >
            <svg class="gen-fav-svg" viewBox="0 0 24 24" aria-hidden="true">
              <path
                fill="currentColor"
                d="M12 3.2l2.37 5.47 5.93.52-4.47 3.88 1.34 5.78L12 15.9l-5.17 3.05 1.34-5.78L4.7 9.19l5.93-.52L12 3.2z"
              />
            </svg>
          </button>
          <img
            :src="img.url"
            alt="生成结果"
            class="gen-img"
            :class="{ 'gen-img--reveal': card.phase === 'done' }"
          />
          <!-- hover 操作层：桌面悬浮 / 移动端常驻底部条 -->
          <div
            class="gen-toolbar"
            :class="{ 'gen-toolbar--visible': toolbarVisible, 'gen-toolbar--mobile': isMobile }"
          >
            <button type="button" class="gen-tool-btn" title="下载" aria-label="下载" @click.stop="onDownload(img.url)">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 3v12m0 0l4-4m-4 4L8 11M5 21h14" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </button>
            <button type="button" class="gen-tool-btn" title="放大" aria-label="放大" @click.stop="openZoom(img.url)">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M15 15l6 6M10 18a8 8 0 110-16 8 8 0 010 16z" stroke-linecap="round"/></svg>
            </button>
            <button type="button" class="gen-tool-btn" title="重新生成" aria-label="重新生成" @click.stop="onRegenerate">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M4 12a8 8 0 018-8V2l3 3-3 3V6a6 6 0 00-6 6H4zM20 12a8 8 0 01-8 8v2l-3-3 3-3v2a6 6 0 006-6h2z" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </button>
            <button type="button" class="gen-tool-btn" title="局部重绘" aria-label="局部重绘" @click.stop="onInpaint">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2l2 4 4 2-4 2-2 4-2-4-4-2 4-2 2-4z" stroke-linejoin="round"/></svg>
            </button>
          </div>
        </figure>
        <!-- 参数药丸 -->
        <div class="gen-params">
          <span v-if="img.params?.size" class="gen-pill">{{ img.params.size }}</span>
          <span
            v-if="img.params?.style"
            class="gen-pill gen-pill--style"
            :title="img.params.style"
          >风格：{{ img.params.style }}</span>
          <span v-if="img.params?.model" class="gen-pill">{{ img.params.model }}</span>
        </div>
      </div>

      <button
        v-if="card.phase === 'done' && card.images?.length"
        type="button"
        class="gen-collapse-btn"
        @click="emit('toggle-collapse')"
      >
        收起为缩略图条
      </button>
    </div>

    <FullscreenImagePreview v-model="zoomOpen" :src="zoomUrl" />
  </div>
</template>

<style scoped>
.gen-card {
  width: 100%;
  max-width: min(100%, 920px);
  margin-top: 0.35rem;
}

.gen-card--collapsed .gen-card-inner {
  display: none;
}

.gen-collapsed-strip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 14px;
  border: 1px solid var(--chat-border-strong);
  background: rgba(255, 255, 255, 0.04);
  cursor: pointer;
  width: 100%;
  transition: background 0.2s ease-out;
}

html[data-theme='light'] .gen-collapsed-strip {
  background: rgba(15, 23, 42, 0.04);
}

.gen-collapsed-strip:hover {
  background: var(--chat-btn-bg-hover);
}

.gen-strip-thumb {
  width: 44px;
  height: 44px;
  border-radius: 10px;
  object-fit: cover;
  border: 1px solid var(--chat-border);
}

.gen-strip-hint {
  font-size: 0.75rem;
  color: var(--chat-muted-3);
  margin-left: auto;
}

.gen-status {
  padding: 12px 14px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid var(--chat-border);
  margin-bottom: 12px;
}

html[data-theme='light'] .gen-status {
  background: rgba(255, 255, 255, 0.65);
}

.gen-status--failed {
  border-color: color-mix(in srgb, var(--chat-danger-fg) 45%, var(--chat-border));
  background: var(--chat-danger-bg);
  color: var(--chat-danger-fg);
  font-size: 0.8125rem;
  line-height: 1.45;
}

.gen-status-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.gen-status-dots {
  display: inline-flex;
  gap: 4px;
}

.gen-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--chat-link-accent-fg);
  opacity: 0.35;
  animation: gen-pulse-dot 1.2s ease-in-out infinite;
}

.gen-dot:nth-child(2) {
  animation-delay: 0.15s;
}
.gen-dot:nth-child(3) {
  animation-delay: 0.3s;
}

@keyframes gen-pulse-dot {
  0%,
  100% {
    opacity: 0.35;
    transform: scale(1);
  }
  50% {
    opacity: 1;
    transform: scale(1.2);
  }
}

.gen-status-text {
  font-size: 0.8125rem;
  color: var(--chat-muted-2);
}

.gen-progress-track {
  height: 3px;
  border-radius: 999px;
  background: var(--chat-toolbar-divider);
  overflow: hidden;
}

.gen-progress-fill {
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, var(--chat-link-accent-fg), rgba(94, 225, 213, 0.35));
  transition: width 0.35s ease-out;
}

.gen-figure-wrap {
  position: relative;
}

.gen-figure {
  position: relative;
  margin: 0;
  border-radius: 20px;
  overflow: hidden;
  border: 1px solid var(--chat-border-strong);
  box-shadow: 0 20px 48px rgba(0, 0, 0, 0.18);
  transition:
    transform 0.35s cubic-bezier(0.22, 1, 0.36, 1),
    box-shadow 0.35s ease-out;
}

.gen-fav {
  position: absolute;
  top: 12px;
  right: 12px;
  z-index: 5;
  width: 42px;
  height: 42px;
  border-radius: 14px;
  border: none;
  background: rgba(15, 17, 22, 0.48);
  backdrop-filter: blur(10px);
  color: rgba(255, 255, 255, 0.82);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition:
    transform 0.15s ease,
    background 0.2s ease,
    color 0.2s ease;
}

.gen-fav:hover:not(:disabled) {
  transform: scale(1.07);
  background: rgba(15, 17, 22, 0.65);
}

.gen-fav--on {
  color: #facc15;
  background: rgba(234, 179, 8, 0.22);
}

.gen-fav--on:hover:not(:disabled) {
  color: #fde047;
}

.gen-fav-svg {
  width: 22px;
  height: 22px;
  display: block;
}

.gen-fav:disabled {
  opacity: 0.55;
  cursor: wait;
}

html[data-theme='light'] .gen-figure {
  box-shadow: 0 16px 40px rgba(15, 23, 42, 0.08);
}

.gen-figure:hover {
  transform: scale(1.02);
}

.gen-img {
  width: 100%;
  max-height: min(70vh, 560px);
  object-fit: contain;
  display: block;
  background: rgba(0, 0, 0, 0.2);
  filter: blur(14px);
  opacity: 0.6;
  transition:
    filter 0.65s ease-out,
    opacity 0.55s ease-out;
}

.gen-img--reveal {
  filter: blur(0);
  opacity: 1;
}

.gen-toolbar {
  position: absolute;
  left: 50%;
  bottom: 14px;
  transform: translateX(-50%) translateY(8px);
  display: flex;
  gap: 6px;
  padding: 8px 10px;
  border-radius: 999px;
  background: rgba(20, 22, 28, 0.45);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  border: 1px solid rgba(255, 255, 255, 0.12);
  opacity: 0;
  pointer-events: none;
  transition:
    opacity 0.25s ease-out,
    transform 0.25s ease-out;
}

.gen-toolbar--visible {
  opacity: 1;
  pointer-events: auto;
  transform: translateX(-50%) translateY(0);
}

.gen-toolbar--mobile {
  left: 8px;
  right: 8px;
  bottom: 8px;
  transform: none;
  width: auto;
  justify-content: space-between;
  flex-wrap: wrap;
  border-radius: 16px;
}

.gen-toolbar--mobile.gen-toolbar--visible {
  transform: none;
}

.gen-tool-btn {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.08);
  color: #fff;
  cursor: pointer;
  transition:
    background 0.2s ease-out,
    transform 0.15s ease-out;
}

.gen-tool-btn:hover {
  background: rgba(94, 225, 213, 0.35);
  transform: scale(1.06);
}

.gen-tool-btn svg {
  width: 18px;
  height: 18px;
}

.gen-params {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
  justify-content: flex-start;
}

.gen-pill {
  font-size: 0.6875rem;
  padding: 4px 10px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
  color: var(--chat-muted-2);
  border: 1px solid var(--chat-border);
}

.gen-pill--style {
  max-width: min(100%, 28rem);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

html[data-theme='light'] .gen-pill {
  background: rgba(15, 23, 42, 0.06);
}

.gen-collapse-btn {
  margin-top: 10px;
  padding: 6px 12px;
  font-size: 0.75rem;
  border-radius: 999px;
  border: 1px solid var(--chat-border);
  background: transparent;
  color: var(--chat-muted-3);
  cursor: pointer;
}

.gen-collapse-btn:hover {
  color: var(--chat-fg);
  border-color: var(--chat-link-accent-fg);
}
</style>
