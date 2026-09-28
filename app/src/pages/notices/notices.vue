<template>
  <view class="page">
    <view v-if="loading" class="empty">加载中…</view>
    <view v-else-if="!list.length" class="empty">暂无公告</view>
    <view
      v-for="item in list"
      :key="item.id"
      class="card"
      @click="open(item.id)"
    >
      <view class="row">
        <text class="title">{{ item.title }}</text>
        <text v-if="item.urgent" class="badge">紧急</text>
      </view>
      <text class="meta">{{ item.timeLabel }}</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { request, type ApiError } from '../../utils/request'
import { formatDateTime } from '../../utils/format'

type NoticeItem = {
  id: number
  title: string
  timeLabel: string
  urgent: boolean
}

const loading = ref(true)
const list = ref<NoticeItem[]>([])
const source = ref('')

onLoad((q) => {
  const s = q?.source === 'COMMITTEE' ? 'COMMITTEE' : q?.source === 'STAFF' ? 'STAFF' : ''
  source.value = s
  const title = s === 'COMMITTEE' ? '业委通知' : s === 'STAFF' ? '物业公告' : '公告'
  uni.setNavigationBarTitle({ title })
})

onShow(() => {
  load()
})

async function load() {
  loading.value = true
  try {
    const data: Record<string, unknown> = { page: 1, pageSize: 50 }
    if (source.value) data.creatorIdentity = source.value
    const res = await request<{ list?: Array<Record<string, unknown>> }>({
      url: '/notices',
      data,
    })
    list.value = ((res && res.list) || []).map((it) => {
      const t = formatDateTime(it.effectiveAt || it.createdAt)
      return {
        id: Number(it.id),
        title: String(it.title || '公告'),
        timeLabel: t ? t.slice(0, 10) : '',
        urgent: Number(it.urgency) >= 2,
      }
    })
  } catch (e) {
    list.value = []
    uni.showToast({ title: (e as ApiError).message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

function open(id: number) {
  if (!id) return
  uni.navigateTo({ url: `/pages/notice-detail/notice-detail?id=${id}` })
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
  align-items: flex-start;
  gap: 12rpx;
}
.title {
  flex: 1;
  font-size: 30rpx;
  font-weight: 600;
  color: #1f4e3d;
  line-height: 1.4;
}
.badge {
  font-size: 20rpx;
  color: #c45c26;
  background: #fff3eb;
  padding: 4rpx 10rpx;
  border-radius: 6rpx;
}
.meta {
  display: block;
  margin-top: 10rpx;
  font-size: 24rpx;
  color: #8a9a93;
}
</style>
