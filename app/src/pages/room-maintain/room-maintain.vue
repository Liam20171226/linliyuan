<template>
  <view class="page">
    <view v-if="loading" class="empty">加载中…</view>
    <template v-else>
      <view class="card">
        <text class="sec">选择房屋</text>
        <picker
          v-if="roomOptions.length"
          :range="roomLabels"
          :value="roomIndex"
          @change="onRoom"
        >
          <view class="picker">{{ currentLabel || '请选择房屋' }}</view>
        </picker>
        <text v-else class="hint">暂无绑定房屋，请先完成住户认证</text>
        <text v-if="currentRole" class="meta">我的角色：{{ currentRole }}</text>
      </view>

      <view v-if="roomId" class="card">
        <text class="sec">房屋变更（仅业主）</text>
        <view
          v-for="a in actions"
          :key="a.type"
          class="entry"
          :class="{ disabled: !isOwner }"
          @click="goChange(a.type)"
        >
          <text class="entry-title">{{ a.name }}</text>
          <text class="entry-desc">{{ isOwner ? a.hint : '仅业主可申请' }}</text>
        </view>
      </view>

      <view v-if="roomId" class="card">
        <text class="sec">申请记录</text>
        <view class="entry" @click="goAuthRecords">
          <text class="entry-title">认证记录</text>
          <text class="entry-desc">查看住户认证申请进度</text>
        </view>
        <view class="entry" @click="goChangeRecords">
          <text class="entry-title">变更记录</text>
          <text class="entry-desc">查看房屋变更申请进度</text>
        </view>
      </view>

      <button class="link" @click="goAuth">去住户认证</button>
    </template>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { request, type ApiError } from '../../utils/request'
import { roleLabel } from '../../utils/format'

type RoomOpt = {
  roomId: number
  communityId?: number
  label: string
  residentRole?: string
}

const ACTIONS = [
  { type: 'ADD_MEMBER', name: '增加成员', hint: '添加家属 / 租户' },
  { type: 'REMOVE_MEMBER', name: '移除成员', hint: '解除本房绑定' },
  { type: 'ADD_VEHICLE', name: '增加车辆', hint: '登记车牌' },
  { type: 'REMOVE_VEHICLE', name: '移除车辆', hint: '删除本房车辆' },
  { type: 'LINK_PARKING', name: '关联车位', hint: '挂未占用车位' },
  { type: 'UNLINK_PARKING', name: '取消关联车位', hint: '解绑本房车位' },
]

const loading = ref(true)
const roomOptions = ref<RoomOpt[]>([])
const roomId = ref<number | null>(null)
const currentRole = ref('')
const isOwner = ref(false)
const actions = ACTIONS

const roomLabels = computed(() => roomOptions.value.map((r) => r.label))
const roomIndex = computed(() => {
  const i = roomOptions.value.findIndex((r) => r.roomId === roomId.value)
  return i >= 0 ? i : 0
})
const currentLabel = computed(() => {
  const hit = roomOptions.value.find((r) => r.roomId === roomId.value)
  return hit ? hit.label : ''
})

function applyRoom(opt: RoomOpt) {
  roomId.value = opt.roomId
  currentRole.value = roleLabel(opt.residentRole)
  isOwner.value = opt.residentRole === 'OWNER'
}

function onRoom(e: { detail: { value: string } }) {
  const opt = roomOptions.value[Number(e.detail.value)]
  if (opt) applyRoom(opt)
}

async function load() {
  loading.value = true
  try {
    const rooms = await request<Array<Record<string, unknown>>>({
      url: '/resident/rooms',
    })
    roomOptions.value = (rooms || []).map((r) => ({
      roomId: Number(r.roomId),
      communityId: r.communityId != null ? Number(r.communityId) : undefined,
      residentRole: String(r.residentRole || ''),
      label: `${r.address || r.roomNo || '房屋' + r.roomId}（${roleLabel(r.residentRole)}）`,
    }))
    if (roomOptions.value.length) {
      const keep = roomOptions.value.find((r) => r.roomId === roomId.value)
      applyRoom(keep || roomOptions.value[0])
    } else {
      roomId.value = null
      currentRole.value = ''
      isOwner.value = false
    }
  } catch (e) {
    roomOptions.value = []
    uni.showToast({ title: (e as ApiError).message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

function goChange(changeType: string) {
  if (!isOwner.value) {
    uni.showToast({ title: '仅业主可申请变更', icon: 'none' })
    return
  }
  if (!roomId.value) return
  uni.navigateTo({
    url: `/pages/room-change-apply/room-change-apply?type=${changeType}&roomId=${roomId.value}`,
  })
}

function goAuthRecords() {
  uni.navigateTo({ url: '/pages/approval-records/approval-records?tab=auth' })
}

function goChangeRecords() {
  uni.navigateTo({ url: '/pages/approval-records/approval-records?tab=change' })
}

function goAuth() {
  uni.navigateTo({ url: '/pages/auth-apply/auth-apply' })
}

onShow(() => {
  load()
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
.sec {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: #1f4e3d;
  margin-bottom: 16rpx;
}
.picker {
  background: #f7faf8;
  border: 1px solid #dfe8e3;
  border-radius: 10rpx;
  padding: 22rpx 20rpx;
  font-size: 28rpx;
  color: #1f3d32;
}
.meta {
  display: block;
  margin-top: 12rpx;
  font-size: 24rpx;
  color: #8a9a93;
}
.hint {
  font-size: 24rpx;
  color: #c45c26;
}
.entry {
  padding: 20rpx 0;
  border-top: 1px solid #eef3f0;
}
.entry:first-of-type {
  border-top: none;
  padding-top: 0;
}
.entry.disabled {
  opacity: 0.55;
}
.entry-title {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: #1f3d32;
}
.entry-desc {
  display: block;
  margin-top: 6rpx;
  font-size: 24rpx;
  color: #8a9a93;
}
.link {
  margin-top: 12rpx;
  background: transparent;
  color: #2f6b55;
  font-size: 28rpx;
}
</style>
