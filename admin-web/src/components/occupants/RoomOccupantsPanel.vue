<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../../api'
import { RESIDENT_ROLE_OPTIONS, residentRoleLabel } from '../../residentRoles'

const props = defineProps<{
  roomId: number
}>()

const emit = defineEmits<{
  changed: []
}>()

type MobileLookup = {
  exists: boolean
  userId?: number
  mobile?: string
  realName?: string
  hasIdCard?: boolean
  idCardMasked?: string
}

const loading = ref(false)
const saving = ref(false)
const lookingUp = ref(false)
const roomOccupants = ref<any[]>([])
const editing = ref<any>(null)
const originalMobile = ref('')
/** 全库手机号匹配（非本房编辑时）；exists=true 则姓名只读带出 */
const matchedUser = ref<MobileLookup | null>(null)
const form = ref({
  mobile: '',
  realName: '',
  role: 'OWNER',
  idCardNo: '',
})
const committeeSavingUserId = ref<number | null>(null)

/** 与后端 DefaultWebPassword 一致；空密码时服务端也用此值 */
const DEFAULT_APP_PASSWORD = 'linliyuan123'
const appPwdVisible = ref(false)
const appPwdSaving = ref(false)
const appPwdForm = ref({
  userId: 0,
  mobile: '',
  realName: '',
  newPassword: DEFAULT_APP_PASSWORD,
})

let lookupTimer: ReturnType<typeof setTimeout> | null = null
let lookupSeq = 0

/** 大陆手机号：1 开头共 11 位（查号与保存一致） */
function isMobileOk(m: string) {
  return /^1\d{10}$/.test(m)
}

const needsIdCard = computed(() => form.value.role === 'OWNER')
const formHeading = computed(() => (editing.value ? '编辑绑定（改角色/资料）' : '新增绑定'))

/** 已有账号且已有姓名 → 本表单不改名（改名走「我的」或另行维护） */
const nameReadOnly = computed(() => {
  if (editing.value) return !!(editing.value.realName || '').trim()
  if (matchedUser.value?.exists) return !!(matchedUser.value.realName || '').trim()
  return false
})

const nameRequired = computed(() => !nameReadOnly.value)

/** 已有身份证：匹配时只读展示；编辑本房住户仍可改正文 */
const idCardReadOnly = computed(() => {
  if (editing.value) return false
  return !!matchedUser.value?.exists && !!matchedUser.value.hasIdCard
})

const hasStoredIdCard = computed(() => {
  if (editing.value?.idCardNo) return true
  if (matchedUser.value?.exists && matchedUser.value.hasIdCard) return true
  return false
})

const COMMITTEE_OPTIONS = [
  { value: '', label: '无业委会职务' },
  { value: 'DIRECTOR', label: '业委会主任' },
  { value: 'MEMBER', label: '业委会成员' },
  { value: 'ACTIVIST', label: '业委会积极分子' },
]

function clearLookupTimer() {
  if (lookupTimer != null) {
    clearTimeout(lookupTimer)
    lookupTimer = null
  }
}

function resetForm() {
  clearLookupTimer()
  lookupSeq += 1
  editing.value = null
  originalMobile.value = ''
  matchedUser.value = null
  lookingUp.value = false
  form.value = { mobile: '', realName: '', role: 'OWNER', idCardNo: '' }
}

async function loadRoomOccupants() {
  if (!props.roomId) {
    roomOccupants.value = []
    return
  }
  loading.value = true
  try {
    const { data } = await api.get('/staff/occupants', {
      params: { page: 1, pageSize: 100, roomId: props.roomId },
    })
    if (data.code === 0) {
      roomOccupants.value = data.data.list || []
    } else ElMessage.error(data.message)
  } finally {
    loading.value = false
  }
}

function openEdit(row: any) {
  clearLookupTimer()
  matchedUser.value = null
  lookingUp.value = false
  editing.value = row
  form.value = {
    mobile: row.mobile || '',
    realName: row.realName || '',
    role: row.residentRole || 'OWNER',
    idCardNo: row.idCardNo || '',
  }
  originalMobile.value = row.mobile || ''
}

