<script setup>
/**
 * 图片创作工作台：UI 状态机 + API 易 Nano Banana Pro（服务端代理 generateContent）。
 *
 * 状态：canvasPhase = empty | generating | done | edit-inpaint | edit-outpaint
 */
import { computed, nextTick, onActivated, onMounted, onUnmounted, provide, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import ChatProfileDrawer from '../components/chat/ChatProfileDrawer.vue'
import SiteMailBell from '../components/site-mail/SiteMailBell.vue'
import FullscreenImagePreview from '../components/FullscreenImagePreview.vue'
import { nanoBananaEdit, nanoBananaTextToImage, optimizeImageStudioPrompt } from '../api/imageStudio'
import {
  createImageStudioSession,
  deleteImageStudioSession,
  fetchImageStudioSessionDetail,
  fetchImageStudioSessionImageInline,
  fetchImageStudioSessions,
  patchImageStudioSession,
} from '../api/imageStudioSessions'
import { deleteMyImage, patchMyImageFavorite } from '../api/meProfile'
import AspectRatioSelector from '../components/image-studio/AspectRatioSelector.vue'
import StudioSkillSelector from '../components/image-studio/StudioSkillSelector.vue'
import ImageSessionSidebar from '../components/image-studio/ImageSessionSidebar.vue'
import QualitySelector from '../components/image-studio/QualitySelector.vue'
import StyleSelector from '../components/image-studio/StyleSelector.vue'
import { getAxiosErrorMessage } from '../utils/httpError'
import {
  nanoBananaPointsForStudioQuality,
  INSUFFICIENT_POINTS_TOOLTIP_ZH,
} from '../constants/pointCosts'
import { STUDIO_SKILL_STORAGE_KEY } from '../constants/imageStudioSkills'
import { useSkillStore } from '../stores/skillStore'

const router = useRouter()
const auth = useAuthStore()
const skillStore = useSkillStore()

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

const dockGlassSelectKey = ref(null)
provide('dockGlassSelectKey', dockGlassSelectKey)

/** @type {import('vue').Ref<{ id: number, title: string, updatedAt: string, imageCount: number, thumbUrl: string | null }[]>} */
const imageSessions = ref([])
const sessionsLoading = ref(false)
/** 右侧：会话列表 + 当前会话生成记录，默认收起 */
const studioChatPanelOpen = ref(false)
/** @type {import('vue').Ref<number | null>} */
const currentSessionId = ref(null)
/** @type {import('vue').Ref<{ id: number, url: string, at: number, prompt: string }[]>} */
const sessionImages = ref([])
const imageFromSession = ref(false)
const advancedParamsOpen = ref(false)

/** 图片工作台对话流（与多模态对话页隔离，仅本页 UI） */
/** @type {import('vue').Ref<{ id: string, role: 'user'|'assistant', text?: string, paramSummary?: string, imageUrl?: string, imageId?: number|null, sessionImage?: boolean, status?: 'loading'|'done'|'error'|'cancelled', progressMsg?: string, progressPct?: number, errorText?: string, createdAt: number }[]>} */
const studioChatMessages = ref([])
/** @type {import('vue').Ref<string|null>} */
const pendingAssistantMsgId = ref(null)
const mainThreadScrollRef = ref(null)
/** 用户主动上滚后主线程不强制跟底，生成结束后再贴底 */
const mainThreadStickToBottom = ref(true)

const STUDIO_PROGRESS_STAGES = [
  { id: 'intent_parsing', label: '正在理解您的创作意图…' },
  { id: 'rag_retrieval', label: '正在检索风格知识库…' },
  { id: 'prompt_optimization', label: '正在优化绘图提示词…' },
  { id: 'generating', label: 'AI 正在绘制中…' },
  { id: 'finalizing', label: '最终优化与出图中…' },
]

function studioMsgUid() {
  return `ig-${Date.now()}-${Math.random().toString(36).slice(2, 9)}`
}

function progressLabelForPct(pct) {
  const p = Math.min(100, Math.max(0, Number(pct) || 0))
  const i = Math.min(
    STUDIO_PROGRESS_STAGES.length - 1,
    Math.floor((p / 100) * STUDIO_PROGRESS_STAGES.length),
  )
  return STUDIO_PROGRESS_STAGES[i].label
}

function buildParamSummary() {
  const tool = TOOL_LABEL_ZH[activeTool.value] || activeTool.value
  const ar = aspects.find((a) => a.id === aspectId.value)?.label || aspectId.value
  const q = qualitySelectOptions.value.find((o) => o.id === quality.value)?.label || quality.value
  const sk = skillStore.skillById(studioSkillId.value)?.name || ''
  return sk ? `${tool} · ${sk} · ${ar} · ${q}` : `${tool} · ${ar} · ${q}`
}

function scrollMainThreadToBottom(force) {
  nextTick(() => {
    const el = mainThreadScrollRef.value
    if (!el) return
    if (!force && !mainThreadStickToBottom.value) return
    el.scrollTop = el.scrollHeight
  })
}

function onMainThreadScroll() {
  const el = mainThreadScrollRef.value
  if (!el) return
  const gap = el.scrollHeight - el.scrollTop - el.clientHeight
  mainThreadStickToBottom.value = gap < 80
}

function updatePendingAssistantBubble(patch) {
  const aid = pendingAssistantMsgId.value
  if (!aid) return
  studioChatMessages.value = studioChatMessages.value.map((m) =>
    m.id === aid ? { ...m, ...patch } : m,
  )
}

function rebuildStudioChatFromSession() {
  const msgs = []
  for (const im of sessionImages.value) {
    const pt = (im.prompt || '').trim()
    msgs.push({
      id: studioMsgUid(),
      role: 'user',
      text: pt || '（生成图片）',
      paramSummary: '历史生成',
      createdAt: im.at,
    })
    msgs.push({
      id: studioMsgUid(),
      role: 'assistant',
      status: 'done',
      imageUrl: im.url,
      imageId: im.id,
      sessionImage: true,
      createdAt: im.at + 1,
    })
  }
  studioChatMessages.value = msgs
  pendingAssistantMsgId.value = null
  void nextTick(() => scrollMainThreadToBottom(true))
}

function onStudioChatImageClick(url) {
  const row = sessionImages.value.find((x) => x.url === url)
  if (row) selectSessionImageThumb(row)
  else {
    resultUrl.value = url
    canvasPhase.value = 'done'
  }
}

function findPrevUserPrompt(assistantIdx) {
  for (let i = assistantIdx - 1; i >= 0; i--) {
    const row = studioChatMessages.value[i]
    if (row.role === 'user' && row.text) return String(row.text)
  }
  return ''
}

function onAiCardRetry(m) {
  const idx = studioChatMessages.value.findIndex((x) => x.id === m.id)
  if (idx < 0) return
  const t = findPrevUserPrompt(idx)
  if (!t) return
  prompt.value = t
  void runGeneration()
}

async function onAiCardDelete(m) {
  if (m.role !== 'assistant' || m.imageId == null) return
  if (!window.confirm('确定删除该生成图？')) return
  try {
    await deleteMyImage(m.imageId)
    await reloadCurrentSessionDetail()
    rebuildStudioChatFromSession()
    const still = sessionImages.value.some((x) => x.url === resultUrl.value)
    if (resultUrl.value && !still) {
      const last = sessionImages.value[sessionImages.value.length - 1]
      if (last?.url) selectSessionImageThumb(last)
      else {
        resultUrl.value = null
        serverImageId.value = null
        canvasPhase.value = 'empty'
      }
    }
    statusHint.value = '已删除'
  } catch (e) {
    statusHint.value = getAxiosErrorMessage(e)
  }
}

function onComposerKeydown(e) {
  if (e.key !== 'Enter' || e.shiftKey) return
  if (e.isComposing || e.keyCode === 229) return
  e.preventDefault()
  if (canvasPhase.value === 'generating' || promptOptimizing.value) return
  void runGeneration()
}

const qualitySelectOptions = computed(() => [
  { id: 'std', label: '标清', hint: '约 1024px · 快速预览' },
  { id: 'hd', label: '高清', hint: '约 1536px · 推荐日常' },
  { id: 'uhd', label: '超清', hint: '约 2048px · 队列可能更久' },
])

const aspects = [
  { id: '1:1', label: '1:1', w: 1, h: 1 },
  { id: '9:16', label: '9:16', w: 9, h: 16 },
  { id: '16:9', label: '16:9', w: 16, h: 9 },
  { id: '3:4', label: '3:4', w: 3, h: 4 },
  { id: '4:3', label: '4:3', w: 4, h: 3 },
  { id: '21:9', label: '21:9', w: 21, h: 9 },
]
const aspectId = ref('1:1')

/** 作图技能（与后端 studioSkillId 对齐）；选项来自 skillStore.skills */
const studioSkillId = ref(skillStore.defaultSkillId)
/** 为 true 时跳过 watch 写 sessionStorage / URL / PATCH，避免载入会话详情时误覆盖 */
const studioSkillHydratingFromServer = ref(false)
/** @type {ReturnType<typeof setTimeout> | null} */
let studioSkillPatchTimer = null

/** Gemini/Banana：风格卡片左侧 12×12 色块（写实肤色、插画明黄、3D 天蓝…） */
const styleOptions = [
  { id: 'realistic', label: '写实', swatch: '#e8c4b0' },
  { id: 'anime', label: '动漫', swatch: '#fda4af' },
  { id: 'cyberpunk', label: '赛博朋克', swatch: '#c084fc' },
  { id: 'oil', label: '油画', swatch: '#b45309' },
  { id: 'illustration', label: '插画', swatch: '#facc15' },
  { id: '3d', label: '3D 渲染', swatch: '#38bdf8' },
  { id: 'flat', label: '极简扁平', swatch: '#6ee7b7' },
  { id: 'sketch', label: '手绘素描', swatch: '#d4d4d8' },
  { id: 'floorplan', label: '平面图', swatch: '#1e3a8a' },
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

/** 切换工具时若有对话流，先经毛玻璃确认再切（见 Teleport #ig-tsw） */
const toolSwitchConfirmOpen = ref(false)
/** @type {import('vue').Ref<ToolId | null>} */
const pendingToolId = ref(null)
const toolSwitchSubmitting = ref(false)
const toolSwitchCancelRef = ref(null)

const pendingToolSwitchLabel = computed(() => {
  const id = pendingToolId.value
  if (!id) return ''
  return tools.find((t) => t.id === id)?.label || ''
})

const presets = ref([])

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
  historyItems.value = []
}

/** @param {{ id: number, imageCount?: number, updatedAt?: string }[]} sessions */
function pickReusableEmptySession(sessions) {
  const list = Array.isArray(sessions) ? sessions.filter((s) => s && s.id != null) : []
  const zeros = list.filter((s) => (Number(s.imageCount) || 0) === 0)
  if (!zeros.length) return null
  zeros.sort((a, b) => String(b.updatedAt ?? '').localeCompare(String(a.updatedAt ?? '')))
  return zeros[0]
}

/** @param {{ id: number, updatedAt?: string }[]} sessions */
function pickMostRecentSession(sessions) {
  const list = Array.isArray(sessions) ? sessions.filter((s) => s && s.id != null) : []
  if (!list.length) return null
  const sorted = [...list].sort((a, b) => String(b.updatedAt ?? '').localeCompare(String(a.updatedAt ?? '')))
  return sorted[0]
}

function tryReadSessionStorageStudioSkill() {
  try {
    const v = sessionStorage.getItem(STUDIO_SKILL_STORAGE_KEY)
    return skillStore.isKnownStudioSkillId(v) ? v : null
  } catch {
    return null
  }
}

function readQueryStudioSkillId() {
  const raw = router.currentRoute.value.query?.studioSkill
  const s = Array.isArray(raw) ? raw[0] : raw
  return skillStore.isKnownStudioSkillId(s) ? String(s) : null
}

function onStudioSkillPreferenceChanged(v) {
  if (studioSkillHydratingFromServer.value) return
  const id = skillStore.normalizeStudioSkillId(v)
  try {
    sessionStorage.setItem(STUDIO_SKILL_STORAGE_KEY, id)
  } catch {
    /* ignore */
  }
  if (router.currentRoute.value.name === 'image-gen') {
    void router.replace({ query: { ...router.currentRoute.value.query, studioSkill: id } })
  }
  if (!auth.isAuthenticated || currentSessionId.value == null) return
  if (studioSkillPatchTimer) window.clearTimeout(studioSkillPatchTimer)
  studioSkillPatchTimer = window.setTimeout(async () => {
    try {
      await patchImageStudioSession(currentSessionId.value, { studioSkillId: id })
      const sid = currentSessionId.value
      const idx = imageSessions.value.findIndex((x) => x.id === sid)
      if (idx >= 0) {
        const row = imageSessions.value[idx]
        imageSessions.value.splice(idx, 1, { ...row, studioSkillId: id })
      }
    } catch {
      /* ignore */
    }
  }, 400)
}

/** 每次进入图片创作模块：复用无图会话或最近会话，避免重复建空会话；右侧栏默认收起 */
async function bootstrapFreshImageSessionOnEnter() {
  studioChatPanelOpen.value = false
  if (!auth.isAuthenticated) {
    imageSessions.value = []
    currentSessionId.value = null
    sessionImages.value = []
    newCreationLocal()
    const gSkill =
      readQueryStudioSkillId() ?? tryReadSessionStorageStudioSkill() ?? skillStore.defaultSkillId
    studioSkillHydratingFromServer.value = true
    studioSkillId.value = skillStore.normalizeStudioSkillId(gSkill)
    void nextTick(() => {
      studioSkillHydratingFromServer.value = false
    })
    return
  }
  await loadSessionsList()
  const empty = pickReusableEmptySession(imageSessions.value)
  if (empty) {
    newCreationLocal()
    currentSessionId.value = empty.id
    await loadSessionDetail(empty.id)
    statusHint.value = '已恢复未使用的会话'
    void auth.refreshMe()
    return
  }
  const recent = pickMostRecentSession(imageSessions.value)
  if (recent) {
    newCreationLocal()
    currentSessionId.value = recent.id
    await loadSessionDetail(recent.id)
    statusHint.value = '已载入最近会话'
    void auth.refreshMe()
    return
  }
  try {
    await createNewSessionApi()
    newCreationLocal()
    statusHint.value = '已进入新会话'
  } catch {
    newCreationLocal()
    statusHint.value = '无法创建新会话，请检查网络后重试'
  }
  void auth.refreshMe()
}

async function loadSessionsList() {
  if (!auth.isAuthenticated) {
    imageSessions.value = []
    return
  }
  sessionsLoading.value = true
  try {
    const { data } = await fetchImageStudioSessions({ page: 0, size: 80 })
    const content = data?.content != null ? data.content : Array.isArray(data) ? data : []
    imageSessions.value = (Array.isArray(content) ? content : []).map((row) => ({
      ...row,
      studioSkillId:
        row?.studioSkillId != null && String(row.studioSkillId).trim()
          ? skillStore.normalizeStudioSkillId(String(row.studioSkillId))
          : skillStore.normalizeStudioSkillId('interior_designer'),
    }))
  } catch {
    imageSessions.value = []
  } finally {
    sessionsLoading.value = false
  }
}

async function createNewSessionApi() {
  const { data } = await createImageStudioSession()
  const id = data?.id != null ? Number(data.id) : null
  if (id == null) throw new Error('no session id')
  await loadSessionsList()
  currentSessionId.value = id
  await loadSessionDetail(id)
  const qSkill = readQueryStudioSkillId()
  if (qSkill && qSkill !== studioSkillId.value) {
    studioSkillHydratingFromServer.value = true
    studioSkillId.value = qSkill
    try {
      await patchImageStudioSession(id, { studioSkillId: qSkill })
    } catch {
      /* ignore */
    }
    void nextTick(() => {
      studioSkillHydratingFromServer.value = false
    })
  }
  return id
}

async function loadSessionDetail(id) {
  const { data } = await fetchImageStudioSessionDetail(id)
  studioSkillHydratingFromServer.value = true
  if (data?.studioSkillId != null && String(data.studioSkillId).trim()) {
    studioSkillId.value = skillStore.normalizeStudioSkillId(data.studioSkillId)
  } else {
    /* 旧接口无字段或空：与历史库默认 interior_designer 语义一致，再经 skillStore 归一 */
    studioSkillId.value = skillStore.normalizeStudioSkillId('interior_designer')
  }
  void nextTick(() => {
    studioSkillHydratingFromServer.value = false
  })
  const imgs = Array.isArray(data?.images) ? data.images : []
  /*
   * 复用「无图」会话时，服务端 context_text 常为旧会话残留；若载入会随 imageSessionContext
   * 拼进模型 prompt，导致新指令（如海边日落）被带偏成室内/窗景等。无图会话一律不继承该字段并写回清空。
   */
  if (imgs.length === 0) {
    imageSessionContext.value = ''
    const stale =
      typeof data?.contextText === 'string' && data.contextText.trim().length > 0
    if (stale) {
      void patchImageStudioSession(id, { contextText: '' }).catch(() => {})
    }
  } else {
    imageSessionContext.value = typeof data?.contextText === 'string' ? data.contextText : ''
  }
  sessionImages.value = imgs.map((r) => ({
    id: r.id != null ? Number(r.id) : 0,
    url: r.imageUrl,
    at: r.createdAt ? new Date(r.createdAt).getTime() : Date.now(),
    prompt: typeof r.userPrompt === 'string' ? r.userPrompt : '',
  }))
  const last = sessionImages.value[sessionImages.value.length - 1]
  if (last?.url) {
    resultUrl.value = last.url
    serverImageId.value = last.id
    resultFavorite.value = false
    imageFromSession.value = true
    canvasPhase.value = 'done'
    genMeta.value = {
      ...genMeta.value,
      ago: formatAgo(last.at),
    }
  } else {
    resultUrl.value = null
    serverImageId.value = null
    resultFavorite.value = false
    imageFromSession.value = false
    canvasPhase.value = 'empty'
  }
  rebuildStudioChatFromSession()
}

async function reloadCurrentSessionDetail() {
  if (!auth.isAuthenticated || currentSessionId.value == null) return
  try {
    await loadSessionDetail(currentSessionId.value)
  } catch {
    /* 列表刷新失败不阻断 */
  }
}

async function patchSessionContextRemote() {
  if (!auth.isAuthenticated || currentSessionId.value == null) return
  try {
    await patchImageStudioSession(currentSessionId.value, {
      contextText: imageSessionContext.value,
      studioSkillId: skillStore.normalizeStudioSkillId(studioSkillId.value),
    })
  } catch {
    /* 静默 */
  }
}

async function selectImageSession(id) {
  if (canvasPhase.value === 'generating') return
  currentSessionId.value = id
  await loadSessionDetail(id)
  statusHint.value = '已切换会话'
}

/** @returns {Promise<boolean>} 是否已成功准备新会话（失败时不应继续依赖「已清空」状态） */
async function onNewImageSession(forceNew = false) {
  if (!auth.isAuthenticated) {
    newCreationLocal()
    return true
  }
  try {
    await loadSessionsList()
    if (!forceNew) {
      const empty = pickReusableEmptySession(imageSessions.value)
      if (empty) {
        newCreationLocal()
        currentSessionId.value = empty.id
        await loadSessionDetail(empty.id)
        statusHint.value = '已切换到未使用的会话'
        return true
      }
    }
    await createNewSessionApi()
    newCreationLocal()
    statusHint.value = '已新建图片会话'
    return true
  } catch (e) {
    statusHint.value = getAxiosErrorMessage(e)
    return false
  }
}

/** 图片会话删除：毛玻璃确认框 */
const imageSessionDeleteOpen = ref(false)
/** @type {import('vue').Ref<number|null>} */
const imageSessionDeleteTargetId = ref(null)
const imageSessionDeleteSubmitting = ref(false)
const imageSessionDeleteCancelRef = ref(null)

const imageSessionDeleteTarget = computed(() => {
  const id = imageSessionDeleteTargetId.value
  if (id == null) return null
  return imageSessions.value.find((s) => s.id === id) ?? null
})

const imageSessionDeleteSummary = computed(() => {
  const s = imageSessionDeleteTarget.value
  if (!s) return { title: '', count: 0 }
  return { title: (s.title || '').trim() || '未命名', count: Number(s.imageCount) || 0 }
})

function openImageSessionDeleteConfirm(id) {
  if (!auth.isAuthenticated) return
  imageSessionDeleteTargetId.value = id
  imageSessionDeleteOpen.value = true
}

function cancelImageSessionDeleteConfirm() {
  if (imageSessionDeleteSubmitting.value) return
  imageSessionDeleteOpen.value = false
  imageSessionDeleteTargetId.value = null
}

watch(imageSessionDeleteOpen, (open) => {
  if (!open) return
  nextTick(() => imageSessionDeleteCancelRef.value?.focus?.())
})

async function confirmImageSessionDelete() {
  const id = imageSessionDeleteTargetId.value
  if (!auth.isAuthenticated || id == null || !imageSessionDeleteOpen.value || imageSessionDeleteSubmitting.value) return
  imageSessionDeleteSubmitting.value = true
  try {
    await deleteImageStudioSession(id)
    await loadSessionsList()
    if (currentSessionId.value === id) {
      if (imageSessions.value.length) {
        await selectImageSession(imageSessions.value[0].id)
      } else {
        await createNewSessionApi()
        newCreationLocal()
      }
    }
    statusHint.value = '已删除会话'
    imageSessionDeleteOpen.value = false
    imageSessionDeleteTargetId.value = null
  } catch (e) {
    statusHint.value = getAxiosErrorMessage(e)
  } finally {
    imageSessionDeleteSubmitting.value = false
  }
}

function selectSessionImageThumb(row) {
  if (!row?.url) return
  resultUrl.value = row.url
  serverImageId.value = row.id
  resultFavorite.value = false
  imageFromSession.value = true
  canvasPhase.value = 'done'
  genMeta.value = { ...genMeta.value, ago: formatAgo(row.at) }
}

function resolveCarryImageUrl() {
  if (resultUrl.value) return resultUrl.value
  const last = sessionImages.value[sessionImages.value.length - 1]
  return last?.url || ''
}

async function toggleCanvasFavorite() {
  if (imageFromSession.value) return
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
    e.target.closest?.('.ig-profile-wrap') ||
    e.target.closest?.('.site-mail-wrap') ||
    e.target.closest?.('.sm-shell') ||
    e.target.closest?.('.ig-sdel-shell') ||
    e.target.closest?.('.ig-tsw-shell')
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
  if (toolSwitchConfirmOpen.value) {
    cancelToolSwitchConfirm()
    return
  }
  if (imageSessionDeleteOpen.value) {
    cancelImageSessionDeleteConfirm()
    return
  }
  if (profileOpen.value) profileOpen.value = false
}

async function applyToolSelection(id) {
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

async function selectTool(id) {
  if (id !== activeTool.value && studioChatMessages.value.length > 0) {
    pendingToolId.value = id
    toolSwitchConfirmOpen.value = true
    return
  }
  await applyToolSelection(id)
}

function cancelToolSwitchConfirm() {
  if (toolSwitchSubmitting.value) return
  toolSwitchConfirmOpen.value = false
  pendingToolId.value = null
}

watch(toolSwitchConfirmOpen, (open) => {
  if (!open) return
  nextTick(() => toolSwitchCancelRef.value?.focus?.())
})

async function confirmToolSwitch() {
  const id = pendingToolId.value
  if (!id || !toolSwitchConfirmOpen.value || toolSwitchSubmitting.value) return
  toolSwitchSubmitting.value = true
  try {
    const ok = await onNewImageSession(true)
    if (!ok) return
    toolSwitchConfirmOpen.value = false
    pendingToolId.value = null
    await applyToolSelection(id)
  } finally {
    toolSwitchSubmitting.value = false
  }
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

function newCreationLocal() {
  stopGeneration()
  canvasPhase.value = 'empty'
  resultUrl.value = null
  prompt.value = ''
  serverImageId.value = null
  resultFavorite.value = false
  imageFromSession.value = false
  imageSessionContext.value = ''
  revokeRefs()
  referenceImages.value = []
  studioChatMessages.value = []
  pendingAssistantMsgId.value = null
  statusHint.value = '已清空画布'
}

function newCreation() {
  onNewImageSession()
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

/** 用户描述里像要「图纸/平面」而非透视效果图时，给文生图补一句约束，减少被模型加成室内渲染图 */
const TECH_DRAWING_HINT_RE =
  /平面图|平面布置|户型图|图纸|施工图|立面|剖面|\bCAD\b|线稿|二维|正投影|技术图|排版图|建筑图/i
const EFFECT_RENDER_EXEMPT_RE = /效果图|渲染|透视|三维|3D|写实室内|照片级/i

function appendTechnicalDrawingStyleGuard(base) {
  const raw = prompt.value.trim()
  if (!raw || !TECH_DRAWING_HINT_RE.test(raw) || EFFECT_RENDER_EXEMPT_RE.test(raw)) {
    return base
  }
  const guard =
    '画面类型须与用户描述一致：优先平面/技术示意或线稿风表达，功能区与墙体线条清晰；不要默认改成三维室内透视「效果图」，除非用户明确要求渲染或效果图'
  const b = (base || '').trim()
  return b ? `${b}。${guard}` : guard
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
  if (activeTool.value === 'txt2img') {
    p = appendTechnicalDrawingStyleGuard(p)
  }
  return p.trim()
}

/**
 * 同会话「在上一张基础上继续改」：编辑 API 专用文案。
 * 不再拼接 aug（风格/创意平衡）+「整体修改」，否则容易整体重画、改偏用户指令（如只改窗）。
 */
function buildCarryEditApiPrompt(userInstruction) {
  const core = (userInstruction || '').trim() || '按参考图微调'
  const fid = Number(fidelity.value)
  const leanKeep =
    Number.isFinite(fid) && fid >= 7
      ? `本次修改优先少动未提及区域（创意/还原约 ${fid}/10）。`
      : ''
  return [
    `【用户修改指令】${core}`,
    leanKeep,
    '以上一张成图为唯一基准：只落实指令中的改动；未写明的墙体、家具、标注、比例、线型与画面类别（平面图/立面/透视等）须与参考图一致。',
    '不要整体重画、不要擅自替换整套户型或把平面图改成未要求的透视效果图。',
  ]
    .map((x) => String(x).trim())
    .filter(Boolean)
    .join('')
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

/**
 * 优先通过同域「会话图片 inline」接口取 Base64，避免对 COS/CDN 直链 fetch 触发 CORS（表现为网络异常且未到后端）。
 * @param {string} url
 * @returns {Promise<{ mimeType: string, dataBase64: string }>}
 */
async function urlToInlinePartForStudioEdit(url) {
  const sid = currentSessionId.value
  if (
    sid != null &&
    url &&
    !String(url).startsWith('blob:') &&
    !String(url).startsWith('data:')
  ) {
    let row = sessionImages.value.find((x) => x.url === url)
    if (!row?.id && resultUrl.value === url && serverImageId.value) {
      row = { id: serverImageId.value, url }
    }
    if (row?.id) {
      const { data } = await fetchImageStudioSessionImageInline(sid, row.id)
      const mimeType =
        typeof data?.mimeType === 'string' && data.mimeType ? data.mimeType : 'image/png'
      const dataBase64 = typeof data?.dataBase64 === 'string' ? data.dataBase64 : ''
      if (dataBase64) {
        return { mimeType, dataBase64 }
      }
    }
  }
  return blobUrlToInlinePart(url)
}

async function collectInlineImages() {
  const out = []
  const seen = new Set()
  async function add(u) {
    if (!u || seen.has(u)) return
    seen.add(u)
    out.push(await urlToInlinePartForStudioEdit(u))
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
    sessionImage: Boolean(data.sessionImage),
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
  if (auth.isAuthenticated && currentSessionId.value != null) {
    void reloadCurrentSessionDetail()
    void patchSessionContextRemote()
  }
  if (auth.isAuthenticated) {
    void auth.refreshMe()
  }
  canvasPhase.value = 'done'
  statusHint.value = partialHint && String(partialHint).trim() ? String(partialHint).trim() : '生成完成'
}

/** 生成前确保已绑定图片会话，便于落库与作品库归档 */
async function ensureStudioSessionForGeneration() {
  if (!auth.isAuthenticated) return false
  if (currentSessionId.value != null) return true
  await loadSessionsList()
  const empty = pickReusableEmptySession(imageSessions.value)
  if (empty) {
    await selectImageSession(empty.id)
    return currentSessionId.value != null
  }
  if (imageSessions.value.length) {
    await selectImageSession(imageSessions.value[0].id)
    return currentSessionId.value != null
  }
  try {
    await createNewSessionApi()
    return currentSessionId.value != null
  } catch {
    return false
  }
}

async function runGeneration() {
  if (canvasPhase.value === 'generating') return
  if (!auth.isAuthenticated) {
    statusHint.value = '请先登录后再生成（服务端持有 API Key）'
    return
  }
  if (!(await ensureStudioSessionForGeneration())) {
    statusHint.value = '无法创建或选择图片会话，请稍后重试'
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
  mainThreadStickToBottom.value = true
  const firstLabel = STUDIO_PROGRESS_STAGES[0].label
  progressPct.value = 0
  progressMsg.value = firstLabel

  genAbortController = new AbortController()
  const signal = genAbortController.signal

  function clearGenTicker() {
    if (genInterval) window.clearInterval(genInterval)
    genInterval = null
  }

  function startProgressTicker() {
    const progressStart = Date.now()
    genInterval = window.setInterval(() => {
      const elapsed = Date.now() - progressStart
      let pct
      if (elapsed < 3000) {
        pct = (elapsed / 3000) * 90
      } else {
        pct = Math.min(94, 90 + Math.min(1, (elapsed - 3000) / 25000) * 4)
      }
      const label = progressLabelForPct(pct)
      progressPct.value = pct
      progressMsg.value = label
      updatePendingAssistantBubble({
        progressMsg: label,
        progressPct: pct,
      })
    }, 220)
  }

  function beginStudioChatTurn(userDisplayText) {
    studioChatMessages.value.push({
      id: studioMsgUid(),
      role: 'user',
      text: userDisplayText,
      paramSummary: buildParamSummary(),
      createdAt: Date.now(),
    })
    const aid = studioMsgUid()
    studioChatMessages.value.push({
      id: aid,
      role: 'assistant',
      status: 'loading',
      progressMsg: firstLabel,
      progressPct: 0,
      createdAt: Date.now(),
    })
    pendingAssistantMsgId.value = aid
    progressPct.value = 0
    progressMsg.value = firstLabel
    startProgressTicker()
    void nextTick(() => scrollMainThreadToBottom(true))
    prompt.value = ''
  }

  try {
    const imageSize = qualityToImageSize(quality.value)
    const aspectRatio = aspectId.value
    const aug = buildAugmentedPrompt()
    const ctxOpt = imageSessionContext.value.trim()
      ? { imageSessionContext: imageSessionContext.value }
      : {}
    const studioSessionPayload = {
      studioToolId: activeTool.value,
      studioSkillId: studioSkillId.value,
      ...(currentSessionId.value != null ? { imageStudioSessionId: currentSessionId.value } : {}),
    }

    let data
    let recordedPrompt = ''

    const carryUrl =
      activeTool.value === 'txt2img' && currentSessionId.value != null ? resolveCarryImageUrl() : ''

    if (activeTool.value === 'txt2img' && carryUrl && p) {
      beginStudioChatTurn(p)
      const images = [await urlToInlinePartForStudioEdit(carryUrl)]
      const promptText = buildCarryEditApiPrompt(p)
      recordedPrompt = p.trim()
      const { data: d } = await nanoBananaEdit(
        {
          prompt: promptText,
          userDisplayPrompt: p.trim(),
          aspectRatio,
          imageSize,
          images,
          ...ctxOpt,
          ...studioSessionPayload,
        },
        { signal },
      )
      data = d
    } else if (activeTool.value === 'txt2img') {
      beginStudioChatTurn(p)
      const promptText = aug || p
      recordedPrompt = p.trim()
      const { data: d } = await nanoBananaTextToImage(
        {
          prompt: promptText,
          userDisplayPrompt: p.trim(),
          aspectRatio,
          imageSize,
          ...ctxOpt,
          ...studioSessionPayload,
        },
        { signal },
      )
      data = d
    } else {
      const images = await collectInlineImages()
      if (images.length === 0) {
        canvasPhase.value = resultUrl.value ? 'done' : 'empty'
        progressPct.value = 0
        progressMsg.value = ''
        statusHint.value = '没有可用的参考图数据'
        return
      }
      const userLine = prompt.value.trim() || '（图片编辑）'
      beginStudioChatTurn(userLine)
      const promptText = buildEditPrompt(aug || p || '按参考图完成编辑')
      recordedPrompt = userLine
      const { data: d } = await nanoBananaEdit(
        {
          prompt: promptText,
          userDisplayPrompt: userLine,
          aspectRatio,
          imageSize,
          images,
          ...ctxOpt,
          ...studioSessionPayload,
        },
        { signal },
      )
      data = d
    }

    clearGenTicker()

    const row = mapStudioGenerateResponse(data)
    if (!row.ok) {
      const aidFail = pendingAssistantMsgId.value
      if (aidFail) {
        studioChatMessages.value = studioChatMessages.value.map((m) =>
          m.id === aidFail
            ? { ...m, status: 'error', errorText: row.error || '生成失败', progressPct: 0 }
            : m,
        )
      }
      pendingAssistantMsgId.value = null
      canvasPhase.value = resultUrl.value ? 'done' : 'empty'
      progressPct.value = 0
      progressMsg.value = ''
      statusHint.value = row.error || '生成失败'
      return
    }

    const aidOk = pendingAssistantMsgId.value
    if (aidOk) {
      studioChatMessages.value = studioChatMessages.value.map((m) =>
        m.id === aidOk
          ? {
              ...m,
              status: 'done',
              imageUrl: row.url,
              imageId: row.serverImageId != null ? Number(row.serverImageId) : null,
              sessionImage: Boolean(row.sessionImage),
              progressPct: 100,
              progressMsg: '完成',
            }
          : m,
      )
    }
    pendingAssistantMsgId.value = null
    mainThreadStickToBottom.value = true

    resultUrl.value = row.url
    serverImageId.value = row.serverImageId != null ? Number(row.serverImageId) : null
    resultFavorite.value = Boolean(row.favorite)
    imageFromSession.value = Boolean(row.sessionImage)
    appendStudioImageContext(TOOL_LABEL_ZH[activeTool.value] || activeTool.value, recordedPrompt)
    applyGenerationSuccess(aspectId.value, null)
    void nextTick(() => scrollMainThreadToBottom(true))
  } catch (e) {
    clearGenTicker()
    const canceled =
      e?.code === 'ERR_CANCELED' || e?.name === 'CanceledError' || e?.name === 'AbortError'
    const errText = canceled ? '已取消' : getAxiosErrorMessage(e)
    statusHint.value = errText
    const aidErr = pendingAssistantMsgId.value
    if (aidErr) {
      studioChatMessages.value = studioChatMessages.value.map((m) =>
        m.id === aidErr
          ? {
              ...m,
              status: canceled ? 'cancelled' : 'error',
              errorText: errText,
              progressPct: 0,
            }
          : m,
      )
    }
    pendingAssistantMsgId.value = null
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
  const aid = pendingAssistantMsgId.value
  if (aid) {
    studioChatMessages.value = studioChatMessages.value.map((m) =>
      m.id === aid
        ? { ...m, status: 'cancelled', errorText: '已取消', progressPct: 0, progressMsg: '' }
        : m,
    )
    pendingAssistantMsgId.value = null
  }
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
  studioSkillId.value = skillStore.defaultSkillId
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

watch(
  () => skillStore.skills.map((s) => s.id).join(','),
  () => {
    studioSkillId.value = skillStore.normalizeStudioSkillId(studioSkillId.value)
  },
)

watch(studioSkillId, (v) => {
  onStudioSkillPreferenceChanged(v)
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

watch(advancedParamsOpen, () => nextTick(() => initInpaintCanvas()))

watch(
  studioChatMessages,
  () => void nextTick(() => scrollMainThreadToBottom(false)),
  { deep: true },
)

watch(
  () => auth.isAuthenticated,
  async (ok) => {
    if (ok) {
      await bootstrapFreshImageSessionOnEnter()
    } else {
      historyItems.value = []
      imageSessions.value = []
      currentSessionId.value = null
      sessionImages.value = []
      imageFromSession.value = false
      studioChatPanelOpen.value = false
      newCreationLocal()
    }
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

onMounted(async () => {
  loadLocalPresets()
  await bootstrapFreshImageSessionOnEnter()
  document.addEventListener('click', onDocClick)
  document.addEventListener('keydown', onDocKeydown)
  document.addEventListener('pointerup', clearSliderTip)
  window.addEventListener('resize', () => {
    if (canvasPhase.value === 'edit-inpaint') initInpaintCanvas()
  })
})

/** 若路由将来对图片页使用 keep-alive，再次显示时需重新 bootstrap（避免沿用缓存会话） */
let skipFirstKeepAliveActivate = true
onActivated(async () => {
  if (skipFirstKeepAliveActivate) {
    skipFirstKeepAliveActivate = false
    return
  }
  await bootstrapFreshImageSessionOnEnter()
})

onUnmounted(() => {
  stopGeneration()
  revokeRefs()
  studioChatPanelOpen.value = false
  currentSessionId.value = null
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
        <SiteMailBell />
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
            :is-admin="auth.isAdmin"
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

        <div
          ref="mainThreadScrollRef"
          class="ig-main-thread"
          @scroll="onMainThreadScroll"
          @dragover.prevent
          @drop.prevent="onCanvasDrop"
        >
          <div v-if="!studioChatMessages.length" class="ig-thread-empty" @dragover.prevent @drop.prevent="onCanvasDrop">
            <p class="ig-thread-empty-title">图片创作</p>
            <p class="ig-thread-empty-desc">在底部输入描述并发送；可拖拽图片到此处作为参考。</p>
            <div class="ig-examples">
              <button v-for="ex in examplePrompts" :key="ex" type="button" class="ig-ex-pill" @click="applyExample(ex)">
                {{ ex }}
              </button>
            </div>
          </div>
          <div v-else class="ig-thread-list">
            <template v-for="m in studioChatMessages" :key="m.id">
              <div v-if="m.role === 'user'" class="ig-msg-user-row">
                <article class="ig-msg-user-bubble">
                  <p class="ig-msg-user-text">{{ m.text }}</p>
                  <p v-if="m.paramSummary" class="ig-msg-user-meta">{{ m.paramSummary }}</p>
                </article>
              </div>
              <div v-else class="ig-msg-ai-row">
                <article
                  class="ig-ai-card"
                  :class="{
                    'ig-ai-card--pulse': m.status === 'loading',
                    'ig-ai-card--err': m.status === 'error' || m.status === 'cancelled',
                  }"
                >
                  <template v-if="m.status === 'loading'">
                    <div class="ig-ai-card-loading-inner">
                      <p class="ig-ai-loading-title">正在生成…</p>
                      <p class="ig-ai-loading-stage">{{ m.progressMsg || '请稍候…' }}</p>
                      <div class="ig-ai-loading-bar-wrap">
                        <div class="ig-ai-loading-bar">
                          <div class="ig-ai-loading-fill" :style="{ width: `${Math.round(m.progressPct || 0)}%` }" />
                        </div>
                        <span class="ig-ai-loading-pct">{{ Math.round(m.progressPct || 0) }}%</span>
                      </div>
                      <button type="button" class="ig-stop ig-stop--card" @click.stop="stopGeneration">
                        <span class="ig-stop-sq" aria-hidden="true" />
                        停止生成
                      </button>
                    </div>
                  </template>
                  <template v-else-if="m.status === 'done' && m.imageUrl">
                    <button
                      type="button"
                      class="ig-ai-card-img-btn"
                      :title="'查看大图'"
                      @click="openFullscreenImagePreview(m.imageUrl)"
                    >
                      <img :src="m.imageUrl" alt="生成结果" class="ig-ai-card-img" />
                    </button>
                    <div class="ig-ai-card-actions">
                      <button type="button" class="ig-ai-act" @click="downloadUrl(m.imageUrl)">下载</button>
                      <button type="button" class="ig-ai-act" @click="openFullscreenImagePreview(m.imageUrl)">放大</button>
                      <button
                        v-if="m.imageId != null"
                        type="button"
                        class="ig-ai-act ig-ai-act--danger"
                        @click="onAiCardDelete(m)"
                      >
                        删除
                      </button>
                      <button
                        v-if="m.imageId != null && resultUrl === m.imageUrl && serverImageId != null && !imageFromSession"
                        type="button"
                        class="ig-ai-act"
                        :class="{ 'ig-ai-act--on': resultFavorite }"
                        :disabled="favBusyId === serverImageId"
                        @click.stop="toggleCanvasFavorite"
                      >
                        收藏
                      </button>
                    </div>
                  </template>
                  <template v-else-if="m.status === 'error' || m.status === 'cancelled'">
                    <p class="ig-ai-err-text">
                      {{ m.status === 'cancelled' ? '已取消' : '生成失败' }}：{{ m.errorText || '未知错误' }}
                    </p>
                    <button
                      v-if="m.status === 'error'"
                      type="button"
                      class="ig-ai-retry"
                      @click="onAiCardRetry(m)"
                    >
                      重试
                    </button>
                  </template>
                </article>
              </div>
            </template>
          </div>
        </div>

        <div
          v-if="canvasPhase === 'edit-inpaint' || canvasPhase === 'edit-outpaint'"
          class="ig-edit-sheet"
          @dragover.prevent
          @drop.prevent="onCanvasDrop"
        >
          <div class="ig-edit-sheet-inner">
            <div class="ig-edit-sheet-imgwrap">
              <img v-if="resultUrl" :src="resultUrl" alt="" class="ig-edit-sheet-img" />
              <canvas
                v-if="canvasPhase === 'edit-inpaint'"
                ref="inpaintCanvasRef"
                class="ig-inpaint-cv ig-inpaint-cv--sheet"
                @mousedown="drawing = true"
                @mouseup="drawing = false"
                @mouseleave="drawing = false"
                @mousemove="paintInpaint"
              />
              <div v-if="canvasPhase === 'edit-outpaint'" class="ig-outpaint-ui ig-outpaint-ui--sheet">
                <span class="ig-outpaint-hint">虚线框示意扩展区域 · 方向与比例在「工具」面板调整</span>
                <div class="ig-outpaint-box" />
              </div>
            </div>
            <div v-if="canvasPhase === 'edit-inpaint'" class="ig-edit-toolbar">
              <label class="ig-mini-label">画笔 {{ inpaintBrush }}px</label>
              <input v-model.number="inpaintBrush" type="range" min="8" max="72" class="ig-range" />
              <button type="button" class="ig-mini-btn" @click="clearInpaintMask">清除涂抹</button>
            </div>
          </div>
        </div>
        </div>

        <div class="ig-compose-split" aria-hidden="true" />
        <footer class="ig-dock">
          <div v-if="canvasPhase === 'edit-inpaint' || canvasPhase === 'edit-outpaint'" class="ig-dock-edit-row">
            <p class="ig-edit-tip">
              {{
                canvasPhase === 'edit-inpaint'
                  ? '涂抹需要重绘的区域，然后在下方输入修改描述（或使用默认）'
                  : '确认扩展方向与比例后点击下方确认生成'
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
          </div>
            <div v-if="referenceImages.length && activeTool === 'img2img'" class="ig-ref-strip">
              <div v-for="(r, i) in referenceImages" :key="i" class="ig-ref-item">
                <img :src="r.url" alt="" />
                <button type="button" class="ig-ref-x" @click="removeRef(i)">×</button>
              </div>
            </div>
            <div class="ig-dock-inner">
              <div class="ig-dock-selectors">
                <StudioSkillSelector
                  v-model="studioSkillId"
                  :options="skillStore.studioSkillOptions"
                  :disabled="canvasPhase === 'generating'"
                />
                <AspectRatioSelector
                  v-model="aspectId"
                  :options="aspects"
                  :disabled="canvasPhase === 'generating'"
                />
                <QualitySelector
                  v-model="quality"
                  :options="qualitySelectOptions"
                  :disabled="canvasPhase === 'generating'"
                />
                <StyleSelector
                  v-model="styleId"
                  :options="styleOptions"
                  :disabled="canvasPhase === 'generating'"
                />
                <button
                  type="button"
                  class="ig-dock-adv-btn"
                  :disabled="canvasPhase === 'generating'"
                  title="工具专属参数、还原度、预设"
                  aria-label="打开工具参数面板"
                  @click="advancedParamsOpen = true"
                >
                  工具
                </button>
              </div>
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
                  rows="2"
                  :disabled="canvasPhase === 'generating'"
                  :placeholder="
                    activeTool === 'img2img'
                      ? '描述你想如何修改这张图片…'
                      : '描述你想要的画面，越详细效果越好…'
                  "
                  @keydown="onComposerKeydown"
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
      </div>

      <Teleport to="body">
        <div
          v-if="advancedParamsOpen"
          class="ig-adv-overlay"
          role="dialog"
          aria-modal="true"
          aria-label="工具与还原参数"
          @click.self="advancedParamsOpen = false"
        >
          <div class="ig-adv-modal ig-panel-scroll--styled" @click.stop>
            <div class="ig-adv-modal-head">
              <span class="ig-adv-modal-title">工具参数</span>
              <button type="button" class="ig-adv-modal-x" @click="advancedParamsOpen = false">✕</button>
            </div>
            <div class="ig-adv-modal-body">
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

                      </div>
          </div>
        </div>
      </Teleport>

      <Teleport to="body">
        <Transition name="ig-sdel">
          <div
            v-if="imageSessionDeleteOpen"
            class="ig-sdel-shell"
            role="dialog"
            aria-modal="true"
            aria-labelledby="ig-sdel-title"
            aria-describedby="ig-sdel-desc"
          >
            <div class="ig-sdel-backdrop" @click="cancelImageSessionDeleteConfirm" />
            <div class="ig-sdel-center">
              <div class="ig-sdel-panel" @click.stop>
                <h2 id="ig-sdel-title" class="ig-sdel-title">删除图片会话</h2>
                <p id="ig-sdel-desc" class="ig-sdel-desc">
                  确定要删除「{{ imageSessionDeleteSummary.title }}」吗？该会话下的
                  {{ imageSessionDeleteSummary.count }} 张生成记录将一并移除，此操作不可恢复。
                </p>
                <div class="ig-sdel-actions">
                  <button
                    ref="imageSessionDeleteCancelRef"
                    type="button"
                    class="ig-sdel-btn ig-sdel-btn--ghost"
                    :disabled="imageSessionDeleteSubmitting"
                    @click="cancelImageSessionDeleteConfirm"
                  >
                    取消
                  </button>
                  <button
                    type="button"
                    class="ig-sdel-btn ig-sdel-btn--danger"
                    :disabled="imageSessionDeleteSubmitting"
                    @click="confirmImageSessionDelete"
                  >
                    {{ imageSessionDeleteSubmitting ? '删除中…' : '确认删除' }}
                  </button>
                </div>
              </div>
            </div>
          </div>
        </Transition>
      </Teleport>

      <Teleport to="body">
        <Transition name="ig-tsw">
          <div
            v-if="toolSwitchConfirmOpen"
            id="ig-tsw"
            class="ig-tsw-shell"
            role="dialog"
            aria-modal="true"
            aria-labelledby="ig-tsw-title"
            aria-describedby="ig-tsw-desc"
          >
            <div class="ig-sdel-backdrop" @click="cancelToolSwitchConfirm" />
            <div class="ig-sdel-center">
              <div class="ig-sdel-panel" @click.stop>
                <p class="ig-tsw-kicker" aria-hidden="true">切换确认</p>
                <h2 id="ig-tsw-title" class="ig-sdel-title">切换到「{{ pendingToolSwitchLabel }}」</h2>
                <p id="ig-tsw-desc" class="ig-sdel-desc">
                  将新建图片会话并清空当前对话流。若未保存的进度仅存在于本页，关闭后将无法找回。
                </p>
                <div class="ig-sdel-actions">
                  <button
                    ref="toolSwitchCancelRef"
                    type="button"
                    class="ig-sdel-btn ig-sdel-btn--ghost"
                    :disabled="toolSwitchSubmitting"
                    @click="cancelToolSwitchConfirm"
                  >
                    取消
                  </button>
                  <button
                    type="button"
                    class="ig-sdel-btn ig-sdel-btn--accent"
                    :disabled="toolSwitchSubmitting"
                    @click="confirmToolSwitch"
                  >
                    {{ toolSwitchSubmitting ? '处理中…' : '继续切换' }}
                  </button>
                </div>
              </div>
            </div>
          </div>
        </Transition>
      </Teleport>

      <div class="ig-chat-shell" :class="{ 'ig-chat-shell--open': studioChatPanelOpen }">
        <div id="ig-chat-panel" class="ig-chat-panel-track">
          <aside v-show="studioChatPanelOpen" class="ig-chat-panel-aside" aria-label="图片会话列表">
            <div class="ig-chat-rail-sessions ig-chat-rail-sessions--only">
              <ImageSessionSidebar
                :sessions="imageSessions"
                :current-id="currentSessionId"
                :loading="sessionsLoading"
                :is-authenticated="auth.isAuthenticated"
                @select="selectImageSession"
                @new="onNewImageSession"
                @delete="openImageSessionDeleteConfirm"
                @session-contextmenu="openImageSessionDeleteConfirm"
              />
            </div>
          </aside>
        </div>
        <button
          type="button"
          class="ig-chat-edge-btn"
          :title="studioChatPanelOpen ? '收起会话与记录' : '展开会话与记录'"
          :aria-expanded="studioChatPanelOpen"
          aria-controls="ig-chat-panel"
          @click="studioChatPanelOpen = !studioChatPanelOpen"
        >
          <span class="ig-chat-edge-ico" aria-hidden="true">{{ studioChatPanelOpen ? '▶' : '◀' }}</span>
          <span v-show="!studioChatPanelOpen" class="ig-chat-edge-cap">会话</span>
        </button>
      </div>

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

/* ---------- 两栏主体：中间画布 + 右侧会话/记录；工作区渐变含底栏输入 ---------- */
.ig-body {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: row;
  overflow: hidden;
  background: var(--ig-workspace-bg);
}

/* 右侧：会话列表 + 当前会话生成记录（默认收起，边缘按钮展开） */
.ig-chat-shell {
  flex-shrink: 0;
  display: flex;
  flex-direction: row;
  align-items: stretch;
  min-height: 0;
  width: 36px;
  transition: width 0.22s cubic-bezier(0.22, 1, 0.36, 1);
  z-index: 11;
}

.ig-chat-shell--open {
  width: calc(36px + 300px);
}

.ig-chat-edge-btn {
  flex-shrink: 0;
  width: 36px;
  min-width: 36px;
  border: none;
  border-left: 1px solid var(--chat-border);
  background: color-mix(in srgb, var(--chat-shell-bg) 92%, transparent);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-start;
  padding: 14px 0 12px;
  gap: 8px;
  font-size: 11px;
  font-weight: 750;
  color: var(--chat-muted);
  transition:
    color 0.15s ease,
    background 0.15s ease;
}

.ig-chat-edge-btn:hover {
  color: var(--chat-fg);
  background: color-mix(in srgb, var(--chat-btn-bg-hover) 80%, transparent);
}

.ig-chat-shell--open .ig-chat-edge-btn {
  color: var(--chat-fg-strong);
}

.ig-chat-edge-ico {
  font-size: 13px;
  line-height: 1;
}

.ig-chat-edge-cap {
  writing-mode: vertical-rl;
  text-orientation: mixed;
  letter-spacing: 0.14em;
  font-size: 11px;
  user-select: none;
}

.ig-chat-panel-track {
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  width: 0;
  opacity: 0;
  pointer-events: none;
  transition:
    width 0.22s cubic-bezier(0.22, 1, 0.36, 1),
    opacity 0.16s ease;
}

.ig-chat-shell--open .ig-chat-panel-track {
  width: 300px;
  min-width: 300px;
  opacity: 1;
  pointer-events: auto;
}

.ig-chat-panel-aside {
  height: 100%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  border-inline-start: 1px solid var(--chat-border);
  background: color-mix(in srgb, var(--chat-shell-bg) 88%, transparent);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
}

.ig-chat-rail-sessions {
  flex: 0 1 44%;
  min-height: 0;
  max-height: min(44vh, 420px);
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-bottom: 1px solid var(--chat-border);
}

.ig-chat-rail-sessions :deep(.iss-root) {
  max-width: none;
  width: 100%;
  flex: 1;
  min-height: 0;
  border-inline-end: none;
}

.ig-chat-rail-subhead {
  flex-shrink: 0;
  padding: 8px 12px 6px;
  border-bottom: 1px solid var(--chat-border);
  background: color-mix(in srgb, var(--chat-shell-bg) 55%, transparent);
}

.ig-chat-rail-subtitle {
  font-size: 0.72rem;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--chat-muted);
}

@media (max-width: 900px) {
  .ig-chat-shell--open {
    width: calc(36px + 240px);
  }
  .ig-chat-shell--open .ig-chat-panel-track {
    width: 240px;
    min-width: 240px;
  }
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

/* —— 主区：对话线程（替代大画布） —— */
.ig-main-thread {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 12px 16px 20px;
  scrollbar-width: thin;
  scrollbar-color: color-mix(in srgb, var(--chat-muted) 35%, transparent) transparent;
}

.ig-thread-empty {
  max-width: 520px;
  margin: 0 auto;
  padding: 40px 16px 24px;
  text-align: center;
}

.ig-thread-empty-title {
  margin: 0 0 8px;
  font-size: 1.125rem;
  font-weight: 750;
  color: var(--chat-fg-strong);
}

.ig-thread-empty-desc {
  margin: 0 0 20px;
  font-size: 0.8125rem;
  line-height: 1.55;
  color: var(--chat-muted);
}

.ig-thread-list {
  max-width: 880px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.ig-msg-user-row {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 12px;
}

.ig-msg-user-bubble {
  max-width: min(92%, 720px);
  padding: 12px 14px 10px;
  border-radius: 16px 16px 4px 16px;
  background: linear-gradient(145deg, #1e293b, #0f172a);
  color: #f8fafc;
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.35);
  border: 1px solid color-mix(in srgb, var(--chat-border-strong) 55%, transparent);
}

.ig-msg-user-text {
  margin: 0;
  font-size: 0.875rem;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}

.ig-msg-user-meta {
  margin: 8px 0 0;
  font-size: 0.68rem;
  color: color-mix(in srgb, #f8fafc 55%, transparent);
  letter-spacing: 0.02em;
}

.ig-msg-ai-row {
  display: flex;
  justify-content: flex-start;
  margin-bottom: 14px;
}

.ig-ai-card {
  width: 80%;
  max-width: 720px;
  border-radius: 16px;
  border: 1px solid var(--chat-border);
  background: color-mix(in srgb, var(--chat-panel) 88%, transparent);
  box-shadow: 0 10px 36px rgba(0, 0, 0, 0.28);
  overflow: hidden;
}

.ig-ai-card--pulse {
  animation: ig-ai-pulse 1.8s ease-in-out infinite;
}

@keyframes ig-ai-pulse {
  0%,
  100% {
    box-shadow: 0 10px 36px rgba(0, 0, 0, 0.28);
  }
  50% {
    box-shadow: 0 12px 44px color-mix(in srgb, var(--ig-brand) 22%, transparent);
  }
}

.ig-ai-card--err {
  border-color: color-mix(in srgb, #f87171 45%, var(--chat-border));
}

.ig-ai-card-loading-inner {
  padding: 22px 18px 18px;
  text-align: center;
}

.ig-ai-loading-title {
  margin: 0 0 6px;
  font-size: 0.9375rem;
  font-weight: 700;
  color: var(--chat-fg-strong);
}

.ig-ai-loading-stage {
  margin: 0 0 16px;
  font-size: 0.78rem;
  color: var(--chat-muted);
}

.ig-ai-loading-bar-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}

.ig-ai-loading-bar {
  flex: 1;
  height: 6px;
  border-radius: 99px;
  background: rgba(255, 255, 255, 0.06);
  overflow: hidden;
}

.ig-ai-loading-fill {
  height: 100%;
  border-radius: 99px;
  background: linear-gradient(90deg, var(--ig-brand), #34d399);
  transition: width 0.25s ease;
}

.ig-ai-loading-pct {
  font-size: 0.72rem;
  font-weight: 700;
  color: var(--chat-muted);
  min-width: 38px;
  text-align: right;
}

.ig-stop--card {
  margin: 0 auto;
}

.ig-ai-card-img-btn {
  display: block;
  width: 100%;
  padding: 0;
  border: none;
  background: #0a0a0c;
  cursor: zoom-in;
}

.ig-ai-card-img {
  display: block;
  width: 100%;
  max-height: min(52vh, 520px);
  object-fit: contain;
}

.ig-ai-card-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 10px 12px 12px;
  border-top: 1px solid var(--chat-border);
  background: color-mix(in srgb, var(--chat-shell-bg) 40%, transparent);
}

.ig-ai-act {
  padding: 6px 12px;
  border-radius: 8px;
  border: 1px solid var(--chat-border-strong);
  background: transparent;
  color: var(--chat-fg);
  font-size: 0.72rem;
  font-weight: 650;
  cursor: pointer;
}

.ig-ai-act:hover {
  background: var(--chat-btn-bg-hover);
}

.ig-ai-act--danger {
  border-color: color-mix(in srgb, #f87171 55%, transparent);
  color: #fecaca;
}

.ig-ai-act--on {
  border-color: color-mix(in srgb, #fbbf24 55%, transparent);
  color: #fde68a;
}

.ig-ai-err-text {
  margin: 0;
  padding: 16px 14px 8px;
  font-size: 0.8125rem;
  line-height: 1.5;
  color: #fecaca;
}

.ig-ai-retry {
  margin: 0 12px 14px;
  padding: 8px 14px;
  border-radius: 10px;
  border: 1px solid var(--chat-border-strong);
  background: var(--chat-btn-bg-hover);
  color: var(--chat-fg-strong);
  font-size: 0.78rem;
  font-weight: 650;
  cursor: pointer;
}

.ig-edit-sheet {
  flex-shrink: 0;
  max-height: min(44vh, 440px);
  padding: 10px 16px 12px;
  border-top: 1px solid var(--chat-border);
  background: color-mix(in srgb, #0a0a0c 92%, transparent);
}

.ig-edit-sheet-inner {
  max-width: 880px;
  margin: 0 auto;
}

.ig-edit-sheet-imgwrap {
  position: relative;
  width: 100%;
  min-height: 200px;
  max-height: min(36vh, 400px);
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  overflow: hidden;
  background: rgba(0, 0, 0, 0.35);
}

.ig-edit-sheet-img {
  max-width: 100%;
  max-height: min(36vh, 400px);
  object-fit: contain;
  display: block;
}

.ig-inpaint-cv--sheet {
  position: absolute;
  inset: 0;
  width: 100% !important;
  height: 100% !important;
  cursor: crosshair;
}

.ig-outpaint-ui--sheet {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.ig-dock-edit-row {
  flex-shrink: 0;
  padding: 10px 16px 12px;
  border-bottom: 1px solid var(--ig-divider-soft);
  background: color-mix(in srgb, var(--chat-shell-bg) 55%, transparent);
}

.ig-chat-rail-sessions--only {
  flex: 1 1 auto;
  max-height: none;
  min-height: 0;
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

.ig-dock-selectors {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.ig-dock-adv-btn {
  height: 38px;
  min-width: 44px;
  padding: 0 10px;
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(26, 26, 30, 0.9);
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  cursor: pointer;
  flex-shrink: 0;
}
.ig-dock-adv-btn:hover:not(:disabled) {
  border-color: rgba(94, 225, 213, 0.28);
  background: rgba(34, 34, 40, 0.92);
}
.ig-dock-adv-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.ig-sess-grid {
  flex-shrink: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 12px 4px;
  max-height: 120px;
  overflow-y: auto;
  border-bottom: 1px solid var(--ig-divider-soft);
}

.ig-sess-cell {
  width: 56px;
  height: 56px;
  padding: 0;
  border-radius: 10px;
  border: 2px solid transparent;
  overflow: hidden;
  cursor: pointer;
  background: rgba(0, 0, 0, 0.2);
}
.ig-sess-cell--on {
  border-color: rgba(94, 225, 213, 0.55);
}
.ig-sess-cell-img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.ig-studio-chat-scroll--rail {
  flex: 1;
  min-height: 0;
  max-height: none;
  border-bottom: none;
}

.ig-chat-panel-aside .ig-studio-chat-inner {
  max-width: 100%;
}

/* —— 图片工作台对话流（气泡） —— */
.ig-studio-chat-scroll {
  flex: 1;
  min-height: 0;
  max-height: min(42vh, 420px);
  overflow-y: auto;
  overflow-x: hidden;
  scrollbar-width: thin;
  scrollbar-color: rgba(94, 225, 213, 0.22) transparent;
  border-bottom: 1px solid var(--ig-divider-soft);
}

.ig-studio-chat-scroll::-webkit-scrollbar {
  width: 6px;
}
.ig-studio-chat-scroll::-webkit-scrollbar-thumb {
  background: rgba(94, 225, 213, 0.2);
  border-radius: 999px;
}

.ig-studio-chat-inner {
  max-width: min(920px, 94%);
  margin: 0 auto;
  padding: 12px 14px 14px;
  box-sizing: border-box;
}

.ig-studio-chat-hint {
  margin: 0 0 10px;
  font-size: 0.75rem;
  line-height: 1.5;
  color: var(--chat-muted);
  text-align: center;
}

.ig-studio-msg-list {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.ig-studio-turn {
  width: 100%;
}

.ig-studio-user-row {
  display: flex;
  justify-content: flex-end;
  width: 100%;
}

/* 与 ChatView 对话流一致：用户右侧圆角气泡 + 阴影 + 深浅色 */
.ig-studio-user-bubble {
  max-width: min(70vw, 560px);
  padding: 12px 14px;
  border-radius: 20px 20px 6px 20px;
  background: linear-gradient(
    145deg,
    rgba(94, 225, 213, 0.18) 0%,
    rgba(140, 160, 230, 0.12) 100%
  );
  border: 1px solid rgba(94, 225, 213, 0.22);
  box-shadow: 0 12px 36px rgba(0, 0, 0, 0.08);
}

html[data-theme='light'] .ig-studio-user-bubble {
  background: linear-gradient(
    145deg,
    rgba(204, 251, 241, 0.85) 0%,
    rgba(224, 231, 255, 0.65) 100%
  );
  border-color: rgba(13, 148, 136, 0.2);
}

.ig-studio-user-text {
  margin: 0;
  font-size: 0.875rem;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
  color: rgba(28, 32, 38, 0.92);
}

html[data-theme='dark'] .ig-studio-user-text,
html:not([data-theme]) .ig-studio-user-text {
  color: rgba(235, 238, 245, 0.94);
}

html[data-theme='light'] .ig-studio-user-text {
  color: rgba(28, 32, 38, 0.92);
}

.ig-studio-ai-col {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  max-width: min(96%, 820px);
}

/* 与 ChatView flow-ai-textcard 一致：毛玻璃卡片 */
.ig-studio-ai-bubble {
  border-radius: 16px;
  border: 1px solid var(--chat-border);
  background: rgba(255, 255, 255, 0.04);
  backdrop-filter: blur(12px);
  overflow: hidden;
}

html[data-theme='light'] .ig-studio-ai-bubble:not(.ig-studio-ai-bubble--err) {
  background: rgba(255, 255, 255, 0.72);
}

.ig-studio-ai-bubble--loading {
  padding: 12px 14px;
  min-width: 200px;
}

.ig-studio-loading-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}

.ig-studio-ai-loading {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 0;
  flex-shrink: 0;
}

.ig-studio-ai-loading-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: color-mix(in srgb, var(--chat-link-accent-fg) 75%, transparent);
  animation: ig-studio-ai-dot-bounce 0.9s ease-in-out infinite both;
}

.ig-studio-ai-loading-dot:nth-child(2) {
  animation-delay: 0.12s;
}

.ig-studio-ai-loading-dot:nth-child(3) {
  animation-delay: 0.24s;
}

@keyframes ig-studio-ai-dot-bounce {
  0%,
  80%,
  100% {
    transform: translateY(0);
    opacity: 0.45;
  }
  40% {
    transform: translateY(-5px);
    opacity: 1;
  }
}

.ig-studio-loading-msg {
  font-size: 0.8125rem;
  font-weight: 500;
  color: var(--chat-muted-2);
  letter-spacing: 0.02em;
}

.ig-studio-loading-bar {
  width: 100% !important;
  margin: 0 !important;
}

.ig-studio-ai-bubble--img {
  padding: 0;
  border-radius: 16px;
}

.ig-studio-chat-img-btn {
  display: block;
  padding: 0;
  margin: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  line-height: 0;
}

.ig-studio-chat-img {
  display: block;
  max-width: min(100%, 420px);
  max-height: 320px;
  width: auto;
  height: auto;
  object-fit: contain;
}

.ig-studio-ai-bubble--err {
  padding: 10px 14px;
  font-size: 0.8125rem;
  color: var(--chat-danger-fg);
  background: var(--chat-danger-bg);
  border-color: color-mix(in srgb, var(--chat-danger-fg) 28%, transparent);
  backdrop-filter: none;
}

.ig-studio-ai-err-text {
  margin: 0;
}

/* TransitionGroup：与 ChatView flowmsg 同向入场 */
.ig-studio-msg-move {
  transition: transform 0.38s cubic-bezier(0.22, 1, 0.36, 1);
}

.ig-studio-msg-enter-active {
  transition:
    opacity 0.45s ease-out,
    transform 0.55s cubic-bezier(0.34, 1.25, 0.64, 1);
}

.ig-studio-msg-enter-from[data-role='user'] {
  opacity: 0;
  transform: translate(18px, 22px) scale(0.96);
}

.ig-studio-msg-enter-from[data-role='assistant'] {
  opacity: 0;
  transform: translate(-14px, 18px);
}

@media (max-width: 768px) {
  .ig-studio-user-bubble {
    max-width: min(88vw, calc(100% - 8px));
  }

  .ig-studio-ai-col {
    max-width: 100%;
    min-width: 0;
  }
}

/* 生成中紧凑区：主进度在对话气泡 */
.ig-gen-compact {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  padding: 20px 16px;
  text-align: center;
}

.ig-gen-compact-preview {
  position: relative;
  max-width: min(100%, 360px);
  border-radius: 16px;
  overflow: hidden;
  border: 1px solid var(--chat-border);
}
.ig-gen-compact-preview img {
  display: block;
  width: 100%;
  max-height: 200px;
  object-fit: contain;
  background: rgba(0, 0, 0, 0.25);
}
.ig-gen-compact-dim {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.35);
  pointer-events: none;
}

.ig-gen-compact-placeholder {
  font-size: 0.8125rem;
  color: var(--chat-muted);
}

.ig-stop--compact {
  margin-top: 4px;
}

.ig-adv-overlay {
  position: fixed;
  inset: 0;
  z-index: 14000;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}

.ig-adv-modal {
  width: min(420px, 100%);
  max-height: min(80vh, 720px);
  border-radius: 16px;
  border: 1px solid var(--chat-border);
  background: var(--chat-shell-bg);
  color: var(--chat-fg);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.ig-adv-modal-head {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 14px;
  border-bottom: 1px solid var(--chat-border);
}

.ig-adv-modal-title {
  font-size: 0.875rem;
  font-weight: 750;
}

.ig-adv-modal-x {
  width: 32px;
  height: 32px;
  border-radius: 10px;
  border: none;
  background: transparent;
  color: var(--chat-muted);
  font-size: 1.1rem;
  cursor: pointer;
}
.ig-adv-modal-x:hover {
  background: var(--chat-btn-bg-hover);
  color: var(--chat-fg);
}

.ig-adv-modal-body {
  padding: 10px 12px 16px;
  overflow-y: auto;
}

.ig-dock-inner {
  display: flex;
  align-items: center;
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
  min-height: 72px;
  max-height: calc(1.5em * 6 + 28px);
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
  min-height: calc(1.5em * 2 + 8px);
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

/* —— 图片会话删除 / 工具切换确认：毛玻璃浮层 —— */
.ig-sdel-shell,
.ig-tsw-shell {
  position: fixed;
  inset: 0;
  z-index: 15080;
  pointer-events: auto;
}

.ig-sdel-backdrop {
  position: absolute;
  inset: 0;
  background: color-mix(in srgb, var(--chat-backdrop, rgba(0, 0, 0, 0.45)) 88%, #000);
  -webkit-backdrop-filter: blur(12px);
  backdrop-filter: blur(12px);
}

.ig-sdel-center {
  position: absolute;
  inset: 0;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: max(20px, env(safe-area-inset-top, 0px)) max(20px, env(safe-area-inset-right, 0px))
    max(20px, env(safe-area-inset-bottom, 0px)) max(20px, env(safe-area-inset-left, 0px));
  box-sizing: border-box;
  pointer-events: none;
}

.ig-sdel-panel {
  width: min(100%, 380px);
  pointer-events: auto;
  border-radius: 20px;
  border: 1px solid color-mix(in srgb, var(--chat-border-strong) 92%, transparent);
  background: color-mix(in srgb, var(--chat-panel) 76%, rgba(255, 255, 255, 0.06));
  -webkit-backdrop-filter: blur(22px);
  backdrop-filter: blur(22px);
  box-shadow:
    var(--chat-panel-shadow, 0 16px 48px rgba(0, 0, 0, 0.45)),
    inset 0 1px 0 color-mix(in srgb, var(--chat-fg-strong, #fff) 8%, transparent);
  padding: 22px 22px 18px;
  box-sizing: border-box;
}

.ig-sdel-title {
  margin: 0 0 10px;
  font-size: 1.0625rem;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--chat-fg-strong);
  text-align: center;
}

.ig-sdel-desc {
  margin: 0;
  font-size: 0.8125rem;
  line-height: 1.65;
  color: var(--chat-muted);
  text-align: center;
}

.ig-sdel-actions {
  display: flex;
  flex-direction: row;
  justify-content: flex-end;
  align-items: center;
  gap: 12px;
  margin-top: 22px;
}

.ig-sdel-btn {
  height: 42px;
  padding: 0 18px;
  border-radius: 11px;
  font-size: 0.875rem;
  font-weight: 600;
  cursor: pointer;
  transition:
    transform 0.18s ease,
    background 0.2s ease,
    border-color 0.2s ease,
    box-shadow 0.22s ease,
    opacity 0.2s ease;
}

.ig-sdel-btn:disabled {
  cursor: not-allowed;
  opacity: 0.45;
}

.ig-sdel-btn--ghost {
  border: 1px solid var(--chat-border-strong);
  background: transparent;
  color: var(--chat-muted-2);
}

.ig-sdel-btn--ghost:hover:not(:disabled) {
  background: var(--chat-btn-bg-hover);
  color: var(--chat-fg-strong);
}

.ig-sdel-btn--ghost:active:not(:disabled) {
  transform: scale(0.97);
}

.ig-sdel-btn--danger {
  border: 1px solid color-mix(in srgb, var(--chat-danger-fg, #f87171) 55%, transparent);
  color: #fff;
  background: linear-gradient(
    145deg,
    color-mix(in srgb, var(--chat-danger-fg, #f87171) 72%, #7f1d1d),
    color-mix(in srgb, var(--chat-link-accent-fg, #5ee1d5) 12%, #991b1b)
  );
  box-shadow: 0 8px 28px color-mix(in srgb, var(--chat-danger-fg, #f87171) 22%, transparent);
  min-width: 120px;
}

.ig-sdel-btn--danger:hover:not(:disabled) {
  transform: translateY(-1px);
  filter: brightness(1.06);
  box-shadow: 0 12px 32px color-mix(in srgb, var(--chat-danger-fg, #f87171) 28%, transparent);
}

.ig-sdel-btn--danger:active:not(:disabled) {
  transform: scale(0.97);
}

.ig-sdel-btn--accent {
  border: 1px solid color-mix(in srgb, var(--chat-link-accent-fg, #5ee1d5) 45%, transparent);
  color: var(--chat-fg-strong, #fff);
  background: linear-gradient(
    145deg,
    color-mix(in srgb, var(--chat-link-accent-fg, #5ee1d5) 38%, #0f766e),
    color-mix(in srgb, var(--chat-link-accent-fg, #5ee1d5) 12%, #134e4a)
  );
  box-shadow:
    0 8px 28px color-mix(in srgb, var(--chat-link-accent-fg, #5ee1d5) 22%, transparent),
    inset 0 1px 0 color-mix(in srgb, #fff 14%, transparent);
  min-width: 120px;
}

.ig-sdel-btn--accent:hover:not(:disabled) {
  transform: translateY(-1px);
  filter: brightness(1.05);
  box-shadow:
    0 12px 36px color-mix(in srgb, var(--chat-link-accent-fg, #5ee1d5) 32%, transparent),
    inset 0 1px 0 color-mix(in srgb, #fff 18%, transparent);
}

.ig-sdel-btn--accent:active:not(:disabled) {
  transform: scale(0.97);
}

.ig-tsw-kicker {
  margin: 0 0 6px;
  font-size: 0.6875rem;
  font-weight: 700;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  text-align: center;
  color: color-mix(in srgb, var(--chat-link-accent-fg, #5ee1d5) 72%, var(--chat-muted));
}

.ig-sdel-enter-active,
.ig-sdel-leave-active {
  transition: opacity 0.22s ease;
}

.ig-sdel-enter-active .ig-sdel-panel,
.ig-sdel-leave-active .ig-sdel-panel {
  transition:
    transform 0.24s cubic-bezier(0.34, 1.45, 0.64, 1),
    opacity 0.22s ease;
}

.ig-sdel-enter-from,
.ig-sdel-leave-to {
  opacity: 0;
}

.ig-sdel-enter-from .ig-sdel-panel,
.ig-sdel-leave-to .ig-sdel-panel {
  opacity: 0;
  transform: scale(0.94);
}

.ig-sdel-enter-to .ig-sdel-panel,
.ig-sdel-leave-from .ig-sdel-panel {
  opacity: 1;
  transform: scale(1);
}

.ig-tsw-enter-active,
.ig-tsw-leave-active {
  transition: opacity 0.22s ease;
}

.ig-tsw-enter-active .ig-sdel-panel,
.ig-tsw-leave-active .ig-sdel-panel {
  transition:
    transform 0.26s cubic-bezier(0.34, 1.45, 0.64, 1),
    opacity 0.22s ease;
}

.ig-tsw-enter-from,
.ig-tsw-leave-to {
  opacity: 0;
}

.ig-tsw-enter-from .ig-sdel-panel,
.ig-tsw-leave-to .ig-sdel-panel {
  opacity: 0;
  transform: scale(0.94) translateY(10px);
}

.ig-tsw-enter-to .ig-sdel-panel,
.ig-tsw-leave-from .ig-sdel-panel {
  opacity: 1;
  transform: scale(1) translateY(0);
}

@media (max-width: 480px) {
  .ig-sdel-actions {
    flex-direction: column-reverse;
  }

  .ig-sdel-btn {
    width: 100%;
  }
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
