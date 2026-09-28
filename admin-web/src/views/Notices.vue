<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import StaffNav from '../components/StaffNav.vue'
import StaffSessionAction from '../components/StaffSessionAction.vue'
import NoticeRichEditor from '../components/NoticeRichEditor.vue'
import {
  extractAttachmentIds,
  hasNoticeBody,
  legacyToHtml,
  rewriteAttachmentUrls,
} from '../noticeContent'

const VIS_OPTIONS = [
  { value: 'PUBLIC_ALL', label: '全部可见' },
  { value: 'RESIDENTS', label: '住户' },
  { value: 'OWNERS', label: '业主' },
]

/** Banner 一共 5 个位置；为空表示不上 Banner */
const BANNER_OPTIONS = [
  { value: 1, label: '第 1 个' },
  { value: 2, label: '第 2 个' },
  { value: 3, label: '第 3 个' },
  { value: 4, label: '第 4 个' },
  { value: 5, label: '第 5 个' },
]

const list = ref<any[]>([])
const keyword = ref('')
const loading = ref(false)
const saving = ref(false)
const uploading = ref(false)

const editingId = ref<number | null>(null)
const form = ref({
  title: '',
  content: '',
  visibility: 'PUBLIC_ALL',
  coverAttachmentId: null as number | null,
  showOnBanner: false,
  bannerOrder: null as number | null,
})
const coverPreview = ref('')
const formRef = ref<HTMLElement | null>(null)
const editorRef = ref<InstanceType<typeof NoticeRichEditor> | null>(null)
const detailOpen = ref(false)
const detail = ref<any>(null)
const detailHtml = ref('')
const blobToId = ref(new Map<string, number>())
const objectUrls = ref<string[]>([])
const bodySrcCache = ref<Record<number, string>>({})

const formHeading = computed(() => (editingId.value ? `编辑公告 #${editingId.value}` : '发布公告'))
const canSave = computed(() => form.value.title.trim().length > 0 && hasNoticeBody(form.value.content))
/** 选中了 Banner 位置才需要（也才展示）封面 */
const onBanner = computed(() => form.value.bannerOrder != null)
/** 顺序优先级高于旧开关：有顺序即上 Banner；无顺序时沿用开关，保证旧公告可正常维护 */
const showCoverArea = computed(() => onBanner.value || form.value.showOnBanner)
const filteredList = computed(() => {
  const q = keyword.value.trim().toLowerCase()
  if (!q) return list.value
  return list.value.filter((row) => String(row.title || '').toLowerCase().includes(q))
})

function visLabel(v: string) {
  return VIS_OPTIONS.find((o) => o.value === v)?.label || v || '—'
}
function formatTime(v: string) {
  if (!v) return '—'
  return String(v).replace('T', ' ').slice(0, 19)
}

function revokeBlobs() {
  for (const u of objectUrls.value) URL.revokeObjectURL(u)
  objectUrls.value = []
  bodySrcCache.value = {}
  blobToId.value = new Map()
}

async function loadBlobUrl(id: number): Promise<string> {
  if (bodySrcCache.value[id]) return bodySrcCache.value[id]
  const res = await api.get(`/attachments/${id}`, { responseType: 'blob' })
  const url = URL.createObjectURL(res.data)
  objectUrls.value.push(url)
  bodySrcCache.value = { ...bodySrcCache.value, [id]: url }
  blobToId.value.set(url, id)
  return url
}

async function load() {
  loading.value = true
  try {
    const { data } = await api.get('/staff/notices', { params: { page: 1, pageSize: 100 } })
    if (data.code !== 0) {
      ElMessage.error(data.message)
      list.value = []
      return
    }
    const rows = data.data?.list || (Array.isArray(data.data) ? data.data : [])
    const withCovers = await Promise.all(
      rows.map(async (row: any) => {
        let coverThumb = ''
        if (row.coverAttachmentId) {
          try {
            coverThumb = await loadBlobUrl(row.coverAttachmentId)
          } catch {
            coverThumb = ''
          }
        }
        return { ...row, coverThumb }
      }),
    )
    list.value = withCovers
  } finally {
    loading.value = false
  }
}

