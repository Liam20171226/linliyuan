<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api } from '../api'
import { authFailRedirectPath } from '../authSession'
import StaffNav from '../components/StaffNav.vue'
import StaffSessionAction from '../components/StaffSessionAction.vue'

/** 新建账号 / 授予客服岗 / 重置密码的默认密码；当事人首次登录 Web 管理端会被强制修改 */
const DEFAULT_PASSWORD = 'linliyuan123'

const LINE_ROLE_FALLBACK = [
  { value: 'CUSTOMER_SERVICE', label: '客服' },
  { value: 'SECURITY', label: '保安' },
  { value: 'CLEANING', label: '保洁' },
  { value: 'LANDSCAPING', label: '绿化' },
  { value: 'FACILITY_MAINT', label: '机电维修' },
]

const router = useRouter()
const list = ref<any[]>([])
/** 兼岗设置 / 添加人员 / 删除：仅物业经理或平台管理员 */
const canManage = ref(false)
/** 平台管理员进小区代管：可重置任意可登录 Web 岗位（客服 / 经理）的密码 */
const isPlatform = ref(false)
/** 当前登录人在本小区的 userId，用于"客服只能重置本人密码" */
const selfUserId = ref(0)
const loading = ref(false)
const lineOptions = ref<{ value: string; label: string }[]>([...LINE_ROLE_FALLBACK])
const saving = ref(false)

const addVisible = ref(false)
const editVisible = ref(false)
const pwdVisible = ref(false)
const pwdSaving = ref(false)
const pwdForm = ref({ userId: 0, mobile: '', realName: '', newPassword: DEFAULT_PASSWORD })
const form = ref({
  userId: 0,
  mobile: '',
  realName: '',
  tempPassword: '',
  webPassword: '',
  hasPassword: true,
  lineRoles: [] as string[],
  isManager: false,
  roleLabels: '',
})

const showActions = computed(() => canManage.value)
/** 只有客服岗与物业经理能登录 Web 管理端，只有这两种岗位才谈 Web 密码 */
const hasWebRole = (row: any) => {
  const rs: string[] = row.roles || []
  return rs.includes('CUSTOMER_SERVICE') || rs.includes('PROPERTY_MANAGER')
}
/** Web 密码三态；一线执行岗（保安/保洁/绿化/机电）不登 Web，显示 '-' */
function webPasswordText(row: any) {
  if (!hasWebRole(row)) return '-'
  if (row.mustChangePassword) return '须改密'
  if (row.hasPassword) return '已设密'
  return '未设密'
}
function webPasswordClass(row: any) {
  const t = webPasswordText(row)
  if (t === '须改密') return 'pwd-todo'
  if (t === '未设密') return 'pwd-none'
  if (t === '-') return 'pwd-na'
  return ''
}
/**
 * 重置密码的可操作范围（与后端 checkResetPasswordPermission 一致）：
 * 平台管理员 = 本小区任意可登录 Web 的岗位（客服 / 物业经理）；
 * 物业经理 = 本人 + 客服；客服 = 仅本人。
 */
function canResetRow(row: any) {
  if (isPlatform.value) return hasWebRole(row)
  if (canManage.value) {
    return row.userId === selfUserId.value || (row.roles || []).includes('CUSTOMER_SERVICE')
  }
  return selfUserId.value !== 0 && row.userId === selfUserId.value
}
/** 不可重置时的提示语，按当前操作者身份区分 */
const resetDenyText = computed(() =>
  isPlatform.value
    ? '该人员不持有可登录 Web 管理端的岗位（客服 / 物业经理），无 Web 密码可重置'
    : canManage.value
      ? '仅可重置本人或客服的 Web 登录密码'
      : '仅可重置本人密码',
)
/** 只有客服岗才能登录 Web 管理端，因此只有客服需要临时密码 */
const needPassword = computed(() => form.value.lineRoles.includes('CUSTOMER_SERVICE'))
/** 兼岗设置：原本不是客服（无登录密码）现在要勾选客服 → 必须当场设置 Web 登录密码 */
const needWebPassword = computed(
  () => form.value.lineRoles.includes('CUSTOMER_SERVICE') && form.value.hasPassword === false,
)

async function loadCatalog() {
  try {
    const { data } = await api.get('/staff/team/role-catalog')
    if (data.code === 0 && Array.isArray(data.data?.lineRoles) && data.data.lineRoles.length) {
      lineOptions.value = data.data.lineRoles
      return
    }
    lineOptions.value = [...LINE_ROLE_FALLBACK]
    if (data.code !== 0) {
      ElMessage.warning(data.message || '岗位列表接口异常，已用本地选项')
    }
  } catch {
    lineOptions.value = [...LINE_ROLE_FALLBACK]
  }
}

