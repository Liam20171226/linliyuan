<template>
  <view class="page">
    <view v-if="loading" class="empty">加载中…</view>
    <view v-else-if="error" class="empty">{{ error }}</view>
    <view v-else>
      <text class="title">{{ title }}</text>
      <text class="meta">{{ sourceLabel }} · {{ statusLabel }}</text>
      <text v-if="rangeLabel" class="meta">{{ rangeLabel }}</text>
      <text v-if="background" class="bg">{{ background }}</text>

      <view class="field">
        <text class="label">投票房屋</text>
        <picker
          v-if="roomOptions.length"
          :range="roomLabels"
          :value="roomIndex"
          @change="onRoom"
        >
          <view class="picker">{{ roomLabel || '请选择房屋' }}</view>
        </picker>
        <text v-else class="hint">暂无业主房屋</text>
      </view>

      <view class="opts">
        <view
          v-for="opt in options"
          :key="opt.id"
          :class="['opt', opt.selected ? 'on' : '', locked ? 'disabled' : '']"
          @click="selectOption(opt.id)"
        >
          <text>{{ opt.optionText }}</text>
        </view>
      </view>

      <button
        class="btn"
        :disabled="!canConfirm || casting || locked || closed"
        :loading="casting"
        @click="confirm"
      >
        {{ locked ? '已投票' : closed ? '已结束' : '确认投票' }}
      </button>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { request, type ApiError } from '../../utils/request'
import { voteStatusLabel, roleLabel, formatDateTime } from '../../utils/format'

type Opt = { id: number | string; optionText: string; selected: boolean }

const loading = ref(true)
const error = ref('')
const voteId = ref<number | null>(null)
const title = ref('')
const background = ref('')
const statusLabel = ref('')
const sourceLabel = ref('')
const rangeLabel = ref('')
const options = ref<Opt[]>([])
const roomId = ref('')
const roomLabel = ref('')
const roomOptions = ref<Array<{ roomId: number | string; label: string }>>([])
const ballotsByRoom = ref<Record<string, string>>({})
const selectedOptionId = ref('')
const locked = ref(false)
const canConfirm = ref(false)
const casting = ref(false)
const closed = ref(false)

const roomLabels = computed(() => roomOptions.value.map((r) => r.label))
const roomIndex = computed(() => {
  const i = roomOptions.value.findIndex((r) => String(r.roomId) === String(roomId.value))
  return i >= 0 ? i : 0
})

function sourceLabelOf(role: unknown) {
  if (role === 'COMMITTEE') return '业委会'
  if (role === 'PROPERTY_MANAGER' || role === 'STAFF') return '物业'
  if (role === 'PLATFORM') return '平台'
  return '业主投票'
}

function shortRange(startAt: unknown, endAt: unknown) {
  const a = formatDateTime(startAt)
  const b = formatDateTime(endAt)
  const as = a ? a.slice(0, 16) : ''
  const bs = b ? b.slice(0, 16) : ''
  if (as && bs) return `${as} ~ ${bs}`
  return as || bs || ''
}

function syncRoomState(rid: string, pending: string) {
  const lockedOptionId = rid && ballotsByRoom.value[rid] != null ? ballotsByRoom.value[rid] : ''
  const isLocked = !!lockedOptionId
  locked.value = isLocked
  selectedOptionId.value = isLocked ? lockedOptionId : pending || ''
  canConfirm.value = !isLocked && !!pending
  options.value = options.value.map((o) => ({
    ...o,
    selected: selectedOptionId.value !== '' && String(o.id) === selectedOptionId.value,
  }))
}

onLoad((q) => {
  voteId.value = q?.id ? Number(q.id) : null
  if (q?.roomId) roomId.value = String(q.roomId)
  if (!voteId.value) {
    loading.value = false
    error.value = '无效投票'
  }
})

onShow(() => {
  if (voteId.value) bootstrap()
})

function onRoom(e: { detail: { value: string } }) {
  const opt = roomOptions.value[Number(e.detail.value)]
  if (!opt) return
  roomId.value = String(opt.roomId)
  roomLabel.value = opt.label
  syncRoomState(roomId.value, '')
}

function selectOption(oid: number | string) {
  if (locked.value || closed.value || casting.value) return
  selectedOptionId.value = String(oid)
  canConfirm.value = true
  options.value = options.value.map((o) => ({
    ...o,
    selected: String(o.id) === selectedOptionId.value,
  }))
}

