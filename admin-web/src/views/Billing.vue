<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { authFailRedirectPath } from '../authSession'
import { formatMoney } from '../money'
import StaffNav from '../components/StaffNav.vue'
import StaffSessionAction from '../components/StaffSessionAction.vue'

const TAB_KEYS = ['bills', 'payment'] as const
type TabKey = (typeof TAB_KEYS)[number]

const route = useRoute()
const router = useRouter()

const activeTab = computed<TabKey>(() => {
  const t = route.query.tab
  if (typeof t === 'string' && (TAB_KEYS as readonly string[]).includes(t)) return t as TabKey
  return 'bills'
})

function onTabChange(name: string | number) {
  const tab = String(name)
  if (tab === activeTab.value) return
  router.replace({ path: '/billing', query: tab === 'bills' ? {} : { tab } })
}

const loading = ref(false)
const billMonth = ref(new Date().toISOString().slice(0, 7))
const bills = ref<any[]>([])
const feeItems = ref<any[]>([])
const meterUploads = ref<any[]>([])
const genResult = ref<any>(null)
const chargePreview = ref<{ summary: any; rooms: any[] } | null>(null)
const previewFilter = ref<'ALL' | 'READY' | 'WILL_FAIL' | 'MISMATCH'>('ALL')
const priceRules = ref<Record<number, any[]>>({})
const ruleDraft = ref<Record<number, {
  mgmt?: string
  monthly?: string
  discount?: string
  useArea?: boolean
  useHouseTypePrice?: boolean
}>>({})

const addVisible = ref(false)
const addSaving = ref(false)

interface FlatRoom {
  id: number
  label: string
}
const spaceTreeLoaded = ref(false)
const prepaidRoomOptions = ref<FlatRoom[]>([])
const prepaidRoomId = ref<number | null>(null)
/** 起止月份，值格式 YYYY-MM */
const prepaidMonthFrom = ref(new Date().toISOString().slice(0, 7))
const prepaidMonthTo = ref(new Date().toISOString().slice(0, 7))
const prepaidCats = ref(['PROPERTY_FEE'])
const prepaidCash = ref(500)
const prepaidListAmount = ref<number | null>(null)
const prepaidPlans = ref<any[]>([])

/** 收款配置（本页配齐商户号+密钥即可开通 App 缴费） */
const payCfg = ref({
  wechatEnabled: false,
  alipayEnabled: false,
  wechatSubMchId: '',
  alipaySmid: '',
  wechatAppId: '',
  wechatApiV3Key: '',
  wechatMchSerialNo: '',
  wechatPrivateKeyPem: '',
  alipayPrivateKey: '',
  alipayPublicKey: '',
  wechatApiV3KeyConfigured: false,
  wechatMchSerialConfigured: false,
  wechatPrivateKeyConfigured: false,
  alipayPrivateKeyConfigured: false,
  alipayPublicKeyConfigured: false,
  wechatChannelReady: false,
  alipayChannelReady: false,
  onboardingRemark: '',
  merchantPayEnabled: false,
  vendor: 'OFFICIAL',
  payMock: true,
})
const payCfgSaving = ref(false)

const onlineConfigReady = computed(() => !!payCfg.value.wechatChannelReady || !!payCfg.value.alipayChannelReady)
const payStatusTitle = computed(() => {
  if (onlineConfigReady.value) {
    return payCfg.value.payMock
      ? '收款已配齐：住户可在 App 选微信/支付宝缴费（当前为模拟到账，账单会自动变已缴）'
      : '收款已配齐：住户可在 App 选微信/支付宝缴费，成功后自动记已缴'
  }
  return '尚未开通：请打开通道并填齐商户号与密钥后保存；未开通时仍可在账单里手动「确认收款」'
})

function expandMonths(from: string, to: string): string[] {
  if (!/^\d{4}-\d{2}$/.test(from) || !/^\d{4}-\d{2}$/.test(to)) return []
  let [fy, fm] = from.split('-').map(Number)
  const [ty, tm] = to.split('-').map(Number)
  if (fy * 12 + fm > ty * 12 + tm) return []
  const out: string[] = []
  while (fy < ty || (fy === ty && fm <= tm)) {
    out.push(`${fy}-${String(fm).padStart(2, '0')}`)
    fm += 1
    if (fm > 12) {
      fm = 1
      fy += 1
    }
    if (out.length > 120) break
  }
  return out
}

const prepaidMonthsList = computed(() => expandMonths(prepaidMonthFrom.value, prepaidMonthTo.value))

const prepaidRoomLabelMap = computed(() => {
  const m: Record<number, string> = {}
  for (const r of prepaidRoomOptions.value) m[r.id] = r.label
  return m
})

function roomLabelOf(roomId: number | null | undefined) {
  if (roomId == null) return '—'
  return prepaidRoomLabelMap.value[roomId] || `房屋#${roomId}`
}

async function ensureSpaceTree() {
  if (spaceTreeLoaded.value) return
  try {
    const { data } = await api.get('/staff/space-tree')
    if (data.code !== 0) return
    const buildings = data.data?.buildings || []
    const out: FlatRoom[] = []
    for (const b of buildings) {
      for (const u of b.units || []) {
        for (const f of u.floors || []) {
          for (const r of f.rooms || []) {
            out.push({
              id: r.id,
              label: [b.name, u.name, f.name, r.roomNo].filter(Boolean).join('-'),
            })
          }
        }
      }
    }
    prepaidRoomOptions.value = out
    spaceTreeLoaded.value = true
  } catch {
    /* ignore */
  }
}

const exemptions = ref<any[]>([])
const exForm = ref({
  roomId: null as number | null,
  feeCategory: 'PROPERTY_FEE',
  effectiveFrom: new Date().toISOString().slice(0, 7),
  effectiveTo: '' as string,
  reason: '',
})
const addForm = ref({
  name: '',
  feeCategory: 'GARBAGE',
  billingMode: 'FIXED' as 'FIXED' | 'FORMULA' | 'IMPORT',
  monthlyAmount: '',
  mgmt: '',
  discountRate: '',
  useArea: true,
  useHouseTypePrice: true,
})

const feeCategoryOptions = [
  { value: 'PROPERTY_FEE', label: '物业管理费', formula: true },
  { value: 'PARKING_MGMT', label: '车位管理费', formula: true },
  { value: 'PARKING_MONTHLY', label: '车辆月保费', formula: true },
  { value: 'SHARED', label: '公摊费', formula: false },
  { value: 'GARBAGE', label: '垃圾费', formula: false },
  { value: 'WATER', label: '代收水费', formula: false },
  { value: 'ELECTRIC', label: '代收电费', formula: false },
  { value: 'GAS', label: '代收煤气费', formula: false },
  { value: 'OTHER', label: '其他（可多条二级名）', formula: false },
]

const billingModeOptionsAll = [
  { value: 'FIXED', label: '固定价格' },
  { value: 'FORMULA', label: '公式算费' },
  { value: 'IMPORT', label: '表格导入' },
]

function allowedBillingModes(cat: string) {
  const hit = feeCategoryOptions.find((o) => o.value === cat)
  if (hit?.formula) return ['FIXED', 'FORMULA', 'IMPORT'] as const
  return ['FIXED', 'IMPORT'] as const
}

function categoryDefaultName(cat: string) {
  const lab = feeCategoryOptions.find((o) => o.value === cat)?.label || cat
  return lab.replace(/（.*）/, '')
}

function isImportCat(cat: string) {
  return ['SHARED', 'WATER', 'ELECTRIC', 'GAS'].includes(cat)
}

/** 兼容：传费项行对象或 category 字符串 */
function billingModeOf(rowOrCat: any): 'FIXED' | 'FORMULA' | 'IMPORT' {
  if (rowOrCat && typeof rowOrCat === 'object') {
    try {
      const m = JSON.parse(rowOrCat.remark || '{}')
      const b = String(m.billingMode || '').toUpperCase()
      if (b === 'FIXED' || b === 'FORMULA' || b === 'IMPORT') return b
    } catch { /* ignore */ }
    return billingModeOf(rowOrCat.feeCategory)
  }
  const cat = String(rowOrCat || '')
  if (cat === 'OTHER' || cat === 'GARBAGE') return 'FIXED'
  if (isImportCat(cat)) return 'IMPORT'
  return 'FORMULA'
}

