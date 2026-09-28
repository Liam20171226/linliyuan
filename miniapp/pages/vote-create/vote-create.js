const { request } = require('../../utils/request')

function pad(n) { return n < 10 ? '0' + n : '' + n }

function todayStr() {
  const d = new Date()
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

function plusDays(days) {
  const d = new Date()
  d.setDate(d.getDate() + days)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

Page({
  data: {
    title: '',
    background: '',
    startDate: todayStr(),
    endDate: plusDays(7),
    options: ['同意', '不同意'],
    submitting: false
  },

  onTitle(e) { this.setData({ title: e.detail.value }) },
  onBg(e) { this.setData({ background: e.detail.value }) },
  onStart(e) { this.setData({ startDate: e.detail.value }) },
  onEnd(e) { this.setData({ endDate: e.detail.value }) },

  onOption(e) {
    const idx = Number(e.currentTarget.dataset.idx)
    const options = this.data.options.slice()
    options[idx] = e.detail.value
    this.setData({ options })
  },

  addOption() {
    if (this.data.options.length >= 6) {
      wx.showToast({ title: '最多 6 个选项', icon: 'none' })
      return
    }
    this.setData({ options: this.data.options.concat(['']) })
  },

  removeOption(e) {
    const idx = Number(e.currentTarget.dataset.idx)
    if (this.data.options.length <= 2) {
      wx.showToast({ title: '至少 2 个选项', icon: 'none' })
      return
    }
    const options = this.data.options.slice()
    options.splice(idx, 1)
    this.setData({ options })
  },

  async submit() {
    const title = (this.data.title || '').trim()
    const background = (this.data.background || '').trim()
    const options = this.data.options.map((s) => String(s || '').trim()).filter(Boolean)
    if (!title) {
      wx.showToast({ title: '请填写标题', icon: 'none' })
      return
    }
    if (!background) {
      wx.showToast({ title: '请填写背景说明', icon: 'none' })
      return
    }
    if (options.length < 2) {
      wx.showToast({ title: '至少 2 个有效选项', icon: 'none' })
      return
    }
    if (!this.data.startDate || !this.data.endDate) {
      wx.showToast({ title: '请选择起止日期', icon: 'none' })
      return
    }
    if (this.data.endDate <= this.data.startDate) {
      wx.showToast({ title: '结束须晚于开始', icon: 'none' })
      return
    }

    this.setData({ submitting: true })
    try {
      await request({
        url: '/committee/votes',
        method: 'POST',
        data: {
          title,
          background,
          startAt: `${this.data.startDate}T00:00:00`,
          endAt: `${this.data.endDate}T23:59:59`,
          options
        }
      })
      wx.showToast({ title: '已发起' })
      setTimeout(() => {
        wx.redirectTo({ url: '/pages/vote-mine/vote-mine' })
      }, 500)
    } catch (err) {
      wx.showModal({ title: '发起失败', content: (err && err.message) || '请稍后重试', showCancel: false })
    } finally {
      this.setData({ submitting: false })
    }
  }
})
