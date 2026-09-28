<template>
  <view class="page">
    <text class="tip">提交后由平台审核。通过后才真正建小区。</text>

    <view class="card">
      <text class="label">省</text>
      <input v-model="provinceName" class="input" placeholder="如：广东省" />

      <text class="label">市</text>
      <input v-model="cityName" class="input" placeholder="如：深圳市" />

      <text class="label">区/县</text>
      <input v-model="districtName" class="input" placeholder="如：南山区" />

      <text class="label">小区名称</text>
      <input v-model="communityName" class="input" placeholder="拟创建的小区名称" />

      <text class="label">申请人姓名</text>
      <input v-model="applicantName" class="input" placeholder="真实姓名" />

      <text class="label">申请角色</text>
      <picker :range="roleLabels" :value="roleIndex" @change="onRole">
        <view class="picker">{{ roleLabels[roleIndex] }}</view>
      </picker>

      <text class="label">联系电话</text>
      <input v-model="contactMobile" class="input" type="number" maxlength="20" placeholder="手机号" />

      <text class="label">申请原因（最多 20 字）</text>
      <input v-model="reason" class="input" maxlength="20" placeholder="可选" />

      <text class="label">初始化备注（可选）</text>
      <textarea v-model="initNote" class="area" placeholder="房屋初始化说明，仅本地备忘" :maxlength="100" />
    </view>

    <button class="btn" :disabled="submitting" @click="submit">提交申请</button>

    <view class="block">
      <text class="block-title">我的申请</text>
      <view v-if="loadingMine" class="empty">加载中…</view>
      <view v-else-if="!mine.length" class="empty">暂无申请记录</view>
      <view v-for="it in mine" :key="it.id" class="mine-row">
        <view class="row">
          <text class="mine-name">{{ it.communityName }}</text>
          <text class="badge" :class="statusClass(it.status)">{{ it.statusLabel }}</text>
        </view>
        <text class="sub">{{ it.applicantRoleLabel }} · {{ it.applicantMobile || '—' }}</text>
        <text v-if="it.applyReason" class="sub">原因：{{ it.applyReason }}</text>
        <text v-if="it.rejectReason" class="sub warn">拒绝：{{ it.rejectReason }}</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { getToken, getUser, mustChangePassword } from '../../utils/auth'
import { request, type ApiError } from '../../utils/request'

const ROLE_VALUES = ['RESIDENT', 'STAFF'] as const
const ROLE_LABELS = ['住户（非物业）', '物业']
const STATUS_LABEL: Record<string, string> = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已拒绝',
}

type RegionDistrict = { code: string; name: string }
type RegionCity = { code: string; name: string; districts?: RegionDistrict[] }
type RegionProvince = { code: string; name: string; cities?: RegionCity[] }

type MineRow = {
  id: number
  communityName: string
  status: string
  statusLabel: string
  applicantRoleLabel: string
  applicantMobile?: string
  applyReason?: string
  rejectReason?: string
}

const provinceName = ref('')
const cityName = ref('')
const districtName = ref('')
const communityName = ref('')
const applicantName = ref('')
const roleIndex = ref(0)
const roleLabels = ROLE_LABELS
const contactMobile = ref('')
const reason = ref('')
const initNote = ref('')
const submitting = ref(false)
const loadingMine = ref(false)
const mine = ref<MineRow[]>([])
const regions = ref<RegionProvince[]>([])

function guard() {
  if (!getToken()) {
    uni.reLaunch({ url: '/pages/login/login' })
    return false
  }
  if (mustChangePassword()) {
    uni.reLaunch({ url: '/pages/change-password/change-password' })
    return false
  }
  return true
}

function prefill() {
  const u = getUser()
  if (!applicantName.value) {
    applicantName.value = String((u?.realName as string) || '')
  }
  if (!contactMobile.value) {
    contactMobile.value = String((u?.mobile as string) || '')
  }
}

function onRole(e: { detail: { value: string } }) {
  roleIndex.value = Number(e.detail.value) || 0
}

function statusClass(s: string) {
  if (s === 'APPROVED') return 'ok'
  if (s === 'REJECTED') return 'bad'
  return 'warn'
}

function resolveRegion() {
  const pName = provinceName.value.trim()
  const cName = cityName.value.trim()
  const dName = districtName.value.trim()
  for (const p of regions.value) {
    if (p.name !== pName) continue
    for (const c of p.cities || []) {
      if (c.name !== cName) continue
      for (const d of c.districts || []) {
        if (d.name === dName) {
          return {
            provinceCode: p.code,
            provinceName: p.name,
            cityCode: c.code,
            cityName: c.name,
            districtCode: d.code,
            districtName: d.name,
          }
        }
      }
    }
  }
  return null
}

async function loadRegions() {
  try {
    const list = await request<RegionProvince[]>({ url: '/regions' })
    if (Array.isArray(list) && list.length) regions.value = list
  } catch {
    /* ignore */
  }
}

