<script setup>
/**
 * 视频创作工作台（Sora 2 能力 UI）：深色 / 毛玻璃 / 品牌色 #10B981，与图片创作模块视觉对齐。
 * 文生 / 图生视频走后端代理 API 易 Sora 2 官转（异步提交 → 轮询 → finalize 落库）；其它模式仍为 UI 占位。
 */
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import ChatProfileDrawer from '../components/chat/ChatProfileDrawer.vue'
import { optimizeImageStudioPrompt } from '../api/imageStudio'
import { submitSora2Video, submitSora2VideoMultipart, getSora2Task, finalizeSora2Task } from '../api/videoStudio'
import { fetchMeImagesPage } from '../api/meProfile'
import { getAxiosErrorMessage } from '../utils/httpError'

const router = useRouter()
const auth = useAuthStore()

/** @typedef {'empty'|'generating'|'done'} PreviewPhase */
/** @typedef {'txt2vid'|'img2vid'|'vid2vid'|'character'|'audioSync'|'extend'} VideoMode */

const profileOpen = ref(false)
const profileWrapRef = ref(null)
const fileInputRef = ref(null)
/** 图生视频专用：起始帧 */
const img2vidRefInputRef = ref(null)
const videoRef = ref(null)

/** @type {import('vue').Ref<File|null>} */
const img2vidReferenceFile = ref(null)
/** @type {import('vue').Ref<string|null>} */
const img2vidReferencePreview = ref(null)

/** @type {import('vue').Ref<PreviewPhase>} */
const previewPhase = ref('empty')
/** @type {import('vue').Ref<VideoMode>} */
const activeMode = ref('txt2vid')

const panelCollapsed = ref(false)
const projectDrawerOpen = ref(false)
const keyframeMode = ref(false)
const advancedOpen = ref(false)
const exportModalOpen = ref(false)
const extractFrameOpen = ref(false)
const qcMode = ref(false)

const prompt = ref('')
const progressMsg = ref('')
const progressPct = ref(0)
const etaSeconds = ref(45)
/** @type {import('vue').Ref<string|null>} */
const resultVideoUrl = ref(null)

const remainingQuotaSec = ref(120)

/** @type {import('vue').Ref<{ id: string, url: string, durationLabel: string, at: number, title: string }[]>} */
const recentVideos = ref([])

/** Sora 2 官转仅支持 4 / 8 / 12 秒（字符串枚举） */
const durationSec = ref(8)
/** 视频工作台默认固定为 OpenAI {@code sora-2}（标准版，仅 720p 横竖两档） */
const aspectId = ref('16:9')
const motionStrength = ref(5)
const styleId = ref('cinematic')

/** 模式专属（演示数据绑定） */
const refImageStrength = ref(62)
const motionDir = ref('dolly_in')
const lockFirstFrame = ref(true)
const refVideoStrength = ref(55)
const repaintAmount = ref(40)
const timeRangeStart = ref(0)
const timeRangeEnd = ref(8)
const consistencyStrength = ref(8)
const expressionFreedom = ref(5)
const beatDetect = ref('auto')
const cutFrequency = ref('beat')
const emotionMap = ref('dynamic')
const extendDir = ref('forward')
const extendExtraSec = ref(10)
const extendBlend = ref(7)

const seedMode = ref('random')
const seedValue = ref('420913')
const negativePrompt = ref('')
const physicsPrecision = ref('standard')
const multiShot = ref('single')

/** @type {AbortController | null} */
let genAbort = null

const statusHint = ref('就绪')
const promptOptimizing = ref(false)

const modes = [
  { id: 'txt2vid', label: '文生视频', icon: '📝', hint: '文本描述生成视频' },
  { id: 'img2vid', label: '图生视频', icon: '🖼️', hint: '图片驱动动态' },
  { id: 'vid2vid', label: '视频生视频', icon: '🎬', hint: '参考视频风格/扩展' },
  { id: 'character', label: '角色一致性', icon: '🎭', hint: '跨镜头保持角色' },
  { id: 'audioSync', label: '音画同步', icon: '🎵', hint: '音频驱动画面节奏' },
  { id: 'extend', label: '视频扩展', icon: '🔄', hint: '向前/向后延长时长' },
]

const styleOptions = [
  { id: 'realistic', label: '写实', swatch: '#e8c4b0' },
  { id: 'cinematic', label: '电影感', swatch: '#3f3f46' },
  { id: 'anime', label: '动画', swatch: '#f472b6' },
  { id: '3d', label: '3D', swatch: '#38bdf8' },
  { id: 'pixel', label: '像素', swatch: '#22c55e' },
  { id: 'ink', label: '水墨', swatch: '#a8a29e' },
]

const aspects = [
  { id: '16:9', label: '16:9' },
  { id: '9:16', label: '9:16' },
  { id: '1:1', label: '1:1' },
  { id: '21:9', label: '21:9' },
  { id: '4:3', label: '4:3' },
]

const motionDirs = [
  { id: 'up', label: '↑' },
  { id: 'down', label: '↓' },
  { id: 'left', label: '←' },
  { id: 'right', label: '→' },
  { id: 'dolly_in', label: '推近' },
  { id: 'dolly_out', label: '拉远' },
  { id: 'orbit', label: '环绕' },
]

const examplePrompts = [
  '一只橘猫在窗台上晒太阳，慢镜头',
  '赛博朋克城市夜景，无人机航拍视角',
  '海浪拍打礁石，电影级调色',
  '水墨山水，仙鹤飞过，国风动画',
]

const videoDurationDisplay = computed(() => {
  const el = videoRef.value
  if (el && Number.isFinite(el.duration) && el.duration > 0) {
    return `${el.duration.toFixed(1)}s`
  }
  return `${durationSec.value}s`
})

const timelineTicks = computed(() => {
  const total = durationSec.value
  const step = total <= 4 ? 2 : 4
  const ticks = []
  for (let t = 0; t <= total; t += step) ticks.push(t)
  if (ticks[ticks.length - 1] !== total) ticks.push(total)
  return ticks
})

const keyframes = ref([
  { id: 'kf0', t: 0, motion: '镜头缓慢推近', camera: '推轨 + 浅景深' },
  { id: 'kf1', t: 4, motion: '环绕主体半圈', camera: '环绕 180°' },
])

const selectedKeyframeId = ref('kf0')

const selectedKeyframe = computed(() => keyframes.value.find((k) => k.id === selectedKeyframeId.value) ?? null)

const videoCurrentTime = ref(0)

function addKeyframe() {
  keyframes.value.push({
    id: `kf${Date.now()}`,
    t: Math.round(durationSec.value / 2),
    motion: '',
    camera: '',
  })
}

function updateKeyframeField(field, e) {
  const k = selectedKeyframe.value
  if (!k || !(e.target instanceof HTMLInputElement)) return
  k[field] = e.target.value
}

watch(durationSec, (d) => {
  const cap = Math.min(d, 12)
  timeRangeEnd.value = Math.min(timeRangeEnd.value, cap)
  timeRangeStart.value = Math.min(timeRangeStart.value, timeRangeEnd.value)
})

function sleep(ms) {
  return new Promise((resolve) => {
    window.setTimeout(resolve, ms)
  })
}

function computeSoraModel() {
  return 'sora-2'
}

/** 标准版仅 720p：按画幅映射为 {@code 720x1280} / {@code 1280x720}。 */
function computeSoraSize() {
  const aspect = aspectId.value
  const portrait = aspect === '9:16' || aspect === '1:1'
  return portrait ? '720x1280' : '1280x720'
}

function parseSizeWxH(sizeStr) {
  const parts = String(sizeStr).split('x')
  const w = parseInt(parts[0], 10)
  const h = parseInt(parts[1], 10)
  if (!Number.isFinite(w) || !Number.isFinite(h) || w <= 0 || h <= 0) {
    throw new Error('无效分辨率')
  }
  return { w, h }
}

/**
 * 输出与官方 {@code size} 完全一致的 PNG（像素级匹配），避免 `Inpaint image must match…`。
 * 采用「等比放大 + 居中裁切」（cover），与文档建议的 crop 再对齐目标宽高一致，减少拉伸变形。
 */
async function buildReferencePngBlob(file, targetW, targetH) {
  const bmp = await createImageBitmap(file)
  try {
    const sw = bmp.width
    const sh = bmp.height
    if (sw <= 0 || sh <= 0) throw new Error('无效图片尺寸')
    const scale = Math.max(targetW / sw, targetH / sh)
    const dw = sw * scale
    const dh = sh * scale
    const ox = (targetW - dw) / 2
    const oy = (targetH - dh) / 2

    const canvas = document.createElement('canvas')
    canvas.width = targetW
    canvas.height = targetH
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new Error('浏览器无法创建画布')
    ctx.drawImage(bmp, ox, oy, dw, dh)
    const blob = await new Promise((resolve, reject) => {
      canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('PNG 编码失败'))), 'image/png')
    })
    return blob
  } finally {
    bmp.close()
  }
}

function clearImg2vidReference() {
  if (img2vidReferencePreview.value) {
    URL.revokeObjectURL(img2vidReferencePreview.value)
  }
  img2vidReferencePreview.value = null
  img2vidReferenceFile.value = null
}

/**
 * @param {File} f
 * @returns {boolean}
 */
