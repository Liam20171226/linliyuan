const { request } = require('../../utils/request')
const { voteStatusLabel, formatDateTime } = require('../../utils/format')

function sourceLabelOf(role) {
  if (role === 'COMMITTEE') return '业委会'
  if (role === 'PROPERTY_MANAGER' || role === 'STAFF') return '物业'
  if (role === 'PLATFORM') return '平台'
  return '业主投票'
}

function shortRange(startAt, endAt) {
  const a = formatDateTime(startAt)
  const b = formatDateTime(endAt)
  const as = a ? a.slice(0, 16) : ''
  const bs = b ? b.slice(0, 16) : ''
  if (as && bs) return `${as} ~ ${bs}`
  return as || bs || ''
}

Page({
  data: {
    list: [],
    loading: true
  },

  onShow() { this.load() },
  onPullDownRefresh() {
    this.load().finally(() => wx.stopPullDownRefresh())
  },

  async load() {
    this.setData({ loading: true })
    try {
      const data = await request({ url: '/resident/votes', data: { page: 1, pageSize: 50 } })
      const list = ((data && data.list) || []).map((it) => Object.assign({}, it, {
        statusLabel: voteStatusLabel(it.status),
        sourceLabel: sourceLabelOf(it.creatorRole),
        rangeLabel: shortRange(it.startAt, it.endAt)
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
    if (id == null || id === '') {
      wx.showToast({ title: '无效投票', icon: 'none' })
      return
    }
    wx.navigateTo({ url: `/pages/vote-detail/vote-detail?id=${id}` })
  }
})
