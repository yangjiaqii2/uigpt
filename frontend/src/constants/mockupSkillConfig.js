/** 「效果图设计 / 全屋定制」技能：面板选项与发往模型的摘要（与产品 YAML 对齐） */

export const MOCKUP_SKILL_META = {
  id: 'mockup',
  name: '效果图设计',
  iconEmoji: '🏠',
  accentColor: '#10B981',
  category: '全屋定制设计',
  outputFormat: 'whole_house_custom_design',
  inputMode: 'text_with_image_upload',
}

/** 户型类型 */
export const HOUSE_TYPE_OPTIONS = [
  { value: 'one_room', label: '一居室', desc: '单间/开间，30-50㎡' },
  { value: 'two_room', label: '两居室', desc: '两室一厅，60-90㎡' },
  { value: 'three_room', label: '三居室', desc: '三室两厅，90-120㎡' },
  { value: 'four_room', label: '四居室', desc: '四室及以上，120-160㎡' },
  { value: 'duplex', label: '复式/跃层', desc: '两层空间，楼梯连接' },
  { value: 'villa', label: '别墅', desc: '多层独立住宅' },
]

/** 出图范围（全屋视角） */
export const RENDER_SCOPE_OPTIONS = [
  {
    value: 'whole_house_overview',
    label: '全屋鸟瞰',
    desc: '俯视整体布局，看动线和分区',
  },
  {
    value: 'living_dining',
    label: '客餐厅全景',
    desc: 'LDK一体化视角，最常用',
  },
  { value: 'living_room', label: '客厅', desc: '沙发区+电视墙+阳台' },
  {
    value: 'master_bedroom',
    label: '主卧',
    desc: '床区+衣柜+飘窗/梳妆台',
  },
  {
    value: 'second_bedroom',
    label: '次卧/儿童房',
    desc: '床+书桌+收纳',
  },
  { value: 'kitchen', label: '厨房', desc: '橱柜+中岛+电器位' },
  { value: 'bathroom', label: '卫生间', desc: '浴室柜+淋浴区+马桶区' },
  { value: 'entryway', label: '玄关', desc: '鞋柜+换鞋凳+挂衣区' },
  { value: 'study', label: '书房', desc: '书桌+书柜+阅读角' },
  {
    value: 'walk_in_closet',
    label: '衣帽间',
    desc: '衣柜岛台+梳妆区',
  },
  { value: 'balcony', label: '阳台', desc: '洗晒区+休闲区+绿植' },
]

export const DESIGN_STYLE_OPTIONS = [
  {
    value: 'modern_simple',
    label: '现代简约',
    desc: '线条简洁，功能至上，全屋统一',
  },
  {
    value: 'nordic',
    label: '北欧风',
    desc: '原木+白色，自然温馨，全屋通铺',
  },
  {
    value: 'chinese_new',
    label: '新中式',
    desc: '传统意境+现代材质，客厅卧室呼应',
  },
  {
    value: 'light_luxury',
    label: '轻奢风',
    desc: '金属+大理石，精致不浮夸',
  },
  {
    value: 'cream',
    label: '奶油风',
    desc: '奶白+奶咖，圆弧造型，全屋柔和',
  },
  {
    value: 'wabi_sabi',
    label: '侘寂风',
    desc: '微水泥+手工质感，全屋统一肌理',
  },
  {
    value: 'japanese',
    label: '日式原木',
    desc: '榻榻米+障子门+原木，禅意自然',
  },
  {
    value: 'industrial',
    label: '工业风',
    desc: '裸露管线+水泥+金属，粗犷个性',
  },
  {
    value: 'minimalist',
    label: '极简风',
    desc: '极致留白，隐藏收纳，高级感',
  },
]

