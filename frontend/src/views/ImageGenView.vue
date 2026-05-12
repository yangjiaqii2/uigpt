<script setup>
/**
 * 图片创作工作台：UI 状态机 + API 易 Nano Banana Pro（服务端代理 generateContent）。
 *
 * 状态：canvasPhase = empty | generating | done | edit-inpaint | edit-outpaint
 */
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import ChatProfileDrawer from '../components/chat/ChatProfileDrawer.vue'
import FullscreenImagePreview from '../components/FullscreenImagePreview.vue'
import { nanoBananaEdit, nanoBananaTextToImage, optimizeImageStudioPrompt } from '../api/imageStudio'
import { fetchMeImagesPage, patchMyImageFavorite } from '../api/meProfile'
import { getAxiosErrorMessage } from '../utils/httpError'
import {
  nanoBananaPointsForStudioQuality,
  INSUFFICIENT_POINTS_TOOLTIP_ZH,
} from '../constants/pointCosts'

const router = useRouter()
const auth = useAuthStore()

const STORAGE_PRESETS = 'uigpt_image_gen_presets'

/** @typedef {'empty'|'generating'|'done'|'edit-inpaint'|'edit-outpaint'} CanvasPhase */
/** @typedef {'txt2img'|'img2img'|'inpaint'|'outpaint'|'enhance'|'style'} ToolId */

const profileOpen = ref(false)
const fullscreenPreviewOpen = ref(false)
/** @type {import('vue').Ref<string>} */
const fullscreenPreviewUrl = ref('')

function openFullscreenImagePreview(url) {
  if (!url) return
  fullscreenPreviewUrl.value = url
  fullscreenPreviewOpen.value = true
}
const profileWrapRef = ref(null)
const fileInputRef = ref(null)
const inpaintCanvasRef = ref(null)

/** @type {import('vue').Ref<CanvasPhase>} */
const canvasPhase = ref('empty')
/** @type {import('vue').Ref<ToolId>} */
const activeTool = ref('txt2img')

const prompt = ref('')
const progressMsg = ref('')
const progressPct = ref(0)
/** @type {import('vue').Ref<string|null>} */
const resultUrl = ref(null)
const genMeta = ref({ w: 1024, h: 1024, style: '', ago: '' })

let genInterval = null
/** @type {AbortController | null} */
let genAbortController = null

const panelCollapsed = ref(false)

const aspects = [
  { id: '1:1', label: '1:1', w: 1, h: 1 },
  { id: '9:16', label: '9:16', w: 9, h: 16 },
  { id: '16:9', label: '16:9', w: 16, h: 9 },
  { id: '3:4', label: '3:4', w: 3, h: 4 },
  { id: '4:3', label: '4:3', w: 4, h: 3 },
  { id: '21:9', label: '21:9', w: 21, h: 9 },
]
const aspectId = ref('1:1')

/** Gemini/Banana：风格卡片左侧 12×12 色块（写实肤色、插画明黄、3D 天蓝…） */
const styleOptions = [
  { id: 'realistic', label: '写实', swatch: '#e8c4b0' },
  { id: 'illustration', label: '插画', swatch: '#facc15' },
  { id: '3d', label: '3D 渲染', swatch: '#38bdf8' },
  { id: 'flat', label: '极简扁平', swatch: '#6ee7b7' },
  { id: 'sketch', label: '手绘素描', swatch: '#d4d4d8' },
  { id: 'cinematic', label: '电影感', swatch: '#3f3f46' },
]
const styleId = ref('realistic')
/** 风格迁移目标风格 */
const styleTargetId = ref('illustration')

const fidelity = ref(7)
const quality = ref('hd')

const studioGenPointsCost = computed(() => nanoBananaPointsForStudioQuality(quality.value))
const studioGenInsufficientPoints = computed(
  () => auth.isAuthenticated && auth.points < studioGenPointsCost.value,
)

const refStrength = ref(62)
const inpaintStrength = ref(45)
const outpaintDirs = ref({ t: true, r: true, b: true, l: true })
const outpaintScale = ref('1.5')
const enhanceRes = ref('2k')
const denoise = ref(40)
const stylePreserve = ref(true)

/** 局部重绘画笔预设：小/中/大 */
const inpaintBrushPreset = ref('md')

/** @type {import('vue').Ref<{ url: string }[]>} */
const referenceImages = ref([])
/** @type {import('vue').Ref<{ id: number, url: string, tool: string, aspect: string, style: string, at: number, favorite?: boolean, skillId?: string }[]>} */
const historyItems = ref([])
/** 当前成图对应服务端记录（COS），用于收藏 */
const serverImageId = ref(null)
const resultFavorite = ref(false)
/** @type {import('vue').Ref<number | null>} */
const favBusyId = ref(null)
const inpaintBrush = ref(28)
const drawing = ref(false)
const statusHint = ref('就绪')
const promptOptimizing = ref(false)

/** 本页多轮出图/编辑摘要，每次成功生成后追加，下次请求带给服务端 imageSessionContext */
const STUDIO_CTX_CAP = 8000
/** @type {import('vue').Ref<string>} */
const imageSessionContext = ref('')

function appendStudioImageContext(toolLabel, promptText) {
  const t = (promptText || '').trim()
  if (!t) return
  const line = `【${toolLabel}】${t.length > 600 ? t.slice(0, 600) + '…' : t}`
  const cur = imageSessionContext.value.trim()
  const next = cur ? `${cur}\n${line}` : line
  imageSessionContext.value =
    next.length > STUDIO_CTX_CAP ? next.slice(next.length - STUDIO_CTX_CAP) : next
}

const TOOL_LABEL_ZH = {
  txt2img: '文生图',
  img2img: '图生图',
  inpaint: '局部重绘',
  outpaint: '智能扩图',
  enhance: '画质增强',
  style: '风格迁移',
}

/** 空状态快捷文案（设计向，点击填入输入框） */
const examplePrompts = [
  '极简留白的产品海报构图，强调层级与呼吸感',
  '克制配色与八栏栅格排版，适合落地页首屏',
  '玻璃拟态 UI 概念稿，圆角与光影层次清晰',
  '基于品牌主色的延展视觉系统色板与应用示例',
]

const tools = [
  { id: 'txt2img', label: '文生图' },
  { id: 'img2img', label: '图生图' },
  { id: 'inpaint', label: '局部重绘' },
  { id: 'outpaint', label: '智能扩图' },
  { id: 'enhance', label: '画质增强' },
  { id: 'style', label: '风格迁移' },
]

const presets = ref([])

const showBottomPrompt = computed(
  () =>
    canvasPhase.value !== 'generating' &&
    canvasPhase.value !== 'edit-inpaint' &&
    canvasPhase.value !== 'edit-outpaint',
)

function loadLocalPresets() {
  try {
    const pr = localStorage.getItem(STORAGE_PRESETS)
    presets.value = pr ? JSON.parse(pr) : []
  } catch {
    presets.value = []
  }
}

function persistPresets() {
  localStorage.setItem(STORAGE_PRESETS, JSON.stringify(presets.value.slice(0, 20)))
}

function mapStudioRows(data) {
  const rows = Array.isArray(data) ? data : []
  return rows.map((row) => ({
    id: row.id,
    url: row.imageUrl,
    tool: '工作台',
    aspect: '',
    style: '',
    at: row.createdAt ? new Date(row.createdAt).getTime() : Date.now(),
    favorite: Boolean(row.favorite),
    skillId: row.skillId || 'studio',
  }))
}

async function refreshStudioLibrary() {
  if (!auth.isAuthenticated) {
    historyItems.value = []
    return
  }
  try {
    const { data } = await fetchMeImagesPage({ page: 0, size: 48, skill: 'studio' })
    historyItems.value = mapStudioRows(data)
  } catch {
    /* 列表失败不阻断作画 */
  }
}

async function toggleCanvasFavorite() {
  const id = serverImageId.value
  if (id == null || favBusyId.value === id) return
  favBusyId.value = id
  const next = !resultFavorite.value
  try {
    await patchMyImageFavorite(id, next)
    resultFavorite.value = next
    const hi = historyItems.value.find((x) => x.id === id)
    if (hi) hi.favorite = next
  } catch (e) {
    statusHint.value = getAxiosErrorMessage(e)
  } finally {
    favBusyId.value = null
  }
}

function formatAgo(ts) {
  const s = Math.floor((Date.now() - ts) / 1000)
  if (s < 60) return '刚刚'
  if (s < 3600) return `${Math.floor(s / 60)} 分钟前`
  if (s < 86400) return `${Math.floor(s / 3600)} 小时前`
  return `${Math.floor(s / 86400)} 天前`
}

function openConversation(id) {
  router.push({ path: '/chat', query: { conversation: String(id) } })
}

function logout() {
  profileOpen.value = false
  auth.logout()
  void router.push('/login')
}

function toggleProfile(e) {
  e.stopPropagation()
  profileOpen.value = !profileOpen.value
}

function onDocClick(e) {
  if (!(e.target instanceof Node)) return
  if (
    e.target.closest?.('.pp-shell') ||
    e.target.closest?.('.pp-modal-shell') ||
    e.target.closest?.('.ig-profile-wrap')
  ) {
    return
  }
  const pw = profileWrapRef.value
  if (pw && !pw.contains(e.target)) profileOpen.value = false
}

function onDocKeydown(e) {
  if (e.key !== 'Escape') return
  if (fullscreenPreviewOpen.value) {
    fullscreenPreviewOpen.value = false
    return
  }
  if (profileOpen.value) profileOpen.value = false
}

function selectTool(id) {
  activeTool.value = id
  if (id === 'inpaint' || id === 'outpaint') {
    if (!resultUrl.value || canvasPhase.value === 'empty') {
      statusHint.value = '请先生成图片或导入参考图后再使用该工具'
      return
    }
    canvasPhase.value = id === 'inpaint' ? 'edit-inpaint' : 'edit-outpaint'
    statusHint.value = id === 'inpaint' ? '涂抹需要重绘的区域，填写描述后确认生成' : '调整扩展方向与比例后确认生成'
    nextTick(() => initInpaintCanvas())
    return
  }
  if (canvasPhase.value === 'edit-inpaint' || canvasPhase.value === 'edit-outpaint') {
    canvasPhase.value = resultUrl.value ? 'done' : 'empty'
  }
  statusHint.value = `已切换：${tools.find((t) => t.id === id)?.label || ''}`
}

function initInpaintCanvas() {
  const c = inpaintCanvasRef.value
  if (!c) return
  const rect = c.parentElement?.getBoundingClientRect()
  if (!rect) return
  const dpr = window.devicePixelRatio || 1
  c.width = Math.floor(rect.width * dpr)
  c.height = Math.floor(rect.height * dpr)
  c.style.width = `${rect.width}px`
  c.style.height = `${rect.height}px`
  const ctx = c.getContext('2d')
  if (!ctx) return
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  ctx.clearRect(0, 0, rect.width, rect.height)
}

function paintInpaint(e) {
  const c = inpaintCanvasRef.value
  if (!c || !drawing.value) return
  const ctx = c.getContext('2d')
  if (!ctx) return
  const r = c.getBoundingClientRect()
  const x = e.clientX - r.left
  const y = e.clientY - r.top
  ctx.fillStyle = 'rgba(16, 185, 129, 0.38)'
  ctx.globalCompositeOperation = 'source-over'
  ctx.beginPath()
  ctx.arc(x, y, inpaintBrush.value / 2, 0, Math.PI * 2)
  ctx.fill()
}

function clearInpaintMask() {
  initInpaintCanvas()
}

function newCreation() {
  stopGeneration()
  canvasPhase.value = 'empty'
  resultUrl.value = null
  prompt.value = ''
  serverImageId.value = null
  resultFavorite.value = false
  revokeRefs()
  referenceImages.value = []
  statusHint.value = '新建创作'
}

function revokeRefs() {
  referenceImages.value.forEach((x) => {
    if (x.url.startsWith('blob:')) URL.revokeObjectURL(x.url)
  })
}

function triggerImport() {
  fileInputRef.value?.click()
}

function onFilesSelected(e) {
  const files = e.target?.files
  if (!files?.length) return
  ingestImageFiles(files)
  e.target.value = ''
}

/** @param {FileList | File[]} files */
function ingestImageFiles(files) {
  if (!files?.length) return
  for (const f of files) {
    if (!f.type.startsWith('image/')) continue
    referenceImages.value.push({ url: URL.createObjectURL(f) })
  }
  if (!resultUrl.value && referenceImages.value.length) {
    canvasPhase.value = 'done'
    resultUrl.value = referenceImages.value[0].url
    serverImageId.value = null
    resultFavorite.value = false
    genMeta.value = { ...genMeta.value, ago: '导入' }
  }
}

function onCanvasDrop(e) {
  const dt = e.dataTransfer
  if (!dt?.files?.length) return
  ingestImageFiles(dt.files)
}