async function bootstrap() {
  loading.value = true
  error.value = ''
  try {
    await loadRooms()
    await loadDetail()
  } catch (e) {
    error.value = (e as ApiError).message || '加载失败'
  } finally {
    loading.value = false
  }
}

async function loadRooms() {
  try {
    const rooms = await request<Array<Record<string, unknown>>>({
      url: '/resident/rooms',
    })
    const owners = (rooms || []).filter((r) => r.residentRole === 'OWNER')
    const source = owners.length ? owners : rooms || []
    roomOptions.value = source.map((r) => ({
      roomId: r.roomId as number,
      label: `${r.address || r.roomNo || '房屋' + r.roomId}（${roleLabel(r.residentRole)}）`,
    }))
    if (roomOptions.value.length) {
      const hit = roomId.value
        ? roomOptions.value.find((o) => String(o.roomId) === String(roomId.value))
        : null
      const pick = hit || roomOptions.value[0]
      roomId.value = String(pick.roomId)
      roomLabel.value = pick.label
    }
  } catch {
    roomOptions.value = []
  }
}

async function loadDetail() {
  const raw = await request<Record<string, unknown>>({
    url: `/resident/votes/${voteId.value}`,
  })
  const opts = ((raw.options as Array<Record<string, unknown>>) || []).map((o) => ({
    id: o.id as number,
    optionText: String(o.optionText || o.text || ''),
    selected: false,
  }))
  const map: Record<string, string> = {}
  ;((raw.myBallots as Array<Record<string, unknown>>) || []).forEach((b) => {
    if (b && b.roomId != null && b.optionId != null) {
      map[String(b.roomId)] = String(b.optionId)
    }
  })
  ballotsByRoom.value = map
  const status = raw.status
  closed.value = status === 'ENDED' || status === 'CLOSED'
  title.value = String(raw.title || '投票')
  background.value = String(raw.background || '')
  statusLabel.value = voteStatusLabel(status)
  sourceLabel.value = sourceLabelOf(raw.creatorRole)
  rangeLabel.value = shortRange(raw.startAt, raw.endAt)
  options.value = opts
  syncRoomState(roomId.value, '')
}

async function confirm() {
  if (casting.value || locked.value || closed.value) return
  if (!roomId.value) {
    uni.showToast({ title: '请先选择投票房屋', icon: 'none' })
    return
  }
  if (!selectedOptionId.value) {
    uni.showToast({ title: '请先选择选项', icon: 'none' })
    return
  }
  casting.value = true
  try {
    await request({
      url: `/resident/votes/${voteId.value}/ballots`,
      method: 'POST',
      data: {
        ballots: [
          {
            roomId: Number(roomId.value),
            optionId: Number(selectedOptionId.value),
          },
        ],
      },
    })
    uni.showToast({ title: '投票成功', icon: 'success' })
    await loadDetail()
  } catch (e) {
    uni.showModal({
      title: '投票失败',
      content: (e as ApiError).message || '请稍后重试',
      showCancel: false,
    })
  } finally {
    casting.value = false
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
.empty {
  padding: 80rpx 0;
  text-align: center;
  color: #8a9a93;
  font-size: 28rpx;
}
.title {
  display: block;
  font-size: 34rpx;
  font-weight: 700;
  color: #1f4e3d;
  line-height: 1.4;
}
.meta {
  display: block;
  margin-top: 10rpx;
  font-size: 24rpx;
  color: #8a9a93;
}
.bg {
  display: block;
  margin: 20rpx 0;
  font-size: 26rpx;
  color: #5c6f68;
  line-height: 1.55;
  white-space: pre-wrap;
}
.field {
  margin: 24rpx 0;
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
.opts {
  margin: 12rpx 0 28rpx;
}
.opt {
  background: #fff;
  border: 1px solid #dfe8e3;
  border-radius: 10rpx;
  padding: 24rpx;
  margin-bottom: 12rpx;
  font-size: 28rpx;
  color: #1f3d32;
}
.opt.on {
  border-color: #2f6b55;
  background: #e8f2ec;
  color: #1f4e3d;
  font-weight: 600;
}
.opt.disabled {
  opacity: 0.85;
}
.btn {
  background: #2f6b55;
  color: #fff;
  font-size: 30rpx;
}
.btn[disabled] {
  opacity: 0.55;
}
</style>
