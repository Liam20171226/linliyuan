const { request } = require('../../utils/request')
const { roleLabel, formatDateTime } = require('../../utils/format')

const STATUS = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已拒绝'
}

function decorateChange(it) {
  return Object.assign({}, it, {
    statusLabel: STATUS[it.status] || it.status || '',
    roomText: it.roomPath || it.roomNo || '—',
    summary: it.payloadSummary || it.changeTypeLabel || '房屋变更',
    timeLabel: formatDateTime(it.createdAt)
  })
}

function decorateOccupant(it) {
  return Object.assign({}, it, {
    roleText: roleLabel(it.residentRole),
    nameText: it.realName || '未填写'
  })
}

function decorateVehicle(it) {
  return Object.assign({}, it, {
    spaceText: it.parkingSpaceNo ? ('车位 ' + it.parkingSpaceNo) : '未关联车位',
    timeLabel: formatDateTime(it.createdAt)
  })
}

Page({
  data: {
    tab: 'change', // change 变更申请 | people 人员 | vehicle 车辆
    pendingList: [],
    doneList: [],
    people: [],
    vehicles: [],
    peopleTotal: 0,
    vehicleTotal: 0,
    keyword: '',
    loading: false
  },

  onLoad(q) {
    const tab = q && q.tab
    if (tab === 'people' || tab === 'vehicle') this.setData({ tab })
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
    this.setData({ tab, keyword: '' })
    this.load()
  },

  onKeyword(e) {
    this.setData({ keyword: e.detail.value || '' })
  },

  onSearch() {
    this.load()
  },

  async load() {
    this.setData({ loading: true })
    const tab = this.data.tab
    const kw = (this.data.keyword || '').trim()
    try {
      if (tab === 'change') {
        const raw = await request({ url: '/staff/room-change-applications' })
        const all = (Array.isArray(raw) ? raw : []).map(decorateChange)
        this.setData({
          pendingList: all.filter((it) => it.status === 'PENDING'),
          doneList: all.filter((it) => it.status !== 'PENDING')
        })
      } else if (tab === 'people') {
        const data = await request({
          url: '/staff/occupants',
          data: { realName: kw || undefined, mobile: kw || undefined, page: 1, pageSize: 50 }
        })
        this.setData({
          people: ((data && data.list) || []).map(decorateOccupant),
          peopleTotal: Number((data && data.total) || 0)
        })
      } else {
        const data = await request({
          url: '/staff/vehicles',
          data: { plateNo: kw || undefined, page: 1, pageSize: 50 }
        })
        this.setData({
          vehicles: ((data && data.list) || []).map(decorateVehicle),
          vehicleTotal: Number((data && data.total) || 0)
        })
      }
    } catch (e) {
      if (tab === 'change') this.setData({ pendingList: [], doneList: [] })
      else if (tab === 'people') this.setData({ people: [], peopleTotal: 0 })
      else this.setData({ vehicles: [], vehicleTotal: 0 })
      wx.showToast({ title: (e && e.message) || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  async approve(e) {
    const id = e.currentTarget.dataset.id
    if (!id) return
    const res = await wx.showModal({ title: '通过变更', content: '确认通过该人员/车辆变更申请？' })
      .catch(() => ({ confirm: false }))
    if (!res || !res.confirm) return
    wx.showLoading({ title: '处理中', mask: true })
    try {
      await request({ url: '/staff/room-change-applications/' + id + '/approve', method: 'POST' })
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
      title: '驳回变更',
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
        url: '/staff/room-change-applications/' + id + '/reject',
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