function removeRef(i) {
  const x = referenceImages.value[i]
  if (x?.url.startsWith('blob:')) URL.revokeObjectURL(x.url)
  referenceImages.value.splice(i, 1)
}

function applyExample(text) {
  prompt.value = text
}

async function optimizePrompt() {
  if (!auth.isAuthenticated) {
    statusHint.value = '请先登录后再使用提示词优化'
    return
  }
  const raw = prompt.value.trim()
  if (!raw) {
    statusHint.value = '请先输入描述'
    return
  }
  if (promptOptimizing.value || canvasPhase.value === 'generating') return
  promptOptimizing.value = true
  statusHint.value = '正在优化提示词…'
  try {
    const toolLab = tools.find((t) => t.id === activeTool.value)?.label || String(activeTool.value)
    const styleLab = styleOptions.find((s) => s.id === styleId.value)?.label || ''
    const aspectLab = aspects.find((a) => a.id === aspectId.value)?.label || String(aspectId.value)
    const qLab = quality.value === 'uhd' ? '超清' : quality.value === 'hd' ? '高清' : '标准'
    const { data } = await optimizeImageStudioPrompt({
      prompt: raw,
      tool: toolLab,
      styleLabel: styleLab,
      aspectLabel: aspectLab,
      qualityLabel: qLab,
    })
    const next = typeof data?.optimizedPrompt === 'string' ? data.optimizedPrompt.trim() : ''
    if (next) {
      prompt.value = next
      statusHint.value = '提示词已优化，可直接生成'
    } else {
      statusHint.value = '优化结果为空，请重试'
    }
  } catch (e) {
    statusHint.value = getAxiosErrorMessage(e)
  } finally {
    promptOptimizing.value = false
  }
}

function qualityToImageSize(q) {
  if (q === 'uhd') return '4K'
  if (q === 'hd') return '2K'
  return '1K'
}

function buildAugmentedPrompt() {
  let p = prompt.value.trim()
  const styleLabel = styleOptions.find((s) => s.id === styleId.value)?.label || ''
  if (styleLabel && activeTool.value !== 'style') {
    p += `${p ? '，' : ''}${styleLabel}风格`
  }
  if (activeTool.value === 'style') {
    const tgt = styleOptions.find((s) => s.id === styleTargetId.value)?.label || ''
    if (tgt) p += `${p ? '，' : ''}目标风格：${tgt}`
  }
  p += `${p ? '，' : ''}创意/还原平衡：${fidelity.value}/10`
  return p.trim()
}

function buildEditPrompt(baseAug) {
  let s = baseAug
  const t = activeTool.value
  if (t === 'inpaint') {
    s += `。局部重绘：变化程度约 ${inpaintStrength.value}%，按指令修改需调整区域（绿色半透明为用户标注示意）。`
  }
  if (t === 'outpaint') {
    const d = outpaintDirs.value
    const dirs = [d.t && '上', d.r && '右', d.b && '下', d.l && '左'].filter(Boolean).join('、')
    s += `。智能扩图：向「${dirs || '四周'}」扩展约 ${outpaintScale.value} 倍，自然补全画面。`
  }
  if (t === 'enhance') {
    s += `。画质增强：目标约 ${String(enhanceRes.value).toUpperCase()}，降噪 ${denoise.value}%。`
  }
  if (t === 'style') {
    const tgt = styleOptions.find((x) => x.id === styleTargetId.value)?.label || ''
    if (tgt) s += `。迁移为「${tgt}」风格。`
    s += stylePreserve.value ? '尽量保留结构与构图。' : '可适度调整构图。'
  }
  if (t === 'img2img') {
    s += `。参考影响约 ${refStrength.value}%（请在整体上保持合理忠实度）。`
  }
  return s
}

/** @returns {Promise<{ mimeType: string, dataBase64: string }>} */
async function blobUrlToInlinePart(url) {
  const res = await fetch(url)
  const blob = await res.blob()
  const mimeType =
    blob.type && blob.type.startsWith('image/')
      ? blob.type === 'image/png'
        ? 'image/png'
        : 'image/jpeg'
      : 'image/jpeg'
  const dataUrl = await new Promise((resolve, reject) => {
    const fr = new FileReader()
    fr.onload = () => resolve(fr.result)
    fr.onerror = reject
    fr.readAsDataURL(blob)
  })
  const comma = String(dataUrl).indexOf(',')
  const dataBase64 = comma >= 0 ? String(dataUrl).slice(comma + 1) : String(dataUrl)
  return { mimeType, dataBase64 }
}

async function collectInlineImages() {
  const out = []
  const seen = new Set()
  async function add(u) {
    if (!u || seen.has(u)) return
    seen.add(u)
    out.push(await blobUrlToInlinePart(u))
  }
  if (resultUrl.value) await add(resultUrl.value)
  for (const r of referenceImages.value) await add(r.url)
  return out
}

/** 将单次文生图/编辑接口响应转为画布状态 */
function mapStudioGenerateResponse(data) {
  if (!data) {
    return { ok: false, error: '无返回数据' }
  }
  const mime = data.mimeType || 'image/png'
  const remote = typeof data.imageUrl === 'string' ? data.imageUrl : ''
  const url =
    remote || (data.imageBase64 ? `data:${mime};base64,${data.imageBase64}` : '')
  if (!url) {
    return { ok: false, error: '无图像数据' }
  }
  return {
    ok: true,
    url,
    serverImageId: data.imageId != null ? Number(data.imageId) : null,
    favorite: Boolean(data.favorite),
  }
}

function applyGenerationSuccess(aspectKey, partialHint) {
  progressPct.value = 100
  progressMsg.value = '完成'
  const [rw, rh] = aspectToPixels(aspectKey)
  genMeta.value = {
    w: rw,
    h: rh,
    style: styleOptions.find((s) => s.id === styleId.value)?.label || '',
    ago: formatAgo(Date.now()),
  }
  void refreshStudioLibrary()
  if (auth.isAuthenticated) {
    void auth.refreshMe()
  }
  canvasPhase.value = 'done'
  statusHint.value = partialHint && String(partialHint).trim() ? String(partialHint).trim() : '生成完成（已保存至作品库）'
}

async function runGeneration() {
  if (canvasPhase.value === 'generating') return
  if (!auth.isAuthenticated) {
    statusHint.value = '请先登录后再生成（服务端持有 API Key）'
    return
  }
  if (auth.points < nanoBananaPointsForStudioQuality(quality.value)) {
    statusHint.value = INSUFFICIENT_POINTS_TOOLTIP_ZH
    return
  }

  const p = prompt.value.trim()
  const needsRefs = activeTool.value !== 'txt2img'
  if (needsRefs && !resultUrl.value && referenceImages.value.length === 0) {
    statusHint.value = '当前工具需要已有成图或上传参考图'
    return
  }
  if (activeTool.value === 'txt2img' && !p) {
    statusHint.value = '请先输入图片描述'
    return
  }

  canvasPhase.value = 'generating'
  progressPct.value = 0
  const steps = ['解析语义中…', '构图生成中…', '细节渲染中…', '最终优化中…']
  progressMsg.value = steps[0]

  genInterval = window.setInterval(() => {
    progressPct.value = Math.min(96, progressPct.value + 5 + Math.random() * 8)
    const stepIdx = Math.min(steps.length - 1, Math.floor(progressPct.value / 28))
    progressMsg.value = steps[stepIdx]
  }, 400)

  genAbortController = new AbortController()
  const signal = genAbortController.signal

  try {
    const imageSize = qualityToImageSize(quality.value)
    const aspectRatio = aspectId.value
    const aug = buildAugmentedPrompt()
    const ctxOpt = imageSessionContext.value.trim()
      ? { imageSessionContext: imageSessionContext.value }
      : {}

    let data
    let recordedPrompt = ''
    if (activeTool.value === 'txt2img') {
      const promptText = aug || p
      recordedPrompt = promptText
      const { data: d } = await nanoBananaTextToImage(
        { prompt: promptText, aspectRatio, imageSize, ...ctxOpt },
        { signal },
      )
      data = d
    } else {
      const images = await collectInlineImages()
      if (images.length === 0) {
        window.clearInterval(genInterval)
        genInterval = null
        canvasPhase.value = resultUrl.value ? 'done' : 'empty'
        progressPct.value = 0
        progressMsg.value = ''
        statusHint.value = '没有可用的参考图数据'
        return
      }
      const promptText = buildEditPrompt(aug || p || '按参考图完成编辑')
      recordedPrompt = promptText
      const { data: d } = await nanoBananaEdit(
        { prompt: promptText, aspectRatio, imageSize, images, ...ctxOpt },
        { signal },
      )
      data = d
    }

    window.clearInterval(genInterval)
    genInterval = null

    const row = mapStudioGenerateResponse(data)
    if (!row.ok) {
      canvasPhase.value = resultUrl.value ? 'done' : 'empty'
      progressPct.value = 0
      progressMsg.value = ''
      statusHint.value = row.error || '生成失败'
      return
    }

    resultUrl.value = row.url
    serverImageId.value = row.serverImageId != null ? Number(row.serverImageId) : null
    resultFavorite.value = Boolean(row.favorite)
    applyGenerationSuccess(aspectId.value, null)
    appendStudioImageContext(TOOL_LABEL_ZH[activeTool.value] || activeTool.value, recordedPrompt)
  } catch (e) {
    window.clearInterval(genInterval)
    genInterval = null
    const canceled =
      e?.code === 'ERR_CANCELED' || e?.name === 'CanceledError' || e?.name === 'AbortError'
    if (canceled) {
      statusHint.value = '已停止生成'
    } else {
      statusHint.value = getAxiosErrorMessage(e)
    }
    if (canvasPhase.value === 'generating') {
      canvasPhase.value = resultUrl.value ? 'done' : 'empty'
    }
    progressPct.value = 0
    progressMsg.value = ''
  } finally {
    genAbortController = null
  }
}

function aspectToPixels(id) {
  const base = quality.value === 'uhd' ? 1280 : quality.value === 'hd' ? 1024 : 768
  const a = aspects.find((x) => x.id === id) || aspects[0]
  const ratio = a.w / a.h
  if (ratio >= 1) return [base, Math.round(base / ratio)]
  return [Math.round(base * ratio), base]
}

function stopGeneration() {
  genAbortController?.abort()
  genAbortController = null
  if (genInterval) window.clearInterval(genInterval)
  genInterval = null
  if (canvasPhase.value === 'generating') {
    canvasPhase.value = resultUrl.value ? 'done' : 'empty'
    progressPct.value = 0
    progressMsg.value = ''
    statusHint.value = '已停止生成'
  }
}

function cancelEdit() {
  canvasPhase.value = resultUrl.value ? 'done' : 'empty'
  statusHint.value = '已取消编辑'
}

function confirmEditGenerate() {
  if (!prompt.value.trim() && canvasPhase.value === 'edit-inpaint') {
    prompt.value = '按涂抹区域优化细节，保持其余部分不变'
  }
  if (!prompt.value.trim() && canvasPhase.value === 'edit-outpaint') {
    prompt.value = '智能扩展画面边界，风格与内容自然衔接'
  }
  void runGeneration()
}

function setInpaintBrushPreset(preset) {
  inpaintBrushPreset.value = preset
  inpaintBrush.value = preset === 'sm' ? 14 : preset === 'lg' ? 48 : 28
}

function rangeBubbleLeftPct(value, min, max) {
  const v = Math.min(max, Math.max(min, Number(value)))
  const pct = ((v - min) / (max - min)) * 100
  return `${pct}%`
}

function restoreDefaults() {
  aspectId.value = '1:1'
  styleId.value = 'realistic'
  styleTargetId.value = 'illustration'
  fidelity.value = 7
  quality.value = 'hd'
  refStrength.value = 62
  inpaintStrength.value = 45
  outpaintScale.value = '1.5'
  enhanceRes.value = '2k'
  denoise.value = 40
  stylePreserve.value = true
  inpaintBrushPreset.value = 'md'
  inpaintBrush.value = 28
  imageSessionContext.value = ''
  statusHint.value = '参数已恢复默认'
}

watch(enhanceRes, (v) => {
  if (v === '8k') enhanceRes.value = '4k'
})

function savePreset() {
  const name = window.prompt('预设名称', `预设 ${presets.value.length + 1}`)
  if (!name) return
  presets.value.unshift({
    name,
    aspectId: aspectId.value,
    styleId: styleId.value,
    fidelity: fidelity.value,
    quality: quality.value,
  })
  persistPresets()
  statusHint.value = `已保存预设「${name}」`
}

function applyPreset(p) {
  aspectId.value = p.aspectId
  styleId.value = p.styleId
  fidelity.value = p.fidelity
  quality.value = p.quality
}

