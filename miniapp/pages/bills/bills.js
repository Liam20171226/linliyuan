const { request } = require('../../utils/request')
const { billStatusLabel, formatMoney, formatDateTime } = require('../../utils/format')

const FEE_LABEL = {
  PROPERTY_FEE: '物业管理费',
  PARKING_MGMT: '车位管理费',
  PARKING_MONTHLY: '车辆月保费',
  SHARED: '公摊费',
  GARBAGE: '垃圾费',
  WATER: '代收水费',
  ELECTRIC: '代收电费',
  GAS: '代收煤气费',
  OTHER: '其他'
}

const PLAN_STATUS = {
  ACTIVE: '生效中',
  VOID: '已作废',
  DRAFT: '草稿'
}

Page({
  data: {
    mode: 'unpaid',
    roomGroups: [],
    loading: true,
    selectedIds: [],
    unpaidCount: 0,
    selectedAmountText: '0.00',
    allSelected: false
  },

  onLoad(query) {
    const mode = query && query.mode === 'history' ? 'history' : 'unpaid'
    this.setData({ mode })
    wx.setNavigationBarTitle({
      title: mode === 'history' ? '缴费记录' : '我的账单'
    })
  },

  onShow() { this.load() },
  onPullDownRefresh() {
    this.load().finally(() => wx.stopPullDownRefresh())
  },

  async load() {
    this.setData({ loading: true, selectedIds: [], selectedAmountText: '0.00', allSelected: false })
    try {
      if (this.data.mode === 'history') {
        await this.loadHistory()
      } else {
        await this.loadUnpaid()
      }
    } catch (e) {
      this.setData({ roomGroups: [], unpaidCount: 0 })
      wx.showToast({ title: (e && e.message) || '请先切换住户身份', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  async loadUnpaid() {
    const data = await request({
      url: '/resident/bills',
      data: { page: 1, pageSize: 100, status: 'UNPAID' }
    })
    const list = ((data && data.list) || []).map((b) => this.normalizeBill(b))
    const roomGroups = this.groupByRoom(list, [])
    this.setData({ roomGroups, unpaidCount: list.length })
  },

  async loadHistory() {
    const [billsData, prepaidData] = await Promise.all([
      request({ url: '/resident/bills', data: { page: 1, pageSize: 100, status: 'PAID' } }),
      request({ url: '/resident/prepaid/plans' }).catch(() => ({ plans: [] }))
    ])
    const list = ((billsData && billsData.list) || []).map((b) => this.normalizeBill(b))
    const plans = ((prepaidData && prepaidData.plans) || []).map((p) => this.normalizePlan(p))
    // 若账单房间标签更完整，用账单侧房间名覆盖预缴 roomLabel
    const roomLabelById = {}
    list.forEach((b) => {
      if (b.roomId != null && b.roomLabel) roomLabelById[b.roomId] = b.roomLabel
    })
    plans.forEach((p) => {
      if (roomLabelById[p.roomId]) p.roomLabel = roomLabelById[p.roomId]
    })
    const roomGroups = this.groupByRoom(list, plans)
    this.setData({ roomGroups, unpaidCount: 0 })
  },

  normalizeBill(b) {
    const lines = (b.lines || []).map((l) => ({
      id: l.id,
      title: l.title || l.feeCategory || '费用',
      amountText: formatMoney(l.amount)
    }))
    const payable = b.status === 'PUBLISHED' || b.status === 'OVERDUE'
    return Object.assign({}, b, {
      statusLabel: billStatusLabel(b.status),
      totalAmountText: formatMoney(b.totalAmount),
      roomLabel: b.roomLabel || b.roomNo || ('房#' + b.roomId),
      lines,
      feeSummary: lines.length
        ? lines.map((l) => l.title + ' ¥' + l.amountText).join('、')
        : (b.lineSummary || '明细加载中'),
      payable,
      paidAtText: formatDateTime(b.paidAt) || '—',
      checked: false
    })
  },

  normalizePlan(p) {
    const months = p.billMonths || []
    const cats = p.feeCategories || []
    return {
      id: p.id,
      roomId: p.roomId,
      roomLabel: p.roomLabel || p.roomNo || ('房#' + p.roomId),
      status: p.status,
      statusLabel: PLAN_STATUS[p.status] || p.status,
      cashAmount: Number(p.cashAmount || 0),
      cashAmountText: formatMoney(p.cashAmount),
      listAmountText: formatMoney(p.listAmount),
      discountAmountText: formatMoney(p.discountAmount),
      monthsText: months.length ? months.join('、') : '—',
      feesText: cats.length
        ? cats.map((c) => FEE_LABEL[c] || c).join('、')
        : '—',
      paidAtText: formatDateTime(p.confirmedAt || p.createdAt) || '—'
    }
  },

  groupByRoom(bills, plans) {
    const map = new Map()
    const ensure = (roomId, roomLabel) => {
      const key = String(roomId)
      if (!map.has(key)) {
        map.set(key, {
          roomId,
          roomLabel: roomLabel || ('房#' + roomId),
          expanded: true,
          bills: [],
          prepaidPlans: [],
          total: 0
        })
      } else if (roomLabel && map.get(key).roomLabel.indexOf('房#') === 0) {
        map.get(key).roomLabel = roomLabel
      }
      return map.get(key)
    }
    bills.forEach((b) => {
      const g = ensure(b.roomId, b.roomLabel)
      g.bills.push(b)
      g.total += Number(b.totalAmount || 0)
    })
    ;(plans || []).forEach((p) => {
      const g = ensure(p.roomId, p.roomLabel)
      g.prepaidPlans.push(p)
      g.total += Number(p.cashAmount || 0)
    })
    return Array.from(map.values()).map((g) => {
      const parts = []
      if (g.bills.length) parts.push(g.bills.length + ' 笔账单')
      if (g.prepaidPlans.length) parts.push(g.prepaidPlans.length + ' 笔预缴')
      return Object.assign({}, g, {
        totalText: formatMoney(g.total),
        metaText: (parts.join(' · ') || '暂无') + ' · ¥' + formatMoney(g.total)
      })
    })
  },

  toggleRoom(e) {
    const roomId = Number(e.currentTarget.dataset.roomId)
    const roomGroups = this.data.roomGroups.map((g) => {
      if (g.roomId === roomId) return Object.assign({}, g, { expanded: !g.expanded })
      return g
    })
    this.setData({ roomGroups })
  },

  toggleSelect(e) {
    const id = Number(e.currentTarget.dataset.id)
    const roomGroups = this.data.roomGroups.map((g) => Object.assign({}, g, {
      bills: g.bills.map((b) => {
        if (b.id === id && b.payable) return Object.assign({}, b, { checked: !b.checked })
        return b
      })
    }))
    this.syncSelection(roomGroups)
  },

  onBillTap(e) {
    const id = Number(e.currentTarget.dataset.id)
    const bill = this.findBill(id)
    if (!bill || !bill.payable) return
    this.toggleSelect(e)
  },

  selectRoomAll(e) {
    const roomId = Number(e.currentTarget.dataset.roomId)
    const roomGroups = this.data.roomGroups.map((g) => {
      if (g.roomId !== roomId) return g
      const payable = g.bills.filter((b) => b.payable)
      const allOn = payable.length > 0 && payable.every((b) => b.checked)
      return Object.assign({}, g, {
        bills: g.bills.map((b) => b.payable ? Object.assign({}, b, { checked: !allOn }) : b)
      })
    })
    this.syncSelection(roomGroups)
  },

  toggleSelectAll() {
    const allOn = this.data.allSelected
    const roomGroups = this.data.roomGroups.map((g) => Object.assign({}, g, {
      bills: g.bills.map((b) => b.payable ? Object.assign({}, b, { checked: !allOn }) : b)
    }))
    this.syncSelection(roomGroups)
  },

  syncSelection(roomGroups) {
    const selected = []
    let sum = 0
    let payableCount = 0
    roomGroups.forEach((g) => {
      g.bills.forEach((b) => {
        if (b.payable) payableCount += 1
        if (b.checked) {
          selected.push(b.id)
          sum += Number(b.totalAmount || 0)
        }
      })
    })
    this.setData({
      roomGroups,
      selectedIds: selected,
      selectedAmountText: formatMoney(sum),
      allSelected: payableCount > 0 && selected.length === payableCount
    })
  },

  async batchPay() {
    const ids = this.data.selectedIds
    if (!ids.length) return
    const tip = `将支付 ${ids.length} 笔账单，合计 ¥${this.data.selectedAmountText}`
    const ok = await new Promise((resolve) => {
      wx.showModal({
        title: '确认支付',
        content: tip,
        success: (r) => resolve(!!r.confirm)
      })
    })
    if (!ok) return
    wx.showLoading({ title: '合并支付', mask: true })
    try {
      const res = await request({
        url: '/resident/bills/batch-wechat-pay',
        method: 'POST',
        data: { billIds: ids }
      })
      if (res && res.paid) {
        wx.showToast({ title: `已付${res.count}笔` })
        this.load()
        return
      }
      wx.showToast({ title: (res && res.hint) || '请逐单支付', icon: 'none' })
    } catch (err) {
      wx.showToast({ title: (err && err.message) || '合并支付失败', icon: 'none' })
    } finally {
      wx.hideLoading()
    }
  },

  async mockPay(e) {
    const id = Number(e.currentTarget.dataset.id)
    const bill = this.findBill(id)
    const room = bill ? (bill.roomLabel || '') : ''
    const fee = bill ? (bill.feeSummary || '') : ''
    const amount = bill ? bill.totalAmountText : ''
    const content = [
      room ? `房屋：${room}` : '',
      bill && bill.billMonth ? `账期：${bill.billMonth}` : '',
      fee ? `费用：${fee}` : '',
      amount ? `应付：¥${amount}` : ''
    ].filter(Boolean).join('\n')
    const ok = await new Promise((resolve) => {
      wx.showModal({
        title: '确认支付',
        content: content || '确认支付该账单？',
        success: (r) => resolve(!!r.confirm)
      })
    })
    if (!ok) return

    wx.showLoading({ title: '拉起支付', mask: true })
    try {
      const pre = await request({ url: `/resident/bills/${id}/wechat-pay`, method: 'POST', data: {} })
      if (pre.mock || !pre.paySign) {
        await request({
          url: '/pay/wechat/notify',
          method: 'POST',
          data: { prepayId: pre.prepayId },
          auth: false
        })
        wx.showToast({ title: '支付成功' })
        this.load()
        return
      }
      await new Promise((resolve, reject) => {
        wx.requestPayment({
          timeStamp: String(pre.timeStamp),
          nonceStr: pre.nonceStr,
          package: pre.package,
          signType: pre.signType || 'RSA',
          paySign: pre.paySign,
          success: resolve,
          fail: reject
        })
      })
      wx.showToast({ title: '支付完成' })
      this.load()
    } catch (err) {
      wx.showToast({ title: (err && err.message) || '支付失败', icon: 'none' })
    } finally {
      wx.hideLoading()
    }
  },

  findBill(id) {
    for (const g of this.data.roomGroups) {
      const hit = g.bills.find((b) => b.id === id)
      if (hit) return hit
    }
    return null
  }
})
