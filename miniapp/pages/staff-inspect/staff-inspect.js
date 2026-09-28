const { request } = require('../../utils/request')
const { formatDateTime } = require('../../utils/format')

Page({
  data: {
    tab: 'open',
    openList: [],
    doneList: [],
    loading: true
  },

  onShow() {
    this.load()
  },

  onPullDownRefresh() {
    this.load().finally(() => wx.stopPullDownRefresh())
  },

  onTab(e) {
    const tab = e.currentTarget.dataset.tab
    if (!tab || tab === this.data.tab) return
    this.setData({ tab })
  },

  mapRow(it) {
    return {
      id: it.id,
      title: it.planTitle || '巡检任务',
      statusLabel: it.statusLabel || it.status,
      status: it.status,
      progress: `${it.visitedCount || 0}/${it.spotCount || 0}`,
      assignee: it.assigneeName || '',
      seqNo: it.seqNo || 1,
      canClaim: it.status === 'OPEN' && !it.assigneeUserId,
      time: (formatDateTime(it.createdAt) || '').slice(0, 16)
    }
  },

  async load() {
    this.setData({ loading: true })
    try {
      const [openRes, doneRes] = await Promise.all([
        request({ url: '/staff/inspect/jobs', data: { status: 'OPEN' } }).catch(() => null),
        request({ url: '/staff/inspect/jobs', data: { status: 'DONE' } }).catch(() => null)
      ])
      const openList = ((openRes && openRes.list) || []).map((it) => this.mapRow(it))
      const doneList = ((doneRes && doneRes.list) || []).map((it) => this.mapRow(it))
      this.setData({ openList, doneList })
    } catch (e) {
      this.setData({ openList: [], doneList: [] })
      wx.showToast({ title: (e && e.message) || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  openJob(e) {
    const id = e.currentTarget.dataset.id
    if (!id) return
    wx.navigateTo({ url: `/pages/staff-inspect-detail/staff-inspect-detail?id=${id}` })
  }
})
