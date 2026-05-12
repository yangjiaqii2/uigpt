/**
 * 全技能共用的 5 项生成参数：默认值、校验、持久化键与按技能映射到原有 build*SkillContext 所需结构。
 */

import {
  buildMockupErnieStyleHint,
  buildMockupSkillContext,
  createDefaultMockupParams,
  normalizeMockupParams,
} from './mockupSkillConfig'
import {
  buildPaletteErnieStyleHint,
  buildPaletteSkillContext,
  createDefaultPaletteParams,
} from './paletteSkillConfig'
import { buildRetouchErnieStyleHint, buildRetouchSkillContext, normalizeRetouchParams } from './retouchSkillConfig'
import {
  buildWireframeErnieStyleHint,
  buildWireframeSkillContext,
  createDefaultWireframeParams,
} from './wireframeSkillConfig'

export const STORAGE_UNIVERSAL_GEN = 'uigpt_universal_gen_params_v1'
export const STORAGE_RECENT_COLORS = 'uigpt_recent_colors_v1'

export const UNIVERSAL_ASPECT_OPTIONS = ['1:1', '9:16', '16:9', '3:4', '4:3', '21:9']

export const UNIVERSAL_STYLE_OPTIONS = ['写实', '插画', '3D 渲染', '极简扁平', '手绘素描', '电影感']

/** @typedef {'standard' | 'hd' | 'ultra'} UniversalQuality */
export const UNIVERSAL_QUALITY_KEYS = /** @type {const} */ (['standard', 'hd', 'ultra'])

export const UNIVERSAL_QUALITY_LABELS = {
  standard: '标准',
  hd: '高清',
  ultra: '超清',
}

/** 通用「出图风格」→ 效果图 design_style 枚举（界面不展示，仅代码映射） */
const STYLE_TO_MOCKUP_DESIGN = {
  写实: 'modern_simple',
  插画: 'cream',
  '3D 渲染': 'light_luxury',
  极简扁平: 'minimalist',
  手绘素描: 'wabi_sabi',
  电影感: 'industrial',
}

/** 通用风格 → 配色技能情绪氛围（隐藏） */
const STYLE_TO_PALETTE_MOOD = {
  写实: 'professional_trust',
  插画: 'playful_cute',
  '3D 渲染': 'luxury_elegant',
  极简扁平: 'minimal_calm',
  手绘素描: 'vintage_retro',
  电影感: 'tech_future',
}

export function createDefaultUniversalParams() {
  return {
    aspect_ratio: '16:9',
    render_style: '写实',
    color_hex: '',
    fidelity: 5,
    quality: /** @type {UniversalQuality} */ ('hd'),
  }
}

/**
 * @param {unknown} raw
 * @returns {ReturnType<typeof createDefaultUniversalParams>}
 */
