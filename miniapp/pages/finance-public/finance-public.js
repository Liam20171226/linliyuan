const { request } = require('../../utils/request')
const { formatMoney } = require('../../utils/format')

function pad(n) { return n < 10 ? '0' + n : '' + n }

function rangeFor(period, year, month) {
  if (period === 'year') {
    return {
      from: `${year}-01-01`,
      to: `${year}-12-31`,
      label: `${year}年`
    }
  }
  const from = `${year}-${pad(month)}-01`
  const last = new Date(year, month, 0).getDate()
  const to = `${year}-${pad(month)}-${pad(last)}`
  return { from, to, label: `${year}年${month}月` }
}

const CAT_LABEL = {
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
  PREPAID_INCOME: '预缴摊入'
}

const CHANNEL_LABEL = {
  WECHAT_MCH: '微信收款',
  TRANSFER: '转账/线下',
  PREPAID: '预缴',
  CASH: '现金'
}

Page({
  data: {
    period: 'month',
    year: 2026,
    month: 9,
    rangeLabel: '',
    from: '',
    to: '',
    summary: null,
    balanceNeg: false,
    ownerBars: [],
    ownerTotal: '0.00',
    showLegend: false,
    payDetails: [],
    otherIncome: [],
    otherIncomeTotal: '0.00',
    expenseBreakdown: [],
    expenseTotal: '0.00',
    showIncomeBlock: false,
    showHow: false,
    loading: true
  },

  onLoad() {
    const now = new Date()
    this.setData({ year: now.getFullYear(), month: now.getMonth() + 1 })
  },

  onShow() { this.load() },
  onPullDownRefresh() {
    this.load().finally(() => wx.stopPullDownRefresh())
  },

  setPeriod(e) {
    const period = e.currentTarget.dataset.period
    if (!period || period === this.data.period) return
    this.setData({ period })
    this.load()
  },

  toggleHow() {
    this.setData({ showHow: !this.data.showHow })
  },

  prev() {
    let { period, year, month } = this.data
    if (period === 'year') year -= 1
    else {
      month -= 1
      if (month < 1) { month = 12; year -= 1 }
    }
    this.setData({ year, month })
    this.load()
  },

  next() {
    let { period, year, month } = this.data
    if (period === 'year') year += 1
    else {
      month += 1
      if (month > 12) { month = 1; year += 1 }
    }
    this.setData({ year, month })
    this.load()
  },

  async load() {
    const { period, year, month } = this.data
    const range = rangeFor(period, year, month)
    this.setData({ loading: true, from: range.from, to: range.to, rangeLabel: range.label })
    try {
      const summary = await request({
        url: '/resident/finance/summary',
        data: { from: range.from, to: range.to, viewMode: 'BY_BILL_MONTH' }
      })
      const s = summary || { income: 0, expense: 0, balance: 0 }
      const payment = Number(s.paymentIncome || 0)
      const ownerDenom = payment > 0 ? payment : 1
      const ownerRows = ((summary && summary.incomeBreakdown) || [])
        .filter((b) => b.feeCategory !== 'PREPAID_INCOME' && b.feeCategory !== 'ENTRY_INCOME')
        .map((b) => {
          const prepaidAmt = Number(b.prepaidAmount || 0)
          const amount = Number(b.amount || 0)
          const paid = b.paidAmount != null
            ? Number(b.paidAmount)
            : Math.round((amount - prepaidAmt) * 100) / 100
          return {
            label: b.label || CAT_LABEL[b.feeCategory] || b.feeCategory,
            raw: Math.round((paid + prepaidAmt) * 100) / 100,
            paid,
            prepaid: prepaidAmt
          }
        })
      const ownerBars = ownerRows.map((b) => ({
        label: b.label,
        amount: formatMoney(b.raw),
        paidPct: Math.min(100, Math.round((Math.abs(b.paid) / ownerDenom) * 1000) / 10),
        prepaidPct: Math.min(100, Math.round((Math.abs(b.prepaid) / ownerDenom) * 1000) / 10),
        showPaid: b.paid !== 0,
        showPrepaid: b.prepaid !== 0
      }))
      const showLegend = ownerRows.some((b) => b.paid !== 0 || b.prepaid !== 0)

      const payDetails = ((summary && summary.paymentDetails) || []).map((p) => {
        const kind = p.kind || ''
        const kindLabel = p.kindLabel || (kind === 'PREPAID_CONFIRM' ? '预缴确认' : kind === 'CREDIT' ? '冲红' : '收款')
        let kindClass = 'recv'
        if (kind === 'PREPAID_CONFIRM') kindClass = 'prepaid'
        if (kind === 'CREDIT' || Number(p.amount) < 0) kindClass = 'neg'
        return {
          kindLabel,
          kindClass,
          amount: formatMoney(p.amount),
          room: p.roomLabel || p.roomNo || '—',
          fee: p.feeTypeLabel || ((p.feeCategories || []).map((c) => CAT_LABEL[c] || c).join('、')) || '—',
          billMonth: p.billMonth || '—',
          channel: CHANNEL_LABEL[p.payChannel] || p.payChannel || '—'
        }
      })

      const otherIncome = ((summary && summary.otherIncomeEntries) || []).map((e) => ({
        id: e.id,
        title: e.title || '收入',
        amount: formatMoney(e.amount),
        occurDate: e.occurDate || '—',
        remark: e.remark || ''
      }))
      const otherIncomeTotalRaw = ((summary && summary.otherIncomeEntries) || [])
        .reduce((acc, e) => acc + Number(e.amount || 0), 0)

      const expenseRows = ((summary && summary.expenseBreakdown) || []).map((b) => ({
        label: b.label || b.title || '支出',
        raw: Number(b.amount || 0),
        remark: b.remark || ''
      }))
      const expenseTotalRaw = expenseRows.reduce((acc, b) => acc + b.raw, 0)
      const expenseDenom = expenseTotalRaw > 0 ? expenseTotalRaw : 1
      const expenseBreakdown = expenseRows.map((b) => ({
        label: b.label,
        amount: formatMoney(b.raw),
        pct: Math.min(100, Math.round((Math.abs(b.raw) / expenseDenom) * 1000) / 10),
        remark: b.remark
      }))

      const showIncomeBlock = ownerBars.length > 0 || payDetails.length > 0 || otherIncome.length > 0

      this.setData({
        summary: {
          income: formatMoney(s.income),
          expense: formatMoney(s.expense),
          balance: formatMoney(s.balance)
        },
        balanceNeg: Number(s.balance || 0) < 0,
        ownerBars,
        ownerTotal: formatMoney(Math.round(payment * 100) / 100),
        showLegend,
        payDetails,
        otherIncome,
        otherIncomeTotal: formatMoney(Math.round(otherIncomeTotalRaw * 100) / 100),
        expenseBreakdown,
        expenseTotal: formatMoney(Math.round(expenseTotalRaw * 100) / 100),
        showIncomeBlock
      })
    } catch (e) {
      this.setData({
        summary: null,
        ownerBars: [],
        payDetails: [],
        otherIncome: [],
        expenseBreakdown: [],
        showIncomeBlock: false,
        showLegend: false
      })
      wx.showToast({ title: (e && e.message) || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  }
})
