<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { setPlatformInCommunity } from '../authSession'
import { residentRoleLabel, RESIDENT_ROLE_OPTIONS } from '../residentRoles'
import NoticeRichEditor from '../components/NoticeRichEditor.vue'
import {
  extractAttachmentIds,
  legacyToHtml,
  rewriteAttachmentUrls,
} from '../noticeContent'

const router = useRouter()
const activeTab = ref('communities')
const list = ref<any[]>([])
const apps = ref<any[]>([])
const name = ref('')
const searchName = ref('')
const searchCodes = ref<string[]>([])
const loading = ref(false)
const appsLoading = ref(false)
const appStatus = ref('PENDING')

const users = ref<any[]>([])
const usersLoading = ref(false)
const userNameQ = ref('')
const userMobileQ = ref('')
const userCommunityQ = ref<number | ''>('')
/** 默认只看物业经理：平台仅任命经理，一线执行岗由小区「岗位人员」维护 */
const userManagerOnly = ref(true)
const userPage = ref(1)
const userTotal = ref(0)

const occupants = ref<any[]>([])
const occupantsLoading = ref(false)
const occCommunityQ = ref<number | ''>('')
const occRealNameQ = ref('')
const occMobileQ = ref('')
const occRoomNoQ = ref('')
const occIdCardQ = ref('')
const occPage = ref(1)
const occTotal = ref(0)
const occLoadedOnce = ref(false)
const occSelection = ref<any[]>([])
const occEditVisible = ref(false)
const occEditSaving = ref(false)

const aboutContent = ref('')
const aboutLoading = ref(false)
const aboutSaving = ref(false)
const aboutEditorRef = ref<InstanceType<typeof NoticeRichEditor> | null>(null)
const aboutBlobToId = ref(new Map<string, number>())
const aboutObjectUrls = ref<string[]>([])
const aboutSrcCache = ref<Record<number, string>>({})
const aboutLoaded = ref(false)
const occEditForm = ref({
  id: 0,
  communityId: 0,
  communityName: '',
  roomId: 0,
  roomLabel: '',
  userId: 0,
  realName: '',
  mobile: '',
  originalMobile: '',
  idCardNo: '',
  residentRole: 'OWNER',
})
const occBatchDeleting = ref(false)

const personAddVisible = ref(false)
const personEditVisible = ref(false)
const personSaving = ref(false)
const personForm = ref({
  id: 0,
  realName: '',
  mobile: '',
  originalMobile: '',
  password: '',
  /** 多社区任职：每行 { communityId, roleKey }，roleKey 仅 STAFF:*（业委会在小区住户栏任命） */
  bindings: [] as { communityId: number | null; roleKey: string }[],
  status: 1,
  hasPassword: false,
  mustChangePassword: false,
  /** 进入弹窗时该账号是否持有物业经理岗；否则本弹窗只改姓名/手机，不触碰任职 */
  hadManagerBinding: false,
})

const roleLabel = (r: string) => (r === 'STAFF' ? '物业' : '住户')
const statusLabel = (s: string) =>
  ({ PENDING: '待审核', APPROVED: '已通过', REJECTED: '已拒绝' } as Record<string, string>)[s] || s
const templateLabel = (code: string | null | undefined) => {
  if (!code) return '不初始化'
  const m = /^C_(\d+)_(\d+)_(\d+)_(\d+)$/.exec(code)
  if (m) return `自定义 ${m[1]}×${m[2]}×${m[3]}×${m[4]}`
  const presets: Record<string, string> = {
    T_1_3_10_4: '标准高层',
    T_1_2_6_2: '小高层',
    T_2_2_12_4: '双栋高层',
  }
  return presets[code] || code
}
const staffRoleLabel = (r: string) =>
  ({
    PROPERTY_MANAGER: '物业经理',
    CUSTOMER_SERVICE: '客服',
    SECURITY: '保安',
    CLEANING: '保洁',
    LANDSCAPING: '绿化',
    FACILITY_MAINT: '机电维修',
    /** 历史物业管理员 ≡ 客服 */
    PROPERTY_ADMIN: '客服',
  } as Record<string, string>)[r] || r

/** 平台仅任命物业经理；一线岗在物业后台「岗位人员」配置 */
const positionOptions = [
  { value: 'STAFF:PROPERTY_MANAGER', label: '物业经理' },
]

function emptyBinding() {
  return { communityId: null as number | null, roleKey: 'STAFF:PROPERTY_MANAGER' }
}

function bindingsFromUser(u: any) {
  const rows: { communityId: number | null; roleKey: string }[] = []
  const seen = new Set<number>()
  for (const a of (u?.affiliations || []).filter((x: any) => x.status === 'ACTIVE')) {
    if (a.type !== 'STAFF') continue
    const roles: string[] = Array.isArray(a.roles) && a.roles.length
      ? a.roles
      : (a.staffRole ? [a.staffRole] : [])
    if (!roles.includes('PROPERTY_MANAGER')) continue
    if (seen.has(a.communityId)) continue
    seen.add(a.communityId)
    rows.push({ communityId: a.communityId, roleKey: 'STAFF:PROPERTY_MANAGER' })
  }
  return rows
}

function bindingsToItems(bindings: { communityId: number | null; roleKey: string }[]) {
  const items: { communityId: number; staffRole: string }[] = []
  const staffSeen = new Set<number>()
  for (const b of bindings) {
    if (b.communityId == null || !b.roleKey) continue
    if (!b.roleKey.startsWith('STAFF:')) continue
    if (staffSeen.has(b.communityId)) {
      throw new Error('同一小区不可重复配置物业经理')
    }
    staffSeen.add(b.communityId)
    items.push({ communityId: b.communityId, staffRole: 'PROPERTY_MANAGER' })
  }
  return items
}

function addBinding() {
  personForm.value.bindings.push(emptyBinding())
}

function removeBinding(idx: number) {
  personForm.value.bindings.splice(idx, 1)
}

