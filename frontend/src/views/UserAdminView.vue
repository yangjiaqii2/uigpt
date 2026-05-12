<script setup>
import { ref, computed, watch, onUnmounted, nextTick } from 'vue'
import {
  adminListUsers,
  adminCreateUser,
  adminUpdateUser,
  adminDeleteUser,
} from '../api/adminUsers'
import { getAxiosErrorMessage } from '../utils/httpError'

const loading = ref(false)
const saving = ref(false)
const error = ref('')
const page = ref(0)
const size = ref(15)
const totalPages = ref(0)
const totalElements = ref(0)
/** @type {import('vue').Ref<Array<Record<string, unknown>>>} */
const rows = ref([])

const filterUsername = ref('')
const filterPhone = ref('')
const filterCreatedFrom = ref('')
const filterCreatedTo = ref('')
/** 全部 ''；否则 '0'|'1'|'2' */
const filterPrivilege = ref('')

const modalOpen = ref(false)
const modalMode = ref('create') // 'create' | 'edit'
/** @type {import('vue').Ref<Record<string, unknown> | null>} */
const editing = ref(null)

const form = ref({
  username: '',
  password: '',
  realName: '',
  phone: '',
  nickname: '',
  status: 1,
  privilege: 0,
  newPassword: '',
  /** 新建：可选附加日配额；编辑：当前附加日配额 */
  pointsBonus: '',
  /** 编辑：当前可用积分 */
  points: 0,
})

/** @type {import('vue').Ref<Record<string, string>>} */
const fieldErrors = ref({})

const deleteOpen = ref(false)
/** @type {import('vue').Ref<Record<string, unknown> | null>} */
const deleteTarget = ref(null)
const deleteSubmitting = ref(false)
const deleteCanConfirm = ref(false)
let deleteCooldownTimer = 0

const STATUS_OPTIONS = [
  { value: 1, label: '正常' },
  { value: 0, label: '禁用' },
  { value: 2, label: '待审核' },
]

const PRIVILEGE_OPTIONS = [
  { value: 0, label: '普通用户' },
  { value: 1, label: '付费用户' },
  { value: 2, label: '超级管理员' },
]

function buildListParams() {
  const params = { page: page.value, size: size.value }
  const u = filterUsername.value.trim()
  if (u) params.username = u
  const ph = filterPhone.value.trim()
  if (ph) params.phone = ph
  const cf = filterCreatedFrom.value.trim()
  if (cf) params.createdFrom = cf
  const ct = filterCreatedTo.value.trim()
  if (ct) params.createdTo = ct
  const pv = filterPrivilege.value
  if (pv !== '' && pv !== null && pv !== undefined) {
    const n = Number(pv)
    if (n === 0 || n === 1 || n === 2) params.privilege = n
  }
  return params
}

function resetFilters() {
  filterUsername.value = ''
  filterPhone.value = ''
  filterCreatedFrom.value = ''
  filterCreatedTo.value = ''
  filterPrivilege.value = ''
  page.value = 0
  void load()
}

function applySearch() {
  page.value = 0
  void load()
}

const USERNAME_RE = /^[a-zA-Z0-9_\-\u4e00-\u9fa5]+$/
const PHONE_RE = /^1[3-9]\d{9}$/

const modalTitle = computed(() => (modalMode.value === 'create' ? '新建用户' : '编辑用户'))

function statusLabel(s) {
  const n = Number(s)
  if (n === 0) return '禁用'
  if (n === 2) return '待审核'
  return '正常'
}

function statusPillClass(s) {
  const n = Number(s)
  if (n === 0) return 'adm-pill adm-pill--off'
  if (n === 2) return 'adm-pill adm-pill--pending'
  return 'adm-pill adm-pill--ok'
}

function privilegeLabel(v) {
  const n = Number(v)
  if (n === 1) return '付费用户'
  if (n === 2) return '超级管理员'
  return '普通用户'
}

function privilegePillClass(v) {
  const n = Number(v)
  if (n === 2) return 'adm-pill adm-pill--role-super'
  if (n === 1) return 'adm-pill adm-pill--role-prem'
  return 'adm-pill adm-pill--role-std'
}

function normalizePrivilege(v) {
  const n = Number(v)
  if (n === 0 || n === 1 || n === 2) return n
  return 0
}

function normalizeStatus(v) {
  const n = Number(v)
  if (n === 0 || n === 2) return n
  return 1
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const { data } = await adminListUsers(buildListParams())
    rows.value = data.content ?? []
    totalPages.value = data.totalPages ?? 0
    totalElements.value = data.totalElements ?? 0
  } catch (e) {
    error.value = getAxiosErrorMessage(e)
    rows.value = []
  } finally {
    loading.value = false
  }
}

function clearFieldErrors() {
  fieldErrors.value = {}
}

function openCreate() {
  modalMode.value = 'create'
  editing.value = null
  form.value = {
    username: '',
    password: '',
    realName: '',
    phone: '',
    nickname: '',
    status: 1,
    privilege: 0,
    newPassword: '',
    pointsBonus: '',
    points: 0,
  }
  clearFieldErrors()
  modalOpen.value = true
}

/** @param {Record<string, unknown>} row */
function openEdit(row) {
  modalMode.value = 'edit'
  editing.value = row
  form.value = {
    username: String(row.username ?? ''),
    password: '',
    realName: String(row.realName ?? ''),
    phone: String(row.phone ?? ''),
    nickname: String(row.nickname ?? ''),
    status: normalizeStatus(row.status),
    privilege: normalizePrivilege(row.privilege),
    newPassword: '',
    points: Number(row.points ?? 0),
    pointsBonus: Number(row.pointsBonus ?? 0),
  }
  clearFieldErrors()
  modalOpen.value = true
}

function closeModal() {
  modalOpen.value = false
}

