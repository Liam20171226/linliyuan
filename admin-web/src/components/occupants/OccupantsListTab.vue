<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../../api'
import { authFailRedirectPath } from '../../authSession'
import { residentRoleLabel } from '../../residentRoles'
import RoomOccupantsPanel from './RoomOccupantsPanel.vue'

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

interface RoomFlags {
  hasOwner: boolean
  hasNonOwner: boolean
}

const router = useRouter()
const loading = ref(false)
const rawTree = ref<any[]>([])

const filterBuildingId = ref<number | null>(null)
const filterUnitId = ref<number | null>(null)
const filterFloorId = ref<number | null>(null)
const roomSearch = ref('')
const selectedRoomId = ref<number | null>(null)

const allList = ref<any[]>([])
const roomFlags = ref<Record<number, RoomFlags>>({})
const allBuildingId = ref<number | null>(null)
const allUnitId = ref<number | null>(null)
const roomNoQ = ref('')
const mobileQ = ref('')
const realNameQ = ref('')
const panelRef = ref<InstanceType<typeof RoomOccupantsPanel> | null>(null)
const maintainSectionRef = ref<HTMLElement | null>(null)

const COMMITTEE_LABELS: Record<string, string> = {
  DIRECTOR: '业委会主任',
  MEMBER: '业委会成员',
  ACTIVIST: '业委会积极分子',
}

const SOURCE_LABELS: Record<string, string> = {
  USER_APPLY: '住户申请',
  ROOM_CHANGE: '房屋变更',
  STAFF_FIX: '后台直改',
  IMPORT: '批量导入',
  PLATFORM: '平台协助',
}

function committeeTitleLabel(t: string | null | undefined) {
  if (!t) return '—'
  return COMMITTEE_LABELS[t] || t
}

