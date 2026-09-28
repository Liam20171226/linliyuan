<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { api } from '../../api'

const batches = ref<any[]>([])
const uploading = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

async function load() {
  const { data } = await api.get('/staff/import/batches')
  if (data.code === 0) batches.value = Array.isArray(data.data) ? data.data : (data.data?.list || [])
  else ElMessage.error(data.message)
}

async function downloadTemplate() {
  const res = await api.get('/staff/import/template', { responseType: 'blob' })
  const url = URL.createObjectURL(res.data)
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
    const { data } = await api.post('/staff/import/batches', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
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
  <div>
    <div class="toolbar">
      <el-button @click="downloadTemplate">下载模板</el-button>
      <el-button type="primary" :loading="uploading" @click="fileInput?.click()">上传 Excel</el-button>
      <input ref="fileInput" type="file" accept=".xlsx,.xls" style="display:none" @change="onFile" />
    </div>
    <p class="hint">下载模板 → 填写住户与房屋绑定 → 上传 Excel 批量导入</p>
    <el-table :data="batches" style="margin-top:16px">
      <el-table-column type="index" label="序号" width="60" :index="(i: number) => i + 1" />
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
.toolbar { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.hint { margin: 12px 0 0; color: #667; font-size: 14px; }
</style>