function loadHistoryThumb(item) {
  resultUrl.value = item.url
  canvasPhase.value = 'done'
  serverImageId.value = item.id != null ? Number(item.id) : null
  resultFavorite.value = Boolean(item.favorite)
  genMeta.value = {
    w: genMeta.value.w,
    h: genMeta.value.h,
    style: item.style,
    ago: formatAgo(item.at),
  }
}

function downloadUrl(url, name = 'uigpt-image.jpg') {
  const a = document.createElement('a')
  a.href = url
  a.download = name
  a.target = '_blank'
  a.rel = 'noopener'
  a.click()
}

watch(panelCollapsed, () => nextTick(() => initInpaintCanvas()))

watch(
  () => auth.isAuthenticated,
  (ok) => {
    if (ok) void refreshStudioLibrary()
    else historyItems.value = []
  },
)

/** 滑块拖动时在圆点上方显示数值（pointerup 清空） */
const sliderTipActive = ref(null)
function onSliderTipDown(name) {
  sliderTipActive.value = name
}
function clearSliderTip() {
  sliderTipActive.value = null
}

function toggleOutpaintDir(key) {
  outpaintDirs.value = { ...outpaintDirs.value, [key]: !outpaintDirs.value[key] }
}

function setOutpaintAllDirs() {
  const allOn = outpaintDirs.value.t && outpaintDirs.value.r && outpaintDirs.value.b && outpaintDirs.value.l
  const v = !allOn
  outpaintDirs.value = { t: v, r: v, b: v, l: v }
}

onMounted(() => {
  loadLocalPresets()
  void refreshStudioLibrary()
  if (auth.isAuthenticated) {
    void auth.refreshMe()
  }
  document.addEventListener('click', onDocClick)
  document.addEventListener('keydown', onDocKeydown)
  document.addEventListener('pointerup', clearSliderTip)
  window.addEventListener('resize', () => {
    if (canvasPhase.value === 'edit-inpaint') initInpaintCanvas()
  })
})

onUnmounted(() => {
  stopGeneration()
  revokeRefs()
  document.removeEventListener('click', onDocClick)
  document.removeEventListener('keydown', onDocKeydown)
  document.removeEventListener('pointerup', clearSliderTip)
})
</script>

