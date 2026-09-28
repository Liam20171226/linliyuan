<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import QRCode from 'qrcode'
import { api } from '../api'
import { authFailRedirectPath } from '../authSession'
import StaffNav from '../components/StaffNav.vue'
import StaffSessionAction from '../components/StaffSessionAction.vue'

const CAT_OPTIONS = [
  { value: 'SAFETY', label: '安全类', reqKey: 'reqSafety' },
  { value: 'CLEANING', label: '保洁类', reqKey: 'reqCleaning' },
  { value: 'FACILITY', label: '机电维修类', reqKey: 'reqFacility' },
  { value: 'LANDSCAPE', label: '绿化类', reqKey: 'reqLandscape' },
] as const

const ROLE_OPTIONS = [
  { value: 'SECURITY', label: '保安' },
  { value: 'CLEANING', label: '保洁' },
  { value: 'FACILITY_MAINT', label: '机电维修' },
  { value: 'LANDSCAPING', label: '绿化' },
]

const FREQ_OPTIONS = [
  { value: 'DAY', label: '每日' },
  { value: 'WEEK', label: '每周' },
  { value: 'MONTH', label: '每月' },
  { value: 'QUARTER', label: '每季' },
  { value: 'YEAR', label: '每年' },
]

const TAB_KEYS = ['spots', 'plans', 'jobs', 'photos'] as const
type TabKey = (typeof TAB_KEYS)[number]

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const jobs = ref<any[]>([])
const jobFilter = ref<'OPEN' | 'DONE' | 'ALL'>('OPEN')

const activeTab = computed<TabKey>(() => {
  const t = route.query.tab
  if (typeof t === 'string' && (TAB_KEYS as readonly string[]).includes(t)) return t as TabKey
  return 'spots'
})

function onTabChange(name: string | number) {
  const tab = String(name)
  if (tab === activeTab.value) return
  router.replace({ path: '/inspection', query: tab === 'spots' ? {} : { tab } })
}

const spaceBuildings = ref<any[]>([])
const spots = ref<any[]>([])
const selectedSpotIds = ref<number[]>([])
const spotDialog = ref(false)
const spotEditingId = ref<number | null>(null)
const spotForm = ref({
  buildingId: null as number | null,
  unitId: null as number | null,
  floorId: null as number | null,
  name: '',
  categories: [] as string[],
  reqSafety: '',
  reqCleaning: '',
  reqFacility: '',
  reqLandscape: '',
  sortNo: 0,
})

const spotUnitOptions = computed(() => {
  const b = spaceBuildings.value.find((x) => x.id === spotForm.value.buildingId)
  return b?.units || []
})
const spotFloorOptions = computed(() => {
  if (spotForm.value.unitId) {
    const u = spotUnitOptions.value.find((x: any) => x.id === spotForm.value.unitId)
    return u?.floors || []
  }
  // 未选单元时：列出该楼栋下全部楼层
  const out: any[] = []
  for (const u of spotUnitOptions.value) {
    for (const f of u.floors || []) out.push(f)
  }
  return out
})

const plans = ref<any[]>([])
const planDialog = ref(false)
const planEditingId = ref<number | null>(null)
const planDetailOpen = ref(false)
const planDetail = ref<any>(null)
const planForm = ref({
  title: '',
  startAt: '',
  endAt: '',
  freqUnit: 'DAY',
  freqTimes: 1,
  ordered: false,
  requirementNote: '',
  executorRoles: [] as string[],
  spotIds: [] as number[],
})

const photos = ref<any[]>([])
const photoTotal = ref(0)
const photoPage = ref(1)
const photoJobId = ref<number | null>(null)
const photoJobLabel = ref('')
const photoThumbs = ref<Record<number, string>>({})
const objectUrls = ref<string[]>([])

function pad(n: number) {
  return String(n).padStart(2, '0')
}
function toLocalApi(dt: string | Date) {
  const d = typeof dt === 'string' ? new Date(dt) : dt
  if (Number.isNaN(d.getTime())) return ''
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}
function formatTime(v: string | null | undefined) {
  if (!v) return '—'
  return String(v).replace('T', ' ').slice(0, 16)
}
async function makeQrDataUrl(content: string) {
  const text = (content || '').trim()
  if (!text) throw new Error('二维码内容为空')
  return QRCode.toDataURL(text, {
    width: 280,
    margin: 2,
    errorCorrectionLevel: 'M',
    color: { dark: '#16211D', light: '#FFFFFF' },
  })
}
function statusTag(st: string) {
  if (st === 'PUBLISHED') return 'success'
  if (st === 'DRAFT') return 'info'
  if (st === 'CANCELLED') return 'danger'
  return ''
}

