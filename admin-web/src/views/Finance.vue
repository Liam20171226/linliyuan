<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { authFailRedirectPath } from '../authSession'
import { formatMoney } from '../money'
import StaffNav from '../components/StaffNav.vue'
import StaffSessionAction from '../components/StaffSessionAction.vue'

const TAB_KEYS = ['summary', 'entries', 'revenue', 'reconcile'] as const
type TabKey = (typeof TAB_KEYS)[number]

const route = useRoute()
const router = useRouter()
const entries = ref<any[]>([])
const revenueItems = ref<any[]>([])
const summary = ref<any>(null)
const summaryLoading = ref(false)
const showHow = ref(false)
/** month=单月；range=多月区间 */
const periodMode = ref<'month' | 'range'>('month')
const _now = new Date()
const _ym = `${_now.getFullYear()}-${String(_now.getMonth() + 1).padStart(2, '0')}`
const revenueForm = ref({ title: '', amountText: '', occurMonth: _ym, remark: '' })
const revenueSaving = ref(false)
const singleMonth = ref(_ym)
const fromMonth = ref(`${_now.getFullYear()}-01`)
const toMonth = ref(_ym)
const viewMode = ref('BY_BILL_MONTH')

const reconcileDate = ref(new Date(Date.now() - 86400000).toISOString().slice(0, 10))
const reconcileChannel = ref('ALL')
const reconcileRows = ref<any[]>([])
const reconcileLoading = ref(false)
const reconcileRunning = ref(false)

const activeTab = computed<TabKey>(() => {
  const t = route.query.tab
  if (typeof t === 'string' && (TAB_KEYS as readonly string[]).includes(t)) return t as TabKey
  return 'summary'
})

function onTabChange(name: string | number) {
  const tab = String(name)
  if (tab === activeTab.value) return
  router.replace({ path: '/finance', query: tab === 'summary' ? {} : { tab } })
}
const FEE_LABEL: Record<string, string> = {
  PROPERTY_FEE: '物业管理费',
  PARKING_MGMT: '车位管理费',
  PARKING_MONTHLY: '车辆月保费',
  SHARED: '公摊费',
  GARBAGE: '垃圾费',
  WATER: '代收水费',
  ELECTRIC: '代收电费',
  GAS: '代收煤气费',
  OTHER: '其他',
  ENTRY_INCOME: '其他经营收入',
  PREPAID_INCOME: '预缴摊入',
}

const CHANNEL_LABEL: Record<string, string> = {
  WECHAT_MCH: '微信收款',
  ALIPAY_MCH: '支付宝收款',
  TRANSFER: '转账/线下',
  PREPAID: '预缴',
  CASH: '现金',
  QR: '收款码',
  OTHER: '其他',
}

const ENTRY_TYPE_LABEL: Record<string, string> = {
  INCOME: '收入',
  EXPENSE: '支出',
}

function feeLabel(code: string) {
  return FEE_LABEL[code] || code
}

function channelLabel(code: string) {
  return CHANNEL_LABEL[code] || code || '—'
}

function entryTypeLabel(code: string) {
  return ENTRY_TYPE_LABEL[code] || code || '—'
}

function kindLabel(row: any) {
  if (row?.kindLabel) return row.kindLabel
  const amt = Number(row?.amount ?? 0)
  if (amt < 0) return '冲红'
  const remark = String(row?.remark || '')
  if (remark.includes('补收')) return '补收'
  if (row?.kind === 'PREPAID_CONFIRM') return '预缴确认'
  return '收款'
}

function kindClass(row: any) {
  if (Number(row?.amount ?? 0) < 0 || row?.kind === 'CREDIT') return 'amt-neg'
  if (row?.kind === 'PREPAID_CONFIRM' || kindLabel(row) === '预缴确认') return 'amt-prepaid'
  return 'amt-pos'
}

function formatPaidAt(v: unknown) {
  if (v == null || v === '') return '—'
  const s = String(v).replace('T', ' ')
  return s.length >= 19 ? s.slice(0, 19) : s
}

function remarkText(row: any) {
  const raw = String(row?.remark || '').trim()
  if (!raw) return '—'
  if (row?.kind === 'CREDIT' || kindLabel(row) === '冲红') {
    return raw.replace(/^冲红[:：]\s*/, '') || '—'
  }
  return raw
}

function paymentSummary({ columns, data }: { columns: any[]; data: any[] }) {
  const sums: string[] = []
  columns.forEach((col, index) => {
    if (index === 0) {
      sums[index] = '合计'
      return
    }
    if (col.property === 'amount' || col.label === '金额') {
      const total = data.reduce((acc, row) => acc + Number(row?.amount ?? 0), 0)
      sums[index] = formatMoney(Math.round(total * 100) / 100)
      return
    }
    sums[index] = ''
  })
  return sums
}

