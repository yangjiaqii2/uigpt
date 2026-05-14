<script setup>
import { computed, ref, watch } from 'vue'
import { RAG_COLLECTION_OPTIONS, validateSkillId } from '../../stores/skillStore'

const props = defineProps({
  open: { type: Boolean, default: false },
  /** @type {'create'|'edit'} */
  mode: { type: String, default: 'create' },
  /** @type {{ id: string, name: string, tag?: string, ragCollection?: string, description?: string, hint?: string } | null} */
  initial: { type: Object, default: null },
})

const emit = defineEmits(['close', 'save'])

const name = ref('')
const skillId = ref('')
const tag = ref('')
const ragCollection = ref('')
const description = ref('')
const hint = ref('')

const titleText = computed(() => (props.mode === 'create' ? '新建技能' : '编辑技能'))

const idError = computed(() => validateSkillId(skillId.value))

const canSave = computed(() => {
  if (!name.value.trim()) return false
  if (idError.value) return false
  return Boolean(skillId.value.trim())
})

watch(
  () => [props.open, props.mode, props.initial],
  () => {
    if (!props.open) return
    if (props.mode === 'edit' && props.initial) {
      name.value = props.initial.name || ''
      skillId.value = props.initial.id || ''
      tag.value = props.initial.tag || ''
      ragCollection.value =
        props.initial.ragCollection != null ? String(props.initial.ragCollection) : ''
      description.value = props.initial.description || ''
      hint.value = props.initial.hint || ''
    } else {
      name.value = ''
      skillId.value = ''
      tag.value = ''
      ragCollection.value = ''
      description.value = ''
      hint.value = ''
    }
  },
  { immediate: true },
)

function close() {
  emit('close')
}

