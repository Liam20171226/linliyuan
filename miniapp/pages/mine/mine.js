const { request } = require('../../utils/request')
const { loadIdentities, switchIdentity, updateRealName } = require('../../utils/identity')

/** 结果类 / 投票待办：点按即知晓并清除（投票同时跳转投票页） */
const DISMISS_TODO_TYPES = {
  AUTH_RESULT: true,
  ROOM_CHANGE_RESULT: true,
  PUBLIC_REVENUE_PUBLISHED: true,
  COMPLAINT_REPLY: true,
  REPAIR_REPLY: true,
  VOTE_RESULT: true,
  VOTE_START: true,
  VOTE_NEAR_DEADLINE: true
}

/** 行动类：跳转对应页，并标记已读（仍可保留 OPEN 直至业务完成） */
const TODO_NAV = {
  BILL_DUE: '/pages/bills/bills',
  VOTE_START: '/pages/votes/votes',
  VOTE_NEAR_DEADLINE: '/pages/votes/votes',
  AUTH_RESULT: '/pages/approval-records/approval-records',
  ROOM_CHANGE_RESULT: '/pages/approval-records/approval-records?tab=change',
  PUBLIC_REVENUE_REVIEW: '/pages/finance-public/finance-public',
  PUBLIC_REVENUE_PUBLISHED: '/pages/finance-public/finance-public',
  PENDING_REVIEW: '/pages/approval-records/approval-records'
}

/** 投票待办：有 bizId 直达投票详情，否则进投票列表 */
function voteDetailNav(todo) {
  const type = (todo && todo.todoType) || ''
  if (type !== 'VOTE_START' && type !== 'VOTE_NEAR_DEADLINE' && type !== 'VOTE_RESULT') return null
  const bizId = todo && todo.bizId
  if (bizId == null || bizId === '') return '/pages/votes/votes'
  return '/pages/vote-detail/vote-detail?id=' + bizId
}

/**
 * 工单类待办：点击直接进入记录详情（不再先跳列表）。
 * 住户：处理完成后的待评价 → service-detail
 * 物业：新单 / 派单 / 升级 → staff-ticket-detail
 */
function ticketDetailNav(todo) {
  const bizId = todo && todo.bizId
  if (bizId == null || bizId === '') return null
  const type = (todo && todo.todoType) || ''
  if (type === 'REPAIR_RATE') {
    return '/pages/service-detail/service-detail?id=' + bizId + '&kind=repair'
  }
  if (type === 'COMPLAINT_RATE') {
    return '/pages/service-detail/service-detail?id=' + bizId + '&kind=complaint'
  }
  if (type === 'REPAIR_NEW' || type === 'REPAIR_ASSIGNED' || type === 'REPAIR_ESCALATE') {
    return '/pages/staff-ticket-detail/staff-ticket-detail?id=' + bizId + '&kind=repair'
  }
  if (type === 'COMPLAINT_NEW' || type === 'COMPLAINT_ASSIGNED') {
    return '/pages/staff-ticket-detail/staff-ticket-detail?id=' + bizId + '&kind=complaint'
  }
  return null
}

/** 物业身份的岗位编码列表 */
function staffRolesOf(it) {
  if (!it) return []
  if (Array.isArray(it.staffRoles) && it.staffRoles.length) return it.staffRoles
  if (it.staffRole) return [it.staffRole]
  return []
}

/** 同一条记录（bizType+bizId）只保留最新一条待办，避免旧通知堆积 */
function dedupeTodoByRecord(list) {
  const out = []
  const seen = {}
  ;(list || []).forEach((t) => {
    const key = t.bizType && t.bizId != null ? t.bizType + ':' + t.bizId : null
    if (!key) {
      out.push(t)
      return
    }
    const idx = seen[key]
    if (idx === undefined) {
      seen[key] = out.length
      out.push(t)
    } else if (Number(t.id) > Number(out[idx].id)) {
      out[idx] = t
    }
  })
  return out
}