function applyImg2vidReferenceImage(f) {
  if (!/^image\/(jpeg|png|webp)$/i.test(f.type)) {
    statusHint.value = '起始帧请使用 JPEG、PNG 或 WEBP'
    return false
  }
  if (img2vidReferencePreview.value) {
    URL.revokeObjectURL(img2vidReferencePreview.value)
  }
  img2vidReferenceFile.value = f
  img2vidReferencePreview.value = URL.createObjectURL(f)
  statusHint.value = `已选择起始帧，提交时将等比裁切对齐为 ${computeSoraSize()}`
  return true
}

function triggerImg2vidRefPick() {
  img2vidRefInputRef.value?.click()
}

function onImg2vidRefSelected(e) {
  const input = e.target
  if (!(input instanceof HTMLInputElement)) return
  const f = input.files?.[0]
  input.value = ''
  if (!f) return
  applyImg2vidReferenceImage(f)
}

function statusToHint(status) {
  switch (status) {
    case 'queued':
      return '排队等待中…'
    case 'in_progress':
      return '正在生成视频…'
    case 'completed':
      return '生成已完成'
    default:
      return status ? `状态：${status}` : '处理中…'
  }
}

const VIDEO_GENERIC_TITLES = new Set(['视频创作工作台', '视频创作', ''])

