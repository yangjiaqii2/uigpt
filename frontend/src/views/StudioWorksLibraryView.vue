<script setup>
import { ref, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { deleteMyImage, fetchMeImagesPage } from '../api/meProfile'
import FullscreenImagePreview from '../components/FullscreenImagePreview.vue'
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
const delBusy = ref({})

/** @type {import('vue').Ref<{ id: number, imageUrl?: string } | null>} */
const deleteTarget = ref(null)
const deleteCancelBtnRef = ref(null)

const previewOpen = ref(false)
const previewUrl = ref('')

function openPreview(url) {
  if (!url) return
  previewUrl.value = url
  previewOpen.value = true
}

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
    const { data } = await fetchMeImagesPage({ page: p, size: pageSize, skill: 'studio' })
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

async function downloadImage(it) {
  const url = it?.imageUrl
  if (!url) return
  const name = `studio-work-${it.id}.png`
  try {
    const res = await fetch(url, { mode: 'cors' })
    if (!res.ok) throw new Error('fetch')
    const blob = await res.blob()
    const u = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = u
    a.download = name
    a.rel = 'noopener'
    a.click()
    URL.revokeObjectURL(u)
  } catch {
    const a = document.createElement('a')
    a.href = url
    a.download = name
    a.target = '_blank'
    a.rel = 'noopener'
    a.click()
  }
}

function openDeleteConfirm(it, e) {
  e?.stopPropagation?.()
  if (!it?.id || delBusy.value[it.id]) return
  deleteTarget.value = it
}

function cancelDeleteDialog() {
  const id = deleteTarget.value?.id
  if (id != null && delBusy.value[id]) return
  deleteTarget.value = null
}

function onDeleteDialogKeydown(e) {
  if (!deleteTarget.value) return
  if (e.key === 'Escape') {
    e.preventDefault()
    cancelDeleteDialog()
  }
}

watch(deleteTarget, (t) => {
  if (!t) return
  nextTick(() => deleteCancelBtnRef.value?.focus?.())
})

async function confirmDeleteDialog() {
  const it = deleteTarget.value
  if (!it?.id || delBusy.value[it.id]) return
  delBusy.value = { ...delBusy.value, [it.id]: true }
  try {
    await deleteMyImage(it.id)
    items.value = items.value.filter((x) => x.id !== it.id)
    deleteTarget.value = null
  } catch (err) {
    error.value = getAxiosErrorMessage(err)
  } finally {
    const next = { ...delBusy.value }
    delete next[it.id]
    delBusy.value = next
  }
}

onMounted(async () => {
  window.addEventListener('keydown', onDeleteDialogKeydown)
  if (!auth.isAuthenticated) {
    router.replace({ path: '/login', query: { redirect: '/studio-works' } })
    return
  }
  await load(true)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onDeleteDialogKeydown)
})
</script>

<template>
  <div class="swl-page">
    <header class="swl-head">
      <button type="button" class="swl-back" aria-label="返回" @click="router.push('/')">←</button>
      <div class="swl-head-text">
        <h1 class="swl-title">作品库</h1>
        <p class="swl-sub">图片创作台归档</p>
      </div>
      <RouterLink class="swl-link" to="/image-gen">去创作</RouterLink>
    </header>

    <p v-if="error" class="swl-err">{{ error }}</p>
    <div v-if="loading" class="swl-loading">加载中…</div>
    <div v-else-if="items.length === 0" class="swl-empty">暂无作品，去图片创作台生成后会自动出现在这里。</div>
    <div v-else class="swl-grid">
      <article v-for="it in items" :key="it.id" class="swl-card">
        <div v-if="!loaded[it.id]" class="swl-skel" />
        <button type="button" class="swl-thumb" @click="openPreview(it.imageUrl)">
          <img
            :src="it.imageUrl"
            alt=""
            class="swl-img"
            loading="lazy"
            decoding="async"
            @load="loaded[it.id] = true"
          />
        </button>
        <div class="swl-actions">
          <button type="button" class="swl-act" @click="openPreview(it.imageUrl)">预览</button>
          <button type="button" class="swl-act swl-act--danger" :disabled="delBusy[it.id]" @click="openDeleteConfirm(it, $event)">
            {{ delBusy[it.id] ? '…' : '删除' }}
          </button>
          <button type="button" class="swl-act" @click="downloadImage(it)">下载</button>
        </div>
      </article>
    </div>
    <button v-if="hasMore && items.length > 0" type="button" class="swl-more" :disabled="loadingMore" @click="loadMore">
      {{ loadingMore ? '加载中…' : '加载更多' }}
    </button>

    <FullscreenImagePreview v-model="previewOpen" :src="previewUrl" />

    <Teleport to="body">
      <Transition name="swl-del">
        <div
          v-if="deleteTarget"
          class="swl-del-shell"
          role="dialog"
          aria-modal="true"
          aria-labelledby="swl-del-title"
          aria-describedby="swl-del-desc"
        >
          <div class="swl-del-backdrop" @click="cancelDeleteDialog" />
          <div class="swl-del-center">
            <div class="swl-del-panel" @click.stop>
              <h2 id="swl-del-title" class="swl-del-title">删除作品</h2>
              <p id="swl-del-desc" class="swl-del-desc">
                该图片将从作品库中永久移除，且无法恢复。请确认是否继续。
              </p>
              <div class="swl-del-actions">
                <button
                  ref="deleteCancelBtnRef"
                  type="button"
                  class="swl-del-btn swl-del-btn--ghost"
                  :disabled="deleteTarget && delBusy[deleteTarget.id]"
                  @click="cancelDeleteDialog"
                >
                  取消
                </button>
                <button
                  type="button"
                  class="swl-del-btn swl-del-btn--danger"
                  :disabled="!deleteTarget || delBusy[deleteTarget.id]"
                  @click="confirmDeleteDialog"
                >
                  {{ deleteTarget && delBusy[deleteTarget.id] ? '删除中…' : '确认删除' }}
                </button>
              </div>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<style scoped>
