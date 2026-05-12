<script setup>
/**
 * 管理员站内信：左侧会话列表 + 右侧详情与回复（独立模块，与用户管理分离）
 */
import { ref, onMounted, onUnmounted } from 'vue'
import {
  fetchAdminSiteMailThreads,
  fetchAdminSiteMailThread,
  sendAdminSiteMailReply,
  fetchAdminSiteMailUnread,
} from '../api/siteMail'
import { getAxiosErrorMessage } from '../utils/httpError'

const error = ref('')
const saving = ref(false)
const mailThreads = ref([])
const mailThreadsLoading = ref(false)
const mailUnreadTotal = ref(0)
const selectedThreadId = ref(null)
const selectedUsername = ref('')
/** @type {import('vue').Ref<Array<Record<string, unknown>>>} */
const messages = ref([])
const detailLoading = ref(false)
const replyBody = ref('')
/** @type {import('vue').Ref<File[]>} */
const replyFiles = ref([])
let pollTimer = 0

function fmtTime(v) {
  if (v == null || v === '') return '—'
  return String(v).replace('T', ' ').slice(0, 19)
}

async function refreshUnread() {
  try {
    const { data } = await fetchAdminSiteMailUnread()
    mailUnreadTotal.value = Number(data.count) || 0
  } catch {
    mailUnreadTotal.value = 0
  }
}

async function loadThreads() {
  mailThreadsLoading.value = true
  error.value = ''
  try {
    const { data } = await fetchAdminSiteMailThreads({ page: 0, size: 100 })
    mailThreads.value = data.content ?? []
    await refreshUnread()
  } catch (e) {
    error.value = getAxiosErrorMessage(e)
    mailThreads.value = []
  } finally {
    mailThreadsLoading.value = false
  }
}

async function selectThread(id, username) {
  error.value = ''
  selectedThreadId.value = id
  selectedUsername.value = username || ''
  detailLoading.value = true
  replyBody.value = ''
  replyFiles.value = []
  try {
    const { data } = await fetchAdminSiteMailThread(id)
    messages.value = data.messages ?? []
    await refreshUnread()
  } catch (e) {
    error.value = getAxiosErrorMessage(e)
    messages.value = []
  } finally {
    detailLoading.value = false
  }
}

function clearSelection() {
  selectedThreadId.value = null
  selectedUsername.value = ''
  messages.value = []
  error.value = ''
  void loadThreads()
}

async function submitReply() {
  const id = selectedThreadId.value
  if (!id) return
  const t = replyBody.value.trim()
  if (!t && replyFiles.value.length === 0) {
    error.value = '请输入回复内容或上传图片'
    return
  }
  saving.value = true
  error.value = ''
  try {
    await sendAdminSiteMailReply(id, replyBody.value, replyFiles.value)
    replyBody.value = ''
    replyFiles.value = []
    const { data } = await fetchAdminSiteMailThread(id)
    messages.value = data.messages ?? []
    await loadThreads()
  } catch (e) {
    error.value = getAxiosErrorMessage(e)
  } finally {
    saving.value = false
  }
}

function onReplyFiles(e) {
  const inp = e.target
  if (!(inp instanceof HTMLInputElement)) return
  const picked = [...(inp.files || [])]
  inp.value = ''
  replyFiles.value = [...replyFiles.value, ...picked].slice(0, 6)
}

function onEsc(e) {
  if (e.key !== 'Escape') return
  if (selectedThreadId.value) clearSelection()
}

onMounted(() => {
  void loadThreads()
  pollTimer = window.setInterval(() => void refreshUnread(), 30_000)
  document.addEventListener('keydown', onEsc)
})

onUnmounted(() => {
  if (pollTimer) window.clearInterval(pollTimer)
  document.removeEventListener('keydown', onEsc)
})
</script>