export const ASPECT_RATIO_OPTIONS = [
  {
    value: '16:9',
    label: '16:9 全景',
    desc: '适合客餐厅大横厅、全屋鸟瞰',
  },
  {
    value: '4:3',
    label: '4:3 标准',
    desc: '通用房间展示',
  },
  {
    value: '21:9',
    label: '21:9 超宽',
    desc: 'LDK一体化、大平层',
  },
  {
    value: '3:2',
    label: '3:2 横版',
    desc: '卧室、书房',
  },
  {
    value: '1:1',
    label: '1:1 细节',
    desc: '柜体特写、材质展示',
  },
  {
    value: '9:16',
    label: '9:16 竖版',
    desc: '玄关、卫生间竖向空间',
  },
]

export const CAMERA_ANGLE_SEGMENTS = ['人视角度', '鸟瞰角度', '半鸟瞰', '轴测角度']

export const LIGHTING_OPTIONS = [
  {
    value: 'natural_day',
    label: '自然日光',
    desc: '白天自然光，明亮通透',
  },
  {
    value: 'warm_evening',
    label: '暖色傍晚',
    desc: '夕阳暖光，温馨氛围',
  },
  {
    value: 'night',
    label: '夜晚灯光',
    desc: '人工照明，氛围灯+主灯',
  },
  {
    value: 'overcast',
    label: '阴天柔光',
    desc: '漫射光，柔和无阴影',
  },
]

export const PROJECT_STAGE_SEGMENTS = ['概念方案', '效果图深化', '施工图辅助']

/** 定制重点（多选） */
export const CUSTOM_FOCUS_OPTIONS = [
  '全屋柜体',
  '背景墙',
  '吊顶造型',
  '地面材质',
  '灯光设计',
  '收纳系统',
  '门窗改造',
]

export const BUDGET_LEVEL_OPTIONS = [
  {
    value: 'economy',
    label: '经济型',
    desc: '注重性价比，国产板材+基础五金',
  },
  {
    value: 'mid_range',
    label: '品质型',
    desc: '国产高端/进口入门，品牌五金',
  },
  {
    value: 'premium',
    label: '高端型',
    desc: '进口板材+进口五金，定制工艺',
  },
  {
    value: 'luxury',
    label: '奢华型',
    desc: '实木+天然石材+进口定制',
  },
]

export const MOCKUP_UPLOAD_HINTS = [
  {
    type: 'floor_plan',
    label: '📐 户型图/平面图',
    desc: '上传开发商户型图或CAD平面图（必须包含房间尺寸）',
  },
  {
    type: 'reference_style',
    label: '🖼️ 风格参考',
    desc: '上传你喜欢的装修风格照片',
  },
  {
    type: 'site_photo',
    label: '📷 现场照片',
    desc: '上传毛坯房或现有装修照片',
  },
]

export const MOCKUP_QUICK_EXAMPLES = [
  '上传户型图：105平三室两厅，帮我做现代简约全屋效果图',
  '上传户型图+参考图：喜欢这个奶油风，应用到我家户型',
  '90平两居室，客厅要悬浮电视墙+无主灯，主卧L型衣柜带梳妆台',
  '120平四居室，新中式风格，客餐厅要背景墙+灯带，儿童房成长性设计',
  '复式180平，轻奢风，楼梯下方做收纳，主卧做步入式衣帽间',
  '上传现场照片：毛坯房，帮我规划全屋定制柜体位置和效果图',
]

export const MOCKUP_PLACEHOLDER =
  '上传户型图/参考图或文字描述需求，例：105平三居现代简约，客厅无主灯+悬浮电视墙…'

const LEGACY_SPACE_TO_RENDER_SCOPE = {
  living_room: 'living_room',
  bedroom: 'master_bedroom',
  kitchen: 'kitchen',
  bathroom: 'bathroom',
  dining_room: 'living_dining',
  study: 'study',
  balcony: 'balcony',
  entryway: 'entryway',
  walk_in_closet: 'walk_in_closet',
  kids_room: 'second_bedroom',
  commercial: 'living_dining',
}