export function normalizeUniversalParams(raw) {
  const d = createDefaultUniversalParams()
  if (!raw || typeof raw !== 'object') return { ...d }
  const o = { ...d, ...raw }
  if (!UNIVERSAL_ASPECT_OPTIONS.includes(o.aspect_ratio)) o.aspect_ratio = d.aspect_ratio
  if (!UNIVERSAL_STYLE_OPTIONS.includes(o.render_style)) o.render_style = d.render_style
  const hex = String(o.color_hex ?? '').trim()
  if (/^#[0-9A-Fa-f]{6}$/.test(hex)) o.color_hex = hex.toUpperCase()
  else o.color_hex = ''
  let f = Number(o.fidelity)
  if (!Number.isFinite(f)) f = d.fidelity
  o.fidelity = Math.min(10, Math.max(1, Math.round(f)))
  const q = String(o.quality ?? '').toLowerCase()
  o.quality = UNIVERSAL_QUALITY_KEYS.includes(q) ? q : d.quality
  return o
}

/**
 * 滑块旁文案：数值 + 描述
 * @param {unknown} n
 */
export function fidelityUiLabel(n) {
  const f = Math.min(10, Math.max(1, Math.round(Number(n)) || 5))
  let desc
  if (f === 1) desc = '创意发散'
  else if (f <= 4) desc = '自由发挥'
  else if (f === 5) desc = '平衡'
  else if (f <= 8) desc = '高度还原'
  else desc = '严格还原'
  return { f, text: `${f} - ${desc}` }
}

function resolutionExpectLineImage(/** @type {UniversalQuality} */ q) {
  const m = {
    standard: '期望输出构图细节约 1024px 量级',
    hd: '期望约 2048px 量级细节',
    ultra: '期望约 4096px 量级材质与细节',
  }
  return `【渲染分辨率期望】${m[q] || m.hd}`
}

function palettePrecisionLine(/** @type {UniversalQuality} */ q) {
  const m = {
    standard: '基础 5 色方案',
    hd: '8 色并附材质与应用场景说明',
    ultra: '12 色方案，须含完整 HEX、材质联想与界面/空间应用示例',
  }
  return `【色板精度】${m[q] || m.hd}`
}

function wireframeQualityLine(/** @type {UniversalQuality} */ q) {
  const m = {
    standard: '低保真线框示意为主',
    hd: '中保真界面示意',
    ultra: '高保真原型级界面示意',
  }
  return `【原型画质档】${m[q] || m.hd}`
}

/**
 * 按技能追加的通用参数说明（用户不可见的语义映射文案）。
 * @param {string} skillId
 * @param {ReturnType<typeof normalizeUniversalParams>} u
 */
export function extraUniversalContextLines(skillId, u) {
  const x = normalizeUniversalParams(u)
  const { f, text } = fidelityUiLabel(x.fidelity)
  const lines = []
  if (skillId === 'mockup') {
    lines.push(
      `【户型/手稿还原度】${text}：数值越高越忠实于用户上传的户型图、平面图或手稿结构与分区。`,
    )
    lines.push(resolutionExpectLineImage(x.quality))
  } else if (skillId === 'retouch') {
    // 模式与修改强度由专用面板提供，此处仅附加画质与色彩等通用项
    lines.push(resolutionExpectLineImage(x.quality))
  } else if (skillId === 'wireframe') {
    lines.push(
      `【需求忠实度】${text}：数值越高越应严格依照用户的文字需求细节排版与命名；数值越低可适度推断与补全。`,
    )
    lines.push(wireframeQualityLine(x.quality))
  } else if (skillId === 'palette') {
    lines.push(
      `【基准色依赖】${text}：数值越高配色越应围绕用户指定基准色做小步调延展；数值越低可做更大胆的色相与对比扩展。`,
    )
    lines.push(palettePrecisionLine(x.quality))
  } else if (skillId === 'freeform') {
    lines.push(
      `【上下文深度】${text}：数值越高越应紧扣会话前文细节与约束；数值越低可更概括地回答或切换焦点。`,
    )
  }
  if (x.color_hex) {
    lines.push(
      `【色彩倾向】用户指定基准色 ${x.color_hex}，须在输出中体现该色相倾向（与参考图/正文不冲突时优先遵循）。`,
    )
  }
  return lines.join('\n')
}

/**
 * @param {ReturnType<typeof normalizeUniversalParams>} u
 */
export function mockupParamsFromUniversal(u) {
  const x = normalizeUniversalParams(u)
  const design = STYLE_TO_MOCKUP_DESIGN[x.render_style] ?? 'modern_simple'
  return normalizeMockupParams({
    ...createDefaultMockupParams(),
    aspect_ratio: x.aspect_ratio,
    design_style: design,
  })
}

/**
 * 画质 → 原型保真度 1–3；还原度滑块单独写在 extraUniversalContextLines。
 * @param {ReturnType<typeof normalizeUniversalParams>} u
 */
export function wireframeParamsFromUniversal(u) {
  const x = normalizeUniversalParams(u)
  let fidelity = 2
  if (x.quality === 'standard') fidelity = 1
  else if (x.quality === 'hd') fidelity = 2
  else fidelity = 3
  return {
    ...createDefaultWireframeParams(),
    aspect_ratio: x.aspect_ratio,
    render_style: x.render_style,
    fidelity,
  }
}

/**
 * @param {ReturnType<typeof normalizeUniversalParams>} u
 */
export function paletteParamsFromUniversal(u) {
  const x = normalizeUniversalParams(u)
  let color_count = 5
  if (x.quality === 'hd') color_count = 8
  else if (x.quality === 'ultra') color_count = 12
  const mood = STYLE_TO_PALETTE_MOOD[x.render_style] ?? 'warm_cozy'
  const mats =
    x.quality === 'standard'
      ? ['乳胶漆']
      : x.quality === 'hd'
        ? ['乳胶漆', '木饰面', '布艺/皮革']
        : ['乳胶漆', '艺术漆/微水泥', '木饰面', '大理石/岩板', '金属/不锈钢', '布艺/皮革']
  return {
    ...createDefaultPaletteParams(),
    application_field: 'custom',
    mood,
    color_count,
    base_color: x.color_hex || null,
    material_context: mats,
    lighting_condition: 'south_room',
  }
}

function qualitySuffixForErnie(/** @type {string} */ skillId, /** @type {ReturnType<typeof normalizeUniversalParams>} */ u) {
  const x = normalizeUniversalParams(u)
  if (skillId === 'mockup' || skillId === 'retouch') {
    const m = {
      standard: '期望约1024px级构图细节',
      hd: '期望约2048px级细节',
      ultra: '期望约4096px级精细细节与材质',
    }
    return `｜${m[x.quality] || m.hd}`
  }
  if (skillId === 'wireframe') {
    const m = {
      standard: '示意以低保真线框为主',
      hd: '示意以中保真为主',
      ultra: '示意以高保真原型为主',
    }
    return `｜${m[x.quality] || m.hd}`
  }
  if (skillId === 'palette') {
    const m = {
      standard: '扁平分区色块示意',
      hd: '含材质分区氛围与主要色标注',
      ultra: '高精度色板海报可加应用场景拼贴',
    }
    return `｜${m[x.quality] || m.hd}`
  }
  return ''
}

/**
 * @param {string} skillId
 * @param {ReturnType<typeof normalizeUniversalParams>} u
 * @param {Record<string, unknown>} [retouchPanel] AI 修图专用面板（模式、强度）；缺省时修图仍用默认值拼 hint（不应发生在当前 UI）
 */
export function ernieStyleHintForSkill(skillId, u, retouchPanel) {
  const x = normalizeUniversalParams(u)
  if (skillId === 'retouch') {
    const p = normalizeRetouchParams(retouchPanel)
    return buildRetouchErnieStyleHint(p) + qualitySuffixForErnie(skillId, x)
  }
  if (skillId === 'wireframe') {
    return buildWireframeErnieStyleHint(wireframeParamsFromUniversal(x)) + qualitySuffixForErnie(skillId, x)
  }
  if (skillId === 'mockup') {
    return buildMockupErnieStyleHint(mockupParamsFromUniversal(x)) + qualitySuffixForErnie(skillId, x)
  }
  if (skillId === 'palette') {
    return (
      buildPaletteErnieStyleHint(paletteParamsFromUniversal(x)) +
      qualitySuffixForErnie(skillId, x) +
      `｜出图风格 ${x.render_style ?? '写实'}`
    )
  }
  return String(x.render_style ?? '写实')
}

/**
 * @param {string} skillId
 * @param {ReturnType<typeof normalizeUniversalParams>} u
 * @param {string} [directive]
 * @param {Record<string, unknown>} [retouchPanel] AI 修图面板状态
 */
export function buildSkillContextForSkill(skillId, u, directive, retouchPanel) {
  const x = normalizeUniversalParams(u)
  const extra = extraUniversalContextLines(skillId, x)
  const dir = typeof directive === 'string' && directive.trim() ? `${directive.trim()}\n\n` : ''

  let base = ''
  if (skillId === 'palette') {
    base = buildPaletteSkillContext(paletteParamsFromUniversal(x))
  } else if (skillId === 'mockup') {
    base = buildMockupSkillContext(mockupParamsFromUniversal(x))
  } else if (skillId === 'retouch') {
    base = buildRetouchSkillContext(normalizeRetouchParams(retouchPanel))
  } else if (skillId === 'wireframe') {
    base = buildWireframeSkillContext(wireframeParamsFromUniversal(x))
  }

  if (!base && skillId === 'freeform') {
    return dir + (extra || '')
  }

  const merged = dir + base + (extra ? `\n${extra}` : '')
  return merged.replace(/\n\n\n+/g, '\n\n').trim()
}

/**
 * 高速线路 API 可选字段：仅效果图 / 修图 / 原型走文生图时使用。
 * @param {string} skillId
 * @param {ReturnType<typeof normalizeUniversalParams>} u
 */
export function qualityTierForApi(skillId, u) {
  if (!['mockup', 'retouch', 'wireframe', 'palette'].includes(skillId)) return undefined
  const q = normalizeUniversalParams(u).quality
  return q
}

/**
 * 从旧版 skillGenParamStore 迁移一轮（首次无 universal 存储时）。
 * @param {Record<string, unknown>} store
 */
export function migrateStoreToUniversal(store) {
  const d = createDefaultUniversalParams()
  if (!store || typeof store !== 'object') return d
  const mockupRow = store.mockup
  const wfRow = store.wireframe
  let aspect = d.aspect_ratio
  let style = d.render_style
  try {
    const mf = mockupRow?.mockupFields
    if (mf && typeof mf === 'object' && typeof mf.aspect_ratio === 'string') {
      const ar = mf.aspect_ratio
      if (UNIVERSAL_ASPECT_OPTIONS.includes(ar)) aspect = ar
    }
  } catch {
    /* ignore */
  }
  try {
    const wf = wfRow?.wireframeFields
    if (wf && typeof wf === 'object') {
      if (typeof wf.aspect_ratio === 'string' && UNIVERSAL_ASPECT_OPTIONS.includes(wf.aspect_ratio)) {
        aspect = wf.aspect_ratio
      }
      if (typeof wf.render_style === 'string' && UNIVERSAL_STYLE_OPTIONS.includes(wf.render_style)) {
        style = wf.render_style
      }
    }
  } catch {
    /* ignore */
  }
  return normalizeUniversalParams({ ...d, aspect_ratio: aspect, render_style: style })
}
