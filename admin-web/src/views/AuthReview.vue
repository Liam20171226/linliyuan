<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { api } from '../api'
import { authFailRedirectPath } from '../authSession'
import { residentRoleLabel } from '../residentRoles'
import StaffNav from '../components/StaffNav.vue'
import StaffSessionAction from '../components/StaffSessionAction.vue'

const router = useRouter()
const loading = ref(false)
const list = ref<any[]>([])
const status = ref('PENDING')
const rejectReason = ref('')
const rejectingId = ref<number | null>(null)
const detail = ref<any | null>(null)
const previewUrls = ref<Record<number, string>>({})

const statusLabel = (s: string) =>
  ({ PENDING: '待审核', APPROVED: '已通过', REJECTED: '已拒绝' } as Record<string, string>)[s] || s

function revokePreviews() {
  Object.values(previewUrls.value).forEach((u) => URL.revokeObjectURL(u))
  previewUrls.value = {}
}

async function loadAttachmentPreviews(attachments: any[]) {
  revokePreviews()
  const next: Record<number, string> = {}
  for (const a of attachments || []) {
    if (!a?.id || !a.image) continue
    try {
      const res = await api.get(`/attachments/${a.id}`, { responseType: 'blob' })
      next[a.id] = URL.createObjectURL(res.data)
    } catch {
      /* ignore */
    }
  }
  previewUrls.value = next
}

async function load() {
  loading.value = true
  try {
    const { data } = await api.get('/staff/auth-applications', {
      params: status.value ? { status: status.value } : {},
    })
    if (data.code === 0) list.value = data.data || []
    else {
      ElMessage.error(data.message || '加载失败')
      if (data.code === 40301 || data.code === 40101) router.push(authFailRedirectPath())
    }
  } finally {
    loading.value = false
  }
}

async function openDetail(id: number) {
  const { data } = await api.get(`/staff/auth-applications/${id}`)
  if (data.code !== 0) {
    ElMessage.error(data.message || '加载详情失败')
    return
  }
  detail.value = data.data
  await loadAttachmentPreviews(data.data?.attachments || [])
}

async function approve(id: number) {
  const { data } = await api.post(`/staff/auth-applications/${id}/approve`)
  if (data.code === 0) {
    ElMessage.success('已通过')
    detail.value = null
    revokePreviews()
    load()
  } else ElMessage.error(data.message)
}

function openReject(id: number) {
  rejectingId.value = id
  rejectReason.value = ''
}

async function confirmReject() {
  if (!rejectingId.value || !rejectReason.value.trim()) {
    ElMessage.warning('请填写拒绝原因')
    return
  }
  const { data } = await api.post(`/staff/auth-applications/${rejectingId.value}/reject`, {
    rejectReason: rejectReason.value,
  })
  if (data.code === 0) {
    ElMessage.success('已拒绝')
    rejectingId.value = null
    detail.value = null
    revokePreviews()
    load()
  } else ElMessage.error(data.message)
}

async function downloadFile(a: any) {
  try {
    const res = await api.get(`/attachments/${a.id}`, { responseType: 'blob' })
    const url = URL.createObjectURL(res.data)
    const link = document.createElement('a')
    link.href = url
    link.download = a.fileName || `attachment-${a.id}`
    link.click()
    URL.revokeObjectURL(url)
  } catch {
    ElMessage.error('下载失败')
  }
}

onMounted(load)
onBeforeUnmount(revokePreviews)
</script>