const billStatusLabel: Record<string, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发放',
  PAID: '已缴费',
  VOID: '已作废',
  REFUNDED: '已退费',
}

const prepaidStatusLabel: Record<string, string> = {
  DRAFT: '草稿',
  ACTIVE: '生效',
  VOID: '已作废',
}

function onBillMonthChange() {
  previewFilter.value = 'ALL'
  load()
}

const previewSummary = computed(() => chargePreview.value?.summary || null)

const previewRooms = computed(() => {
  const list = chargePreview.value?.rooms || []
  if (previewFilter.value === 'READY') return list.filter((r) => r.status === 'READY')
  if (previewFilter.value === 'WILL_FAIL') return list.filter((r) => r.status === 'WILL_FAIL')
  if (previewFilter.value === 'MISMATCH') return list.filter((r) => r.diffWithGenerated)
  return list
})

function setPreviewFilter(f: 'ALL' | 'READY' | 'WILL_FAIL' | 'MISMATCH') {
  previewFilter.value = f
}

function previewRowClassName({ row }: { row: any }) {
  return row?.diffWithGenerated ? 'preview-row-mismatch' : ''
}

const FAIL_ROOMS_KEY = 'billingChargeFailRoomIds'

function goSpace(roomId?: number | null) {
  const failIds = (chargePreview.value?.rooms || [])
    .filter((r) => r.status === 'WILL_FAIL' && r.roomId != null)
    .map((r) => Number(r.roomId))
  try {
    sessionStorage.setItem(FAIL_ROOMS_KEY, JSON.stringify(failIds))
  } catch {
    /* ignore */
  }
  const q: Record<string, string> = { from: 'billing' }
  const target =
    roomId != null ? Number(roomId) : failIds.length ? failIds[0] : null
  if (target != null) {
    q.roomId = String(target)
    router.push({ path: '/space', query: q })
    return
  }
  router.push({ path: '/space', query: q })
}

function parsePropertyRemark(remark: string | null | undefined): {
  discountRate: number
  useArea: boolean
  useHouseTypePrice: boolean
} {
  try {
    const o = remark ? JSON.parse(remark) : {}
    const d = Number(o.discountRate)
    let useArea = o.useArea !== false
    let useHouseTypePrice = o.useHouseTypePrice !== false
    if (o.areaSource === 'NONE') useArea = false
    if (o.unitPriceSource === 'NONE') useHouseTypePrice = false
    return {
      discountRate: Number.isFinite(d) && d >= 0 ? d : 1,
      useArea,
      useHouseTypePrice,
    }
  } catch {
    return { discountRate: 1, useArea: true, useHouseTypePrice: true }
  }
}

function buildPropertyRemark(discountRate: number, useArea: boolean, useHouseTypePrice: boolean) {
  return JSON.stringify({
    billingMode: 'FORMULA',
    useArea,
    useHouseTypePrice,
    discountRate,
  })
}

function onFeeCategoryChange() {
  const cat = addForm.value.feeCategory
  const allowed = allowedBillingModes(cat)
  if (!(allowed as readonly string[]).includes(addForm.value.billingMode)) {
    addForm.value.billingMode = allowed[0]
  }
  if (cat !== 'OTHER') {
    addForm.value.name = categoryDefaultName(cat)
  } else {
    addForm.value.name = ''
  }
}

function onBillingModeChange() {
  /* fields driven by template */
}

function ensureRuleDraft(id: number) {
  if (!ruleDraft.value[id]) {
    ruleDraft.value[id] = { useArea: true, useHouseTypePrice: true }
  }
  return ruleDraft.value[id]
}

function parseAmount(s: string | undefined) {
  if (s == null || String(s).trim() === '') return null
  const n = Number(String(s).trim())
  return Number.isFinite(n) ? n : null
}

const enabledImportItems = computed(() =>
  feeItems.value.filter((f) => f.status === 1 && billingModeOf(f) === 'IMPORT'),
)

const importReady = computed(() => {
  if (!enabledImportItems.value.length) return true
  const cats = new Set(meterUploads.value.map((m) => m.feeCategory))
  return enabledImportItems.value.every((f) => cats.has(f.feeCategory))
})

const missingImportNames = computed(() => {
  const cats = new Set(meterUploads.value.map((m) => m.feeCategory))
  return enabledImportItems.value
    .filter((f) => !cats.has(f.feeCategory))
    .map((f) => f.name)
})

async function ensureStaff() {
  const { data } = await api.get('/staff/community')
  if (data.code !== 0) {
    ElMessage.error(data.message || '请先物业登录')
    router.push(authFailRedirectPath())
    return false
  }
  return true
}

async function loadRules(itemId: number) {
  const { data } = await api.get(`/staff/fee-items/${itemId}/price-rules`)
  if (data.code === 0) {
    const rules = data.data || []
    priceRules.value[itemId] = rules
    const item = feeItems.value.find((x) => x.id === itemId)
    const draft: {
      mgmt?: string
      monthly?: string
      discount?: string
      useArea?: boolean
      useHouseTypePrice?: boolean
    } = {
      ...ruleDraft.value[itemId],
    }
    for (const r of rules) {
      if (item?.feeCategory === 'PARKING_MGMT' && (r.matchKey === 'DEFAULT' || r.matchKey === 'OWNED')) {
        if (r.matchKey === 'DEFAULT' || draft.mgmt == null) draft.mgmt = String(r.unitPrice)
      }
      if (item?.feeCategory === 'PARKING_MONTHLY' && r.matchKey === 'DEFAULT') {
        draft.monthly = String(r.unitPrice)
      }
    }
    ruleDraft.value[itemId] = draft
  }
}

async function load() {
  if (activeTab.value === 'payment') {
    loading.value = true
    try {
      if (!(await ensureStaff())) return
      await loadPayConfig()
    } finally {
      loading.value = false
    }
    return
  }
  loading.value = true
  try {
    if (!(await ensureStaff())) return
    const [f, b, m, p] = await Promise.all([
      api.get('/staff/fee-items'),
      api.get('/staff/bills', { params: { billMonth: billMonth.value, page: 1, pageSize: 100 } }),
      api.get('/staff/bill-meter-uploads', { params: { billMonth: billMonth.value } }),
      api.get('/staff/bills/charge-preview', { params: { billMonth: billMonth.value } }),
    ])
    if (f.data.code === 0) {
      feeItems.value = f.data.data || []
      await Promise.all(feeItems.value.map((it) => loadRules(it.id)))
      for (const it of feeItems.value) {
        if (billingModeOf(it) === 'FIXED' && it.monthlyAmount != null) {
          ensureRuleDraft(it.id).monthly = String(it.monthlyAmount)
        }
        if (it.feeCategory === 'PROPERTY_FEE') {
          const cfg = parsePropertyRemark(it.remark)
          const d = ensureRuleDraft(it.id)
          d.discount = String(cfg.discountRate)
          d.useArea = cfg.useArea
          d.useHouseTypePrice = cfg.useHouseTypePrice
        }
      }
    }
    if (b.data.code === 0) bills.value = b.data.data?.list || b.data.data || []
    if (m.data.code === 0) meterUploads.value = m.data.data || []
    if (p.data.code === 0) chargePreview.value = p.data.data
    else {
      chargePreview.value = null
      if (p.data.message) ElMessage.error(p.data.message)
    }
    try {
      const ex = await api.get('/staff/room-fee-exemptions')
      if (ex.data.code === 0) exemptions.value = ex.data.data || []
    } catch {
      exemptions.value = []
    }
    try {
      await ensureSpaceTree()
      await loadPrepaid()
    } catch {
      /* ignore */
    }
  } finally {
    loading.value = false
  }
}

