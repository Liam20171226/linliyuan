const { request } = require('../../utils/request')
const { splitNoticeBody, attachmentSrc } = require('../../utils/noticeContent')

Page({
  data: {
    loading: true,
    error: '',
    detail: null,
    blocks: [],
    attachments: []
  },

  onShow() {
    this.load()
  },

  onPullDownRefresh() {
    this.load().finally(() => wx.stopPullDownRefresh())
  },

  async load() {
    this.setData({ loading: true, error: '' })
    try {
      const raw = await request({ url: '/about-us' })
      const prepared = splitNoticeBody(raw.content || '')
      const merged = mergeAttachments(prepared.attachments || [], raw.attachments || [])
      this.setData({
        detail: { id: raw.id },
        blocks: prepared.blocks || [],
        attachments: merged,
        loading: false
      })
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
    const urls = (this.data.blocks || []).filter((b) => b.type === 'img').map((b) => b.src)
    wx.previewImage({ current: src, urls: urls.length ? urls : [src] })
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

function mergeAttachments(fromBody, fromApi) {
  const out = []
  const seen = new Set()
  const push = (id, name) => {
    const n = Number(id)
    if (!n || seen.has(n)) return
    seen.add(n)
    out.push({ id: n, name: name || '附件', src: attachmentSrc(n) })
  }
  for (const a of fromBody || []) push(a.id, a.name)
  for (const a of fromApi || []) push(a.id, a.name || a.fileName)
  return out
}
