<script setup>
import { ref, onMounted } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { fetchConversations } from '../api/conversations'
import { getAxiosErrorMessage } from '../utils/httpError'

const auth = useAuthStore()
const router = useRouter()

const list = ref([])
const loading = ref(true)
const error = ref('')

function formatTime(iso) {
  if (iso == null) return ''
  try {
    const d = new Date(iso)
    return d.toLocaleString('zh-CN', { hour12: false })
  } catch {
    return String(iso)
  }
}

onMounted(async () => {
  if (!auth.isAuthenticated) {
    router.replace({ path: '/login', query: { redirect: '/history' } })
    return
  }
  error.value = ''
  try {
    const { data } = await fetchConversations()
    list.value = data
  } catch (e) {
    error.value = getAxiosErrorMessage(e)
  } finally {
    loading.value = false
  }
})

function openConversation(id) {
  router.push({ path: '/chat', query: { conversation: String(id) } })
}
</script>

<template>
  <div class="history-layout">
    <header class="top">
      <div class="brand">
        <span class="dot" />
        <span class="title">对话记录</span>
      </div>
      <nav class="nav-actions">
        <RouterLink class="btn-ghost link-plain" to="/chat">返回对话</RouterLink>
        <span class="user-name">{{ auth.username }}</span>
      </nav>
    </header>

    <p v-if="loading" class="muted">加载中…</p>
    <p v-else-if="error" class="err">{{ error }}</p>
    <ul v-else-if="list.length === 0" class="muted empty">暂无保存的对话，去首页开始聊天即可自动保存。</ul>
    <ul v-else class="conv-list">
      <li v-for="c in list" :key="c.id">
        <button type="button" class="conv-row" @click="openConversation(c.id)">
          <span class="conv-title">{{ c.title || '新对话' }}</span>
          <span class="conv-time">{{ formatTime(c.updatedAt) }}</span>
        </button>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.history-layout {
  min-height: 100vh;
  max-width: 720px;
  margin: 0 auto;
  padding: 52px 56px 2rem 1rem;
  padding-left: max(1rem, env(safe-area-inset-left, 0px));
  /* 右侧为全局主题按钮留出空间；刘海屏再取更大值 */
  padding-right: max(56px, env(safe-area-inset-right, 0px));
  padding-bottom: max(2rem, env(safe-area-inset-bottom, 0px) + 1rem);
}

@media (max-width: 768px) {
  .history-layout {
    padding-top: max(3rem, env(safe-area-inset-top, 0px) + 2.25rem);
  }
}

.top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 1rem 0 0.75rem;
  border-bottom: 1px solid var(--stroke);
  position: sticky;
  top: 0;
  background: linear-gradient(to bottom, var(--bg-deep) 70%, transparent);
  z-index: 2;
}

.brand {
  display: flex;
  align-items: center;
  gap: 0.6rem;
}

.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--accent);
  box-shadow: 0 0 14px rgba(94, 225, 213, 0.6);
}

.title {
  font-weight: 700;
  letter-spacing: -0.02em;
}

.nav-actions {
  display: flex;
  align-items: center;
  gap: 0.65rem;
}

.user-name {
  font-size: 0.9rem;
  color: var(--text-muted);
}

.link-plain {
  text-decoration: none;
  display: inline-flex;
  align-items: center;
}

.btn-ghost {
  padding: 0.45rem 0.85rem;
  border-radius: 10px;
  border: 1px solid var(--stroke);
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  font-size: 0.85rem;
  transition:
    color 0.15s,
    border-color 0.15s;
}

.btn-ghost:hover {
  color: var(--text);
  border-color: rgba(94, 225, 213, 0.35);
}

.muted {
  color: var(--text-muted);
  font-size: 0.95rem;
  margin-top: 1.25rem;
}

.empty {
  list-style: none;
  padding: 0;
}

.err {
  color: var(--danger);
  margin-top: 1rem;
}

.conv-list {
  list-style: none;
  padding: 0;
  margin: 1rem 0 0;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.conv-row {
  width: 100%;
  min-width: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 0.85rem 1rem;
  border-radius: var(--radius-md);
  border: 1px solid var(--stroke);
  background: rgba(18, 22, 32, 0.55);
  color: var(--text);
  cursor: pointer;
  text-align: left;
  font-size: 0.95rem;
  transition:
    border-color 0.15s,
    background 0.15s;
}

.conv-row:hover {
  border-color: rgba(94, 225, 213, 0.22);
  background: rgba(94, 225, 213, 0.06);
}

.conv-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conv-time {
  flex-shrink: 0;
  font-size: 0.8rem;
  color: var(--text-muted);
}

@media (max-width: 560px) {
  .history-layout {
    padding-left: max(0.75rem, env(safe-area-inset-left, 0px));
    padding-right: max(0.75rem, env(safe-area-inset-right, 0px));
  }

  .top {
    flex-wrap: wrap;
    align-items: flex-start;
    row-gap: 0.65rem;
  }

  .nav-actions {
    width: 100%;
    justify-content: space-between;
    flex-wrap: wrap;
    gap: 0.5rem;
  }

  .conv-row {
    flex-wrap: wrap;
    row-gap: 0.35rem;
  }

  .conv-time {
    margin-left: auto;
  }
}
</style>
