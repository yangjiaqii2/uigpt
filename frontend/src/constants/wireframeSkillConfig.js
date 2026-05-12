/** 「原型图设计」技能：面板参数与发往模型的摘要 */

export const WIREFRAME_SKILL_META = {
  id: 'wireframe',
  name: '原型图设计',
  iconEmoji: '📐',
  accentColor: '#3B82F6',
  outputFormat: 'structured_prototype',
  inputMode: 'text_with_image_upload',
}

export const WIREFRAME_DEVICE_TYPES = ['📱 手机端', '💻 网页端', '📟 平板端', '⌚ 手表端']

export const WIREFRAME_FIDELITY_LABELS = ['低保真线框', '中保真', '高保真原型']

export const WIREFRAME_PAGE_TYPES = [
  '首页',
  '列表页',
  '详情页',
  '表单页',
  '个人中心',
  '支付页',
  '搜索页',
  '自定义',
]

export const WIREFRAME_PLACEHOLDER =
  '描述页面与关键模块，例：电商详情页—轮播、价格、规格、购买按钮…'

/** 与后端 ConversationImageService / ChatView GEN_ASPECT_VALUES 对齐 */
export const WIREFRAME_ASPECT_OPTIONS = [
  { value: '1:1', label: '1:1' },
  { value: '9:16', label: '9:16' },
  { value: '16:9', label: '16:9' },
  { value: '4:3', label: '4:3' },
  { value: '3:4', label: '3:4' },
  { value: '21:9', label: '21:9' },
]

export const WIREFRAME_STYLE_OPTIONS = [
  { value: '写实', label: '写实' },
  { value: '插画', label: '插画' },
  { value: '3D 渲染', label: '3D 渲染' },
  { value: '极简扁平', label: '极简扁平' },
]

/** @param {unknown} n */
export function wireframeFidelityLabel(n) {
  const raw = Number(n)
  const idx = Number.isFinite(raw) ? Math.min(3, Math.max(1, Math.round(raw))) - 1 : 0
  return WIREFRAME_FIDELITY_LABELS[idx] ?? WIREFRAME_FIDELITY_LABELS[0]
}

/** @returns {Record<string, unknown>} */
export function createDefaultWireframeParams() {
  return {
    device_type: '📱 手机端',
    fidelity: 1,
    page_type: '首页',
    aspect_ratio: '9:16',
    render_style: '极简扁平',
  }
}

/**
 * @param {Record<string, unknown>} p
 */
export function buildWireframeSkillContext(p) {
  const lines = []
  lines.push(`设备类型：${p.device_type ?? '📱 手机端'}`)
  lines.push(`保真度：${wireframeFidelityLabel(p.fidelity)}（${Number(p.fidelity) || 1}/3）`)
  lines.push(`页面类型：${p.page_type ?? '首页'}`)
  lines.push(`示意出图比例：${p.aspect_ratio ?? '9:16'}；示意渲染风格：${p.render_style ?? '极简扁平'}`)
  lines.push(
    '请按 UX/UI 原型设计师角色输出 structured_prototype：页面结构分析（信息架构、模块划分）、详细线框描述（各模块位置与大致比例）、交互逻辑（点击、滑动、状态变化）；风格为简洁线框，黑白灰为主，关键交互点可用蓝色文字标注；可用 ASCII 或文字描述布局。若用户上传了参考图，请分析其结构并融入方案。',
  )
  return lines.join('\n')
}

/**
 * 供第二轮「英文提示词」参考的简短标签（原型示意）
 * @param {Record<string, unknown>} p
 */
export function buildWireframeErnieStyleHint(p) {
  const dev = p.device_type ?? '📱 手机端'
  const page = p.page_type ?? '首页'
  const fid = wireframeFidelityLabel(p.fidelity)
  const style = p.render_style ?? '极简扁平'
  return ['原型线框示意', dev, page, fid, style].join('｜')
}
