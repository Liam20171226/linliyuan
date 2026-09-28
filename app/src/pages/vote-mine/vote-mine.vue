<template>
  <view class="page">
    <view v-if="loading" class="empty">加载中…</view>
    <view v-else-if="!list.length" class="empty">暂无投票</view>
    <view v-for="it in list" :key="it.id" class="card" @click="open(it)">
      <view class="row">
        <text class="title">{{ it.title }}</text>
        <text class="badge">{{ it.statusLabel }}</text>
      </view>
      <text class="time">{{ it.timeLabel }}</text>
      <text v-if="it.mine" class="tag">我发起的</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { getCurrentContext, getToken, getUser, mustChangePassword } from '../../utils/auth'
import { request, type ApiError } from '../../utils/request'
import { formatDateTime, voteStatusLabel } from '../../utils/format'

type Row = {
  id: number
  title: string
  statusLabel: string
  timeLabel: string
  mine: boolean
}

const loading = ref(true)
const list = ref<Row[]>([])

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

async function load() {
  loading.value = true
  try {
    const ctx = getCurrentContext()
    const idType = ctx?.identityType || ''
    const url = idType === 'STAFF' ? '/staff/votes' : '/committee/votes'

    let userId: number | null = null
    try {
      const packed = await request<{
        profile?: { id?: number }
        user?: { id?: number }
      }>({ url: '/auth/identities' })
      userId =
        (packed.profile && packed.profile.id) ||
        (packed.user && packed.user.id) ||
        (getUser()?.id as number) ||
        null
      if (userId != null) userId = Number(userId)
    } catch {
      const u = getUser()
      userId = u?.id != null ? Number(u.id) : null
    }

    const data = await request<{ list?: Array<Record<string, unknown>> }>({
      url,
      data: { page: 1, pageSize: 50 },
    })
    list.value = ((data && data.list) || []).map((it) => {
      const mine = userId != null && Number(it.createdBy) === userId
      return {
        id: Number(it.id),
        title: String(it.title || '投票'),
        statusLabel: voteStatusLabel(it.status),
        timeLabel: `${formatDateTime(it.startAt)} ~ ${formatDateTime(it.endAt)}`,
        mine,
      }
    })
  } catch (e) {
    list.value = []
    uni.showToast({ title: (e as ApiError).message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

function open(it: Row) {
  uni.navigateTo({
    url: `/pages/vote-stats/vote-stats?id=${it.id}&mine=${it.mine ? '1' : '0'}`,
  })
}

onShow(() => {
  if (!guard()) return
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
.card {
  background: #fff;
  border: 1px solid #dfe8e3;
  border-radius: 12rpx;
  padding: 24rpx;
  margin-bottom: 16rpx;
}
.row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12rpx;
}
.title {
  flex: 1;
  font-size: 30rpx;
  font-weight: 600;
  color: #1f3d32;
}
.badge {
  font-size: 22rpx;
  color: #2f6b55;
  background: #e8f2ec;
  padding: 4rpx 12rpx;
  border-radius: 8rpx;
}
.time {
  display: block;
  margin-top: 10rpx;
  font-size: 24rpx;
  color: #8a9a93;
}
.tag {
  display: inline-block;
  margin-top: 8rpx;
  font-size: 22rpx;
  color: #b45309;
}
.empty {
  padding: 80rpx 0;
  text-align: center;
  font-size: 28rpx;
  color: #8a9a93;
}
</style>
