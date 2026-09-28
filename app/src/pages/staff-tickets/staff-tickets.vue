<template>
  <view class="page">
    <view class="tabs">
      <view
        class="tab"
        :class="{ on: tab === 'open' }"
        @click="tab = 'open'"
      >待办</view>
      <view
        class="tab"
        :class="{ on: tab === 'done' }"
        @click="tab = 'done'"
      >已完成</view>
    </view>

    <view v-if="loading" class="empty">加载中…</view>
    <view v-else-if="!rows.length" class="empty">暂无工单</view>
    <view
      v-for="item in rows"
      :key="item.id + '-' + item.kind"
      class="card"
      @click="open(item)"
    >
      <view class="row">
        <text class="title">{{ item.category }}</text>
        <text class="status">{{ item.statusLabel }}</text>
      </view>
      <text v-if="item.desc" class="desc">{{ item.desc }}</text>
      <view class="meta-row">
        <text class="meta">{{ item.location || '—' }}</text>
        <text class="meta">{{ item.time }}</text>
      </view>
      <text v-if="item.pendingClaim" class="tag">待接单</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { request, type ApiError } from '../../utils/request'
import {
  repairStatusLabel,
  complaintStatusLabel,
  formatDateTime,
} from '../../utils/format'

type Row = {
  id: number
  kind: 'repair' | 'complaint'
  category: string
  statusLabel: string
  desc: string
  location: string
  pendingClaim: boolean
  time: string
  sortKey: string
}

const tab = ref<'open' | 'done'>('open')
const loading = ref(true)
const openList = ref<Row[]>([])
const doneList = ref<Row[]>([])

const rows = computed(() => (tab.value === 'open' ? openList.value : doneList.value))

function byTimeDesc(a: Row, b: Row) {
  if (a.sortKey === b.sortKey) return Number(b.id) - Number(a.id)
  return a.sortKey < b.sortKey ? 1 : -1
}

function mapList(list: Array<Record<string, unknown>> | undefined): Row[] {
  return (list || []).map((it) => {
    const kind = it.kind === 'COMPLAINT' ? 'complaint' : 'repair'
    const statusLabel =
      kind === 'complaint'
        ? complaintStatusLabel(it.status)
        : repairStatusLabel(it.status)
    const sortKey = formatDateTime(it.createdAt) || ''
    return {
      id: Number(it.id),
      kind,
      category: String(it.category || (kind === 'complaint' ? '投诉' : '报修')),
      statusLabel,
      desc: String(it.content || ''),
      location: String(it.location || ''),
      pendingClaim:
        !it.assigneeUserId && !!it.assigneeRole && it.status === 'ASSIGNED',
      time: sortKey ? sortKey.slice(0, 16) : '',
      sortKey,
    }
  })
}

async function load() {
  loading.value = true
  try {
    const [mine, pool, done] = await Promise.all([
      request<{ list?: Array<Record<string, unknown>> }>({
        url: '/staff/repairs/mine',
        data: { page: 1, pageSize: 50 },
      }).catch(() => null),
      request<{ list?: Array<Record<string, unknown>> }>({
        url: '/staff/repairs/pool',
        data: { page: 1, pageSize: 50 },
      }).catch(() => null),
      request<{ list?: Array<Record<string, unknown>> }>({
        url: '/staff/repairs/done',
        data: { page: 1, pageSize: 50 },
      }).catch(() => null),
    ])
    const map: Record<number, Record<string, unknown>> = {}
    ;[(mine && mine.list) || [], (pool && pool.list) || []].forEach((arr) => {
      arr.forEach((it) => {
        if (it && it.id != null) map[Number(it.id)] = it
      })
    })
    openList.value = mapList(Object.values(map)).sort(byTimeDesc)
    doneList.value = mapList((done && done.list) || []).sort(byTimeDesc)
  } catch (e) {
    openList.value = []
    doneList.value = []
    uni.showToast({ title: (e as ApiError).message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

function open(item: Row) {
  uni.navigateTo({
    url: `/pages/staff-ticket-detail/staff-ticket-detail?id=${item.id}&kind=${item.kind}`,
  })
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
  gap: 0;
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
.desc {
  display: block;
  margin-top: 10rpx;
  font-size: 26rpx;
  color: #3d5249;
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.meta-row {
  display: flex;
  justify-content: space-between;
  margin-top: 10rpx;
}
.meta {
  font-size: 22rpx;
  color: #8a9a93;
}
.tag {
  display: inline-block;
  margin-top: 10rpx;
  font-size: 22rpx;
  color: #b45309;
  background: #fef3c7;
  padding: 4rpx 12rpx;
  border-radius: 6rpx;
}
</style>
