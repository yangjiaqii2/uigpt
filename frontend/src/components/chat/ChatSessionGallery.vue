<script setup>
/**
 * 会话右侧作品瀑布流：筛选、懒加载、折叠、移动端抽屉、灯箱与上下文菜单。
 */
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'

const FILTERS = [
  { id: 'all', label: '全部' },
  { id: 'mockup', label: '效果图' },
  { id: 'wireframe', label: '原型图' },
  { id: 'retouch', label: '修图' },
  { id: 'palette', label: '配色' },
]

const props = defineProps({
  /** @type {{ id: string | number, url: string, skillId: string, messageIndex: number, serverImageId?: number }[]} */
  items: { type: Array, default: () => [] },
  collapsed: { type: Boolean, default: false },
  selectedSkillId: { type: String, default: 'freeform' },
})

const emit = defineEmits([
  'update:collapsed',
  'locate',
  'delete-image',
  'use-reference',
  'download',
])

const filterId = ref('all')
const mobileOpen = ref(false)
const isMobile = ref(false)
/** @type {import('vue').Ref<MediaQueryList | null>} */
let mq = null

const PAGE = 50
const visibleLimit = ref(PAGE)

watch(
  () => props.selectedSkillId,
  (id) => {
    if (id === 'mockup' || id === 'wireframe' || id === 'retouch' || id === 'palette') {
      filterId.value = id
    }
  },
  { immediate: true },
)

const filteredItems = computed(() => {
  if (filterId.value === 'all') return props.items
  return props.items.filter((x) => x.skillId === filterId.value)
})

watch(filteredItems, () => {
  visibleLimit.value = PAGE
})

const displayedItems = computed(() => filteredItems.value.slice(0, visibleLimit.value))
const hasMore = computed(() => filteredItems.value.length > visibleLimit.value)

function loadMore() {
  visibleLimit.value += PAGE
}

/** @type {import('vue').Ref<Record<string, boolean>>} */
const imgLoaded = ref({})
function markLoaded(key, v) {
  imgLoaded.value = { ...imgLoaded.value, [key]: v }
}

const fsOpen = ref(false)
const fsIndex = ref(0)
function openFs(i) {
  fsIndex.value = i
  fsOpen.value = true
}
function closeFs() {
  fsOpen.value = false
}
function fsPrev() {
  const n = filteredItems.value.length
  if (n === 0) return
  fsIndex.value = (fsIndex.value - 1 + n) % n
}
function fsNext() {
  const n = filteredItems.value.length
  if (n === 0) return
  fsIndex.value = (fsIndex.value + 1) % n
}

/** 上下文菜单 */
const ctx = ref(
  /** @type {{ x: number, y: number, item: (typeof props.items)[0] } | null} */ (null),
)
function closeCtx() {
  ctx.value = null
}

function onCardClick(item) {
  emit('locate', item.messageIndex)
}

function onCardDblclick(item) {
  const idx = filteredItems.value.indexOf(item)
  openFs(idx >= 0 ? idx : 0)
}

function onCtxDownload(item) {
  emit('download', item.url)
  closeCtx()
}
function onCtxDelete(item) {
  emit('delete-image', item)
  closeCtx()
}
function onCtxRef(item) {
  emit('use-reference', item.url)
  closeCtx()
}

/** 触摸长按 */
let longPressTimer = null
function touchStart(item, e) {
  longPressTimer = window.setTimeout(() => {
    const t = e.touches?.[0]
    if (t) ctx.value = { x: t.clientX, y: t.clientY, item }
  }, 520)
}
function touchEnd() {
  if (longPressTimer) {
    clearTimeout(longPressTimer)
    longPressTimer = null
  }
}

/** 灯箱滑动 */
let fsTouchX = 0
function onFsTouchStart(e) {
  fsTouchX = e.changedTouches[0].clientX
}
function onFsTouchEnd(e) {
  const dx = e.changedTouches[0].clientX - fsTouchX
  if (dx > 56) fsPrev()
  else if (dx < -56) fsNext()
}

