<template>
  <view class="page">
    <view class="tabs">
      <text :class="['tab', mode === 'unpaid' ? 'on' : '']" @click="setMode('unpaid')">待缴</text>
      <text :class="['tab', mode === 'history' ? 'on' : '']" @click="setMode('history')">记录</text>
    </view>

    <view v-if="loading" class="empty">加载中…</view>
    <view v-else-if="!bills.length" class="empty">
      {{ mode === 'history' ? '暂无缴费记录' : '暂无待缴账单' }}
    </view>

    <view v-for="b in bills" :key="b.id" class="card">
      <view class="row">
        <text class="room">{{ b.roomLabel }}</text>
        <text class="status">{{ b.statusLabel }}</text>
      </view>
      <text v-if="b.billMonth" class="meta">账期 {{ b.billMonth }}</text>
      <view v-for="line in b.lines" :key="line.id" class="line">
        <text>{{ line.title }}</text>
        <text>¥{{ line.amountText }}</text>
      </view>
      <view class="footer">
        <text class="amount">¥{{ b.totalAmountText }}</text>
        <button
          v-if="mode === 'unpaid' && b.payable"
          class="pay"
          size="mini"
          @click="onPay"
        >
          去支付
        </button>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { request, type ApiError } from '../../utils/request'
import { billStatusLabel, formatMoney } from '../../utils/format'

type BillLine = { id: number | string; title: string; amountText: string }
type BillRow = {
  id: number
  roomLabel: string
  statusLabel: string
  billMonth: string
  totalAmountText: string
  payable: boolean
  lines: BillLine[]
}

const mode = ref<'unpaid' | 'history'>('unpaid')
const loading = ref(true)
const bills = ref<BillRow[]>([])

onLoad((q) => {
  mode.value = q?.mode === 'history' ? 'history' : 'unpaid'
  uni.setNavigationBarTitle({
    title: mode.value === 'history' ? '缴费记录' : '我的账单',
  })
})

onShow(() => {
  load()
})

function setMode(m: 'unpaid' | 'history') {
  if (mode.value === m) return
  mode.value = m
  uni.setNavigationBarTitle({
    title: m === 'history' ? '缴费记录' : '我的账单',
  })
  load()
}

function normalize(b: Record<string, unknown>): BillRow {
  const lines = ((b.lines as Array<Record<string, unknown>>) || []).map((l, i) => ({
    id: (l.id as number) ?? i,
    title: String(l.title || l.feeCategory || '费用'),
    amountText: formatMoney(l.amount),
  }))
  return {
    id: Number(b.id),
    roomLabel: String(b.roomLabel || b.roomNo || `房#${b.roomId}`),
    statusLabel: billStatusLabel(b.status),
    billMonth: String(b.billMonth || ''),
    totalAmountText: formatMoney(b.totalAmount),
    payable: b.status === 'PUBLISHED' || b.status === 'OVERDUE',
    lines,
  }
}

async function load() {
  loading.value = true
  try {
    const status = mode.value === 'history' ? 'PAID' : 'UNPAID'
    const data = await request<{ list?: Array<Record<string, unknown>> }>({
      url: '/resident/bills',
      data: { page: 1, pageSize: 100, status },
    })
    bills.value = ((data && data.list) || []).map(normalize)
  } catch (e) {
    bills.value = []
    uni.showToast({
      title: (e as ApiError).message || '请先切换住户身份',
      icon: 'none',
    })
  } finally {
    loading.value = false
  }
}

function onPay() {
  uni.showToast({ title: '支付暂未开放', icon: 'none' })
}
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
  gap: 16rpx;
  margin-bottom: 20rpx;
}
.tab {
  flex: 1;
  text-align: center;
  padding: 16rpx 0;
  font-size: 28rpx;
  color: #5c6f68;
  background: #fff;
  border: 1px solid #dfe8e3;
  border-radius: 10rpx;
}
.tab.on {
  color: #1f4e3d;
  font-weight: 600;
  border-color: #2f6b55;
  background: #e8f2ec;
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
}
.room {
  font-size: 28rpx;
  font-weight: 600;
  color: #1f4e3d;
}
.status {
  font-size: 22rpx;
  color: #2f6b55;
}
.meta {
  display: block;
  margin: 8rpx 0 12rpx;
  font-size: 22rpx;
  color: #8a9a93;
}
.line {
  display: flex;
  justify-content: space-between;
  font-size: 24rpx;
  color: #5c6f68;
  padding: 6rpx 0;
}
.footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 16rpx;
  padding-top: 12rpx;
  border-top: 1px solid #eef3f0;
}
.amount {
  font-size: 34rpx;
  font-weight: 700;
  color: #1f4e3d;
}
.pay {
  background: #2f6b55;
  color: #fff;
  font-size: 24rpx;
  margin: 0;
}
</style>
