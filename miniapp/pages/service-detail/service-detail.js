const { request } = require('../../utils/request')
const { repairStatusLabel, complaintStatusLabel, formatDateTime } = require('../../utils/format')

function fmt(v) {
  const s = formatDateTime(v)
  return s ? String(s).slice(0, 16) : ''
}

function tokenStr() {
  return getApp().globalData.token || wx.getStorageSync('token') || ''
}
function apiBaseStr() {
  return getApp().globalData.apiBase || ''
}

Page({
  data: {
    id: '',
    kind: 'repair',
    catKey: 'indoor',
    loading: true,
    // 报修 / 投诉统一结构（后端 service_ticket 单表，detail 均返回 order + timeline）
    order: null,
    timeline: [],
    statusLabel: '',
    timeLabel: '',
    photos: [],
    // 评价表单：是否处理完成 + 三项 1~5 评分
    isPraise: false,
    hasTimelineRate: false,
    rateResolved: 1,
    rateResponse: 0,
    rateHandling: 0,
    rateSatisfaction: 0,
    rateComment: '',
    rateSubmitting: false
  },

  onLoad(options) {
    const id = options.id
    const kind = options.kind || 'repair'
    const catKey = options.catKey || (kind === 'repair' ? 'indoor' : 'complaint')
    this.setData({ id, kind, catKey })
    wx.setNavigationBarTitle({ title: kind === 'repair' ? '报修详情' : '记录详情' })
    this.load()
  },

  async load() {
    try {
      const url = this.data.kind === 'repair'
        ? `/resident/repairs/${this.data.id}`
        : `/resident/complaints/${this.data.id}`
      const d = await request({ url })
      const order = d.order || {}
      const timeline = (d.timeline || []).map((n) => ({
        ...n,
        timeLabel: fmt(n.time),
        rating: n.rating || 0,
        ratingResolved: n.ratingResolved,
        ratingResponse: n.ratingResponse,
        ratingHandling: n.ratingHandling,
        ratingSatisfaction: n.ratingSatisfaction
      }))
      this.setData({
        order,
        timeline,
        hasTimelineRate: timeline.some((n) => n.type === 'RATE'),
        statusLabel: this.data.kind === 'repair'
          ? repairStatusLabel(order.status)
          : complaintStatusLabel(order.status),
        timeLabel: fmt(order.createdAt),
        // 表扬不参与满意度评价
        isPraise: order.category === '表扬',
        loading: false
      })
      this.loadPhotos()
    } catch (e) {
      wx.showToast({ title: '加载失败', icon: 'none' })
      this.setData({ loading: false })
    }
  },

  async loadPhotos() {
    const bizType = this.data.kind === 'repair' ? 'REPAIR' : 'COMPLAINT'
    try {
      const list = await request({
        url: '/attachments',
        data: { bizType, bizId: Number(this.data.id) }
      })
      const token = encodeURIComponent(tokenStr())
      const apiBase = apiBaseStr()
      const photos = (list || []).map((a) => ({
        id: a.id,
        src: `${apiBase}/attachments/${a.id}?token=${token}`
      }))
      this.setData({ photos })
    } catch (e) {
      this.setData({ photos: [] })
    }
  },

  previewPhoto(e) {
    const url = e.currentTarget.dataset.url
    const urls = (this.data.photos || []).map((p) => p.src)
    if (!urls.length) return
    wx.previewImage({ current: url || urls[0], urls })
  },

  // ===== 评价 =====

  setResolved(e) {
    this.setData({ rateResolved: Number(e.currentTarget.dataset.v) })
  },

  onStar(e) {
    const k = e.currentTarget.dataset.k
    const v = Number(e.currentTarget.dataset.v)
    if (!k) return
    this.setData({ [k]: v })
  },

  onRateComment(e) {
    this.setData({ rateComment: e.detail.value })
  },

  async submitRate() {
    const d = this.data
    if (d.rateSubmitting) return
    if (d.isPraise) {
      wx.showToast({ title: '表扬不参与满意度评价', icon: 'none' })
      return
    }
    if (!d.rateResponse || !d.rateHandling || !d.rateSatisfaction) {
      wx.showToast({ title: '请完成三项评分', icon: 'none' })
      return
    }
    this.setData({ rateSubmitting: true })
    try {
      const rateUrl = d.kind === 'repair'
        ? `/resident/repairs/${d.id}/rate`
        : `/resident/complaints/${d.id}/rate`
      await request({
        url: rateUrl,
        method: 'POST',
        data: {
          rating: d.rateSatisfaction,
          comment: String(d.rateComment || '').trim() || null,
          resolved: d.rateResolved,
          responseScore: d.rateResponse,
          handlingScore: d.rateHandling,
          satisfactionScore: d.rateSatisfaction
        }
      })
      wx.showToast({ title: '已评价' })
      // 评价后清掉对应待办（若从待办进入）
      try {
        const todoData = await request({ url: '/todos', data: { status: 'OPEN' } })
        const list = (todoData && todoData.list) || []
        const hit = list.find((t) =>
          String(t.bizId) === String(d.id) &&
          (t.todoType === 'REPAIR_RATE' || t.todoType === 'COMPLAINT_RATE'))
        if (hit) await request({ url: '/todos/' + hit.id + '/done', method: 'POST' })
      } catch (e) { /* ignore */ }
      this.load()
    } catch (e) {
      wx.showModal({
        title: '评价失败',
        content: (e && e.message) || '请稍后重试',
        showCancel: false
      })
    } finally {
      this.setData({ rateSubmitting: false })
    }
  }
})