function onDocClick() {
  closeCtx()
}

function onKey(e) {
  if (!fsOpen.value) return
  if (e.key === 'Escape') closeFs()
  if (e.key === 'ArrowLeft') fsPrev()
  if (e.key === 'ArrowRight') fsNext()
}

function setCollapsed(v) {
  emit('update:collapsed', v)
}

function toggleCollapsed() {
  setCollapsed(!props.collapsed)
}

function onMq() {
  isMobile.value = mq?.matches ?? false
  if (!isMobile.value) mobileOpen.value = false
}

onMounted(() => {
  mq = window.matchMedia('(max-width: 768px)')
  onMq()
  mq.addEventListener('change', onMq)
  document.addEventListener('click', onDocClick)
  document.addEventListener('keydown', onKey)
})

onUnmounted(() => {
  mq?.removeEventListener('change', onMq)
  document.removeEventListener('click', onDocClick)
  document.removeEventListener('keydown', onKey)
  if (longPressTimer) clearTimeout(longPressTimer)
})

watch(mobileOpen, async (o) => {
  if (o) await nextTick()
})

const fsUrl = computed(() => filteredItems.value[fsIndex.value]?.url ?? '')
</script>

<template>
  <!-- 移动端：底部抽屉触发 -->
  <button
    v-if="isMobile && !mobileOpen"
    type="button"
    class="gal-mobile-fab"
    aria-label="本会话作品"
    @click.stop="mobileOpen = true"
  >
    <span class="gal-mobile-fab-ic" aria-hidden="true">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
        <rect x="3" y="5" width="7" height="7" rx="1.5" />
        <rect x="14" y="5" width="7" height="7" rx="1.5" />
        <rect x="3" y="14" width="7" height="7" rx="1.5" />
        <rect x="14" y="14" width="7" height="7" rx="1.5" />
      </svg>
    </span>
    <span class="gal-mobile-fab-t">作品</span>
  </button>

  <div
    v-if="isMobile && mobileOpen"
    class="gal-mobile-backdrop"
    aria-hidden="true"
    @click="mobileOpen = false"
  />

  <!-- 桌面侧栏 + 移动端抽屉内容 -->
  <aside
    class="gal-aside"
    :class="{
      'gal-aside--collapsed': collapsed && !isMobile,
      'gal-aside--mobile-open': isMobile && mobileOpen,
    }"
    aria-label="本会话作品"
    @click.stop
  >
    <button
      v-if="isMobile"
      type="button"
      class="gal-mobile-close"
      aria-label="收起作品栏"
      @click="mobileOpen = false"
    >
      <span class="gal-mobile-close-label">收起</span>
      <span class="gal-mobile-close-x" aria-hidden="true">×</span>
    </button>

    <!-- 拖拽条 / 收起触发 -->
    <button
      v-if="!isMobile"
      type="button"
      class="gal-edge-hit"
      :title="collapsed ? '展开作品栏' : '收起作品栏'"
      aria-label="收起或展开作品栏"
      @click="toggleCollapsed"
    />

    <div class="gal-panel-inner">
      <header class="gal-head">
        <div class="gal-head-row">
          <span class="gal-head-ic" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
              <rect x="3" y="5" width="7" height="7" rx="1.5" />
              <rect x="14" y="5" width="7" height="7" rx="1.5" />
              <rect x="3" y="14" width="7" height="7" rx="1.5" />
              <rect x="14" y="14" width="7" height="7" rx="1.5" />
            </svg>
          </span>
          <h2 class="gal-title">本会话作品</h2>
          <div class="gal-head-trailing">
            <span class="gal-count">共 {{ filteredItems.length }} 张</span>
            <button
              v-if="!isMobile && !collapsed"
              type="button"
              class="gal-head-collapse"
              title="收起作品栏"
              aria-label="收起作品栏"
              @click="setCollapsed(true)"
            >
              收起
            </button>
          </div>
        </div>
        <div class="gal-filter-scroll">
          <button
            v-for="f in FILTERS"
            :key="f.id"
            type="button"
            class="gal-pill"
            :class="{ 'gal-pill--on': filterId === f.id }"
            @click="filterId = f.id"
          >
            {{ f.label }}
          </button>
        </div>
      </header>

      <div class="gal-scroll">
        <div v-if="filteredItems.length === 0" class="gal-empty">
          <div class="gal-empty-art" aria-hidden="true">
            <svg viewBox="0 0 120 100" fill="none" class="gal-empty-svg">
              <path
                d="M20 78h80M28 70V42l16-18 24 30 12-10 12 26v18M44 52l-8 18M92 58l8 12"
                stroke="currentColor"
                stroke-width="1.2"
                stroke-linecap="round"
                opacity="0.35"
              />
              <circle cx="58" cy="28" r="6" stroke="currentColor" stroke-width="1" opacity="0.25" />
            </svg>
          </div>
          <p class="gal-empty-text">暂无作品，开始创作吧</p>
        </div>

        <TransitionGroup v-else name="gal-fly" tag="div" class="gal-masonry">
          <div
            v-for="(item, i) in displayedItems"
            :key="item.id"
            class="gal-card-wrap"
            @click.stop="onCardClick(item)"
            @dblclick.stop="onCardDblclick(item)"
            @contextmenu.prevent.stop="
              ctx = { x: $event.clientX, y: $event.clientY, item }
            "
            @touchstart.passive="touchStart(item, $event)"
            @touchend="touchEnd"
            @touchcancel="touchEnd"
          >
            <div class="gal-card">
              <div v-if="!imgLoaded[item.id]" class="gal-skel" />
              <img
                :src="item.url"
                alt=""
                class="gal-thumb"
                loading="lazy"
                decoding="async"
                @load="markLoaded(item.id, true)"
              />
              <div class="gal-hover">
                <div class="gal-hover-actions">
                  <button
                    type="button"
                    class="gal-act"
                    title="下载"
                    aria-label="下载"
                    @click.stop="
                      emit('download', item.url)
                    "
                  >
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <path d="M12 3v12m0 0l4-4m-4 4L8 11M5 21h14" stroke-linecap="round" />
                    </svg>
                  </button>
                  <button
                    type="button"
                    class="gal-act"
                    title="放大"
                    aria-label="放大"
                    @click.stop="openFs(filteredItems.indexOf(item))"
                  >
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <path d="M15 15l6 6M10 18a8 8 0 110-16 8 8 0 010 16z" stroke-linecap="round" />
                    </svg>
                  </button>
                  <button
                    type="button"
                    class="gal-act"
                    title="定位到消息"
                    aria-label="定位到消息"
                    @click.stop="emit('locate', item.messageIndex)"
                  >
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <path d="M12 5v14M8 9l4-4 4 4" stroke-linecap="round" stroke-linejoin="round" />
                    </svg>
                  </button>
                </div>
              </div>
            </div>
          </div>
        </TransitionGroup>

        <button v-if="hasMore" type="button" class="gal-more" @click="loadMore">加载更多</button>
      </div>
    </div>
  </aside>

  <!-- 收起后桌面窄条 -->
  <button
    v-if="!isMobile && collapsed"
    type="button"
    class="gal-collapsed-tab"
    aria-label="展开作品栏"
    @click="setCollapsed(false)"
  >
    <span class="gal-collapsed-tab-ic" aria-hidden="true">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
        <rect x="3" y="5" width="7" height="7" rx="1.5" />
        <rect x="14" y="5" width="7" height="7" rx="1.5" />
        <rect x="3" y="14" width="7" height="7" rx="1.5" />
        <rect x="14" y="14" width="7" height="7" rx="1.5" />
      </svg>
    </span>
    <span class="gal-collapsed-tab-t">作品</span>
  </button>

  <!-- 上下文菜单 -->
  <Teleport to="body">
    <div
      v-if="ctx"
      class="gal-ctx"
      :style="{ left: ctx.x + 'px', top: ctx.y + 'px' }"
      @click.stop
    >
      <button type="button" class="gal-ctx-i" @click="onCtxDownload(ctx.item)">下载</button>
      <button type="button" class="gal-ctx-i" @click="onCtxDelete(ctx.item)">删除此记录</button>
      <button type="button" class="gal-ctx-i" @click="onCtxRef(ctx.item)">以此为参考图重新生成</button>
    </div>
  </Teleport>

  <!-- 灯箱 -->
  <Teleport to="body">
    <div
      v-if="fsOpen"
      class="gal-fs"
      role="dialog"
      aria-modal="true"
      @click.self="closeFs"
    >
      <div class="gal-fs-blur" aria-hidden="true" />
      <button type="button" class="gal-fs-x" aria-label="关闭" @click="closeFs">×</button>
      <button type="button" class="gal-fs-nav gal-fs-nav--prev" aria-label="上一张" @click.stop="fsPrev">
        ‹
      </button>
      <img
        :src="fsUrl"
        alt=""
        class="gal-fs-img"
        @touchstart.passive="onFsTouchStart"
        @touchend="onFsTouchEnd"
      />
      <button type="button" class="gal-fs-nav gal-fs-nav--next" aria-label="下一张" @click.stop="fsNext">
        ›
      </button>
    </div>
  </Teleport>