const spotDialogTitle = computed(() => (spotEditingId.value ? '编辑巡检位置' : '新增巡检位置'))
const planDialogTitle = computed(() => (planEditingId.value ? '编辑巡检计划' : '新建巡检计划'))

async function loadFloors() {
  const { data } = await api.get('/staff/space-tree')
  if (data.code === 40101 || data.code === 40301) {
    router.push(authFailRedirectPath())
    return
  }
  if (data.code !== 0) return
  const buildings = Array.isArray(data.data?.buildings)
    ? data.data.buildings
    : Array.isArray(data.data)
      ? data.data
      : []
  spaceBuildings.value = buildings
}

function findFloorPath(floorId: number | null | undefined) {
  if (floorId == null) return { buildingId: null as number | null, unitId: null as number | null }
  for (const b of spaceBuildings.value) {
    for (const u of b.units || []) {
      for (const f of u.floors || []) {
        if (f.id === floorId) {
          return { buildingId: b.id as number, unitId: u.id as number }
        }
      }
    }
  }
  return { buildingId: null as number | null, unitId: null as number | null }
}

function onSpotBuildingChange() {
  spotForm.value.unitId = null
  spotForm.value.floorId = null
}
function onSpotUnitChange() {
  spotForm.value.floorId = null
}
function onSpotFloorChange(floorId: number | null) {
  if (floorId == null) return
  for (const u of spotUnitOptions.value) {
    if ((u.floors || []).some((f: any) => f.id === floorId)) {
      spotForm.value.unitId = u.id
      return
    }
  }
}

async function loadSpots() {
  const { data } = await api.get('/staff/inspect/spots')
  if (data.code === 40101 || data.code === 40301) {
    router.push(authFailRedirectPath())
    return
  }
  if (data.code !== 0) {
    ElMessage.error(data.message)
    spots.value = []
    return
  }
  spots.value = Array.isArray(data.data) ? data.data : []
  selectedSpotIds.value = selectedSpotIds.value.filter((id) => spots.value.some((s) => s.id === id))
}

async function loadPlans() {
  const { data } = await api.get('/staff/inspect/plans')
  if (data.code === 40101 || data.code === 40301) {
    router.push(authFailRedirectPath())
    return
  }
  if (data.code !== 0) {
    ElMessage.error(data.message)
    plans.value = []
    return
  }
  plans.value = Array.isArray(data.data) ? data.data : []
}

async function loadJobs() {
  const params: Record<string, string> = {}
  if (jobFilter.value && jobFilter.value !== 'ALL') params.status = jobFilter.value
  const { data } = await api.get('/staff/inspect/jobs', { params })
  if (data.code === 40101 || data.code === 40301) {
    router.push(authFailRedirectPath())
    return
  }
  if (data.code !== 0) {
    ElMessage.error(data.message)
    jobs.value = []
    return
  }
  jobs.value = data.data?.list || []
}

function jobStatusType(st: string) {
  if (st === 'DONE') return 'success'
  if (st === 'IN_PROGRESS') return 'warning'
  if (st === 'OPEN') return 'info'
  if (st === 'CANCELLED') return 'danger'
  return ''
}

async function loadPhotos() {
  const params: Record<string, number> = { page: photoPage.value, pageSize: 40 }
  if (photoJobId.value) params.jobId = photoJobId.value
  const { data } = await api.get('/staff/inspect/photos', { params })
  if (data.code !== 0) {
    ElMessage.error(data.message)
    photos.value = []
    return
  }
  photos.value = data.data?.list || []
  photoTotal.value = data.data?.total || 0
  for (const row of photos.value) {
    const id = row.photoAttachmentId
    if (!id || photoThumbs.value[id]) continue
    try {
      const res = await api.get(`/attachments/${id}`, { responseType: 'blob' })
      const url = URL.createObjectURL(res.data)
      objectUrls.value.push(url)
      photoThumbs.value = { ...photoThumbs.value, [id]: url }
    } catch {
      /* ignore */
    }
  }
}

function viewJobPhotos(row: any) {
  photoJobId.value = row.id
  photoJobLabel.value = `${row.planTitle || '任务'} · 第${row.seqNo}轮`
  photoPage.value = 1
  router.replace({ path: '/inspection', query: { tab: 'photos' } })
}

function clearPhotoJobFilter() {
  photoJobId.value = null
  photoJobLabel.value = ''
  photoPage.value = 1
  loadPhotos()
}

