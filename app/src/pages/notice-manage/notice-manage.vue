<template>
  <view class="page">
    <view class="tabs">
      <view class="tab" :class="{ on: tab === 'mine' }" @click="switchTab('mine')">我发布的</view>
      <view class="tab" :class="{ on: tab === 'all' }" @click="switchTab('all')">全部</view>
    </view>

    <button class="create" @click="goCreate">发布通知</button>

    <view v-if="loading" class="empty">加载中…</view>
    <view v-else-if="!list.length" class="empty">暂无通知</view>
    <view v-for="item in list" :key="item.id" class="card">
      <view class="row" @click="openDetail(item.id)">
        <text class="title">{{ item.title }}</text>
        <text class="vis">{{ item.visLabel }}</text>
      </view>
      <text class="meta">{{ item.timeLabel }}</text>
      <view v-if="item.canEdit" class="ops">
        <text class="op" @click="openEdit(item.id)">编辑</text>
        <text class="op danger" @click="remove(item.id)">删除</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { request, type ApiError } from '../../utils/request'
import { formatDateTime } from '../../utils/format'

const VIS_LABEL: Record<string, string> = {
  RESIDENTS: '住户',
  OWNERS: '仅业主',
}

type Row = {
  id: number
  title: string
  visLabel: string
  timeLabel: string
  canEdit: boolean
}

const tab = ref<'mine' | 'all'>('mine')
const loading = ref(true)
const list = ref<Row[]>([])
const userId = ref<number | null>(null)

function switchTab(t: 'mine' | 'all') {
  if (tab.value === t) return
  tab.value = t
  uni.setNavigationBarTitle({
    title: t === 'all' ? '业委会全部通知' : '我发布的通知',
  })
  load()
}

async function load() {
  loading.value = true
  try {
    if (userId.value == null) {
      try {
        const packed = await request<{
          profile?: { id?: number }
          user?: { id?: number }
        }>({ url: '/auth/identities' })
        userId.value =
          (packed && packed.profile && packed.profile.id) ||
          (packed && packed.user && packed.user.id) ||
          null
      } catch {
        /* ignore */
      }
    }
    const url = tab.value === 'mine' ? '/committee/notices/mine' : '/committee/notices'
    const data = await request<{ list?: Array<Record<string, unknown>> }>({
      url,
      data: { page: 1, pageSize: 50 },
    })
    list.value = ((data && data.list) || [])
      .filter((it) => !it.creatorIdentity || it.creatorIdentity === 'COMMITTEE')
      .map((it) => {
        const mine =
          userId.value != null && Number(it.createdBy) === Number(userId.value)
        const t = formatDateTime(it.effectiveAt || it.createdAt)
        return {
          id: Number(it.id),
          title: String(it.title || '通知'),
          visLabel: VIS_LABEL[String(it.visibility)] || String(it.visibility || ''),
          timeLabel: t ? t.slice(0, 16) : '',
          canEdit: mine,
        }
      })
  } catch (e) {
    list.value = []
    uni.showToast({ title: (e as ApiError).message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

function goCreate() {
  uni.navigateTo({ url: '/pages/notice-create/notice-create' })
}

function openEdit(id: number) {
  uni.navigateTo({ url: `/pages/notice-create/notice-create?id=${id}` })
}

function openDetail(id: number) {
  if (!id) return
  uni.navigateTo({ url: `/pages/notice-detail/notice-detail?id=${id}` })
}

function remove(id: number) {
  uni.showModal({
    title: '删除通知',
    content: '确定删除该通知？',
    success: async (res) => {
      if (!res.confirm) return
      uni.showLoading({ title: '删除中', mask: true })
      try {
        await request({ url: `/committee/notices/${id}`, method: 'DELETE' })
        uni.showToast({ title: '已删除' })
        load()
      } catch (err) {
        uni.showModal({
          title: '删除失败',
          content: (err as ApiError).message || '请稍后重试',
          showCancel: false,
        })
      } finally {
        uni.hideLoading()
      }
    },
  })
}

onLoad((q) => {
  tab.value = q?.tab === 'all' ? 'all' : 'mine'
  uni.setNavigationBarTitle({
    title: tab.value === 'all' ? '业委会全部通知' : '我发布的通知',
  })
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
  margin: 0 -32rpx 16rpx;
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
.create {
  background: #2f6b55;
  color: #fff;
  font-size: 28rpx;
  border-radius: 10rpx;
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
  gap: 12rpx;
}
.title {
  font-size: 28rpx;
  font-weight: 600;
  color: #1f4e3d;
}
.vis {
  font-size: 22rpx;
  color: #8a9a93;
  flex-shrink: 0;
}
.meta {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #8a9a93;
}
.ops {
  display: flex;
  gap: 28rpx;
  margin-top: 14rpx;
}
.op {
  font-size: 26rpx;
  color: #2f6b55;
}
.op.danger {
  color: #b45309;
}
</style>
