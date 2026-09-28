<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { ElTree } from 'element-plus'
import { api } from '../api'
import { authFailRedirectPath } from '../authSession'
import StaffNav from '../components/StaffNav.vue'
import StaffSessionAction from '../components/StaffSessionAction.vue'
import RoomOccupantsPanel from '../components/occupants/RoomOccupantsPanel.vue'

type SpaceKind = 'building' | 'unit' | 'floor' | 'room'
const TAB_KEYS = ['structure', 'house-types', 'parking'] as const
type TabKey = (typeof TAB_KEYS)[number]

interface TreeNode {
  key: string
  id: number
  kind: SpaceKind
  label: string
  name: string
  areaSqm?: number | null
  houseTypeId?: number | null
  children?: TreeNode[]
}

const router = useRouter()
const route = useRoute()
const community = ref<any>(null)
const rawTree = ref<any[]>([])
const houseTypes = ref<any[]>([])
const parking = ref<any[]>([])
const loading = ref(false)

const treeRef = ref<InstanceType<typeof ElTree>>()
const currentKey = ref<string>('')
const selected = ref<TreeNode | null>(null)

const addName = ref('')
const editName = ref('')
const editSaving = ref(false)
const htName = ref('')
const htPrice = ref<number | undefined>()
const spaceNo = ref('')
const linkDialogVisible = ref(false)
const linkSaving = ref(false)
const linkTarget = ref<any>(null)
const linkBuildingId = ref<number | null>(null)
const linkUnitId = ref<number | null>(null)
const linkFloorId = ref<number | null>(null)
const linkRoomId = ref<number | null>(null)
/** 选中房屋时编辑：面积 + 房屋类型（决定物业费单价） */
const roomForm = ref({
  roomNo: '',
  areaSqm: '' as string,
  houseTypeId: null as number | null,
})
const roomSaving = ref(false)

const activeTab = computed<TabKey>(() => {
  const t = route.query.tab
  if (typeof t === 'string' && (TAB_KEYS as readonly string[]).includes(t)) return t as TabKey
  return 'structure'
})

const fromBilling = computed(() => route.query.from === 'billing')
const roomAreaMissing = computed(
  () => selected.value?.kind === 'room' && !String(roomForm.value.areaSqm || '').trim(),
)
const roomTypeMissing = computed(
  () => selected.value?.kind === 'room' && roomForm.value.houseTypeId == null,
)

const FAIL_ROOMS_KEY = 'billingChargeFailRoomIds'
const failRoomIds = ref<number[]>([])

function readFailRoomIds() {
  try {
    const raw = sessionStorage.getItem(FAIL_ROOMS_KEY)
    const arr = raw ? JSON.parse(raw) : []
    failRoomIds.value = Array.isArray(arr)
      ? arr.map((x: unknown) => Number(x)).filter((n: number) => Number.isFinite(n))
      : []
  } catch {
    failRoomIds.value = []
  }
}

const failProgress = computed(() => {
  if (!fromBilling.value || !failRoomIds.value.length) return null
  const cur = selected.value?.kind === 'room' ? selected.value.id : null
  const idx = cur == null ? -1 : failRoomIds.value.indexOf(cur)
  return {
    index: idx >= 0 ? idx + 1 : 0,
    total: failRoomIds.value.length,
    hasNext: idx >= 0 && idx < failRoomIds.value.length - 1,
    nextId: idx >= 0 && idx < failRoomIds.value.length - 1 ? failRoomIds.value[idx + 1] : null,
  }
})

function backToBilling() {
  router.push('/billing')
}

function goNextFailRoom() {
  const nextId = failProgress.value?.nextId
  if (nextId == null) {
    ElMessage.info('已是最后一套将失败房屋，可返回账单重新预览')
    return
  }
  router.replace({ path: '/space', query: { from: 'billing', roomId: String(nextId) } })
}

function onTabChange(name: string | number) {
  const tab = String(name)
  if (tab === activeTab.value) return
  router.replace({ path: '/space', query: tab === 'structure' ? {} : { tab } })
}

watch(
  () => route.query.tab,
  (t) => {
    if (t != null && typeof t === 'string' && !(TAB_KEYS as readonly string[]).includes(t)) {
      router.replace({ path: '/space' })
    }
  },
  { immediate: true },
)

watch(
  () => route.query.roomId,
  (rid) => {
    if (rid == null || String(rid).trim() === '') return
    if (!rawTree.value?.length) return
    readFailRoomIds()
    loadAll(`r-${Number(rid)}`)
  },
)

