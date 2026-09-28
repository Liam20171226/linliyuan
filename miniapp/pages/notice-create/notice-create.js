const { request } = require('../../utils/request')
const { hasNoticeBody, imgMarker, parseNoticeContent } = require('../../utils/noticeContent')

/** 受众：业主 / 住户（住户=全部挂房人员，界面只写「住户」） */
const VIS_OPTS = [
  { value: 'RESIDENTS', label: '住户' },
  { value: 'OWNERS', label: '仅业主' }
]
const VIS_LABEL = Object.fromEntries(VIS_OPTS.map((x) => [x.value, x.label]))
const VIS_PICKER = VIS_OPTS.map((x) => x.label)

Page({
  data: {
    editId: null,
    title: '',
    content: '',
    visibility: 'RESIDENTS',
    visLabel: '住户',
    visPicker: VIS_PICKER,
    submitting: false
  },

  onLoad(q) {
    const id = q && q.id ? Number(q.id) : null
    if (id) {
      this.setData({ editId: id })
      wx.setNavigationBarTitle({ title: '修改通知' })
      this.loadNotice(id)
    } else {
      wx.setNavigationBarTitle({ title: '发布通知' })
    }
  },

  async loadNotice(id) {
    wx.showLoading({ title: '加载中', mask: true })
    try {
      const item = await request({ url: `/notices/${id}` })
      const visibility = item && item.visibility === 'OWNERS' ? 'OWNERS' : 'RESIDENTS'
      this.setData({
        title: (item && item.title) || '',
        content: (item && item.content) || '',
        visibility,
        visLabel: VIS_LABEL[visibility]
      })
    } catch (err) {
      wx.showModal({
        title: '无法打开',
        content: (err && err.message) || '通知不存在或无权修改',
        showCancel: false,
        success: () => wx.navigateBack({ fail: () => {} })
      })
    } finally {
      wx.hideLoading()
    }
  },

  onTitle(e) { this.setData({ title: e.detail.value }) },
  onContent(e) { this.setData({ content: e.detail.value }) },
  onVis(e) {
    const opt = VIS_OPTS[Number(e.detail.value)]
    if (!opt) return
    this.setData({ visibility: opt.value, visLabel: opt.label })
  },

  pickBodyImage() {
    wx.chooseImage({
      count: 1,
      sizeType: ['compressed'],
      sourceType: ['album', 'camera'],
      success: async (res) => {
        const path = (res.tempFilePaths || [])[0]
        if (!path) return
        wx.showLoading({ title: '上传中', mask: true })
        try {
          const data = await this.uploadImage(path)
          const cur = this.data.content || ''
          const pad = cur && !/\n$/.test(cur) ? '\n' : ''
          this.setData({ content: `${cur}${pad}${imgMarker(data.id)}\n` })
          wx.showToast({ title: '图片已插入' })
        } catch (err) {
          wx.showToast({ title: (err && err.message) || '上传失败', icon: 'none' })
        } finally {
          wx.hideLoading()
        }
      }
    })
  },

  uploadImage(filePath) {
    const app = getApp()
    const token = (app && app.globalData && app.globalData.token) || wx.getStorageSync('token')
    const apiBase = (app && app.globalData && app.globalData.apiBase) || 'http://127.0.0.1:8080/api/v1'
    return new Promise((resolve, reject) => {
      wx.uploadFile({
        url: `${apiBase}/attachments/upload`,
        filePath,
        name: 'file',
        formData: { bizType: 'NOTICE' },
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
  },

  async submit() {
    if (this.data.submitting) return
    const title = (this.data.title || '').trim()
    const content = this.data.content || ''
    if (!title) {
      wx.showToast({ title: '请填写标题', icon: 'none' })
      return
    }
    if (!hasNoticeBody(content)) {
      wx.showToast({ title: '请填写正文或插入图片', icon: 'none' })
      return
    }
    const attachmentIds = parseNoticeContent(content)
      .filter((b) => b.type === 'img')
      .map((b) => b.id)
    const isEdit = !!this.data.editId
    const now = new Date()
    const effectiveAt = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}T00:00:00`
    this.setData({ submitting: true })
    try {
      const payload = {
        title,
        content,
        noticeType: 'OTHER',
        urgency: 0,
        visibility: this.data.visibility,
        showOnBanner: false,
        bannerOrder: 0,
        coverAttachmentId: 0,
        attachmentIds,
        effectiveAt
      }
      if (isEdit) {
        await request({
          url: `/committee/notices/${this.data.editId}`,
          method: 'PUT',
          data: payload
        })
        wx.showToast({ title: '已保存' })
      } else {
        await request({
          url: '/committee/notices',
          method: 'POST',
          data: payload
        })
        wx.showToast({ title: '已发布' })
      }
      setTimeout(() => {
        wx.navigateBack({
          fail: () => wx.redirectTo({ url: '/pages/notice-manage/notice-manage?tab=mine' })
        })
      }, 400)
    } catch (err) {
      wx.showModal({
        title: isEdit ? '保存失败' : '发布失败',
        content: (err && err.message) || '请稍后重试',
        showCancel: false
      })
    } finally {
      this.setData({ submitting: false })
    }
  }
})
