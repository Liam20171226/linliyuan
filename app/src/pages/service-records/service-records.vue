<template>
  <view class="page">
    <view v-if="loading" class="empty">加载中…</view>
    <view v-else-if="!list.length" class="empty">暂无服务记录</view>
    <view
      v-for="item in list"
      :key="item.key"
      class="card"
      @click="goDetail(item)"
    >
      <view class="row">
        <text class="title">{{ item.title }}</text>
        <text class="status">{{ item.statusLabel }}</text>
      </view>
      <text class="desc">{{ item.desc }}</text>
      <text v-if="item.waitRate" class="wait">待评价</text>
      <text class="meta">{{ item.time }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { request } from '../../utils/request'
import {
  repairStatusLabel,
  complaintStatusLabel,
  formatDateTime,
} from '../../utils/format'

type Row = {
  key: string
  id: number
  kind: 'repair' | 'complaint'
  title: string
  desc: string
  statusLabel: string
  waitRate: boolean
  time: string
  createdAt: string
}

const loading = ref(true)
const list = ref<Row[]>([])

onShow(() => {
  load()
})

function shortTime(v: unknown) {
  const s = formatDateTime(v)
  return s ? s.slice(0, 16) : ''
}

function goDetail(item: Row) {
  uni.navigateTo({
    url: `/pages/service-detail/service-detail?id=${item.id}&kind=${item.kind}`,
  })
}

async function load() {
  loading.value = true
  try {
    const [repairs, complaints] = await Promise.all([
      request<{ list?: Array<Record<string, unknown>> }>({
        url: '/resident/repairs',
        data: { page: 1, pageSize: 50 },
      }).catch(() => null),
      request<{ list?: Array<Record<string, unknown>> }>({
        url: '/resident/complaints',
        data: { page: 1, pageSize: 50 },
      }).catch(() => null),
    ])

    const merged: Row[] = []
    ;((repairs && repairs.list) || []).forEach((it) => {
      const status = String(it.status || '')
      merged.push({
        key: 'R' + it.id,
        id: Number(it.id),
        kind: 'repair',
        title: String(it.category || '报修'),
        desc: String(it.content || it.description || ''),
        statusLabel: repairStatusLabel(it.status),
        waitRate: status === 'DONE_WAIT_RATE',
        time: shortTime(it.createdAt),
        createdAt: String(it.createdAt || ''),
      })
    })
    ;((complaints && complaints.list) || []).forEach((it) => {
      const status = String(it.status || '')
      merged.push({
        key: 'C' + it.id,
        id: Number(it.id),
        kind: 'complaint',
        title: String(it.category || '投诉'),
        desc: String(it.content || ''),
        statusLabel: complaintStatusLabel(it.status),
        waitRate: status === 'DONE_WAIT_RATE' && it.category !== '表扬',
        time: shortTime(it.createdAt),
        createdAt: String(it.createdAt || ''),
      })
    })
    merged.sort((a, b) => String(b.createdAt).localeCompare(String(a.createdAt)))
    list.value = merged
  } finally {
    loading.value = false
  }
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
  font-size: 28rpx;
  font-weight: 600;
  color: #1f4e3d;
}
.status {
  font-size: 22rpx;
  color: #2f6b55;
}
.desc {
  display: block;
  margin-top: 10rpx;
  font-size: 26rpx;
  color: #5c6f68;
  line-height: 1.45;
}
.wait {
  display: inline-block;
  margin-top: 10rpx;
  padding: 4rpx 14rpx;
  font-size: 22rpx;
  color: #b45309;
  background: #fef3c7;
  border-radius: 6rpx;
}
.meta {
  display: block;
  margin-top: 10rpx;
  font-size: 22rpx;
  color: #8a9a93;
}
</style>
