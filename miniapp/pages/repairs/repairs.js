const { request } = require('../../utils/request')
const { repairStatusLabel, roleLabel } = require('../../utils/format')
const photoAttach = require('../../utils/photo-attach')

Page(Object.assign({}, photoAttach, {
  data: Object.assign({}, photoAttach.data, {
    pageTitle: '提交报修',
    submitLabel: '提交报修',
    roomId: '',
    roomLabel: '',
    roomOptions: [],
    category: '室内维修',
    description: '',
    descPlaceholder: '请描述位置与现象，便于上门',
    list: [],
    categories: ['公区报障', '室内维修'],
    submitting: false,
    showList: true
  }),

  onLoad(q) {
    const category = q && q.category ? decodeURIComponent(q.category) : ''
    const title = q && q.title ? decodeURIComponent(q.title) : ''
    const mode = q && q.mode
    const patch = {}
    if (category && this.data.categories.indexOf(category) >= 0) {
      patch.category = category
      patch.descPlaceholder = category === '公区报障'
        ? '请写明具体位置（如：1号楼大堂）及故障现象，便于上门'
        : '请描述位置与现象，便于上门'
    }
    if (title) {
      patch.pageTitle = title
      patch.submitLabel = '提交' + title
      wx.setNavigationBarTitle({ title })
    }
    if (mode === 'submit') patch.showList = false
    if (Object.keys(patch).length) this.setData(patch)
  },

  onShow() {
    this.bootstrap()
  },
  onPullDownRefresh() {
    this.bootstrap().finally(() => wx.stopPullDownRefresh())
  },

  onRoom(e) { this.setData({ roomId: e.detail.value }) },
  onDesc(e) { this.setData({ description: e.detail.value }) },
  onCat(e) {
    const category = this.data.categories[Number(e.detail.value)]
    this.setData({
      category,
      descPlaceholder: category === '公区报障'
        ? '请写明具体位置（如：1号楼大堂）及故障现象，便于上门'
        : '请描述位置与现象，便于上门'
    })
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
      const data = await request({ url: '/resident/repairs', data: { page: 1, pageSize: 50 } })
      const list = ((data && data.list) || []).map((it) => Object.assign({}, it, {
        statusLabel: repairStatusLabel(it.status)
      }))
      this.setData({ list })
    } catch (e) {
      this.setData({ list: [] })
    }
  },

  async submit() {
    if (!this.data.roomId || !this.data.description) {
      wx.showToast({ title: '请选择房屋并填写描述', icon: 'none' })
      return
    }
    this.setData({ submitting: true })
    try {
      await request({
        url: '/resident/repairs',
        method: 'POST',
        data: {
          roomId: Number(this.data.roomId),
          category: this.data.category,
          location: null,
          description: this.data.description,
          attachmentIds: this.getAttachmentIds()
        }
      })
      wx.showToast({ title: '已提交' })
      this.setData({ description: '' })
      this.resetPhotos()
      this.load()
    } catch (e) {
      wx.showModal({ title: '提交失败', content: (e && e.message) || '请稍后重试', showCancel: false })
    } finally {
      this.setData({ submitting: false })
    }
  },

  goRate(e) {
    const id = e.currentTarget.dataset.id
    if (!id) return
    wx.navigateTo({ url: `/pages/service-detail/service-detail?id=${id}&kind=repair` })
  }
}))
