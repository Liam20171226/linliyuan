const { request } = require('../../utils/request')
const { tree: localRegions } = require('../../utils/regions')
const { loadIdentities } = require('../../utils/identity')

Page({
  data: {
    regions: [],
    regionRange: [[], [], []],
    regionIndex: [0, 0, 0],
    regionLabel: '',
    selectedRegion: null,

    communityList: [],
    communityNames: [],
    communityIndex: 0,
    communityId: null,
    communityName: '',
    communityAddress: '',
    communityLoading: false,

    buildings: [],
    flatRooms: [],
    spaceReady: false,
    spaceLoading: false,
    spaceRange: [[], [], [], []],
    spaceIndex: [0, 0, 0, 0],
    roomSheetVisible: false,
    roomKeyword: '',
    roomHits: [],
    roomId: null,
    roomPath: '',

    applicantName: '',
    nameLocked: false,
    applicantIdCardNo: '',
    proofFiles: [],
    applyRole: 'OWNER_MEMBER',
    roleLabel: '业主成员',
    applyMessage: '',
    submitting: false,
    roles: [
      { label: '业主成员', value: 'OWNER_MEMBER' },
      { label: '租户', value: 'TENANT' },
      { label: '租户成员', value: 'TENANT_MEMBER' },
      { label: '业主（需身份证+产权附件）', value: 'OWNER' }
    ]
  },

  onLoad() {
    this.initRegions(localRegions())
    this.applyAccountName()
  },

  onShow() {
    this.loadRegions()
    this.refreshAccountName()
  },

  noop() {},

  applyAccountName() {
    const u = getApp().globalData.user || {}
    const profile = getApp().globalData.profile || {}
    const name = String(profile.realName || u.realName || '').trim()
    if (name) {
      this.setData({ applicantName: name, nameLocked: true })
    } else if (!this.data.nameLocked) {
      this.setData({ nameLocked: false })
    }
  },

  async refreshAccountName() {
    try {
      const packed = await loadIdentities()
      const user = packed.user || {}
      getApp().globalData.user = user
      getApp().globalData.profile = user
      const name = String(user.realName || '').trim()
      if (name) {
        this.setData({ applicantName: name, nameLocked: true })
      } else {
        this.setData({ nameLocked: false })
      }
    } catch (e) {
      this.applyAccountName()
    }
  },

  async loadRegions() {
    try {
      const regions = await request({ url: '/regions' })
      if (Array.isArray(regions) && regions.length) {
        this.initRegions(regions)
      }
    } catch (e) {
      /* 本地省市区 */
    }
  },

  initRegions(list) {
    const regions = Array.isArray(list) && list.length ? list : localRegions()
    this.setData({ regions }, () => {
      this.applyRegionIndex(0, 0, 0, false)
    })
  },

  applyRegionIndex(pi, ci, di, setSelection) {
    const provinces = this.data.regions || []
    if (!provinces.length) {
      this.setData({ regionRange: [[], [], []], regionIndex: [0, 0, 0] })
      return
    }
    const pIdx = Math.min(Math.max(0, Number(pi) || 0), provinces.length - 1)
    const p = provinces[pIdx]
    const cities = (p && p.cities) || []
    const cIdx = cities.length ? Math.min(Math.max(0, Number(ci) || 0), cities.length - 1) : 0
    const c = cities[cIdx] || { name: '', districts: [] }
    const districts = c.districts || []
    const dIdx = districts.length ? Math.min(Math.max(0, Number(di) || 0), districts.length - 1) : 0
    const d = districts[dIdx] || { name: '' }
    const patch = {
      regionRange: [
        provinces.map((x) => x.name),
        cities.map((x) => x.name),
        districts.map((x) => x.name)
      ],
      regionIndex: [pIdx, cIdx, dIdx]
    }
    if (setSelection) {
      patch.regionLabel = `${p.name} ${c.name} ${d.name}`
      patch.selectedRegion = {
        provinceName: p.name,
        cityName: c.name,
        districtName: d.name
      }
    }
    this.setData(patch)
  },

  onRegionColumnChange(e) {
    const col = e.detail.column
    const val = e.detail.value
    const idx = (this.data.regionIndex || [0, 0, 0]).slice()
    idx[col] = val
    if (col === 0) {
      idx[1] = 0
      idx[2] = 0
    } else if (col === 1) {
      idx[2] = 0
    }
    this.applyRegionIndex(idx[0], idx[1], idx[2], false)
  },

  onRegionChange(e) {
    if (!(this.data.regions && this.data.regions.length)) return
    const v = e.detail.value || [0, 0, 0]
    this.applyRegionIndex(v[0], v[1], v[2], true)
    this.clearCommunityAndSpace()
    const provinces = this.data.regions || []
    const p = provinces[v[0]]
    const cities = (p && p.cities) || []
    const c = cities[v[1]] || {}
    const districts = c.districts || []
    const d = districts[v[2]] || {}
    const selectedRegion = {
      provinceName: p.name,
      cityName: c.name,
      districtName: d.name
    }
    this.setData({ selectedRegion, regionLabel: `${p.name} ${c.name} ${d.name}` }, () => {
      this.loadCommunitiesForRegion()
    })
  },

  clearCommunityAndSpace() {
    this.setData({
      communityList: [],
      communityNames: [],
      communityIndex: 0,
      communityId: null,
      communityName: '',
      communityAddress: '',
      buildings: [],
      flatRooms: [],
      spaceReady: false,
      spaceLoading: false,
      spaceRange: [[], [], [], []],
      spaceIndex: [0, 0, 0, 0],
      roomSheetVisible: false,
      roomKeyword: '',
      roomHits: [],
      roomId: null,
      roomPath: ''
    })
  },

  clearSpaceOnly() {
    this.setData({
      buildings: [],
      flatRooms: [],
      spaceReady: false,
      spaceRange: [[], [], [], []],
      spaceIndex: [0, 0, 0, 0],
      roomSheetVisible: false,
      roomKeyword: '',
      roomHits: [],
      roomId: null,
      roomPath: ''
    })
  },

  async loadCommunitiesForRegion() {
    const reg = this.data.selectedRegion
    if (!reg || !reg.provinceName) return
    const seq = (this._communityLoadSeq = (this._communityLoadSeq || 0) + 1)
    this.setData({ communityLoading: true })
    try {
      const data = await request({
        url: '/resident/communities',
        data: {
          provinceName: reg.provinceName,
          cityName: reg.cityName,
          districtName: reg.districtName,
          page: 1,
          pageSize: 100
        }
      })
      if (seq !== this._communityLoadSeq) return
      const list = (data && data.list) || []
      this.setData({
        communityList: list,
        communityNames: list.map((x) => x.name),
        communityIndex: 0,
        communityId: null,
        communityName: '',
        communityAddress: ''
      })
      this.clearSpaceOnly()
    } catch (e) {
      if (seq !== this._communityLoadSeq) return
      this.setData({
        communityList: [],
        communityNames: []
      })
      wx.showToast({ title: (e && e.message) || '加载小区失败', icon: 'none' })
    } finally {
      if (seq === this._communityLoadSeq) {
        this.setData({ communityLoading: false })
      }
    }
  },

  flattenRooms(buildings) {
    const flat = []
    ;(buildings || []).forEach((b, bi) => {
      ;((b && b.units) || []).forEach((u, ui) => {
        ;((u && u.floors) || []).forEach((f, fi) => {
          ;((f && f.rooms) || []).forEach((r, ri) => {
            flat.push({
              roomId: r.id,
              roomNo: String(r.roomNo || ''),
              path: `${b.name}${u.name}${f.name}${r.roomNo}`,
              bi,
              ui,
              fi,
              ri
            })
          })
        })
      })
    })
    return flat
  },

  buildSpaceRange(buildings, bi, ui, fi) {
    const bList = buildings || []
    const bNames = bList.map((b) => b.name)
    if (!bNames.length) {
      return { spaceRange: [[], [], [], []], spaceIndex: [0, 0, 0, 0] }
    }
    const bIdx = Math.min(Math.max(0, bi || 0), bList.length - 1)
    const units = (bList[bIdx] && bList[bIdx].units) || []
    const uNames = units.map((u) => u.name)
    const uIdx = uNames.length ? Math.min(Math.max(0, ui || 0), units.length - 1) : 0
    const floors = (units[uIdx] && units[uIdx].floors) || []
    const fNames = floors.map((f) => f.name)
    const fIdx = fNames.length ? Math.min(Math.max(0, fi || 0), floors.length - 1) : 0
    const rooms = (floors[fIdx] && floors[fIdx].rooms) || []
    const rNames = rooms.map((r) => r.roomNo)
    return {
      spaceRange: [bNames, uNames, fNames, rNames],
      spaceIndex: [bIdx, uIdx, fIdx, 0]
    }
  },

  async onCommunityPick(e) {
    const idx = Number(e.detail.value)
    const hit = (this.data.communityList || [])[idx]
    if (!hit) return
    this.clearSpaceOnly()
    this.setData({
      communityIndex: idx,
      communityId: hit.id,
      communityName: hit.name,
      communityAddress: hit.address || '',
      spaceLoading: true
    })
    try {
      const tree = await request({ url: `/resident/communities/${hit.id}/space-tree` })
      const buildings = (tree && tree.buildings) || []
      const flatRooms = this.flattenRooms(buildings)
      const range = this.buildSpaceRange(buildings, 0, 0, 0)
      this.setData({
        buildings,
        flatRooms,
        spaceReady: flatRooms.length > 0,
        spaceRange: range.spaceRange,
        spaceIndex: range.spaceIndex,
        spaceLoading: false
      })
      if (!flatRooms.length) {
        wx.showToast({ title: '该小区暂无房屋，请联系物业', icon: 'none' })
      }
    } catch (err) {
      this.setData({ spaceLoading: false, spaceReady: false })
      wx.showToast({ title: (err && err.message) || '加载房屋失败', icon: 'none' })
    }
  },

  openRoomSheet() {
    if (!this.data.communityId) {
      wx.showToast({ title: '请先选择小区', icon: 'none' })
      return
    }
    if (!this.data.spaceReady) {
      wx.showToast({ title: this.data.spaceLoading ? '房屋加载中' : '该小区暂无房屋', icon: 'none' })
      return
    }
    const range = this.buildSpaceRange(
      this.data.buildings,
      this.data.spaceIndex[0],
      this.data.spaceIndex[1],
      this.data.spaceIndex[2]
    )
    this.setData({
      roomSheetVisible: true,
      roomKeyword: '',
      roomHits: [],
      spaceRange: range.spaceRange,
      spaceIndex: range.spaceIndex
    })
  },

  closeRoomSheet() {
    this.setData({ roomSheetVisible: false, roomKeyword: '', roomHits: [] })
  },

  onRoomKeyword(e) {
    const keyword = String(e.detail.value || '').trim()
    if (!keyword) {
      this.setData({ roomKeyword: '', roomHits: [] })
      return
    }
    const hits = (this.data.flatRooms || []).filter((r) =>
      String(r.roomNo).includes(keyword) || String(r.path).includes(keyword)
    )
    this.setData({ roomKeyword: keyword, roomHits: hits.slice(0, 50) })
  },

  pickRoomHit(e) {
    const id = Number(e.currentTarget.dataset.id)
    const hit = (this.data.flatRooms || []).find((x) => x.roomId === id)
    if (!hit) return
    const range = this.buildSpaceRange(this.data.buildings, hit.bi, hit.ui, hit.fi)
    range.spaceIndex[3] = hit.ri
    this.setData({
      roomId: hit.roomId,
      roomPath: hit.path,
      spaceRange: range.spaceRange,
      spaceIndex: range.spaceIndex,
      roomSheetVisible: false,
      roomKeyword: '',
      roomHits: []
    })
  },

  onSpacePickerChange(e) {
    const val = e.detail.value || [0, 0, 0, 0]
    const prev = this.data.spaceIndex || [0, 0, 0, 0]
    let bi = val[0] || 0
    let ui = val[1] || 0
    let fi = val[2] || 0
    let ri = val[3] || 0
    if (bi !== prev[0]) {
      ui = 0
      fi = 0
      ri = 0
    } else if (ui !== prev[1]) {
      fi = 0
      ri = 0
    } else if (fi !== prev[2]) {
      ri = 0
    }
    const range = this.buildSpaceRange(this.data.buildings, bi, ui, fi)
    range.spaceIndex[3] = Math.min(ri, Math.max(0, (range.spaceRange[3] || []).length - 1))
    this.setData({
      spaceRange: range.spaceRange,
      spaceIndex: range.spaceIndex
    })
  },

  confirmSpacePick() {
    const buildings = this.data.buildings || []
    const idx = this.data.spaceIndex || [0, 0, 0, 0]
    const b = buildings[idx[0]]
    const u = ((b && b.units) || [])[idx[1]]
    const f = ((u && u.floors) || [])[idx[2]]
    const r = ((f && f.rooms) || [])[idx[3]]
    if (!r) {
      wx.showToast({ title: '请选择有效房号', icon: 'none' })
      return
    }
    this.setData({
      roomId: r.id,
      roomPath: `${b.name}${u.name}${f.name}${r.roomNo}`,
      roomSheetVisible: false,
      roomKeyword: '',
      roomHits: []
    })
  },

  onName(e) {
    if (this.data.nameLocked) return
    this.setData({ applicantName: e.detail.value })
  },
  onIdCard(e) { this.setData({ applicantIdCardNo: e.detail.value }) },
  onMsg(e) { this.setData({ applyMessage: e.detail.value }) },
  onRole(e) {
    const idx = Number(e.detail.value)
    const role = this.data.roles[idx]
    this.setData({ applyRole: role.value, roleLabel: role.label })
  },

  addProof() {
    if (!this.data.communityId) {
      wx.showToast({ title: '请先选择小区房屋', icon: 'none' })
      return
    }
    const remain = 3 - (this.data.proofFiles || []).length
    if (remain <= 0) return
    wx.chooseMedia({
      count: remain,
      mediaType: ['image'],
      sourceType: ['album', 'camera'],
      success: async (res) => {
        const files = res.tempFiles || []
        wx.showLoading({ title: '上传中', mask: true })
        try {
          for (const f of files) {
            const uploaded = await this.uploadTemp(f.tempFilePath)
            const next = (this.data.proofFiles || []).concat([{
              id: uploaded.id,
              localPath: f.tempFilePath,
              fileName: uploaded.fileName || '证明'
            }])
            this.setData({ proofFiles: next.slice(0, 3) })
          }
        } catch (err) {
          wx.showToast({ title: (err && err.message) || '上传失败', icon: 'none' })
        } finally {
          wx.hideLoading()
        }
      }
    })
  },

  uploadTemp(filePath) {
    const token = getApp().globalData.token || wx.getStorageSync('token')
    const communityId = this.data.communityId
    return new Promise((resolve, reject) => {
      wx.uploadFile({
        url: `${getApp().globalData.apiBase}/attachments/upload`,
        filePath,
        name: 'file',
        formData: {
          bizType: 'TEMP',
          communityId: communityId ? String(communityId) : ''
        },
        header: { Authorization: `Bearer ${token}` },
        success: (r) => {
          try {
            const body = JSON.parse(r.data)
            if (body.code === 0) resolve(body.data)
            else reject(body)
          } catch (err) {
            reject(err)
          }
        },
        fail: reject
      })
    })
  },

  removeProof(e) {
    const id = Number(e.currentTarget.dataset.id)
    const proofFiles = (this.data.proofFiles || []).filter((x) => x.id !== id)
    this.setData({ proofFiles })
    request({ url: `/attachments/${id}`, method: 'DELETE' }).catch(() => {})
  },

  previewProof(e) {
    const url = e.currentTarget.dataset.url
    const urls = (this.data.proofFiles || []).map((x) => x.localPath)
    wx.previewImage({ current: url, urls })
  },

  async submit() {
    const d = this.data
    if (!d.communityId || !d.roomId || !d.applicantName) {
      wx.showToast({ title: '请选小区房屋并填写姓名', icon: 'none' })
      return
    }
    this.setData({ submitting: true })
    try {
      let attachmentIds = []
      if (d.applyRole === 'OWNER') {
        if (!d.applicantIdCardNo) {
          wx.showToast({ title: '业主须填身份证', icon: 'none' })
          this.setData({ submitting: false })
          return
        }
        attachmentIds = (d.proofFiles || []).map((x) => x.id)
        if (!attachmentIds.length) {
          wx.showToast({ title: '请上传产权证明图片', icon: 'none' })
          this.setData({ submitting: false })
          return
        }
      }
      const app = await request({
        url: '/resident/auth-applications',
        method: 'POST',
        data: {
          communityId: Number(d.communityId),
          roomId: Number(d.roomId),
          applicantName: d.applicantName,
          applicantIdCardNo: d.applicantIdCardNo || null,
          applyRole: d.applyRole,
          applyMessage: d.applyMessage || null,
          attachmentIds
        }
      })
      wx.showModal({
        title: '已提交',
        content: `申请单号${app.id}，请联系物业人员审核。`,
        showCancel: false,
        success: () => wx.navigateBack()
      })
    } catch (e) {
      wx.showModal({ title: '提交失败', content: (e && e.message) || '请稍后重试', showCancel: false })
    } finally {
      this.setData({ submitting: false })
    }
  }
})