function validateForm() {
  clearFieldErrors()
  const err = {}
  const f = form.value

  if (modalMode.value === 'create') {
    const u = f.username.trim()
    if (!u) err.username = '请输入用户名'
    else if (u.length < 3 || u.length > 64) err.username = '用户名为 3～64 个字符'
    else if (!USERNAME_RE.test(u)) err.username = '仅支持字母、数字、下划线、横线或中文'

    if (!f.password) err.password = '请输入初始密码'
    else if (f.password.length < 8 || f.password.length > 72) err.password = '密码长度为 8～72 个字符'
  }

  const rn = f.realName.trim()
  if (!rn) err.realName = '请输入姓名'
  else if (rn.length > 64) err.realName = '姓名不超过 64 个字符'

  const ph = f.phone.trim()
  if (!ph) err.phone = '请输入手机号'
  else if (!PHONE_RE.test(ph)) err.phone = '请输入有效的 11 位中国大陆手机号'

  const nn = f.nickname.trim()
  if (nn.length > 128) err.nickname = '昵称过长'

  if (modalMode.value === 'edit' && f.newPassword.trim()) {
    const np = f.newPassword
    if (np.length < 8 || np.length > 72) err.newPassword = '新密码长度为 8～72 个字符'
  }

  if (modalMode.value === 'create') {
    const rawPb = String(f.pointsBonus ?? '').trim()
    if (rawPb !== '') {
      const n = Number(rawPb)
      if (!Number.isFinite(n) || Math.trunc(n) !== n) err.pointsBonus = '附加日配额须为整数'
      else if (n < -1_000_000 || n > 1_000_000) err.pointsBonus = '附加日配额超出允许范围'
    }
  }

  if (modalMode.value === 'edit') {
    const pv = Number(f.points)
    if (!Number.isFinite(pv) || Math.trunc(pv) !== pv) err.points = '当前积分须为整数'
    else if (pv < -1_000_000 || pv > 1_000_000) err.points = '当前积分超出允许范围'
    const bv = Number(f.pointsBonus)
    if (!Number.isFinite(bv) || Math.trunc(bv) !== bv) err.pointsBonus = '附加日配额须为整数'
    else if (bv < -1_000_000 || bv > 1_000_000) err.pointsBonus = '附加日配额超出允许范围'
  }

  fieldErrors.value = err
  return Object.keys(err).length === 0
}

const FIELD_SCROLL_ORDER = [
  'username',
  'password',
  'realName',
  'phone',
  'nickname',
  'status',
  'privilege',
  'points',
  'pointsBonus',
  'newPassword',
]

async function scrollToFirstError() {
  await nextTick()
  for (const key of FIELD_SCROLL_ORDER) {
    if (!fieldErrors.value[key]) continue
    const el = document.querySelector(`[data-ux-field="${key}"]`)
    if (el instanceof HTMLElement) {
      el.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
      break
    }
  }
}

async function submitModal() {
  if (!validateForm()) {
    void scrollToFirstError()
    return
  }
  saving.value = true
  error.value = ''
  try {
    if (modalMode.value === 'create') {
      await adminCreateUser({
        username: form.value.username.trim(),
        password: form.value.password,
        realName: form.value.realName.trim(),
        phone: form.value.phone.trim(),
        nickname: form.value.nickname.trim() || undefined,
        status: Number(form.value.status),
        privilege: Number(form.value.privilege),
        ...(() => {
          const raw = String(form.value.pointsBonus ?? '').trim()
          if (raw === '') return {}
          return { pointsBonus: Math.trunc(Number(raw)) }
        })(),
      })
    } else if (editing.value) {
      const id = editing.value.id
      const body = {
        realName: form.value.realName.trim(),
        phone: form.value.phone.trim(),
        nickname: form.value.nickname.trim() || null,
        status: Number(form.value.status),
        privilege: Number(form.value.privilege),
        points: Math.trunc(Number(form.value.points)),
        pointsBonus: Math.trunc(Number(form.value.pointsBonus)),
      }
      if (form.value.newPassword.trim()) {
        body.newPassword = form.value.newPassword
      }
      await adminUpdateUser(id, body)
    }
    modalOpen.value = false
    await load()
  } catch (e) {
    error.value = getAxiosErrorMessage(e)
  } finally {
    saving.value = false
  }
}

function openDelete(row) {
  deleteTarget.value = row
  deleteOpen.value = true
  deleteSubmitting.value = false
  deleteCanConfirm.value = false
  if (deleteCooldownTimer) window.clearTimeout(deleteCooldownTimer)
  deleteCooldownTimer = window.setTimeout(() => {
    deleteCanConfirm.value = true
    deleteCooldownTimer = 0
  }, 500)
}

function closeDelete() {
  deleteOpen.value = false
  deleteTarget.value = null
  deleteCanConfirm.value = false
  if (deleteCooldownTimer) {
    window.clearTimeout(deleteCooldownTimer)
    deleteCooldownTimer = 0
  }
}

async function confirmDelete() {
  const row = deleteTarget.value
  if (!row || !deleteCanConfirm.value || deleteSubmitting.value) return
  deleteSubmitting.value = true
  error.value = ''
  try {
    await adminDeleteUser(row.id)
    closeDelete()
    await load()
  } catch (e) {
    error.value = getAxiosErrorMessage(e)
  } finally {
    deleteSubmitting.value = false
  }
}

function prevPage() {
  if (page.value <= 0) return
  page.value -= 1
  void load()
}

function nextPage() {
  if (page.value >= totalPages.value - 1) return
  page.value += 1
  void load()
}

function fmtTime(v) {
  if (v == null || v === '') return '—'
  return String(v).replace('T', ' ').slice(0, 19)
}

function onGlobalKeydown(e) {
  if (e.key !== 'Escape') return
  if (deleteOpen.value) closeDelete()
  else if (modalOpen.value) closeModal()
}

watch(
  () => modalOpen.value || deleteOpen.value,
  (open) => {
    if (open) {
      document.addEventListener('keydown', onGlobalKeydown)
      document.body.style.overflow = 'hidden'
    } else {
      document.removeEventListener('keydown', onGlobalKeydown)
      document.body.style.overflow = ''
    }
  },
)

watch(modalOpen, (o) => {
  if (!o) clearFieldErrors()
})

onUnmounted(() => {
  document.removeEventListener('keydown', onGlobalKeydown)
  document.body.style.overflow = ''
  if (deleteCooldownTimer) window.clearTimeout(deleteCooldownTimer)
})

void load()
</script>

