/** 身份 / 状态文案：对齐小程序 format.js */

const IDENTITY_LABEL: Record<string, string> = {
  RESIDENT: '住户',
  COMMITTEE: '业委会',
  STAFF: '物业',
  PLATFORM: '平台',
  GUEST: '游客',
}

const ROLE_LABEL: Record<string, string> = {
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
  PROPERTY_ADMIN: '客服',
  ACTIVIST: '积极分子',
  DIRECTOR: '主任',
  MEMBER: '委员',
}

const BILL_STATUS: Record<string, string> = {
  DRAFT: '草稿',
  PUBLISHED: '待缴费',
  PARTIAL: '部分缴纳',
  PAID: '已缴清',
  OVERDUE: '已逾期',
  VOID: '已作废',
}

const REPAIR_STATUS: Record<string, string> = {
  PENDING_ASSIGN: '待派单',
  ASSIGNED: '已派单',
  IN_PROGRESS: '处理中',
  DONE_WAIT_RATE: '待评价',
  COMPLETED: '已完成',
  CLOSED: '已关闭',
  CANCELLED: '已取消',
}

const COMPLAINT_STATUS: Record<string, string> = {
  PENDING: '待处理',
  SUBMITTED: '已提交',
  PROCESSING: '处理中',
  REPLIED: '已回复',
  ASSIGNED: '已派单',
  IN_PROGRESS: '处理中',
  DONE_WAIT_RATE: '待评价',
  COMPLETED: '已完成',
  CLOSED: '已关闭',
}

const VOTE_STATUS: Record<string, string> = {
  DRAFT: '草稿',
  PUBLISHED: '进行中',
  ONGOING: '进行中',
  CLOSED: '已结束',
  ENDED: '已结束',
}

function mapLabel(dict: Record<string, string>, key: unknown, fallback?: string) {
  if (key == null || key === '') return fallback || ''
  const k = String(key)
  return dict[k] || fallback || k
}

export function identityLabel(type: unknown) {
  return mapLabel(IDENTITY_LABEL, type, type != null ? String(type) : '')
}

export function roleLabel(role: unknown) {
  return mapLabel(ROLE_LABEL, role, role != null ? String(role) : '')
}

export function billStatusLabel(s: unknown) {
  return mapLabel(BILL_STATUS, s, s != null ? String(s) : '')
}

export function repairStatusLabel(s: unknown) {
  return mapLabel(REPAIR_STATUS, s, s != null ? String(s) : '')
}

export function complaintStatusLabel(s: unknown) {
  return mapLabel(COMPLAINT_STATUS, s, s != null ? String(s) : '')
}

export function voteStatusLabel(s: unknown) {
  return mapLabel(VOTE_STATUS, s, s != null ? String(s) : '')
}

/** 申请时间等：2026-08-21 15:21:01 */
export function formatDateTime(v: unknown): string {
  if (v == null || v === '') return ''
  if (Array.isArray(v) && v.length >= 3) {
    const y = v[0]
    const m = String(v[1]).padStart(2, '0')
    const d = String(v[2]).padStart(2, '0')
    const hh = String(v[3] != null ? v[3] : 0).padStart(2, '0')
    const mm = String(v[4] != null ? v[4] : 0).padStart(2, '0')
    const ss = String(v[5] != null ? Math.floor(Number(v[5])) : 0).padStart(2, '0')
    return `${y}-${m}-${d} ${hh}:${mm}:${ss}`
  }
  const s = String(v).trim()
  const m = s.match(/^(\d{4}-\d{2}-\d{2})[T\s](\d{2}:\d{2}:\d{2})/)
  if (m) return `${m[1]} ${m[2]}`
  if (/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(s)) return s
  const d = new Date(s)
  if (!Number.isNaN(d.getTime())) {
    const p = (n: number) => String(n).padStart(2, '0')
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
  }
  return s
}

/** 金额统一两位小数 */
export function formatMoney(v: unknown): string {
  if (v == null || v === '') return '0.00'
  const n = typeof v === 'number' ? v : Number(v)
  if (!Number.isFinite(n)) return '0.00'
  return n.toFixed(2)
}

export function stripHtml(html: unknown): string {
  if (html == null) return ''
  return String(html)
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/p>/gi, '\n')
    .replace(/<[^>]+>/g, '')
    .replace(/&nbsp;/g, ' ')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&amp;/g, '&')
    .trim()
}
