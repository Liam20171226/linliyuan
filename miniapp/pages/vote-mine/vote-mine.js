const { request } = require('../../utils/request')
const { voteStatusLabel, formatDateTime } = require('../../utils/format')

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
      title: tab === 'all' ? '业委会全部投票' : '我发起的投票'
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
      const packed = await request({ url: '/auth/identities' })
      const userId = packed && packed.profile && packed.profile.id
        || packed && packed.user && packed.user.id
        || null
      const data = await request({ url: '/committee/votes', data: { page: 1, pageSize: 50 } })
      let list = ((data && data.list) || [])
        .filter((it) => String(it.creatorRole || '') === 'COMMITTEE')
        .map((it) => {
          const mine = userId != null && Number(it.createdBy) === Number(userId)
          return Object.assign({}, it, {
            statusLabel: voteStatusLabel(it.status),
            timeLabel: `${formatDateTime(it.startAt)} ~ ${formatDateTime(it.endAt)}`,
            mine,
            canDelete: mine
          })
        })
      if (this.data.tab === 'mine') {
        list = userId != null ? list.filter((it) => it.mine) : []
      }
      this.setData({ list, userId })
    } catch (e) {
      this.setData({ list: [] })
      wx.showToast({ title: (e && e.message) || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  open(e) {
    const id = e.currentTarget.dataset.id
    const mine = e.currentTarget.dataset.mine
    if (!id) return
    wx.navigateTo({
      url: `/pages/vote-stats/vote-stats?id=${id}&mine=${mine ? '1' : '0'}`
    })
  }
})
