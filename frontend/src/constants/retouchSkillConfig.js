/** 「AI 修图」技能：面板参数与发往模型的摘要 */

export const RETOUCH_SKILL_META = {
  id: 'retouch',
  name: 'AI 修图',
  iconEmoji: '✨',
  accentColor: '#8B5CF6',
  outputFormat: 'image_edit_instruction',
  inputMode: 'image_required',
}

export const RETOUCH_EDIT_MODES = [
  '🎨 风格迁移',
  '🔧 局部重绘',
  '✂️ 智能扩图',
  '🔄 画质增强',
  '🖼️ 背景替换',
]

/** 下拉菜单左侧图标（与 {@link RETOUCH_EDIT_MODES} 逐项对应，与存储值中的 emoji 无关） */
export const RETOUCH_MODE_MENU_ICONS = ['🎨', '✂️', '🔍', '✨', '🖼️']

/** 去掉模式串首部 emoji，仅保留中文名称（如「风格迁移」） */
export function retouchModePlainLabel(full) {
  const s = String(full ?? '').trim()
  const i = s.indexOf(' ')
  return i === -1 ? s : s.slice(i + 1).trim()
}

/** @param {string} modeValue {@link RETOUCH_EDIT_MODES} 中的完整值 */
export function retouchModeMenuIcon(modeValue) {
  const idx = RETOUCH_EDIT_MODES.indexOf(modeValue)
  if (idx < 0) return '✨'
  return RETOUCH_MODE_MENU_ICONS[idx] ?? '✨'
}

export const RETOUCH_PLACEHOLDER =
  '上传图片并说明修改点，例：背景换海边日落、衣服改红色…'

/** @returns {Record<string, unknown>} */
export function createDefaultRetouchParams() {
  return {
    edit_mode: '🎨 风格迁移',
    strength: 50,
  }
}

/**
 * 合并本地缓存与默认值（持久化 / URL 恢复用）。
 * @param {Record<string, unknown> | null | undefined} raw
 */
export function normalizeRetouchParams(raw) {
  const d = createDefaultRetouchParams()
  const o = raw && typeof raw === 'object' ? raw : {}
  let strength = Number(o.strength)
  if (!Number.isFinite(strength)) strength = d.strength
  strength = Math.min(100, Math.max(0, strength))
  const modeOk = RETOUCH_EDIT_MODES.includes(o.edit_mode)
  const edit_mode = modeOk ? o.edit_mode : d.edit_mode
  return { ...d, edit_mode, strength }
}

/**
 * 供第二轮「英文提示词」参考的简短风格标签（修图专用）
 * @param {Record<string, unknown>} p
 */
export function buildRetouchErnieStyleHint(p) {
  const mode = p.edit_mode ?? '🎨 风格迁移'
  const strength = Number(p.strength)
  const parts = [`修图`, mode, `强度${Number.isFinite(strength) ? strength : 50}%`]
  return parts.join('｜')
}

/**
 * @param {Record<string, unknown>} p
 */
export function buildRetouchSkillContext(p) {
  const mode = p.edit_mode ?? '🎨 风格迁移'
  const strength = Number(p.strength)
  const lines = []
  lines.push(`修图模式：${mode}`)
  lines.push(`修改强度：${Number.isFinite(strength) ? strength : 50}（0 保留原图 → 100 大幅重构）`)
  lines.push(
    '用户已按规范上传参考图（见消息中的参考图说明）。请先输出图片分析、修图方案与优化后的生图提示词；输出格式 image_edit_instruction。',
  )
  return lines.join('\n')
}
