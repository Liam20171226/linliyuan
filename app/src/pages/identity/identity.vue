<template>
  <view class="page">
    <text class="title">选择身份</text>
    <text class="sub">{{ userLabel }}</text>
    <view v-if="loading" class="empty">加载中…</view>
    <view v-else-if="!identities.length" class="empty">暂无可用身份</view>
    <view
      v-for="(item, idx) in identities"
      :key="idx"
      class="card"
      @click="pick(item)"
    >
      <text class="card-title">{{ labelOf(item) }}</text>
      <text class="card-meta">{{ metaOf(item) }}</text>
    </view>
    <button class="link" @click="onLogout">退出登录</button>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import {
  getUser,
  logout,
  setSession,
  getToken,
  mustChangePassword,
  setCurrentContext,
} from '../../utils/auth'
import { request, type ApiError } from '../../utils/request'

type Identity = {
  identityType?: string
  communityId?: number
  communityName?: string
  staffRole?: string
  staffRoles?: string[]
  roomId?: number
  rooms?: Array<{ roomId?: number; roomPath?: string }>
}

const loading = ref(true)
const identities = ref<Identity[]>([])
const userLabel = computed(() => {
  const u = getUser()
  if (!u) return ''
  const name = (u.realName as string) || ''
  const mobile = (u.mobile as string) || ''
  return [name, mobile].filter(Boolean).join(' · ')
})

const TYPE_LABEL: Record<string, string> = {
  RESIDENT: '住户',
  COMMITTEE: '业委会',
  STAFF: '物业',
  GUEST: '游客',
}

function labelOf(item: Identity) {
  const t = TYPE_LABEL[item.identityType || ''] || item.identityType || '身份'
  return `${t}${item.communityName ? ' · ' + item.communityName : ''}`
}

function metaOf(item: Identity) {
  const parts: string[] = []
  if (item.staffRole) parts.push(item.staffRole)
  const rooms = item.rooms || []
  if (rooms.length) {
    parts.push(rooms.map((r) => r.roomPath || `房${r.roomId}`).join('、'))
  }
  return parts.join(' · ') || '点击进入'
}

async function load() {
  if (!getToken()) {
    uni.reLaunch({ url: '/pages/login/login' })
    return
  }
  if (mustChangePassword()) {
    uni.reLaunch({ url: '/pages/change-password/change-password' })
    return
  }
  loading.value = true
  try {
    const data = await request<{ identities?: Identity[]; user?: Record<string, unknown> }>({
      url: '/auth/identities',
    })
    if (data.user) {
      setSession({ token: getToken(), user: data.user, mustChangePassword: false })
    }
    identities.value = data.identities || []
    if (identities.value.length === 1) {
      await pick(identities.value[0], true)
    }
  } catch (e) {
    uni.showToast({ title: (e as ApiError).message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

async function pick(item: Identity, silent = false) {
  try {
    const roomId =
      item.roomId ||
      (item.rooms && item.rooms[0] && item.rooms[0].roomId) ||
      undefined
    const data = await request<{ token: string }>({
      url: '/auth/context/switch',
      method: 'POST',
      data: {
        identityType: item.identityType,
        communityId: item.communityId,
        roomId,
      },
    })
    setSession({
      token: data.token,
      user: getUser() || undefined,
      mustChangePassword: false,
    })
    setCurrentContext({
      identityType: item.identityType,
      communityId: item.communityId ?? null,
      communityName: item.communityName || '',
      staffRoles: item.staffRoles || (item.staffRole ? [item.staffRole] : []),
      roomId: roomId ?? null,
    })
    uni.reLaunch({ url: '/pages/home/home' })
  } catch (e) {
    if (!silent) {
      uni.showToast({ title: (e as ApiError).message || '切换失败', icon: 'none' })
    }
  }
}

function onLogout() {
  logout()
}

onShow(() => {
  load()
})
</script>

<style scoped>
.page {
  min-height: 100vh;
  padding: 48rpx;
  background: #f7faf8;
  box-sizing: border-box;
}
.title {
  display: block;
  font-size: 40rpx;
  font-weight: 700;
  color: #1f4e3d;
}
.sub {
  display: block;
  margin: 8rpx 0 32rpx;
  font-size: 26rpx;
  color: #5c6f68;
}
.card {
  background: #fff;
  border: 1px solid #dfe8e3;
  border-radius: 10rpx;
  padding: 28rpx 32rpx;
  margin-bottom: 20rpx;
}
.card-title {
  display: block;
  font-size: 32rpx;
  color: #1f3d32;
  font-weight: 600;
}
.card-meta {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #7a8c85;
}
.empty {
  padding: 40rpx 0;
  color: #8a9a93;
  font-size: 28rpx;
}
.link {
  margin-top: 40rpx;
  background: transparent;
  color: #5c6f68;
  font-size: 28rpx;
}
</style>