/** @returns {Record<string, unknown>} */
export function createDefaultMockupParams() {
  return {
    house_type: 'three_room',
    area: '',
    design_style: 'modern_simple',
    render_scope: 'living_dining',
    aspect_ratio: '16:9',
    camera_angle: '人视角度',
    lighting: 'natural_day',
    project_stage: '概念方案',
    custom_focus: ['全屋柜体', '背景墙'],
    budget_level: 'mid_range',
  }
}

/**
 * 合并本地缓存与默认值；兼容旧版 space_type / 家具模式字段。
 * @param {Record<string, unknown> | null | undefined} raw
 */
export function normalizeMockupParams(raw) {
  const base = createDefaultMockupParams()
  const o = raw && typeof raw === 'object' ? { ...raw } : {}
  const merged = { ...base, ...o }

  if (!o.house_type && typeof o.space_type === 'string' && o.space_type) {
    merged.house_type = 'three_room'
  }
  if (!o.render_scope && typeof o.space_type === 'string' && o.space_type) {
    merged.render_scope =
      LEGACY_SPACE_TO_RENDER_SCOPE[o.space_type] ?? base.render_scope
  }

  if (!Array.isArray(merged.custom_focus)) {
    merged.custom_focus = [...base.custom_focus]
  }

  const arOk = ASPECT_RATIO_OPTIONS.some((x) => x.value === merged.aspect_ratio)
  if (!arOk) merged.aspect_ratio = base.aspect_ratio

  const htOk = HOUSE_TYPE_OPTIONS.some((x) => x.value === merged.house_type)
  if (!htOk) merged.house_type = base.house_type

  const rsOk = RENDER_SCOPE_OPTIONS.some((x) => x.value === merged.render_scope)
  if (!rsOk) merged.render_scope = base.render_scope

  const blOk = BUDGET_LEVEL_OPTIONS.some((x) => x.value === merged.budget_level)
  if (!blOk) merged.budget_level = base.budget_level

  if (!CAMERA_ANGLE_SEGMENTS.includes(String(merged.camera_angle ?? ''))) {
    merged.camera_angle = base.camera_angle
  }

  return merged
}

export function labelForHouseType(value) {
  const o = HOUSE_TYPE_OPTIONS.find((x) => x.value === value)
  return o ? o.label : String(value)
}

export function labelForRenderScope(value) {
  const o = RENDER_SCOPE_OPTIONS.find((x) => x.value === value)
  return o ? o.label : String(value)
}

export function descForRenderScope(value) {
  const o = RENDER_SCOPE_OPTIONS.find((x) => x.value === value)
  return o?.desc ?? ''
}

export function labelForDesignStyle(value) {
  const o = DESIGN_STYLE_OPTIONS.find((x) => x.value === value)
  return o ? o.label : String(value)
}

/** 设计风格完整说明（用于面板展示与模型上下文） */
export function descForDesignStyle(value) {
  const o = DESIGN_STYLE_OPTIONS.find((x) => x.value === value)
  return o?.desc ?? ''
}

/** @param {string} value */
export function detailLineForDesignStyle(value) {
  const o = DESIGN_STYLE_OPTIONS.find((x) => x.value === value)
  if (!o) return String(value)
  return `${o.label}：${o.desc}`
}

/** 出图比例对应的构图说明 */
export function descForAspectRatio(value) {
  const o = ASPECT_RATIO_OPTIONS.find((x) => x.value === value)
  return o?.desc ?? ''
}

export function labelForLighting(value) {
  const o = LIGHTING_OPTIONS.find((x) => x.value === value)
  return o ? o.label : String(value)
}

export function descForLighting(value) {
  const o = LIGHTING_OPTIONS.find((x) => x.value === value)
  return o?.desc ?? ''
}

export function labelForBudgetLevel(value) {
  const o = BUDGET_LEVEL_OPTIONS.find((x) => x.value === value)
  return o ? o.label : String(value)
}

