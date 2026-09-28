<template>
  <view class="page">
    <view v-if="loading" class="empty">加载中…</view>
    <template v-else-if="ready">
      <view class="card tip-card">
        <text class="tip">{{ pageTitle }}：提交后由物业审核，通过后生效。</text>
      </view>

      <!-- ADD_MEMBER -->
      <view v-if="type === 'ADD_MEMBER'" class="card">
        <text class="label">手机号</text>
        <input
          v-model="mobile"
          class="input"
          type="number"
          maxlength="11"
          placeholder="1 开头 11 位"
          @input="onMobileInput"
        />
        <text v-if="lookupHint" class="hint">{{ lookupHint }}</text>

        <text class="label">姓名</text>
        <input
          v-model="name"
          class="input"
          :disabled="nameLocked"
          placeholder="真实姓名"
        />

        <text class="label">角色</text>
        <picker :range="roleLabels" :value="roleIndex" @change="onRole">
          <view class="picker">{{ roleLabels[roleIndex] }}</view>
        </picker>
      </view>

      <!-- REMOVE_MEMBER -->
      <view v-else-if="type === 'REMOVE_MEMBER'" class="card">
        <template v-if="members.length">
          <text class="label">选择成员</text>
          <picker :range="memberLabels" :value="memberIndex" @change="onMember">
            <view class="picker">{{ memberLabels[memberIndex] }}</view>
          </picker>
        </template>
        <text v-else class="hint warn">本房无可移除成员</text>
      </view>

      <!-- ADD_VEHICLE -->
      <view v-else-if="type === 'ADD_VEHICLE'" class="card">
        <text class="label">车牌号</text>
        <input v-model="plateNo" class="input" placeholder="如 京A12345" />
        <text class="label">车位（可选）</text>
        <picker :range="freeSpaceLabels" :value="freeSpaceIndex" @change="onFreeSpace">
          <view class="picker">{{ freeSpaceLabels[freeSpaceIndex] }}</view>
        </picker>
      </view>

      <!-- REMOVE_VEHICLE -->
      <view v-else-if="type === 'REMOVE_VEHICLE'" class="card">
        <template v-if="vehicles.length">
          <text class="label">选择车辆</text>
          <picker :range="vehicleLabels" :value="vehicleIndex" @change="onVehicle">
            <view class="picker">{{ vehicleLabels[vehicleIndex] }}</view>
          </picker>
        </template>
        <text v-else class="hint warn">本房无车辆</text>
      </view>

      <!-- LINK_PARKING -->
      <view v-else-if="type === 'LINK_PARKING'" class="card">
        <template v-if="unlinked.length">
          <text class="label">未挂房车位</text>
          <picker :range="unlinkedLabels" :value="unlinkedIndex" @change="onUnlinked">
            <view class="picker">{{ unlinkedLabels[unlinkedIndex] }}</view>
          </picker>
        </template>
        <text v-else class="hint warn">暂无未挂房车位，请联系物业</text>
      </view>

      <!-- UNLINK_PARKING -->
      <view v-else-if="type === 'UNLINK_PARKING'" class="card">
        <template v-if="parkings.length">
          <text class="label">本房车位</text>
          <picker :range="parkingLabels" :value="parkingIndex" @change="onParking">
            <view class="picker">{{ parkingLabels[parkingIndex] }}</view>
          </picker>
        </template>
        <text v-else class="hint warn">本房无已挂车位</text>
      </view>

      <view class="card">
        <text class="label">留言（可选）</text>
        <textarea
          v-model="applyMessage"
          class="area"
          placeholder="补充说明"
          :maxlength="200"
        />
      </view>

      <button class="btn" :disabled="submitting" @click="submit">提交申请</button>
    </template>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import {
  getToken,
  getUser,
  setSession,
  setCurrentContext,
  getCurrentContext,
} from '../../utils/auth'
import { request, type ApiError } from '../../utils/request'
import { roleLabel } from '../../utils/format'