<template>
  <div class="ig-root">
    <!-- 顶栏 56px：与对话页一致的毛玻璃；模块内自建导航 -->
    <header class="ig-topbar">
      <div class="ig-topbar-lead">
        <button type="button" class="ig-icon-btn" title="返回主对话" aria-label="返回主对话" @click="router.push('/chat')">
          ←
        </button>
        <span class="ig-topbar-title">图片创作</span>
      </div>
      <div class="ig-topbar-center">
        <button type="button" class="ig-pill-btn" @click="newCreation">新建创作</button>
      </div>
      <div class="ig-topbar-trail">
        <span v-if="auth.isAuthenticated" class="ig-points-chip" title="当前可用积分">积分 {{ auth.points }}</span>
        <div ref="profileWrapRef" class="ig-profile-wrap">
          <button
            type="button"
            class="ig-profile-trigger"
            aria-haspopup="menu"
            :aria-expanded="profileOpen"
            aria-label="个人中心"
            @click="toggleProfile"
          >
            <span class="ig-av">{{ auth.isAuthenticated ? auth.username.slice(0, 1).toUpperCase() : '?' }}</span>
            <span class="ig-profile-txt">个人中心</span>
          </button>
          <ChatProfileDrawer
            :open="profileOpen"
            :is-authenticated="auth.isAuthenticated"
            :username="auth.username"
            @update:open="profileOpen = $event"
            @logout="logout"
            @open-conversation="openConversation"
          />
        </div>
      </div>
    </header>

    <div class="ig-body">
      <!-- 中央画布 + 顶栏工具 + 底栏输入：同一列 -->
      <div class="ig-main-stage">
        <!-- 画布顶部：文生图 / 图生图 等工具 -->
        <!-- 工具条与画布共用同一背景，视觉上连成一块 -->
        <div class="ig-canvas-surface">
        <nav class="ig-tool-strip" aria-label="创作工具">
          <div class="ig-tool-strip-inner">
            <button
              v-for="t in tools"
              :key="t.id"
              type="button"
              class="ig-tool"
              :class="{ 'ig-tool--active': activeTool === t.id }"
              :title="t.label"
              @click="selectTool(t.id)"
            >
              <span class="ig-tool-stack">
                <span class="ig-tool-hit">
                  <span class="ig-tool-indicator" aria-hidden="true" />
                  <svg
                    v-if="t.id === 'txt2img'"
                    class="ig-svg-ic"
                    viewBox="0 0 24 24"
                    fill="none"
                    aria-hidden="true"
                  >
                    <path
                      stroke="currentColor"
                      stroke-width="2"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      d="M12 3v3m0 12v3M5.6 5.6l2.1 2.1m8.6 8.6l2.1 2.1M3 12h3m12 0h3M5.6 18.4l2.1-2.1m8.6-8.6l2.1-2.1"
                    />
                    <path stroke="currentColor" stroke-width="2" stroke-linecap="round" d="M12 8v8M8 12h8" />
                  </svg>
                  <svg
                    v-else-if="t.id === 'img2img'"
                    class="ig-svg-ic"
                    viewBox="0 0 24 24"
                    fill="none"
                    aria-hidden="true"
                  >
                    <rect x="3" y="5" width="18" height="14" rx="2" stroke="currentColor" stroke-width="2" />
                    <path
                      stroke="currentColor"
                      stroke-width="2"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      d="M8 13l2.5-2.5a1.5 1.5 0 012.12 0L15 13l4-4"
                    />
                    <circle cx="9" cy="9" r="1.5" stroke="currentColor" stroke-width="2" />
                  </svg>
                  <svg
                    v-else-if="t.id === 'inpaint'"
                    class="ig-svg-ic"
                    viewBox="0 0 24 24"
                    fill="none"
                    aria-hidden="true"
                  >
                    <path
                      stroke="currentColor"
                      stroke-width="2"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      d="M12 19l6.5-6.5a2.12 2.12 0 10-3-3L9 16"
                    />
                    <path stroke="currentColor" stroke-width="2" stroke-linecap="round" d="M5 21l4-4" />
                  </svg>
                  <svg
                    v-else-if="t.id === 'outpaint'"
                    class="ig-svg-ic"
                    viewBox="0 0 24 24"
                    fill="none"
                    aria-hidden="true"
                  >
                    <path
                      stroke="currentColor"
                      stroke-width="2"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      d="M15 3h4a2 2 0 012 2v4M9 21H5a2 2 0 01-2-2v-4m16 0v4a2 2 0 01-2 2h-4M3 15V9a2 2 0 012-2h4"
                    />
                  </svg>
                  <svg
                    v-else-if="t.id === 'enhance'"
                    class="ig-svg-ic"
                    viewBox="0 0 24 24"
                    fill="none"
                    aria-hidden="true"
                  >
                    <path
                      stroke="currentColor"
                      stroke-width="2"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      d="M13 2L4 14h8l-1 8 9-12h-8l1-8z"
                    />
                  </svg>
                  <svg v-else class="ig-svg-ic" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                    <path
                      stroke="currentColor"
                      stroke-width="2"
                      stroke-linecap="round"
                      d="M7 14a3 3 0 103.5-5.8M17 10a3 3 0 10-3.5 5.8M12 18a3 3 0 10-.2-6"
                    />
                  </svg>
                </span>
                <span class="ig-tool-cap" :class="{ 'ig-tool-cap--on': activeTool === t.id }">{{ t.label }}</span>
              </span>
            </button>
          </div>
        </nav>

        <section class="ig-canvas-wrap">
          <Transition name="ig-canvas-switch">
            <div :key="activeTool + '-' + canvasPhase" class="ig-canvas-flow">
              <!-- 空状态：虚线框 32px 圆角 + 呼吸动画 -->
              <div v-if="canvasPhase === 'empty'" class="ig-empty">
                <div class="ig-empty-frame" @dragover.prevent @drop.prevent="onCanvasDrop">
                  <div class="ig-empty-dash" aria-hidden="true" />
                  <div class="ig-empty-glow" aria-hidden="true" />
                  <div class="ig-empty-icon" aria-hidden="true">
                    <svg class="ig-empty-icon-svg" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                      <path
                        stroke="currentColor"
                        stroke-width="2"
                        stroke-linecap="round"
                        stroke-linejoin="round"
                        d="M12 3v3m0 12v3M5.6 5.6l2.1 2.1m8.6 8.6l2.1 2.1M3 12h3m12 0h3M5.6 18.4l2.1-2.1m8.6-8.6l2.1-2.1"
                      />
                      <path stroke="currentColor" stroke-width="2" stroke-linecap="round" d="M12 8v8M8 12h8" />
                    </svg>
                  </div>
                  <p class="ig-empty-txt">描述你的创意，或拖拽图片到此处</p>
                  <div class="ig-examples">
                    <button v-for="ex in examplePrompts" :key="ex" type="button" class="ig-ex-pill" @click="applyExample(ex)">
                      {{ ex }}
                    </button>
                  </div>
                </div>
              </div>

              <!-- 生成中：单路脉冲 + 进度 -->
              <div v-else-if="canvasPhase === 'generating'" class="ig-gen">
                <div class="ig-pulse-stack" aria-hidden="true">
                  <span class="ig-pulse-ring-item" style="animation-delay: 0s" />
                  <span class="ig-pulse-ring-item" style="animation-delay: 0.35s" />
                  <span class="ig-pulse-ring-item" style="animation-delay: 0.7s" />
                </div>
                <p class="ig-gen-msg">{{ progressMsg }}</p>
                <div class="ig-progress-micro ig-progress-micro--wide">
                  <div class="ig-progress-fill" :style="{ width: `${progressPct}%` }" />
                </div>
                <button type="button" class="ig-stop" @click="stopGeneration">
                  <span class="ig-stop-sq" aria-hidden="true" />
                  停止生成
                </button>
              </div>

              <!-- 完成 / 编辑 -->
              <div
                v-else
                class="ig-result"
                :class="{
                  'ig-result--edit': canvasPhase === 'edit-inpaint' || canvasPhase === 'edit-outpaint',
                }"
              >
                <div class="ig-img-wrap">
                  <div class="ig-img-shell" @dragover.prevent @drop.prevent="onCanvasDrop">
                    <img v-if="resultUrl" :src="resultUrl" alt="生成结果" class="ig-result-img ig-reveal" />
                    <button
                      v-if="canvasPhase === 'done' && serverImageId != null"
                      type="button"
                      class="ig-result-fav"
                      :class="{ 'ig-result-fav--on': resultFavorite }"
                      :disabled="favBusyId === serverImageId"
                      title="收藏"
                      aria-label="收藏"
                      @click.stop="toggleCanvasFavorite"
                    >
                      <svg class="ig-result-fav-svg" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                        <path
                          stroke="currentColor"
                          stroke-width="2"
                          stroke-linejoin="round"
                          d="M12 3l2.9 6.28 6.87.69-5.18 4.77 1.43 6.74L12 17.77 5.98 21.48l1.43-6.74L2.23 9.97l6.87-.69L12 3z"
                        />
                      </svg>
                    </button>
                    <canvas
                      v-if="canvasPhase === 'edit-inpaint'"
                      ref="inpaintCanvasRef"
                      class="ig-inpaint-cv"
                      @mousedown="drawing = true"
                      @mouseup="drawing = false"
                      @mouseleave="drawing = false"
                      @mousemove="paintInpaint"
                    />
                    <div v-if="canvasPhase === 'edit-outpaint'" class="ig-outpaint-ui">
                      <span class="ig-outpaint-hint">虚线框示意扩展区域 · 参数在右侧面板调整</span>
                      <div class="ig-outpaint-box" />
                    </div>
                    <Transition name="ig-fade">
                      <div v-if="canvasPhase === 'done' && resultUrl" class="ig-float-tools">
                        <button type="button" class="ig-ft" title="下载" @click="downloadUrl(resultUrl)">
                          <svg class="ig-ft-svg" viewBox="0 0 24 24" fill="none">
                            <path
                              stroke="currentColor"
                              stroke-width="2"
                              stroke-linecap="round"
                              stroke-linejoin="round"
                              d="M12 3v12m0 0l-4-4m4 4l4-4M5 19h14"
                            />
                          </svg>
                        </button>
                        <button
                          type="button"
                          class="ig-ft"
                          title="预览"
                          aria-label="预览"
                          @click="openFullscreenImagePreview(resultUrl)"
                        >
                          <svg class="ig-ft-svg" viewBox="0 0 24 24" fill="none">
                            <path
                              stroke="currentColor"
                              stroke-width="2"
                              stroke-linecap="round"
                              d="M2 12s4-7 10-7 10 7 10 7-4 7-10 7S2 12 2 12z"
                            />
                            <circle cx="12" cy="12" r="3" stroke="currentColor" stroke-width="2" />
                          </svg>
                        </button>
                        <button
                          type="button"
                          class="ig-ft"
                          :disabled="studioGenInsufficientPoints"
                          :title="studioGenInsufficientPoints ? INSUFFICIENT_POINTS_TOOLTIP_ZH : '重新生成'"
                          aria-label="重新生成"
                          @click="runGeneration"
                        >
                          <svg class="ig-ft-svg" viewBox="0 0 24 24" fill="none">
                            <path
                              stroke="currentColor"
                              stroke-width="2"
                              stroke-linecap="round"
                              stroke-linejoin="round"
                              d="M21 12a9 9 0 11-3.26-6.94M21 3v6h-6"
                            />
                          </svg>
                        </button>
                        <button type="button" class="ig-ft" title="局部重绘" @click="selectTool('inpaint')">
                          <svg class="ig-ft-svg" viewBox="0 0 24 24" fill="none">
                            <path
                              stroke="currentColor"
                              stroke-width="2"
                              stroke-linecap="round"
                              stroke-linejoin="round"
                              d="M12 19l6.5-6.5a2.12 2.12 0 10-3-3L9 16M5 21l4-4"
                            />
                          </svg>
                        </button>
                        <button type="button" class="ig-ft" title="智能扩图" @click="selectTool('outpaint')">
                          <svg class="ig-ft-svg" viewBox="0 0 24 24" fill="none">
                            <path
                              stroke="currentColor"
                              stroke-width="2"
                              stroke-linecap="round"
                              stroke-linejoin="round"
                              d="M15 3h4a2 2 0 012 2v4M9 21H5a2 2 0 01-2-2v-4m16 0v4a2 2 0 01-2 2h-4M3 15V9a2 2 0 012-2h4"
                            />
                          </svg>
                        </button>
                      </div>
                    </Transition>
                  </div>
                </div>
                <p v-if="canvasPhase === 'done' && genMeta" class="ig-meta">
                  {{ genMeta.w }}×{{ genMeta.h }} · {{ genMeta.style }} · {{ genMeta.ago }}
                </p>

                <div v-if="canvasPhase === 'edit-inpaint'" class="ig-edit-toolbar">
                  <label class="ig-mini-label">画笔 {{ inpaintBrush }}px</label>
                  <input v-model.number="inpaintBrush" type="range" min="8" max="72" class="ig-range" />
                  <button type="button" class="ig-mini-btn" @click="clearInpaintMask">清除涂抹</button>
                </div>
              </div>
            </div>
          </Transition>
        </section>
        </div>

        <template v-if="showBottomPrompt">
          <div class="ig-compose-split" aria-hidden="true" />
          <footer class="ig-dock">
            <div v-if="referenceImages.length && activeTool === 'img2img'" class="ig-ref-strip">
              <div v-for="(r, i) in referenceImages" :key="i" class="ig-ref-item">
                <img :src="r.url" alt="" />
                <button type="button" class="ig-ref-x" @click="removeRef(i)">×</button>
              </div>
            </div>
            <div class="ig-dock-inner">
              <input ref="fileInputRef" type="file" class="ig-hidden-input" accept="image/*" multiple @change="onFilesSelected" />
              <button type="button" class="ig-attach" title="上传参考图" aria-label="上传参考图" @click="triggerImport">
                <svg class="ig-attach-svg" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path
                    stroke="currentColor"
                    stroke-width="2"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    d="M21.44 11.05l-9.19 9.19a5 5 0 01-7.07-7.07l9.19-9.19a3 3 0 014.24 4.24l-9.2 9.19a1 1 0 01-1.41-1.41l8.79-8.78"
                  />
                </svg>
              </button>
              <div class="ig-prompt-composer">
                <textarea
                  v-model="prompt"
                  class="ig-textarea ig-textarea--dock"
                  rows="1"
                  :placeholder="
                    activeTool === 'img2img'
                      ? '描述你想如何修改这张图片…'
                      : '描述你想要的画面，越详细效果越好…'
                  "
                />
                <button
                  type="button"
                  class="ig-prompt-opt"
                  :disabled="
                    promptOptimizing || canvasPhase === 'generating' || !prompt.trim()
                  "
                  title="提示词优化"
                  aria-label="提示词优化"
                  @click="optimizePrompt"
                >
                  <svg class="ig-prompt-opt-ic" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                    <path
                      stroke="currentColor"
                      stroke-width="2"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      d="M12 3v2m0 14v2M4.6 4.6l1.4 1.4m12 12l1.4 1.4M3 12h2m14 0h2M4.6 19.4l1.4-1.4m12-12l1.4-1.4"
                    />
                    <path
                      fill="currentColor"
                      d="M12 8.5l1.2 2.8 3 .4-2.2 2 0.6 3L12 15.9 9.4 17l0.6-3-2.2-2 3-.4z"
                      opacity="0.9"
                    />
                  </svg>
                  <span class="ig-prompt-opt-cap">{{ promptOptimizing ? '…' : '优化' }}</span>
                </button>
              </div>
              <button
                type="button"
                class="ig-send"
                :disabled="canvasPhase === 'generating' || studioGenInsufficientPoints"
                :title="studioGenInsufficientPoints ? INSUFFICIENT_POINTS_TOOLTIP_ZH : undefined"
                aria-label="生成"
                @click="runGeneration"
              >
                <svg class="ig-send-svg" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path
                    stroke="currentColor"
                    stroke-width="2.2"
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    d="M5 12h14M13 6l6 6-6 6"
                  />
                </svg>
              </button>
            </div>
          </footer>
        </template>

        <template v-else-if="canvasPhase === 'edit-inpaint' || canvasPhase === 'edit-outpaint'">
          <div class="ig-compose-split" aria-hidden="true" />
          <footer class="ig-dock ig-dock--edit">
            <p class="ig-edit-tip">
              {{
                canvasPhase === 'edit-inpaint'
                  ? '涂抹需要重绘的区域，然后在上方输入修改描述（或使用默认）'
                  : '确认扩展方向与比例后生成新画面'
              }}
            </p>
            <div class="ig-edit-actions">
              <button type="button" class="ig-btn-secondary" @click="cancelEdit">取消编辑</button>
              <button
                type="button"
                class="ig-btn-primary"
                :disabled="studioGenInsufficientPoints"
                :title="studioGenInsufficientPoints ? INSUFFICIENT_POINTS_TOOLTIP_ZH : undefined"
                @click="confirmEditGenerate"
              >
                确认生成
              </button>
            </div>
          </footer>
        </template>
      </div>

      <!-- 右侧参数：280px / 卡片分组 -->
      <aside class="ig-panel" :class="{ 'ig-panel--collapsed': panelCollapsed }">
        <button type="button" class="ig-panel-toggle" @click="panelCollapsed = !panelCollapsed">
          {{ panelCollapsed ? '◀' : '▶' }}
        </button>
        <div v-show="!panelCollapsed" class="ig-panel-scroll ig-panel-scroll--styled">
          <!-- OUTPUT：比例网格（Banana / Gemini 走 aspectRatio） -->
          <section class="ig-pgroup">
            <p class="ig-pgroup-title">OUTPUT</p>
            <div class="ig-pgroup-card">
              <div class="ig-aspect-grid">
                <button
                  v-for="a in aspects"
                  :key="a.id"
                  type="button"
                  class="ig-aspect-cell"
                  :class="{ 'ig-aspect-cell--on': aspectId === a.id }"
                  :title="a.label"
                  @click="aspectId = a.id"
                >
                  <span class="ig-aspect-icon" aria-hidden="true">
                    <svg v-if="a.id === '1:1'" viewBox="0 0 36 24" class="ig-aspect-svg">
                      <rect x="10" y="5" width="16" height="16" rx="1.5" fill="none" stroke="currentColor" stroke-width="1.4" />
                    </svg>
                    <svg v-else-if="a.id === '9:16'" viewBox="0 0 36 24" class="ig-aspect-svg">
                      <rect x="13" y="2" width="10" height="20" rx="1.5" fill="none" stroke="currentColor" stroke-width="1.4" />
                    </svg>
                    <svg v-else-if="a.id === '16:9'" viewBox="0 0 36 24" class="ig-aspect-svg">
                      <rect x="3" y="9" width="30" height="8" rx="1.5" fill="none" stroke="currentColor" stroke-width="1.4" />
                    </svg>
                    <svg v-else-if="a.id === '3:4'" viewBox="0 0 36 24" class="ig-aspect-svg">
                      <rect x="12" y="3" width="12" height="18" rx="1.5" fill="none" stroke="currentColor" stroke-width="1.4" />
                    </svg>
                    <svg v-else-if="a.id === '4:3'" viewBox="0 0 36 24" class="ig-aspect-svg">
                      <rect x="4" y="7" width="28" height="12" rx="1.5" fill="none" stroke="currentColor" stroke-width="1.4" />
                    </svg>
                    <svg v-else viewBox="0 0 36 24" class="ig-aspect-svg">
                      <rect x="1" y="10" width="34" height="5" rx="1.5" fill="none" stroke="currentColor" stroke-width="1.4" />
                    </svg>
                  </span>
                  <span class="ig-aspect-lbl">{{ a.label }}</span>
                </button>
              </div>
              <p v-if="aspectId === '21:9'" class="ig-banana-note">
                超宽比例在 Banana 上渲染较慢，生成时间约为常规的 <strong>2–3 倍</strong>。
              </p>
            </div>
          </section>

          <!-- STYLE -->
          <section class="ig-pgroup">
            <p class="ig-pgroup-title">STYLE</p>
            <div class="ig-pgroup-card">
              <div class="ig-style-scroller">
                <button
                  v-for="s in styleOptions"
                  :key="s.id"
                  type="button"
                  class="ig-style-card"
                  :class="{ 'ig-style-card--on': styleId === s.id }"
                  @click="styleId = s.id"
                >
                  <span class="ig-style-swatch" :style="{ background: s.swatch }" />
                  <span class="ig-style-name">{{ s.label }}</span>
                </button>
              </div>
              <p class="ig-model-hint ig-model-hint--tight">
                Gemini：写实与插画表现最佳；3D 渲染建议配合下方「高清」画质。
              </p>
            </div>
          </section>

          <!-- FIDELITY -->
          <section class="ig-pgroup">
            <p class="ig-pgroup-title">FIDELITY</p>
            <div class="ig-pgroup-card">
              <div class="ig-slider-labels ig-slider-labels--sm">
                <span>创意发散</span><span>严格还原</span>
              </div>
              <div class="ig-brand-slider ig-brand-slider--tip" @pointerdown="onSliderTipDown('fidelity')">
                <output
                  v-show="sliderTipActive === 'fidelity'"
                  class="ig-slider-bubble ig-slider-bubble--brand"
                  :style="{ left: rangeBubbleLeftPct(fidelity, 1, 10) }"
                  >{{ fidelity }}</output
                >
                <input v-model.number="fidelity" type="range" min="1" max="10" class="ig-brand-slider-input" />
              </div>
              <ul class="ig-fidelity-legend">
                <li><strong>1–3</strong>：AI 自由发挥，适合概念探索</li>
                <li><strong>4–7</strong>：平衡模式，适合参考图二创（推荐）</li>
                <li><strong>8–10</strong>：严格还原手稿等，可能削弱创意优化</li>
              </ul>
            </div>
          </section>

          <!-- QUALITY -->
          <section class="ig-pgroup">
            <p class="ig-pgroup-title">QUALITY</p>
            <div class="ig-pgroup-card">
              <div class="ig-quality-pills">
                <button type="button" class="ig-qpill" :class="{ 'ig-qpill--on': quality === 'std' }" @click="quality = 'std'">标准</button>
                <button type="button" class="ig-qpill" :class="{ 'ig-qpill--on': quality === 'hd' }" @click="quality = 'hd'">高清</button>
                <button type="button" class="ig-qpill" :class="{ 'ig-qpill--on': quality === 'uhd' }" @click="quality = 'uhd'">超清</button>
              </div>
              <p class="ig-quality-res">标准 ≈ 1024px · fastest，适合快速预览</p>
              <p class="ig-quality-res">高清 ≈ 1536px · balanced，推荐日常</p>
              <p class="ig-quality-res">超清 ≈ 2048px · 细节优先；Banana 队列可能 <strong>30s+</strong></p>
            </div>
          </section>

          <!-- TOOL：折叠动画切换专属参数 -->
          <section class="ig-pgroup">
            <p class="ig-pgroup-title">TOOL</p>
            <Transition name="ig-acc" mode="out-in">
              <div v-if="activeTool === 'img2img'" key="i2i" class="ig-pgroup-card ig-tool-card">
                <p class="ig-micro-label">参考强度（0–100%）</p>
                <div class="ig-brand-slider ig-brand-slider--tip" @pointerdown="onSliderTipDown('ref')">
                  <output
                    v-show="sliderTipActive === 'ref'"
                    class="ig-slider-bubble ig-slider-bubble--brand"
                    :style="{ left: rangeBubbleLeftPct(refStrength, 0, 100) }"
                    >{{ refStrength }}%</output
                  >
                  <input v-model.number="refStrength" type="range" min="0" max="100" class="ig-brand-slider-input" />
                </div>
                <p class="ig-model-hint">Banana：参考强度 <strong>60–80%</strong> 通常效果最佳。</p>
              </div>
              <div v-else-if="activeTool === 'inpaint'" key="inp" class="ig-pgroup-card ig-tool-card">
                <p class="ig-micro-label">画笔大小</p>
                <div class="ig-brush-presets">
                  <button
                    type="button"
                    class="ig-brush-dot"
                    :class="{ 'ig-brush-dot--on': inpaintBrushPreset === 'sm' }"
                    title="小"
                    aria-label="小画笔"
                    @click="setInpaintBrushPreset('sm')"
                  >
                    <span class="ig-brush-dot-inner ig-brush-dot-inner--sm" />
                  </button>
                  <button
                    type="button"
                    class="ig-brush-dot"
                    :class="{ 'ig-brush-dot--on': inpaintBrushPreset === 'md' }"
                    title="中"
                    aria-label="中画笔"
                    @click="setInpaintBrushPreset('md')"
                  >
                    <span class="ig-brush-dot-inner ig-brush-dot-inner--md" />
                  </button>
                  <button
                    type="button"
                    class="ig-brush-dot"
                    :class="{ 'ig-brush-dot--on': inpaintBrushPreset === 'lg' }"
                    title="大"
                    aria-label="大画笔"
                    @click="setInpaintBrushPreset('lg')"
                  >
                    <span class="ig-brush-dot-inner ig-brush-dot-inner--lg" />
                  </button>
                </div>
                <p class="ig-micro-label ig-micro-label--sp">重绘幅度</p>
                <div class="ig-brand-slider ig-brand-slider--tip" @pointerdown="onSliderTipDown('inpaint')">
                  <output
                    v-show="sliderTipActive === 'inpaint'"
                    class="ig-slider-bubble ig-slider-bubble--brand"
                    :style="{ left: rangeBubbleLeftPct(inpaintStrength, 0, 100) }"
                    >{{ inpaintStrength }}%</output
                  >
                  <input v-model.number="inpaintStrength" type="range" min="0" max="100" class="ig-brand-slider-input" />
                </div>
              </div>
              <div v-else-if="activeTool === 'outpaint'" key="out" class="ig-pgroup-card ig-tool-card">
                <p class="ig-micro-label">扩展方向（24px）</p>
                <div class="ig-dir-btns ig-dir-btns--24">
                  <button
                    type="button"
                    class="ig-dir-btn"
                    :class="{ 'ig-dir-btn--on': outpaintDirs.t }"
                    title="向上"
                    aria-label="向上"
                    @click="toggleOutpaintDir('t')"
                  >
                    <svg viewBox="0 0 24 24" fill="none" class="ig-dir-svg">
                      <path stroke="currentColor" stroke-width="2" stroke-linecap="round" d="M12 19V5M12 5l-5 5M12 5l5 5" />
                    </svg>
                  </button>
                  <button
                    type="button"
                    class="ig-dir-btn"
                    :class="{ 'ig-dir-btn--on': outpaintDirs.r }"
                    title="向右"
                    aria-label="向右"
                    @click="toggleOutpaintDir('r')"
                  >
                    <svg viewBox="0 0 24 24" fill="none" class="ig-dir-svg">
                      <path stroke="currentColor" stroke-width="2" stroke-linecap="round" d="M5 12h14M19 12l-5-5M19 12l-5 5" />
                    </svg>
                  </button>
                  <button
                    type="button"
                    class="ig-dir-btn"
                    :class="{ 'ig-dir-btn--on': outpaintDirs.b }"
                    title="向下"
                    aria-label="向下"
                    @click="toggleOutpaintDir('b')"
                  >
                    <svg viewBox="0 0 24 24" fill="none" class="ig-dir-svg">
                      <path stroke="currentColor" stroke-width="2" stroke-linecap="round" d="M12 5v14M12 19l-5-5M12 19l5-5" />
                    </svg>
                  </button>
                  <button
                    type="button"
                    class="ig-dir-btn"
                    :class="{ 'ig-dir-btn--on': outpaintDirs.l }"
                    title="向左"
                    aria-label="向左"
                    @click="toggleOutpaintDir('l')"
                  >
                    <svg viewBox="0 0 24 24" fill="none" class="ig-dir-svg">
                      <path stroke="currentColor" stroke-width="2" stroke-linecap="round" d="M19 12H5M5 12l5-5M5 12l5 5" />
                    </svg>
                  </button>
                  <button type="button" class="ig-dir-btn ig-dir-btn--all" title="四向全选/清除" aria-label="四向" @click="setOutpaintAllDirs">
                    <svg viewBox="0 0 24 24" fill="none" class="ig-dir-svg">
                      <path
                        stroke="currentColor"
                        stroke-width="2"
                        stroke-linecap="round"
                        stroke-linejoin="round"
                        d="M15 3h4a2 2 0 012 2v4M9 21H5a2 2 0 01-2-2v-4m16 0v4a2 2 0 01-2 2h-4M3 15V9a2 2 0 012-2h4"
                      />
                    </svg>
                  </button>
                </div>
                <p class="ig-micro-label ig-micro-label--sp">扩展比例</p>
                <div class="ig-scale-pills">
                  <button type="button" class="ig-spill" :class="{ 'ig-spill--on': outpaintScale === '1' }" @click="outpaintScale = '1'">
                    1×
                  </button>
                  <button type="button" class="ig-spill" :class="{ 'ig-spill--on': outpaintScale === '1.5' }" @click="outpaintScale = '1.5'">
                    1.5×
                  </button>
                  <button type="button" class="ig-spill" :class="{ 'ig-spill--on': outpaintScale === '2' }" @click="outpaintScale = '2'">
                    2×
                  </button>
                </div>
              </div>
              <div v-else-if="activeTool === 'enhance'" key="en" class="ig-pgroup-card ig-tool-card">
                <p class="ig-micro-label">目标分辨率（Banana 当前最高 4K）</p>
                <div class="ig-quality-pills ig-quality-pills--sm">
                  <button type="button" class="ig-qpill" :class="{ 'ig-qpill--on': enhanceRes === '2k' }" @click="enhanceRes = '2k'">
                    2K
                  </button>
                  <button type="button" class="ig-qpill" :class="{ 'ig-qpill--on': enhanceRes === '4k' }" @click="enhanceRes = '4k'">
                    4K
                  </button>
                </div>
                <p class="ig-micro-label ig-micro-label--sp">降噪</p>
                <div class="ig-brand-slider ig-brand-slider--tip" @pointerdown="onSliderTipDown('denoise')">
                  <output
                    v-show="sliderTipActive === 'denoise'"
                    class="ig-slider-bubble ig-slider-bubble--brand"
                    :style="{ left: rangeBubbleLeftPct(denoise, 0, 100) }"
                    >{{ denoise }}%</output
                  >
                  <input v-model.number="denoise" type="range" min="0" max="100" class="ig-brand-slider-input" />
                </div>
              </div>
              <div v-else-if="activeTool === 'style'" key="st" class="ig-pgroup-card ig-tool-card">
                <label class="ig-toggle-row">
                  <span class="ig-toggle-lab">保留结构</span>
                  <button
                    type="button"
                    class="ig-switch"
                    :class="{ 'ig-switch--on': stylePreserve }"
                    role="switch"
                    :aria-checked="stylePreserve"
                    @click="stylePreserve = !stylePreserve"
                  >
                    <span class="ig-switch-knob" />
                  </button>
                </label>
                <p class="ig-micro-label ig-micro-label--sp">目标风格</p>
                <div class="ig-style-scroller ig-style-scroller--tool">
                  <button
                    v-for="s in styleOptions"
                    :key="'tg-' + s.id"
                    type="button"
                    class="ig-style-card"
                    :class="{ 'ig-style-card--on': styleTargetId === s.id }"
                    @click="styleTargetId = s.id"
                  >
                    <span class="ig-style-swatch" :style="{ background: s.swatch }" />
                    <span class="ig-style-name">{{ s.label }}</span>
                  </button>
                </div>
              </div>
              <div v-else key="none" class="ig-pgroup-card ig-tool-card ig-tool-card--muted">当前工具无额外参数</div>
            </Transition>
          </section>

          <div class="ig-quick">
            <button type="button" class="ig-text-link" @click="restoreDefaults">恢复默认</button>
            <button type="button" class="ig-text-link" @click="savePreset">保存为预设</button>
          </div>
          <p class="ig-powered-by">Powered by Gemini / Banana</p>
          <div v-if="presets.length" class="ig-presets">
            <button v-for="(pr, i) in presets" :key="i" type="button" class="ig-preset-chip" @click="applyPreset(pr)">
              {{ pr.name }}
            </button>
          </div>

          <div class="ig-hist-block">
            <p class="ig-recent-title">RECENT</p>
            <div v-if="!historyItems.length" class="ig-recent-empty">
              <span v-for="n in 4" :key="n" class="ig-recent-ph" />
              <p class="ig-recent-empty-txt">暂无作品</p>
            </div>
            <div v-else class="ig-hist-strip">
              <button
                v-for="h in historyItems.slice(0, 12)"
                :key="h.id"
                type="button"
                class="ig-hist-thumb"
                @click="loadHistoryThumb(h)"
              >
                <span class="ig-hist-thumb-wrap">
                  <img :src="h.url" alt="" />
                  <span class="ig-hist-dl" role="presentation" @click.stop="downloadUrl(h.url, `recent-${h.id}.png`)">
                    <svg viewBox="0 0 24 24" fill="none" class="ig-hist-dl-svg" aria-hidden="true">
                      <path
                        stroke="currentColor"
                        stroke-width="2"
                        stroke-linecap="round"
                        stroke-linejoin="round"
                        d="M12 3v12m0 0l-4-4m4 4l4-4M5 19h14"
                      />
                    </svg>
                  </span>
                </span>
              </button>
            </div>
          </div>
        </div>
      </aside>
    </div>

    <div class="ig-status">{{ statusHint }}</div>

    <FullscreenImagePreview v-model="fullscreenPreviewOpen" :src="fullscreenPreviewUrl" />
  </div>