function save() {
  if (!canSave.value) return
  emit('save', {
    id: skillId.value.trim(),
    name: name.value.trim(),
    tag: tag.value.trim(),
    ragCollection: ragCollection.value.trim(),
    description: description.value.trim(),
    hint: hint.value.trim(),
  })
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="spl-modal-backdrop"
      aria-modal="true"
      role="dialog"
      @click.self="close"
    >
      <div class="spl-modal" @keydown.escape.stop="close">
        <div class="spl-modal-head">
          <h2 class="spl-modal-title">{{ titleText }}</h2>
          <button type="button" class="spl-icon-btn" aria-label="关闭" @click="close">×</button>
        </div>
        <div class="spl-modal-body">
          <label class="spl-field">
            <span class="spl-label">技能名称 <span class="spl-req">*</span></span>
            <input v-model="name" class="spl-input" type="text" maxlength="64" placeholder="如 全能大师" />
          </label>
          <label class="spl-field">
            <span class="spl-label">技能 ID <span class="spl-req">*</span></span>
            <input
              v-model="skillId"
              class="spl-input"
              type="text"
              maxlength="64"
              placeholder="如 universal_master"
            />
            <span v-if="idError" class="spl-err">{{ idError }}</span>
          </label>
          <label class="spl-field">
            <span class="spl-label">展示标签</span>
            <input v-model="tag" class="spl-input" type="text" maxlength="16" placeholder="卡片角标，如 全能大师" />
          </label>
          <label class="spl-field">
            <span class="spl-label">RAG 知识库绑定</span>
            <select v-model="ragCollection" class="spl-select">
              <option v-for="opt in RAG_COLLECTION_OPTIONS" :key="opt.value === '' ? '_empty' : opt.value" :value="opt.value">
                {{ opt.label }}
              </option>
            </select>
          </label>
          <label class="spl-field">
            <span class="spl-label">描述</span>
            <textarea v-model="description" class="spl-textarea" rows="3" maxlength="512" placeholder="能力说明（可选）" />
          </label>
          <label class="spl-field">
            <span class="spl-label">工作台提示 hint</span>
            <textarea v-model="hint" class="spl-textarea" rows="2" maxlength="300" placeholder="作图台下拉副文案（可选）" />
          </label>
        </div>
        <div class="spl-modal-foot">
          <button type="button" class="spl-btn spl-btn--ghost" @click="close">取消</button>
          <button type="button" class="spl-btn spl-btn--primary" :disabled="!canSave" @click="save">保存</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.spl-modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 16000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: max(20px, env(safe-area-inset-top, 0px)) max(20px, env(safe-area-inset-right, 0px))
    max(20px, env(safe-area-inset-bottom, 0px)) max(20px, env(safe-area-inset-left, 0px));
  background: color-mix(in srgb, var(--chat-backdrop, rgba(0, 0, 0, 0.45)) 88%, #000);
  -webkit-backdrop-filter: blur(12px);
  backdrop-filter: blur(12px);
}

.spl-modal {
  width: min(100%, 440px);
  max-height: min(90vh, 720px);
  overflow: auto;
  border-radius: 18px;
  border: 1px solid color-mix(in srgb, var(--chat-border-strong) 92%, transparent);
  background: color-mix(in srgb, var(--chat-panel) 78%, rgba(255, 255, 255, 0.06));
  -webkit-backdrop-filter: blur(22px);
  backdrop-filter: blur(22px);
  box-shadow:
    var(--chat-panel-shadow, 0 16px 48px rgba(0, 0, 0, 0.45)),
    inset 0 1px 0 color-mix(in srgb, var(--chat-fg-strong, #fff) 8%, transparent);
}

.spl-modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 18px 12px;
  border-bottom: 1px solid var(--chat-border, #2a2f3a);
}

.spl-modal-title {
  margin: 0;
  font-size: 1.05rem;
  font-weight: 700;
  color: var(--chat-fg-strong, #e8ecf5);
}

.spl-icon-btn {
  width: 36px;
  height: 36px;
  border: none;
  border-radius: 10px;
  background: transparent;
  color: var(--chat-muted, #9aa3b2);
  font-size: 1.35rem;
  line-height: 1;
  cursor: pointer;
}

.spl-icon-btn:hover {
  background: var(--chat-btn-bg-hover, color-mix(in srgb, var(--chat-fg, #fff) 8%, transparent));
  color: var(--chat-fg-strong, #e8ecf5);
}

.spl-modal-body {
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.spl-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.spl-label {
  font-size: 0.78rem;
  font-weight: 600;
  color: var(--chat-muted, #9aa3b2);
}

.spl-req {
  color: var(--chat-danger-fg, #f87171);
}

.spl-input,
.spl-select,
.spl-textarea {
  width: 100%;
  box-sizing: border-box;
  border-radius: 10px;
  border: 1px solid var(--chat-border-strong, #ffffff1a);
  background: color-mix(in srgb, var(--chat-panel, #1a1d26) 88%, #000);
  color: var(--chat-fg-strong, #e8ecf5);
  font-size: 0.875rem;
  padding: 10px 12px;
}

.spl-textarea {
  resize: vertical;
  min-height: 72px;
  line-height: 1.5;
}

.spl-err {
  font-size: 0.75rem;
  color: var(--chat-danger-fg, #f87171);
}

.spl-modal-foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 14px 18px 16px;
  border-top: 1px solid var(--chat-border, #2a2f3a);
}

.spl-btn {
  padding: 8px 18px;
  border-radius: 10px;
  font-size: 0.875rem;
  font-weight: 600;
  cursor: pointer;
  border: 1px solid var(--chat-border-strong, #ffffff1a);
  background: color-mix(in srgb, var(--chat-fg, #fff) 6%, transparent);
  color: var(--chat-fg-strong, #e8ecf5);
}

.spl-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.spl-btn--ghost:hover:not(:disabled) {
  background: var(--chat-btn-bg-hover);
}

.spl-btn--primary {
  border-color: color-mix(in srgb, var(--accent, #5ee1d5) 45%, transparent);
  background: color-mix(in srgb, var(--accent, #5ee1d5) 20%, transparent);
}

.spl-btn--primary:hover:not(:disabled) {
  filter: brightness(1.06);
}
</style>
