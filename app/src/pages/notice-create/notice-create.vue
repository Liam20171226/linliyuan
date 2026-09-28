<template>
  <view class="page">
    <view class="card">
      <text class="label">标题</text>
      <input v-model="title" class="input" placeholder="通知标题" maxlength="80" />

      <text class="label">受众</text>
      <picker :range="visPicker" :value="visIndex" @change="onVis">
        <view class="picker">{{ visLabel }}</view>
      </picker>

      <text class="label">紧急度（可选）</text>
      <input v-model="urgency" class="input" type="number" placeholder="0 普通 · 2 紧急" />

      <text class="label">正文</text>
      <textarea v-model="content" class="area" placeholder="通知内容" :maxlength="5000" />
    </view>

    <button class="btn" :disabled="submitting" @click="submit">
      {{ editId ? '保存' : '发布' }}
    </button>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { request, type ApiError } from '../../utils/request'

const VIS_OPTS = [
  { value: 'RESIDENTS', label: '住户' },
  { value: 'OWNERS', label: '仅业主' },
]
const visPicker = VIS_OPTS.map((x) => x.label)

const editId = ref<number | null>(null)
const title = ref('')
const content = ref('')
const visibility = ref('RESIDENTS')
const visLabel = ref('住户')
const visIndex = ref(0)
const urgency = ref('0')
const submitting = ref(false)

function onVis(e: { detail: { value: string } }) {
  const idx = Number(e.detail.value)
  const opt = VIS_OPTS[idx]
  if (!opt) return
  visIndex.value = idx
  visibility.value = opt.value
  visLabel.value = opt.label
}

async function loadNotice(id: number) {
  uni.showLoading({ title: '加载中', mask: true })
  try {
    const item = await request<Record<string, unknown>>({ url: `/notices/${id}` })
    const vis = item && item.visibility === 'OWNERS' ? 'OWNERS' : 'RESIDENTS'
    title.value = String((item && item.title) || '')
    content.value = String((item && item.content) || '')
    visibility.value = vis
    visIndex.value = vis === 'OWNERS' ? 1 : 0
    visLabel.value = VIS_OPTS[visIndex.value].label
    urgency.value = String(item.urgency != null ? item.urgency : 0)
  } catch (err) {
    uni.showModal({
      title: '无法打开',
      content: (err as ApiError).message || '通知不存在或无权修改',
      showCancel: false,
      success: () => uni.navigateBack(),
    })
  } finally {
    uni.hideLoading()
  }
}

async function submit() {
  if (submitting.value) return
  const t = title.value.trim()
  const c = content.value.trim()
  if (!t) {
    uni.showToast({ title: '请填写标题', icon: 'none' })
    return
  }
  if (!c) {
    uni.showToast({ title: '请填写正文', icon: 'none' })
    return
  }
  const now = new Date()
  const effectiveAt = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}T00:00:00`
  const payload = {
    title: t,
    content: c,
    noticeType: 'OTHER',
    urgency: Number(urgency.value) || 0,
    visibility: visibility.value,
    showOnBanner: false,
    bannerOrder: 0,
    coverAttachmentId: 0,
    attachmentIds: [] as number[],
    effectiveAt,
  }
  submitting.value = true
  try {
    if (editId.value) {
      await request({
        url: `/committee/notices/${editId.value}`,
        method: 'PUT',
        data: payload,
      })
      uni.showToast({ title: '已保存' })
    } else {
      await request({
        url: '/committee/notices',
        method: 'POST',
        data: payload,
      })
      uni.showToast({ title: '已发布' })
    }
    setTimeout(() => {
      uni.navigateBack({
        fail: () =>
          uni.redirectTo({ url: '/pages/notice-manage/notice-manage?tab=mine' }),
      })
    }, 400)
  } catch (err) {
    uni.showModal({
      title: editId.value ? '保存失败' : '发布失败',
      content: (err as ApiError).message || '请稍后重试',
      showCancel: false,
    })
  } finally {
    submitting.value = false
  }
}

onLoad((q) => {
  const id = q?.id ? Number(q.id) : null
  if (id) {
    editId.value = id
    uni.setNavigationBarTitle({ title: '修改通知' })
    loadNotice(id)
  } else {
    uni.setNavigationBarTitle({ title: '发布通知' })
  }
})
</script>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 32rpx 48rpx;
  background: #f7faf8;
  box-sizing: border-box;
}
.card {
  background: #fff;
  border: 1px solid #dfe8e3;
  border-radius: 12rpx;
  padding: 24rpx;
  margin-bottom: 24rpx;
}
.label {
  display: block;
  margin-top: 16rpx;
  margin-bottom: 8rpx;
  font-size: 24rpx;
  color: #5c6f68;
}
.label:first-child {
  margin-top: 0;
}
.input,
.picker {
  background: #f7faf8;
  border: 1px solid #dfe8e3;
  border-radius: 10rpx;
  padding: 18rpx 16rpx;
  font-size: 28rpx;
  color: #1f3d32;
}
.area {
  width: 100%;
  min-height: 280rpx;
  background: #f7faf8;
  border: 1px solid #dfe8e3;
  border-radius: 10rpx;
  padding: 16rpx;
  font-size: 28rpx;
  box-sizing: border-box;
}
.btn {
  background: #2f6b55;
  color: #fff;
  font-size: 30rpx;
  border-radius: 10rpx;
}
</style>