async function load() {
  loading.value = true
  try {
    const { data } = await api.get('/staff/team')
    if (data.code === 0) {
      const payload = data.data
      if (Array.isArray(payload)) {
        list.value = payload
        canManage.value = false
        isPlatform.value = false
        selfUserId.value = 0
      } else {
        list.value = payload?.members || []
        canManage.value = !!payload?.canManage
        isPlatform.value = !!payload?.isPlatform
        selfUserId.value = Number(payload?.selfUserId || 0)
      }
    } else if (data.code === 40101 || data.code === 40301) router.replace(authFailRedirectPath())
    else ElMessage.error(data.message || '加载失败')
  } catch {
    ElMessage.error('加载失败')
  } finally {
    loading.value = false
  }
}

function openAdd() {
  if (!canManage.value) {
    ElMessage.warning('仅物业经理或平台管理员可操作')
    return
  }
  form.value = {
    userId: 0,
    mobile: '',
    realName: '',
    tempPassword: DEFAULT_PASSWORD,
    webPassword: '',
    hasPassword: true,
    lineRoles: [],
    isManager: false,
    roleLabels: '',
  }
  addVisible.value = true
}

function openEdit(row: any) {
  if (!canManage.value) {
    ElMessage.warning('仅物业经理或平台管理员可操作')
    return
  }
  const roles = (row.roles || []).filter((r: string) => r !== 'PROPERTY_MANAGER')
  form.value = {
    userId: row.userId,
    mobile: row.mobile || '',
    realName: row.realName || '',
    tempPassword: '',
    webPassword: DEFAULT_PASSWORD,
    hasPassword: row.hasPassword !== false,
    lineRoles: [...roles],
    isManager: !!row.isManager,
    roleLabels: row.roleLabels || '',
  }
  editVisible.value = true
}

function openReset(row: any) {
  if (!canResetRow(row)) {
    ElMessage.warning(resetDenyText.value)
    return
  }
  pwdForm.value = {
    userId: row.userId,
    mobile: row.mobile || '',
    realName: row.realName || '',
    newPassword: DEFAULT_PASSWORD,
  }
  pwdVisible.value = true
}

async function saveReset() {
  if (pwdForm.value.newPassword && pwdForm.value.newPassword.length < 6) {
    ElMessage.warning('新密码至少 6 位，或留空使用默认密码')
    return
  }
  pwdSaving.value = true
  try {
    const { data } = await api.post(`/staff/team/members/${pwdForm.value.userId}/reset-password`, {
      newPassword: pwdForm.value.newPassword,
    })
    if (data.code === 0) {
      ElMessage.success(
        pwdForm.value.newPassword === DEFAULT_PASSWORD
          ? `密码已重置为默认密码 ${DEFAULT_PASSWORD}，请告知当事人；其首次登录须修改密码`
          : '密码已重置，请将新密码告知当事人；其首次登录 Web 管理端须修改密码',
      )
      pwdVisible.value = false
      load()
    } else ElMessage.error(data.message || '重置失败')
  } finally {
    pwdSaving.value = false
  }
}

