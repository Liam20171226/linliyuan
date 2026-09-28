<template>
  <view class="page">
    <view class="nav">
      <text class="nav-btn" @click="prev">‹</text>
      <view class="nav-mid">
        <text class="range">{{ rangeLabel }}</text>
        <view class="period-tabs">
          <text :class="['ptab', period === 'month' ? 'on' : '']" @click="setPeriod('month')">按月</text>
          <text :class="['ptab', period === 'year' ? 'on' : '']" @click="setPeriod('year')">按年</text>
        </view>
      </view>
      <text class="nav-btn" @click="next">›</text>
    </view>

    <view v-if="loading" class="empty">加载中…</view>
    <view v-else-if="!summary" class="empty">暂无数据</view>
    <view v-else>
      <view class="summary">
        <view class="cell">
          <text class="k">收入</text>
          <text class="v">¥{{ summary.income }}</text>
        </view>
        <view class="cell">
          <text class="k">支出</text>
          <text class="v">¥{{ summary.expense }}</text>
        </view>
        <view class="cell">
          <text class="k">结余</text>
          <text :class="['v', balanceNeg ? 'neg' : '']">¥{{ summary.balance }}</text>
        </view>
      </view>

      <view v-if="incomeRows.length" class="block">
        <text class="block-title">收入构成</text>
        <view v-for="(r, i) in incomeRows" :key="'i' + i" class="row">
          <text class="name">{{ r.label }}</text>
          <text class="amt">¥{{ r.amount }}</text>
        </view>
      </view>

      <view v-if="expenseRows.length" class="block">
        <text class="block-title">支出构成</text>
        <view v-for="(r, i) in expenseRows" :key="'e' + i" class="row">
          <text class="name">{{ r.label }}</text>
          <text class="amt">¥{{ r.amount }}</text>
        </view>
      </view>
    </view>

    <button class="link" @click="goRevenue">公共收益公示</button>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { request, type ApiError } from '../../utils/request'
import { formatMoney } from '../../utils/format'

const CAT_LABEL: Record<string, string> = {
  PROPERTY_FEE: '物业管理费',
  PARKING_MGMT: '车位管理费',
  PARKING_MONTHLY: '车辆月保费',
  SHARED: '公摊费',
  GARBAGE: '垃圾费',
  WATER: '代收水费',
  ELECTRIC: '代收电费',
  GAS: '代收煤气费',
  OTHER: '其他',
  ENTRY_INCOME: '其他经营收入',
  PREPAID_INCOME: '预缴摊入',
}

function pad(n: number) {
  return n < 10 ? '0' + n : '' + n
}

function rangeFor(period: string, year: number, month: number) {
  if (period === 'year') {
    return { from: `${year}-01-01`, to: `${year}-12-31`, label: `${year}年` }
  }
  const from = `${year}-${pad(month)}-01`
  const last = new Date(year, month, 0).getDate()
  const to = `${year}-${pad(month)}-${pad(last)}`
  return { from, to, label: `${year}年${month}月` }
}

const period = ref<'month' | 'year'>('month')
const year = ref(2026)
const month = ref(9)
const rangeLabel = ref('')
const loading = ref(true)
const summary = ref<{ income: string; expense: string; balance: string } | null>(null)
const balanceNeg = ref(false)
const incomeRows = ref<Array<{ label: string; amount: string }>>([])
const expenseRows = ref<Array<{ label: string; amount: string }>>([])

onLoad(() => {
  const now = new Date()
  year.value = now.getFullYear()
  month.value = now.getMonth() + 1
})

onShow(() => {
  load()
})

function setPeriod(p: 'month' | 'year') {
  if (period.value === p) return
  period.value = p
  load()
}

function prev() {
  if (period.value === 'year') year.value -= 1
  else {
    month.value -= 1
    if (month.value < 1) {
      month.value = 12
      year.value -= 1
    }
  }
  load()
}

function next() {
  if (period.value === 'year') year.value += 1
  else {
    month.value += 1
    if (month.value > 12) {
      month.value = 1
      year.value += 1
    }
  }
  load()
}

