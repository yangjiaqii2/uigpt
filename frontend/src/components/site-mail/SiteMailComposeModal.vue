<script setup>
/**
 * 联系管理员：毛玻璃拟态 + 正文 + 多图上传（与站内信 API 对接）
 */
import { ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useSiteMailStore } from '../../stores/siteMail'
import { sendSiteMailMessage } from '../../api/siteMail'
import { getAxiosErrorMessage } from '../../utils/httpError'

const store = useSiteMailStore()
const { composeOpen } = storeToRefs(store)

const body = ref('')
const files = ref([])
const previews = ref([])
const submitting = ref(false)
const err = ref('')
const ok = ref('')

watch(composeOpen, (o) => {
  if (o) {
    body.value = ''
    files.value = []
    previews.value.forEach((u) => URL.revokeObjectURL(u))
    previews.value = []
    err.value = ''
    ok.value = ''
  }
})

function close() {
  store.closeCompose()
}

function onPickFiles(e) {
  const inp = e.target
  if (!(inp instanceof HTMLInputElement)) return
  const picked = [...(inp.files || [])]
  inp.value = ''
  const next = [...files.value, ...picked].slice(0, 6)
  files.value = next
  syncPreviews()
}

function removeAt(i) {
  files.value = files.value.filter((_, j) => j !== i)
  syncPreviews()
}

function syncPreviews() {
  previews.value.forEach((u) => URL.revokeObjectURL(u))
  previews.value = files.value.map((f) => URL.createObjectURL(f))
}

async function submit() {
  err.value = ''
  ok.value = ''
  const t = body.value.trim()
  if (!t && files.value.length === 0) {
    err.value = '请输入内容或选择图片'
    return
  }
  submitting.value = true
  try {
    await sendSiteMailMessage(body.value, files.value)
    ok.value = '已发送'
    await store.refreshSummary()
    window.setTimeout(() => {
      close()
    }, 700)
  } catch (e) {
    err.value = getAxiosErrorMessage(e)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <Transition name="sm-fade">
      <div v-if="composeOpen" class="sm-shell" role="dialog" aria-modal="true" aria-labelledby="sm-compose-title">
        <div class="sm-backdrop" @click="close" />
        <div class="sm-panel" @click.stop>
          <header class="sm-head">
            <h2 id="sm-compose-title" class="sm-title">联系管理员</h2>
            <button type="button" class="sm-close" aria-label="关闭" @click="close">
              <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2.2">
                <path d="M18 6 6 18M6 6l12 12" stroke-linecap="round" />
              </svg>
            </button>
          </header>
          <p class="sm-hint">消息将发送给管理员，可在右上角站内信查看回复。</p>
          <textarea v-model="body" class="sm-textarea" rows="5" maxlength="8000" placeholder="描述你的问题或建议…" />
          <div class="sm-files">
            <label class="sm-file-btn">
              <input type="file" accept="image/*" multiple class="sm-file-input" @change="onPickFiles" />
              添加图片（最多 6 张）
            </label>
            <div v-if="previews.length" class="sm-prev-grid">
              <div v-for="(url, i) in previews" :key="url" class="sm-prev-cell">
                <img :src="url" alt="" class="sm-prev-img" />
                <button type="button" class="sm-prev-x" @click="removeAt(i)">×</button>
              </div>
            </div>
          </div>
          <p v-if="err" class="sm-err">{{ err }}</p>
          <p v-if="ok" class="sm-ok">{{ ok }}</p>
          <footer class="sm-foot">
            <button type="button" class="sm-btn sm-btn--ghost" :disabled="submitting" @click="close">取消</button>
            <button type="button" class="sm-btn sm-btn--primary" :disabled="submitting" @click="submit">
              {{ submitting ? '发送中…' : '发送' }}
            </button>
          </footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.sm-shell {
  position: fixed;
  inset: 0;
  z-index: 14500;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: max(20px, env(safe-area-inset-top, 0px)) max(20px, env(safe-area-inset-right, 0px))
    max(20px, env(safe-area-inset-bottom, 0px)) max(20px, env(safe-area-inset-left, 0px));
  box-sizing: border-box;
}

.sm-backdrop {
  position: absolute;
  inset: 0;
  background: rgba(8, 10, 18, 0.45);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
}

.sm-panel {
  position: relative;
  width: min(480px, 100%);
  max-height: min(86vh, 720px);
  display: flex;
  flex-direction: column;
  border-radius: 22px;
  background: linear-gradient(
    155deg,
    rgba(255, 255, 255, 0.12) 0%,
    rgba(255, 255, 255, 0.05) 50%,
    rgba(255, 255, 255, 0.03) 100%
  );
  background-color: rgba(22, 26, 36, 0.55);
  border: 1px solid rgba(255, 255, 255, 0.12);
  box-shadow:
    0 0 0 1px rgba(255, 255, 255, 0.04) inset,
    0 28px 80px rgba(0, 0, 0, 0.45);
  backdrop-filter: blur(26px) saturate(1.2);
  -webkit-backdrop-filter: blur(26px) saturate(1.2);
  padding: 18px 20px 20px;
  overflow: hidden;
}

.sm-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}

.sm-title {
  margin: 0;
  font-size: 1.1rem;
  font-weight: 800;
  color: rgba(248, 250, 252, 0.96);
}

.sm-close {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(255, 255, 255, 0.06);
  color: rgba(226, 232, 240, 0.9);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
}

.sm-hint {
  margin: 0 0 12px;
  font-size: 0.8125rem;
  line-height: 1.45;
  color: rgba(203, 213, 225, 0.85);
}

.sm-textarea {
  width: 100%;
  box-sizing: border-box;
  min-height: 120px;
  resize: vertical;
  border-radius: 14px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(0, 0, 0, 0.2);
  color: rgba(248, 250, 252, 0.95);
  font-size: 0.9375rem;
  line-height: 1.5;
  padding: 12px 14px;
  outline: none;
}

.sm-files {
  margin-top: 12px;
}

.sm-file-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 8px 14px;
  border-radius: 999px;
  border: 1px dashed rgba(148, 163, 184, 0.45);
  font-size: 0.8125rem;
  font-weight: 600;
  color: rgba(186, 230, 253, 0.95);
  cursor: pointer;
}

