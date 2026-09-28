const { request } = require('../../utils/request')
const { roleLabel, formatDateTime } = require('../../utils/format')

const STATUS = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已拒绝'
}

Page({
  data: {
    loading: true,
    detail: null
  },

  onLoad(q) {
    this.id = q.id
    this.load()
  },

  async load() {
    if (!this.id) {
      wx.showToast({ title: '缺少申请单号', icon: 'none' })
      return
    }
    this.setData({ loading: true })
    try {
      const raw = await request({ url: `/resident/auth-applications/${this.id}` })
      const attachments = (raw.attachments || []).map((a) => ({
        ...a,
        isImage: !!(a.image || (a.contentType && String(a.contentType).indexOf('image/') === 0))
      }))
      this.setData({
        detail: {
          ...raw,
          statusLabel: STATUS[raw.status] || raw.status,
          roleLabel: roleLabel(raw.applyRole),
          roomLabel: raw.roomPath || raw.roomNo || '—',
          applyTimeLabel: formatDateTime(raw.createdAt),
          reviewTimeLabel: formatDateTime(raw.reviewedAt),
          attachments
        }
      })
    } catch (e) {
      wx.showModal({
        title: '加载失败',
        content: (e && e.message) || '请稍后重试',
        showCancel: false
      })
    } finally {
      this.setData({ loading: false })
    }
  },

  preview(e) {
    const url = e.currentTarget.dataset.url
    if (!url) return
    const urls = (this.data.detail.attachments || [])
      .filter((a) => a.isImage && a.url)
      .map((a) => a.url)
    wx.previewImage({ current: url, urls: urls.length ? urls : [url] })
  }
})
