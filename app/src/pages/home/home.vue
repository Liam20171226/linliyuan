<template>
  <view class="page">
    <view class="head">
      <text class="brand">邻里院</text>
      <text class="hello">{{ greet }}</text>
      <text v-if="roleHint" class="role">{{ roleHint }}</text>
    </view>

    <view class="section">
      <text class="section-title">功能入口</text>
      <text class="section-tip">与小程序共用后端 API。支付暂未开放。</text>
      <view class="grid">
        <view
          v-for="item in entries"
          :key="item.key"
          class="cell"
          @click="onEntry(item)"
        >
          <view class="cell-head">
            <text class="cell-title">{{ item.title }}</text>
            <text v-if="item.badge" class="badge">{{ item.badge }}</text>
          </view>
          <text class="cell-desc">{{ item.desc }}</text>
        </view>
      </view>
    </view>

    <view class="actions">
      <button class="ghost" @click="goIdentity">切换身份</button>
      <button class="ghost" @click="onLogout">退出登录</button>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import {
  getToken,
  getUser,
  logout,
  mustChangePassword,
  setCurrentContext,
  getCurrentContext,
  type CurrentContext,
} from '../../utils/auth'
import { request } from '../../utils/request'
import { identityLabel } from '../../utils/format'

type Entry = {
  key: string
  title: string
  desc: string
  badge?: string
}

const ctx = ref<CurrentContext | null>(getCurrentContext())
const unpaidBillCount = ref(0)
const staffRepairCount = ref(0)

const greet = computed(() => {
  const u = getUser()
  const name = (u?.realName as string) || (u?.mobile as string) || '用户'
  return `你好，${name}`
})

const roleHint = computed(() => {
  const t = ctx.value?.identityType
  if (!t) return ''
  const parts = [identityLabel(t)]
  if (ctx.value?.communityName) parts.push(String(ctx.value.communityName))
  return parts.join(' · ')
})

const entries = computed(() => {
  const idType = ctx.value?.identityType || ''
  const roles = ctx.value?.staffRoles || []
  const list: Entry[] = []

  const isResident = idType === 'RESIDENT'
  const isGuest = idType === 'GUEST' || !idType
  const isStaff = idType === 'STAFF'
  const isCommittee = idType === 'COMMITTEE'

  // 住户 / 业委 / 未识别：住户侧入口
  if (isResident || isCommittee || isGuest) {
    list.push(
      { key: 'notices', title: '公告', desc: '小区通知' },
      {
        key: 'bills',
        title: '账单',
        desc: '待缴 / 记录',
        badge: unpaidBillCount.value > 0 ? String(unpaidBillCount.value) : undefined,
      },
      { key: 'service', title: '服务台', desc: '报修投诉' },
      { key: 'vote', title: '投票', desc: '业主表决' },
      { key: 'finance', title: '财务公开', desc: '收支公示' },
      { key: 'mine', title: '我的', desc: '房屋与资料' },
    )
  }

  // 建小区：游客 / 任意已登录身份均可
  list.push({ key: 'communityApply', title: '建小区申请', desc: '提交平台审核' })

  if (isGuest || isResident) {
    list.push({ key: 'authApply', title: '住户认证', desc: '申请挂房' })
    list.push({ key: 'approvalRecords', title: '认证记录', desc: '申请进度' })
  }

  if (isResident) {
    list.push({ key: 'roomMaintain', title: '房屋维护', desc: '成员与变更' })
    list.push({ key: 'prepaid', title: '预缴协议', desc: '查看预缴状态' })
  }

  if (isStaff) {
    list.push({ key: 'notices', title: '公告', desc: '小区通知' })
    list.push({
      key: 'staffTickets',
      title: '物业工单',
      desc: '待办 / 已完成',
      badge: staffRepairCount.value > 0 ? String(staffRepairCount.value) : undefined,
    })
    list.push({ key: 'staffInspect', title: '巡检', desc: '巡检任务' })
    list.push({ key: 'mine', title: '我的', desc: '资料' })
    if (
      roles.includes('CUSTOMER_SERVICE') ||
      roles.includes('PROPERTY_MANAGER')
    ) {
      list.push({ key: 'staffAuth', title: '认证审核', desc: '住户认证审批' })
      list.push({ key: 'staffPeople', title: '人员车辆', desc: '名册与车辆' })
      list.push({ key: 'staffChanges', title: '变更审核', desc: '人员车辆变更' })
      list.push({ key: 'approvalRecords', title: '认证记录', desc: '住户申请进度' })
    }
    if (roles.includes('PROPERTY_MANAGER')) {
      list.push({ key: 'voteCreate', title: '发起投票', desc: '物业发起表决' })
      list.push({ key: 'voteMine', title: '我的投票', desc: '投票与统计' })
    }
  }

  if (isCommittee) {
    list.push({ key: 'complaintOverview', title: '工单总览', desc: '报修投诉只读' })
    list.push({ key: 'noticeManage', title: '发公告', desc: '业委通知管理' })
    list.push({ key: 'occupantsRoster', title: '住户名册', desc: '楼栋住户' })
    list.push({ key: 'voteCreate', title: '发起投票', desc: '业主表决' })
    list.push({ key: 'voteMine', title: '我的投票', desc: '投票与统计' })
    list.push({ key: 'publicRevenue', title: '公共收益', desc: '收益公示' })
  }

  // 去重（按 key 保留首次）
  const seen = new Set<string>()
  return list.filter((it) => {
    if (seen.has(it.key)) return false
    seen.add(it.key)
    return true
  })
})

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

