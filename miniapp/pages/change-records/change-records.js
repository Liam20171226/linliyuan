const { request } = require('../../utils/request')
const { roleLabel, formatDateTime } = require('../../utils/format')

const STATUS = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已拒绝'
}

Page({
  data: {
    list: [],
    loading: false
  },

  onShow() {
    this.load()
  },

  onPullDownRefresh() {
    this.load().finally(() => wx.stopPullDownRefresh())
  },

  async load() {
    this.setData({ loading: true })
    try {
      const raw = await request({ url: '/resident/room-change-applications/mine' })
      const list = (raw || []).map((it) => ({
        ...it,
        statusLabel: STATUS[it.status] || it.status,
        roomLabel: it.roomPath || it.roomNo || (it.roomId != null ? `房ID ${it.roomId}` : '—'),
        applyTimeLabel: formatDateTime(it.createdAt)
      }))
      this.setData({ list })
    } catch (e) {
      this.setData({ list: [] })
      wx.showToast({ title: (e && e.message) || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  }
})
