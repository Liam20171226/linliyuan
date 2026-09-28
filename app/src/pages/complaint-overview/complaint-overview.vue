<template>
  <view class="page">
    <view v-if="loading" class="empty">加载中…</view>
    <view v-else-if="!list.length" class="empty">暂无工单</view>
    <view
      v-for="item in list"
      :key="item.id + '-' + item.navKind"
      class="card"
      @click="open(item)"
    >
      <view class="row">
        <text class="title">{{ item.kindLabel }}</text>
        <text class="status">{{ item.statusLabel }}</text>
      </view>
      <text v-if="item.desc" class="desc">{{ item.desc }}</text>
      <text v-if="item.contactLine" class="meta">{{ item.contactLine }}</text>
      <text class="meta">{{ item.timeLabel }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { request, type ApiError } from '../../utils/request'
import {
  repairStatusLabel,
  complaintStatusLabel,
  formatDateTime,
} from '../../utils/format'

type Row = {
  id: number
  kindLabel: string
  statusLabel: string
  desc: string
  contactLine: string
  timeLabel: string
  navKind: 'repair' | 'complaint'
}

const loading = ref(true)
const list = ref<Row[]>([])

async function load() {
  loading.value = true
  try {
    let data: { list?: Array<Record<string, unknown>> } | null = null
    try {
      data = await request({
        url: '/committee/tickets',
        data: { page: 1, pageSize: 50 },
      })
    } catch {
      data = await request({
        url: '/committee/complaints/overview',
        data: { page: 1, pageSize: 50 },
      })
    }
    list.value = ((data && data.list) || []).map((it) => {
      const isRepair = it.kind === 'REPAIR'
      return {
        id: Number(it.id),
        kindLabel: isRepair ? '报修' : String(it.category || '投诉'),
        statusLabel: isRepair
          ? repairStatusLabel(it.status)
          : complaintStatusLabel(it.status),
        desc: String(it.content || it.description || ''),
        contactLine:
          [it.contactName, it.contactMobile].filter(Boolean).join(' ') ||
          String(it.roomLabel || ''),
        timeLabel: formatDateTime(it.createdAt),
        navKind: isRepair ? 'repair' : 'complaint',
      }
    })
  } catch (e) {
    list.value = []
    uni.showToast({ title: (e as ApiError).message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

function open(item: Row) {
  if (!item.id) return
  uni.navigateTo({
    url: `/pages/staff-ticket-detail/staff-ticket-detail?id=${item.id}&kind=${item.navKind}&readonly=1`,
  })
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
.row {
  display: flex;
  justify-content: space-between;
  align-items: center;
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
  margin-top: 12rpx;
  font-size: 26rpx;
  color: #1f3d32;
  line-height: 1.45;
}
.meta {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #8a9a93;
}
</style>