function resetForm() {
  form.value = {
    title: '',
    content: '',
    visibility: 'PUBLIC_ALL',
    coverAttachmentId: null,
    showOnBanner: false,
    bannerOrder: null,
  }
  coverPreview.value = ''
  editingId.value = null
}

async function openEdit(row: any) {
  detailOpen.value = false
  editingId.value = row.id
  form.value = {
    title: row.title || '',
    content: row.content || '',
    visibility: row.visibility || 'PUBLIC_ALL',
    coverAttachmentId: row.coverAttachmentId || null,
    showOnBanner: !!row.showOnBanner,
    // 未上 Banner 时不回填顺序（下架的公告顺序已失效）
    bannerOrder: row.showOnBanner ? (row.bannerOrder ?? null) : null,
  }
  coverPreview.value = row.coverThumb || ''
  if (row.coverAttachmentId && !coverPreview.value) {
    try {
      coverPreview.value = await loadBlobUrl(row.coverAttachmentId)
    } catch {
      coverPreview.value = ''
    }
  }
  await nextTick()
  formRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

async function openDetail(row: any) {
  detail.value = row
  detailOpen.value = true
  detailHtml.value = '加载中…'
  let html = legacyToHtml(row.content || '')
  const ids = extractAttachmentIds(html)
  for (const id of ids) {
    try {
      await loadBlobUrl(id)
    } catch {
      /* ignore */
    }
  }
  detailHtml.value = rewriteAttachmentUrls(html, (id) => bodySrcCache.value[id] || '')
}

async function onCoverFile(file: File) {
  uploading.value = true
  try {
    const body = new FormData()
    body.append('file', file)
    body.append('bizType', 'NOTICE')
    const { data } = await api.post('/attachments/upload', body, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    if (data.code === 0 && data.data?.id) {
      form.value.coverAttachmentId = data.data.id
      coverPreview.value = await loadBlobUrl(data.data.id)
      ElMessage.success('封面已上传')
    } else ElMessage.error(data.message || '上传失败')
  } catch {
    ElMessage.error('上传失败')
  } finally {
    uploading.value = false
  }
  return false
}

function clearCover() {
  form.value.coverAttachmentId = null
  // 没有封面就不可能上 Banner，顺序一并清掉
  form.value.showOnBanner = false
  form.value.bannerOrder = null
  coverPreview.value = ''
}

async function save() {
  if (!form.value.title.trim()) {
    ElMessage.warning('请填写标题')
    return
  }
  const content = editorRef.value?.getStorageHtml?.() || form.value.content
  if (!hasNoticeBody(content)) {
    ElMessage.warning('请填写正文或插入图片/附件')
    return
  }
  if ((onBanner.value || form.value.showOnBanner) && !form.value.coverAttachmentId) {
    ElMessage.warning('上首页 Banner 须先上传封面图')
    return
  }
  saving.value = true
  try {
    const payload: Record<string, unknown> = {
      title: form.value.title.trim(),
      content,
      noticeType: 'OTHER',
      urgency: 0,
      visibility: form.value.visibility,
      coverAttachmentId: form.value.coverAttachmentId ?? 0,
      showOnBanner: onBanner.value || form.value.showOnBanner,
      // 0 表示不指定顺序（取消 Banner）；1~5 为轮播位置
      bannerOrder: form.value.bannerOrder ?? 0,
      attachmentIds: extractAttachmentIds(content),
    }
    const { data } = editingId.value
      ? await api.put(`/staff/notices/${editingId.value}`, payload)
      : await api.post('/staff/notices', {
          ...payload,
          coverAttachmentId: form.value.coverAttachmentId || undefined,
        })
    if (data.code === 0) {
      const order = form.value.bannerOrder
      ElMessage.success(
        order
          ? `已设为第 ${order} 个 Banner，原第 ${order} 个（若有）已自动下架`
          : editingId.value
            ? '已保存'
            : '已发布',
      )
      resetForm()
      await load()
    } else ElMessage.error(data.message)
  } finally {
    saving.value = false
  }
}

async function remove(row: any) {
  try {
    await ElMessageBox.confirm(`确定删除公告「${row.title}」？`, '删除公告', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
  } catch {
    return
  }
  const { data } = await api.delete(`/staff/notices/${row.id}`)
  if (data.code === 0) {
    ElMessage.success('已删除')
    if (detailOpen.value && detail.value?.id === row.id) detailOpen.value = false
    if (editingId.value === row.id) resetForm()
    await load()
  } else ElMessage.error(data.message)
}

onMounted(load)
onBeforeUnmount(revokeBlobs)
</script>

<template>
  <div class="page" v-loading="loading">
    <header>
      <div class="hd-top">
        <div>
          <h2>公告</h2>
          <p class="sub">富文本正文支持字体、字号、图片与附件；封面 +「上 Banner」出现在小程序首页轮播</p>
        </div>
        <StaffSessionAction />
      </div>
      <StaffNav />
    </header>

    <section class="block">
      <div class="sec-head">
        <h3>公告列表</h3>
        <div class="sec-actions">
          <el-input
            v-model="keyword"
            clearable
            placeholder="按标题查找"
            style="width:220px"
          />
          <el-button @click="load">刷新</el-button>
        </div>
      </div>
      <el-table :data="filteredList" size="small" empty-text="暂无公告">
        <el-table-column type="index" label="序号" width="60" :index="(i: number) => i + 1" />
        <el-table-column label="封面" width="100">
          <template #default="{ row }">
            <div v-if="row.coverThumb" class="thumb" @click="openDetail(row)">
              <img :src="row.coverThumb" alt="封面" />
            </div>
            <span v-else class="muted">无</span>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="160" show-overflow-tooltip />
        <el-table-column label="可见范围" width="100">
          <template #default="{ row }">{{ visLabel(row.visibility) }}</template>
        </el-table-column>
        <el-table-column label="Banner" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.showOnBanner && row.bannerOrder" size="small" type="success" effect="plain">
              第 {{ row.bannerOrder }} 个
            </el-tag>
            <el-tag v-else-if="row.showOnBanner" size="small" type="success" effect="plain">是</el-tag>
            <span v-else class="muted">否</span>
          </template>
        </el-table-column>
        <el-table-column label="时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">查看</el-button>
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <section ref="formRef" class="block create">
      <div class="sec-head">
        <h3>{{ formHeading }}</h3>
        <el-button v-if="editingId" link type="danger" @click="resetForm">取消编辑</el-button>
      </div>
      <p class="field-hint">正文使用富文本编辑；图片与附件会随公告保存。附件支持图片、PDF、Office、ZIP（单文件 ≤10MB）</p>

      <el-form label-position="top" class="create-form" @submit.prevent="save">
        <el-form-item label="标题" required>
          <el-input v-model="form.title" maxlength="64" show-word-limit placeholder="公告标题" />
        </el-form-item>

        <div class="form-row">
          <el-form-item label="可见范围" required>
            <el-select v-model="form.visibility" style="width:100%; max-width:320px">
              <el-option v-for="o in VIS_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
          </el-form-item>

          <el-form-item label="Banner 顺序">
            <el-select
              v-model="form.bannerOrder"
              clearable
              placeholder="不展示"
              style="width:100%; max-width:320px"
            >
              <el-option v-for="o in BANNER_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
            <p class="hint">共 5 个位置，选择即上首页轮播；不选则不上 Banner。同一位置被占用时，旧的公告会自动下架</p>
          </el-form-item>
        </div>

        <el-form-item v-if="showCoverArea" label="封面">
          <div class="cover-row">
            <div v-if="coverPreview" class="cover-preview">
              <img :src="coverPreview" alt="封面预览" />
            </div>
            <div class="cover-actions">
              <el-upload :show-file-list="false" :before-upload="onCoverFile" accept="image/*">
                <el-button :loading="uploading">{{ coverPreview ? '更换封面' : '上传封面' }}</el-button>
              </el-upload>
              <el-button v-if="form.coverAttachmentId" link type="danger" @click="clearCover">清除</el-button>
              <p class="hint">建议横图 16:9；已选 Banner 顺序时必填</p>
            </div>
          </div>
        </el-form-item>

        <el-form-item label="正文" required>
          <NoticeRichEditor
            ref="editorRef"
            v-model="form.content"
            :blob-to-id="blobToId"
          />
        </el-form-item>

        <div class="form-actions">
          <el-button @click="resetForm">{{ editingId ? '取消编辑' : '清空' }}</el-button>
          <el-button type="primary" :loading="saving" :disabled="!canSave" @click="save">
            {{ editingId ? '保存修改' : '发布公告' }}
          </el-button>
        </div>
      </el-form>
    </section>

    <el-dialog v-model="detailOpen" title="公告详情" width="720px">
      <template v-if="detail">
        <div v-if="detail.coverThumb" class="detail-cover">
          <img :src="detail.coverThumb" alt="封面" />
        </div>
        <h3 class="detail-title">{{ detail.title }}</h3>
        <p class="detail-meta">
          {{ visLabel(detail.visibility) }}
          <template v-if="detail.showOnBanner && detail.bannerOrder">
            · Banner 第 {{ detail.bannerOrder }} 个
          </template>
          <template v-else-if="detail.showOnBanner"> · Banner</template>
          · {{ formatTime(detail.createdAt) }}
        </p>
        <div class="detail-html" v-html="detailHtml"></div>
      </template>
      <template #footer>
        <el-button @click="detailOpen = false">关闭</el-button>
        <el-button type="primary" @click="openEdit(detail)">编辑</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding: 24px 32px; max-width: 1500px; margin: 0 auto; }
header { display: block; }
.hd-top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}
h2 { margin: 0; color: #1f4e3d; }
.sub { margin: 4px 0 0; color: #667; font-size: 14px; }
.block {
  margin-top: 16px;
  background: #fff;
  border: 1px solid #dfe6e1;
  padding: 18px 20px;
}
.sec-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.sec-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
h3 { margin: 0 0 14px; font-size: 15px; color: #1f4e3d; }
.sec-head h3 { margin: 0; }
.create-form { max-width: 900px; }
.form-row { display: flex; gap: 20px; flex-wrap: wrap; }
.form-row :deep(.el-form-item) { flex: 1 1 260px; min-width: 240px; }
.field-hint { margin: -6px 0 14px; font-size: 12px; color: #889; line-height: 1.4; }
.form-actions { display: flex; gap: 10px; justify-content: flex-end; margin-top: 4px; }
.cover-row { display: flex; gap: 16px; align-items: flex-start; flex-wrap: wrap; }
.cover-preview {
  width: 240px;
  height: 135px;
  border-radius: 8px;
  overflow: hidden;
  background: #eef3f0;
  border: 1px solid #e4ebe7;
}
.cover-preview img { width: 100%; height: 100%; object-fit: cover; display: block; }
.cover-actions { display: flex; flex-direction: column; align-items: flex-start; gap: 6px; }
.hint { margin: 0; font-size: 12px; color: #8a9a93; }
.muted { color: #9aa9a2; font-size: 13px; }
.thumb {
  width: 72px;
  height: 40px;
  border-radius: 4px;
  overflow: hidden;
  background: #eef3f0;
  cursor: pointer;
}
.thumb img { width: 100%; height: 100%; object-fit: cover; display: block; }
.detail-cover {
  width: 100%;
  max-height: 280px;
  border-radius: 8px;
  overflow: hidden;
  background: #eef3f0;
  margin-bottom: 16px;
}
.detail-cover img { width: 100%; max-height: 280px; object-fit: cover; display: block; }
.detail-title { margin: 0 0 8px; color: #1f4e3d; font-size: 20px; }
.detail-meta { margin: 0 0 16px; color: #6a7a74; font-size: 13px; }
.detail-html {
  font-size: 14px;
  line-height: 1.7;
  color: #2a4a3c;
  word-break: break-word;
}
.detail-html :deep(img) {
  max-width: 100%;
  border-radius: 8px;
  margin: 8px 0;
}
.detail-html :deep(p) { margin: 0 0 10px; }
.detail-html :deep(a) { color: #0f3d34; }
</style>