.swl-page {
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

.swl-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
  min-width: 0;
}

.swl-back {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  border: 1px solid var(--chat-border);
  background: var(--chat-btn-bg);
  color: var(--chat-fg);
  cursor: pointer;
  flex-shrink: 0;
}

.swl-head-text {
  flex: 1;
  min-width: 0;
}

.swl-title {
  margin: 0;
  font-size: 1.125rem;
  font-weight: 700;
}

.swl-sub {
  margin: 4px 0 0;
  font-size: 0.75rem;
  color: var(--chat-muted);
}

.swl-link {
  flex-shrink: 0;
  font-size: 0.8125rem;
  color: var(--chat-link-accent-fg, #6366f1);
  text-decoration: none;
  padding: 8px 12px;
  border-radius: 10px;
  border: 1px solid var(--chat-border);
}

.swl-link:hover {
  background: color-mix(in srgb, var(--chat-fg, #fff) 6%, transparent);
}

.swl-err {
  color: var(--chat-danger-fg);
  font-size: 0.875rem;
}

.swl-loading,
.swl-empty {
  text-align: center;
  padding: 48px 16px;
  color: var(--chat-muted);
}

.swl-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 14px;
}

.swl-card {
  position: relative;
  border-radius: 14px;
  overflow: hidden;
  background: var(--chat-panel);
  border: 1px solid var(--chat-border);
}

.swl-thumb {
  display: block;
  width: 100%;
  padding: 0;
  border: none;
  cursor: zoom-in;
  background: transparent;
}

.swl-img {
  width: 100%;
  aspect-ratio: 1;
  object-fit: cover;
  display: block;
  vertical-align: middle;
}

.swl-skel {
  position: absolute;
  inset: 0;
  bottom: 44px;
  background: linear-gradient(
    110deg,
    rgba(255, 255, 255, 0.04) 0%,
    rgba(255, 255, 255, 0.09) 45%,
    rgba(255, 255, 255, 0.04) 90%
  );
  background-size: 200% 100%;
  animation: swl-sh 1s ease-in-out infinite;
  z-index: 1;
  pointer-events: none;
}

@keyframes swl-sh {
  0% {
    background-position: 100% 0;
  }
  100% {
    background-position: -100% 0;
  }
}

.swl-actions {
  display: flex;
  gap: 6px;
  padding: 8px;
  border-top: 1px solid var(--chat-border);
  background: color-mix(in srgb, var(--chat-panel) 88%, transparent);
}

.swl-act {
  flex: 1;
  min-width: 0;
  padding: 8px 4px;
  border-radius: 8px;
  border: 1px solid var(--chat-border);
  background: var(--chat-btn-bg);
  color: var(--chat-fg);
  font-size: 0.72rem;
  font-weight: 600;
  cursor: pointer;
}

.swl-act:disabled {
  opacity: 0.5;
  cursor: wait;
}

.swl-act--danger {
  border-color: color-mix(in srgb, var(--chat-danger-fg, #f87171) 45%, var(--chat-border));
  color: var(--chat-danger-fg, #f87171);
}

.swl-more {
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

.swl-more:disabled {
  opacity: 0.5;
}

/* —— 删除确认：毛玻璃浮层 —— */
.swl-del-shell {
  position: fixed;
  inset: 0;
  z-index: 10080;
  pointer-events: auto;
}

.swl-del-backdrop {
  position: absolute;
  inset: 0;
  background: color-mix(in srgb, var(--chat-backdrop, rgba(0, 0, 0, 0.45)) 88%, #000);
  -webkit-backdrop-filter: blur(12px);
  backdrop-filter: blur(12px);
}

.swl-del-center {
  position: absolute;
  inset: 0;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: max(20px, env(safe-area-inset-top, 0px)) max(20px, env(safe-area-inset-right, 0px))
    max(20px, env(safe-area-inset-bottom, 0px)) max(20px, env(safe-area-inset-left, 0px));
  box-sizing: border-box;
  pointer-events: none;
}

.swl-del-panel {
  width: min(100%, 380px);
  pointer-events: auto;
  border-radius: 20px;
  border: 1px solid color-mix(in srgb, var(--chat-border-strong) 92%, transparent);
  background: color-mix(in srgb, var(--chat-panel) 76%, rgba(255, 255, 255, 0.06));
  -webkit-backdrop-filter: blur(22px);
  backdrop-filter: blur(22px);
  box-shadow:
    var(--chat-panel-shadow, 0 16px 48px rgba(0, 0, 0, 0.45)),
    inset 0 1px 0 color-mix(in srgb, var(--chat-fg-strong, #fff) 8%, transparent);
  padding: 22px 22px 18px;
  box-sizing: border-box;
}

.swl-del-title {
  margin: 0 0 10px;
  font-size: 1.0625rem;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--chat-fg-strong);
  text-align: center;
}

.swl-del-desc {
  margin: 0;
  font-size: 0.8125rem;
  line-height: 1.65;
  color: var(--chat-muted);
  text-align: center;
}

.swl-del-actions {
  display: flex;
  flex-direction: row;
  justify-content: flex-end;
  align-items: center;
  gap: 12px;
  margin-top: 22px;
}

.swl-del-btn {
  height: 42px;
  padding: 0 18px;
  border-radius: 11px;
  font-size: 0.875rem;
  font-weight: 600;
  cursor: pointer;
  transition:
    transform 0.18s ease,
    background 0.2s ease,
    border-color 0.2s ease,
    box-shadow 0.22s ease,
    opacity 0.2s ease;
}

.swl-del-btn:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.swl-del-btn--ghost {
  border: 1px solid var(--chat-border-strong);
  background: transparent;
  color: var(--chat-muted-2);
}

.swl-del-btn--ghost:hover:not(:disabled) {
  background: var(--chat-btn-bg-hover);
  color: var(--chat-fg-strong);
}

.swl-del-btn--ghost:active:not(:disabled) {
  transform: scale(0.97);
}

.swl-del-btn--danger {
  border: 1px solid color-mix(in srgb, var(--chat-danger-fg, #f87171) 55%, transparent);
  color: #fff;
  background: linear-gradient(
    145deg,
    color-mix(in srgb, var(--chat-danger-fg, #f87171) 72%, #7f1d1d),
    color-mix(in srgb, var(--chat-link-accent-fg, #5ee1d5) 12%, #991b1b)
  );
  box-shadow: 0 8px 28px color-mix(in srgb, var(--chat-danger-fg, #f87171) 22%, transparent);
  min-width: 120px;
}

.swl-del-btn--danger:hover:not(:disabled) {
  transform: translateY(-1px);
  filter: brightness(1.06);
  box-shadow: 0 12px 32px color-mix(in srgb, var(--chat-danger-fg, #f87171) 28%, transparent);
}

.swl-del-btn--danger:active:not(:disabled) {
  transform: scale(0.97);
}

.swl-del-enter-active,
.swl-del-leave-active {
  transition: opacity 0.22s ease;
}

.swl-del-enter-active .swl-del-panel,
.swl-del-leave-active .swl-del-panel {
  transition:
    transform 0.24s cubic-bezier(0.34, 1.45, 0.64, 1),
    opacity 0.22s ease;
}

.swl-del-enter-from,
.swl-del-leave-to {
  opacity: 0;
}

.swl-del-enter-from .swl-del-panel,
.swl-del-leave-to .swl-del-panel {
  opacity: 0;
  transform: scale(0.94);
}

.swl-del-enter-to .swl-del-panel,
.swl-del-leave-from .swl-del-panel {
  opacity: 1;
  transform: scale(1);
}

@media (max-width: 480px) {
  .swl-del-actions {
    flex-direction: column-reverse;
  }

  .swl-del-btn {
    width: 100%;
  }
}
</style>