const TYPE_META: Record<string, string> = {
  ADD_MEMBER: '增加成员',
  REMOVE_MEMBER: '移除成员',
  ADD_VEHICLE: '增加车辆',
  REMOVE_VEHICLE: '移除车辆',
  LINK_PARKING: '关联车位',
  UNLINK_PARKING: '取消关联车位',
}

const MEMBER_ROLES = [
  { label: '业主成员', value: 'OWNER_MEMBER' },
  { label: '租户', value: 'TENANT' },
  { label: '租户成员', value: 'TENANT_MEMBER' },
]

type Opt = { id: number; occupantId?: number; label: string }

const type = ref('')
const roomId = ref<number | null>(null)
const loading = ref(true)
const ready = ref(false)
const submitting = ref(false)
const pageTitle = computed(() => TYPE_META[type.value] || '房屋变更')

const name = ref('')
const mobile = ref('')
const nameLocked = ref(false)
const lookupHint = ref('')
const roleIndex = ref(0)
const roleLabels = MEMBER_ROLES.map((r) => r.label)
const applyMessage = ref('')
const plateNo = ref('')

const members = ref<Opt[]>([])
const vehicles = ref<Opt[]>([])
const parkings = ref<Opt[]>([])
const unlinked = ref<Opt[]>([])
const freeSpaces = ref<Opt[]>([])
const freeSpaceLabels = ref<string[]>(['不绑车位'])

const memberIndex = ref(0)
const vehicleIndex = ref(0)
const parkingIndex = ref(0)
const unlinkedIndex = ref(0)
const freeSpaceIndex = ref(0)

const memberLabels = computed(() => members.value.map((m) => m.label))
const vehicleLabels = computed(() => vehicles.value.map((v) => v.label))
const parkingLabels = computed(() => parkings.value.map((p) => p.label))
const unlinkedLabels = computed(() => unlinked.value.map((p) => p.label))

let lookupSeq = 0
let lookupTimer: ReturnType<typeof setTimeout> | null = null

async function ensureRoomContext(rid: number, communityId?: number | null) {
  const ctx = getCurrentContext()
  if (ctx?.identityType === 'RESIDENT' && Number(ctx.roomId) === rid) {
    return
  }
  const data = await request<{ token: string }>({
    url: '/auth/context/switch',
    method: 'POST',
    data: {
      identityType: 'RESIDENT',
      communityId: communityId ?? ctx?.communityId ?? undefined,
      roomId: rid,
    },
  })
  setSession({
    token: data.token,
    user: getUser() || undefined,
    mustChangePassword: false,
  })
  setCurrentContext({
    ...(ctx || {}),
    identityType: 'RESIDENT',
    communityId: communityId ?? ctx?.communityId ?? null,
    roomId: rid,
  })
}

