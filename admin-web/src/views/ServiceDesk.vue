<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, API_BASE } from '../api'
import { authFailRedirectPath } from '../authSession'
import StaffNav from '../components/StaffNav.vue'
import StaffSessionAction from '../components/StaffSessionAction.vue'

type BizKind = 'REPAIR' | 'COMPLAINT'

interface Ticket {
  key: string
  biz: BizKind
  id: number
  roomId: number | null
  roomLabel: string
  category: string
  location: string
  summary: string
  contactName: string
  contactMobile: string
  status: string
  createdAt: string | null
  raw: any
}

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const tickets = ref<Ticket[]>([])
const categoryQ = ref('')
const statusQ = ref('')
const keywordQ = ref('')
/** 入口来自旧路由时限定大类：repair | complaint | '' */
const kindGate = ref('')

const replyOpen = ref(false)
const replyBiz = ref<BizKind>('REPAIR')
const replyId = ref<number | null>(null)
const replyText = ref('')

/** 工单详情抽屉：展示完整订单过程与图片 */
const detailOpen = ref(false)
const detailData = ref<any>(null)
const detailLoading = ref(false)

/** 回复照片（最多 3 张） */
const pendingFiles = ref<{ id: number; fileName: string }[]>([])
const uploading = ref(false)
function attachUrl(id: number): string {
  const token = localStorage.getItem('token') || ''
  return `${API_BASE}/attachments/${id}?token=${encodeURIComponent(token)}`
}

/** 派单 / 转派弹窗（派给岗位） */
const assignOpen = ref(false)
const assignRow = ref<Ticket | null>(null)
const assignRole = ref('')
const assignRemark = ref('')
const assignSubmitting = ref(false)

const ROLE_OPTIONS = [
  { value: 'SECURITY', label: '保安岗' },
  { value: 'CLEANING', label: '保洁岗' },
  { value: 'LANDSCAPING', label: '绿化岗' },
  { value: 'FACILITY_MAINT', label: '机电维修岗' },
  { value: 'CUSTOMER_SERVICE', label: '客服岗' },
  { value: 'PROPERTY_MANAGER', label: '物业经理' },
]

const ROLE_LABEL: Record<string, string> = Object.fromEntries(
  ROLE_OPTIONS.map((o) => [o.value, o.label]),
)

function roleLabel(r?: string | null) {
  return (r && ROLE_LABEL[r]) || r || ''
}

/** 团队成员 userId → 姓名，用于显示已接单的处理人 */
const staffNames = ref<Record<string, string>>({})

const isTransferAssign = computed(() => {
  const s = assignRow.value?.status
  return !!s && s !== 'PENDING_ASSIGN' && s !== 'PENDING'
})

const CATEGORY_OPTIONS = [
  { value: '', label: '全部类型' },
  { value: '公区报障', label: '公区报障' },
  { value: '室内维修', label: '室内维修' },
  { value: '投诉', label: '投诉' },
  { value: '表扬', label: '表扬' },
  { value: '咨询建议', label: '咨询建议' },
]

const REPAIR_STATUS = [
  { value: 'PENDING_ASSIGN', label: '待派单' },
  { value: 'ASSIGNED', label: '已派单' },
  { value: 'IN_PROGRESS', label: '处理中' },
  { value: 'DONE_WAIT_RATE', label: '待评价' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'CLOSED', label: '已关闭' },
]

const COMPLAINT_STATUS = [
  { value: 'PENDING', label: '待处理' },
  { value: 'ASSIGNED', label: '已派单' },
  { value: 'PROCESSING', label: '处理中' },
  { value: 'REPLIED', label: '已回复' },
  { value: 'DONE_WAIT_RATE', label: '待评价' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'CLOSED', label: '已关闭' },
]

const STATUS_LABEL: Record<string, string> = Object.fromEntries(
  [...REPAIR_STATUS, ...COMPLAINT_STATUS].map((o) => [o.value, o.label]),
)

const statusFilterOptions = computed(() => {
  const repairOnly =
    kindGate.value === 'repair'
    || categoryQ.value === '公区报障'
    || categoryQ.value === '室内维修'
  const complaintOnly =
    kindGate.value === 'complaint'
    || categoryQ.value === '投诉'
    || categoryQ.value === '表扬'
    || categoryQ.value === '咨询建议'
  if (repairOnly && !complaintOnly) {
    return [{ value: '', label: '全部状态' }, ...REPAIR_STATUS]
  }
  if (complaintOnly && !repairOnly) {
    return [{ value: '', label: '全部状态' }, ...COMPLAINT_STATUS]
  }
  return [
    { value: '', label: '全部状态' },
    ...REPAIR_STATUS,
    ...COMPLAINT_STATUS.filter((s) => !REPAIR_STATUS.some((r) => r.value === s.value)),
  ]
})