async function lookupByMobile(mobile: string) {
  const seq = ++lookupSeq
  lookingUp.value = true
  try {
    const { data } = await api.get('/staff/users/by-mobile', { params: { mobile } })
    if (seq !== lookupSeq) return
    if (data.code !== 0) {
      matchedUser.value = null
      ElMessage.error(data.message)
      return
    }
    const hit = data.data as MobileLookup
    matchedUser.value = hit
    if (hit?.exists) {
      form.value.realName = hit.realName || ''
      if (hit.hasIdCard) form.value.idCardNo = ''
    }
  } catch {
    if (seq === lookupSeq) matchedUser.value = null
  } finally {
    if (seq === lookupSeq) lookingUp.value = false
  }
}

/** 11 位手机：本房已绑 → 编辑；否则查全库匹配。 */
watch(
  () => form.value.mobile,
  (raw) => {
    if (editing.value) return
    clearLookupTimer()
    const m = String(raw || '').trim()
    if (!isMobileOk(m)) {
      matchedUser.value = null
      lookingUp.value = false
      lookupSeq += 1
      return
    }
    const roomHit = roomOccupants.value.find((r) => (r.mobile || '').trim() === m)
    if (roomHit) {
      openEdit(roomHit)
      return
    }
    lookupTimer = setTimeout(() => lookupByMobile(m), 280)
  },
)

onBeforeUnmount(() => {
  clearLookupTimer()
  lookupSeq += 1
})

async function save() {
  if (!props.roomId) {
    ElMessage.warning('未选中房屋')
    return
  }
  const mobile = form.value.mobile.trim()
  if (!isMobileOk(mobile)) {
    ElMessage.warning('手机号须为 1 开头的 11 位数字')
    return
  }
  if (lookingUp.value) {
    ElMessage.warning('正在匹配手机号，请稍候')
    return
  }
  if (nameRequired.value && !form.value.realName.trim()) {
    ElMessage.warning('请填写姓名')
    return
  }
  if (
    needsIdCard.value &&
    !form.value.idCardNo.trim() &&
    !hasStoredIdCard.value
  ) {
    ElMessage.warning('业主须填写身份证')
    return
  }
  saving.value = true
  try {
    const body: Record<string, unknown> = {
      action: 'BIND',
      roomId: props.roomId,
      mobile,
      role: form.value.role,
    }
    if (editing.value?.userId) body.userId = editing.value.userId
    // 已有姓名不改名：不传 realName，避免覆盖
    if (!nameReadOnly.value && form.value.realName.trim()) {
      body.realName = form.value.realName.trim()
    }
    if (!idCardReadOnly.value && form.value.idCardNo.trim()) {
      body.idCardNo = form.value.idCardNo.trim()
    }
    const { data } = await api.post('/staff/fixes/occupants', body)
    if (data.code === 0) {
      const mobileChanged =
        !!editing.value && mobile !== (originalMobile.value || '').trim()
      ElMessage.success(
        mobileChanged
          ? '已更新。手机号已变更，对方须用新号重新微信授权登录小程序'
          : editing.value
            ? '已更新'
            : matchedUser.value?.exists
              ? '已绑定到已有账号'
              : '已绑定',
      )
      resetForm()
      await loadRoomOccupants()
      emit('changed')
    } else ElMessage.error(data.message)
  } finally {
    saving.value = false
  }
}

