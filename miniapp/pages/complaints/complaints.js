const { request } = require('../../utils/request')
const { complaintStatusLabel, roleLabel } = require('../../utils/format')
const photoAttach = require('../../utils/photo-attach')

function formPatchForCategory(name) {
  const labels = {
    投诉: { contentLabel: '投诉内容', pageTitle: '提交投诉', submitLabel: '提交投诉' },
    表扬: { contentLabel: '表扬内容', pageTitle: '提交表扬', submitLabel: '提交表扬' },
    咨询建议: { contentLabel: '建议内容', pageTitle: '提交咨询建议', submitLabel: '提交咨询建议' }
  }
  const L = labels[name] || { contentLabel: name + '内容', pageTitle: '提交' + name, submitLabel: '提交' + name }
  return Object.assign({ category: name }, L)
}

Page(Object.assign({}, photoAttach, {
  data: Object.assign({}, photoAttach.data, {
    pageTitle: '提交投诉',
    submitLabel: '提交投诉',
    contentLabel: '投诉内容',
    roomId: '',
    roomLabel: '',
    roomOptions: [],
    category: '投诉',
    content: '',
    list: [],
    categories: ['投诉', '表扬', '咨询建议'],
    submitting: false,
    showList: true
  }),

  onLoad(q) {
    const category = q && q.category ? decodeURIComponent(q.category) : ''
    const title = q && q.title ? decodeURIComponent(q.title) : ''
    const mode = q && q.mode
    const patch = formPatchForCategory(
      category && this.data.categories.indexOf(category) >= 0 ? category : this.data.category
    )
    if (title) {
      patch.pageTitle = '提交' + title
      patch.submitLabel = '提交' + title
      wx.setNavigationBarTitle({ title })
    } else {
      wx.setNavigationBarTitle({ title: patch.pageTitle.replace(/^提交/, '') || '投诉建议' })
    }
    if (mode === 'submit') patch.showList = false
    this.setData(patch)
  },

  onShow() { this.bootstrap() },
  onPullDownRefresh() {
    this.bootstrap().finally(() => wx.stopPullDownRefresh())
  },

  onField(e) {
    const k = e.currentTarget.dataset.k
    this.setData({ [k]: e.detail.value })
  },
  onCat(e) {
    const name = this.data.categories[Number(e.detail.value)]
    this.setData(formPatchForCategory(name))
  },
  onRoomPick(e) {
    const opt = this.data.roomOptions[Number(e.detail.value)]
    if (!opt) return
    this.setData({ roomId: String(opt.roomId), roomLabel: opt.label })
  },

  async bootstrap() {
    const tasks = [this.loadRooms()]
    if (this.data.showList) tasks.push(this.load())
    await Promise.all(tasks)
  },

  async loadRooms() {
    try {
      const rooms = await request({ url: '/resident/rooms' })
      const roomOptions = (rooms || []).map((r) => ({
        roomId: r.roomId,
        label: `${r.address || r.roomNo || ('房屋' + r.roomId)}（${roleLabel(r.residentRole)}）`
      }))
      const patch = { roomOptions }
      if (roomOptions.length && !this.data.roomId) {
        patch.roomId = String(roomOptions[0].roomId)
        patch.roomLabel = roomOptions[0].label
      }
      this.setData(patch)
    } catch (e) {
      this.setData({ roomOptions: [] })
    }
  },

  async load() {
    try {
      const data = await request({ url: '/resident/complaints', data: { page: 1, pageSize: 50 } })
      const list = ((data && data.list) || []).map((it) => Object.assign({}, it, {
        statusLabel: complaintStatusLabel(it.status)
      }))
      this.setData({ list })
    } catch (e) {
      this.setData({ list: [] })
    }
  },

  async submit() {
    if (!this.data.roomId || !this.data.content) {
      wx.showToast({ title: '请填写房屋与内容', icon: 'none' })
      return
    }
    this.setData({ submitting: true })
    try {
      await request({
        url: '/resident/complaints',
        method: 'POST',
        data: {
          roomId: Number(this.data.roomId),
          category: this.data.category,
          content: this.data.content,
          attachmentIds: this.getAttachmentIds()
        }
      })
      wx.showToast({ title: '已提交' })
      this.setData({ content: '' })
      this.resetPhotos()
      this.load()
    } catch (e) {
      wx.showModal({ title: '提交失败', content: (e && e.message) || '请稍后重试', showCancel: false })
    } finally {
      this.setData({ submitting: false })
    }
  }
}))
