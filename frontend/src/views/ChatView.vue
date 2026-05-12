<script setup>
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { streamChat } from '../api/chat'
import { fetchConversations, fetchConversationMessages } from '../api/conversations'
import { getAxiosErrorMessage } from '../utils/httpError'
import ChatComposerDock from '../components/chat/ChatComposerDock.vue'
import ChatEmptyState from '../components/chat/ChatEmptyState.vue'
import ChatGenImageCard from '../components/chat/ChatGenImageCard.vue'
import ChatConversationInpaintOverlay from '../components/chat/ChatConversationInpaintOverlay.vue'
import ChatProfileDrawer from '../components/chat/ChatProfileDrawer.vue'
import SiteMailBell from '../components/site-mail/SiteMailBell.vue'
import ChatSessionTimeline from '../components/chat/ChatSessionTimeline.vue'
import { findSkillById } from '../constants/chatSkills'
import {
  DEFAULT_FAST_FREEFORM_FAMILY,
  FAST_FREEFORM_FAMILY_IDS,
  migrateStoredPreference,
  apiModelIdForFamily,
} from '../constants/fastFreeformModels'
import { normalizeMessage, messagesToApiPayload } from '../utils/chatMessage'
import { sessionTitleFromUserContent, firstUserLinePlain } from '../utils/sessionTitle'
import { buildImageConversationContext } from '../utils/imageConversationContext'
import {
  VISION_PAYLOAD_SKILL_IDS,
  attachVisionImagesToLastUser,
  blobAttachmentsToImageParts,
} from '../utils/chatVisionPayload'
import { shouldShowFreeformNoImageHint } from '../utils/freeformImageCapabilityHint'
import {
  fetchConversationImages,
  uploadConversationImage,
  generateErnieConversationImage,
  generateErnieConversationImageInpaint,
} from '../api/conversationImages'
import { chatTurnPointsCost } from '../constants/pointCosts'

const STORAGE_FAST_FREEFORM_MODEL = 'uigpt_fast_freeform_model_v1'
const STORAGE_FAST_FREEFORM_DEEP = 'uigpt_fast_freeform_deep_reasoning_v1'

function readFastFreeformModelPref() {
  try {
    const raw = localStorage.getItem(STORAGE_FAST_FREEFORM_MODEL)
    return migrateStoredPreference(raw)
  } catch {
    /* ignore */
  }
  return DEFAULT_FAST_FREEFORM_FAMILY
}

function readFastFreeformDeepPref() {
  try {
    if (typeof localStorage === 'undefined') return true
    const v = localStorage.getItem(STORAGE_FAST_FREEFORM_DEEP)
    if (v === null || v === '') return true
    return v === '1'
  } catch {
    /* ignore */
  }
  return true
}

/** 稳定 key，供 TransitionGroup 与列表 diff */
let msgUid = 0


const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

/** 发往 API易 的对话 model id（已登录用户可选；访客由服务端默认） */
const fastFreeformModelId = ref(readFastFreeformModelPref())

/** 深度推理（更强推理路由；持久化） */
const freeformDeepReasoning = ref(readFastFreeformDeepPref())

/** 生成接口在 JSON 中返回 `b64_json` 时优先拼 data URL；历史列表仍仅有 imageUrl */
function displayUrlFromConversationImagePayload(data) {
  const b64 = data?.b64_json
  if (b64 != null && String(b64).trim() !== '') {
    const mime =
      data.b64_mime_type != null && String(data.b64_mime_type).trim() !== ''
        ? String(data.b64_mime_type).trim()
        : 'image/png'
    return `data:${mime};base64,${String(b64).trim()}`
  }
  return data?.imageUrl ?? ''
}

const messages = ref([])
/** 输入框旁参考图预览（object URL），发送后并入用户消息 */
const composerAttachments = ref([])
const selectedSkillId = ref('freeform')
const input = ref('')
const sending = ref(false)
const error = ref('')
/** 自由对话下检测到生图诉求时，提示本模式不自动出图 */
const freeformNoImageHint = ref('')
const FREEFORM_NO_IMAGE_HINT_TEXT =
  '当前为自由对话模式：不提供自动生成图片功能，界面不会展示配图结果；以上为模型的文字回复。'
const listRef = ref(null)
/** @type {import('vue').Ref<number | null>} */
const conversationId = ref(null)

/** 取助手消息前最近一条用户正文（去掉末尾「参考图」附言），不含兜底句 */
function userPlainBeforeAssistantFromList(msgs, assistantIdx) {
  for (let i = assistantIdx - 1; i >= 0; i--) {
    const u = msgs[i]
    if (u?.role === 'user') {
      return (u.content || '').replace(/\n\[参考图\][\s\S]*$/, '').trim()
    }
  }
  return ''
}

/** 取助手消息前最近一条用户正文（去掉末尾「参考图」附言），供文生图请求体 */
function lastUserPlainBefore(aiIdx) {
  const raw = userPlainBeforeAssistantFromList(messages.value, aiIdx)
  return raw || '（根据上下文出图）'
}

/** 历史会话重新出图：已无通用参数面板，固定比例与风格交由服务端默认处理 */
function legacyAspectForRegen() {
  return '1:1'
}

function legacyStyleForRegen() {
  return ''
}

const sidebarList = ref([])
const profileOpen = ref(false)
const profileWrapRef = ref(null)
const profileDrawerRef = ref(null)

/** 已有用户发言或载入的历史会话：消息区 + 底部输入栏 */
const chatActive = computed(() => messages.value.some((m) => m.role === 'user'))

function gallerySkillForMessage(m) {
  const id = m.skillId
  if (id === 'mockup' || id === 'wireframe' || id === 'retouch' || id === 'palette') return id
  const lb = m.skillLabel || ''
  if (lb.includes('效果图')) return 'mockup'
  if (lb.includes('原型')) return 'wireframe'
  if (lb.includes('修图')) return 'retouch'
  if (lb.includes('配色')) return 'palette'
  return 'mockup'
}

const inputPlaceholder = computed(() => findSkillById(selectedSkillId.value).placeholder)

/** 已登录用户本轮流式对话预扣积分（与后端 ChatController#chatTurnCost 一致；访客为 0） */
const composerChatTurnCost = computed(() =>
  auth.isAuthenticated ? chatTurnPointsCost(freeformDeepReasoning.value) : 0,
)

const chatInsufficientPointsForSend = computed(
  () => auth.isAuthenticated && auth.points < composerChatTurnCost.value,
)

/** 输入框占位轮播（dedupe，至少 2 条才启用轮播） */
const composerPlaceholderHints = computed(() => {
  const primary = findSkillById(selectedSkillId.value).placeholder
  const pool = [
    primary,
    '描述代码问题或需求',
    '描述设计需求或上传代码文件'
  ]
  const seen = new Set()
  const out = []
  for (const t of pool) {
    const s = String(t ?? '').trim()
    if (!s || seen.has(s)) continue
    seen.add(s)
    out.push(s)
  }
  return out
})

const activeConversationId = computed(() => {
  const q = route.query.conversation
  if (q == null || q === '') return null
  const n = Number(typeof q === 'string' ? q : Array.isArray(q) ? q[0] : '')
  return Number.isNaN(n) ? null : n
})