async function loadPayConfig() {
  try {
    const { data } = await api.get('/staff/payment-config')
    if (data.code !== 0 || !data.data) return
    const d = data.data
    payCfg.value = {
      wechatEnabled: !!d.wechatEnabled,
      alipayEnabled: !!d.alipayEnabled,
      wechatSubMchId: d.wechatSubMchId || '',
      alipaySmid: d.alipaySmid || '',
      wechatAppId: d.wechatAppId || '',
      wechatApiV3Key: '',
      wechatMchSerialNo: '',
      wechatPrivateKeyPem: '',
      alipayPrivateKey: '',
      alipayPublicKey: '',
      wechatApiV3KeyConfigured: !!d.wechatApiV3KeyConfigured,
      wechatMchSerialConfigured: !!d.wechatMchSerialConfigured,
      wechatPrivateKeyConfigured: !!d.wechatPrivateKeyConfigured,
      alipayPrivateKeyConfigured: !!d.alipayPrivateKeyConfigured,
      alipayPublicKeyConfigured: !!d.alipayPublicKeyConfigured,
      wechatChannelReady: !!d.wechatChannelReady,
      alipayChannelReady: !!d.alipayChannelReady,
      onboardingRemark: d.onboardingRemark || '',
      merchantPayEnabled: !!d.merchantPayEnabled,
      vendor: d.vendor || 'OFFICIAL',
      payMock: d.payMock !== false,
    }
  } catch {
    /* ignore */
  }
}

async function savePayConfig() {
  payCfgSaving.value = true
  try {
    const { data } = await api.put('/staff/payment-config', {
      wechatEnabled: payCfg.value.wechatEnabled,
      alipayEnabled: payCfg.value.alipayEnabled,
      wechatSubMchId: payCfg.value.wechatSubMchId,
      alipaySmid: payCfg.value.alipaySmid,
      wechatAppId: payCfg.value.wechatAppId,
      wechatApiV3Key: payCfg.value.wechatApiV3Key || undefined,
      wechatMchSerialNo: payCfg.value.wechatMchSerialNo || undefined,
      wechatPrivateKeyPem: payCfg.value.wechatPrivateKeyPem || undefined,
      alipayPrivateKey: payCfg.value.alipayPrivateKey || undefined,
      alipayPublicKey: payCfg.value.alipayPublicKey || undefined,
      onboardingRemark: payCfg.value.onboardingRemark,
    })
    if (data.code === 0) {
      ElMessage.success('收款配置已保存')
      await loadPayConfig()
    } else ElMessage.error(data.message || '保存失败')
  } finally {
    payCfgSaving.value = false
  }
}

async function loadPrepaid() {
  await ensureSpaceTree()
  const roomId = prepaidRoomId.value || undefined
  const { data } = await api.get('/staff/prepaid/plans', { params: roomId ? { roomId } : {} })
  if (data.code === 0) prepaidPlans.value = data.data || []
  else ElMessage.error(data.message)
}

async function previewPrepaid() {
  const roomId = prepaidRoomId.value
  if (!roomId) {
    ElMessage.warning('请选择房屋')
    return
  }
  const months = prepaidMonthsList.value
  if (!months.length) {
    ElMessage.warning('请选择有效的起止账期（起 ≤ 止）')
    return
  }
  if (!prepaidCats.value.length) {
    ElMessage.warning('请选择费项')
    return
  }
  const { data } = await api.post('/staff/prepaid/plans/preview', {
    roomId,
    billMonths: months,
    feeCategories: prepaidCats.value,
  })
  if (data.code === 0) {
    prepaidListAmount.value = Number(data.data?.listAmount ?? 0)
    ElMessage.success(`原价合计 ${prepaidListAmount.value}（共 ${months.length} 个月）`)
  } else ElMessage.error(data.message)
}

async function createPrepaidPlan() {
  const roomId = prepaidRoomId.value
  if (!roomId) {
    ElMessage.warning('请选择房屋')
    return
  }
  const months = prepaidMonthsList.value
  if (!months.length) {
    ElMessage.warning('请选择有效的起止账期（起 ≤ 止）')
    return
  }
  if (!prepaidCats.value.length) {
    ElMessage.warning('请选择费项')
    return
  }
  const { data } = await api.post('/staff/prepaid/plans', {
    roomId,
    billMonths: months,
    feeCategories: prepaidCats.value,
    cashAmount: prepaidCash.value,
    payChannel: 'CASH',
    confirm: true,
  })
  if (data.code === 0) {
    ElMessage.success(data.data?.note || '预缴协议已生效')
    loadPrepaid()
  } else ElMessage.error(data.message)
}

async function voidPrepaid(id: number) {
  try {
    await ElMessageBox.confirm(
      '作废后不再跳过出账；已改账单不自动加回；已入账实收不回冲。确定作废？',
      '作废预缴协议',
      { type: 'warning' },
    )
  } catch {
    return
  }
  const { data } = await api.post(`/staff/prepaid/plans/${id}/void`, { reason: '后台作废' })
  if (data.code === 0) {
    ElMessage.success(data.data?.note || '已作废')
    loadPrepaid()
  } else ElMessage.error(data.message)
}

async function deletePrepaid(id: number) {
  try {
    await ElMessageBox.confirm('删除后不可恢复（仅已作废/草稿可删）。确定删除？', '删除预缴协议', {
      type: 'warning',
    })
  } catch {
    return
  }
  const { data } = await api.delete(`/staff/prepaid/plans/${id}`)
  if (data.code === 0) {
    ElMessage.success(data.data?.note || '已删除')
    loadPrepaid()
  } else ElMessage.error(data.message)
}

async function createExemption() {
  const roomId = exForm.value.roomId
  if (roomId == null) {
    ElMessage.warning('请选择房屋')
    return
  }
  if (!exForm.value.effectiveFrom) {
    ElMessage.warning('请选择起始账期')
    return
  }
  if (
    exForm.value.effectiveTo &&
    exForm.value.effectiveTo < exForm.value.effectiveFrom
  ) {
    ElMessage.warning('结束账期不能早于起始账期')
    return
  }
  const { data } = await api.post('/staff/room-fee-exemptions', {
    roomId,
    feeCategory: exForm.value.feeCategory,
    effectiveFrom: exForm.value.effectiveFrom,
    effectiveTo: exForm.value.effectiveTo || null,
    reason: exForm.value.reason || null,
  })
  if (data.code === 0) {
    ElMessage.success('已添加豁免')
    exForm.value.reason = ''
    load()
  } else ElMessage.error(data.message)
}

async function deleteExemption(id: number) {
  const { data } = await api.delete(`/staff/room-fee-exemptions/${id}`)
  if (data.code === 0) {
    ElMessage.success('已删除豁免')
    load()
  } else ElMessage.error(data.message)
}

async function upsertRule(itemId: number, key: string, price: number) {
  const rules = priceRules.value[itemId] || []
  const exist = rules.find((r) => r.matchKey === key)
  if (exist) {
    return api.put(`/staff/fee-item-price-rules/${exist.id}`, { matchKey: key, unitPrice: price })
  }
  return api.post(`/staff/fee-items/${itemId}/price-rules`, { matchKey: key, unitPrice: price })
}

async function saveParkingMgmt(item: any) {
  const mgmt = parseAmount(ensureRuleDraft(item.id).mgmt)
  if (mgmt == null) {
    ElMessage.warning('请填写车位管理费单价')
    return
  }
  const { data } = await upsertRule(item.id, 'DEFAULT', mgmt)
  if (data.code !== 0) {
    ElMessage.error(data.message)
    return
  }
  ElMessage.success('已保存')
  await loadRules(item.id)
}

async function saveParkingMonthly(item: any) {
  const monthly = parseAmount(ensureRuleDraft(item.id).monthly)
  if (monthly == null) {
    ElMessage.warning('请填写月保费单价')
    return
  }
  const { data } = await upsertRule(item.id, 'DEFAULT', monthly)
  if (data.code !== 0) {
    ElMessage.error(data.message)
    return
  }
  ElMessage.success('已保存')
  await loadRules(item.id)
}

