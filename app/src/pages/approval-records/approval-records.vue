<template>
  <view class="page">
    <view class="tabs">
      <view class="tab" :class="{ on: tab === 'auth' }" @click="tab = 'auth'">住户认证</view>
      <view class="tab" :class="{ on: tab === 'change' }" @click="tab = 'change'">房屋变更</view>
    </view>

    <view v-if="loading" class="empty">加载中…</view>
    <view v-else-if="!rows.length" class="empty">暂无记录</view>
    <view v-for="item in rows" :key="item.id" class="card">
      <view class="row">
        <text class="title">{{ item.title }}</text>
        <text class="status">{{ item.statusLabel }}</text>
      </view>
      <text class="meta">{{ item.roomLabel }}</text>
      <text v-if="item.roleLabel" class="meta">角色 {{ item.roleLabel }}</text>
      <text class="meta">{{ item.applyTimeLabel }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { request, type ApiError } from '../../utils/request'
import { roleLabel, formatDateTime } from '../../utils/format'

const STATUS: Record<string, string> = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已拒绝',
}

type Row = {
  id: number
  title: string
  statusLabel: string
  roomLabel: string
  roleLabel?: string
  applyTimeLabel: string
}

const tab = ref<'auth' | 'change'>('auth')
const loading = ref(false)
const authList = ref<Row[]>([])
const changeList = ref<Row[]>([])
const rows = computed(() => (tab.value === 'auth' ? authList.value : changeList.value))

async function load() {
  loading.value = true
  try {
    const [authRaw, changeRaw] = await Promise.all([
      request<Array<Record<string, unknown>> | { list?: Array<Record<string, unknown>> }>({
        url: '/resident/auth-applications/mine',
      }).catch(() => []),
      request<Array<Record<string, unknown>> | { list?: Array<Record<string, unknown>> }>({
        url: '/resident/room-change-applications/mine',
      }).catch(() => []),
    ])
    const authArr = Array.isArray(authRaw)
      ? authRaw
      : ((authRaw && (authRaw as { list?: Array<Record<string, unknown>> }).list) || [])
    const changeArr = Array.isArray(changeRaw)
      ? changeRaw
      : ((changeRaw && (changeRaw as { list?: Array<Record<string, unknown>> }).list) || [])

    authList.value = authArr.map((it) => ({
      id: Number(it.id),
      title: '认证申请 #' + it.id,
      statusLabel: STATUS[String(it.status)] || String(it.status || ''),
      roleLabel: roleLabel(it.applyRole),
      roomLabel: String(it.roomPath || it.roomNo || '—'),
      applyTimeLabel: formatDateTime(it.createdAt),
    }))
    changeList.value = changeArr.map((it) => ({
      id: Number(it.id),
      title: '变更申请 #' + it.id,
      statusLabel: STATUS[String(it.status)] || String(it.status || ''),
      roomLabel: String(it.roomPath || it.roomNo || '—'),
      applyTimeLabel: formatDateTime(it.createdAt),
    }))
  } catch (e) {
    authList.value = []
    changeList.value = []
    uni.showToast({ title: (e as ApiError).message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

onLoad((q) => {
  if (q?.tab === 'change') tab.value = 'change'
})

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
