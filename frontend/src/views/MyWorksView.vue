<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { fetchMeImagesPage, patchMyImageFavorite } from '../api/meProfile'
import { getAxiosErrorMessage } from '../utils/httpError'

const auth = useAuthStore()
const router = useRouter()

const items = ref([])
const nextPage = ref(0)
const pageSize = 48
const loading = ref(true)
const loadingMore = ref(false)
const error = ref('')
const hasMore = ref(true)

/** @type {import('vue').Ref<Record<number, boolean>>} */
const loaded = ref({})
/** @type {import('vue').Ref<Record<number, boolean>>} */
const favBusy = ref({})

async function load(initial) {
  if (!auth.isAuthenticated) return
  if (initial) {
    loading.value = true
    nextPage.value = 0
    items.value = []
    hasMore.value = true
  } else {
    loadingMore.value = true
  }
  error.value = ''
  const p = initial ? 0 : nextPage.value
  try {
    const { data } = await fetchMeImagesPage({ page: p, size: pageSize })
    const batch = Array.isArray(data) ? data : []
    if (initial) items.value = batch
    else items.value = [...items.value, ...batch]
    hasMore.value = batch.length === pageSize
    nextPage.value = p + 1
  } catch (e) {
    error.value = getAxiosErrorMessage(e)
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

function loadMore() {
  if (!hasMore.value || loadingMore.value || loading.value) return
  load(false)
}

function openWork(it) {
  if (it.skillId === 'studio') {
    router.push({ name: 'image-gen' })
    return
  }
  if (it.conversationId != null && it.conversationId !== '') {
    router.push({ path: '/chat', query: { conversation: String(it.conversationId) } })
  } else {
    router.push({ path: '/chat' })
  }
}

async function toggleFavorite(it, e) {
  e?.stopPropagation?.()
  if (!it?.id || favBusy.value[it.id]) return
  favBusy.value = { ...favBusy.value, [it.id]: true }
  try {
    const next = !it.favorite
    await patchMyImageFavorite(it.id, next)
    it.favorite = next
  } catch (err) {
    error.value = getAxiosErrorMessage(err)
  } finally {
    const next = { ...favBusy.value }
    delete next[it.id]
    favBusy.value = next
  }
}

onMounted(async () => {
  if (!auth.isAuthenticated) {
    router.replace({ path: '/login', query: { redirect: '/works' } })
    return
  }
  await load(true)
})
</script>

<template>
  <div class="mw-page">
    <header class="mw-head">
      <button type="button" class="mw-back" aria-label="返回" @click="router.push('/')">←</button>
      <h1 class="mw-title">我的图片作品</h1>
    </header>
    <p v-if="error" class="mw-err">{{ error }}</p>
    <div v-if="loading" class="mw-loading">加载中…</div>
    <div v-else-if="items.length === 0" class="mw-empty">暂无作品</div>
    <div v-else class="mw-grid">
      <div
        v-for="it in items"
        :key="it.id"
        class="mw-cell"
        role="button"
        tabindex="0"
        @click="openWork(it)"
        @keydown.enter.prevent="openWork(it)"
      >
        <div v-if="!loaded[it.id]" class="mw-skel" />
        <img
          :src="it.imageUrl"
          alt=""
          class="mw-img"
          loading="lazy"
          decoding="async"
          @load="loaded[it.id] = true"
        />
        <button
          type="button"
          class="mw-fav"
          :class="{ 'mw-fav--on': it.favorite }"
          title="收藏"
          aria-label="收藏"
          :disabled="favBusy[it.id]"
          @click.stop="toggleFavorite(it, $event)"
        >
          <svg class="mw-fav-svg" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path
              stroke="currentColor"
              stroke-width="2"
              stroke-linejoin="round"
              d="M12 3l2.9 6.28 6.87.69-5.18 4.77 1.43 6.74L12 17.77 5.98 21.48l1.43-6.74L2.23 9.97l6.87-.69L12 3z"
            />
          </svg>
        </button>
      </div>
    </div>
    <button v-if="hasMore && items.length > 0" type="button" class="mw-more" :disabled="loadingMore" @click="loadMore">
      {{ loadingMore ? '加载中…' : '加载更多' }}
    </button>
  </div>
</template>

<style scoped>
.mw-page {
  min-height: 100vh;
  min-height: 100dvh;
  box-sizing: border-box;
  max-width: 100%;
  overflow-x: clip;
  padding: max(16px, env(safe-area-inset-top, 0px)) max(18px, env(safe-area-inset-right, 0px))
    max(48px, env(safe-area-inset-bottom, 0px)) max(18px, env(safe-area-inset-left, 0px));
  background: var(--chat-shell-bg);
  color: var(--chat-fg);
}

.mw-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
  min-width: 0;
}

.mw-back {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  border: 1px solid var(--chat-border);
  background: var(--chat-btn-bg);
  color: var(--chat-fg);
  cursor: pointer;
}

.mw-title {
  margin: 0;
  font-size: 1.125rem;
  font-weight: 700;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mw-err {
  color: var(--chat-danger-fg);
  font-size: 0.875rem;
}

.mw-loading,
.mw-empty {
  text-align: center;
  padding: 48px 16px;
  color: var(--chat-muted);
}

.mw-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 12px;
}

.mw-cell {
  position: relative;
  padding: 0;
  border: none;
  border-radius: 14px;
  overflow: hidden;
  cursor: pointer;
  background: var(--chat-panel);
  border: 1px solid var(--chat-border);
  aspect-ratio: 3 / 4;
  transition:
    transform 0.2s ease,
    box-shadow 0.2s ease;
}

.mw-fav {
  position: absolute;
  top: 8px;
  right: 8px;
  width: 34px;
  height: 34px;
  border: none;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  background: rgba(0, 0, 0, 0.48);
  backdrop-filter: blur(8px);
  cursor: pointer;
  z-index: 2;
  transition:
    transform 0.15s ease,
    color 0.15s ease,
    background 0.15s ease;
}

.mw-fav:hover:not(:disabled) {
  transform: scale(1.06);
}

.mw-fav:disabled {
  opacity: 0.5;
  cursor: wait;
}

.mw-fav--on {
  color: #fde047;
  background: color-mix(in srgb, var(--chat-link-accent-fg, #5ee1d5) 38%, rgba(0, 0, 0, 0.5));
}

.mw-fav-svg {
  width: 17px;
  height: 17px;
  display: block;
}

.mw-fav--on .mw-fav-svg path {
  fill: currentColor;
  stroke: rgba(0, 0, 0, 0.12);
}

.mw-cell:hover {
  transform: scale(1.02);
  box-shadow: var(--chat-panel-shadow);
}

.mw-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.mw-skel {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    110deg,
    rgba(255, 255, 255, 0.04) 0%,
    rgba(255, 255, 255, 0.09) 45%,
    rgba(255, 255, 255, 0.04) 90%
  );
  background-size: 200% 100%;
  animation: mw-sh 1s ease-in-out infinite;
}

@keyframes mw-sh {
  0% {
    background-position: 100% 0;
  }
  100% {
    background-position: -100% 0;
  }
}

.mw-more {
  display: block;
  width: 100%;
  max-width: 320px;
  margin: 28px auto 0;
  padding: 12px;
  border-radius: 14px;
  border: 1px dashed var(--chat-border-strong);
  background: transparent;
  color: var(--chat-muted-2);
  cursor: pointer;
}

.mw-more:disabled {
  opacity: 0.5;
}
</style>
