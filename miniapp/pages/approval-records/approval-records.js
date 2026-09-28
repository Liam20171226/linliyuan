const { request } = require('../../utils/request')
const { roleLabel, formatDateTime } = require('../../utils/format')

const STATUS = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已拒绝'
}

Page({
  data: {
    tab: 'auth',
    authList: [],
    changeList: [],
    loading: false
  },

  onLoad(q) {
    if (q && q.tab === 'change') this.setData({ tab: 'change' })
  },

  onShow() {
    this.load()
  },

  onPullDownRefresh() {
    this.load().finally(() => wx.stopPullDownRefresh())
  },

  switchTab(e) {
    const tab = e.currentTarget.dataset.tab
    if (!tab || tab === this.data.tab) return
    this.setData({ tab })
  },

  async load() {
    this.setData({ loading: true })
    try {
      const [authRaw, changeRaw] = await Promise.all([
        request({ url: '/resident/auth-applications/mine' }).catch(() => []),
        request({ url: '/resident/room-change-applications/mine' }).catch(() => [])
      ])
      const authList = (authRaw || []).map((it) => ({
        ...it,
        statusLabel: STATUS[it.status] || it.status,
        roleLabel: roleLabel(it.applyRole),
        roomLabel: it.roomPath || it.roomNo || '—',
        applyTimeLabel: formatDateTime(it.createdAt)
      }))
      const changeList = (changeRaw || []).map((it) => ({
        ...it,
        statusLabel: STATUS[it.status] || it.status,
        roomLabel: it.roomPath || it.roomNo || '—',
        applyTimeLabel: formatDateTime(it.createdAt)
      }))
      this.setData({ authList, changeList })
    } catch (e) {
      this.setData({ authList: [], changeList: [] })
      wx.showToast({ title: (e && e.message) || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  goAuthDetail(e) {
    const id = e.currentTarget.dataset.id
    if (id == null) return
    wx.navigateTo({ url: `/pages/auth-detail/auth-detail?id=${id}` })
  }
})