async function load() {
  loading.value = true
  try {
    const params: Record<string, string | number> = { page: 1, pageSize: 50 }
    const q = searchName.value.trim()
    if (q) params.name = q
    const region = namesFromCodes(searchCodes.value)
    if (region.provinceName) params.provinceName = region.provinceName
    if (region.cityName) params.cityName = region.cityName
    if (region.districtName) params.districtName = region.districtName
    const { data } = await api.get('/platform/communities', { params })
    if (data.code === 0) list.value = data.data.list || []
    else ElMessage.error(data.message)
  } finally {
    loading.value = false
  }
}

function resetSearch() {
  searchName.value = ''
  searchCodes.value = []
  load()
}

async function loadApps() {
  appsLoading.value = true
  try {
    const { data } = await api.get('/platform/community-applications', {
      params: { status: appStatus.value || undefined, page: 1, pageSize: 50 },
    })
    if (data.code === 0) apps.value = data.data.list || []
    else ElMessage.error(data.message)
  } finally {
    appsLoading.value = false
  }
}

async function createOne() {
  if (!name.value.trim()) return
  const { data } = await api.post('/platform/communities', { name: name.value, hasFormalCommittee: 0 })
  if (data.code === 0) {
    ElMessage.success('已开户')
    name.value = ''
    load()
  } else ElMessage.error(data.message)
}

type RegionNode = { value: string; label: string; children?: RegionNode[] }

const addrVisible = ref(false)
const addrSaving = ref(false)
const addrRow = ref<any>(null)
const addrCodes = ref<string[]>([])
const addrDetail = ref('')
const regionOptions = ref<RegionNode[]>([])

function mapRegions(list: any[]): RegionNode[] {
  return (list || []).map((p: any) => ({
    value: String(p.code),
    label: String(p.name),
    children: (p.cities || []).map((c: any) => ({
      value: String(c.code),
      label: String(c.name),
      children: (c.districts || []).map((d: any) => ({
        value: String(d.code),
        label: String(d.name),
      })),
    })),
  }))
}

function namesFromCodes(codes: string[]) {
  const [pc, cc, dc] = codes
  const p = regionOptions.value.find((x) => x.value === pc)
  const c = p?.children?.find((x) => x.value === cc)
  const d = c?.children?.find((x) => x.value === dc)
  return {
    provinceCode: pc,
    provinceName: p?.label || '',
    cityCode: cc,
    cityName: c?.label || '',
    districtCode: dc,
    districtName: d?.label || '',
  }
}

function parseStoredAddress(address: string) {
  if (!address) return { codes: [] as string[], detail: '' }
  let best = { codes: [] as string[], detail: address, len: -1 }
  for (const p of regionOptions.value) {
    for (const c of p.children || []) {
      for (const d of c.children || []) {
        const full = p.label + c.label + d.label
        const dedup = p.label === c.label ? p.label + d.label : full
        for (const prefix of [full, dedup]) {
          if (address.startsWith(prefix) && prefix.length > best.len) {
            best = {
              codes: [p.value, c.value, d.value],
              detail: address.slice(prefix.length),
              len: prefix.length,
            }
          }
        }
      }
    }
  }
  return { codes: best.codes, detail: best.detail }
}

async function ensureRegions() {
  if (regionOptions.value.length) return
  const { data } = await api.get('/regions')
  if (data.code === 0) regionOptions.value = mapRegions(data.data || [])
}

async function openAddress(row: any) {
  await ensureRegions()
  addrRow.value = row
  const parsed = parseStoredAddress(row.address || '')
  addrCodes.value = parsed.codes
  addrDetail.value = parsed.detail
  addrVisible.value = true
}

async function saveAddress() {
  if (!addrRow.value) return
  if (addrCodes.value.length !== 3) {
    ElMessage.warning('请选择省 / 市 / 区')
    return
  }
  if (!addrDetail.value.trim()) {
    ElMessage.warning('请填写具体地址')
    return
  }
  addrSaving.value = true
  try {
    const region = namesFromCodes(addrCodes.value)
    const { data } = await api.put(`/platform/communities/${addrRow.value.id}`, {
      name: addrRow.value.name,
      ...region,
      addressDetail: addrDetail.value.trim(),
    })
    if (data.code === 0) {
      ElMessage.success('地址已更新')
      addrVisible.value = false
      load()
    } else ElMessage.error(data.message)
  } finally {
    addrSaving.value = false
  }
}

async function enterCommunity(row: any) {
  const { data } = await api.post(`/platform/communities/${row.id}/enter`)
  if (data.code !== 0) {
    ElMessage.error(data.message)
    return
  }
  setPlatformInCommunity(data.data.token, data.data.community?.name || row.name, row.id)
  ElMessage.success(`已进入「${data.data.community?.name || row.name}」`)
  router.push('/space')
}

