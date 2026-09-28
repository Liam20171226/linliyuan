const { request } = require('../../utils/request')
const { roleLabel } = require('../../utils/format')
const { readLastResident } = require('../../utils/identity')
const photoAttach = require('../../utils/photo-attach')

const CATEGORY_DEFS = [
  {
    name: '公区报障',
    kind: 'repair',
    needLocation: false,
    descLabel: '问题描述',
    descPlaceholder: '请写明具体位置（如：1号楼大堂）及故障现象，便于上门'
  },
  {
    name: '室内维修',
    kind: 'repair',
    needLocation: false,
    descLabel: '问题描述',
    descPlaceholder: '请描述位置与现象，便于上门'
  },
  {
    name: '投诉',
    kind: 'complaint',
    descLabel: '投诉内容',
    descPlaceholder: '请客观描述情况'
  },
  {
    name: '表扬',
    kind: 'complaint',
    descLabel: '表扬内容',
    descPlaceholder: '请写明表扬对象与事迹'
  },
  {
    name: '咨询建议',
    kind: 'complaint',
    descLabel: '建议内容',
    descPlaceholder: '请填写咨询或建议内容'
  }
]

function applyCategory(name) {
  const def = CATEGORY_DEFS.find((x) => x.name === name) || CATEGORY_DEFS[0]
  return {
    category: def.name,
    kind: def.kind,
    needLocation: !!def.needLocation,
    descLabel: def.descLabel,
    descPlaceholder: def.descPlaceholder,
    pageTitle: def.name,
    submitLabel: '提交' + def.name
  }
}

Page(Object.assign({}, photoAttach, {
  data: Object.assign({}, photoAttach.data, {
    categories: CATEGORY_DEFS.map((x) => x.name),
    roomId: '',
    roomLabel: '',
    roomOptions: [],
    category: '室内维修',
    kind: 'repair',
    needLocation: false,
    location: '',
    content: '',
    descLabel: '问题描述',
    descPlaceholder: '请描述位置与现象，便于上门',
    pageTitle: '室内维修',
    submitLabel: '提交室内维修',
    submitting: false
  }),

  onLoad(q) {
    const category = q && q.category ? decodeURIComponent(q.category) : ''
    const allowed = CATEGORY_DEFS.map((x) => x.name)
    const patch = applyCategory(
      category && allowed.indexOf(category) >= 0 ? category : this.data.category
    )
    this.setData(patch)
    wx.setNavigationBarTitle({ title: patch.pageTitle })
  },

  onShow() {
    this.loadRooms()
  },

  onRoom(e) { this.setData({ roomId: e.detail.value }) },
  onField(e) {
    const k = e.currentTarget.dataset.k
    this.setData({ [k]: e.detail.value })
  },
  onRoomPick(e) {
    const opt = this.data.roomOptions[Number(e.detail.value)]
    if (!opt) return
    this.setData({ roomId: String(opt.roomId), roomLabel: opt.label })
  },
  onCat(e) {
    const name = this.data.categories[Number(e.detail.value)]
    const patch = applyCategory(name)
    if (!patch.needLocation) patch.location = ''
    this.setData(patch)
    wx.setNavigationBarTitle({ title: patch.pageTitle })
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
        // 默认选中「我的」页当前选择的房屋（lastResidentRoom），没有则取第一套
        const last = readLastResident()
        const hit = last
          ? roomOptions.find((o) => Number(o.roomId) === Number(last.roomId))
          : null
        const pick = hit || roomOptions[0]
        patch.roomId = String(pick.roomId)
        patch.roomLabel = pick.label
      }
      this.setData(patch)
    } catch (e) {
      this.setData({ roomOptions: [] })
    }
  },

  async submit() {
    if (!this.data.roomId) {
      wx.showToast({ title: '请选择房屋', icon: 'none' })
      return
    }
    if (!this.data.content || !String(this.data.content).trim()) {
      wx.showToast({ title: '请填写' + this.data.descLabel, icon: 'none' })
      return
    }

    this.setData({ submitting: true })
    try {
      const attachmentIds = this.getAttachmentIds()
      if (this.data.kind === 'repair') {
        await request({
          url: '/resident/repairs',
          method: 'POST',
          data: {
            roomId: Number(this.data.roomId),
            category: this.data.category,
            location: null,
            description: String(this.data.content).trim(),
            attachmentIds
          }
        })
      } else {
        await request({
          url: '/resident/complaints',
          method: 'POST',
          data: {
            roomId: Number(this.data.roomId),
            category: this.data.category,
            content: String(this.data.content).trim(),
            attachmentIds
          }
        })
      }
      wx.showToast({ title: '已提交' })
      this.resetPhotos()
      setTimeout(() => wx.navigateBack({ delta: 1 }), 500)
    } catch (e) {
      wx.showModal({ title: '提交失败', content: (e && e.message) || '请稍后重试', showCancel: false })
    } finally {
      this.setData({ submitting: false })
    }
  }
}))
