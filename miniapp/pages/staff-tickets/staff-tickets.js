const { request } = require('../../utils/request')
const { repairStatusLabel, complaintStatusLabel, formatDateTime } = require('../../utils/format')

/** 时间由近到远（createdAt 为 YYYY-MM-DD HH:mm:ss 形式，字典序即时间序） */
function byTimeDesc(a, b) {
  const x = String((a && a.sortKey) || '')
  const y = String((b && b.sortKey) || '')
  if (x === y) return Number(b.id || 0) - Number(a.id || 0)
  return x < y ? 1 : -1
}

function mapList(list) {
  return (list || []).map((it) => {
    const kind = it.kind === 'COMPLAINT' ? 'complaint' : 'repair'
    const statusLabel = kind === 'complaint'
      ? complaintStatusLabel(it.status)
      : repairStatusLabel(it.status)
    return {
      id: it.id,
      kind,
      sortKey: formatDateTime(it.createdAt) || '',
      category: it.category || (kind === 'complaint' ? '投诉' : '报修'),
      statusLabel,
      status: it.status,
      desc: it.content || '',
      location: it.location || '',
      pendingClaim: !it.assigneeUserId && !!it.assigneeRole && it.status === 'ASSIGNED',
      time: (formatDateTime(it.createdAt) || '').slice(0, 16)
    }
  })
}

Page({
  data: {
    tab: 'open', // open 待办工单 | done 已完成工单
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

  async load() {
    this.setData({ loading: true })
    try {
      const [mine, pool, done] = await Promise.all([
        request({ url: '/staff/repairs/mine', data: { page: 1, pageSize: 50 } }).catch(() => null),
        request({ url: '/staff/repairs/pool', data: { page: 1, pageSize: 50 } }).catch(() => null),
        request({ url: '/staff/repairs/done', data: { page: 1, pageSize: 50 } }).catch(() => null)
      ])
      // 待办工单：我的待办 + 待派单合并去重，按时间由近到远（含投诉/咨询/表扬）
      const map = {}
      ;[(mine && mine.list) || [], (pool && pool.list) || []].forEach((arr) => {
        arr.forEach((it) => {
          if (it && it.id != null) map[it.id] = it
        })
      })
      const openList = Object.keys(map).map((k) => map[k])
      this.setData({
        openList: mapList(openList).sort(byTimeDesc),
        doneList: mapList((done && done.list) || []).sort(byTimeDesc)
      })
    } catch (e) {
      this.setData({ openList: [], doneList: [] })
      wx.showToast({ title: (e && e.message) || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  openTicket(e) {
    const id = e.currentTarget.dataset.id
    const kind = e.currentTarget.dataset.kind || 'repair'
    if (!id) return
    wx.navigateTo({ url: `/pages/staff-ticket-detail/staff-ticket-detail?id=${id}&kind=${kind}` })
  }
})