const prepaidIncome = computed(() => Number(summary.value?.prepaidIncome ?? 0))
const paymentIncome = computed(() => Number(summary.value?.paymentIncome ?? 0))
const entryIncome = computed(() => Number(summary.value?.entryIncome ?? 0))
const ownerIncomeTotal = computed(() => Math.round(paymentIncome.value * 100) / 100)
const ownerIncomeTotalText = computed(() => formatMoney(ownerIncomeTotal.value))
const entryIncomeText = computed(() => formatMoney(entryIncome.value))
const balanceNum = computed(() => Number(summary.value?.balance ?? 0))

/** 业主缴交：费项柱状（不含其他经营收入） */
const ownerFeeBreakdown = computed(() => {
  return (summary.value?.incomeBreakdown || [])
    .filter((b: any) => b?.feeCategory !== 'PREPAID_INCOME' && b?.feeCategory !== 'ENTRY_INCOME')
    .map((b: any) => {
      const prepaid = Number(b?.prepaidAmount ?? 0)
      const amount = Number(b?.amount ?? 0)
      const paid = b?.paidAmount != null
        ? Number(b.paidAmount)
        : Math.round((amount - prepaid) * 100) / 100
      return {
        ...b,
        paid,
        prepaid,
        amount: Math.round((paid + prepaid) * 100) / 100,
      }
    })
})
const ownerFeeBars = computed(() => {
  const total = ownerIncomeTotal.value > 0 ? ownerIncomeTotal.value : 1
  return ownerFeeBreakdown.value.map((b: any) => {
    const paid = Number(b.paid ?? 0)
    const prepaid = Number(b.prepaid ?? 0)
    const amt = Number(b.amount ?? 0)
    return {
      ...b,
      labelText: b.label || feeLabel(b.feeCategory),
      amountText: formatMoney(amt),
      paidPct: Math.min(100, Math.round((Math.abs(paid) / total) * 1000) / 10),
      prepaidPct: Math.min(100, Math.round((Math.abs(prepaid) / total) * 1000) / 10),
      showPaid: paid !== 0,
      showPrepaid: prepaid !== 0,
    }
  })
})

const otherIncomeEntries = computed(() => summary.value?.otherIncomeEntries || [])
const otherIncomeTotalText = computed(() => {
  const sum = otherIncomeEntries.value.reduce((acc: number, b: any) => acc + Number(b?.amount ?? 0), 0)
  return formatMoney(Math.round(sum * 100) / 100)
})

const expenseBreakdown = computed(() => {
  return (summary.value?.expenseBreakdown || []).map((b: any) => ({
    ...b,
    amount: Number(b?.amount ?? 0),
    labelText: b.label || b.title || '支出',
  }))
})
const expenseBreakdownTotal = computed(() => {
  const sum = expenseBreakdown.value.reduce((acc: number, b: any) => acc + Number(b?.amount ?? 0), 0)
  return Math.round(sum * 100) / 100
})
const expenseBreakdownTotalText = computed(() => formatMoney(expenseBreakdownTotal.value))
const expenseBars = computed(() => {
  const total = expenseBreakdownTotal.value > 0 ? expenseBreakdownTotal.value : 1
  return expenseBreakdown.value.map((b: any) => {
    const amt = Number(b.amount ?? 0)
    return {
      ...b,
      amountText: formatMoney(amt),
      pct: Math.min(100, Math.round((Math.abs(amt) / total) * 1000) / 10),
    }
  })
})

const showIncomeBlock = computed(() =>
  ownerFeeBars.value.length > 0
  || (summary.value?.paymentDetails?.length ?? 0) > 0
  || otherIncomeEntries.value.length > 0
  || entryIncome.value !== 0
)
const form = ref({
  amountText: '',
  occurDate: `${_ym}-01`,
  category: 'OTHER',
  title: '',
  remark: '',
})
const pendingFiles = ref<{ id: number; fileName: string }[]>([])
const uploading = ref(false)

const amountHint = computed(() => {
  const n = Number(form.value.amountText)
  if (!form.value.amountText || Number.isNaN(n) || n === 0) return '正数记收入 · 负数记支出'
  return n < 0 ? '将记为支出' : '将记为收入'
})

function monthStart(ym: string) {
  return `${ym}-01`
}

function monthEnd(ym: string) {
  const [y, m] = ym.split('-').map(Number)
  const last = new Date(y, m, 0).getDate()
  return `${ym}-${String(last).padStart(2, '0')}`
}

const periodLabel = computed(() => {
  if (periodMode.value === 'month') return singleMonth.value || ''
  if (!fromMonth.value || !toMonth.value) return ''
  if (fromMonth.value === toMonth.value) return fromMonth.value
  return `${fromMonth.value} ～ ${toMonth.value}`
})

function queryFrom() {
  return periodMode.value === 'month' ? singleMonth.value : fromMonth.value
}

function queryTo() {
  return periodMode.value === 'month' ? singleMonth.value : toMonth.value
}

function syncFormOccurDate() {
  const from = queryFrom()
  if (from) form.value.occurDate = monthStart(from)
}

function onPeriodQuery() {
  syncFormOccurDate()
  load()
}

