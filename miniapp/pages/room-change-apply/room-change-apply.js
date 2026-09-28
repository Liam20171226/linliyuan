const { request } = require('../../utils/request')
const { ensureLogin, roleLabel } = require('../../utils/format')

const TYPE_META = {
  ADD_MEMBER: { title: '增加成员', needSelect: false },
  REMOVE_MEMBER: { title: '移除成员', needSelect: 'member' },
  ADD_VEHICLE: { title: '增加车辆', needSelect: false },
  REMOVE_VEHICLE: { title: '移除车辆', needSelect: 'vehicle' },
  LINK_PARKING: { title: '关联车位', needSelect: 'unlinked' },
  UNLINK_PARKING: { title: '取消关联车位', needSelect: 'parking' }
}

const MEMBER_ROLES = [
  { label: '业主成员', value: 'OWNER_MEMBER' },
  { label: '租户', value: 'TENANT' },
  { label: '租户成员', value: 'TENANT_MEMBER' }
]

Page({
  data: {
    type: '',
    title: '',
    roomId: null,
    loading: true,
    submitting: false,
    applyMessage: '',
    name: '',
    mobile: '',
    nameLocked: false,
    lookupHint: '',
    roleIndex: 0,
    roleLabels: MEMBER_ROLES.map((r) => r.label),
    members: [],
    vehicles: [],
    parkings: [],
    unlinked: [],
    memberIndex: 0,
    vehicleIndex: 0,
    parkingIndex: 0,
    unlinkedIndex: 0,
    plateNo: '',
    bindSpace: false,
    freeSpaces: [],
    freeSpaceIndex: 0,
    freeSpaceLabels: ['不绑车位']
  },

  onLoad(q) {
    const type = (q && q.type) || ''
    const meta = TYPE_META[type]
    if (!meta) {
      wx.showModal({
        title: '不支持的类型',
        content: '请从房屋数据维护重新进入',
        showCancel: false,
        success: () => wx.navigateBack()
      })
      return
    }
    this.type = type
    this._lookupSeq = 0
    this.setData({
      type,
      title: meta.title,
      roomId: q.roomId ? Number(q.roomId) : null
    })
    wx.setNavigationBarTitle({ title: meta.title })
    this.bootstrap()
  },

  async bootstrap() {
    if (!ensureLogin()) return
    this.setData({ loading: true })
    try {
      const roomId = this.data.roomId
      if (!roomId) throw new Error('缺少房屋')
      const detail = await request({ url: `/resident/rooms/${roomId}` })
      if (detail.myRole !== 'OWNER') {
        throw new Error('仅当前房业主可提交变更')
      }
      const members = (detail.members || [])
        .filter((m) => m.residentRole !== 'OWNER')
        .map((m) => ({
          ...m,
          label: `${m.realName || '未命名'} · ${roleLabel(m.residentRole)} · ${m.mobile || ''}`
        }))
      const vehicles = (detail.vehicles || []).map((v) => ({
        ...v,
        label: `${v.plateNo}${v.parkingSpaceId ? '（已绑位）' : ''}`
      }))
      const parkings = (detail.parkingSpaces || []).map((p) => ({
        ...p,
        label: p.spaceNo || `车位#${p.id}`
      }))
      const freeSpaces = (detail.parkingSpaces || []).filter((p) => {
        const used = (detail.vehicles || []).some((v) => Number(v.parkingSpaceId) === Number(p.id))
        return !used
      })
      const freeSpaceLabels = ['不绑车位'].concat(freeSpaces.map((p) => p.spaceNo || `车位#${p.id}`))

      let unlinked = []
      if (this.type === 'LINK_PARKING') {
        unlinked = (await request({ url: '/resident/parking-spaces/unlinked' }) || []).map((p) => ({
          ...p,
          label: p.spaceNo || `车位#${p.id}`
        }))
      }

      this.setData({
        members,
        vehicles,
        parkings,
        unlinked,
        freeSpaces,
        freeSpaceLabels,
        memberIndex: 0,
        vehicleIndex: 0,
        parkingIndex: 0,
        unlinkedIndex: 0,
        freeSpaceIndex: 0
      })
    } catch (e) {
      wx.showModal({
        title: '无法申请',
        content: (e && e.message) || '请稍后重试',
        showCancel: false,
        success: () => wx.navigateBack()
      })
    } finally {
      this.setData({ loading: false })
    }
  },

  onName(e) {
    if (this.data.nameLocked) return
    this.setData({ name: e.detail.value })
  },
  onMobile(e) {
    const mobile = (e.detail.value || '').trim()
    this.setData({ mobile })
    this.scheduleLookup(mobile)
  },
  onPlate(e) { this.setData({ plateNo: e.detail.value }) },
  onMsg(e) { this.setData({ applyMessage: e.detail.value }) },
  onRole(e) { this.setData({ roleIndex: Number(e.detail.value) }) },
  onMember(e) { this.setData({ memberIndex: Number(e.detail.value) }) },
  onVehicle(e) { this.setData({ vehicleIndex: Number(e.detail.value) }) },
  onParking(e) { this.setData({ parkingIndex: Number(e.detail.value) }) },
  onUnlinked(e) { this.setData({ unlinkedIndex: Number(e.detail.value) }) },
  onFreeSpace(e) { this.setData({ freeSpaceIndex: Number(e.detail.value) }) },

  scheduleLookup(mobile) {
    if (this.type !== 'ADD_MEMBER') return
    if (this._lookupTimer) clearTimeout(this._lookupTimer)
    const seq = ++this._lookupSeq
    if (!/^1\d{10}$/.test(mobile)) {
      this.setData({
        lookupHint: '',
        nameLocked: false
      })
      return
    }
    this.setData({ lookupHint: '正在匹配账号…' })
    this._lookupTimer = setTimeout(() => this.lookupByMobile(mobile, seq), 280)
  },

  async lookupByMobile(mobile, seq) {
    try {
      const data = await request({
        url: '/resident/users/by-mobile',
        data: { mobile }
      })
      if (seq !== this._lookupSeq) return
      if (data && data.exists) {
        const realName = (data.realName || '').trim()
        this.setData({
          name: realName || this.data.name,
          nameLocked: !!realName,
          lookupHint: realName ? '已匹配系统账号，姓名已带入' : '已匹配系统账号，请补充姓名'
        })
      } else {
        this.setData({
          nameLocked: false,
          lookupHint: '未找到已有账号，请填写姓名'
        })
      }
    } catch (e) {
      if (seq !== this._lookupSeq) return
      this.setData({
        nameLocked: false,
        lookupHint: (e && e.message) || '匹配失败，可手动填写'
      })
    }
  },

  buildPayload() {
    const d = this.data
    switch (this.type) {
      case 'ADD_MEMBER': {
        const mobile = (d.mobile || '').trim()
        if (!/^1\d{10}$/.test(mobile)) throw new Error('手机号须为 1 开头的 11 位数字')
        const name = (d.name || '').trim()
        if (!name) throw new Error('请填写姓名')
        return {
          name,
          mobile,
          resident_role: MEMBER_ROLES[d.roleIndex].value
        }
      }
      case 'REMOVE_MEMBER': {
        if (!d.members.length) throw new Error('本房无可移除成员')
        return { occupant_id: d.members[d.memberIndex].occupantId }
      }
      case 'ADD_VEHICLE': {
        const plate = (d.plateNo || '').trim().toUpperCase()
        if (!plate) throw new Error('请填写车牌')
        const payload = { plate_no: plate }
        if (d.freeSpaceIndex > 0) {
          payload.parking_space_id = d.freeSpaces[d.freeSpaceIndex - 1].id
        }
        return payload
      }
      case 'REMOVE_VEHICLE': {
        if (!d.vehicles.length) throw new Error('本房无车辆')
        return { vehicle_id: d.vehicles[d.vehicleIndex].id }
      }
      case 'LINK_PARKING': {
        if (!d.unlinked.length) throw new Error('暂无未挂房车位')
        return { parking_space_id: d.unlinked[d.unlinkedIndex].id }
      }
      case 'UNLINK_PARKING': {
        if (!d.parkings.length) throw new Error('本房无已挂车位')
        return { parking_space_id: d.parkings[d.parkingIndex].id }
      }
      default:
        throw new Error('不支持的类型')
    }
  },

  async submit() {
    if (this.data.submitting) return
    let payload
    try {
      payload = this.buildPayload()
    } catch (e) {
      wx.showToast({ title: e.message || '请检查填写', icon: 'none' })
      return
    }
    this.setData({ submitting: true })
    try {
      await request({
        url: '/resident/room-change-applications',
        method: 'POST',
        data: {
          changeType: this.type,
          payload,
          applyMessage: (this.data.applyMessage || '').trim() || undefined
        }
      })
      wx.showToast({ title: '已提交', icon: 'success' })
      setTimeout(() => {
        wx.navigateBack()
      }, 500)
    } catch (e) {
      wx.showModal({
        title: '提交失败',
        content: (e && e.message) || '请稍后重试',
        showCancel: false
      })
    } finally {
      this.setData({ submitting: false })
    }
  }
})
