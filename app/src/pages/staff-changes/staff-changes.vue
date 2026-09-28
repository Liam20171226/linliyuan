<template>
  <view class="page">
    <view class="tabs">
      <view class="tab" :class="{ on: tab === 'pending' }" @click="tab = 'pending'">
        待审核 {{ pendingList.length }}
      </view>
      <view class="tab" :class="{ on: tab === 'done' }" @click="tab = 'done'">
        已处理 {{ doneList.length }}
      </view>
    </view>

    <view v-if="loading" class="empty">加载中…</view>
    <template v-else-if="tab === 'pending'">
      <view v-for="item in pendingList" :key="item.id" class="card">
        <view class="row">
          <text class="cat">{{ item.changeTypeLabel }}</text>
          <text class="badge warn">{{ item.statusLabel }}</text>
        </view>
        <text class="desc">{{ item.summary }}</text>
        <text class="sub">房屋：{{ item.roomText }}</text>
        <text v-if="item.applyMessage" class="sub">备注：{{ item.applyMessage }}</text>
        <text class="time">{{ item.timeLabel }}</text>
        <view class="acts">
          <button class="ok" size="mini" @click="approve(item.id)">通过</button>
          <button class="no" size="mini" @click="openReject(item.id)">驳回</button>
        </view>
      </view>
      <view v-if="!pendingList.length" class="empty">没有待审核的变更申请</view>
    </template>
    <template v-else>
      <view v-for="item in doneList" :key="item.id" class="card">
        <view class="row">
          <text class="cat">{{ item.changeTypeLabel }}</text>
          <text class="badge" :class="item.status === 'APPROVED' ? 'ok' : 'mute'">
            {{ item.statusLabel }}
          </text>
        </view>
        <text class="desc">{{ item.summary }}</text>
        <text class="sub">房屋：{{ item.roomText }}</text>
        <text v-if="item.rejectReason" class="reply">驳回原因：{{ item.rejectReason }}</text>
        <text class="time">{{ item.timeLabel }}</text>
      </view>
      <view v-if="!doneList.length" class="empty">暂无已处理的变更</view>
    </template>

    <view v-if="rejectVisible" class="mask" @click="rejectVisible = false">
      <view class="dialog" @click.stop>
        <text class="dialog-title">驳回变更</text>
        <input v-model="rejectReason" class="dialog-input" placeholder="请填写驳回原因" />
        <view class="dialog-acts">
          <button class="ghost" size="mini" @click="rejectVisible = false">取消</button>
          <button class="no" size="mini" @click="confirmReject">确认驳回</button>
        </view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { request, type ApiError } from '../../utils/request'
import { formatDateTime } from '../../utils/format'

type Row = {
  id: number
  status: string
  statusLabel: string
  changeTypeLabel: string
  summary: string
  roomText: string
  timeLabel: string
  applyMessage?: string
  rejectReason?: string
}

const STATUS: Record<string, string> = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已拒绝',
}

const tab = ref<'pending' | 'done'>('pending')
const loading = ref(true)
const pendingList = ref<Row[]>([])
const doneList = ref<Row[]>([])
const rejectVisible = ref(false)
const rejectReason = ref('')
const rejectId = ref<number | null>(null)

function decorate(it: Record<string, unknown>): Row {
  const status = String(it.status || '')
  return {
    id: Number(it.id),
    status,
    statusLabel: STATUS[status] || status,
    changeTypeLabel: String(it.changeTypeLabel || it.changeType || '房屋变更'),
    summary: String(it.payloadSummary || it.changeTypeLabel || '房屋变更'),
    roomText: String(it.roomPath || it.roomNo || '—'),
    timeLabel: formatDateTime(it.createdAt),
    applyMessage: it.applyMessage != null ? String(it.applyMessage) : undefined,
    rejectReason: it.rejectReason != null ? String(it.rejectReason) : undefined,
  }
}