/** 顶栏：当前会话名（首条用户提问首行，不超过 10 字） */
const topBarSessionTitle = computed(() => {
  const firstUser = messages.value.find((m) => m.role === 'user')
  if (firstUser?.content) {
    const t = sessionTitleFromUserContent(firstUser.content, 10)
    if (t) return t
  }
  if (auth.isAuthenticated && conversationId.value != null) {
    const row = sidebarList.value.find((c) => c.id === conversationId.value)
    if (row?.title) {
      const t = sessionTitleFromUserContent(row.title, 10)
      return t || row.title
    }
  }
  return '新对话'
})

const topBarSessionTitleTooltip = computed(() => {
  const firstUser = messages.value.find((m) => m.role === 'user')
  if (firstUser?.content) {
    const full = firstUserLinePlain(firstUser.content)
    if (full) return full
  }
  if (auth.isAuthenticated && conversationId.value != null) {
    const row = sidebarList.value.find((c) => c.id === conversationId.value)
    if (row?.title) return row.title
  }
  return ''
})

async function scrollToEnd() {
  await nextTick()
  const el = listRef.value
  if (el) el.scrollTop = el.scrollHeight
}

watch(
  messages,
  () => {
    if (chatActive.value) scrollToEnd()
  },
  { deep: true },
)

async function loadSidebarConversations() {
  if (!auth.isAuthenticated) {
    sidebarList.value = []
    return
  }
  try {
    const { data } = await fetchConversations()
    sidebarList.value = data
  } catch {
    sidebarList.value = []
  }
}

function openConversation(id) {
  router.push({ path: '/chat', query: { conversation: String(id) } })
}

function revokeComposerAttachments() {
  for (const a of composerAttachments.value) {
    if (a.url?.startsWith('blob:')) URL.revokeObjectURL(a.url)
  }
  composerAttachments.value = []
}

function revokeAllMessageBlobs() {
  for (const m of messages.value) {
    m.attachments?.forEach((x) => {
      if (x.url?.startsWith('blob:')) URL.revokeObjectURL(x.url)
    })
  }
}

function newChat() {
  conversationId.value = null
  revokeComposerAttachments()
  revokeAllMessageBlobs()
  messages.value = []
  input.value = ''
  selectedSkillId.value = 'freeform'
  router.replace({ path: '/chat', query: {} })
  error.value = ''
  freeformNoImageHint.value = ''
}

function logout() {
  profileOpen.value = false
  auth.logout()
  conversationId.value = null
  messages.value = []
  sidebarList.value = []
  router.push('/login')
}

function onDocClick(e) {
  if (!(e.target instanceof Node)) return
  if (
    e.target.closest?.('.pp-shell') ||
    e.target.closest?.('.pp-modal-shell') ||
    e.target.closest?.('.sb-modal-backdrop') ||
    e.target.closest?.('.srl-modal-backdrop') ||
    e.target.closest?.('.gen-regen-backdrop') ||
    e.target.closest?.('.cvi-backdrop') ||
    e.target.closest?.('.sb-ctx-menu') ||
    e.target.closest?.('.srl-ctx') ||
    e.target.closest?.('.delconv-shell') ||
    e.target.closest?.('.site-mail-wrap') ||
    e.target.closest?.('.sm-shell')
  ) {
    return
  }
  const pw = profileWrapRef.value
  if (pw && !pw.contains(e.target)) {
    profileOpen.value = false
  }
}

/** 会话内局部重绘全屏流程（蒙版 → 提示词）；Escape 由 onDocKeydown 关闭 */
const inpaintOpen = ref(false)
/** @type {import('vue').Ref<number | null>} */
const inpaintAiIdx = ref(null)
const inpaintImageUrl = ref('')
const inpaintInitialPrompt = ref('')
const inpaintSubmitting = ref(false)

function closeConvInpaint() {
  inpaintOpen.value = false
  inpaintAiIdx.value = null
  inpaintImageUrl.value = ''
  inpaintInitialPrompt.value = ''
  inpaintSubmitting.value = false
}

watch(fastFreeformModelId, (v) => {
  try {
    if (FAST_FREEFORM_FAMILY_IDS.has(v)) localStorage.setItem(STORAGE_FAST_FREEFORM_MODEL, v)
  } catch {
    /* ignore */
  }
})

watch(freeformDeepReasoning, (v) => {
  try {
    localStorage.setItem(STORAGE_FAST_FREEFORM_DEEP, v ? '1' : '0')
  } catch {
    /* ignore */
  }
})

function onDocKeydown(e) {
  if (e.key !== 'Escape') return
  if (inpaintOpen.value) {
    closeConvInpaint()
    return
  }
  if (regenModalOpen.value) {
    closeGenRegenerateModal()
    return
  }
  if (!profileOpen.value) return
  profileOpen.value = false
}

onMounted(() => {
  if (auth.isAuthenticated) {
    void auth.refreshMe()
  }
  if (chatActive.value) scrollToEnd()
  document.addEventListener('click', onDocClick)
  document.addEventListener('keydown', onDocKeydown)
  loadSidebarConversations()
})

onUnmounted(() => {
  document.removeEventListener('click', onDocClick)
  document.removeEventListener('keydown', onDocKeydown)
  revokeComposerAttachments()
  revokeAllMessageBlobs()
})

watch(
  () => auth.isAuthenticated,
  (v) => {
    if (v) loadSidebarConversations()
    else sidebarList.value = []
  },
)

watch(
  () => ({ q: route.query.conversation, authed: auth.isAuthenticated }),
  async ({ q, authed }) => {
    if (!authed) {
      conversationId.value = null
      if (q != null && q !== '') {
        router.replace({ path: '/chat', query: {} })
      }
      return
    }
    if (q == null || q === '') {
      return
    }
    const id = Number(typeof q === 'string' ? q : Array.isArray(q) ? q[0] : '')
    if (Number.isNaN(id)) return
    error.value = ''
    freeformNoImageHint.value = ''
    try {
      const [msgRes, imgRes] = await Promise.all([
        fetchConversationMessages(id),
        fetchConversationImages(id, { offset: 0, limit: 200 }).catch(() => ({ data: [] })),
      ])
      const msgs = msgRes.data.map((m) => ({
        ...normalizeMessage(m),
        _key: ++msgUid,
      }))
      applyConversationImages(msgs, imgRes.data || [])
      messages.value = msgs
      conversationId.value = id
      await scrollToEnd()
    } catch (e) {
      error.value = getAxiosErrorMessage(e)
      router.replace({ path: '/chat', query: {} })
    }
  },
  { immediate: true },
)

/**
 * 将会话图片表合并进助手消息的 genCard（按 messageSortOrder 对齐 chat_messages.sort_order）
 * @param {ReturnType<normalizeMessage>[]} msgs
 * @param {Array<{ id: number, messageSortOrder: number, skillId: string, imageUrl: string, createdAt?: string }>} images
 */
