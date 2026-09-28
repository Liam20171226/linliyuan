<template>
  <view class="page">
    <view v-if="loading" class="empty">加载中…</view>
    <view v-else-if="!list.length" class="empty">暂无公示项目</view>
    <view v-for="item in list" :key="item.id" class="card">
      <view class="row">
        <text class="title">{{ item.title }}</text>
        <text class="amt">¥{{ item.amountText }}</text>
      </view>
      <text class="meta">已公示 · {{ item.timeLabel }}</text>
      <text v-if="item.remark" class="remark">{{ item.remark }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { request, type ApiError } from '../../utils/request'
import { formatDateTime, formatMoney } from '../../utils/format'

type Row = {
  id: number | string
  title: string
  amountText: string
  timeLabel: string
  remark: string
}

const loading = ref(true)
const list = ref<Row[]>([])

onShow(() => {
  load()
})

async function load() {
  loading.value = true
  try {
    let data: { list?: Array<Record<string, unknown>> } | null = null
    try {
      data = await request({
        url: '/resident/public-revenue/items',
        data: { page: 1, pageSize: 50 },
      })
    } catch {
      data = await request({
        url: '/committee/public-revenue/items',
        data: { page: 1, pageSize: 50 },
      })
    }
    list.value = ((data && data.list) || []).map((it) => ({
      id: (it.id as number) ?? String(it.title),
      title: String(it.title || '公示项目'),
      amountText: formatMoney(it.amount),
      timeLabel: formatDateTime(it.updatedAt || it.createdAt || it.confirmedAt).slice(0, 16),
      remark: String(it.remark || ''),
    }))
  } catch (e) {
    list.value = []
    uni.showToast({ title: (e as ApiError).message || '加载失败', icon: 'none' })
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
  flex: 1;
  font-size: 28rpx;
  font-weight: 600;
  color: #1f4e3d;
}
.amt {
  font-size: 28rpx;
  font-weight: 700;
  color: #2f6b55;
}
.meta {
  display: block;
  margin-top: 10rpx;
  font-size: 22rpx;
  color: #8a9a93;
}
.remark {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #5c6f68;
}
</style>
