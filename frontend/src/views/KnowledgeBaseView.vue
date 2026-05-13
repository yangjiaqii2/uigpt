<script setup>
import { ref, onMounted, computed, watch, nextTick, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useKnowledgeImportStore } from '../stores/knowledgeImport'
import {
  adminRagListDocuments,
  adminRagGetDocument,
  adminRagCreateDocument,
  adminRagDeleteDocument,
} from '../api/ragAdmin'
import { getAxiosErrorMessage } from '../utils/httpError'

const route = useRoute()
const auth = useAuthStore()
const kbImport = useKnowledgeImportStore()
const loading = ref(false)
const error = ref('')
const toast = ref('')

const page = ref(0)
const size = ref(15)
/** @type {import('vue').Ref<Array<{ id: string, title: string | null, preview: string, createdAt: string }>>} */
const rows = ref([])
const totalPages = ref(0)
const totalElements = ref(0)

const addOpen = ref(false)
const addTitle = ref('')
const addText = ref('')
const addSaving = ref(false)

const previewOpen = ref(false)
/** @type {import('vue').Ref<{ id: string, title: string | null, text: string, createdAt: string } | null>} */
const previewDoc = ref(null)
const previewLoading = ref(false)

const importInputRef = ref(null)

const deleteConfirmOpen = ref(false)
/** @type {import('vue').Ref<{ id: string, title: string | null, preview: string } | null>} */
const deleteTargetRow = ref(null)
const deleteCancelBtnRef = ref(null)
const deleteSubmitting = ref(false)

const importBusy = computed(() => kbImport.busy)

const canPrev = computed(() => page.value > 0)
const canNext = computed(() => page.value < totalPages.value - 1)

watch(deleteConfirmOpen, (open) => {
  if (!open) return
  void nextTick(() => deleteCancelBtnRef.value?.focus?.())
})

function onDeleteDialogKeydown(e) {
  if (!deleteConfirmOpen.value) return
  if (e.key === 'Escape') {
    e.preventDefault()
    cancelDeleteDialog()
  }
}

onMounted(() => {
  window.addEventListener('keydown', onDeleteDialogKeydown)
  void auth.refreshMe().then(() => {
    if (auth.isSuperAdmin) void loadList()
  })
})

onUnmounted(() => {
  window.removeEventListener('keydown', onDeleteDialogKeydown)
})

watch(
  () => [kbImport.needsListRefresh, route.name],
  async ([need, name]) => {
    if (!need || name !== 'knowledge' || !auth.isSuperAdmin) return
    page.value = 0
    await loadList()
    kbImport.clearNeedsListRefresh()
  },
  { immediate: true },
)

async function loadList() {
  if (!auth.isSuperAdmin) return
  loading.value = true
  error.value = ''
  try {
    const { data } = await adminRagListDocuments({ page: page.value, size: size.value })
    rows.value = data?.content ?? []
    totalPages.value = data?.totalPages ?? 0
    totalElements.value = data?.totalElements ?? 0
  } catch (e) {
    error.value = getAxiosErrorMessage(e)
    rows.value = []
  } finally {
    loading.value = false
  }
}

function refresh() {
  page.value = 0
  void loadList()
}

function goPrev() {
  if (!canPrev.value) return
  page.value -= 1
  void loadList()
}

function goNext() {
  if (!canNext.value) return
  page.value += 1
  void loadList()
}

function openAdd() {
  addTitle.value = ''
  addText.value = ''
  addOpen.value = true
}

function closeAdd() {
  if (addSaving.value) return
  addOpen.value = false
}

async function submitAdd() {
  const text = String(addText.value ?? '').trim()
  if (!text) {
    error.value = '请输入正文'
    return
  }
  addSaving.value = true
  error.value = ''
  try {
    const title = String(addTitle.value ?? '').trim()
    await adminRagCreateDocument({
      text,
      ...(title ? { title } : {}),
    })
    toast.value = '已保存并写入向量库'
    addOpen.value = false
    page.value = 0
    await loadList()
    setTimeout(() => {
      toast.value = ''
    }, 3000)
  } catch (e) {
    error.value = getAxiosErrorMessage(e)
  } finally {
    addSaving.value = false
  }
}

async function openPreview(row) {
  previewOpen.value = true
  previewDoc.value = null
  previewLoading.value = true
  error.value = ''
  try {
    const { data } = await adminRagGetDocument(row.id)
    previewDoc.value = data
  } catch (e) {
    error.value = getAxiosErrorMessage(e)
    previewOpen.value = false
  } finally {
    previewLoading.value = false
  }
}