function onPeriodModeChange() {
  if (periodMode.value === 'month') {
    singleMonth.value = toMonth.value || fromMonth.value || singleMonth.value
  } else {
    fromMonth.value = singleMonth.value || fromMonth.value
    toMonth.value = singleMonth.value || toMonth.value
  }
  onPeriodQuery()
}

async function loadLists() {
  const from = queryFrom()
  const to = queryTo()
  const params: Record<string, string | number> = { page: 1, pageSize: 100 }
  if (from) params.from = monthStart(from)
  if (to) params.to = monthEnd(to)
  const { data } = await api.get('/staff/finance/entries', { params })
  if (data.code === 0) entries.value = data.data?.list || []
  else {
    ElMessage.error(data.message)
    if (data.code === 40101 || data.code === 40301) router.push(authFailRedirectPath())
  }
}

async function loadSummary() {
  const from = queryFrom()
  const to = queryTo()
  if (!from || !to) {
    ElMessage.warning(periodMode.value === 'month' ? '请选择月份' : '请选择汇总起止月份')
    return
  }
  if (from > to) {
    ElMessage.warning('起始月份不能晚于结束月份')
    return
  }
  summaryLoading.value = true
  try {
    const { data } = await api.get('/staff/finance/summary', {
      params: { from: monthStart(from), to: monthEnd(to), viewMode: viewMode.value },
    })
    if (data.code === 0) summary.value = data.data
    else ElMessage.error(data.message)
  } finally {
    summaryLoading.value = false
  }
}

async function loadRevenue() {
  const { data } = await api.get('/staff/public-revenue/items', { params: { page: 1, pageSize: 100 } })
  if (data.code === 0) revenueItems.value = data.data?.list || []
  else {
    ElMessage.error(data.message)
    if (data.code === 40101 || data.code === 40301) router.push(authFailRedirectPath())
  }
}

async function createRevenue() {
  const raw = Number(revenueForm.value.amountText)
  if (!revenueForm.value.title.trim()) {
    ElMessage.warning('请填写标题')
    return
  }
  if (!revenueForm.value.amountText.trim() || Number.isNaN(raw) || raw <= 0) {
    ElMessage.warning('金额须为正数')
    return
  }
  revenueSaving.value = true
  try {
    const { data } = await api.post('/staff/public-revenue/items', {
      title: revenueForm.value.title.trim(),
      amount: Math.round(raw * 100) / 100,
      occurMonth: revenueForm.value.occurMonth || null,
      remark: (revenueForm.value.remark || '').trim() || null,
    })
    if (data.code === 0) {
      ElMessage.success('已提交并直接公示')
      revenueForm.value.title = ''
      revenueForm.value.amountText = ''
      revenueForm.value.remark = ''
      await loadRevenue()
    } else ElMessage.error(data.message)
  } finally {
    revenueSaving.value = false
  }
}