async function saveOtherMonthly(item: any) {
  const monthly = parseAmount(ensureRuleDraft(item.id).monthly)
  if (monthly == null) {
    ElMessage.warning('请填写月固定额')
    return
  }
  const { data } = await api.put(`/staff/fee-items/${item.id}`, {
    monthlyAmount: monthly,
    status: item.status,
  })
  if (data.code === 0) {
    ElMessage.success('已保存')
    load()
  } else ElMessage.error(data.message)
}

async function savePropertyFee(item: any) {
  const d = ensureRuleDraft(item.id)
  const raw = d.discount
  const discount = raw == null || String(raw).trim() === '' ? 1 : parseAmount(raw)
  if (discount == null || discount < 0) {
    ElMessage.warning('系数须为非负数，留空表示 1')
    return
  }
  const useArea = d.useArea !== false
  const useHouseTypePrice = d.useHouseTypePrice !== false
  if (!useArea && !useHouseTypePrice) {
    ElMessage.warning('请至少勾选「收费面积」或「物业费单价」')
    return
  }
  const { data } = await api.put(`/staff/fee-items/${item.id}`, {
    remark: buildPropertyRemark(discount, useArea, useHouseTypePrice),
    status: item.status,
  })
  if (data.code === 0) {
    ElMessage.success('已保存公式配置')
    load()
  } else ElMessage.error(data.message)
}

async function toggleStatus(item: any) {
  const next = item.status === 1 ? 0 : 1
  const { data } = await api.put(`/staff/fee-items/${item.id}`, { status: next })
  if (data.code === 0) {
    ElMessage.success(next === 1 ? '已启用：将对有住户房屋生效' : '已停用：生成账单时不再计入')
    load()
  } else ElMessage.error(data.message)
}

async function deleteItem(item: any) {
  try {
    await ElMessageBox.confirm(`删除费项「${item.name}」？`, '删除费项', { type: 'warning' })
  } catch {
    return
  }
  const { data } = await api.delete(`/staff/fee-items/${item.id}`)
  if (data.code === 0) {
    ElMessage.success('已删除')
    load()
  } else ElMessage.error(data.message)
}

function openAdd() {
  addForm.value = {
    name: '垃圾费',
    feeCategory: 'GARBAGE',
    billingMode: 'FIXED',
    monthlyAmount: '',
    mgmt: '',
    discountRate: '',
    useArea: true,
    useHouseTypePrice: true,
  }
  addVisible.value = true
}

async function saveAdd() {
  const f = addForm.value
  if (!f.name.trim()) {
    ElMessage.warning(f.feeCategory === 'OTHER' ? '请填写二级名称' : '请填写显示名')
    return
  }
  const feeCategory = f.feeCategory
  const billingMode = f.billingMode
  addSaving.value = true
  try {
    const body: any = {
      name: f.name.trim(),
      feeCategory,
      billingMode,
    }
    if (billingMode === 'FIXED') {
      const m = parseAmount(f.monthlyAmount)
      if (m == null) {
        ElMessage.warning('请填写固定金额（元/房屋/月）')
        return
      }
      body.monthlyAmount = m
      body.remark = JSON.stringify({ billingMode: 'FIXED' })
    } else if (billingMode === 'IMPORT') {
      body.remark = JSON.stringify({ billingMode: 'IMPORT' })
    } else if (feeCategory === 'PROPERTY_FEE') {
      const discount =
        f.discountRate.trim() === '' ? 1 : parseAmount(f.discountRate)
      if (discount == null || discount < 0) {
        ElMessage.warning('系数须为非负数，留空表示 1')
        return
      }
      if (!f.useArea && !f.useHouseTypePrice) {
        ElMessage.warning('请至少勾选「收费面积」或「物业费单价」')
        return
      }
      body.remark = buildPropertyRemark(discount, f.useArea, f.useHouseTypePrice)
    } else {
      body.remark = JSON.stringify({ billingMode: 'FORMULA' })
    }
    const { data } = await api.post('/staff/fee-items', body)
    if (data.code !== 0) {
      ElMessage.error(data.message)
      return
    }
    const id = data.data.id
    if (billingMode === 'FORMULA' && feeCategory === 'PARKING_MGMT') {
      const mgmt = parseAmount(f.mgmt)
      if (mgmt == null) {
        ElMessage.warning('请填写车位管理费单价')
        return
      }
      await upsertRule(id, 'DEFAULT', mgmt)
    } else if (billingMode === 'FORMULA' && feeCategory === 'PARKING_MONTHLY') {
      const m = parseAmount(f.monthlyAmount)
      if (m == null) {
        ElMessage.warning('请填写月保费单价')
        return
      }
      await upsertRule(id, 'DEFAULT', m)
    }
    ElMessage.success(
      billingMode === 'IMPORT' ? '已新增（需按月表格导入）'
        : billingMode === 'FORMULA' ? '已新增公式费项' : '已新增固定价格费项',
    )
    addVisible.value = false
    load()
  } finally {
    addSaving.value = false
  }
}

async function generate() {
  if (enabledImportItems.value.length && !importReady.value) {
    ElMessage.warning(
      `存在需导入的启用费项（${missingImportNames.value.join('、')}），请先下载模板并上传本月数据`,
    )
    return
  }
  const { data } = await api.post('/staff/bills/generate', { billMonth: billMonth.value })
  if (data.code === 0) {
    genResult.value = data.data
    ElMessage.success(`生成完成 成功 ${data.data.successCount ?? '-'}，失败 ${data.data.failCount ?? '-'}`)
    load()
  } else ElMessage.error(data.message)
}

async function publishAll() {
  if (enabledImportItems.value.length && !importReady.value) {
    ElMessage.warning('请先完成本月导入配置，再一键发放')
    return
  }
  const { data } = await api.post('/staff/bills/publish', { billMonth: billMonth.value })
  if (data.code === 0) {
    ElMessage.success(`已发放 ${data.data.count ?? ''} 单`)
    load()
  } else ElMessage.error(data.message)
}