async function refresh() {
  loading.value = true
  try {
    if (activeTab.value === 'spots') {
      await loadFloors()
      await loadSpots()
    } else if (activeTab.value === 'plans') await loadPlans()
    else if (activeTab.value === 'jobs') await loadJobs()
    else await loadPhotos()
  } catch (e: any) {
    const code = e?.response?.data?.code
    if (code === 40101 || code === 40301) {
      router.push(authFailRedirectPath())
      return
    }
    ElMessage.error(e?.response?.data?.message || e?.message || '加载失败')
  } finally {
    loading.value = false
  }
}

function resetSpotForm() {
  spotEditingId.value = null
  spotForm.value = {
    buildingId: null,
    unitId: null,
    floorId: null,
    name: '',
    categories: [],
    reqSafety: '',
    reqCleaning: '',
    reqFacility: '',
    reqLandscape: '',
    sortNo: 0,
  }
}

async function openCreateSpot() {
  await loadFloors()
  resetSpotForm()
  const maxNo = spots.value.reduce((m, s) => Math.max(m, Number(s.sortNo) || 0), 0)
  spotForm.value.sortNo = maxNo + 1
  spotDialog.value = true
}

async function openEditSpot(row: any) {
  await loadFloors()
  const path = findFloorPath(row.floorId)
  spotEditingId.value = row.id
  spotForm.value = {
    buildingId: path.buildingId ?? row.buildingId ?? null,
    unitId: path.unitId ?? row.unitId ?? null,
    floorId: row.floorId,
    name: row.name || '',
    categories: [...(row.categories || [])],
    reqSafety: row.reqSafety || '',
    reqCleaning: row.reqCleaning || '',
    reqFacility: row.reqFacility || '',
    reqLandscape: row.reqLandscape || '',
    sortNo: row.sortNo || 0,
  }
  spotDialog.value = true
}

async function saveSpot() {
  const f = spotForm.value
  if (!f.buildingId) {
    ElMessage.warning('请选择楼栋')
    return
  }
  if (!f.name.trim()) {
    ElMessage.warning('请填写位置名称')
    return
  }
  if (!f.categories.length) {
    ElMessage.warning('请至少选择一个类别')
    return
  }
  const body: Record<string, unknown> = {
    buildingId: f.buildingId,
    unitId: f.unitId,
    floorId: f.floorId,
    name: f.name.trim(),
    categories: f.categories,
    reqSafety: f.reqSafety,
    reqCleaning: f.reqCleaning,
    reqFacility: f.reqFacility,
    reqLandscape: f.reqLandscape,
    sortNo: f.sortNo,
  }
  const { data } = spotEditingId.value
    ? await api.put(`/staff/inspect/spots/${spotEditingId.value}`, body)
    : await api.post('/staff/inspect/spots', body)
  if (data.code !== 0) {
    ElMessage.error(data.message)
    return
  }
  ElMessage.success(spotEditingId.value ? '已保存' : '已新增')
  spotDialog.value = false
  loadSpots()
}

async function removeSpot(row: any) {
  try {
    await ElMessageBox.confirm(`确认删除位置「${row.name}」？`, '删除位置', { type: 'warning' })
  } catch {
    return
  }
  const { data } = await api.delete(`/staff/inspect/spots/${row.id}`)
  if (data.code !== 0) {
    ElMessage.error(data.message)
    return
  }
  ElMessage.success('已删除')
  loadSpots()
}

const qrPreviewOpen = ref(false)
const qrPreviewLoading = ref(false)
const qrPreview = ref<{ name: string; address: string; cats: string; dataUrl: string; content: string } | null>(null)

async function printQr(rows: any[]) {
  if (!rows.length) {
    ElMessage.warning('请先选择位置')
    return
  }
  // 必须在用户点击同步栈内先开窗，否则 await 后会被浏览器拦截
  const w = window.open('', '_blank')
  if (!w) {
    ElMessage.error('浏览器拦截了打印窗口，请允许本站弹窗后重试')
    return
  }
  w.document.write('<!DOCTYPE html><html><head><meta charset="utf-8"/><title>巡检二维码</title></head><body><p style="font-family:sans-serif;padding:24px;color:#666">正在生成二维码…</p></body></html>')
  w.document.close()

  try {
    const cards: string[] = []
    for (const r of rows) {
      const content = r.qrContent || (r.qrToken ? `PROPERTY_INSPECT:${r.qrToken}` : '')
      if (!content) {
        throw new Error(`位置「${r.name || r.id}」缺少二维码内容`)
      }
      const dataUrl = await makeQrDataUrl(content)
      cards.push(`
      <div class="card">
        <img src="${dataUrl}" alt="qr" />
        <div class="name">${escapeHtml(r.name || '')}</div>
        <div class="addr">${escapeHtml(r.address || '')}</div>
        <div class="cats">${escapeHtml((r.categoryLabels || []).join(' · '))}</div>
      </div>`)
    }
    w.document.open()
    w.document.write(`<!DOCTYPE html><html><head><meta charset="utf-8"/><title>巡检二维码</title>
    <style>
      body{font-family:sans-serif;padding:16px;color:#16211D}
      .grid{display:flex;flex-wrap:wrap;gap:16px}
      .card{width:200px;border:1px solid #ddd;padding:12px;text-align:center;page-break-inside:avoid}
      .card img{width:160px;height:160px}
      .name{font-weight:700;margin-top:8px;font-size:14px}
      .addr,.cats{font-size:12px;color:#666;margin-top:4px}
      @media print{body{padding:0}.card{border-color:#999}}
    </style></head><body>
    <h2>巡检位置二维码（共 ${rows.length} 个）</h2>
    <div class="grid">${cards.join('')}</div>
    <script>setTimeout(function(){window.print()},400)<\/script>
    </body></html>`)
    w.document.close()
  } catch (e: any) {
    try { w.close() } catch { /* ignore */ }
    ElMessage.error(e?.message || '生成二维码失败')
  }
}