<template>
  <div class="asm">
    <aside class="asm-rail">
      <div class="asm-rail-head">
        <span class="asm-rail-title">会话</span>
        <span v-if="mailUnreadTotal > 0" class="asm-rail-badge">{{ mailUnreadTotal > 99 ? '99+' : mailUnreadTotal }}</span>
      </div>
      <p v-if="mailThreadsLoading" class="asm-rail-hint">加载中…</p>
      <div v-else class="asm-rail-list">
        <button
          v-for="t in mailThreads"
          :key="t.threadId"
          type="button"
          class="asm-rail-item"
          :class="{ 'asm-rail-item--active': selectedThreadId === t.threadId }"
          @click="selectThread(t.threadId, t.username)"
        >
          <span class="asm-rail-row1">
            <span class="asm-rail-user">{{ t.username }}</span>
            <span v-if="t.unreadForAdmin > 0" class="asm-rail-dot" aria-label="未读" />
          </span>
          <span class="asm-rail-preview">{{ t.lastPreview || '（无预览）' }}</span>
          <span class="asm-rail-time">{{ fmtTime(t.updatedAt) }}</span>
        </button>
        <p v-if="!mailThreads.length" class="asm-rail-empty">暂无用户来信</p>
      </div>
    </aside>

    <main class="asm-main">
      <template v-if="!selectedThreadId">
        <header class="asm-placeholder-head">
          <h1 class="asm-title">站内信</h1>
          <p class="asm-desc">在左侧选择用户，查看其与管理员的消息记录并回复。</p>
        </header>
      </template>
      <template v-else>
        <header class="asm-detail-head">
          <button type="button" class="asm-back" @click="clearSelection">← 返回列表</button>
          <div>
            <h1 class="asm-title">与 {{ selectedUsername }}</h1>
            <p class="asm-desc">与该用户的对话；支持文字与图片回复。</p>
          </div>
        </header>
        <p v-if="error" class="asm-err">{{ error }}</p>
        <p v-if="detailLoading" class="asm-loading">加载中…</p>
        <div v-else class="asm-thread">
          <div
            v-for="m in messages"
            :key="m.id"
            class="asm-msg"
            :class="{ 'asm-msg--user': !m.fromSelf, 'asm-msg--adm': m.fromSelf }"
          >
            <div class="asm-msg-meta">
              <span class="asm-msg-who">{{ m.fromSelf ? '我（管理员）' : selectedUsername }}</span>
              <span class="asm-msg-time">{{ fmtTime(m.createdAt) }}</span>
            </div>
            <div v-if="m.imageUrls?.length" class="asm-msg-imgs">
              <a v-for="(url, i) in m.imageUrls" :key="i" :href="url" target="_blank" rel="noopener noreferrer">
                <img :src="url" alt="" class="asm-msg-img" />
              </a>
            </div>
            <p class="asm-msg-txt">{{ (m.body || '').trim() }}</p>
          </div>
        </div>
        <div class="asm-reply">
          <textarea
            v-model="replyBody"
            class="asm-reply-ta"
            rows="4"
            maxlength="8000"
            placeholder="输入回复…"
          />
          <div class="asm-reply-row">
            <label class="asm-file">
              <input type="file" accept="image/*" multiple class="asm-file-inp" @change="onReplyFiles" />
              添加图片（最多 6 张）
            </label>
            <button type="button" class="asm-send" :disabled="saving" @click="submitReply">
              {{ saving ? '发送中…' : '发送回复' }}
            </button>
          </div>
        </div>
      </template>
    </main>
  </div>
</template>

<style scoped>
.asm {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(220px, 280px) minmax(0, 1fr);
  overflow: hidden;
  box-sizing: border-box;
}

