<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api, API_BASE } from '../api'
import StaffNav from '../components/StaffNav.vue'
import StaffSessionAction from '../components/StaffSessionAction.vue'

const batches = ref<any[]>([])
const uploading = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

async function load() {
  const { data } = await api.get('/staff/import/batches')
  if (data.code === 0) batches.value = Array.isArray(data.data) ? data.data : (data.data?.list || [])
  else ElMessage.error(data.message)
}

async function downloadTemplate() {
  const token = localStorage.getItem('token')
  const res = await fetch(`${API_BASE}/staff/import/template`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  const blob = await res.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'resident-import-template.xlsx'
  a.click()
  URL.revokeObjectURL(url)
}

async function onFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  uploading.value = true
  try {
    const form = new FormData()
    form.append('file', file)
    const token = localStorage.getItem('token')
    const res = await fetch(`${API_BASE}/staff/import/batches`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token || ''}` },
      body: form,
    })
    const data = await res.json()
    if (data.code === 0) {
      ElMessage.success(`成功 ${data.data.successCount} / 失败 ${data.data.failCount}`)
      load()
    } else ElMessage.error(data.message)
  } finally {
    uploading.value = false
    input.value = ''
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <header>
      <div>
        <h2>住户导入</h2>
        <p class="sub">下载模板 → 填写 → 上传 Excel</p>
        <StaffNav />
      </div>
      <div class="row">
        <el-button @click="downloadTemplate">下载模板</el-button>
        <el-button type="primary" :loading="uploading" @click="fileInput?.click()">上传 Excel</el-button>
        <input ref="fileInput" type="file" accept=".xlsx,.xls" style="display:none" @change="onFile" />
        <StaffSessionAction />
      </div>
    </header>
    <el-table :data="batches" style="margin-top:16px">
      <el-table-column prop="id" label="批次" width="70" />
      <el-table-column prop="fileName" label="文件" />
      <el-table-column prop="status" label="状态" width="140" />
      <el-table-column prop="successCount" label="成功" width="80" />
      <el-table-column prop="failCount" label="失败" width="80" />
      <el-table-column prop="failDetailJson" label="失败明细" />
      <el-table-column prop="createdAt" label="时间" width="180" />
    </el-table>
  </div>
</template>

<style scoped>
.page { padding: 24px 32px; max-width: 1500px; margin: 0 auto; }
header { display: flex; justify-content: space-between; gap: 16px; }
h2 { margin: 0; color: #1f4e3d; }
.sub { margin: 4px 0 0; color: #667; font-size: 14px; }
.row { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
</style>
