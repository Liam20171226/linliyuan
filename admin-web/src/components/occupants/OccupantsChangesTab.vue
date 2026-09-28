<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../../api'
import { authFailRedirectPath } from '../../authSession'

const router = useRouter()
const loading = ref(false)
const list = ref<any[]>([])
const status = ref('PENDING')
const rejectReason = ref('')
const rejectingId = ref<number | null>(null)

const statusLabel = (s: string) =>
  ({ PENDING: '待审', APPROVED: '已通过', REJECTED: '已拒绝' } as Record<string, string>)[s] || s

async function load() {
  loading.value = true
  try {
    const { data } = await api.get('/staff/room-change-applications', {
      params: status.value ? { status: status.value } : {},
    })
    if (data.code === 0) {
      list.value = Array.isArray(data.data) ? data.data : (data.data?.list || [])
    } else {
      ElMessage.error(data.message)
      if (data.code === 40101 || data.code === 40301) router.push(authFailRedirectPath())
    }
  } finally {
    loading.value = false
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

async function confirmReject() {
  if (rejectingId.value == null) return
  if (!rejectReason.value.trim()) {
    ElMessage.warning('请填写拒绝原因')
    return
  }
  const { data } = await api.post(`/staff/room-change-applications/${rejectingId.value}/reject`, {
    rejectReason: rejectReason.value,
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
  <div v-loading="loading">
    <div class="toolbar">
      <el-select v-model="status" style="width:140px" @change="load">
        <el-option label="待审" value="PENDING" />
        <el-option label="已通过" value="APPROVED" />
        <el-option label="已拒绝" value="REJECTED" />
      </el-select>
      <el-button @click="load">刷新</el-button>
    </div>

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
      <el-table-column prop="applyMessage" label="说明" min-width="120" show-overflow-tooltip />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">{{ statusLabel(row.status) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template #default="{ row }">
          <template v-if="row.status === 'PENDING'">
            <el-button link type="primary" @click="approve(row.id)">通过</el-button>
            <el-button link type="danger" @click="openReject(row.id)">拒绝</el-button>
          </template>
          <span v-else-if="row.rejectReason" class="reject-hint">{{ row.rejectReason }}</span>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      :model-value="rejectingId != null"
      title="拒绝原因"
      width="420px"
      @update:model-value="(v: boolean) => { if (!v) rejectingId = null }"
    >
      <el-input v-model="rejectReason" type="textarea" :rows="3" placeholder="请填写拒绝原因" />
      <template #footer>
        <el-button @click="rejectingId = null">取消</el-button>
        <el-button type="danger" @click="confirmReject">确认拒绝</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.reject-hint { font-size: 12px; color: #8a9a93; }
</style>
