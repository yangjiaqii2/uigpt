/**
 * 自由对话：用户按「大类」选择系列，前端映射为 API易 上的具体 model id。
 */

/** @typedef {{ id: string, label: string, apiModelId: string, hint?: string }} FastFreeformFamily */

/** 默认大类（映射见 {@link FAST_FREEFORM_MODEL_FAMILIES}） */
export const DEFAULT_FAST_FREEFORM_FAMILY = 'gpt'

/**
 * 大类列表（界面仅展示这些项；具体路由 model 见 apiModelId）。
 */
export const FAST_FREEFORM_MODEL_FAMILIES = [
  {
    id: 'gpt',
    label: 'GPT',
    apiModelId: 'gpt-5',
    hint: 'OpenAI',
  },
  {
    id: 'claude',
    label: 'Claude',
    apiModelId: 'claude-haiku-4-5-20251001',
    hint: 'Anthropic',
  },
  {
    id: 'gemini',
    label: 'Gemini',
    apiModelId: 'gemini-3-flash-preview-nothinking',
    hint: 'Google',
  },
  {
    id: 'grok',
    label: 'Grok',
    apiModelId: 'grok-3',
    hint: 'xAI',
  },
  {
    id: 'deepseek',
    label: 'DeepSeek',
    apiModelId: 'deepseek-v3-1-250821',
    hint: 'DeepSeek',
  },
  {
    id: 'zhipu',
    label: '智谱 GLM',
    apiModelId: 'glm-4.6',
    hint: '智谱',
  },
]

/** @type {Set<string>} */
export const FAST_FREEFORM_FAMILY_IDS = new Set(FAST_FREEFORM_MODEL_FAMILIES.map((f) => f.id))

/**
 * 「深度推理」开启时路由到的具体 model（各系列偏最强 / 高推理档，须与后端 FastFreeformModelIds 白名单一致）。
 * @type {Record<string, string>}
 */
export const FAST_FREEFORM_DEEP_API_MODEL_BY_FAMILY = {
  gpt: 'gpt-5.4-pro',
  claude: 'claude-opus-4-6-thinking',
  gemini: 'gemini-3.1-pro-preview',
  grok: 'grok-4',
  deepseek: 'deepseek-v3.2',
  zhipu: 'glm-5',
}

/**
 * @param {string} familyId
 * @param {{ deepReasoning?: boolean }} [options]
 * @returns {string}
 */
export function apiModelIdForFamily(familyId, options = {}) {
  const fam = migrateStoredPreference(familyId)
  const deep = Boolean(options.deepReasoning)
  if (deep) {
    const deepId = FAST_FREEFORM_DEEP_API_MODEL_BY_FAMILY[fam]
    if (deepId) return deepId
  }
  const f = FAST_FREEFORM_MODEL_FAMILIES.find((x) => x.id === fam)
  return f?.apiModelId ?? FAST_FREEFORM_MODEL_FAMILIES[0].apiModelId
}

/**
 * @param {string} familyId
 * @returns {string}
 */
export function labelForFastFreeformFamily(familyId) {
  const f = FAST_FREEFORM_MODEL_FAMILIES.find((x) => x.id === familyId)
  return f?.label ?? FAST_FREEFORM_MODEL_FAMILIES[0].label
}

/**
 * localStorage 曾存具体 model id；迁移为大类 id。
 * @param {string | null | undefined} raw
 * @returns {string}
 */
export function migrateStoredPreference(raw) {
  const t = raw != null ? String(raw).trim() : ''
  if (t && FAST_FREEFORM_FAMILY_IDS.has(t)) return t
  if (!t) return DEFAULT_FAST_FREEFORM_FAMILY
  const lower = t.toLowerCase()
  if (lower.startsWith('gpt-') || lower === 'gpt-5' || lower.startsWith('o3') || lower.startsWith('o4'))
    return 'gpt'
  if (lower.startsWith('claude-')) return 'claude'
  if (lower.startsWith('gemini-')) return 'gemini'
  if (lower.startsWith('grok-')) return 'grok'
  if (lower.startsWith('deepseek-')) return 'deepseek'
  if (lower.startsWith('glm-')) return 'zhipu'
  return DEFAULT_FAST_FREEFORM_FAMILY
}

/** @deprecated 使用 {@link FAST_FREEFORM_FAMILY_IDS} */
export const FAST_FREEFORM_MODEL_IDS = FAST_FREEFORM_FAMILY_IDS

/** @deprecated 使用 {@link DEFAULT_FAST_FREEFORM_FAMILY} */
export const DEFAULT_FAST_FREEFORM_MODEL = DEFAULT_FAST_FREEFORM_FAMILY

/**
 * @param {string} id 大类 id 或历史具体 model id（用于展示）
 * @returns {string}
 */
export function labelForFastFreeformModel(id) {
  const fam = migrateStoredPreference(id)
  return labelForFastFreeformFamily(fam)
}
