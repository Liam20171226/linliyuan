<template>
  <view class="page">
    <text v-if="staffTip" class="tip">{{ staffTip }}</text>
    <view class="card">
      <text class="label">标题</text>
      <input v-model="title" class="input" placeholder="投票标题" maxlength="80" />

      <text class="label">背景说明</text>
      <textarea v-model="background" class="area" placeholder="表决背景" :maxlength="2000" />

      <text class="label">开始日期</text>
      <input v-model="startDate" class="input" placeholder="YYYY-MM-DD" />

      <text class="label">结束日期</text>
      <input v-model="endDate" class="input" placeholder="YYYY-MM-DD" />

      <text class="label">选项（至少 2 个）</text>
      <view v-for="(opt, idx) in options" :key="idx" class="opt-row">
        <input
          v-model="options[idx]"
          class="input flex"
          :placeholder="'选项 ' + (idx + 1)"
        />
        <text v-if="options.length > 2" class="rm" @click="removeOption(idx)">删</text>
      </view>
      <button class="ghost" @click="addOption">添加选项</button>
    </view>

    <button class="btn" :disabled="submitting || !createUrl" @click="submit">发起投票</button>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { getCurrentContext } from '../../utils/auth'
import { request, type ApiError } from '../../utils/request'

function pad(n: number) {
  return n < 10 ? '0' + n : '' + n
}
function todayStr() {
  const d = new Date()
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}
function plusDays(days: number) {
  const d = new Date()
  d.setDate(d.getDate() + days)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

const title = ref('')
const background = ref('')
const startDate = ref(todayStr())
const endDate = ref(plusDays(7))
const options = ref(['同意', '不同意'])
const submitting = ref(false)
const staffTip = ref('')
const createUrl = ref('/committee/votes')

onShow(() => {
  const ctx = getCurrentContext()
  const idType = ctx?.identityType || ''
  const roles = ctx?.staffRoles || []
  if (idType === 'STAFF') {
    if (roles.includes('PROPERTY_MANAGER')) {
      createUrl.value = '/staff/votes'
      staffTip.value = ''
    } else {
      createUrl.value = ''
      staffTip.value = '当前物业角色不支持在 App 发起投票，请使用 Web 管理端。'
    }
  } else {
    createUrl.value = '/committee/votes'
    staffTip.value = ''
  }
})

function addOption() {
  if (options.value.length >= 6) {
    uni.showToast({ title: '最多 6 个选项', icon: 'none' })
    return
  }
  options.value = options.value.concat([''])
}

function removeOption(idx: number) {
  if (options.value.length <= 2) {
    uni.showToast({ title: '至少 2 个选项', icon: 'none' })
    return
  }
  const next = options.value.slice()
  next.splice(idx, 1)
  options.value = next
}

async function submit() {
  const t = title.value.trim()
  const bg = background.value.trim()
  const opts = options.value.map((s) => String(s || '').trim()).filter(Boolean)
  if (!t) {
    uni.showToast({ title: '请填写标题', icon: 'none' })
    return
  }
  if (!bg) {
    uni.showToast({ title: '请填写背景说明', icon: 'none' })
    return
  }
  if (opts.length < 2) {
    uni.showToast({ title: '至少 2 个有效选项', icon: 'none' })
    return
  }
  if (!startDate.value || !endDate.value) {
    uni.showToast({ title: '请填写起止日期', icon: 'none' })
    return
  }
  if (endDate.value <= startDate.value) {
    uni.showToast({ title: '结束须晚于开始', icon: 'none' })
    return
  }
  if (!createUrl.value) {
    uni.showToast({ title: staffTip.value || '无法发起', icon: 'none' })
    return
  }
  submitting.value = true
  try {
    await request({
      url: createUrl.value,
      method: 'POST',
      data: {
        title: t,
        background: bg,
        startAt: `${startDate.value}T00:00:00`,
        endAt: `${endDate.value}T23:59:59`,
        options: opts,
      },
    })
    uni.showToast({ title: '已发起' })
    setTimeout(() => {
      uni.redirectTo({
        url: '/pages/vote-mine/vote-mine',
        fail: () => uni.navigateTo({ url: '/pages/vote-mine/vote-mine' }),
      })
    }, 500)
  } catch (err) {
    uni.showModal({
      title: '发起失败',
      content: (err as ApiError).message || '请稍后重试',
      showCancel: false,
    })
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 32rpx 48rpx;
  background: #f7faf8;
  box-sizing: border-box;
}
.tip {
  display: block;
  margin-bottom: 16rpx;
  font-size: 24rpx;
  color: #b45309;
  line-height: 1.45;
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
.input {
  background: #f7faf8;
  border: 1px solid #dfe8e3;
  border-radius: 10rpx;
  padding: 18rpx 16rpx;
  font-size: 28rpx;
  color: #1f3d32;
}
.area {
  width: 100%;
  min-height: 180rpx;
  background: #f7faf8;
  border: 1px solid #dfe8e3;
  border-radius: 10rpx;
  padding: 16rpx;
  font-size: 28rpx;
  box-sizing: border-box;
}
.opt-row {
  display: flex;
  align-items: center;
  gap: 12rpx;
  margin-bottom: 12rpx;
}
.flex {
  flex: 1;
}
.rm {
  font-size: 26rpx;
  color: #b45309;
  padding: 8rpx;
}
.ghost {
  background: #fff;
  color: #2f6b55;
  border: 1px solid #c5d9cf;
  font-size: 26rpx;
  margin-top: 8rpx;
}
.btn {
  background: #2f6b55;
  color: #fff;
  font-size: 30rpx;
  border-radius: 10rpx;
}
</style>
