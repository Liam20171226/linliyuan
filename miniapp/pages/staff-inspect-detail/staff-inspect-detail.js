const { request } = require('../../utils/request')
const { formatDateTime } = require('../../utils/format')

function getToken() {
  return getApp().globalData.token || wx.getStorageSync('token') || ''
}
function getApiBase() {
  return getApp().globalData.apiBase || ''
}

function uploadPhoto(filePath) {
  const token = getToken()
  const apiBase = getApiBase()
  return new Promise((resolve, reject) => {
    wx.uploadFile({
      url: `${apiBase}/attachments/upload`,
      filePath,
      name: 'file',
      formData: { bizType: 'TEMP' },
      header: { Authorization: `Bearer ${token}` },
      success: (r) => {
        try {
          const body = JSON.parse(r.data)
          if (body.code === 0 && body.data) resolve(body.data)
          else reject(new Error((body && body.message) || '上传失败'))
        } catch (err) {
          reject(err)
        }
      },
      fail: reject
    })
  })
}

Page({
  data: {
    id: null,
    loading: true,
    claiming: false,
    submitting: false,
    job: null,
    spots: [],
    progressText: '',
    canClaim: false,
    mine: false,
    done: false,
    claimHint: '',
    scanOpen: false,
    scanSpotName: '',
    scanQrContent: '',
    note: '',
    photoLocal: '',
    photoId: null
  },

  onLoad(q) {
    const id = q && q.id ? Number(q.id) : null
    this.setData({ id })
    if (id) this.load()
  },

  onShow() {
    if (this.data.id) this.load()
  },

  async load() {
    if (!this.data.id) return
    this.setData({ loading: true })
    try {
      const d = await request({ url: `/staff/inspect/jobs/${this.data.id}` })
      const spots = (d.spots || []).map((s) => ({
        ...s,
        cats: (s.categoryLabels || []).join(' · '),
        scannedAtLabel: s.scannedAt ? (formatDateTime(s.scannedAt) || '').slice(0, 16) : ''
      }))
      this.setData({
        job: d,
        spots,
        progressText: `${d.visitedCount || 0}/${d.spotCount || 0}`,
        canClaim: !!d.canClaim,
        mine: !!d.mine,
        done: d.status === 'DONE' || d.status === 'CANCELLED',
        claimHint: (!d.canClaim && !d.mine && (d.status === 'OPEN' || d.status === 'IN_PROGRESS') && !d.assigneeUserId)
          ? ('本计划执行岗：' + ((d.executorRoleLabels || []).join('、') || '未指定') + '，当前身份无法接单')
          : ''
      })
      wx.setNavigationBarTitle({ title: d.planTitle || '巡检执行' })
    } catch (e) {
      wx.showToast({ title: (e && e.message) || '加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },

  async claim() {
    if (this.data.claiming || !this.data.id) return
    this.setData({ claiming: true })
    try {
      await request({
        url: `/staff/inspect/jobs/${this.data.id}/claim`,
        method: 'POST',
        data: {}
      })
      wx.showToast({ title: '已接单', icon: 'success' })
      await this.load()
    } catch (e) {
      const msg = (e && (e.message || e.errMsg)) || '接单失败'
      wx.showToast({ title: msg, icon: 'none', duration: 2500 })
    } finally {
      this.setData({ claiming: false })
    }
  },

  closeScan() {
    this.setData({
      scanOpen: false,
      scanSpotName: '',
      scanQrContent: '',
      note: '',
      photoLocal: '',
      photoId: null
    })
  },

  onNote(e) {
    this.setData({ note: (e.detail && e.detail.value) || '' })
  },

  choosePhoto() {
    wx.chooseMedia({
      count: 1,
      mediaType: ['image'],
      sizeType: ['compressed'],
      sourceType: ['camera', 'album'],
      success: async (res) => {
        const path = res.tempFiles && res.tempFiles[0] && res.tempFiles[0].tempFilePath
        if (!path) return
        wx.showLoading({ title: '上传中', mask: true })
        try {
          const uploaded = await uploadPhoto(path)
          this.setData({ photoLocal: path, photoId: uploaded.id })
        } catch (err) {
          wx.showToast({ title: (err && err.message) || '上传失败', icon: 'none' })
        } finally {
          wx.hideLoading()
        }
      }
    })
  },

  async doScan() {
    if (!this.data.mine) {
      wx.showToast({ title: '请先接单', icon: 'none' })
      return
    }
    if (this.data.done) {
      wx.showToast({ title: '任务已结束', icon: 'none' })
      return
    }
    try {
      const scan = await new Promise((resolve, reject) => {
        wx.scanCode({
          onlyFromCamera: false,
          success: resolve,
          fail: reject
        })
      })
      const content = (scan && (scan.result || scan.path)) || ''
      if (!content) {
        wx.showToast({ title: '未识别到二维码', icon: 'none' })
        return
      }
      // 根据二维码匹配点位名称（可选提示）
      let spotName = '巡检点'
      const token = content.indexOf('PROPERTY_INSPECT:') === 0
        ? content.slice('PROPERTY_INSPECT:'.length)
        : content
      const hit = (this.data.spots || []).find((s) => s.qrToken === token || s.qrContent === content)
      if (hit) spotName = hit.name || spotName
      this.setData({
        scanOpen: true,
        scanSpotName: spotName,
        scanQrContent: content,
        note: '',
        photoLocal: '',
        photoId: null
      })
    } catch (e) {
      if (e && e.errMsg && e.errMsg.indexOf('cancel') >= 0) return
      wx.showToast({ title: '扫码取消或失败', icon: 'none' })
    }
  },

  async submitVisit() {
    if (this.data.submitting) return
    if (!this.data.photoId) {
      wx.showToast({ title: '请先拍照', icon: 'none' })
      return
    }
    this.setData({ submitting: true })
    try {
      await request({
        url: `/staff/inspect/jobs/${this.data.id}/scan`,
        method: 'POST',
        data: {
          qrContent: this.data.scanQrContent,
          photoAttachmentId: this.data.photoId,
          note: (this.data.note || '').trim() || null
        }
      })
      wx.showToast({ title: '已记录', icon: 'success' })
      this.closeScan()
      await this.load()
    } catch (e) {
      wx.showToast({ title: (e && e.message) || '提交失败', icon: 'none' })
    } finally {
      this.setData({ submitting: false })
    }
  }
})