Page({
  data: {
    loggedIn: false,
    identities: [],
    todos: [],
    displayName: '邻里院用户',
    personName: '',
    mobile: '',
    avatarText: '邻',
    contextLine: '',
    pickerVisible: false,
    pickerList: [],
    showMyRooms: false,
    hasRooms: false,
    currentRoomId: null,
    isCommittee: false,
    isStaff: false,
    showResidentRecords: true,
    showStaffWorkRecords: false,
    showStaffAuth: false,
    addressLine: '',
    roleLine: ''
  },

  onLoad() {
    this.syncTabBar(this.hasToken())
  },

  onShow() {
    // 先同步一次底部栏，再拉数据：即便 refresh 中途出错也不会卡在隐藏状态
    this.syncTabBar(this.hasToken())
    this.refresh()
  },

  /** 是否有登录态 */
  hasToken() {
    try {
      const app = getApp()
      return !!(app.globalData.token || wx.getStorageSync('token'))
    } catch (e) {
      return false
    }
  },

  /** 未登录时不展示底部 tabBar，登录后恢复 */
  syncTabBar(loggedIn) {
    try {
      if (loggedIn) wx.showTabBar({ animation: false })
      else wx.hideTabBar({ animation: false })
    } catch (e) { /* 非 tab 页调用会失败，忽略 */ }
  },

  onPullDownRefresh() {
    this.refresh().finally(() => wx.stopPullDownRefresh())
  },

  async refresh() {
    const app = getApp()
    const token = app.globalData.token || wx.getStorageSync('token')
    if (!token) {
      this.setData({
        loggedIn: false,
        identities: [],
        todos: [],
        displayName: '未登录',
        personName: '',
        mobile: '',
        avatarText: '邻',
        contextLine: '',
        pickerVisible: false,
        pickerList: [],
        showMyRooms: false,
        hasRooms: false,
        currentRoomId: null,
        isCommittee: false,
        addressLine: '',
        roleLine: ''
      })
      this.syncTabBar(false)
      // 未登录不允许停在本页（底部栏已隐藏，会无路可走），直接回首页访客态
      wx.switchTab({ url: '/pages/index/index' })
      return
    }

    this.syncTabBar(true)

    let identities = []
    let todos = []
    let displayName = '邻里院用户'
    let personName = '邻居'
    let mobile = ''
    let contextLine = '已登录'
    let addressLine = ''
    let roleLine = ''
    let pickerList = []
    let showMyRooms = false
    let hasRooms = false
    let identityType = ''
    let currentRoomId = null
    let communityId = null
    let isCommittee = false
    let isStaff = false
    let showResidentRecords = true
    let showStaffWorkRecords = false
    let showStaffAuth = false

    try {
      const packed = await loadIdentities()
      identities = packed.raw
      pickerList = packed.identities
      contextLine = packed.contextLine
      app.globalData.identities = identities
      app.globalData.user = packed.user
      displayName = packed.displayName || '邻里院用户'
      personName = packed.personName || '邻居'
      mobile = (packed.user && packed.user.mobile) || ''
      identityType = (packed.current && packed.current.identityType)
        || (packed.active && packed.active.identityType)
        || ''
      currentRoomId = packed.currentRoomId
      communityId = packed.current && packed.current.communityId
      isCommittee = !!packed.isCommittee
        || identityType === 'COMMITTEE'
        || !!(packed.identities || []).some(
          (it) => it.isCommittee && (
            communityId == null || Number(it.communityId) === Number(communityId)
          )
        )
      isStaff = identityType === 'STAFF'
      // 物业 / 平台：工作记录；住户侧个人记录不展示（切回住户身份再查）
      showStaffWorkRecords = identityType === 'STAFF' || identityType === 'PLATFORM'
      showResidentRecords = !showStaffWorkRecords
      if (showStaffWorkRecords) {
        const roles = staffRolesOf(packed.current || packed.active)
        // 与首页一致：住户认证 / 人员车辆仅客服岗；平台管理员全开
        showStaffAuth = identityType === 'PLATFORM' || roles.indexOf('CUSTOMER_SERVICE') >= 0
      }
      showMyRooms = ['RESIDENT', 'COMMITTEE', 'GUEST'].indexOf(identityType) >= 0 || isCommittee
      // 身份列表已按房展开，有住户房即可视为已绑房
      hasRooms = (pickerList || []).some((it) => it.identityType === 'RESIDENT' && it.roomId != null)

      const active = packed.active
      if (active) {
        addressLine = active.roomHint || active.label || ''
        const rl = String(active.roleLabel || '')
        const parts = rl.split(' · ').filter(Boolean)
        roleLine = parts.filter((p) => p.indexOf('/') < 0 && p !== active.label).join(' · ')
        if (!addressLine) addressLine = contextLine || displayName
      }
    } catch (e) {
      /* ignore */
    }

    if (showMyRooms && !hasRooms) {
      try {
        const rawRooms = await request({ url: '/resident/rooms' })
        hasRooms = !!(rawRooms && rawRooms.length)
      } catch (e) {
        hasRooms = false
      }
    }

    try {
      const todoData = await request({ url: '/todos', data: { status: 'OPEN' } })
      // 同一条记录（同一详情页）只展示最新一条待办
      const rawList = dedupeTodoByRecord((todoData && todoData.list) || [])
      todos = rawList.map((t) => {
        const type = t.todoType || t.todo_type || ''
        const title = String(t.title || '')
        const isBill = type === 'BILL_DUE' || title.indexOf('账单') >= 0
        let hint = ''
        if (!isBill) {
          if (type === 'VOTE_START' || type === 'VOTE_NEAR_DEADLINE') hint = '点按去投票'
          else if (DISMISS_TODO_TYPES[type]) hint = '点按查看并清除'
          else if (ticketDetailNav(t) || TODO_NAV[type]) hint = '点按处理'
          else hint = '点按查看'
        }
        return Object.assign({}, t, {
          todoType: type,
          content: String(t.content || '').replace(/账期[^，,]*(?:[，,]\s*)?/, '').trim(),
          hint
        })
      })
    } catch (e) {
      todos = []
    }

    const avatarSrc = personName && personName !== '邻居' && personName.indexOf('尾号') !== 0
      ? personName
      : (displayName || '邻')
    this.setData({
      loggedIn: true,
      identities,
      todos,
      displayName,
      personName,
      mobile,
      avatarText: (avatarSrc && avatarSrc[0]) || '邻',
      contextLine,
      addressLine,
      roleLine,
      pickerList,
      showMyRooms,
      hasRooms,
      identityType,
      currentRoomId,
      communityId,
      isCommittee,
      isStaff,
      showResidentRecords,
      showStaffWorkRecords,
      showStaffAuth
    })
  },

  openPicker() {
    if (!(this.data.pickerList && this.data.pickerList.length)) {
      wx.showToast({ title: '暂无可用身份', icon: 'none' })
      return
    }
    this.setData({ pickerVisible: true })
  },

  closePicker() {
    this.setData({ pickerVisible: false })
  },

  async onPickIdentity(e) {
    const item = e.detail && e.detail.item
    if (!item) return
    this.setData({ pickerVisible: false })
    wx.showLoading({ title: '切换中', mask: true })
    try {
      await switchIdentity(item)
      const roomHint = item.roomHint || (item.rooms && item.rooms[0] && item.rooms[0].roomNo) || ''
      wx.showToast({ title: '已切换到' + (item.label || '') + (roomHint ? ' · ' + roomHint : '') })
      this.refresh()
    } catch (err) {
      wx.showModal({ title: '切换失败', content: (err && err.message) || '请稍后重试', showCancel: false })
    } finally {
      wx.hideLoading()
    }
  },

  async onTapTodo(e) {
    const todo = e.currentTarget.dataset.todo
    if (!todo || !todo.id) return
    const type = todo.todoType || ''
    const detailNav = ticketDetailNav(todo) || voteDetailNav(todo)
    const nav = detailNav || TODO_NAV[type]

    if (DISMISS_TODO_TYPES[type]) {
      wx.showLoading({ title: '处理中', mask: true })
      try {
        await request({ url: '/todos/' + todo.id + '/done', method: 'POST' })
        wx.hideLoading()
        if (nav && nav !== '/pages/mine/mine') {
          wx.navigateTo({ url: nav })
        } else {
          wx.showToast({ title: '已清除' })
        }
        this.refresh()
      } catch (err) {
        wx.hideLoading()
        wx.showToast({ title: (err && err.message) || '操作失败', icon: 'none' })
      }
      return
    }

    if (nav) {
      try {
        await request({ url: '/todos/' + todo.id + '/read', method: 'POST' })
      } catch (e) { /* ignore */ }
      wx.navigateTo({ url: nav })
      return
    }

    wx.showModal({
      title: todo.title || '待办',
      content: todo.content || '暂无更多说明',
      confirmText: '知道了',
      cancelText: '关闭',
      success: async (res) => {
        if (!res.confirm) return
        try {
          await request({ url: '/todos/' + todo.id + '/done', method: 'POST' })
          wx.showToast({ title: '已清除' })
          this.refresh()
        } catch (err) {
          wx.showToast({ title: (err && err.message) || '操作失败', icon: 'none' })
        }
      }
    })
  },

  editRealName() {
    const current = this.data.personName === '邻居' || (this.data.personName || '').indexOf('尾号') === 0
      ? ''
      : (this.data.personName || '')
    wx.showModal({
      title: '修改姓名',
      editable: true,
      placeholderText: '请输入真实姓名',
      content: current,
      success: async (res) => {
        if (!res.confirm) return
        const name = (res.content || '').trim()
        if (!name) {
          wx.showToast({ title: '姓名不能为空', icon: 'none' })
          return
        }
        if (name.length > 32) {
          wx.showToast({ title: '姓名最多 32 字', icon: 'none' })
          return
        }
        wx.showLoading({ title: '保存中', mask: true })
        try {
          const profile = await updateRealName(name)
          getApp().globalData.user = profile
          wx.showToast({ title: '已保存' })
          this.refresh()
        } catch (err) {
          wx.showModal({
            title: '保存失败',
            content: (err && err.message) || '请稍后重试',
            showCancel: false
          })
        } finally {
          wx.hideLoading()
        }
      }
    })
  },

  goAuth() { wx.navigateTo({ url: '/pages/auth-apply/auth-apply' }) },
  goAuthRecords() { wx.navigateTo({ url: '/pages/approval-records/approval-records' }) },
  goApprovalRecords() { wx.navigateTo({ url: '/pages/approval-records/approval-records' }) },
  goStaffTickets() { wx.navigateTo({ url: '/pages/staff-tickets/staff-tickets' }) },
  goStaffAuth() { wx.navigateTo({ url: '/pages/staff-auth/staff-auth' }) },
  goStaffPeople() { wx.navigateTo({ url: '/pages/staff-people/staff-people' }) },
  goOccupantsRoster() { wx.navigateTo({ url: '/pages/occupants-roster/occupants-roster' }) },
  goComplaintOverview() { wx.navigateTo({ url: '/pages/complaint-overview/complaint-overview' }) },
  goTeam() { wx.navigateTo({ url: '/pages/staff-tickets/staff-tickets?tab=pool' }) },
  goChangeRecords() { wx.navigateTo({ url: '/pages/approval-records/approval-records?tab=change' }) },
  goCommunityApply() { wx.navigateTo({ url: '/pages/community-apply/community-apply' }) },
  goBills() { wx.navigateTo({ url: '/pages/bills/bills?mode=history' }) },
  goServiceRecords() { wx.navigateTo({ url: '/pages/service-records/service-records' }) },
  goNotices() { wx.navigateTo({ url: '/pages/notices/notices' }) },
  goVotes() { wx.navigateTo({ url: '/pages/votes/votes' }) },
  goAboutUs() { wx.navigateTo({ url: '/pages/about-us/about-us' }) },
  goNoticeManage() { wx.navigateTo({ url: '/pages/notice-manage/notice-manage?tab=mine' }) },
  goNoticeMine() { wx.navigateTo({ url: '/pages/notice-manage/notice-manage?tab=mine' }) },
  goVoteMine() { wx.navigateTo({ url: '/pages/vote-mine/vote-mine?tab=mine' }) },

  logout() {
    wx.showModal({
      title: '退出登录',
      content: '确定退出当前账号？',
      success: (res) => {
        if (!res.confirm) return
        wx.removeStorageSync('token')
        getApp().globalData.token = ''
        getApp().globalData.identities = []
        getApp().globalData.user = null
        this.setData({
          loggedIn: false,
          identities: [],
          todos: [],
          personName: '',
          mobile: '',
          pickerVisible: false,
          pickerList: [],
          showMyRooms: false,
          hasRooms: false
        })
        this.syncTabBar(false)
        wx.showToast({ title: '已退出' })
        // 退出后回到首页访客态，避免停在「我的」无路可走
        wx.switchTab({ url: '/pages/index/index' })
      }
    })
  }
})
