<template>
  <view class="page">
    <text class="tip">搜索小区并选择房屋后提交认证；业主须身份证号与产权证明照片。</text>

    <view class="card">
      <text class="label">搜索小区</text>
      <view class="search-row">
        <input
          v-model="keyword"
          class="input flex"
          placeholder="输入小区名称关键词"
          confirm-type="search"
          @confirm="searchCommunities"
        />
        <button class="search-btn" size="mini" :loading="searching" @click="searchCommunities">
          搜索
        </button>
      </view>

      <view v-if="communityList.length" class="list">
        <view
          v-for="c in communityList"
          :key="c.id"
          class="list-item"
          :class="{ on: communityId === c.id }"
          @click="pickCommunity(c)"
        >
          <text class="list-title">{{ c.name }}</text>
          <text class="list-meta">{{ c.address || '' }}</text>
        </view>
      </view>
      <text v-else-if="searched && !searching" class="hint">未找到小区，请换关键词</text>

      <text v-if="communityName" class="picked">已选：{{ communityName }}</text>

      <text class="label">房屋</text>
      <picker
        v-if="roomLabels.length"
        :range="roomLabels"
        :value="roomIndex"
        @change="onRoomPick"
      >
        <view class="picker">{{ roomPath || '请选择房屋' }}</view>
      </picker>
      <text v-else-if="spaceLoading" class="hint">房屋加载中…</text>
      <text v-else-if="communityId && !spaceLoading" class="hint">该小区暂无房屋</text>
      <text v-else class="hint">请先选择小区</text>

      <text class="label">申请角色</text>
      <picker :range="roleLabels" :value="roleIndex" @change="onRole">
        <view class="picker">{{ roleLabels[roleIndex] }}</view>
      </picker>

      <text class="label">真实姓名</text>
      <input
        v-model="applicantName"
        class="input"
        :disabled="nameLocked"
        placeholder="与证件一致"
      />

      <template v-if="applyRole === 'OWNER'">
        <text class="label">身份证号</text>
        <input v-model="applicantIdCardNo" class="input" placeholder="业主必填" />

        <text class="label">产权证明（至少 1 张，最多 3 张）</text>
        <view class="photos">
          <view v-for="f in proofFiles" :key="f.id" class="thumb-wrap">
            <image
              class="thumb"
              :src="f.localPath || attachmentUrl(f.id)"
              mode="aspectFill"
              @click="previewProof(f)"
            />
            <view class="del" @click.stop="removeProof(f.id)">×</view>
          </view>
          <view v-if="proofFiles.length < 3" class="add" @click="addProof">添加照片</view>
        </view>
      </template>

      <text class="label">留言（可选）</text>
      <textarea v-model="applyMessage" class="area" placeholder="补充说明" :maxlength="200" />
    </view>

    <button class="btn" :disabled="submitting" @click="submit">提交认证申请</button>
    <button class="link" @click="goRecords">查看我的申请记录</button>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { getUser } from '../../utils/auth'
import { request, type ApiError } from '../../utils/request'
import {
  chooseAndUpload,
  deleteAttachment,
  attachmentUrl,
  type UploadedFile,
} from '../../utils/upload'

type Community = { id: number; name: string; address?: string }
type FlatRoom = { roomId: number; path: string }

const roles = [
  { label: '业主成员', value: 'OWNER_MEMBER' },
  { label: '租户', value: 'TENANT' },
  { label: '租户成员', value: 'TENANT_MEMBER' },
  { label: '业主（需身份证+产权附件）', value: 'OWNER' },
]
const roleLabels = roles.map((r) => r.label)
const roleIndex = ref(0)
const applyRole = ref('OWNER_MEMBER')

const keyword = ref('')
const searching = ref(false)
const searched = ref(false)
const communityList = ref<Community[]>([])
const communityId = ref<number | null>(null)
const communityName = ref('')
const spaceLoading = ref(false)
const flatRooms = ref<FlatRoom[]>([])
const roomId = ref<number | null>(null)
const roomPath = ref('')
const roomIndex = ref(0)

const applicantName = ref('')
const nameLocked = ref(false)
const applicantIdCardNo = ref('')
const applyMessage = ref('')
const proofFiles = ref<UploadedFile[]>([])
const submitting = ref(false)

const roomLabels = computed(() => flatRooms.value.map((r) => r.path))

function syncName() {
  const u = getUser()
  const name = String((u && u.realName) || '').trim()
  if (name) {
    applicantName.value = name
    nameLocked.value = true
  }
}

function flattenRooms(buildings: Array<Record<string, unknown>>): FlatRoom[] {
  const flat: FlatRoom[] = []
  ;(buildings || []).forEach((b) => {
    ;(((b.units as Array<Record<string, unknown>>) || [])).forEach((u) => {
      ;(((u.floors as Array<Record<string, unknown>>) || [])).forEach((f) => {
        ;(((f.rooms as Array<Record<string, unknown>>) || [])).forEach((r) => {
          flat.push({
            roomId: Number(r.id),
            path: `${b.name || ''}${u.name || ''}${f.name || ''}${r.roomNo || ''}`,
          })
        })
      })
    })
  })
  return flat
}

async function searchCommunities() {
  const name = keyword.value.trim()
  if (!name) {
    uni.showToast({ title: '请输入小区关键词', icon: 'none' })
    return
  }
  searching.value = true
  searched.value = true
  try {
    const data = await request<{ list?: Community[] }>({
      url: '/resident/communities',
      data: { name, page: 1, pageSize: 50 },
    })
    communityList.value = (data && data.list) || []
  } catch (e) {
    communityList.value = []
    uni.showToast({ title: (e as ApiError).message || '搜索失败', icon: 'none' })
  } finally {
    searching.value = false
  }
}

