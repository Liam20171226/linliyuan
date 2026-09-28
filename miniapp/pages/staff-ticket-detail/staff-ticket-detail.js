const { request } = require('../../utils/request')
const { repairStatusLabel, complaintStatusLabel, formatDateTime } = require('../../utils/format')
const photoAttach = require('../../utils/photo-attach')

const ROLE_OPTIONS = [
  { code: 'SECURITY', label: '保安岗' },
  { code: 'CLEANING', label: '保洁岗' },
  { code: 'LANDSCAPING', label: '绿化岗' },
  { code: 'FACILITY_MAINT', label: '机电维修岗' },
  { code: 'CUSTOMER_SERVICE', label: '客服岗' },
  { code: 'PROPERTY_MANAGER', label: '物业经理' }
]

const ROLE_LABEL = {}
ROLE_OPTIONS.forEach((r) => { ROLE_LABEL[r.code] = r.label })

/** 平台管理员 / 物业经理 / 客服：可直接办理（回复即处理），办理不了再转派；最终处理完成 */
const OFFICE_ROLES = ['PROPERTY_MANAGER', 'CUSTOMER_SERVICE']

Page(Object.assign({}, photoAttach, {
  data: Object.assign({}, photoAttach.data, {
    id: null,
    kind: 'repair', // repair | complaint
    readonly: false, // 业委会只读
    order: null,
    timeline: [],
    orderImages: [],
    dispatches: [],
    replies: [],
    memberNames: {},
    myRoles: [],
    isPlatform: false,
    canClaim: false,
    canReply: false,
    canTransfer: false,
    canFinish: false,
    assigneeRoleLabel: '',
    replyText: '',
    transferOpen: false,
    transferRole: null,
    transferRemark: '',
    roleOptions: ROLE_OPTIONS,
    submitting: false
  }),

  onLoad(q) {
    const id = q && q.id ? Number(q.id) : null
    const kind = (q && q.kind) === 'complaint' ? 'complaint' : 'repair'
    const readonly = !!(q && (q.readonly === '1' || q.from === 'committee'))
    this.setData({ id, kind, readonly })
    wx.setNavigationBarTitle({
      title: readonly
        ? (kind === 'complaint' ? '工单查看' : '报修查看')
        : (kind === 'complaint' ? '工单详情' : '报修详情')
    })
  },

  onShow() {
    if (this.data.id) this.load()
  },

  onPullDownRefresh() {
    this.load().finally(() => wx.stopPullDownRefresh())
  },

  apiPrefix() {
    if (this.data.readonly) return '/committee/tickets'
    return this.data.kind === 'complaint' ? '/staff/complaints' : '/staff/repairs'
  },

  async load() {
    try {
      const apiBase = getApp().globalData.apiBase || ''
      const token = getApp().globalData.token || wx.getStorageSync('token') || ''
      let d
      try {
        d = await request({ url: `${this.apiPrefix()}/${this.data.id}` })
      } catch (e) {
        // 待办可能未带 kind：报修失败时再试投诉（仅物业端）
        if (!this.data.readonly && this.data.kind === 'repair') {
          d = await request({ url: `/staff/complaints/${this.data.id}` })
          this.setData({ kind: 'complaint' })
        } else {
          throw e
        }
      }
      const order = d.order || {}
      if (order.kind === 'COMPLAINT') this.setData({ kind: 'complaint' })
      if (order.kind === 'REPAIR') this.setData({ kind: 'repair' })

      const status = order.status
      const active = ['ASSIGNED', 'IN_PROGRESS', 'PENDING_ASSIGN', 'PENDING', 'PROCESSING', 'REPLIED'].indexOf(status) >= 0
      const claimed = !!order.assigneeUserId

      let myRoles = []
      let isPlatform = false
      if (!this.data.readonly) {
        try {
          const idn = await request({ url: '/auth/identities' })
          const cur = idn.current || {}
          isPlatform = cur.identityType === 'PLATFORM'
          const me = (idn.identities || []).find((it) =>
            it.identityType === cur.identityType &&
            String(it.communityId) === String(cur.communityId))
          myRoles = (me && me.staffRoles) || []
        } catch (e) {
          myRoles = []
        }
      }

      const office = isPlatform || myRoles.some((r) => OFFICE_ROLES.includes(r))
      const myRoleSet = new Set(myRoles)
      const roleMatches = !!order.assigneeRole && myRoleSet.has(order.assigneeRole)
      const readonly = !!this.data.readonly

      const buildUrl = (a) => ({ ...a, url: `${apiBase}/attachments/${a.id}?token=${token}` })

      this.setData({
        order,
        timeline: (d.timeline || []).map((n) => ({
          ...n,
          images: (n.images || []).map(buildUrl)
        })),
        orderImages: (d.attachments || []).map(buildUrl),
        replies: d.replies || [],
        dispatches: (d.dispatches || []).map((it) => ({
          ...it,
          toText: it.toUserId
            ? (it.toRole ? ROLE_LABEL[it.toRole] || '' : '')
            : (ROLE_LABEL[it.toRole] || it.toRole || '待接单')
        })),
        myRoles,
        isPlatform,
        canClaim: !readonly && !office && status === 'ASSIGNED' && !claimed && !!order.assigneeRole && roleMatches,
        canReply: !readonly && active && (office || claimed || roleMatches || status === 'PENDING_ASSIGN' || status === 'PENDING'),
        canTransfer: !readonly && active && office,
        canFinish: !readonly && (this.data.kind === 'complaint'
          ? ['PENDING', 'ASSIGNED', 'PROCESSING', 'REPLIED'].indexOf(status) >= 0
          : ['ASSIGNED', 'IN_PROGRESS'].indexOf(status) >= 0),
        assigneeRoleLabel: order.assigneeRole ? (ROLE_LABEL[order.assigneeRole] || order.assigneeRole) : ''
      })
      if (!readonly) this.loadMembers()
    } catch (e) {
      wx.showToast({ title: (e && e.message) || '加载失败', icon: 'none' })
    }
  },

  async loadMembers() {
    try {
      const d = await request({ url: '/staff/team' })
      const list = (d && d.members) || []
      const names = {}
      list.forEach((it) => {
        names[String(it.userId)] = it.realName || it.mobile || ('员工' + it.userId)
      })
      this.setData({ memberNames: names })
    } catch (e) {
      this.setData({ memberNames: {} })
    }
  },

  onReplyInput(e) { this.setData({ replyText: e.detail.value }) },
  onRemarkInput(e) { this.setData({ transferRemark: e.detail.value }) },

  async claim() {
    const res = await new Promise((resolve) => {
      wx.showModal({
        title: '确认接单',
        content: '接单后由我负责处理该工单',
        success: (r) => resolve(r.confirm),
        fail: () => resolve(false)
      })
    })
    if (!res) return
    this.setData({ submitting: true })
    try {
      await request({ url: `${this.apiPrefix()}/${this.data.id}/claim`, method: 'POST', data: {} })
      wx.showToast({ title: '已接单' })
      this.load()
    } catch (e) {
      wx.showToast({ title: (e && e.message) || '接单失败', icon: 'none' })
    } finally {
      this.setData({ submitting: false })
    }
  },

  async submitReply() {
    const content = String(this.data.replyText || '').trim()
    if (!content) {
      wx.showToast({ title: '请填写处理留言', icon: 'none' })
      return
    }
    this.setData({ submitting: true })
    try {
      if (this.data.kind === 'complaint') {
        await request({
          url: `/staff/complaints/${this.data.id}/handle`,
          method: 'POST',
          data: { replyContent: content, attachmentIds: this.getAttachmentIds() }
        })
      } else {
        await request({
          url: `/staff/repairs/${this.data.id}/replies`,
          method: 'POST',
          data: { content, attachmentIds: this.getAttachmentIds() }
        })
      }
      this.setData({ replyText: '' })
      this.resetPhotos()
      wx.showToast({ title: '已提交' })
      this.load()
    } catch (e) {
      wx.showToast({ title: (e && e.message) || '提交失败', icon: 'none' })
    } finally {
      this.setData({ submitting: false })
    }
  },

  openTransfer() {
    this.setData({ transferOpen: true })
  },
  closeTransfer() {
    this.setData({ transferOpen: false })
  },
  pickRole(e) {
    this.setData({ transferRole: e.currentTarget.dataset.code })
  },

  async submitTransfer() {
    const role = this.data.transferRole
    if (!role) {
      wx.showToast({ title: '请选择转派岗位', icon: 'none' })
      return
    }
    this.setData({ submitting: true })
    try {
      await request({
        url: `${this.apiPrefix()}/${this.data.id}/assign`,
        method: 'POST',
        data: { assigneeRole: role, remark: this.data.transferRemark }
      })
      this.setData({ transferOpen: false, transferRole: null, transferRemark: '' })
      wx.showToast({ title: '已转派' })
      this.load()
    } catch (e) {
      wx.showToast({ title: (e && e.message) || '转派失败', icon: 'none' })
    } finally {
      this.setData({ submitting: false })
    }
  },

  async finish() {
    const isPraise = this.data.order && this.data.order.category === '表扬'
    const res = await new Promise((resolve) => {
      wx.showModal({
        title: '处理完成',
        content: isPraise
          ? '请确认事项已办理完毕。表扬不参与满意度评价，完成后住户可查看完整流程。'
          : '请确认事项已办理完毕，即将发送给住户进行满意度评价',
        confirmText: '确认完成',
        cancelText: '取消',
        success: (r) => resolve(r.confirm),
        fail: () => resolve(false)
      })
    })
    if (!res) return
    this.setData({ submitting: true })
    try {
      await request({ url: `${this.apiPrefix()}/${this.data.id}/complete`, method: 'POST', data: {} })
      wx.showToast({ title: isPraise ? '已处理完成' : '已完成，待业主评价' })
      this.load()
    } catch (e) {
      wx.showToast({ title: (e && e.message) || '操作失败', icon: 'none' })
    } finally {
      this.setData({ submitting: false })
    }
  },

  onPreviewSection(e) {
    const urls = (e.currentTarget.dataset.urls || []).map((u) => u.url || u)
    const url = e.currentTarget.dataset.url
    if (!urls.length) return
    wx.previewImage({ current: url || urls[0], urls })
  },

  memberName(uid) {
    return this.data.memberNames[String(uid)] || uid
  },

  fmt(v) {
    const s = formatDateTime(v)
    return s ? String(s).slice(0, 16) : ''
  },

  statusLabel(s) {
    return this.data.kind === 'complaint' ? complaintStatusLabel(s) : repairStatusLabel(s)
  },
  roleLabelOf(code) { return ROLE_LABEL[code] || code || '' }
}))
