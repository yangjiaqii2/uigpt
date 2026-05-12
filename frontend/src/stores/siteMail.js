import { defineStore } from 'pinia'
import { ref } from 'vue'
import { fetchSiteMailSummary } from '../api/siteMail'

export const useSiteMailStore = defineStore('siteMail', () => {
  const unreadCount = ref(0)
  const threadId = ref(null)
  const composeOpen = ref(false)
  const lastError = ref('')

  async function refreshSummary() {
    const token = typeof localStorage !== 'undefined' ? localStorage.getItem('uigpt_token') : ''
    if (!token) {
      unreadCount.value = 0
      threadId.value = null
      return
    }
    try {
      const { data } = await fetchSiteMailSummary()
      unreadCount.value = Number(data.unreadCount) || 0
      threadId.value = data.threadId != null ? Number(data.threadId) : null
      lastError.value = ''
    } catch {
      lastError.value = ''
    }
  }

  function openCompose() {
    composeOpen.value = true
  }

  function closeCompose() {
    composeOpen.value = false
  }

  function reset() {
    unreadCount.value = 0
    threadId.value = null
  }

  return {
    unreadCount,
    threadId,
    composeOpen,
    lastError,
    refreshSummary,
    openCompose,
    closeCompose,
    reset,
  }
})