const treeData = computed<TreeNode[]>(() =>
  (rawTree.value || []).map((b: any) => ({
    key: `b-${b.id}`,
    id: b.id,
    kind: 'building' as const,
    label: b.name,
    name: b.name,
    children: (b.units || []).map((u: any) => ({
      key: `u-${u.id}`,
      id: u.id,
      kind: 'unit' as const,
      label: u.name,
      name: u.name,
      children: (u.floors || []).map((f: any) => ({
        key: `f-${f.id}`,
        id: f.id,
        kind: 'floor' as const,
        label: f.name,
        name: f.name,
        children: (f.rooms || []).map((r: any) => ({
          key: `r-${r.id}`,
          id: r.id,
          kind: 'room' as const,
          label: r.roomNo,
          name: r.roomNo,
          areaSqm: r.areaSqm ?? null,
          houseTypeId: r.houseTypeId ?? null,
        })),
      })),
    })),
  })),
)

const pathText = computed(() => {
  if (!selected.value) return '小区根节点'
  const parts: string[] = []
  const walk = (nodes: TreeNode[], target: string, trail: string[]): boolean => {
    for (const n of nodes) {
      const next = [...trail, kindLabel(n.kind) + n.label]
      if (n.key === target) {
        parts.push(...next)
        return true
      }
      if (n.children?.length && walk(n.children, target, next)) return true
    }
    return false
  }
  walk(treeData.value, selected.value.key, [])
  return parts.join(' / ') || kindLabel(selected.value.kind) + selected.value.label
})

const formMeta = computed(() => {
  if (!selected.value) {
    return {
      title: '新增楼栋',
      tip: '在小区下新建楼栋。建好后点选该楼栋，可继续加单元。',
      fieldLabel: '楼栋名称',
      placeholder: '例如：A栋',
      submitText: '保存楼栋',
      canSubmit: true,
      editKindLabel: '',
    }
  }
  if (selected.value.kind === 'building') {
    return {
      title: '新增单元',
      tip: `在楼栋「${selected.value.label}」下新增单元。`,
      fieldLabel: '单元名称',
      placeholder: '例如：1单元',
      submitText: '保存单元',
      canSubmit: true,
      editKindLabel: '楼栋',
    }
  }
  if (selected.value.kind === 'unit') {
    return {
      title: '新增楼层',
      tip: `在单元「${selected.value.label}」下新增楼层。`,
      fieldLabel: '楼层名称',
      placeholder: '例如：1层',
      submitText: '保存楼层',
      canSubmit: true,
      editKindLabel: '单元',
    }
  }
  if (selected.value.kind === 'floor') {
    return {
      title: '新增房屋',
      tip: `在楼层「${selected.value.label}」下新增房屋。`,
      fieldLabel: '房号',
      placeholder: '例如：101',
      submitText: '保存房屋',
      canSubmit: true,
      editKindLabel: '楼层',
    }
  }
  return {
    title: '编辑房屋',
    tip: `已选中房屋「${selected.value.label}」。请补全面积并绑定房屋类型，出账时物业费 = 面积 × 该类型单价。`,
    fieldLabel: '',
    placeholder: '',
    submitText: '',
    canSubmit: false,
    editKindLabel: '房屋',
  }
})

const canEditCurrent = computed(
  () => !!selected.value && selected.value.kind !== 'room',
)

function houseTypeName(id: number | null | undefined) {
  if (id == null) return ''
  return houseTypes.value.find((h) => h.id === id)?.name || ''
}

interface FlatRoom {
  id: number
  roomNo: string
  buildingId: number
  buildingName: string
  unitId: number
  unitName: string
  floorId: number
  floorName: string
}

const allRooms = computed<FlatRoom[]>(() => {
  const out: FlatRoom[] = []
  for (const b of rawTree.value || []) {
    for (const u of b.units || []) {
      for (const f of u.floors || []) {
        for (const r of f.rooms || []) {
          out.push({
            id: r.id,
            roomNo: r.roomNo,
            buildingId: b.id,
            buildingName: b.name || '',
            unitId: u.id,
            unitName: u.name || '',
            floorId: f.id,
            floorName: f.name || '',
          })
        }
      }
    }
  }
  return out
})

const linkBuildingOptions = computed(() =>
  (rawTree.value || []).map((b: any) => ({ id: b.id, name: b.name })),
)
const linkUnitOptions = computed(() => {
  if (linkBuildingId.value == null) return []
  const b = (rawTree.value || []).find((x: any) => x.id === linkBuildingId.value)
  return (b?.units || []).map((u: any) => ({ id: u.id, name: u.name }))
})
const linkFloorOptions = computed(() => {
  if (linkBuildingId.value == null || linkUnitId.value == null) return []
  const b = (rawTree.value || []).find((x: any) => x.id === linkBuildingId.value)
  const u = (b?.units || []).find((x: any) => x.id === linkUnitId.value)
  return (u?.floors || []).map((f: any) => ({ id: f.id, name: f.name }))
})
const linkRoomOptions = computed(() => {
  if (linkFloorId.value == null) return []
  return allRooms.value
    .filter((r) => r.floorId === linkFloorId.value)
    .map((r) => ({ id: r.id, name: r.roomNo }))
})