</template>

<style scoped>
.gal-aside {
  --gal-w: 300px;
  flex: 0 0 var(--gal-w);
  width: var(--gal-w);
  max-width: var(--gal-w);
  min-width: 0;
  display: flex;
  position: relative;
  background: var(--gal-panel-bg);
  border-left: 1px solid var(--gal-divider);
  box-shadow: -6px 0 24px rgba(0, 0, 0, 0.12);
  transition:
    flex-basis 0.3s ease-out,
    width 0.3s ease-out,
    opacity 0.25s ease,
    border-color 0.2s ease;
  z-index: 4;
}

html[data-theme='light'] .gal-aside {
  box-shadow: -4px 0 20px rgba(15, 23, 42, 0.06);
}

.gal-aside--collapsed {
  flex-basis: 0 !important;
  width: 0 !important;
  max-width: 0 !important;
  opacity: 0;
  pointer-events: none;
  overflow: hidden;
  border-left-width: 0;
}

.gal-edge-hit {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 4px;
  transform: translateX(-2px);
  padding: 0;
  border: none;
  background: rgba(255, 255, 255, 0.04);
  cursor: col-resize;
  z-index: 6;
}

.gal-edge-hit:hover {
  background: rgba(94, 225, 213, 0.35);
}

.gal-panel-inner {
  flex: 1;
  min-width: var(--gal-w);
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.gal-head {
  flex-shrink: 0;
  padding: 12px 12px 10px;
  position: sticky;
  top: 0;
  z-index: 3;
  background: inherit;
  border-bottom: 1px solid transparent;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.06);
}