async function bootstrap() {
  if (!TYPE_META[type.value]) {
    loading.value = false
    uni.showModal({
      title: '不支持的类型',
      content: '请从房屋数据维护重新进入',
      showCancel: false,
      success: () => uni.navigateBack(),
    })
    return
  }
  if (!roomId.value) {
    loading.value = false
    uni.showToast({ title: '缺少房屋', icon: 'none' })
    return
  }
  loading.value = true
  ready.value = false
  try {
    if (!getToken()) {
      uni.reLaunch({ url: '/pages/login/login' })
      return
    }
    const detail = await request<Record<string, unknown>>({
      url: `/resident/rooms/${roomId.value}`,
    })
    if (detail.myRole !== 'OWNER') {
      throw new Error('仅当前房业主可提交变更')
    }
    await ensureRoomContext(
      roomId.value,
      detail.communityId != null ? Number(detail.communityId) : null,
    )

    const memberList = (detail.members as Array<Record<string, unknown>>) || []
    members.value = memberList
      .filter((m) => m.residentRole !== 'OWNER')
      .map((m) => ({
        id: Number(m.occupantId || m.id),
        occupantId: Number(m.occupantId || m.id),
        label: `${m.realName || '未命名'} · ${roleLabel(m.residentRole)} · ${m.mobile || ''}`,
      }))

    const vehicleList = (detail.vehicles as Array<Record<string, unknown>>) || []
    vehicles.value = vehicleList.map((v) => ({
      id: Number(v.id),
      label: `${v.plateNo}${v.parkingSpaceId ? '（已绑位）' : ''}`,
    }))

    const parkingList =
      (detail.parkingSpaces as Array<Record<string, unknown>>) || []
    parkings.value = parkingList.map((p) => ({
      id: Number(p.id),
      label: String(p.spaceNo || `车位#${p.id}`),
    }))

    const free = parkingList.filter((p) => {
      const used = vehicleList.some(
        (v) => Number(v.parkingSpaceId) === Number(p.id),
      )
      return !used
    })
    freeSpaces.value = free.map((p) => ({
      id: Number(p.id),
      label: String(p.spaceNo || `车位#${p.id}`),
    }))
    freeSpaceLabels.value = ['不绑车位'].concat(
      freeSpaces.value.map((p) => p.label),
    )

    if (type.value === 'LINK_PARKING') {
      const raw = await request<Array<Record<string, unknown>>>({
        url: '/resident/parking-spaces/unlinked',
      })
      unlinked.value = (raw || []).map((p) => ({
        id: Number(p.id),
        label: String(p.spaceNo || `车位#${p.id}`),
      }))
    }

    memberIndex.value = 0
    vehicleIndex.value = 0
    parkingIndex.value = 0
    unlinkedIndex.value = 0
    freeSpaceIndex.value = 0
    ready.value = true
  } catch (e) {
    uni.showModal({
      title: '无法申请',
      content: (e as ApiError).message || (e as Error).message || '请稍后重试',
      showCancel: false,
      success: () => uni.navigateBack(),
    })
  } finally {
    loading.value = false
  }
}

function onRole(e: { detail: { value: string } }) {
  roleIndex.value = Number(e.detail.value)
}
function onMember(e: { detail: { value: string } }) {
  memberIndex.value = Number(e.detail.value)
}
function onVehicle(e: { detail: { value: string } }) {
  vehicleIndex.value = Number(e.detail.value)
}
function onParking(e: { detail: { value: string } }) {
  parkingIndex.value = Number(e.detail.value)
}
function onUnlinked(e: { detail: { value: string } }) {
  unlinkedIndex.value = Number(e.detail.value)
}
function onFreeSpace(e: { detail: { value: string } }) {
  freeSpaceIndex.value = Number(e.detail.value)
}

function onMobileInput() {
  if (type.value !== 'ADD_MEMBER') return
  const m = mobile.value.trim()
  if (lookupTimer) clearTimeout(lookupTimer)
  const seq = ++lookupSeq
  if (!/^1\d{10}$/.test(m)) {
    lookupHint.value = ''
    nameLocked.value = false
    return
  }
  lookupHint.value = '正在匹配账号…'
  lookupTimer = setTimeout(() => lookupByMobile(m, seq), 280)
}

async function lookupByMobile(m: string, seq: number) {
  try {
    const data = await request<{ exists?: boolean; realName?: string }>({
      url: '/resident/users/by-mobile',
      data: { mobile: m },
    })
    if (seq !== lookupSeq) return
    if (data && data.exists) {
      const realName = String(data.realName || '').trim()
      if (realName) {
        name.value = realName
        nameLocked.value = true
        lookupHint.value = '已匹配系统账号，姓名已带入'
      } else {
        nameLocked.value = false
        lookupHint.value = '已匹配系统账号，请补充姓名'
      }
    } else {
      nameLocked.value = false
      lookupHint.value = '未找到已有账号，请填写姓名'
    }
  } catch (e) {
    if (seq !== lookupSeq) return
    nameLocked.value = false
    lookupHint.value = (e as ApiError).message || '匹配失败，可手动填写'
  }
}