<template>
  <div class="adm">
    <header class="adm-head">
      <div>
        <h1 class="adm-title">用户管理</h1>
        <p class="adm-desc">
          查看、新建、编辑与删除系统用户；角色含普通/付费/超级管理员。访问本页须满足环境变量 UIGPT_ADMIN_USERNAMES 或账号在库中为超级管理员。积分按上海时区（Asia/Shanghai）自然日重置：普通 100/日、付费与超级管理员 2000/日，另加管理员「附加日配额」。
        </p>
      </div>
      <button type="button" class="adm-btn adm-btn--primary" :disabled="loading || saving" @click="openCreate">
        新建用户
      </button>
    </header>

    <section class="adm-filters" aria-label="用户筛选">
      <div class="adm-filter-row">
        <label class="adm-filter-field">
          <span class="adm-filter-label">用户名</span>
          <input
            v-model="filterUsername"
            class="adm-filter-input"
            type="search"
            autocomplete="off"
            placeholder="模糊匹配"
            @keydown.enter.prevent="applySearch"
          />
        </label>
        <label class="adm-filter-field">
          <span class="adm-filter-label">手机号</span>
          <input
            v-model="filterPhone"
            class="adm-filter-input"
            type="search"
            inputmode="tel"
            autocomplete="off"
            placeholder="包含匹配"
            @keydown.enter.prevent="applySearch"
          />
        </label>
        <label class="adm-filter-field">
          <span class="adm-filter-label">注册自</span>
          <input v-model="filterCreatedFrom" class="adm-filter-input adm-filter-input--date" type="date" />
        </label>
        <label class="adm-filter-field">
          <span class="adm-filter-label">注册至</span>
          <input v-model="filterCreatedTo" class="adm-filter-input adm-filter-input--date" type="date" />
        </label>
        <label class="adm-filter-field">
          <span class="adm-filter-label">角色</span>
          <select v-model="filterPrivilege" class="adm-filter-select">
            <option value="">全部</option>
            <option value="0">普通</option>
            <option value="1">付费</option>
            <option value="2">超级管理</option>
          </select>
        </label>
        <div class="adm-filter-actions">
          <button type="button" class="adm-btn adm-btn--primary" :disabled="loading || saving" @click="applySearch">
            查询
          </button>
          <button type="button" class="adm-btn" :disabled="loading || saving" @click="resetFilters">重置</button>
        </div>
      </div>
    </section>

    <p v-if="error" class="adm-err">{{ error }}</p>

    <div class="adm-table-wrap">
      <table v-if="!loading" class="adm-table">
        <thead>
          <tr>
            <th>ID</th>
            <th>用户名</th>
            <th>姓名</th>
            <th>手机</th>
            <th>昵称</th>
            <th>状态</th>
            <th>角色</th>
            <th>积分</th>
            <th>创建时间</th>
            <th class="adm-th-actions">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in rows" :key="r.id">
            <td>{{ r.id }}</td>
            <td class="adm-mono">{{ r.username }}</td>
            <td>{{ r.realName || '—' }}</td>
            <td>{{ r.phone || '—' }}</td>
            <td>{{ r.nickname || '—' }}</td>
            <td>
              <span :class="statusPillClass(r.status)">
                {{ statusLabel(r.status) }}
              </span>
            </td>
            <td>
              <span :class="privilegePillClass(r.privilege)">
                {{ privilegeLabel(r.privilege) }}
              </span>
            </td>
            <td class="adm-mono">{{ r.points ?? '—' }}</td>
            <td class="adm-muted">{{ fmtTime(r.createdAt) }}</td>
            <td class="adm-actions">
              <button type="button" class="adm-link" @click="openEdit(r)">编辑</button>
              <button type="button" class="adm-link adm-link--danger" @click="openDelete(r)">删除</button>
            </td>
          </tr>
          <tr v-if="rows.length === 0">
            <td colspan="10" class="adm-empty">暂无数据或无访问权限</td>
          </tr>
        </tbody>
      </table>
      <p v-else class="adm-loading">加载中…</p>
    </div>

    <footer v-if="totalPages > 1" class="adm-pager">
      <button type="button" class="adm-btn" :disabled="page <= 0 || loading" @click="prevPage">上一页</button>
      <span class="adm-pager-meta">{{ page + 1 }} / {{ totalPages }}（共 {{ totalElements }} 条）</span>
      <button
        type="button"
        class="adm-btn"
        :disabled="page >= totalPages - 1 || loading"
        @click="nextPage"
      >
        下一页
      </button>
    </footer>

    <!-- 新建 / 编辑 -->
    <Teleport to="body">
      <Transition name="adm-ux-layer">
        <div
          v-if="modalOpen"
          class="adm-ux-layer adm-ux-layer--form"
          role="presentation"
          aria-hidden="false"
        >
          <div class="adm-ux-scrim" @click="closeModal" />
          <div
            class="adm-ux-card adm-ux-card--form"
            role="dialog"
            aria-modal="true"
            :aria-label="modalTitle"
            @click.stop
          >
            <header class="adm-ux-head">
              <div class="adm-ux-head-left">
                <span class="adm-ux-head-ic" aria-hidden="true">{{ modalMode === 'create' ? '+' : '✏️' }}</span>
                <h2 class="adm-ux-title">{{ modalTitle }}</h2>
              </div>
              <button type="button" class="adm-ux-close" aria-label="关闭" title="关闭" @click="closeModal">
                ×
              </button>
            </header>

            <div class="adm-ux-body">
              <div
                v-if="modalMode === 'create'"
                class="adm-ux-field"
                data-ux-field="username"
                :class="{ 'adm-ux-field--err': !!fieldErrors.username }"
              >
                <label class="adm-ux-label" for="adm-in-user">用户名</label>
                <div class="adm-ux-input-wrap">
                  <span class="adm-ux-input-ic" aria-hidden="true">
                    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
                      <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                      <circle cx="12" cy="7" r="4" />
                    </svg>
                  </span>
                  <input id="adm-in-user" v-model="form.username" class="adm-ux-input" autocomplete="off" />
                </div>
                <p v-if="fieldErrors.username" class="adm-ux-err-strip">
                  <span class="adm-ux-err-ic">!</span>{{ fieldErrors.username }}
                </p>
              </div>

              <div
                v-if="modalMode === 'create'"
                class="adm-ux-field"
                data-ux-field="password"
                :class="{ 'adm-ux-field--err': !!fieldErrors.password }"
              >
                <label class="adm-ux-label" for="adm-in-pass">初始密码</label>
                <div class="adm-ux-input-wrap">
                  <span class="adm-ux-input-ic" aria-hidden="true">
                    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
                      <rect x="5" y="11" width="14" height="10" rx="2" />
                      <path d="M7 11V7a5 5 0 0 1 10 0v4" />
                    </svg>
                  </span>
                  <input
                    id="adm-in-pass"
                    v-model="form.password"
                    class="adm-ux-input"
                    type="password"
                    autocomplete="new-password"
                  />
                </div>
                <p v-if="fieldErrors.password" class="adm-ux-err-strip">
                  <span class="adm-ux-err-ic">!</span>{{ fieldErrors.password }}
                </p>
              </div>

              <div
                v-if="modalMode === 'edit'"
                class="adm-ux-field"
                data-ux-field="username"
              >
                <label class="adm-ux-label" for="adm-in-user-ro">用户名</label>
                <div class="adm-ux-input-wrap adm-ux-input-wrap--ro">
                  <span class="adm-ux-input-ic" aria-hidden="true">
                    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
                      <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                      <circle cx="12" cy="7" r="4" />
                    </svg>
                  </span>
                  <input id="adm-in-user-ro" class="adm-ux-input" :value="form.username" readonly tabindex="-1" />
                </div>
              </div>

              <div
                class="adm-ux-field"
                data-ux-field="realName"
                :class="{ 'adm-ux-field--err': !!fieldErrors.realName }"
              >
                <label class="adm-ux-label" for="adm-in-name">姓名</label>
                <div class="adm-ux-input-wrap">
                  <span class="adm-ux-input-ic" aria-hidden="true">
                    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
                      <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                      <circle cx="12" cy="7" r="4" />
                    </svg>
                  </span>
                  <input id="adm-in-name" v-model="form.realName" class="adm-ux-input" autocomplete="name" />
                </div>
                <p v-if="fieldErrors.realName" class="adm-ux-err-strip">
                  <span class="adm-ux-err-ic">!</span>{{ fieldErrors.realName }}
                </p>
              </div>

              <div
                class="adm-ux-field"
                data-ux-field="phone"
                :class="{ 'adm-ux-field--err': !!fieldErrors.phone }"
              >
                <label class="adm-ux-label" for="adm-in-phone">手机号</label>
                <div class="adm-ux-input-wrap">
                  <span class="adm-ux-input-ic" aria-hidden="true">
                    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
                      <rect x="6" y="3" width="12" height="18" rx="2" />
                      <path d="M10 18h4" />
                    </svg>
                  </span>
                  <input id="adm-in-phone" v-model="form.phone" class="adm-ux-input" autocomplete="tel" />
                </div>
                <p v-if="fieldErrors.phone" class="adm-ux-err-strip">
                  <span class="adm-ux-err-ic">!</span>{{ fieldErrors.phone }}
                </p>
              </div>

              <div
                class="adm-ux-field"
                data-ux-field="nickname"
                :class="{ 'adm-ux-field--err': !!fieldErrors.nickname }"
              >
                <label class="adm-ux-label" for="adm-in-nick">昵称</label>
                <div class="adm-ux-input-wrap">
                  <span class="adm-ux-input-ic" aria-hidden="true">
                    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
                      <path d="M4 20h16M6 16l6-10 4 6 4-2" />
                    </svg>
                  </span>
                  <input id="adm-in-nick" v-model="form.nickname" class="adm-ux-input" autocomplete="nickname" />
                </div>
                <p v-if="fieldErrors.nickname" class="adm-ux-err-strip">
                  <span class="adm-ux-err-ic">!</span>{{ fieldErrors.nickname }}
                </p>
              </div>

              <div class="adm-ux-field" data-ux-field="status">
                <span class="adm-ux-label">状态</span>
                <div class="adm-ux-seg" role="group" aria-label="账号状态">
                  <button
                    v-for="opt in STATUS_OPTIONS"
                    :key="opt.value"
                    type="button"
                    class="adm-ux-seg-btn"
                    :class="{ 'adm-ux-seg-btn--on': Number(form.status) === opt.value }"
                    @click="form.status = opt.value"
                  >
                    {{ opt.label }}
                  </button>
                </div>
              </div>

              <div class="adm-ux-field" data-ux-field="privilege">
                <span class="adm-ux-label">角色</span>
                <div class="adm-ux-seg" role="group" aria-label="用户角色">
                  <button
                    v-for="opt in PRIVILEGE_OPTIONS"
                    :key="opt.value"
                    type="button"
                    class="adm-ux-seg-btn"
                    :class="{ 'adm-ux-seg-btn--on': Number(form.privilege) === opt.value }"
                    @click="form.privilege = opt.value"
                  >
                    {{ opt.label }}
                  </button>
                </div>
              </div>

              <div
                v-if="modalMode === 'create'"
                class="adm-ux-field"
                data-ux-field="pointsBonus"
                :class="{ 'adm-ux-field--err': !!fieldErrors.pointsBonus }"
              >
                <label class="adm-ux-label" for="adm-in-pb-create">附加日配额（可选）</label>
                <div class="adm-ux-input-wrap">
                  <span class="adm-ux-input-ic" aria-hidden="true">∑</span>
                  <input
                    id="adm-in-pb-create"
                    v-model="form.pointsBonus"
                    class="adm-ux-input"
                    inputmode="numeric"
                    autocomplete="off"
                    placeholder="留空为 0"
                  />
                </div>
                <p v-if="fieldErrors.pointsBonus" class="adm-ux-err-strip">
                  <span class="adm-ux-err-ic">!</span>{{ fieldErrors.pointsBonus }}
                </p>
                <p class="adm-field-hint">
                  每个上海自然日与角色日上限相加后写入当日余额；仅管理员可设。
                </p>
              </div>

              <template v-if="modalMode === 'edit'">
                <div
                  class="adm-ux-field"
                  data-ux-field="points"
                  :class="{ 'adm-ux-field--err': !!fieldErrors.points }"
                >
                  <label class="adm-ux-label" for="adm-in-points">当前可用积分</label>
                  <div class="adm-ux-input-wrap">
                    <span class="adm-ux-input-ic" aria-hidden="true">◎</span>
                    <input
                      id="adm-in-points"
                      v-model.number="form.points"
                      class="adm-ux-input"
                      type="number"
                      step="1"
                      autocomplete="off"
                    />
                  </div>
                  <p v-if="fieldErrors.points" class="adm-ux-err-strip">
                    <span class="adm-ux-err-ic">!</span>{{ fieldErrors.points }}
                  </p>
                  <p class="adm-field-hint">
                    直接覆盖当日库内余额。次日 0 点（上海）起会按「角色日上限 + 附加日配额」重新派发，未用完的当日余额不结转。
                  </p>
                </div>
                <div
                  class="adm-ux-field"
                  data-ux-field="pointsBonus"
                  :class="{ 'adm-ux-field--err': !!fieldErrors.pointsBonus }"
                >
                  <label class="adm-ux-label" for="adm-in-pb-edit">附加日配额</label>
                  <div class="adm-ux-input-wrap">
                    <span class="adm-ux-input-ic" aria-hidden="true">∑</span>
                    <input
                      id="adm-in-pb-edit"
                      v-model.number="form.pointsBonus"
                      class="adm-ux-input"
                      type="number"
                      step="1"
                      autocomplete="off"
                    />
                  </div>
                  <p v-if="fieldErrors.pointsBonus" class="adm-ux-err-strip">
                    <span class="adm-ux-err-ic">!</span>{{ fieldErrors.pointsBonus }}
                  </p>
                  <p class="adm-field-hint">
                    持久字段；修改后若本日已按日历重置过，会立刻按新「日上限 + 附加」对齐当日余额。
                  </p>
                </div>
              </template>

              <div
                v-if="modalMode === 'edit'"
                class="adm-ux-field"
                data-ux-field="newPassword"
                :class="{ 'adm-ux-field--err': !!fieldErrors.newPassword }"
              >
                <label class="adm-ux-label" for="adm-in-np">重置密码（可空）</label>
                <div class="adm-ux-input-wrap">
                  <span class="adm-ux-input-ic" aria-hidden="true">
                    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
                      <rect x="5" y="11" width="14" height="10" rx="2" />
                      <path d="M7 11V7a5 5 0 0 1 10 0v4" />
                    </svg>
                  </span>
                  <input
                    id="adm-in-np"
                    v-model="form.newPassword"
                    class="adm-ux-input"
                    type="password"
                    autocomplete="new-password"
                  />
                </div>
                <p v-if="fieldErrors.newPassword" class="adm-ux-err-strip">
                  <span class="adm-ux-err-ic">!</span>{{ fieldErrors.newPassword }}
                </p>
              </div>
            </div>

            <footer class="adm-ux-foot">
              <button type="button" class="adm-ux-btn adm-ux-btn--ghost" :disabled="saving" @click="closeModal">
                取消
              </button>
              <button type="button" class="adm-ux-btn adm-ux-btn--primary" :disabled="saving" @click="submitModal">
                保存
              </button>
            </footer>
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- 删除确认 -->
    <Teleport to="body">
      <Transition name="adm-ux-layer-del">
        <div
          v-if="deleteOpen"
          class="adm-ux-layer adm-ux-layer--del"
          role="presentation"
          aria-hidden="false"
        >
          <div class="adm-ux-scrim" @click="closeDelete" />
          <div
            class="adm-ux-card adm-ux-card--del"
            role="alertdialog"
            aria-modal="true"
            aria-labelledby="adm-del-title"
            @click.stop
          >
            <div class="adm-ux-del-icon-wrap" aria-hidden="true">
              <span class="adm-ux-del-icon">⚠️</span>
            </div>
            <h2 id="adm-del-title" class="adm-ux-del-title">删除用户</h2>
            <p class="adm-ux-del-desc">
              确定要删除用户 [{{ deleteTarget?.username }}] 吗？相关会话与图片将一并删除（级联）。
            </p>
            <div class="adm-ux-del-warn">
              <span class="adm-ux-del-warn-ic" aria-hidden="true">!</span>
              <span>此操作不可恢复</span>
            </div>
            <footer class="adm-ux-foot adm-ux-foot--del">
              <button type="button" class="adm-ux-btn adm-ux-btn--ghost" :disabled="deleteSubmitting" @click="closeDelete">
                取消
              </button>
              <button
                type="button"
                class="adm-ux-btn adm-ux-btn--danger"
                :disabled="!deleteCanConfirm || deleteSubmitting"
                @click="confirmDelete"
              >
                <span v-if="deleteSubmitting" class="adm-ux-spinner" aria-hidden="true" />
                {{ deleteSubmitting ? '删除中…' : '确认删除' }}
              </button>
            </footer>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<style scoped>