html[data-theme='dark'] .gal-head {
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.35);
}

.gal-head-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.gal-head-ic {
  display: flex;
  color: var(--chat-link-accent-fg);
  opacity: 0.9;
}

.gal-head-ic svg {
  width: 18px;
  height: 18px;
}

.gal-title {
  margin: 0;
  flex: 1;
  min-width: 0;
  font-size: 0.8125rem;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--chat-fg-strong);
}

.gal-head-trailing {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  margin-left: auto;
}

.gal-head-collapse {
  padding: 4px 10px;
  border-radius: 8px;
  border: 1px solid var(--chat-border);
  background: var(--chat-btn-bg);
  color: var(--chat-muted-2);
  font-size: 0.6875rem;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  transition:
    background 0.15s,
    border-color 0.15s,
    color 0.15s;
}

.gal-head-collapse:hover {
  background: var(--chat-btn-bg-hover);
  color: var(--chat-fg-strong);
  border-color: var(--chat-border-strong);
}

.gal-count {
  font-size: 0.6875rem;
  color: var(--chat-muted-3);
  white-space: nowrap;
}

.gal-filter-scroll {
  display: flex;
  gap: 6px;
  overflow-x: auto;
  padding-bottom: 2px;
  scrollbar-width: none;
}

.gal-filter-scroll::-webkit-scrollbar {
  height: 0;
}

