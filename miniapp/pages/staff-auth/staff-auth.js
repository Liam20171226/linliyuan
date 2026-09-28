const { request } = require('../../utils/request')
const { roleLabel, formatDateTime } = require('../../utils/format')

const STATUS = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已拒绝'
}

function decorate(it) {
  return Object.assign({}, it, {
    statusLabel: STATUS[it.status] || it.status || '',
    roleText: roleLabel(it.applyRole),
    roomText: it.roomPath || it.roomNo || '—',
    timeLabel: formatDateTime(it.createdAt)
  })
}

Page({
  data: {
    tab: 'pending', // pending 待审核 | done 已处理
    pendingList: [],
    doneList: [],
    loading: false
  },

  onLoad(q) {
    if (q && q.tab === 'done') this.setData({ tab: 'done' })
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

  async load() {
    this.setData({ loading: true })
    try {
      const raw = await request({ url: '/staff/auth-applications' })
      const all = (Array.isArray(raw) ? raw : []).map(decorate)
      this.setData({
        pendingList: all.filter((it) => it.status === 'PENDING'),
        doneList: all.filter((it) => it.status !== 'PENDING')
      })
    } catch (e) {
      this.setData({ pendingList: [], doneList: [] })
      wx.showToast({ title: (e && e.message) || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  async approve(e) {
    const id = e.currentTarget.dataset.id
    if (!id) return
    const res = await wx.showModal({
      title: '通过认证',
      content: '确认通过该住户认证申请？'
    }).catch(() => ({ confirm: false }))
    if (!res || !res.confirm) return
    wx.showLoading({ title: '处理中', mask: true })
    try {
      await request({ url: '/staff/auth-applications/' + id + '/approve', method: 'POST' })
      wx.hideLoading()
      wx.showToast({ title: '已通过' })
      this.load()
    } catch (err) {
      wx.hideLoading()
      wx.showToast({ title: (err && err.message) || '操作失败', icon: 'none' })
    }
  },

  async reject(e) {
    const id = e.currentTarget.dataset.id
    if (!id) return
    const ask = await wx.showModal({
      title: '驳回申请',
      placeholderText: '请填写驳回原因',
      editable: true
    }).catch(() => ({ confirm: false }))
    if (!ask || !ask.confirm) return
    const reason = (ask.content || '').trim()
    if (!reason) {
      wx.showToast({ title: '请填写驳回原因', icon: 'none' })
      return
    }
    wx.showLoading({ title: '处理中', mask: true })
    try {
      await request({
        url: '/staff/auth-applications/' + id + '/reject',
        method: 'POST',
        data: { rejectReason: reason }
      })
      wx.hideLoading()
      wx.showToast({ title: '已驳回' })
      this.load()
    } catch (err) {
      wx.hideLoading()
      wx.showToast({ title: (err && err.message) || '操作失败', icon: 'none' })
    }
  }
})