function statusLabel(s: string) {
  return STATUS_LABEL[s] || s || '—'
}

function categoryTone(cat: string) {
  if (cat === '公区报障') return 'orange'
  if (cat === '室内维修') return 'blue'
  if (cat === '投诉') return 'gold'
  if (cat === '表扬') return 'coral'
  if (cat === '咨询建议') return 'mint'
  return 'mute'
}

function createdTs(v: any): number {
  if (v == null) return 0
  if (Array.isArray(v) && v.length >= 3) {
    return Date.UTC(v[0], (v[1] || 1) - 1, v[2] || 1, v[3] || 0, v[4] || 0, Math.floor(v[5] || 0))
  }
  const t = Date.parse(String(v))
  return Number.isNaN(t) ? 0 : t
}

function formatTime(v: any): string {
  if (v == null || v === '') return '—'
  if (Array.isArray(v) && v.length >= 3) {
    const p = (n: number) => String(n).padStart(2, '0')
    return `${v[0]}-${p(v[1])}-${p(v[2])} ${p(v[3] ?? 0)}:${p(v[4] ?? 0)}`
  }
  const s = String(v)
  const m = s.match(/^(\d{4}-\d{2}-\d{2})[T\s](\d{2}:\d{2})/)
  if (m) return `${m[1]} ${m[2]}`
  return s
}

function mapRepair(row: any): Ticket {
  return {
    key: `R-${row.id}`,
    biz: 'REPAIR',
    id: row.id,
    roomId: row.roomId ?? null,
    roomLabel: row.roomLabel || '',
    category: row.category || '报修',
    location: row.location || '',
    summary: row.content || '',
    contactName: '',
    contactMobile: '',
    status: row.status || '',
    createdAt: row.createdAt || null,
    raw: row,
  }
}

/** 当前处理岗 / 处理人展示文案：优先用后端解析的姓名（含平台管理员） */
function handlerText(row: Ticket): string {
  const raw = row.raw || {}
  if (raw.assigneeUserId) {
    return raw.assigneeName
      || staffNames.value[String(raw.assigneeUserId)]
      || `员工#${raw.assigneeUserId}`
  }
  if (raw.assigneeRole) return roleLabel(raw.assigneeRole)
  if (raw.handlerUserId) {
    return raw.handlerName
      || staffNames.value[String(raw.handlerUserId)]
      || `员工#${raw.handlerUserId}`
  }
  return '—'
}

function handlerPendingClaim(row: Ticket): boolean {
  return !row.raw?.assigneeUserId && !!row.raw?.assigneeRole && row.status === 'ASSIGNED'
}

function canAssignRow(row: Ticket): boolean {
  return ['PENDING_ASSIGN', 'PENDING', 'ASSIGNED', 'IN_PROGRESS', 'PROCESSING', 'REPLIED'].includes(row.status)
}

function canCompleteRow(row: Ticket): boolean {
  if (['DONE_WAIT_RATE', 'COMPLETED', 'CLOSED'].includes(row.status)) return false
  if (row.biz === 'REPAIR') return ['ASSIGNED', 'IN_PROGRESS'].includes(row.status)
  return ['PENDING', 'ASSIGNED', 'PROCESSING', 'REPLIED'].includes(row.status)
}

function canReplyRow(row: Ticket): boolean {
  return !['DONE_WAIT_RATE', 'COMPLETED', 'CLOSED'].includes(row.status)
}

async function loadTeam() {
  try {
    const { data } = await api.get('/staff/team')
    if (data.code !== 0) return
    const names: Record<string, string> = {}
    for (const m of data.data?.members || []) {
      names[String(m.userId)] = m.realName || m.mobile || `员工${m.userId}`
    }
    staffNames.value = names
  } catch {
    /* 名称映射失败不影响列表 */
  }
}

function mapComplaint(row: any): Ticket {
  return {
    key: `C-${row.id}`,
    biz: 'COMPLAINT',
    id: row.id,
    roomId: row.roomId ?? null,
    roomLabel: row.roomLabel || '',
    category: row.category || '投诉',
    location: '',
    summary: row.content || '',
    contactName: row.contactName || '',
    contactMobile: row.contactMobile || '',
    status: row.status || '',
    createdAt: row.createdAt || null,
    raw: row,
  }
}