function applyConversationImages(msgs, images) {
  const bySort = new Map()
  for (const im of images) {
    const k = im.messageSortOrder
    if (!bySort.has(k)) bySort.set(k, [])
    bySort.get(k).push(im)
  }
  for (let mi = 0; mi < msgs.length; mi++) {
    const m = msgs[mi]
    if (m.role !== 'assistant') continue
    const so = m.sortOrder
    if (so == null) continue
    const list = bySort.get(so)
    if (!list?.length) continue
    const sorted = [...list].sort((a, b) => {
      const ta = a.createdAt ? Date.parse(a.createdAt) : 0
      const tb = b.createdAt ? Date.parse(b.createdAt) : 0
      return tb - ta
    })
    if (!m.skillId && sorted[0]?.skillId) {
      m.skillId = sorted[0].skillId
    }
    const hint = userPlainBeforeAssistantFromList(msgs, mi)
    const card = {
      phase: 'done',
      statusText: '生成完成',
      progress: 100,
      collapsed: false,
      images: sorted.map((im) => ({
        id: String(im.id),
        serverImageId: im.id,
        url: im.imageUrl,
        favorite: Boolean(im.favorite),
        params: {},
      })),
    }
    if (hint) card.lastUserPrompt = hint
    m.genCard = card
  }
}

/**
 * 单张生成图写入 MinIO + chat_conversation_images（已有 serverImageId 则跳过）
 * @param {number} cid
 * @param {number} sortOrder
 * @param {string} skillId
 * @param {{ url: string, serverImageId?: number }} img
 * @param {string} fileStem
 */
async function uploadOneGenImageToMinio(cid, sortOrder, skillId, img, fileStem) {
  if (img?.serverImageId != null && img.serverImageId !== '') {
    return { ...img }
  }
  const blob = await fetch(img.url, { mode: 'cors' }).then((r) => r.blob())
  const fd = new FormData()
  fd.append('file', blob, `${fileStem}.png`)
  fd.append('messageSortOrder', String(sortOrder))
  fd.append('skillId', skillId)
  const { data } = await uploadConversationImage(cid, fd)
  return {
    ...img,
    url: data.imageUrl,
    serverImageId: data.id,
    favorite: Boolean(data.favorite),
  }
}

async function persistGenImagesToMinio(aiIdx, skillId) {
  const cid = conversationId.value
  const m = messages.value[aiIdx]
  if (!auth.isAuthenticated || cid == null || !m?.genCard?.images?.length) return
  const sortOrder = m.sortOrder ?? aiIdx
  const imgs = m.genCard.images
  for (let i = 0; i < imgs.length; i++) {
    try {
      imgs.splice(i, 1, await uploadOneGenImageToMinio(cid, sortOrder, skillId, imgs[i], `gen-${Date.now()}-${i}`))
    } catch (e) {
      console.warn('MinIO 上传失败（请检查服务与跨域）', e)
    }
  }
}

function onImageFavoriteChange() {
  profileDrawerRef.value?.refreshDashboard?.()
}

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms))
}

/** 重新生成：优先用卡片上图的「比例 / 风格」药丸，否则回落默认 */
function aspectStyleForRegenerate(m) {
  const img0 = m?.genCard?.images?.[0]
  const p = img0?.params || {}
  const aspect =
    typeof p.size === 'string' && p.size.trim() ? p.size.trim() : legacyAspectForRegen()
  const style =
    typeof p.style === 'string' && p.style.trim() ? p.style.trim() : legacyStyleForRegen()
  return { aspectKey: aspect, styleLabel: style }
}

/** 重新生成提示词弹窗 */
const regenModalOpen = ref(false)
/** @type {import('vue').Ref<number | null>} */
const regenModalAiIdx = ref(null)
const regenModalPrompt = ref('')

function openGenRegenerateModal(aiIdx) {
  const m = messages.value[aiIdx]
  if (!m?.genCard || m.genCard.phase !== 'done' || !m.genCard.images?.length) return
  const stored = m.genCard.lastUserPrompt
  const fromStored = typeof stored === 'string' && stored.trim() ? stored.trim() : ''
  const fromUser = userPlainBeforeAssistantFromList(messages.value, aiIdx)
  regenModalPrompt.value = fromStored || fromUser
  regenModalAiIdx.value = aiIdx
  regenModalOpen.value = true
  error.value = ''
}

function closeGenRegenerateModal() {
  regenModalOpen.value = false
  regenModalAiIdx.value = null
  regenModalPrompt.value = ''
}

async function confirmGenRegenerate() {
  const aiIdx = regenModalAiIdx.value
  const text = regenModalPrompt.value.trim()
  if (aiIdx == null) return
  if (!text) {
    error.value = '请输入提示词。'
    return
  }
  const m = messages.value[aiIdx]
  if (!m?.genCard) return

  const { aspectKey, styleLabel } = aspectStyleForRegenerate(m)
  const regenSkill = gallerySkillForMessage(m)

  closeGenRegenerateModal()

  mockAbort.value = false
  m.genCard = {
    ...m.genCard,
    phase: 'drawing',
    statusText: '重新生成中…',
    progress: 28,
    images: [],
    collapsed: false,
  }
  await sleep(900)
  await runImagePipeline(aiIdx, aspectKey, styleLabel, regenSkill, text, {
    conversationId: conversationId.value,
  })
}

/** 局部重绘提示词预填：上次本卡片的重绘词 > 出图 userMessage > 最近用户气泡 */
function inpaintPromptSeed(m, aiIdx) {
  const li = typeof m?.genCard?.lastInpaintPrompt === 'string' ? m.genCard.lastInpaintPrompt.trim() : ''
  if (li) return li
  const lu = typeof m?.genCard?.lastUserPrompt === 'string' ? m.genCard.lastUserPrompt.trim() : ''
  if (lu) return lu
  return lastUserPlainBefore(aiIdx)
}

function openConvInpaint(aiIdx) {
  const m = messages.value[aiIdx]
  const img = m?.genCard?.images?.[0]
  if (!auth.isAuthenticated || conversationId.value == null) {
    error.value = '请登录后在会话中使用局部重绘。'
    return
  }
  if (!m?.genCard || m.genCard.phase !== 'done' || !img?.url || img.serverImageId == null) {
    error.value = '请等待图片生成完成后再试，或刷新页面后重试。'
    return
  }
  inpaintAiIdx.value = aiIdx
  inpaintImageUrl.value = img.url
  inpaintInitialPrompt.value = inpaintPromptSeed(m, aiIdx)
  inpaintOpen.value = true
  error.value = ''
}

