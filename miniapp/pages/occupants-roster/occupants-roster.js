const { request } = require('../../utils/request')
const { roleLabel } = require('../../utils/format')
const { loadIdentities, switchIdentity } = require('../../utils/identity')

const ROLE_ORDER = {
  OWNER: 0,
  OWNER_MEMBER: 1,
  TENANT: 2
}

function sortPeople(a, b) {
  const ra = ROLE_ORDER[a.residentRole] != null ? ROLE_ORDER[a.residentRole] : 9
  const rb = ROLE_ORDER[b.residentRole] != null ? ROLE_ORDER[b.residentRole] : 9
  if (ra !== rb) return ra - rb
  return String(a.realName || '').localeCompare(String(b.realName || ''), 'zh')
}

function nodeKey(parts) {
  return parts.map((p) => (p == null || p === '' ? '_' : String(p))).join('/')
}

function naturalRoom(a, b) {
  return String(a.roomNo || '').localeCompare(String(b.roomNo || ''), 'zh', { numeric: true })
}

function personRoleLine(raw) {
  const parts = []
  const resident = roleLabel(raw.residentRole)
  if (resident) parts.push(resident)
  const ct = roleLabel(raw.committeeTitle)
  if (ct) parts.push('业委会' + ct)
  return parts.join(' · ') || '住户'
}

/** 楼栋 → 单元 → 楼层 → 房屋 → 住户 */
function buildTree(list) {
  const buildings = new Map()

  list.forEach((raw) => {
    const person = {
      id: raw.id,
      realName: raw.realName || '未填姓名',
      roleLabel: personRoleLine(raw),
      residentRole: raw.residentRole,
      mobile: raw.mobile || '无手机',
      idCardNo: raw.idCardNo || '无身份证'
    }
    const bKey = nodeKey([raw.buildingId, raw.buildingName || '未分楼栋'])
    const uKey = nodeKey([raw.unitId, raw.unitName || '未分单元'])
    const fKey = nodeKey([raw.floorId, raw.floorName || '未分层'])
    const rKey = nodeKey([raw.roomId, raw.roomNo || '未知房号'])

    if (!buildings.has(bKey)) {
      buildings.set(bKey, {
        key: bKey,
        label: raw.buildingName || '未分楼栋',
        expanded: false,
        count: 0,
        units: new Map()
      })
    }
    const building = buildings.get(bKey)
    building.count += 1

    if (!building.units.has(uKey)) {
      building.units.set(uKey, {
        key: uKey,
        label: raw.unitName || '未分单元',
        expanded: false,
        count: 0,
        floors: new Map()
      })
    }
    const unit = building.units.get(uKey)
    unit.count += 1

    if (!unit.floors.has(fKey)) {
      unit.floors.set(fKey, {
        key: fKey,
        label: raw.floorName || '未分层',
        expanded: false,
        count: 0,
        rooms: new Map()
      })
    }
    const floor = unit.floors.get(fKey)
    floor.count += 1

    if (!floor.rooms.has(rKey)) {
      floor.rooms.set(rKey, {
        key: rKey,
        label: raw.roomNo || '未知房号',
        roomNo: raw.roomNo || '',
        address: raw.address || '',
        expanded: true,
        people: []
      })
    }
    floor.rooms.get(rKey).people.push(person)
  })

  const tree = Array.from(buildings.values()).map((b) => {
    const units = Array.from(b.units.values()).map((u) => {
      const floors = Array.from(u.floors.values()).map((f) => {
        const rooms = Array.from(f.rooms.values())
          .map((r) => {
            r.people = r.people.slice().sort(sortPeople)
            return r
          })
          .sort(naturalRoom)
        return {
          key: f.key,
          label: f.label,
          count: f.count,
          expanded: false,
          rooms
        }
      })
      floors.sort((a, c) => String(a.label).localeCompare(String(c.label), 'zh', { numeric: true }))
      return {
        key: u.key,
        label: u.label,
        count: u.count,
        expanded: false,
        floors
      }
    })
    units.sort((a, c) => String(a.label).localeCompare(String(c.label), 'zh', { numeric: true }))
    return {
      key: b.key,
      label: b.label,
      count: b.count,
      expanded: false,
      units
    }
  })

  tree.sort((a, b) => String(a.label).localeCompare(String(b.label), 'zh', { numeric: true }))
  if (tree[0]) {
    tree[0].expanded = true
    if (tree[0].units[0]) {
      tree[0].units[0].expanded = true
      if (tree[0].units[0].floors[0]) {
        tree[0].units[0].floors[0].expanded = true
      }
    }
  }
  return tree
}