</template>

<style scoped>
.ig-root {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--chat-shell-bg);
  color: var(--chat-fg);
  overflow: hidden;
}

/* ---------- 顶栏 ---------- */
.ig-topbar {
  flex-shrink: 0;
  height: 56px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  align-items: center;
  gap: 10px;
  padding: 0 max(12px, env(safe-area-inset-right)) 0 max(12px, env(safe-area-inset-left));
  border-bottom: 1px solid var(--chat-border);
  background: var(--chat-topbar-bg);
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);
  position: relative;
  z-index: 30;
}

.ig-topbar-lead {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.ig-topbar-title {
  font-size: 0.9375rem;
  font-weight: 700;
  color: var(--chat-fg-strong);
  letter-spacing: 0.02em;
}

.ig-topbar-center {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
}

.ig-topbar-trail {
  justify-self: end;
  display: flex;
  align-items: center;
  gap: 8px;
}

.ig-points-chip {
  flex-shrink: 0;
  padding: 4px 10px;
  border-radius: 999px;
  border: 1px solid var(--chat-border-strong);
  background: var(--ig-glass-bg);
  color: var(--chat-muted);
  font-size: 0.75rem;
  font-weight: 600;
}

.ig-icon-btn {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  border: 1px solid var(--chat-border-strong);
  background: var(--ig-glass-bg);
  backdrop-filter: blur(16px);
  color: var(--chat-fg);
  cursor: pointer;
  transition:
    transform 0.15s ease,
    background 0.15s ease;
}
.ig-icon-btn:hover {
  transform: scale(1.04);
  background: var(--chat-btn-bg-hover);
}

.ig-pill-btn {
  padding: 8px 14px;
  border-radius: 999px;
  border: 1px solid var(--ig-glass-border);
  background: var(--ig-glass-bg);
  backdrop-filter: blur(16px);
  color: var(--chat-fg);
  font-size: 0.78rem;
  font-weight: 600;
  cursor: pointer;
  transition:
    background 0.15s ease,
    border-color 0.15s ease,
    transform 0.15s ease;
}
.ig-pill-btn:hover {
  background: var(--ig-brand-softer);
  border-color: var(--ig-brand-border);
  transform: translateY(-1px);
}

.ig-profile-wrap {
  position: relative;
}
.ig-profile-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px 6px 6px;
  border-radius: 999px;
  border: 1px solid var(--chat-profile-border);
  background: var(--chat-profile-bg);
  cursor: pointer;
  color: var(--chat-fg);
  font-size: 0.8125rem;
}
.ig-av {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--ig-brand), var(--ig-brand-hover));
  color: var(--ig-send-fg);
  font-weight: 700;
  font-size: 0.75rem;
  display: flex;
  align-items: center;
  justify-content: center;
}

