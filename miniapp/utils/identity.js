const { request } = require('./request')
const { decorateIdentity } = require('./format')

const LAST_RESIDENT_KEY = 'lastResidentRoom'

function roomIdOf(x) {
  if (!x) return null
  if (x.roomId != null && x.roomId !== '') return Number(x.roomId)
  const r0 = x.rooms && x.rooms[0]
  if (r0 && r0.roomId != null) return Number(r0.roomId)
  return null
}

function sameIdentity(a, b) {
  if (!a || !b) return false
  if (a.identityType !== b.identityType) return false
  if (a.identityType === 'GUEST' || a.identityType === 'PLATFORM') return true
  const ac = a.communityId == null ? null : Number(a.communityId)
  const bc = b.communityId == null ? null : Number(b.communityId)
  if (ac !== bc) return false
  if (a.identityType === 'RESIDENT') {
    const ar = roomIdOf(a)
    const br = roomIdOf(b)
    if (ar == null || br == null) return false
    return ar === br
  }
  return true
}

/** 业委会不单独切换：合并进同小区住户行，并挂上职务 */
function mergeCommitteeIntoResident(raw) {
  const list = raw || []
  const committeeByCommunity = {}
  list.forEach((it) => {
    if (it.identityType !== 'COMMITTEE' || it.communityId == null) return
    const cid = Number(it.communityId)
    committeeByCommunity[cid] = {
      committeeTitle: it.committeeTitle || it.title || it.role || '',
      communityName: it.communityName
    }
  })
  return list
    .filter((it) => it.identityType !== 'COMMITTEE')
    .map((it) => {
      if (it.identityType !== 'RESIDENT' || it.communityId == null) return it
      const meta = committeeByCommunity[Number(it.communityId)]
      if (!meta) return it
      return Object.assign({}, it, {
        isCommittee: true,
        committeeTitle: meta.committeeTitle || 'MEMBER'
      })
    })
}

/** 住户同一小区多房 → 展开为多条可选项（每房一行） */
function flattenIdentities(raw) {
  const merged = mergeCommitteeIntoResident(raw)
  const out = []
  merged.forEach((it) => {
    if (it.identityType === 'RESIDENT' && Array.isArray(it.rooms) && it.rooms.length) {
      it.rooms.forEach((r) => {
        out.push({
          identityType: 'RESIDENT',
          communityId: it.communityId,
          communityName: it.communityName,
          roomId: r.roomId,
          rooms: [r],
          isCommittee: !!it.isCommittee,
          committeeTitle: it.committeeTitle || ''
        })
      })
    } else {
      out.push(it)
    }
  })
  return out
}

function markActive(list, current) {
  return (list || []).map((it, idx) => {
    const d = decorateIdentity(it)
    const rid = roomIdOf(it)
    d.key = `${it.identityType}-${it.communityId || 0}-${rid || 0}-${idx}`
    d.roomId = rid
    d.active = sameIdentity(it, current)
    return d
  })
}

function pickActive(identities, current) {
  const list = identities || []
  const hit = list.find((it) => it.active)
  if (hit) return hit
  if (current && current.identityType === 'COMMITTEE' && current.communityId != null) {
    const cid = Number(current.communityId)
    return list.find((it) => it.identityType === 'RESIDENT' && Number(it.communityId) === cid && it.isCommittee)
      || list.find((it) => it.identityType === 'RESIDENT' && Number(it.communityId) === cid)
      || null
  }
  if (current && current.identityType === 'RESIDENT' && current.communityId != null) {
    const cid = Number(current.communityId)
    return list.find((it) => it.identityType === 'RESIDENT' && Number(it.communityId) === cid) || null
  }
  return list.find((it) => sameIdentity(it, current)) || list[0] || null
}

/** 头像区副文案：职务 */
function contextLineFrom(active) {
  if (!active) return '点按切换身份'
  return active.roleLabel || '点按切换身份'
}

/** 首页单行：小区 · 职务 */
function summaryLineFrom(active) {
  if (!active) return '点按切换身份'
  return [active.label, active.roleLabel].filter(Boolean).join(' · ')
}

function readLastResident() {
  try {
    const v = wx.getStorageSync(LAST_RESIDENT_KEY)
    if (!v || v.roomId == null || v.roomId === '') return null
    return {
      communityId: v.communityId != null && v.communityId !== '' ? Number(v.communityId) : null,
      roomId: Number(v.roomId)
    }
  } catch (e) {
    return null
  }
}

function saveLastResident(communityId, roomId) {
  if (roomId == null || roomId === '') return
  try {
    wx.setStorageSync(LAST_RESIDENT_KEY, {
      communityId: communityId != null && communityId !== '' ? Number(communityId) : null,
      roomId: Number(roomId),
      at: Date.now()
    })
  } catch (e) { /* ignore */ }
}

