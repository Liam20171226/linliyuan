const { request } = require('../../utils/request')

Page({
  data: {
    plans: [],
    loading: true
  },

  onShow() { this.load() },
  onPullDownRefresh() {
    this.load().finally(() => wx.stopPullDownRefresh())
  },

  async load() {
    this.setData({ loading: true })
    try {
      const data = await request({ url: '/resident/prepaid/plans' })
      this.setData({
        plans: (data && data.plans) || []
      })
    } catch (e) {
      this.setData({ plans: [] })
      wx.showToast({ title: (e && e.message) || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  }
})