<template>
  <div class="page" v-loading="loading">
    <header>
      <div>
        <h2>认证审核</h2>
        <p class="sub">物业审核住户认证申请</p>
        <StaffNav />
      </div>
      <div class="row">
        <el-select v-model="status" style="width:140px" @change="load">
          <el-option label="待审核" value="PENDING" />
          <el-option label="已通过" value="APPROVED" />
          <el-option label="已拒绝" value="REJECTED" />
          <el-option label="全部" value="" />
        </el-select>
        <el-button @click="load">刷新</el-button>
        <StaffSessionAction />
      </div>
    </header>

    <el-table :data="list" style="width:100%; margin-top:16px">
      <el-table-column type="index" label="序号" width="60" :index="(i: number) => i + 1" />
      <el-table-column label="房屋地址" min-width="160">
        <template #default="{ row }">{{ row.roomPath || row.roomNo || '—' }}</template>
      </el-table-column>
      <el-table-column prop="applicantName" label="姓名" width="100" />
      <el-table-column prop="applicantMobile" label="手机" width="120" />
      <el-table-column label="角色" width="100">
        <template #default="{ row }">{{ residentRoleLabel(row.applyRole) }}</template>
      </el-table-column>
      <el-table-column label="附件" width="80">
        <template #default="{ row }">
          {{ (row.attachments || []).length ? (row.attachments.length + ' 张') : '—' }}
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="提交时间" width="170" />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">{{ statusLabel(row.status) }}</template>
      </el-table-column>
      <el-table-column prop="applyMessage" label="留言" min-width="100" show-overflow-tooltip />
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link @click="openDetail(row.id)">查看</el-button>
          <template v-if="row.status === 'PENDING'">
            <el-button type="success" link @click="approve(row.id)">通过</el-button>
            <el-button type="danger" link @click="openReject(row.id)">拒绝</el-button>
          </template>
          <span v-else class="muted">{{ row.rejectReason || '—' }}</span>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog
      :model-value="detail != null"
      title="认证申请详情"
      width="640px"
      @update:model-value="(v: boolean) => { if (!v) { detail = null; revokePreviews() } }"
    >
      <template v-if="detail">
        <p><b>单号</b> {{ detail.id }} · {{ statusLabel(detail.status) }}</p>
        <p><b>房屋地址</b> {{ detail.roomPath || detail.roomNo || detail.roomId }}</p>
        <p><b>申请人</b> {{ detail.applicantName }} / {{ detail.applicantMobile }}</p>
        <p><b>角色</b> {{ residentRoleLabel(detail.applyRole) }}</p>
        <p v-if="detail.createdAt"><b>提交时间</b> {{ detail.createdAt }}</p>
        <p v-if="detail.applicantIdCardNo"><b>身份证</b> {{ detail.applicantIdCardNo }}</p>
        <p v-if="detail.applyMessage"><b>留言</b> {{ detail.applyMessage }}</p>
        <p v-if="detail.rejectReason"><b>拒绝原因</b> {{ detail.rejectReason }}</p>
        <div class="atts">
          <div class="atts-title">产权附件</div>
          <div v-if="!(detail.attachments || []).length" class="muted">无附件</div>
          <div class="att-grid">
            <div v-for="a in detail.attachments || []" :key="a.id" class="att-card">
              <img v-if="a.image && previewUrls[a.id]" :src="previewUrls[a.id]" class="att-img" alt="" />
              <div v-else class="att-file">{{ a.fileName || ('附件' + a.id) }}</div>
              <el-button link type="primary" @click="downloadFile(a)">下载</el-button>
            </div>
          </div>
        </div>
      </template>
      <template #footer>
        <el-button @click="detail = null; revokePreviews()">关闭</el-button>
        <template v-if="detail?.status === 'PENDING'">
          <el-button type="danger" @click="openReject(detail.id)">拒绝</el-button>
          <el-button type="primary" @click="approve(detail.id)">通过</el-button>
        </template>
      </template>
    </el-dialog>

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
.page { padding: 24px 32px; max-width: 1500px; margin: 0 auto; }
header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; }
h2 { margin: 0; color: #1f4e3d; }
.sub { margin: 4px 0 0; color: #667; font-size: 14px; }
.row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.muted { color: #889; font-size: 13px; }
.atts { margin-top: 16px; }
.atts-title { font-weight: 600; margin-bottom: 8px; color: #1f4e3d; }
.att-grid { display: flex; flex-wrap: wrap; gap: 12px; }
.att-card { width: 140px; }
.att-img { width: 140px; height: 140px; object-fit: cover; border-radius: 8px; border: 1px solid #dfe6e1; display: block; margin-bottom: 4px; }
.att-file { width: 140px; height: 80px; border-radius: 8px; background: #f3f6f4; display: flex; align-items: center; justify-content: center; padding: 8px; font-size: 12px; color: #556; box-sizing: border-box; margin-bottom: 4px; }
</style>
