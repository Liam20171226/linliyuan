const { request } = require('../../utils/request')
const { ensureLogin, formatDateTime } = require('../../utils/format')
const { loadIdentities, switchIdentity, readLastResident } = require('../../utils/identity')
const { loadCitiesCatalog } = require('../../utils/communityCatalog')

const VOTE_TODO = {
  VOTE_START: true,
  VOTE_NEAR_DEADLINE: true
}

const VIEW_KEY = 'homeViewCommunity'

function readViewCommunity() {
  try {
    const v = wx.getStorageSync(VIEW_KEY)
    if (!v || v.id == null || v.id === '') return null
    return { id: Number(v.id), name: v.name ? String(v.name) : '小区' }
  } catch (e) {
    return null
  }
}

/** 物业身份的岗位编码列表：身份行里 staffRoles 为数组，兼容只有 staffRole 的情况 */
function staffRolesOf(it) {
  if (!it) return []
  if (Array.isArray(it.staffRoles) && it.staffRoles.length) return it.staffRoles
  if (it.staffRole) return [it.staffRole]
  return []
}

function pickForCommunity(list, preferType, last) {
  const typed = preferType ? list.filter((it) => it.identityType === preferType) : []
  let pool = typed.length ? typed : list.filter((it) => it.identityType === 'RESIDENT')
  if (!pool.length) pool = list
  if (pool[0] && pool[0].identityType === 'RESIDENT' && last && Number(last.communityId) === Number(pool[0].communityId)) {
    const hit = pool.find((it) => Number(it.roomId) === Number(last.roomId))
    if (hit) return hit
  }
  return pool[0]
}

/** 按小区去重：一行一个小区，不列出角色或房屋 */
function communitiesForPicker(identities, preferType, currentCommunityId) {
  const groups = []
  const indexById = {}
  ;(identities || []).forEach((it) => {
    if (it.communityId == null || it.communityId === '') return
    const cid = Number(it.communityId)
    if (indexById[cid] == null) {
      indexById[cid] = groups.length
      groups.push({ cid, list: [] })
    }
    groups[indexById[cid]].list.push(it)
  })
  const last = readLastResident()
  return groups.map((g) => {
    const identity = pickForCommunity(g.list, preferType, last)
    const name = (identity && (identity.communityName || identity.label)) || '小区'
    return {
      key: 'c-' + g.cid,
      label: name,
      communityId: g.cid,
      identityType: identity && identity.identityType,
      roomId: identity && identity.roomId != null ? identity.roomId : null,
      active: currentCommunityId != null && Number(currentCommunityId) === g.cid
    }
  })
}

function mapNotice(it, app, tokenStr) {
  const coverSrc = it.coverAttachmentId
    ? `${app.globalData.apiBase}/attachments/${it.coverAttachmentId}?token=${encodeURIComponent(tokenStr)}`
    : ''
  const full = formatDateTime(it.createdAt || it.effectiveAt)
  return {
    id: it.id,
    title: it.title || '物业公告',
    timeLabel: full ? full.slice(0, 10) : '',
    coverSrc
  }
}

