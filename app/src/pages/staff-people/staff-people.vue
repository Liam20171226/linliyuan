<template>
  <view class="page">
    <view class="tabs">
      <view class="tab" :class="{ on: tab === 'people' }" @click="switchTab('people')">人员</view>
      <view class="tab" :class="{ on: tab === 'vehicle' }" @click="switchTab('vehicle')">车辆</view>
      <view class="tab" :class="{ on: tab === 'change' }" @click="goChanges">变更审核</view>
    </view>

    <view v-if="tab !== 'change'" class="search">
      <input
        v-model="keyword"
        class="input"
        :placeholder="tab === 'people' ? '姓名/手机' : '车牌号'"
        confirm-type="search"
        @confirm="load"
      />
      <button class="search-btn" size="mini" @click="load">搜索</button>
    </view>

    <view v-if="loading" class="empty">加载中…</view>

    <template v-else-if="tab === 'people'">
      <text class="total">共 {{ peopleTotal }} 人</text>
      <view v-for="it in people" :key="it.id" class="card">
        <view class="row">
          <text class="name">{{ it.nameText }}</text>
          <text class="role">{{ it.roleText }}</text>
        </view>
        <text class="sub">{{ it.mobile || '无手机' }}</text>
        <text class="path">{{ it.roomPath }}</text>
      </view>
      <view v-if="!people.length" class="empty">暂无人员</view>
    </template>

    <template v-else-if="tab === 'vehicle'">
      <text class="total">共 {{ vehicleTotal }} 辆</text>
      <view v-for="it in vehicles" :key="it.id" class="card">
        <text class="name">{{ it.plateNo }}</text>
        <text class="sub">{{ it.spaceText }}</text>
        <text class="path">{{ it.ownerText }}</text>
        <text class="time">{{ it.timeLabel }}</text>
      </view>
      <view v-if="!vehicles.length" class="empty">暂无车辆</view>
    </template>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { getToken, mustChangePassword } from '../../utils/auth'
import { request, type ApiError } from '../../utils/request'
import { formatDateTime, roleLabel } from '../../utils/format'

type PeopleRow = {
  id: number | string
  nameText: string
  roleText: string
  mobile: string
  roomPath: string
}

type VehicleRow = {
  id: number | string
  plateNo: string
  spaceText: string
  ownerText: string
  timeLabel: string
}

const tab = ref<'people' | 'vehicle' | 'change'>('people')
const keyword = ref('')
const loading = ref(false)
const people = ref<PeopleRow[]>([])
const vehicles = ref<VehicleRow[]>([])
const peopleTotal = ref(0)
const vehicleTotal = ref(0)

function guard() {
  if (!getToken()) {
    uni.reLaunch({ url: '/pages/login/login' })
    return false
  }
  if (mustChangePassword()) {
    uni.reLaunch({ url: '/pages/change-password/change-password' })
    return false
  }
  return true
}

function roomPathOf(raw: Record<string, unknown>) {
  if (raw.roomPath) return String(raw.roomPath)
  if (raw.address) return String(raw.address)
  const parts = [raw.buildingName, raw.unitName, raw.floorName, raw.roomNo]
    .filter((x) => x != null && String(x) !== '')
    .map(String)
  return parts.join(' · ') || '—'
}

onLoad((q) => {
  const t = q && q.tab
  if (t === 'vehicle' || t === 'people') tab.value = t
  if (t === 'change') {
    uni.redirectTo({ url: '/pages/staff-changes/staff-changes' })
  }
})

function switchTab(next: 'people' | 'vehicle') {
  if (tab.value === next) return
  tab.value = next
  keyword.value = ''
  load()
}

function goChanges() {
  uni.navigateTo({ url: '/pages/staff-changes/staff-changes' })
}

async function load() {
  if (tab.value === 'change') return
  loading.value = true
  const kw = keyword.value.trim()
  try {
    if (tab.value === 'people') {
      const data = await request<{ list?: Record<string, unknown>[]; total?: number }>({
        url: '/staff/occupants',
        data: {
          realName: kw || undefined,
          mobile: kw || undefined,
          page: 1,
          pageSize: 50,
        },
      })
      people.value = ((data && data.list) || []).map((it) => ({
        id: (it.id as number) ?? `${it.roomId}-${it.mobile}`,
        nameText: String(it.realName || '未填写'),
        roleText: roleLabel(it.residentRole) || '住户',
        mobile: String(it.mobile || ''),
        roomPath: roomPathOf(it),
      }))
      peopleTotal.value = Number((data && data.total) || people.value.length)
    } else {
      const data = await request<{ list?: Record<string, unknown>[]; total?: number }>({
        url: '/staff/vehicles',
        data: {
          plateNo: kw || undefined,
          page: 1,
          pageSize: 50,
        },
      })
      vehicles.value = ((data && data.list) || []).map((it) => ({
        id: (it.id as number) ?? String(it.plateNo),
        plateNo: String(it.plateNo || '—'),
        spaceText: it.parkingSpaceNo ? `车位 ${it.parkingSpaceNo}` : '未关联车位',
        ownerText: String(it.ownerName || it.realName || it.mobile || ''),
        timeLabel: formatDateTime(it.createdAt),
      }))
      vehicleTotal.value = Number((data && data.total) || vehicles.value.length)
    }
  } catch (e) {
    if (tab.value === 'people') {
      people.value = []
      peopleTotal.value = 0
    } else {
      vehicles.value = []
      vehicleTotal.value = 0
    }
    uni.showToast({ title: (e as ApiError).message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

onShow(() => {
  if (!guard()) return
  if (tab.value !== 'change') load()
})
</script>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 32rpx 48rpx;
  background: #f7faf8;
  box-sizing: border-box;
}
.tabs {
  display: flex;
  gap: 8rpx;
  margin-bottom: 16rpx;
}
.tab {
  flex: 1;
  text-align: center;
  padding: 16rpx 8rpx;
  font-size: 26rpx;
  color: #5c6f68;
  background: #fff;
  border: 1px solid #dfe8e3;
  border-radius: 10rpx;
}
.tab.on {
  color: #fff;
  background: #2f6b55;
  border-color: #2f6b55;
}
.search {
  display: flex;
  gap: 12rpx;
  margin-bottom: 12rpx;
}
.input {
  flex: 1;
  background: #fff;
  border: 1px solid #dfe8e3;
  border-radius: 10rpx;
  padding: 16rpx;
  font-size: 28rpx;
}
.search-btn {
  background: #2f6b55;
  color: #fff;
  font-size: 26rpx;
}
.total {
  display: block;
  margin-bottom: 12rpx;
  font-size: 24rpx;
  color: #8a9a93;
}
.card {
  background: #fff;
  border: 1px solid #dfe8e3;
  border-radius: 12rpx;
  padding: 22rpx 24rpx;
  margin-bottom: 14rpx;
}
.row {
  display: flex;
  justify-content: space-between;
  gap: 12rpx;
}
.name {
  font-size: 30rpx;
  font-weight: 600;
  color: #1f3d32;
}
.role {
  font-size: 22rpx;
  color: #2f6b55;
}
.sub {
  display: block;
  margin-top: 8rpx;
  font-size: 26rpx;
  color: #5c6f68;
}
.path,
.time {
  display: block;
  margin-top: 4rpx;
  font-size: 24rpx;
  color: #8a9a93;
}
.empty {
  padding: 48rpx 0;
  text-align: center;
  font-size: 28rpx;
  color: #8a9a93;
}
</style>
