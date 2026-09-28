<template>
  <view class="page">
    <view v-if="loading" class="empty">加载中…</view>
    <template v-else-if="job">
      <view class="card">
        <text class="title">{{ job.planTitle || '巡检执行' }}</text>
        <text class="meta">进度 {{ progressText }} · {{ job.status }}</text>
        <text v-if="claimHint" class="hint">{{ claimHint }}</text>
        <button
          v-if="canClaim"
          class="btn"
          :disabled="claiming"
          @click="claim"
        >接单</button>
        <button
          v-if="mine && !done"
          class="btn"
          :disabled="submitting"
          @click="doScan"
        >扫码打卡</button>
      </view>

      <view class="card">
        <text class="sec">巡检点位</text>
        <view v-if="!spots.length" class="empty-inline">暂无点位</view>
        <view v-for="s in spots" :key="String(s.id)" class="spot">
          <view class="row">
            <text class="spot-name">{{ s.name || '巡检点' }}</text>
            <text class="spot-st" :class="{ ok: !!s.scannedAt }">
              {{ s.scannedAt ? '已巡' : '未巡' }}
            </text>
          </view>
          <text v-if="s.cats" class="meta">{{ s.cats }}</text>
          <text v-if="s.scannedAtLabel" class="meta">{{ s.scannedAtLabel }}</text>
          <button
            v-if="mine && !done && !s.scannedAt && s.qrToken"
            class="btn ghost sm"
            :disabled="submitting"
            @click="manualVisit(s)"
          >手动打卡</button>
        </view>
      </view>

      <view v-if="scanOpen" class="mask" @click="closeScan">
        <view class="sheet" @click.stop>
          <text class="sec">打卡 · {{ scanSpotName }}</text>
          <text class="meta">请拍照上传后提交</text>
          <view v-if="photoLocal" class="photo-preview">
            <image class="preview-img" :src="photoLocal" mode="aspectFill" />
          </view>
          <button class="btn ghost" :disabled="submitting" @click="choosePhoto">
            {{ photoId ? '重拍 / 换图' : '拍照上传' }}
          </button>
          <textarea
            v-model="note"
            class="area"
            placeholder="备注（可选）"
            :maxlength="200"
          />
          <button class="btn" :disabled="submitting" @click="submitVisit">提交打卡</button>
          <button class="btn ghost" :disabled="submitting" @click="closeScan">取消</button>
        </view>
      </view>
    </template>
    <view v-else class="empty">任务不存在</view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { request, type ApiError } from '../../utils/request'
import { formatDateTime } from '../../utils/format'
import { uploadTempFile } from '../../utils/upload'

type Spot = {
  id?: number
  name?: string
  cats?: string
  scannedAt?: unknown
  scannedAtLabel?: string
  qrToken?: string
  qrContent?: string
}

const id = ref<number | null>(null)
const loading = ref(true)
const claiming = ref(false)
const submitting = ref(false)
const job = ref<Record<string, unknown> | null>(null)
const spots = ref<Spot[]>([])
const progressText = ref('')
const canClaim = ref(false)
const mine = ref(false)
const done = ref(false)
const claimHint = ref('')

const scanOpen = ref(false)
const scanSpotName = ref('巡检点')
const scanQrContent = ref('')
const note = ref('')
const photoLocal = ref('')
const photoId = ref<number | null>(null)

