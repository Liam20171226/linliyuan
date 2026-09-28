<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { authFailRedirectPath } from '../authSession'
import StaffNav from '../components/StaffNav.vue'
import StaffSessionAction from '../components/StaffSessionAction.vue'

const router = useRouter()
const list = ref<any[]>([])
const status = ref('PENDING')
const rejectReason = ref('')
const rejectingId = ref<number | null>(null)

async function load() {
  const { data } = await api.get('/staff/room-change-applications', {
    params: status.value ? { status: status.value } : {},
  })
  if (data.code === 0) {
    list.value = Array.isArray(data.data) ? data.data : (data.data?.list || [])
  } else {
    ElMessage.error(data.message)
    if (data.code === 40101 || data.code === 40301) router.push(authFailRedirectPath())
  }
}

async function approve(id: number) {
  const { data } = await api.post(`/staff/room-change-applications/${id}/approve`)
  if (data.code === 0) {
    ElMessage.success('已通过')
    load()
  } else ElMessage.error(data.message)
}

function openReject(id: number) {
  rejectingId.value = id
  rejectReason.value = ''
}

async function reject() {
  if (rejectingId.value == null) return
  const { data } = await api.post(`/staff/room-change-applications/${rejectingId.value}/reject`, {
    rejectReason: rejectReason.value || '不符合要求',
  })
  if (data.code === 0) {
    ElMessage.success('已拒绝')
    rejectingId.value = null
    load()
  } else ElMessage.error(data.message)
}

onMounted(load)
</script>

<template>
  <div class="page">
    <header>
      <div>
        <h2>房屋变更审核</h2>
        <p class="sub">业主提交的变更申请</p>
        <StaffNav />
      </div>
      <div class="row">
        <el-select v-model="status" style="width:140px" @change="load">
          <el-option label="待审" value="PENDING" />
          <el-option label="已通过" value="APPROVED" />
          <el-option label="已拒绝" value="REJECTED" />
        </el-select>
        <el-button @click="load">刷新</el-button>
        <StaffSessionAction />
      </div>
    </header>
    <el-table :data="list" style="margin-top:16px">
      <el-table-column type="index" label="序号" width="60" :index="(i: number) => i + 1" />
      <el-table-column label="房屋" min-width="180">
        <template #default="{ row }">{{ row.roomPath || row.roomNo || row.roomId || '—' }}</template>
      </el-table-column>
      <el-table-column label="类型" width="120">
        <template #default="{ row }">{{ row.changeTypeLabel || row.changeType }}</template>
      </el-table-column>
      <el-table-column label="申请内容" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ row.payloadSummary || '—' }}</template>
      </el-table-column>
      <el-table-column prop="applyMessage" label="说明" />
      <el-table-column prop="status" label="状态" width="100" />
      <el-table-column label="操作" width="160">
        <template #default="{ row }">
          <template v-if="row.status === 'PENDING'">
            <el-button link type="primary" @click="approve(row.id)">通过</el-button>
            <el-button link type="danger" @click="openReject(row.id)">拒绝</el-button>
          </template>
        </template>
      </el-table-column>
    </el-table>
    <div v-if="rejectingId" class="reject">
      <el-input v-model="rejectReason" placeholder="拒绝原因" />
      <el-button type="danger" style="margin-top:8px" @click="reject">确认拒绝</el-button>
      <el-button style="margin-top:8px" @click="rejectingId = null">取消</el-button>
    </div>
  </div>
</template>

<style scoped>
.page { padding: 24px 32px; max-width: 1500px; margin: 0 auto; }
header { display: flex; justify-content: space-between; gap: 16px; }
h2 { margin: 0; color: #1f4e3d; }
.sub { margin: 4px 0 0; color: #667; font-size: 14px; }
.row { display: flex; gap: 8px; }
.reject { margin-top: 16px; background: #fff; border: 1px solid #dfe6e1; padding: 12px; }
</style>
