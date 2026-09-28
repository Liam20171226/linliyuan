/** 身份 / 状态文案：对齐常见物业 App，避免直接展示枚举 */

const IDENTITY_LABEL = {
  RESIDENT: '住户',
  COMMITTEE: '业委会',
  STAFF: '物业',
  PLATFORM: '平台',
  GUEST: '游客'
}

const ROLE_LABEL = {
  OWNER: '业主',
  OWNER_MEMBER: '业主家属',
  TENANT: '租户',
  TENANT_MEMBER: '租户家属',
  PROPERTY_MANAGER: '物业经理',
  CUSTOMER_SERVICE: '客服',
  SECURITY: '保安',
  CLEANING: '保洁',
  LANDSCAPING: '绿化',
  FACILITY_MAINT: '机电维修',
  /** @deprecated 历史物业管理员，展示为客服 */
  PROPERTY_ADMIN: '客服',
  ACTIVIST: '积极分子',
  DIRECTOR: '主任',
  MEMBER: '委员'
}

const BILL_STATUS = {
  DRAFT: '草稿',
  PUBLISHED: '待缴费',
  PARTIAL: '部分缴纳',
  PAID: '已缴清',
  OVERDUE: '已逾期',
  VOID: '已作废'
}

/* 与后端 RepairService 保持一致：
   PENDING_ASSIGN 待派单 → ASSIGNED 已派单 → IN_PROGRESS 处理中 → DONE_WAIT_RATE 待评价 → COMPLETED 已完成
   旁路：CLOSED 已关闭（物业关单） */
const REPAIR_STATUS = {
  PENDING_ASSIGN: '待派单',
  ASSIGNED: '已派单',
  IN_PROGRESS: '处理中',
  DONE_WAIT_RATE: '待评价',
  COMPLETED: '已完成',
  CLOSED: '已关闭',
  CANCELLED: '已取消'
}

const COMPLAINT_STATUS = {
  PENDING: '待处理',
  SUBMITTED: '已提交',
  PROCESSING: '处理中',
  REPLIED: '已回复',
  ASSIGNED: '已派单',
  IN_PROGRESS: '处理中',
  // 投诉/咨询建议/表扬 与报修共用同一套终态：处理完成待评价 → 已评价完成
  DONE_WAIT_RATE: '待评价',
  COMPLETED: '已完成',
  CLOSED: '已关闭'
}

const VOTE_STATUS = {
  DRAFT: '草稿',
  PUBLISHED: '进行中',
  ONGOING: '进行中',
  CLOSED: '已结束',
  ENDED: '已结束'
}

function mapLabel(dict, key, fallback) {
  if (key == null || key === '') return fallback || ''
  return dict[key] || fallback || key
}

function identityLabel(type) {
  return mapLabel(IDENTITY_LABEL, type, type)
}

function roleLabel(role) {
  return mapLabel(ROLE_LABEL, role, role)
}

function billStatusLabel(s) {
  return mapLabel(BILL_STATUS, s, s)
}

function repairStatusLabel(s) {
  return mapLabel(REPAIR_STATUS, s, s)
}

function complaintStatusLabel(s) {
  return mapLabel(COMPLAINT_STATUS, s, s)
}

function voteStatusLabel(s) {
  return mapLabel(VOTE_STATUS, s, s)
}

function roomPathOf(room, communityName) {
  if (!room) return ''
  // 统一结构：楼栋/单元/层/房号（不含小区；小区单独展示）
  const parts = []
  ;['buildingName', 'unitName', 'floorName'].forEach((k) => {
    if (room[k] != null && String(room[k]).trim()) parts.push(String(room[k]).trim())
  })
  if (room.roomNo != null && String(room.roomNo).trim()) {
    parts.push(String(room.roomNo).trim())
  }
  if (parts.length) return parts.join('/')
  if (room.address != null && String(room.address).trim()) {
    // 后端已按 / 拼接时直接用；兼容旧数据
    return String(room.address).trim().replace(/\s+/g, '/')
  }
  if (communityName != null && String(communityName).trim()) {
    return String(communityName).trim()
  }
  return ''
}

