<template>
  <view class="page">
    <view class="search">
      <input
        v-model="keyword"
        class="input"
        placeholder="按姓名筛选"
        confirm-type="search"
        @confirm="load"
      />
      <button class="search-btn" size="mini" @click="load">搜索</button>
    </view>

    <text class="total">共 {{ displayList.length }} 人</text>

    <view v-if="loading" class="empty">加载中…</view>
    <view v-else-if="!displayList.length" class="empty">暂无住户</view>
    <view v-for="it in displayList" :key="it.id" class="card">
      <view class="row">
        <text class="name">{{ it.name }}</text>
        <text class="role">{{ it.role }}</text>
      </view>
      <text class="sub">{{ it.mobile }}</text>
      <text class="path">{{ it.roomPath }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { getCurrentContext, getToken, mustChangePassword } from '../../utils/auth'
import { request, type ApiError } from '../../utils/request'
import { roleLabel } from '../../utils/format'

type Row = {
  id: number | string
  name: string
  mobile: string
  role: string
  roomPath: string
  realName: string
}

const keyword = ref('')
const loading = ref(false)
const displayList = ref<Row[]>([])

function guard() {
  if (!getToken()) {
    uni.reLaunch({ url: '/pages/login/login' })
    return false
  }
  if (mustChangePassword()) {
    uni.reLaunch({ url: '/pages/change-password/change-password' })
    return false
  }
  return true
}

function roomPathOf(raw: Record<string, unknown>) {
  if (raw.roomPath) return String(raw.roomPath)
  if (raw.address) return String(raw.address)
  const parts = [raw.buildingName, raw.unitName, raw.floorName, raw.roomNo]
    .filter((x) => x != null && String(x) !== '')
    .map(String)
  return parts.join(' · ') || '—'
}

function personRole(raw: Record<string, unknown>) {
  const parts: string[] = []
  const resident = roleLabel(raw.residentRole)
  if (resident) parts.push(resident)
  const ct = roleLabel(raw.committeeTitle)
  if (ct) parts.push('业委会' + ct)
  return parts.join(' · ') || '住户'
}

async function fetchAll(url: string, useServerKeyword: boolean) {
  const pageSize = 100
  let page = 1
  let total = 0
  const all: Record<string, unknown>[] = []
  const kw = keyword.value.trim()
  for (;;) {
    const params: Record<string, unknown> = { page, pageSize }
    if (useServerKeyword && kw) params.realName = kw
    const data = await request<{ list?: Record<string, unknown>[]; total?: number }>({
      url,
      data: params,
    })
    const chunk = (data && data.list) || []
    total = data && data.total != null ? Number(data.total) : all.length + chunk.length
    all.push(...chunk)
    if (!chunk.length || all.length >= total || chunk.length < pageSize) break
    page += 1
    if (page > 50) break
  }
  return all
}

async function load() {
  loading.value = true
  try {
    const ctx = getCurrentContext()
    const idType = ctx?.identityType || ''
    const url = idType === 'STAFF' ? '/staff/occupants' : '/committee/occupants'
    const raw = await fetchAll(url, true)
    displayList.value = raw.map((it) => ({
      id: (it.id as number) ?? `${it.roomId}-${it.mobile}`,
      name: String(it.realName || '未填姓名'),
      realName: String(it.realName || ''),
      mobile: String(it.mobile || '无手机'),
      role: personRole(it),
      roomPath: roomPathOf(it),
    }))
  } catch (e) {
    displayList.value = []
    uni.showToast({ title: (e as ApiError).message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

onShow(() => {
  if (!guard()) return
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
.search {
  display: flex;
  gap: 12rpx;
  margin-bottom: 12rpx;
}
.input {
  flex: 1;
  background: #fff;
  border: 1px solid #dfe8e3;
  border-radius: 10rpx;
  padding: 16rpx;
  font-size: 28rpx;
}
.search-btn {
  background: #2f6b55;
  color: #fff;
  font-size: 26rpx;
}
.total {
  display: block;
  margin-bottom: 16rpx;
  font-size: 24rpx;
  color: #8a9a93;
}
.card {
  background: #fff;
  border: 1px solid #dfe8e3;
  border-radius: 12rpx;
  padding: 22rpx 24rpx;
  margin-bottom: 14rpx;
}
.row {
  display: flex;
  justify-content: space-between;
  gap: 12rpx;
}
.name {
  font-size: 30rpx;
  font-weight: 600;
  color: #1f3d32;
}
.role {
  font-size: 22rpx;
  color: #2f6b55;
}
.sub {
  display: block;
  margin-top: 8rpx;
  font-size: 26rpx;
  color: #5c6f68;
}
.path {
  display: block;
  margin-top: 4rpx;
  font-size: 24rpx;
  color: #8a9a93;
}
.empty {
  padding: 48rpx 0;
  text-align: center;
  font-size: 28rpx;
  color: #8a9a93;
}
</style>
