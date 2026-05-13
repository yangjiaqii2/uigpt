import { defineStore } from 'pinia'
import { ref } from 'vue'
import { adminRagImportFormData } from '../api/ragAdmin'
import { getAxiosErrorMessage } from '../utils/httpError'

/**
 * 知识库文件导入：与页面解耦，切换路由后仍可在后台完成上传；导入前将文件读入内存，避免组件销毁后 File 失效。
 */
export const useKnowledgeImportStore = defineStore('knowledgeImport', () => {
  const busy = ref(false)
  const flashMessage = ref('')
  const lastImportedCount = ref(0)
  const needsListRefresh = ref(false)

  /** @type {ReturnType<typeof setTimeout> | null} */
  let flashTimer = null

  function scheduleClearFlash(ms) {
    if (flashTimer != null) {
      clearTimeout(flashTimer)
      flashTimer = null
    }
    flashTimer = window.setTimeout(() => {
      flashMessage.value = ''
      flashTimer = null
    }, ms)
  }

  function clearNeedsListRefresh() {
    needsListRefresh.value = false
  }

  /**
   * @param {FileList | File[]} files
   */
  async function runImport(files) {
    const list = Array.from(files ?? []).filter(Boolean)
    if (!list.length) return
    if (busy.value) return
    busy.value = true
    flashMessage.value = '已提交导入任务，后台处理中…（可切换页面，不影响进度）'
    lastImportedCount.value = 0
    try {
      const fd = new FormData()
      for (const f of list) {
        const buf = await f.arrayBuffer()
        const blob = new Blob([buf], { type: f.type || 'application/octet-stream' })
        fd.append('files', blob, f.name)
      }
      const { data } = await adminRagImportFormData(fd)
      const n = data?.imported ?? 0
      lastImportedCount.value = n
      needsListRefresh.value = true
      flashMessage.value = `知识库导入完成，新增 ${n} 条`
      scheduleClearFlash(4500)
    } catch (e) {
      flashMessage.value = getAxiosErrorMessage(e)
      scheduleClearFlash(6500)
    } finally {
      busy.value = false
    }
  }

  return {
    busy,
    flashMessage,
    lastImportedCount,
    needsListRefresh,
    clearNeedsListRefresh,
    runImport,
  }
})