.asm-rail {
  border-right: 1px solid var(--stroke, #2a2f3a);
  background: var(--bg-panel, #161922);
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.asm-rail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 12px;
  border-bottom: 1px solid var(--stroke, #2a2f3a);
  font-size: 0.75rem;
  font-weight: 800;
  letter-spacing: 0.1em;
  text-transform: uppercase;
  color: var(--text-muted, #9aa3b2);
}

.asm-rail-badge {
  min-width: 20px;
  height: 20px;
  padding: 0 6px;
  border-radius: 999px;
  font-size: 0.65rem;
  font-weight: 800;
  line-height: 20px;
  text-align: center;
  background: #ef4444;
  color: #fff;
}

.asm-rail-hint,
.asm-rail-empty {
  padding: 12px;
  font-size: 0.8125rem;
  color: var(--text-muted, #9aa3b2);
}

.asm-rail-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.asm-rail-item {
  text-align: left;
  border: 1px solid var(--stroke, #2a2f3a);
  border-radius: 10px;
  padding: 8px 10px;
  background: var(--bg-app, #0f1115);
  color: var(--text, #e8eaed);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 4px;
  transition:
    border-color 0.15s,
    background 0.15s;
}

.asm-rail-item:hover {
  border-color: color-mix(in srgb, var(--accent, #5ee1d5) 40%, var(--stroke, #2a2f3a));
}

.asm-rail-item--active {
  border-color: color-mix(in srgb, var(--accent, #5ee1d5) 55%, transparent);
  background: color-mix(in srgb, var(--accent, #5ee1d5) 8%, transparent);
}

.asm-rail-row1 {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.asm-rail-user {
  font-weight: 700;
  font-size: 0.875rem;
}

.asm-rail-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #f97316;
  flex-shrink: 0;
}

.asm-rail-preview {
  font-size: 0.72rem;
  color: var(--text-muted, #9aa3b2);
  line-height: 1.35;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.asm-rail-time {
  font-size: 0.65rem;
  color: var(--text-muted, #9aa3b2);
  opacity: 0.85;
}

.asm-main {
  min-width: 0;
  display: flex;
  flex-direction: column;
  padding: 22px 22px 28px;
  overflow: auto;
  box-sizing: border-box;
}

.asm-placeholder-head {
  margin-bottom: 8px;
}

.asm-detail-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 12px;
  margin-bottom: 16px;
}

.asm-back {
  border: 1px solid var(--stroke, #2a2f3a);
  background: var(--bg-panel, #161922);
  color: var(--text, #e8eaed);
  border-radius: 10px;
  padding: 8px 14px;
  font-size: 0.8125rem;
  font-weight: 600;
  cursor: pointer;
}

.asm-title {
  margin: 0 0 6px;
  font-size: 1.25rem;
}

.asm-desc {
  margin: 0;
  font-size: 0.8125rem;
  color: var(--chat-muted, #9aa3b2);
  max-width: 720px;
  line-height: 1.5;
}

.asm-err {
  color: #fecaca;
  font-size: 0.875rem;
  margin: 0 0 12px;
}

.asm-loading {
  color: var(--text-muted, #9aa3b2);
  font-size: 0.875rem;
}

.asm-thread {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 20px;
}

.asm-msg {
  max-width: 92%;
  border-radius: 12px;
  padding: 10px 12px;
  font-size: 0.875rem;
  line-height: 1.45;
}

.asm-msg--user {
  align-self: flex-start;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid var(--stroke, #2a2f3a);
}

.asm-msg--adm {
  align-self: flex-end;
  background: color-mix(in srgb, var(--accent, #5ee1d5) 12%, transparent);
  border: 1px solid color-mix(in srgb, var(--accent, #5ee1d5) 35%, transparent);
}

.asm-msg-meta {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 6px;
  font-size: 0.72rem;
  color: var(--text-muted, #9aa3b2);
}

.asm-msg-who {
  font-weight: 700;
}

.asm-msg-imgs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 6px;
}

.asm-msg-img {
  width: 72px;
  height: 72px;
  object-fit: cover;
  border-radius: 8px;
  border: 1px solid var(--stroke, #2a2f3a);
}

.asm-msg-txt {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
}

.asm-reply {
  margin-top: auto;
  padding-top: 12px;
  border-top: 1px solid var(--stroke, #2a2f3a);
}

.asm-reply-ta {
  width: 100%;
  box-sizing: border-box;
  border-radius: 12px;
  border: 1px solid var(--stroke, #2a2f3a);
  background: var(--bg-app, #0f1115);
  color: var(--text, #e8eaed);
  padding: 10px 12px;
  font-size: 0.875rem;
  margin-bottom: 10px;
  resize: vertical;
  min-height: 88px;
}

.asm-reply-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}

.asm-file {
  font-size: 0.8125rem;
  font-weight: 600;
  color: var(--accent, #5ee1d5);
  cursor: pointer;
}

.asm-file-inp {
  display: none;
}

.asm-send {
  border: none;
  border-radius: 12px;
  padding: 10px 20px;
  font-weight: 700;
  font-size: 0.875rem;
  cursor: pointer;
  background: linear-gradient(135deg, #5ee1d5, #7ee8cb);
  color: #042f2e;
}

.asm-send:disabled {
  opacity: 0.65;
  cursor: not-allowed;
}
</style>
