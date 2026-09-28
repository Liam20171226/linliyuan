<template>
  <view class="page">
    <view v-if="loading" class="empty">加载中…</view>
    <template v-else-if="order">
      <view class="card">
        <view class="row">
          <text class="title">{{ order.category || (kind === 'complaint' ? '投诉' : '报修') }}</text>
          <text class="status">{{ statusLabel }}</text>
        </view>
        <text class="body">{{ order.content || order.description || '—' }}</text>
        <text class="meta">位置：{{ order.location || '—' }}</text>
        <text class="meta">受理岗：{{ assigneeRoleLabel || '—' }}</text>
        <text v-if="order.assigneeUserId" class="meta">处理人：{{ memberName(order.assigneeUserId) }}</text>
      </view>

      <view v-if="timeline.length" class="card">
        <text class="sec">进度</text>
        <view v-for="(n, i) in timeline" :key="i" class="tl">
          <text class="tl-t">{{ shortTime(n.createdAt || n.time) }}</text>
          <text class="tl-b">{{ n.content || n.action || n.remark || '—' }}</text>
        </view>
      </view>

      <view v-if="!readonly && (canClaim || canReply || canTransfer || canFinish)" class="card">
        <text class="sec">操作</text>

        <button v-if="canClaim" class="btn" :disabled="submitting" @click="claim">接单</button>

        <template v-if="canReply">
          <textarea
            v-model="replyText"
            class="area"
            placeholder="处理留言"
            :maxlength="500"
          />
          <view class="photos">
            <view v-for="f in photos" :key="f.id" class="thumb-wrap">
              <image
                class="thumb"
                :src="f.localPath || attachmentUrl(f.id)"
                mode="aspectFill"
                @click="previewPhoto(f)"
              />
              <view class="del" @click.stop="removePhoto(f.id)">×</view>
            </view>
            <view v-if="photos.length < 3" class="add" @click="addPhotos">添加照片</view>
          </view>
          <button class="btn" :disabled="submitting" @click="submitReply">提交回复</button>
        </template>

        <template v-if="canTransfer">
          <button class="btn ghost" :disabled="submitting" @click="transferOpen = !transferOpen">
            {{ transferOpen ? '收起转派' : '转派岗位' }}
          </button>
          <view v-if="transferOpen" class="transfer">
            <view class="roles">
              <view
                v-for="r in roleOptions"
                :key="r.code"
                class="role"
                :class="{ on: transferRole === r.code }"
                @click="transferRole = r.code"
              >{{ r.label }}</view>
            </view>
            <input v-model="transferRemark" class="input" placeholder="转派备注（可选）" />
            <button class="btn" :disabled="submitting" @click="submitTransfer">确认转派</button>
          </view>
        </template>

        <button v-if="canFinish" class="btn primary" :disabled="submitting" @click="finish">
          处理完成
        </button>
      </view>

      <view v-if="readonly" class="hint">业委会只读查看</view>
    </template>
    <view v-else class="empty">工单不存在</view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { request, type ApiError } from '../../utils/request'
import {
  repairStatusLabel,
  complaintStatusLabel,
  formatDateTime,
} from '../../utils/format'
import {
  chooseAndUpload,
  deleteAttachment,
  attachmentUrl,
  type UploadedFile,
} from '../../utils/upload'

const OFFICE_ROLES = ['PROPERTY_MANAGER', 'CUSTOMER_SERVICE']

const ROLE_OPTIONS = [
  { code: 'SECURITY', label: '保安岗' },
  { code: 'CLEANING', label: '保洁岗' },
  { code: 'LANDSCAPING', label: '绿化岗' },
  { code: 'FACILITY_MAINT', label: '机电维修岗' },
  { code: 'CUSTOMER_SERVICE', label: '客服岗' },
  { code: 'PROPERTY_MANAGER', label: '物业经理' },
]

const ROLE_LABEL: Record<string, string> = {}
ROLE_OPTIONS.forEach((r) => {
  ROLE_LABEL[r.code] = r.label
})

