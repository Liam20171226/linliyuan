<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { setStaffSession } from '../authSession'

const router = useRouter()
const oldPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const loading = ref(false)

async function submit() {
  if (newPassword.value.length < 6) {
    ElMessage.warning('新密码至少 6 位')
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  if (newPassword.value === oldPassword.value) {
    ElMessage.warning('新密码不能与临时密码相同')
    return
  }
  loading.value = true
  try {
    const { data } = await api.post('/auth/staff/change-password', {
      oldPassword: oldPassword.value,
      newPassword: newPassword.value,
    })
    if (data.code !== 0) {
      ElMessage.error(data.message || '改密失败')
      return
    }
    ElMessage.success('密码已更新，请选择小区进入')
    sessionStorage.removeItem('staffMustChangePassword')
    const raw = sessionStorage.getItem('staffPendingCommunities')
    const communities = raw ? JSON.parse(raw) : []
    sessionStorage.removeItem('staffPendingCommunities')
    if (communities.length === 1) {
      const c = communities[0]
      const sw = await api.post('/auth/context/switch', {
        identityType: 'STAFF',
        communityId: c.communityId,
      })
      if (sw.data.code === 0) {
        setStaffSession(sw.data.data.token, c.communityName || '', c.communityId)
        router.replace('/space')
        return
      }
    }
    sessionStorage.setItem('staffPendingCommunities', JSON.stringify(communities))
    router.replace('/staff-login?afterChange=1')
  } catch (e: any) {
    ElMessage.error(e?.message || '网络错误')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="page">
    <div class="panel">
      <h1>修改临时密码</h1>
      <p class="sub">平台为您设置了临时密码，须修改后方可进入物业后台</p>
      <el-form @submit.prevent="submit">
        <el-form-item label="临时密码">
          <el-input v-model="oldPassword" type="password" show-password />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input v-model="newPassword" type="password" show-password placeholder="至少 6 位" />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input v-model="confirmPassword" type="password" show-password />
        </el-form-item>
        <el-button type="primary" :loading="loading" style="width:100%" @click="submit">确认修改</el-button>
      </el-form>
      <p class="link"><router-link to="/staff-login">返回登录</router-link></p>
    </div>
  </div>
</template>

<style scoped>
.page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
}
.panel {
  width: min(420px, 100%);
  padding: 40px 36px;
  background: rgba(255, 252, 247, 0.94);
  border: 1px solid rgba(31, 58, 50, 0.12);
  border-radius: 20px;
  box-shadow: 0 20px 50px rgba(15, 61, 52, 0.1);
}
h1 {
  margin: 0 0 8px;
  font-size: 28px;
  letter-spacing: 0.06em;
  color: #0f3d34;
  font-family: "Noto Serif SC", "Songti SC", serif;
}
.sub { margin: 0 0 28px; color: #5c6f68; font-size: 14px; line-height: 1.5; }
.link { margin-top: 18px; font-size: 13px; }
.link a { color: #1a6b5c; text-decoration: none; }
</style>
