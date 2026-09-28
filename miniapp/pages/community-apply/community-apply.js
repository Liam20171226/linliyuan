const { request } = require('../../utils/request')
const { ensureLogin } = require('../../utils/format')
const { tree: localRegions } = require('../../utils/regions')

const ROLE_VALUES = ['RESIDENT', 'STAFF']
const ROLE_LABELS = ['住户（非物业）', '物业']
const STATUS_LABEL = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已拒绝'
}

/** 0=不初始化；1=自行填写 */
const INIT_CUSTOM = 1
const INIT_LABELS = ['不初始化房屋', '自行填写数量']

const LEGACY_TEMPLATE_LABEL = {
  T_1_3_10_4: '标准高层（1 栋 × 3 单元 × 10 层 × 4 户）',
  T_1_2_6_2: '小高层（1 栋 × 2 单元 × 6 层 × 2 户）',
  T_2_2_12_4: '双栋高层（2 栋 × 2 单元 × 12 层 × 4 户）'
}

function parsePositiveInt(v) {
  const n = parseInt(String(v == null ? '' : v).trim(), 10)
  return Number.isFinite(n) && n > 0 ? n : 0
}

function describeTemplateCode(code) {
  if (!code) return '不初始化'
  const m = /^C_(\d+)_(\d+)_(\d+)_(\d+)$/.exec(code)
  if (m) return `自定义（${m[1]} 栋 × ${m[2]} 单元 × ${m[3]} 层 × ${m[4]} 户）`
  return LEGACY_TEMPLATE_LABEL[code] || code
}