function closePreview() {
  previewOpen.value = false
  previewDoc.value = null
}

function openDeleteConfirm(row) {
  deleteTargetRow.value = row
  deleteConfirmOpen.value = true
}

function cancelDeleteDialog() {
  if (deleteSubmitting.value) return
  deleteConfirmOpen.value = false
  deleteTargetRow.value = null
}

async function confirmDeleteKnowledge() {
  const row = deleteTargetRow.value
  if (!row?.id || !deleteConfirmOpen.value) return
  deleteSubmitting.value = true
  error.value = ''
  try {
    await adminRagDeleteDocument(row.id)
    toast.value = '已删除'
    deleteConfirmOpen.value = false
    deleteTargetRow.value = null
    await loadList()
    setTimeout(() => {
      toast.value = ''
    }, 2500)
  } catch (e) {
    error.value = getAxiosErrorMessage(e)
  } finally {
    deleteSubmitting.value = false
  }
}

function triggerImport() {
  importInputRef.value?.click()
}

async function onImportPick(e) {
  const input = e.target
  const files = input?.files
  if (!files?.length) return
  const arr = Array.from(files)
  input.value = ''
  error.value = ''
  void kbImport.runImport(arr)
}

function displayTitle(row) {
  const t = row.title
  if (t != null && String(t).trim() !== '') return String(t).trim()
  return '—'
}
</script>