function formatVideoProjectTitle(ts) {
  const d = new Date(ts)
  const pad = (n) => String(n).padStart(2, '0')
  return `视频 · ${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** @param {string} raw */
function titleFromPromptSnippet(raw) {
  const t = raw.trim()
  if (!t) return formatVideoProjectTitle(Date.now())
  return t.length > 36 ? `${t.slice(0, 36)}…` : t
}

function mapVideoStudioRecent(data) {
  const rows = Array.isArray(data) ? data : []
  return rows.map((row) => {
    const at = row.createdAt ? new Date(row.createdAt).getTime() : Date.now()
    const convTitle = typeof row.conversationTitle === 'string' ? row.conversationTitle.trim() : ''
    const generic = VIDEO_GENERIC_TITLES.has(convTitle)
    const title = generic ? formatVideoProjectTitle(at) : convTitle.length > 40 ? `${convTitle.slice(0, 40)}…` : convTitle
    return {
      id: String(row.id),
      url: row.imageUrl,
      /** 后端 RecentImageResponse 无 mimeType；video-studio 分页均为成片 */
      durationLabel: '视频',
      at,
      title,
    }
  })
}

async function refreshVideoLibrary() {
  if (!auth.isAuthenticated) {
    recentVideos.value = []
    return
  }
  try {
    const { data } = await fetchMeImagesPage({ page: 0, size: 48, skill: 'video-studio' })
    recentVideos.value = mapVideoStudioRecent(data)
  } catch {
    /* 列表失败不阻断作画 */
  }
}

function formatAgo(ts) {
  const s = Math.floor((Date.now() - ts) / 1000)
  if (s < 60) return '刚刚'
  if (s < 3600) return `${Math.floor(s / 60)} 分钟前`
  return `${Math.floor(s / 3600)} 小时前`
}

function toggleProfile(e) {
  e.stopPropagation()
  profileOpen.value = !profileOpen.value
}

function openConversation(id) {
  router.push({ path: '/chat', query: { conversation: String(id) } })
}

function logout() {
  profileOpen.value = false
  auth.logout()
  void router.push('/login')
}

function onDocClick(e) {
  if (!(e.target instanceof Node)) return
  if (
    e.target.closest?.('.pp-shell') ||
    e.target.closest?.('.pp-modal-shell') ||
    e.target.closest?.('.vg-profile-wrap') ||
    e.target.closest?.('.vg-proj-drawer') ||
    e.target.closest?.('.vg-export-modal') ||
    e.target.closest?.('.vg-ctx-menu')
  ) {
    return
  }
  const pw = profileWrapRef.value
  if (pw && !pw.contains(e.target)) profileOpen.value = false
  if (projectDrawerOpen.value && !e.target.closest?.('.vg-proj-trigger')) {
    projectDrawerOpen.value = false
  }
}

function onDocKeydown(e) {
  if (e.key !== 'Escape') return
  profileOpen.value = false
  projectDrawerOpen.value = false
  exportModalOpen.value = false
  extractFrameOpen.value = false
}

function applyExample(text) {
  prompt.value = text
}

async function optimizeVideoPrompt() {
  if (!auth.isAuthenticated) {
    statusHint.value = '请先登录后再使用提示词优化'
    return
  }
  const raw = prompt.value.trim()
  if (!raw) {
    statusHint.value = '请先输入描述'
    return
  }
  if (promptOptimizing.value || previewPhase.value === 'generating') return
  promptOptimizing.value = true
  statusHint.value = '正在优化提示词…'
  try {
    const modeLab = modes.find((m) => m.id === activeMode.value)?.label || String(activeMode.value)
    const styleLab = styleOptions.find((s) => s.id === styleId.value)?.label || ''
    const aspectLab = aspectId.value
    const qualityLab = `720p · ${durationSec.value} 秒 · 运动幅度 ${motionStrength.value}/10`
    const { data } = await optimizeImageStudioPrompt({
      prompt: raw,
      tool: modeLab,
      styleLabel: styleLab,
      aspectLabel: aspectLab,
      qualityLabel: qualityLab,
      medium: 'video',
    })
    const next = typeof data?.optimizedPrompt === 'string' ? data.optimizedPrompt.trim() : ''
    if (next) {
      prompt.value = next
      statusHint.value = '提示词已优化，可直接生成视频'
    } else {
      statusHint.value = '优化结果为空，请重试'
    }
  } catch (e) {
    statusHint.value = getAxiosErrorMessage(e)
  } finally {
    promptOptimizing.value = false
  }
}

function newProject() {
  stopGenerate()
  previewPhase.value = 'empty'
  resultVideoUrl.value = null
  prompt.value = ''
  progressPct.value = 0
  clearImg2vidReference()
}

function triggerImport() {
  fileInputRef.value?.click()
}

function onFilesSelected(e) {
  const input = e.target
  if (!(input instanceof HTMLInputElement)) return
  const files = input.files
  if (!files?.length) return
  const list = Array.from(files)

  if (activeMode.value === 'img2vid') {
    const firstRef = list.find((f) => /^image\/(jpeg|png|webp)$/i.test(f.type))
    if (firstRef) {
      applyImg2vidReferenceImage(firstRef)
    }
    const hasUnsupportedImage = list.some(
      (f) => f.type.startsWith('image/') && !/^image\/(jpeg|png|webp)$/i.test(f.type),
    )
    const hasAnyOkImage = list.some((f) => /^image\/(jpeg|png|webp)$/i.test(f.type))
    if (!firstRef && list.length > 0) {
      if (hasUnsupportedImage) {
        statusHint.value = '起始帧仅支持 JPEG / PNG / WEBP'
      } else if (!hasAnyOkImage) {
        statusHint.value = '图生视频请选择 JPEG / PNG / WEBP 图片作为起始帧'
      }
    }
    input.value = ''
    return
  }

  if (activeMode.value === 'character') {
    statusHint.value = '角色参考上传即将接入；请使用「图生视频」上传起始帧'
    input.value = ''
    return
  }

  statusHint.value = '导入本地文件请切换到「图生视频」，用于选择起始帧图片（JPEG / PNG / WEBP）'
  input.value = ''
}

function openProjectSettings() {
  exportModalOpen.value = true
}

async function runVideoGeneration() {
  if (previewPhase.value === 'generating') return

  const needsPromptModes = ['txt2vid', 'img2vid', 'character', 'audioSync', 'extend']
  if (needsPromptModes.includes(activeMode.value) && !prompt.value.trim()) {
    statusHint.value = '请先输入图片描述'
    return
  }

  if (activeMode.value !== 'txt2vid' && activeMode.value !== 'img2vid') {
    statusHint.value = '当前仅「文生视频 / 图生视频」已接入 Sora 2 官转 API，其它模式敬请期待'
    return
  }

  if (activeMode.value === 'img2vid' && !img2vidReferenceFile.value) {
    statusHint.value = '图生视频请先选择起始帧图片（JPEG / PNG / WEBP）'
    return
  }

  if (!auth.isAuthenticated) {
    statusHint.value = '请先登录后再生成视频'
    return
  }

  const text = prompt.value.trim()
  const resumeUrl = resultVideoUrl.value

  genAbort?.abort()
  genAbort = new AbortController()
  const signal = genAbort.signal

  const model = computeSoraModel()
  const size = computeSoraSize()
  const seconds = String(durationSec.value)

  previewPhase.value = 'generating'
  progressPct.value = 4
  progressMsg.value = '提交任务中…'
  etaSeconds.value = 300

  try {
    let submitData
    if (activeMode.value === 'img2vid') {
      const sz = computeSoraSize()
      const { w, h } = parseSizeWxH(sz)
      progressPct.value = 6
      progressMsg.value = '裁切并对齐参考图分辨率…'
      const pngBlob = await buildReferencePngBlob(img2vidReferenceFile.value, w, h)
      const fd = new FormData()
      fd.append('prompt', text)
      fd.append('model', model)
      fd.append('seconds', seconds)
      fd.append('size', sz)
      fd.append('input_reference', pngBlob, 'reference.png')
      ;({ data: submitData } = await submitSora2VideoMultipart(fd, { signal }))
    } else {
      ;({ data: submitData } = await submitSora2Video({ prompt: text, model, seconds, size }, { signal }))
    }
    const videoId = submitData?.videoId
    if (!videoId) {
      throw new Error('服务未返回任务 ID')
    }

    progressMsg.value = '排队等待中…'

    const maxPoll = 60
    let completed = false
    for (let i = 0; i < maxPoll; i++) {
      if (signal.aborted) throw new DOMException('Aborted', 'AbortError')

      const { data: task } = await getSora2Task(videoId, { signal })
      const status = typeof task?.status === 'string' ? task.status : ''
      const prog = typeof task?.progress === 'number' ? task.progress : 0
      progressPct.value = Math.min(90, Math.max(8, prog > 0 ? prog : 10 + i * 2))
      progressMsg.value = statusToHint(status)

      if (status === 'completed') {
        completed = true
        break
      }
      if (status === 'failed') {
        const detail =
          task?.error && typeof task.error === 'object'
            ? task.error.message || task.error.code || JSON.stringify(task.error)
            : '上游返回失败'
        throw new Error(typeof detail === 'string' ? detail : '视频生成失败')
      }

      await sleep(15000)
    }

    if (!completed) {
      throw new Error('等待结果超时，请稍后在「最近成片」中查看或重试')
    }

    if (signal.aborted) throw new DOMException('Aborted', 'AbortError')

    progressMsg.value = '下载并保存成片…'
    progressPct.value = 93
    const { data: fin } = await finalizeSora2Task(videoId, { prompt: text }, { signal })
    const url = fin?.imageUrl
    if (!url) throw new Error('未返回可播放地址')

    progressPct.value = 100
    progressMsg.value = '完成'
    resultVideoUrl.value = url
    previewPhase.value = 'done'
    remainingQuotaSec.value = Math.max(0, remainingQuotaSec.value - durationSec.value)
    recentVideos.value.unshift({
      id: String(fin.imageId ?? videoId),
      url,
      durationLabel: `${durationSec.value}s`,
      at: Date.now(),
      title: titleFromPromptSnippet(text),
    })
    if (recentVideos.value.length > 16) recentVideos.value.pop()
    statusHint.value = '生成完成（请及时下载：上游成片仅保留约 1 天）'
    await refreshVideoLibrary()
    nextTick(() => videoRef.value?.play().catch(() => {}))
  } catch (e) {
    const aborted =
      signal.aborted ||
      e?.name === 'AbortError' ||
      e?.code === 'ERR_CANCELED' ||
      (typeof e?.message === 'string' && /cancel/i.test(e.message))
    resultVideoUrl.value = resumeUrl
    previewPhase.value = resumeUrl ? 'done' : 'empty'
    progressPct.value = 0
    progressMsg.value = ''
    statusHint.value = aborted ? '已停止生成' : getAxiosErrorMessage(e)
  } finally {
    genAbort = null
  }
}

function stopGenerate() {
  genAbort?.abort()
}

function onPreviewDrop() {
  if (previewPhase.value === 'empty') {
    statusHint.value = '已接收拖拽素材（演示）'
  }
}

function toggleVideoPlay() {
  const v = videoRef.value
  if (!v) return
  if (v.paused) void v.play()
  else v.pause()
}

function onVideoTimeUpdate(e) {
  const v = e.target
  if (v instanceof HTMLVideoElement) videoCurrentTime.value = v.currentTime
}

function onVideoKeydown(e) {
  if (!videoRef.value || previewPhase.value !== 'done') return
  const v = videoRef.value
  if (e.key === ' ' || e.code === 'Space') {
    e.preventDefault()
    v.paused ? v.play() : v.pause()
  } else if (e.key === 'ArrowLeft') {
    e.preventDefault()
    v.currentTime = Math.max(0, v.currentTime - 1 / 30)
  } else if (e.key === 'ArrowRight') {
    e.preventDefault()
    v.currentTime = Math.min(v.duration || 0, v.currentTime + 1 / 30)
  } else if (e.key.toLowerCase() === 'f') {
    e.preventDefault()
    v.requestFullscreen?.()
  } else if (e.key.toLowerCase() === 'm') {
    e.preventDefault()
    v.muted = !v.muted
  }
}

const ctxMenuX = ref(0)
const ctxMenuY = ref(0)
const ctxMenuOpen = ref(false)

function onVideoContextMenu(e) {
  e.preventDefault()
  ctxMenuX.value = e.clientX
  ctxMenuY.value = e.clientY
  ctxMenuOpen.value = true
}

function closeCtxMenu() {
  ctxMenuOpen.value = false
}

function pickRecent(rv) {
  resultVideoUrl.value = rv.url
  previewPhase.value = 'done'
  nextTick(() => videoRef.value?.play().catch(() => {}))
}

function pickRecentFromProject(rv) {
  pickRecent(rv)
  projectDrawerOpen.value = false
}

watch(projectDrawerOpen, (open) => {
  if (open) void refreshVideoLibrary()
})

onMounted(() => {
  document.addEventListener('click', onDocClick)
  document.addEventListener('keydown', onDocKeydown)
  void refreshVideoLibrary()
})

onUnmounted(() => {
  stopGenerate()
  clearImg2vidReference()
  document.removeEventListener('click', onDocClick)
  document.removeEventListener('keydown', onDocKeydown)
})
</script>

<template>
  <div class="vg-root">
    <header class="vg-topbar">
      <div class="vg-topbar-lead">
        <button type="button" class="vg-icon-btn" title="返回主应用" aria-label="返回主应用" @click="router.push('/chat')">
          ←
        </button>
        <span class="vg-topbar-title">视频创作</span>
        <span class="vg-badge" title="基于 Sora 2 能力规划">Sora 2</span>
      </div>
      <div class="vg-topbar-center">
        <button type="button" class="vg-pill-btn" @click="newProject">新建项目</button>
        <button type="button" class="vg-pill-btn" @click="triggerImport">导入素材</button>
        <button type="button" class="vg-pill-btn" @click="exportModalOpen = true">批量导出</button>
        <button type="button" class="vg-pill-btn" @click="openProjectSettings">项目设置</button>
        <button type="button" class="vg-proj-trigger vg-pill-btn" @click.stop="projectDrawerOpen = !projectDrawerOpen">
          项目库
        </button>
      </div>
      <div class="vg-topbar-trail">
        <div class="vg-quota" title="按秒计费示意">
          <span class="vg-quota-label">剩余</span>
          <strong>{{ remainingQuotaSec }}s</strong>
        </div>
        <div ref="profileWrapRef" class="vg-profile-wrap">
          <button
            type="button"
            class="vg-profile-trigger"
            aria-haspopup="menu"
            :aria-expanded="profileOpen"
            aria-label="个人中心"
            @click="toggleProfile"
          >
            <span class="vg-av">{{ auth.isAuthenticated ? auth.username.slice(0, 1).toUpperCase() : '?' }}</span>
            <span class="vg-profile-txt">个人中心</span>
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
      <input
        ref="fileInputRef"
        type="file"
        class="vg-hidden-input"
        accept="image/jpeg,image/png,image/webp"
        multiple
        @change="onFilesSelected"
      />
    </header>

    <div class="vg-body">
      <!-- 中央：画布上方工具条（与图片模块一致）+ 预览 + 时间轴 -->
      <div class="vg-main-stage">
        <div class="vg-canvas-surface">
          <nav class="vg-tool-strip" aria-label="创作模式">
            <div class="vg-tool-strip-inner">
              <button
                v-for="m in modes"
                :key="m.id"
                type="button"
                class="vg-tool"
                :class="{ 'vg-tool--active': activeMode === m.id }"
                :title="m.hint"
                @click="activeMode = m.id"
              >
                <span class="vg-tool-stack">
                  <span class="vg-tool-hit">
                    <span class="vg-tool-indicator" aria-hidden="true" />
                    <span class="vg-tool-ic" aria-hidden="true">{{ m.icon }}</span>
                  </span>
                  <span class="vg-tool-cap" :class="{ 'vg-tool-cap--on': activeMode === m.id }">{{ m.label }}</span>
                </span>
              </button>
            </div>
          </nav>

          <div class="vg-preview-stack">
          <div class="vg-preview-scroll">
          <div class="vg-preview-inner" @dragover.prevent @drop.prevent="onPreviewDrop">
            <template v-if="previewPhase === 'empty'">
              <div class="vg-empty-frame">
                <div class="vg-empty-dash" aria-hidden="true" />
                <div class="vg-empty-glow" aria-hidden="true" />
                <div class="vg-empty-play" aria-hidden="true">▶</div>
                <p class="vg-empty-txt">描述你的视频创意，或拖拽图片/视频开始创作</p>
                <div class="vg-examples">
                  <button v-for="ex in examplePrompts" :key="ex" type="button" class="vg-ex-pill" @click="applyExample(ex)">
                    {{ ex }}
                  </button>
                </div>
              </div>
            </template>

            <template v-else-if="previewPhase === 'generating'">
              <div class="vg-gen-block">
                <div class="vg-pulse-stack" aria-hidden="true">
                  <span class="vg-pulse-ring" style="animation-delay: 0s" />
                  <span class="vg-pulse-ring" style="animation-delay: 0.35s" />
                  <span class="vg-pulse-ring" style="animation-delay: 0.7s" />
                </div>
                <p class="vg-gen-brand">Sora 2</p>
                <p class="vg-gen-msg">{{ progressMsg }}</p>
                <div class="vg-progress-track">
                  <div class="vg-progress-fill" :style="{ width: `${progressPct}%` }" />
                </div>
                <p class="vg-gen-eta">约 {{ etaSeconds }} 秒</p>
                <p class="vg-gen-foot">官转异步生成：常见 3–10 分钟；秒数仅支持 4 / 8 / 12</p>
                <button type="button" class="vg-stop-btn" @click="stopGenerate">停止生成</button>
              </div>
            </template>

            <template v-else>
              <div class="vg-player-stack">
                <div class="vg-player-shell" @keydown="onVideoKeydown" tabindex="0">
                  <video
                    ref="videoRef"
                    class="vg-video"
                    :src="resultVideoUrl || undefined"
                    playsinline
                    muted
                    loop
                    @contextmenu="onVideoContextMenu"
                    @timeupdate="onVideoTimeUpdate"
                  />
                  <div class="vg-player-bar">
                    <button type="button" class="vg-mini-btn" @click="toggleVideoPlay">
                      ⏯
                    </button>
                    <span class="vg-mini-hint">空格播放 · ← → 逐帧 · F 全屏 · M 静音</span>
                    <button type="button" class="vg-mini-btn" @click="extractFrameOpen = true">提取关键帧</button>
                    <button type="button" class="vg-mini-btn" :class="{ 'vg-mini-btn--on': qcMode }" @click="qcMode = !qcMode">
                      检查
                    </button>
                  </div>
                  <div v-if="qcMode" class="vg-qc-strip">
                    逐帧 · 帧 {{ Math.floor(videoCurrentTime * 30) }} · 参数快照（演示）
                  </div>
                </div>
                <p class="vg-meta-line">
                  720p · {{ aspectId }} · {{ videoDurationDisplay }} ·
                  {{ styleOptions.find((s) => s.id === styleId)?.label || '' }} · 生成于 {{ formatAgo(Date.now() - 60000) }}
                </p>
                <div class="vg-float-tools">
                  <button type="button" @click="runVideoGeneration">🔄 重新生成</button>
                  <button type="button" @click="activeMode = 'extend'">⏱️ 扩展时长</button>
                  <button type="button" @click="activeMode = 'character'">🎭 角色一致性</button>
                  <button type="button" @click="keyframeMode = true">✂️ 剪辑</button>
                  <button type="button" @click="exportModalOpen = true">📥 下载</button>
                </div>
              </div>
            </template>
          </div>
          </div>

          </div>
        </div>

        <!-- 简易时间轴 -->
        <div class="vg-timeline-wrap">
          <div class="vg-timeline-head">
            <span>时间轴</span>
            <button type="button" class="vg-text-btn" @click="keyframeMode = !keyframeMode">
              {{ keyframeMode ? '收起关键帧' : '关键帧控制' }}
            </button>
          </div>
          <div class="vg-timeline-rail">
            <span v-for="t in timelineTicks" :key="'tk-' + t" class="vg-tick">{{ t }}s</span>
          </div>
          <div v-if="keyframeMode" class="vg-kf-editor">
            <div class="vg-kf-rail">
              <button
                v-for="kf in keyframes"
                :key="kf.id"
                type="button"
                class="vg-kf-dot"
                :class="{ 'vg-kf-dot--on': selectedKeyframeId === kf.id }"
                :style="{ left: `${(kf.t / Math.max(1, durationSec)) * 100}%` }"
                @click="selectedKeyframeId = kf.id"
              />
            </div>
            <button type="button" class="vg-text-btn" @click="addKeyframe">
              + 添加关键帧
            </button>
            <template v-if="selectedKeyframe">
              <label class="vg-kf-field">
                运动描述
                <input
                  :value="selectedKeyframe.motion"
                  type="text"
                  class="vg-kf-input"
                  @input="updateKeyframeField('motion', $event)"
                />
              </label>
              <label class="vg-kf-field">
                镜头控制
                <input
                  :value="selectedKeyframe.camera"
                  type="text"
                  class="vg-kf-input"
                  @input="updateKeyframeField('camera', $event)"
                />
              </label>
            </template>
          </div>
        </div>

        <!-- 底部输入 -->
        <footer v-if="!keyframeMode || previewPhase !== 'done'" class="vg-dock">
          <input
            ref="img2vidRefInputRef"
            type="file"
            class="vg-hidden-input"
            accept="image/jpeg,image/png,image/webp"
            @change="onImg2vidRefSelected"
          />
          <div v-if="activeMode === 'img2vid'" class="vg-ref-strip vg-ref-strip--img2vid">
            <button type="button" class="vg-ref-action" @click="triggerImg2vidRefPick">选择起始帧</button>
            <img v-if="img2vidReferencePreview" :src="img2vidReferencePreview" alt="" class="vg-ref-thumb" />
            <span class="vg-ref-meta">输出 {{ computeSoraSize() }} · 提交前等比裁切至精确像素</span>
            <button v-if="img2vidReferenceFile" type="button" class="vg-ref-action" @click="clearImg2vidReference">
              清除
            </button>
          </div>
          <div v-else-if="activeMode === 'vid2vid'" class="vg-ref-strip">
            <span class="vg-ref-meta">参考视频（敬请期待）</span>
          </div>
          <div class="vg-dock-inner">
            <button type="button" class="vg-attach" title="上传参考" aria-label="上传" @click="triggerImport">
              📎
            </button>
            <div class="vg-prompt-composer">
              <textarea
                v-model="prompt"
                class="vg-textarea vg-textarea--dock"
                rows="2"
                :placeholder="
                  activeMode === 'txt2vid'
                    ? '详细描述你想要的视频场景，包括主体、动作、环境、镜头运动、光影氛围…'
                    : activeMode === 'img2vid'
                      ? '描述如何让画面动起来：镜头推拉、物体运动、光线变化…（画面已由起始帧固定）'
                      : '描述你希望的运动方式、风格变化或额外元素…'
                "
              />
              <button
                type="button"
                class="vg-prompt-opt"
                :disabled="promptOptimizing || previewPhase === 'generating' || !prompt.trim()"
                title="提示词优化"
                aria-label="提示词优化"
                @click="optimizeVideoPrompt"
              >
                <svg class="vg-prompt-opt-ic" viewBox="0 0 24 24" fill="none" aria-hidden="true">
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
                <span class="vg-prompt-opt-cap">{{ promptOptimizing ? '…' : '优化' }}</span>
              </button>
            </div>
            <button type="button" class="vg-send" :disabled="previewPhase === 'generating'" @click="runVideoGeneration">
              <span>生成视频</span>
              <span class="vg-send-ic">▶</span>
            </button>
          </div>
          <p class="vg-dock-hint">提示词技巧：主体 + 动作 + 环境 + 镜头语言 + 风格 + 情绪</p>
        </footer>
        <footer v-else class="vg-dock vg-dock--kf">
          <p class="vg-kf-zoom">
            缩放：
            <button type="button">1×</button>
            <button type="button">2×</button>
            <button type="button">4×</button>
          </p>
          <p class="vg-dock-hint">选中关键帧后在右侧面板调节镜头；帧间自动插值（演示）。</p>
        </footer>
      </div>

      <!-- 右侧参数 -->
      <aside class="vg-panel" :class="{ 'vg-panel--collapsed': panelCollapsed }">
        <button type="button" class="vg-panel-toggle" @click="panelCollapsed = !panelCollapsed">
          {{ panelCollapsed ? '◀' : '▶' }}
        </button>
        <div v-show="!panelCollapsed" class="vg-panel-scroll">
          <section class="vg-pgroup">
            <p class="vg-pgroup-title">OUTPUT</p>
            <div class="vg-pcard">
              <p class="vg-micro-label">时长</p>
              <div class="vg-seg">
                <button v-for="d in [4, 8, 12]" :key="d" type="button" class="vg-seg-btn" :class="{ on: durationSec === d }" @click="durationSec = d">
                  {{ d }}s
                </button>
              </div>
              <p class="vg-micro-label">分辨率</p>
              <p class="vg-muted">固定 <strong>720p</strong>（模型 <code>sora-2</code> 标准版）；切换横竖见下方比例。</p>
              <p class="vg-micro-label">画面比例</p>
              <div class="vg-aspect-grid">
                <button
                  v-for="a in aspects"
                  :key="a.id"
                  type="button"
                  class="vg-aspect-cell"
                  :class="{ on: aspectId === a.id }"
                  @click="aspectId = a.id"
                >
                  {{ a.label }}
                </button>
              </div>
              <p class="vg-micro-label">运动幅度（1–10）</p>
              <input v-model.number="motionStrength" type="range" min="1" max="10" class="vg-range" />
              <p class="vg-micro-label">风格</p>
              <div class="vg-style-scroll">
                <button
                  v-for="s in styleOptions"
                  :key="s.id"
                  type="button"
                  class="vg-style-card"
                  :class="{ on: styleId === s.id }"
                  @click="styleId = s.id"
                >
                  <span class="vg-style-swatch" :style="{ background: s.swatch }" />
                  {{ s.label }}
                </button>
              </div>
            </div>
          </section>

          <section class="vg-pgroup">
            <p class="vg-pgroup-title">MODE</p>
            <div class="vg-pcard">
              <template v-if="activeMode === 'txt2vid'">
                <p class="vg-muted">纯文本驱动，无额外参数。</p>
              </template>
              <template v-else-if="activeMode === 'img2vid'">
                <p class="vg-warn">
                  Sora 2 图生视频：底部选择起始帧；提交时按 OUTPUT 分辨率等比放大并居中裁切，导出 PNG 上传（像素与 size 完全一致）。
                </p>
                <p class="vg-micro-label">参考图强度</p>
                <input v-model.number="refImageStrength" type="range" min="0" max="100" class="vg-range" />
                <p class="vg-micro-label">运动方向</p>
                <div class="vg-dir-grid">
                  <button
                    v-for="d in motionDirs"
                    :key="d.id"
                    type="button"
                    class="vg-dir-cell"
                    :class="{ on: motionDir === d.id }"
                    @click="motionDir = d.id"
                  >
                    {{ d.label }}
                  </button>
                </div>
                <label class="vg-switch-row">
                  <span>首帧固定</span>
                  <input v-model="lockFirstFrame" type="checkbox" />
                </label>
              </template>
              <template v-else-if="activeMode === 'vid2vid'">
                <p class="vg-micro-label">参考视频强度</p>
                <input v-model.number="refVideoStrength" type="range" min="0" max="100" class="vg-range" />
                <p class="vg-micro-label">重绘幅度</p>
                <input v-model.number="repaintAmount" type="range" min="0" max="100" class="vg-range" />
                <p class="vg-micro-label">时间范围（秒）</p>
                <div class="vg-row2">
                  <input v-model.number="timeRangeStart" type="number" min="0" class="vg-num" />
                  <span>—</span>
                  <input v-model.number="timeRangeEnd" type="number" min="0" class="vg-num" />
                </div>
              </template>
              <template v-else-if="activeMode === 'character'">
                <p class="vg-micro-label">角色参考（1–3 张）</p>
                <button type="button" class="vg-secondary-btn" @click="triggerImport">上传角色图</button>
                <p class="vg-micro-label">一致性强度</p>
                <input v-model.number="consistencyStrength" type="range" min="1" max="10" class="vg-range" />
                <p class="vg-micro-label">表情 / 动作自由度</p>
                <input v-model.number="expressionFreedom" type="range" min="1" max="10" class="vg-range" />
              </template>
              <template v-else-if="activeMode === 'audioSync'">
                <p class="vg-micro-label">节拍检测</p>
                <select v-model="beatDetect" class="vg-select">
                  <option value="auto">自动</option>
                  <option value="manual">手动标记</option>
                </select>
                <p class="vg-micro-label">画面切换</p>
                <select v-model="cutFrequency" class="vg-select">
                  <option value="beat">跟随节拍</option>
                  <option value="2">每 2 秒</option>
                  <option value="4">每 4 秒</option>
                </select>
                <p class="vg-micro-label">情绪映射</p>
                <select v-model="emotionMap" class="vg-select">
                  <option value="dynamic">激昂 → 高对比</option>
                  <option value="calm">舒缓 → 柔和色调</option>
                  <option value="tense">紧张 → 冷色偏移</option>
                </select>
                <div class="vg-wave">〜 音频波形示意 〜</div>
              </template>
              <template v-else-if="activeMode === 'extend'">
                <p class="vg-micro-label">扩展方向</p>
                <div class="vg-seg">
                  <button type="button" class="vg-seg-btn" :class="{ on: extendDir === 'forward' }" @click="extendDir = 'forward'">向前</button>
                  <button type="button" class="vg-seg-btn" :class="{ on: extendDir === 'backward' }" @click="extendDir = 'backward'">向后</button>
                  <button type="button" class="vg-seg-btn" :class="{ on: extendDir === 'both' }" @click="extendDir = 'both'">双向</button>
                </div>
                <p class="vg-micro-label">扩展时长</p>
                <div class="vg-seg">
                  <button type="button" class="vg-seg-btn" :class="{ on: extendExtraSec === 5 }" @click="extendExtraSec = 5">5s</button>
                  <button type="button" class="vg-seg-btn" :class="{ on: extendExtraSec === 10 }" @click="extendExtraSec = 10">10s</button>
                  <button type="button" class="vg-seg-btn" :class="{ on: extendExtraSec === 15 }" @click="extendExtraSec = 15">15s</button>
                </div>
                <p class="vg-micro-label">衔接平滑度</p>
                <input v-model.number="extendBlend" type="range" min="1" max="10" class="vg-range" />
              </template>
            </div>
          </section>

          <section class="vg-pgroup">
            <button type="button" class="vg-acc-head" @click="advancedOpen = !advancedOpen">
              高级参数 <span>{{ advancedOpen ? '−' : '+' }}</span>
            </button>
            <div v-show="advancedOpen" class="vg-pcard">
              <p class="vg-micro-label">种子</p>
              <div class="vg-row2">
                <select v-model="seedMode" class="vg-select">
                  <option value="random">随机</option>
                  <option value="fixed">固定</option>
                </select>
                <input v-model="seedValue" type="text" class="vg-num" :disabled="seedMode !== 'fixed'" />
              </div>
              <label class="vg-micro-label">负面提示词</label>
              <textarea v-model="negativePrompt" class="vg-small-ta" rows="2" placeholder="不想出现的元素…" />
              <p class="vg-micro-label">物理模拟精度</p>
              <div class="vg-seg">
                <button type="button" class="vg-seg-btn" :class="{ on: physicsPrecision === 'standard' }" @click="physicsPrecision = 'standard'">
                  标准
                </button>
                <button type="button" class="vg-seg-btn" :class="{ on: physicsPrecision === 'high' }" @click="physicsPrecision = 'high'">
                  高精度
                </button>
              </div>
              <p class="vg-micro-label">多镜头叙事</p>
              <select v-model="multiShot" class="vg-select">
                <option value="single">单镜头</option>
                <option value="dual">双镜头切换</option>
                <option value="triple">三镜头叙事</option>
              </select>
            </div>
          </section>

          <section class="vg-pgroup vg-recent-block">
            <p class="vg-pgroup-title">RECENT VIDEOS</p>
            <div v-if="!recentVideos.length" class="vg-recent-empty">暂无成片</div>
            <div v-else class="vg-recent-strip">
              <button
                v-for="rv in recentVideos"
                :key="rv.id"
                type="button"
                class="vg-recent-thumb"
                :title="rv.title"
                @click="pickRecent(rv)"
              >
                <video :src="rv.url" muted preload="metadata" class="vg-recent-vid" />
                <span class="vg-recent-dur">{{ rv.durationLabel }}</span>
              </button>
            </div>
          </section>

          <p class="vg-powered">视频模块 UI · 接入 Sora 2 API 后即可打通生成链路</p>
        </div>
      </aside>
    </div>

    <p class="vg-status">{{ statusHint }}</p>

    <!-- 项目库抽屉 -->
    <Teleport to="body">
      <Transition name="vg-drawer">
        <div v-if="projectDrawerOpen" class="vg-drawer-backdrop" @click.self="projectDrawerOpen = false">
          <aside class="vg-proj-drawer" @click.stop>
            <header class="vg-proj-head">
              <h2>项目库</h2>
              <button type="button" class="vg-icon-btn" aria-label="关闭" @click="projectDrawerOpen = false">×</button>
            </header>
            <div class="vg-proj-actions">
              <button type="button" class="vg-pill-btn">批量下载</button>
              <button type="button" class="vg-pill-btn">批量删除</button>
              <button type="button" class="vg-pill-btn">批量重新生成</button>
            </div>
            <div class="vg-proj-grid">
              <p v-if="!recentVideos.length" class="vg-proj-empty">
                {{
                  auth.isAuthenticated
                    ? '暂无成片。生成成功后条目将出现在此处（与右侧「最近成片」同源）。'
                    : '登录并生成视频后，成片将显示在项目库中。'
                }}
              </p>
              <button
                v-for="p in recentVideos"
                v-else
                :key="p.id"
                type="button"
                class="vg-proj-card"
                @click="pickRecentFromProject(p)"
              >
                <div class="vg-proj-thumb">
                  <video :src="p.url" muted preload="metadata" class="vg-proj-thumb-vid" />
                </div>
                <p class="vg-proj-title">{{ p.title }}</p>
                <p class="vg-proj-meta">{{ p.durationLabel }} · {{ formatAgo(p.at) }}</p>
                <span class="vg-proj-status" data-st="done">已完成</span>
              </button>
            </div>
          </aside>
        </div>
      </Transition>
    </Teleport>

    <!-- 导出 / 项目设置 -->
    <Teleport to="body">
      <div v-if="exportModalOpen" class="vg-modal-backdrop" @click.self="exportModalOpen = false">
        <div class="vg-export-modal" @click.stop>
          <header class="vg-proj-head">
            <h2>导出选项</h2>
            <button type="button" class="vg-icon-btn" @click="exportModalOpen = false">×</button>
          </header>
          <div class="vg-export-body">
            <label>格式</label>
            <select class="vg-select">
              <option>MP4 (H.264)</option>
              <option>MP4 (H.265)</option>
              <option>MOV (ProRes)</option>
              <option>GIF</option>
              <option>WebM</option>
              <option>帧序列 PNG</option>
            </select>
            <label>分辨率</label>
            <select class="vg-select">
              <option>原分辨率</option>
              <option>2K 超分</option>
              <option>4K 超分</option>
            </select>
            <label>帧率</label>
            <select class="vg-select">
              <option>24 fps</option>
              <option>30 fps</option>
              <option>60 fps</option>
            </select>
            <label>音频</label>
            <select class="vg-select">
              <option>保留音轨</option>
              <option>分离音轨</option>
              <option>替换背景音乐</option>
            </select>
          </div>
          <footer class="vg-modal-foot">
            <button type="button" class="vg-secondary-btn" @click="exportModalOpen = false">取消</button>
            <button type="button" class="vg-primary-btn" @click="exportModalOpen = false">开始导出</button>
          </footer>
        </div>
      </div>
    </Teleport>

    <!-- 右键菜单 -->
    <Teleport to="body">
      <div
        v-if="ctxMenuOpen"
        class="vg-ctx-menu"
        :style="{ left: `${ctxMenuX}px`, top: `${ctxMenuY}px` }"
        @click="closeCtxMenu"
      >
        <button type="button">提取帧</button>
        <button type="button">下载片段</button>
      </div>
    </Teleport>

    <!-- 提取关键帧 -->
    <Teleport to="body">
      <div v-if="extractFrameOpen" class="vg-modal-backdrop" @click.self="extractFrameOpen = false">
        <div class="vg-export-modal" @click.stop>
          <header class="vg-proj-head">
            <h2>提取关键帧</h2>
            <button type="button" class="vg-icon-btn" @click="extractFrameOpen = false">×</button>
          </header>
          <p class="vg-muted">在时间轴上选择时刻，导出为 PNG（演示）。</p>
          <input type="range" min="0" :max="durationSec" class="vg-range" />
          <footer class="vg-modal-foot">
            <button type="button" class="vg-primary-btn" @click="extractFrameOpen = false">导出帧</button>
          </footer>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.vg-root {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: #0f0f11;
  color: var(--chat-fg, #e8eaed);
  overflow: hidden;
}

.vg-topbar {
  flex-shrink: 0;
  height: 56px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  align-items: center;
  gap: 10px;
  padding: 0 max(12px, env(safe-area-inset-right)) 0 max(12px, env(safe-area-inset-left));
  border-bottom: 1px solid var(--chat-border, #2a2f3a);
  background: color-mix(in srgb, #141416 88%, transparent);
  backdrop-filter: blur(22px);
  -webkit-backdrop-filter: blur(22px);
  z-index: 30;
}

.vg-topbar-lead {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.vg-topbar-title {
  font-size: 0.9375rem;
  font-weight: 700;
  color: var(--chat-fg-strong, #fff);
}

.vg-badge {
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.06em;
  padding: 4px 8px;
  border-radius: 8px;
  background: linear-gradient(135deg, color-mix(in srgb, var(--ig-brand, #10b981) 35%, transparent), transparent);
  border: 1px solid color-mix(in srgb, var(--ig-brand, #10b981) 45%, transparent);
  color: var(--ig-brand, #10b981);
}

.vg-topbar-center {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
}

.vg-topbar-trail {
  justify-self: end;
  display: flex;
  align-items: center;
  gap: 10px;
}

.vg-quota {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.55);
  padding: 6px 12px;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(255, 255, 255, 0.04);
}

.vg-quota strong {
  color: var(--ig-brand, #10b981);
  margin-left: 4px;
}

.vg-icon-btn {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  border: 1px solid var(--chat-border-strong, #3d4454);
  background: var(--ig-glass-bg, rgba(255, 255, 255, 0.06));
  backdrop-filter: blur(16px);
  color: inherit;
  cursor: pointer;
  transition:
    transform 0.15s ease,
    background 0.15s ease;
}

.vg-icon-btn:hover {
  transform: scale(1.04);
  background: rgba(255, 255, 255, 0.1);
}

.vg-pill-btn {
  padding: 8px 14px;
  border-radius: 999px;
  border: 1px solid var(--ig-glass-border, rgba(255, 255, 255, 0.12));
  background: var(--ig-glass-bg, rgba(255, 255, 255, 0.06));
  backdrop-filter: blur(16px);
  color: inherit;
  font-size: 0.78rem;
  font-weight: 600;
  cursor: pointer;
  transition:
    background 0.15s ease,
    border-color 0.15s ease,
    transform 0.15s ease;
}

.vg-pill-btn:hover {
  background: color-mix(in srgb, var(--ig-brand, #10b981) 14%, transparent);
  border-color: color-mix(in srgb, var(--ig-brand, #10b981) 40%, transparent);
  transform: translateY(-1px);
}

.vg-profile-wrap {
  position: relative;
}

.vg-profile-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px 6px 6px;
  border-radius: 999px;
  border: 1px solid var(--chat-profile-border, #2a2f3a);
  background: var(--chat-profile-bg, #1a1d26);
  cursor: pointer;
  color: inherit;
  font-size: 0.8125rem;
}

.vg-av {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--ig-brand, #10b981), var(--ig-brand-hover, #34d399));
  color: #042f2e;
  font-weight: 700;
  font-size: 0.75rem;
  display: flex;
  align-items: center;
  justify-content: center;
}

.vg-hidden-input {
  display: none;
}

.vg-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: row;
  overflow: hidden;
  background: linear-gradient(180deg, #0a0a0c 0%, #0f0f11 40%);
}

/* —— 画布顶栏工具条（对齐图片模块 ig-tool-strip） —— */
.vg-tool-strip {
  flex-shrink: 0;
  background: transparent;
  border-bottom: none;
}

.vg-tool-strip-inner {
  display: flex;
  flex-direction: row;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: center;
  gap: 12px;
  padding: 12px 16px;
}

.vg-tool {
  width: auto;
  flex-shrink: 0;
  padding: 0;
  border: none;
  background: transparent;
  cursor: pointer;
  display: flex;
  justify-content: center;
}

.vg-tool-stack {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  transition:
    transform 0.2s ease,
    color 0.2s ease;
}

.vg-tool-hit {
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
    transform 0.2s ease;
}

.vg-tool-indicator {
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

.vg-tool-ic {
  font-size: 1.15rem;
  line-height: 1;
}

.vg-tool:hover .vg-tool-hit {
  background: rgba(255, 255, 255, 0.05);
}

.vg-tool:hover .vg-tool-ic {
  transform: scale(1.08);
}

.vg-tool--active .vg-tool-stack {
  transform: translateY(2px);
}

.vg-tool--active .vg-tool-hit {
  background: linear-gradient(135deg, #10b981, #34d399);
  box-shadow: 0 8px 24px color-mix(in srgb, #10b981 35%, transparent);
}

.vg-tool--active .vg-tool-indicator {
  opacity: 1;
}

.vg-tool-cap {
  font-size: 10px;
  line-height: 1.2;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.5);
  max-width: 64px;
  text-align: center;
}

.vg-tool-cap--on {
  color: #fff;
}

/* —— 中央画布列 —— */
.vg-main-stage {
  flex: 1;
  min-width: 0;
  position: relative;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.vg-canvas-surface {
  flex: 1;
  min-height: 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: transparent;
}

.vg-preview-stack {
  flex: 1;
  min-height: 0;
  position: relative;
  isolation: isolate;
  display: flex;
  flex-direction: column;
}

.vg-preview-scroll {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 16px;
}

.vg-preview-inner {
  min-height: min(52vh, 420px);
  display: flex;
  align-items: center;
  justify-content: center;
}

.vg-empty-frame {
  width: min(100%, 880px);
  min-height: min(52vh, 400px);
  border-radius: 32px;
  background: color-mix(in srgb, #141416 82%, transparent);
  backdrop-filter: blur(22px);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 32px;
  text-align: center;
  position: relative;
  overflow: hidden;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.35);
}

.vg-empty-dash {
  position: absolute;
  inset: 0;
  border-radius: 32px;
  border: 2px dashed var(--ig-brand, #10b981);
  opacity: 0.15;
  animation: vg-dash 3s ease-in-out infinite;
  pointer-events: none;
}

@keyframes vg-dash {
  0%,
  100% {
    opacity: 0.1;
  }
  50% {
    opacity: 0.22;
  }
}

.vg-empty-glow {
  position: absolute;
  inset: 18%;
  background: radial-gradient(circle, color-mix(in srgb, var(--ig-brand, #10b981) 12%, transparent), transparent 68%);
  filter: blur(56px);
  pointer-events: none;
}

.vg-empty-play {
  position: relative;
  z-index: 1;
  font-size: 48px;
  color: var(--ig-brand, #10b981);
  animation: vg-pulse-icon 2s ease-in-out infinite;
}

@keyframes vg-pulse-icon {
  0%,
  100% {
    transform: scale(1);
    opacity: 0.85;
  }
  50% {
    transform: scale(1.06);
    opacity: 1;
  }
}

.vg-empty-txt {
  position: relative;
  z-index: 1;
  margin: 12px 0 16px;
  font-size: 14px;
  color: rgba(255, 255, 255, 0.55);
  max-width: 420px;
  line-height: 1.6;
}

.vg-examples {
  position: relative;
  z-index: 1;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
}

.vg-ex-pill {
  padding: 8px 14px;
  border-radius: 999px;
  border: none;
  background: rgba(255, 255, 255, 0.06);
  color: rgba(255, 255, 255, 0.55);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition:
    background 0.2s ease,
    color 0.2s ease;
}

.vg-ex-pill:hover {
  background: rgba(255, 255, 255, 0.12);
  color: #fff;
}

.vg-gen-block {
  text-align: center;
  padding: 48px 24px;
}

.vg-pulse-stack {
  position: relative;
  width: 120px;
  height: 120px;
  margin: 0 auto 20px;
}

.vg-pulse-ring {
  position: absolute;
  left: 50%;
  top: 50%;
  width: 72px;
  height: 72px;
  margin: -36px 0 0 -36px;
  border-radius: 50%;
  border: 2px solid color-mix(in srgb, var(--ig-brand, #10b981) 55%, transparent);
  animation: vg-conc 2.4s ease-out infinite;
  opacity: 0;
}

@keyframes vg-conc {
  0% {
    transform: scale(0.55);
    opacity: 0.55;
  }
  100% {
    transform: scale(2.15);
    opacity: 0;
  }
}

.vg-gen-brand {
  font-weight: 800;
  letter-spacing: 0.2em;
  font-size: 11px;
  color: var(--ig-brand, #10b981);
  margin: 0 0 8px;
}

.vg-gen-msg {
  font-weight: 650;
  margin: 0 0 14px;
}

.vg-progress-track {
  width: min(280px, 80vw);
  height: 2px;
  margin: 0 auto 12px;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.08);
  overflow: hidden;
}

.vg-progress-fill {
  height: 100%;
  border-radius: 2px;
  background: linear-gradient(90deg, #10b981, #34d399);
  box-shadow: 0 0 16px color-mix(in srgb, #10b981 55%, transparent);
  transition: width 0.35s ease;
}

.vg-gen-eta {
  margin: 0 0 8px;
  font-size: 13px;
}

.vg-gen-foot {
  margin: 0 0 16px;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.45);
}

.vg-stop-btn {
  padding: 10px 18px;
  border-radius: 12px;
  border: 1px solid color-mix(in srgb, var(--chat-danger-fg, #f87171) 45%, transparent);
  background: var(--chat-danger-bg, rgba(248, 113, 113, 0.12));
  color: var(--chat-danger-fg, #f87171);
  font-weight: 600;
  cursor: pointer;
}

.vg-player-stack {
  width: 100%;
  max-width: 920px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
}

.vg-player-shell {
  position: relative;
  width: 100%;
  border-radius: 16px;
  overflow: hidden;
  outline: none;
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.42);
}

.vg-video {
  width: 100%;
  display: block;
  background: #000;
  max-height: min(56vh, 520px);
}

.vg-player-bar {
  position: absolute;
  left: 12px;
  right: 12px;
  bottom: 12px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 14px;
  background: color-mix(in srgb, #141416 72%, transparent);
  backdrop-filter: blur(20px);
  opacity: 0;
  transition: opacity 0.2s ease;
}

.vg-player-shell:hover .vg-player-bar,
.vg-player-shell:focus-within .vg-player-bar {
  opacity: 1;
}

.vg-mini-btn {
  padding: 6px 10px;
  border-radius: 10px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(255, 255, 255, 0.06);
  color: #fff;
  font-size: 12px;
  cursor: pointer;
}

.vg-mini-btn--on {
  border-color: var(--ig-brand, #10b981);
  color: var(--ig-brand, #10b981);
}

.vg-mini-hint {
  flex: 1;
  font-size: 10px;
  color: rgba(255, 255, 255, 0.45);
  min-width: 140px;
}

.vg-qc-strip {
  margin-top: 8px;
  padding: 8px 12px;
  border-radius: 10px;
  background: rgba(0, 0, 0, 0.35);
  font-size: 11px;
  color: rgba(255, 255, 255, 0.65);
}

.vg-meta-line {
  margin: 0;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.45);
}

.vg-float-tools {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
  padding: 8px 12px;
  border-radius: 999px;
  background: color-mix(in srgb, #141416 78%, transparent);
  backdrop-filter: blur(18px);
  border: 1px solid rgba(255, 255, 255, 0.08);
}

.vg-float-tools button {
  padding: 8px 12px;
  border-radius: 999px;
  border: none;
  background: rgba(255, 255, 255, 0.06);
  color: rgba(255, 255, 255, 0.85);
  font-size: 12px;
  cursor: pointer;
}

.vg-float-tools button:hover {
  background: color-mix(in srgb, var(--ig-brand, #10b981) 22%, transparent);
}

/* 时间轴 */
.vg-timeline-wrap {
  flex-shrink: 0;
  padding: 10px 16px 8px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
  background: rgba(10, 10, 12, 0.65);
}

.vg-timeline-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
  font-size: 12px;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.65);
}

.vg-text-btn {
  border: none;
  background: none;
  color: var(--ig-brand, #10b981);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}

.vg-timeline-rail {
  display: flex;
  justify-content: space-between;
  font-size: 10px;
  color: rgba(255, 255, 255, 0.35);
  padding: 4px 0;
}

.vg-kf-editor {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed rgba(255, 255, 255, 0.08);
}

.vg-kf-rail {
  position: relative;
  height: 28px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.04);
  margin-bottom: 10px;
}

.vg-kf-dot {
  position: absolute;
  top: 50%;
  width: 12px;
  height: 12px;
  margin: -6px 0 0 -6px;
  border-radius: 50%;
  border: 2px solid rgba(255, 255, 255, 0.35);
  background: #141416;
  cursor: pointer;
  padding: 0;
}

.vg-kf-dot--on {
  border-color: var(--ig-brand, #10b981);
  background: var(--ig-brand, #10b981);
}

.vg-kf-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 11px;
  margin-top: 8px;
  color: rgba(255, 255, 255, 0.55);
}

.vg-kf-input {
  padding: 8px 10px;
  border-radius: 10px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(255, 255, 255, 0.04);
  color: inherit;
}

/* 底部输入 */
.vg-dock {
  flex-shrink: 0;
  padding: 12px 16px calc(12px + env(safe-area-inset-bottom));
  border-top: 1px solid rgba(255, 255, 255, 0.06);
  background: linear-gradient(180deg, transparent, rgba(10, 10, 12, 0.9));
}

.vg-dock--kf .vg-kf-zoom {
  margin: 0 0 8px;
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.vg-dock--kf button {
  padding: 4px 10px;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(255, 255, 255, 0.05);
  color: inherit;
  cursor: pointer;
}

.vg-ref-strip {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  margin-bottom: 10px;
}

.vg-ref-strip--img2vid {
  align-items: center;
  flex-wrap: wrap;
}

.vg-ref-thumb {
  height: 40px;
  width: auto;
  max-width: 72px;
  border-radius: 8px;
  object-fit: cover;
  border: 1px solid rgba(255, 255, 255, 0.12);
}

.vg-ref-meta {
  font-size: 11px;
  color: rgba(226, 232, 240, 0.65);
  flex: 1 1 140px;
  min-width: 0;
}

.vg-ref-action {
  flex: 0 0 auto;
  padding: 8px 12px;
  border-radius: 10px;
  font-size: 12px;
  border: 1px solid rgba(16, 185, 129, 0.35);
  background: rgba(16, 185, 129, 0.12);
  color: #a7f3d0;
  cursor: pointer;
}

.vg-ref-action:hover {
  background: rgba(16, 185, 129, 0.2);
}

.vg-ref-chip {
  flex: 0 0 auto;
  padding: 8px 12px;
  border-radius: 10px;
  font-size: 11px;
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.08);
}

.vg-dock-inner {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  max-width: min(960px, 100%);
  margin: 0 auto;
}

.vg-attach {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  border: none;
  background: rgba(255, 255, 255, 0.06);
  cursor: pointer;
  font-size: 1.1rem;
  transition: transform 0.2s ease;
}

.vg-attach:hover {
  transform: scale(1.06);
}

.vg-prompt-composer {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: stretch;
  min-height: 80px;
  max-height: calc(1.5em * 8 + 52px);
  border-radius: 16px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(255, 255, 255, 0.03);
  overflow: hidden;
  transition: border-color 0.15s ease;
}

.vg-prompt-composer:focus-within {
  border-color: color-mix(in srgb, var(--ig-brand, #10b981) 35%, transparent);
}

.vg-textarea--dock {
  flex: 1;
  min-width: 0;
  min-height: 80px;
  max-height: calc(1.5em * 8 + 24px);
  border: none;
  border-radius: 0;
  padding: 12px 14px;
  resize: vertical;
  font-family: inherit;
  font-size: 0.875rem;
  background: transparent;
  color: inherit;
}

.vg-textarea--dock:focus {
  outline: none;
}

.vg-prompt-opt {
  flex-shrink: 0;
  width: 52px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  padding: 8px 4px;
  border: none;
  border-left: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(255, 255, 255, 0.035);
  color: var(--ig-brand, #10b981);
  cursor: pointer;
  transition:
    background 0.15s ease,
    color 0.15s ease;
}

.vg-prompt-opt:hover:not(:disabled) {
  background: color-mix(in srgb, var(--ig-brand, #10b981) 14%, transparent);
}

.vg-prompt-opt:disabled {
  opacity: 0.42;
  cursor: not-allowed;
}

.vg-prompt-opt-ic {
  width: 18px;
  height: 18px;
  display: block;
}

.vg-prompt-opt-cap {
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.06em;
  line-height: 1;
}

.vg-send {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 0 18px;
  height: 44px;
  border: none;
  border-radius: 999px;
  cursor: pointer;
  font-weight: 700;
  color: #042f2e;
  background: linear-gradient(145deg, #10b981, #34d399);
  box-shadow: 0 8px 28px color-mix(in srgb, #10b981 42%, transparent);
}

.vg-send:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.vg-send-ic {
  font-size: 12px;
}

.vg-dock-hint {
  margin: 10px auto 0;
  max-width: min(960px, 100%);
  text-align: center;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.45);
}

/* 右侧面板 */
.vg-panel {
  width: 280px;
  flex-shrink: 0;
  position: relative;
  border-left: 1px solid rgba(255, 255, 255, 0.06);
  background: color-mix(in srgb, #141416 94%, transparent);
  backdrop-filter: blur(22px);
}

.vg-panel--collapsed {
  width: 36px;
}

.vg-panel-toggle {
  position: absolute;
  left: 6px;
  top: 12px;
  z-index: 2;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(255, 255, 255, 0.05);
  color: inherit;
  cursor: pointer;
}

.vg-panel-scroll {
  padding: 44px 14px 20px;
  height: 100%;
  overflow-y: auto;
  scrollbar-width: thin;
}

.vg-pgroup {
  margin-bottom: 16px;
}

.vg-pgroup-title {
  margin: 0 0 8px;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.12em;
  color: rgba(255, 255, 255, 0.4);
}

.vg-pcard {
  padding: 12px;
  border-radius: 16px;
  border: 1px solid rgba(255, 255, 255, 0.06);
  background: rgba(255, 255, 255, 0.03);
}

.vg-micro-label {
  margin: 10px 0 6px;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.45);
}

.vg-micro-label:first-child {
  margin-top: 0;
}

.vg-seg {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.vg-seg-btn {
  padding: 6px 12px;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: transparent;
  color: rgba(255, 255, 255, 0.75);
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
}

.vg-seg-btn.on {
  border-color: var(--ig-brand, #10b981);
  background: color-mix(in srgb, var(--ig-brand, #10b981) 18%, transparent);
  color: #fff;
}

.vg-warn {
  margin: 8px 0 0;
  font-size: 11px;
  color: #fbbf24;
}

.vg-aspect-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
}

.vg-aspect-cell {
  padding: 8px 4px;
  border-radius: 10px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: transparent;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  color: rgba(255, 255, 255, 0.65);
}

.vg-aspect-cell.on {
  border-color: var(--ig-brand, #10b981);
  color: var(--ig-brand, #10b981);
}

.vg-range {
  width: 100%;
  accent-color: var(--ig-brand, #10b981);
}

.vg-style-scroll {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding-bottom: 4px;
}

.vg-style-card {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 8px 10px;
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: transparent;
  font-size: 10px;
  cursor: pointer;
  color: rgba(255, 255, 255, 0.65);
}

.vg-style-card.on {
  border-color: var(--ig-brand, #10b981);
}

.vg-style-swatch {
  width: 22px;
  height: 22px;
  border-radius: 8px;
}

.vg-dir-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 6px;
}

.vg-dir-cell {
  padding: 8px 4px;
  border-radius: 10px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: transparent;
  font-size: 11px;
  cursor: pointer;
  color: rgba(255, 255, 255, 0.75);
}

.vg-dir-cell.on {
  border-color: var(--ig-brand, #10b981);
  color: var(--ig-brand, #10b981);
}

.vg-switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10px;
  font-size: 12px;
}

.vg-row2 {
  display: flex;
  align-items: center;
  gap: 8px;
}

.vg-num {
  flex: 1;
  min-width: 0;
  padding: 6px 8px;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(255, 255, 255, 0.04);
  color: inherit;
}

.vg-select {
  width: 100%;
  padding: 8px 10px;
  border-radius: 10px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(255, 255, 255, 0.04);
  color: inherit;
  font-size: 12px;
}

.vg-wave {
  margin-top: 10px;
  padding: 16px 8px;
  border-radius: 10px;
  text-align: center;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.35);
  background: repeating-linear-gradient(
    90deg,
    rgba(16, 185, 129, 0.15) 0 4px,
    transparent 4px 8px
  );
}

.vg-secondary-btn {
  width: 100%;
  padding: 8px 12px;
  border-radius: 10px;
  border: 1px solid rgba(255, 255, 255, 0.15);
  background: rgba(255, 255, 255, 0.05);
  color: inherit;
  cursor: pointer;
  font-size: 12px;
}

.vg-acc-head {
  width: 100%;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 12px;
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(255, 255, 255, 0.03);
  font-size: 12px;
  font-weight: 650;
  cursor: pointer;
  color: inherit;
}

.vg-small-ta {
  width: 100%;
  margin-top: 6px;
  padding: 8px;
  border-radius: 10px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: rgba(0, 0, 0, 0.25);
  color: inherit;
  font-family: inherit;
  resize: vertical;
}

.vg-muted {
  margin: 0;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.45);
}

.vg-recent-block {
  border-top: 1px solid rgba(255, 255, 255, 0.06);
  padding-top: 14px;
}

.vg-recent-empty {
  font-size: 12px;
  color: rgba(255, 255, 255, 0.35);
}

.vg-recent-strip {
  display: flex;
  gap: 8px;
  overflow-x: auto;
  padding: 6px 0;
}

.vg-recent-thumb {
  position: relative;
  flex: 0 0 auto;
  width: 72px;
  height: 48px;
  padding: 0;
  border: none;
  border-radius: 8px;
  overflow: hidden;
  cursor: pointer;
  background: #000;
}

.vg-recent-thumb:hover {
  transform: scale(1.06);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.45);
}

.vg-recent-vid {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.vg-recent-dur {
  position: absolute;
  right: 4px;
  bottom: 4px;
  font-size: 9px;
  padding: 2px 5px;
  border-radius: 4px;
  background: rgba(0, 0, 0, 0.65);
  color: #fff;
}

.vg-powered {
  font-size: 10px;
  color: rgba(255, 255, 255, 0.28);
  margin: 16px 0 8px;
}

.vg-status {
  flex-shrink: 0;
  text-align: center;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.35);
  padding: 4px 8px 10px;
}

/* 抽屉与弹窗 */
.vg-drawer-backdrop {
  position: fixed;
  inset: 0;
  z-index: 14000;
  background: var(--chat-backdrop, rgba(0, 0, 0, 0.55));
  display: flex;
  justify-content: flex-end;
}

.vg-proj-drawer {
  width: min(100%, 420px);
  height: 100%;
  background: color-mix(in srgb, #141416 94%, transparent);
  backdrop-filter: blur(22px);
  border-left: 1px solid rgba(255, 255, 255, 0.08);
  display: flex;
  flex-direction: column;
}

.vg-proj-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 18px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

.vg-proj-head h2 {
  margin: 0;
  font-size: 1rem;
}

.vg-proj-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 12px 14px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.vg-proj-grid {
  flex: 1;
  overflow-y: auto;
  padding: 14px;
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}

.vg-proj-empty {
  grid-column: 1 / -1;
  margin: 0;
  padding: 24px 12px;
  font-size: 13px;
  line-height: 1.55;
  color: rgba(255, 255, 255, 0.42);
  text-align: center;
}

.vg-proj-card {
  border-radius: 14px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  overflow: hidden;
  padding: 10px;
  background: rgba(255, 255, 255, 0.03);
  text-align: left;
  font: inherit;
  color: inherit;
  cursor: pointer;
  transition:
    transform 0.15s ease,
    border-color 0.15s ease;
}

.vg-proj-card:hover {
  transform: translateY(-2px);
  border-color: color-mix(in srgb, var(--ig-brand, #10b981) 35%, transparent);
}

.vg-proj-thumb {
  aspect-ratio: 16 / 10;
  border-radius: 10px;
  background: #0a0a0c;
  margin-bottom: 8px;
  overflow: hidden;
}

.vg-proj-thumb-vid {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
  pointer-events: none;
}

.vg-proj-title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
}

.vg-proj-meta {
  margin: 4px 0;
  font-size: 11px;
  color: rgba(255, 255, 255, 0.45);
}

.vg-proj-status {
  font-size: 10px;
  padding: 2px 8px;
  border-radius: 999px;
  display: inline-block;
}

.vg-proj-status[data-st='done'] {
  background: color-mix(in srgb, var(--ig-brand, #10b981) 22%, transparent);
  color: var(--ig-brand, #10b981);
}

.vg-modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 15000;
  background: rgba(0, 0, 0, 0.55);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}

.vg-export-modal {
  width: min(100%, 400px);
  border-radius: 20px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: #141416;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.5);
}

.vg-export-body {
  padding: 14px 18px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.vg-export-body label {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.45);
}

.vg-modal-foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 14px 18px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}

.vg-primary-btn {
  padding: 10px 18px;
  border-radius: 12px;
  border: none;
  background: linear-gradient(135deg, #10b981, #34d399);
  color: #042f2e;
  font-weight: 700;
  cursor: pointer;
}

.vg-ctx-menu {
  position: fixed;
  z-index: 16000;
  min-width: 160px;
  padding: 6px;
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.1);
  background: color-mix(in srgb, #141416 92%, transparent);
  backdrop-filter: blur(18px);
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.vg-ctx-menu button {
  padding: 8px 10px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: inherit;
  text-align: left;
  font-size: 12px;
  cursor: pointer;
}

.vg-ctx-menu button:hover {
  background: rgba(255, 255, 255, 0.06);
}

.vg-drawer-enter-active,
.vg-drawer-leave-active {
  transition: opacity 0.2s ease;
}

.vg-drawer-enter-from,
.vg-drawer-leave-to {
  opacity: 0;
}

@media (max-width: 1100px) {
  .vg-panel {
    position: absolute;
    right: 0;
    top: 56px;
    bottom: 0;
    z-index: 25;
    box-shadow: -12px 0 40px rgba(0, 0, 0, 0.4);
  }

  .vg-panel--collapsed {
    transform: translateX(100%);
    width: 280px;
  }
}

@media (max-width: 720px) {
  .vg-topbar-center {
    display: none;
  }

  .vg-profile-txt {
    display: none;
  }

  .vg-tool-strip-inner {
    flex-wrap: nowrap;
    justify-content: flex-start;
    overflow-x: auto;
    padding: 10px 12px;
    gap: 12px;
    -webkit-overflow-scrolling: touch;
  }

  .vg-tool-cap {
    font-size: 9px;
    max-width: 52px;
  }
}
</style>