function buildPayload(): Record<string, string | number> {
  switch (type.value) {
    case 'ADD_MEMBER': {
      const m = mobile.value.trim()
      if (!/^1\d{10}$/.test(m)) throw new Error('手机号须为 1 开头的 11 位数字')
      const n = name.value.trim()
      if (!n) throw new Error('请填写姓名')
      return {
        name: n,
        mobile: m,
        resident_role: MEMBER_ROLES[roleIndex.value].value,
      }
    }
    case 'REMOVE_MEMBER': {
      if (!members.value.length) throw new Error('本房无可移除成员')
      const occ = members.value[memberIndex.value]
      return { occupant_id: occ.occupantId ?? occ.id }
    }
    case 'ADD_VEHICLE': {
      const plate = plateNo.value.trim().toUpperCase()
      if (!plate) throw new Error('请填写车牌')
      const payload: Record<string, string | number> = { plate_no: plate }
      if (freeSpaceIndex.value > 0) {
        payload.parking_space_id = freeSpaces.value[freeSpaceIndex.value - 1].id
      }
      return payload
    }
    case 'REMOVE_VEHICLE': {
      if (!vehicles.value.length) throw new Error('本房无车辆')
      return { vehicle_id: vehicles.value[vehicleIndex.value].id }
    }
    case 'LINK_PARKING': {
      if (!unlinked.value.length) throw new Error('暂无未挂房车位')
      return { parking_space_id: unlinked.value[unlinkedIndex.value].id }
    }
    case 'UNLINK_PARKING': {
      if (!parkings.value.length) throw new Error('本房无已挂车位')
      return { parking_space_id: parkings.value[parkingIndex.value].id }
    }
    default:
      throw new Error('不支持的类型')
  }
}

async function submit() {
  if (submitting.value) return
  let payload: Record<string, string | number>
  try {
    payload = buildPayload()
  } catch (e) {
    uni.showToast({ title: (e as Error).message || '请检查填写', icon: 'none' })
    return
  }
  submitting.value = true
  try {
    if (roomId.value) {
      await ensureRoomContext(roomId.value)
    }
    await request({
      url: '/resident/room-change-applications',
      method: 'POST',
      data: {
        changeType: type.value,
        payload,
        applyMessage: applyMessage.value.trim() || undefined,
      },
    })
    uni.showToast({ title: '已提交', icon: 'success' })
    setTimeout(() => uni.navigateBack(), 500)
  } catch (e) {
    uni.showModal({
      title: '提交失败',
      content: (e as ApiError).message || '请稍后重试',
      showCancel: false,
    })
  } finally {
    submitting.value = false
  }
}

onLoad((q) => {
  type.value = (q?.type && String(q.type)) || ''
  roomId.value = q?.roomId ? Number(q.roomId) : null
  uni.setNavigationBarTitle({
    title: TYPE_META[type.value] || '房屋变更',
  })
  bootstrap()
})
</script>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 32rpx 48rpx;
  background: #f7faf8;
  box-sizing: border-box;
}
.empty {
  padding: 80rpx 0;
  text-align: center;
  color: #8a9a93;
  font-size: 28rpx;
}
.card {
  background: #fff;
  border: 1px solid #dfe8e3;
  border-radius: 12rpx;
  padding: 24rpx;
  margin-bottom: 16rpx;
}
.tip-card {
  padding: 20rpx 24rpx;
}
.tip {
  display: block;
  font-size: 24rpx;
  color: #7a8c85;
  line-height: 1.45;
}
.label {
  display: block;
  margin-top: 16rpx;
  margin-bottom: 8rpx;
  font-size: 24rpx;
  color: #5c6f68;
}
.label:first-child {
  margin-top: 0;
}
.input,
.picker {
  background: #f7faf8;
  border: 1px solid #dfe8e3;
  border-radius: 10rpx;
  padding: 18rpx 16rpx;
  font-size: 28rpx;
  color: #1f3d32;
}
.area {
  width: 100%;
  min-height: 140rpx;
  background: #f7faf8;
  border: 1px solid #dfe8e3;
  border-radius: 10rpx;
  padding: 16rpx;
  font-size: 28rpx;
  box-sizing: border-box;
}
.hint {
  display: block;
  margin-top: 8rpx;
  font-size: 22rpx;
  color: #2f6b55;
}
.hint.warn {
  color: #c45c26;
  margin-top: 0;
}
.btn {
  margin-top: 8rpx;
  background: #2f6b55;
  color: #fff;
  font-size: 30rpx;
  border-radius: 10rpx;
}
</style>