const filtered = computed(() => {
  const kw = keywordQ.value.trim()
  return tickets.value.filter((t) => {
    if (kindGate.value === 'repair' && t.biz !== 'REPAIR') return false
    if (kindGate.value === 'complaint' && t.biz !== 'COMPLAINT') return false
    if (categoryQ.value && t.category !== categoryQ.value) return false
    if (statusQ.value && t.status !== statusQ.value) return false
    if (kw) {
      const blob = `${t.summary} ${t.location} ${t.contactName} ${t.contactMobile} ${t.roomId ?? ''}`
      if (!blob.includes(kw)) return false
    }
    return true
  })
})

async function load() {
  loading.value = true
  try {
    const [rRes, cRes] = await Promise.all([
      api.get('/staff/repairs', { params: { page: 1, pageSize: 100 } }),
      api.get('/staff/complaints', { params: { page: 1, pageSize: 100 } }),
    ])
    if (rRes.data.code !== 0) {
      ElMessage.error(rRes.data.message || '加载报修失败')
      if (rRes.data.code === 40101 || rRes.data.code === 40301) router.push(authFailRedirectPath())
      return
    }
    if (cRes.data.code !== 0) {
      ElMessage.error(cRes.data.message || '加载投诉失败')
      if (cRes.data.code === 40101 || cRes.data.code === 40301) router.push(authFailRedirectPath())
      return
    }
    const repairs = (rRes.data.data?.list || []).map(mapRepair)
    const complaints = (cRes.data.data?.list || []).map(mapComplaint)
    tickets.value = [...repairs, ...complaints].sort(
      (a, b) => createdTs(b.createdAt) - createdTs(a.createdAt),
    )
  } finally {
    loading.value = false
  }
}

function openAssign(row: Ticket) {
  assignRow.value = row
  assignRole.value = ''
  assignRemark.value = ''
  assignOpen.value = true
}

async function submitAssign() {
  const row = assignRow.value
  if (!row || assignSubmitting.value) return
  if (!assignRole.value) {
    ElMessage.warning('请选择接收岗位')
    return
  }
  assignSubmitting.value = true
  try {
    const path = row.biz === 'REPAIR'
      ? `/staff/repairs/${row.id}/assign`
      : `/staff/complaints/${row.id}/assign`
    const { data } = await api.post(path, {
      assigneeRole: assignRole.value,
      remark: assignRemark.value.trim(),
    })
    if (data.code === 0) {
      ElMessage.success(isTransferAssign.value ? '已转派' : '已派单')
      assignOpen.value = false
      load()
    } else ElMessage.error(data.message)
  } finally {
    assignSubmitting.value = false
  }
}

