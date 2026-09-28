const { ensureLogin } = require('../../utils/format')

Page({
  data: {
    entries: [
      {
        key: 'public',
        name: '公区报障',
        tone: 'orange',
        icon: '公',
        category: '公区报障'
      },
      {
        key: 'indoor',
        name: '室内维修',
        tone: 'blue',
        icon: '室',
        category: '室内维修'
      },
      {
        key: 'complaint',
        name: '投诉',
        tone: 'gold',
        icon: '诉',
        category: '投诉'
      },
      {
        key: 'praise',
        name: '表扬',
        tone: 'coral',
        icon: '扬',
        category: '表扬'
      },
      {
        key: 'advice',
        name: '咨询建议',
        tone: 'mint',
        icon: '询',
        category: '咨询建议'
      }
    ]
  },

  onEntry(e) {
    if (!ensureLogin()) return
    const key = e.currentTarget.dataset.key
    const item = (this.data.entries || []).find((x) => x.key === key)
    if (!item) return
    this.openEntry(item)
  },

  openEntry(item) {
    const cat = encodeURIComponent(item.category || '')
    wx.navigateTo({
      url: `/pages/service-submit/service-submit?category=${cat}`
    })
  }
})
