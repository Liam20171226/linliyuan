<template>
  <view class="page">
    <view class="intro">
      <text class="intro-title">报事报修</text>
      <text class="intro-sub">选择类型后填写并提交</text>
    </view>

    <view
      v-for="item in entries"
      :key="item.key"
      class="entry"
      @click="open(item.category)"
    >
      <text class="entry-name">{{ item.name }}</text>
      <text class="arrow">›</text>
    </view>

    <button class="link" @click="goRecords">我的服务记录</button>
  </view>
</template>

<script setup lang="ts">
const entries = [
  { key: 'public', name: '公区报障', category: '公区报障' },
  { key: 'indoor', name: '室内维修', category: '室内维修' },
  { key: 'complaint', name: '投诉', category: '投诉' },
  { key: 'praise', name: '表扬', category: '表扬' },
  { key: 'advice', name: '咨询建议', category: '咨询建议' },
]

function open(category: string) {
  uni.navigateTo({
    url: `/pages/service-submit/service-submit?category=${encodeURIComponent(category)}`,
  })
}

function goRecords() {
  uni.navigateTo({ url: '/pages/service-records/service-records' })
}
</script>

<style scoped>
.page {
  min-height: 100vh;
  padding: 32rpx;
  background: #f7faf8;
  box-sizing: border-box;
}
.intro {
  margin-bottom: 28rpx;
}
.intro-title {
  display: block;
  font-size: 36rpx;
  font-weight: 700;
  color: #1f4e3d;
}
.intro-sub {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #8a9a93;
}
.entry {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #fff;
  border: 1px solid #dfe8e3;
  border-radius: 12rpx;
  padding: 28rpx 24rpx;
  margin-bottom: 16rpx;
}
.entry-name {
  font-size: 30rpx;
  color: #1f4e3d;
  font-weight: 600;
}
.arrow {
  font-size: 36rpx;
  color: #a0b0a8;
}
.link {
  margin-top: 32rpx;
  background: #fff;
  color: #2f6b55;
  border: 1px solid #c5d9cf;
  font-size: 28rpx;
}
</style>
