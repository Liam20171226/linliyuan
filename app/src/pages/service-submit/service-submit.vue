<template>
  <view class="page">
    <view class="field">
      <text class="label">类型</text>
      <picker :range="categories" :value="catIndex" @change="onCat">
        <view class="picker">{{ category }}</view>
      </picker>
    </view>

    <view class="field">
      <text class="label">房屋</text>
      <picker
        v-if="roomOptions.length"
        :range="roomLabels"
        :value="roomIndex"
        @change="onRoom"
      >
        <view class="picker">{{ roomLabel || '请选择房屋' }}</view>
      </picker>
      <text v-else class="hint">暂无绑定房屋，请先完成认证</text>
    </view>

    <view class="field">
      <text class="label">{{ descLabel }}</text>
      <textarea
        class="textarea"
        :placeholder="descPlaceholder"
        v-model="content"
        maxlength="500"
      />
    </view>

    <view class="field">
      <text class="label">照片（选填，最多 3 张）</text>
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
    </view>

    <button class="btn" :loading="submitting" @click="submit">
      {{ submitLabel }}
    </button>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { request, type ApiError } from '../../utils/request'
import { roleLabel } from '../../utils/format'
import {
  chooseAndUpload,
  deleteAttachment,
  attachmentUrl,
  type UploadedFile,
} from '../../utils/upload'

type CatDef = {
  name: string
  kind: 'repair' | 'complaint'
  descLabel: string
  descPlaceholder: string
}

const CATEGORY_DEFS: CatDef[] = [
  {
    name: '公区报障',
    kind: 'repair',
    descLabel: '问题描述',
    descPlaceholder: '请写明具体位置（如：1号楼大堂）及故障现象，便于上门',
  },
  {
    name: '室内维修',
    kind: 'repair',
    descLabel: '问题描述',
    descPlaceholder: '请描述位置与现象，便于上门',
  },
  {
    name: '投诉',
    kind: 'complaint',
    descLabel: '投诉内容',
    descPlaceholder: '请客观描述情况',
  },
  {
    name: '表扬',
    kind: 'complaint',
    descLabel: '表扬内容',
    descPlaceholder: '请写明表扬对象与事迹',
  },
  {
    name: '咨询建议',
    kind: 'complaint',
    descLabel: '建议内容',
    descPlaceholder: '请填写咨询或建议内容',
  },
]

const categories = CATEGORY_DEFS.map((x) => x.name)
const category = ref('室内维修')
const kind = ref<'repair' | 'complaint'>('repair')
const descLabel = ref('问题描述')
const descPlaceholder = ref('请描述位置与现象，便于上门')
const submitLabel = ref('提交室内维修')
const content = ref('')
const submitting = ref(false)
const roomId = ref('')
const roomLabel = ref('')
const roomOptions = ref<Array<{ roomId: number | string; label: string }>>([])
const photos = ref<UploadedFile[]>([])

const catIndex = computed(() => {
  const i = categories.indexOf(category.value)
  return i >= 0 ? i : 0
})
const roomLabels = computed(() => roomOptions.value.map((r) => r.label))
const roomIndex = computed(() => {
  const i = roomOptions.value.findIndex((r) => String(r.roomId) === String(roomId.value))
  return i >= 0 ? i : 0
})

function applyCategory(name: string) {
  const def = CATEGORY_DEFS.find((x) => x.name === name) || CATEGORY_DEFS[1]
  category.value = def.name
  kind.value = def.kind
  descLabel.value = def.descLabel
  descPlaceholder.value = def.descPlaceholder
  submitLabel.value = '提交' + def.name
  uni.setNavigationBarTitle({ title: def.name })
}

onLoad((q) => {
  const raw = q?.category ? decodeURIComponent(String(q.category)) : ''
  applyCategory(categories.includes(raw) ? raw : '室内维修')
})

onShow(() => {
  loadRooms()
})

function onCat(e: { detail: { value: string } }) {
  const name = categories[Number(e.detail.value)]
  if (name) applyCategory(name)
}

function onRoom(e: { detail: { value: string } }) {
  const opt = roomOptions.value[Number(e.detail.value)]
  if (!opt) return
  roomId.value = String(opt.roomId)
  roomLabel.value = opt.label
}