.adm {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 22px 22px 28px;
  overflow: auto;
  box-sizing: border-box;
}

.adm-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.adm-title {
  margin: 0 0 6px;
  font-size: 1.25rem;
}

.adm-desc {
  margin: 0;
  font-size: 0.8125rem;
  color: var(--chat-muted, #9aa3b2);
  max-width: 720px;
}

.adm-filters {
  margin: 0 0 14px;
  padding: 12px 14px;
  border-radius: 12px;
  border: 1px solid var(--chat-border, #2f3542);
  background: color-mix(in srgb, var(--chat-panel, #1a1f2e) 88%, transparent);
}

.adm-filter-row {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 10px 12px;
}

.adm-filter-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  flex: 1 1 120px;
}

.adm-filter-field:has(.adm-filter-select) {
  flex: 0 1 132px;
}

.adm-filter-field:has(.adm-filter-input--date) {
  flex: 0 1 150px;
}

.adm-filter-label {
  font-size: 0.6875rem;
  font-weight: 600;
  color: var(--chat-muted, #9aa3b2);
  letter-spacing: 0.02em;
}

.adm-filter-input,
.adm-filter-select {
  min-height: 36px;
  padding: 6px 10px;
  border-radius: 8px;
  border: 1px solid var(--chat-border, #2f3542);
  background: var(--chat-btn-bg, #252a36);
  color: var(--chat-fg-strong, #fff);
  font-size: 0.8125rem;
  width: 100%;
  box-sizing: border-box;
}

.adm-filter-input:focus,
.adm-filter-select:focus {
  outline: none;
  border-color: color-mix(in srgb, var(--chat-link-accent-fg, #6366f1) 55%, transparent);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--chat-link-accent-fg, #6366f1) 18%, transparent);
}

.adm-filter-input--date {
  min-width: 0;
}

.adm-filter-select {
  cursor: pointer;
  appearance: none;
  background-image: linear-gradient(45deg, transparent 50%, var(--chat-muted, #9aa3b2) 50%),
    linear-gradient(135deg, var(--chat-muted, #9aa3b2) 50%, transparent 50%);
  background-position:
    calc(100% - 16px) calc(50% - 3px),
    calc(100% - 11px) calc(50% - 3px);
  background-size:
    5px 5px,
    5px 5px;
  background-repeat: no-repeat;
  padding-right: 28px;
}

.adm-filter-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  flex: 0 0 auto;
  margin-left: auto;
}

@media (max-width: 720px) {
  .adm-filter-actions {
    margin-left: 0;
    width: 100%;
    justify-content: flex-start;
  }
}

.adm-field-hint {
  margin: 6px 0 0;
  font-size: 0.6875rem;
  line-height: 1.45;
  color: var(--chat-muted-2, #8b95a8);
}

.adm-err {
  color: var(--chat-danger-fg, #f87171);
  font-size: 0.875rem;
  margin: 0 0 12px;
}

.adm-btn {
  padding: 8px 14px;
  border-radius: 8px;
  border: 1px solid var(--chat-border, #2f3542);
  background: var(--chat-btn-bg, #252a36);
  color: var(--chat-fg-strong, #fff);
  cursor: pointer;
  font-size: 0.8125rem;
}

.adm-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.adm-btn--primary {
  border-color: color-mix(in srgb, var(--chat-link-accent-fg, #6366f1) 55%, transparent);
  background: color-mix(in srgb, var(--chat-link-accent-fg, #6366f1) 35%, #1a1f2e);
}

.adm-table-wrap {
  border: 1px solid var(--chat-border, #2f3542);
  border-radius: 12px;
  overflow: auto;
  background: color-mix(in srgb, var(--chat-panel, #1a1f2e) 90%, transparent);
}

.adm-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.8125rem;
}

.adm-table th,
.adm-table td {
  padding: 10px 12px;
  text-align: left;
  border-bottom: 1px solid var(--chat-border, #2f3542);
}

.adm-table th {
  color: var(--chat-muted, #9aa3b2);
  font-weight: 600;
  white-space: nowrap;
}

.adm-th-actions {
  text-align: right;
}

.adm-actions {
  text-align: right;
  white-space: nowrap;
}

.adm-link {
  background: none;
  border: none;
  color: var(--chat-link-accent-fg, #818cf8);
  cursor: pointer;
  font-size: inherit;
  margin-left: 10px;
}

.adm-link--danger {
  color: #f87171;
}

.adm-mono {
  font-family: ui-monospace, monospace;
}

.adm-muted {
  color: var(--chat-muted-2, #8b95a8);
  white-space: nowrap;
}

.adm-pill {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 0.75rem;
}

.adm-pill--ok {
  background: color-mix(in srgb, #22c55e 22%, transparent);
  color: #bbf7d0;
}

.adm-pill--off {
  background: color-mix(in srgb, #f87171 22%, transparent);
  color: #fecaca;
}

.adm-pill--pending {
  background: color-mix(in srgb, #fbbf24 24%, transparent);
  color: #fef3c7;
}

.adm-pill--role-std {
  background: color-mix(in srgb, var(--chat-muted, #9aa3b2) 22%, transparent);
  color: var(--chat-muted, #cbd5e1);
}

.adm-pill--role-prem {
  background: color-mix(in srgb, #a78bfa 22%, transparent);
  color: #e9d5ff;
}

.adm-pill--role-super {
  background: color-mix(in srgb, #38bdf8 24%, transparent);
  color: #e0f2fe;
}

.adm-empty,
.adm-loading {
  text-align: center;
  padding: 28px 16px;
  color: var(--chat-muted, #9aa3b2);
}

.adm-pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  margin-top: 16px;
}

.adm-pager-meta {
  font-size: 0.8125rem;
  color: var(--chat-muted, #9aa3b2);
}

/* —— 模态统一：深色默认 —— */
.adm-ux-layer {
  position: fixed;
  inset: 0;
  z-index: 24000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  box-sizing: border-box;
}

.adm-ux-scrim {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  backdrop-filter: blur(4px);
  -webkit-backdrop-filter: blur(4px);
}

.adm-ux-card {
  position: relative;
  z-index: 1;
  width: 100%;
  max-height: min(90dvh, 900px);
  display: flex;
  flex-direction: column;
  border-radius: 22px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(26, 26, 30, 0.95);
  backdrop-filter: blur(24px);
  -webkit-backdrop-filter: blur(24px);
  box-shadow:
    0 0 0 1px rgba(255, 255, 255, 0.04) inset,
    0 24px 80px rgba(0, 0, 0, 0.55);
  overflow: hidden;
  color: #fff;
  --ux-brand: #10b981;
  --ux-brand-2: #34d399;
  --ux-muted: rgba(255, 255, 255, 0.6);
  --ux-muted-2: rgba(255, 255, 255, 0.4);
  --ux-input-bg: #141416;
  --ux-input-border: rgba(255, 255, 255, 0.06);
  --ux-danger-a: color-mix(in srgb, #ef4444 80%, white 20%);
  --ux-danger-b: color-mix(in srgb, #dc2626 75%, white 25%);
}

.adm-ux-card--form {
  max-width: 460px;
}

.adm-ux-card--del {
  max-width: 380px;
  text-align: center;
}

.adm-ux-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 18px 20px 14px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  flex-shrink: 0;
}

.adm-ux-head-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.adm-ux-head-ic {
  display: flex;
  width: 36px;
  height: 36px;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.06);
  font-size: 1.1rem;
  flex-shrink: 0;
}

.adm-ux-title {
  margin: 0;
  font-size: 18px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

.adm-ux-close {
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.06);
  color: rgba(255, 255, 255, 0.75);
  font-size: 1.35rem;
  line-height: 1;
  cursor: pointer;
  transition:
    transform 0.22s ease,
    background 0.15s ease,
    color 0.15s ease;
}

.adm-ux-close:hover {
  transform: rotate(90deg);
  background: rgba(255, 255, 255, 0.12);
  color: #fff;
}

.adm-ux-body {
  padding: 18px 20px 8px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.adm-ux-label {
  display: block;
  font-size: 12px;
  color: var(--ux-muted);
  margin-bottom: 6px;
  font-weight: 500;
}

.adm-ux-input-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 46px;
  padding: 0 12px 0 14px;
  border-radius: 12px;
  border: 1px solid var(--ux-input-border);
  background: var(--ux-input-bg);
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease;
}

.adm-ux-input-wrap:focus-within {
  border-color: var(--ux-brand);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--ux-brand) 22%, transparent);
}

.adm-ux-field--err .adm-ux-input-wrap {
  border-color: color-mix(in srgb, #ef4444 65%, transparent);
  box-shadow: 0 0 0 2px color-mix(in srgb, #ef4444 12%, transparent);
}

.adm-ux-input-wrap--ro {
  opacity: 0.85;
}

.adm-ux-input-ic {
  flex-shrink: 0;
  display: flex;
  color: var(--ux-muted-2);
}

.adm-ux-input {
  flex: 1;
  min-width: 0;
  height: 46px;
  border: none;
  background: transparent;
  color: #fff;
  font-size: 14px;
  outline: none;
}

.adm-ux-input::placeholder {
  color: rgba(255, 255, 255, 0.28);
}

.adm-ux-err-strip {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 8px 0 0;
  padding: 8px 10px;
  border-radius: 8px;
  font-size: 12px;
  line-height: 1.45;
  color: #fecaca;
  background: rgba(239, 68, 68, 0.1);
  border: 1px solid rgba(239, 68, 68, 0.18);
  animation: adm-ux-err-in 0.22s ease;
}

@keyframes adm-ux-err-in {
  from {
    opacity: 0;
    transform: translateY(-4px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.adm-ux-err-ic {
  flex-shrink: 0;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: rgba(239, 68, 68, 0.35);
  color: #fff;
  font-size: 11px;
  font-weight: 800;
  display: flex;
  align-items: center;
  justify-content: center;
}

.adm-ux-seg {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.adm-ux-seg-btn {
  flex: 1;
  min-width: 0;
  padding: 10px 12px;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.08);
  background: rgba(255, 255, 255, 0.04);
  color: var(--ux-muted);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition:
    background 0.18s ease,
    border-color 0.18s ease,
    color 0.18s ease;
}

.adm-ux-seg-btn:hover {
  background: rgba(255, 255, 255, 0.08);
  color: rgba(255, 255, 255, 0.88);
}

.adm-ux-seg-btn--on {
  border-color: color-mix(in srgb, var(--ux-brand) 55%, transparent);
  background: linear-gradient(
    135deg,
    color-mix(in srgb, var(--ux-brand) 35%, transparent),
    color-mix(in srgb, var(--ux-brand-2) 28%, transparent)
  );
  color: #fff;
}

.adm-ux-foot {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  gap: 12px;
  padding: 16px 20px 18px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
  flex-shrink: 0;
}

.adm-ux-foot--del {
  justify-content: flex-end;
}

.adm-ux-btn {
  min-height: 42px;
  padding: 0 20px;
  border-radius: 14px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  border: 1px solid transparent;
  transition:
    transform 0.12s ease,
    box-shadow 0.2s ease,
    background 0.18s ease,
    opacity 0.15s ease;
}

.adm-ux-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
  transform: none !important;
  box-shadow: none !important;
}

.adm-ux-btn--ghost {
  background: rgba(255, 255, 255, 0.06);
  border-color: rgba(255, 255, 255, 0.1);
  color: rgba(255, 255, 255, 0.88);
}

.adm-ux-btn--ghost:hover:not(:disabled) {
  background: rgba(255, 255, 255, 0.1);
}

.adm-ux-btn--primary {
  min-width: 112px;
  border: none;
  color: #fff;
  font-weight: 700;
  background: linear-gradient(135deg, #10b981, #34d399);
  box-shadow: 0 8px 24px color-mix(in srgb, #10b981 35%, transparent);
}

.adm-ux-btn--primary:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 12px 32px color-mix(in srgb, #10b981 45%, transparent);
}

.adm-ux-btn--primary:active:not(:disabled) {
  transform: scale(0.96) translateY(0);
}

.adm-ux-btn--danger {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  width: 100%;
  border: none;
  color: #fff;
  font-weight: 700;
  background: linear-gradient(135deg, var(--ux-danger-a), var(--ux-danger-b));
  box-shadow: 0 8px 26px color-mix(in srgb, #ef4444 28%, transparent);
}

.adm-ux-btn--danger:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 12px 34px color-mix(in srgb, #ef4444 38%, transparent);
}

.adm-ux-spinner {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  border: 2px solid rgba(255, 255, 255, 0.35);
  border-top-color: #fff;
  animation: adm-ux-spin 0.65s linear infinite;
}

@keyframes adm-ux-spin {
  to {
    transform: rotate(360deg);
  }
}

.adm-ux-del-icon-wrap {
  margin: 8px auto 12px;
  width: 56px;
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: color-mix(in srgb, #ef4444 16%, transparent);
  box-shadow: 0 0 0 8px color-mix(in srgb, #ef4444 6%, transparent);
  animation: adm-ux-pulse 2.2s ease-in-out infinite;
}

@keyframes adm-ux-pulse {
  0%,
  100% {
    box-shadow: 0 0 0 8px color-mix(in srgb, #ef4444 6%, transparent);
  }
  50% {
    box-shadow: 0 0 0 14px color-mix(in srgb, #ef4444 2%, transparent);
  }
}

.adm-ux-del-icon {
  font-size: 1.5rem;
  line-height: 1;
  filter: saturate(0.7);
}

.adm-ux-del-title {
  margin: 0 0 10px;
  font-size: 17px;
  font-weight: 700;
}

.adm-ux-del-desc {
  margin: 0 0 14px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--ux-muted);
  padding: 0 8px;
}

.adm-ux-del-warn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  margin: 0 0 18px;
  padding: 10px 12px;
  border-radius: 8px;
  background: rgba(239, 68, 68, 0.08);
  color: color-mix(in srgb, #ef4444 88%, white 12%);
  font-size: 12px;
  font-weight: 600;
}

.adm-ux-del-warn-ic {
  width: 20px;
  height: 20px;
  border-radius: 6px;
  background: color-mix(in srgb, #ef4444 22%, transparent);
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 800;
  font-size: 13px;
}

/* 进入 / 离开：整层含毛玻璃卡片 */
.adm-ux-layer-enter-active,
.adm-ux-layer-leave-active,
.adm-ux-layer-del-enter-active,
.adm-ux-layer-del-leave-active {
  transition: opacity 0.22s ease;
}

.adm-ux-layer-enter-active .adm-ux-card,
.adm-ux-layer-leave-active .adm-ux-card,
.adm-ux-layer-del-enter-active .adm-ux-card,
.adm-ux-layer-del-leave-active .adm-ux-card {
  transition:
    transform 0.25s cubic-bezier(0.34, 1.56, 0.64, 1),
    opacity 0.25s cubic-bezier(0.34, 1.56, 0.64, 1);
}

.adm-ux-layer-enter-from,
.adm-ux-layer-leave-to,
.adm-ux-layer-del-enter-from,
.adm-ux-layer-del-leave-to {
  opacity: 0;
}

.adm-ux-layer-enter-from .adm-ux-card,
.adm-ux-layer-leave-to .adm-ux-card,
.adm-ux-layer-del-enter-from .adm-ux-card,
.adm-ux-layer-del-leave-to .adm-ux-card {
  transform: scale(0.92);
  opacity: 0;
}

@media (max-width: 640px) {
  .adm-ux-layer {
    align-items: flex-end;
    padding: 0;
  }

  .adm-ux-layer-enter-from .adm-ux-card,
  .adm-ux-layer-leave-to .adm-ux-card,
  .adm-ux-layer-del-enter-from .adm-ux-card,
  .adm-ux-layer-del-leave-to .adm-ux-card {
    transform: translateY(110%);
    opacity: 0;
  }

  .adm-ux-layer-enter-active .adm-ux-card,
  .adm-ux-layer-leave-active .adm-ux-card,
  .adm-ux-layer-del-enter-active .adm-ux-card,
  .adm-ux-layer-del-leave-active .adm-ux-card {
    transition:
      transform 0.28s cubic-bezier(0.32, 0.72, 0, 1),
      opacity 0.22s ease;
  }

  .adm-ux-card {
    width: 92%;
    margin: 0 auto;
    max-width: none;
    border-radius: 20px 20px 0 0;
    max-height: 88dvh;
  }

  .adm-ux-foot {
    flex-direction: column-reverse;
    align-items: stretch;
  }

  .adm-ux-btn--primary,
  .adm-ux-btn--ghost,
  .adm-ux-btn--danger {
    width: 100%;
    justify-content: center;
  }
}

@media (prefers-reduced-motion: reduce) {
  .adm-ux-layer-enter-active,
  .adm-ux-layer-leave-active,
  .adm-ux-layer-del-enter-active,
  .adm-ux-layer-del-leave-active,
  .adm-ux-layer-enter-active .adm-ux-card,
  .adm-ux-layer-leave-active .adm-ux-card,
  .adm-ux-layer-del-enter-active .adm-ux-card,
  .adm-ux-layer-del-leave-active .adm-ux-card {
    transition-duration: 0.01ms !important;
  }

  .adm-ux-del-icon-wrap {
    animation: none;
  }

  .adm-ux-err-strip {
    animation: none;
  }

  .adm-ux-spinner {
    animation: none;
  }
}
</style>

<style>
/* 浅色主题（与 html[data-theme] 同步） */
html[data-theme='light'] .adm-ux-card {
  color: #0f172a;
  border-color: rgba(15, 23, 42, 0.08);
  background: rgba(255, 255, 255, 0.94);
  box-shadow:
    0 0 0 1px rgba(255, 255, 255, 0.8) inset,
    0 24px 80px rgba(15, 23, 42, 0.12);
  --ux-muted: rgba(15, 23, 42, 0.55);
  --ux-muted-2: rgba(15, 23, 42, 0.38);
  --ux-input-bg: #f4f4f5;
  --ux-input-border: rgba(15, 23, 42, 0.1);
}

html[data-theme='light'] .adm-ux-scrim {
  background: rgba(15, 23, 42, 0.45);
}

html[data-theme='light'] .adm-ux-head {
  border-bottom-color: rgba(15, 23, 42, 0.08);
}

html[data-theme='light'] .adm-ux-head-ic {
  background: rgba(15, 23, 42, 0.06);
}

html[data-theme='light'] .adm-ux-close {
  background: rgba(15, 23, 42, 0.06);
  color: rgba(15, 23, 42, 0.65);
}

html[data-theme='light'] .adm-ux-close:hover {
  background: rgba(15, 23, 42, 0.1);
  color: #0f172a;
}

html[data-theme='light'] .adm-ux-input {
  color: #0f172a;
}

html[data-theme='light'] .adm-ux-input::placeholder {
  color: rgba(15, 23, 42, 0.35);
}

html[data-theme='light'] .adm-ux-foot {
  border-top-color: rgba(15, 23, 42, 0.08);
}

html[data-theme='light'] .adm-ux-btn--ghost {
  background: rgba(15, 23, 42, 0.06);
  border-color: rgba(15, 23, 42, 0.12);
  color: #0f172a;
}

html[data-theme='light'] .adm-ux-btn--ghost:hover:not(:disabled) {
  background: rgba(15, 23, 42, 0.1);
}

html[data-theme='light'] .adm-ux-seg-btn {
  border-color: rgba(15, 23, 42, 0.1);
  background: rgba(15, 23, 42, 0.04);
  color: rgba(15, 23, 42, 0.55);
}

html[data-theme='light'] .adm-ux-seg-btn:hover {
  background: rgba(15, 23, 42, 0.08);
  color: #0f172a;
}

html[data-theme='light'] .adm-ux-input-wrap--ro {
  opacity: 1;
}
</style>
