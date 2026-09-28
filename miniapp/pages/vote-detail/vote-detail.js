const { request } = require('../../utils/request')
const { voteStatusLabel, roleLabel, formatDateTime } = require('../../utils/format')

function sourceLabelOf(role) {
  if (role === 'COMMITTEE') return '业委会'
  if (role === 'PROPERTY_MANAGER' || role === 'STAFF') return '物业'
  if (role === 'PLATFORM') return '平台'
  return '业主投票'
}

function shortRange(startAt, endAt) {
  const a = formatDateTime(startAt)
  const b = formatDateTime(endAt)
  const as = a ? a.slice(0, 16) : ''
  const bs = b ? b.slice(0, 16) : ''
  if (as && bs) return `${as} ~ ${bs}`
  return as || bs || ''
}

function syncRoomVoteState(roomId, ballotsByRoom, pendingOptionId) {
  const rid = roomId != null ? String(roomId) : ''
  const lockedOptionId = rid && ballotsByRoom[rid] != null ? String(ballotsByRoom[rid]) : ''
  const locked = !!lockedOptionId
  return {
    locked,
    selectedOptionId: locked ? lockedOptionId : (pendingOptionId || ''),
    canConfirm: !locked && !!pendingOptionId
  }
}

Page({
  data: {
    id: null,
    loading: true,
    error: '',
    title: '',
    background: '',
    statusLabel: '',
    sourceLabel: '',
    rangeLabel: '',
    options: [],
    roomId: '',
    roomLabel: '',
    roomOptions: [],
    /** roomId -> optionId */
    ballotsByRoom: {},
    selectedOptionId: '',
    locked: false,
    canConfirm: false,
    votedRoomCount: 0,
    casting: false,
    closed: false
  },

  onLoad(q) {
    const id = q && q.id ? Number(q.id) : null
    const roomId = q && q.roomId ? String(q.roomId) : ''
    this.setData({ id, roomId })
    wx.setNavigationBarTitle({ title: '投票明细' })
    if (!id) {
      this.setData({ loading: false, error: '无效投票' })
    }
  },

  onShow() {
    if (this.data.id) this.bootstrap()
  },

  onPullDownRefresh() {
    this.bootstrap().finally(() => wx.stopPullDownRefresh())
  },

  onRoomPick(e) {
    const opt = this.data.roomOptions[Number(e.detail.value)]
    if (!opt) return
    const roomId = String(opt.roomId)
    const state = syncRoomVoteState(roomId, this.data.ballotsByRoom, '')
    this.setData(Object.assign({
      roomId,
      roomLabel: opt.label,
      options: this.markOptions(this.data.options, state.selectedOptionId, state.locked)
    }, state))
  },

  selectOption(e) {
    if (this.data.locked || this.data.closed || this.data.casting) return
    const optionId = e.currentTarget.dataset.oid
    if (optionId == null) return
    const selectedOptionId = String(optionId)
    this.setData({
      selectedOptionId,
      canConfirm: true,
      options: this.markOptions(this.data.options, selectedOptionId, false)
    })
  },

  markOptions(options, selectedOptionId, locked) {
    const sid = selectedOptionId != null ? String(selectedOptionId) : ''
    return (options || []).map((o) => Object.assign({}, o, {
      selected: sid !== '' && String(o.id) === sid,
      locked: !!locked && sid !== '' && String(o.id) === sid
    }))
  },

  async bootstrap() {
    this.setData({ loading: true, error: '' })
    try {
      await this.loadRooms()
      await this.loadDetail()
    } catch (err) {
      this.setData({ error: (err && err.message) || '加载失败' })
    } finally {
      this.setData({ loading: false })
    }
  },

  async loadRooms() {
    try {
      const rooms = await request({ url: '/resident/rooms' })
      const owners = (rooms || []).filter((r) => r.residentRole === 'OWNER')
      const source = owners.length ? owners : (rooms || [])
      const roomOptions = source.map((r) => ({
        roomId: r.roomId,
        label: `${r.address || r.roomNo || ('房屋' + r.roomId)}（${roleLabel(r.residentRole)}）`
      }))
      const patch = { roomOptions }
      if (roomOptions.length) {
        const hit = this.data.roomId
          ? roomOptions.find((o) => String(o.roomId) === String(this.data.roomId))
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

  async loadDetail() {
    const raw = await request({ url: `/resident/votes/${this.data.id}` })
    const options = ((raw && raw.options) || []).map((o) => ({
      id: o.id,
      optionText: o.optionText || o.text || ''
    }))
    const myBallots = (raw && raw.myBallots) || []
    const ballotsByRoom = {}
    myBallots.forEach((b) => {
      if (b && b.roomId != null && b.optionId != null) {
        ballotsByRoom[String(b.roomId)] = String(b.optionId)
      }
    })
    const status = raw && raw.status
    const closed = status === 'ENDED' || status === 'CLOSED'
    const roomId = this.data.roomId
    const state = syncRoomVoteState(roomId, ballotsByRoom, '')
    wx.setNavigationBarTitle({ title: '投票明细' })
    this.setData(Object.assign({
      title: raw.title || '投票',
      background: raw.background || '',
      statusLabel: voteStatusLabel(status),
      sourceLabel: sourceLabelOf(raw.creatorRole),
      rangeLabel: shortRange(raw.startAt, raw.endAt),
      options: this.markOptions(options, state.selectedOptionId, state.locked),
      ballotsByRoom,
      votedRoomCount: Object.keys(ballotsByRoom).length,
      closed
    }, state))
  },

  async confirm() {
    if (this.data.casting || this.data.locked || this.data.closed) return
    if (!this.data.roomId) {
      wx.showToast({ title: '请先选择投票房屋', icon: 'none' })
      return
    }
    if (!this.data.selectedOptionId) {
      wx.showToast({ title: '请先选择选项', icon: 'none' })
      return
    }
    this.setData({ casting: true })
    try {
      await request({
        url: `/resident/votes/${this.data.id}/ballots`,
        method: 'POST',
        data: {
          ballots: [{
            roomId: Number(this.data.roomId),
            optionId: Number(this.data.selectedOptionId)
          }]
        }
      })
      wx.showToast({ title: '投票成功' })
      await this.loadDetail()
    } catch (err) {
      wx.showModal({ title: '投票失败', content: (err && err.message) || '请稍后重试', showCancel: false })
    } finally {
      this.setData({ casting: false })
    }
  }
})
