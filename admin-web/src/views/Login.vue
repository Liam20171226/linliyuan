<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { setPlatformSession } from '../authSession'

const router = useRouter()
const username = ref('admin')
const password = ref('admin')
const loading = ref(false)

async function login() {
  loading.value = true
  try {
    const { data } = await api.post('/auth/platform/login', {
      username: username.value,
      password: password.value,
    })
    if (data.code !== 0) {
      ElMessage.error(data.message || '登录失败')
      return
    }
    setPlatformSession(data.data.token)
    ElMessage.success('登录成功')
    router.push('/communities')
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
      <h1>邻里院</h1>
      <p class="sub">平台管理端</p>
      <el-form @submit.prevent="login">
        <el-form-item label="账号">
          <el-input v-model="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="password" type="password" show-password />
        </el-form-item>
        <el-button type="primary" :loading="loading" style="width:100%" @click="login">登录</el-button>
      </el-form>
      <p class="link"><router-link to="/staff-login">物业登录</router-link></p>
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
  width: min(400px, 100%);
  padding: 40px 36px;
  background: rgba(255, 252, 247, 0.94);
  border: 1px solid rgba(31, 58, 50, 0.12);
  border-radius: 20px;
  box-shadow: 0 20px 50px rgba(15, 61, 52, 0.1);
}
h1 {
  margin: 0 0 8px;
  font-size: 34px;
  letter-spacing: 0.08em;
  color: #0f3d34;
  font-family: "Noto Serif SC", "Songti SC", serif;
}
.sub { margin: 0 0 28px; color: #5c6f68; font-size: 14px; }
.link { margin-top: 18px; font-size: 13px; text-align: center; }
.link a { color: #1a6b5c; text-decoration: none; }
</style>