function parkingRoomLabel(roomId: number | null | undefined) {
  if (roomId == null) return ''
  const r = allRooms.value.find((x) => x.id === roomId)
  if (!r) return `房屋#${roomId}`
  return `${r.buildingName}${r.unitName}${r.floorName}${r.roomNo}`
}

function fillLinkCascade(roomId: number | null | undefined) {
  const r = roomId == null ? null : allRooms.value.find((x) => x.id === roomId)
  linkBuildingId.value = r?.buildingId ?? null
  linkUnitId.value = r?.unitId ?? null
  linkFloorId.value = r?.floorId ?? null
  linkRoomId.value = r?.id ?? null
}

function onLinkBuildingChange() {
  linkUnitId.value = null
  linkFloorId.value = null
  linkRoomId.value = null
}
function onLinkUnitChange() {
  linkFloorId.value = null
  linkRoomId.value = null
}
function onLinkFloorChange() {
  linkRoomId.value = null
}

function fillRoomForm(node: TreeNode | null) {
  if (!node || node.kind !== 'room') {
    roomForm.value = { roomNo: '', areaSqm: '', houseTypeId: null }
    return
  }
  roomForm.value = {
    roomNo: node.name || '',
    areaSqm: node.areaSqm == null ? '' : String(node.areaSqm),
    houseTypeId: node.houseTypeId ?? null,
  }
}

function kindLabel(kind: SpaceKind) {
  return { building: '楼栋 ', unit: '单元 ', floor: '楼层 ', room: '' }[kind]
}

function kindTagType(kind: SpaceKind) {
  return ({ building: 'success', unit: 'info', floor: 'warning', room: 'info' } as const)[kind]
}

async function ensureStaff() {
  const { data } = await api.get('/staff/community')
  if (data.code !== 0) {
    ElMessage.error(data.message || '请先物业登录并切换小区')
    router.push(authFailRedirectPath())
    return false
  }
  community.value = data.data
  return true
}

async function loadAll(keepKey?: string) {
  loading.value = true
  try {
    if (!(await ensureStaff())) return
    const [t, h, p] = await Promise.all([
      api.get('/staff/space-tree'),
      api.get('/staff/house-types'),
      api.get('/staff/parking-spaces'),
    ])
    if (t.data.code === 0) rawTree.value = t.data.data.buildings || []
    if (h.data.code === 0) houseTypes.value = h.data.data || []
    if (p.data.code === 0) parking.value = p.data.data || []

    await nextTick()
    const qRoom = route.query.roomId
    const fromQuery =
      qRoom != null && String(qRoom).trim() !== '' ? `r-${Number(qRoom)}` : ''
    const key = keepKey || currentKey.value || (Number.isFinite(Number(qRoom)) ? fromQuery : '')
    if (key && treeRef.value) {
      // 展开到目标房屋
      const expandKeys: string[] = []
      const collect = (nodes: TreeNode[], target: string, trail: string[]): boolean => {
        for (const n of nodes) {
          const next = [...trail, n.key]
          if (n.key === target) {
            expandKeys.push(...trail)
            return true
          }
          if (n.children?.length && collect(n.children, target, next)) return true
        }
        return false
      }
      collect(treeData.value, key, [])
      for (const k of expandKeys) {
        const n = treeRef.value.getNode(k)
        if (n && !n.expanded) n.expand()
      }
      treeRef.value.setCurrentKey(key)
      const node = treeRef.value.getCurrentNode() as TreeNode | null
      selected.value = node
      currentKey.value = key
      fillRoomForm(node)
      editName.value = node && node.kind !== 'room' ? node.name : ''
      if (fromQuery && key === fromQuery) {
        // 消费掉 query，避免刷新反复跳
        const q = { ...route.query }
        delete q.roomId
        router.replace({ path: '/space', query: q })
      }
    }
  } finally {
    loading.value = false
  }
}

function onNodeClick(data: TreeNode) {
  selected.value = data
  currentKey.value = data.key
  addName.value = ''
  editName.value = data.kind === 'room' ? '' : data.name
  fillRoomForm(data)
}

function clearSelection() {
  selected.value = null
  currentKey.value = ''
  addName.value = ''
  editName.value = ''
  fillRoomForm(null)
  treeRef.value?.setCurrentKey(undefined as any)
}

