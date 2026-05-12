import { FREEFORM_PLACEHOLDER, FREEFORM_SKILL_META } from './freeformSkillConfig'

/** 历史会话可能仍含以下技能的文生图记录；新对话仅使用自由对话。 */
export const GEN_IMAGE_PIPELINE_SKILL_IDS = new Set(['mockup', 'wireframe', 'retouch', 'palette'])

export const GEN_IMAGE_PARAM_SKILL_IDS = GEN_IMAGE_PIPELINE_SKILL_IDS

/**
 * 当前产品仅保留「自由对话」。
 */
export const CHAT_SKILLS = [
  {
    id: 'freeform',
    label: FREEFORM_SKILL_META.name,
    placeholder: FREEFORM_PLACEHOLDER,
    directive: '',
    icon: 'freeform',
    iconEmoji: FREEFORM_SKILL_META.iconEmoji,
    accentColor: FREEFORM_SKILL_META.accentColor,
  },
]

/** @param {string} id */
export function findSkillById(id) {
  return CHAT_SKILLS.find((s) => s.id === id) ?? CHAT_SKILLS[0]
}