async function onConvInpaintSubmit({ prompt, maskPng }) {
  const aiIdx = inpaintAiIdx.value
  const cid = conversationId.value
  const m = aiIdx != null ? messages.value[aiIdx] : null
  if (!m?.genCard || cid == null || !maskPng) return

  const img0 = m.genCard.images?.[0]
  if (!img0?.serverImageId) return

  const text = (prompt || '').trim()
  if (!text) {
    error.value = '请输入局部重绘提示词。'
    return
  }

  const { aspectKey, styleLabel } = aspectStyleForRegenerate(m)
  const skillId = gallerySkillForMessage(m)

  const prevSnap = {
    phase: 'done',
    statusText: m.genCard.statusText,
    progress: 100,
    collapsed: m.genCard.collapsed,
    images: (m.genCard.images || []).map((i) => ({ ...i })),
    lastUserPrompt: m.genCard.lastUserPrompt,
    lastInpaintPrompt: m.genCard.lastInpaintPrompt,
    variantGroups: m.genCard.variantGroups,
    variantSelected: m.genCard.variantSelected,
  }

  inpaintSubmitting.value = true
  error.value = ''

  m.genCard = {
    ...m.genCard,
    phase: 'thinking',
    statusText: '正在提交局部重绘…',
    progress: 25,
    collapsed: false,
  }

  const fd = new FormData()
  fd.append('messageSortOrder', String(m.sortOrder ?? aiIdx))
  fd.append('skillId', skillId)
  fd.append('userMessage', text)
  if (m.content?.trim()) fd.append('assistantReply', m.content.trim())
  fd.append('sourceImageId', String(img0.serverImageId))
  fd.append('aspectKey', aspectKey)
  fd.append('styleLabel', styleLabel)
  fd.append('mask', maskPng, 'mask.png')
  const ctx = buildImageConversationContext(messages.value, aiIdx)
  if (ctx) fd.append('imageConversationContext', ctx)

  try {
    const { data } = await generateErnieConversationImageInpaint(cid, fd)
    m.genCard = {
      phase: 'done',
      statusText: '生成完成',
      progress: 100,
      collapsed: false,
      lastUserPrompt: prevSnap.lastUserPrompt,
      lastInpaintPrompt: text,
      images: [
        {
          id: String(data.id),
          serverImageId: data.id,
          url: displayUrlFromConversationImagePayload(data),
          favorite: Boolean(data.favorite),
          params: {
            size: aspectKey,
            style: styleLabel,
            model: 'GPT Image 局部重绘（APIYi）',
          },
        },
      ],
    }
    closeConvInpaint()
    void auth.refreshMe()
  } catch (e) {
    const msg = getAxiosErrorMessage(e)
    error.value = msg
    console.warn('局部重绘失败', e)
    m.genCard = {
      ...m.genCard,
      ...prevSnap,
      phase: 'done',
    }
  } finally {
    inpaintSubmitting.value = false
  }
}

/** 新一轮效果图开始时收起上一轮已完成卡片（时间线缩略条） */
function collapsePreviousGenCards() {
  for (const m of messages.value) {
    if (m.role === 'assistant' && m.genCard && m.genCard.phase === 'done' && m.genCard.images?.length) {
      m.genCard = { ...m.genCard, collapsed: true }
    }
  }
}

function setGenCardFailed(aiIdx, msg) {
  const m = messages.value[aiIdx]
  if (!m || m.role !== 'assistant') return
  const text = typeof msg === 'string' && msg.trim() ? msg.trim() : '图片生成失败'
  m.genCard = {
    phase: 'failed',
    statusText: text,
    progress: 0,
    images: [],
    collapsed: false,
  }
}

/**
 * 会话文生图：服务端调用 APIYi，写入 COS。
 * @param {number | null | undefined} [explicitConvId] 流式结束后返回的会话 id（避免 ref 尚未同步）
 */
async function runErnieImagePipeline(
  aiIdx,
  aspectKey,
  styleLabel,
  persistSkillId,
  userMessagePlain,
  explicitConvId,
) {
  const touch = () => messages.value[aiIdx]
  let m = touch()
  if (!m || m.role !== 'assistant') return

  const cid =
    explicitConvId != null && !Number.isNaN(Number(explicitConvId))
      ? Number(explicitConvId)
      : conversationId.value
  if (cid == null || Number.isNaN(cid)) {
    throw new Error('会话未就绪')
  }

  collapsePreviousGenCards()
  m.genCard = {
    phase: 'thinking',
    statusText: '正在生成绘图提示词并调用文生图模型…',
    progress: 30,
    images: [],
    collapsed: false,
  }

  const assistantText = m.content || ''
  const userMessage = (userMessagePlain || '').trim() || '（根据上下文出图）'
  const imageConversationContext = buildImageConversationContext(messages.value, aiIdx)
  const { data } = await generateErnieConversationImage(cid, {
    messageSortOrder: m.sortOrder ?? aiIdx,
    skillId: persistSkillId,
    userMessage,
    assistantReply: assistantText,
    imageConversationContext: imageConversationContext || undefined,
    aspectKey,
    styleLabel,
    tierMode: 'fast',
  })

  m = touch()
  if (!m || mockAbort.value) return

  m.genCard = {
    phase: 'done',
    statusText: '生成完成',
    progress: 100,
    collapsed: false,
    lastUserPrompt: userMessage,
    images: [
      {
        id: String(data.id),
        serverImageId: data.id,
        url: displayUrlFromConversationImagePayload(data),
        favorite: Boolean(data.favorite),
        params: {
          size: aspectKey,
          style: styleLabel,
          model: 'GPT Image（APIYi）',
        },
      },
    ],
  }
  void auth.refreshMe()
}

/**
 * 会话内文生图（服务端 APIYi → COS）。
 * @param {{ conversationId?: number | null }} [opts] 流式返回的会话 id，优先于 ref
 */
async function runImagePipeline(aiIdx, aspectKey, styleLabel, persistSkillId, userMessagePlain, opts = {}) {
  const m = messages.value[aiIdx]
  if (!m || m.role !== 'assistant') return

  const cidRaw = opts.conversationId ?? conversationId.value
  const cid =
    cidRaw != null && cidRaw !== '' && !Number.isNaN(Number(cidRaw)) ? Number(cidRaw) : null

  if (!auth.isAuthenticated) {
    const msg = '使用文生图请先登录；本站已不再使用演示占位图。'
    error.value = msg
    setGenCardFailed(aiIdx, msg)
    return
  }

  if (cid == null) {
    const msg = '会话未就绪，请重新发送本条消息。'
    error.value = msg
    setGenCardFailed(aiIdx, msg)
    return
  }

  try {
    await runErnieImagePipeline(aiIdx, aspectKey, styleLabel, persistSkillId, userMessagePlain, cid)
  } catch (e) {
    const msg = getAxiosErrorMessage(e)
    error.value = msg
    console.warn('文生图失败', e)
    setGenCardFailed(aiIdx, msg)
  }
}

/** @type {AbortController | null} */
let streamAbort = null
/** 演示出图阶段中止（流结束后仍会跑 mock，需单独打断） */
const mockAbort = ref(false)

function stopGeneration() {
  streamAbort?.abort()
  mockAbort.value = true
}