.ig-hidden-input {
  display: none;
}

/* ---------- 三栏主体（Grid/Flex 布局与尺寸标注） ---------- */
/* 整块工作区一条渐变：中间列 + 右侧参数 + 底部输入共用，避免色块拼接 */
.ig-body {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: row;
  overflow: hidden;
  background: var(--ig-workspace-bg);
}

/* 中央列：画布 + 分隔线 + 输入区同一视觉柱 */
.ig-main-stage {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: transparent;
}

/* 画布与输入区分隔：1px / 透明度 6% */
.ig-compose-split {
  flex-shrink: 0;
  height: 1px;
  background: var(--ig-divider-soft);
}

/* 画布主区域：工具条 + 滚动画布（底图由 .ig-body 提供） */
.ig-canvas-surface {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: transparent;
}

/* —— 画布顶部工具条：背景透明，融入画布 —— */
.ig-tool-strip {
  flex-shrink: 0;
  background: transparent;
  border-bottom: none;
}

.ig-tool-strip-inner {
  display: flex;
  flex-direction: row;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: center;
  gap: 12px;
  padding: 12px 16px;
}

.ig-tool {
  width: auto;
  flex-shrink: 0;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  display: flex;
  justify-content: center;
}

.ig-tool-stack {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px; /* 图标区 → 标签 8px */
  transition:
    transform 0.2s ease,
    color 0.2s ease;
}

.ig-tool-hit {
  position: relative;
  width: 48px;
  height: 48px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  transition:
    background 0.2s ease,
    transform 0.2s ease,
    color 0.2s ease;
}

/* 顶栏选中态：底部白色短条（替代左侧竖条） */
.ig-tool-indicator {
  position: absolute;
  left: 50%;
  bottom: 5px;
  width: 20px;
  height: 3px;
  margin-left: -10px;
  border-radius: 2px;
  background: #fff;
  opacity: 0;
  transition: opacity 0.2s ease;
}

.ig-svg-ic {
  width: 20px;
  height: 20px;
  color: var(--ig-tool-default-icon);
  transition: color 0.2s ease;
}

.ig-tool:hover .ig-tool-hit {
  background: rgba(255, 255, 255, 0.05);
}

.ig-tool:hover .ig-svg-ic {
  color: color-mix(in srgb, var(--chat-fg) 88%, transparent);
}

.ig-tool--active .ig-tool-stack {
  transform: translateY(2px);
}

.ig-tool--active .ig-tool-hit {
  background: linear-gradient(135deg, #10b981, #34d399);
}

.ig-tool--active .ig-tool-indicator {
  opacity: 1;
}

.ig-tool--active .ig-svg-ic {
  color: #fff;
}

.ig-tool--active:hover .ig-tool-hit {
  background: linear-gradient(135deg, #10b981, #34d399);
}

.ig-tool-cap {
  font-size: 10px;
  line-height: 1.2;
  font-weight: 600;
  color: var(--ig-muted-50);
  max-width: 56px;
  text-align: center;
  transition: color 0.2s ease;
}

.ig-tool-cap--on {
  color: #fff;
}

/* —— 画布区 —— */
.ig-canvas-wrap {
  flex: 1;
  min-width: 0;
  min-height: 0;
  padding: 16px;
  overflow: auto;
  background: transparent;
}

.ig-canvas-flow {
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
}

/* 工具切换：淡入 + blur 4→0 */
.ig-canvas-switch-enter-active,
.ig-canvas-switch-leave-active {
  transition:
    opacity 0.2s ease,
    filter 0.2s ease;
}

.ig-canvas-switch-enter-from,
.ig-canvas-switch-leave-to {
  opacity: 0;
  filter: blur(4px);
}

/* 空状态：圆角 32px；虚线呼吸 3s */
.ig-empty-frame {
  width: min(100%, 720px);
  min-height: min(56vh, 420px);
  border-radius: 32px;
  border: none;
  background: var(--ig-glass-bg);
  backdrop-filter: blur(20px);
  -webkit-backdrop-filter: blur(20px);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 32px 24px;
  text-align: center;
  position: relative;
  overflow: hidden;
  box-shadow: 0 16px 48px color-mix(in srgb, var(--chat-fg-strong) 8%, transparent);
}

.ig-empty-dash {
  position: absolute;
  inset: 0;
  border-radius: 32px;
  border: 2px dashed var(--ig-brand);
  opacity: 0.15;
  pointer-events: none;
  animation: ig-dash-breathe 3s ease-in-out infinite;
}

@keyframes ig-dash-breathe {
  0%,
  100% {
    opacity: 0.1;
  }
  50% {
    opacity: 0.2;
  }
}

.ig-empty-glow {
  position: absolute;
  inset: 22%;
  background: radial-gradient(circle, color-mix(in srgb, var(--ig-brand) 5%, transparent), transparent 65%);
  filter: blur(48px);
  pointer-events: none;
}

.ig-empty-icon {
  position: relative;
  z-index: 1;
  margin-bottom: 10px;
}

.ig-empty-icon-svg {
  width: 48px;
  height: 48px;
  display: block;
  color: var(--ig-brand);
}

.ig-empty-txt {
  margin: 0 0 16px;
  font-size: 14px;
  line-height: 1.6;
  color: var(--ig-muted-60);
  position: relative;
  z-index: 1;
}

.ig-examples {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
  position: relative;
  z-index: 1;
  max-width: 640px;
}

/* 设计向预设：药丸按钮，文案较长时可折行 */
.ig-ex-pill {
  max-width: 100%;
  min-height: 32px;
  padding: 6px 14px;
  border-radius: 20px;
  border: none;
  background: rgba(255, 255, 255, 0.05);
  color: var(--ig-muted-60);
  font-size: 12px;
  font-weight: 600;
  line-height: 1.35;
  text-align: center;
  cursor: pointer;
  transition:
    background 0.2s ease,
    color 0.2s ease,
    transform 0.2s ease;
}

.ig-ex-pill:hover {
  background: rgba(255, 255, 255, 0.12);
  color: #fff;
  transform: translateY(-1px);
}

/* —— 生成中：三层同心圆脉冲 —— */
.ig-gen {
  text-align: center;
  padding: 40px;
  position: relative;
}

.ig-pulse-stack {
  position: relative;
  width: 120px;
  height: 120px;
  margin: 0 auto 24px;
}

.ig-pulse-ring-item {
  position: absolute;
  left: 50%;
  top: 50%;
  width: 72px;
  height: 72px;
  margin: -36px 0 0 -36px;
  border-radius: 50%;
  border: 2px solid color-mix(in srgb, var(--ig-brand) 55%, transparent);
  animation: ig-concentric 2.4s ease-out infinite;
  opacity: 0;
}

@keyframes ig-concentric {
  0% {
    transform: scale(0.55);
    opacity: 0.55;
  }
  100% {
    transform: scale(2.15);
    opacity: 0;
  }
}

.ig-gen-msg {
  font-size: 0.9rem;
  font-weight: 600;
  color: var(--chat-fg-strong);
  margin: 0 0 12px;
}

/* 进度条：200×2；品牌色 + 微光 */
.ig-progress-micro {
  width: 200px;
  height: 2px;
  margin: 0 auto 20px;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.08);
  overflow: hidden;
}

.ig-progress-fill {
  height: 100%;
  border-radius: 2px;
  background: linear-gradient(90deg, var(--ig-brand), var(--ig-brand-hover));
  box-shadow: 0 0 14px color-mix(in srgb, var(--ig-brand) 65%, transparent);
  transition: width 0.35s ease;
}

.ig-stop {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 18px;
  border-radius: 12px;
  border: 1px solid color-mix(in srgb, var(--chat-danger-fg) 35%, transparent);
  background: var(--chat-danger-bg);
  color: var(--chat-danger-fg);
  font-weight: 600;
  cursor: pointer;
}

.ig-stop-sq {
  width: 12px;
  height: 12px;
  background: currentColor;
  border-radius: 2px;
}

.ig-progress-micro--wide {
  width: min(360px, 88vw);
}

/* —— 结果区：完成图圆角 20px；阴影 y16 blur48 @15% —— */
.ig-result {
  width: 100%;
  max-width: 960px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

/* 为底部悬浮条留出空间，避免紧贴图片下沿 */
.ig-img-wrap {
  position: relative;
  width: 100%;
  max-width: min(85vw, 820px);
  padding-bottom: 56px;
}

.ig-img-shell {
  position: relative;
  width: 100%;
  border-radius: 20px;
  overflow: hidden;
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.15);
}

.ig-result-fav {
  position: absolute;
  top: 10px;
  right: 10px;
  z-index: 4;
  width: 40px;
  height: 40px;
  border: none;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: #fff;
  background: rgba(0, 0, 0, 0.42);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  box-shadow: 0 4px 14px rgba(0, 0, 0, 0.25);
  transition:
    transform 0.15s ease,
    background 0.15s ease,
    color 0.15s ease;
}

.ig-result-fav:hover:not(:disabled) {
  transform: scale(1.06);
  background: rgba(0, 0, 0, 0.55);
}

.ig-result-fav:disabled {
  opacity: 0.55;
  cursor: wait;
}

.ig-result-fav--on {
  color: #fde047;
  background: color-mix(in srgb, var(--ig-brand) 35%, rgba(0, 0, 0, 0.45));
}

.ig-result-fav-svg {
  width: 20px;
  height: 20px;
  display: block;
}

.ig-result-fav--on .ig-result-fav-svg path {
  fill: currentColor;
  stroke: rgba(0, 0, 0, 0.15);
}

.ig-result-img {
  width: 100%;
  display: block;
  max-height: min(70vh, 640px);
  object-fit: contain;
  background: var(--chat-panel);
}

/* 生成完成：blur 20→0 + scale 1.03→1，0.6s */
.ig-reveal {
  animation: ig-rev 0.6s cubic-bezier(0.22, 1, 0.36, 1) forwards;
}

@keyframes ig-rev {
  from {
    filter: blur(20px);
    transform: scale(1.03);
    opacity: 0.75;
  }
  to {
    filter: blur(0);
    transform: scale(1);
    opacity: 1;
  }
}

.ig-inpaint-cv {
  position: absolute;
  inset: 0;
  cursor: crosshair;
  touch-action: none;
}

.ig-outpaint-ui {
  position: absolute;
  inset: 0;
  pointer-events: none;
  display: flex;
  align-items: center;
  justify-content: center;
}

.ig-outpaint-hint {
  position: absolute;
  top: 12px;
  left: 50%;
  transform: translateX(-50%);
  font-size: 0.72rem;
  color: var(--chat-muted);
  background: var(--ig-glass-bg);
  backdrop-filter: blur(12px);
  padding: 6px 12px;
  border-radius: 999px;
}

.ig-outpaint-box {
  width: 88%;
  height: 88%;
  border: 2px dashed color-mix(in srgb, var(--ig-brand) 40%, transparent);
  border-radius: 16px;
  box-shadow: 0 0 0 4000px color-mix(in srgb, var(--chat-shell-bg) 35%, transparent) inset;
}

/* Hover 显示；底部居中悬浮条：rgba + blur 16；图标区 20px / 间距 16px */
.ig-float-tools {
  position: absolute;
  left: 50%;
  bottom: 4px;
  transform: translateX(-50%);
  display: flex;
  gap: 16px;
  align-items: center;
  padding: 10px 16px;
  border-radius: 12px;
  background: rgba(0, 0, 0, 0.6);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.2s ease;
}

.ig-img-shell:hover .ig-float-tools {
  opacity: 1;
  pointer-events: auto;
}

.ig-ft {
  padding: 0;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: #fff;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition:
    transform 0.15s ease,
    opacity 0.15s ease;
}

.ig-ft:hover {
  transform: scale(1.08);
  opacity: 0.92;
}

.ig-ft-svg {
  width: 20px;
  height: 20px;
  display: block;
}

.ig-meta {
  margin: 0;
  font-size: 0.68rem;
  color: var(--chat-muted);
  opacity: 0.85;
}

.ig-edit-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding: 10px 14px;
  border-radius: 14px;
  background: var(--ig-glass-bg);
  backdrop-filter: blur(16px);
  border: 1px solid var(--chat-border);
}

.ig-mini-label {
  font-size: 0.72rem;
  color: var(--chat-muted);
}

.ig-mini-btn {
  padding: 6px 12px;
  border-radius: 10px;
  border: 1px solid var(--chat-border-strong);
  background: var(--chat-btn-bg);
  cursor: pointer;
  color: var(--chat-fg);
  font-size: 0.75rem;
}

.ig-range {
  width: 180px;
  max-width: 100%;
  accent-color: var(--ig-brand);
}