async function load() {
  loading.value = true
  try {
    const raw = await request<Array<Record<string, unknown>>>({
      url: '/staff/room-change-applications',
    })
    const all = (Array.isArray(raw) ? raw : []).map(decorate)
    pendingList.value = all.filter((it) => it.status === 'PENDING')
    doneList.value = all.filter((it) => it.status !== 'PENDING')
  } catch (e) {
    pendingList.value = []
    doneList.value = []
    uni.showToast({ title: (e as ApiError).message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

async function approve(id: number) {
  const res = await new Promise<boolean>((resolve) => {
    uni.showModal({
      title: '通过变更',
      content: '确认通过该人员/车辆变更申请？',
      success: (r) => resolve(!!r.confirm),
      fail: () => resolve(false),
    })
  })
  if (!res) return
  uni.showLoading({ title: '处理中', mask: true })
  try {
    await request({
      url: `/staff/room-change-applications/${id}/approve`,
      method: 'POST',
    })
    uni.hideLoading()
    uni.showToast({ title: '已通过' })
    load()
  } catch (e) {
    uni.hideLoading()
    uni.showToast({ title: (e as ApiError).message || '操作失败', icon: 'none' })
  }
}

function openReject(id: number) {
  rejectId.value = id
  rejectReason.value = ''
  rejectVisible.value = true
}

async function confirmReject() {
  const id = rejectId.value
  const reason = rejectReason.value.trim()
  if (!id) return
  if (!reason) {
    uni.showToast({ title: '请填写驳回原因', icon: 'none' })
    return
  }
  rejectVisible.value = false
  uni.showLoading({ title: '处理中', mask: true })
  try {
    await request({
      url: `/staff/room-change-applications/${id}/reject`,
      method: 'POST',
      data: { rejectReason: reason },
    })
    uni.hideLoading()
    uni.showToast({ title: '已驳回' })
    load()
  } catch (e) {
    uni.hideLoading()
    uni.showToast({ title: (e as ApiError).message || '操作失败', icon: 'none' })
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
.tabs {
  display: flex;
  gap: 12rpx;
  margin-bottom: 20rpx;
}
.tab {
  flex: 1;
  text-align: center;
  padding: 18rpx 0;
  font-size: 26rpx;
  color: #5c6f68;
  background: #fff;
  border: 1px solid #dfe8e3;
  border-radius: 10rpx;
}
.tab.on {
  color: #fff;
  background: #2f6b55;
  border-color: #2f6b55;
  font-weight: 600;
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
.cat {
  font-size: 28rpx;
  font-weight: 600;
  color: #1f4e3d;
}
.badge {
  font-size: 22rpx;
  padding: 4rpx 12rpx;
  border-radius: 8rpx;
}
.badge.warn {
  color: #b45309;
  background: #fef3c7;
}
.badge.ok {
  color: #166534;
  background: #dcfce7;
}
.badge.mute {
  color: #6b7280;
  background: #f3f4f6;
}
.desc {
  display: block;
  margin-top: 12rpx;
  font-size: 26rpx;
  color: #1f3d32;
  line-height: 1.45;
}
.sub,
.time,
.reply {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #8a9a93;
}
.reply {
  color: #c45c26;
}
.acts {
  display: flex;
  gap: 16rpx;
  margin-top: 20rpx;
}
.ok {
  background: #2f6b55;
  color: #fff;
}
.no {
  background: #fff;
  color: #c45c26;
  border: 1px solid #f0d4c4;
}
.mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 100;
  padding: 48rpx;
}
.dialog {
  width: 100%;
  background: #fff;
  border-radius: 16rpx;
  padding: 32rpx;
}
.dialog-title {
  display: block;
  font-size: 30rpx;
  font-weight: 600;
  color: #1f4e3d;
  margin-bottom: 20rpx;
}
.dialog-input {
  background: #f7faf8;
  border: 1px solid #dfe8e3;
  border-radius: 10rpx;
  padding: 18rpx 16rpx;
  font-size: 28rpx;
}
.dialog-acts {
  display: flex;
  justify-content: flex-end;
  gap: 16rpx;
  margin-top: 24rpx;
}
.ghost {
  background: #fff;
  color: #5c6f68;
  border: 1px solid #dfe8e3;
}
</style>
