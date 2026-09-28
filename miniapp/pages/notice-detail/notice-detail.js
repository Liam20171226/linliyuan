const { request } = require('../../utils/request')
const { formatDateTime } = require('../../utils/format')
const { splitNoticeBody } = require('../../utils/noticeContent')

const SOURCE_LABEL = {
  STAFF: '物业',
  COMMITTEE: '业委会',
  PLATFORM: '平台'
}

Page({
  data: {
    loading: true,
    error: '',
    detail: null,
    blocks: [],
    attachments: []
  },

  onLoad(q) {
    const id = q && q.id ? Number(q.id) : 0
    if (!id) {
      this.setData({ loading: false, error: '公告不存在' })
      return
    }
    this._id = id
    this.load()
  },

  onPullDownRefresh() {
    this.load().finally(() => wx.stopPullDownRefresh())
  },

  async load() {
    this.setData({ loading: true, error: '' })
    try {
      const raw = await request({ url: `/notices/${this._id}` })
      const prepared = splitNoticeBody(raw.content || '')
      const detail = {
        id: raw.id,
        title: raw.title || '',
        urgency: Number(raw.urgency) || 0,
        timeLabel: formatDateTime(raw.effectiveAt || raw.createdAt).slice(0, 16),
        sourceLabel: SOURCE_LABEL[raw.creatorIdentity] || '物业'
      }
      this.setData({
        detail,
        blocks: prepared.blocks,
        attachments: prepared.attachments,
        loading: false
      })
      wx.setNavigationBarTitle({ title: '公告详情' })
    } catch (e) {
      this.setData({
        loading: false,
        error: (e && e.message) || '加载失败',
        detail: null,
        blocks: [],
        attachments: []
      })
    }
  },

  previewBody(e) {
    const src = e.currentTarget.dataset.src
    if (!src) return
    const urls = (this.data.blocks || []).filter((b) => b.type === 'img' && b.src).map((b) => b.src)
    wx.previewImage({ urls: urls.length ? urls : [src], current: src })
  },

  openAttachment(e) {
    const src = e.currentTarget.dataset.src
    const name = e.currentTarget.dataset.name || '附件'
    if (!src) return
    wx.showLoading({ title: '打开中', mask: true })
    wx.downloadFile({
      url: src,
      success: (res) => {
        if (res.statusCode !== 200) {
          wx.showToast({ title: '下载失败', icon: 'none' })
          return
        }
        const path = res.tempFilePath
        const lower = String(name).toLowerCase()
        if (/\.(png|jpe?g|gif|webp)$/.test(lower)) {
          wx.previewImage({ urls: [path], current: path })
          return
        }
        wx.openDocument({
          filePath: path,
          showMenu: true,
          fail: () => wx.showToast({ title: '无法预览该附件', icon: 'none' })
        })
      },
      fail: () => wx.showToast({ title: '下载失败', icon: 'none' }),
      complete: () => wx.hideLoading()
    })
  }
})