function escapeHtml(s: string) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function onSpotSelectionChange(rows: any[]) {
  selectedSpotIds.value = (rows || []).map((r) => r.id)
}

function printSelected() {
  const rows = spots.value.filter((s) => selectedSpotIds.value.includes(s.id))
  if (!rows.length) {
    ElMessage.warning('请先勾选要打印的位置')
    return
  }
  void printQr(rows)
}

async function printOne(row: any) {
  qrPreviewLoading.value = true
  qrPreviewOpen.value = true
  qrPreview.value = null
  try {
    const content = row.qrContent || (row.qrToken ? `PROPERTY_INSPECT:${row.qrToken}` : '')
    const dataUrl = await makeQrDataUrl(content)
    qrPreview.value = {
      name: row.name || '',
      address: row.address || '',
      cats: (row.categoryLabels || []).join(' · '),
      dataUrl,
      content,
    }
  } catch (e: any) {
    qrPreviewOpen.value = false
    ElMessage.error(e?.message || '生成二维码失败')
  } finally {
    qrPreviewLoading.value = false
  }
}

function printPreviewOne() {
  if (!qrPreview.value) return
  printQr([{
    name: qrPreview.value.name,
    address: qrPreview.value.address,
    categoryLabels: qrPreview.value.cats ? qrPreview.value.cats.split(' · ') : [],
    qrContent: qrPreview.value.content,
  }])
}

function defaultPlanRange() {
  const s = new Date()
  s.setSeconds(0, 0)
  const e = new Date(s.getTime() + 30 * 86400000)
  planForm.value.startAt = toLocalApi(s)
  planForm.value.endAt = toLocalApi(e)
}

function resetPlanForm() {
  planEditingId.value = null
  planForm.value = {
    title: '',
    startAt: '',
    endAt: '',
    freqUnit: 'DAY',
    freqTimes: 1,
    ordered: false,
    requirementNote: '',
    executorRoles: [],
    spotIds: [],
  }
  defaultPlanRange()
}

async function openCreatePlan() {
  if (!spots.value.length) await loadSpots()
  resetPlanForm()
  planDialog.value = true
}

async function openEditPlan(row: any) {
  if (!spots.value.length) await loadSpots()
  const { data } = await api.get(`/staff/inspect/plans/${row.id}`)
  if (data.code !== 0) {
    ElMessage.error(data.message)
    return
  }
  const p = data.data
  planEditingId.value = p.id
  planForm.value = {
    title: p.title || '',
    startAt: toLocalApi(p.startAt),
    endAt: toLocalApi(p.endAt),
    freqUnit: p.freqUnit || 'DAY',
    freqTimes: p.freqTimes || 1,
    ordered: !!p.ordered,
    requirementNote: p.requirementNote || '',
    executorRoles: [...(p.executorRoles || [])],
    spotIds: (p.spots || []).map((s: any) => s.id),
  }
  planDialog.value = true
}

async function viewPlan(row: any) {
  const { data } = await api.get(`/staff/inspect/plans/${row.id}`)
  if (data.code !== 0) {
    ElMessage.error(data.message)
    return
  }
  planDetail.value = data.data
  planDetailOpen.value = true
}