function decorateIdentity(item) {
  if (!item) return item
  const rooms = item.rooms || []
  const firstRoom = rooms[0]
  const type = item.identityType
  const typeLabel = identityLabel(type)
  let duty = ''
  if (type === 'STAFF') {
    if (item.roleLabels) {
      duty = String(item.roleLabels)
    } else if (Array.isArray(item.staffRoles) && item.staffRoles.length) {
      duty = item.staffRoles.map((r) => roleLabel(r)).filter(Boolean).join('·') || '物业'
    } else {
      duty = roleLabel(item.staffRole) || '物业'
    }
  } else if (type === 'COMMITTEE') {
    const t = roleLabel(item.committeeTitle)
    if (t === '主任') duty = '业委会主任'
    else if (t === '委员') duty = '业委会成员'
    else if (t === '积极分子') duty = '业委会积极分子'
    else duty = t ? `业委会${t}` : '业委会'
  } else if (type === 'RESIDENT') {
    const room = (item.roomId != null && rooms.find((r) => Number(r.roomId) === Number(item.roomId)))
      || firstRoom
    duty = roleLabel(room && room.residentRole) || '住户'
    // 副文案用 楼栋/单元/房号（小区已在主标题），避免两套房仅房号相同无法区分
    const path = roomPathOf(room)
    if (path) duty = `${duty} · ${path}`
    if (item.committeeTitle) {
      const ct = roleLabel(item.committeeTitle)
      let cDuty = '业委会'
      if (ct === '主任') cDuty = '业委会主任'
      else if (ct === '委员') cDuty = '业委会成员'
      else if (ct === '积极分子') cDuty = '业委会积极分子'
      else if (ct) cDuty = `业委会${ct}`
      duty = `${duty} · ${cDuty}`
    } else if (item.isCommittee) {
      duty = `${duty} · 业委会`
    }
  } else if (type === 'PLATFORM') {
    duty = '平台管理'
  }

  const hasCommunity = !!(item.communityName && String(item.communityName).trim())
  // 方案 A：主标题=小区；无小区时用身份大类（游客/平台）
  const label = hasCommunity ? String(item.communityName).trim() : (typeLabel || '邻里院')
  const roleLabelText = duty
  const room = (item.roomId != null && rooms.find((r) => Number(r.roomId) === Number(item.roomId)))
    || firstRoom

  return Object.assign({}, item, {
    label,
    roleLabel: roleLabelText,
    identityTypeLabel: typeLabel,
    roomHint: roomPathOf(room)
  })
}

function ensureLogin() {
  const app = getApp()
  const token = (app && app.globalData && app.globalData.token) || wx.getStorageSync('token')
  if (token) return true
  wx.showModal({
    title: '需要登录',
    content: '登录后可使用认证、账单、报修等服务',
    confirmText: '去登录',
    success(res) {
      if (res.confirm) {
        wx.navigateTo({ url: '/pages/login/login' })
      }
    }
  })
  return false
}

/** 申请时间等：2026-08-21 15:21:01 */
function formatDateTime(v) {
  if (v == null || v === '') return ''
  if (Array.isArray(v) && v.length >= 3) {
    const y = v[0]
    const m = String(v[1]).padStart(2, '0')
    const d = String(v[2]).padStart(2, '0')
    const hh = String(v[3] != null ? v[3] : 0).padStart(2, '0')
    const mm = String(v[4] != null ? v[4] : 0).padStart(2, '0')
    const ss = String(v[5] != null ? Math.floor(v[5]) : 0).padStart(2, '0')
    return `${y}-${m}-${d} ${hh}:${mm}:${ss}`
  }
  const s = String(v).trim()
  const m = s.match(/^(\d{4}-\d{2}-\d{2})[T\s](\d{2}:\d{2}:\d{2})/)
  if (m) return `${m[1]} ${m[2]}`
  if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(s)) return s
  const d = new Date(s)
  if (!Number.isNaN(d.getTime())) {
    const p = (n) => String(n).padStart(2, '0')
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
  }
  return s
}

/** 金额统一两位小数 */
function formatMoney(v) {
  if (v == null || v === '') return '0.00'
  const n = typeof v === 'number' ? v : Number(v)
  if (!Number.isFinite(n)) return '0.00'
  return n.toFixed(2)
}

module.exports = {
  identityLabel,
  roleLabel,
  billStatusLabel,
  repairStatusLabel,
  complaintStatusLabel,
  voteStatusLabel,
  roomPathOf,
  decorateIdentity,
  ensureLogin,
  formatDateTime,
  formatMoney
}
