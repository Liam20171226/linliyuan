<template>
  <view class="page">
    <view v-if="loading" class="empty">加载中…</view>
    <view v-else-if="error" class="empty">
      <text class="err-title">无法打开</text>
      <text class="err-desc">{{ error }}</text>
    </view>
    <view v-else class="article">
      <text v-if="title" class="title">{{ title }}</text>
      <rich-text v-if="contentHtml" class="body" :nodes="contentHtml" />
      <text v-else class="empty-body">暂无介绍内容</text>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { request, type ApiError } from '../../utils/request'

const loading = ref(true)
const error = ref('')
const title = ref('')
const contentHtml = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    let raw: { title?: string; content?: string }
    try {
      raw = await request({ url: '/about-us' })
    } catch {
      raw = await request({ url: '/about-us', auth: false })
    }
    title.value = String((raw && raw.title) || '')
    contentHtml.value = String((raw && raw.content) || '')
    if (title.value) {
      uni.setNavigationBarTitle({ title: title.value })
    }
  } catch (e) {
    error.value = (e as ApiError).message || '加载失败'
    title.value = ''
    contentHtml.value = ''
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
  padding: 32rpx;
  background: #f7faf8;
  box-sizing: border-box;
}
.empty {
  padding: 80rpx 24rpx;
  text-align: center;
}
.err-title {
  display: block;
  font-size: 30rpx;
  font-weight: 600;
  color: #1f4e3d;
}
.err-desc {
  display: block;
  margin-top: 12rpx;
  font-size: 26rpx;
  color: #8a9a93;
}
.article {
  background: #fff;
  border: 1px solid #dfe8e3;
  border-radius: 12rpx;
  padding: 32rpx 28rpx;
}
.title {
  display: block;
  font-size: 36rpx;
  font-weight: 700;
  color: #1f4e3d;
  margin-bottom: 24rpx;
}
.body {
  display: block;
  font-size: 28rpx;
  color: #1f3d32;
  line-height: 1.65;
  word-break: break-word;
}
.empty-body {
  font-size: 26rpx;
  color: #8a9a93;
}
</style>
