<template>
  <view class="page">
    <view v-if="loading" class="empty">加载中…</view>
    <view v-else-if="error" class="empty">{{ error }}</view>
    <view v-else class="body">
      <text class="title">{{ detail.title }}</text>
      <text class="meta">{{ detail.sourceLabel }} · {{ detail.timeLabel }}</text>
      <text v-if="!detail.html" class="content">{{ detail.plain }}</text>
      <rich-text v-else class="rich" :nodes="detail.html" />
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { request, type ApiError } from '../../utils/request'
import { formatDateTime, stripHtml } from '../../utils/format'

const SOURCE_LABEL: Record<string, string> = {
  STAFF: '物业',
  COMMITTEE: '业委会',
  PLATFORM: '平台',
}

const loading = ref(true)
const error = ref('')
const detail = ref({
  title: '',
  sourceLabel: '',
  timeLabel: '',
  plain: '',
  html: '',
})

let noticeId = 0

onLoad((q) => {
  noticeId = q?.id ? Number(q.id) : 0
  if (!noticeId) {
    loading.value = false
    error.value = '公告不存在'
    return
  }
  load()
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const raw = await request<Record<string, unknown>>({
      url: `/notices/${noticeId}`,
    })
    const content = String(raw.content || '')
    const hasHtml = /<[a-z][\s\S]*>/i.test(content)
    const time = formatDateTime(raw.effectiveAt || raw.createdAt)
    detail.value = {
      title: String(raw.title || '公告'),
      sourceLabel: SOURCE_LABEL[String(raw.creatorIdentity || '')] || '物业',
      timeLabel: time ? time.slice(0, 16) : '',
      plain: stripHtml(content),
      html: hasHtml ? content : '',
    }
  } catch (e) {
    error.value = (e as ApiError).message || '加载失败'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.page {
  min-height: 100vh;
  padding: 32rpx;
  background: #f7faf8;
  box-sizing: border-box;
}
.empty {
  padding: 80rpx 0;
  text-align: center;
  color: #8a9a93;
  font-size: 28rpx;
}
.title {
  display: block;
  font-size: 36rpx;
  font-weight: 700;
  color: #1f4e3d;
  line-height: 1.4;
}
.meta {
  display: block;
  margin: 16rpx 0 28rpx;
  font-size: 24rpx;
  color: #8a9a93;
}
.content {
  display: block;
  font-size: 28rpx;
  color: #2a3d35;
  line-height: 1.7;
  white-space: pre-wrap;
}
.rich {
  margin-top: 20rpx;
  font-size: 28rpx;
  color: #2a3d35;
}
</style>