const id = ref<number | null>(null)
const kind = ref<'repair' | 'complaint'>('repair')
const readonly = ref(false)
const loading = ref(true)
const submitting = ref(false)
const order = ref<Record<string, unknown> | null>(null)
const timeline = ref<Array<Record<string, unknown>>>([])
const memberNames = ref<Record<string, string>>({})
const canClaim = ref(false)
const canReply = ref(false)
const canTransfer = ref(false)
const canFinish = ref(false)
const replyText = ref('')
const photos = ref<UploadedFile[]>([])
const transferOpen = ref(false)
const transferRole = ref<string | null>(null)
const transferRemark = ref('')
const roleOptions = ROLE_OPTIONS

const statusLabel = computed(() => {
  const s = order.value?.status
  return kind.value === 'complaint' ? complaintStatusLabel(s) : repairStatusLabel(s)
})

const assigneeRoleLabel = computed(() => {
  const role = order.value?.assigneeRole
  if (!role) return ''
  return ROLE_LABEL[String(role)] || String(role)
})

function apiPrefix() {
  if (readonly.value) return '/committee/tickets'
  return kind.value === 'complaint' ? '/staff/complaints' : '/staff/repairs'
}

function shortTime(v: unknown) {
  const s = formatDateTime(v)
  return s ? s.slice(0, 16) : ''
}

function memberName(uid: unknown) {
  return memberNames.value[String(uid)] || String(uid)
}

async function loadMembers() {
  try {
    const d = await request<{ members?: Array<Record<string, unknown>> }>({
      url: '/staff/team',
    })
    const names: Record<string, string> = {}
    ;((d && d.members) || []).forEach((it) => {
      names[String(it.userId)] = String(it.realName || it.mobile || '员工' + it.userId)
    })
    memberNames.value = names
  } catch {
    memberNames.value = {}
  }
}

