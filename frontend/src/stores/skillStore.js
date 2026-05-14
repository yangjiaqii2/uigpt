import { defineStore } from 'pinia'
import { computed, ref, watch } from 'vue'

const STORAGE_KEY = 'uigpt_skill_plaza_skills_v1'

/** 与技能编辑弹窗下拉一致：不选则通用扩写；家装独立库 home_design */
export const RAG_COLLECTION_OPTIONS = [
  { value: '', label: '不绑定（通用扩写）' },
  { value: 'home_design', label: 'home_design（家装独立库）' },
]

function seedSkills() {
  return [
    {
      id: 'interior_designer',
      name: '家装设计师',
      tag: '家装',
      ragCollection: 'home_design',
      description: '家装三阶段：意图 JSON → 领域 RAG → 英文 SD 组词（室内场景优化）',
      hint: '【家装】家装三阶段：意图 JSON → 领域 RAG → 英文 SD 组词',
      updatedAt: Date.now(),
    },
    {
      id: 'universal_master',
      name: '全能大师',
      tag: '通用',
      ragCollection: '',
      description:
        '不限领域，支持任意主题的高质量创意图像生成；服务端不绑定领域 RAG、不注入向量知识块',
      hint: '【通用】不限领域，支持任意主题的高质量创意图像生成；服务端不绑定领域 RAG、不注入向量知识块',
      updatedAt: Date.now(),
    },
  ]
}

function loadFromStorage() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return seedSkills()
    const arr = JSON.parse(raw)
    if (!Array.isArray(arr) || arr.length === 0) return seedSkills()
    return arr.map(normalizeSkillRow)
  } catch {
    return seedSkills()
  }
}

/** @param {unknown} row */
function normalizeSkillRow(row) {
  const o = row && typeof row === 'object' ? row : {}
  const id = String(o.id || '').trim()
  const name = String(o.name || '').trim() || id || '未命名技能'
  const tag = String(o.tag || '').trim()
  const rag = o.ragCollection != null ? String(o.ragCollection).trim() : ''
  return {
    id,
    name,
    tag,
    ragCollection: rag,
    description: String(o.description || '').trim(),
    hint: String(o.hint || '').trim(),
    updatedAt: typeof o.updatedAt === 'number' ? o.updatedAt : Date.now(),
  }
}

const SKILL_ID_PATTERN = /^[a-z][a-z0-9_]{0,62}$/

export function validateSkillId(id) {
  const s = String(id || '').trim()
  if (!s) return '技能 ID 不能为空'
  if (!SKILL_ID_PATTERN.test(s)) return '技能 ID 须为小写英文开头，仅含小写字母、数字、下划线'
  return ''
}

export const useSkillStore = defineStore('skill', () => {
  const skills = ref(loadFromStorage())

  watch(
    skills,
    (v) => {
      try {
        localStorage.setItem(STORAGE_KEY, JSON.stringify(v))
      } catch {
        /* ignore */
      }
    },
    { deep: true },
  )

  const studioSkillOptions = computed(() =>
    skills.value.map((s) => ({
      id: s.id,
      label: s.name,
      hint: s.hint || s.description || '',
      tag: s.tag,
    })),
  )

  const defaultSkillId = computed(() => {
    const u = skills.value.find((x) => x.id === 'universal_master')
    if (u) return u.id
    return skills.value[0]?.id ?? 'universal_master'
  })

  function isKnownStudioSkillId(id) {
    return skills.value.some((s) => s.id === String(id || ''))
  }

  function normalizeStudioSkillId(raw) {
    const id = String(raw ?? '').trim()
    return isKnownStudioSkillId(id) ? id : defaultSkillId.value
  }

  /** @param {string} id */
  function skillById(id) {
    return skills.value.find((s) => s.id === id) ?? null
  }

  /** @param {{ id: string, name: string, tag?: string, ragCollection?: string, description?: string, hint?: string }} payload */
  function addSkill(payload) {
    const err = validateSkillId(payload.id)
    if (err) throw new Error(err)
    if (skills.value.some((s) => s.id === payload.id)) throw new Error('技能 ID 已存在')
    const name = String(payload.name || '').trim()
    if (!name) throw new Error('技能名称不能为空')
    skills.value.push(
      normalizeSkillRow({
        ...payload,
        name,
        updatedAt: Date.now(),
      }),
    )
  }

  /** @param {string} id @param {Record<string, unknown>} patch */
  function updateSkill(id, patch) {
    const i = skills.value.findIndex((s) => s.id === id)
    if (i < 0) throw new Error('技能不存在')
    const cur = skills.value[i]
    const nextId = patch.id != null ? String(patch.id).trim() : cur.id
    if (nextId !== cur.id) {
      const err = validateSkillId(nextId)
      if (err) throw new Error(err)
      if (skills.value.some((s, j) => j !== i && s.id === nextId)) throw new Error('技能 ID 已存在')
    }
    const merged = normalizeSkillRow({
      ...cur,
      ...patch,
      id: nextId,
      name: patch.name != null ? String(patch.name).trim() : cur.name,
    })
    if (!merged.name) throw new Error('技能名称不能为空')
    skills.value.splice(i, 1, { ...merged, updatedAt: Date.now() })
  }

  /** @param {string} id */
  function removeSkill(id) {
    if (skills.value.length <= 1) throw new Error('至少保留一个技能')
    const i = skills.value.findIndex((s) => s.id === id)
    if (i < 0) throw new Error('技能不存在')
    skills.value.splice(i, 1)
  }

  return {
    skills,
    studioSkillOptions,
    defaultSkillId,
    RAG_COLLECTION_OPTIONS,
    isKnownStudioSkillId,
    normalizeStudioSkillId,
    skillById,
    addSkill,
    updateSkill,
    removeSkill,
  }
})

/*
 * ========== REST API 契约（供后续后端接入，路径前缀示例 /api/v1） ==========
 *
 * GET    /skill-plaza/skills
 *        200: { "items": [ { "id","name","tag","ragCollection","description","hint","updatedAt" } ] }
 *
 * POST   /skill-plaza/skills
 *        body: { "id","name","tag?","ragCollection?","description?","hint?" }
 *        201: { item }  400: 校验失败  409: id 冲突
 *
 * PATCH  /skill-plaza/skills/{id}
 *        body: 部分字段同上（可改 id，需全局唯一）
 *        200: { item }  404
 *
 * DELETE /skill-plaza/skills/{id}
 *        204  400: 最后一个技能不可删  404
 *
 * 说明：
 * - ragCollection 空字符串表示不绑定 RAG（通用扩写）；如 home_design 与后端向量库名对齐。
 * - 图片生成接口路径不变；请求体仍可仅传 studioSkillId，服务端用 id 映射 RAG 策略（与现 ImageStudioSkillIds 一致）。
 */
