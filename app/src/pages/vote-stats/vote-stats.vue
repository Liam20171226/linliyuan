<template>
  <view class="page">
    <view v-if="loading" class="empty">加载中…</view>
    <view v-else-if="error" class="empty">{{ error }}</view>
    <template v-else>
      <view class="head">
        <text class="title">{{ title }}</text>
        <text v-if="statusLabel" class="meta">{{ statusLabel }}</text>
        <text v-if="timeLabel" class="meta">{{ timeLabel }}</text>
        <text class="meta">合计票数 {{ totalBallots }}</text>
      </view>

      <view class="card">
        <text class="block-title">选项统计</text>
        <view v-for="o in options" :key="o.optionId" class="opt">
          <view class="row">
            <text class="opt-text">{{ o.optionText }}</text>
            <text class="opt-n">{{ o.votes }}（{{ o.pct }}%）</text>
          </view>
          <view class="bar">
            <view class="fill" :style="{ width: o.pct + '%' }" />
          </view>
        </view>
        <view v-if="!options.length" class="empty-inline">暂无选项数据</view>
      </view>

      <button v-if="canDelete" class="danger" @click="remove">删除投票</button>
    </template>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { getCurrentContext, getToken, getUser, mustChangePassword } from '../../utils/auth'
import { request, type ApiError } from '../../utils/request'
import { formatDateTime, voteStatusLabel } from '../../utils/format'

type Opt = {
  optionId: number | string
  optionText: string
  votes: number
  pct: number
}

const id = ref<number | null>(null)
const loading = ref(true)
const error = ref('')
const title = ref('投票结果')
const statusLabel = ref('')
const timeLabel = ref('')
const canDelete = ref(false)
const totalBallots = ref(0)
const options = ref<Opt[]>([])

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

onLoad((q) => {
  const raw = q && q.id ? Number(q.id) : NaN
  id.value = Number.isFinite(raw) ? raw : null
  // mine=1 来自列表；最终以 createdBy 校验为准
  canDelete.value = !!(q && (q.mine === '1' || q.mine === 'true'))
  if (!id.value) {
    loading.value = false
    error.value = '无效投票'
  }
})

async function load() {
  if (!id.value) return
  loading.value = true
  error.value = ''
  try {
    let userId: number | null = null
    try {
      const packed = await request<{
        profile?: { id?: number }
        user?: { id?: number }
      }>({ url: '/auth/identities' })
      userId =
        (packed.profile && packed.profile.id) ||
        (packed.user && packed.user.id) ||
        (getUser()?.id as number) ||
        null
      if (userId != null) userId = Number(userId)
    } catch {
      /* ignore */
    }

    const ctx = getCurrentContext()
    const listUrl = ctx?.identityType === 'STAFF' ? '/staff/votes' : '/committee/votes'

    const [stats, listData] = await Promise.all([
      request<{
        totalBallots?: number
        options?: Array<Record<string, unknown>>
      }>({ url: `/votes/${id.value}/stats` }),
      request<{ list?: Array<Record<string, unknown>> }>({
        url: listUrl,
        data: { page: 1, pageSize: 50 },
      }).catch(() => null),
    ])

    const item = ((listData && listData.list) || []).find(
      (x) => Number(x.id) === Number(id.value),
    )
    const mine = userId != null && item && Number(item.createdBy) === userId
    title.value = String((item && item.title) || '投票结果')
    uni.setNavigationBarTitle({ title: title.value })
    statusLabel.value = item ? voteStatusLabel(item.status) : ''
    timeLabel.value = item
      ? `${formatDateTime(item.startAt)} ~ ${formatDateTime(item.endAt)}`
      : ''
    canDelete.value = !!mine

    const total = Number((stats && stats.totalBallots) || 0)
    totalBallots.value = total
    options.value = ((stats && stats.options) || []).map((o) => {
      const votes = Number(o.votes || 0)
      return {
        optionId: (o.optionId as number) ?? String(o.optionText),
        optionText: String(o.optionText || '—'),
        votes,
        pct: total > 0 ? Math.round((votes / total) * 1000) / 10 : 0,
      }
    })
  } catch (e) {
    error.value = (e as ApiError).message || '加载失败'
  } finally {
    loading.value = false
  }
}

function remove() {
  if (!canDelete.value || !id.value) {
    uni.showToast({ title: '只能删除自己发起的', icon: 'none' })
    return
  }
  const voteId = id.value
  uni.showModal({
    title: '删除投票',
    content: '截止前可删除；删除后不可恢复',
    success: async (res) => {
      if (!res.confirm) return
      uni.showLoading({ title: '删除中', mask: true })
      try {
        await request({ url: `/votes/${voteId}`, method: 'DELETE' })
        uni.showToast({ title: '已删除' })
        setTimeout(() => uni.navigateBack(), 400)
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

onShow(() => {
  if (!guard()) return
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
.head {
  margin-bottom: 20rpx;
}
.title {
  display: block;
  font-size: 36rpx;
  font-weight: 700;
  color: #1f3d32;
}
.meta {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #8a9a93;
}
.card {
  background: #fff;
  border: 1px solid #dfe8e3;
  border-radius: 12rpx;
  padding: 24rpx;
  margin-bottom: 24rpx;
}
.block-title {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: #1f3d32;
  margin-bottom: 16rpx;
}
.opt {
  margin-bottom: 20rpx;
}
.row {
  display: flex;
  justify-content: space-between;
  gap: 12rpx;
  margin-bottom: 8rpx;
}
.opt-text {
  font-size: 28rpx;
  color: #1f3d32;
}
.opt-n {
  font-size: 24rpx;
  color: #5c6f68;
}
.bar {
  height: 12rpx;
  background: #e8f2ec;
  border-radius: 8rpx;
  overflow: hidden;
}
.fill {
  height: 100%;
  background: #2f6b55;
  border-radius: 8rpx;
}
.danger {
  background: #fff;
  color: #c45c26;
  border: 1px solid #f0d4c4;
  font-size: 28rpx;
}
.empty,
.empty-inline {
  padding: 40rpx 0;
  text-align: center;
  font-size: 28rpx;
  color: #8a9a93;
}
</style>