async function send() {
  const text = input.value.trim()
  if (!text || sending.value) return
  if (auth.isAuthenticated && auth.points < chatTurnPointsCost(freeformDeepReasoning.value)) return
  const fastFreeformFamilySnapshot = fastFreeformModelId.value
  const freeformDeepSnapshot = freeformDeepReasoning.value
  error.value = ''
  freeformNoImageHint.value = ''

  const attachmentSnap = composerAttachments.value.map((a) => ({ url: a.url, name: a.name }))
  let contentForApi = text
  if (attachmentSnap.length) {
    contentForApi += `\n[参考图] ${attachmentSnap.map((a) => a.name).join('、')}`
  }

  /** 发送失败（含流式中途网络错误）时整轮回滚，避免列表里已有用户气泡且输入框仍回填导致重复发送 */
  const snapshotBeforeSend = [...messages.value]

  const sortBase = messages.value.length
  const userMsg = {
    role: 'user',
    content: contentForApi,
    attachments: attachmentSnap,
    sortOrder: sortBase,
    _key: ++msgUid,
  }
  composerAttachments.value = []

  const history = [...snapshotBeforeSend, userMsg]
  messages.value = history

  const skillSnap = findSkillById(selectedSkillId.value)
  const dir = typeof skillSnap.directive === 'string' ? skillSnap.directive : ''
  const skillContext = dir.trim() ? dir : undefined
  const skillLabelForReply = skillSnap.label || ''

  const aiMsg = {
    role: 'assistant',
    content: '',
    skillLabel: skillLabelForReply,
    skillId: skillSnap.id,
    sortOrder: sortBase + 1,
    _key: ++msgUid,
  }
  messages.value = [...history, aiMsg]
  const aiIdx = messages.value.length - 1

  sending.value = true
  mockAbort.value = false
  input.value = ''
  streamAbort = new AbortController()

  try {
    let payload = messagesToApiPayload(messages.value.slice(0, -1))
    if (
      auth.isAuthenticated &&
      attachmentSnap.length > 0 &&
      VISION_PAYLOAD_SKILL_IDS.includes(skillSnap.id)
    ) {
      const imageParts = await blobAttachmentsToImageParts(attachmentSnap, 4)
      if (imageParts.length > 0) {
        payload = attachVisionImagesToLastUser(payload, imageParts)
      }
    }
    const streamExtras = {
      skillContext,
      skillId: skillSnap.id,
      signal: streamAbort.signal,
    }
    if (auth.isAuthenticated) {
      streamExtras.tierMode = 'fast'
      streamExtras.fastFreeformModel = apiModelIdForFamily(fastFreeformFamilySnapshot, {
        deepReasoning: freeformDeepSnapshot,
      })
      if (freeformDeepSnapshot) streamExtras.deepReasoning = true
    }
    await streamChat(
      payload,
      auth.isAuthenticated ? conversationId.value : undefined,
      {
        onDelta: (delta) => {
          messages.value[aiIdx].content += delta
        },
        onDone: async (cid) => {
          if (auth.isAuthenticated && cid != null && !Number.isNaN(cid)) {
            conversationId.value = cid
            await loadSidebarConversations()
          }
        },
      },
      streamExtras,
    )

    if (auth.isAuthenticated) {
      await auth.refreshMe()
    }

    if (auth.isAuthenticated) {
      void auth.refreshMe()
    }

    if (
      skillSnap.id === 'freeform' &&
      shouldShowFreeformNoImageHint({
        userText: contentForApi,
        assistantText: messages.value[aiIdx]?.content ?? '',
      })
    ) {
      freeformNoImageHint.value = FREEFORM_NO_IMAGE_HINT_TEXT
    }

    streamAbort = null
  } catch (e) {
    if (e?.name === 'AbortError') {
      error.value = ''
      return
    }
    messages.value = snapshotBeforeSend
    revokeComposerAttachments()
    composerAttachments.value = attachmentSnap
    input.value = text
    error.value = getAxiosErrorMessage(e)
  } finally {
    sending.value = false
    streamAbort = null
  }
}

function onKeydown(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    if (auth.isAuthenticated && auth.points < chatTurnPointsCost(freeformDeepReasoning.value)) return
    send()
  }
}

function toggleProfile(e) {
  e.stopPropagation()
  profileOpen.value = !profileOpen.value
}

function onConversationDeletedActive() {
  newChat()
}
</script>