Page({
  data: {
    communityName: '',
    applicantName: '',
    applicantMobile: '',
    applyReason: '',
    roleIndex: 0,
    roleLabels: ROLE_LABELS,
    regions: [],
    regionRange: [[], [], []],
    regionIndex: [0, 0, 0],
    regionLabel: '',
    selectedRegion: null,
    initLabels: INIT_LABELS,
    initIndex: 0,
    showCustomCounts: false,
    buildings: '1',
    unitsPerBuilding: '1',
    floorsPerUnit: '1',
    roomsPerFloor: '1',
    estimatedRooms: 1,
    mine: [],
    submitting: false
  },

  onLoad() {
    this.initRegions(localRegions())
  },

  onShow() {
    if (!ensureLogin()) return
    this.loadRemote()
  },

  initRegions(list) {
    const regions = Array.isArray(list) && list.length ? list : localRegions()
    this.setData({ regions }, () => {
      this.applyRegionIndex(0, 0, 0, false)
    })
  },

  refreshEstimate() {
    const b = parsePositiveInt(this.data.buildings)
    const u = parsePositiveInt(this.data.unitsPerBuilding)
    const f = parsePositiveInt(this.data.floorsPerUnit)
    const r = parsePositiveInt(this.data.roomsPerFloor)
    this.setData({ estimatedRooms: b && u && f && r ? b * u * f * r : 0 })
  },

  async loadRemote() {
    try {
      const regions = await request({ url: '/regions' })
      if (Array.isArray(regions) && regions.length) {
        this.initRegions(regions)
      }
    } catch (e) {
      /* 已用本地省市区 */
    }

    try {
      const mineData = await request({ url: '/community-applications/mine' })
      this.setData({
        mine: ((mineData && mineData.list) || []).map((it) => Object.assign({}, it, {
          statusLabel: STATUS_LABEL[it.status] || it.status,
          applicantRoleLabel: it.applicantRole === 'STAFF' ? '物业' : '住户',
          initLabel: describeTemplateCode(it.templateCode)
        }))
      })
    } catch (e) {
      /* ignore */
    }
  },

  applyRegionIndex(pi, ci, di, setSelection) {
    const provinces = this.data.regions || []
    if (!provinces.length) {
      this.setData({
        regionRange: [[], [], []],
        regionIndex: [0, 0, 0]
      })
      return
    }
    const pIdx = Math.min(Math.max(0, Number(pi) || 0), provinces.length - 1)
    const p = provinces[pIdx]
    const cities = (p && p.cities) || []
    const cIdx = cities.length ? Math.min(Math.max(0, Number(ci) || 0), cities.length - 1) : 0
    const c = cities[cIdx] || { code: '', name: '', districts: [] }
    const districts = c.districts || []
    const dIdx = districts.length ? Math.min(Math.max(0, Number(di) || 0), districts.length - 1) : 0
    const d = districts[dIdx] || { code: '', name: '' }

    const patch = {
      regionRange: [
        provinces.map((x) => x.name),
        cities.map((x) => x.name),
        districts.map((x) => x.name)
      ],
      regionIndex: [pIdx, cIdx, dIdx]
    }
    if (setSelection && p && c.name && d.name) {
      patch.regionLabel = `${p.name} ${c.name} ${d.name}`
      patch.selectedRegion = {
        provinceCode: p.code,
        provinceName: p.name,
        cityCode: c.code,
        cityName: c.name,
        districtCode: d.code,
        districtName: d.name
      }
    }
    this.setData(patch)
  },

  onRegionColumnChange(e) {
    const column = Number(e.detail.column)
    const value = Number(e.detail.value)
    const idx = (this.data.regionIndex || [0, 0, 0]).slice()
    idx[column] = value
    if (column === 0) {
      idx[1] = 0
      idx[2] = 0
    } else if (column === 1) {
      idx[2] = 0
    }
    this.applyRegionIndex(idx[0], idx[1], idx[2], false)
  },

  onRegionChange(e) {
    const raw = (e.detail && e.detail.value) || [0, 0, 0]
    if (!(this.data.regions && this.data.regions.length)) {
      wx.showToast({ title: '地区数据未就绪', icon: 'none' })
      return
    }
    this.applyRegionIndex(Number(raw[0]) || 0, Number(raw[1]) || 0, Number(raw[2]) || 0, true)
  },

  onField(e) {
    const k = e.currentTarget.dataset.k
    this.setData({ [k]: e.detail.value }, () => {
      if (['buildings', 'unitsPerBuilding', 'floorsPerUnit', 'roomsPerFloor'].includes(k)) {
        this.refreshEstimate()
      }
    })
  },
  onRole(e) {
    this.setData({ roleIndex: Number(e.detail.value) })
  },
  onInitMode(e) {
    const initIndex = Number(e.detail.value)
    this.setData({
      initIndex,
      showCustomCounts: initIndex === INIT_CUSTOM
    }, () => this.refreshEstimate())
  },

  async submit() {
    const d = this.data
    const sel = d.selectedRegion
    if (!sel || !sel.provinceCode) {
      wx.showToast({ title: '请选择地区', icon: 'none' })
      return
    }
    if (!d.communityName || !d.applicantName || !d.applicantMobile) {
      wx.showToast({ title: '请填写必填项', icon: 'none' })
      return
    }
    if (d.applyReason && d.applyReason.length > 20) {
      wx.showToast({ title: '申请原因最多 20 字', icon: 'none' })
      return
    }

    const payload = {
      provinceCode: sel.provinceCode,
      provinceName: sel.provinceName,
      cityCode: sel.cityCode,
      cityName: sel.cityName,
      districtCode: sel.districtCode,
      districtName: sel.districtName,
      communityName: d.communityName,
      applicantName: d.applicantName,
      applicantRole: ROLE_VALUES[d.roleIndex],
      applicantMobile: d.applicantMobile,
      applyReason: d.applyReason || null
    }

    if (d.initIndex === INIT_CUSTOM) {
      const buildings = parsePositiveInt(d.buildings)
      const unitsPerBuilding = parsePositiveInt(d.unitsPerBuilding)
      const floorsPerUnit = parsePositiveInt(d.floorsPerUnit)
      const roomsPerFloor = parsePositiveInt(d.roomsPerFloor)
      if (!buildings || !unitsPerBuilding || !floorsPerUnit || !roomsPerFloor) {
        wx.showToast({ title: '请填写有效房屋数量', icon: 'none' })
        return
      }
      const total = buildings * unitsPerBuilding * floorsPerUnit * roomsPerFloor
      if (total > 8000) {
        wx.showToast({ title: '房屋总数不能超过 8000', icon: 'none' })
        return
      }
      payload.buildings = buildings
      payload.unitsPerBuilding = unitsPerBuilding
      payload.floorsPerUnit = floorsPerUnit
      payload.roomsPerFloor = roomsPerFloor
    }

    this.setData({ submitting: true })
    try {
      await request({
        url: '/community-applications',
        method: 'POST',
        data: payload
      })
      wx.showToast({ title: '已提交' })
      this.setData({
        communityName: '',
        applyReason: '',
        initIndex: 0,
        showCustomCounts: false,
        buildings: '1',
        unitsPerBuilding: '1',
        floorsPerUnit: '1',
        roomsPerFloor: '1',
        estimatedRooms: 1
      })
      this.loadRemote()
    } catch (e) {
      wx.showModal({ title: '提交失败', content: (e && e.message) || '请稍后重试', showCancel: false })
    } finally {
      this.setData({ submitting: false })
    }
  }
})