function patchExpanded(tree, path, key) {
  const [level, bKey, uKey, fKey] = path
  return tree.map((b) => {
    if (level === 'building' && b.key === key) {
      return Object.assign({}, b, { expanded: !b.expanded })
    }
    if (b.key !== bKey) return b
    return Object.assign({}, b, {
      units: b.units.map((u) => {
        if (level === 'unit' && u.key === key) {
          return Object.assign({}, u, { expanded: !u.expanded })
        }
        if (u.key !== uKey) return u
        return Object.assign({}, u, {
          floors: u.floors.map((f) => {
            if (level === 'floor' && f.key === key) {
              return Object.assign({}, f, { expanded: !f.expanded })
            }
            if (f.key !== fKey) return f
            return Object.assign({}, f, {
              rooms: f.rooms.map((r) => {
                if (level === 'room' && r.key === key) {
                  return Object.assign({}, r, { expanded: !r.expanded })
                }
                return r
              })
            })
          })
        })
      })
    })
  })
}

async function fetchAllOccupants(keyword) {
  const pageSize = 100
  let page = 1
  let total = 0
  const all = []
  for (;;) {
    const params = { page, pageSize }
    if (keyword) params.realName = keyword
    const data = await request({
      url: '/committee/occupants',
      data: params
    })
    const chunk = (data && data.list) || []
    total = (data && data.total) != null ? Number(data.total) : all.length + chunk.length
    all.push.apply(all, chunk)
    if (!chunk.length || all.length >= total || chunk.length < pageSize) break
    page += 1
    if (page > 50) break
  }
  return { list: all, total: total || all.length }
}

/**
 * 名册依赖 JWT 小区；无小区或非业委上下文时，切到本小区带业委会职务的住户身份。
 */
async function ensureRosterContext() {
  try {
    const packed = await loadIdentities()
    const current = packed.current || {}
    const cid = current.communityId != null ? Number(current.communityId) : null
    const type = current.identityType || ''
    const hasCid = cid != null && !Number.isNaN(cid)
    if (hasCid && (type === 'RESIDENT' || type === 'COMMITTEE' || type === 'STAFF')) {
      const inCommunity = (packed.identities || []).some(
        (it) => it.isCommittee && Number(it.communityId) === cid
      )
      if (inCommunity || packed.isCommittee) return
    }
    const hit = (packed.identities || []).find(
      (it) => it.identityType === 'RESIDENT' && it.isCommittee
    ) || (packed.identities || []).find((it) => it.isCommittee)
    if (hit) await switchIdentity(hit)
  } catch (e) { /* 后续请求会提示 */ }
}

Page({
  data: {
    keyword: '',
    tree: [],
    loading: true,
    total: 0
  },

  onShow() { this.load() },
  onPullDownRefresh() {
    this.load().finally(() => wx.stopPullDownRefresh())
  },

  onKeyword(e) {
    this.setData({ keyword: e.detail.value })
  },

  search() { this.load() },

  toggle(e) {
    const { level, key, building, unit, floor } = e.currentTarget.dataset
    if (!level || !key) return
    const tree = patchExpanded(this.data.tree, [level, building, unit, floor], key)
    this.setData({ tree })
  },

  async load() {
    this.setData({ loading: true })
    try {
      await ensureRosterContext()
      const kw = (this.data.keyword || '').trim()
      const { list, total } = await fetchAllOccupants(kw)
      const tree = buildTree(list)
      this.setData({ tree, total })
    } catch (e) {
      this.setData({ tree: [], total: 0 })
      wx.showToast({ title: (e && e.message) || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  }
})