<template>
  <div class="ds-shell">
    <div class="ds-main ds-main--chat-flow">
      <div class="ds-flow-bg" aria-hidden="true" />
      <header class="ds-topbar">
        <div class="ds-topbar-center">
          <span
            class="ds-topbar-title ds-topbar-title--session"
            :title="topBarSessionTitleTooltip || undefined"
          >
            {{ topBarSessionTitle }}
          </span>
          <button
            type="button"
            class="ds-new-session-btn"
            aria-label="新建会话"
            title="新建会话"
            @click="newChat"
          >
            新建会话
          </button>
        </div>
        <div class="ds-topbar-trailing">
          <SiteMailBell />
          <span v-if="auth.isAuthenticated" class="ds-points-chip" title="当前可用积分">积分 {{ auth.points }}</span>
          <div ref="profileWrapRef" class="ds-profile-wrap">
            <button
              type="button"
              class="ds-profile-trigger"
              aria-haspopup="menu"
              :aria-expanded="profileOpen"
              aria-label="个人中心"
              @click="toggleProfile"
            >
              <span class="ds-profile-avatar">{{ auth.isAuthenticated ? auth.username.slice(0, 1).toUpperCase() : '?' }}</span>
              <span class="ds-profile-text">个人中心</span>
            </button>
            <ChatProfileDrawer
              ref="profileDrawerRef"
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

      <div class="ds-chat-stage">
        <div class="ds-chat-stage-row">
          <div class="ds-chat-stage-main">
            <template v-if="chatActive">
              <div class="ds-chat-stage-fill">
                <div ref="listRef" class="ds-messages-scroll ds-messages-scroll--flow">
                  <div class="ds-messages-inner ds-messages-inner--flow">
                    <TransitionGroup name="flowmsg" tag="div" class="ds-flow-list">
                      <div
                        v-for="(m, i) in messages"
                        :key="m._key ?? i"
                        class="ds-flow-turn"
                        :data-role="m.role"
                        :data-turn-index="i"
                      >
                    <!-- 用户：右侧圆角气泡 + 参考缩略图 -->
                    <div v-if="m.role === 'user'" class="flow-user-row">
                      <article class="flow-user-bubble">
                        <div v-if="m.attachments?.length" class="flow-userimgs">
                          <a
                            v-for="(att, ai) in m.attachments"
                            :key="ai"
                            :href="att.url"
                            target="_blank"
                            rel="noopener noreferrer"
                            class="flow-userimg-wrap"
                          >
                            <img :src="att.url" alt="" class="flow-userimg" />
                          </a>
                        </div>
                        <p class="flow-user-text">{{ m.content }}</p>
                      </article>
                    </div>
                    <!-- 助手：文字卡片 + 可选生图卡片 -->
                    <div v-else class="flow-ai-col">
                      <span v-if="m.skillLabel" class="flow-ai-pill">{{ m.skillLabel }}</span>
                      <div v-if="m.content?.trim()" class="flow-ai-textcard">
                        <p class="flow-ai-text">{{ m.content }}</p>
                      </div>
                      <div
                        v-else-if="sending && i === messages.length - 1"
                        class="flow-ai-textcard flow-ai-textcard--pending"
                        role="status"
                        aria-live="polite"
                        aria-label="正在生成回复"
                      >
                        <div class="flow-ai-loading" aria-hidden="true">
                          <span class="flow-ai-loading-dot" />
                          <span class="flow-ai-loading-dot" />
                          <span class="flow-ai-loading-dot" />
                        </div>
                        <span class="flow-ai-loading-label">正在思考…</span>
                      </div>
                      <ChatGenImageCard
                        v-if="m.genCard"
                        v-model="m.genCard"
                        :conversation-id="conversationId"
                        :enable-favorite="auth.isAuthenticated"
                        @regenerate="openGenRegenerateModal(i)"
                        @inpaint="openConvInpaint(i)"
                        @toggle-collapse="m.genCard.collapsed = !m.genCard.collapsed"
                        @favorite-change="onImageFavoriteChange"
                      />
                    </div>
                      </div>
                    </TransitionGroup>
                  </div>
                </div>
              </div>
              <footer class="ds-composer ds-composer--anchored">
                <div v-if="freeformNoImageHint" class="ds-hint ds-hint--info" role="status">
                  <span class="ds-hint-text">{{ freeformNoImageHint }}</span>
                  <button type="button" class="ds-hint-dismiss" @click="freeformNoImageHint = ''">
                    知道了
                  </button>
                </div>
                <p v-if="error" class="ds-err">{{ error }}</p>
                <ChatComposerDock
                  v-model="input"
                  v-model:attachments="composerAttachments"
                  v-model:fast-freeform-model-id="fastFreeformModelId"
                  v-model:freeform-deep-reasoning="freeformDeepReasoning"
                  :show-fast-freeform-model-picker="auth.isAuthenticated"
                  :insufficient-points-for-send="chatInsufficientPointsForSend"
                  :sending="sending"
                  :is-streaming="sending"
                  :input-placeholder="inputPlaceholder"
                  :placeholder-hints="composerPlaceholderHints"
                  @send="send"
                  @stop="stopGeneration"
                  @keydown="onKeydown"
                />
              </footer>
            </template>
            <template v-else>
              <div class="ds-idle-stack">
                <ChatEmptyState />
                <footer class="ds-composer ds-composer--idle-centered">
                  <div v-if="freeformNoImageHint" class="ds-hint ds-hint--info" role="status">
                    <span class="ds-hint-text">{{ freeformNoImageHint }}</span>
                    <button type="button" class="ds-hint-dismiss" @click="freeformNoImageHint = ''">
                      知道了
                    </button>
                  </div>
                  <p v-if="error" class="ds-err">{{ error }}</p>
                  <ChatComposerDock
                    v-model="input"
                    v-model:attachments="composerAttachments"
                    v-model:fast-freeform-model-id="fastFreeformModelId"
                    v-model:freeform-deep-reasoning="freeformDeepReasoning"
                    :show-fast-freeform-model-picker="auth.isAuthenticated"
                    :insufficient-points-for-send="chatInsufficientPointsForSend"
                    :sending="sending"
                    :is-streaming="sending"
                    :input-placeholder="inputPlaceholder"
                    :placeholder-hints="composerPlaceholderHints"
                    @send="send"
                    @stop="stopGeneration"
                    @keydown="onKeydown"
                  />
                </footer>
              </div>
            </template>
          </div>
          <ChatSessionTimeline
            v-if="auth.isAuthenticated"
            :conversations="sidebarList"
            :active-conversation-id="activeConversationId"
            :is-authenticated="auth.isAuthenticated"
            @new-chat="newChat"
            @open-conversation="openConversation"
            @conversations-mutated="loadSidebarConversations"
            @deleted-active="onConversationDeletedActive"
          />
        </div>
      </div>
    </div>
  </div>
  <Teleport to="body">
    <div
      v-if="regenModalOpen"
      class="gen-regen-backdrop"
      role="dialog"
      aria-modal="true"
      aria-labelledby="gen-regen-title"
      @click.self="closeGenRegenerateModal"
    >
      <div class="gen-regen-panel" @click.stop>
        <h2 id="gen-regen-title" class="gen-regen-title">重新生成</h2>
        <label class="gen-regen-label" for="gen-regen-ta">提示词</label>
        <textarea
          id="gen-regen-ta"
          v-model="regenModalPrompt"
          class="gen-regen-textarea"
          rows="5"
          placeholder="描述画面内容、风格与构图等"
        />
        <div class="gen-regen-actions">
          <button type="button" class="gen-regen-btn gen-regen-btn--ghost" @click="closeGenRegenerateModal">
            取消
          </button>
          <button type="button" class="gen-regen-btn gen-regen-btn--primary" @click="confirmGenRegenerate">
            重新生成
          </button>
        </div>
      </div>
    </div>
  </Teleport>
  <ChatConversationInpaintOverlay
    v-model="inpaintOpen"
    :image-url="inpaintImageUrl"
    :initial-prompt="inpaintInitialPrompt"
    :submitting="inpaintSubmitting"
    @close="closeConvInpaint"
    @submit="onConvInpaintSubmit"
  />
</template>

<style scoped>
.ds-shell {
  display: flex;
  min-height: 0;
  height: 100%;
  background: var(--chat-shell-bg);
  color: var(--chat-fg);
  max-width: 100%;
  overflow-x: clip;
}

.ds-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  position: relative;
  /* 聊天主列缩放（略收紧，避免气泡/标题过大）；窄屏用 100% 避免 vw + 内边距溢出 */
  --ds-chat-scale: 1.06;
  --ds-chat-col-max: min(920px, 72vw);
}

/* 对话流：动态浅色底（深色主题为低饱和纹理感） */
.ds-main--chat-flow {
  overflow: hidden;
}

.ds-flow-bg {
  pointer-events: none;
  position: absolute;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  background: var(--chat-flow-bg-base);
}

/* 顶栏必须高于下方聊天区，否则个人中心下拉超出顶栏边界后会被消息层盖住且无法点击 */
.ds-main--chat-flow > .ds-topbar {
  position: relative;
  z-index: 20;
}

.ds-main--chat-flow > .ds-chat-stage,
.ds-main--chat-flow > .ds-composer {
  position: relative;
  z-index: 1;
}

.ds-topbar {
  flex-shrink: 0;
  height: 52px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  align-items: center;
  column-gap: 8px;
  padding: 0 max(12px, env(safe-area-inset-right, 0px)) 0 max(12px, env(safe-area-inset-left, 0px));
  border-bottom: 1px solid var(--chat-border);
  background: var(--chat-topbar-bg);
  backdrop-filter: blur(12px);
  position: relative;
}

.ds-topbar-center {
  grid-column: 2;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  max-width: min(calc(100vw - 200px), 520px);
}

.ds-topbar-title {
  font-size: 0.875rem;
  font-weight: 600;
  color: var(--chat-muted);
}

.ds-topbar-title--session {
  display: block;
  min-width: 0;
  flex: 1 1 auto;
  max-width: min(42vw, 280px);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 0.9375rem;
  font-weight: 650;
  color: var(--chat-fg-strong);
  letter-spacing: 0.02em;
}

.ds-new-session-btn {
  flex-shrink: 0;
  padding: 6px 12px;
  border-radius: 999px;
  border: 1px solid var(--chat-border-strong);
  background: var(--chat-btn-bg);
  color: var(--chat-fg-strong);
  font-size: 0.8125rem;
  font-weight: 600;
  cursor: pointer;
  transition:
    background 0.15s ease,
    border-color 0.15s ease;
}

.ds-new-session-btn:hover {
  background: var(--chat-btn-bg-hover);
  border-color: var(--chat-border-strong);
}

.ds-topbar-trailing {
  grid-column: 3;
  justify-self: end;
  display: flex;
  align-items: center;
  gap: 8px;
}

.ds-points-chip {
  flex-shrink: 0;
  padding: 4px 10px;
  border-radius: 999px;
  border: 1px solid var(--chat-border-strong);
  background: rgba(255, 255, 255, 0.04);
  color: var(--chat-muted);
  font-size: 0.75rem;
  font-weight: 600;
  letter-spacing: 0.02em;
}