export function descForBudgetLevel(value) {
  const o = BUDGET_LEVEL_OPTIONS.find((x) => x.value === value)
  return o?.desc ?? ''
}

/**
 * @param {Record<string, unknown>} p
 */
export function buildMockupSkillContext(p) {
  const lines = []
  const ar = p.aspect_ratio ?? '16:9'
  const ds = p.design_style ?? 'modern_simple'
  const lt = p.lighting ?? 'natural_day'
  const areaStr =
    p.area != null && String(p.area).trim() !== '' ? String(p.area).trim() : '（未填）'
  lines.push(`户型类型：${labelForHouseType(p.house_type ?? 'three_room')}（${p.house_type}）`)
  lines.push(`建筑面积：${areaStr}㎡（面板填写，可与正文互相补充）`)
  lines.push(`设计风格（须严格遵守）：${detailLineForDesignStyle(ds)}（枚举值 ${ds}）`)
  lines.push(
    `出图范围：${labelForRenderScope(p.render_scope ?? 'living_dining')}——${descForRenderScope(p.render_scope ?? 'living_dining')}（${p.render_scope}）`,
  )
  lines.push(`出图比例与构图（须严格遵守）：${ar}；${descForAspectRatio(ar)}`)
  lines.push(`渲染视角：${p.camera_angle ?? '人视角度'}`)
  lines.push(
    `光照氛围：${labelForLighting(lt)}——${descForLighting(lt)}（枚举值 ${lt}）`,
  )
  lines.push(`项目阶段：${p.project_stage ?? '概念方案'}`)
  const focus = Array.isArray(p.custom_focus) ? p.custom_focus : []
  lines.push(`定制重点：${focus.length ? focus.join('、') : '（未选）'}`)
  lines.push(
    `预算档位：${labelForBudgetLevel(p.budget_level ?? 'mid_range')}——${descForBudgetLevel(p.budget_level ?? 'mid_range')}（${p.budget_level}）`,
  )
  lines.push(
    '请结合上述面板选项与用户正文（及户型图/参考图说明），按 whole_house_custom_design 输出；助手中文摘要须点名户型、出图范围、风格关键词、比例构图与光照气质，便于后续英文文生图还原。',
  )
  lines.push(
    '【出图一致性】后续自动文生图将按用户所选「设计风格」「出图比例」「光照」生成画面：请勿在回复中改换其他风格或横竖构图。',
  )
  return lines.join('\n')
}

/**
 * 供 ERNIE 管线「风格标签」字段：聚合面板选项，强化与界面选择一致。
 * @param {Record<string, unknown>} p
 */
export function buildMockupErnieStyleHint(p) {
  const ds = p.design_style ?? 'modern_simple'
  const ar = p.aspect_ratio ?? '16:9'
  const lt = p.lighting ?? 'natural_day'
  const areaBit =
    p.area != null && String(p.area).trim() !== ''
      ? `${String(p.area).trim()}㎡`
      : ''
  const parts = [
    '全屋定制装修设计效果图',
    detailLineForDesignStyle(ds),
    `户型 ${labelForHouseType(p.house_type ?? 'three_room')}${areaBit ? ` ${areaBit}` : ''}`,
    `出图范围 ${labelForRenderScope(p.render_scope ?? 'living_dining')}`,
    `画面比例 ${ar}｜构图要求：${descForAspectRatio(ar)}`,
    `${labelForLighting(lt)}｜${descForLighting(lt)}`,
    `相机视角 ${p.camera_angle ?? '人视角度'}`,
    `阶段 ${p.project_stage ?? '概念方案'}`,
    `预算 ${labelForBudgetLevel(p.budget_level ?? 'mid_range')}`,
  ]
  const focus = Array.isArray(p.custom_focus) ? p.custom_focus : []
  if (focus.length) parts.push(`定制重点 ${focus.join('、')}`)
  parts.push('必须严格按上述装修风格与构图比例生成，不得擅自替换为其他风格或画幅。')
  return parts.join('｜')
}