async function savePlan() {
  const f = planForm.value
  if (!f.title.trim()) {
    ElMessage.warning('请填写计划标题')
    return
  }
  if (!f.executorRoles.length) {
    ElMessage.warning('请选择执行岗位')
    return
  }
  if (!f.spotIds.length) {
    ElMessage.warning('请选择巡检点位')
    return
  }
  const body = {
    title: f.title.trim(),
    startAt: f.startAt,
    endAt: f.endAt,
    freqUnit: f.freqUnit,
    freqTimes: f.freqTimes,
    ordered: f.ordered,
    requirementNote: f.requirementNote,
    executorRoles: f.executorRoles,
    spotIds: f.spotIds,
  }
  const { data } = planEditingId.value
    ? await api.put(`/staff/inspect/plans/${planEditingId.value}`, body)
    : await api.post('/staff/inspect/plans', body)
  if (data.code !== 0) {
    ElMessage.error(data.message)
    return
  }
  ElMessage.success(planEditingId.value ? '已保存' : '已创建草稿')
  planDialog.value = false
  loadPlans()
}

async function publishPlan(row: any) {
  try {
    await ElMessageBox.confirm(`发布「${row.title}」后将生成待接单任务，确认？`, '发布计划', { type: 'warning' })
  } catch {
    return
  }
  const { data } = await api.post(`/staff/inspect/plans/${row.id}/publish`)
  if (data.code !== 0) {
    ElMessage.error(data.message)
    return
  }
  ElMessage.success('已发布')
  loadPlans()
}

async function cancelPlan(row: any) {
  try {
    await ElMessageBox.confirm(`确认取消计划「${row.title}」？进行中的任务将一并取消。`, '取消计划', { type: 'warning' })
  } catch {
    return
  }
  const { data } = await api.post(`/staff/inspect/plans/${row.id}/cancel`)
  if (data.code !== 0) {
    ElMessage.error(data.message)
    return
  }
  ElMessage.success('已取消')
  loadPlans()
}

watch(
  () => activeTab.value,
  () => { refresh() },
)
watch(jobFilter, () => {
  if (activeTab.value === 'jobs') loadJobs()
})
watch(photoPage, () => {
  if (activeTab.value === 'photos') loadPhotos()
})

onMounted(async () => {
  try {
    await loadFloors()
    await refresh()
  } catch (e: any) {
    const code = e?.response?.data?.code
    if (code === 40101 || code === 40301) {
      router.push(authFailRedirectPath())
      return
    }
    ElMessage.error(e?.response?.data?.message || e?.message || '加载失败')
  }
})
</script>