async function saveCurrentName() {
  if (!selected.value || selected.value.kind === 'room') return
  const name = editName.value.trim()
  if (!name) {
    ElMessage.warning('名称不能为空')
    return
  }
  editSaving.value = true
  try {
    let data: any
    if (selected.value.kind === 'building') {
      ;({ data } = await api.put(`/staff/buildings/${selected.value.id}`, { name }))
    } else if (selected.value.kind === 'unit') {
      ;({ data } = await api.put(`/staff/units/${selected.value.id}`, { name }))
    } else {
      ;({ data } = await api.put(`/staff/floors/${selected.value.id}`, { name }))
    }
    if (data.code === 0) {
      ElMessage.success('已保存修改')
      await loadAll(selected.value.key)
    } else ElMessage.error(data.message)
  } finally {
    editSaving.value = false
  }
}

async function submitAdd() {
  const name = addName.value.trim()
  if (!name) {
    ElMessage.warning('请填写名称')
    return
  }
  let data: any
  let nextKey = ''
  if (!selected.value) {
    ;({ data } = await api.post('/staff/buildings', { name }))
    if (data.code === 0 && data.data?.id) nextKey = `b-${data.data.id}`
  } else if (selected.value.kind === 'building') {
    ;({ data } = await api.post(`/staff/buildings/${selected.value.id}/units`, { name }))
    if (data.code === 0 && data.data?.id) nextKey = `u-${data.data.id}`
  } else if (selected.value.kind === 'unit') {
    ;({ data } = await api.post(`/staff/units/${selected.value.id}/floors`, { name }))
    if (data.code === 0 && data.data?.id) nextKey = `f-${data.data.id}`
  } else if (selected.value.kind === 'floor') {
    ;({ data } = await api.post(`/staff/floors/${selected.value.id}/rooms`, { roomNo: name }))
    if (data.code === 0 && data.data?.id) nextKey = `r-${data.data.id}`
  } else {
    return
  }
  if (data.code === 0) {
    addName.value = ''
    ElMessage.success('已保存')
    await loadAll(currentKey.value || nextKey)
  } else ElMessage.error(data.message)
}

async function confirmSpaceDelete(
  summaryPath: string,
  deletePath: string,
  label: string,
  emptyHint: string,
) {
  const occRes = await api.get(summaryPath)
  if (occRes.data.code !== 0) {
    ElMessage.error(occRes.data.message || '查询住户失败')
    return false
  }
  const count = Number(occRes.data.data?.count || 0)
  const names = occRes.data.data?.names || '住户'
  let releaseOccupants = false
  if (count > 0) {
    try {
      await ElMessageBox.confirm(
        `${names} 仍绑定在该${label}下房屋中，请确认是否将其变为游客。确认则解除绑定并删除${label}（含下属空间），取消则不变。`,
        `删除${label}`,
        { confirmButtonText: '确认并删除', cancelButtonText: '取消', type: 'warning' },
      )
      releaseOccupants = true
    } catch {
      return false
    }
  } else {
    try {
      await ElMessageBox.confirm(emptyHint, `删除${label}`)
    } catch {
      return false
    }
  }
  const { data } = await api.delete(deletePath, { params: { releaseOccupants } })
  if (data.code === 0) {
    ElMessage.success(releaseOccupants ? `已解绑住户并删除${label}` : '已删除')
    return true
  }
  ElMessage.error(data.message)
  return false
}

async function removeNode(node: TreeNode, ev?: Event) {
  ev?.stopPropagation()
  let ok = false
  if (node.kind === 'building') {
    ok = await confirmSpaceDelete(
      `/staff/buildings/${node.id}/occupants-summary`,
      `/staff/buildings/${node.id}`,
      '楼栋',
      `确认删除楼栋「${node.name}」及其下属单元/楼层/房屋？`,
    )
  } else if (node.kind === 'unit') {
    ok = await confirmSpaceDelete(
      `/staff/units/${node.id}/occupants-summary`,
      `/staff/units/${node.id}`,
      '单元',
      `确认删除单元「${node.name}」及其下属楼层/房屋？`,
    )
  } else if (node.kind === 'floor') {
    ok = await confirmSpaceDelete(
      `/staff/floors/${node.id}/occupants-summary`,
      `/staff/floors/${node.id}`,
      '楼层',
      `确认删除楼层「${node.name}」及其下属房屋？`,
    )
  } else {
    const occRes = await api.get(`/staff/rooms/${node.id}/occupants`)
    if (occRes.data.code !== 0) {
      ElMessage.error(occRes.data.message || '查询住户失败')
      return
    }
    const occupants: any[] = occRes.data.data?.occupants || []
    let releaseOccupants = false
    if (occupants.length) {
      const names = occupants.map((o) => o.displayName).filter(Boolean).join('、') || '住户'
      try {
        await ElMessageBox.confirm(
          `${names} 在 ${node.name} 房，请确认是否将其变为游客。确认则解除绑定并删除房屋，取消则不变。`,
          '删除房屋',
          { confirmButtonText: '确认并删除', cancelButtonText: '取消', type: 'warning' },
        )
        releaseOccupants = true
      } catch {
        return
      }
    } else {
      try {
        await ElMessageBox.confirm(`确认删除房屋 ${node.name}？`, '删除房屋')
      } catch {
        return
      }
    }
    const { data } = await api.delete(`/staff/rooms/${node.id}`, { params: { releaseOccupants } })
    if (data.code === 0) {
      ElMessage.success(releaseOccupants ? '已解绑住户并删除房屋' : '已删除')
      ok = true
    } else {
      ElMessage.error(data.message)
      return
    }
  }
  if (!ok) return
  if (selected.value?.key === node.key) clearSelection()
  await loadAll()
}

