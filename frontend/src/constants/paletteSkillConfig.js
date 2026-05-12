/** 配色方案技能：面板选项与发往模型的结构化摘要（与 UI 配置一致） */

export const PALETTE_SKILL_META = {
  id: 'palette',
  name: '配色方案',
  iconEmoji: '🎨',
  accentColor: '#F59E0B',
  category: '专业配色',
  outputFormat: 'professional_color_palette',
  inputMode: 'text_with_image_upload',
}

export const APPLICATION_FIELD_OPTIONS = [
  { value: 'interior_wall', label: '🏠 墙面配色', category: '装修', desc: '乳胶漆、艺术漆、墙纸墙布色彩' },
  { value: 'interior_floor', label: '🏠 地面配色', category: '装修', desc: '地板、瓷砖、地毯色彩' },
  { value: 'interior_cabinet', label: '🏠 柜体配色', category: '装修', desc: '衣柜、橱柜、电视柜门板色彩' },
  { value: 'interior_soft', label: '🏠 软装配色', category: '装修', desc: '窗帘、沙发、床品、装饰画色彩' },
  { value: 'interior_lighting', label: '🏠 灯光色温', category: '装修', desc: '主灯、筒灯、灯带、氛围光色温' },
  { value: 'brand_vi', label: '🏢 品牌VI', category: '商业', desc: 'Logo、名片、品牌主色系统' },
  { value: 'packaging', label: '📦 包装设计', category: '商业', desc: '产品包装、礼盒、瓶贴色彩' },
  { value: 'restaurant', label: '🍽️ 餐饮空间', category: '商业', desc: '餐厅、咖啡馆、奶茶店色彩氛围' },
  { value: 'retail', label: '🛍️ 零售门店', category: '商业', desc: '服装店、美妆店、潮玩店色彩' },
  { value: 'web_ui', label: '🌐 网页设计', category: '数字', desc: '网站、落地页、后台系统色彩' },
  { value: 'app_ui', label: '📱 App界面', category: '数字', desc: '移动端应用、小程序色彩' },
  { value: 'dashboard', label: '📊 数据大屏', category: '数字', desc: 'B端后台、可视化看板色彩' },
  { value: 'illustration', label: '🖼️ 插画配色', category: '视觉', desc: '商业插画、绘本、海报色彩' },
  { value: 'photography', label: '📷 摄影调色', category: '视觉', desc: '人像、风景、产品摄影色调' },
  { value: 'fashion', label: '👗 时尚穿搭', category: '视觉', desc: '服装搭配、季节配色、场合配色' },
  { value: 'custom', label: '✨ 自定义', category: '通用', desc: '根据描述自由生成配色方案' },
]

export const MOOD_OPTIONS = [
  { value: 'warm_cozy', label: '温暖治愈', desc: '奶咖、暖白、原木，适合卧室、客厅、咖啡馆' },
  { value: 'fresh_energetic', label: '清新活力', desc: '薄荷绿、天空蓝、柠檬黄，适合儿童房、运动品牌、夏季' },
  { value: 'professional_trust', label: '专业稳重', desc: '深蓝、深灰、金色点缀，适合金融、律所、商务空间' },
  { value: 'luxury_elegant', label: '奢华高级', desc: '墨绿、酒红、香槟金，适合酒店、奢侈品、高端会所' },
  { value: 'minimal_calm', label: '极简冷静', desc: '纯白、浅灰、黑色线条，适合美术馆、科技产品、极简主义' },
  { value: 'romantic_dreamy', label: '浪漫梦幻', desc: '樱花粉、薰衣草紫、珍珠白，适合婚礼、美妆、少女品牌' },
  { value: 'vintage_retro', label: '复古怀旧', desc: '焦糖棕、橄榄绿、芥末黄，适合复古店、咖啡馆、胶片摄影' },
  { value: 'nature_organic', label: '自然有机', desc: '苔藓绿、陶土色、亚麻色，适合民宿、有机品牌、侘寂风' },
  { value: 'tech_future', label: '科技未来', desc: '电光蓝、霓虹紫、深空黑，适合游戏、元宇宙、科技产品' },
  { value: 'playful_cute', label: '活泼趣味', desc: '珊瑚橙、薄荷绿、奶油黄，适合儿童乐园、零食品牌、插画' },
]

export const LIGHTING_OPTIONS = [
  { value: 'north_room', label: '北向房间（偏冷）', desc: '建议暖色调平衡' },
  { value: 'south_room', label: '南向房间（偏暖）', desc: '可承受冷色调' },
  { value: 'low_floor', label: '低楼层/采光差', desc: '建议高明度色彩提亮' },
  { value: 'high_floor', label: '高楼层/采光好', desc: '色彩选择自由度高' },
  { value: 'artificial_light', label: '主要靠灯光', desc: '需考虑色温对色彩的影响' },
]

export const MATERIAL_OPTIONS = [
  '乳胶漆',
  '艺术漆/微水泥',
  '木饰面',
  '大理石/岩板',
  '瓷砖',
  '木地板',
  '金属/不锈钢',
  '玻璃',
  '布艺/皮革',
  '绿植',
]