.gal-pill {
  flex-shrink: 0;
  padding: 5px 11px;
  border-radius: 999px;
  border: 1px solid var(--chat-border);
  background: rgba(255, 255, 255, 0.04);
  color: var(--chat-muted-2);
  font-size: 0.6875rem;
  cursor: pointer;
  transition:
    background 0.15s,
    border-color 0.15s,
    color 0.15s;
}

html[data-theme='light'] .gal-pill {
  background: rgba(15, 23, 42, 0.04);
}

.gal-pill--on {
  border-color: color-mix(in srgb, var(--chat-link-accent-fg) 55%, var(--chat-border));
  color: var(--chat-link-accent-fg);
  background: var(--chat-link-accent-bg);
}

.gal-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 10px 10px 16px;
  scrollbar-width: thin;
  scrollbar-color: rgba(255, 255, 255, 0.15) transparent;
}

.gal-scroll::-webkit-scrollbar {
  width: 4px;
}
.gal-scroll::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.12);
  border-radius: 99px;
}
.gal-scroll:hover::-webkit-scrollbar {
  width: 7px;
}
.gal-scroll:hover::-webkit-scrollbar-thumb {
  background: rgba(94, 225, 213, 0.35);
}

html[data-theme='light'] .gal-scroll::-webkit-scrollbar-thumb {
  background: rgba(15, 23, 42, 0.15);
}

.gal-masonry {
  column-count: 2;
  column-gap: 9px;
}

.gal-card-wrap {
  break-inside: avoid;
  margin-bottom: 9px;
  cursor: pointer;
}

.gal-card {
  position: relative;
  border-radius: 12px;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.06);
  box-shadow: 0 8px 22px rgba(0, 0, 0, 0.2);
  transition:
    transform 0.35s cubic-bezier(0.34, 1.45, 0.64, 1),
    box-shadow 0.35s ease;
}

html[data-theme='light'] .gal-card {
  border-color: rgba(15, 23, 42, 0.08);
  box-shadow: 0 6px 18px rgba(15, 23, 42, 0.08);
}

.gal-card:hover {
  transform: translateY(-4px) scale(1.03);
  box-shadow: 0 18px 40px rgba(0, 0, 0, 0.35);
}

.gal-skel {
  aspect-ratio: 3 / 4;
  background: linear-gradient(
    110deg,
    rgba(255, 255, 255, 0.04) 0%,
    rgba(255, 255, 255, 0.09) 42%,
    rgba(255, 255, 255, 0.04) 84%
  );
  background-size: 200% 100%;
  animation: gal-shimmer 1.2s ease-in-out infinite;
}

@keyframes gal-shimmer {
  0% {
    background-position: 100% 0;
  }
  100% {
    background-position: -100% 0;
  }
}

.gal-thumb {
  display: block;
  width: 100%;
  height: auto;
  vertical-align: middle;
}

.gal-hover {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.3);
  opacity: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: opacity 0.22s ease;
  pointer-events: none;
}

.gal-card:hover .gal-hover {
  opacity: 1;
  pointer-events: auto;
}

.gal-hover-actions {
  display: flex;
  gap: 8px;
}

