const { request } = require('../../utils/request')
const { voteStatusLabel, formatDateTime } = require('../../utils/format')

Page({
  data: {
    id: null,
    loading: true,
    error: '',
    title: '',
    statusLabel: '',
    timeLabel: '',
    mine: false,
    canDelete: false,
    totalBallots: 0,
    options: [],
    ballots: []
  },

  onLoad(q) {
    const id = q && q.id ? Number(q.id) : null
    const mine = q && (q.mine === '1' || q.mine === 'true')
    this.setData({ id, mine: !!mine, canDelete: !!mine })
    if (!id) {
      this.setData({ loading: false, error: '无效投票' })
    }
  },

  onShow() {
    if (this.data.id) this.load()
  },

  onPullDownRefresh() {
    this.load().finally(() => wx.stopPullDownRefresh())
  },

  async load() {
    this.setData({ loading: true, error: '' })
    try {
      let userId = null
      try {
        const packed = await request({ url: '/auth/identities' })
        userId = packed && packed.profile && packed.profile.id
          || packed && packed.user && packed.user.id
          || null
      } catch (e) { /* ignore */ }

      const [stats, listData] = await Promise.all([
        request({ url: `/votes/${this.data.id}/stats` }),
        request({ url: '/committee/votes', data: { page: 1, pageSize: 50 } }).catch(() => null)
      ])

      const item = ((listData && listData.list) || []).find((x) => Number(x.id) === Number(this.data.id))
      const mine = userId != null && item && Number(item.createdBy) === Number(userId)
      const title = (item && item.title) || '投票结果'
      wx.setNavigationBarTitle({ title })

      const total = Number((stats && stats.totalBallots) || 0)
      const options = ((stats && stats.options) || []).map((o) => {
        const votes = Number(o.votes || 0)
        return {
          optionId: o.optionId,
          optionText: o.optionText || '—',
          votes,
          pct: total > 0 ? Math.round((votes / total) * 1000) / 10 : 0
        }
      })
      const ballots = ((stats && stats.ballots) || []).map((b) => ({
        roomLabel: b.roomLabel || '—',
        voterName: b.voterName || '—',
        optionText: b.optionText || '—',
        votedAt: formatDateTime(b.votedAt).slice(0, 16) || '—'
      }))

      this.setData({
        title,
        statusLabel: item ? voteStatusLabel(item.status) : '',
        timeLabel: item
          ? `${formatDateTime(item.startAt)} ~ ${formatDateTime(item.endAt)}`
          : '',
        mine: !!mine,
        canDelete: !!mine,
        totalBallots: total,
        options,
        ballots
      })
    } catch (err) {
      this.setData({ error: (err && err.message) || '加载失败' })
    } finally {
      this.setData({ loading: false })
    }
  },

  remove() {
    if (!this.data.canDelete) {
      wx.showToast({ title: '只能删除自己发起的', icon: 'none' })
      return
    }
    const id = this.data.id
    wx.showModal({
      title: '删除投票',
      content: '截止前可删除；删除后不可恢复',
      success: async (res) => {
        if (!res.confirm) return
        wx.showLoading({ title: '删除中', mask: true })
        try {
          await request({ url: `/votes/${id}`, method: 'DELETE' })
          wx.showToast({ title: '已删除' })
          setTimeout(() => wx.navigateBack(), 400)
        } catch (err) {
          wx.showModal({ title: '删除失败', content: (err && err.message) || '请稍后重试', showCancel: false })
        } finally {
          wx.hideLoading()
        }
      }
    })
  }
})