/* —— 右侧面板：独立底色 #0D0D0F，与画布 1px 极淡分隔 —— */
.ig-panel {
  position: relative;
  width: 280px;
  flex-shrink: 0;
  border-left: 1px solid var(--ig-panel-border);
  background: var(--ig-panel-surface);
  transition:
    width 0.22s ease,
    transform 0.22s ease;
}

.ig-panel--collapsed {
  width: 36px;
}

.ig-panel-toggle {
  position: absolute;
  left: -12px;
  top: 50%;
  transform: translateY(-50%);
  z-index: 2;
  width: 24px;
  height: 48px;
  border-radius: 8px 0 0 8px;
  border: 1px solid var(--chat-border);
  border-right: none;
  background: var(--chat-panel);
  cursor: pointer;
  color: var(--chat-muted);
  font-size: 0.75rem;
}

.ig-panel-scroll {
  padding: 16px 14px 24px;
  max-height: 100%;
  overflow-y: auto;
}

.ig-panel-scroll--styled {
  scrollbar-gutter: stable;
  scrollbar-width: thin;
  scrollbar-color: rgba(255, 255, 255, 0.22) transparent;
}

.ig-panel-scroll--styled::-webkit-scrollbar {
  width: 4px;
}

.ig-panel-scroll--styled::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.22);
  border-radius: 4px;
  transition:
    background 0.2s ease,
    box-shadow 0.2s ease;
}

.ig-panel-scroll--styled:hover {
  scrollbar-color: color-mix(in srgb, var(--ig-brand) 45%, rgba(255, 255, 255, 0.15)) transparent;
}

.ig-panel-scroll--styled:hover::-webkit-scrollbar {
  width: 6px;
}

.ig-panel-scroll--styled:hover::-webkit-scrollbar-thumb {
  background: color-mix(in srgb, var(--ig-brand) 52%, rgba(255, 255, 255, 0.12));
}

/* 组间距 16px */
.ig-pgroup {
  margin-bottom: 16px;
}

/* 组标题：10px / 灰 40% / 大写 / 字距 1px */
.ig-pgroup-title {
  margin: 0 0 8px;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 1px;
  text-transform: uppercase;
  color: var(--ig-muted-40);
}

/* 组内卡片：白 3% / 圆角 12 / 内边距 12 */
.ig-pgroup-card {
  background: var(--ig-card-fill);
  border-radius: 12px;
  padding: 12px;
}

.ig-micro-label {
  margin: 0 0 8px;
  font-size: 10px;
  font-weight: 600;
  color: var(--ig-muted-50);
  letter-spacing: 0.04em;
}

.ig-micro-label--sp {
  margin-top: 12px;
}

/* 出图比例：CSS Grid 2×3；格子 72×48 / 圆角 8 */
.ig-aspect-grid {
  display: grid;
  grid-template-columns: repeat(2, 72px);
  grid-template-rows: repeat(3, auto);
  gap: 10px;
  justify-content: center;
}

.ig-aspect-cell {
  width: 72px;
  min-height: 48px;
  padding: 6px 4px;
  border-radius: 8px;
  border: 2px solid transparent;
  background: rgba(255, 255, 255, 0.05);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  transition:
    background 0.2s ease,
    border-color 0.2s ease,
    transform 0.2s ease;
}

.ig-aspect-cell:hover {
  background: rgba(255, 255, 255, 0.09);
}

.ig-aspect-cell:hover .ig-aspect-lbl {
  color: color-mix(in srgb, var(--chat-fg) 88%, transparent);
}

.ig-aspect-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  color: rgba(255, 255, 255, 0.42);
}

.ig-aspect-svg {
  width: 44px;
  height: 29px;
  display: block;
}

.ig-aspect-cell--on .ig-aspect-icon {
  color: var(--ig-brand);
}

.ig-aspect-lbl {
  font-size: 10px;
  font-weight: 600;
  color: var(--ig-muted-50);
}

.ig-aspect-cell--on {
  border-color: var(--ig-brand);
  background: color-mix(in srgb, var(--ig-brand) 10%, transparent);
}

.ig-aspect-cell--on .ig-aspect-lbl {
  font-size: 12px;
  color: var(--ig-brand);
}

.ig-banana-note {
  margin: 10px 0 0;
  padding: 8px 10px;
  border-radius: 8px;
  font-size: 11px;
  line-height: 1.45;
  color: rgba(251, 191, 36, 0.92);
  background: rgba(251, 191, 36, 0.08);
  border: 1px solid rgba(251, 191, 36, 0.18);
}

.ig-banana-note strong {
  font-weight: 700;
}

/* 风格：横向滚动条 4px */
.ig-style-scroller {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding-bottom: 8px;
  margin-bottom: 2px;
  scrollbar-width: thin;
  scrollbar-color: rgba(255, 255, 255, 0.25) transparent;
}

.ig-style-scroller::-webkit-scrollbar {
  height: 4px;
}

.ig-style-scroller::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.28);
  border-radius: 4px;
  transition:
    background 0.2s ease,
    height 0.2s ease;
}

.ig-style-scroller:hover::-webkit-scrollbar {
  height: 6px;
}

.ig-style-scroller:hover::-webkit-scrollbar-thumb {
  background: color-mix(in srgb, var(--ig-brand) 40%, rgba(255, 255, 255, 0.2));
}

/* 风格卡片 80×40 */
.ig-style-card {
  flex: 0 0 auto;
  width: 80px;
  height: 40px;
  border-radius: 8px;
  border: 2px solid transparent;
  padding: 0 8px;
  display: flex;
  align-items: center;
  gap: 6px;
  background: rgba(255, 255, 255, 0.05);
  cursor: pointer;
  transition:
    border-color 0.2s ease,
    background 0.2s ease,
    transform 0.2s ease;
}

.ig-style-card:hover {
  transform: translateY(-1px);
  background: rgba(255, 255, 255, 0.08);
}

.ig-style-card--on {
  border-color: var(--ig-brand);
  background: color-mix(in srgb, var(--ig-brand) 10%, transparent);
}

.ig-style-swatch {
  width: 12px;
  height: 12px;
  border-radius: 3px;
  flex-shrink: 0;
}

.ig-model-hint {
  margin: 8px 0 0;
  font-size: 11px;
  line-height: 1.45;
  color: rgba(255, 255, 255, 0.5);
}

.ig-model-hint strong {
  font-weight: 700;
  color: color-mix(in srgb, var(--chat-fg) 82%, transparent);
}

.ig-model-hint--tight {
  margin-top: 6px;
}

.ig-style-name {
  font-size: 11px;
  font-weight: 600;
  color: var(--chat-fg);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 还原度滑块：轨道 6px / thumb 20px；两端标签对齐轨道 */
.ig-slider-labels {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: var(--ig-muted-50);
  margin-bottom: 6px;
}

.ig-slider-labels--sm {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.5);
}

.ig-brand-slider {
  position: relative;
  padding-top: 22px;
  margin-bottom: 4px;
}

.ig-brand-slider--tip {
  padding-top: 26px;
}

.ig-slider-bubble {
  position: absolute;
  top: 0;
  left: 50%;
  transform: translateX(-50%);
  padding: 2px 8px;
  border-radius: 6px;
  font-size: 11px;
  font-weight: 700;
  color: var(--ig-brand);
  background: color-mix(in srgb, var(--ig-brand) 12%, transparent);
  animation: ig-num-pop 0.12s cubic-bezier(0.34, 1.56, 0.64, 1);
  pointer-events: none;
}

.ig-slider-bubble--brand {
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 8px;
  color: #fff;
  background: linear-gradient(145deg, var(--ig-brand), var(--ig-brand-hover));
  box-shadow:
    0 4px 14px color-mix(in srgb, var(--ig-brand) 38%, transparent),
    0 0 0 1px rgba(255, 255, 255, 0.12) inset;
}

@keyframes ig-num-pop {
  0% {
    transform: translateX(-50%) scale(0.92);
  }
  70% {
    transform: translateX(-50%) scale(1.04);
  }
  100% {
    transform: translateX(-50%) scale(1);
  }
}

.ig-fidelity-legend {
  margin: 10px 0 0;
  padding-left: 18px;
  font-size: 10px;
  line-height: 1.55;
  color: rgba(255, 255, 255, 0.44);
  list-style: disc;
}

.ig-fidelity-legend li {
  margin: 0 0 4px;
}

.ig-fidelity-legend strong {
  color: color-mix(in srgb, var(--chat-fg) 75%, transparent);
  font-weight: 700;
}

.ig-quality-res {
  margin: 5px 0 0;
  font-size: 10px;
  line-height: 1.45;
  color: rgba(255, 255, 255, 0.42);
}

.ig-quality-res:first-of-type {
  margin-top: 10px;
}

.ig-quality-res strong {
  color: rgba(255, 255, 255, 0.58);
  font-weight: 700;
}

.ig-brand-slider-input {
  -webkit-appearance: none;
  appearance: none;
  width: 100%;
  height: 6px;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.1);
  outline: none;
}

.ig-brand-slider-input::-webkit-slider-thumb {
  -webkit-appearance: none;
  appearance: none;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: linear-gradient(145deg, var(--ig-brand), var(--ig-brand-hover));
  box-shadow: 0 2px 10px color-mix(in srgb, var(--ig-brand) 45%, transparent);
  cursor: grab;
  margin-top: -7px;
}

.ig-brand-slider-input::-moz-range-thumb {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  border: none;
  background: linear-gradient(145deg, var(--ig-brand), var(--ig-brand-hover));
  box-shadow: 0 2px 10px color-mix(in srgb, var(--ig-brand) 45%, transparent);
  cursor: grab;
}

.ig-brand-slider-input::-webkit-slider-runnable-track {
  height: 6px;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.1);
}

.ig-brand-slider-input::-moz-range-track {
  height: 6px;
  border-radius: 3px;
  background: rgba(255, 255, 255, 0.1);
}

/* 画质：三等分药丸，高 36px / 整体圆角 10px */
.ig-quality-pills {
  display: flex;
  width: 100%;
  height: 36px;
  border-radius: 10px;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.06);
}

.ig-quality-pills--sm {
  height: 32px;
  border-radius: 8px;
}

.ig-qpill {
  flex: 1;
  border: none;
  margin: 0;
  padding: 0;
  background: transparent;
  font-size: 13px;
  font-weight: 600;
  color: var(--ig-muted-50);
  cursor: pointer;
  transition:
    background 0.2s ease,
    color 0.2s ease,
    transform 0.2s ease;
}

.ig-qpill:hover {
  color: color-mix(in srgb, var(--chat-fg) 90%, transparent);
}

.ig-qpill--on {
  background: linear-gradient(135deg, #10b981, #34d399);
  color: #fff;
  animation: ig-seg-pop 0.12s cubic-bezier(0.34, 1.56, 0.64, 1);
}

@keyframes ig-seg-pop {
  0% {
    transform: scale(0.97);
  }
  70% {
    transform: scale(1.03);
  }
  100% {
    transform: scale(1);
  }
}

.ig-hint-sm {
  margin: 10px 0 0;
  font-size: 0.65rem;
  color: var(--chat-muted-4);
  line-height: 1.4;
}

.ig-tool-card {
  min-height: 48px;
}

.ig-tool-card--muted {
  color: var(--chat-muted-3);
  font-size: 12px;
  text-align: center;
}

/* 扩图方向：24×24 图标按钮 */
.ig-dir-btns {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 4px;
}

.ig-dir-btn {
  width: 28px;
  height: 28px;
  padding: 0;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(255, 255, 255, 0.05);
  color: var(--ig-muted-50);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition:
    background 0.2s ease,
    border-color 0.2s ease,
    color 0.2s ease;
}

.ig-dir-btn--on {
  border-color: var(--ig-brand);
  color: var(--ig-brand);
  background: color-mix(in srgb, var(--ig-brand) 12%, transparent);
}

.ig-dir-svg {
  width: 16px;
  height: 16px;
  display: block;
}

.ig-dir-btn--all .ig-dir-svg {
  width: 18px;
  height: 18px;
}

.ig-dir-btns--24 {
  gap: 6px;
}

.ig-dir-btns--24 .ig-dir-btn {
  width: 24px;
  height: 24px;
  border-radius: 6px;
  padding: 0;
}

.ig-dir-btns--24 .ig-dir-svg {
  width: 12px;
  height: 12px;
}

.ig-dir-btns--24 .ig-dir-btn--all .ig-dir-svg {
  width: 13px;
  height: 13px;
}

/* 局部重绘：画笔预设（小/中/大） */
.ig-brush-presets {
  display: flex;
  align-items: center;
  gap: 14px;
}

.ig-brush-dot {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  padding: 0;
  border: 2px solid rgba(255, 255, 255, 0.1);
  background: rgba(255, 255, 255, 0.05);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition:
    border-color 0.2s ease,
    background 0.2s ease,
    transform 0.2s ease;
}

.ig-brush-dot:hover {
  transform: scale(1.05);
  background: rgba(255, 255, 255, 0.08);
}

.ig-brush-dot--on {
  border-color: var(--ig-brand);
  background: color-mix(in srgb, var(--ig-brand) 14%, transparent);
}

.ig-brush-dot-inner {
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.88);
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.25);
}