/* 收起右侧栏时：顶栏「新建」与旧侧栏窄轨按钮一致 */
.ds-profile-wrap {
  position: relative;
}

.ds-profile-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px 6px 6px;
  border-radius: 999px;
  border: 1px solid var(--chat-profile-border);
  background: var(--chat-profile-bg);
  color: var(--chat-fg);
  font-size: 0.8125rem;
  cursor: pointer;
  transition:
    background 0.15s,
    border-color 0.15s;
}

.ds-profile-trigger:hover {
  background: var(--chat-btn-bg-hover);
  border-color: var(--chat-border-strong);
}

.ds-profile-avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: linear-gradient(
    135deg,
    var(--chat-avatar-gradient-start),
    var(--chat-avatar-gradient-end)
  );
  color: var(--chat-send-fg);
  font-weight: 700;
  font-size: 0.75rem;
  display: flex;
  align-items: center;
  justify-content: center;
}

.ds-profile-text {
  padding-right: 4px;
}

.ds-chat-stage {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  align-items: stretch;
  width: 100%;
}

.ds-chat-stage-row {
  flex: 1;
  display: flex;
  flex-direction: row;
  align-items: stretch;
  min-height: 0;
  min-width: 0;
  position: relative;
}

.ds-chat-stage-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.ds-chat-stage-fill {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

/** 未发消息前：欢迎语 + 大号线路切换 + 输入框整体垂直居中 */
.ds-idle-stack {
  flex: 1;
  min-height: 0;
  width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: calc(16px * var(--ds-chat-scale)) calc(20px * var(--ds-chat-scale))
    calc(24px * var(--ds-chat-scale));
  box-sizing: border-box;
  gap: 0;
  overflow-y: auto;
}

.ds-idle-hero {
  flex-shrink: 0;
  text-align: center;
  padding: 0 calc(24px * var(--ds-chat-scale)) calc(20px * var(--ds-chat-scale));
  max-width: calc(520px * var(--ds-chat-scale));
  margin: 0 auto;
  box-sizing: border-box;
}

.ds-idle-mode {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px;
  margin-bottom: calc(22px * var(--ds-chat-scale));
  border-radius: 999px;
  border: 1px solid var(--chat-border-strong);
  background: var(--chat-panel);
  flex-shrink: 0;
}

.ds-idle-mode-btn {
  padding: 12px 28px;
  border-radius: 999px;
  border: none;
  background: transparent;
  color: var(--chat-muted-2);
  font-size: 0.875rem;
  font-weight: 600;
  cursor: pointer;
  transition:
    color 0.15s,
    background 0.15s;
}

.ds-idle-mode-btn:hover {
  color: var(--chat-fg);
}

.ds-idle-mode-btn--on {
  background: var(--chat-mode-on-bg);
  color: var(--chat-fg-strong);
}

.ds-welcome-title {
  margin: 0 0 calc(12px * var(--ds-chat-scale));
  font-size: calc(1.15rem * var(--ds-chat-scale));
  font-weight: 700;
  letter-spacing: 0.02em;
  color: var(--chat-fg-strong);
  line-height: 1.35;
}

.ds-idle-sub {
  margin: 0 auto;
  max-width: 28em;
  font-size: calc(0.8125rem * var(--ds-chat-scale));
  line-height: 1.55;
  color: var(--chat-muted-3);
}

/* 居中对话流 + 细滚动条 */
.ds-messages-scroll--flow {
  scrollbar-width: thin;
  scrollbar-color: rgba(94, 225, 213, 0.25) transparent;
}

.ds-messages-scroll--flow::-webkit-scrollbar {
  width: 5px;
}

.ds-messages-scroll--flow::-webkit-scrollbar-thumb {
  background: rgba(94, 225, 213, 0.22);
  border-radius: 999px;
}

.ds-messages-scroll--flow:hover::-webkit-scrollbar {
  width: 8px;
}

.ds-messages-inner--flow {
  max-width: var(--ds-chat-col-max);
  width: 100%;
  margin: 0 auto;
  padding: calc(20px * var(--ds-chat-scale)) max(5vw, 16px)
    calc(20px * var(--ds-chat-scale));
  box-sizing: border-box;
}

.ds-flow-list {
  display: flex;
  flex-direction: column;
  gap: calc(18px * var(--ds-chat-scale));
}

/* 勿用 display:contents，否则 TransitionGroup 入场动画失效 */
.ds-flow-turn {
  width: 100%;
}

.flow-user-row {
  display: flex;
  justify-content: flex-end;
  width: 100%;
}

.flow-user-bubble {
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

html[data-theme='light'] .flow-user-bubble {
  background: linear-gradient(
    145deg,
    rgba(204, 251, 241, 0.85) 0%,
    rgba(224, 231, 255, 0.65) 100%
  );
  border-color: rgba(13, 148, 136, 0.2);
}

.flow-userimgs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
}

.flow-userimg-wrap {
  display: block;
  border-radius: 12px;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.15);
  transition: transform 0.28s cubic-bezier(0.34, 1.36, 0.64, 1);
}

.flow-userimg-wrap:hover {
  transform: scale(1.08);
  z-index: 2;
}

.flow-userimg {
  width: 72px;
  height: 72px;
  object-fit: cover;
  display: block;
}

.flow-user-text {
  margin: 0;
  font-size: calc(0.875rem * var(--ds-chat-scale));
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
  color: rgba(28, 32, 38, 0.92);
}

html[data-theme='dark'] .flow-user-text {
  color: rgba(235, 238, 245, 0.94);
}

.flow-ai-col {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 10px;
  max-width: min(96%, 820px);
}

.flow-ai-pill {
  align-self: flex-start;
  padding: 4px 10px;
  border-radius: 999px;
  font-size: calc(0.65625rem * var(--ds-chat-scale));
  font-weight: 700;
  color: var(--chat-link-accent-fg);
  background: rgba(94, 225, 213, 0.12);
  border: 1px solid rgba(94, 225, 213, 0.22);
}

.flow-ai-textcard {
  padding: 12px 14px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid var(--chat-border);
  backdrop-filter: blur(12px);
}

html[data-theme='light'] .flow-ai-textcard {
  background: rgba(255, 255, 255, 0.72);
}

.flow-ai-textcard--pending {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 48px;
}

.flow-ai-loading {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 0;
}

.flow-ai-loading-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: color-mix(in srgb, var(--chat-link-accent-fg) 75%, transparent);
  animation: flow-ai-dot-bounce 0.9s ease-in-out infinite both;
}

.flow-ai-loading-dot:nth-child(2) {
  animation-delay: 0.12s;
}

.flow-ai-loading-dot:nth-child(3) {
  animation-delay: 0.24s;
}

@keyframes flow-ai-dot-bounce {
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

.flow-ai-loading-label {
  font-size: calc(0.8125rem * var(--ds-chat-scale));
  font-weight: 500;
  color: var(--chat-muted-2);
  letter-spacing: 0.02em;
}

.flow-ai-text {
  margin: 0;
  font-size: calc(0.875rem * var(--ds-chat-scale));
  line-height: 1.58;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--chat-fg);
}

/* TransitionGroup：消息入场 */
.flowmsg-move {
  transition: transform 0.38s cubic-bezier(0.22, 1, 0.36, 1);
}

