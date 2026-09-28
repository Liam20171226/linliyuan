const { request } = require('../../utils/request')
const { ensureLogin, roleLabel } = require('../../utils/format')
const { loadIdentities, roomIdOf } = require('../../utils/identity')

const ACTIONS = [
  { type: 'ADD_MEMBER', name: '增加成员', hint: '添加家属/租户' },
  { type: 'REMOVE_MEMBER', name: '移除成员', hint: '解除本房绑定' },
  { type: 'ADD_VEHICLE', name: '增加车辆', hint: '登记车牌' },
  { type: 'REMOVE_VEHICLE', name: '移除车辆', hint: '删除本房车辆' },
  { type: 'LINK_PARKING', name: '关联车位', hint: '挂未占用车位' },
  { type: 'UNLINK_PARKING', name: '取消关联车位', hint: '解绑本房车位' }
]

Page({
  data: {
    loading: false,
    loggedIn: false,
    isOwner: false,
    roomId: null,
    address: '',
    myRoleLabel: '',
    members: [],
    vehicles: [],
    parkingSpaces: [],
    actions: ACTIONS
  },

  onShow() {
    if (!ensureLogin()) {
      this.setData({ loggedIn: false })
      return
    }
    this.setData({ loggedIn: true })
    this.load()
  },

  onPullDownRefresh() {
    this.load().finally(() => wx.stopPullDownRefresh())
  },

  async load() {
    this.setData({ loading: true })
    try {
      const packed = await loadIdentities()
      const active = packed.active || packed.current || {}
      const roomId = packed.currentRoomId || roomIdOf(active)
      if (!roomId || active.identityType !== 'RESIDENT') {
        this.setData({
          roomId: null,
          isOwner: false,
          address: '',
          myRoleLabel: '',
          members: [],
          vehicles: [],
          parkingSpaces: []
        })
        return
      }
      const detail = await request({ url: `/resident/rooms/${roomId}` })
      const isOwner = detail.myRole === 'OWNER'
      const members = (detail.members || []).map((m) => ({
        ...m,
        roleLabel: roleLabel(m.residentRole)
      }))
      const vehicles = (detail.vehicles || []).map((v) => ({
        ...v,
        spaceLabel: v.parkingSpaceId ? `车位#${v.parkingSpaceId}` : '未绑车位'
      }))
      this.setData({
        roomId,
        isOwner,
        address: detail.address || detail.roomNo || '',
        myRoleLabel: roleLabel(detail.myRole),
        members,
        vehicles,
        parkingSpaces: detail.parkingSpaces || []
      })
    } catch (e) {
      wx.showToast({ title: (e && e.message) || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  onAction(e) {
    const type = e.currentTarget.dataset.type
    if (!this.data.isOwner) {
      wx.showToast({ title: '仅业主可申请变更', icon: 'none' })
      return
    }
    if (!this.data.roomId) return
    wx.navigateTo({
      url: `/pages/room-change-apply/room-change-apply?type=${type}&roomId=${this.data.roomId}`
    })
  },

  goAuth() {
    wx.navigateTo({ url: '/pages/auth-apply/auth-apply' })
  }
})
