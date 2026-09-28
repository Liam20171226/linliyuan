<template>
  <view class="page">
    <view class="tabs">
      <view class="tab" :class="{ on: tab === 'open' }" @click="tab = 'open'">进行中</view>
      <view class="tab" :class="{ on: tab === 'done' }" @click="tab = 'done'">已完成</view>
    </view>

    <view v-if="loading" class="empty">加载中…</view>
    <view v-else-if="!rows.length" class="empty">暂无巡检任务</view>
    <view
      v-for="item in rows"
      :key="item.id"
      class="card"
      @click="open(item.id)"
    >
      <view class="row">
        <text class="title">{{ item.title }}</text>
        <text class="status">{{ item.statusLabel }}</text>
      </view>
      <text class="meta">进度 {{ item.progress }}</text>
      <text v-if="item.assignee" class="meta">执行人 {{ item.assignee }}</text>
      <text class="meta">{{ item.time }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { request, type ApiError } from '../../utils/request'
import { formatDateTime } from '../../utils/format'

type Row = {
  id: number
  title: string
  statusLabel: string
  progress: string
  assignee: string
  time: string
}

const tab = ref<'open' | 'done'>('open')
const loading = ref(true)
const openList = ref<Row[]>([])
const doneList = ref<Row[]>([])
const rows = computed(() => (tab.value === 'open' ? openList.value : doneList.value))

function mapRow(it: Record<string, unknown>): Row {
  const t = formatDateTime(it.createdAt)
  return {
    id: Number(it.id),
    title: String(it.planTitle || '巡检任务'),
    statusLabel: String(it.statusLabel || it.status || ''),
    progress: `${it.visitedCount || 0}/${it.spotCount || 0}`,
    assignee: String(it.assigneeName || ''),
    time: t ? t.slice(0, 16) : '',
  }
}

async function load() {
  loading.value = true
  try {
    const [openRes, doneRes] = await Promise.all([
      request<{ list?: Array<Record<string, unknown>> }>({
        url: '/staff/inspect/jobs',
        data: { status: 'OPEN' },
      }).catch(() => null),
      request<{ list?: Array<Record<string, unknown>> }>({
        url: '/staff/inspect/jobs',
        data: { status: 'DONE' },
      }).catch(() => null),
    ])
    openList.value = ((openRes && openRes.list) || []).map(mapRow)
    doneList.value = ((doneRes && doneRes.list) || []).map(mapRow)
  } catch (e) {
    openList.value = []
    doneList.value = []
    uni.showToast({ title: (e as ApiError).message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

function open(id: number) {
  if (!id) return
  uni.navigateTo({ url: `/pages/staff-inspect-detail/staff-inspect-detail?id=${id}` })
}

onShow(() => {
  load()
})
</script>

<style scoped>
.page {
  min-height: 100vh;
  padding: 0 32rpx 48rpx;
  background: #f7faf8;
  box-sizing: border-box;
}
.tabs {
  display: flex;
  margin: 0 -32rpx 20rpx;
  background: #fff;
  border-bottom: 1px solid #dfe8e3;
}
.tab {
  flex: 1;
  text-align: center;
  padding: 24rpx 0;
  font-size: 28rpx;
  color: #5c6f68;
}
.tab.on {
  color: #1f4e3d;
  font-weight: 700;
  border-bottom: 4rpx solid #2f6b55;
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
  font-size: 28rpx;
  font-weight: 600;
  color: #1f4e3d;
}
.status {
  font-size: 24rpx;
  color: #2f6b55;
  flex-shrink: 0;
}
.meta {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #8a9a93;
}
</style>