async function complete(row: Ticket) {
  const praise = row.category === '表扬'
  try {
    await ElMessageBox.confirm(
      praise
        ? '请确认事项已办理完毕。表扬不参与满意度评价，完成后住户可查看完整流程。'
        : '请确认事项已办理完毕，即将发送给住户进行满意度评价',
      '处理完成',
      { confirmButtonText: '确认完成', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  const url = row.biz === 'REPAIR'
    ? `/staff/repairs/${row.id}/complete`
    : `/staff/complaints/${row.id}/complete`
  const { data } = await api.post(url)
  if (data.code === 0) {
    ElMessage.success(praise ? '已处理完成（表扬不参与评价）' : '已处理完成，等待业主评价')
    load()
  } else ElMessage.error(data.message)
}

function openReply(row: Ticket) {
  replyBiz.value = row.biz
  replyId.value = row.id
  replyText.value = ''
  pendingFiles.value = []
  replyOpen.value = true
}

/** 工单详情：加载完整订单过程与图片 */
async function openDetail(row: Ticket) {
  detailLoading.value = true
  detailOpen.value = true
  detailData.value = null
  try {
    const path = row.biz === 'REPAIR' ? `/staff/repairs/${row.id}` : `/staff/complaints/${row.id}`
    const { data } = await api.get(path)
    if (data.code === 0) detailData.value = data.data
    else ElMessage.error(data.message)
  } finally {
    detailLoading.value = false
  }
}

/** 详情弹窗内的快捷操作：回复 / 完成（经理·客服直接办理） */
function replyFromDetail() {
  const o = detailData.value?.order
  if (!o) return
  detailOpen.value = false
  openReply({ biz: o.kind === 'REPAIR' ? 'REPAIR' : 'COMPLAINT', id: o.id } as Ticket)
}

async function completeFromDetail() {
  const o = detailData.value?.order
  if (!o) return
  const praise = o.category === '表扬'
  try {
    await ElMessageBox.confirm(
      praise
        ? '请确认事项已办理完毕。表扬不参与满意度评价，完成后住户可查看完整流程。'
        : '请确认事项已办理完毕，即将发送给住户进行满意度评价',
      '处理完成',
      { confirmButtonText: '确认完成', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  const url = o.kind === 'REPAIR'
    ? `/staff/repairs/${o.id}/complete`
    : `/staff/complaints/${o.id}/complete`
  const { data } = await api.post(url)
  if (data.code === 0) {
    ElMessage.success(praise ? '已处理完成（表扬不参与评价）' : '已处理完成，等待业主评价')
    detailOpen.value = false
    load()
  } else ElMessage.error(data.message)
}

/** 详情弹窗内派单 / 转派（派给岗位），复用列表的派单弹窗 */
function assignFromDetail() {
  const o = detailData.value?.order
  if (!o) return
  detailOpen.value = false
  openAssign({
    biz: o.kind === 'REPAIR' ? 'REPAIR' : 'COMPLAINT',
    id: o.id,
    status: o.status,
    category: o.category,
    summary: o.content,
  } as Ticket)
}

/** 工单是否已进入终态（终态后留言不可再改删） */
function ticketTerminated(): boolean {
  const s = detailData.value?.order?.status
  return !!s && ['DONE_WAIT_RATE', 'COMPLETED', 'CLOSED'].includes(s)
}

/** 仅「处理完成前」的留言可删除；是否具备权限由后端校验（物业经理/客服/平台） */
function canDeleteReply(n: any): boolean {
  return n?.type === 'REPLY' && n.replyId != null && !ticketTerminated()
}

async function deleteReplyNode(n: any) {
  const o = detailData.value?.order
  if (!o) return
  try {
    await ElMessageBox.confirm('确定删除该条留言及其图片？', '删除确认', { type: 'warning' })
  } catch {
    return
  }
  const path = o.kind === 'REPAIR'
    ? `/staff/repairs/${o.id}/replies/${n.replyId}`
    : `/staff/complaints/${o.id}/replies/${n.replyId}`
  const { data } = await api.delete(path)
  if (data.code === 0) {
    ElMessage.success('留言已删除')
    await openDetail({ biz: o.kind === 'REPAIR' ? 'REPAIR' : 'COMPLAINT', id: o.id } as Ticket)
    load()
  } else ElMessage.error(data.message)
}

async function editReplyNode(n: any) {
  const o = detailData.value?.order
  if (!o || n.replyId == null) return
  try {
    const { value } = await ElMessageBox.prompt('修改留言内容', '修改留言', {
      inputValue: n.content || '',
      inputType: 'textarea',
      confirmButtonText: '保存',
    })
    const content = String(value || '').trim()
    if (!content) {
      ElMessage.warning('内容不能为空')
      return
    }
    const path = o.kind === 'REPAIR'
      ? `/staff/repairs/${o.id}/replies/${n.replyId}`
      : `/staff/complaints/${o.id}/replies/${n.replyId}`
    const body = o.kind === 'REPAIR' ? { content } : { replyContent: content }
    const { data } = await api.put(path, body)
    if (data.code === 0) {
      ElMessage.success('已修改')
      await openDetail({ biz: o.kind === 'REPAIR' ? 'REPAIR' : 'COMPLAINT', id: o.id } as Ticket)
      load()
    } else ElMessage.error(data.message)
  } catch {
    /* cancel */
  }
}

async function deleteReplyImage(n: any, attachmentId: number) {
  const o = detailData.value?.order
  if (!o || n.replyId == null) return
  try {
    await ElMessageBox.confirm('确定删除该图片？', '删除确认', { type: 'warning' })
  } catch {
    return
  }
  const path = o.kind === 'REPAIR'
    ? `/staff/repairs/${o.id}/replies/${n.replyId}/attachments/${attachmentId}`
    : `/staff/complaints/${o.id}/replies/${n.replyId}/attachments/${attachmentId}`
  const { data } = await api.delete(path)
  if (data.code === 0) {
    ElMessage.success('图片已删除')
    await openDetail({ biz: o.kind === 'REPAIR' ? 'REPAIR' : 'COMPLAINT', id: o.id } as Ticket)
  } else ElMessage.error(data.message)
}

async function onReplyFile(file: File) {
  if (pendingFiles.value.length >= 3) {
    ElMessage.warning('最多 3 张照片')
    return false
  }
  uploading.value = true
  try {
    const body = new FormData()
    body.append('file', file)
    body.append('bizType', 'TEMP')
    // 勿手写 multipart Content-Type，否则缺少 boundary，服务端解析不到文件
    const { data } = await api.post('/attachments/upload', body)
    if (data.code === 0 && data.data?.id) {
      pendingFiles.value.push({ id: data.data.id, fileName: data.data.fileName || file.name })
      ElMessage.success('照片已上传')
    } else ElMessage.error(data.message || '上传失败')
  } catch {
    ElMessage.error('上传失败')
  } finally {
    uploading.value = false
  }
  return false
}

function removePendingFile(id: number) {
  pendingFiles.value = pendingFiles.value.filter((f) => f.id !== id)
  api.delete(`/attachments/${id}`).catch(() => {})
}

async function submitReply() {
  if (replyId.value == null || !replyText.value.trim()) return
  const attachmentIds = pendingFiles.value.map((f) => f.id)
  if (replyBiz.value === 'REPAIR') {
    const { data } = await api.post(`/staff/repairs/${replyId.value}/replies`, {
      content: replyText.value.trim(),
      attachmentIds,
    })
    if (data.code === 0) {
      ElMessage.success('已回复')
      replyOpen.value = false
      pendingFiles.value = []
      load()
    } else ElMessage.error(data.message)
    return
  }
  const { data } = await api.post(`/staff/complaints/${replyId.value}/handle`, {
    replyContent: replyText.value.trim(),
    attachmentIds,
  })
  if (data.code === 0) {
    ElMessage.success('已处理')
    replyOpen.value = false
    pendingFiles.value = []
    load()
  } else ElMessage.error(data.message)
}

async function closeTicket(row: Ticket) {
  if (row.biz === 'REPAIR') {
    const { data } = await api.post(`/staff/repairs/${row.id}/close`)
    if (data.code === 0) {
      ElMessage.success('已关闭')
      load()
    } else ElMessage.error(data.message)
    return
  }
  try {
    await ElMessageBox.confirm('确定关闭该单？', '关闭确认', { type: 'warning' })
  } catch {
    return
  }
  const { data } = await api.post(`/staff/complaints/${row.id}/close`)
  if (data.code === 0) {
    ElMessage.success('已关闭')
    load()
  } else ElMessage.error(data.message)
}

function applyRouteQuery() {
  const cat = typeof route.query.category === 'string' ? route.query.category : ''
  const kind = typeof route.query.kind === 'string' ? route.query.kind : ''
  kindGate.value = kind === 'repair' || kind === 'complaint' ? kind : ''
  if (cat && CATEGORY_OPTIONS.some((o) => o.value === cat)) {
    categoryQ.value = cat
  }
}

watch(categoryQ, () => {
  // 切换类型后若当前状态不属于该类型，清空状态筛选
  if (statusQ.value && !statusFilterOptions.value.some((o) => o.value === statusQ.value)) {
    statusQ.value = ''
  }
})

onMounted(() => {
  applyRouteQuery()
  load()
  loadTeam()
})
</script>

<template>
  <div class="page" v-loading="loading">
    <header>
      <div class="hd-top">
        <div>
          <h2>报事报修</h2>
          <p class="sub">公区报障 · 室内维修 · 投诉 · 表扬 · 咨询建议</p>
        </div>
        <StaffSessionAction />
      </div>
      <StaffNav />
    </header>

    <section class="block">
      <div class="sec-head">
        <h3>工单列表</h3>
        <div class="row">
          <el-select v-model="categoryQ" placeholder="类型" style="width:140px" clearable>
            <el-option v-for="o in CATEGORY_OPTIONS" :key="o.value || 'all'" :label="o.label" :value="o.value" />
          </el-select>
          <el-button v-if="kindGate" link type="primary" @click="kindGate = ''">查看全部类型</el-button>
          <el-select v-model="statusQ" placeholder="状态" style="width:140px" clearable>
            <el-option
              v-for="o in statusFilterOptions"
              :key="o.value || 'all-st'"
              :label="o.label"
              :value="o.value"
            />
          </el-select>
          <el-input
            v-model="keywordQ"
            placeholder="搜索内容 / 联系人"
            clearable
            style="width:220px"
          />
          <el-button type="primary" @click="load">刷新</el-button>
        </div>
      </div>

      <el-table :data="filtered" size="small" class="ticket-table">
        <el-table-column type="index" label="序号" width="60" :index="(i: number) => i + 1" />
        <el-table-column label="类型" width="88">
          <template #default="{ row }">
            <span class="tag" :class="'tone-' + categoryTone(row.category)">{{ row.category }}</span>
          </template>
        </el-table-column>
        <el-table-column label="房屋" width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.roomLabel || row.roomId || '—' }}</template>
        </el-table-column>
        <el-table-column label="内容" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.summary || '—' }}</template>
        </el-table-column>
        <el-table-column label="联系人" width="72" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.biz === 'COMPLAINT'">{{ row.contactName || '—' }}</span>
            <span v-else class="mute">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="72">
          <template #default="{ row }">{{ statusLabel(row.status) }}</template>
        </el-table-column>
        <el-table-column label="处理岗" width="100" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ handlerText(row) }}</span>
            <el-tag v-if="handlerPendingClaim(row)" size="small" type="warning" class="claim-tag">待接</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="126">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right" class-name="ops-col">
          <template #default="{ row }">
            <el-button link type="info" @click="openDetail(row)">详情</el-button>
            <el-button v-if="canReplyRow(row)" link type="primary" @click="openReply(row)">提交回复</el-button>
            <el-button v-if="canAssignRow(row)" link @click="openAssign(row)">
              {{ row.status === 'PENDING_ASSIGN' || row.status === 'PENDING' ? '派单' : '转派' }}
            </el-button>
            <el-button v-if="canCompleteRow(row)" link type="warning" @click="complete(row)">处理完成</el-button>
          </template>
        </el-table-column>
      </el-table>
      <p v-if="!loading && !filtered.length" class="empty">暂无工单</p>
    </section>

    <!-- 派单 / 转派弹窗：派给岗位 -->
    <el-dialog
      v-model="assignOpen"
      :title="isTransferAssign ? '转派工单' : '派单'"
      width="460px"
    >
      <div v-if="assignRow" class="assign-ticket">
        <span class="tag" :class="'tone-' + categoryTone(assignRow.category)">{{ assignRow.category }}</span>
        <span class="assign-desc">{{ assignRow.summary }}</span>
      </div>
      <el-form label-width="80px">
        <el-form-item label="接收岗位">
          <el-select v-model="assignRole" placeholder="选择岗位" style="width: 100%">
            <el-option v-for="o in ROLE_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
          <div class="assign-hint">工单将进入该岗位的待接单池，由岗位员工在小程序接单后处理</div>
        </el-form-item>
        <el-form-item label="派单附言">
          <el-input
            v-model="assignRemark"
            type="textarea"
            :rows="3"
            placeholder="选填，将作为留言同步给业主"
            maxlength="200"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="assignOpen = false">取消</el-button>
        <el-button type="primary" :loading="assignSubmitting" @click="submitAssign">
          {{ isTransferAssign ? '确认转派' : '确认派单' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="replyOpen"
      :title="replyBiz === 'REPAIR' ? '回复报修' : '处理回复'"
      width="520px"
    >
      <el-input v-model="replyText" type="textarea" :rows="4" placeholder="回复内容" />
      <div class="reply-photos">
        <el-upload
          :show-file-list="false"
          :before-upload="onReplyFile"
          accept="image/*"
          :disabled="uploading || pendingFiles.length >= 3"
        >
          <el-button :loading="uploading">添加照片（{{ pendingFiles.length }}/3）</el-button>
        </el-upload>
        <div class="thumb-list" v-if="pendingFiles.length">
          <div class="thumb" v-for="f in pendingFiles" :key="f.id">
            <img :src="attachUrl(f.id)" class="thumb-img" alt="" />
            <span class="thumb-del" @click="removePendingFile(f.id)">×</span>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="replyOpen = false">取消</el-button>
        <el-button type="primary" @click="submitReply">提交</el-button>
      </template>
    </el-dialog>

    <!-- 工单详情：居中窗口弹窗（完整订单过程 + 图片） -->
    <el-dialog v-model="detailOpen" title="工单详情" width="640px" top="6vh" class="detail-dialog">
      <div v-loading="detailLoading" class="detail">
        <template v-if="detailData">
          <div class="detail-head">
            <span class="tag" :class="'tone-' + categoryTone(detailData.order?.category)">{{ detailData.order?.category }}</span>
            <span class="mute">{{ statusLabel(detailData.order?.status) }}</span>
          </div>
          <p class="detail-content">{{ detailData.order?.content }}</p>
          <p class="mute" v-if="detailData.order?.roomLabel">房屋：{{ detailData.order.roomLabel }}</p>
          <p class="mute" v-if="detailData.order?.location">位置：{{ detailData.order.location }}</p>

          <h4 class="detail-sec">订单过程</h4>
          <div class="tl">
            <div class="tl-item" v-for="(n, i) in (detailData.timeline || [])" :key="i">
              <div class="tl-dot" :class="'tl-' + n.type"></div>
              <div class="tl-body">
                <div class="tl-head">
                  <span class="tl-title">{{ n.title }}</span>
                  <span class="tl-time">{{ formatTime(n.time) }}</span>
                </div>
                <div class="tl-op" v-if="n.opName">{{ n.opName }}</div>

                <!-- 业主评价：与小程序相同模板（是否解决 + 三维度星级 + 补充说明） -->
                <div v-if="n.type === 'RATE'" class="rate-card">
                  <div class="rate-row">
                    <span class="rate-label">是否解决</span>
                    <span class="rate-res" :class="{ no: Number(n.ratingResolved) === 0 }">
                      {{ Number(n.ratingResolved) === 0 ? '未解决' : '解决' }}
                    </span>
                  </div>
                  <div class="rate-row">
                    <span class="rate-label">响应及时</span>
                    <el-rate :model-value="Number(n.ratingResponse ?? n.rating ?? 0)" disabled size="small" />
                  </div>
                  <div class="rate-row">
                    <span class="rate-label">处理及时</span>
                    <el-rate :model-value="Number(n.ratingHandling ?? n.rating ?? 0)" disabled size="small" />
                  </div>
                  <div class="rate-row">
                    <span class="rate-label">处理结果</span>
                    <el-rate :model-value="Number(n.ratingSatisfaction ?? n.rating ?? 0)" disabled size="small" />
                  </div>
                  <div class="rate-comment" v-if="n.content">{{ n.content }}</div>
                </div>

                <div class="tl-content" v-else-if="n.content">{{ n.content }}</div>
                <div class="tl-target" v-if="n.target">→ {{ n.target }}</div>
                <!-- 物业经理/客服：处理完成前可改删被派单岗位的留言（含图片） -->
                <div class="tl-actions" v-if="canDeleteReply(n)">
                  <el-button link type="primary" size="small" @click="editReplyNode(n)">修改留言</el-button>
                  <el-button link type="danger" size="small" @click="deleteReplyNode(n)">删除留言</el-button>
                </div>
                <div class="tl-imgs" v-if="n.images && n.images.length">
                  <div v-for="img in n.images" :key="img.id" class="tl-img-wrap">
                    <el-image
                      :src="attachUrl(img.id)"
                      :preview-src-list="n.images.map((x: any) => attachUrl(x.id))"
                      class="tl-img"
                      fit="cover"
                    />
                    <span
                      v-if="canDeleteReply(n)"
                      class="tl-img-del"
                      @click="deleteReplyImage(n, img.id)"
                    >×</span>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </template>
      </div>
      <template #footer>
        <template v-if="detailData?.order">
          <el-button
            v-if="detailData.order.status === 'PENDING_ASSIGN'
              || detailData.order.status === 'PENDING'
              || detailData.order.status === 'ASSIGNED'
              || detailData.order.status === 'IN_PROGRESS'
              || detailData.order.status === 'PROCESSING'
              || detailData.order.status === 'REPLIED'"
            type="warning"
            @click="assignFromDetail"
          >{{ detailData.order.status === 'PENDING_ASSIGN' || detailData.order.status === 'PENDING' ? '派单' : '转派' }}</el-button>
          <el-button
            v-if="(detailData.order.kind === 'REPAIR'
                    && ['ASSIGNED', 'IN_PROGRESS'].includes(detailData.order.status))
                  || (detailData.order.kind !== 'REPAIR'
                    && ['PENDING', 'ASSIGNED', 'PROCESSING', 'REPLIED'].includes(detailData.order.status))"
            type="warning"
            plain
            @click="completeFromDetail"
          >处理完成</el-button>
          <el-button
            v-if="detailData.order.status !== 'COMPLETED' && detailData.order.status !== 'CLOSED' && detailData.order.status !== 'DONE_WAIT_RATE'"
            type="primary"
            @click="replyFromDetail"
          >提交回复</el-button>
        </template>
        <el-button @click="detailOpen = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding: 24px 32px; max-width: 1500px; margin: 0 auto; }
header { display: block; }
.hd-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}
h2 { margin: 0; color: #1f4e3d; }
.sub { margin: 4px 0 0; color: #667; font-size: 14px; }
.block {
  margin-top: 16px;
  background: #fff;
  border: 1px solid #dfe6e1;
  padding: 18px 20px;
}
.sec-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}
h3 { margin: 0; font-size: 15px; color: #1f4e3d; }
.row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.tag {
  display: inline-block;
  font-size: 11px;
  line-height: 1;
  padding: 4px 6px;
  border-radius: 3px;
}
.ticket-table :deep(.el-table__cell) {
  padding: 6px 0;
}
.ticket-table :deep(.ops-col .cell) {
  display: flex;
  flex-wrap: wrap;
  gap: 0 2px;
  line-height: 1.2;
}
.ticket-table :deep(.ops-col .el-button) {
  margin: 0;
  padding: 0 4px;
}
.tone-orange { background: rgba(196, 92, 38, 0.12); color: #c45c26; }
.tone-blue { background: rgba(36, 99, 140, 0.12); color: #24638c; }
.tone-gold { background: rgba(168, 132, 40, 0.14); color: #8a6a12; }
.tone-coral { background: rgba(163, 75, 44, 0.12); color: #a34b2c; }
.tone-mint { background: rgba(31, 106, 90, 0.12); color: #1f6a5a; }
.tone-mute { background: #eef2f0; color: #5c6f68; }
.mute { color: #9aa8a1; }
.rate-line { display: flex; align-items: center; gap: 6px; }
.rate-dims { font-size: 12px; color: #5c6f68; margin-top: 2px; }
.rate-card {
  margin-top: 8px;
  padding: 10px 12px;
  background: #f6f9f7;
  border-radius: 6px;
}
.rate-card .rate-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 6px;
}
.rate-card .rate-row:first-child { margin-top: 0; }
.rate-card .rate-label {
  width: 64px;
  flex-shrink: 0;
  font-size: 13px;
  color: #5c6f68;
}
.rate-card .rate-res {
  font-size: 13px;
  color: #1f6a5a;
  font-weight: 600;
}
.rate-card .rate-res.no { color: #c45c26; }
.rate-card .rate-comment {
  margin-top: 8px;
  font-size: 13px;
  color: #3c4a44;
  line-height: 1.5;
}
.detail-rate {
  margin-top: 10px;
  padding: 10px 12px;
  background: #f6f9f7;
  border-radius: 6px;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.rate-comment {
  font-size: 12px;
  color: var(--muted);
  line-height: 1.5;
  margin-top: 2px;
}
.empty { margin: 24px 0 8px; text-align: center; color: #8a9a94; font-size: 13px; }
.claim-tag { margin-left: 6px; }
.assign-ticket {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
  padding: 8px 10px;
  background: #f6f9f7;
  border-radius: 6px;
}
.assign-desc {
  font-size: 13px;
  color: #3c4a44;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.assign-hint {
  font-size: 12px;
  color: #8a9a94;
  line-height: 1.5;
  margin-top: 4px;
}
.reply-photos { margin-top: 14px; }
.thumb-list { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 10px; }
.thumb { position: relative; width: 84px; height: 84px; border-radius: 6px; overflow: hidden; background: #f6f9f7; }
.thumb-img { width: 100%; height: 100%; object-fit: cover; display: block; }
.thumb-del {
  position: absolute;
  top: 2px; right: 2px;
  width: 20px; height: 20px;
  line-height: 18px;
  text-align: center;
  border-radius: 50%;
  background: rgba(22, 33, 29, 0.6);
  color: #fff;
  font-size: 14px;
  cursor: pointer;
}

/* 详情抽屉：时间线 */
.detail { padding: 4px 2px; }
.detail-head { display: flex; align-items: center; gap: 10px; }
.detail-content { margin: 12px 0 4px; font-size: 14px; color: #2a3631; line-height: 1.7; }
.detail-sec { margin: 20px 0 10px; font-size: 14px; color: #1f4e3d; }
.tl { position: relative; }
.tl-item { display: flex; gap: 12px; padding-bottom: 18px; position: relative; }
.tl-item:last-child { padding-bottom: 0; }
.tl-dot {
  width: 12px; height: 12px;
  margin-top: 4px; margin-left: 2px;
  border-radius: 50%;
  background: #1f4e3d;
  flex-shrink: 0;
  z-index: 2;
}
.tl-dot.tl-DISPATCH { background: #c9a063; }
.tl-dot.tl-REPLY { background: #1a6b5c; }
.tl-dot.tl-COMPLETE { background: #2e7d5b; }
.tl-dot.tl-RATE { background: #b8791f; }
.tl-item:not(:last-child)::before {
  content: '';
  position: absolute;
  left: 8px; top: 18px; bottom: 0;
  width: 2px; background: #e3e8e5;
}
.tl-body { flex: 1; min-width: 0; }
.tl-head { display: flex; justify-content: space-between; align-items: baseline; gap: 10px; }
.tl-title { font-size: 13px; font-weight: 600; color: #1f2d28; }
.tl-time { font-size: 12px; color: #8a9a94; flex-shrink: 0; }
.tl-op { font-size: 12px; color: #1f4e3d; margin-top: 2px; }
.tl-content { font-size: 13px; color: #3c4a44; line-height: 1.6; margin-top: 4px; white-space: pre-wrap; }
.tl-target { font-size: 12px; color: #8a9a94; margin-top: 2px; }
.tl-imgs { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 8px; }
.tl-img-wrap { position: relative; width: 88px; height: 88px; }
.tl-img { width: 88px; height: 88px; border-radius: 6px; }
.tl-img-del {
  position: absolute; top: -6px; right: -6px;
  width: 20px; height: 20px; border-radius: 50%;
  background: #c45656; color: #fff; font-size: 14px; line-height: 20px;
  text-align: center; cursor: pointer;
}

</style>
