/**
 * 照片附件 mixin —— 报事报修五大类提交页统一复用。
 * 能力：选图（最多 3 张）→ 以 TEMP 业务类型上传 → 缩略图展示 → 点击预览 → 删除（同时删服务端）。
 * 提交时调用 getAttachmentIds() 拿到附件 id 列表，随报修/投诉接口一并提交，后端 bindToBiz 绑定。
 */
const { request } = require('./request')

const MAX_PHOTOS = 3

function getToken() {
  return getApp().globalData.token || wx.getStorageSync('token') || ''
}
function getApiBase() {
  return getApp().globalData.apiBase || ''
}

function uploadOne(filePath) {
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

module.exports = {
  data: {
    photos: [],
    maxPhotos: MAX_PHOTOS
  },

  choosePhotos() {
    const remain = this.data.maxPhotos - (this.data.photos || []).length
    if (remain <= 0) {
      wx.showToast({ title: `最多 ${this.data.maxPhotos} 张`, icon: 'none' })
      return
    }
    wx.chooseMedia({
      count: remain,
      mediaType: ['image'],
      sizeType: ['compressed'],
      sourceType: ['album', 'camera'],
      success: async (res) => {
        const files = (res.tempFiles || [])
          .map((f) => (f && f.tempFilePath) || '')
          .filter(Boolean)
        if (!files.length) return
        wx.showLoading({ title: '上传中', mask: true })
        try {
          const next = this.data.photos.slice()
          for (const path of files) {
            if (next.length >= this.data.maxPhotos) break
            const uploaded = await uploadOne(path)
            next.push({
              id: uploaded.id,
              localPath: path,
              fileName: uploaded.fileName || '照片'
            })
          }
          this.setData({ photos: next.slice(0, this.data.maxPhotos) })
        } catch (err) {
          wx.showToast({ title: (err && err.message) || '上传失败', icon: 'none' })
        } finally {
          wx.hideLoading()
        }
      }
    })
  },

  previewPhoto(e) {
    const url = e.currentTarget.dataset.url
    const urls = (this.data.photos || [])
      .map((p) => p.localPath)
      .filter(Boolean)
    if (!urls.length) return
    wx.previewImage({ current: url || urls[0], urls })
  },

  removePhoto(e) {
    const id = Number(e.currentTarget.dataset.id)
    const photos = (this.data.photos || []).filter((p) => p.id !== id)
    this.setData({ photos })
    if (id) {
      // 仅删除尚未绑定的 TEMP 附件；已随工单提交的会由后端绑定，删除请走工单本身
      request({ url: `/attachments/${id}`, method: 'DELETE' }).catch(() => {})
    }
  },

  getAttachmentIds() {
    return (this.data.photos || []).map((p) => p.id)
  },

  resetPhotos() {
    this.setData({ photos: [] })
  }
}
