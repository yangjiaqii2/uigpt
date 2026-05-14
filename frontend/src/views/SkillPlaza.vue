<script setup>
import { computed, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useSkillStore } from '../stores/skillStore'
import SkillCard from '../components/skill-plaza/SkillCard.vue'
import SkillEditorModal from '../components/skill-plaza/SkillEditorModal.vue'

const skillStore = useSkillStore()
const { skills } = storeToRefs(skillStore)

const filterText = ref('')
const tagFilter = ref('')
const toast = ref('')
const editorOpen = ref(false)
const editorMode = ref('create')
/** @type {import('vue').Ref<Record<string, unknown> | null>} */
const editorInitial = ref(null)

const tagOptions = computed(() => {
  const set = new Set()
  for (const s of skills.value) {
    const t = (s.tag || '').trim()
    if (t) set.add(t)
  }
  return [...set].sort((a, b) => a.localeCompare(b, 'zh-CN'))
})

const filteredSkills = computed(() => {
  const q = filterText.value.trim().toLowerCase()
  const tag = tagFilter.value.trim()
  return skills.value.filter((s) => {
    if (tag && (s.tag || '').trim() !== tag) return false
    if (!q) return true
    const blob = `${s.id} ${s.name} ${s.tag || ''} ${s.description || ''}`.toLowerCase()
    return blob.includes(q)
  })
})

function showToast(msg) {
  toast.value = msg
  window.setTimeout(() => {
    toast.value = ''
  }, 3200)
}

function openCreate() {
  editorMode.value = 'create'
  editorInitial.value = null
  editorOpen.value = true
}

/** @param {(typeof skills.value)[number]} row */
function openEdit(row) {
  editorMode.value = 'edit'
  editorInitial.value = { ...row }
  editorOpen.value = true
}

function closeEditor() {
  editorOpen.value = false
}

/** @param {{ id: string, name: string, tag?: string, ragCollection?: string, description?: string, hint?: string }} payload */
function onEditorSave(payload) {
  try {
    if (editorMode.value === 'create') {
      skillStore.addSkill(payload)
      showToast('已新建技能')
    } else {
      const orig = editorInitial.value && typeof editorInitial.value.id === 'string' ? editorInitial.value.id : null
      if (!orig) return
      skillStore.updateSkill(orig, payload)
      showToast('已保存技能')
    }
    editorOpen.value = false
  } catch (e) {
    showToast(e instanceof Error ? e.message : '保存失败')
  }
}

/** @param {(typeof skills.value)[number]} row */
function onDelete(row) {
  if (!window.confirm(`确定删除技能「${row.name}」？图片工作台若正在使用该 ID，将自动回退为默认技能。`)) return
  try {
    skillStore.removeSkill(row.id)
    showToast('已删除')
  } catch (e) {
    showToast(e instanceof Error ? e.message : '删除失败')
  }
}
</script>

<template>
  <div class="spl-page">
    <header class="spl-head">
      <div class="spl-head-row">
        <div>
          <h1 class="spl-title">技能广场</h1>
          <p class="spl-sub">管理作图技能卡片；与图片工作台技能筛选实时联动（Pinia + localStorage）。</p>
        </div>
        <button type="button" class="spl-btn spl-btn--primary" @click="openCreate">+ 新建技能</button>
      </div>
    </header>

    <p v-if="toast" class="spl-toast" role="status">{{ toast }}</p>

    <div class="spl-toolbar">
      <label class="spl-search">
        <span class="spl-sr-only">筛选</span>
        <input v-model="filterText" type="search" class="spl-search-input" placeholder="按名称、ID、标签、描述筛选…" />
      </label>
      <div v-if="tagOptions.length" class="spl-tags" role="tablist" aria-label="标签筛选">
        <button
          type="button"
          class="spl-tag-chip"
          :class="{ 'spl-tag-chip--on': tagFilter === '' }"
          role="tab"
          :aria-selected="tagFilter === ''"
          @click="tagFilter = ''"
        >
          全部
        </button>
        <button
          v-for="t in tagOptions"
          :key="t"
          type="button"
          class="spl-tag-chip"
          :class="{ 'spl-tag-chip--on': tagFilter === t }"
          role="tab"
          :aria-selected="tagFilter === t"
          @click="tagFilter = t"
        >
          {{ t }}
        </button>
      </div>
    </div>

    <div v-if="filteredSkills.length === 0" class="spl-empty">
      <template v-if="skills.length === 0">暂无技能数据。</template>
      <template v-else>没有符合筛选条件的技能，请调整关键词或标签。</template>
    </div>

    <div v-else class="spl-grid" role="list">
      <div v-for="s in filteredSkills" :key="s.id" class="spl-grid-cell" role="listitem">
        <SkillCard :skill="s" @edit="openEdit(s)" @delete="onDelete(s)" />
      </div>
    </div>

    <SkillEditorModal
      :open="editorOpen"
      :mode="editorMode"
      :initial="editorInitial"
      @close="closeEditor"
      @save="onEditorSave"
    />
  </div>
