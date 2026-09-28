<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { authFailRedirectPath, isPlatformRole } from '../authSession'
import StaffNav from '../components/StaffNav.vue'
import StaffSessionAction from '../components/StaffSessionAction.vue'

const OPTION_LABELS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'

const router = useRouter()
const isPlatform = computed(() => isPlatformRole())

const propertyList = ref<any[]>([])
const committeeList = ref<any[]>([])
const loading = ref(false)
const publishing = ref(false)

const title = ref('')
const background = ref('')
const options = ref(['同意', '反对'])
const startAt = ref<string>('')
const endAt = ref<string>('')

const statsVisible = ref(false)
const statsTitle = ref('')
const statsRows = ref<{ optionText: string; votes: number; pct: number }[]>([])
const statsBallots = ref<{ roomLabel: string; voterName: string; optionText: string; votedAt: string }[]>([])
const statsTotal = ref(0)

function pad(n: number) {
  return String(n).padStart(2, '0')
}

function toLocalApi(dt: string | Date) {
  const d = typeof dt === 'string' ? new Date(dt) : dt
  if (Number.isNaN(d.getTime())) return ''
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function formatDisplay(v: string | null | undefined) {
  if (!v) return '—'
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return String(v).replace('T', ' ').slice(0, 16)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function defaultRange() {
  const s = new Date()
  s.setSeconds(0, 0)
  const e = new Date(s.getTime() + 7 * 86400000)
  startAt.value = toLocalApi(s)
  endAt.value = toLocalApi(e)
}

function resetForm() {
  title.value = ''
  background.value = ''
  options.value = ['同意', '反对']
  defaultRange()
}

function optionLabel(i: number) {
  return OPTION_LABELS[i] || String(i + 1)
}

function addOption() {
  if (options.value.length >= 10) {
    ElMessage.warning('最多 10 个选项')
    return
  }
  options.value.push('')
}

function removeOption(i: number) {
  if (options.value.length <= 2) {
    ElMessage.warning('至少保留 2 个选项')
    return
  }
  options.value.splice(i, 1)
}

function statusLabel(s: string) {
  return ({ ONGOING: '进行中', ENDED: '已结束' } as Record<string, string>)[s] || s || '—'
}

function statusTagType(s: string) {
  return s === 'ONGOING' ? 'success' : 'info'
}

const canPublish = computed(() => {
  const opts = options.value.map((o) => o.trim()).filter(Boolean)
  return (
    title.value.trim().length > 0 &&
    background.value.trim().length > 0 &&
    !!startAt.value &&
    !!endAt.value &&
    opts.length >= 2
  )
})

async function load() {
  loading.value = true
  try {
    const { data } = await api.get('/staff/votes', { params: { page: 1, pageSize: 50 } })
    if (data.code === 0) {
      // 仅物业来源，防止旧数据/旧接口混入业委会票
      propertyList.value = (data.data?.list || []).filter((v: any) =>
        ['PROPERTY_MANAGER', 'STAFF', 'PLATFORM'].includes(String(v.creatorRole || ''))
      )
    } else {
      ElMessage.error(data.message)
      if (data.code === 40101 || data.code === 40301) router.push(authFailRedirectPath())
      propertyList.value = []
    }
    if (isPlatform.value) {
      try {
        const res = await api.get('/staff/votes/committee', { params: { page: 1, pageSize: 50 } })
        if (res.data.code === 0) {
          committeeList.value = (res.data.data?.list || []).filter(
            (v: any) => String(v.creatorRole || '') === 'COMMITTEE'
          )
        } else committeeList.value = []
      } catch {
        committeeList.value = []
      }
    } else {
      committeeList.value = []
    }
  } finally {
    loading.value = false
  }
}

async function createOne() {
  const opts = options.value.map((o) => o.trim()).filter(Boolean)
  if (!title.value.trim()) {
    ElMessage.warning('请填写标题')
    return
  }
  if (!background.value.trim()) {
    ElMessage.warning('请填写背景说明')
    return
  }
  if (opts.length < 2) {
    ElMessage.warning('至少填写 2 个有效选项')
    return
  }
  if (!startAt.value || !endAt.value) {
    ElMessage.warning('请选择开始与结束时间')
    return
  }
  if (new Date(endAt.value) <= new Date(startAt.value)) {
    ElMessage.warning('结束时间须晚于开始时间')
    return
  }
  publishing.value = true
  try {
    const { data } = await api.post('/staff/votes', {
      title: title.value.trim(),
      background: background.value.trim(),
      startAt: toLocalApi(startAt.value),
      endAt: toLocalApi(endAt.value),
      options: opts,
    })
    if (data.code === 0) {
      ElMessage.success('投票已发布')
      resetForm()
      load()
    } else ElMessage.error(data.message)
  } finally {
    publishing.value = false
  }
}

async function showStats(row: any) {
  const { data } = await api.get(`/votes/${row.id}/stats`)
  if (data.code !== 0) {
    ElMessage.error(data.message)
    return
  }
  const total = Number(data.data?.totalBallots || 0)
  const rows = (data.data?.options || []).map((o: any) => {
    const votes = Number(o.votes || 0)
    return {
      optionText: o.optionText || '—',
      votes,
      pct: total > 0 ? Math.round((votes / total) * 1000) / 10 : 0,
    }
  })
  const ballots = (data.data?.ballots || []).map((b: any) => ({
    roomLabel: b.roomLabel || '—',
    voterName: b.voterName || '—',
    optionText: b.optionText || '—',
    votedAt: formatDisplay(b.votedAt),
  }))
  statsTitle.value = row.title || '投票结果'
  statsTotal.value = total
  statsRows.value = rows
  statsBallots.value = ballots
  statsVisible.value = true
}

async function remove(id: number) {
  try {
    await ElMessageBox.confirm('确认删除该投票？删除后不可恢复。', '删除投票', { type: 'warning' })
  } catch {
    return
  }
  const { data } = await api.delete(`/votes/${id}`)
  if (data.code === 0) {
    ElMessage.success('已删除')
    load()
  } else ElMessage.error(data.message)
}

onMounted(() => {
  defaultRange()
  load()
})
</script>

<template>
  <div class="page" v-loading="loading">
    <header>
      <div class="hd-top">
        <div>
          <h2>业主投票</h2>
          <p class="sub">物业经理发起物业投票；业委会投票仅平台可查看；结果含各房业主选票明细</p>
        </div>
        <StaffSessionAction />
      </div>
      <StaffNav />
    </header>

    <section class="block create">
      <h3>发起物业投票</h3>

      <el-form label-position="top" class="create-form" @submit.prevent="createOne">
        <el-form-item label="标题" required>
          <el-input v-model="title" maxlength="64" show-word-limit placeholder="例如：是否同意增设充电桩" />
        </el-form-item>

        <el-form-item label="背景说明" required>
          <el-input
            v-model="background"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            placeholder="向业主说明本次投票事由、依据与注意事项"
          />
        </el-form-item>

        <el-form-item label="投票选项" required>
          <p class="field-hint">至少 2 项，最多 10 项；业主投票时单选其一</p>
          <div class="option-list">
            <div v-for="(_opt, i) in options" :key="i" class="option-row">
              <span class="opt-badge">{{ optionLabel(i) }}</span>
              <el-input v-model="options[i]" maxlength="40" :placeholder="`选项 ${optionLabel(i)}`" />
              <el-button link type="danger" :disabled="options.length <= 2" @click="removeOption(i)">删除</el-button>
            </div>
          </div>
          <el-button class="add-opt" @click="addOption">+ 添加选项</el-button>
        </el-form-item>

        <div class="time-row">
          <el-form-item label="开始时间" required class="time-item">
            <el-date-picker
              v-model="startAt"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ss"
              format="YYYY-MM-DD HH:mm"
              placeholder="选择开始时间"
              style="width:100%"
            />
          </el-form-item>
          <el-form-item label="结束时间" required class="time-item">
            <el-date-picker
              v-model="endAt"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm:ss"
              format="YYYY-MM-DD HH:mm"
              placeholder="选择结束时间"
              style="width:100%"
            />
          </el-form-item>
        </div>

        <div class="form-actions">
          <el-button @click="resetForm">清空</el-button>
          <el-button type="primary" :loading="publishing" :disabled="!canPublish" @click="createOne">
            发布投票
          </el-button>
        </div>
      </el-form>
    </section>

    <section class="block">
      <div class="sec-head">
        <h3>物业投票列表</h3>
        <el-button @click="load">刷新</el-button>
      </div>
      <el-table :data="propertyList" size="small">
        <el-table-column type="index" label="序号" width="60" :index="(i: number) => i + 1" />
        <el-table-column prop="title" label="标题" min-width="180" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTagType(row.status)" effect="plain">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="投票时间" min-width="220">
          <template #default="{ row }">
            {{ formatDisplay(row.startAt) }} ~ {{ formatDisplay(row.endAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="showStats(row)">结果</el-button>
            <el-button link type="danger" @click="remove(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <p v-if="!loading && !propertyList.length" class="empty">暂无物业投票</p>
    </section>

    <section v-if="isPlatform" class="block">
      <div class="sec-head">
        <h3>业委会投票列表</h3>
        <span class="sec-tip">仅平台管理员可见</span>
      </div>
      <el-table :data="committeeList" size="small">
        <el-table-column type="index" label="序号" width="60" :index="(i: number) => i + 1" />
        <el-table-column prop="title" label="标题" min-width="180" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTagType(row.status)" effect="plain">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="投票时间" min-width="220">
          <template #default="{ row }">
            {{ formatDisplay(row.startAt) }} ~ {{ formatDisplay(row.endAt) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="showStats(row)">结果</el-button>
            <el-button link type="danger" @click="remove(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <p v-if="!loading && !committeeList.length" class="empty">暂无业委会投票</p>
    </section>

    <el-dialog v-model="statsVisible" :title="statsTitle" width="720px">
      <p class="stats-total">合计 {{ statsTotal }} 票（按房屋套数）</p>
      <div v-if="statsRows.length" class="stats-list">
        <div v-for="(row, i) in statsRows" :key="i" class="stats-row">
          <div class="stats-head">
            <span class="stats-name">{{ optionLabel(i) }}. {{ row.optionText }}</span>
            <span class="stats-num">{{ row.votes }} 票 · {{ row.pct }}%</span>
          </div>
          <div class="bar-track">
            <div class="bar-fill" :style="{ width: `${row.pct}%` }"></div>
          </div>
        </div>
      </div>
      <p v-else class="empty">暂无选票</p>

      <h4 class="ballot-hd">各房业主选票</h4>
      <el-table v-if="statsBallots.length" :data="statsBallots" size="small" max-height="320">
        <el-table-column type="index" label="#" width="48" :index="(i: number) => i + 1" />
        <el-table-column prop="roomLabel" label="房屋" min-width="160" />
        <el-table-column prop="voterName" label="业主" width="120" />
        <el-table-column prop="optionText" label="所选选项" min-width="120" />
        <el-table-column prop="votedAt" label="投票时间" width="150" />
      </el-table>
      <p v-else class="empty">尚无业主投票</p>

      <template #footer>
        <el-button type="primary" @click="statsVisible = false">关闭</el-button>
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
}
.sec-tip { font-size: 12px; color: #889; }
h3 { margin: 0 0 14px; font-size: 15px; color: #1f4e3d; }
.sec-head h3 { margin: 0; }
.create-form { max-width: 720px; }
.field-hint { margin: 0 0 10px; font-size: 12px; color: #889; line-height: 1.4; }
.option-list { display: flex; flex-direction: column; gap: 8px; width: 100%; }
.option-row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
}
.opt-badge {
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: #e7efe9;
  color: #1f4e3d;
  font-size: 13px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.add-opt { margin-top: 10px; }
.time-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.time-item { margin-bottom: 18px; }
.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 4px;
  padding-top: 8px;
  border-top: 1px solid #eef2ef;
}
.stats-total { margin: 0 0 16px; font-size: 13px; color: #5c6f68; }
.stats-list { display: flex; flex-direction: column; gap: 14px; }
.stats-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 6px;
  font-size: 13px;
}
.stats-name { color: #2a4a3c; font-weight: 500; }
.stats-num { color: #6a7a74; flex-shrink: 0; }
.bar-track {
  height: 8px;
  border-radius: 999px;
  background: #eef3f0;
  overflow: hidden;
}
.bar-fill {
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, #3d7a66, #1f4e3d);
  min-width: 0;
  transition: width 0.25s ease;
}
.ballot-hd {
  margin: 24px 0 12px;
  font-size: 14px;
  color: #1f4e3d;
}
.empty { margin: 12px 0; color: #99a; font-size: 13px; text-align: center; }
@media (max-width: 720px) {
  .time-row { grid-template-columns: 1fr; }
}
</style>