async function loadMine() {
  loadingMine.value = true
  try {
    const data = await request<{ list?: Array<Record<string, unknown>> }>({
      url: '/community-applications/mine',
    })
    mine.value = ((data && data.list) || []).map((it) => ({
      id: Number(it.id),
      communityName: String(it.communityName || '—'),
      status: String(it.status || ''),
      statusLabel: STATUS_LABEL[String(it.status)] || String(it.status || ''),
      applicantRoleLabel: it.applicantRole === 'STAFF' ? '物业' : '住户',
      applicantMobile: it.applicantMobile ? String(it.applicantMobile) : '',
      applyReason: it.applyReason ? String(it.applyReason) : '',
      rejectReason: it.rejectReason ? String(it.rejectReason) : '',
    }))
  } catch {
    mine.value = []
  } finally {
    loadingMine.value = false
  }
}

async function submit() {
  const sel = resolveRegion()
  if (!sel) {
    uni.showToast({
      title: regions.value.length ? '省市区名称无效，请与区划一致' : '地区数据未就绪',
      icon: 'none',
    })
    return
  }
  if (!communityName.value.trim() || !applicantName.value.trim() || !contactMobile.value.trim()) {
    uni.showToast({ title: '请填写必填项', icon: 'none' })
    return
  }
  if (reason.value && reason.value.length > 20) {
    uni.showToast({ title: '申请原因最多 20 字', icon: 'none' })
    return
  }

  const payload: Record<string, unknown> = {
    provinceCode: sel.provinceCode,
    provinceName: sel.provinceName,
    cityCode: sel.cityCode,
    cityName: sel.cityName,
    districtCode: sel.districtCode,
    districtName: sel.districtName,
    communityName: communityName.value.trim(),
    applicantName: applicantName.value.trim(),
    applicantRole: ROLE_VALUES[roleIndex.value],
    applicantMobile: contactMobile.value.trim(),
    applyReason: reason.value.trim() || null,
  }
  // initNote 无后端字段，不提交

  submitting.value = true
  try {
    await request({
      url: '/community-applications',
      method: 'POST',
      data: payload,
    })
    uni.showToast({ title: '已提交' })
    communityName.value = ''
    reason.value = ''
    initNote.value = ''
    await loadMine()
  } catch (e) {
    uni.showModal({
      title: '提交失败',
      content: (e as ApiError).message || '请稍后重试',
      showCancel: false,
    })
  } finally {
    submitting.value = false
  }
}

onShow(() => {
  if (!guard()) return
  prefill()
  loadRegions()
  loadMine()
})
</script>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx 32rpx 48rpx;
  background: #f7faf8;
  box-sizing: border-box;
}
.tip {
  display: block;
  margin-bottom: 16rpx;
  font-size: 24rpx;
  color: #7a8c85;
  line-height: 1.45;
}
.card,
.block {
  background: #fff;
  border: 1px solid #dfe8e3;
  border-radius: 12rpx;
  padding: 24rpx;
  margin-bottom: 24rpx;
}
.label {
  display: block;
  margin-top: 16rpx;
  margin-bottom: 8rpx;
  font-size: 24rpx;
  color: #5c6f68;
}
.label:first-child {
  margin-top: 0;
}
.input {
  background: #f7faf8;
  border: 1px solid #dfe8e3;
  border-radius: 10rpx;
  padding: 18rpx 16rpx;
  font-size: 28rpx;
  color: #1f3d32;
}
.area {
  width: 100%;
  min-height: 120rpx;
  background: #f7faf8;
  border: 1px solid #dfe8e3;
  border-radius: 10rpx;
  padding: 16rpx;
  font-size: 28rpx;
  box-sizing: border-box;
}
.picker {
  background: #f7faf8;
  border: 1px solid #dfe8e3;
  border-radius: 10rpx;
  padding: 18rpx 16rpx;
  font-size: 28rpx;
  color: #1f3d32;
}
.btn {
  background: #2f6b55;
  color: #fff;
  font-size: 30rpx;
  border-radius: 10rpx;
  margin-bottom: 24rpx;
}
.block-title {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: #1f3d32;
  margin-bottom: 12rpx;
}
.mine-row {
  padding: 16rpx 0;
  border-top: 1px solid #eef3f0;
}
.mine-row:first-of-type {
  border-top: none;
}
.row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12rpx;
}
.mine-name {
  font-size: 28rpx;
  color: #1f3d32;
  font-weight: 600;
}
.badge {
  font-size: 22rpx;
  padding: 4rpx 12rpx;
  border-radius: 8rpx;
}
.badge.warn {
  color: #b45309;
  background: #fff7ed;
}
.badge.ok {
  color: #2f6b55;
  background: #e8f2ec;
}
.badge.bad {
  color: #b91c1c;
  background: #fef2f2;
}
.sub {
  display: block;
  margin-top: 6rpx;
  font-size: 24rpx;
  color: #8a9a93;
}
.sub.warn {
  color: #b45309;
}
.empty {
  font-size: 26rpx;
  color: #8a9a93;
  padding: 12rpx 0;
}
</style>