.sm-file-input {
  display: none;
}

.sm-prev-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.sm-prev-cell {
  position: relative;
  width: 72px;
  height: 72px;
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.sm-prev-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.sm-prev-x {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 22px;
  height: 22px;
  border: none;
  border-radius: 6px;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
}

.sm-err {
  margin: 10px 0 0;
  font-size: 0.8125rem;
  color: #fecaca;
}

.sm-ok {
  margin: 10px 0 0;
  font-size: 0.8125rem;
  color: #a7f3d0;
}

.sm-foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 16px;
}

.sm-btn {
  border-radius: 12px;
  padding: 10px 18px;
  font-weight: 700;
  font-size: 0.875rem;
  cursor: pointer;
  border: none;
}

.sm-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.sm-btn--ghost {
  background: rgba(255, 255, 255, 0.06);
  color: rgba(226, 232, 240, 0.9);
  border: 1px solid rgba(255, 255, 255, 0.1);
}

.sm-btn--primary {
  background: linear-gradient(135deg, #5ee1d5, #7ee8cb);
  color: #042f2e;
}

.sm-fade-enter-active,
.sm-fade-leave-active {
  transition: opacity 0.25s ease;
}
.sm-fade-enter-active .sm-panel,
.sm-fade-leave-active .sm-panel {
  transition:
    opacity 0.25s ease,
    transform 0.3s cubic-bezier(0.22, 1, 0.36, 1);
}
.sm-fade-enter-from,
.sm-fade-leave-to {
  opacity: 0;
}
.sm-fade-enter-from .sm-panel,
.sm-fade-leave-to .sm-panel {
  opacity: 0;
  transform: translateY(10px) scale(0.98);
}
</style>