async function deactivate(row: any) {
  try {
    await ElMessageBox.confirm(
      `确定解除 ${row.realName || row.mobile} 与本房的绑定？` +
        (row.residentRole === 'OWNER' && row.committeeTitle
          ? '（若其在本小区已无其他业主身份，将同时解除业委会职务）'
          : ''),
      '解除绑定',
      { type: 'warning', confirmButtonText: '解除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  const { data } = await api.post('/staff/fixes/occupants', {
    action: 'DEACTIVATE',
    roomId: row.roomId || props.roomId,
    occupantId: row.id,
  })
  if (data.code === 0) {
    ElMessage.success('已解除绑定')
    resetForm()
    await loadRoomOccupants()
    emit('changed')
  } else ElMessage.error(data.message)
}

async function setCommittee(row: any, title: string) {
  if (row.residentRole !== 'OWNER') {
    ElMessage.warning('仅业主可设业委会职务')
    return
  }
  committeeSavingUserId.value = row.userId
  try {
    const { data } = await api.put(`/staff/users/${row.userId}/committee`, {
      title: title || null,
    })
    if (data.code === 0) {
      ElMessage.success(title ? '已设置业委会职务' : '已解除业委会职务')
      await loadRoomOccupants()
      emit('changed')
    } else ElMessage.error(data.message)
  } finally {
    committeeSavingUserId.value = null
  }
}

function openAppPwd(row: any) {
  if (!row?.userId) {
    ElMessage.warning('无用户 ID，无法设密码')
    return
  }
  appPwdForm.value = {
    userId: row.userId,
    mobile: row.mobile || '',
    realName: row.realName || '',
    newPassword: DEFAULT_APP_PASSWORD,
  }
  appPwdVisible.value = true
}

async function saveAppPwd() {
  const pwd = (appPwdForm.value.newPassword || '').trim()
  if (pwd && pwd.length < 6) {
    ElMessage.warning('临时密码至少 6 位，或清空以使用默认密码')
    return
  }
  appPwdSaving.value = true
  try {
    const { data } = await api.post(`/staff/users/${appPwdForm.value.userId}/reset-app-password`, {
      newPassword: pwd || undefined,
    })
    if (data.code === 0) {
      const shown = data.data?.tempPassword || pwd || DEFAULT_APP_PASSWORD
      ElMessage.success(
        `App 临时密码已设为 ${shown}，请当面告知本人；对方用 App 首次登录须改密`,
      )
      appPwdVisible.value = false
    } else ElMessage.error(data.message || '设置失败')
  } finally {
    appPwdSaving.value = false
  }
}

watch(
  () => props.roomId,
  () => {
    resetForm()
    loadRoomOccupants()
  },
  { immediate: true },
)

defineExpose({ reload: loadRoomOccupants, resetForm })
</script>

<template>
  <div class="room-occupants" v-loading="loading">
    <div class="detail-section-head">
      <h4>本房住户</h4>
      <span class="count">{{ roomOccupants.length }} 人</span>
    </div>
    <p class="form-tip">
      同一人在本房只能有一个角色；解绑后若无其他住户绑定则为游客。仅业主可任命业委会职务。
      住户使用 Android App 时须由物业设置临时密码（与 Web 密码共用），对方首次登录 App 须改密。
    </p>

    <div v-if="roomOccupants.length" class="occupant-cards">
      <article
        v-for="row in roomOccupants"
        :key="row.id"
        class="occupant-card"
        :class="{ editing: editing?.id === row.id }"
      >
        <div class="occupant-top">
          <div class="occupant-who">
            <strong class="name">{{ row.realName || '未命名' }}</strong>
            <span class="role-pill">{{ residentRoleLabel(row.residentRole) }}</span>
          </div>
          <div class="occupant-btns">
            <el-button type="primary" link @click="openEdit(row)">编辑</el-button>
            <el-button type="primary" link @click="openAppPwd(row)">设App密码</el-button>
            <el-button type="danger" link @click="deactivate(row)">解除</el-button>
          </div>
        </div>
        <dl class="occupant-meta">
          <div>
            <dt>手机</dt>
            <dd>{{ row.mobile || '—' }}</dd>
          </div>
          <div>
            <dt>身份证</dt>
            <dd>{{ row.idCardNo || '—' }}</dd>
          </div>
          <div>
            <dt>来源</dt>
            <dd>{{ row.source || '—' }}</dd>
          </div>
        </dl>
        <div v-if="row.residentRole === 'OWNER'" class="committee-row">
          <label>业委会职务</label>
          <el-select
            :model-value="row.committeeTitle || ''"
            placeholder="选择职务"
            :loading="committeeSavingUserId === row.userId"
            @change="(v: string) => setCommittee(row, v)"
          >
            <el-option
              v-for="o in COMMITTEE_OPTIONS"
              :key="o.value || 'none'"
              :label="o.label"
              :value="o.value"
            />
          </el-select>
        </div>
      </article>
    </div>
    <p v-else class="empty-inline">本房暂无有效住户，可在下方新增绑定。</p>

    <h4 class="form-heading">{{ formHeading }}</h4>
    <el-form class="bind-form" label-position="top" @submit.prevent="save">
      <div class="form-grid">
        <el-form-item label="手机号" required>
          <el-input
            v-model="form.mobile"
            placeholder="1 开头共 11 位，系统内唯一"
            maxlength="11"
          />
          <p v-if="lookingUp" class="lookup-status">正在匹配账号…</p>
        </el-form-item>
        <el-form-item label="姓名" :required="nameRequired">
          <el-input
            v-model="form.realName"
            :placeholder="nameReadOnly ? '已有账号姓名' : '真实姓名'"
            :readonly="nameReadOnly"
            :disabled="nameReadOnly"
          />
        </el-form-item>
        <el-form-item label="角色" required>
          <el-select v-model="form.role" style="width:100%">
            <el-option v-for="o in RESIDENT_ROLE_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="身份证" :required="needsIdCard && !hasStoredIdCard">
          <el-input
            v-if="idCardReadOnly"
            :model-value="matchedUser?.idCardMasked || '已登记'"
            readonly
            disabled
          />
          <el-input
            v-else
            v-model="form.idCardNo"
            :placeholder="needsIdCard ? '业主必填' : '选填'"
          />
        </el-form-item>
      </div>

      <div
        v-if="!editing && matchedUser?.exists"
        class="match-card"
      >
        <div class="match-card-title">已匹配系统账号</div>
        <dl class="match-card-meta">
          <div>
            <dt>姓名</dt>
            <dd>{{ matchedUser.realName || '未填写' }}</dd>
          </div>
          <div>
            <dt>手机</dt>
            <dd>{{ matchedUser.mobile }}</dd>
          </div>
          <div>
            <dt>身份证</dt>
            <dd>{{ matchedUser.hasIdCard ? (matchedUser.idCardMasked || '已登记') : '未登记' }}</dd>
          </div>
        </dl>
        <p class="match-card-note">保存将把该账号绑定到本房；姓名不在此修改。</p>
      </div>
      <p v-else-if="!editing && matchedUser && !matchedUser.exists && isMobileOk(form.mobile.trim())" class="id-hint match-hint new-user-hint">
        新手机号：将创建账号并绑定本房，请填写姓名。
      </p>
      <p v-if="editing" class="id-hint match-hint">
        正在编辑本房已有住户；改角色会覆盖原角色，不会新增第二条绑定。
        <template v-if="nameReadOnly"> 姓名只读，改名请让对方在小程序「我的」修改。</template>
      </p>
      <p class="id-hint">身份证：业主必填；业主成员 / 租户 / 租户成员选填。编辑时若更改手机号，对方须用新号重新微信授权登录。</p>
      <el-form-item>
        <el-button type="primary" :loading="saving" :disabled="lookingUp" @click="save">
          {{ editing ? '保存修改' : matchedUser?.exists ? '确认绑定已有账号' : '保存绑定' }}
        </el-button>
        <el-button v-if="editing" @click="resetForm">取消编辑</el-button>
      </el-form-item>
    </el-form>

    <el-dialog
      v-model="appPwdVisible"
      title="设置 App 临时密码"
      width="420px"
      destroy-on-close
      @closed="appPwdForm.newPassword = DEFAULT_APP_PASSWORD"
    >
      <p class="form-tip" style="margin-top: 0">
        {{ appPwdForm.realName || '住户' }}（{{ appPwdForm.mobile || '无手机号' }}）
        · 与 Web 登录密码共用；设置后对方须用 App 登录并强制改密。小程序微信登录不受影响。
      </p>
      <el-form label-position="top">
        <el-form-item label="临时密码">
          <el-input
            v-model="appPwdForm.newPassword"
            type="password"
            show-password
            :placeholder="`默认 ${DEFAULT_APP_PASSWORD}，至少 6 位`"
          />
          <p class="id-hint">已预填默认密码 {{ DEFAULT_APP_PASSWORD }}；清空保存则使用系统默认值。</p>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="appPwdVisible = false">取消</el-button>
        <el-button type="primary" :loading="appPwdSaving" @click="saveAppPwd">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.detail-section-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 4px;
}
.detail-section-head h4 { margin: 0; font-size: 15px; color: #1f4e3d; }
.detail-section-head .count {
  font-size: 12px;
  color: #7a8c85;
}
.form-tip { margin: 0 0 12px; font-size: 13px; color: #5c6f68; line-height: 1.55; }
.occupant-cards {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 18px;
}
.occupant-card {
  background: #fff;
  border: 1px solid #dfe8e3;
  padding: 14px 16px;
}
.occupant-card.editing {
  border-color: #8fafa0;
  box-shadow: inset 3px 0 0 #2f6b55;
}
.occupant-top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
}
.occupant-who {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.occupant-who .name {
  font-size: 16px;
  color: #1f3d32;
}
.role-pill {
  display: inline-block;
  padding: 1px 8px;
  font-size: 12px;
  color: #2f6b55;
  background: #e8f2ec;
  border-radius: 2px;
  flex-shrink: 0;
}
.occupant-btns {
  display: flex;
  gap: 4px;
  flex-shrink: 0;
}
.occupant-meta {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px 16px;
  margin: 12px 0 0;
}
.occupant-meta > div { min-width: 0; }
.occupant-meta dt {
  margin: 0;
  font-size: 12px;
  color: #8a9a93;
}
.occupant-meta dd {
  margin: 2px 0 0;
  font-size: 13px;
  color: #2a4a3c;
  word-break: break-all;
}
.committee-row {
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr);
  align-items: center;
  gap: 10px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid #eef3f0;
}
.committee-row label {
  font-size: 13px;
  color: #4a5c55;
}
.committee-row :deep(.el-select) {
  width: 100%;
}
.empty-inline {
  margin: 0 0 16px;
  padding: 16px;
  text-align: center;
  font-size: 13px;
  color: #889;
  background: #fff;
  border: 1px dashed #d5e0da;
}
.form-heading { margin: 8px 0 10px; font-size: 15px; color: #1f4e3d; }
.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 14px;
}
.form-grid .el-form-item { margin-bottom: 12px; }
.id-hint {
  margin: -4px 0 12px;
  font-size: 12px;
  color: #7a8c85;
  line-height: 1.4;
}
.lookup-status {
  margin: 4px 0 0;
  font-size: 12px;
  color: #7a8c85;
}
.match-hint {
  color: #8a5a2b;
  background: #fbf6ef;
  padding: 8px 10px;
  margin-bottom: 10px;
  border-radius: 2px;
}
.new-user-hint {
  color: #2f6b55;
  background: #eef6f1;
}
.match-card {
  margin: 0 0 12px;
  padding: 12px 14px;
  background: #f5f9f7;
  border: 1px solid #c5d9cf;
  border-radius: 2px;
}
.match-card-title {
  font-size: 13px;
  font-weight: 600;
  color: #1f4e3d;
  margin-bottom: 8px;
}
.match-card-meta {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px 14px;
  margin: 0;
}
.match-card-meta dt {
  margin: 0;
  font-size: 12px;
  color: #8a9a93;
}
.match-card-meta dd {
  margin: 2px 0 0;
  font-size: 13px;
  color: #2a4a3c;
  word-break: break-all;
}
.match-card-note {
  margin: 10px 0 0;
  font-size: 12px;
  color: #5c6f68;
  line-height: 1.4;
}
@media (max-width: 720px) {
  .form-grid { grid-template-columns: 1fr; }
  .occupant-meta { grid-template-columns: 1fr; }
  .committee-row { grid-template-columns: 1fr; }
  .match-card-meta { grid-template-columns: 1fr; }
}
</style>