async function saveRoom() {
  if (!selected.value || selected.value.kind !== 'room') return
  if (!roomForm.value.roomNo.trim()) {
    ElMessage.warning('房号不能为空')
    return
  }
  const areaRaw = roomForm.value.areaSqm.trim()
  let areaSqm: number | null = null
  if (areaRaw !== '') {
    const n = Number(areaRaw)
    if (!Number.isFinite(n) || n < 0) {
      ElMessage.warning('面积须为 ≥ 0 的数字')
      return
    }
    areaSqm = n
  }
  roomSaving.value = true
  try {
    const { data } = await api.put(`/staff/rooms/${selected.value.id}`, {
      roomNo: roomForm.value.roomNo.trim(),
      areaSqm,
      houseTypeId: roomForm.value.houseTypeId ?? 0,
    })
    if (data.code === 0) {
      ElMessage.success(
        fromBilling.value
          ? '房屋已保存。可点上方「返回账单」重新预览算费'
          : '房屋已保存（面积与类型）',
      )
      await loadAll(selected.value.key)
    } else ElMessage.error(data.message)
  } finally {
    roomSaving.value = false
  }
}

async function addHouseType() {
  if (!htName.value.trim()) return
  const { data } = await api.post('/staff/house-types', {
    name: htName.value,
    propertyFeeUnitPrice: htPrice.value,
  })
  if (data.code === 0) {
    htName.value = ''
    htPrice.value = undefined
    ElMessage.success('已建房屋类型')
    loadAll(currentKey.value)
  } else ElMessage.error(data.message)
}

async function addParking() {
  if (!spaceNo.value.trim()) return
  const { data } = await api.post('/staff/parking-spaces', {
    spaceNo: spaceNo.value,
  })
  if (data.code === 0) {
    spaceNo.value = ''
    ElMessage.success('已建车位')
    loadAll(currentKey.value)
  } else ElMessage.error(data.message)
}

function openLinkParking(row: any) {
  linkTarget.value = row
  fillLinkCascade(row.roomId)
  linkDialogVisible.value = true
}

async function confirmLinkParking() {
  if (!linkTarget.value) return
  if (linkRoomId.value == null) {
    ElMessage.warning('请选择楼栋、单元、楼层与房屋')
    return
  }
  linkSaving.value = true
  try {
    const id = linkTarget.value.id
    const already = linkTarget.value.roomId != null
    const { data } = await api.post(
      already
        ? `/staff/parking-spaces/${id}/relink`
        : `/staff/parking-spaces/${id}/link`,
      { roomId: linkRoomId.value },
    )
    if (data.code === 0) {
      ElMessage.success(already ? '已改挂房屋' : '已挂靠房屋')
      linkDialogVisible.value = false
      await loadAll(currentKey.value)
    } else ElMessage.error(data.message)
  } finally {
    linkSaving.value = false
  }
}

async function unlinkParking(row: any) {
  if (row.roomId == null) return
  try {
    await ElMessageBox.confirm(
      `确定取消车位 ${row.spaceNo} 与「${parkingRoomLabel(row.roomId)}」的挂靠？原绑该位的车辆将按规则顺位或改为未绑车位。`,
      '取消挂房',
      { type: 'warning', confirmButtonText: '取消挂靠', cancelButtonText: '返回' },
    )
  } catch {
    return
  }
  const { data } = await api.post(`/staff/parking-spaces/${row.id}/unlink`)
  if (data.code === 0) {
    ElMessage.success('已取消挂房')
    await loadAll(currentKey.value)
  } else ElMessage.error(data.message)
}

onMounted(() => {
  readFailRoomIds()
  loadAll()
})
</script>