<template>
  <div class="kb">
    <header class="kb-head">
      <h1 class="kb-title">知识库</h1>
      <p class="kb-desc">
        已取消列表与 MySQL 元数据之间的单独同步：列表与详情直接读库。每次新增或导入时，保存流程内先向量化并写入
        Qdrant，再落库 MySQL（库写失败会尝试删除对应向量点）。对话检索需在服务端配置
        <code class="kb-code">UIGPT_RAG_*</code>（见 <code class="kb-code">application.yml</code> 的
        <code class="kb-code">uigpt.rag</code>）。首次使用前请在 MySQL 执行
        <code class="kb-code">backend/src/main/resources/db/knowledge_documents.mysql.sql</code>（运行时 classpath
        为 <code class="kb-code">db/knowledge_documents.mysql.sql</code>）。
      </p>
    </header>

    <p v-if="toast" class="kb-toast" role="status">{{ toast }}</p>
    <p v-if="error" class="kb-msg kb-msg--err">{{ error }}</p>

    <template v-if="auth.isSuperAdmin">
      <div class="kb-toolbar">
        <div class="kb-toolbar-left">
          <button type="button" class="kb-btn kb-btn--primary" @click="openAdd">新增</button>
          <button type="button" class="kb-btn" :disabled="importBusy" @click="triggerImport">
            {{ importBusy ? '导入中…' : '导入' }}
          </button>
          <input
            ref="importInputRef"
            type="file"
            class="kb-file-input"
            accept=".txt,.md,.csv,.pdf,.doc,.docx,.xls,.xlsx,.json,text/plain,text/markdown,text/csv,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/msword,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.ms-excel,application/json"
            multiple
            @change="onImportPick"
          />
          <button type="button" class="kb-btn" :disabled="loading" @click="loadList">刷新</button>
        </div>
        <div class="kb-toolbar-meta">
          共 <strong>{{ totalElements }}</strong> 条
        </div>
      </div>

      <div class="kb-table-wrap">
        <div v-if="loading" class="kb-loading">加载中…</div>
        <table v-else class="kb-table">
          <thead>
            <tr>
              <th class="kb-th-title">标题</th>
              <th>摘要</th>
              <th class="kb-th-time">创建时间</th>
              <th class="kb-th-actions">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in rows" :key="row.id">
              <td class="kb-td-title">{{ displayTitle(row) }}</td>
              <td class="kb-td-preview">{{ row.preview }}</td>
              <td class="kb-td-time">{{ row.createdAt }}</td>
              <td class="kb-td-actions">
                <button type="button" class="kb-link" @click="openPreview(row)">预览</button>
                <button type="button" class="kb-link kb-link--danger" @click="openDeleteConfirm(row)">删除</button>
              </td>
            </tr>
            <tr v-if="!rows.length">
              <td colspan="4" class="kb-empty">暂无条目，请点击「新增」或「导入」。</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div v-if="totalPages > 1" class="kb-pager">
        <button type="button" class="kb-btn" :disabled="!canPrev" @click="goPrev">上一页</button>
        <span class="kb-pager-meta">第 {{ page + 1 }} / {{ totalPages }} 页</span>
        <button type="button" class="kb-btn" :disabled="!canNext" @click="goNext">下一页</button>
      </div>
    </template>

    <template v-else>
      <section class="kb-panel kb-panel--muted">
        <p class="kb-muted">
          当前账号无超级管理员权限（需
          <code class="kb-code">users.privilege = 2</code>
          ）。仅超级管理员可管理知识库。
        </p>
      </section>
    </template>

    <!-- 新增 -->
    <div v-if="addOpen" class="kb-modal-backdrop" @click.self="closeAdd">
      <div class="kb-modal" role="dialog" aria-modal="true" aria-labelledby="kb-add-h">
        <h2 id="kb-add-h" class="kb-modal-title">新增知识</h2>
        <label class="kb-label">标题（可选）</label>
        <input v-model="addTitle" type="text" class="kb-input" maxlength="512" placeholder="简短标题" />
        <label class="kb-label">正文</label>
        <textarea v-model="addText" class="kb-textarea" rows="10" placeholder="知识正文…" />
        <div class="kb-modal-actions">
          <button type="button" class="kb-btn" @click="closeAdd">取消</button>
          <button type="button" class="kb-btn kb-btn--primary" :disabled="addSaving" @click="submitAdd">
            {{ addSaving ? '保存中…' : '保存并写入库' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 预览 -->
    <div v-if="previewOpen" class="kb-modal-backdrop" @click.self="closePreview">
      <div class="kb-modal kb-modal--wide" role="dialog" aria-modal="true" aria-labelledby="kb-prev-h">
        <h2 id="kb-prev-h" class="kb-modal-title">预览</h2>
        <div v-if="previewLoading" class="kb-loading">加载中…</div>
        <template v-else-if="previewDoc">
          <p class="kb-preview-meta">
            <span class="kb-preview-id">ID: {{ previewDoc.id }}</span>
            <span v-if="previewDoc.title">标题: {{ previewDoc.title }}</span>
            <span>创建: {{ previewDoc.createdAt }}</span>
          </p>
          <pre class="kb-preview-body">{{ previewDoc.text }}</pre>
        </template>
        <div class="kb-modal-actions">
          <button type="button" class="kb-btn kb-btn--primary" @click="closePreview">关闭</button>
        </div>
      </div>
    </div>

    <Teleport to="body">
      <Transition name="prm-del">
        <div
          v-if="deleteConfirmOpen"
          class="prm-del-shell"
          role="dialog"
          aria-modal="true"
          aria-labelledby="kb-del-title"
          aria-describedby="kb-del-desc"
        >
          <div class="prm-del-backdrop" @click="cancelDeleteDialog" />
          <div class="prm-del-center">
            <div class="prm-del-panel" @click.stop>
              <h2 id="kb-del-title" class="prm-del-title">删除知识</h2>
              <p id="kb-del-desc" class="prm-del-desc">
                将从列表与向量库中永久移除
                <strong class="kb-del-em">{{
                  deleteTargetRow ? displayTitle(deleteTargetRow) : '—'
                }}</strong>
                （{{ deleteTargetRow?.id || '—' }}）。请确认是否继续。
              </p>
              <div class="prm-del-actions">
                <button
                  ref="deleteCancelBtnRef"
                  type="button"
                  class="prm-del-btn prm-del-btn--ghost"
                  :disabled="deleteSubmitting"
                  @click="cancelDeleteDialog"
                >
                  取消
                </button>
                <button
                  type="button"
                  class="prm-del-btn prm-del-btn--danger"
                  :disabled="deleteSubmitting"
                  @click="confirmDeleteKnowledge"
                >
                  {{ deleteSubmitting ? '删除中…' : '确认删除' }}
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
.kb {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 28px 24px 32px;
  overflow: auto;
  box-sizing: border-box;
}

.kb-head {
  margin-bottom: 16px;
}

.kb-title {
  margin: 0 0 8px;
  font-size: 1.35rem;
  font-weight: 700;
  color: var(--chat-fg-strong, #e8ecf5);
}

.kb-desc {
  margin: 0;
  font-size: 0.9rem;
  line-height: 1.55;
  color: var(--chat-muted, #9aa3b2);
  max-width: 900px;
}

.kb-code {
  font-size: 0.8125rem;
  padding: 1px 6px;
  border-radius: 6px;
  background: var(--chat-bubble-bg, #ffffff0a);
  border: 1px solid var(--chat-border, #ffffff0f);
}

.kb-toast {
  margin: 0 0 10px;
  padding: 8px 12px;
  border-radius: 8px;
  background: color-mix(in srgb, var(--accent, #5ee1d5) 15%, transparent);
  color: var(--accent, #5ee1d5);
  font-size: 0.875rem;
}

.kb-msg--err {
  margin: 0 0 12px;
  color: var(--danger, #ff6b8a);
  font-size: 0.875rem;
}

.kb-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}

.kb-toolbar-left {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.kb-toolbar-meta {
  font-size: 0.8125rem;
  color: var(--chat-muted, #9aa3b2);
}

.kb-file-input {
  position: absolute;
  width: 0;
  height: 0;
  opacity: 0;
  pointer-events: none;
}

.kb-btn {
  padding: 7px 14px;
  border-radius: 999px;
  border: 1px solid var(--chat-border-strong, #ffffff1a);
  background: var(--chat-btn-bg, #ffffff0f);
  color: var(--chat-fg-strong, #e8ecf5);
  font-size: 0.8125rem;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease;
}

.kb-btn:hover:not(:disabled) {
  background: var(--chat-btn-bg-hover, #ffffff1a);
}

.kb-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.kb-btn--primary {
  border-color: color-mix(in srgb, var(--accent, #5ee1d5) 45%, transparent);
  background: color-mix(in srgb, var(--accent, #5ee1d5) 18%, transparent);
}

.kb-table-wrap {
  border-radius: 12px;
  border: 1px solid var(--chat-border-strong, #ffffff1a);
  background: var(--chat-panel, #242424);
  overflow: auto;
}

.kb-loading {
  padding: 24px;
  text-align: center;
  color: var(--chat-muted, #9aa3b2);
}

.kb-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.8125rem;
}

.kb-table th,
.kb-table td {
  padding: 10px 12px;
  text-align: left;
  border-bottom: 1px solid var(--chat-border, #ffffff0f);
  vertical-align: top;
}

.kb-table th {
  color: var(--chat-muted-2, #8b95a8);
  font-weight: 600;
  white-space: nowrap;
}

.kb-th-title {
  width: 140px;
}

.kb-th-time {
  width: 180px;
}

.kb-th-actions {
  width: 120px;
}

.kb-td-title {
  color: var(--chat-fg-strong, #e8ecf5);
  font-weight: 550;
}

.kb-td-preview {
  color: var(--chat-muted, #9aa3b2);
  word-break: break-word;
}

.kb-td-time {
  color: var(--chat-muted-2, #8b95a8);
  white-space: nowrap;
}

.kb-td-actions {
  white-space: nowrap;
}

.kb-link {
  padding: 0;
  margin-right: 10px;
  border: none;
  background: none;
  color: var(--accent, #5ee1d5);
  font-size: inherit;
  cursor: pointer;
  text-decoration: underline;
  text-underline-offset: 2px;
}

.kb-link--danger {
  color: var(--danger, #ff6b8a);
}

.kb-empty {
  text-align: center;
  color: var(--chat-muted, #9aa3b2);
  padding: 28px 12px;
}

.kb-pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-top: 16px;
}

.kb-pager-meta {
  font-size: 0.8125rem;
  color: var(--chat-muted, #9aa3b2);
}

.kb-panel--muted {
  max-width: 720px;
  border-radius: 14px;
  border: 1px dashed var(--chat-border-strong, #ffffff1a);
  background: var(--chat-panel, #242424);
  padding: 20px;
}

.kb-muted {
  margin: 0;
  font-size: 0.9rem;
  line-height: 1.6;
  color: var(--chat-muted-2, #8b95a8);
}

.kb-modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 100;
  background: rgba(0, 0, 0, 0.55);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}

.kb-modal {
  width: min(480px, 100%);
  max-height: min(88vh, 720px);
  overflow: auto;
  border-radius: 14px;
  border: 1px solid var(--chat-border-strong, #ffffff1a);
  background: var(--chat-panel, #242424);
  padding: 20px 20px 16px;
  box-shadow: var(--chat-panel-shadow, 0 16px 48px #00000073);
}

.kb-modal--wide {
  width: min(720px, 100%);
}

.kb-modal-title {
  margin: 0 0 14px;
  font-size: 1.05rem;
  font-weight: 650;
  color: var(--chat-fg-strong, #e8ecf5);
}

.kb-label {
  display: block;
  margin: 10px 0 6px;
  font-size: 0.8125rem;
  color: var(--chat-muted, #9aa3b2);
}

.kb-input {
  width: 100%;
  box-sizing: border-box;
  padding: 8px 10px;
  border-radius: 8px;
  border: 1px solid var(--chat-border-strong, #ffffff1a);
  background: var(--chat-shell-bg, #1a1a1a);
  color: var(--chat-fg, #e8e8e8);
  font-size: 0.875rem;
}

.kb-textarea {
  width: 100%;
  box-sizing: border-box;
  resize: vertical;
  min-height: 160px;
  padding: 10px 12px;
  border-radius: 8px;
  border: 1px solid var(--chat-border-strong, #ffffff1a);
  background: var(--chat-shell-bg, #1a1a1a);
  color: var(--chat-fg, #e8e8e8);
  font-size: 0.875rem;
  line-height: 1.5;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}

.kb-modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 16px;
}

.kb-preview-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 16px;
  font-size: 0.8125rem;
  color: var(--chat-muted, #9aa3b2);
  margin: 0 0 12px;
}

.kb-preview-id {
  word-break: break-all;
}

.kb-preview-body {
  margin: 0;
  padding: 12px;
  border-radius: 8px;
  background: var(--chat-shell-bg, #1a1a1a);
  border: 1px solid var(--chat-border, #ffffff0f);
  color: var(--chat-fg, #e8e8e8);
  font-size: 0.8125rem;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 50vh;
  overflow: auto;
}

.kb-del-em {
  color: var(--chat-fg-strong, #e8ecf5);
  font-weight: 650;
  word-break: break-word;
}

/* 与提示词页 PromptsView 删除确认一致的毛玻璃模态（类名 prm-del-* 复用） */
.prm-del-shell {
  position: fixed;
  inset: 0;
  z-index: 10080;
  pointer-events: auto;
}

.prm-del-backdrop {
  position: absolute;
  inset: 0;
  background: color-mix(in srgb, var(--chat-backdrop, rgba(0, 0, 0, 0.45)) 88%, #000);
  -webkit-backdrop-filter: blur(12px);
  backdrop-filter: blur(12px);
}

.prm-del-center {
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

.prm-del-panel {
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

.prm-del-title {
  margin: 0 0 10px;
  font-size: 1.0625rem;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--chat-fg-strong);
  text-align: center;
}

.prm-del-desc {
  margin: 0;
  font-size: 0.8125rem;
  line-height: 1.65;
  color: var(--chat-muted);
  text-align: center;
}

.prm-del-actions {
  display: flex;
  flex-direction: row;
  justify-content: flex-end;
  align-items: center;
  gap: 12px;
  margin-top: 22px;
}

.prm-del-btn {
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

.prm-del-btn:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.prm-del-btn--ghost {
  border: 1px solid var(--chat-border-strong);
  background: transparent;
  color: var(--chat-muted-2);
}

.prm-del-btn--ghost:hover:not(:disabled) {
  background: var(--chat-btn-bg-hover);
  color: var(--chat-fg-strong);
}

.prm-del-btn--ghost:active:not(:disabled) {
  transform: scale(0.97);
}

.prm-del-btn--danger {
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

.prm-del-btn--danger:hover:not(:disabled) {
  transform: translateY(-1px);
  filter: brightness(1.06);
  box-shadow: 0 12px 32px color-mix(in srgb, var(--chat-danger-fg, #f87171) 28%, transparent);
}

.prm-del-btn--danger:active:not(:disabled) {
  transform: scale(0.97);
}

.prm-del-enter-active,
.prm-del-leave-active {
  transition: opacity 0.22s ease;
}

.prm-del-enter-active .prm-del-panel,
.prm-del-leave-active .prm-del-panel {
  transition:
    transform 0.24s cubic-bezier(0.34, 1.45, 0.64, 1),
    opacity 0.22s ease;
}

.prm-del-enter-from,
.prm-del-leave-to {
  opacity: 0;
}

.prm-del-enter-from .prm-del-panel,
.prm-del-leave-to .prm-del-panel {
  opacity: 0;
  transform: scale(0.94);
}

.prm-del-enter-to .prm-del-panel,
.prm-del-leave-from .prm-del-panel {
  opacity: 1;
  transform: scale(1);
}

@media (max-width: 480px) {
  .prm-del-actions {
    flex-direction: column-reverse;
  }

  .prm-del-btn {
    width: 100%;
  }
}
</style>