async function loadRooms() {
  try {
    const rooms = await request<Array<Record<string, unknown>>>({
      url: '/resident/rooms',
    })
    roomOptions.value = (rooms || []).map((r) => ({
      roomId: r.roomId as number,
      label: `${r.address || r.roomNo || '房屋' + r.roomId}（${roleLabel(r.residentRole)}）`,
    }))
    if (roomOptions.value.length && !roomId.value) {
      const pick = roomOptions.value[0]
      roomId.value = String(pick.roomId)
      roomLabel.value = pick.label
    }
  } catch {
    roomOptions.value = []
  }
}

async function addPhotos() {
  try {
    photos.value = await chooseAndUpload(photos.value, 3)
  } catch (e) {
    uni.showToast({ title: (e as Error).message || '上传失败', icon: 'none' })
  }
}

async function removePhoto(id: number) {
  photos.value = photos.value.filter((x) => x.id !== id)
  await deleteAttachment(id)
}

function previewPhoto(f: UploadedFile) {
  const urls = photos.value.map((x) => x.localPath || attachmentUrl(x.id))
  const current = f.localPath || attachmentUrl(f.id)
  uni.previewImage({ current, urls })
}

async function submit() {
  if (!roomId.value) {
    uni.showToast({ title: '请选择房屋', icon: 'none' })
    return
  }
  if (!content.value.trim()) {
    uni.showToast({ title: '请填写' + descLabel.value, icon: 'none' })
    return
  }
  const attachmentIds = photos.value.map((x) => x.id)
  submitting.value = true
  try {
    if (kind.value === 'repair') {
      await request({
        url: '/resident/repairs',
        method: 'POST',
        data: {
          roomId: Number(roomId.value),
          category: category.value,
          location: null,
          description: content.value.trim(),
          attachmentIds,
        },
      })
    } else {
      await request({
        url: '/resident/complaints',
        method: 'POST',
        data: {
          roomId: Number(roomId.value),
          category: category.value,
          content: content.value.trim(),
          attachmentIds,
        },
      })
    }
    uni.showToast({ title: '已提交', icon: 'success' })
    setTimeout(() => uni.navigateBack({ delta: 1 }), 500)
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
</script>

<style scoped>
.page {
  min-height: 100vh;
  padding: 32rpx;
  background: #f7faf8;
  box-sizing: border-box;
}
.field {
  margin-bottom: 24rpx;
}
.label {
  display: block;
  margin-bottom: 10rpx;
  font-size: 26rpx;
  color: #5c6f68;
}
.picker {
  background: #fff;
  border: 1px solid #dfe8e3;
  border-radius: 10rpx;
  padding: 22rpx 20rpx;
  font-size: 28rpx;
  color: #1f3d32;
}
.hint {
  font-size: 24rpx;
  color: #c45c26;
}
.textarea {
  width: 100%;
  min-height: 220rpx;
  background: #fff;
  border: 1px solid #dfe8e3;
  border-radius: 10rpx;
  padding: 20rpx;
  font-size: 28rpx;
  box-sizing: border-box;
}
.photos {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
}
.thumb-wrap {
  position: relative;
  width: 160rpx;
  height: 160rpx;
}
.thumb {
  width: 160rpx;
  height: 160rpx;
  border-radius: 10rpx;
  background: #e8f2ec;
}
.del {
  position: absolute;
  top: -8rpx;
  right: -8rpx;
  width: 40rpx;
  height: 40rpx;
  line-height: 36rpx;
  text-align: center;
  background: #c45c26;
  color: #fff;
  border-radius: 20rpx;
  font-size: 28rpx;
}
.add {
  width: 160rpx;
  height: 160rpx;
  border: 1px dashed #c5d9cf;
  border-radius: 10rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24rpx;
  color: #2f6b55;
  background: #fff;
  text-align: center;
  padding: 8rpx;
  box-sizing: border-box;
}
.btn {
  margin-top: 16rpx;
  background: #2f6b55;
  color: #fff;
  font-size: 30rpx;
}
</style>