async function load() {
  const range = rangeFor(period.value, year.value, month.value)
  rangeLabel.value = range.label
  loading.value = true
  try {
    const s = await request<Record<string, unknown>>({
      url: '/resident/finance/summary',
      data: { from: range.from, to: range.to, viewMode: 'BY_BILL_MONTH' },
    })
    const raw = s || { income: 0, expense: 0, balance: 0 }
    summary.value = {
      income: formatMoney(raw.income),
      expense: formatMoney(raw.expense),
      balance: formatMoney(raw.balance),
    }
    balanceNeg.value = Number(raw.balance || 0) < 0

    incomeRows.value = ((raw.incomeBreakdown as Array<Record<string, unknown>>) || [])
      .filter((b) => b.feeCategory !== 'PREPAID_INCOME')
      .map((b) => ({
        label: String(b.label || CAT_LABEL[String(b.feeCategory)] || b.feeCategory || '收入'),
        amount: formatMoney(b.amount),
      }))

    expenseRows.value = ((raw.expenseBreakdown as Array<Record<string, unknown>>) || []).map(
      (b) => ({
        label: String(b.label || b.title || '支出'),
        amount: formatMoney(b.amount),
      }),
    )
  } catch (e) {
    summary.value = null
    incomeRows.value = []
    expenseRows.value = []
    uni.showToast({ title: (e as ApiError).message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

function goRevenue() {
  uni.navigateTo({ url: '/pages/public-revenue/public-revenue' })
}
</script>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 32rpx 48rpx;
  background: #f7faf8;
  box-sizing: border-box;
}
.nav {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-bottom: 24rpx;
}
.nav-btn {
  width: 64rpx;
  height: 64rpx;
  line-height: 64rpx;
  text-align: center;
  font-size: 40rpx;
  color: #2f6b55;
  background: #fff;
  border: 1px solid #dfe8e3;
  border-radius: 10rpx;
}
.nav-mid {
  flex: 1;
  text-align: center;
}
.range {
  display: block;
  font-size: 30rpx;
  font-weight: 600;
  color: #1f4e3d;
}
.period-tabs {
  display: flex;
  justify-content: center;
  gap: 12rpx;
  margin-top: 10rpx;
}
.ptab {
  font-size: 22rpx;
  color: #8a9a93;
  padding: 4rpx 16rpx;
  border-radius: 20rpx;
  background: #fff;
  border: 1px solid #dfe8e3;
}
.ptab.on {
  color: #1f4e3d;
  border-color: #2f6b55;
  background: #e8f2ec;
}
.empty {
  padding: 80rpx 0;
  text-align: center;
  color: #8a9a93;
  font-size: 28rpx;
}
.summary {
  display: flex;
  gap: 12rpx;
  margin-bottom: 20rpx;
}
.cell {
  flex: 1;
  background: #fff;
  border: 1px solid #dfe8e3;
  border-radius: 12rpx;
  padding: 20rpx 12rpx;
  text-align: center;
}
.k {
  display: block;
  font-size: 22rpx;
  color: #8a9a93;
}
.v {
  display: block;
  margin-top: 8rpx;
  font-size: 26rpx;
  font-weight: 700;
  color: #1f4e3d;
}
.v.neg {
  color: #c45c26;
}
.block {
  background: #fff;
  border: 1px solid #dfe8e3;
  border-radius: 12rpx;
  padding: 20rpx;
  margin-bottom: 16rpx;
}
.block-title {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: #1f4e3d;
  margin-bottom: 12rpx;
}
.row {
  display: flex;
  justify-content: space-between;
  padding: 10rpx 0;
  font-size: 26rpx;
  color: #5c6f68;
}
.amt {
  color: #1f4e3d;
  font-weight: 600;
}
.link {
  margin-top: 24rpx;
  background: #fff;
  color: #2f6b55;
  border: 1px solid #c5d9cf;
  font-size: 28rpx;
}
</style>