async function removeRevenue(row: any) {
  try {
    await ElMessageBox.confirm(`确定删除「${row.title}」？删除后业主端将不再展示。`, '删除公共收益', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  const { data } = await api.delete(`/staff/public-revenue/items/${row.id}`)
  if (data.code === 0) {
    ElMessage.success('已删除')
    loadRevenue()
  } else ElMessage.error(data.message)
}

async function loadReconcile() {
  reconcileLoading.value = true
  try {
    const { data } = await api.get('/staff/pay/reconcile', { params: { page: 1, pageSize: 50 } })
    if (data.code === 0) reconcileRows.value = data.data?.list || []
    else ElMessage.error(data.message || '加载对账失败')
  } finally {
    reconcileLoading.value = false
  }
}

async function runReconcile() {
  reconcileRunning.value = true
  try {
    const { data } = await api.post('/staff/pay/reconcile/run', {
      date: reconcileDate.value,
      channel: reconcileChannel.value,
    })
    if (data.code === 0) {
      ElMessage.success(data.data?.status === 'MATCHED' ? '对账一致' : '对账存在差额，请核对')
      await loadReconcile()
    } else ElMessage.error(data.message || '对账失败')
  } finally {
    reconcileRunning.value = false
  }
}

async function load() {
  if (activeTab.value === 'revenue') {
    await loadRevenue()
    return
  }
  if (activeTab.value === 'reconcile') {
    await loadReconcile()
    return
  }
  await Promise.all([loadLists(), loadSummary()])
}

async function onEntryFile(file: File) {
  if (pendingFiles.value.length >= 3) {
    ElMessage.warning('最多 3 个附件')
    return false
  }
  uploading.value = true
  try {
    const body = new FormData()
    body.append('file', file)
    body.append('bizType', 'TEMP')
    const { data } = await api.post('/attachments/upload', body, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    if (data.code === 0 && data.data?.id) {
      pendingFiles.value.push({ id: data.data.id, fileName: data.data.fileName || file.name })
      ElMessage.success('附件已上传')
    } else ElMessage.error(data.message || '上传失败')
  } catch {
    ElMessage.error('上传失败')
  } finally {
    uploading.value = false
  }
  return false
}

function removePendingFile(id: number) {
  pendingFiles.value = pendingFiles.value.filter((f) => f.id !== id)
  api.delete(`/attachments/${id}`).catch(() => {})
}

async function openAttachment(id: number, fileName?: string) {
  try {
    const res = await api.get(`/attachments/${id}`, { responseType: 'blob' })
    const url = URL.createObjectURL(res.data)
    const a = document.createElement('a')
    a.href = url
    a.target = '_blank'
    a.rel = 'noopener'
    if (fileName) a.download = fileName
    a.click()
    setTimeout(() => URL.revokeObjectURL(url), 30_000)
  } catch {
    ElMessage.error('打开附件失败')
  }
}

async function createEntry() {
  const raw = Number(form.value.amountText)
  if (!form.value.amountText.trim() || Number.isNaN(raw) || raw === 0) {
    ElMessage.warning('请填写非零金额（正数收入，负数支出）')
    return
  }
  if (!form.value.title.trim()) {
    ElMessage.warning('请填写标题')
    return
  }
  if (!form.value.occurDate) {
    ElMessage.warning('请选择发生日')
    return
  }
  if ((form.value.remark || '').length > 200) {
    ElMessage.warning('备注最多 200 字')
    return
  }
  const payload = {
    amount: Math.round(raw * 100) / 100,
    occurDate: form.value.occurDate,
    category: form.value.category,
    title: form.value.title.trim(),
    remark: (form.value.remark || '').trim() || null,
    attachmentIds: pendingFiles.value.map((f) => f.id),
  }
  const { data } = await api.post('/staff/finance/entries', payload)
  if (data.code === 0) {
    ElMessage.success('已登记并计入公开')
    form.value.amountText = ''
    form.value.title = ''
    form.value.remark = ''
    pendingFiles.value = []
    syncFormOccurDate()
    load()
  } else ElMessage.error(data.message)
}

async function removeEntry(row: any) {
  try {
    await ElMessageBox.confirm(`确定删除「${row.title}」？删除后不再计入财务公开。`, '删除收支', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  const { data } = await api.delete(`/staff/finance/entries/${row.id}`)
  if (data.code === 0) {
    ElMessage.success('已删除')
    load()
  } else ElMessage.error(data.message)
}

onMounted(load)

watch(activeTab, () => {
  load()
})
</script>

<template>
  <div class="page">
    <header>
      <div>
        <h2>经营财务</h2>
        <p class="sub">业主可见的公开账本 · 按账单月份统计</p>
        <StaffNav />
      </div>
      <StaffSessionAction />
    </header>

    <el-tabs :model-value="activeTab" class="tabs" @tab-change="onTabChange">
      <el-tab-pane label="公开汇总" name="summary">
        <section v-if="activeTab === 'summary'" class="panel" v-loading="summaryLoading">
          <div class="panel-hd">
            <div>
              <h3>公开汇总</h3>
              <p class="panel-desc">只算已交清的账单与已登记收支，未缴费不算收入</p>
            </div>
            <div class="filters">
              <el-radio-group v-model="periodMode" size="default" @change="onPeriodModeChange">
                <el-radio-button value="month">按月</el-radio-button>
                <el-radio-button value="range">区间</el-radio-button>
              </el-radio-group>
              <template v-if="periodMode === 'month'">
                <el-date-picker
                  v-model="singleMonth"
                  type="month"
                  value-format="YYYY-MM"
                  placeholder="月份"
                  style="width:148px"
                  @change="onPeriodQuery"
                />
              </template>
              <template v-else>
                <el-date-picker
                  v-model="fromMonth"
                  type="month"
                  value-format="YYYY-MM"
                  placeholder="起"
                  style="width:132px"
                />
                <span class="dash">—</span>
                <el-date-picker
                  v-model="toMonth"
                  type="month"
                  value-format="YYYY-MM"
                  placeholder="止"
                  style="width:132px"
                />
                <el-button type="primary" @click="onPeriodQuery">查询</el-button>
              </template>
            </div>
          </div>

          <template v-if="summary">
            <div class="kpi-grid">
              <div class="kpi-card in">
                <span class="kpi-lab">本期收入</span>
                <span class="kpi-num">¥{{ formatMoney(summary.income) }}</span>
              </div>
              <div class="kpi-card out">
                <span class="kpi-lab">本期支出</span>
                <span class="kpi-num">¥{{ formatMoney(summary.expense) }}</span>
              </div>
              <div class="kpi-card bal" :class="{ neg: balanceNum < 0 }">
                <span class="kpi-lab">本期结余</span>
                <span class="kpi-num">¥{{ formatMoney(summary.balance) }}</span>
              </div>
            </div>

            <button type="button" class="how-toggle" @click="showHow = !showHow">
              {{ showHow ? '收起说明' : '怎么算的？' }}
            </button>
            <div v-if="showHow" class="how-box">
              <p>按账单月份：该月发出的账单交清即计入，不论哪天交的；未交不算。</p>
              <p>预缴按约定月份摊入实付款，优惠不计入；经营收支登记后即时计入。</p>
            </div>

            <div v-if="showIncomeBlock" class="block">
              <div class="block-hd">
                <h3 class="block-title">收入</h3>
                <span class="block-sum">合计 ¥{{ formatMoney(summary.income) }}</span>
              </div>

              <div
                v-if="ownerFeeBars.length || summary.paymentDetails?.length"
                class="section nested"
              >
                <div class="section-hd">
                  <h4>业主缴交</h4>
                  <span class="section-sum">合计 ¥{{ ownerIncomeTotalText }}</span>
                </div>
                <div v-if="ownerFeeBars.length" class="bar-list">
                  <div v-for="(b, i) in ownerFeeBars" :key="i" class="bar-row">
                    <div class="bar-meta">
                      <span>{{ b.labelText }}</span>
                      <span class="bar-amt">¥{{ b.amountText }}</span>
                    </div>
                    <div class="bar-track">
                      <div
                        v-if="b.showPaid"
                        class="bar-fill paid"
                        :style="{ width: b.paidPct + '%' }"
                      />
                      <div
                        v-if="b.showPrepaid"
                        class="bar-fill prepaid"
                        :style="{ width: b.prepaidPct + '%' }"
                      />
                    </div>
                  </div>
                </div>

                <div v-if="summary.paymentDetails?.length" class="sub-section">
                  <div class="section-hd">
                    <h4>缴费明细</h4>
                    <span class="section-sum">{{ summary.paymentDetails.length }} 笔</span>
                  </div>
                  <p class="section-tip">含账单收款与预缴按账期确收；优惠差额不计入</p>
                  <el-table
                    :data="summary.paymentDetails"
                    size="small"
                    max-height="320"
                    show-summary
                    :summary-method="paymentSummary"
                    class="pay-table"
                  >
                    <el-table-column label="类型" width="96">
                      <template #default="{ row }">
                        <span :class="kindClass(row)">{{ kindLabel(row) }}</span>
                      </template>
                    </el-table-column>
                    <el-table-column label="房屋" min-width="150" show-overflow-tooltip>
                      <template #default="{ row }">{{ row.roomLabel || row.roomNo || '—' }}</template>
                    </el-table-column>
                    <el-table-column label="费用" min-width="140" show-overflow-tooltip>
                      <template #default="{ row }">
                        {{ row.feeTypeLabel || (row.feeCategories || []).map(feeLabel).join('、') || '—' }}
                      </template>
                    </el-table-column>
                    <el-table-column label="账期" width="96">
                      <template #default="{ row }">{{ row.billMonth || '—' }}</template>
                    </el-table-column>
                    <el-table-column label="金额" width="100" prop="amount" align="right">
                      <template #default="{ row }">
                        <span :class="Number(row.amount) < 0 ? 'amt-neg' : ''">{{ formatMoney(row.amount) }}</span>
                      </template>
                    </el-table-column>
                    <el-table-column label="方式" width="100">
                      <template #default="{ row }">{{ channelLabel(row.payChannel) }}</template>
                    </el-table-column>
                    <el-table-column label="时间" min-width="150">
                      <template #default="{ row }">{{ formatPaidAt(row.paidAt) }}</template>
                    </el-table-column>
                    <el-table-column label="说明" min-width="110" show-overflow-tooltip>
                      <template #default="{ row }">{{ remarkText(row) }}</template>
                    </el-table-column>
                  </el-table>
                </div>
              </div>

              <div v-if="otherIncomeEntries.length" class="section nested">
                <div class="section-hd">
                  <h4>其他经营收入</h4>
                  <span class="section-sum">合计 ¥{{ otherIncomeTotalText }}</span>
                </div>
                <el-table :data="otherIncomeEntries" size="small" class="entry-table">
                  <el-table-column prop="title" label="标题" min-width="140" show-overflow-tooltip />
                  <el-table-column label="金额" width="110" align="right">
                    <template #default="{ row }">
                      <span class="amt-pos">{{ formatMoney(row.amount) }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column prop="occurDate" label="发生日" width="110" />
                  <el-table-column label="备注" min-width="140" show-overflow-tooltip>
                    <template #default="{ row }">{{ row.remark || '—' }}</template>
                  </el-table-column>
                  <el-table-column label="附件" min-width="120">
                    <template #default="{ row }">
                      <template v-if="row.attachments?.length">
                        <button
                          v-for="a in row.attachments"
                          :key="a.id"
                          type="button"
                          class="att-link"
                          @click="openAttachment(a.id, a.fileName)"
                        >{{ a.fileName || '附件' }}</button>
                      </template>
                      <span v-else>—</span>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </div>

            <div v-if="expenseBars.length" class="block">
              <div class="block-hd">
                <h3 class="block-title">支出</h3>
                <span class="block-sum out">合计 ¥{{ expenseBreakdownTotalText }}</span>
              </div>
              <div class="section nested">
                <div class="bar-list">
                  <div v-for="(b, i) in expenseBars" :key="i" class="bar-row">
                    <div class="bar-meta">
                      <span>{{ b.labelText }}</span>
                      <span class="bar-amt out">¥{{ b.amountText }}</span>
                    </div>
                    <div class="bar-track">
                      <div class="bar-fill expense" :style="{ width: b.pct + '%' }" />
                    </div>
                    <div v-if="b.remark" class="bar-remark">{{ b.remark }}</div>
                  </div>
                </div>
              </div>
            </div>
          </template>
          <p v-else class="empty-hint">选择期间后加载汇总</p>
        </section>
      </el-tab-pane>

      <el-tab-pane label="登记收支" name="entries">
        <section v-if="activeTab === 'entries'" class="panel">
          <div class="panel-hd">
            <div>
              <h3>登记收支</h3>
              <p class="panel-desc">正数收入 · 负数支出 · 登记即时公开 · 可删除</p>
            </div>
            <div class="filters">
              <el-radio-group v-model="periodMode" size="default" @change="onPeriodModeChange">
                <el-radio-button value="month">按月</el-radio-button>
                <el-radio-button value="range">区间</el-radio-button>
              </el-radio-group>
              <template v-if="periodMode === 'month'">
                <el-date-picker
                  v-model="singleMonth"
                  type="month"
                  value-format="YYYY-MM"
                  placeholder="月份"
                  style="width:148px"
                  @change="onPeriodQuery"
                />
              </template>
              <template v-else>
                <el-date-picker
                  v-model="fromMonth"
                  type="month"
                  value-format="YYYY-MM"
                  placeholder="起"
                  style="width:132px"
                />
                <span class="dash">—</span>
                <el-date-picker
                  v-model="toMonth"
                  type="month"
                  value-format="YYYY-MM"
                  placeholder="止"
                  style="width:132px"
                />
                <el-button type="primary" @click="onPeriodQuery">查询</el-button>
              </template>
              <span v-if="periodLabel" class="period-tag">{{ periodLabel }}</span>
            </div>
          </div>
          <div class="entry-form">
            <el-input
              v-model="form.amountText"
              placeholder="金额，如 200 或 -150"
              style="width:168px"
              clearable
            />
            <span class="amount-hint" :class="{ out: Number(form.amountText) < 0 }">{{ amountHint }}</span>
            <el-date-picker
              v-model="form.occurDate"
              type="date"
              value-format="YYYY-MM-DD"
              placeholder="发生日"
              style="width:150px"
            />
            <el-input v-model="form.title" placeholder="标题，如：电梯维修" style="width:220px" clearable />
            <el-upload
              :show-file-list="false"
              :before-upload="onEntryFile"
              accept=".jpg,.jpeg,.png,.pdf,image/jpeg,image/png,application/pdf"
              :disabled="uploading || pendingFiles.length >= 3"
            >
              <el-button :loading="uploading">上传附件</el-button>
            </el-upload>
            <el-button type="primary" @click="createEntry">登记</el-button>
          </div>
          <div class="entry-extra">
            <el-input
              v-model="form.remark"
              type="textarea"
              :rows="2"
              maxlength="200"
              show-word-limit
              placeholder="备注（选填，最多 200 字）"
            />
            <div v-if="pendingFiles.length" class="pending-files">
              <span v-for="f in pendingFiles" :key="f.id" class="file-chip">
                {{ f.fileName }}
                <button type="button" class="file-x" @click="removePendingFile(f.id)">×</button>
              </span>
            </div>
          </div>
          <el-table :data="entries" size="small" class="entry-table">
            <el-table-column type="index" label="#" width="50" :index="(i: number) => i + 1" />
            <el-table-column label="类型" width="80">
              <template #default="{ row }">
                <span :class="row.entryType === 'EXPENSE' ? 'tag-out' : 'tag-in'">
                  {{ entryTypeLabel(row.entryType) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="title" label="标题" min-width="140" show-overflow-tooltip />
            <el-table-column label="金额" width="110" align="right">
              <template #default="{ row }">
                <span :class="row.entryType === 'EXPENSE' ? 'amt-neg' : 'amt-pos'">
                  {{ row.entryType === 'EXPENSE' ? '-' : '' }}{{ formatMoney(row.amount) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="occurDate" label="发生日" width="110" />
            <el-table-column label="备注" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ row.remark || '—' }}</template>
            </el-table-column>
            <el-table-column label="附件" min-width="120">
              <template #default="{ row }">
                <template v-if="row.attachments?.length">
                  <button
                    v-for="a in row.attachments"
                    :key="a.id"
                    type="button"
                    class="att-link"
                    @click="openAttachment(a.id, a.fileName)"
                  >{{ a.fileName || '附件' }}</button>
                </template>
                <span v-else>—</span>
              </template>
            </el-table-column>
            <el-table-column label="" width="80" align="right">
              <template #default="{ row }">
                <el-button link type="danger" @click="removeEntry(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </el-tab-pane>

      <el-tab-pane label="公共收益" name="revenue">
        <section v-if="activeTab === 'revenue'" class="panel">
          <div class="panel-hd">
            <div>
              <h3>公共收益</h3>
              <p class="panel-desc">提交后直接公示 · 默认不计入上方经营公开余额三数</p>
            </div>
          </div>
          <div class="entry-form revenue-form">
            <el-input v-model="revenueForm.title" placeholder="标题，如广告位租金" maxlength="64" style="max-width:220px" />
            <el-input v-model="revenueForm.amountText" placeholder="金额（正数）" style="width:120px" />
            <el-date-picker
              v-model="revenueForm.occurMonth"
              type="month"
              value-format="YYYY-MM"
              placeholder="账期"
              style="width:140px"
            />
            <el-input v-model="revenueForm.remark" placeholder="备注（可选）" maxlength="200" style="max-width:220px" />
            <el-button type="primary" :loading="revenueSaving" @click="createRevenue">提交公示</el-button>
          </div>
          <el-table :data="revenueItems" size="small" class="entry-table">
            <el-table-column type="index" label="#" width="50" :index="(i: number) => i + 1" />
            <el-table-column prop="title" label="标题" min-width="140" show-overflow-tooltip />
            <el-table-column label="金额" width="110" align="right">
              <template #default="{ row }">{{ formatMoney(row.amount) }}</template>
            </el-table-column>
            <el-table-column prop="occurMonth" label="账期" width="100" />
            <el-table-column prop="status" label="状态" width="100">
              <template #default="{ row }">{{ row.status === 'PUBLISHED' ? '已公示' : row.status }}</template>
            </el-table-column>
            <el-table-column label="备注" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ row.remark || '—' }}</template>
            </el-table-column>
            <el-table-column label="" width="80" align="right">
              <template #default="{ row }">
                <el-button link type="danger" @click="removeRevenue(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </el-tab-pane>

      <el-tab-pane label="支付对账" name="reconcile">
        <section v-if="activeTab === 'reconcile'" class="panel" v-loading="reconcileLoading">
          <div class="panel-hd">
            <div>
              <h3>线上支付对账</h3>
              <p class="panel-desc">对比当日微信/支付宝入账与支付单净额；冲红走原路退后净额应一致</p>
            </div>
          </div>
          <div class="row wrap" style="gap:10px;margin-bottom:14px;align-items:center">
            <el-date-picker v-model="reconcileDate" type="date" value-format="YYYY-MM-DD" placeholder="对账日" style="width:160px" />
            <el-select v-model="reconcileChannel" style="width:140px">
              <el-option label="全部渠道" value="ALL" />
              <el-option label="微信" value="WECHAT" />
              <el-option label="支付宝" value="ALIPAY" />
            </el-select>
            <el-button type="primary" :loading="reconcileRunning" @click="runReconcile">执行对账</el-button>
            <el-button @click="loadReconcile">刷新</el-button>
          </div>
          <el-table :data="reconcileRows" size="small">
            <el-table-column prop="reconcileDate" label="日期" width="120" />
            <el-table-column prop="channel" label="渠道" width="100" />
            <el-table-column label="平台笔数" width="100">
              <template #default="{ row }">{{ row.platformCount }}</template>
            </el-table-column>
            <el-table-column label="平台金额" width="120">
              <template #default="{ row }">{{ formatMoney(row.platformAmount) }}</template>
            </el-table-column>
            <el-table-column label="渠道笔数" width="100">
              <template #default="{ row }">{{ row.channelCount }}</template>
            </el-table-column>
            <el-table-column label="渠道金额" width="120">
              <template #default="{ row }">{{ formatMoney(row.channelAmount) }}</template>
            </el-table-column>
            <el-table-column label="差额" width="120">
              <template #default="{ row }">{{ formatMoney(row.diffAmount) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-tag :type="row.status === 'MATCHED' ? 'success' : 'warning'" size="small">
                  {{ row.status === 'MATCHED' ? '一致' : '差异' }}
                </el-tag>
              </template>
            </el-table-column>
          </el-table>
          <p v-if="!reconcileRows.length" class="hint" style="margin-top:12px">暂无对账记录，选择日期后执行对账。</p>
        </section>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.page {
  --ink: #1c2b26;
  --muted: #6a7a74;
  --line: #e6ece9;
  --brand: #0f3d34;
  --warm: #a34b2c;
  --bg: #f3f6f4;
  padding: 24px 32px 48px;
  max-width: 1500px;
  margin: 0 auto;
}
header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  align-items: flex-start;
  margin-bottom: 8px;
}
h2 { margin: 0; color: var(--ink); font-size: 22px; letter-spacing: -0.02em; }
.sub { margin: 6px 0 10px; color: var(--muted); font-size: 13px; }
.tabs { margin-top: 12px; }

.panel {
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 20px 22px 22px;
  margin-bottom: 16px;
}
.panel-hd {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 18px;
}
.panel-hd h3 { margin: 0; font-size: 16px; color: var(--ink); }
.panel-desc { margin: 4px 0 0; font-size: 12px; color: var(--muted); }
.filters { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.dash { color: var(--muted); }

.kpi-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}
.kpi-card {
  border-radius: 12px;
  padding: 16px 18px;
  background: #f7faf8;
  border: 1px solid var(--line);
}
.kpi-card.in { background: linear-gradient(180deg, #eef8f3 0%, #f7faf8 100%); }
.kpi-card.out { background: linear-gradient(180deg, #fbf3ef 0%, #faf7f5 100%); }
.kpi-card.bal { background: linear-gradient(180deg, #eef3f1 0%, #f5f7f6 100%); }
.kpi-card.bal.neg { background: linear-gradient(180deg, #fbf0ed 0%, #faf6f5 100%); }
.kpi-lab { display: block; font-size: 12px; color: var(--muted); margin-bottom: 8px; }
.kpi-num {
  display: block;
  font-size: 26px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.03em;
  color: var(--ink);
  line-height: 1.1;
}
.kpi-card.in .kpi-num { color: var(--brand); }
.kpi-card.out .kpi-num { color: var(--warm); }
.kpi-card.bal.neg .kpi-num { color: var(--warm); }

.block {
  margin-top: 22px;
  padding-top: 18px;
  border-top: 1px solid var(--line);
}
.block-hd {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 8px;
}
.block-title {
  margin: 0;
  font-size: 16px;
  color: var(--ink);
  font-weight: 700;
}
.block-sum {
  font-size: 14px;
  font-weight: 700;
  color: var(--ink);
  font-variant-numeric: tabular-nums;
}
.block-sum.out { color: var(--warm); }

.how-toggle {
  margin-top: 12px;
  border: none;
  background: none;
  padding: 0;
  color: var(--brand);
  font-size: 12px;
  cursor: pointer;
}
.how-box {
  margin-top: 8px;
  padding: 12px 14px;
  background: var(--bg);
  border-radius: 10px;
  font-size: 12px;
  color: #445;
  line-height: 1.6;
}
.how-box p { margin: 0 0 4px; }
.how-box p:last-child { margin: 0; }

.section { margin-top: 22px; padding-top: 18px; border-top: 1px solid var(--line); }
.section.nested {
  margin-top: 14px;
  padding: 14px 14px 12px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fbfcfc;
}
.sub-section {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px dashed var(--line);
}
.bar-remark {
  margin-top: 6px;
  font-size: 12px;
  color: var(--muted);
}
.section-hd {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 12px;
}
.section-hd h4 { margin: 0; font-size: 14px; color: var(--ink); }
.section-sum { font-size: 13px; font-weight: 600; color: var(--ink); font-variant-numeric: tabular-nums; }
.section-tip { margin: -4px 0 10px; font-size: 12px; color: var(--muted); }

.bar-list { display: flex; flex-direction: column; gap: 12px; }
.bar-meta {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  color: var(--ink);
  margin-bottom: 6px;
}
.bar-amt { font-variant-numeric: tabular-nums; color: #2a4a3c; }
.bar-amt.out { color: var(--warm); }
.bar-track {
  height: 8px;
  border-radius: 999px;
  background: #eef2f0;
  overflow: hidden;
  display: flex;
  flex-direction: row;
  align-items: stretch;
}
.bar-fill {
  height: 100%;
  min-width: 2px;
  flex-shrink: 0;
}
.bar-fill.paid {
  background: #0f3d34;
}
.bar-fill.prepaid {
  background: #3b6fd4;
}
.bar-fill.entry {
  background: #6b7c85;
}
.bar-fill.expense {
  background: #a34b2c;
}

.pay-table { width: 100%; }
.amt-pos { color: var(--brand); }
.amt-prepaid { color: #3b6fd4; font-weight: 600; }
.amt-neg { color: var(--warm); font-weight: 600; }
.empty-hint { margin: 8px 0 0; color: #99a; font-size: 13px; }

.period-tag {
  font-size: 12px;
  color: var(--brand);
  background: #e8f3ee;
  padding: 4px 10px;
  border-radius: 999px;
}
.entry-form {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 10px;
}
.amount-hint {
  font-size: 12px;
  color: var(--brand);
  min-width: 7em;
}
.amount-hint.out { color: var(--warm); }
.entry-extra {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 14px;
  max-width: 720px;
}
.pending-files {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.file-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #2a4a3c;
  background: #eef2f0;
  padding: 4px 8px;
  border-radius: 6px;
}
.file-x {
  border: none;
  background: transparent;
  cursor: pointer;
  color: #888;
  font-size: 14px;
  line-height: 1;
  padding: 0;
}
.att-link {
  display: inline-block;
  margin-right: 8px;
  color: #3b6fd4;
  font-size: 12px;
  text-decoration: none;
  border: none;
  background: none;
  padding: 0;
  cursor: pointer;
}
.att-link:hover { text-decoration: underline; }
.entry-table { width: 100%; }
.tag-in, .tag-out {
  display: inline-block;
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 6px;
}
.tag-in { color: var(--brand); background: #e8f3ee; }
.tag-out { color: var(--warm); background: #f8ebe6; }

@media (max-width: 720px) {
  .kpi-grid { grid-template-columns: 1fr; }
  .kpi-num { font-size: 22px; }
}
</style>
