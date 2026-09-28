<template>
  <view class="page">
    <view v-if="loading" class="empty">加载中…</view>
    <view v-else-if="!list.length" class="empty">暂无投票</view>
    <view
      v-for="item in list"
      :key="item.id"
      class="card"
      @click="open(item.id)"
    >
      <view class="row">
        <text class="title">{{ item.title }}</text>
        <text class="status">{{ item.statusLabel }}</text>
      </view>
      <text class="meta">{{ item.sourceLabel }} · {{ item.rangeLabel }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { request, type ApiError } from '../../utils/request'
import { voteStatusLabel, formatDateTime } from '../../utils/format'

type VoteRow = {
  id: number
  title: string
  statusLabel: string
  sourceLabel: string
  rangeLabel: string
}

const loading = ref(true)
const list = ref<VoteRow[]>([])

function sourceLabelOf(role: unknown) {
  if (role === 'COMMITTEE') return '业委会'
  if (role === 'PROPERTY_MANAGER' || role === 'STAFF') return '物业'
  if (role === 'PLATFORM') return '平台'
  return '业主投票'
}

function shortRange(startAt: unknown, endAt: unknown) {
  const a = formatDateTime(startAt)
  const b = formatDateTime(endAt)
  const as = a ? a.slice(0, 16) : ''
  const bs = b ? b.slice(0, 16) : ''
  if (as && bs) return `${as} ~ ${bs}`
  return as || bs || ''
}

onShow(() => {
  load()
})

async function load() {
  loading.value = true
  try {
    const data = await request<{ list?: Array<Record<string, unknown>> }>({
      url: '/resident/votes',
      data: { page: 1, pageSize: 50 },
    })
    list.value = ((data && data.list) || []).map((it) => ({
      id: Number(it.id),
      title: String(it.title || '投票'),
      statusLabel: voteStatusLabel(it.status),
      sourceLabel: sourceLabelOf(it.creatorRole),
      rangeLabel: shortRange(it.startAt, it.endAt),
    }))
  } catch (e) {
    list.value = []
    uni.showToast({ title: (e as ApiError).message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

function open(id: number) {
  if (!id) {
    uni.showToast({ title: '无效投票', icon: 'none' })
    return
  }
  uni.navigateTo({ url: `/pages/vote-detail/vote-detail?id=${id}` })
}
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
.row {
  display: flex;
  justify-content: space-between;
  gap: 12rpx;
}
.title {
  flex: 1;
  font-size: 30rpx;
  font-weight: 600;
  color: #1f4e3d;
}
.status {
  font-size: 22rpx;
  color: #2f6b55;
}
.meta {
  display: block;
  margin-top: 10rpx;
  font-size: 22rpx;
  color: #8a9a93;
}
</style>