async function confirmPaid(id: number) {
  try {
    await ElMessageBox.confirm('确认已收到该笔款项？确认后账单将锁定为已缴，不可再改。', '确认收款', {
      type: 'warning',
      confirmButtonText: '确认收款',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  const { data } = await api.post(`/staff/bills/${id}/confirm-paid`, { payChannel: 'TRANSFER' })
  if (data.code === 0) {
    ElMessage.success('已确认收款')
    load()
  } else ElMessage.error(data.message)
}

async function voidBill(id: number) {
  try {
    await ElMessageBox.confirm('作废后住户不可再缴此单；公开收入不受影响。确定作废？', '作废未缴账单', {
      type: 'warning',
    })
  } catch {
    return
  }
  const { data } = await api.post(`/staff/bills/${id}/void`, { remark: '物业作废' })
  if (data.code === 0) {
    ElMessage.success('已作废')
    load()
  } else ElMessage.error(data.message)
}

async function creditReverse(id: number) {
  try {
    const { value } = await ElMessageBox.prompt('请填写冲红原因（将回冲财务公开收入）', '已缴冲红', {
      inputPlaceholder: '原因必填',
      type: 'warning',
    })
    if (!value?.trim()) {
      ElMessage.warning('原因必填')
      return
    }
    const { data } = await api.post(`/staff/bills/${id}/credit-reverse`, { remark: value.trim() })
    if (data.code === 0) {
      ElMessage.success('已冲红，可再单房重出正确账单')
      load()
    } else ElMessage.error(data.message)
  } catch {
    /* cancel */
  }
}

async function republishRoom(row: any) {
  const label = row.roomLabel || row.roomNo || row.roomId
  try {
    await ElMessageBox.confirm(
      `将重新计算并覆盖「${label}」账期 ${billMonth.value} 的待缴金额，住户将看到新金额。确定单房重出？`,
      '单房重出',
      { type: 'warning', confirmButtonText: '重出并发放', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  const { data } = await api.post(`/staff/rooms/${row.roomId}/bills/republish`, {
    billMonth: billMonth.value,
  })
  if (data.code === 0) {
    ElMessage.success('已重出并发放')
    load()
  } else ElMessage.error(data.message)
}

async function downloadMeterTemplate() {
  const res = await api.get('/staff/bill-meter-uploads/template', {
    params: { billMonth: billMonth.value },
    responseType: 'blob',
  })
  const url = URL.createObjectURL(res.data)
  const a = document.createElement('a')
  a.href = url
  a.download = `bill-meter-${billMonth.value}.xlsx`
  a.click()
  URL.revokeObjectURL(url)
}

async function onMeterFile(file: File) {
  const form = new FormData()
  form.append('file', file)
  const { data } = await api.post('/staff/bill-meter-uploads/import', form, {
    headers: { 'Content-Type': 'multipart/form-data' },
  })
  if (data.code === 0) {
    ElMessage.success(`导入成功 ${data.data.successCount}，失败 ${data.data.failCount}`)
    load()
  } else ElMessage.error(data.message)
  return false
}

onMounted(load)

watch(activeTab, () => {
  load()
})

watch(
  () => route.query.tab,
  (t) => {
    if (t != null && typeof t === 'string' && !(TAB_KEYS as readonly string[]).includes(t)) {
      router.replace({ path: '/billing' })
    }
  },
)
</script>

<template>
  <div class="page" v-loading="loading">
    <header>
      <div>
        <h2>账单</h2>
        <p class="sub">出账发放与收款配置</p>
        <StaffNav />
      </div>
      <StaffSessionAction />
    </header>

    <el-tabs :model-value="activeTab" class="tabs" @tab-change="onTabChange">
      <el-tab-pane label="收款配置" name="payment" />
      <el-tab-pane label="账单" name="bills" />
    </el-tabs>

    <template v-if="activeTab === 'payment'">
    <section class="block pay-guide-card">
      <h3>当前效果</h3>
      <p class="pay-status">{{ payStatusTitle }}</p>
      <ol class="pay-steps">
        <li>在本页打开微信和/或支付宝，填入官方商户资料与密钥，点保存。配齐后住户 App 即可缴费，无需另外部署。</li>
        <li>微信需：商户号、APIv3 密钥、证书序列号、商户私钥；支付宝需：APPID、应用私钥、支付宝公钥。</li>
        <li>密钥保存后再次打开页面不会回显明文；要更换请重新粘贴后保存。未开通时仍可在「账单」手动确认收款。</li>
      </ol>
    </section>

    <section class="block">
      <h3>微信支付</h3>
      <el-form label-width="140px" class="pay-cfg-form" style="max-width:820px">
        <el-form-item label="启用微信">
          <el-switch v-model="payCfg.wechatEnabled" />
          <el-tag v-if="payCfg.wechatChannelReady" type="success" style="margin-left:10px">已配齐</el-tag>
          <el-tag v-else type="info" style="margin-left:10px">未配齐</el-tag>
        </el-form-item>
        <el-form-item label="商户号">
          <el-input v-model="payCfg.wechatSubMchId" placeholder="微信支付商户号" :disabled="!payCfg.wechatEnabled" />
        </el-form-item>
        <el-form-item label="AppID（选填）">
          <el-input v-model="payCfg.wechatAppId" placeholder="拉起支付用的 App/小程序 AppID" :disabled="!payCfg.wechatEnabled" />
        </el-form-item>
        <el-form-item label="APIv3 密钥">
          <el-input
            v-model="payCfg.wechatApiV3Key"
            type="password"
            show-password
            :placeholder="payCfg.wechatApiV3KeyConfigured ? '已配置，留空不修改' : '32 位 APIv3 密钥'"
            :disabled="!payCfg.wechatEnabled"
          />
        </el-form-item>
        <el-form-item label="证书序列号">
          <el-input
            v-model="payCfg.wechatMchSerialNo"
            :placeholder="payCfg.wechatMchSerialConfigured ? '已配置，留空不修改' : '商户 API 证书序列号'"
            :disabled="!payCfg.wechatEnabled"
          />
        </el-form-item>
        <el-form-item label="商户私钥">
          <el-input
            v-model="payCfg.wechatPrivateKeyPem"
            type="textarea"
            :rows="4"
            :placeholder="payCfg.wechatPrivateKeyConfigured ? '已配置，留空不修改；更换请粘贴完整 PEM' : '-----BEGIN PRIVATE KEY----- ...'"
            :disabled="!payCfg.wechatEnabled"
          />
        </el-form-item>
      </el-form>
    </section>

    <section class="block">
      <h3>支付宝</h3>
      <el-form label-width="140px" class="pay-cfg-form" style="max-width:820px">
        <el-form-item label="启用支付宝">
          <el-switch v-model="payCfg.alipayEnabled" />
          <el-tag v-if="payCfg.alipayChannelReady" type="success" style="margin-left:10px">已配齐</el-tag>
          <el-tag v-else type="info" style="margin-left:10px">未配齐</el-tag>
        </el-form-item>
        <el-form-item label="APPID">
          <el-input v-model="payCfg.alipaySmid" placeholder="支付宝应用 APPID" :disabled="!payCfg.alipayEnabled" />
        </el-form-item>
        <el-form-item label="应用私钥">
          <el-input
            v-model="payCfg.alipayPrivateKey"
            type="textarea"
            :rows="4"
            :placeholder="payCfg.alipayPrivateKeyConfigured ? '已配置，留空不修改' : '应用私钥（PKCS8）'"
            :disabled="!payCfg.alipayEnabled"
          />
        </el-form-item>
        <el-form-item label="支付宝公钥">
          <el-input
            v-model="payCfg.alipayPublicKey"
            type="textarea"
            :rows="4"
            :placeholder="payCfg.alipayPublicKeyConfigured ? '已配置，留空不修改' : '支付宝公钥'"
            :disabled="!payCfg.alipayEnabled"
          />
        </el-form-item>
        <el-form-item label="备注（选填）">
          <el-input v-model="payCfg.onboardingRemark" type="textarea" :rows="2" placeholder="对接人、开通备注等" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="payCfgSaving" @click="savePayConfig">保存收款配置</el-button>
          <span class="hint" style="margin-left:12px">保存后 App 住户端即可按已开通渠道缴费。</span>
        </el-form-item>
      </el-form>
    </section>
    </template>

    <template v-else>
    <section class="block">
      <h3>预缴登记</h3>
      <p class="hint">约定月份与费项；实付可含折扣（优惠不进公开）。生效后：覆盖月已缴须先冲红；未缴账单自动去掉覆盖费项（空单作废）；以后出账跳过；实收按账期分摊计入公示。</p>
      <div class="prepaid-form">
        <div class="row wrap" style="gap:8px; align-items:center">
          <el-select
            v-model="prepaidRoomId"
            filterable
            clearable
            placeholder="搜索选房（楼栋-单元-楼层-房号）"
            style="width:280px"
          >
            <el-option
              v-for="r in prepaidRoomOptions"
              :key="r.id"
              :label="r.label"
              :value="r.id"
            />
          </el-select>
          <el-date-picker
            v-model="prepaidMonthFrom"
            type="month"
            value-format="YYYY-MM"
            placeholder="起始账期"
            style="width:140px"
          />
          <span class="hint">至</span>
          <el-date-picker
            v-model="prepaidMonthTo"
            type="month"
            value-format="YYYY-MM"
            placeholder="结束账期"
            style="width:140px"
          />
          <span class="hint" v-if="prepaidMonthsList.length">共 {{ prepaidMonthsList.length }} 个月</span>
          <el-select v-model="prepaidCats" multiple collapse-tags placeholder="费项大类" style="width:260px">
            <el-option v-for="o in feeCategoryOptions" :key="o.value" :label="categoryDefaultName(o.value)" :value="o.value" />
          </el-select>
          <el-input-number v-model="prepaidCash" :min="0.01" :precision="2" :step="0.01" :controls="false" />
          <el-button @click="previewPrepaid">算原价</el-button>
          <el-button type="success" @click="createPrepaidPlan">确认预缴</el-button>
          <el-button @click="loadPrepaid">刷新协议</el-button>
        </div>
        <p v-if="prepaidListAmount != null" class="hint" style="margin-top:8px">
          原价 {{ formatMoney(prepaidListAmount) }} / 实付 {{ formatMoney(prepaidCash) }}
          <template v-if="prepaidListAmount >= prepaidCash">
            （优惠 {{ formatMoney(prepaidListAmount - prepaidCash) }}，不进公开收入）
          </template>
        </p>
        <p v-if="!prepaidRoomOptions.length" class="hint" style="margin-top:6px">暂无房屋，请先在「空间」维护楼栋房屋。</p>
      </div>
      <el-table v-if="prepaidPlans.length" :data="prepaidPlans" size="small" style="margin-top:10px">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column label="房屋" min-width="160">
          <template #default="{ row }">{{ roomLabelOf(row.roomId) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">{{ prepaidStatusLabel[row.status] || row.status }}</template>
        </el-table-column>
        <el-table-column label="原价" width="100">
          <template #default="{ row }">{{ formatMoney(row.listAmount) }}</template>
        </el-table-column>
        <el-table-column label="实付" width="100">
          <template #default="{ row }">{{ formatMoney(row.cashAmount) }}</template>
        </el-table-column>
        <el-table-column label="优惠" width="100">
          <template #default="{ row }">{{ formatMoney(row.discountAmount) }}</template>
        </el-table-column>
        <el-table-column label="账期" min-width="200">
          <template #default="{ row }">
            <span v-if="(row.billMonths || []).length">
              {{ row.billMonths[0] }}
              <template v-if="row.billMonths.length > 1">
                ～{{ row.billMonths[row.billMonths.length - 1] }}
                （{{ row.billMonths.length }}个月）
              </template>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'ACTIVE' || row.status === 'DRAFT'"
              link
              type="danger"
              @click="voidPrepaid(row.id)"
            >作废</el-button>
            <el-button
              v-if="row.status === 'VOID' || row.status === 'DRAFT'"
              link
              type="danger"
              @click="deletePrepaid(row.id)"
            >删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section class="block">
      <div class="sec-head">
        <h3>费项模板</h3>
        <el-button type="primary" @click="openAdd">新增费项</el-button>
      </div>
      <p class="hint">
        先选<strong>费用大类</strong>（九大类写死；除「其他」外各大类本小区仅一条），再选<strong>计费方式</strong>（固定价格 / 公式算费 / 表格导入，三选一）。公式仅物业/车位/月保可用；显示名可改，财务归属仍按大类。
        <strong>启用</strong>=对本小区有住户房屋出账时计入；<strong>停用</strong>=不计入。
        默认<strong>空列表</strong>，由物业自行新增；删除后不会自动恢复。未配置启用费项时无法生成账单。
      </p>
      <el-table :data="feeItems" size="small">
        <el-table-column type="index" label="序号" width="60" :index="(i: number) => i + 1" />
        <el-table-column prop="name" label="显示名" min-width="120" />
        <el-table-column label="大类" width="110">
          <template #default="{ row }">{{ categoryDefaultName(row.feeCategory) }}</template>
        </el-table-column>
        <el-table-column label="计费方式" width="100">
          <template #default="{ row }">
            <el-tag v-if="billingModeOf(row) === 'IMPORT'" size="small" type="warning">表格导入</el-tag>
            <el-tag v-else-if="billingModeOf(row) === 'FORMULA'" size="small" type="success">公式算费</el-tag>
            <el-tag v-else size="small">固定价格</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="单价 / 月额 / 公式" min-width="480">
          <template #default="{ row }">
            <div v-if="row.feeCategory === 'PROPERTY_FEE' && billingModeOf(row) === 'FORMULA'" class="rule-row nowrap">
              <el-checkbox v-model="ensureRuleDraft(row.id).useArea">收费面积</el-checkbox>
              <el-checkbox v-model="ensureRuleDraft(row.id).useHouseTypePrice">× 物业费单价</el-checkbox>
              <span>×</span>
              <el-input v-model="ensureRuleDraft(row.id).discount" placeholder="1" style="width:64px" />
              <span class="muted">系数（≥0，留空=1）</span>
              <el-button size="small" type="primary" link @click="savePropertyFee(row)">保存</el-button>
            </div>
            <div v-else-if="row.feeCategory === 'PARKING_MGMT' && billingModeOf(row) === 'FORMULA'" class="rule-row">
              <span>元/车位·月</span>
              <el-input v-model="ensureRuleDraft(row.id).mgmt" placeholder="元" style="width:100px" />
              <el-button size="small" type="primary" link @click="saveParkingMgmt(row)">保存</el-button>
            </div>
            <div v-else-if="row.feeCategory === 'PARKING_MONTHLY' && billingModeOf(row) === 'FORMULA'" class="rule-row">
              <span>元/车·月</span>
              <el-input v-model="ensureRuleDraft(row.id).monthly" placeholder="元" style="width:100px" />
              <el-button size="small" type="primary" link @click="saveParkingMonthly(row)">保存</el-button>
            </div>
            <div v-else-if="billingModeOf(row) === 'FIXED'" class="rule-row">
              <span>{{
                row.feeCategory === 'PARKING_MGMT'
                  ? '元/车位·月'
                  : row.feeCategory === 'PARKING_MONTHLY'
                    ? '元/车·月'
                    : '元/房屋/月'
              }}</span>
              <el-input v-model="ensureRuleDraft(row.id).monthly" placeholder="元" style="width:100px" />
              <el-button size="small" type="primary" link @click="saveOtherMonthly(row)">保存</el-button>
            </div>
            <span v-else class="muted">按月 Excel 导入金额/读数</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
              {{ row.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="toggleStatus(row)">
              {{ row.status === 1 ? '停用' : '启用' }}
            </el-button>
            <el-button link type="danger" @click="deleteItem(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section class="block">
      <h3>房屋费项豁免</h3>
      <p class="hint">按房 + 费项类别 + 生效账期配置；出账时自动跳过。减免不计入财务公开余额。</p>
      <div class="row wrap" style="margin-bottom:12px; gap:8px; align-items:center">
        <el-select
          v-model="exForm.roomId"
          filterable
          clearable
          placeholder="搜索选房（楼栋-单元-楼层-房号）"
          style="width:280px"
        >
          <el-option
            v-for="r in prepaidRoomOptions"
            :key="r.id"
            :label="r.label"
            :value="r.id"
          />
        </el-select>
        <el-select v-model="exForm.feeCategory" style="width:160px">
          <el-option v-for="o in feeCategoryOptions" :key="o.value" :label="categoryDefaultName(o.value)" :value="o.value" />
        </el-select>
        <el-date-picker
          v-model="exForm.effectiveFrom"
          type="month"
          value-format="YYYY-MM"
          placeholder="起始账期"
          style="width:140px"
          :clearable="false"
        />
        <span class="hint">至</span>
        <el-date-picker
          v-model="exForm.effectiveTo"
          type="month"
          value-format="YYYY-MM"
          placeholder="结束账期（可空）"
          style="width:150px"
          clearable
        />
        <el-input v-model="exForm.reason" placeholder="原因" style="width:160px" />
        <el-button type="primary" @click="createExemption">添加豁免</el-button>
      </div>
      <p v-if="!prepaidRoomOptions.length" class="hint" style="margin:-4px 0 10px">暂无房屋，请先在「空间」维护楼栋房屋。</p>
      <el-table :data="exemptions" size="small">
        <el-table-column label="房屋" min-width="180">
          <template #default="{ row }">{{ roomLabelOf(row.roomId) }}</template>
        </el-table-column>
        <el-table-column label="费项大类" width="130">
          <template #default="{ row }">{{ categoryDefaultName(row.feeCategory) }}</template>
        </el-table-column>
        <el-table-column prop="effectiveFrom" label="起" width="100" />
        <el-table-column prop="effectiveTo" label="止" width="100" />
        <el-table-column prop="reason" label="原因" min-width="120" />
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button link type="danger" @click="deleteExemption(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section class="block" :class="{ warn: enabledImportItems.length && !importReady }">
      <h3>本月导入配置（账期 {{ billMonth }}）</h3>
      <p v-if="!enabledImportItems.length" class="hint">
        当前无「表格导入」类启用费项，可直接点「生成账单」再「一键发放」。
      </p>
      <template v-else>
        <p class="hint">
          已启用需导入的费项：{{ enabledImportItems.map((x) => x.name).join('、') }}。
          请先<strong>下载模板</strong>（已列出本小区全部房屋；第 1 行表头，从第 2 行填；公摊填 H 列金额，水电气填 I～K 列）。填完后上传，才能生成/发放账单。
          <span v-if="importReady" class="ok">本月导入已就绪（{{ meterUploads.length }} 条）。</span>
          <span v-else class="bad">缺少：{{ missingImportNames.join('、') }}</span>
        </p>
        <div class="row">
          <el-button @click="downloadMeterTemplate">下载导入模板</el-button>
          <el-upload :show-file-list="false" :before-upload="onMeterFile" accept=".xlsx,.xls">
            <el-button type="primary">上传本月配置</el-button>
          </el-upload>
        </div>
      </template>
    </section>

    <section class="block">
      <div class="sec-head">
        <h3>账单</h3>
        <div class="row">
          <span class="muted">账期</span>
          <el-date-picker
            v-model="billMonth"
            type="month"
            value-format="YYYY-MM"
            placeholder="选择月份"
            style="width:140px"
            :clearable="false"
            @change="onBillMonthChange"
          />
          <el-button type="primary" @click="generate">生成账单</el-button>
          <el-button type="success" @click="publishAll">一键发放</el-button>
          <el-button @click="load">刷新</el-button>
        </div>
      </div>
      <p class="hint">
        选择账期后先看下方<strong>算费预览</strong>（不写库），确认可出账房屋后再点<strong>生成账单</strong>写出草稿；
        <strong>一键发放</strong>后住户可见。缺面积/房屋类型请到「空间」补全。
      </p>

      <div v-if="previewSummary" class="stat-row">
        <button
          type="button"
          class="stat-card"
          :class="{ active: previewFilter === 'ALL' }"
          @click="setPreviewFilter('ALL')"
        >
          <span class="stat-num">{{ previewSummary.includedCount }}</span>
          <span class="stat-label">纳入出账</span>
          <span class="stat-desc">有有效住户的房屋</span>
        </button>
        <button
          type="button"
          class="stat-card ok-card"
          :class="{ active: previewFilter === 'READY' }"
          @click="setPreviewFilter('READY')"
        >
          <span class="stat-num">{{ previewSummary.readyCount }}</span>
          <span class="stat-label">可出账</span>
          <span class="stat-desc">按当前费项可生成</span>
        </button>
        <button
          type="button"
          class="stat-card bad-card"
          :class="{ active: previewFilter === 'WILL_FAIL' }"
          @click="setPreviewFilter('WILL_FAIL')"
        >
          <span class="stat-num">{{ previewSummary.failCount }}</span>
          <span class="stat-label">将失败</span>
          <span class="stat-desc">缺资料或单价</span>
        </button>
        <button
          v-if="(previewSummary.mismatchCount || 0) > 0"
          type="button"
          class="stat-card bad-card"
          :class="{ active: previewFilter === 'MISMATCH' }"
          @click="setPreviewFilter('MISMATCH')"
        >
          <span class="stat-num">{{ previewSummary.mismatchCount }}</span>
          <span class="stat-label">与已生成不一致</span>
          <span class="stat-desc">二次预览有差异，红字行</span>
        </button>
        <div v-if="previewSummary.propertyFeeEnabled" class="stat-card plain">
          <span class="stat-num">{{ previewSummary.propertyReadyCount }}/{{ previewSummary.includedCount }}</span>
          <span class="stat-label">物业费资料</span>
          <span class="stat-desc">
            缺 {{ previewSummary.propertyMissingCount }} 套
            <el-button type="primary" link @click.stop="goSpace()">去空间补全</el-button>
          </span>
        </div>
        <div v-if="previewSummary.importFeeEnabled" class="stat-card plain">
          <span class="stat-num">{{ previewSummary.meterRoomCount }}</span>
          <span class="stat-label">本月已导入房</span>
          <span class="stat-desc">
            <span v-if="previewSummary.importReady" class="ok">导入门禁已通过</span>
            <span v-else class="bad">缺：{{ (previewSummary.missingImportCategories || []).join('、') }}</span>
          </span>
        </div>
      </div>

      <div class="preview-head">
        <h4>本月算费预览</h4>
        <span class="muted">只读干跑，与生成逻辑一致；点上方数字可筛选。已有账单时，合计/费项不一致会红字标注。</span>
      </div>
      <el-table
        :data="previewRooms"
        size="small"
        max-height="360"
        style="margin-bottom:16px"
        :row-class-name="previewRowClassName"
      >
        <el-table-column type="index" label="序号" width="55" :index="(i: number) => i + 1" />
        <el-table-column label="房屋" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="{ bad: row.diffWithGenerated }">{{ row.roomLabel || row.roomNo || row.roomId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="住户" width="70">
          <template #default="{ row }">{{ row.occupantCount ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="面积" width="80">
          <template #default="{ row }">{{ row.areaSqm ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="类型/单价" min-width="110" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.houseTypeName">{{ row.houseTypeName }}{{ row.unitPrice != null ? ` · ${row.unitPrice}` : '' }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="车位/车" width="80">
          <template #default="{ row }">{{ row.parkingSpaces ?? 0 }}/{{ row.vehicles ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="预计费项" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="{ bad: row.diffWithGenerated }">
              {{ (row.expectedLines && row.expectedLines.length) ? row.expectedLines.join('、') : '—' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="预计合计" width="90">
          <template #default="{ row }">
            <span :class="{ bad: row.diffWithGenerated }">{{ row.estimatedTotal ?? '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.diffWithGenerated" size="small" type="danger">不一致</el-tag>
            <el-tag v-else-if="row.status === 'READY'" size="small" type="success">可出账</el-tag>
            <el-tag v-else size="small" type="danger">将失败</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.diffWithGenerated" class="bad">{{ row.diffReason }}</span>
            <span v-else-if="row.failReason" class="bad">{{ row.failReason }}</span>
            <span v-else-if="row.billStatus" class="muted">本月已有 {{ billStatusLabel[row.billStatus] || row.billStatus }}</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="goSpace(row.roomId)">去空间</el-button>
          </template>
        </el-table-column>
      </el-table>
      <p v-if="!previewRooms.length" class="hint">当前筛选下无房屋；若纳入出账为 0，请先在「住户」绑定有效住户。</p>

      <p v-if="genResult" class="hint">上次生成：成功 {{ genResult.successCount }} / 失败 {{ genResult.failCount }}</p>
      <h4 class="bills-subhead">已生成账单</h4>
      <el-table :data="bills" size="small" row-key="id">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div v-if="row.lines?.length" class="line-detail">
              <div v-for="(ln, i) in row.lines" :key="ln.id || i" class="line-row">
                <span>{{ ln.title || ln.feeCategory }}</span>
                <span>{{ formatMoney(ln.amount) }} 元</span>
              </div>
            </div>
            <span v-else class="muted">暂无明细（请先生成账单）</span>
          </template>
        </el-table-column>
        <el-table-column type="index" label="序号" width="60" :index="(i: number) => i + 1" />
        <el-table-column label="房屋" min-width="140">
          <template #default="{ row }">{{ row.roomLabel || row.roomNo || row.roomId }}</template>
        </el-table-column>
        <el-table-column label="费项汇总" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.lineSummary || '—' }}</template>
        </el-table-column>
        <el-table-column label="应缴合计" width="110">
          <template #default="{ row }">{{ formatMoney(row.totalAmount) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag
              size="small"
              :type="row.status === 'PAID' ? 'success' : row.status === 'PUBLISHED' ? 'warning' : 'info'"
            >
              {{ billStatusLabel[row.status] || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="dueDate" label="到期日" width="120" />
        <el-table-column label="操作" width="280">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'PUBLISHED'"
              type="primary"
              link
              @click="confirmPaid(row.id)"
            >确认收款</el-button>
            <el-button
              v-if="row.status === 'PUBLISHED' || row.status === 'DRAFT'"
              type="warning"
              link
              @click="republishRoom(row)"
            >单房重出</el-button>
            <el-button
              v-if="row.status === 'PUBLISHED' || row.status === 'DRAFT'"
              type="danger"
              link
              @click="voidBill(row.id)"
            >作废</el-button>
            <el-button
              v-if="row.status === 'PAID'"
              type="danger"
              link
              @click="creditReverse(row.id)"
            >冲红</el-button>
            <span v-if="row.status === 'VOID'" class="muted">已作废</span>
          </template>
        </el-table-column>
      </el-table>
    </section>
    </template>

    <el-dialog v-model="addVisible" title="新增费项" width="520px">
      <el-form label-position="top">
        <el-form-item label="费用大类" required>
          <el-select v-model="addForm.feeCategory" style="width:100%" @change="onFeeCategoryChange">
            <el-option v-for="o in feeCategoryOptions" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="计费方式" required>
          <el-select v-model="addForm.billingMode" style="width:100%" @change="onBillingModeChange">
            <el-option
              v-for="o in billingModeOptionsAll.filter((x) => (allowedBillingModes(addForm.feeCategory) as readonly string[]).includes(x.value))"
              :key="o.value"
              :label="o.label"
              :value="o.value"
            />
          </el-select>
          <p class="hint" style="margin:6px 0 0">同一费项同时只能选一种方式；无标准公式的大类不提供公式算费。</p>
        </el-form-item>
        <el-form-item :label="addForm.feeCategory === 'OTHER' ? '二级名称' : '显示名'" required>
          <el-input v-model="addForm.name" :placeholder="addForm.feeCategory === 'OTHER' ? '如：清洁费' : '可改显示名，财务归属仍按大类'" maxlength="32" />
        </el-form-item>
        <el-form-item
          v-if="addForm.billingMode === 'FIXED'"
          :label="
            addForm.feeCategory === 'PARKING_MGMT'
              ? '固定单价（元/车位·月）'
              : addForm.feeCategory === 'PARKING_MONTHLY'
                ? '固定单价（元/车·月）'
                : '固定金额（元/房屋/月）'
          "
          required
        >
          <el-input v-model="addForm.monthlyAmount" placeholder="如：50" style="width:160px" />
          <p
            v-if="addForm.feeCategory === 'PARKING_MGMT' || addForm.feeCategory === 'PARKING_MONTHLY'"
            class="hint"
            style="margin:6px 0 0"
          >
            按房屋实际车位/车辆数量计费；无绑定位或无超额车辆时该房不计此项。
          </p>
        </el-form-item>
        <template v-if="addForm.billingMode === 'FORMULA' && addForm.feeCategory === 'PROPERTY_FEE'">
          <el-form-item label="计算公式">
            <div class="formula-col">
              <el-checkbox v-model="addForm.useArea">收费面积</el-checkbox>
              <el-checkbox v-model="addForm.useHouseTypePrice">乘以物业费单价</el-checkbox>
              <p class="hint" style="margin:6px 0 0">
                面积与单价在「空间」按房维护。至少勾选一项；系数选填，留空为 1，须 ≥ 0。
              </p>
            </div>
          </el-form-item>
          <el-form-item label="系数（选填）">
            <el-input v-model="addForm.discountRate" placeholder="留空=1，可为任意非负数" style="width:220px" />
          </el-form-item>
        </template>
        <el-form-item v-if="addForm.billingMode === 'FORMULA' && addForm.feeCategory === 'PARKING_MGMT'" label="单价（元/车位·月）">
          <el-input v-model="addForm.mgmt" style="width:160px" />
          <p class="hint" style="margin:8px 0 0">
            按本房挂车位数 N、车辆数 X：收 <strong>min(X,N)×单价</strong>。
          </p>
        </el-form-item>
        <el-form-item
          v-if="addForm.billingMode === 'FORMULA' && addForm.feeCategory === 'PARKING_MONTHLY'"
          label="月保单价（元/车·月）"
        >
          <el-input v-model="addForm.monthlyAmount" style="width:160px" />
          <p class="hint" style="margin:8px 0 0">当 X&gt;N 时，对超出部分收 <strong>(X−N)×单价</strong>。</p>
        </el-form-item>
        <p v-if="addForm.billingMode === 'IMPORT'" class="hint">
          表格导入：启用后每个账期须下载模板并上传，才能生成/发放账单。
        </p>
      </el-form>
      <template #footer>
        <el-button @click="addVisible = false">取消</el-button>
        <el-button type="primary" :loading="addSaving" @click="saveAdd">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding: 24px 32px; max-width: 1500px; margin: 0 auto; }
header { display: flex; justify-content: space-between; gap: 16px; align-items: flex-start; }
h2 { margin: 0; color: #1f4e3d; }
.sub { margin: 4px 0 0; color: #667; font-size: 14px; }
.tabs { margin-top: 16px; }
.pay-guide-card { background: #f7faf8; }
.pay-status { margin: 0 0 12px; font-size: 15px; font-weight: 600; color: #1f4e3d; line-height: 1.5; }
.pay-steps { margin: 0; padding-left: 20px; color: #445; font-size: 14px; line-height: 1.7; }
.pay-steps li { margin-bottom: 10px; }
.pay-steps ul { margin: 6px 0 0; padding-left: 20px; }
.row { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.sec-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.block {
  margin-top: 20px;
  background: #fff;
  border: 1px solid #dfe6e1;
  padding: 16px 18px;
}
.block.warn { border-color: #e6b566; background: #fffbf3; }
h3 { margin: 0 0 12px; color: #2a4a3c; font-size: 16px; }
.hint { font-size: 13px; color: #667; margin: 0 0 12px; line-height: 1.5; }
.ok { color: #2a7a4b; }
.bad { color: #c45656; }
.muted { color: #99a; }
.rule-row { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.rule-row.nowrap { flex-wrap: nowrap; white-space: nowrap; }
.formula-col { display: flex; flex-direction: column; gap: 4px; }
.line-detail { padding: 4px 12px 8px 48px; }
.line-row { display: flex; justify-content: space-between; gap: 16px; max-width: 420px; font-size: 13px; color: #445; line-height: 1.7; }
.stat-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin: 0 0 14px;
}
.stat-card {
  flex: 1 1 140px;
  min-width: 130px;
  max-width: 200px;
  text-align: left;
  border: 1px solid #dfe6e1;
  background: #fafcfb;
  border-radius: 2px;
  padding: 10px 12px;
  cursor: pointer;
  font: inherit;
  color: inherit;
}
.stat-card.plain { cursor: default; max-width: 220px; }
.stat-card.active { outline: 2px solid #2a6f4e; background: #eef6f1; }
.stat-card.ok-card.active { outline-color: #2a7a4b; }
.stat-card.bad-card.active { outline-color: #c45656; background: #fdf4f4; }
:deep(.preview-row-mismatch) {
  --el-table-tr-bg-color: #fff5f5;
}
:deep(.preview-row-mismatch td.el-table__cell) {
  background: #fff5f5 !important;
}
:deep(.preview-row-mismatch:hover > td.el-table__cell) {
  background: #ffeaea !important;
}
.stat-num { display: block; font-size: 22px; font-weight: 700; color: #1f4e3d; line-height: 1.2; }
.stat-label { display: block; margin-top: 2px; font-size: 13px; font-weight: 600; color: #2a4a3c; }
.stat-desc { display: block; margin-top: 2px; font-size: 12px; color: #6a7a74; line-height: 1.35; }
.preview-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  margin: 4px 0 8px;
}
.preview-head h4,
.bills-subhead {
  margin: 0 0 8px;
  font-size: 14px;
  color: #1f4e3d;
}
.bills-subhead { margin-top: 8px; }
</style>