<template>
  <div class="page" v-loading="loading">
    <header>
      <div>
        <h2>空间配置</h2>
        <p class="sub">{{ community?.name || '当前小区' }} · 结构、房屋类型与车位</p>
        <StaffNav />
      </div>
      <div class="row">
        <el-button @click="loadAll(currentKey)">刷新</el-button>
        <StaffSessionAction />
      </div>
    </header>

    <el-tabs :model-value="activeTab" class="tabs" @tab-change="onTabChange">
      <el-tab-pane label="空间结构" name="structure">
        <section class="space-panel block">
          <div v-if="fromBilling" class="charge-banner">
            <div>
              <strong>来自账单算费预览</strong>
              <span>
                请补全该房面积与房屋类型（决定物业费单价），保存后返回账单重新预览。
                <template v-if="failProgress && failProgress.total > 1">
                  （将失败 {{ failProgress.index || '?' }}/{{ failProgress.total }}）
                </template>
              </span>
            </div>
            <div class="charge-banner-actions">
              <el-button
                v-if="failProgress?.hasNext"
                @click="goNextFailRoom"
              >下一套缺资料</el-button>
              <el-button type="primary" @click="backToBilling">返回账单</el-button>
            </div>
          </div>
          <div class="panel-head">
            <h3>空间结构</h3>
            <p class="hint">先在左侧选中节点：可编辑当前名称，或在其下新增下级。房屋类型在「房屋类型」页签维护，本页绑房时下拉选用即可。</p>
          </div>

          <div class="space-layout">
            <div class="tree-pane">
              <div class="pane-toolbar">
                <span>空间树</span>
                <el-button link type="primary" @click="clearSelection">选中小区根</el-button>
              </div>
              <el-tree
                v-if="treeData.length"
                ref="treeRef"
                :data="treeData"
                node-key="key"
                highlight-current
                default-expand-all
                :expand-on-click-node="false"
                :props="{ label: 'label', children: 'children' }"
                @node-click="onNodeClick"
              >
                <template #default="{ data }">
                  <div class="tree-node">
                    <el-tag size="small" :type="kindTagType(data.kind)" effect="plain">
                      {{ { building: '楼栋', unit: '单元', floor: '楼层', room: '房屋' }[data.kind as SpaceKind] }}
                    </el-tag>
                    <span class="tree-label">
                      {{ data.label }}
                      <span v-if="data.kind === 'room' && data.houseTypeId" class="tree-meta">
                        · {{ houseTypeName(data.houseTypeId) }}
                      </span>
                      <span v-else-if="data.kind === 'room'" class="tree-meta warn">· 未绑类型</span>
                    </span>
                    <el-button
                      class="tree-del"
                      link
                      type="danger"
                      size="small"
                      @click="removeNode(data, $event)"
                    >
                      删除
                    </el-button>
                  </div>
                </template>
              </el-tree>
              <p v-else class="empty">暂无空间数据，请先在右侧新增楼栋</p>
            </div>

            <div class="form-pane">
              <div class="path">当前位置：{{ pathText }}</div>

              <template v-if="canEditCurrent">
                <h4>编辑{{ formMeta.editKindLabel }}</h4>
                <p class="form-tip">修改当前选中节点的名称。</p>
                <el-form label-position="top" @submit.prevent="saveCurrentName">
                  <el-form-item :label="formMeta.editKindLabel + '名称'" required>
                    <el-input v-model="editName" maxlength="32" clearable @keyup.enter="saveCurrentName" />
                  </el-form-item>
                  <el-form-item>
                    <el-button type="primary" :loading="editSaving" @click="saveCurrentName">保存修改</el-button>
                    <el-button type="danger" plain @click="selected && removeNode(selected)">
                      删除该{{ formMeta.editKindLabel }}
                    </el-button>
                  </el-form-item>
                </el-form>
                <div class="form-divider"></div>
              </template>

              <template v-if="formMeta.canSubmit">
                <h4>{{ formMeta.title }}</h4>
                <p class="form-tip">{{ formMeta.tip }}</p>
                <el-form label-position="top" @submit.prevent="submitAdd">
                  <el-form-item :label="formMeta.fieldLabel" required>
                    <el-input
                      v-model="addName"
                      :placeholder="formMeta.placeholder"
                      maxlength="32"
                      clearable
                      @keyup.enter="submitAdd"
                    />
                  </el-form-item>
                  <el-form-item>
                    <el-button type="primary" @click="submitAdd">{{ formMeta.submitText }}</el-button>
                  </el-form-item>
                </el-form>
              </template>

              <div v-else class="room-edit">
                <RoomOccupantsPanel
                  v-if="selected?.kind === 'room'"
                  :key="selected.id"
                  :room-id="selected.id"
                />
                <div class="form-divider"></div>
                <h4>{{ formMeta.title }}</h4>
                <p class="form-tip">{{ formMeta.tip }}</p>
                <el-form label-position="top" @submit.prevent="saveRoom">
                  <div class="room-row">
                    <el-form-item label="房号" required>
                      <el-input v-model="roomForm.roomNo" maxlength="32" />
                    </el-form-item>
                    <el-form-item label="面积（㎡）" :class="{ 'is-warn': fromBilling && roomAreaMissing }">
                      <el-input v-model="roomForm.areaSqm" placeholder="如 89.5" inputmode="decimal" />
                    </el-form-item>
                  </div>
                  <el-form-item
                    label="房屋类型（决定物业费单价）"
                    :class="{ 'is-warn': fromBilling && roomTypeMissing }"
                  >
                    <el-select
                      v-model="roomForm.houseTypeId"
                      clearable
                      placeholder="请先在「房屋类型」页签维护，再绑定到本房"
                      style="width:100%"
                    >
                      <el-option
                        v-for="h in houseTypes.filter((x) => x.status !== 0)"
                        :key="h.id"
                        :label="`${h.name}（${h.propertyFeeUnitPrice ?? '—'} 元/㎡）`"
                        :value="h.id"
                      />
                    </el-select>
                  </el-form-item>
                  <el-form-item>
                    <el-button type="primary" :loading="roomSaving" @click="saveRoom">保存房屋</el-button>
                    <el-button
                      v-if="fromBilling && failProgress?.hasNext"
                      type="warning"
                      plain
                      @click="goNextFailRoom"
                    >下一套缺资料</el-button>
                    <el-button v-if="fromBilling" type="success" plain @click="backToBilling">返回账单</el-button>
                    <el-button type="danger" plain @click="selected && removeNode(selected)">删除该房屋</el-button>
                    <el-button @click="clearSelection">返回小区根</el-button>
                  </el-form-item>
                </el-form>
                <p class="hint">
                  物业费出账公式：面积 × 所选类型的物业费单价。停车费等其它费项在「账单」页的费项模板中配置。
                </p>
              </div>
            </div>
          </div>
        </section>
      </el-tab-pane>

      <el-tab-pane label="房屋类型" name="house-types">
        <section class="block tab-block">
          <h3>房屋类型（物业费单价模板）</h3>
          <p class="hint">低频固定配置：开盘时建好类型与单价即可。日常在「结构」页点选房屋，用下拉绑定类型。</p>
          <el-form inline class="tab-form" @submit.prevent="addHouseType">
            <el-form-item label="类型名">
              <el-input v-model="htName" placeholder="如：三室两厅" style="width: 160px" />
            </el-form-item>
            <el-form-item label="物业费单价">
              <el-input-number v-model="htPrice" :min="0" :precision="2" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="addHouseType">新增</el-button>
            </el-form-item>
          </el-form>
          <el-table :data="houseTypes" size="small">
            <el-table-column type="index" label="序号" width="60" :index="(i: number) => i + 1" />
            <el-table-column prop="name" label="名称" />
            <el-table-column prop="propertyFeeUnitPrice" label="单价（元/㎡）" width="140" />
            <el-table-column prop="status" label="状态" width="80" />
          </el-table>
        </section>
      </el-tab-pane>

      <el-tab-pane label="车位" name="parking">
        <section class="block tab-block">
          <h3>车位</h3>
          <p class="hint">
            维护车位编号，并可挂靠到房屋（楼栋→单元→楼层→房屋）。改挂/取消挂靠为后台直改；业主自行关联须走变更审核。
          </p>
          <el-form inline class="tab-form" @submit.prevent="addParking">
            <el-form-item label="车位号">
              <el-input v-model="spaceNo" placeholder="如：B1-001" style="width: 160px" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="addParking">新增</el-button>
            </el-form-item>
          </el-form>
          <el-table :data="parking" size="small" class="parking-table">
            <el-table-column type="index" label="序号" width="60" :index="(i: number) => i + 1" />
            <el-table-column prop="spaceNo" label="编号" width="160" />
            <el-table-column label="挂房" min-width="220">
              <template #default="{ row }">
                <span v-if="row.roomId" class="linked">{{ parkingRoomLabel(row.roomId) }}</span>
                <span v-else class="unlinked">未挂房</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <el-button type="primary" link @click="openLinkParking(row)">
                  {{ row.roomId ? '改挂' : '挂靠房屋' }}
                </el-button>
                <el-button
                  v-if="row.roomId"
                  type="danger"
                  link
                  @click="unlinkParking(row)"
                >
                  取消挂靠
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </section>

        <el-dialog
          v-model="linkDialogVisible"
          :title="linkTarget?.roomId ? `改挂车位 ${linkTarget?.spaceNo || ''}` : `挂靠车位 ${linkTarget?.spaceNo || ''}`"
          width="480px"
          destroy-on-close
        >
          <p class="hint" style="margin-bottom: 12px">
            按楼栋 → 单元 → 楼层 → 房屋选择。改挂时原房车辆会按规则顺位；新房空位将自动补绑未绑车辆。
          </p>
          <el-form label-position="top">
            <el-form-item label="楼栋" required>
              <el-select
                v-model="linkBuildingId"
                clearable
                placeholder="选择楼栋"
                style="width: 100%"
                @change="onLinkBuildingChange"
              >
                <el-option
                  v-for="b in linkBuildingOptions"
                  :key="b.id"
                  :label="b.name"
                  :value="b.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="单元" required>
              <el-select
                v-model="linkUnitId"
                clearable
                placeholder="选择单元"
                style="width: 100%"
                :disabled="linkBuildingId == null"
                @change="onLinkUnitChange"
              >
                <el-option
                  v-for="u in linkUnitOptions"
                  :key="u.id"
                  :label="u.name"
                  :value="u.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="楼层" required>
              <el-select
                v-model="linkFloorId"
                clearable
                placeholder="选择楼层"
                style="width: 100%"
                :disabled="linkUnitId == null"
                @change="onLinkFloorChange"
              >
                <el-option
                  v-for="f in linkFloorOptions"
                  :key="f.id"
                  :label="f.name"
                  :value="f.id"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="房屋" required>
              <el-select
                v-model="linkRoomId"
                clearable
                filterable
                placeholder="选择房号"
                style="width: 100%"
                :disabled="linkFloorId == null"
              >
                <el-option
                  v-for="r in linkRoomOptions"
                  :key="r.id"
                  :label="r.name"
                  :value="r.id"
                />
              </el-select>
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="linkDialogVisible = false">取消</el-button>
            <el-button type="primary" :loading="linkSaving" @click="confirmLinkParking">确定</el-button>
          </template>
        </el-dialog>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.page { padding: 24px 32px; max-width: 1500px; margin: 0 auto; }