</template>

<style scoped>
.spl-page {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 28px 24px 40px;
  overflow: auto;
  box-sizing: border-box;
}

.spl-head {
  margin-bottom: 18px;
}

.spl-head-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.spl-title {
  margin: 0 0 6px;
  font-size: 1.35rem;
  font-weight: 700;
  color: var(--chat-fg-strong, #e8ecf5);
}

.spl-sub {
  margin: 0;
  max-width: 560px;
  font-size: 0.8125rem;
  line-height: 1.55;
  color: var(--chat-muted, #9aa3b2);
}

.spl-toast {
  margin: 0 0 12px;
  font-size: 0.875rem;
  color: var(--accent, #5ee1d5);
}

.spl-toolbar {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 20px;
}

.spl-search-input {
  width: min(100%, 420px);
  box-sizing: border-box;
  padding: 10px 14px;
  border-radius: 12px;
  border: 1px solid var(--chat-border-strong, #ffffff1a);
  background: var(--chat-panel, #242424);
  color: var(--chat-fg-strong, #e8ecf5);
  font-size: 0.875rem;
}

.spl-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.spl-tag-chip {
  padding: 6px 12px;
  border-radius: 999px;
  border: 1px solid var(--chat-border-strong, #ffffff1a);
  background: transparent;
  color: var(--chat-muted, #9aa3b2);
  font-size: 0.78rem;
  font-weight: 600;
  cursor: pointer;
}

.spl-tag-chip--on {
  border-color: color-mix(in srgb, var(--accent, #5ee1d5) 45%, transparent);
  color: var(--chat-fg-strong, #e8ecf5);
  background: color-mix(in srgb, var(--accent, #5ee1d5) 14%, transparent);
}

.spl-empty {
  margin-top: 48px;
  padding: 28px 20px;
  text-align: center;
  border-radius: 16px;
  border: 1px dashed var(--chat-border-strong, #ffffff1a);
  color: var(--chat-muted-2, #8b95a8);
  font-size: 0.9rem;
}

.spl-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
  gap: 20px;
  align-content: start;
}

.spl-grid-cell {
  display: flex;
  justify-content: center;
}

.spl-btn {
  padding: 8px 16px;
  border-radius: 999px;
  border: 1px solid var(--chat-border-strong, #ffffff1a);
  background: var(--chat-bubble-bg, #ffffff0a);
  color: var(--chat-fg-strong, #e8ecf5);
  font-size: 0.875rem;
  font-weight: 600;
  cursor: pointer;
}

.spl-btn--primary {
  border-color: color-mix(in srgb, var(--accent, #5ee1d5) 45%, transparent);
  background: color-mix(in srgb, var(--accent, #5ee1d5) 18%, transparent);
}

.spl-btn--primary:hover {
  filter: brightness(1.05);
}

.spl-sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

@media (max-width: 520px) {
  .spl-grid {
    grid-template-columns: 1fr;
  }
}
</style>