export const CONTRAST_SEGMENTS = ['AA级（标准）', 'AAA级（高对比）', '自定义']

export const UPLOAD_PROMPT_HINTS = [
  { type: 'reference_image', label: '🖼️ 参考图片', desc: '上传你喜欢的配色参考图' },
  { type: 'space_photo', label: '🏠 空间照片', desc: '上传你的房间照片，AI基于现有硬装配色' },
  { type: 'product_photo', label: '📦 产品照片', desc: '上传产品图，提取产品色做品牌延展' },
]

export const PALETTE_QUICK_EXAMPLES = [
  '奶油风客厅，南向，墙面用艺术漆，地板原木色，想要温暖治愈',
  '帮我基于这张户型照片，提取现有硬装色，配软装方案',
  '儿童房10㎡，男孩，想要活泼但不刺眼，墙面+窗帘+床品配色',
  '极简风卧室，微水泥墙面，想要侘寂感，配床品和窗帘',
  '新中式茶饮品牌，目标人群25-35岁，要传统但不老气',
  '科技初创公司Logo，要传递创新和可靠，深色模式优先',
  '医疗健康管理App，要专业安心，深色模式，高对比度',
  'B端数据分析后台，要长时间使用不疲劳，区分多组数据',
  '秋季通勤穿搭，适合黄皮，要高级不沉闷，全身配色',
  '海边度假穿搭，冷白皮，要清新出片，配配饰色彩',
  '上传这张插画，提取配色并生成同风格的品牌色板',
  '我想要一个以墨绿为主色的奢华配色，用于会所设计',
]

export const PALETTE_PLACEHOLDER =
  '描述配色或上传参考图，例：客厅奶油风｜新茶饮品牌清新活泼｜B端深色界面…'

/** @returns {Record<string, unknown>} */
export function createDefaultPaletteParams() {
  return {
    application_field: 'interior_wall',
    mood: 'warm_cozy',
    color_count: 5,
    base_color: null,
    contrast_level: 'AA级（标准）',
    material_context: ['乳胶漆', '木地板'],
    lighting_condition: 'south_room',
  }
}

export function paletteShowsContrast(applicationField) {
  return ['web_ui', 'app_ui', 'dashboard'].includes(applicationField)
}

export function paletteShowsInteriorExtras(applicationField) {
  return typeof applicationField === 'string' && applicationField.startsWith('interior_')
}

function optLabel(options, value) {
  const o = options.find((x) => x.value === value)
  return o ? o.label : String(value)
}

/**
 * @param {Record<string, unknown>} p
 */
/**
 * 文生图第二轮「风格标签」：配色可视化 / 换色示意。
 * @param {Record<string, unknown>} p 与 {@link buildPaletteSkillContext} 相同结构
 */
export function buildPaletteErnieStyleHint(p) {
  const af = p.application_field ?? 'custom'
  const mood = p.mood ?? 'warm_cozy'
  const n = Number(p.color_count)
  const cnt = Number.isFinite(n) ? Math.min(24, Math.max(3, Math.round(n))) : 5
  const parts = [
    '配色方案可视化',
    optLabel(APPLICATION_FIELD_OPTIONS, af),
    optLabel(MOOD_OPTIONS, mood),
    `${cnt}色专业色板`,
    '含主辅点缀背景文字角色分区',
  ]
  if (p.base_color && String(p.base_color).trim() !== '') {
    parts.push(`基准色 ${String(p.base_color).trim()}`)
  }
  return parts.join('｜')
}

export function buildPaletteSkillContext(p) {
  const af = p.application_field ?? 'interior_wall'
  const mood = p.mood ?? 'warm_cozy'
  const lines = []
  lines.push(`应用领域：${optLabel(APPLICATION_FIELD_OPTIONS, af)}（${af}）`)
  lines.push(`情绪氛围：${optLabel(MOOD_OPTIONS, mood)}（${mood}）`)
  lines.push(`色板颜色数量：${p.color_count ?? 5}`)
  if (p.base_color && String(p.base_color).trim() !== '') {
    lines.push(`用户指定基准色：${String(p.base_color).trim()}`)
  }
  if (paletteShowsContrast(af)) {
    lines.push(`对比度标准：${p.contrast_level ?? 'AA级（标准）'}`)
  }
  if (paletteShowsInteriorExtras(af)) {
    const mats = Array.isArray(p.material_context) ? p.material_context : []
    lines.push(`关联材质：${mats.length ? mats.join('、') : '（未选）'}`)
    lines.push(
      `空间光照：${optLabel(LIGHTING_OPTIONS, p.lighting_condition ?? 'south_room')}`,
    )
  }
  lines.push(
    '请严格结合上述面板选项与用户正文（及参考图说明）输出 professional_color_palette 结构的专业配色方案。',
  )
  lines.push(
    '【配图流水线】本站将在你回复后自动生成一张「配色可视化配图」。若用户上传了参考图，除文字色板外须明确写出「换色示意」：在同构图/同主体前提下按本方案替换颜色后的氛围与色相分配，便于后续文生图还原。',
  )
  return lines.join('\n')
}