header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
h2 { margin: 0; color: #1f4e3d; }
.sub { margin: 4px 0 0; color: #667; font-size: 14px; }
.row { display: flex; gap: 8px; align-items: center; }
.tabs { margin-top: 16px; }
.block {
  background: #fff;
  border: 1px solid #dfe6e1;
  padding: 16px 18px;
}
.tab-block { margin-top: 4px; }
.tab-form { margin: 12px 0 8px; }
.parking-table { width: 100%; }
.linked { color: #2a4a3c; }
.unlinked { color: #9aa8a2; }
.panel-head { margin-bottom: 14px; }
h3 { margin: 0 0 6px; font-size: 16px; color: #2a4a3c; }
.hint { margin: 0; color: #6a7a74; font-size: 13px; line-height: 1.5; }
.room-edit .hint { margin-top: 8px; }
.room-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 14px;
}
.room-row .el-form-item { margin-bottom: 12px; }
@media (max-width: 700px) {
  .room-row { grid-template-columns: 1fr; }
}
.tree-meta { color: #778; font-size: 12px; }
.tree-meta.warn { color: #c47a2c; }
.charge-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 14px;
  padding: 10px 14px;
  background: #fff8ef;
  border: 1px solid #e6b566;
  color: #5c4a2a;
  font-size: 13px;
  line-height: 1.45;
}
.charge-banner-actions { display: flex; flex-wrap: wrap; gap: 8px; flex-shrink: 0; }
.charge-banner span { color: #6a5a3a; }
.is-warn :deep(.el-input__wrapper),
.is-warn :deep(.el-select__wrapper) {
  box-shadow: 0 0 0 1px #e6b566 inset;
}
.space-layout {
  display: grid;
  grid-template-columns: minmax(280px, 1.1fr) minmax(260px, 0.9fr);
  gap: 16px;
  min-height: 360px;
}
.tree-pane, .form-pane {
  border: 1px solid #e4ebe6;
  background: #fafcfb;
  border-radius: 2px;
  padding: 12px 14px;
}
.pane-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
  font-size: 13px;
  color: #4a5c55;
  font-weight: 600;
}
.tree-node {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding-right: 4px;
}
.tree-label { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; }
.tree-del { opacity: 0.35; }
.tree-node:hover .tree-del { opacity: 1; }
.path {
  font-size: 12px;
  color: #6a7a74;
  margin-bottom: 10px;
  padding: 6px 8px;
  background: #eef3f0;
}
.form-pane h4 { margin: 0 0 8px; font-size: 15px; color: #1f4e3d; }
.form-tip { margin: 0 0 16px; font-size: 13px; color: #5c6f68; line-height: 1.55; }
.form-divider {
  height: 1px;
  background: #e4ebe6;
  margin: 8px 0 18px;
}
.empty { color: #889; font-size: 13px; margin: 24px 0; text-align: center; }
@media (max-width: 900px) {
  .space-layout { grid-template-columns: 1fr; }
}
</style>
