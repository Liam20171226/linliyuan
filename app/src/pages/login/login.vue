<template>
  <view class="page">
    <view class="brand">
      <text class="name">邻里院</text>
      <text class="sub">物业 App · 手机号登录</text>
    </view>

    <view class="form">
      <input
        class="input"
        type="number"
        maxlength="11"
        placeholder="手机号"
        v-model="mobile"
      />
      <input
        class="input"
        password
        placeholder="密码（物业设置的临时密码）"
        v-model="password"
      />
      <button class="btn" :loading="loading" @click="onLogin">登录</button>
      <text class="tip">暂无密码请联系物业在管理后台「设 App 密码」。微信支付暂未开放。</text>
    </view>

    <view class="api-row">
      <text class="api-label">API</text>
      <input class="api-input" v-model="apiBase" placeholder="http://IP:8080/api/v1" />
      <button class="api-btn" size="mini" @click="saveApi">保存</button>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { getApiBase, setApiBase } from '../../utils/config'
import { setSession } from '../../utils/auth'
import { request, type ApiError } from '../../utils/request'

const mobile = ref('')
const password = ref('')
const loading = ref(false)
const apiBase = ref(getApiBase())

function saveApi() {
  if (!apiBase.value.trim()) {
    uni.showToast({ title: '请填写 API 地址', icon: 'none' })
    return
  }
  setApiBase(apiBase.value)
  uni.showToast({ title: '已保存', icon: 'success' })
}

type LoginRes = {
  token: string
  mustChangePassword?: boolean
  needChooseIdentity?: boolean
  user?: Record<string, unknown>
  identities?: unknown[]
  current?: unknown
}

async function onLogin() {
  const m = mobile.value.trim()
  const p = password.value
  if (!/^1\d{10}$/.test(m)) {
    uni.showToast({ title: '请输入正确手机号', icon: 'none' })
    return
  }
  if (!p) {
    uni.showToast({ title: '请输入密码', icon: 'none' })
    return
  }
  loading.value = true
  try {
    const data = await request<LoginRes>({
      url: '/auth/app/login',
      method: 'POST',
      data: { mobile: m, password: p },
      auth: false,
    })
    setSession({
      token: data.token,
      user: data.user,
      mustChangePassword: !!data.mustChangePassword,
    })
    if (data.mustChangePassword) {
      uni.reLaunch({ url: '/pages/change-password/change-password' })
      return
    }
    if (data.needChooseIdentity) {
      uni.reLaunch({ url: '/pages/identity/identity' })
      return
    }
    uni.reLaunch({ url: '/pages/home/home' })
  } catch (e) {
    const err = e as ApiError
    uni.showToast({ title: err.message || '登录失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.page {
  min-height: 100vh;
  padding: 80rpx 48rpx 48rpx;
  background: linear-gradient(165deg, #e8f2ec 0%, #f7faf8 45%, #ffffff 100%);
  box-sizing: border-box;
}
.brand {
  margin-bottom: 64rpx;
}
.name {
  display: block;
  font-size: 56rpx;
  font-weight: 700;
  color: #1f4e3d;
  letter-spacing: 4rpx;
}
.sub {
  display: block;
  margin-top: 12rpx;
  font-size: 26rpx;
  color: #5c6f68;
}
.form {
  display: flex;
  flex-direction: column;
  gap: 24rpx;
}
.input {
  height: 88rpx;
  padding: 0 28rpx;
  background: #fff;
  border: 1px solid #dfe8e3;
  border-radius: 8rpx;
  font-size: 30rpx;
}
.btn {
  margin-top: 16rpx;
  height: 88rpx;
  line-height: 88rpx;
  background: #2f6b55;
  color: #fff;
  border-radius: 8rpx;
  font-size: 32rpx;
}
.tip {
  font-size: 24rpx;
  color: #7a8c85;
  line-height: 1.5;
}
.api-row {
  margin-top: 80rpx;
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.api-label {
  font-size: 22rpx;
  color: #8a9a93;
  flex-shrink: 0;
}
.api-input {
  flex: 1;
  height: 56rpx;
  padding: 0 16rpx;
  font-size: 22rpx;
  background: #fff;
  border: 1px solid #e5ebe7;
  border-radius: 6rpx;
}
.api-btn {
  flex-shrink: 0;
  background: #e8f2ec;
  color: #2f6b55;
}
</style>