.gal-act {
  width: 38px;
  height: 38px;
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.18);
  background: rgba(255, 255, 255, 0.12);
  backdrop-filter: blur(12px);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
}

.gal-act svg {
  width: 18px;
  height: 18px;
}

.gal-empty {
  padding: 28px 12px;
  text-align: center;
}

.gal-empty-art {
  color: var(--chat-muted-4);
  animation: gal-float 4s ease-in-out infinite;
}

@keyframes gal-float {
  0%,
  100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-5px);
  }
}

.gal-empty-svg {
  width: 120px;
  height: 100px;
  margin: 0 auto;
}

.gal-empty-text {
  margin: 12px 0 0;
  font-size: 0.75rem;
  color: var(--chat-muted-3);
}

.gal-more {
  width: 100%;
  margin-top: 12px;
  padding: 8px;
  border-radius: 10px;
  border: 1px dashed var(--chat-border-strong);
  background: transparent;
  color: var(--chat-muted-2);
  font-size: 0.72rem;
  cursor: pointer;
}

.gal-collapsed-tab {
  position: absolute;
  right: 0;
  top: 50%;
  transform: translateY(-50%);
  z-index: 5;
  width: 34px;
  padding: 14px 0;
  border-radius: 10px 0 0 10px;
  border: 1px solid var(--gal-divider);
  border-right: none;
  background: var(--gal-panel-bg);
  color: var(--chat-muted-2);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  box-shadow: -4px 0 18px rgba(0, 0, 0, 0.15);
}

.gal-collapsed-tab:hover {
  color: var(--chat-link-accent-fg);
}

.gal-collapsed-tab-ic svg {
  width: 18px;
  height: 18px;
}

.gal-collapsed-tab-t {
  writing-mode: vertical-rl;
  font-size: 0.72rem;
  letter-spacing: 0.12em;
}

.gal-ctx {
  position: fixed;
  z-index: 25000;
  min-width: 168px;
  padding: 6px;
  border-radius: 12px;
  border: 1px solid var(--chat-border-strong);
  background: var(--chat-panel);
  box-shadow: var(--chat-panel-shadow);
}

.gal-ctx-i {
  display: block;
  width: 100%;
  text-align: left;
  padding: 8px 10px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--chat-fg);
  font-size: 0.78rem;
  cursor: pointer;
}

.gal-ctx-i:hover {
  background: var(--chat-btn-bg-hover);
}

.gal-fs {
  position: fixed;
  inset: 0;
  z-index: 24000;
  display: flex;
  align-items: center;
  justify-content: center;
}

.gal-fs-blur {
  position: absolute;
  inset: 0;
  backdrop-filter: blur(14px);
  background: rgba(0, 0, 0, 0.55);
}

.gal-fs-img {
  position: relative;
  max-width: min(96vw, 1200px);
  max-height: 88vh;
  object-fit: contain;
  border-radius: 12px;
  z-index: 1;
}

.gal-fs-x {
  position: absolute;
  top: 16px;
  right: 18px;
  z-index: 2;
  width: 44px;
  height: 44px;
  border: none;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.12);
  color: #fff;
  font-size: 1.5rem;
  cursor: pointer;
}

.gal-fs-nav {
  position: absolute;
  top: 50%;
  transform: translateY(-50%);
  z-index: 2;
  width: 44px;
  height: 56px;
  border: none;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
  font-size: 1.6rem;
  cursor: pointer;
}

.gal-fs-nav--prev {
  left: 12px;
}
.gal-fs-nav--next {
  right: 12px;
}

/* TransitionGroup 新图入场 */
.gal-fly-enter-active {
  transition:
    opacity 0.42s cubic-bezier(0.34, 1.45, 0.64, 1),
    transform 0.42s cubic-bezier(0.34, 1.45, 0.64, 1);
}
.gal-fly-enter-from {
  opacity: 0;
  transform: translateY(-20px);
}
.gal-fly-move {
  transition: transform 0.42s cubic-bezier(0.34, 1.45, 0.64, 1);
}

