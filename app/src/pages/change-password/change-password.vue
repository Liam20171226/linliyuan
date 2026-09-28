<template>
  <view class="page">
    <text class="title">修改临时密码</text>
    <text class="sub">物业为您设置了临时密码，须修改后方可使用 App</text>
    <input class="input" password placeholder="当前临时密码" v-model="oldPassword" />
    <input class="input" password placeholder="新密码（至少 6 位）" v-model="newPassword" />
    <input class="input" password placeholder="确认新密码" v-model="confirm" />
    <button class="btn" :loading="loading" @click="onSubmit">确认修改</button>
    <button class="link" @click="onLogout">退出登录</button>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { logout, setMustChangePassword } from '../../utils/auth'
import { request, type ApiError } from '../../utils/request'

const oldPassword = ref('')
const newPassword = ref('')
const confirm = ref('')
const loading = ref(false)

function onLogout() {
  logout()
}

async function onSubmit() {
  if (!oldPassword.value) {
    uni.showToast({ title: '请输入临时密码', icon: 'none' })
    return
  }
  if (newPassword.value.length < 6) {
    uni.showToast({ title: '新密码至少 6 位', icon: 'none' })
    return
  }
  if (newPassword.value !== confirm.value) {
    uni.showToast({ title: '两次新密码不一致', icon: 'none' })
    return
  }
  if (oldPassword.value === newPassword.value) {
    uni.showToast({ title: '新密码不能与临时密码相同', icon: 'none' })
    return
  }
  loading.value = true
  try {
    await request({
      url: '/auth/app/change-password',
      method: 'POST',
      data: { oldPassword: oldPassword.value, newPassword: newPassword.value },
    })
    setMustChangePassword(false)
    uni.showToast({ title: '已修改', icon: 'success' })
    setTimeout(() => {
      uni.reLaunch({ url: '/pages/identity/identity' })
    }, 400)
  } catch (e) {
    uni.showToast({ title: (e as ApiError).message || '修改失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.page {
  min-height: 100vh;
  padding: 64rpx 48rpx;
  background: #f7faf8;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 24rpx;
}
.title {
  font-size: 40rpx;
  font-weight: 700;
  color: #1f4e3d;
}
.sub {
  font-size: 26rpx;
  color: #5c6f68;
  margin-bottom: 16rpx;
  line-height: 1.5;
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
}
.link {
  background: transparent;
  color: #5c6f68;
  font-size: 28rpx;
}
</style>
