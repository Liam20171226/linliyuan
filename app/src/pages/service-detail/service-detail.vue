<template>
  <view class="page">
    <view v-if="loading" class="empty">加载中…</view>
    <template v-else-if="order">
      <view class="card">
        <view class="row">
          <text class="title">{{ order.category || (kind === 'repair' ? '报修' : '投诉') }}</text>
          <text class="status">{{ statusLabel }}</text>
        </view>
        <text class="body">{{ order.content || order.description || '—' }}</text>
        <text class="meta">{{ timeLabel }}</text>
      </view>

      <view v-if="photos.length" class="card">
        <text class="sec">现场照片</text>
        <view class="photos">
          <image
            v-for="p in photos"
            :key="p.id"
            class="thumb"
            :src="p.src"
            mode="aspectFill"
            @click="previewPhoto(p.src)"
          />
        </view>
      </view>

      <view v-if="timeline.length" class="card">
        <text class="sec">进度</text>
        <view v-for="(n, i) in timeline" :key="i" class="tl">
          <text class="tl-t">{{ n.timeLabel }}</text>
          <text class="tl-b">{{ n.content || n.action || n.remark || '—' }}</text>
          <text v-if="n.type === 'RATE'" class="tl-rate">
            评分 {{ n.ratingSatisfaction || n.rating || '—' }}
            · {{ n.ratingResolved === 1 || n.ratingResolved === true ? '已解决' : '未解决' }}
          </text>
        </view>
      </view>

      <view v-if="canRate" class="card">
        <text class="sec">满意度评价</text>
        <text class="label">是否已解决</text>
        <view class="toggle">
          <view class="chip" :class="{ on: rateResolved === 1 }" @click="rateResolved = 1">已解决</view>
          <view class="chip" :class="{ on: rateResolved === 0 }" @click="rateResolved = 0">未解决</view>
        </view>

        <text class="label">响应速度</text>
        <view class="stars">
          <text
            v-for="n in 5"
            :key="'r' + n"
            class="star"
            :class="{ on: rateResponse >= n }"
            @click="rateResponse = n"
          >★</text>
        </view>

        <text class="label">处理质量</text>
        <view class="stars">
          <text
            v-for="n in 5"
            :key="'h' + n"
            class="star"
            :class="{ on: rateHandling >= n }"
            @click="rateHandling = n"
          >★</text>
        </view>

        <text class="label">整体满意</text>
        <view class="stars">
          <text
            v-for="n in 5"
            :key="'s' + n"
            class="star"
            :class="{ on: rateSatisfaction >= n }"
            @click="rateSatisfaction = n"
          >★</text>
        </view>

        <text class="label">留言（可选）</text>
        <textarea
          v-model="rateComment"
          class="area"
          placeholder="补充评价"
          :maxlength="200"
        />
        <button class="btn" :disabled="rateSubmitting" @click="submitRate">提交评价</button>
      </view>
    </template>
    <view v-else class="empty">记录不存在</view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { request, type ApiError } from '../../utils/request'
import {
  repairStatusLabel,
  complaintStatusLabel,
  formatDateTime,
} from '../../utils/format'
import { attachmentUrl } from '../../utils/upload'

const id = ref('')
const kind = ref<'repair' | 'complaint'>('repair')
const loading = ref(true)
const order = ref<Record<string, unknown> | null>(null)
const timeline = ref<Array<Record<string, unknown>>>([])
const photos = ref<Array<{ id: number; src: string }>>([])
const isPraise = ref(false)
const hasTimelineRate = ref(false)

const rateResolved = ref(1)
const rateResponse = ref(0)
const rateHandling = ref(0)
const rateSatisfaction = ref(0)
const rateComment = ref('')
const rateSubmitting = ref(false)

const statusLabel = computed(() =>
  kind.value === 'repair'
    ? repairStatusLabel(order.value?.status)
    : complaintStatusLabel(order.value?.status),
)

const timeLabel = computed(() => {
  const s = formatDateTime(order.value?.createdAt)
  return s ? s.slice(0, 16) : ''
})

const canRate = computed(
  () =>
    !!order.value &&
    order.value.status === 'DONE_WAIT_RATE' &&
    !isPraise.value &&
    !hasTimelineRate.value,
)

function shortTime(v: unknown) {
  const s = formatDateTime(v)
  return s ? s.slice(0, 16) : ''
}

