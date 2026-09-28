<template>
  <view class="page">
    <text class="hint">预缴由物业登记；覆盖项后续不再出账。本页仅查看，支付暂未开放。</text>
    <view v-if="loading" class="empty">加载中…</view>
    <template v-else>
      <view v-for="item in plans" :key="item.id" class="card">
        <view class="row">
          <text class="title">协议 #{{ item.id }}</text>
          <text class="status">{{ item.status || '—' }}</text>
        </view>
        <text class="meta">
          原价 {{ item.listAmount }} · 实付 {{ item.cashAmount }} · 优惠 {{ item.discountAmount }}
        </text>
        <text class="meta">账期：{{ item.billMonths || '—' }}</text>
        <text class="meta">费项：{{ item.feeCategories || '—' }}</text>
      </view>
      <view v-if="!plans.length" class="empty">暂无预缴协议</view>
    </template>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { request, type ApiError } from '../../utils/request'

type Plan = {
  id: number
  status?: string
  listAmount?: string | number
  cashAmount?: string | number
  discountAmount?: string | number
  billMonths?: string
  feeCategories?: string
}

const loading = ref(true)
const plans = ref<Plan[]>([])

async function load() {
  loading.value = true
  try {
    const data = await request<{ plans?: Plan[] }>({
      url: '/resident/prepaid/plans',
    })
    plans.value = (data && data.plans) || []
  } catch (e) {
    plans.value = []
    uni.showToast({ title: (e as ApiError).message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
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
.hint {
  display: block;
  font-size: 24rpx;
  color: #7a8c85;
  line-height: 1.45;
  margin-bottom: 20rpx;
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
.meta {
  display: block;
  margin-top: 10rpx;
  font-size: 24rpx;
  color: #8a9a93;
}
</style>