async function load() {
  if (!id.value) return
  loading.value = true
  try {
    const d = await request<Record<string, unknown>>({
      url: `/staff/inspect/jobs/${id.value}`,
    })
    const list = ((d.spots as Array<Record<string, unknown>>) || []).map((s) => ({
      ...s,
      cats: ((s.categoryLabels as string[]) || []).join(' · '),
      scannedAtLabel: s.scannedAt
        ? (formatDateTime(s.scannedAt) || '').slice(0, 16)
        : '',
    })) as Spot[]
    job.value = d
    spots.value = list
    progressText.value = `${d.visitedCount || 0}/${d.spotCount || 0}`
    canClaim.value = !!d.canClaim
    mine.value = !!d.mine
    done.value = d.status === 'DONE' || d.status === 'CANCELLED'
    claimHint.value =
      !d.canClaim &&
      !d.mine &&
      (d.status === 'OPEN' || d.status === 'IN_PROGRESS') &&
      !d.assigneeUserId
        ? '本计划执行岗：' +
          (((d.executorRoleLabels as string[]) || []).join('、') || '未指定') +
          '，当前身份无法接单'
        : ''
    uni.setNavigationBarTitle({ title: String(d.planTitle || '巡检执行') })
  } catch (e) {
    job.value = null
    uni.showToast({ title: (e as ApiError).message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

async function claim() {
  if (claiming.value || !id.value) return
  claiming.value = true
  try {
    await request({
      url: `/staff/inspect/jobs/${id.value}/claim`,
      method: 'POST',
      data: {},
    })
    uni.showToast({ title: '已接单' })
    await load()
  } catch (e) {
    uni.showToast({ title: (e as ApiError).message || '接单失败', icon: 'none' })
  } finally {
    claiming.value = false
  }
}

function closeScan() {
  scanOpen.value = false
  scanQrContent.value = ''
  note.value = ''
  photoLocal.value = ''
  photoId.value = null
}

function openScanPanel(qrContent: string, spotName: string) {
  scanOpen.value = true
  scanSpotName.value = spotName || '巡检点'
  scanQrContent.value = qrContent
  note.value = ''
  photoLocal.value = ''
  photoId.value = null
}

async function doScan() {
  if (!mine.value) {
    uni.showToast({ title: '请先接单', icon: 'none' })
    return
  }
  if (done.value) {
    uni.showToast({ title: '任务已结束', icon: 'none' })
    return
  }
  try {
    const scan = await new Promise<UniApp.ScanCodeSuccessRes>((resolve, reject) => {
      uni.scanCode({
        onlyFromCamera: false,
        success: resolve,
        fail: reject,
      })
    })
    const content = (scan && (scan.result || '')) || ''
    if (!content) {
      uni.showToast({ title: '未识别到二维码', icon: 'none' })
      return
    }
    let spotName = '巡检点'
    const token =
      content.indexOf('PROPERTY_INSPECT:') === 0
        ? content.slice('PROPERTY_INSPECT:'.length)
        : content
    const hit = spots.value.find((s) => s.qrToken === token || s.qrContent === content)
    if (hit) spotName = hit.name || spotName
    openScanPanel(content, spotName)
  } catch (e) {
    const msg = String((e as { errMsg?: string })?.errMsg || '')
    if (msg.includes('cancel')) return
    uni.showToast({ title: '扫码取消或失败', icon: 'none' })
  }
}

async function choosePhoto() {
  try {
    const paths: string[] = await new Promise((resolve, reject) => {
      uni.chooseImage({
        count: 1,
        sizeType: ['compressed'],
        sourceType: ['camera', 'album'],
        success: (res) => resolve(res.tempFilePaths || []),
        fail: (err) => {
          if (err && String(err.errMsg || '').includes('cancel')) resolve([])
          else reject(err)
        },
      })
    })
    if (!paths.length) return
    uni.showLoading({ title: '上传中', mask: true })
    try {
      const uploaded = await uploadTempFile(paths[0])
      photoLocal.value = paths[0]
      photoId.value = uploaded.id
    } finally {
      uni.hideLoading()
    }
  } catch (e) {
    uni.showToast({ title: (e as Error).message || '上传失败', icon: 'none' })
  }
}

async function submitVisit() {
  if (submitting.value || !id.value) return
  if (!photoId.value) {
    uni.showToast({ title: '请先拍照', icon: 'none' })
    return
  }
  if (!scanQrContent.value) {
    uni.showToast({ title: '缺少扫码内容', icon: 'none' })
    return
  }
  submitting.value = true
  try {
    await request({
      url: `/staff/inspect/jobs/${id.value}/scan`,
      method: 'POST',
      data: {
        qrContent: scanQrContent.value,
        photoAttachmentId: photoId.value,
        note: note.value.trim() || null,
      },
    })
    uni.showToast({ title: '已记录', icon: 'success' })
    closeScan()
    await load()
  } catch (e) {
    uni.showToast({ title: (e as ApiError).message || '提交失败', icon: 'none' })
  } finally {
    submitting.value = false
  }
}

/** 点位手动打卡：同样走拍照 + scan */
async function manualVisit(s: Spot) {
  if (!id.value || submitting.value) return
  const qr =
    s.qrContent ||
    (s.qrToken ? `PROPERTY_INSPECT:${s.qrToken}` : '') ||
    s.qrToken ||
    ''
  if (!qr) {
    uni.showToast({ title: '无点位码', icon: 'none' })
    return
  }
  openScanPanel(String(qr), s.name || '巡检点')
}

onLoad((q) => {
  id.value = q?.id ? Number(q.id) : null
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
.empty-inline {
  color: #8a9a93;
  font-size: 26rpx;
  padding: 12rpx 0;
}
.card {
  background: #fff;
  border: 1px solid #dfe8e3;
  border-radius: 12rpx;
  padding: 24rpx;
  margin-bottom: 16rpx;
}
.title {
  display: block;
  font-size: 32rpx;
  font-weight: 700;
  color: #1f4e3d;
}
.meta {
  display: block;
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #8a9a93;
}
.hint {
  display: block;
  margin: 12rpx 0;
  font-size: 24rpx;
  color: #b45309;
}
.sec {
  display: block;
  font-size: 28rpx;
  font-weight: 600;
  color: #1f4e3d;
  margin-bottom: 12rpx;
}
.spot {
  padding: 16rpx 0;
  border-top: 1px solid #eef3f0;
}
.row {
  display: flex;
  justify-content: space-between;
}
.spot-name {
  font-size: 28rpx;
  color: #1f3d32;
  font-weight: 600;
}
.spot-st {
  font-size: 24rpx;
  color: #8a9a93;
}
.spot-st.ok {
  color: #2f6b55;
}
.btn {
  margin-top: 16rpx;
  background: #2f6b55;
  color: #fff;
  font-size: 28rpx;
  border-radius: 10rpx;
}
.btn.ghost {
  background: #fff;
  color: #2f6b55;
  border: 1px solid #c5d9cf;
}
.btn.sm {
  font-size: 24rpx;
  padding: 0 20rpx;
  line-height: 2;
  width: auto;
  display: inline-block;
}
.mask {
  position: fixed;
  left: 0;
  right: 0;
  top: 0;
  bottom: 0;
  background: rgba(15, 40, 30, 0.45);
  display: flex;
  align-items: flex-end;
  z-index: 100;
}
.sheet {
  width: 100%;
  background: #fff;
  border-radius: 24rpx 24rpx 0 0;
  padding: 32rpx;
  box-sizing: border-box;
  max-height: 85vh;
  overflow-y: auto;
}
.photo-preview {
  margin: 12rpx 0;
}
.preview-img {
  width: 100%;
  height: 320rpx;
  border-radius: 12rpx;
  background: #e8f2ec;
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
  margin-top: 12rpx;
}
</style>