async function load() {
  if (!id.value) return
  loading.value = true
  try {
    const url =
      kind.value === 'repair'
        ? `/resident/repairs/${id.value}`
        : `/resident/complaints/${id.value}`
    const d = await request<{
      order?: Record<string, unknown>
      timeline?: Array<Record<string, unknown>>
    }>({ url })
    const ord = d.order || {}
    const tl = (d.timeline || []).map((n) => ({
      ...n,
      timeLabel: shortTime(n.time || n.createdAt),
    }))
    order.value = ord
    timeline.value = tl
    hasTimelineRate.value = tl.some((n) => n.type === 'RATE')
    isPraise.value = ord.category === '表扬'
    uni.setNavigationBarTitle({
      title: kind.value === 'repair' ? '报修详情' : '记录详情',
    })
    await loadPhotos()
  } catch (e) {
    order.value = null
    uni.showToast({ title: (e as ApiError).message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

async function loadPhotos() {
  const bizType = kind.value === 'repair' ? 'REPAIR' : 'COMPLAINT'
  try {
    const list = await request<Array<{ id?: number }>>({
      url: '/attachments',
      data: { bizType, bizId: Number(id.value) },
    })
    photos.value = (list || [])
      .filter((a) => a.id != null)
      .map((a) => ({
        id: Number(a.id),
        src: attachmentUrl(Number(a.id)),
      }))
  } catch {
    photos.value = []
  }
}

function previewPhoto(src: string) {
  const urls = photos.value.map((p) => p.src)
  if (!urls.length) return
  uni.previewImage({ current: src || urls[0], urls })
}

async function submitRate() {
  if (rateSubmitting.value || !id.value) return
  if (isPraise.value) {
    uni.showToast({ title: '表扬不参与满意度评价', icon: 'none' })
    return
  }
  if (!rateResponse.value || !rateHandling.value || !rateSatisfaction.value) {
    uni.showToast({ title: '请完成三项评分', icon: 'none' })
    return
  }
  rateSubmitting.value = true
  try {
    const rateUrl =
      kind.value === 'repair'
        ? `/resident/repairs/${id.value}/rate`
        : `/resident/complaints/${id.value}/rate`
    await request({
      url: rateUrl,
      method: 'POST',
      data: {
        rating: rateSatisfaction.value,
        comment: rateComment.value.trim() || null,
        resolved: rateResolved.value,
        responseScore: rateResponse.value,
        handlingScore: rateHandling.value,
        satisfactionScore: rateSatisfaction.value,
      },
    })
    uni.showToast({ title: '已评价' })
    try {
      const todoData = await request<{ list?: Array<Record<string, unknown>> }>({
        url: '/todos',
        data: { status: 'OPEN' },
      })
      const list = (todoData && todoData.list) || []
      const hit = list.find(
        (t) =>
          String(t.bizId) === String(id.value) &&
          (t.todoType === 'REPAIR_RATE' || t.todoType === 'COMPLAINT_RATE'),
      )
      if (hit) {
        await request({ url: `/todos/${hit.id}/done`, method: 'POST', data: {} })
      }
    } catch {
      /* ignore */
    }
    await load()
  } catch (e) {
    uni.showModal({
      title: '评价失败',
      content: (e as ApiError).message || '请稍后重试',
      showCancel: false,
    })
  } finally {
    rateSubmitting.value = false
  }
}

onLoad((q) => {
  id.value = q?.id ? String(q.id) : ''
  kind.value = q?.kind === 'complaint' ? 'complaint' : 'repair'
})

onShow(() => {
  if (id.value) load()
})
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
  justify-content: space-between;
  gap: 12rpx;
}
.title {
  font-size: 30rpx;
  font-weight: 700;
  color: #1f4e3d;
}
.status {
  font-size: 24rpx;
  color: #2f6b55;
}
.body {
  display: block;
  margin: 16rpx 0;
  font-size: 28rpx;
  color: #3d5249;
  line-height: 1.5;
}
.meta {
  display: block;
  font-size: 24rpx;
  color: #8a9a93;
}
.sec {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: #1f4e3d;
  margin-bottom: 16rpx;
}
.photos {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
}
.thumb {
  width: 160rpx;
  height: 160rpx;
  border-radius: 10rpx;
  background: #e8f2ec;
}
.tl {
  padding: 12rpx 0;
  border-top: 1px solid #eef3f0;
}
.tl-t {
  display: block;
  font-size: 22rpx;
  color: #8a9a93;
}
.tl-b {
  display: block;
  margin-top: 4rpx;
  font-size: 26rpx;
  color: #3d5249;
}
.tl-rate {
  display: block;
  margin-top: 4rpx;
  font-size: 22rpx;
  color: #2f6b55;
}
.label {
  display: block;
  margin: 16rpx 0 8rpx;
  font-size: 24rpx;
  color: #5c6f68;
}
.toggle {
  display: flex;
  gap: 16rpx;
}
.chip {
  padding: 12rpx 28rpx;
  border: 1px solid #dfe8e3;
  border-radius: 8rpx;
  font-size: 26rpx;
  color: #5c6f68;
  background: #fff;
}
.chip.on {
  border-color: #2f6b55;
  color: #1f4e3d;
  background: #e8f2ec;
  font-weight: 600;
}
.stars {
  display: flex;
  gap: 12rpx;
}
.star {
  font-size: 44rpx;
  color: #dfe8e3;
  line-height: 1;
}
.star.on {
  color: #c9a227;
}
.area {
  width: 100%;
  min-height: 140rpx;
  background: #f7faf8;
  border: 1px solid #dfe8e3;
  border-radius: 10rpx;
  padding: 16rpx;
  font-size: 28rpx;
  box-sizing: border-box;
  margin-bottom: 16rpx;
}
.btn {
  background: #2f6b55;
  color: #fff;
  font-size: 28rpx;
  border-radius: 10rpx;
}
</style>
