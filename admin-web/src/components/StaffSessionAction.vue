<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { isPlatformRole } from '../authSession'

const router = useRouter()
const platform = computed(() => isPlatformRole())

async function backToPlatform() {
  const { data } = await api.post('/platform/context/exit-community')
  if (data.code === 0 && data.data?.token) {
    localStorage.setItem('token', data.data.token)
  }
  localStorage.setItem('adminRole', 'platform')
  localStorage.removeItem('communityName')
  localStorage.removeItem('communityId')
  ElMessage.success('已返回平台')
  router.push('/communities')
}

function switchAccount() {
  router.push('/staff-login')
}
</script>

<template>
  <el-button v-if="platform" @click="backToPlatform">返回平台</el-button>
  <el-button v-else @click="switchAccount">切换账号</el-button>
</template>