/* 移动端 */
.gal-mobile-fab {
  display: none;
}

.gal-mobile-backdrop {
  display: none;
}

.gal-mobile-close {
  display: none;
}

@media (max-width: 768px) {
  .gal-aside {
    position: fixed;
    right: 0;
    left: 0;
    bottom: 0;
    height: min(60vh, calc(100dvh - env(safe-area-inset-bottom, 0px)));
    max-height: min(60vh, calc(100dvh - env(safe-area-inset-bottom, 0px)));
    width: 100% !important;
    padding-bottom: env(safe-area-inset-bottom, 0px);
    box-sizing: border-box;
    flex-basis: auto !important;
    border-left: none;
    border-top: 1px solid var(--gal-divider);
    border-radius: 18px 18px 0 0;
    transform: translateY(100%);
    transition: transform 0.32s cubic-bezier(0.22, 1, 0.36, 1);
    box-shadow: 0 -12px 40px rgba(0, 0, 0, 0.35);
  }

  .gal-aside--mobile-open {
    transform: translateY(0);
    opacity: 1;
    pointer-events: auto;
    max-width: none !important;
  }

  .gal-edge-hit {
    display: none;
  }

  .gal-panel-inner {
    min-width: 0;
  }

  .gal-head {
    padding-right: 100px;
  }

  .gal-masonry {
    column-count: 1;
    display: flex;
    gap: 10px;
    overflow-x: auto;
    padding-bottom: 8px;
    scroll-snap-type: x mandatory;
  }

  .gal-card-wrap {
    flex: 0 0 42vw;
    max-width: 180px;
    scroll-snap-align: start;
    margin-bottom: 0;
  }

  .gal-mobile-fab {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 2px;
    position: fixed;
    right: max(12px, env(safe-area-inset-right, 0px));
    bottom: calc(88px + env(safe-area-inset-bottom, 0px));
    z-index: 30;
    width: 52px;
    padding: 8px 6px;
    border-radius: 14px;
    border: 1px solid var(--chat-border-strong);
    background: var(--gal-panel-bg);
    color: var(--chat-muted-2);
    box-shadow: var(--chat-panel-shadow);
    cursor: pointer;
  }

  .gal-mobile-fab-ic svg {
    width: 20px;
    height: 20px;
  }

  .gal-mobile-fab-t {
    font-size: 0.625rem;
    letter-spacing: 0.06em;
  }

  .gal-mobile-backdrop {
    display: block;
    position: fixed;
    inset: 0;
    z-index: 29;
    background: rgba(0, 0, 0, 0.38);
  }

  .gal-mobile-close {
    display: flex;
    position: absolute;
    right: 10px;
    top: 8px;
    z-index: 8;
    min-width: 36px;
    height: 36px;
    padding: 0 10px;
    gap: 4px;
    align-items: center;
    justify-content: center;
    border: none;
    border-radius: 10px;
    background: var(--chat-btn-bg);
    color: var(--chat-fg);
    font-size: 0.75rem;
    font-weight: 600;
    cursor: pointer;
  }

  .gal-mobile-close-x {
    font-size: 1.2rem;
    font-weight: 400;
    line-height: 1;
    opacity: 0.9;
  }

  .gal-collapsed-tab {
    display: none;
  }
}
</style>

<style>
/* 主题变量挂在 html 上，scoped 独立组件需全局一块 */
html[data-theme='dark'],
html:not([data-theme]) {
  --gal-panel-bg: #0a0a0c;
  --gal-divider: rgba(255, 255, 255, 0.08);
}

html[data-theme='light'] {
  --gal-panel-bg: #f3f4f8;
  --gal-divider: rgba(15, 23, 42, 0.08);
}
</style>