async function saveAdd() {
  if (!form.value.mobile.trim()) {
    ElMessage.warning('请填写手机号')
    return
  }
  if (!form.value.lineRoles.length) {
    ElMessage.warning('请至少选择一个一线岗位')
    return
  }
  if (needPassword.value && form.value.tempPassword && form.value.tempPassword.length < 6) {
    ElMessage.warning('临时密码至少 6 位')
    return
  }
  saving.value = true
  try {
    const { data } = await api.post('/staff/team/members', {
      mobile: form.value.mobile.trim(),
      realName: form.value.realName.trim() || undefined,
      tempPassword: needPassword.value ? form.value.tempPassword || undefined : undefined,
      lineRoles: form.value.lineRoles,
    })
    if (data.code === 0) {
      const pwd = form.value.tempPassword || DEFAULT_PASSWORD
      // 后端只在「原本没密码」时才写入密码；已有密码的账号不会被覆盖，提示必须如实
      const passwordSet = data.data?.passwordSet === true
      if (passwordSet) {
        ElMessage.success(`已添加。登录密码为 ${pwd}，请告知当事人，首次登录 Web 管理端须修改密码`)
      } else if (data.data?.hasPassword) {
        ElMessage.warning('岗位已保存。该手机号已有 Web 登录密码，本次未变更原密码；若当事人无法登录，请用【重置密码】重置')
      } else {
        ElMessage.success('已添加')
      }
      addVisible.value = false
      load()
    } else ElMessage.error(data.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function saveEdit() {
  if (needWebPassword.value && form.value.webPassword && form.value.webPassword.length < 6) {
    ElMessage.warning('密码至少 6 位，或清空以使用默认密码')
    return
  }
  saving.value = true
  try {
    const { data } = await api.put(`/staff/team/members/${form.value.userId}/line-roles`, {
      lineRoles: form.value.lineRoles,
      webPassword: form.value.webPassword || undefined,
    })
    if (data.code === 0) {
      ElMessage.success(
        data.data?.passwordSet
          ? `已更新岗位，登录密码为 ${form.value.webPassword || DEFAULT_PASSWORD}，请告知当事人，其首次登录须修改`
          : '已更新岗位',
      )
      form.value.webPassword = ''
      editVisible.value = false
      load()
    } else ElMessage.error(data.message || '保存失败')
  } finally {
    saving.value = false
  }
}

async function clearLineRoles(row: any) {
  if (!canManage.value) {
    ElMessage.warning('仅物业经理或平台管理员可操作')
    return
  }
  if (row.isManager && !(row.roles || []).some((r: string) => r !== 'PROPERTY_MANAGER')) {
    ElMessage.info('该人员仅有经理岗，兼岗请在「兼岗设置」中勾选')
    return
  }
  try {
    await ElMessageBox.confirm(
      `删除「${row.realName || row.mobile}」的全部一线兼岗？物业经理岗不受影响。`,
      '确认删除',
    )
  } catch {
    return
  }
  const { data } = await api.put(`/staff/team/members/${row.userId}/line-roles`, { lineRoles: [] })
  if (data.code === 0) {
    ElMessage.success('已删除一线兼岗')
    load()
  } else ElMessage.error(data.message || '操作失败')
}

onMounted(async () => {
  await loadCatalog()
  await load()
})
</script>

<template>
  <div class="page">
    <header>
      <div>
        <h2>岗位人员</h2>
        <p class="sub">物业经理配置本小区一线岗位，同一人可兼多岗；经理本人由平台任命。客服可在此自助重置本人密码。</p>
      </div>
      <StaffSessionAction />
    </header>
    <StaffNav />

    <section class="block">
      <div class="sec-head">
        <h3>本小区团队</h3>
        <el-button v-if="showActions" type="primary" @click="openAdd">添加人员</el-button>
      </div>
      <el-table :data="list" v-loading="loading" style="width: 100%">
        <el-table-column label="姓名" min-width="120">
          <template #default="{ row }">{{ row.realName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="mobile" label="手机号" width="130" />
        <el-table-column label="岗位" min-width="200">
          <template #default="{ row }">
            <span>{{ row.roleLabels || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="Web密码" width="100">
          <template #default="{ row }">
            <span :class="webPasswordClass(row)">{{ webPasswordText(row) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <template v-if="canManage">
              <el-button link type="primary" @click="openEdit(row)">兼岗设置</el-button>
              <el-button v-if="canResetRow(row)" link type="primary" @click="openReset(row)">
                重置密码
              </el-button>
              <el-button link type="danger" @click="clearLineRoles(row)">删除</el-button>
            </template>
            <template v-else-if="canResetRow(row)">
              <el-button link type="primary" @click="openReset(row)">重置我的密码</el-button>
            </template>
            <span v-else class="hint">—</span>
          </template>
        </el-table-column>
      </el-table>
      <p class="hint">
        <template v-if="isPlatform">
          平台管理员可重置<strong>本小区任意可登录 Web 管理端岗位（客服、物业经理）</strong>的密码，
          也可代物业经理做兼岗设置与删除一线兼岗；
        </template>
        <template v-else>物业经理可设置兼岗、重置<strong>本人与客服</strong>的 Web 密码、删除一线兼岗；
        客服<strong>仅可重置本人密码</strong>，不能做兼岗设置，也不能删除；</template>
        不可在此任命第二位经理（经理由平台任命）。非客服岗转为客服岗时，若该账号尚未设置登录密码，
        须当场为其设置 Web 登录密码；已有密码的账号不会被覆盖，如需重置请用【重置密码】。
        密码重置后对方变为<strong>须改密</strong>，首次登录 Web 管理端会被强制修改密码。
        Web 密码只对可登录 Web 管理端的岗位（客服、物业经理）有效，保安 / 保洁 / 绿化 / 机电
        只用小程序接单，不再持有客服岗时其 Web 密码会自动清除。
      </p>
    </section>

    <el-dialog v-model="addVisible" title="添加一线人员" width="520px">
      <el-form label-position="top">
        <el-form-item label="手机号" required>
          <el-input v-model="form.mobile" maxlength="11" placeholder="11 位手机号" />
        </el-form-item>
        <el-form-item label="姓名">
          <el-input v-model="form.realName" maxlength="32" placeholder="选填" />
        </el-form-item>
        <el-form-item v-if="needPassword" label="登录密码（客服）">
          <el-input v-model="form.tempPassword" type="password" show-password :placeholder="`默认 ${DEFAULT_PASSWORD}，至少 6 位`" />
          <p class="hint" style="margin: 4px 0 0">
            仅客服岗需要密码（保安、保洁、绿化、机电只用小程序）。已预填默认密码 {{ DEFAULT_PASSWORD }}，
            留空则使用默认密码；首次登录 Web 管理端须修改密码。
            若该手机号已存在且已有登录密码，本次不会覆盖原密码，保存后可点【重置密码】重置。
          </p>
        </el-form-item>
        <el-form-item label="一线岗位" required>
          <el-checkbox-group v-model="form.lineRoles" class="role-checks">
            <el-checkbox v-for="o in lineOptions" :key="o.value" :label="o.value">
              {{ o.label }}
            </el-checkbox>
          </el-checkbox-group>
          <p v-if="!lineOptions.length" class="hint">岗位选项未加载，请刷新页面或重启后端后再试</p>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveAdd">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editVisible" title="兼岗设置" width="520px">
      <p v-if="form.isManager" class="hint" style="margin-top: 0">当前为物业经理，经理岗由平台管理；此处只设置一线兼岗。</p>
      <p class="meta">{{ form.realName || '—' }} · {{ form.mobile }}</p>
      <el-checkbox-group v-model="form.lineRoles" class="role-checks">
        <el-checkbox v-for="o in lineOptions" :key="o.value" :label="o.value">
          {{ o.label }}
        </el-checkbox>
      </el-checkbox-group>
      <p v-if="needPassword && !needWebPassword" class="hint" style="margin: 12px 0 0">
        该账号已有 Web 登录密码，本次兼岗<strong>不会变更</strong>原密码。若当事人无法登录，
        请使用列表中的【重置密码】将密码重置为默认密码 {{ DEFAULT_PASSWORD }}。
      </p>
      <el-form v-if="needWebPassword" label-position="top" style="margin-top: 12px">
        <el-form-item label="Web 登录密码">
          <el-input
            v-model="form.webPassword"
            type="password"
            show-password
            :placeholder="`默认 ${DEFAULT_PASSWORD}，至少 6 位`"
          />
          <p class="hint" style="margin: 4px 0 0">
            该账号尚无登录密码，客服岗须登录 Web 管理端。已预填默认密码 {{ DEFAULT_PASSWORD }}，可修改；
            保存后当事人首次登录须修改密码。
          </p>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="pwdVisible" title="重置密码" width="460px">
      <p class="meta">{{ pwdForm.realName || '—' }} · {{ pwdForm.mobile }}</p>
      <el-form label-position="top">
        <el-form-item label="新密码">
          <el-input
            v-model="pwdForm.newPassword"
            type="password"
            show-password
            :placeholder="`默认 ${DEFAULT_PASSWORD}，至少 6 位`"
          />
          <p class="hint" style="margin: 4px 0 0">
            已预填默认密码 {{ DEFAULT_PASSWORD }}，留空则使用默认密码。重置后当事人首次登录 Web 管理端须修改密码，请务必线下告知本人。
          </p>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwdVisible = false">取消</el-button>
        <el-button type="primary" :loading="pwdSaving" @click="saveReset">确认重置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding: 24px 32px; max-width: 1500px; margin: 0 auto; }
header { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
h2 { margin: 0; color: #1f4e3d; }
.sub { margin: 4px 0 0; color: #667; font-size: 14px; }
h3 { margin: 0; color: #2a4a3c; font-size: 16px; }
.sec-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; gap: 12px; }
.block {
  margin-top: 16px;
  padding: 20px 24px;
  background: #fff;
  border: 1px solid #e8ece9;
  border-radius: 8px;
}
.hint { margin: 12px 0 0; color: #889; font-size: 13px; line-height: 1.5; }
.pwd-todo { color: #b8791f; font-weight: 600; }
.pwd-none { color: #a8442f; font-weight: 600; }
.pwd-na { color: #aab; }
.meta { margin: 0 0 12px; color: #445; }
.role-checks { display: flex; flex-wrap: wrap; gap: 4px 8px; }
:deep(.el-checkbox) { margin-right: 8px; margin-bottom: 4px; }
</style>