const ENTRY_URL: Record<string, string> = {
  notices: '/pages/notices/notices',
  bills: '/pages/bills/bills',
  service: '/pages/service/service',
  vote: '/pages/votes/votes',
  finance: '/pages/finance/finance',
  mine: '/pages/mine/mine',
  authApply: '/pages/auth-apply/auth-apply',
  approvalRecords: '/pages/approval-records/approval-records',
  roomMaintain: '/pages/room-maintain/room-maintain',
  prepaid: '/pages/prepaid/prepaid',
  staffTickets: '/pages/staff-tickets/staff-tickets',
  staffInspect: '/pages/staff-inspect/staff-inspect',
  staffAuth: '/pages/staff-auth/staff-auth',
  staffChanges: '/pages/staff-changes/staff-changes',
  complaintOverview: '/pages/complaint-overview/complaint-overview',
  noticeManage: '/pages/notice-manage/notice-manage',
  voteCreate: '/pages/vote-create/vote-create',
  voteMine: '/pages/vote-mine/vote-mine',
  publicRevenue: '/pages/public-revenue/public-revenue',
  communityApply: '/pages/community-apply/community-apply',
  occupantsRoster: '/pages/occupants-roster/occupants-roster',
  staffPeople: '/pages/staff-people/staff-people',
}

function onEntry(item: { key: string; title: string }) {
  const url = ENTRY_URL[item.key]
  if (!url) {
    uni.showToast({ title: `${item.title}页开发中`, icon: 'none' })
    return
  }
  uni.navigateTo({ url })
}

function goIdentity() {
  uni.navigateTo({ url: '/pages/identity/identity' })
}

function onLogout() {
  logout()
}

async function refreshIdentity() {
  try {
    const idn = await request<{
      current?: {
        identityType?: string
        communityId?: number
        communityName?: string
      }
      identities?: Array<{
        identityType?: string
        communityId?: number
        communityName?: string
        staffRoles?: string[]
        isCommittee?: boolean
      }>
      user?: Record<string, unknown>
    }>({ url: '/auth/identities' })

    const cur = idn.current || {}
    const me = (idn.identities || []).find(
      (it) =>
        it.identityType === cur.identityType &&
        String(it.communityId) === String(cur.communityId),
    )
    const next: CurrentContext = {
      identityType: cur.identityType || me?.identityType,
      communityId: cur.communityId ?? me?.communityId ?? null,
      communityName: cur.communityName || me?.communityName || '',
      staffRoles: (me && me.staffRoles) || [],
    }
    setCurrentContext(next)
    ctx.value = next

    const idType = next.identityType || ''
    if (idType === 'RESIDENT' || idType === 'COMMITTEE') {
      try {
        const bills = await request<{ total?: number }>({
          url: '/resident/bills',
          data: { page: 1, pageSize: 1, status: 'UNPAID' },
        })
        unpaidBillCount.value = Number((bills && bills.total) || 0)
      } catch {
        unpaidBillCount.value = 0
      }
    } else {
      unpaidBillCount.value = 0
    }

    if (idType === 'STAFF') {
      try {
        const [mine, pool] = await Promise.all([
          request<{ total?: number }>({
            url: '/staff/repairs/mine',
            data: { page: 1, pageSize: 1 },
          }).catch(() => null),
          request<{ total?: number }>({
            url: '/staff/repairs/pool',
            data: { page: 1, pageSize: 1 },
          }).catch(() => null),
        ])
        staffRepairCount.value =
          Number((mine && mine.total) || 0) + Number((pool && pool.total) || 0)
      } catch {
        staffRepairCount.value = 0
      }
    } else {
      staffRepairCount.value = 0
    }
  } catch {
    /* keep cached context */
  }
}

onShow(() => {
  if (!guard()) return
  refreshIdentity()
})
</script>

<style scoped>
.page {
  min-height: 100vh;
  padding: 48rpx 40rpx 80rpx;
  background: linear-gradient(180deg, #e8f2ec 0%, #f7faf8 28%, #ffffff 100%);
  box-sizing: border-box;
}
.head {
  margin-bottom: 40rpx;
}
.brand {
  display: block;
  font-size: 48rpx;
  font-weight: 700;
  color: #1f4e3d;
  letter-spacing: 2rpx;
}
.hello {
  display: block;
  margin-top: 10rpx;
  font-size: 28rpx;
  color: #5c6f68;
}
.role {
  display: block;
  margin-top: 6rpx;
  font-size: 22rpx;
  color: #8a9a93;
}
.section-title {
  display: block;
  font-size: 32rpx;
  font-weight: 600;
  color: #1f3d32;
}
.section-tip {
  display: block;
  margin: 8rpx 0 24rpx;
  font-size: 24rpx;
  color: #7a8c85;
  line-height: 1.45;
}
.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 20rpx;
}
.cell {
  background: #fff;
  border: 1px solid #dfe8e3;
  border-radius: 12rpx;
  padding: 28rpx 24rpx;
}
.cell-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8rpx;
}
.cell-title {
  font-size: 30rpx;
  color: #1f4e3d;
  font-weight: 600;
}
.badge {
  min-width: 32rpx;
  padding: 0 10rpx;
  height: 32rpx;
  line-height: 32rpx;
  text-align: center;
  font-size: 20rpx;
  color: #fff;
  background: #c2410c;
  border-radius: 16rpx;
}
.cell-desc {
  display: block;
  margin-top: 8rpx;
  font-size: 22rpx;
  color: #8a9a93;
}
.actions {
  margin-top: 48rpx;
  display: flex;
  gap: 20rpx;
}
.ghost {
  flex: 1;
  background: #fff;
  color: #2f6b55;
  border: 1px solid #c5d9cf;
  font-size: 28rpx;
}
</style>