.ig-brush-dot-inner--sm {
  width: 8px;
  height: 8px;
}

.ig-brush-dot-inner--md {
  width: 14px;
  height: 14px;
}

.ig-brush-dot-inner--lg {
  width: 22px;
  height: 22px;
}

/* 扩展比例小分段 */
.ig-scale-pills {
  display: flex;
  gap: 6px;
}

.ig-spill {
  flex: 1;
  height: 30px;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: transparent;
  font-size: 12px;
  font-weight: 600;
  color: var(--ig-muted-50);
  cursor: pointer;
}

.ig-spill--on {
  border-color: var(--ig-brand);
  color: var(--ig-brand);
  background: color-mix(in srgb, var(--ig-brand) 10%, transparent);
}

/* 风格迁移 · 开关 */
.ig-toggle-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 0;
  cursor: pointer;
}

.ig-toggle-lab {
  font-size: 13px;
  color: var(--chat-fg);
}

.ig-switch {
  width: 44px;
  height: 26px;
  border-radius: 999px;
  border: none;
  padding: 0;
  background: rgba(255, 255, 255, 0.12);
  cursor: pointer;
  position: relative;
  flex-shrink: 0;
  transition: background 0.2s ease;
}

.ig-switch--on {
  background: linear-gradient(90deg, var(--ig-brand), var(--ig-brand-hover));
}

.ig-switch-knob {
  position: absolute;
  top: 3px;
  left: 3px;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.2);
  transition: transform 0.2s ease;
}

.ig-switch--on .ig-switch-knob {
  transform: translateX(18px);
}

/* 底部文字链接 12px / hover 下划线 */
.ig-quick {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin-bottom: 12px;
}

.ig-text-link {
  position: relative;
  border: none;
  background: none;
  padding: 0 0 2px;
  font-size: 12px;
  font-weight: 600;
  color: var(--ig-brand);
  cursor: pointer;
  text-decoration: none;
}

.ig-text-link::after {
  content: '';
  position: absolute;
  left: 0;
  bottom: 0;
  width: 100%;
  height: 1px;
  background: currentColor;
  transform: scaleX(0);
  transform-origin: left center;
  transition: transform 0.22s ease;
}

.ig-text-link:hover::after {
  transform: scaleX(1);
}

.ig-presets {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 16px;
}

.ig-preset-chip {
  padding: 4px 10px;
  border-radius: 999px;
  border: 1px solid var(--chat-border);
  background: var(--chat-btn-bg);
  font-size: 0.68rem;
  cursor: pointer;
  color: var(--chat-fg);
}

/* RECENT */
.ig-hist-block {
  border-top: 1px solid var(--ig-divider-soft);
  padding-top: 14px;
}

.ig-recent-title {
  margin: 0 0 10px;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 1px;
  text-transform: uppercase;
  color: var(--ig-muted-40);
}

.ig-recent-empty {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.ig-recent-ph {
  width: 40px;
  height: 40px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.03);
  animation: ig-ph-pulse 2s ease-in-out infinite;
}

.ig-recent-ph:nth-child(2) {
  animation-delay: 0.2s;
}

.ig-recent-ph:nth-child(3) {
  animation-delay: 0.4s;
}

.ig-recent-ph:nth-child(4) {
  animation-delay: 0.6s;
}

@keyframes ig-ph-pulse {
  0%,
  100% {
    opacity: 0.3;
  }
  50% {
    opacity: 0.6;
  }
}

.ig-recent-empty-txt {
  width: 100%;
  margin: 8px 0 0;
  font-size: 11px;
  color: var(--ig-muted-50);
}

.ig-hist-strip {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding: 8px 4px 10px 2px;
  scrollbar-width: thin;
  scrollbar-color: rgba(255, 255, 255, 0.22) transparent;
}

.ig-hist-strip::-webkit-scrollbar {
  height: 4px;
}

.ig-hist-strip::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.25);
  border-radius: 4px;
}

.ig-hist-thumb {
  flex: 0 0 auto;
  width: 40px;
  height: 40px;
  padding: 0;
  border: none;
  border-radius: 8px;
  overflow: visible;
  cursor: pointer;
  background: transparent;
  position: relative;
  z-index: 0;
}

.ig-hist-thumb:hover {
  z-index: 3;
}

.ig-hist-thumb-wrap {
  position: relative;
  display: block;
  width: 40px;
  height: 40px;
  border-radius: 8px;
  overflow: hidden;
  transition:
    transform 0.22s ease,
    box-shadow 0.22s ease;
}

.ig-hist-thumb:hover .ig-hist-thumb-wrap {
  transform: scale(1.4);
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.38);
}

.ig-hist-thumb img {
  width: 100%;
  height: 100%;
  border-radius: 8px;
  object-fit: cover;
  display: block;
}

.ig-hist-dl {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, 0.42);
  opacity: 0;
  transition: opacity 0.18s ease;
  cursor: pointer;
  color: #fff;
}

.ig-hist-thumb:hover .ig-hist-dl {
  opacity: 1;
}

.ig-hist-dl-svg {
  width: 18px;
  height: 18px;
  display: block;
  filter: drop-shadow(0 1px 2px rgba(0, 0, 0, 0.45));
}

.ig-powered-by {
  margin: 0 0 14px;
  font-size: 10px;
  letter-spacing: 0.04em;
  color: rgba(255, 255, 255, 0.3);
}

/* —— 底部输入：底图与画布一体（由 .ig-body 渐变透出） —— */
.ig-dock {
  flex-shrink: 0;
  padding: 12px max(16px, env(safe-area-inset-right)) calc(12px + env(safe-area-inset-bottom))
    max(16px, env(safe-area-inset-left));
  border-top: none;
  background: transparent;
}

.ig-ref-strip {
  display: flex;
  gap: 10px;
  overflow-x: auto;
  margin-bottom: 10px;
}

.ig-ref-item {
  position: relative;
  flex: 0 0 auto;
  width: 60px;
  height: 60px;
  border-radius: 12px;
  overflow: hidden;
  border: 1px solid var(--chat-border);
}

.ig-ref-item img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.ig-ref-x {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 22px;
  height: 22px;
  border: none;
  border-radius: 6px;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  cursor: pointer;
  font-size: 0.9rem;
  line-height: 1;
}

.ig-dock-inner {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  max-width: min(920px, 100%);
  margin: 0 auto;
}

/* 附件按钮：36px 圆；hover 涟漪 */
.ig-attach {
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  border-radius: 50%;
  border: none;
  background: transparent;
  color: var(--chat-fg);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  overflow: hidden;
  transition: transform 0.2s ease;
}

.ig-attach::after {
  content: '';
  position: absolute;
  inset: 0;
  margin: auto;
  width: 0;
  height: 0;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.12);
  transition:
    width 0.45s ease,
    height 0.45s ease;
}

.ig-attach:hover::after {
  width: 120%;
  height: 120%;
}

.ig-attach:hover {
  transform: scale(1.04);
}

.ig-attach-svg {
  width: 18px;
  height: 18px;
  position: relative;
  z-index: 1;
}

.ig-prompt-composer {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: stretch;
  min-height: 48px;
  max-height: calc(1.5em * 5 + 22px);
  border-radius: 16px;
  border: 1px solid var(--ig-divider-soft);
  background: var(--ig-card-fill);
  overflow: hidden;
  transition: border-color 0.15s ease;
}

.ig-prompt-composer:focus-within {
  border-color: color-mix(in srgb, var(--ig-brand) 35%, transparent);
}

.ig-textarea {
  flex: 1;
  min-height: 48px;
  max-height: calc(1.5em * 5 + 22px);
  resize: none;
  border-radius: 16px;
  border: 1px solid var(--ig-divider-soft);
  padding: 12px 14px;
  font-family: inherit;
  font-size: 0.875rem;
  line-height: 1.5;
  background: var(--ig-card-fill);
  color: var(--chat-input-fg);
}

.ig-textarea--dock {
  flex: 1;
  min-width: 0;
  border: none;
  border-radius: 0;
  background: transparent;
  max-height: none;
  overflow-y: auto;
}

.ig-textarea--dock:focus {
  outline: none;
}

.ig-textarea:focus {
  outline: none;
  border-color: color-mix(in srgb, var(--ig-brand) 35%, transparent);
}

.ig-prompt-opt {
  flex-shrink: 0;
  width: 52px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  padding: 8px 4px;
  border: none;
  border-left: 1px solid var(--ig-divider-soft);
  background: rgba(255, 255, 255, 0.035);
  color: var(--ig-brand);
  cursor: pointer;
  transition:
    background 0.15s ease,
    color 0.15s ease;
}

.ig-prompt-opt:hover:not(:disabled) {
  background: color-mix(in srgb, var(--ig-brand) 14%, transparent);
}

.ig-prompt-opt:disabled {
  opacity: 0.42;
  cursor: not-allowed;
}

.ig-prompt-opt-ic {
  width: 18px;
  height: 18px;
  display: block;
}

.ig-prompt-opt-cap {
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.06em;
  line-height: 1;
}

/* 发送：44px 圆；渐变 + hover 光晕 */
.ig-send {
  width: 44px;
  height: 44px;
  flex-shrink: 0;
  border: none;
  border-radius: 50%;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--ig-send-fg);
  background: linear-gradient(145deg, #10b981, #34d399);
  box-shadow:
    0 8px 28px color-mix(in srgb, var(--ig-brand) 42%, transparent),
    0 0 0 1px rgba(255, 255, 255, 0.06) inset;
  transition:
    transform 0.2s ease,
    box-shadow 0.2s ease,
    filter 0.2s ease;
}

.ig-send-svg {
  width: 20px;
  height: 20px;
}

.ig-send:hover:not(:disabled) {
  transform: scale(1.06);
  box-shadow:
    0 10px 36px color-mix(in srgb, var(--ig-brand) 55%, transparent),
    0 0 24px color-mix(in srgb, var(--ig-brand) 35%, transparent);
  filter: brightness(1.05);
}

.ig-send:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.ig-dock--edit .ig-edit-tip {
  margin: 0 0 12px;
  text-align: center;
  font-size: 0.8rem;
  color: var(--chat-muted);
}

.ig-edit-actions {
  display: flex;
  justify-content: center;
  gap: 12px;
}

.ig-btn-secondary {
  padding: 10px 20px;
  border-radius: 12px;
  border: 1px solid var(--chat-border-strong);
  background: var(--chat-btn-bg);
  color: var(--chat-fg);
  cursor: pointer;
  font-weight: 600;
}

.ig-btn-primary {
  padding: 10px 22px;
  border-radius: 12px;
  border: none;
  background: linear-gradient(135deg, var(--ig-brand), var(--ig-brand-hover));
  color: var(--ig-send-fg);
  font-weight: 700;
  cursor: pointer;
}

.ig-status {
  flex-shrink: 0;
  text-align: center;
  font-size: 0.65rem;
  color: var(--chat-muted-4);
  padding: 4px 8px 8px;
}

/* TOOL 专属区 accordion（opacity + 位移，避免 max-height 抖动） */
.ig-acc-enter-active,
.ig-acc-leave-active {
  transition:
    opacity 0.3s ease-out,
    transform 0.3s ease-out;
}

.ig-acc-enter-from,
.ig-acc-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

/* 过渡 */
.ig-fade-enter-active,
.ig-fade-leave-active {
  transition: opacity 0.2s ease;
}
.ig-fade-enter-from,
.ig-fade-leave-to {
  opacity: 0;
}

.ig-cross-enter-active,
.ig-cross-leave-active {
  transition:
    opacity 0.15s ease,
    transform 0.15s ease;
}
.ig-cross-enter-from,
.ig-cross-leave-to {
  opacity: 0;
  transform: translateY(6px);
}

/* 响应式 */
@media (max-width: 1024px) {
  .ig-panel {
    position: absolute;
    right: 0;
    top: 56px;
    bottom: 0;
    z-index: 25;
    box-shadow: -12px 0 32px rgba(0, 0, 0, 0.35);
    background: var(--ig-panel-surface);
    border-left: 1px solid var(--ig-panel-border);
  }
  .ig-panel--collapsed {
    transform: translateX(100%);
    width: 280px;
  }
}

@media (max-width: 720px) {
  .ig-topbar-center {
    display: none;
  }
  .ig-profile-txt {
    display: none;
  }
  .ig-body {
    flex-direction: column;
  }
  .ig-tool-strip-inner {
    flex-wrap: nowrap;
    justify-content: flex-start;
    overflow-x: auto;
    padding: 10px 12px;
    gap: 12px;
    -webkit-overflow-scrolling: touch;
  }
  .ig-tool-cap {
    font-size: 9px;
    max-width: 48px;
  }
}
</style>
