const { request } = require('../../utils/request')
const { formatDateTime } = require('../../utils/format')

function coverUrl(attachmentId) {
  if (!attachmentId) return ''
  const app = getApp()
  const token = (app && app.globalData && app.globalData.token) || wx.getStorageSync('token') || ''
  const base = (app && app.globalData && app.globalData.apiBase) || ''
  return `${base}/attachments/${attachmentId}${token ? `?token=${encodeURIComponent(token)}` : ''}`
}

function shortDate(v) {
  const s = formatDateTime(v)
  return s ? s.slice(0, 10) : ''
}

Page({
  data: {
    list: [],
    loading: true,
    source: '', // STAFF | COMMITTEE | ''
    pageTitle: '公告'
  },

  onLoad(q) {
    if (q && q.id) {
      wx.redirectTo({ url: `/pages/notice-detail/notice-detail?id=${q.id}` })
      return
    }
    if (q && q.communityId) this._communityId = q.communityId
    const source = (q && q.source) === 'COMMITTEE' ? 'COMMITTEE'
      : (q && q.source) === 'STAFF' ? 'STAFF' : ''
    const pageTitle = source === 'COMMITTEE' ? '业委通知'
      : source === 'STAFF' ? '物业公告' : '公告'
    this.setData({ source, pageTitle })
    wx.setNavigationBarTitle({ title: pageTitle })
  },

  onShow() { this.load() },
  onPullDownRefresh() {
    this.load().finally(() => wx.stopPullDownRefresh())
  },

  async load() {
    this.setData({ loading: true })
    try {
      const data = { page: 1, pageSize: 50 }
      if (this._communityId) data.communityId = this._communityId
      if (this.data.source) data.creatorIdentity = this.data.source
      const dataRes = await request({ url: '/notices', data })
      const list = ((dataRes && dataRes.list) || []).map((it) => Object.assign({}, it, {
        timeLabel: shortDate(it.effectiveAt || it.createdAt),
        coverThumb: coverUrl(it.coverAttachmentId),
        urgent: Number(it.urgency) >= 2
      }))
      this.setData({ list })
    } catch (e) {
      this.setData({ list: [] })
      wx.showToast({ title: (e && e.message) || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  open(e) {
    const id = e.currentTarget.dataset.id
    if (!id) return
    wx.navigateTo({ url: `/pages/notice-detail/notice-detail?id=${id}` })
  }
})
