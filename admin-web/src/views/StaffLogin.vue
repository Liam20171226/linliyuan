<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { setStaffSession } from '../authSession'

const router = useRouter()
const route = useRoute()
const mobile = ref('13800000001')
const password = ref('staff123')
const loading = ref(false)
const communities = ref<any[]>([])
const picked = ref<number | null>(null)

onMounted(() => {
  if (route.query.afterChange === '1') {
    const raw = sessionStorage.getItem('staffPendingCommunities')
    if (raw) {
      try {
        communities.value = JSON.parse(raw)
        sessionStorage.removeItem('staffPendingCommunities')
        if (communities.value.length === 1) {
          picked.value = communities.value[0].communityId
          switchInto()
        } else if (communities.value.length > 1) {
          ElMessage.success('请选择小区')
        }
      } catch {
        /* ignore */
      }
    }
  }
})

async function login() {
  loading.value = true
  try {
    const { data } = await api.post('/auth/staff/login', {
      mobile: mobile.value,
      password: password.value,
    })
    if (data.code !== 0) {
      ElMessage.error(data.message || '登录失败')
      return
    }
    localStorage.setItem('token', data.data.token)
    if (data.data.user?.id != null) {
      localStorage.setItem('staffUserId', String(data.data.user.id))
    }
    communities.value = data.data.communities || []
    if (data.data.mustChangePassword) {
      sessionStorage.setItem('staffMustChangePassword', '1')
      sessionStorage.setItem('staffPendingCommunities', JSON.stringify(communities.value))
      ElMessage.warning('请先修改临时密码')
      router.push('/staff-change-password')
      return
    }
    sessionStorage.removeItem('staffMustChangePassword')
    if (communities.value.length === 1) {
      picked.value = communities.value[0].communityId
      await switchInto()
    } else if (!communities.value.length) {
      ElMessage.error('无任职小区')
    } else {
      ElMessage.success('请选择小区')
    }
  } catch (e: any) {
    ElMessage.error(e?.message || '网络错误')
  } finally {
    loading.value = false
  }
}

async function switchInto() {
  if (picked.value == null) return
  const { data } = await api.post('/auth/context/switch', {
    identityType: 'STAFF',
    communityId: picked.value,
  })
  if (data.code === 40302) {
    ElMessage.warning(data.message || '请先修改临时密码')
    router.push('/staff-change-password')
    return
  }
  if (data.code !== 0) {
    ElMessage.error(data.message)
    return
  }
  const c = communities.value.find((x) => x.communityId === picked.value)
  setStaffSession(data.data.token, c?.communityName || '', picked.value)
  ElMessage.success('已进入物业身份')
  router.push('/space')
}
</script>

<template>
  <div class="page">
    <div class="panel">
      <h1>邻里院</h1>
      <p class="sub">物业工作台</p>
      <el-form @submit.prevent="login">
        <el-form-item label="手机号">
          <el-input v-model="mobile" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="password" type="password" show-password />
        </el-form-item>
        <el-button type="primary" :loading="loading" style="width:100%" @click="login">登录</el-button>
      </el-form>
      <div v-if="communities.length > 1" class="pick">
        <p>选择小区</p>
        <el-radio-group v-model="picked">
          <el-radio v-for="c in communities" :key="c.communityId" :value="c.communityId">
            {{ c.communityName }}（{{ c.roleLabels || c.staffRole }}）
          </el-radio>
        </el-radio-group>
        <el-button type="primary" style="margin-top:12px" @click="switchInto">进入</el-button>
      </div>
      <p class="link"><a href="/login">平台登录</a></p>
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
  font-size: 34px;
  letter-spacing: 0.08em;
  color: #0f3d34;
  font-family: "Noto Serif SC", "Songti SC", serif;
}
.sub { margin: 0 0 28px; color: #5c6f68; font-size: 14px; }
.pick { margin-top: 20px; border-top: 1px solid rgba(31, 58, 50, 0.1); padding-top: 16px; }
.link { margin-top: 18px; font-size: 13px; }
.link a { color: #1a6b5c; text-decoration: none; }
</style>
