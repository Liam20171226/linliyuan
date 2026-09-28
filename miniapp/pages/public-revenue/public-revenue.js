const { request } = require('../../utils/request')
const { formatDateTime, formatMoney } = require('../../utils/format')

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
      // 直接公示：住户/业委会均只读已公示列表
      let data
      try {
        data = await request({
          url: '/resident/public-revenue/items',
          data: { page: 1, pageSize: 50 }
        })
      } catch (e) {
        data = await request({
          url: '/committee/public-revenue/items',
          data: { page: 1, pageSize: 50 }
        })
      }
      const list = ((data && data.list) || []).map((it) => Object.assign({}, it, {
        statusLabel: '已公示',
        timeLabel: formatDateTime(it.updatedAt || it.createdAt || it.confirmedAt),
        amountText: formatMoney(it.amount)
      }))
      this.setData({ list })
    } catch (e) {
      this.setData({ list: [] })
      wx.showToast({ title: (e && e.message) || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  }
})