<template>
  <div class="page" v-loading="loading">
    <header>
      <div>
        <h2>巡检</h2>
        <p class="sub">位置 → 计划 → 执行；照片墙供核查</p>
        <StaffNav />
      </div>
      <StaffSessionAction />
    </header>

    <el-tabs :model-value="activeTab" class="tabs" @tab-change="onTabChange">
      <el-tab-pane label="位置" name="spots" />
      <el-tab-pane label="计划" name="plans" />
      <el-tab-pane label="执行" name="jobs" />
      <el-tab-pane label="照片墙" name="photos" />
    </el-tabs>

    <!-- 位置 -->
    <section v-if="activeTab === 'spots'" class="block">
      <div class="toolbar">
        <el-button type="primary" @click="openCreateSpot">新增位置</el-button>
        <el-button :disabled="!selectedSpotIds.length" @click="printSelected">
          批量打印二维码（{{ selectedSpotIds.length }}）
        </el-button>
      </div>
      <el-table
        :data="spots"
        row-key="id"
        @selection-change="onSpotSelectionChange"
      >
        <el-table-column type="selection" width="44" />
        <el-table-column label="序号" width="72" prop="sortNo" />
        <el-table-column label="位置" min-width="140" prop="name" />
        <el-table-column label="地址" min-width="180" prop="address" />
        <el-table-column label="类别" min-width="140">
          <template #default="{ row }">
            {{ (row.categoryLabels || []).join(' · ') || '—' }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="printOne(row)">二维码</el-button>
            <el-button link type="primary" @click="openEditSpot(row)">编辑</el-button>
            <el-button link type="danger" @click="removeSpot(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <p v-if="!spots.length" class="empty">暂无巡检位置，请先在楼层下新增</p>
    </section>

    <!-- 计划 -->
    <section v-else-if="activeTab === 'plans'" class="block">
      <div class="toolbar">
        <el-button type="primary" @click="openCreatePlan">新建计划</el-button>
      </div>
      <el-table :data="plans" row-key="id">
        <el-table-column label="标题" min-width="160" prop="title" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTag(row.status)">{{ row.statusLabel || row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="频率" width="120">
          <template #default="{ row }">
            {{ row.freqUnitLabel || row.freqUnit }} × {{ row.freqTimes }}
          </template>
        </el-table-column>
        <el-table-column label="执行岗位" min-width="140">
          <template #default="{ row }">
            {{ (row.executorRoleLabels || []).join('、') || '—' }}
          </template>
        </el-table-column>
        <el-table-column label="点位数" width="80" prop="spotCount" />
        <el-table-column label="起止" min-width="200">
          <template #default="{ row }">
            {{ formatTime(row.startAt) }} ~ {{ formatTime(row.endAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="viewPlan(row)">详情</el-button>
            <el-button v-if="row.status === 'DRAFT'" link type="primary" @click="openEditPlan(row)">编辑</el-button>
            <el-button
              v-if="row.status === 'DRAFT' || row.status === 'PUBLISHED'"
              link
              type="success"
              @click="publishPlan(row)"
            >发布</el-button>
            <el-button
              v-if="row.status !== 'CANCELLED'"
              link
              type="danger"
              @click="cancelPlan(row)"
            >取消</el-button>
          </template>
        </el-table-column>
      </el-table>
      <p v-if="!plans.length" class="empty">暂无计划</p>
    </section>

    <!-- 执行进度 -->
    <section v-else-if="activeTab === 'jobs'" class="block">
      <div class="toolbar">
        <el-radio-group v-model="jobFilter" size="small">
          <el-radio-button value="OPEN">待办 / 进行中</el-radio-button>
          <el-radio-button value="DONE">已完成</el-radio-button>
          <el-radio-button value="ALL">全部</el-radio-button>
        </el-radio-group>
      </div>
      <el-table :data="jobs" row-key="id">
        <el-table-column label="计划" min-width="160" prop="planTitle" />
        <el-table-column label="轮次" width="80">
          <template #default="{ row }">第{{ row.seqNo }}轮</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="jobStatusType(row.status)">{{ row.statusLabel || row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进度" width="100">
          <template #default="{ row }">{{ row.visitedCount || 0 }}/{{ row.spotCount || 0 }}</template>
        </el-table-column>
        <el-table-column label="执行人" width="120">
          <template #default="{ row }">{{ row.assigneeName || '—' }}</template>
        </el-table-column>
        <el-table-column label="接单" width="150">
          <template #default="{ row }">{{ formatTime(row.claimedAt) }}</template>
        </el-table-column>
        <el-table-column label="完成" width="150">
          <template #default="{ row }">{{ formatTime(row.doneAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="viewJobPhotos(row)">照片</el-button>
          </template>
        </el-table-column>
      </el-table>
      <p v-if="!jobs.length" class="empty">暂无执行任务（发布计划后生成）</p>
    </section>

    <!-- 照片墙 -->
    <section v-else class="block">
      <div v-if="photoJobId" class="toolbar">
        <el-tag closable type="info" @close="clearPhotoJobFilter">筛选：{{ photoJobLabel }}</el-tag>
      </div>
      <div class="photo-grid" v-if="photos.length">
        <div v-for="p in photos" :key="p.id" class="photo-card">
          <img v-if="photoThumbs[p.photoAttachmentId]" :src="photoThumbs[p.photoAttachmentId]" alt="" />
          <div v-else class="photo-ph">加载中</div>
          <div class="photo-meta">
            <div class="pn">{{ p.spotName || '点位' }}</div>
            <div class="ps">{{ p.planTitle }} · 第{{ p.jobSeq }}轮</div>
            <div class="ps">{{ p.userName }} · {{ formatTime(p.scannedAt) }}</div>
            <div v-if="p.note" class="pnote">{{ p.note }}</div>
          </div>
        </div>
      </div>
      <p v-else class="empty">暂无巡检照片</p>
      <div v-if="photoTotal > 40" class="pager">
        <el-pagination
          layout="prev, pager, next"
          :total="photoTotal"
          :page-size="40"
          v-model:current-page="photoPage"
        />
      </div>
    </section>

    <!-- 位置弹窗 -->
    <el-dialog v-model="spotDialog" :title="spotDialogTitle" width="560px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="所属楼栋" required>
          <el-select
            v-model="spotForm.buildingId"
            filterable
            placeholder="选择楼栋"
            style="width: 100%"
            @change="onSpotBuildingChange"
          >
            <el-option
              v-for="b in spaceBuildings"
              :key="b.id"
              :label="b.name"
              :value="b.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="所属单元">
          <el-select
            v-model="spotForm.unitId"
            filterable
            clearable
            placeholder="可选"
            style="width: 100%"
            :disabled="!spotForm.buildingId"
            @change="onSpotUnitChange"
          >
            <el-option
              v-for="u in spotUnitOptions"
              :key="u.id"
              :label="u.name"
              :value="u.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="所属楼层">
          <el-select
            v-model="spotForm.floorId"
            filterable
            clearable
            placeholder="可选"
            style="width: 100%"
            :disabled="!spotForm.buildingId"
            @change="onSpotFloorChange"
          >
            <el-option
              v-for="f in spotFloorOptions"
              :key="f.id"
              :label="f.name || (f.floorNo != null ? f.floorNo + '层' : '#' + f.id)"
              :value="f.id"
            />
          </el-select>
          <p v-if="!spaceBuildings.length" class="hint" style="margin-left:0;margin-top:6px">
            暂无楼栋，请先到「空间」页新建
          </p>
        </el-form-item>
        <el-form-item label="位置名称" required>
          <el-input v-model="spotForm.name" maxlength="64" placeholder="例如：东门消防栓、走廊配电箱" />
        </el-form-item>
        <el-form-item label="序号">
          <el-input-number v-model="spotForm.sortNo" :min="0" :max="9999" controls-position="right" />
          <span class="hint">列表与打印按序号升序排列，数字越小越靠前</span>
        </el-form-item>
        <el-form-item label="巡检类别" required>
          <el-checkbox-group v-model="spotForm.categories">
            <el-checkbox v-for="c in CAT_OPTIONS" :key="c.value" :label="c.value">{{ c.label }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item
          v-for="c in CAT_OPTIONS"
          v-show="spotForm.categories.includes(c.value)"
          :key="c.reqKey"
          :label="`${c.label}说明`"
        >
          <el-input
            v-if="c.reqKey === 'reqSafety'"
            v-model="spotForm.reqSafety"
            type="textarea"
            :rows="2"
            :placeholder="`${c.label}巡检要求（可选）`"
          />
          <el-input
            v-else-if="c.reqKey === 'reqCleaning'"
            v-model="spotForm.reqCleaning"
            type="textarea"
            :rows="2"
            :placeholder="`${c.label}巡检要求（可选）`"
          />
          <el-input
            v-else-if="c.reqKey === 'reqFacility'"
            v-model="spotForm.reqFacility"
            type="textarea"
            :rows="2"
            :placeholder="`${c.label}巡检要求（可选）`"
          />
          <el-input
            v-else
            v-model="spotForm.reqLandscape"
            type="textarea"
            :rows="2"
            :placeholder="`${c.label}巡检要求（可选）`"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="spotDialog = false">取消</el-button>
        <el-button type="primary" @click="saveSpot">保存</el-button>
      </template>
    </el-dialog>

    <!-- 计划弹窗 -->
    <el-dialog v-model="planDialog" :title="planDialogTitle" width="640px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="标题" required>
          <el-input v-model="planForm.title" maxlength="128" placeholder="例如：消防设施周巡" />
        </el-form-item>
        <div class="time-row">
          <el-form-item label="开始时间" required>
            <el-date-picker
              v-model="planForm.startAt"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ss"
              style="width: 100%"
            />
          </el-form-item>
          <el-form-item label="结束时间" required>
            <el-date-picker
              v-model="planForm.endAt"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ss"
              style="width: 100%"
            />
          </el-form-item>
        </div>
        <div class="time-row">
          <el-form-item label="频率" required>
            <el-select v-model="planForm.freqUnit" style="width: 100%">
              <el-option v-for="o in FREQ_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="每周期次数" required>
            <el-input-number v-model="planForm.freqTimes" :min="1" :max="30" />
          </el-form-item>
        </div>
        <el-form-item label="执行岗位" required>
          <el-checkbox-group v-model="planForm.executorRoles">
            <el-checkbox v-for="r in ROLE_OPTIONS" :key="r.value" :label="r.value">{{ r.label }}</el-checkbox>
          </el-checkbox-group>
        </el-form-item>
        <el-form-item label="按顺序巡检">
          <el-switch v-model="planForm.ordered" />
          <span class="hint">开启后须按点位顺序扫码</span>
        </el-form-item>
        <el-form-item label="巡检要求">
          <el-input v-model="planForm.requirementNote" type="textarea" :rows="2" maxlength="500" />
        </el-form-item>
        <el-form-item label="巡检点位" required>
          <el-select
            v-model="planForm.spotIds"
            multiple
            filterable
            placeholder="选择点位（顺序即执行顺序）"
            style="width: 100%"
          >
            <el-option
              v-for="s in spots"
              :key="s.id"
              :label="`${s.name}（${s.address || ''}）`"
              :value="s.id"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="planDialog = false">取消</el-button>
        <el-button type="primary" @click="savePlan">保存草稿</el-button>
      </template>
    </el-dialog>

    <!-- 二维码预览 -->
    <el-dialog v-model="qrPreviewOpen" title="巡检二维码" width="420px" destroy-on-close>
      <div v-loading="qrPreviewLoading" class="qr-preview">
        <template v-if="qrPreview">
          <img :src="qrPreview.dataUrl" alt="二维码" class="qr-img" />
          <div class="qr-name">{{ qrPreview.name }}</div>
          <div class="qr-meta" v-if="qrPreview.address">{{ qrPreview.address }}</div>
          <div class="qr-meta" v-if="qrPreview.cats">{{ qrPreview.cats }}</div>
        </template>
      </div>
      <template #footer>
        <el-button @click="qrPreviewOpen = false">关闭</el-button>
        <el-button type="primary" :disabled="!qrPreview" @click="printPreviewOne">打印</el-button>
      </template>
    </el-dialog>

    <!-- 计划详情 -->
    <el-dialog v-model="planDetailOpen" title="计划详情" width="560px" destroy-on-close>
      <template v-if="planDetail">
        <h3 class="detail-title">{{ planDetail.title }}</h3>
        <p class="detail-sub">{{ planDetail.statusLabel }} · {{ planDetail.freqUnitLabel }} × {{ planDetail.freqTimes }}</p>
        <p class="detail-line">岗位：{{ (planDetail.executorRoleLabels || []).join('、') }}</p>
        <p class="detail-line">时间：{{ formatTime(planDetail.startAt) }} ~ {{ formatTime(planDetail.endAt) }}</p>
        <p class="detail-line" v-if="planDetail.requirementNote">要求：{{ planDetail.requirementNote }}</p>
        <h4>点位（{{ (planDetail.spots || []).length }}）</h4>
        <ol class="spot-ol">
          <li v-for="s in planDetail.spots || []" :key="s.id">
            {{ s.name }}
            <span class="muted">{{ s.address }}</span>
          </li>
        </ol>
        <h4>任务</h4>
        <ul class="job-ul">
          <li v-for="j in planDetail.jobs || []" :key="j.id">
            第{{ j.seqNo }}轮 · {{ j.statusLabel }}
            <span v-if="j.assigneeName"> · {{ j.assigneeName }}</span>
            <span class="muted"> · {{ j.visitedCount }}/{{ j.spotCount }}</span>
          </li>
          <li v-if="!(planDetail.jobs || []).length" class="muted">暂无任务（发布后生成）</li>
        </ul>
      </template>
      <template #footer>
        <el-button type="primary" @click="planDetailOpen = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding: 24px 32px; max-width: 1500px; margin: 0 auto; }
header { display: flex; justify-content: space-between; gap: 16px; align-items: flex-start; }
h2 { margin: 0; color: #1f4e3d; }
.sub { margin: 4px 0 0; color: #667; font-size: 14px; }
.tabs { margin-top: 16px; }
.block {
  background: #fff;
  border: 1px solid #edebe6;
  border-radius: 12px;
  padding: 16px;
}
.toolbar { display: flex; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
.empty { text-align: center; color: #6e7a74; padding: 32px 0; margin: 0; }
.time-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
.hint { margin-left: 10px; font-size: 12px; color: #6e7a74; }
.photo-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 12px;
}
.photo-card {
  border: 1px solid #edebe6;
  border-radius: 10px;
  overflow: hidden;
  background: #f7f6f3;
}
.photo-card img, .photo-ph {
  width: 100%;
  height: 140px;
  object-fit: cover;
  display: block;
  background: #e7efe9;
}
.photo-ph {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #6e7a74;
  font-size: 13px;
}
.photo-meta { padding: 10px; }
.pn { font-weight: 600; font-size: 14px; color: #16211d; }
.ps { font-size: 12px; color: #6e7a74; margin-top: 2px; }
.pnote { font-size: 12px; color: #3a4741; margin-top: 6px; }
.pager { margin-top: 16px; display: flex; justify-content: center; }
.detail-title { margin: 0 0 4px; font-size: 18px; color: #16211d; }
.detail-sub { margin: 0 0 12px; font-size: 13px; color: #6e7a74; }
.detail-line { font-size: 13px; color: #3a4741; margin: 6px 0; }
.qr-preview {
  min-height: 220px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 8px 0;
}
.qr-img { width: 220px; height: 220px; }
.qr-name { margin-top: 12px; font-size: 16px; font-weight: 600; color: #16211d; }
.qr-meta { margin-top: 4px; font-size: 13px; color: #6e7a74; }
.spot-ol, .job-ul { padding-left: 18px; font-size: 13px; color: #3a4741; }
.muted { color: #6e7a74; }
h3 { margin: 0 0 4px; }
h4 { margin: 16px 0 8px; font-size: 14px; }
</style>
