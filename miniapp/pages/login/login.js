const { request, resolveApiBase, pingApi } = require('../../utils/request')
const { markActive, flattenIdentities, switchIdentity, ensurePreferredResidentContext } = require('../../utils/identity')
const { DEV_API_BASE, isDevToolsEnv } = require('../../config')

const DEFAULT_LOCAL = 'http://127.0.0.1:8080/api/v1'
const DEFAULT_LAN = 'http://10.19.156.73:8080/api/v1'
const DEFAULT_HOTSPOT = (DEV_API_BASE || 'http://192.168.137.1:8080/api/v1').replace(/\/$/, '')

Page({
  data: {
    phone: '13800138000',
    code: 'dev_r_13800138000',
    sessionToken: '',
    identities: [],
    pickerList: [],
    pickerVisible: false,
    step: 1,
    /** 仅 develop/trial 显示开发调试区；正式版 release 隐藏 */
    devEnabled: false,
    showDev: false,
    wxLoading: false,
    phoneLoading: false,
    devLoading: false,
    apiBase: DEFAULT_HOTSPOT,
    pinging: false,
    pingText: ''
  },

  onLoad() {
    const devEnabled = isDevToolsEnv()
    // 开发/体验版默认热点；正式版不展示开发调试区
    let apiBase = resolveApiBase() || DEFAULT_HOTSPOT
    try {
      const stored = wx.getStorageSync('apiBaseOverride')
      if (stored && /10\.19\.156\.73|192\.168\.1\.4/.test(String(stored))) {
        apiBase = DEFAULT_HOTSPOT
        wx.setStorageSync('apiBaseOverride', DEFAULT_HOTSPOT)
        const app = getApp()
        if (app && app.globalData) app.globalData.apiBase = DEFAULT_HOTSPOT
      }
    } catch (e) { /* ignore */ }
    this.setData({ apiBase, devEnabled, showDev: devEnabled })
  },

  onPhoneInput(e) { this.setData({ phone: e.detail.value }) },
  onCodeInput(e) { this.setData({ code: e.detail.value }) },
  onApiBaseInput(e) { this.setData({ apiBase: e.detail.value }) },
  toggleDev() { this.setData({ showDev: !this.data.showDev }) },

  applyApiBase(base) {
    const v = (base || this.data.apiBase || '').trim().replace(/\/$/, '')
    if (!v) {
      wx.showToast({ title: '请填写 API 地址', icon: 'none' })
      return ''
    }
    wx.setStorageSync('apiBaseOverride', v)
    const app = getApp()
    if (app && app.globalData) app.globalData.apiBase = v
    this.setData({ apiBase: v })
    return v
  },

  useLanApi() {
    this.applyApiBase(DEFAULT_LAN)
    wx.showToast({ title: '已切局域网', icon: 'none' })
  },

  useLocalApi() {
    this.applyApiBase(DEFAULT_LOCAL)
    wx.showToast({ title: '已切本机代理(推荐)', icon: 'none' })
  },

  useHotspotApi() {
    this.applyApiBase(DEFAULT_HOTSPOT)
    wx.showToast({ title: '已切电脑热点IP', icon: 'none' })
  },

  async pingBackend() {
    const base = this.applyApiBase(this.data.apiBase)
    if (!base) return
    this.setData({ pinging: true, pingText: '探测中…' })
    try {
      const r = await pingApi(base)
      const text = r.ok
        ? ('✓ ' + r.detail + ' (' + (r.statusCode || '') + ')\n' + base)
        : ('✗ ' + r.detail + '\n' + base + '\n手机须与电脑同一 WiFi，勿用流量')
      this.setData({ pingText: text })
      wx.showModal({ title: r.ok ? '后端可达' : '连不上后端', content: text, showCancel: false })
    } finally {
      this.setData({ pinging: false })
    }
  },

  goHome() {
    wx.switchTab({
      url: '/pages/index/index',
      fail: () => wx.redirectTo({ url: '/pages/index/index' })
    })
  },

  async applyPreferredRoom(identities, current) {
    try {
      await ensurePreferredResidentContext(identities || [], current || null)
    } catch (e) { /* ignore */ }
  },

  formatLoginError(e) {
    const apiBase = (e && e.apiBase) || this.data.apiBase || resolveApiBase() || ''
    const msg = (e && (e.message || e.errMsg)) || '请重试'
    const s = String(msg)
    if (/request:fail|timeout|connect|ERR_|ENOTFOUND|ECONN/i.test(s) || /连不上/.test(s)) {
      return '连不上后端\n' + apiBase
        + '\n\n1. 手机连与电脑同一 WiFi（不要用 5G/流量）'
        + '\n2. 先点「探测后端」确认可达'
        + '\n3. 真机调试通道可试「本机 127.0.0.1」'
        + '\n\n原始错误：' + s
    }
    return s + (apiBase ? ('\n\nAPI：' + apiBase) : '')
  },

  async loginWx() {
    this.setData({ wxLoading: true })
    try {
      const login = await new Promise((resolve, reject) => {
        wx.login({ success: resolve, fail: reject })
      })
      const c2s = await request({
        url: '/auth/miniapp/code2session',
        method: 'POST',
        data: { code: login.code },
        auth: false
      })
      if (c2s && c2s.token) {
        this.saveToken(c2s.token, c2s.identities || [])
        await this.applyPreferredRoom(c2s.identities, c2s.current)
        wx.showToast({ title: '登录成功' })
        setTimeout(() => this.goHome(), 350)
        return
      }
      this.setData({ sessionToken: (c2s && c2s.sessionToken) || '', step: 2 })
      wx.showToast({ title: '请授权手机号', icon: 'none' })
    } catch (e) {
      wx.showModal({ title: '微信登录失败', content: this.formatLoginError(e), showCancel: false })
    } finally {
      this.setData({ wxLoading: false })
    }
  },

  async onGetPhoneNumber(e) {
    const detail = e.detail || {}
    if (!detail.code) {
      wx.showToast({ title: '未授权手机号', icon: 'none' })
      return
    }
    if (!this.data.sessionToken) {
      wx.showToast({ title: '请先微信登录', icon: 'none' })
      return
    }
    this.setData({ phoneLoading: true })
    try {
      const matched = await request({
        url: '/auth/miniapp/phone-match',
        method: 'POST',
        data: { sessionToken: this.data.sessionToken, phoneCode: detail.code },
        auth: false
      })
      await this.afterMatch(matched)
    } catch (err) {
      wx.showModal({ title: '匹配失败', content: this.formatLoginError(err), showCancel: false })
    } finally {
      this.setData({ phoneLoading: false })
    }
  },

  async loginDev() {
    this.setData({ devLoading: true })
    try {
      this.applyApiBase(this.data.apiBase)
      const c2s = await request({
        url: '/auth/miniapp/code2session',
        method: 'POST',
        data: { code: this.data.code || ('dev_' + Date.now()) },
        auth: false
      })
      if (c2s && c2s.token) {
        this.saveToken(c2s.token, c2s.identities || [])
        await this.applyPreferredRoom(c2s.identities, c2s.current)
        wx.showToast({ title: '已登录' })
        setTimeout(() => this.goHome(), 350)
        return
      }
      if (!c2s || !c2s.sessionToken) {
        throw { message: '未返回 sessionToken，请检查后端' }
      }
      const phone = this.data.phone || '13800138000'
      const matched = await request({
        url: '/auth/miniapp/phone-match',
        method: 'POST',
        data: { sessionToken: c2s.sessionToken, phoneCode: phone },
        auth: false
      })
      await this.afterMatch(matched)
    } catch (e) {
      wx.showModal({ title: '登录失败', content: this.formatLoginError(e), showCancel: false })
    } finally {
      this.setData({ devLoading: false })
    }
  },

  async afterMatch(matched) {
    const raw = (matched && matched.identities) || []
    this.saveToken(matched.token, raw)
    if (matched.needChooseIdentity) {
      const pickerList = markActive(flattenIdentities(raw), matched.current || null)
      this.setData({
        identities: raw,
        pickerList,
        pickerVisible: true,
        step: 3
      })
      wx.showToast({ title: '请选择身份', icon: 'none' })
    } else {
      await this.applyPreferredRoom(raw, matched.current)
      wx.showToast({ title: '登录成功' })
      setTimeout(() => this.goHome(), 350)
    }
  },

  saveToken(token, identities) {
    const app = getApp()
    wx.setStorageSync('token', token)
    if (app && app.globalData) {
      app.globalData.token = token
      app.globalData.identities = identities || []
    }
    try { wx.showTabBar({ animation: false }) } catch (e) { /* ignore */ }
  },

  closePicker() {
    this.setData({ pickerVisible: false })
  },

  reopenPicker() {
    this.setData({ pickerVisible: true })
  },

  async onPickIdentity(e) {
    const item = e.detail && e.detail.item
    if (!item) return
    this.setData({ pickerVisible: false })
    wx.showLoading({ title: '进入中', mask: true })
    try {
      const data = await switchIdentity(item)
      this.saveToken(data.token, this.data.identities)
      wx.showToast({ title: '已进入' })
      setTimeout(() => this.goHome(), 350)
    } catch (err) {
      this.setData({ pickerVisible: true })
      wx.showModal({ title: '切换失败', content: this.formatLoginError(err), showCancel: false })
    } finally {
      wx.hideLoading()
    }
  }
})
