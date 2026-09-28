const { request } = require('../../utils/request')
const { formatDateTime } = require('../../utils/format')

const VIS_OPTS = [
  { value: 'RESIDENTS', label: '住户' },
  { value: 'OWNERS', label: '仅业主' }
]
const VIS_LABEL = Object.fromEntries(VIS_OPTS.map((x) => [x.value, x.label]))

Page({
  data: {
    tab: 'mine', // mine | all
    list: [],
    loading: true,
    userId: null
  },

  onLoad(q) {
    const tab = q && q.tab === 'all' ? 'all' : 'mine'
    this.setData({ tab })
    this.syncTitle(tab)
  },

  syncTitle(tab) {
    wx.setNavigationBarTitle({
      title: tab === 'all' ? '业委会全部通知' : '我发布的通知'
    })
  },

  onShow() { this.load() },
  onPullDownRefresh() {
    this.load().finally(() => wx.stopPullDownRefresh())
  },

  switchTab(e) {
    const tab = e.currentTarget.dataset.tab
    if (!tab || tab === this.data.tab) return
    this.setData({ tab })
    this.syncTitle(tab)
    this.load()
  },

  async load() {
    this.setData({ loading: true })
    try {
      let userId = this.data.userId
      if (userId == null) {
        try {
          const packed = await request({ url: '/auth/identities' })
          userId = packed && packed.profile && packed.profile.id
            || packed && packed.user && packed.user.id
            || null
        } catch (e) { /* ignore */ }
      }
      const mineOnly = this.data.tab === 'mine'
      const url = mineOnly ? '/committee/notices/mine' : '/committee/notices'
      const data = await request({ url, data: { page: 1, pageSize: 50 } })
      const list = ((data && data.list) || [])
        .filter((it) => !it.creatorIdentity || it.creatorIdentity === 'COMMITTEE')
        .map((it) => {
          const mine = userId != null && Number(it.createdBy) === Number(userId)
          return Object.assign({}, it, {
            visLabel: VIS_LABEL[it.visibility] || it.visibility || '',
            timeLabel: formatDateTime(it.effectiveAt || it.createdAt).slice(0, 16),
            mine,
            canEdit: mine
          })
        })
      this.setData({ list, userId })
    } catch (e) {
      this.setData({ list: [] })
      wx.showToast({ title: (e && e.message) || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  goCreate() {
    wx.navigateTo({ url: '/pages/notice-create/notice-create' })
  },

  openEdit(e) {
    const id = e.currentTarget.dataset.id
    const item = this.data.list.find((x) => Number(x.id) === Number(id))
    if (!item || !item.canEdit) {
      wx.showToast({ title: '只能修改自己发布的', icon: 'none' })
      return
    }
    wx.navigateTo({ url: `/pages/notice-create/notice-create?id=${id}` })
  },

  openDetail(e) {
    const id = e.currentTarget.dataset.id
    if (!id) return
    wx.navigateTo({ url: `/pages/notice-detail/notice-detail?id=${id}` })
  },

  remove(e) {
    const id = e.currentTarget.dataset.id
    const item = this.data.list.find((x) => Number(x.id) === Number(id))
    if (item && !item.canEdit) {
      wx.showToast({ title: '只能删除自己发布的', icon: 'none' })
      return
    }
    wx.showModal({
      title: '删除通知',
      content: '确定删除该通知？',
      success: async (res) => {
        if (!res.confirm) return
        wx.showLoading({ title: '删除中', mask: true })
        try {
          await request({ url: `/committee/notices/${id}`, method: 'DELETE' })
          wx.showToast({ title: '已删除' })
          this.load()
        } catch (err) {
          wx.showModal({ title: '删除失败', content: (err && err.message) || '请稍后重试', showCancel: false })
        } finally {
          wx.hideLoading()
        }
      }
    })
  }
})