.flowmsg-enter-active {
  transition:
    opacity 0.45s ease-out,
    transform 0.55s cubic-bezier(0.34, 1.25, 0.64, 1);
}

.flowmsg-enter-from[data-role='user'] {
  opacity: 0;
  transform: translate(18px, 22px) scale(0.96);
}

.flowmsg-enter-from[data-role='assistant'] {
  opacity: 0;
  transform: translate(-14px, 18px);
}

@media (max-width: 768px) {
  .flow-user-bubble {
    max-width: min(88vw, calc(100% - 8px));
  }

  .flow-ai-col {
    max-width: 100%;
    min-width: 0;
  }
}

/* 全宽滚动条贴在视口右侧，内容仍居中 */
.ds-messages-scroll {
  flex: 1;
  min-height: 0;
  width: 100%;
  overflow-x: hidden;
  overflow-y: auto;
}

.ds-messages-inner {
  max-width: var(--ds-chat-col-max);
  margin: 0 auto;
  padding: calc(24px * var(--ds-chat-scale)) calc(20px * var(--ds-chat-scale))
    calc(16px * var(--ds-chat-scale));
  display: flex;
  flex-direction: column;
  gap: calc(16px * var(--ds-chat-scale));
  box-sizing: border-box;
}

/* 底部输入区外层：为悬浮胶囊留出侧向与底部呼吸空间 */
.ds-composer {
  flex-shrink: 0;
  width: 100%;
  max-width: var(--ds-chat-col-max);
  margin: 0 auto;
  padding: 0 calc(12px * var(--ds-chat-scale)) calc(6px * var(--ds-chat-scale));
  box-sizing: border-box;
}

.ds-composer--anchored {
  margin-top: auto;
}

.ds-composer--idle-centered {
  padding-bottom: calc(8px * var(--ds-chat-scale));
  width: 100%;
  max-width: var(--ds-chat-col-max);
}

.ds-hint {
  display: flex;
  align-items: flex-start;
  gap: calc(10px * var(--ds-chat-scale));
  margin: 0 0 calc(10px * var(--ds-chat-scale));
  padding: calc(10px * var(--ds-chat-scale)) calc(12px * var(--ds-chat-scale));
  font-size: calc(0.78125rem * var(--ds-chat-scale));
  border-radius: calc(10px * var(--ds-chat-scale));
  border: 1px solid var(--chat-border-strong);
  background: var(--chat-panel);
  color: var(--chat-fg);
}

.ds-hint-text {
  flex: 1;
  min-width: 0;
  line-height: 1.45;
  color: var(--chat-muted);
}

.ds-hint-dismiss {
  flex-shrink: 0;
  margin: 0;
  padding: calc(4px * var(--ds-chat-scale)) calc(10px * var(--ds-chat-scale));
  font-size: calc(0.75rem * var(--ds-chat-scale));
  border-radius: calc(8px * var(--ds-chat-scale));
  border: 1px solid var(--chat-border);
  background: var(--chat-btn-bg);
  color: var(--chat-fg-strong);
  cursor: pointer;
}

.ds-hint-dismiss:hover {
  background: var(--chat-btn-bg-hover);
}

.ds-err {
  margin: 0 0 calc(10px * var(--ds-chat-scale));
  padding: 0 calc(8px * var(--ds-chat-scale));
  font-size: calc(0.78125rem * var(--ds-chat-scale));
  color: var(--chat-danger-fg);
}

@media (max-width: 768px) {
  /* 桌面端放大阅读；手机宽度有限，保持 1rem 基准避免欢迎语/气泡/输入框突兀偏大 */
  .ds-main {
    --ds-chat-scale: 1;
    --ds-chat-col-max: min(920px, 100%);
  }

  .ds-profile-text {
    display: none;
  }

  .ds-topbar {
    height: auto;
    min-height: calc(52px + env(safe-area-inset-top, 0px));
    padding-top: env(safe-area-inset-top, 0px);
  }

  .ds-shell {
    min-height: 0;
    height: 100%;
    padding-bottom: env(safe-area-inset-bottom, 0px);
  }

  .ds-messages-inner,
  .ds-messages-inner--flow {
    padding-left: max(calc(16px * var(--ds-chat-scale)), env(safe-area-inset-left, 0px));
    padding-right: max(calc(16px * var(--ds-chat-scale)), env(safe-area-inset-right, 0px));
  }

  .ds-idle-stack {
    padding-left: max(calc(16px * var(--ds-chat-scale)), env(safe-area-inset-left, 0px));
    padding-right: max(calc(16px * var(--ds-chat-scale)), env(safe-area-inset-right, 0px));
  }

  .ds-composer,
  .ds-composer--idle-centered {
    padding-left: max(calc(10px * var(--ds-chat-scale)), env(safe-area-inset-left, 0px));
    padding-right: max(calc(10px * var(--ds-chat-scale)), env(safe-area-inset-right, 0px));
  }
}

/* 重新生成提示词（Teleport 至 body，仍随本组件 scoped） */
.gen-regen-backdrop {
  position: fixed;
  inset: 0;
  z-index: 24000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: rgba(0, 0, 0, 0.55);
  backdrop-filter: blur(8px);
}

.gen-regen-panel {
  width: min(100%, 440px);
  padding: 20px 22px 18px;
  border-radius: 16px;
  border: 1px solid var(--chat-border-strong);
  background: var(--chat-shell-bg);
  color: var(--chat-fg);
  box-shadow: 0 24px 64px rgba(0, 0, 0, 0.35);
}

.gen-regen-title {
  margin: 0 0 14px;
  font-size: 1.05rem;
  font-weight: 600;
}

.gen-regen-label {
  display: block;
  margin-bottom: 8px;
  font-size: 0.8125rem;
  color: var(--chat-muted-2);
}

.gen-regen-textarea {
  width: 100%;
  box-sizing: border-box;
  min-height: 120px;
  padding: 10px 12px;
  margin-bottom: 16px;
  border-radius: 12px;
  border: 1px solid var(--chat-border);
  background: rgba(255, 255, 255, 0.04);
  color: var(--chat-fg);
  font-size: 0.875rem;
  line-height: 1.45;
  resize: vertical;
  font-family: inherit;
}

html[data-theme='light'] .gen-regen-textarea {
  background: rgba(15, 23, 42, 0.04);
}

.gen-regen-textarea:focus {
  outline: none;
  border-color: var(--chat-link-accent-fg);
  box-shadow: 0 0 0 2px rgba(94, 225, 213, 0.2);
}

.gen-regen-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.gen-regen-btn {
  padding: 8px 16px;
  border-radius: 10px;
  font-size: 0.875rem;
  cursor: pointer;
  border: 1px solid transparent;
}

.gen-regen-btn--ghost {
  border-color: var(--chat-border);
  background: transparent;
  color: var(--chat-muted-2);
}

.gen-regen-btn--ghost:hover {
  color: var(--chat-fg);
  border-color: var(--chat-link-accent-fg);
}

.gen-regen-btn--primary {
  border: none;
  background: linear-gradient(135deg, var(--chat-link-accent-fg), rgba(94, 225, 213, 0.75));
  color: #0f172a;
  font-weight: 600;
}

.gen-regen-btn--primary:hover {
  filter: brightness(1.06);
}
</style>
