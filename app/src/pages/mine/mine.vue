<template>
  <view class="page">
    <view class="head">
      <text class="name">{{ displayName }}</text>
      <text class="mobile">{{ mobile || '—' }}</text>
      <text v-if="contextLine" class="ctx">{{ contextLine }}</text>
    </view>

    <view class="card">
      <view class="stat">
        <text class="stat-k">待办</text>
        <text class="stat-v">{{ todoCount }}</text>
      </view>
      <view class="stat">
        <text class="stat-k">房屋</text>
        <text class="stat-v">{{ rooms.length }}</text>
      </view>
    </view>

    <view v-if="rooms.length" class="block">
      <text class="block-title">我的房屋</text>
      <view v-for="r in rooms" :key="r.roomId" class="room">
        <text>{{ r.label }}</text>
      </view>
    </view>

    <view v-if="identities.length" class="block">
      <text class="block-title">可用身份</text>
      <view v-for="(it, idx) in identities" :key="idx" class="room">
        <text>{{ it.label }}</text>
      </view>
    </view>

    <view class="actions">
      <button class="ghost" @click="goIdentity">切换身份</button>
      <button class="ghost" @click="goRoomMaintain">房屋维护</button>
      <button class="ghost" @click="goBills">缴费记录</button>
      <button class="ghost" @click="goPrepaid">预缴协议</button>
      <button class="ghost" @click="goNotices">公告</button>
      <button class="ghost" @click="goCommunityApply">建小区申请</button>
      <button class="ghost" @click="goAbout">关于我们</button>
      <button class="ghost" @click="goPassword">修改密码</button>
      <button class="danger" @click="onLogout">退出登录</button>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { getToken, getUser, logout, mustChangePassword, setSession } from '../../utils/auth'
import { request } from '../../utils/request'
import { identityLabel, roleLabel } from '../../utils/format'

type RoomRow = { roomId: number | string; label: string }
type IdRow = { label: string }

const displayName = ref('邻里院用户')
const mobile = ref('')
const contextLine = ref('')
const todoCount = ref(0)
const rooms = ref<RoomRow[]>([])
const identities = ref<IdRow[]>([])

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

onShow(() => {
  if (guard()) refresh()
})

async function refresh() {
  const u = getUser()
  displayName.value = String((u?.realName as string) || (u?.mobile as string) || '邻里院用户')
  mobile.value = String((u?.mobile as string) || '')

  try {
    const data = await request<{
      identities?: Array<Record<string, unknown>>
      user?: Record<string, unknown>
      current?: Record<string, unknown>
    }>({ url: '/auth/identities' })
    if (data.user) {
      setSession({
        token: getToken(),
        user: data.user,
        mustChangePassword: mustChangePassword(),
      })
      displayName.value = String(
        (data.user.realName as string) || (data.user.mobile as string) || displayName.value,
      )
      mobile.value = String((data.user.mobile as string) || mobile.value)
    }
    identities.value = (data.identities || []).map((it) => {
      const t = identityLabel(it.identityType)
      const c = it.communityName ? String(it.communityName) : ''
      return { label: c ? `${t} · ${c}` : t }
    })
    const cur = data.current
    if (cur) {
      const t = identityLabel(cur.identityType)
      const c = cur.communityName ? String(cur.communityName) : ''
      contextLine.value = c ? `${t} · ${c}` : t
    } else if (identities.value.length) {
      contextLine.value = identities.value[0].label
    }
  } catch {
    /* ignore */
  }

  try {
    const raw = await request<Array<Record<string, unknown>>>({ url: '/resident/rooms' })
    rooms.value = (raw || []).map((r) => ({
      roomId: r.roomId as number,
      label: `${r.address || r.roomNo || '房屋' + r.roomId}（${roleLabel(r.residentRole)}）`,
    }))
  } catch {
    rooms.value = []
  }

  try {
    const todoData = await request<{ list?: unknown[] }>({
      url: '/todos',
      data: { status: 'OPEN' },
    })
    todoCount.value = ((todoData && todoData.list) || []).length
  } catch {
    todoCount.value = 0
  }
}

function goIdentity() {
  uni.navigateTo({ url: '/pages/identity/identity' })
}

function goRoomMaintain() {
  uni.navigateTo({ url: '/pages/room-maintain/room-maintain' })
}

function goBills() {
  uni.navigateTo({ url: '/pages/bills/bills?mode=history' })
}

function goPrepaid() {
  uni.navigateTo({ url: '/pages/prepaid/prepaid' })
}

function goNotices() {
  uni.navigateTo({ url: '/pages/notices/notices' })
}

function goAbout() {
  uni.navigateTo({ url: '/pages/about/about' })
}

function goCommunityApply() {
  uni.navigateTo({ url: '/pages/community-apply/community-apply' })
}

function goPassword() {
  uni.navigateTo({ url: '/pages/change-password/change-password' })
}

function onLogout() {
  uni.showModal({
    title: '退出登录',
    content: '确定退出当前账号？',
    success: (res) => {
      if (res.confirm) logout()
    },
  })
}
</script>

<style scoped>
.page {
  min-height: 100vh;
  padding: 32rpx;
  background: linear-gradient(180deg, #e8f2ec 0%, #f7faf8 30%, #ffffff 100%);
  box-sizing: border-box;
}
.head {
  margin-bottom: 28rpx;
}
.name {
  display: block;
  font-size: 40rpx;
  font-weight: 700;
  color: #1f4e3d;
}
.mobile {
  display: block;
  margin-top: 8rpx;
  font-size: 26rpx;
  color: #5c6f68;
}
.ctx {
  display: block;
  margin-top: 6rpx;
  font-size: 24rpx;
  color: #8a9a93;
}
.card {
  display: flex;
  gap: 16rpx;
  margin-bottom: 20rpx;
}
.stat {
  flex: 1;
  background: #fff;
  border: 1px solid #dfe8e3;
  border-radius: 12rpx;
  padding: 24rpx;
  text-align: center;
}
.stat-k {
  display: block;
  font-size: 22rpx;
  color: #8a9a93;
}
.stat-v {
  display: block;
  margin-top: 8rpx;
  font-size: 36rpx;
  font-weight: 700;
  color: #1f4e3d;
}
.block {
  background: #fff;
  border: 1px solid #dfe8e3;
  border-radius: 12rpx;
  padding: 20rpx 24rpx;
  margin-bottom: 16rpx;
}
.block-title {
  display: block;
  font-size: 26rpx;
  font-weight: 600;
  color: #1f4e3d;
  margin-bottom: 12rpx;
}
.room {
  padding: 12rpx 0;
  font-size: 26rpx;
  color: #5c6f68;
  border-top: 1px solid #eef3f0;
}
.room:first-of-type {
  border-top: none;
}
.actions {
  margin-top: 28rpx;
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}
.ghost {
  background: #fff;
  color: #2f6b55;
  border: 1px solid #c5d9cf;
  font-size: 28rpx;
}
.danger {
  background: #fff;
  color: #c45c26;
  border: 1px solid #f0d4c4;
  font-size: 28rpx;
}
</style>