function residentRoomChoices(rawIdentities) {
  return flattenIdentities(rawIdentities || []).filter(
    (it) => it.identityType === 'RESIDENT' && roomIdOf(it) != null
  )
}

/** 优先上次房屋；无效则随机一套 */
function pickPreferredResidentItem(rawIdentities) {
  const choices = residentRoomChoices(rawIdentities)
  if (!choices.length) return null
  const last = readLastResident()
  if (last) {
    const hit = choices.find((it) => {
      if (Number(roomIdOf(it)) !== last.roomId) return false
      if (last.communityId == null) return true
      return Number(it.communityId) === last.communityId
    })
    if (hit) return hit
  }
  return choices[Math.floor(Math.random() * choices.length)]
}

async function loadIdentities() {
  const data = await request({ url: '/auth/identities' })
  const raw = data.identities || []
  const current = data.current || null
  const user = data.profile || data.user || null
  const flat = flattenIdentities(raw)
  const identities = markActive(flat, current)
  const active = pickActive(identities, current)
  const personName = personDisplayName(user)
  const isCommittee = !!(active && active.isCommittee)
  return {
    raw,
    current,
    user,
    identities,
    active,
    displayName: active ? active.label : '邻里院用户',
    personName,
    contextLine: contextLineFrom(active),
    summaryLine: summaryLineFrom(active),
    currentRoomId: roomIdOf(current),
    isCommittee
  }
}

function personDisplayName(user) {
  if (user && user.realName != null && String(user.realName).trim()) {
    return String(user.realName).trim()
  }
  const mobile = user && user.mobile != null ? String(user.mobile).trim() : ''
  if (mobile.length >= 4) {
    return '尾号' + mobile.slice(-4)
  }
  return '邻居'
}

async function updateRealName(realName) {
  return request({
    url: '/auth/profile',
    method: 'PUT',
    data: { realName }
  })
}

async function switchIdentity(item) {
  const roomId = roomIdOf(item)
  const data = await request({
    url: '/auth/context/switch',
    method: 'POST',
    data: {
      identityType: item.identityType,
      communityId: item.communityId || null,
      roomId: roomId
    }
  })
  const app = getApp()
  wx.setStorageSync('token', data.token)
  if (app && app.globalData) app.globalData.token = data.token
  if (item.identityType === 'RESIDENT' && roomId != null) {
    saveLastResident(item.communityId, roomId)
  }
  return data
}

/**
 * 登录后 / 冷启动：住户身份下恢复上次房屋；无记录则随机一套并落盘。
 * 若当前仍是旧版 COMMITTEE，强制切回同小区住户（保留业委会能力）。
 * 其他非住户身份不强制切换。
 */
async function ensurePreferredResidentContext(rawIdentities, current) {
  if (current && current.identityType === 'COMMITTEE') {
    const preferred = pickPreferredResidentItem(rawIdentities)
      || flattenIdentities(rawIdentities).find(
        (it) => it.identityType === 'RESIDENT'
          && Number(it.communityId) === Number(current.communityId)
      )
    if (preferred) {
      const data = await switchIdentity(preferred)
      return { switched: true, item: preferred, token: data.token }
    }
  }
  if (current && current.identityType && current.identityType !== 'RESIDENT') {
    return { switched: false }
  }
  const preferred = pickPreferredResidentItem(rawIdentities)
  if (!preferred) return { switched: false }

  const prefRoom = roomIdOf(preferred)
  const curRoom = current ? roomIdOf(current) : null
  const sameRoom = curRoom != null
    && Number(curRoom) === Number(prefRoom)
    && current
    && current.identityType === 'RESIDENT'
    && Number(current.communityId) === Number(preferred.communityId)

  if (sameRoom) {
    saveLastResident(preferred.communityId, prefRoom)
    return { switched: false, item: preferred }
  }

  const data = await switchIdentity(preferred)
  return { switched: true, item: preferred, token: data.token }
}

/** 有 token 时拉取身份并恢复房屋偏好 */
async function restorePreferredResidentIfNeeded() {
  const app = getApp()
  const token = (app && app.globalData && app.globalData.token) || wx.getStorageSync('token')
  if (!token) return { switched: false }
  try {
    const packed = await loadIdentities()
    return await ensurePreferredResidentContext(packed.raw, packed.current)
  } catch (e) {
    return { switched: false, error: e }
  }
}

module.exports = {
  markActive,
  flattenIdentities,
  loadIdentities,
  switchIdentity,
  updateRealName,
  contextLineFrom,
  summaryLineFrom,
  sameIdentity,
  pickActive,
  personDisplayName,
  roomIdOf,
  saveLastResident,
  readLastResident,
  pickPreferredResidentItem,
  ensurePreferredResidentContext,
  restorePreferredResidentIfNeeded
}