async function deleteCommunity(row: any) {
  try {
    await ElMessageBox.confirm(
      `确认永久删除小区「${row.name}」？下属空间、任职、住户绑定及本小区账单等数据将一并删除，不可恢复。`,
      '删除小区',
      { confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  let { data } = await api.delete(`/platform/communities/${row.id}`, { params: { force: false } })
  if (data.code !== 0 && String(data.message || '').includes('住户')) {
    try {
        await ElMessageBox.confirm(data.message || '该小区尚有住户，确认后将永久删除', '二次确认', {
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        type: 'warning',
      })
    } catch {
      return
    }
    ;({ data } = await api.delete(`/platform/communities/${row.id}`, { params: { force: true } }))
  }
  if (data.code === 0) {
    ElMessage.success('已删除小区')
    load()
  } else ElMessage.error(data.message)
}

async function approveApp(row: any) {
  try {
    await ElMessageBox.confirm(`通过「${row.communityName}」申请并建小区？`, '审核通过')
  } catch {
    return
  }
  const { data } = await api.post(`/platform/community-applications/${row.id}/approve`)
  if (data.code === 0) {
    ElMessage.success(`已建小区 id=${data.data.communityId}`)
    loadApps()
    load()
  } else ElMessage.error(data.message)
}

async function rejectApp(row: any) {
  let reason = ''
  try {
    const { value } = await ElMessageBox.prompt('请填写拒绝原因', '拒绝申请', {
      inputPlaceholder: '原因',
      inputValidator: (v) => (!!v && !!String(v).trim()) || '必填',
    })
    reason = String(value).trim()
  } catch {
    return
  }
  const { data } = await api.post(`/platform/community-applications/${row.id}/reject`, {
    rejectReason: reason,
  })
  if (data.code === 0) {
    ElMessage.success('已拒绝')
    loadApps()
  } else ElMessage.error(data.message)
}

function personCommunityText(u: any) {
  const names = [
    ...new Set(
      (u.affiliations || [])
        .filter((a: any) => a.status === 'ACTIVE' && (a.type === 'STAFF' || a.type === 'COMMITTEE'))
        .map((a: any) => a.communityName)
        .filter(Boolean),
    ),
  ]
  return names.length ? names.join('、') : '—'
}

function personRoleText(u: any) {
  const parts: string[] = []
  const seen = new Set<string>()
  const add = (s: string) => {
    if (!seen.has(s)) {
      seen.add(s)
      parts.push(s)
    }
  }
  for (const a of (u.affiliations || []).filter((x: any) => x.status === 'ACTIVE' && x.type === 'STAFF')) {
    const cname = a.communityName || '小区'
    const labels = a.roleLabels
      || (Array.isArray(a.roles) ? a.roles.map((r: string) => staffRoleLabel(r)).join('·') : '')
      || staffRoleLabel(a.staffRole)
    add(`${cname}·${labels}`)
  }
  if (u.isPlatformAdmin === 1) add('平台管理员')
  if (u.status === 0) add('已停用')
  return parts.length ? parts.join('；') : '—'
}

/** 新建/重置 Web 登录密码时的默认密码；当事人首次登录 Web 管理端会被强制修改 */
const DEFAULT_PASSWORD = 'linliyuan123'

function fillDefaultPassword() {
  personForm.value.password = DEFAULT_PASSWORD
}

/** 该账号是否持有可登录 Web 管理端的岗位（客服 / 物业经理） */
function hasWebRoleUser(u: any) {
  return (u.affiliations || []).some(
    (a: any) =>
      a.status === 'ACTIVE' &&
      a.type === 'STAFF' &&
      Array.isArray(a.roles) &&
      a.roles.some((r: string) => r === 'CUSTOMER_SERVICE' || r === 'PROPERTY_MANAGER'),
  )
}

/**
 * Web 密码只对客服与物业经理有意义（只有这两种岗位能登 Web 管理端）；
 * 保安 / 保洁 / 绿化 / 机电只用小程序，显示 '-'，避免出现无意义的「未设密」。
 */
function personPasswordText(u: any) {
  if (!hasWebRoleUser(u)) return '-'
  if (u.mustChangePassword) return '须改密'
  if (u.hasPassword) return '已设密'
  return '未设密'
}

function personPasswordClass(u: any) {
  const t = personPasswordText(u)
  if (t === '须改密') return 'pwd-todo'
  if (t === '未设密') return 'pwd-none'
  if (t === '-') return 'pwd-na'
  return ''
}

/**
 * 平台管理员重置物业人员（含物业经理、客服）的 Web 登录密码。
 * 重置后 must_change_password=1，对方首次登录 Web 管理端须修改密码。
 */
async function resetPersonPassword(row: any) {
  if (row.isPlatformAdmin === 1) {
    ElMessage.warning('不可通过此入口重置平台管理员密码')
    return
  }
  if (!hasWebRoleUser(row)) {
    ElMessage.warning('该人员不持有可登录 Web 管理端的岗位（客服 / 物业经理），无 Web 密码可重置')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将「${row.realName || row.mobile}」的 Web 登录密码重置为默认密码 ${DEFAULT_PASSWORD}？重置后对方首次登录 Web 管理端须修改密码。`,
      '重置 Web 密码',
      { confirmButtonText: '重置', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  const { data } = await api.post('/platform/users/reset-password', {
    mobile: row.mobile,
    newPassword: DEFAULT_PASSWORD,
  })
  if (data.code === 0) {
    ElMessage.success(`已重置为默认密码 ${DEFAULT_PASSWORD}，请告知当事人；其首次登录须修改密码`)
    loadUsers()
  } else ElMessage.error(data.message || '重置失败')
}

function openPersonAdd() {
  personForm.value = {
    id: 0,
    realName: '',
    mobile: '',
    originalMobile: '',
    password: DEFAULT_PASSWORD,
    bindings: list.value[0]?.id
      ? [{ communityId: list.value[0].id, roleKey: 'STAFF:PROPERTY_MANAGER' }]
      : [],
    status: 1,
    hasPassword: false,
    mustChangePassword: false,
    hadManagerBinding: false,
  }
  personAddVisible.value = true
}

function openPersonEdit(row: any) {
  if (row.isPlatformAdmin === 1) {
    ElMessage.warning('不可改平台管理员')
    return
  }
  personForm.value = {
    id: row.id,
    realName: row.realName || '',
    mobile: row.mobile || '',
    originalMobile: row.mobile || '',
    password: '',
    bindings: bindingsFromUser(row),
    status: row.status,
    hasPassword: !!row.hasPassword,
    mustChangePassword: !!row.mustChangePassword,
    hadManagerBinding: (bindingsFromUser(row) || []).some(
      (b: any) => b.roleKey === 'STAFF:PROPERTY_MANAGER',
    ),
  }
  personEditVisible.value = true
}

async function savePersonAdd() {
  const f = personForm.value
  if (!/^\d{11}$/.test(f.mobile.trim())) {
    ElMessage.warning('手机号须为 11 位数字')
    return
  }
  if (!f.password || f.password.length < 6) {
    ElMessage.warning('密码至少 6 位')
    return
  }
  for (const b of f.bindings) {
    if (b.communityId == null || !b.roleKey) {
      ElMessage.warning('请完善每条任职的社区与岗位，或删除空行')
      return
    }
  }
  let items: { communityId: number; staffRole: string }[]
  try {
    items = bindingsToItems(f.bindings)
  } catch (e: any) {
    ElMessage.warning(e.message || '任职配置有误')
    return
  }
  personSaving.value = true
  try {
    const { data } = await api.post('/platform/staff-users', {
      mobile: f.mobile.trim(),
      password: f.password,
      realName: f.realName.trim() || undefined,
    })
    if (data.code !== 0) {
      ElMessage.error(data.message)
      return
    }
    const userId = data.data.id
    if (items.length) {
      const as = await api.put(`/platform/users/${userId}/community-roles`, { items })
      if (as.data.code !== 0) {
        ElMessage.warning(`账号已建，岗位设置失败：${as.data.message}`)
        personAddVisible.value = false
        loadUsers()
        return
      }
    }
    ElMessage.success('已新增。请将临时密码告知当事人，首次登录物业后台须修改密码')
    personAddVisible.value = false
    loadUsers()
  } finally {
    personSaving.value = false
  }
}

async function savePersonEdit() {
  const f = personForm.value
  if (!/^\d{11}$/.test(f.mobile.trim())) {
    ElMessage.warning('手机号须为 11 位数字')
    return
  }
  for (const b of f.bindings) {
    if (b.communityId == null || !b.roleKey) {
      ElMessage.warning('请完善每条任职的社区与岗位，或删除空行')
      return
    }
  }
  let items: { communityId: number; staffRole: string }[]
  try {
    items = bindingsToItems(f.bindings)
  } catch (e: any) {
    ElMessage.warning(e.message || '任职配置有误')
    return
  }
  const willHaveStaff = items.length > 0
  if (willHaveStaff && !f.hasPassword && !f.password.trim()) {
    ElMessage.warning('转为物业人员须设置临时密码（对方首次登录后须修改）')
    return
  }
  if (f.password.trim() && f.password.length < 6) {
    ElMessage.warning('临时密码至少 6 位')
    return
  }
  personSaving.value = true
  try {
    const mobileChanged = f.mobile.trim() !== (f.originalMobile || '').trim()
    const { data } = await api.put(`/platform/users/${f.id}`, {
      realName: f.realName.trim(),
      mobile: f.mobile.trim(),
    })
    if (data.code !== 0) {
      ElMessage.error(data.message)
      return
    }
    if (f.password.trim()) {
      const rp = await api.post('/platform/users/reset-password', {
        mobile: f.mobile.trim(),
        newPassword: f.password,
      })
      if (rp.data.code !== 0) {
        ElMessage.error(rp.data.message)
        return
      }
    }
    if (f.status === 1) {
      const sc = await api.put(`/platform/users/${f.id}/community-roles`, { items })
      if (sc.data.code !== 0) {
        ElMessage.error(sc.data.message)
        return
      }
    }
    let ok = '已保存'
    if (mobileChanged) {
      ok = '已保存。手机号已变更，对方须用新号重新微信授权登录小程序'
    } else if (f.password.trim()) {
      ok = '已保存。请将临时密码当面告知当事人，首次登录须修改后方可进入后台'
    }
    ElMessage.success(ok)
    personEditVisible.value = false
    loadUsers()
  } finally {
    personSaving.value = false
  }
}

async function loadUsers() {
  usersLoading.value = true
  try {
    const { data } = await api.get('/platform/users', {
      params: {
        realName: userNameQ.value.trim() || undefined,
        mobile: userMobileQ.value.trim() || undefined,
        communityId: userCommunityQ.value === '' ? undefined : userCommunityQ.value,
        managerOnly: userManagerOnly.value ? true : undefined,
        page: userPage.value,
        pageSize: 20,
      },
    })
    if (data.code === 0) {
      users.value = (data.data.list || []).map((u: any) => ({
        ...u,
        communityText: personCommunityText(u),
        roleText: personRoleText(u),
      }))
      userTotal.value = data.data.total || 0
    } else ElMessage.error(data.message)
  } finally {
    usersLoading.value = false
  }
}

async function loadOccupants() {
  occupantsLoading.value = true
  try {
    const { data } = await api.get('/platform/occupants', {
      params: {
        page: occPage.value,
        pageSize: 20,
        communityId: occCommunityQ.value === '' ? undefined : occCommunityQ.value,
        realName: occRealNameQ.value.trim() || undefined,
        mobile: occMobileQ.value.trim() || undefined,
        roomNo: occRoomNoQ.value.trim() || undefined,
        idCardNo: occIdCardQ.value.trim() || undefined,
      },
    })
    if (data.code === 0) {
      occupants.value = data.data.list || []
      occTotal.value = data.data.total || 0
      occLoadedOnce.value = true
      occSelection.value = []
    } else ElMessage.error(data.message)
  } finally {
    occupantsLoading.value = false
  }
}

function resetOccupantSearch() {
  occCommunityQ.value = ''
  occRealNameQ.value = ''
  occMobileQ.value = ''
  occRoomNoQ.value = ''
  occIdCardQ.value = ''
  occPage.value = 1
  loadOccupants()
}

function onOccSelectionChange(rows: any[]) {
  occSelection.value = rows
}

function openOccEdit(row: any) {
  const roomParts = [row.buildingName, row.unitName, row.floorName, row.roomNo].filter(Boolean)
  occEditForm.value = {
    id: row.id,
    communityId: row.communityId,
    communityName: row.communityName || '',
    roomId: row.roomId,
    roomLabel: roomParts.join('/') || String(row.roomId || ''),
    userId: row.userId,
    realName: row.realName || '',
    mobile: row.mobile || '',
    originalMobile: row.mobile || '',
    idCardNo: row.idCardNo || '',
    residentRole: row.residentRole || 'OWNER',
  }
  occEditVisible.value = true
}

async function saveOccEdit() {
  const f = occEditForm.value
  const mobile = (f.mobile || '').trim()
  if (!/^1\d{10}$/.test(mobile)) {
    ElMessage.warning('手机号须为 1 开头的 11 位数字')
    return
  }
  if (f.residentRole === 'OWNER' && !(f.idCardNo || '').trim()) {
    ElMessage.warning('业主须填写身份证')
    return
  }
  occEditSaving.value = true
  try {
    const body: Record<string, unknown> = {
      action: 'BIND',
      communityId: f.communityId,
      roomId: f.roomId,
      userId: f.userId,
      mobile,
      role: f.residentRole,
      realName: (f.realName || '').trim() || undefined,
    }
    if ((f.idCardNo || '').trim()) body.idCardNo = f.idCardNo.trim()
    const { data } = await api.post('/platform/fixes/occupants', body)
    if (data.code === 0) {
      const mobileChanged = mobile !== (f.originalMobile || '').trim()
      ElMessage.success(
        mobileChanged ? '已更新。手机号已变更，对方须用新号重新微信授权登录小程序' : '已更新',
      )
      occEditVisible.value = false
      loadOccupants()
    } else ElMessage.error(data.message)
  } finally {
    occEditSaving.value = false
  }
}

async function deactivateOcc(row: any) {
  try {
    await ElMessageBox.confirm(
      `确定解除「${row.realName || row.mobile}」在 ${row.communityName || ''} ${row.roomNo || ''} 的住户绑定？` +
        (row.residentRole === 'OWNER' ? '（若其在本小区已无其他业主身份，将同时解除业委会职务）' : ''),
      '解除绑定',
      { type: 'warning', confirmButtonText: '解除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  const { data } = await api.post('/platform/fixes/occupants', {
    action: 'DEACTIVATE',
    communityId: row.communityId,
    roomId: row.roomId,
    occupantId: row.id,
  })
  if (data.code === 0) {
    ElMessage.success('已解除绑定')
    loadOccupants()
  } else ElMessage.error(data.message)
}

async function batchDeactivateOcc() {
  const rows = occSelection.value
  if (!rows.length) {
    ElMessage.warning('请先勾选要解除的住户')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确定批量解除已选 ${rows.length} 条住户绑定？解除后对方不再作为该房住户。`,
      '批量解除绑定',
      { type: 'warning', confirmButtonText: '全部解除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  occBatchDeleting.value = true
  try {
    const { data } = await api.post('/platform/occupants/batch-deactivate', {
      occupantIds: rows.map((r) => r.id),
    })
    if (data.code === 0) {
      const succ = data.data?.successCount ?? 0
      const fail = data.data?.failCount ?? 0
      if (fail > 0) {
        ElMessage.warning(`已解除 ${succ} 条，失败 ${fail} 条`)
      } else {
        ElMessage.success(`已解除 ${succ} 条绑定`)
      }
      loadOccupants()
    } else ElMessage.error(data.message)
  } finally {
    occBatchDeleting.value = false
  }
}

function onTabChange(name: string | number) {
  const tab = String(name)
  activeTab.value = tab
  if (tab === 'communities') {
    load()
    loadApps()
  } else if (tab === 'staff') {
    if (!list.value.length) load()
    loadUsers()
  } else if (tab === 'occupants') {
    if (!list.value.length) load()
    if (!occLoadedOnce.value) loadOccupants()
  } else if (tab === 'about') {
    loadAbout()
  }
}

function refreshCurrent() {
  if (activeTab.value === 'communities') {
    load()
    loadApps()
  } else if (activeTab.value === 'staff') {
    loadUsers()
  } else if (activeTab.value === 'about') {
    loadAbout()
  } else {
    loadOccupants()
  }
}

function revokeAboutBlobs() {
  for (const u of aboutObjectUrls.value) URL.revokeObjectURL(u)
  aboutObjectUrls.value = []
  aboutSrcCache.value = {}
  aboutBlobToId.value = new Map()
}

async function loadAboutBlobUrl(id: number): Promise<string> {
  if (aboutSrcCache.value[id]) return aboutSrcCache.value[id]
  const res = await api.get(`/attachments/${id}`, { responseType: 'blob' })
  const url = URL.createObjectURL(res.data)
  aboutObjectUrls.value.push(url)
  aboutSrcCache.value = { ...aboutSrcCache.value, [id]: url }
  aboutBlobToId.value.set(url, id)
  return url
}

async function loadAbout() {
  aboutLoading.value = true
  try {
    const { data } = await api.get('/platform/about-us')
    if (data.code !== 0) {
      ElMessage.error(data.message || '加载失败')
      return
    }
    revokeAboutBlobs()
    let html = legacyToHtml(data.data?.content || '')
    const ids = extractAttachmentIds(html)
    await Promise.all(ids.map((id) => loadAboutBlobUrl(id).catch(() => '')))
    aboutContent.value = rewriteAttachmentUrls(html, (id) => aboutSrcCache.value[id] || '')
    aboutLoaded.value = true
  } finally {
    aboutLoading.value = false
  }
}

async function saveAbout() {
  const storageHtml = aboutEditorRef.value?.getStorageHtml?.() || aboutContent.value
  aboutSaving.value = true
  try {
    const attachmentIds = extractAttachmentIds(storageHtml)
    const { data } = await api.put('/platform/about-us', {
      title: '关于我们',
      content: storageHtml,
      attachmentIds,
    })
    if (data.code === 0) {
      ElMessage.success('已保存')
      await loadAbout()
    } else ElMessage.error(data.message || '保存失败')
  } finally {
    aboutSaving.value = false
  }
}

onBeforeUnmount(() => {
  revokeAboutBlobs()
})

async function enablePersonInEdit() {
  const f = personForm.value
  const { data } = await api.post(`/platform/users/${f.id}/enable`)
  if (data.code === 0) {
    ElMessage.success('已启用')
    f.status = 1
    loadUsers()
  } else ElMessage.error(data.message)
}

async function deletePerson(row: any) {
  if (row.isPlatformAdmin === 1) {
    ElMessage.warning('不可删除平台管理员')
    return
  }
  if (row.status === 1) {
    try {
      await ElMessageBox.confirm(
        `停用「${row.realName || row.mobile}」？停用后不可登录，并解除有效任职与住户绑定。若要永久删除，请停用后再点删除。`,
        '删除人员',
        { confirmButtonText: '停用', cancelButtonText: '取消', type: 'warning' },
      )
    } catch {
      return
    }
    const { data } = await api.delete(`/platform/users/${row.id}`)
    if (data.code === 0) {
      ElMessage.success('已停用')
      loadUsers()
    } else ElMessage.error(data.message)
    return
  }
  try {
    await ElMessageBox.confirm(
      `永久删除「${row.realName || row.mobile}」？将删除用户及任职/住户/微信绑定，不可恢复。`,
      '永久删除',
      { confirmButtonText: '永久删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  const { data } = await api.delete(`/platform/users/${row.id}`, { params: { hard: true } })
  if (data.code === 0) {
    ElMessage.success('已删除')
    loadUsers()
  } else ElMessage.error(data.message)
}

function logout() {
  localStorage.removeItem('token')
  localStorage.removeItem('adminRole')
  localStorage.removeItem('communityName')
  localStorage.removeItem('communityId')
  router.push('/login')
}

onMounted(() => {
  ensureRegions()
  load()
  loadApps()
})
</script>

<template>
  <div class="page">
    <header>
      <div>
        <h2>平台管理</h2>
        <p class="sub">小区 · 物业人员 · 全体住户 · 关于我们</p>
      </div>
      <div class="row">
        <el-button @click="refreshCurrent">刷新</el-button>
        <el-button @click="router.push('/staff-login')">物业登录</el-button>
        <el-button @click="logout">退出</el-button>
      </div>
    </header>

    <el-tabs :model-value="activeTab" class="tabs" @tab-change="onTabChange">
      <el-tab-pane label="小区" name="communities">
        <section class="setup first">
          <div class="sec-head">
            <h3>小区列表 · 进入详情</h3>
            <div class="row">
              <el-input v-model="name" placeholder="小区名称（直开）" style="width:220px" />
              <el-button type="primary" @click="createOne">新建小区</el-button>
            </div>
          </div>
          <div class="row wrap" style="margin-bottom:10px">
            <el-cascader
              v-model="searchCodes"
              :options="regionOptions"
              :props="{ checkStrictly: true }"
              placeholder="按省 / 市 / 区"
              clearable
              filterable
              style="width:280px"
            />
            <el-input
              v-model="searchName"
              placeholder="按小区名称"
              clearable
              style="width:200px"
              @keyup.enter="load"
            />
            <el-button type="primary" @click="load">搜索</el-button>
            <el-button @click="resetSearch">重置</el-button>
          </div>
          <el-table :data="list" v-loading="loading" style="width:100%">
            <el-table-column type="index" label="序号" width="60" :index="(i: number) => i + 1" />
            <el-table-column prop="name" label="名称" min-width="140" />
            <el-table-column prop="address" label="地址" min-width="160" />
            <el-table-column label="操作" width="230" fixed="right">
              <template #default="{ row }">
                <el-button type="primary" link @click="enterCommunity(row)">进入小区</el-button>
                <el-button type="primary" link @click="openAddress(row)">修改</el-button>
                <el-button type="danger" link @click="deleteCommunity(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <p class="hint">进入后可配置空间、住户、账单等（与物业后台相同），左上角可返回本页。</p>
        </section>

        <section class="setup">
          <div class="sec-head">
            <h3>建小区申请（平台审核）</h3>
            <div class="row">
              <el-select v-model="appStatus" style="width:120px" @change="loadApps">
                <el-option label="待审核" value="PENDING" />
                <el-option label="已通过" value="APPROVED" />
                <el-option label="已拒绝" value="REJECTED" />
                <el-option label="全部" value="" />
              </el-select>
              <el-button @click="loadApps">刷新申请</el-button>
            </div>
          </div>
          <el-table :data="apps" v-loading="appsLoading" style="width:100%">
            <el-table-column type="index" label="序号" width="60" :index="(i: number) => i + 1" />
            <el-table-column prop="communityName" label="小区" min-width="120" />
            <el-table-column label="地区" min-width="160">
              <template #default="{ row }">
                {{ row.provinceName }}{{ row.cityName }}{{ row.districtName }}
              </template>
            </el-table-column>
            <el-table-column prop="applicantName" label="申请人" width="90" />
            <el-table-column label="角色" width="80">
              <template #default="{ row }">{{ roleLabel(row.applicantRole) }}</template>
            </el-table-column>
            <el-table-column prop="applicantMobile" label="电话" width="120" />
            <el-table-column label="房屋初始化" min-width="140">
              <template #default="{ row }">{{ templateLabel(row.templateCode) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">{{ statusLabel(row.status) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="160" fixed="right">
              <template #default="{ row }">
                <template v-if="row.status === 'PENDING'">
                  <el-button link type="primary" @click="approveApp(row)">通过</el-button>
                  <el-button link type="danger" @click="rejectApp(row)">拒绝</el-button>
                </template>
                <span v-else-if="row.communityId">小区 #{{ row.communityId }}</span>
                <span v-else>{{ row.rejectReason || '—' }}</span>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </el-tab-pane>

      <el-tab-pane label="物业人员" name="staff">
        <section class="setup first">
          <div class="sec-head">
            <h3>物业人员</h3>
            <el-button type="primary" @click="openPersonAdd">新增</el-button>
          </div>
          <div class="row wrap" style="margin-bottom:10px">
            <el-input
              v-model="userNameQ"
              placeholder="人员名称"
              clearable
              style="width:140px"
              @keyup.enter="userPage=1;loadUsers()"
            />
            <el-input
              v-model="userMobileQ"
              placeholder="联系方式"
              clearable
              style="width:140px"
              @keyup.enter="userPage=1;loadUsers()"
            />
            <el-select
              v-model="userCommunityQ"
              placeholder="归属社区"
              clearable
              style="width:180px"
              @change="userPage=1;loadUsers()"
            >
              <el-option v-for="c in list" :key="c.id" :label="c.name" :value="c.id" />
            </el-select>
              <el-button type="primary" @click="userPage=1;loadUsers()">查询</el-button>
              <el-checkbox
                v-model="userManagerOnly"
                @change="userPage=1;loadUsers()"
                style="margin-left: 10px"
                border
              >
                只看物业经理
              </el-checkbox>
          </div>
          <el-table :data="users" v-loading="usersLoading" style="width:100%">
            <el-table-column label="人员名称" min-width="120">
              <template #default="{ row }">{{ row.realName || '—' }}</template>
            </el-table-column>
            <el-table-column prop="mobile" label="联系方式" width="130" />
            <el-table-column prop="communityText" label="归属社区" min-width="180" />
            <el-table-column prop="roleText" label="岗位" min-width="220" />
            <el-table-column label="Web密码" width="90">
              <template #default="{ row }">
                <span :class="personPasswordClass(row)">{{ personPasswordText(row) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="220" fixed="right">
              <template #default="{ row }">
                <template v-if="row.isPlatformAdmin !== 1">
                  <el-button link type="primary" @click="openPersonEdit(row)">修改</el-button>
                  <el-button
                    v-if="hasWebRoleUser(row)"
                    link
                    type="primary"
                    @click="resetPersonPassword(row)"
                  >
                    重置密码
                  </el-button>
                  <el-button link type="danger" @click="deletePerson(row)">删除</el-button>
                </template>
                <span v-else class="hint">—</span>
              </template>
            </el-table-column>
          </el-table>
          <div class="row" style="margin-top:10px;justify-content:flex-end">
            <el-pagination
              background
              layout="prev, pager, next, total"
              :total="userTotal"
              :page-size="20"
              v-model:current-page="userPage"
              @current-change="loadUsers"
            />
          </div>
          <p class="hint">本表列有物业任职的账号。平台仅任命物业经理；一线岗位（客服/保安/保洁/绿化/机电）在进入小区后的「岗位人员」配置。业主与业委会请在小区「住户」栏查看。转为物业须设临时密码，首次登录后须改密。物业经理与客服可在此直接【重置密码】，重置后对方变回「须改密」。</p>
        </section>
      </el-tab-pane>

      <el-tab-pane label="全体住户" name="occupants">
        <section class="setup first">
          <div class="sec-head">
            <h3>全体住户</h3>
            <div class="row">
              <el-button
                type="danger"
                plain
                :disabled="!occSelection.length"
                :loading="occBatchDeleting"
                @click="batchDeactivateOcc"
              >
                批量解除{{ occSelection.length ? `（${occSelection.length}）` : '' }}
              </el-button>
            </div>
          </div>
          <div class="row wrap" style="margin-bottom:10px">
            <el-select
              v-model="occCommunityQ"
              placeholder="全部小区"
              clearable
              filterable
              style="width:180px"
              @change="occPage=1;loadOccupants()"
            >
              <el-option v-for="c in list" :key="c.id" :label="c.name" :value="c.id" />
            </el-select>
            <el-input
              v-model="occRealNameQ"
              placeholder="姓名"
              clearable
              style="width:120px"
              @keyup.enter="occPage=1;loadOccupants()"
            />
            <el-input
              v-model="occMobileQ"
              placeholder="手机号"
              clearable
              style="width:140px"
              @keyup.enter="occPage=1;loadOccupants()"
            />
            <el-input
              v-model="occIdCardQ"
              placeholder="身份证号"
              clearable
              style="width:180px"
              @keyup.enter="occPage=1;loadOccupants()"
            />
            <el-input
              v-model="occRoomNoQ"
              placeholder="房号"
              clearable
              style="width:120px"
              @keyup.enter="occPage=1;loadOccupants()"
            />
            <el-button type="primary" @click="occPage=1;loadOccupants()">查询</el-button>
            <el-button @click="resetOccupantSearch">重置</el-button>
          </div>
          <el-table
            :data="occupants"
            v-loading="occupantsLoading"
            style="width:100%"
            @selection-change="onOccSelectionChange"
          >
            <el-table-column type="selection" width="48" />
            <el-table-column type="index" label="序号" width="60" :index="(i: number) => (occPage - 1) * 20 + i + 1" />
            <el-table-column prop="communityName" label="小区" min-width="120" />
            <el-table-column prop="buildingName" label="楼栋" min-width="100" />
            <el-table-column prop="unitName" label="单元" width="100" />
            <el-table-column prop="floorName" label="楼层" width="80" />
            <el-table-column prop="roomNo" label="房号" width="80" />
            <el-table-column prop="realName" label="姓名" width="100" />
            <el-table-column prop="mobile" label="手机" width="120" />
            <el-table-column label="角色" width="100">
              <template #default="{ row }">{{ residentRoleLabel(row.residentRole) }}</template>
            </el-table-column>
            <el-table-column prop="idCardNo" label="身份证" min-width="170" />
            <el-table-column label="操作" width="140" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openOccEdit(row)">编辑</el-button>
                <el-button link type="danger" @click="deactivateOcc(row)">解除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="row" style="margin-top:10px;justify-content:flex-end">
            <el-pagination
              background
              layout="prev, pager, next, total"
              :total="occTotal"
              :page-size="20"
              v-model:current-page="occPage"
              @current-change="loadOccupants"
            />
          </div>
          <p class="hint">可按小区/姓名/手机/身份证/房号查询有效住户绑定。编辑可改姓名、手机、角色与身份证；解除绑定后对方不再作为该房住户（账号保留）。</p>
        </section>
      </el-tab-pane>

      <el-tab-pane label="关于我们" name="about">
        <section class="setup first" v-loading="aboutLoading">
          <div class="sec-head">
            <h3>关于我们</h3>
            <el-button type="primary" :loading="aboutSaving" @click="saveAbout">保存更新</el-button>
          </div>
          <el-form label-position="top" style="max-width:960px">
            <el-form-item label="正文">
              <NoticeRichEditor
                v-if="aboutLoaded || !aboutLoading"
                ref="aboutEditorRef"
                v-model="aboutContent"
                :blob-to-id="aboutBlobToId"
                biz-type="ABOUT"
                :biz-id="1"
              />
            </el-form-item>
          </el-form>
          <p class="hint">平台统一维护的单页介绍，小程序「我的 → 关于我们」只展示正文（不含标题与更新时间）。编辑能力与公告一致（字体、图片、附件）。</p>
        </section>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="occEditVisible" title="编辑住户绑定" width="480px">
      <el-form label-position="top">
        <el-form-item label="小区 / 房屋">
          <el-input :model-value="`${occEditForm.communityName} · ${occEditForm.roomLabel}`" disabled />
        </el-form-item>
        <el-form-item label="姓名">
          <el-input v-model="occEditForm.realName" placeholder="姓名" maxlength="32" />
        </el-form-item>
        <el-form-item label="手机号" required>
          <el-input v-model="occEditForm.mobile" placeholder="11 位手机号" maxlength="11" />
        </el-form-item>
        <el-form-item label="角色" required>
          <el-select v-model="occEditForm.residentRole" style="width:100%">
            <el-option
              v-for="o in RESIDENT_ROLE_OPTIONS"
              :key="o.value"
              :label="o.label"
              :value="o.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="occEditForm.residentRole === 'OWNER' ? '身份证（业主必填）' : '身份证'">
          <el-input v-model="occEditForm.idCardNo" placeholder="身份证号" maxlength="18" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="occEditVisible = false">取消</el-button>
        <el-button type="primary" :loading="occEditSaving" @click="saveOccEdit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="addrVisible" :title="addrRow ? `修改地址 · ${addrRow.name}` : '修改地址'" width="480px">
      <el-form label-position="top">
        <el-form-item label="省 / 市 / 区" required>
          <el-cascader
            v-model="addrCodes"
            :options="regionOptions"
            placeholder="请选择省市区"
            style="width: 100%"
            filterable
            clearable
          />
        </el-form-item>
        <el-form-item label="具体地址" required>
          <el-input v-model="addrDetail" placeholder="街道、门牌号等" maxlength="120" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addrVisible = false">取消</el-button>
        <el-button type="primary" :loading="addrSaving" @click="saveAddress">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="personAddVisible" title="新增人员" width="560px">
      <el-form label-position="top">
        <el-form-item label="人员名称">
          <el-input v-model="personForm.realName" placeholder="姓名" maxlength="32" />
        </el-form-item>
        <el-form-item label="联系方式" required>
          <el-input v-model="personForm.mobile" placeholder="11 位手机号" maxlength="11" />
        </el-form-item>
        <el-form-item label="初始临时密码" required>
          <el-input v-model="personForm.password" type="password" show-password placeholder="至少 6 位，首次登录须修改" />
        </el-form-item>
        <el-form-item label="社区岗位（可多条）">
          <div v-for="(b, idx) in personForm.bindings" :key="idx" class="bind-row">
            <el-select v-model="b.communityId" placeholder="社区" style="flex:1.2">
              <el-option v-for="c in list" :key="c.id" :label="c.name" :value="c.id" />
            </el-select>
            <el-select v-model="b.roleKey" placeholder="岗位" style="flex:1">
              <el-option v-for="o in positionOptions" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
            <el-button link type="danger" @click="removeBinding(idx)">删除</el-button>
          </div>
          <el-button type="primary" link @click="addBinding">+ 添加任职社区</el-button>
          <p class="hint">平台仅任命各小区物业经理（每小区一人）。客服/保安等一线岗请在进入小区后，于「岗位人员」中配置。业委会不在此配置。</p>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="personAddVisible = false">取消</el-button>
        <el-button type="primary" :loading="personSaving" @click="savePersonAdd">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="personEditVisible" title="修改人员" width="560px">
      <el-form label-position="top">
        <el-form-item label="人员名称">
          <el-input v-model="personForm.realName" placeholder="姓名" maxlength="32" />
        </el-form-item>
        <el-form-item label="联系方式" required>
          <el-input v-model="personForm.mobile" placeholder="11 位手机号" maxlength="11" />
          <p class="hint">更改手机号后，对方须用新号重新微信授权，才能再进小程序。</p>
        </el-form-item>
        <el-form-item
          :label="
            !personForm.hasPassword
              ? '临时密码（转为物业时必填）'
              : personForm.mustChangePassword
                ? '重置临时密码（当前须改密）'
                : '重置临时密码（不填则不改）'
          "
        >
          <el-input
            v-model="personForm.password"
            type="password"
            show-password
            :placeholder="`默认 ${DEFAULT_PASSWORD}，至少 6 位`"
          />
          <p class="hint" style="margin: 4px 0 0">
            默认密码 {{ DEFAULT_PASSWORD }}
            <el-button link type="primary" @click="fillDefaultPassword">填入</el-button>
            ；设置后对方首次登录须修改密码。
          </p>
        </el-form-item>
        <el-form-item label="社区岗位（可多条）">
          <div v-for="(b, idx) in personForm.bindings" :key="idx" class="bind-row">
            <el-select
              v-model="b.communityId"
              placeholder="社区"
              :disabled="personForm.status !== 1"
              style="flex:1.2"
            >
              <el-option v-for="c in list" :key="c.id" :label="c.name" :value="c.id" />
            </el-select>
            <el-select
              v-model="b.roleKey"
              placeholder="岗位"
              :disabled="personForm.status !== 1"
              style="flex:1"
            >
              <el-option v-for="o in positionOptions" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
            <el-button link type="danger" :disabled="personForm.status !== 1" @click="removeBinding(idx)">删除</el-button>
          </div>
          <el-button type="primary" link :disabled="personForm.status !== 1" @click="addBinding">+ 添加任职社区</el-button>
          <p class="hint">每行表示该人担任该小区物业经理。删掉并保存即解除经理岗（一线岗不受影响）。住户绑定不在此改。</p>
        </el-form-item>
        <p v-if="personForm.status === 0" class="hint">账号已停用：可先启用，再改岗位；永久删除请在列表点删除。</p>
      </el-form>
      <template #footer>
        <el-button v-if="personForm.status === 0" type="success" @click="enablePersonInEdit">启用</el-button>
        <el-button @click="personEditVisible = false">取消</el-button>
        <el-button type="primary" :loading="personSaving" @click="savePersonEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding: 24px 32px; max-width: 1500px; margin: 0 auto; }
header { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
.row { display: flex; gap: 8px; align-items: center; }
.wrap { flex-wrap: wrap; }
h2 { margin: 0; color: #1f4e3d; }
.sub { margin: 4px 0 0; color: #667; font-size: 14px; }
h3 { margin: 0 0 12px; color: #2a4a3c; font-size: 16px; }
.sec-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; gap: 12px; flex-wrap: wrap; }
.tabs { margin-top: 12px; }
.setup {
  margin-top: 16px;
  padding: 16px 18px;
  background: #fff;
  border: 1px solid #dfe6e1;
}
.setup.first { margin-top: 8px; }
.hint { margin: 10px 0 0; color: #778; font-size: 13px; }
.bind-row { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; width: 100%; }
.pwd-todo { color: #b8791f; font-weight: 600; }
.pwd-none { color: #a8442f; font-weight: 600; }
.pwd-na { color: #aab; }
</style>