async function load() {
  if (!id.value) return
  loading.value = true
  try {
    let d: Record<string, unknown>
    try {
      d = await request({ url: `${apiPrefix()}/${id.value}` })
    } catch (e) {
      if (!readonly.value && kind.value === 'repair') {
        d = await request({ url: `/staff/complaints/${id.value}` })
        kind.value = 'complaint'
      } else {
        throw e
      }
    }
    const ord = (d.order || {}) as Record<string, unknown>
    if (ord.kind === 'COMPLAINT') kind.value = 'complaint'
    if (ord.kind === 'REPAIR') kind.value = 'repair'

    const status = String(ord.status || '')
    const active = [
      'ASSIGNED',
      'IN_PROGRESS',
      'PENDING_ASSIGN',
      'PENDING',
      'PROCESSING',
      'REPLIED',
    ].includes(status)
    const claimed = !!ord.assigneeUserId

    let myRoles: string[] = []
    let isPlatform = false
    if (!readonly.value) {
      try {
        const idn = await request<{
          current?: { identityType?: string; communityId?: number }
          identities?: Array<{
            identityType?: string
            communityId?: number
            staffRoles?: string[]
          }>
        }>({ url: '/auth/identities' })
        const cur = idn.current || {}
        isPlatform = cur.identityType === 'PLATFORM'
        const me = (idn.identities || []).find(
          (it) =>
            it.identityType === cur.identityType &&
            String(it.communityId) === String(cur.communityId),
        )
        myRoles = (me && me.staffRoles) || []
      } catch {
        myRoles = []
      }
    }

    const office = isPlatform || myRoles.some((r) => OFFICE_ROLES.includes(r))
    const myRoleSet = new Set(myRoles)
    const roleMatches = !!ord.assigneeRole && myRoleSet.has(String(ord.assigneeRole))
    const ro = readonly.value

    order.value = ord
    timeline.value = (d.timeline as Array<Record<string, unknown>>) || []
    canClaim.value =
      !ro && !office && status === 'ASSIGNED' && !claimed && !!ord.assigneeRole && roleMatches
    canReply.value =
      !ro &&
      active &&
      (office || claimed || roleMatches || status === 'PENDING_ASSIGN' || status === 'PENDING')
    canTransfer.value = !ro && active && office
    canFinish.value =
      !ro &&
      (kind.value === 'complaint'
        ? ['PENDING', 'ASSIGNED', 'PROCESSING', 'REPLIED'].includes(status)
        : ['ASSIGNED', 'IN_PROGRESS'].includes(status))

    if (!ro) loadMembers()
  } catch (e) {
    order.value = null
    uni.showToast({ title: (e as ApiError).message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

async function claim() {
  const ok = await new Promise<boolean>((resolve) => {
    uni.showModal({
      title: '确认接单',
      content: '接单后由我负责处理该工单',
      success: (r) => resolve(!!r.confirm),
      fail: () => resolve(false),
    })
  })
  if (!ok || !id.value) return
  submitting.value = true
  try {
    await request({
      url: `${apiPrefix()}/${id.value}/claim`,
      method: 'POST',
      data: {},
    })
    uni.showToast({ title: '已接单' })
    await load()
  } catch (e) {
    uni.showToast({ title: (e as ApiError).message || '接单失败', icon: 'none' })
  } finally {
    submitting.value = false
  }
}

async function addPhotos() {
  try {
    photos.value = await chooseAndUpload(photos.value, 3)
  } catch (e) {
    uni.showToast({ title: (e as Error).message || '上传失败', icon: 'none' })
  }
}

async function removePhoto(pid: number) {
  photos.value = photos.value.filter((x) => x.id !== pid)
  await deleteAttachment(pid)
}

function previewPhoto(f: UploadedFile) {
  const urls = photos.value.map((x) => x.localPath || attachmentUrl(x.id))
  uni.previewImage({ current: f.localPath || attachmentUrl(f.id), urls })
}

async function submitReply() {
  const content = replyText.value.trim()
  if (!content) {
    uni.showToast({ title: '请填写处理留言', icon: 'none' })
    return
  }
  if (!id.value) return
  const attachmentIds = photos.value.map((x) => x.id)
  submitting.value = true
  try {
    if (kind.value === 'complaint') {
      await request({
        url: `/staff/complaints/${id.value}/handle`,
        method: 'POST',
        data: { replyContent: content, attachmentIds },
      })
    } else {
      await request({
        url: `/staff/repairs/${id.value}/replies`,
        method: 'POST',
        data: { content, attachmentIds },
      })
    }
    replyText.value = ''
    photos.value = []
    uni.showToast({ title: '已提交' })
    await load()
  } catch (e) {
    uni.showToast({ title: (e as ApiError).message || '提交失败', icon: 'none' })
  } finally {
    submitting.value = false
  }
}

async function submitTransfer() {
  if (!transferRole.value) {
    uni.showToast({ title: '请选择转派岗位', icon: 'none' })
    return
  }
  if (!id.value) return
  submitting.value = true
  try {
    await request({
      url: `${apiPrefix()}/${id.value}/assign`,
      method: 'POST',
      data: { assigneeRole: transferRole.value, remark: transferRemark.value },
    })
    transferOpen.value = false
    transferRole.value = null
    transferRemark.value = ''
    uni.showToast({ title: '已转派' })
    await load()
  } catch (e) {
    uni.showToast({ title: (e as ApiError).message || '转派失败', icon: 'none' })
  } finally {
    submitting.value = false
  }
}

async function finish() {
  const isPraise = order.value?.category === '表扬'
  const ok = await new Promise<boolean>((resolve) => {
    uni.showModal({
      title: '处理完成',
      content: isPraise
        ? '请确认事项已办理完毕。表扬不参与满意度评价。'
        : '请确认事项已办理完毕，即将发送给住户进行满意度评价',
      confirmText: '确认完成',
      success: (r) => resolve(!!r.confirm),
      fail: () => resolve(false),
    })
  })
  if (!ok || !id.value) return
  submitting.value = true
  try {
    await request({
      url: `${apiPrefix()}/${id.value}/complete`,
      method: 'POST',
      data: {},
    })
    uni.showToast({ title: isPraise ? '已处理完成' : '已完成，待业主评价' })
    await load()
  } catch (e) {
    uni.showToast({ title: (e as ApiError).message || '操作失败', icon: 'none' })
  } finally {
    submitting.value = false
  }
}

onLoad((q) => {
  id.value = q?.id ? Number(q.id) : null
  kind.value = q?.kind === 'complaint' ? 'complaint' : 'repair'
  readonly.value = !!(q && (q.readonly === '1' || q.from === 'committee'))
  const title = readonly.value
    ? kind.value === 'complaint'
      ? '工单查看'
      : '报修查看'
    : kind.value === 'complaint'
      ? '工单详情'
      : '报修详情'
  uni.setNavigationBarTitle({ title })
})

onShow(() => {
  if (id.value) load()
})
</script>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 32rpx 48rpx;
  background: #f7faf8;
  box-sizing: border-box;
}
.empty {
  padding: 80rpx 0;
  text-align: center;
  color: #8a9a93;
  font-size: 28rpx;
}
.card {
  background: #fff;
  border: 1px solid #dfe8e3;
  border-radius: 12rpx;
  padding: 24rpx;
  margin-bottom: 16rpx;
}
.row {
  display: flex;
  justify-content: space-between;
  gap: 12rpx;
}
.title {
  font-size: 30rpx;
  font-weight: 700;
  color: #1f4e3d;
}
.status {
  font-size: 24rpx;
  color: #2f6b55;
}
.body {
  display: block;
  margin: 16rpx 0;
  font-size: 28rpx;
  color: #3d5249;
  line-height: 1.5;
}
.meta {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #7a8c85;
}
.sec {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: #1f4e3d;
  margin-bottom: 16rpx;
}
.tl {
  padding: 12rpx 0;
  border-top: 1px solid #eef3f0;
}
.tl-t {
  display: block;
  font-size: 22rpx;
  color: #8a9a93;
}
.tl-b {
  display: block;
  margin-top: 4rpx;
  font-size: 26rpx;
  color: #3d5249;
}
.area {
  width: 100%;
  min-height: 160rpx;
  background: #f7faf8;
  border: 1px solid #dfe8e3;
  border-radius: 10rpx;
  padding: 16rpx;
  font-size: 28rpx;
  box-sizing: border-box;
  margin-bottom: 16rpx;
}
.photos {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
  margin-bottom: 16rpx;
}
.thumb-wrap {
  position: relative;
  width: 140rpx;
  height: 140rpx;
}
.thumb {
  width: 140rpx;
  height: 140rpx;
  border-radius: 10rpx;
  background: #e8f2ec;
}
.del {
  position: absolute;
  top: -8rpx;
  right: -8rpx;
  width: 36rpx;
  height: 36rpx;
  line-height: 32rpx;
  text-align: center;
  background: #c45c26;
  color: #fff;
  border-radius: 18rpx;
  font-size: 26rpx;
}
.add {
  width: 140rpx;
  height: 140rpx;
  border: 1px dashed #c5d9cf;
  border-radius: 10rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22rpx;
  color: #2f6b55;
  background: #fff;
  text-align: center;
  padding: 8rpx;
  box-sizing: border-box;
}
.input {
  background: #f7faf8;
  border: 1px solid #dfe8e3;
  border-radius: 10rpx;
  padding: 16rpx;
  font-size: 28rpx;
  margin: 12rpx 0 16rpx;
}
.btn {
  background: #2f6b55;
  color: #fff;
  font-size: 28rpx;
  margin-bottom: 16rpx;
  border-radius: 10rpx;
}
.btn.ghost {
  background: #fff;
  color: #2f6b55;
  border: 1px solid #c5d9cf;
}
.btn.primary {
  background: #1f4e3d;
}
.roles {
  display: flex;
  flex-wrap: wrap;
  gap: 12rpx;
}
.role {
  padding: 10rpx 18rpx;
  border: 1px solid #dfe8e3;
  border-radius: 8rpx;
  font-size: 24rpx;
  color: #5c6f68;
  background: #fff;
}
.role.on {
  border-color: #2f6b55;
  color: #1f4e3d;
  background: #e8f2ec;
  font-weight: 600;
}
.hint {
  text-align: center;
  font-size: 24rpx;
  color: #8a9a93;
  padding: 16rpx;
}
</style>
