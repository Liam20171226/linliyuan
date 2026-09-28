const { request } = require('../../utils/request')
const { repairStatusLabel, complaintStatusLabel, formatDateTime } = require('../../utils/format')

Page({
  data: { list: [], loading: true },

  onShow() { this.load() },
  onPullDownRefresh() {
    this.load().finally(() => wx.stopPullDownRefresh())
  },

  async load() {
    this.setData({ loading: true })
    try {
      let data
      try {
        data = await request({
          url: '/committee/tickets',
          data: { page: 1, pageSize: 50 }
        })
      } catch (e) {
        // 兼容旧后端：仅投诉概况接口
        data = await request({
          url: '/committee/complaints/overview',
          data: { page: 1, pageSize: 50 }
        })
      }
      const list = ((data && data.list) || []).map((it) => {
        const isRepair = it.kind === 'REPAIR'
        return Object.assign({}, it, {
          kindLabel: isRepair ? '报修' : (it.category || '投诉'),
          statusLabel: isRepair
            ? repairStatusLabel(it.status)
            : complaintStatusLabel(it.status),
          desc: it.content || it.description || '',
          contactLine: [it.contactName, it.contactMobile].filter(Boolean).join(' ')
            || (it.roomLabel || ''),
          timeLabel: formatDateTime(it.createdAt),
          navKind: isRepair ? 'repair' : 'complaint'
        })
      })
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
    const kind = e.currentTarget.dataset.kind || 'complaint'
    if (!id) return
    wx.navigateTo({
      url: `/pages/staff-ticket-detail/staff-ticket-detail?id=${id}&kind=${kind}&from=committee`
    })
  }
})