async function pickCommunity(c: Community) {
  communityId.value = c.id
  communityName.value = c.name
  roomId.value = null
  roomPath.value = ''
  roomIndex.value = 0
  flatRooms.value = []
  spaceLoading.value = true
  try {
    const tree = await request<{ buildings?: Array<Record<string, unknown>> }>({
      url: `/resident/communities/${c.id}/space-tree`,
    })
    flatRooms.value = flattenRooms((tree && tree.buildings) || [])
    if (!flatRooms.value.length) {
      uni.showToast({ title: '该小区暂无房屋，请联系物业', icon: 'none' })
    }
  } catch (e) {
    flatRooms.value = []
    uni.showToast({ title: (e as ApiError).message || '加载房屋失败', icon: 'none' })
  } finally {
    spaceLoading.value = false
  }
}

function onRoomPick(e: { detail: { value: string } }) {
  const idx = Number(e.detail.value)
  const hit = flatRooms.value[idx]
  if (!hit) return
  roomIndex.value = idx
  roomId.value = hit.roomId
  roomPath.value = hit.path
}

function onRole(e: { detail: { value: string } }) {
  const idx = Number(e.detail.value)
  roleIndex.value = idx
  applyRole.value = roles[idx].value
}

async function addProof() {
  if (!communityId.value) {
    uni.showToast({ title: '请先选择小区', icon: 'none' })
    return
  }
  try {
    proofFiles.value = await chooseAndUpload(proofFiles.value, 3)
  } catch (e) {
    uni.showToast({ title: (e as Error).message || '上传失败', icon: 'none' })
  }
}

async function removeProof(pid: number) {
  proofFiles.value = proofFiles.value.filter((x) => x.id !== pid)
  await deleteAttachment(pid)
}

function previewProof(f: UploadedFile) {
  const urls = proofFiles.value.map((x) => x.localPath || attachmentUrl(x.id))
  uni.previewImage({ current: f.localPath || attachmentUrl(f.id), urls })
}

async function submit() {
  const cid = communityId.value
  const rid = roomId.value
  const name = applicantName.value.trim()
  if (!cid || !rid || !name) {
    uni.showToast({ title: '请选小区房屋并填写姓名', icon: 'none' })
    return
  }
  let attachmentIds: number[] = []
  if (applyRole.value === 'OWNER') {
    if (!applicantIdCardNo.value.trim()) {
      uni.showToast({ title: '业主须填身份证', icon: 'none' })
      return
    }
    attachmentIds = proofFiles.value.map((x) => x.id)
    if (!attachmentIds.length) {
      uni.showToast({ title: '请上传产权证明图片', icon: 'none' })
      return
    }
  }
  submitting.value = true
  try {
    const app = await request<{ id?: number }>({
      url: '/resident/auth-applications',
      method: 'POST',
      data: {
        communityId: cid,
        roomId: rid,
        applicantName: name,
        applicantIdCardNo: applicantIdCardNo.value.trim() || null,
        applyRole: applyRole.value,
        applyMessage: applyMessage.value.trim() || null,
        attachmentIds,
      },
    })
    uni.showModal({
      title: '已提交',
      content: `申请单号 ${app.id || ''}，请联系物业审核。`,
      showCancel: false,
      success: () => uni.navigateBack(),
    })
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

function goRecords() {
  uni.navigateTo({ url: '/pages/approval-records/approval-records' })
}

onShow(() => {
  syncName()
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
  font-size: 24rpx;
  color: #7a8c85;
  line-height: 1.45;
  margin-bottom: 20rpx;
}
.card {
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
.search-row {
  display: flex;
  gap: 12rpx;
  align-items: center;
}
.flex {
  flex: 1;
}
.search-btn {
  background: #2f6b55;
  color: #fff;
  font-size: 24rpx;
  margin: 0;
  flex-shrink: 0;
}
.input,
.picker {
  background: #f7faf8;
  border: 1px solid #dfe8e3;
  border-radius: 10rpx;
  padding: 18rpx 16rpx;
  font-size: 28rpx;
  color: #1f3d32;
}
.list {
  margin-top: 12rpx;
  max-height: 360rpx;
  overflow-y: auto;
  border: 1px solid #eef3f0;
  border-radius: 10rpx;
}
.list-item {
  padding: 16rpx 18rpx;
  border-bottom: 1px solid #eef3f0;
}
.list-item:last-child {
  border-bottom: none;
}
.list-item.on {
  background: #e8f2ec;
}
.list-title {
  display: block;
  font-size: 28rpx;
  color: #1f3d32;
  font-weight: 600;
}
.list-meta {
  display: block;
  margin-top: 4rpx;
  font-size: 22rpx;
  color: #8a9a93;
}
.picked {
  display: block;
  margin-top: 12rpx;
  font-size: 24rpx;
  color: #2f6b55;
}
.area {
  width: 100%;
  min-height: 140rpx;
  background: #f7faf8;
  border: 1px solid #dfe8e3;
  border-radius: 10rpx;
  padding: 16rpx;
  font-size: 28rpx;
  box-sizing: border-box;
}
.hint {
  display: block;
  margin-top: 8rpx;
  font-size: 22rpx;
  color: #b45309;
}
.photos {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
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
.btn {
  background: #2f6b55;
  color: #fff;
  font-size: 30rpx;
  border-radius: 10rpx;
}
.link {
  margin-top: 20rpx;
  background: transparent;
  color: #2f6b55;
  font-size: 28rpx;
}
</style>