Page({
  data: {
    loggedIn: false,
    banners: [],
    latestNotices: [],
    bannerIndex: 0,
    voteTasks: [],
    communityId: null,
    communityName: '选择小区',
    pickerVisible: false,
    pickerLoading: false,
    pickerError: '',
    cityGroups: [],
    joinedMap: {},
    statusBarHeight: 20,
    navHeight: 64,
    leftMax: 140,
    showFinance: false,
    showBills: false,
    showRepair: false,
    showAuth: false,
    showVehicles: false,
    showVotes: false,
    isCommittee: false,
    isStaff: false,
    unpaidBillCount: 0,
    // 物业工作台
    showStaffAuth: false,
    staffRoleLabel: '',
    staffRepairCount: 0,
    staffInspectCount: 0
  },

  onLoad() {
    this.initNav()
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

  /** 未登录时不展示底部 tabBar：落地页全屏沉浸，登录后恢复骨架 */
  syncTabBar(loggedIn) {
    try {
      if (loggedIn) wx.showTabBar({ animation: false })
      else wx.hideTabBar({ animation: false })
    } catch (e) { /* 非 tab 页调用会失败，忽略 */ }
  },

  initNav() {
    let sys = {}
    try {
      sys = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()
    } catch (e) {
      sys = wx.getSystemInfoSync()
    }
    const statusBarHeight = sys.statusBarHeight || 20
    const windowWidth = sys.windowWidth || 375
    let navHeight = statusBarHeight + 44
    try {
      const menu = wx.getMenuButtonBoundingClientRect()
      if (menu && menu.bottom) {
        navHeight = menu.bottom + Math.max(menu.top - statusBarHeight, 0)
      }
    } catch (e) { /* keep fallback */ }
    this.setData({
      statusBarHeight,
      navHeight,
      leftMax: Math.max(96, Math.floor(windowWidth / 2 - 48))
    })
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
        banners: [],
        latestNotices: [],
        voteTasks: [],
        communityId: null,
        communityName: '选择小区',
        pickerVisible: false,
        cityGroups: [],
        joinedMap: {},
        showFinance: false,
        showBills: false,
        showRepair: false,
        showAuth: false,
        showVehicles: false,
        showVotes: false,
        isCommittee: false,
        isStaff: false,
        unpaidBillCount: 0,
        showStaffAuth: false,
        staffRoleLabel: '',
        staffRepairCount: 0,
        staffInspectCount: 0
      })
      this.syncTabBar(false)
      return
    }

    this.syncTabBar(true)

    let communityId = null
    let communityName = '选择小区'
    let pickerList = []
    let voteTasks = []
    let showFinance = false
    let showBills = false
    let showRepair = false
    let showAuth = true
    let showVehicles = false
    let showVotes = false
    let isCommittee = false
    let isStaff = false
    let showStaffAuth = false
    let staffRoleLabel = ''
    let canDispatch = false

    try {
      const packed = await loadIdentities()
      app.globalData.identities = packed.raw
      app.globalData.user = packed.user
      const active = packed.active
      communityId = (packed.current && packed.current.communityId)
        || (active && active.communityId)
        || null
      const idType = (packed.current && packed.current.identityType)
        || (active && active.identityType)
        || ''
      pickerList = communitiesForPicker(packed.identities, idType, communityId)
      const currentCommunity = pickerList.find((it) => it.active)
      communityName = (currentCommunity && currentCommunity.label)
        || (active && active.communityName)
        || '选择小区'
      const resident = idType === 'RESIDENT'
      isStaff = idType === 'STAFF'
      // 同小区有业委会任职即显示业委入口（可不切离物业身份）
      const cidNum = communityId != null ? Number(communityId) : null
      isCommittee = idType === 'COMMITTEE'
        || !!(packed.identities || []).some(
          (it) => it.isCommittee && (cidNum == null || Number(it.communityId) === cidNum)
        )
        || (resident && !!packed.isCommittee)
      if (isStaff) {
        const roles = staffRolesOf(packed.current || active)
        // 住户认证 / 人员车辆：仅客服岗在小程序工作台可见（项目经理走 Web 管理端）
        showStaffAuth = roles.indexOf('CUSTOMER_SERVICE') >= 0
        canDispatch = roles.indexOf('CUSTOMER_SERVICE') >= 0 || roles.indexOf('PROPERTY_MANAGER') >= 0
        staffRoleLabel = (active && (active.roleLabels || active.roleLabel)) || '物业'
      }
      showBills = resident || idType === 'COMMITTEE'
      showRepair = resident || idType === 'COMMITTEE'
      showFinance = (resident || idType === 'COMMITTEE') && communityId != null
      showAuth = resident || idType === 'GUEST' || !idType
      showVehicles = resident || idType === 'COMMITTEE'
      showVotes = resident || idType === 'COMMITTEE'
    } catch (e) {
      /* ignore */
    }

    const joinedMap = {}
    ;(pickerList || []).forEach((it) => {
      if (it && it.communityId != null) joinedMap[String(it.communityId)] = it
    })

    const view = readViewCommunity()
    if (view) {
      const hit = joinedMap[String(view.id)]
      if (hit) {
        try { wx.removeStorageSync(VIEW_KEY) } catch (e) { /* ignore */ }
        if (communityId == null || Number(communityId) !== Number(view.id)) {
          await switchIdentity(hit)
          return this.refresh()
        }
      } else {
        communityId = view.id
        communityName = view.name || '小区'
        showFinance = false
        showBills = false
        showRepair = false
        showAuth = true
        showVehicles = false
        showVotes = false
        isCommittee = false
        isStaff = false
        showStaffAuth = false
        canDispatch = false
        staffRoleLabel = ''
        voteTasks = []
      }
    }

    if (showVotes) {
      try {
        const todos = await request({ url: '/todos', data: { status: 'OPEN' } })
        const list = (todos && todos.list) || []
        voteTasks = list
          .filter((t) => VOTE_TODO[t.todoType])
          .map((t) => ({
            id: t.id,
            title: t.title || '待参与投票',
            bizId: t.bizId
          }))
      } catch (e) {
        voteTasks = []
      }
    }

    let banners = []
    let latestNotices = []
    try {
      const params = {}
      if (communityId != null) params.communityId = communityId
      const data = await request({ url: '/notices/home', data: params })
      const tokenStr = app.globalData.token || wx.getStorageSync('token') || ''
      banners = ((data && data.banners) || []).map((it) => mapNotice(it, app, tokenStr))
        .filter((it) => !!it.coverSrc)
      latestNotices = ((data && data.latest) || []).map((it) => mapNotice(it, app, tokenStr))
    } catch (e) {
      banners = []
      latestNotices = []
    }

    // 物业工作台角标：待办工单数（我的待办 + 待派单，与报事报修页口径一致）
    let staffRepairCount = 0
    let staffInspectCount = 0
    if (isStaff) {
      try {
        const [mine, pool] = await Promise.all([
          request({ url: '/staff/repairs/mine', data: { page: 1, pageSize: 1 } }).catch(() => null),
          request({ url: '/staff/repairs/pool', data: { page: 1, pageSize: 1 } }).catch(() => null)
        ])
        staffRepairCount = Number((mine && mine.total) || 0) + Number((pool && pool.total) || 0)
      } catch (e) {
        staffRepairCount = 0
      }
      try {
        const jobs = await request({ url: '/staff/inspect/jobs', data: { status: 'OPEN' } }).catch(() => null)
        staffInspectCount = ((jobs && jobs.list) || []).length
      } catch (e) {
        staffInspectCount = 0
      }
    }

    let unpaidBillCount = 0
    if (showFinance) {
      try {
        const bills = await request({
          url: '/resident/bills',
          data: { page: 1, pageSize: 1, status: 'UNPAID' }
        })
        unpaidBillCount = Number((bills && bills.total) || 0)
      } catch (e) {
        unpaidBillCount = 0
      }
    }

    this.setData({
      loggedIn: true,
      banners,
      latestNotices,
      bannerIndex: 0,
      voteTasks,
      communityId,
      communityName,
      joinedMap,
      showFinance,
      showBills,
      showRepair,
      showAuth,
      showVehicles,
      showVotes,
      isCommittee,
      isStaff,
      showStaffAuth,
      staffRoleLabel,
      staffRepairCount,
      staffInspectCount,
      unpaidBillCount
    })
  },

  async openPicker() {
    this.setData({ pickerVisible: true, pickerLoading: true, pickerError: '', cityGroups: [] })
    try {
      const cities = await loadCitiesCatalog()
      const currentId = this.data.communityId
      const cityGroups = (cities || []).map((city) => ({
        city: city.cityName || '其他',
        communities: (city.communities || []).map((c) => ({
          id: c.id,
          name: c.name || '小区',
          districtName: c.districtName || '',
          active: currentId != null && Number(currentId) === Number(c.id)
        }))
      }))
      this.setData({
        cityGroups,
        pickerLoading: false,
        pickerError: cityGroups.length ? '' : '系统里还没有小区'
      })
    } catch (e) {
      this.setData({
        cityGroups: [],
        pickerLoading: false,
        pickerError: (e && e.message) || '小区列表加载失败'
      })
    }
  },
  closePicker() { this.setData({ pickerVisible: false }) },
  noop() {},

  async onPickCommunity(e) {
    const id = Number(e.currentTarget.dataset.id)
    const name = e.currentTarget.dataset.name || '小区'
    if (!id) return
    if (this.data.communityId != null && Number(this.data.communityId) === id) {
      this.setData({ pickerVisible: false })
      return
    }
    const joined = (this.data.joinedMap || {})[String(id)]
    this.setData({ pickerVisible: false })
    if (joined) {
      try { wx.removeStorageSync(VIEW_KEY) } catch (err) { /* ignore */ }
      wx.showLoading({ title: '切换中', mask: true })
      try {
        await switchIdentity(joined)
        wx.showToast({ title: '已切换到' + (joined.label || name) })
        this.refresh()
      } catch (err) {
        wx.showModal({ title: '切换失败', content: (err && err.message) || '请稍后重试', showCancel: false })
      } finally {
        wx.hideLoading()
      }
      return
    }
    try {
      wx.setStorageSync(VIEW_KEY, { id, name })
    } catch (err) { /* ignore */ }
    wx.showToast({ title: '已切换到' + name })
    this.refresh()
  },

  onBannerChange(e) {
    this.setData({ bannerIndex: (e.detail && e.detail.current) || 0 })
  },

  goLogin() { wx.navigateTo({ url: '/pages/login/login' }) },
  goAuth() {
    if (!ensureLogin()) return
    wx.navigateTo({ url: '/pages/auth-apply/auth-apply' })
  },
  goRoomMaintain() {
    if (!ensureLogin()) return
    wx.navigateTo({ url: '/pages/room-maintain/room-maintain' })
  },
  goCommunityApply() {
    if (!ensureLogin()) return
    wx.navigateTo({ url: '/pages/community-apply/community-apply' })
  },
  goBills() {
    if (!ensureLogin()) return
    wx.navigateTo({ url: '/pages/bills/bills' })
  },
  goPrepaid() {
    if (!ensureLogin()) return
    wx.navigateTo({ url: '/pages/prepaid/prepaid' })
  },
  goServiceDesk() {
    if (!ensureLogin()) return
    wx.navigateTo({ url: '/pages/service-desk/service-desk' })
  },
  goStaffTickets() {
    if (!ensureLogin()) return
    wx.navigateTo({ url: '/pages/staff-tickets/staff-tickets' })
  },
  goStaffInspect() {
    if (!ensureLogin()) return
    wx.navigateTo({ url: '/pages/staff-inspect/staff-inspect' })
  },
  goStaffAuth() {
    if (!ensureLogin()) return
    wx.navigateTo({ url: '/pages/staff-auth/staff-auth' })
  },
  goStaffPeople() {
    if (!ensureLogin()) return
    wx.navigateTo({ url: '/pages/staff-people/staff-people' })
  },
  goNotices() {
    if (!ensureLogin()) return
    wx.navigateTo({
      url: this.data.communityId
        ? `/pages/notices/notices?communityId=${this.data.communityId}&source=STAFF`
        : '/pages/notices/notices?source=STAFF'
    })
  },
  goCommitteeNotices() {
    if (!ensureLogin()) return
    wx.navigateTo({
      url: this.data.communityId
        ? `/pages/notices/notices?communityId=${this.data.communityId}&source=COMMITTEE`
        : '/pages/notices/notices?source=COMMITTEE'
    })
  },
  goNoticeDetail(e) {
    const id = e.currentTarget.dataset.id
    if (!ensureLogin()) return
    wx.navigateTo({
      url: id ? `/pages/notice-detail/notice-detail?id=${id}` : '/pages/notices/notices?source=STAFF'
    })
  },
  goVotes() {
    if (!ensureLogin()) return
    wx.navigateTo({ url: '/pages/votes/votes' })
  },
  /** 首页投票待办：直达投票页并标记完成（待办消失） */
  async goVoteTodo() {
    if (!ensureLogin()) return
    const task = (this.data.voteTasks || [])[0]
    if (!task || !task.id) {
      wx.navigateTo({ url: '/pages/votes/votes' })
      return
    }
    const url = task.bizId != null && task.bizId !== ''
      ? '/pages/vote-detail/vote-detail?id=' + task.bizId
      : '/pages/votes/votes'
    try {
      await request({ url: '/todos/' + task.id + '/done', method: 'POST' })
    } catch (e) { /* 仍跳转，避免卡死 */ }
    this.setData({ voteTasks: (this.data.voteTasks || []).filter((t) => t.id !== task.id) })
    wx.navigateTo({ url })
  },
  goFinancePublic() {
    if (!ensureLogin()) return
    wx.navigateTo({ url: '/pages/finance-public/finance-public' })
  },
  goOccupantsRoster() {
    if (!ensureLogin()) return
    wx.navigateTo({ url: '/pages/occupants-roster/occupants-roster' })
  },
  goVoteCreate() {
    if (!ensureLogin()) return
    wx.navigateTo({ url: '/pages/vote-create/vote-create' })
  },
  goComplaintOverview() {
    if (!ensureLogin()) return
    wx.navigateTo({ url: '/pages/complaint-overview/complaint-overview' })
  },
  goNoticeManage() {
    if (!ensureLogin()) return
    wx.navigateTo({ url: '/pages/notice-create/notice-create' })
  }
})