function sourceLabel(s: string | null | undefined) {
  if (!s) return '—'
  return SOURCE_LABELS[s] || s
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

const buildingOptions = computed(() =>
  (rawTree.value || []).map((b: any) => ({ id: b.id, name: b.name })),
)

const unitOptions = computed(() => {
  if (filterBuildingId.value == null) return []
  const b = (rawTree.value || []).find((x: any) => x.id === filterBuildingId.value)
  return (b?.units || []).map((u: any) => ({ id: u.id, name: u.name }))
})

const floorOptions = computed(() => {
  if (filterBuildingId.value == null || filterUnitId.value == null) return []
  const b = (rawTree.value || []).find((x: any) => x.id === filterBuildingId.value)
  const u = (b?.units || []).find((x: any) => x.id === filterUnitId.value)
  return (u?.floors || []).map((f: any) => ({ id: f.id, name: f.name }))
})

const allUnitOptions = computed(() => {
  if (allBuildingId.value == null) return []
  const b = (rawTree.value || []).find((x: any) => x.id === allBuildingId.value)
  return (b?.units || []).map((u: any) => ({ id: u.id, name: u.name }))
})

const filteredRooms = computed(() => {
  const q = roomSearch.value.trim()
  return allRooms.value.filter((r) => {
    if (filterBuildingId.value != null && r.buildingId !== filterBuildingId.value) return false
    if (filterUnitId.value != null && r.unitId !== filterUnitId.value) return false
    if (filterFloorId.value != null && r.floorId !== filterFloorId.value) return false
    if (q && !String(r.roomNo).includes(q)) return false
    return true
  })
})

const selectedRoom = computed(() =>
  selectedRoomId.value == null ? null : allRooms.value.find((r) => r.id === selectedRoomId.value) || null,
)

const pathText = computed(() => {
  const r = selectedRoom.value
  if (!r) return '未选中房屋'
  return `楼栋 ${r.buildingName} / 单元 ${r.unitName} / 楼层 ${r.floorName} / ${r.roomNo}`
})

watch(filterBuildingId, () => {
  filterUnitId.value = null
  filterFloorId.value = null
})
watch(filterUnitId, () => {
  filterFloorId.value = null
})
watch(allBuildingId, () => {
  allUnitId.value = null
})

function flagsOf(roomId: number): RoomFlags {
  return roomFlags.value[roomId] || { hasOwner: false, hasNonOwner: false }
}

function roomListMeta(r: FlatRoom) {
  const parts: string[] = []
  if (filterBuildingId.value == null) parts.push(r.buildingName)
  if (filterUnitId.value == null) parts.push(r.unitName)
  if (filterFloorId.value == null) parts.push(r.floorName)
  return parts.filter(Boolean).join('/') || '—'
}

function roomFullPath(r: FlatRoom) {
  return `${r.buildingName}/${r.unitName}/${r.floorName}/${r.roomNo}`
}

function rebuildFlags(rows: any[]) {
  const map: Record<number, RoomFlags> = {}
  for (const row of rows) {
    const id = row.roomId as number
    if (id == null) continue
    if (!map[id]) map[id] = { hasOwner: false, hasNonOwner: false }
    if (row.residentRole === 'OWNER') map[id].hasOwner = true
    else map[id].hasNonOwner = true
  }
  roomFlags.value = map
}

async function loadTree() {
  const { data } = await api.get('/staff/space-tree')
  if (data.code === 0) rawTree.value = data.data?.buildings || []
}

async function loadOccupantFlags() {
  const rows: any[] = []
  let page = 1
  let total = Infinity
  while (rows.length < total && page <= 20) {
    const { data } = await api.get('/staff/occupants', { params: { page, pageSize: 100 } })
    if (data.code !== 0) {
      ElMessage.error(data.message)
      if (data.code === 40101 || data.code === 40301) router.push(authFailRedirectPath())
      break
    }
    const list = data.data?.list || []
    total = Number(data.data?.total ?? list.length)
    rows.push(...list)
    if (list.length < 100) break
    page += 1
  }
  rebuildFlags(rows)
}

async function loadAllOccupants() {
  const { data } = await api.get('/staff/occupants', {
    params: {
      page: 1,
      pageSize: 200,
      buildingId: allBuildingId.value ?? undefined,
      unitId: allUnitId.value ?? undefined,
      roomNo: roomNoQ.value || undefined,
      mobile: mobileQ.value || undefined,
      realName: realNameQ.value || undefined,
    },
  })
  if (data.code === 0) allList.value = data.data.list || []
  else {
    ElMessage.error(data.message)
    if (data.code === 40101 || data.code === 40301) router.push(authFailRedirectPath())
  }
}

async function refresh() {
  loading.value = true
  try {
    await loadTree()
    await Promise.all([loadOccupantFlags(), loadAllOccupants()])
    panelRef.value?.reload?.()
  } finally {
    loading.value = false
  }
}

function selectRoom(room: FlatRoom) {
  selectedRoomId.value = room.id
}

async function locateRoom(row: any) {
  const room = allRooms.value.find((r) => r.id === row.roomId)
  if (!room) {
    ElMessage.warning('未在空间中找到该房屋')
    return
  }
  filterBuildingId.value = room.buildingId
  filterUnitId.value = room.unitId
  filterFloorId.value = room.floorId
  roomSearch.value = ''
  selectRoom(room)
  await nextTick()
  maintainSectionRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

function onOccupantsChanged() {
  loadOccupantFlags()
  loadAllOccupants()
}

onMounted(() => refresh())
</script>

<template>
  <div v-loading="loading">
    <section class="block all-section">
      <h3>全小区住户</h3>
      <p class="hint">跨房屋检索有效绑定；点「定位」可跳到下方按房屋维护。</p>
      <div class="toolbar">
        <el-select v-model="allBuildingId" clearable placeholder="楼栋" style="width:130px">
          <el-option v-for="b in buildingOptions" :key="b.id" :label="b.name" :value="b.id" />
        </el-select>
        <el-select
          v-model="allUnitId"
          clearable
          placeholder="单元"
          style="width:120px"
          :disabled="allBuildingId == null"
        >
          <el-option v-for="u in allUnitOptions" :key="u.id" :label="u.name" :value="u.id" />
        </el-select>
        <el-input v-model="realNameQ" placeholder="姓名" style="width:120px" clearable />
        <el-input v-model="roomNoQ" placeholder="房号" style="width:120px" clearable />
        <el-input v-model="mobileQ" placeholder="手机号" style="width:140px" clearable />
        <el-button type="primary" @click="loadAllOccupants">查询</el-button>
        <el-button @click="refresh">刷新</el-button>
      </div>
      <el-table :data="allList" size="small" style="margin-top:12px">
        <el-table-column type="index" label="序号" width="60" :index="(i: number) => i + 1" />
        <el-table-column prop="buildingName" label="楼栋" min-width="100" />
        <el-table-column prop="unitName" label="单元" width="100" />
        <el-table-column prop="floorName" label="楼层" width="80" />
        <el-table-column prop="roomNo" label="房号" width="80" />
        <el-table-column prop="realName" label="姓名" width="90" />
        <el-table-column prop="mobile" label="手机" width="120" />
        <el-table-column prop="idCardNo" label="身份证" min-width="170" />
        <el-table-column label="角色" width="90">
          <template #default="{ row }">{{ residentRoleLabel(row.residentRole) }}</template>
        </el-table-column>
        <el-table-column label="业委会" width="120">
          <template #default="{ row }">{{ committeeTitleLabel(row.committeeTitle) }}</template>
        </el-table-column>
        <el-table-column label="来源" width="110" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="nowrap">{{ sourceLabel(row.source) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="locateRoom(row)">定位</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section ref="maintainSectionRef" class="occupants-panel block">
      <div class="panel-head">
        <h3>按房屋维护</h3>
        <p class="hint">用楼栋/单元/楼层缩小范围，或直接搜房号；点选房屋后在右侧维护住户。</p>
      </div>

      <div class="space-layout">
        <div class="picker-pane">
          <div class="pane-toolbar">
            <span>选择房屋</span>
            <span class="legend">
              <span class="badge-icon owner" title="已绑业主">
                <svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M12 12a4.5 4.5 0 1 0-4.5-4.5A4.5 4.5 0 0 0 12 12Zm0 1.75c-3.6 0-6.75 1.8-6.75 4.05V19.5h13.5v-1.7c0-2.25-3.15-4.05-6.75-4.05Z"/></svg>
              </span>
              业主
              <span class="badge-icon member" title="已绑非业主">
                <svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M12 12a4.5 4.5 0 1 0-4.5-4.5A4.5 4.5 0 0 0 12 12Zm0 1.75c-3.6 0-6.75 1.8-6.75 4.05V19.5h13.5v-1.7c0-2.25-3.15-4.05-6.75-4.05Z"/></svg>
              </span>
              非业主
            </span>
          </div>

          <div class="cascade">
            <el-select
              v-model="filterBuildingId"
              clearable
              placeholder="楼栋"
              style="width:100%"
            >
              <el-option v-for="b in buildingOptions" :key="b.id" :label="b.name" :value="b.id" />
            </el-select>
            <el-select
              v-model="filterUnitId"
              clearable
              placeholder="单元"
              style="width:100%"
              :disabled="filterBuildingId == null"
            >
              <el-option v-for="u in unitOptions" :key="u.id" :label="u.name" :value="u.id" />
            </el-select>
            <el-select
              v-model="filterFloorId"
              clearable
              placeholder="楼层"
              style="width:100%"
              :disabled="filterUnitId == null"
            >
              <el-option v-for="f in floorOptions" :key="f.id" :label="f.name" :value="f.id" />
            </el-select>
            <el-input
              v-model="roomSearch"
              clearable
              placeholder="搜房号，如 101"
            />
          </div>

          <div v-if="filteredRooms.length" class="room-list">
            <button
              v-for="r in filteredRooms"
              :key="r.id"
              type="button"
              class="room-item"
              :class="{ active: selectedRoomId === r.id }"
              :title="roomFullPath(r)"
              @click="selectRoom(r)"
            >
              <div class="room-main">
                <div class="room-top">
                  <span class="room-no">{{ r.roomNo }}</span>
                  <span class="room-icons">
                    <span
                      v-if="flagsOf(r.id).hasOwner"
                      class="badge-icon owner"
                      title="已绑定业主"
                    >
                      <svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M12 12a4.5 4.5 0 1 0-4.5-4.5A4.5 4.5 0 0 0 12 12Zm0 1.75c-3.6 0-6.75 1.8-6.75 4.05V19.5h13.5v-1.7c0-2.25-3.15-4.05-6.75-4.05Z"/></svg>
                    </span>
                    <span
                      v-if="flagsOf(r.id).hasNonOwner"
                      class="badge-icon member"
                      title="已绑定非业主"
                    >
                      <svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M12 12a4.5 4.5 0 1 0-4.5-4.5A4.5 4.5 0 0 0 12 12Zm0 1.75c-3.6 0-6.75 1.8-6.75 4.05V19.5h13.5v-1.7c0-2.25-3.15-4.05-6.75-4.05Z"/></svg>
                    </span>
                  </span>
                </div>
                <span class="room-meta">{{ roomListMeta(r) }}</span>
              </div>
            </button>
          </div>
          <p v-else-if="allRooms.length" class="empty">无匹配房屋，请调整筛选或房号</p>
          <p v-else class="empty">暂无空间数据，请先在「空间」页维护楼栋与房屋</p>
        </div>

        <div class="detail-pane">
          <div class="path">当前位置：{{ pathText }}</div>

          <RoomOccupantsPanel
            v-if="selectedRoomId != null"
            :key="selectedRoomId"
            ref="panelRef"
            :room-id="selectedRoomId"
            @changed="onOccupantsChanged"
          />

          <template v-else>
            <h4>请选择房屋</h4>
            <p class="form-tip">左侧用级联筛选或搜房号，点选房屋后即可维护住户绑定。</p>
            <p class="hint">也可在上方「全小区住户」中按房号或手机号检索并定位。</p>
          </template>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.block {
  background: #fff;
  border: 1px solid #dfe6e1;
  padding: 16px 18px;
}
.occupants-panel { margin-top: 20px; }
.panel-head { margin-bottom: 14px; }
h3 { margin: 0 0 6px; font-size: 16px; color: #2a4a3c; }
h4 { margin: 0 0 8px; font-size: 15px; color: #1f4e3d; }
.hint { margin: 0; color: #6a7a74; font-size: 13px; line-height: 1.5; }
.nowrap { white-space: nowrap; }
.form-tip { margin: 0 0 12px; font-size: 13px; color: #5c6f68; line-height: 1.55; }
.space-layout {
  display: grid;
  grid-template-columns: 320px minmax(0, 1fr);
  gap: 16px;
  min-height: 420px;
  align-items: stretch;
}
.picker-pane, .detail-pane {
  border: 1px solid #e4ebe6;
  background: #fafcfb;
  border-radius: 2px;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
}
.detail-pane {
  padding: 14px 18px 18px;
}
.path {
  margin-bottom: 14px;
  padding: 8px 12px;
  background: #eef4f0;
  font-size: 13px;
  color: #2a4a3c;
}
.pane-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
  font-size: 13px;
  color: #4a5c55;
  font-weight: 600;
  gap: 8px;
}
.legend {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-weight: 400;
  font-size: 12px;
  color: #6a7a74;
}
.cascade {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-bottom: 10px;
}
.cascade > :nth-child(4) { grid-column: 1 / -1; }
.room-list {
  flex: 1;
  overflow: auto;
  max-height: 480px;
  border: 1px solid #e4ebe6;
  background: #fff;
}
.room-item {
  display: block;
  width: 100%;
  padding: 8px 10px;
  border: 0;
  border-bottom: 1px solid #eef2ef;
  background: transparent;
  cursor: pointer;
  text-align: left;
  font: inherit;
  color: #2a4a3c;
}
.room-item:hover { background: #f3f7f4; }
.room-item.active {
  background: #e7efe9;
  outline: 1px solid #c5d4cc;
}
.room-main { min-width: 0; }
.room-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.room-no { font-weight: 600; }
.room-meta {
  display: block;
  margin-top: 2px;
  font-size: 12px;
  color: #889;
  line-height: 1.35;
  word-break: break-all;
}
.room-icons { display: inline-flex; gap: 5px; flex-shrink: 0; align-items: center; }
.badge-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 999px;
  flex-shrink: 0;
}
.badge-icon svg { width: 12px; height: 12px; }
.badge-icon.owner { color: #c45c26; background: rgba(196, 92, 38, 0.12); }
.badge-icon.member { color: #2a6f9c; background: rgba(42, 111, 156, 0.12); }
.empty {
  margin: 16px 0 0;
  font-size: 13px;
  color: #889;
  text-align: center;
}
.toolbar { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
@media (max-width: 900px) {
  .space-layout { grid-template-columns: 1fr; }
}
</style>
